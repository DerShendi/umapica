package net.shendi.umapica;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.shendi.umapica.keybind.KeyBindings;
import net.shendi.umapica.keybind.KeyInputHandler;
import net.shendi.umapica.render.HologramGizmoRenderer;
import net.shendi.umapica.render.HologramTexturedRenderer;

import java.io.File;

@Mod(value = Umapica.MOD_ID, dist = Dist.CLIENT)
public class UmapicaClient {

    /**
     * The {@code umapica/} folder inside the game directory.
     * Created at client startup. Place your {@code .umap} files here.
     */
    private static File umapDir = null;

    public UmapicaClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        KeyBindings.register(modEventBus);
        modEventBus.addListener((RegisterDebugRenderersEvent e) -> e.register(new HologramGizmoRenderer()));
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        KeyInputHandler.register();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(HologramTexturedRenderer.INSTANCE);
        // Create the umapica/ folder next to saves/, options.txt, etc.
        File gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory;
        umapDir = new File(gameDir, "umapica");
        if (!umapDir.exists()) {
            if (umapDir.mkdirs()) {
                Umapica.LOGGER.info("[Umapica] Created UMAP folder: {}", umapDir.getAbsolutePath());
            }
        } else {
            Umapica.LOGGER.info("[Umapica] UMAP folder: {}", umapDir.getAbsolutePath());
        }
    }

    /** Returns the {@code umapica/} game-directory folder (may be null before client setup). */
    public static File getUmapDir() {
        return umapDir;
    }
}
