package net.shendi.umapica.hologram;

import net.minecraft.core.BlockPos;
import net.shendi.umapica.umap.FVector;
import net.shendi.umapica.umap.UmapPackage;

/**
 * Represents a single loaded UMAP hologram placed in the Minecraft world.
 *
 * <p>All UE spatial data (actor transforms) is stored in Unreal Engine units
 * (centimetres). Converting to Minecraft block-coordinates is done by dividing
 * by {@link #scale}.
 */
public class HologramInstance {

    /** Unique id – matches the index in {@link HologramManager}. */
    public final int id;

    /** The source package (fully parsed). */
    public final UmapPackage umap;

    /** Display name shown in the GUI (defaults to file name). */
    public String name;

    /** Whether this hologram is currently visible. */
    public boolean visible = true;

    /** Active render mode (user-switchable). */
    public RenderMode renderMode = RenderMode.GHOST_MESH;

    /**
     * World-origin of the hologram in Minecraft block-space.
     * The UE origin (0,0,0) maps to this position.
     */
    public BlockPos origin = BlockPos.ZERO;

    /**
     * Scale factor. Default 100 = 1:1 (100 UE cm → 1 MC block).
     * Higher values make the hologram larger; lower values shrink it.
     */
    public double scale = 100.0;

    /** Tint color applied on top of per-actor hint colours.  0xAARRGGBB.  Default fully transparent (no tint). */
    public int globalTint = 0x00FFFFFF;

    /**
     * Global hologram rotation in UE-space, degrees.
     * Always snapped to 90° increments via {@link #rotateX}, {@link #rotateY}, {@link #rotateZ}.
     */
    public float rotX = 0, rotY = 0, rotZ = 0;

    /** Rotate the hologram 90 degrees around the UE X axis. */
    public void rotateX(int sign) { rotX = ((rotX + sign * 90) % 360 + 360) % 360; }
    /** Rotate the hologram 90 degrees around the UE Y axis. */
    public void rotateY(int sign) { rotY = ((rotY + sign * 90) % 360 + 360) % 360; }
    /** Rotate the hologram 90 degrees around the UE Z axis. */
    public void rotateZ(int sign) { rotZ = ((rotZ + sign * 90) % 360 + 360) % 360; }

    public HologramInstance(int id, UmapPackage umap) {
        this.id   = id;
        this.umap = umap;
        this.name = umap.file.getName().replace(".umap", "");
    }

    /**
     * Converts a UE-space translation to a Minecraft world-space position.
     * scale=100 → 100 UE cm = 1 MC block (1 m). ×2 scale = twice as large.
     */
    public double toMcX(double ueX) { return origin.getX() + ueX * scale / 10000.0; }
    /** UE Z axis = MC Y axis (up). */
    public double toMcY(double ueZ) { return origin.getY() + ueZ * scale / 10000.0; }
    /** UE Y axis = MC Z axis. */
    public double toMcZ(double ueY) { return origin.getZ() + ueY * scale / 10000.0; }

    @Override
    public String toString() {
        return String.format("Hologram[%d '%s' mode=%s scale=%.0f rot=(%.0f,%.0f,%.0f) origin=%s actors=%d]",
                id, name, renderMode, scale, rotX, rotY, rotZ, origin, umap.actors.size());
    }
}
