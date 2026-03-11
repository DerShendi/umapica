package net.shendi.umapica.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.shendi.umapica.Config;
import net.shendi.umapica.hologram.HologramInstance;
import net.shendi.umapica.hologram.HologramManager;
import net.shendi.umapica.hologram.RenderMode;
import net.shendi.umapica.umap.FQuat;
import net.shendi.umapica.umap.UmapActor;
import net.shendi.umapica.umap.UmapMeshData;

import java.util.List;

/**
 * Renders {@link RenderMode#TEXTURED_MESH} holograms using actual material textures.
 *
 * <p>Listens to {@link RenderLevelStageEvent} on the NeoForge game-event bus, which
 * provides the world {@link PoseStack} already offset to camera space.  Each triangle
 * is emitted to a {@code entityTranslucentEmissive} render buffer so it appears
 * self-lit and semi-transparent on top of the world geometry.</p>
 *
 * <p>Register via {@code NeoForge.EVENT_BUS.register(HologramTexturedRenderer.INSTANCE)}
 * from {@code UmapicaClient#onClientSetup}.</p>
 */
public final class HologramTexturedRenderer {

    public static final HologramTexturedRenderer INSTANCE = new HologramTexturedRenderer();

    private HologramTexturedRenderer() {}

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (Minecraft.getInstance().level == null) return;

        List<HologramInstance> holograms = HologramManager.get().getAll();
        if (holograms.isEmpty()) return;

        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        MultiBufferSource.BufferSource buffers =
                Minecraft.getInstance().renderBuffers().bufferSource();

        double _maxDist = Config.RENDER_DISTANCE.get();
        double _maxDistSq = _maxDist * _maxDist;
        for (HologramInstance h : holograms) {
            if (!h.visible || h.renderMode != RenderMode.TEXTURED_MESH) continue;
            for (UmapActor actor : h.umap.actors) {
                UmapMeshData mesh = actor.meshData;
                if (mesh == null || mesh.positions == null || mesh.positions.length < 9
                        || mesh.indices.length < 3) continue;
                renderActorTextured(h, actor, mesh, buffers, cam, _maxDistSq);
            }
        }

        // Flush all collected batches now
        buffers.endBatch();
    }

    // ------------------------------------------------------------------

    private void renderActorTextured(HologramInstance h, UmapActor actor, UmapMeshData mesh,
                                     MultiBufferSource buffers, Vec3 cam, double maxDistSq) {
        float[] pos  = mesh.positions;
        float[] uvs  = mesh.uvs;           // may be null
        int[]   idx  = mesh.indices;
        double  sx   = actor.transform.scale3D().x();
        double  sy   = actor.transform.scale3D().y();
        double  sz   = actor.transform.scale3D().z();
        double  tx   = actor.transform.translation().x();
        double  ty   = actor.transform.translation().y();
        double  tz   = actor.transform.translation().z();
        FQuat   rot  = actor.transform.rotation();

        if (mesh.sections != null && mesh.sections.length > 0) {
            // Render per section with its own texture
            for (UmapMeshData.Section section : mesh.sections) {
                Identifier texId = UmapTextureManager.getTexture(section);
                RenderType rt = RenderTypes.entityTranslucentEmissive(texId);
                VertexConsumer vc = buffers.getBuffer(rt);
                int end = section.firstIndex + section.indexCount;
                for (int i = section.firstIndex; i + 2 < end; i += 3) {
                    renderTriangle(vc, cam, maxDistSq, pos, uvs, idx, i, sx, sy, sz, tx, ty, tz, rot, h);
                }
            }
        } else {
            // No section data – render all triangles with white fallback texture
            Identifier texId = UmapTextureManager.getWhiteFallback();
            RenderType rt = RenderTypes.entityTranslucentEmissive(texId);
            VertexConsumer vc = buffers.getBuffer(rt);
            for (int i = 0; i + 2 < idx.length; i += 3) {
                renderTriangle(vc, cam, maxDistSq, pos, uvs, idx, i, sx, sy, sz, tx, ty, tz, rot, h);
            }
        }
    }

    private void renderTriangle(VertexConsumer vc, Vec3 cam, double maxDistSq,
                                 float[] pos, float[] uvs, int[] idx, int base,
                                 double sx, double sy, double sz,
                                 double tx, double ty, double tz,
                                 FQuat rot,
                                 HologramInstance h) {
        int ia = idx[base]     * 3;
        int ib = idx[base + 1] * 3;
        int ic = idx[base + 2] * 3;
        if (ia + 2 >= pos.length || ib + 2 >= pos.length || ic + 2 >= pos.length) return;

        float ax = pos[ia], ay = pos[ia+1], az = pos[ia+2];
        float bx = pos[ib], by = pos[ib+1], bz = pos[ib+2];
        float cx = pos[ic], cy = pos[ic+1], cz = pos[ic+2];

        // Per-face distance culling via centroid
        double cLx = (ax + bx + cx) / 3.0, cLy = (ay + by + cy) / 3.0, cLz = (az + bz + cz) / 3.0;
        double[] cScaled = rot.rotateVector(sx * cLx, sy * cLy, sz * cLz);
        double[] cr = rotateUePoint(h, cScaled[0] + tx, cScaled[1] + ty, cScaled[2] + tz);
        double dCx = h.toMcX(cr[0]) - cam.x, dCy = h.toMcY(cr[2]) - cam.y, dCz = h.toMcZ(cr[1]) - cam.z;
        if (dCx*dCx + dCy*dCy + dCz*dCz > maxDistSq) return;

        // Compute face normal
        float ex = bx-ax, ey = by-ay, ez = bz-az;
        float fx = cx-ax, fy = cy-ay, fz = cz-az;
        float nx = ey*fz - ez*fy;
        float ny = ez*fx - ex*fz;
        float nz = ex*fy - ey*fx;
        float nl = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; }

        // UV coordinates (or generate if missing)
        float ua = uvs != null ? uvs[idx[base]     * 2] : 0f;
        float va = uvs != null ? uvs[idx[base]     * 2 + 1] : 0f;
        float ub = uvs != null ? uvs[idx[base + 1] * 2] : 1f;
        float vb = uvs != null ? uvs[idx[base + 1] * 2 + 1] : 0f;
        float uc = uvs != null ? uvs[idx[base + 2] * 2] : 0.5f;
        float vc2= uvs != null ? uvs[idx[base + 2] * 2 + 1] : 1f;

        emitVertex(vc, cam, h, ax, ay, az, sx, sy, sz, tx, ty, tz, rot, ua, va, nx, ny, nz);
        emitVertex(vc, cam, h, bx, by, bz, sx, sy, sz, tx, ty, tz, rot, ub, vb, nx, ny, nz);
        emitVertex(vc, cam, h, cx, cy, cz, sx, sy, sz, tx, ty, tz, rot, uc, vc2, nx, ny, nz);
    }

    private void emitVertex(VertexConsumer vc, Vec3 cam,
                             HologramInstance h,
                             float lx, float ly, float lz,
                             double sx, double sy, double sz,
                             double tx, double ty, double tz,
                             FQuat actorRot,
                             float u, float v,
                             float nx, float ny, float nz) {
        // 1 – Scale
        double scaledX = sx * lx;
        double scaledY = sy * ly;
        double scaledZ = sz * lz;
        // 2 – Rotate (actor orientation)
        double[] rotated = actorRot.rotateVector(scaledX, scaledY, scaledZ);
        // 3 – Translate to UE world space
        double ueX = rotated[0] + tx;
        double ueY = rotated[1] + ty;
        double ueZ = rotated[2] + tz;
        // 4 – Apply hologram rotation in UE space
        double[] r = rotateUePoint(h, ueX, ueY, ueZ);
        // UE world → MC world (UE: X=East, Y=South, Z=Up  →  MC: X=East, Y=Up, Z=South)
        double mcX = h.toMcX(r[0]);
        double mcY = h.toMcY(r[2]);
        double mcZ = h.toMcZ(r[1]);
        // Camera-relative position for rendering
        float rx = (float)(mcX - cam.x);
        float ry = (float)(mcY - cam.y);
        float rz = (float)(mcZ - cam.z);

        vc.addVertex(rx, ry, rz)
          .setColor(255, 255, 255, 200)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(LightTexture.FULL_BRIGHT)
          .setNormal(nx, ny, nz);
    }

    // ------------------------------------------------------------------
    //  Rotation helper (mirrors HologramGizmoRenderer)
    // ------------------------------------------------------------------

    /**
     * Rotates a UE-space point by the hologram's global rotation (rotX, rotY, rotZ degrees).
     * Rotation order: Rx → Ry → Rz.
     */
    static double[] rotateUePoint(HologramInstance h, double x, double y, double z) {
        if (h.rotX == 0 && h.rotY == 0 && h.rotZ == 0) return new double[]{x, y, z};
        // Rotate around X
        double rx = Math.toRadians(h.rotX);
        double cosX = Math.cos(rx), sinX = Math.sin(rx);
        double y1 = y * cosX - z * sinX;
        double z1 = y * sinX + z * cosX;
        double x1 = x;
        // Rotate around Y
        double ry = Math.toRadians(h.rotY);
        double cosY = Math.cos(ry), sinY = Math.sin(ry);
        double x2 = x1 * cosY + z1 * sinY;
        double z2 = -x1 * sinY + z1 * cosY;
        double y2 = y1;
        // Rotate around Z
        double rz = Math.toRadians(h.rotZ);
        double cosZ = Math.cos(rz), sinZ = Math.sin(rz);
        double x3 = x2 * cosZ - y2 * sinZ;
        double y3 = x2 * sinZ + y2 * cosZ;
        double z3 = z2;
        return new double[]{x3, y3, z3};
    }
}
