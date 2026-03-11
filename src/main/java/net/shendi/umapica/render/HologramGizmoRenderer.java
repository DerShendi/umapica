package net.shendi.umapica.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.shendi.umapica.Config;
import net.shendi.umapica.hologram.HologramInstance;
import net.shendi.umapica.hologram.HologramManager;
import net.shendi.umapica.umap.FBox;
import net.shendi.umapica.umap.FQuat;
import net.shendi.umapica.umap.UmapActor;
import net.shendi.umapica.umap.UmapMeshData;

import java.util.List;

/**
 * Renders all active holograms using the Gizmos API.
 *
 * <ul>
 *   <li><b>WIREFRAME</b> – transparent outline boxes per actor.</li>
 *   <li><b>VOXEL</b>     – filled semi-transparent boxes per actor.</li>
 *   <li><b>GHOST_MESH</b> – per-triangle geometry if mesh data is loaded;
 *                           falls back to outlined+filled boxes otherwise.</li>
 * </ul>
 *
 * <p>Registered via {@code RegisterDebugRenderersEvent} (mod event bus) so it
 * runs unconditionally every frame while a world is loaded.</p>
 */
public class HologramGizmoRenderer implements DebugRenderer.SimpleDebugRenderer {

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValueAccess,
                           Frustum frustum,
                           float partialTick) {

        if (Minecraft.getInstance().level == null) return;

        List<HologramInstance> holograms = HologramManager.get().getAll();
        if (holograms.isEmpty()) return;

        double _maxDist = Config.RENDER_DISTANCE.get();
        double _maxDistSq = _maxDist * _maxDist;
        for (HologramInstance h : holograms) {
            if (!h.visible) continue;
            switch (h.renderMode) {
                case WIREFRAME     -> renderWireframe(h);
                case VOXEL         -> renderVoxel(h);
                case GHOST_MESH    -> renderGhostMesh(h, camX, camY, camZ, _maxDistSq);
                case TEXTURED_MESH -> {} // fully handled by HologramTexturedRenderer
            }
        }
    }

    // ============================================================
    //  WIREFRAME – outline boxes
    // ============================================================

    private void renderWireframe(HologramInstance h) {
        for (UmapActor actor : h.umap.actors) {
            FBox wb = actor.worldBounds();
            if (wb == null) continue;
            AABB aabb = toMcAABB(h, wb);
            int color = 0xFF000000 | (actor.hintColor & 0x00FFFFFF);
            Gizmos.cuboid(aabb, GizmoStyle.stroke(color, 2.5f));
        }
    }

    // ============================================================
    //  VOXEL – semi-transparent filled boxes
    // ============================================================

    private void renderVoxel(HologramInstance h) {
        for (UmapActor actor : h.umap.actors) {
            FBox wb = actor.worldBounds();
            if (wb == null) continue;
            AABB aabb = toMcAABB(h, wb);
            int rgb   = actor.hintColor & 0x00FFFFFF;
            int stroke = 0xFF000000 | rgb;       // fully opaque outline
            int fill   = 0x50000000 | rgb;       // ~31 % alpha fill
            Gizmos.cuboid(aabb, GizmoStyle.strokeAndFill(stroke, 2.0f, fill));
        }
    }

    // ============================================================
    //  GHOST MESH – per-triangle if mesh loaded, else filled box
    // ============================================================

    private void renderGhostMesh(HologramInstance h, double camX, double camY, double camZ, double maxDistSq) {
        for (UmapActor actor : h.umap.actors) {
            UmapMeshData mesh = actor.meshData;
            if (mesh == null || mesh.positions == null || mesh.positions.length < 9 || mesh.indices.length < 3) continue;
            renderMesh(h, actor, mesh, camX, camY, camZ, maxDistSq);
        }
    }

    /**
     * Renders each triangle of the mesh as a degenerate quad (a-b-c-c) using
     * {@link Gizmos#rect(Vec3, Vec3, Vec3, Vec3, GizmoStyle)}.
     * Each triangle gets a diffuse shade computed from its face normal so the
     * hologram looks three-dimensional.
     * The actor's full transform (scale → rotate → translate) is applied to each vertex.
     */
    private void renderMesh(HologramInstance h, UmapActor actor, UmapMeshData mesh,
                             double camX, double camY, double camZ, double maxDistSq) {
        int  rgb    = actor.hintColor & 0x00FFFFFF;
        int  hr = (rgb >> 16) & 0xFF;
        int  hg = (rgb >>  8) & 0xFF;
        int  hb =  rgb        & 0xFF;

        float[] pos  = mesh.positions;
        int[]   idx  = mesh.indices;
        double  sx   = actor.transform.scale3D().x();
        double  sy   = actor.transform.scale3D().y();
        double  sz   = actor.transform.scale3D().z();
        double  tx   = actor.transform.translation().x();
        double  ty   = actor.transform.translation().y();
        double  tz   = actor.transform.translation().z();
        FQuat   rot  = actor.transform.rotation();

        for (int i = 0; i + 2 < idx.length; i += 3) {
            int ia = idx[i]     * 3;
            int ib = idx[i + 1] * 3;
            int ic = idx[i + 2] * 3;
            if (ia + 2 >= pos.length || ib + 2 >= pos.length || ic + 2 >= pos.length) continue;

            float ax = pos[ia], ay = pos[ia+1], az = pos[ia+2];
            float bx = pos[ib], by = pos[ib+1], bz = pos[ib+2];
            float cx = pos[ic], cy = pos[ic+1], cz = pos[ic+2];

            // Compute face normal for shading
            float shade = computeShade(ax, ay, az, bx, by, bz, cx, cy, cz);
            int sr = Math.max(0, Math.min(255, (int)(hr * shade)));
            int sg = Math.max(0, Math.min(255, (int)(hg * shade)));
            int sb = Math.max(0, Math.min(255, (int)(hb * shade)));
            int shadedRgb = (sr << 16) | (sg << 8) | sb;

            int stroke = 0xC0000000 | shadedRgb;
            int fill   = 0x60000000 | shadedRgb;
            GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 1.0f, fill);

            Vec3 va = localToMc(h, ax, ay, az, sx, sy, sz, tx, ty, tz, rot);
            Vec3 vb = localToMc(h, bx, by, bz, sx, sy, sz, tx, ty, tz, rot);
            Vec3 vc = localToMc(h, cx, cy, cz, sx, sy, sz, tx, ty, tz, rot);
            // Per-face distance culling: skip triangle if its centroid is beyond render distance
            double fcx = (va.x + vb.x + vc.x) / 3.0 - camX;
            double fcy = (va.y + vb.y + vc.y) / 3.0 - camY;
            double fcz = (va.z + vb.z + vc.z) / 3.0 - camZ;
            if (fcx*fcx + fcy*fcy + fcz*fcz > maxDistSq) continue;
            // Render as degenerate quad (triangle = a-b-c-c)
            Gizmos.rect(va, vb, vc, vc, style);
        }
    }

    /** Sun direction for diffuse shading (normalised). Values match Minecraft's default lighting. */
    private static final float SUN_X = 0.577f, SUN_Y = 0.817f, SUN_Z = 0.577f;
    private static final float AMBIENT = 0.35f, DIFFUSE = 0.65f;

    /**
     * Returns a shade factor in [AMBIENT, 1.0] based on the face normal of the triangle a-b-c.
     */
    private static float computeShade(float ax, float ay, float az,
                                       float bx, float by, float bz,
                                       float cx, float cy, float cz) {
        float ex = bx-ax, ey = by-ay, ez = bz-az;
        float fx = cx-ax, fy = cy-ay, fz = cz-az;
        float nx = ey*fz - ez*fy;
        float ny = ez*fx - ex*fz;
        float nz = ex*fy - ey*fx;
        float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (len < 1e-6f) return AMBIENT;
        nx /= len; ny /= len; nz /= len;
        float dot = nx*SUN_X + ny*SUN_Y + nz*SUN_Z;
        float absDot = Math.abs(dot); // both face directions get their own shade
        return AMBIENT + DIFFUSE * absDot;
    }

    // ============================================================
    //  Coordinate helpers
    // ============================================================

    /**
     * Converts a UE world-space bounding box to a Minecraft world-space {@link AABB}.
     * Applies the hologram's global rotation to all 8 box corners before computing the AABB.
     * UE axes: X = East/West, Y = North/South, Z = Up.
     * MC  axes: X = East/West, Z = North/South, Y = Up.
     */
    private AABB toMcAABB(HologramInstance h, FBox wb) {
        double x0 = wb.min().x(), y0 = wb.min().y(), z0 = wb.min().z();
        double x1 = wb.max().x(), y1 = wb.max().y(), z1 = wb.max().z();
        double minX = Double.MAX_VALUE,  minY = Double.MAX_VALUE,  minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[][] corners = {
            {x0,y0,z0},{x1,y0,z0},{x0,y1,z0},{x1,y1,z0},
            {x0,y0,z1},{x1,y0,z1},{x0,y1,z1},{x1,y1,z1}
        };
        for (double[] c : corners) {
            double[] r = rotateUePoint(h, c[0], c[1], c[2]);
            double mcx = h.toMcX(r[0]), mcy = h.toMcY(r[2]), mcz = h.toMcZ(r[1]);
            if (mcx < minX) minX = mcx;  if (mcx > maxX) maxX = mcx;
            if (mcy < minY) minY = mcy;  if (mcy > maxY) maxY = mcy;
            if (mcz < minZ) minZ = mcz;  if (mcz > maxZ) maxZ = mcz;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Converts a mesh local-space vertex to a Minecraft world-space {@link Vec3}.
     * Full actor transform (scale → rotate → translate) and hologram global rotation are applied.
     */
    public static Vec3 localToMc(HologramInstance h,
                            float lx, float ly, float lz,
                            double sx, double sy, double sz,
                            double tx, double ty, double tz,
                            FQuat actorRot) {
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
        // 4 – Apply hologram global rotation around origin
        double[] r = rotateUePoint(h, ueX, ueY, ueZ);
        // UE X→MC X, UE Z→MC Y, UE Y→MC Z
        return new Vec3(h.toMcX(r[0]), h.toMcY(r[2]), h.toMcZ(r[1]));
    }

    /**
     * Rotates a UE-space point by the hologram's global rotation (rotX, rotY, rotZ).
     * Angles are in degrees; rotation order is Rx → Ry → Rz (extrinsic).
     */
    public static double[] rotateUePoint(HologramInstance h, double x, double y, double z) {
        if (h.rotX == 0 && h.rotY == 0 && h.rotZ == 0) return new double[]{x, y, z};
        // --- Rotate around X ---
        double rx = Math.toRadians(h.rotX);
        double cosX = Math.cos(rx), sinX = Math.sin(rx);
        double y1 = y * cosX - z * sinX;
        double z1 = y * sinX + z * cosX;
        double x1 = x;
        // --- Rotate around Y ---
        double ry = Math.toRadians(h.rotY);
        double cosY = Math.cos(ry), sinY = Math.sin(ry);
        double x2 = x1 * cosY + z1 * sinY;
        double z2 = -x1 * sinY + z1 * cosY;
        double y2 = y1;
        // --- Rotate around Z ---
        double rz = Math.toRadians(h.rotZ);
        double cosZ = Math.cos(rz), sinZ = Math.sin(rz);
        double x3 = x2 * cosZ - y2 * sinZ;
        double y3 = x2 * sinZ + y2 * cosZ;
        double z3 = z2;
        return new double[]{x3, y3, z3};
    }
}
