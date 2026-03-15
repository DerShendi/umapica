package net.shendi.umapica;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DEFAULT_SCALE = BUILDER
            .comment("Default units-per-block scale when loading a UMAP file. " +
                     "Unreal Engine uses 1 unit = 1 cm, so 100 units = 1 block by default.")
            .defineInRange("defaultScale", 100.0, 1.0, 100000.0);

    public static final ModConfigSpec.IntValue WIREFRAME_COLOR = BUILDER
            .comment("Default wireframe ARGB color (hex int). Default: 0xFF00FF00 (opaque green).")
            .defineInRange("wireframeColor", 0xFF00FF00, Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue VOXEL_ALPHA = BUILDER
            .comment("Alpha (0-255) used when rendering voxel hologram blocks.")
            .defineInRange("voxelAlpha", 100, 0, 255);

    public static final ModConfigSpec.IntValue GHOST_ALPHA = BUILDER
            .comment("Alpha (0-255) used when rendering ghost mesh surfaces.")
            .defineInRange("ghostAlpha", 80, 0, 255);

    public static final ModConfigSpec.IntValue RENDER_DISTANCE = BUILDER
            .comment("Current hologram render distance in blocks (16 - maxRenderDistance). Default 128.")
            .defineInRange("renderDistance", 128, 16, 4096);

    public static final ModConfigSpec.IntValue MAX_RENDER_DISTANCE = BUILDER
            .comment("Upper limit of the in-game render-distance slider in blocks (16-4096). " +
                     "Raise this if you want the slider to go beyond 512. Default 512.")
            .defineInRange("maxRenderDistance", 512, 16, 4096);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ASSET_SEARCH_PATHS = BUILDER
            .comment("Directories to search for .upk / .uasset mesh files. " +
                     "Add the full path to your game's CookedPC folder here. " +
                     "Example: C:/Program Files (x86)/Steam/steamapps/common/HatinTime/HatinTimeGame/CookedPC")
            .defineListAllowEmpty("assetSearchPaths", List.of(), o -> o instanceof String);

    static final ModConfigSpec SPEC = BUILDER.build();
}
