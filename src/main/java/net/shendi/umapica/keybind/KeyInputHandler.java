package net.shendi.umapica.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.shendi.umapica.Config;
import net.shendi.umapica.gui.UmapicaScreen;
import net.shendi.umapica.hologram.HologramInstance;
import net.shendi.umapica.hologram.HologramManager;
import net.shendi.umapica.render.HologramGizmoRenderer;
import net.shendi.umapica.umap.FBox;
import net.shendi.umapica.umap.UmapActor;

import java.util.List;
import java.util.Optional;

/**
 * Polls key-bindings each client tick and triggers the corresponding actions.
 * Registered on the NeoForge game-event bus.
 */
public class KeyInputHandler {

    private static final KeyInputHandler INSTANCE = new KeyInputHandler();

    private KeyInputHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return; // GUI already open

        if (KeyBindings.OPEN_GUI.consumeClick()) {
            mc.setScreen(new UmapicaScreen());
        }

        if (KeyBindings.TOGGLE_ALL.consumeClick()) {
            List<HologramInstance> all = HologramManager.get().getAll();
            if (all.isEmpty()) {
                mc.player.displayClientMessage(Component.literal("[Umapica] No holograms loaded."), true);
                return;
            }
            boolean anyVisible = all.stream().anyMatch(h -> h.visible);
            all.forEach(h -> h.visible = !anyVisible);
            mc.player.displayClientMessage(
                Component.literal("[Umapica] Holograms " + (anyVisible ? "hidden" : "shown") + "."), true);
        }

        if (KeyBindings.CYCLE_MODE.consumeClick()) {
            List<HologramInstance> all = HologramManager.get().getAll();
            if (all.isEmpty()) {
                mc.player.displayClientMessage(Component.literal("[Umapica] No holograms loaded."), true);
                return;
            }
            // Cycle the FIRST hologram's mode (user can change individual modes in the GUI)
            HologramInstance first = all.get(0);
            first.renderMode = first.renderMode.next();
            mc.player.displayClientMessage(
                Component.literal("[Umapica] Mode → " + first.renderMode.displayName), true);
        }

        // ---- Rotation keybinds (Numpad 8/2 = X, 4/6 = Y, 7/9 = Z) ----
        handleRotationKeys(mc);
    }

    private void handleRotationKeys(net.minecraft.client.Minecraft mc) {
        List<HologramInstance> all = HologramManager.get().getAll();
        if (all.isEmpty()) return;
        HologramInstance first = all.get(0);

        boolean rotated = false;
        String axis = "";

        if (KeyBindings.ROTATE_X_POS.consumeClick()) { first.rotateX(+1); rotated = true; axis = "+X"; }
        if (KeyBindings.ROTATE_X_NEG.consumeClick()) { first.rotateX(-1); rotated = true; axis = "-X"; }
        if (KeyBindings.ROTATE_Y_POS.consumeClick()) { first.rotateY(+1); rotated = true; axis = "+Y"; }
        if (KeyBindings.ROTATE_Y_NEG.consumeClick()) { first.rotateY(-1); rotated = true; axis = "-Y"; }
        if (KeyBindings.ROTATE_Z_POS.consumeClick()) { first.rotateZ(+1); rotated = true; axis = "+Z"; }
        if (KeyBindings.ROTATE_Z_NEG.consumeClick()) { first.rotateZ(-1); rotated = true; axis = "-Z"; }

        if (rotated && mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal(String.format("[Umapica] Rot %s → X=%.0f° Y=%.0f° Z=%.0f°",
                    axis, first.rotX, first.rotY, first.rotZ)), true);
        }
    }

    // ============================================================
    //  Flint left-click → hide actor  |  right-click → undo hide
    // ============================================================

    @SubscribeEvent
    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!mc.player.getMainHandItem().is(Items.FLINT)) return;

        if (event.isAttack()) {
            // Left click: hide the triangle face the player is looking at
            PickResult hit = pickActor(mc);
            if (hit != null) {
                HologramManager.get().hideActor(hit.hologram(), hit.actor(), hit.triangleIndex());
                String label = hit.triangleIndex() >= 0
                        ? hit.actor().actorName + " face #" + hit.triangleIndex()
                        : hit.actor().actorName;
                mc.player.displayClientMessage(
                    Component.literal("[Umapica] Hid: " + label), true);
            }
            event.setCanceled(true);
            event.setSwingHand(false);
        } else if (event.isUseItem()) {
            // Right click: undo the last hide
            UmapActor restored = HologramManager.get().undoHide();
            if (restored != null) {
                mc.player.displayClientMessage(
                    Component.literal("[Umapica] Restored: " + restored.actorName), true);
            } else {
                mc.player.displayClientMessage(
                    Component.literal("[Umapica] Nothing to restore."), true);
            }
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private record PickResult(HologramInstance hologram, UmapActor actor, int triangleIndex) {}

    /** Raycasts against all visible mesh actors and returns the closest hit. */
    private static PickResult pickActor(Minecraft mc) {
        Vec3 origin = mc.gameRenderer.getMainCamera().position();
        Vec3 dir    = mc.player.getLookAngle();
        double maxDist = Config.RENDER_DISTANCE.get();
        Vec3 end = origin.add(dir.scale(maxDist));

        double bestT = maxDist;
        HologramInstance bestH     = null;
        UmapActor        bestActor = null;
        int              bestTriangle = -1;

        for (HologramInstance h : HologramManager.get().getAll()) {
            if (!h.visible) continue;
            for (UmapActor actor : h.umap.actors) {
                if (actor.hidden || actor.meshData == null) continue;

                // AABB pre-check in MC world space
                FBox wb = actor.worldBounds();
                AABB aabb = HologramGizmoRenderer.toMcAABB(h, wb);
                Optional<Vec3> aabbHit = aabb.clip(origin, end);
                if (aabbHit.isEmpty()) continue;

                // Per-triangle Möller–Trumbore test
                HologramGizmoRenderer.ensureVertexCache(h, actor);
                double[] mcVerts = actor.cachedMcVerts;
                if (mcVerts == null) {
                    // Cache not ready; use AABB distance as fallback — hide whole actor
                    double t = aabbHit.get().distanceTo(origin);
                    if (t < bestT) { bestT = t; bestH = h; bestActor = actor; bestTriangle = -1; }
                    continue;
                }
                int[] idx = actor.meshData.indices;
                for (int i = 0; i + 2 < idx.length; i += 3) {
                    if (actor.hiddenTriangles != null && actor.hiddenTriangles.get(i / 3)) continue;
                    double t = rayTriangle(origin, dir, mcVerts, idx[i], idx[i + 1], idx[i + 2]);
                    if (t > 0 && t < bestT) { bestT = t; bestH = h; bestActor = actor; bestTriangle = i / 3; }
                }
            }
        }
        return bestH != null ? new PickResult(bestH, bestActor, bestTriangle) : null;
    }

    /** Möller–Trumbore ray-triangle intersection. Returns hit distance or -1 on miss. */
    private static double rayTriangle(Vec3 o, Vec3 d, double[] mc, int ia, int ib, int ic) {
        double ax = mc[ia*3], ay = mc[ia*3+1], az = mc[ia*3+2];
        double bx = mc[ib*3], by = mc[ib*3+1], bz = mc[ib*3+2];
        double cx = mc[ic*3], cy = mc[ic*3+1], cz = mc[ic*3+2];
        double e1x = bx-ax, e1y = by-ay, e1z = bz-az;
        double e2x = cx-ax, e2y = cy-ay, e2z = cz-az;
        double hx = d.y*e2z - d.z*e2y;
        double hy = d.z*e2x - d.x*e2z;
        double hz = d.x*e2y - d.y*e2x;
        double det = e1x*hx + e1y*hy + e1z*hz;
        if (Math.abs(det) < 1e-10) return -1;
        double invDet = 1.0 / det;
        double sx = o.x-ax, sy = o.y-ay, sz = o.z-az;
        double u = (sx*hx + sy*hy + sz*hz) * invDet;
        if (u < 0 || u > 1) return -1;
        double qx = sy*e1z - sz*e1y, qy = sz*e1x - sx*e1z, qz = sx*e1y - sy*e1x;
        double v = (d.x*qx + d.y*qy + d.z*qz) * invDet;
        if (v < 0 || u+v > 1) return -1;
        double t = (e2x*qx + e2y*qy + e2z*qz) * invDet;
        return t > 1e-10 ? t : -1;
    }
}
