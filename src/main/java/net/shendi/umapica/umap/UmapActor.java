package net.shendi.umapica.umap;

import org.jetbrains.annotations.Nullable;

/**
 * Represents a single placed actor inside a .umap package.
 * All spatial data is in Unreal Engine world-space units (1 unit = 1 cm).
 */
public class UmapActor {

    /** Short class name, e.g. "StaticMeshActor", "PointLight", "Brush". */
    public final String className;
    /** The actor's name as it appears in the export table. */
    public final String actorName;
    /** World-space transform (translation in UE centimetres). */
    public FTransform transform;
    /**
     * Bounding box in actor-local space.  In the common case this is derived
     * from the mesh bounds stored in the referenced .uasset, or estimated
     * from the component relative-location properties.
     */
    public FBox localBounds;
    /**
     * Soft-object path to the referenced StaticMesh, e.g.
     * "/Game/Environment/SM_Rock.SM_Rock".  Null for non-mesh actors.
     */
    @Nullable
    public String meshAssetPath;
    /**
     * When true the mesh is an EXPORT in the same .umap file (no separate .upk needed).
     * Used by {@link UmapPackage#loadMeshData} to skip the asset-file search.
     */
    public boolean meshInSameFile = false;
    /**
     * Mesh geometry loaded from the referenced .uasset.
     * Null until explicitly loaded by {@link UmapPackage#loadMeshData}.
     */
    @Nullable
    public UmapMeshData meshData;

    /** Rough material / surface colour hint extracted from the asset name (for voxel colouring). */
    public int hintColor = 0xFFAAAAAA; // light-grey default

    public UmapActor(String className, String actorName) {
        this.className   = className;
        this.actorName   = actorName;
        this.transform   = FTransform.IDENTITY;
        this.localBounds = new FBox(new FVector(-50, -50, -50), new FVector(50, 50, 50), true);
    }

    /**
     * World-space AABB, accounting for the actor transform's translation.
     * This is an approximation – the scale is applied but rotation is ignored for box computation.
     */
    public FBox worldBounds() {
        FVector t  = transform.translation();
        FVector s  = transform.scale3D();
        FVector bMin = localBounds.min();
        FVector bMax = localBounds.max();
        double sX = s.x(), sY = s.y(), sZ = s.z();
        return new FBox(
            new FVector(t.x() + bMin.x() * sX, t.y() + bMin.y() * sY, t.z() + bMin.z() * sZ),
            new FVector(t.x() + bMax.x() * sX, t.y() + bMax.y() * sY, t.z() + bMax.z() * sZ),
            true
        );
    }

    @Override
    public String toString() {
        return String.format("UmapActor[%s '%s' at %s]", className, actorName, transform.translation());
    }
}
