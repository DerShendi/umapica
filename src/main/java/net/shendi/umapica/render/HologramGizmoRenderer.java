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

    private int facesThisFrame;
    private int maxFacesThisFrame;

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValueAccess,
                           Frustum frustum,
                           float partialTick) {

        if (Minecraft.getInstance().level == null) return;

        List<HologramInstance> holograms = HologramManager.get().getAll();
        if (holograms.isEmpty()) return;

        facesThisFrame    = 0;
        maxFacesThisFrame = Config.FACE_LIMIT.get();
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
            if (actor.hidden) continue;
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
            if (actor.hidden) continue;
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
            if (actor.hidden) continue;
            UmapMeshData mesh = actor.meshData;
            if (mesh == null || mesh.positions == null || mesh.positions.length < 9 || mesh.indices.length < 3) continue;
            renderMesh(h, actor, mesh, camX, camY, camZ, maxDistSq);
        }
    }

    /**
     * Ensures the actor's MC world-space vertex cache is up to date.
     *
     * <p>All per-vertex transforms (scale, actor rotation, actor translation, hologram
     * global rotation, UE→MC axis mapping) are pre-baked into a flat double array
     * {@code actor.cachedMcVerts}. The cache is invalidated only when the hologram's
     * origin, scale, or rotation changes – typically never during normal gameplay.</p>
     */
    public static void ensureVertexCache(HologramInstance h, UmapActor actor) {
        if (actor.meshData == null) return;
        // Fast path: check if the cache is still valid.
        if (actor.cachedMcVerts != null
                && actor.cacheOriginHash == h.origin.asLong()
                && actor.cacheScale == h.scale
                && actor.cacheRotX == h.rotX
                && actor.cacheRotY == h.rotY
                && actor.cacheRotZ == h.rotZ) {
            return;
        }

        float[] pos     = actor.meshData.positions;
        int     numV    = pos.length / 3;
        double[] mc     = (actor.cachedMcVerts != null && actor.cachedMcVerts.length == numV * 3)
                          ? actor.cachedMcVerts : new double[numV * 3];

        double sx = actor.transform.scale3D().x();
        double sy = actor.transform.scale3D().y();
        double sz = actor.transform.scale3D().z();
        double tx = actor.transform.translation().x();
        double ty = actor.transform.translation().y();
        double tz = actor.transform.translation().z();
        FQuat  rot = actor.transform.rotation();
        double qx = rot.x(), qy = rot.y(), qz = rot.z(), qw = rot.w();
        boolean identityActorRot = (qx == 0.0 && qy == 0.0 && qz == 0.0);

        // Pre-compute hologram rotation trig once for all vertices.
        boolean hasHolRot = (h.rotX != 0 || h.rotY != 0 || h.rotZ != 0);
        double cosX = 1, sinX = 0, cosY = 1, sinY = 0, cosZ = 1, sinZ = 0;
        if (hasHolRot) {
            double rxr = Math.toRadians(h.rotX), ryr = Math.toRadians(h.rotY), rzr = Math.toRadians(h.rotZ);
            cosX = Math.cos(rxr); sinX = Math.sin(rxr);
            cosY = Math.cos(ryr); sinY = Math.sin(ryr);
            cosZ = Math.cos(rzr); sinZ = Math.sin(rzr);
        }

        for (int v = 0; v < numV; v++) {
            int base = v * 3;
            double wx = sx * pos[base], wy = sy * pos[base+1], wz = sz * pos[base+2];

            if (!identityActorRot) {
                double ttx = 2.0 * (qy * wz - qz * wy);
                double tty = 2.0 * (qz * wx - qx * wz);
                double ttz = 2.0 * (qx * wy - qy * wx);
                wx = wx + qw * ttx + (qy * ttz - qz * tty);
                wy = wy + qw * tty + (qz * ttx - qx * ttz);
                wz = wz + qw * ttz + (qx * tty - qy * ttx);
            }

            wx += tx; wy += ty; wz += tz;

            if (hasHolRot) {
                double y1 = wy * cosX - wz * sinX;
                double z1 = wy * sinX + wz * cosX;
                double x2 = wx * cosY + z1 * sinY;
                double z2 = -wx * sinY + z1 * cosY;
                wx = x2 * cosZ - y1 * sinZ;
                wy = x2 * sinZ + y1 * cosZ;
                wz = z2;
            }

            // UE X→MC X,  UE Z→MC Y,  UE Y→MC Z
            mc[base]   = h.toMcX(wx);
            mc[base+1] = h.toMcY(wz);
            mc[base+2] = h.toMcZ(wy);
        }

        actor.cachedMcVerts  = mc;
        actor.cacheOriginHash = h.origin.asLong();
        actor.cacheScale     = h.scale;
        actor.cacheRotX      = h.rotX;
        actor.cacheRotY      = h.rotY;
        actor.cacheRotZ      = h.rotZ;
    }

    /**
     * Renders each triangle of the mesh as a degenerate quad (a-b-c-c) using
     * {@link Gizmos#rect(Vec3, Vec3, Vec3, Vec3, GizmoStyle)}.
     * Each triangle gets a diffuse shade computed from its face normal.
     * MC world-space vertices are read from the pre-built vertex cache.
     */
    private void renderMesh(HologramInstance h, UmapActor actor, UmapMeshData mesh,
                             double camX, double camY, double camZ, double maxDistSq) {
        ensureVertexCache(h, actor);
        double[] mc  = actor.cachedMcVerts;
        if (mc == null) return;

        int  rgb = actor.hintColor & 0x00FFFFFF;
        int  hr  = (rgb >> 16) & 0xFF;
        int  hg  = (rgb >>  8) & 0xFF;
        int  hb  =  rgb        & 0xFF;

        float[] pos = mesh.positions;
        int[]   idx = mesh.indices;
        int     mcLen = mc.length;

        for (int i = 0; i + 2 < idx.length; i += 3) {
            int ia = idx[i]     * 3;
            int ib = idx[i + 1] * 3;
            int ic = idx[i + 2] * 3;
            if (ia + 2 >= mcLen || ib + 2 >= mcLen || ic + 2 >= mcLen) continue;
            if (actor.hiddenTriangles != null && actor.hiddenTriangles.get(i / 3)) continue;

            double vax = mc[ia], vay = mc[ia+1], vaz = mc[ia+2];
            double vbx = mc[ib], vby = mc[ib+1], vbz = mc[ib+2];
            double vcx = mc[ic], vcy = mc[ic+1], vcz = mc[ic+2];

            // Distance culling on centroid (no transform needed – already MC-space)
            double fcx = (vax + vbx + vcx) * (1.0/3.0) - camX;
            double fcy = (vay + vby + vcy) * (1.0/3.0) - camY;
            double fcz = (vaz + vbz + vcz) * (1.0/3.0) - camZ;
            if (fcx*fcx + fcy*fcy + fcz*fcz > maxDistSq) continue;

            // Backface culling in MC world space
            double eabx = vbx-vax, eaby = vby-vay, eabz = vbz-vaz;
            double eacx = vcx-vax, eacy = vcy-vay, eacz = vcz-vaz;
            double mnx = eaby*eacz - eabz*eacy;
            double mny = eabz*eacx - eabx*eacz;
            double mnz = eabx*eacy - eaby*eacx;
            if (mnx*fcx + mny*fcy + mnz*fcz > 0) continue;

            // Face budget
            if (maxFacesThisFrame > 0) {
                if (facesThisFrame >= maxFacesThisFrame) break;
                facesThisFrame++;
            }

            // Shade from local-space positions (no transform needed for diffuse shading)
            if (ia + 2 >= pos.length || ib + 2 >= pos.length || ic + 2 >= pos.length) continue;
            float shade = computeShade(pos[ia], pos[ia+1], pos[ia+2],
                                       pos[ib], pos[ib+1], pos[ib+2],
                                       pos[ic], pos[ic+1], pos[ic+2]);
            int sr = Math.max(0, Math.min(255, (int)(hr * shade)));
            int sg = Math.max(0, Math.min(255, (int)(hg * shade)));
            int sb = Math.max(0, Math.min(255, (int)(hb * shade)));
            int shadedRgb = (sr << 16) | (sg << 8) | sb;
            GizmoStyle style = GizmoStyle.strokeAndFill(0xC0000000 | shadedRgb, 1.0f, 0x60000000 | shadedRgb);

            Gizmos.rect(new Vec3(vax, vay, vaz), new Vec3(vbx, vby, vbz),
                        new Vec3(vcx, vcy, vcz), new Vec3(vcx, vcy, vcz), style);
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
    public static AABB toMcAABB(HologramInstance h, FBox wb) {
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
