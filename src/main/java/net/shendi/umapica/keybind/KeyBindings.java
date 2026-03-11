package net.shendi.umapica.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Registers all key-mappings for Umapica and acts on them via the input event.
 *
 * <ul>
 *   <li><b>M</b> – Open the Umapica control GUI (load files, manage holograms).</li>
 *   <li><b>H</b> – Toggle visibility of all holograms on/off.</li>
 *   <li><b>N</b> – Cycle the render mode of the currently-selected hologram.</li>
 *   <li><b>Numpad 8/2</b> – Rotate hologram ±90° around the X axis.</li>
 *   <li><b>Numpad 4/6</b> – Rotate hologram ±90° around the Y axis.</li>
 *   <li><b>Numpad 7/9</b> – Rotate hologram ±90° around the Z axis.</li>
 * </ul>
 */
public class KeyBindings {

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.parse("umapica:category"));

    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.umapica.open_gui",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_M,
            CATEGORY
    );

    public static final KeyMapping TOGGLE_ALL = new KeyMapping(
            "key.umapica.toggle_all",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_H,
            CATEGORY
    );

    public static final KeyMapping CYCLE_MODE = new KeyMapping(
            "key.umapica.cycle_mode",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_N,
            CATEGORY
    );

    // GLFW numpad key codes (GLFW_KEY_KP_n)
    /** Numpad 8 – rotate +90° around the X axis. */
    public static final KeyMapping ROTATE_X_POS = new KeyMapping(
            "key.umapica.rotate_x_pos",
            InputConstants.Type.KEYSYM,
            328,   // GLFW_KEY_KP_8
            CATEGORY
    );
    /** Numpad 2 – rotate −90° around the X axis. */
    public static final KeyMapping ROTATE_X_NEG = new KeyMapping(
            "key.umapica.rotate_x_neg",
            InputConstants.Type.KEYSYM,
            322,   // GLFW_KEY_KP_2
            CATEGORY
    );
    /** Numpad 4 – rotate +90° around the Y axis. */
    public static final KeyMapping ROTATE_Y_POS = new KeyMapping(
            "key.umapica.rotate_y_pos",
            InputConstants.Type.KEYSYM,
            324,   // GLFW_KEY_KP_4
            CATEGORY
    );
    /** Numpad 6 – rotate −90° around the Y axis. */
    public static final KeyMapping ROTATE_Y_NEG = new KeyMapping(
            "key.umapica.rotate_y_neg",
            InputConstants.Type.KEYSYM,
            326,   // GLFW_KEY_KP_6
            CATEGORY
    );
    /** Numpad 7 – rotate +90° around the Z axis. */
    public static final KeyMapping ROTATE_Z_POS = new KeyMapping(
            "key.umapica.rotate_z_pos",
            InputConstants.Type.KEYSYM,
            327,   // GLFW_KEY_KP_7
            CATEGORY
    );
    /** Numpad 9 – rotate −90° around the Z axis. */
    public static final KeyMapping ROTATE_Z_NEG = new KeyMapping(
            "key.umapica.rotate_z_neg",
            InputConstants.Type.KEYSYM,
            329,   // GLFW_KEY_KP_9
            CATEGORY
    );

    /**
     * Subscribes the register event on the mod event-bus.
     * Call from {@link net.shendi.umapica.UmapicaClient}'s constructor.
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN_GUI);
            e.register(TOGGLE_ALL);
            e.register(CYCLE_MODE);
            e.register(ROTATE_X_POS);
            e.register(ROTATE_X_NEG);
            e.register(ROTATE_Y_POS);
            e.register(ROTATE_Y_NEG);
            e.register(ROTATE_Z_POS);
            e.register(ROTATE_Z_NEG);
        });
    }
}
