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

    private int facesThisFrame;
    private int maxFacesThisFrame;

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
        facesThisFrame    = 0;
        maxFacesThisFrame = Config.FACE_LIMIT.get();
        for (HologramInstance h : holograms) {
            if (!h.visible || h.renderMode != RenderMode.TEXTURED_MESH) continue;
            for (UmapActor actor : h.umap.actors) {
                if (actor.hidden) continue;
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
        HologramGizmoRenderer.ensureVertexCache(h, actor);
        double[] mc = actor.cachedMcVerts;
        if (mc == null) return;

        float[] pos = mesh.positions;
        float[] uvs = mesh.uvs;
        int[]   idx = mesh.indices;
        int     mcLen = mc.length;

        // Build a per-triangle section index on first use so lookup is O(1) per triangle.
        int[] triSection = mesh.getTriangleSectionIndex();

        UmapMeshData.Section[] sections = (mesh.sections != null && mesh.sections.length > 0)
                ? mesh.sections : null;

        for (int i = 0; i + 2 < idx.length; i += 3) {
            int ia = idx[i]     * 3;
            int ib = idx[i + 1] * 3;
            int ic = idx[i + 2] * 3;
            if (ia + 2 >= mcLen || ib + 2 >= mcLen || ic + 2 >= mcLen) continue;
            if (actor.hiddenTriangles != null && actor.hiddenTriangles.get(i / 3)) continue;

            double vax = mc[ia], vay = mc[ia+1], vaz = mc[ia+2];
            double vbx = mc[ib], vby = mc[ib+1], vbz = mc[ib+2];
            double vcx = mc[ic], vcy = mc[ic+1], vcz = mc[ic+2];

            // Per-face distance culling
            double dx = (vax + vbx + vcx) * (1.0/3.0) - cam.x;
            double dy = (vay + vby + vcy) * (1.0/3.0) - cam.y;
            double dz = (vaz + vbz + vcz) * (1.0/3.0) - cam.z;
            if (dx*dx + dy*dy + dz*dz > maxDistSq) continue;
            // NOTE: no backface culling — the UE→MC axis swap (x,y,z)→(x,z,y) inverts winding
            // order, so any per-winding cull would remove the visible faces.

            // Face budget
            if (maxFacesThisFrame > 0) {
                if (facesThisFrame >= maxFacesThisFrame) break;
                facesThisFrame++;
            }

            // O(1) section lookup
            Identifier texId = UmapTextureManager.getWhiteFallback();
            int triIdx = i / 3;
            if (sections != null && triSection != null && triIdx < triSection.length) {
                int si = triSection[triIdx];
                if (si >= 0 && si < sections.length) texId = UmapTextureManager.getTexture(sections[si]);
            }
            RenderType rt = RenderTypes.entityTranslucentEmissive(texId);
            VertexConsumer vcBuf = buffers.getBuffer(rt);

            // Face normal from local-space coords
            float nx = 0, ny = 0, nz = 1;
            if (ia + 2 < pos.length && ib + 2 < pos.length && ic + 2 < pos.length) {
                float ex = pos[ib]-pos[ia], ey = pos[ib+1]-pos[ia+1], ez = pos[ib+2]-pos[ia+2];
                float fx = pos[ic]-pos[ia], fy = pos[ic+1]-pos[ia+1], fz = pos[ic+2]-pos[ia+2];
                nx = ey*fz - ez*fy;
                ny = ez*fx - ex*fz;
                nz = ex*fy - ey*fx;
                float nl = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; }
            }

            float ua = uvs != null ? uvs[idx[i]     * 2]     : 0f;
            float va = uvs != null ? uvs[idx[i]     * 2 + 1] : 0f;
            float ub = uvs != null ? uvs[idx[i + 1] * 2]     : 1f;
            float vb = uvs != null ? uvs[idx[i + 1] * 2 + 1] : 0f;
            float uc = uvs != null ? uvs[idx[i + 2] * 2]     : 0.5f;
            float vc2= uvs != null ? uvs[idx[i + 2] * 2 + 1] : 1f;

            emitVertex(vcBuf, cam, vax, vay, vaz, ua, va, nx, ny, nz);
            emitVertex(vcBuf, cam, vbx, vby, vbz, ub, vb, nx, ny, nz);
            emitVertex(vcBuf, cam, vcx, vcy, vcz, uc, vc2, nx, ny, nz);
        }
    }

    private void emitVertex(VertexConsumer vc, Vec3 cam,
                             double wx, double wy, double wz,
                             float u, float v, float nx, float ny, float nz) {
        vc.addVertex((float)(wx - cam.x), (float)(wy - cam.y), (float)(wz - cam.z))
          .setColor(255, 255, 255, 200)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(LightTexture.FULL_BRIGHT)
          .setNormal(nx, ny, nz);
    }
}
