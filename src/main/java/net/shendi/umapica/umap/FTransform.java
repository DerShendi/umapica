package net.shendi.umapica.umap;

/** Unreal Engine FTransform – rotation + translation + scale. */
public record FTransform(FQuat rotation, FVector translation, FVector scale3D) {

    public static final FTransform IDENTITY =
            new FTransform(FQuat.IDENTITY, FVector.ZERO, FVector.ONE);

    @Override
    public String toString() {
        return String.format("FTransform(T=%s, R=%s, S=%s)", translation, rotation, scale3D);
    }
}
