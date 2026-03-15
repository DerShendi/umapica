package net.shendi.umapica.umap;

/**
 * Triangle mesh geometry extracted from a UE4/UE5 StaticMesh .uasset.
 *
 * <h3>Vertex layout</h3>
 * <ul>
 *   <li>{@link #positions} – flat float array: x0,y0,z0,  x1,y1,z1, …</li>
 *   <li>{@link #uvs}       – flat float array: u0,v0,  u1,v1, … (channel 0).
 *       May be {@code null} when not extracted.</li>
 * </ul>
 *
 * <h3>Sections / materials</h3>
 * Each {@link Section} maps a contiguous range of the index buffer to
 * a material slot and (optionally) a resolved texture resource location.
 */
public class UmapMeshData {

    /** Flat vertex position array: x0,y0,z0,  x1,y1,z1, … */
    public final float[] positions;
    /** Triangle index list: length = numTriangles × 3 */
    public final int[]   indices;
    /**
     * UV channel-0 per vertex: u0,v0, u1,v1, …
     * Length = {@code positions.length / 3 * 2}.  May be {@code null}.
     */
    public float[] uvs;
    /** Source bounds reported by the asset. */
    public final FBox    bounds;

    /**
     * Material sections.  May be empty when no section data was extracted.
     * Triangle {@code t} belongs to the section whose
     * {@code firstIndex ≤ t*3 < firstIndex+indexCount}.
     */
    public Section[] sections = new Section[0];

    // ------------------------------------------------------------------ //

    public UmapMeshData(float[] positions, int[] indices, FBox bounds) {
        this.positions = positions;
        this.indices   = indices;
        this.bounds    = bounds;
    }

    public int vertexCount()   { return positions.length / 3; }
    public int triangleCount() { return indices.length   / 3; }

    // ------------------------------------------------------------------ //

    /** A single mesh section bound to one material slot. */
    public static final class Section {
        /** First entry in {@link UmapMeshData#indices} that belongs to this section. */
        public final int firstIndex;
        /** Number of index entries in this section (= triangles × 3). */
        public final int indexCount;
        /** UE material-slot index (0 = first material). */
        public final int materialIndex;
        /**
         * Soft-object path of the material/texture, e.g.
         * {@code "M_MafiaBuilding_Brick"}, or {@code null} if unknown.
         */
        public final String materialPath;
        /**
         * Minecraft {@code ResourceLocation} string for the loaded texture,
         * e.g. {@code "umapica:textures/dynamic/abc123.png"}.
         * Set by {@code UmapTextureManager} after loading; may stay {@code null}.
         */
        public volatile String textureResourceId;

        public Section(int firstIndex, int indexCount, int materialIndex, String materialPath) {
            this.firstIndex   = firstIndex;
            this.indexCount   = indexCount;
            this.materialIndex = materialIndex;
            this.materialPath = materialPath;
        }
    }

    /**
     * Per-triangle section index, built lazily.
     * {@code sectionPerTriangle[t]} is the index into {@link #sections} that owns triangle {@code t},
     * or {@code -1} if the triangle isn't covered by any section.
     * This makes texture lookup O(1) per triangle instead of O(sections) per triangle.
     */
    private volatile int[] sectionPerTriangle;

    /**
     * Returns (building on first call) the per-triangle section index array.
     * Thread-safe: the array is published via a volatile write after construction.
     */
    public int[] getTriangleSectionIndex() {
        if (sections == null || sections.length == 0) return null;
        int[] cached = sectionPerTriangle;
        if (cached != null) return cached;

        int triCount = indices.length / 3;
        int[] map = new int[triCount];
        java.util.Arrays.fill(map, -1);
        for (int si = 0; si < sections.length; si++) {
            Section s = sections[si];
            int first = s.firstIndex / 3;
            int last  = first + s.indexCount / 3;
            for (int t = Math.max(0, first); t < Math.min(triCount, last); t++) {
                map[t] = si;
            }
        }
        sectionPerTriangle = map;
        return map;
    }

    @Override
    public String toString() {
        return String.format("UmapMeshData(verts=%d, tris=%d, sections=%d, uvs=%s, bounds=%s)",
                vertexCount(), triangleCount(), sections.length, (uvs != null ? "yes" : "no"), bounds);
    }
}
