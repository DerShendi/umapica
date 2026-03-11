package net.shendi.umapica.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.shendi.umapica.gui.UmapicaScreen;
import net.shendi.umapica.hologram.HologramInstance;
import net.shendi.umapica.hologram.HologramManager;

import java.util.List;

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
}
