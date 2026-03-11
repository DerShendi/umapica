package net.shendi.umapica.umap;

/** Unreal Engine FBox – axis-aligned bounding box. */
public record FBox(FVector min, FVector max, boolean isValid) {

    public static final FBox EMPTY = new FBox(FVector.ZERO, FVector.ZERO, false);

    public FVector center() {
        return new FVector(
            (min.x() + max.x()) * 0.5,
            (min.y() + max.y()) * 0.5,
            (min.z() + max.z()) * 0.5
        );
    }

    public FVector extent() {
        return new FVector(
            (max.x() - min.x()) * 0.5,
            (max.y() - min.y()) * 0.5,
            (max.z() - min.z()) * 0.5
        );
    }

    /** Returns an FBox that encompasses both boxes. */
    public FBox encapsulate(FBox other) {
        if (!other.isValid) return this;
        if (!this.isValid) return other;
        return new FBox(
            new FVector(Math.min(min.x(), other.min.x()), Math.min(min.y(), other.min.y()), Math.min(min.z(), other.min.z())),
            new FVector(Math.max(max.x(), other.max.x()), Math.max(max.y(), other.max.y()), Math.max(max.z(), other.max.z())),
            true
        );
    }

    @Override
    public String toString() {
        return String.format("FBox(min=%s, max=%s)", min, max);
    }
}
