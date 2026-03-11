package net.shendi.umapica.umap;

/** Unreal Engine FVector – three 32-bit (or 64-bit for UE5) floats. */
public record FVector(double x, double y, double z) {

    public static final FVector ZERO = new FVector(0, 0, 0);
    public static final FVector ONE  = new FVector(1, 1, 1);

    /** Convert to a Minecraft block-space position given a scale factor (UE units per block). */
    public double toBlockX(double scale) { return x / scale; }
    public double toBlockY(double scale) { return z / scale; } // UE Z = MC Y
    public double toBlockZ(double scale) { return y / scale; }

    @Override
    public String toString() {
        return String.format("FVector(%.2f, %.2f, %.2f)", x, y, z);
    }
}
