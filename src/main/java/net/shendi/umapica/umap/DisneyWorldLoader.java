package net.shendi.umapica.umap;

import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Avalanche Software Meridian engine world ZIP archives
 * (Toy Story 3, Cars 2, Brave, etc.).
 *
 * <h3>Binary layout</h3>
 * <ul>
 *   <li>{@code *_0.vbuf} - vertex buffer: float32[N][5] = x, y, z, u, v  (stride = 20 bytes)</li>
 *   <li>{@code *_0.ibuf} - index buffer:  uint16[M] = GLOBAL vertex indices (3 per triangle)</li>
 * </ul>
 *
 * <h3>Coordinate system (Meridian raw, metres, right-hand Z-up)</h3>
 * <ul>
 *   <li>mer_x = East / West</li>
 *   <li>mer_y = depth (small, ~6 m for a building facade)</li>
 *   <li>mer_z = height (up-axis, e.g. 0..25 m)</li>
 * </ul>
 * Stored as UE left-hand Z-up centimetres for the renderer:
 * <ul>
 *   <li>pos[0] = ue_x =  mer_x * 100</li>
 *   <li>pos[1] = ue_y = -mer_y * 100  (handedness flip)</li>
 *   <li>pos[2] = ue_z =  mer_z * 100</li>
 * </ul>
 * {@code localToMc} then maps: MC_x = origin.x + ue_x * scale/10000,
 *                               MC_y = origin.y + ue_z * scale/10000,
 *                               MC_z = origin.z + ue_y * scale/10000.
 *
 * <h3>Triangle filtering</h3>
 * The ibuf contains ~2900 "connector" triangles with edges >= 49 m that span
 * completely disconnected mesh sections -- rendering them creates a giant blob.
 * All triangles with any edge longer than {@value #MAX_EDGE_M} m are discarded.
 * Real geometry has max edges <= 8 m; connectors jump straight to >= 49 m.
 */
public final class DisneyWorldLoader {

    /** Vertices with |coord| > this (metres) are sentinel/padding -- skip their triangles. */
    private static final float MAX_COORD_M = 100f;

    /**
     * Triangles with any edge longer than this (metres) are engine visibility
     * connectors -- discard them. Real geometry = max edge <= 8 m.
     */
    private static final float MAX_EDGE_M = 10f;

    /**
     * Skip a mesh section if its average triangle edge length exceeds this (metres).
     * The vbuf contains multiple LOD tiers for the same spatial regions.
     * LOD0 sections have avg edge ~0.7-1.4m for dense indoor/outdoor geometry.
     * Very coarse LOD tiers (avg > 2.0m) create overlapping lower-quality duplicates.
     */
    private static final float MAX_SECTION_AVG_EDGE_M = 2.0f;

    /**
     * Triangle-level Y-depth filter. In Meridian Z-up coords, Y is the depth axis
     * (front-to-back of the building). The exterior shell sits at Y in [-5, +5] m.
     * Interior geometry (rooms, corridors) goes to Y=20..75m and creates a
     * confusing overlapping mess in the map view. Discard any triangle whose
     * maximum-Y vertex exceeds this threshold.
     */
    private static final float MAX_TRI_DEPTH_M = 15f;

    /**
     * Skip a mesh section if more than this fraction of its raw vertices sit within
     * 10m of the ±{@value #MAX_COORD_M}m sentinel boundary.
     *
     * <p>Meridian packs dense "occlusion sphere" triangles near that boundary.
     * Adjacent sentinel vertices are only a few metres apart, so they pass the
     * per-triangle edge filter while still forming a hollow sphere in 3-D space.
     * Sections with high boundary-vert density are pure sphere artefacts and
     * must be discarded at the section level.
     *
     * <p>Empirically: real geometry = 0–10% boundary; artefact sphere = 15–80%.
     */
    private static final float MAX_SECTION_BOUNDARY_VERT_PCT = 0.15f;

    /**
     * Skip a mesh section if its Z (vertical height) extent exceeds this value.
     * Normal sections are ≤ 80m tall; extreme extents indicate stacked phantom geometry.
     */
    private static final float MAX_SECTION_Z_EXTENT_M = 80f;

    /**
     * Hard cap on the total number of surviving triangles per world.
     * Prevents pathological worlds (e.g. st_junkyard at 3.4M tris) from
     * consuming gigabytes of heap and crashing the client.
     * Sections are included whole; once the running total reaches this
     * limit no further sections are processed.
     */
    private static final int MAX_TOTAL_TRIS = 500_000;

    private DisneyWorldLoader() {}

    // ------------------------------------------------------------------ //
    //  Public entry point
    // ------------------------------------------------------------------ //

    /**
     * Reads a Meridian world ZIP and returns a {@link UmapPackage} with one mesh actor.
     *
     * @throws IOException if the zip is unreadable or contains no valid geometry
     */
    public static UmapPackage load(File zipFile) throws IOException {
        String worldName = zipFile.getName().replaceAll("(?i)\\.zip$", "");

        byte[] vbufData = null;
        byte[] ibufData = null;

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String base = baseName(entry.getName());
                if (vbufData == null && base.endsWith("_0.vbuf")) {
                    try (InputStream is = zf.getInputStream(entry)) { vbufData = readAll(is); }
                } else if (ibufData == null && base.endsWith("_0.ibuf")) {
                    try (InputStream is = zf.getInputStream(entry)) { ibufData = readAll(is); }
                }
            }
        }

        if (vbufData == null || ibufData == null) {
            // Some Meridian ZIPs contain only physics/script data (.bent, .oct, .ap, etc.)
            // and carry no renderable vbuf/ibuf geometry at all. Treat them as empty worlds
            // rather than errors so the load doesn't show as a failure in the log.
            String reason = vbufData == null ? "No _0.vbuf" : "No _0.ibuf";
            Umapica.LOGGER.info("[Umapica] Disney '{}': {} – no renderable geometry (physics/script-only ZIP)",
                    worldName, reason);
            return new UmapPackage(zipFile);  // empty package, 0 actors
        }

        UmapMeshData mesh = buildMesh(vbufData, ibufData, worldName);
        if (mesh == null || mesh.positions.length == 0)
            throw new IOException("No valid geometry in " + zipFile.getName());

        Umapica.LOGGER.info("[Umapica] Disney world '{}': {} verts {} tris bounds={}",
                worldName, mesh.vertexCount(), mesh.triangleCount(), mesh.bounds);

        UmapPackage pkg = new UmapPackage(zipFile);
        UmapActor actor = new UmapActor("DisneyWorld", worldName);
        actor.transform   = FTransform.IDENTITY;
        actor.localBounds = mesh.bounds;
        actor.meshData    = mesh;
        pkg.actors.add(actor);
        return pkg;
    }

    // ------------------------------------------------------------------ //
    //  Geometry extraction
    // ------------------------------------------------------------------ //

    private static @Nullable UmapMeshData buildMesh(byte[] vbuf, byte[] ibuf, String name) {
        final int STRIDE = 20;   // 5 x float32 per vertex: x, y, z, u, v

        // -- Step 1: read raw Meridian vertex data (metres) --
        int numVerts = vbuf.length / STRIDE;
        ByteBuffer vb = ByteBuffer.wrap(vbuf).order(ByteOrder.LITTLE_ENDIAN);
        float[] mx  = new float[numVerts];
        float[] my  = new float[numVerts];
        float[] mz  = new float[numVerts];
        float[] mu  = new float[numVerts];
        float[] mv  = new float[numVerts];
        boolean[] bad = new boolean[numVerts];

        for (int i = 0; i < numVerts; i++) {
            float x = vb.getFloat(), y = vb.getFloat(), z = vb.getFloat();
            float u = vb.getFloat(), v = vb.getFloat();
            mx[i] = x;  my[i] = y;  mz[i] = z;
            mu[i] = u;  mv[i] = 1f - v;
            bad[i] = !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                  || Math.abs(x) > MAX_COORD_M || Math.abs(y) > MAX_COORD_M || Math.abs(z) > MAX_COORD_M;
        }

        // -- Step 2: determine section size and number of sections --
        // The vbuf is a flat concatenation of N equal-size vertex sections.
        // The ibuf contains local uint16 indices (0..sectSize-1) that apply to
        // EVERY section; for section S the global vertex index = S*sectSize + localIdx.
        // We discover sectSize = max(ibuf_index) + 1.
        int numIbufIdx = ibuf.length / 2;
        int numTrisPerSection = numIbufIdx / 3;
        if (numTrisPerSection == 0) return null;

        ByteBuffer ib0 = ByteBuffer.wrap(ibuf).order(ByteOrder.LITTLE_ENDIAN);
        int[] localIdx = new int[numIbufIdx];
        int maxLocalIdx = 0;
        for (int i = 0; i < numIbufIdx; i++) {
            int v = Short.toUnsignedInt(ib0.getShort());
            localIdx[i] = v;
            if (v > maxLocalIdx) maxLocalIdx = v;
        }
        int sectSize   = maxLocalIdx + 1;          // e.g. 6352
        int numSections = numVerts / sectSize;      // e.g. 16
        Umapica.LOGGER.debug("[Umapica] Disney '{}': sectSize={} sections={} tris/sect={}",
                name, sectSize, numSections, numTrisPerSection);
        if (numSections == 0) return null;

        // -- Step 3: apply ibuf to every section, collect valid global triangles --
        // Upper bound on surviving triangles: sections * tris per section
        int maxGoodTris = numSections * numTrisPerSection;
        int[] orig0 = new int[maxGoodTris];
        int[] orig1 = new int[maxGoodTris];
        int[] orig2 = new int[maxGoodTris];
        int goodTris = 0;
        int skippedSentinel = 0, skippedGiant = 0;

        for (int s = 0; s < numSections; s++) {
            int offset = s * sectSize;

            // Compute section stats in a single pass: Z-extent and boundary-vert fraction.
            // Boundary verts are within 10m of the ±MAX_COORD_M sentinel boundary;
            // if too many are present the section contains sphere artefacts.
            final float BDRY = MAX_COORD_M - 10f;  // 90m
            int boundaryVerts = 0;
            float sMinZ = Float.MAX_VALUE, sMaxZ = -Float.MAX_VALUE;
            for (int i = 0; i < sectSize; i++) {
                int vi = offset + i;
                if (Math.abs(mx[vi]) > BDRY || Math.abs(my[vi]) > BDRY || Math.abs(mz[vi]) > BDRY) {
                    boundaryVerts++;
                }
                if (!bad[vi]) {
                    if (mz[vi] < sMinZ) sMinZ = mz[vi];
                    if (mz[vi] > sMaxZ) sMaxZ = mz[vi];
                }
            }
            float boundaryFrac = (float) boundaryVerts / sectSize;
            if (boundaryFrac > MAX_SECTION_BOUNDARY_VERT_PCT) {
                Umapica.LOGGER.debug("[Umapica] Disney '{}': sect {} skipped (boundary verts {}% > {}%)",
                        name, s, String.format("%.0f", boundaryFrac * 100),
                        (int)(MAX_SECTION_BOUNDARY_VERT_PCT * 100));
                continue;
            }
            float zExtent = sMaxZ - sMinZ;
            if (zExtent > MAX_SECTION_Z_EXTENT_M) {
                Umapica.LOGGER.debug("[Umapica] Disney '{}': sect {} skipped (Z-extent={} > {}m)",
                        name, s, String.format("%.1f", zExtent), (int) MAX_SECTION_Z_EXTENT_M);
                continue;
            }

            // Pre-pass: compute average edge length to detect lower-LOD sections.
            // LOD0 has avg ~0.7-1.1m; coarser LOD tiers have avg ~1.5-2.5m.
            // Only count edges below MAX_EDGE_M to exclude connector strips from the avg.
            double edgeSum = 0.0;
            int edgeCount = 0;
            for (int t = 0; t < numTrisPerSection; t++) {
                int i0 = localIdx[t * 3    ] + offset;
                int i1 = localIdx[t * 3 + 1] + offset;
                int i2 = localIdx[t * 3 + 2] + offset;
                if (i0 >= numVerts || i1 >= numVerts || i2 >= numVerts
                        || bad[i0] || bad[i1] || bad[i2]) continue;
                float e01 = (float) Math.sqrt(edgeLenSq(mx, my, mz, i0, i1));
                float e12 = (float) Math.sqrt(edgeLenSq(mx, my, mz, i1, i2));
                float e02 = (float) Math.sqrt(edgeLenSq(mx, my, mz, i0, i2));
                if (Math.max(e01, Math.max(e12, e02)) > MAX_EDGE_M) continue; // skip connector tris
                edgeSum += e01 + e12 + e02;
                edgeCount += 3;
            }
            if (edgeCount == 0) continue;
            float avgEdge = (float) (edgeSum / edgeCount);
            if (avgEdge > MAX_SECTION_AVG_EDGE_M) {
                Umapica.LOGGER.debug("[Umapica] Disney '{}': sect {} skipped (LOD, avgEdge={} > {}m)",
                        name, s, String.format("%.2f", avgEdge), MAX_SECTION_AVG_EDGE_M);
                continue;
            }

            for (int t = 0; t < numTrisPerSection; t++) {
                int i0 = localIdx[t * 3    ] + offset;
                int i1 = localIdx[t * 3 + 1] + offset;
                int i2 = localIdx[t * 3 + 2] + offset;

                // Filter A: out-of-range or sentinel vertices
                if (i0 >= numVerts || i1 >= numVerts || i2 >= numVerts
                        || bad[i0] || bad[i1] || bad[i2]) {
                    skippedSentinel++;
                    continue;
                }

                // Filter B: degenerate / giant connector edges
                if (edgeLenSq(mx, my, mz, i0, i1) > MAX_EDGE_M * MAX_EDGE_M
                 || edgeLenSq(mx, my, mz, i1, i2) > MAX_EDGE_M * MAX_EDGE_M
                 || edgeLenSq(mx, my, mz, i0, i2) > MAX_EDGE_M * MAX_EDGE_M) {
                    skippedGiant++;
                    continue;
                }

                // Filter C: interior geometry -- Y (depth) axis extends far into
                // the building in BOTH directions. Keep only the exterior shell.
                // Check abs(my) so interior going negative-Y is also caught.
                if (Math.abs(my[i0]) > MAX_TRI_DEPTH_M
                 || Math.abs(my[i1]) > MAX_TRI_DEPTH_M
                 || Math.abs(my[i2]) > MAX_TRI_DEPTH_M) {
                    continue;
                }

                orig0[goodTris] = i0;
                orig1[goodTris] = i1;
                orig2[goodTris] = i2;
                goodTris++;
            }

            // Hard cap: stop adding sections once we have enough triangles.
            if (goodTris >= MAX_TOTAL_TRIS) {
                Umapica.LOGGER.info("[Umapica] Disney '{}': triangle cap {} reached at sect {}/{}, stopping.",
                        name, MAX_TOTAL_TRIS, s + 1, numSections);
                break;
            }
        } // end section loop

        if (skippedSentinel > 0)
            Umapica.LOGGER.debug("[Umapica] Disney '{}': {} sentinel tris skipped", name, skippedSentinel);
        if (skippedGiant > 0)
            Umapica.LOGGER.debug("[Umapica] Disney '{}': {} giant tris skipped", name, skippedGiant);
        if (goodTris == 0) return null;

        Umapica.LOGGER.info("[Umapica] Disney '{}': {} tris survive across {} sections",
                name, goodTris, numSections);

        // -- Step 4: dense-remap to only used vertices --
        int[] oldToNew = new int[numVerts];
        Arrays.fill(oldToNew, -1);
        int newVtxCount = 0;
        for (int t = 0; t < goodTris; t++) {
            if (oldToNew[orig0[t]] < 0) oldToNew[orig0[t]] = newVtxCount++;
            if (oldToNew[orig1[t]] < 0) oldToNew[orig1[t]] = newVtxCount++;
            if (oldToNew[orig2[t]] < 0) oldToNew[orig2[t]] = newVtxCount++;
        }

        // -- Step 5: build final position / UV / index arrays --
        float[] posArr = new float[newVtxCount * 3];
        float[] uvArr  = new float[newVtxCount * 2];
        int[]   idxArr = new int[goodTris * 3];

        for (int oi = 0; oi < numVerts; oi++) {
            int ni = oldToNew[oi];
            if (ni < 0) continue;
            // Meridian metres -> UE left-hand Z-up centimetres
            posArr[ni * 3    ] =  mx[oi] * 100f;   // ue_x
            posArr[ni * 3 + 1] = -my[oi] * 100f;   // ue_y (negate for handedness)
            posArr[ni * 3 + 2] =  mz[oi] * 100f;   // ue_z
            uvArr [ni * 2    ] = mu[oi];
            uvArr [ni * 2 + 1] = mv[oi];
        }
        for (int t = 0; t < goodTris; t++) {
            idxArr[t * 3    ] = oldToNew[orig0[t]];
            idxArr[t * 3 + 1] = oldToNew[orig1[t]];
            idxArr[t * 3 + 2] = oldToNew[orig2[t]];
        }

        FBox bounds = computeBounds(posArr);
        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, bounds);
        mesh.uvs      = uvArr;
        mesh.sections = new UmapMeshData.Section[0];
        return mesh;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /** Squared distance between raw Meridian vertices a and b (metres squared). */
    private static float edgeLenSq(float[] x, float[] y, float[] z, int a, int b) {
        float dx = x[a] - x[b], dy = y[a] - y[b], dz = z[a] - z[b];
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns the lowercase filename component of a zip entry path. */
    private static String baseName(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        return slash >= 0 ? lower.substring(slash + 1) : lower;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static FBox computeBounds(float[] pos) {
        if (pos.length < 3) return FBox.EMPTY;
        float mnX = pos[0], mnY = pos[1], mnZ = pos[2];
        float mxX = mnX, mxY = mnY, mxZ = mnZ;
        for (int i = 3; i < pos.length; i += 3) {
            if (pos[i    ] < mnX) mnX = pos[i    ]; else if (pos[i    ] > mxX) mxX = pos[i    ];
            if (pos[i + 1] < mnY) mnY = pos[i + 1]; else if (pos[i + 1] > mxY) mxY = pos[i + 1];
            if (pos[i + 2] < mnZ) mnZ = pos[i + 2]; else if (pos[i + 2] > mxZ) mxZ = pos[i + 2];
        }
        return new FBox(new FVector(mnX, mnY, mnZ), new FVector(mxX, mxY, mxZ), true);
    }
}
