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
        float[] pos = mesh.positions;
        float[] uvs = mesh.uvs;           // may be null
        int[]   idx = mesh.indices;
        double  sx  = actor.transform.scale3D().x();
        double  sy  = actor.transform.scale3D().y();
        double  sz  = actor.transform.scale3D().z();
        double  tx  = actor.transform.translation().x();
        double  ty  = actor.transform.translation().y();
        double  tz  = actor.transform.translation().z();
        FQuat   rot = actor.transform.rotation();

        // Build a quick section-lookup table: for each triangle index, which section owns it?
        // Falls back to section 0 / white fallback when sections are absent or incomplete.
        UmapMeshData.Section[] sections = (mesh.sections != null && mesh.sections.length > 0)
                ? mesh.sections : null;

        // Iterate ALL triangles exactly like ghost-mesh does – no section range splitting
        // that could produce wrong geometry when firstIndex/indexCount are stale.
        for (int i = 0; i + 2 < idx.length; i += 3) {
            int ia = idx[i]     * 3;
            int ib = idx[i + 1] * 3;
            int ic = idx[i + 2] * 3;
            if (ia + 2 >= pos.length || ib + 2 >= pos.length || ic + 2 >= pos.length) continue;

            float ax = pos[ia], ay = pos[ia+1], az = pos[ia+2];
            float bx = pos[ib], by = pos[ib+1], bz = pos[ib+2];
            float cx = pos[ic], cy = pos[ic+1], cz = pos[ic+2];

            // Use EXACT same transform as GhostMesh renderer
            Vec3 va = HologramGizmoRenderer.localToMc(h, ax, ay, az, sx, sy, sz, tx, ty, tz, rot);
            Vec3 vb = HologramGizmoRenderer.localToMc(h, bx, by, bz, sx, sy, sz, tx, ty, tz, rot);
            Vec3 vc = HologramGizmoRenderer.localToMc(h, cx, cy, cz, sx, sy, sz, tx, ty, tz, rot);

            // Per-face distance culling – mirrors ghost mesh
            double fcx = (va.x + vb.x + vc.x) / 3.0 - cam.x;
            double fcy = (va.y + vb.y + vc.y) / 3.0 - cam.y;
            double fcz = (va.z + vb.z + vc.z) / 3.0 - cam.z;
            if (fcx*fcx + fcy*fcy + fcz*fcz > maxDistSq) continue;
            // Backface culling in MC world space
            double eabx = vb.x-va.x, eaby = vb.y-va.y, eabz = vb.z-va.z;
            double eacx = vc.x-va.x, eacy = vc.y-va.y, eacz = vc.z-va.z;
            double mnx = eaby*eacz - eabz*eacy;
            double mny = eabz*eacx - eabx*eacz;
            double mnz = eabx*eacy - eaby*eacx;
            if (mnx*fcx + mny*fcy + mnz*fcz > 0) continue;

            // Look up texture for this triangle's section
            Identifier texId = UmapTextureManager.getWhiteFallback();
            if (sections != null) {
                for (UmapMeshData.Section sec : sections) {
                    if (i >= sec.firstIndex && i < sec.firstIndex + sec.indexCount) {
                        texId = UmapTextureManager.getTexture(sec);
                        break;
                    }
                }
            }
            RenderType rt = RenderTypes.entityTranslucentEmissive(texId);
            VertexConsumer vcBuf = buffers.getBuffer(rt);

            // Face normal for lighting
            float ex = bx-ax, ey = by-ay, ez = bz-az;
            float fx2= cx-ax, fy2= cy-ay, fz2= cz-az;
            float nx = ey*fz2 - ez*fy2;
            float ny = ez*fx2 - ex*fz2;
            float nz = ex*fy2 - ey*fx2;
            float nl = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; }

            // UV
            float ua = uvs != null ? uvs[idx[i]     * 2] : 0f;
            float vaU= uvs != null ? uvs[idx[i]     * 2 + 1] : 0f;
            float ub = uvs != null ? uvs[idx[i + 1] * 2] : 1f;
            float vbU= uvs != null ? uvs[idx[i + 1] * 2 + 1] : 0f;
            float uc = uvs != null ? uvs[idx[i + 2] * 2] : 0.5f;
            float vc2= uvs != null ? uvs[idx[i + 2] * 2 + 1] : 1f;

            emitVertex(vcBuf, cam, va, ua, vaU, nx, ny, nz);
            emitVertex(vcBuf, cam, vb, ub, vbU, nx, ny, nz);
            emitVertex(vcBuf, cam, vc, uc, vc2, nx, ny, nz);
        }
    }

    private void emitVertex(VertexConsumer vc, Vec3 cam, Vec3 worldPos,
                             float u, float v, float nx, float ny, float nz) {
        float rx = (float)(worldPos.x - cam.x);
        float ry = (float)(worldPos.y - cam.y);
        float rz = (float)(worldPos.z - cam.z);
        vc.addVertex(rx, ry, rz)
          .setColor(255, 255, 255, 200)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(LightTexture.FULL_BRIGHT)
          .setNormal(nx, ny, nz);
    }

    // ------------------------------------------------------------------
    //  rotateUePoint removed – uses HologramGizmoRenderer.rotateUePoint directly
    // ------------------------------------------------------------------
}
