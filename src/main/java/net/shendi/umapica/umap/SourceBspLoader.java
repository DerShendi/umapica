package net.shendi.umapica.umap;

import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Loads Valve Source Engine BSP map files and returns a {@link UmapPackage}
 * with the world geometry as a single mesh actor.
 *
 * <h3>Supported BSP versions (Source 1)</h3>
 * <ul>
 *   <li>v17 – early HL2 beta</li>
 *   <li>v19 – Counter-Strike: Source, early HL2 maps</li>
 *   <li>v20 – Half-Life 2 (all episodes), Team Fortress 2, Portal, Left 4 Dead 1 &amp; 2,
 *             Portal 2, Payday 2, Garry's Mod, Insurgency, …</li>
 *   <li>v21 – Counter-Strike: Global Offensive</li>
 *   <li>v25 – Titanfall (partial; same lump layout)</li>
 * </ul>
 * CS2 / Source 2 (RMAP/VBSP2, v29+) is <em>not</em> supported.
 * GoldSrc (HL1/CS 1.6) BSP v30 uses a completely different format and is also not supported.
 *
 * <h3>Geometry extracted</h3>
 * <ul>
 *   <li>World brush faces (lump 7) – fan-triangulated polygons</li>
 *   <li>Displacement surfaces (lump 26 + 33) – triangulated grids for terrain</li>
 * </ul>
 * Faces flagged NODRAW, SKY, HINT, SKIP (or referencing TOOLS/TOOLSNODRAW etc.)
 * are discarded.  Trigger/clip geometry is also skipped.
 *
 * <h3>Coordinate mapping (Source → UE cm)</h3>
 * Source uses right-hand Z-up metre-like units (52.49 units ≈ 1 m):
 * <pre>
 *   ue_x =  src_x × 1.905   (East, same axis)
 *   ue_y = -src_y × 1.905   (negate: right-hand → UE left-hand)
 *   ue_z =  src_z × 1.905   (Z-up, same direction)
 * </pre>
 * {@code localToMc} then maps: MC_x = ue_x, MC_y = ue_z, MC_z = ue_y.
 */
public final class SourceBspLoader {

    // ── BSP lump indices ─────────────────────────────────────────────────
    private static final int LUMP_TEXDATA              =  2;
    private static final int LUMP_VERTEXES             =  3;
    private static final int LUMP_TEXINFO              =  6;
    private static final int LUMP_FACES                =  7;
    private static final int LUMP_EDGES                = 12;
    private static final int LUMP_SURFEDGES            = 13;
    private static final int LUMP_MODELS               = 14;
    private static final int LUMP_DISPINFO             = 26;
    private static final int LUMP_ORIGINALFACES        = 27;
    private static final int LUMP_DISP_VERTS           = 33;
    private static final int LUMP_TEXDATA_STRING_TABLE = 43;
    private static final int LUMP_TEXDATA_STRING_DATA  = 44;

    // ── texinfo surface flags ─────────────────────────────────────────────
    private static final int SURF_SKY2D  = 0x0002;
    private static final int SURF_SKY    = 0x0004;
    private static final int SURF_NODRAW = 0x0080;
    private static final int SURF_HINT   = 0x0100;
    private static final int SURF_SKIP   = 0x0200;

    /** 1 Source unit = 1.905 cm  (52.49 units ≈ 1 m, 1 unit ≈ 0.75 inch). */
    private static final float SRC_TO_CM = 1.905f;

    /** Max triangles per map to prevent OOM on enormous maps. */
    private static final int MAX_TRIS = 1_000_000;

    /** LZMA magic that may appear at the start of a compressed lump. */
    private static final int LZMA_MAGIC = 0x414D5A4C; // "LZMA" LE

    private static final int LUMP_COUNT    = 64;
    private static final int LUMP_HDR_SIZE = 16;    // per lump entry
    private static final int BSP_HDR_SIZE  = 8 + LUMP_COUNT * LUMP_HDR_SIZE; // 1036

    private static final int FACE_STRIDE     = 56;
    private static final int TEXINFO_STRIDE  = 72;
    private static final int TEXDATA_STRIDE  = 32;
    private static final int VERTEX_STRIDE   = 12;  // 3 × float
    private static final int EDGE_STRIDE     =  4;  // 2 × uint16
    private static final int SURFEDGE_STRIDE =  4;  // int32
    private static final int DISPINFO_STRIDE = 176;
    private static final int DISPVERT_STRIDE =  20;  // vec(12) + dist(4) + alpha(4)

    private SourceBspLoader() {}

    // ─────────────────────────────────────────────────────────────────────
    //  Public entry point
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads a Source Engine {@code .bsp} file and returns a {@link UmapPackage}
     * with one actor containing the full-world mesh.
     *
     * @throws IOException if the file cannot be read or is not a supported Source BSP
     */
    public static UmapPackage load(File bspFile) throws IOException {
        String mapName = bspFile.getName().replaceAll("(?i)\\.bsp$", "");

        try (RandomAccessFile raf = new RandomAccessFile(bspFile, "r")) {
            // ── Validate header ──────────────────────────────────────────
            byte[] hdr = new byte[8];
            raf.readFully(hdr);
            ByteBuffer hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            int magic   = hb.getInt();
            int version = hb.getInt();

            if (magic != 0x50534256) { // "VBSP" in little-endian
                throw new IOException(bspFile.getName() + " is not a Source BSP (bad magic 0x"
                        + Integer.toHexString(magic) + ")");
            }
            if (version < 17 || version > 27) {
                // CS2 is v29+ with different format; GoldSrc is v30 with completely different format
                throw new IOException(bspFile.getName() + " has unsupported BSP version " + version
                        + " (supported: 17–27 Source 1)");
            }
            Umapica.LOGGER.info("[Umapica] Source BSP '{}': version {}", mapName, version);

            // ── Read lump directory ──────────────────────────────────────
            int[] lumpOff = new int[LUMP_COUNT];
            int[] lumpLen = new int[LUMP_COUNT];
            for (int i = 0; i < LUMP_COUNT; i++) {
                byte[] le = new byte[LUMP_HDR_SIZE];
                raf.readFully(le);
                ByteBuffer lb = ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN);
                lumpOff[i] = lb.getInt();
                lumpLen[i] = lb.getInt();
                lb.getInt(); // lump version (ignored)
                lb.getInt(); // fourCC / uncompressed size
            }

            // ── Read all needed lumps into memory ─────────────────────────
            byte[] vBuf  = readLump(raf, lumpOff, lumpLen, LUMP_VERTEXES,             "vertices");
            byte[] eBuf  = readLump(raf, lumpOff, lumpLen, LUMP_EDGES,                "edges");
            byte[] seBuf = readLump(raf, lumpOff, lumpLen, LUMP_SURFEDGES,            "surfedges");
            byte[] fBuf  = readLump(raf, lumpOff, lumpLen, LUMP_FACES,                "faces");
            byte[] tiBuf = readLump(raf, lumpOff, lumpLen, LUMP_TEXINFO,              "texinfo");
            byte[] tdBuf = readLump(raf, lumpOff, lumpLen, LUMP_TEXDATA,              "texdata");
            byte[] stTab = readLump(raf, lumpOff, lumpLen, LUMP_TEXDATA_STRING_TABLE, "texdata_string_table");
            byte[] stDat = readLump(raf, lumpOff, lumpLen, LUMP_TEXDATA_STRING_DATA,  "texdata_string_data");
            byte[] diBuf = readLump(raf, lumpOff, lumpLen, LUMP_DISPINFO,             "dispinfo");
            byte[] dvBuf = readLump(raf, lumpOff, lumpLen, LUMP_DISP_VERTS,          "disp_verts");

            if (vBuf == null || eBuf == null || seBuf == null || fBuf == null)
                throw new IOException("Required BSP lumps missing from " + bspFile.getName());

            return buildPackage(bspFile, mapName, vBuf, eBuf, seBuf, fBuf,
                    tiBuf, tdBuf, stTab, stDat, diBuf, dvBuf);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Geometry extraction
    // ─────────────────────────────────────────────────────────────────────

    private static UmapPackage buildPackage(
            File bspFile, String mapName,
            byte[] vBuf, byte[] eBuf, byte[] seBuf, byte[] fBuf,
            @Nullable byte[] tiBuf, @Nullable byte[] tdBuf,
            @Nullable byte[] stTab, @Nullable byte[] stDat,
            @Nullable byte[] diBuf, @Nullable byte[] dvBuf) throws IOException {

        // ── Parsed arrays ────────────────────────────────────────────────
        int nVerts     = vBuf.length  / VERTEX_STRIDE;
        int nEdges     = eBuf.length  / EDGE_STRIDE;
        int nSurfedges = seBuf.length / SURFEDGE_STRIDE;
        int nFaces     = fBuf.length  / FACE_STRIDE;
        int nTexinfo   = tiBuf != null ? tiBuf.length / TEXINFO_STRIDE : 0;
        int nTexdata   = tdBuf != null ? tdBuf.length / TEXDATA_STRIDE : 0;

        ByteBuffer vb  = wrap(vBuf);
        ByteBuffer eb  = wrap(eBuf);
        ByteBuffer seb = wrap(seBuf);
        ByteBuffer fb  = wrap(fBuf);

        // ── Build texinfo hidden-flags lookup ─────────────────────────────
        boolean[] texHidden = buildTexHiddenFlags(tiBuf, tdBuf, stTab, stDat, nTexinfo, nTexdata);

        // ── Vertex positions (src units → UE cm) ─────────────────────────
        float[] vx = new float[nVerts];
        float[] vy = new float[nVerts];
        float[] vz = new float[nVerts];
        for (int i = 0; i < nVerts; i++) {
            float sx = vb.getFloat();
            float sy = vb.getFloat();
            float sz = vb.getFloat();
            vx[i] =  sx * SRC_TO_CM;
            vy[i] = -sy * SRC_TO_CM;
            vz[i] =  sz * SRC_TO_CM;
        }

        // ── Edges: each is 2 × uint16 vertex indices ─────────────────────
        int[] edgeV0 = new int[nEdges];
        int[] edgeV1 = new int[nEdges];
        for (int i = 0; i < nEdges; i++) {
            edgeV0[i] = eb.getShort() & 0xFFFF;
            edgeV1[i] = eb.getShort() & 0xFFFF;
        }

        // ── Surfedges: signed int32 index into edges ──────────────────────
        int[] surfedges = new int[nSurfedges];
        for (int i = 0; i < nSurfedges; i++) surfedges[i] = seb.getInt();

        // ── Collect triangles (exact pre-scan) ───────────────────────────
        // Source BSP faces can have very many edges (observed up to 32 in cs_assault).
        // Fan-triangulating an n-gon yields (n-2) triangles, so we must pre-scan.
        int estimatedTris = 0;
        for (int fi = 0; fi < nFaces; fi++) {
            int ne = leInt16s(fBuf, fi * FACE_STRIDE + 8);
            if (ne >= 3) estimatedTris += ne - 2;
        }
        estimatedTris = Math.min(estimatedTris, MAX_TRIS) + 8; // +8 safety margin
        int[] tri0 = new int[estimatedTris];
        int[] tri1 = new int[estimatedTris];
        int[] tri2 = new int[estimatedTris];
        int nTris  = 0;

        // ── Process regular faces ─────────────────────────────────────────
        // Faces we've already handled via dispinfo (skip when iterating faces again)
        boolean[] faceIsDisp = new boolean[nFaces];

        int skippedHidden = 0, skippedBadRef = 0;

        for (int fi = 0; fi < nFaces && nTris < MAX_TRIS; fi++) {
            int base = fi * FACE_STRIDE;
            // planenum(2) side(1) onNode(1) firstedge(4) numedges(2) texinfo(2) dispinfo(2) sfvid(2)
            int firstedge = leInt32(fBuf, base + 4);
            int numedges  = leInt16s(fBuf, base + 8);
            int texinfo   = leInt16s(fBuf, base + 10);
            int dispinfo  = leInt16s(fBuf, base + 12);

            if (dispinfo >= 0) { faceIsDisp[fi] = true; continue; } // handled below

            if (numedges < 3) continue;

            // Check texinfo hidden flag
            if (texinfo >= 0 && texinfo < texHidden.length && texHidden[texinfo]) {
                skippedHidden++;
                continue;
            }

            // Resolve face vertices via surfedge chain
            if (firstedge < 0 || firstedge + numedges > nSurfedges) {
                skippedBadRef++;
                continue;
            }

            int v0 = surfedgeVertex(surfedges, edgeV0, edgeV1, firstedge);
            for (int k = 1; k < numedges - 1 && nTris < tri0.length; k++) {
                int vA = surfedgeVertex(surfedges, edgeV0, edgeV1, firstedge + k);
                int vB = surfedgeVertex(surfedges, edgeV0, edgeV1, firstedge + k + 1);
                if (v0 >= nVerts || vA >= nVerts || vB >= nVerts) { skippedBadRef++; continue; }
                tri0[nTris] = v0;
                tri1[nTris] = vA;
                tri2[nTris] = vB;
                nTris++;
            }
        }

        Umapica.LOGGER.debug("[Umapica] Source '{}': regular faces → {} tris (hidden={}, badref={})",
                mapName, nTris, skippedHidden, skippedBadRef);

        // ── Process displacement surfaces ─────────────────────────────────
        if (diBuf != null && diBuf.length > 0 && dvBuf != null && dvBuf.length > 0) {
            int nDisp = diBuf.length / DISPINFO_STRIDE;
            int nDispVerts = dvBuf.length / DISPVERT_STRIDE;

            // We may need more triangle slots; rebuild arrays if needed
            int maxDispTris = nDisp * (16 * 16 * 2); // power=4 worst case
            if (nTris + maxDispTris > tri0.length) {
                tri0 = Arrays.copyOf(tri0, nTris + maxDispTris);
                tri1 = Arrays.copyOf(tri1, nTris + maxDispTris);
                tri2 = Arrays.copyOf(tri2, nTris + maxDispTris);
            }

            // We'll add displacement vertices appended after BSP vertices
            // Build a list of extra positions (displacement verts)
            List<float[]> extraPos = new ArrayList<>();

            ByteBuffer dib = wrap(diBuf);
            ByteBuffer dvb = wrap(dvBuf);

            int dispTrisAdded = 0;

            for (int di = 0; di < nDisp && nTris < MAX_TRIS; di++) {
                int base = di * DISPINFO_STRIDE;
                // startPosition: float[3] at base+0
                float spx = leFloat(diBuf, base + 0);
                float spy = leFloat(diBuf, base + 4);
                float spz = leFloat(diBuf, base + 8);
                int dispVertStart = leInt32(diBuf, base + 12);
                int power         = leInt32(diBuf, base + 20);
                // MapFace is uint16 at offset 36 (NOT int32 — only 2 bytes wide)
                int mapFace       = leUInt16(diBuf, base + 36);

                if (power < 1 || power > 4) continue;
                if (mapFace < 0 || mapFace >= nFaces) continue;

                int dim = (1 << power) + 1; // e.g. power=2 → 5

                // Get the 4 corner vertices of the face
                int fBase     = mapFace * FACE_STRIDE;
                int firstedge = leInt32(fBuf, fBase + 4);
                int numedges  = leInt16s(fBuf, fBase + 8);
                if (numedges != 4 || firstedge < 0 || firstedge + 4 > nSurfedges) continue;

                int[] corners = new int[4];
                for (int k = 0; k < 4; k++)
                    corners[k] = surfedgeVertex(surfedges, edgeV0, edgeV1, firstedge + k);
                boolean badCorner = false;
                for (int c : corners) if (c >= nVerts) { badCorner = true; break; }
                if (badCorner) continue;

                // Find which corner matches startPosition (determines winding rotation)
                int startCorner = 0;
                float bestDist = Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    int c = corners[k];
                    float dx = vx[c] / SRC_TO_CM - spx;
                    float dy = -vy[c] / SRC_TO_CM - spy; // undo our Y-flip
                    float dz = vz[c] / SRC_TO_CM - spz;
                    float dist = dx*dx + dy*dy + dz*dz;
                    if (dist < bestDist) { bestDist = dist; startCorner = k; }
                }

                // Rotate corners so startCorner is index 0
                int[] c = new int[4];
                for (int k = 0; k < 4; k++) c[k] = corners[(startCorner + k) & 3];

                // Build displacement grid positions
                // corner order: c[0]=bottom-left, c[1]=bottom-right, c[2]=top-right, c[3]=top-left
                // grid: i=row (0..dim-1), j=col (0..dim-1)
                int baseVtx = nVerts + extraPos.size();
                for (int i = 0; i < dim; i++) {
                    float t = (float) i / (dim - 1);
                    // Edge from c[0]→c[3] and c[1]→c[2]
                    float ex0 = lerp(vx[c[0]], vx[c[3]], t);
                    float ey0 = lerp(vy[c[0]], vy[c[3]], t);
                    float ez0 = lerp(vz[c[0]], vz[c[3]], t);
                    float ex1 = lerp(vx[c[1]], vx[c[2]], t);
                    float ey1 = lerp(vy[c[1]], vy[c[2]], t);
                    float ez1 = lerp(vz[c[1]], vz[c[2]], t);
                    for (int j = 0; j < dim; j++) {
                        float s = (float) j / (dim - 1);
                        float bx = lerp(ex0, ex1, s);
                        float by = lerp(ey0, ey1, s);
                        float bz = lerp(ez0, ez1, s);

                        // Read displacement delta for this vertex
                        int dvIdx = dispVertStart + i * dim + j;
                        float[] dv = new float[]{0,0,0};
                        if (dvIdx < nDispVerts) {
                            // dDispVert: vec(3f) dist(f) alpha(f) = 20 bytes
                            float dvecX =  leFloat(dvBuf, dvIdx * DISPVERT_STRIDE + 0);
                            float dvecY = -leFloat(dvBuf, dvIdx * DISPVERT_STRIDE + 4); // flip Y
                            float dvecZ =  leFloat(dvBuf, dvIdx * DISPVERT_STRIDE + 8);
                            float dist  =  leFloat(dvBuf, dvIdx * DISPVERT_STRIDE + 12);
                            dv[0] = dvecX * dist * SRC_TO_CM;
                            dv[1] = dvecY * dist * SRC_TO_CM;
                            dv[2] = dvecZ * dist * SRC_TO_CM;
                        }
                        extraPos.add(new float[]{bx + dv[0], by + dv[1], bz + dv[2]});
                    }
                }

                // Triangulate the (dim-1) × (dim-1) quad grid
                for (int i = 0; i < dim - 1 && nTris < MAX_TRIS; i++) {
                    for (int j = 0; j < dim - 1 && nTris < MAX_TRIS; j++) {
                        int vTL = baseVtx + i * dim + j;
                        int vTR = baseVtx + i * dim + j + 1;
                        int vBL = baseVtx + (i+1) * dim + j;
                        int vBR = baseVtx + (i+1) * dim + j + 1;
                        // Checkerboard diagonal for better lighting continuity
                        if (((i + j) & 1) == 0) {
                            tri0[nTris]=vTL; tri1[nTris]=vTR; tri2[nTris]=vBL; nTris++;
                            tri0[nTris]=vTR; tri1[nTris]=vBR; tri2[nTris]=vBL; nTris++;
                        } else {
                            tri0[nTris]=vTL; tri1[nTris]=vTR; tri2[nTris]=vBR; nTris++;
                            tri0[nTris]=vTL; tri1[nTris]=vBR; tri2[nTris]=vBL; nTris++;
                        }
                        dispTrisAdded += 2;
                    }
                }
            }

            // Merge extra displacement vertices into the main arrays
            if (!extraPos.isEmpty()) {
                int oldN = nVerts;
                int newN = oldN + extraPos.size();
                vx = Arrays.copyOf(vx, newN);
                vy = Arrays.copyOf(vy, newN);
                vz = Arrays.copyOf(vz, newN);
                for (int i = 0; i < extraPos.size(); i++) {
                    float[] p = extraPos.get(i);
                    vx[oldN + i] = p[0];
                    vy[oldN + i] = p[1];
                    vz[oldN + i] = p[2];
                }
                nVerts = newN;
            }

            Umapica.LOGGER.debug("[Umapica] Source '{}': displacement surfaces → {} tris", mapName, dispTrisAdded);
        }

        if (nTris == MAX_TRIS)
            Umapica.LOGGER.info("[Umapica] Source '{}': triangle cap {} reached", mapName, MAX_TRIS);

        if (nTris == 0)
            throw new IOException("No renderable geometry found in " + bspFile.getName());

        // ── Dense-remap to only referenced vertices ───────────────────────
        int[] oldToNew = new int[nVerts];
        Arrays.fill(oldToNew, -1);
        int newVtxCount = 0;
        for (int t = 0; t < nTris; t++) {
            if (oldToNew[tri0[t]] < 0) oldToNew[tri0[t]] = newVtxCount++;
            if (oldToNew[tri1[t]] < 0) oldToNew[tri1[t]] = newVtxCount++;
            if (oldToNew[tri2[t]] < 0) oldToNew[tri2[t]] = newVtxCount++;
        }

        float[] posArr = new float[newVtxCount * 3];
        int[]   idxArr = new int[nTris * 3];

        for (int oi = 0; oi < nVerts; oi++) {
            int ni = oldToNew[oi];
            if (ni < 0) continue;
            posArr[ni * 3    ] = vx[oi];
            posArr[ni * 3 + 1] = vy[oi];
            posArr[ni * 3 + 2] = vz[oi];
        }
        for (int t = 0; t < nTris; t++) {
            idxArr[t * 3    ] = oldToNew[tri0[t]];
            idxArr[t * 3 + 1] = oldToNew[tri1[t]];
            idxArr[t * 3 + 2] = oldToNew[tri2[t]];
        }

        FBox bounds = computeBounds(posArr);
        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, bounds);
        mesh.sections = new UmapMeshData.Section[0];

        Umapica.LOGGER.info("[Umapica] Source BSP '{}': {} verts {} tris bounds={}",
                mapName, newVtxCount, nTris, bounds);

        UmapPackage pkg   = new UmapPackage(bspFile);
        UmapActor actor   = new UmapActor("SourceMap", mapName);
        actor.transform   = FTransform.IDENTITY;
        actor.localBounds = bounds;
        actor.meshData    = mesh;
        pkg.actors.add(actor);
        return pkg;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Texture hidden-flag lookup
    // ─────────────────────────────────────────────────────────────────────

    private static boolean[] buildTexHiddenFlags(
            @Nullable byte[] tiBuf, @Nullable byte[] tdBuf,
            @Nullable byte[] stTab, @Nullable byte[] stDat,
            int nTexinfo, int nTexdata) {

        if (tiBuf == null || nTexinfo == 0) return new boolean[0];

        boolean[] hidden = new boolean[nTexinfo];

        // Build texture-name array from texdata + string table/data
        String[] texNames = new String[nTexdata];
        if (tdBuf != null && stTab != null && stDat != null) {
            // append sentinel null byte so index searches always terminate
            byte[] stDatZ = Arrays.copyOf(stDat, stDat.length + 1);
            for (int i = 0; i < nTexdata; i++) {
                int stid  = leInt32(tdBuf, i * TEXDATA_STRIDE + 12);
                if (stid < 0 || stid * 4 + 4 > stTab.length) continue;
                int stoff = leInt32(stTab, stid * 4);
                if (stoff < 0 || stoff >= stDatZ.length) continue;
                int end = stoff;
                while (end < stDatZ.length && stDatZ[end] != 0) end++;
                texNames[i] = new String(stDatZ, stoff, end - stoff, java.nio.charset.StandardCharsets.US_ASCII)
                        .toUpperCase(java.util.Locale.ROOT);
            }
        }

        for (int i = 0; i < nTexinfo; i++) {
            int flags   = leInt32(tiBuf, i * TEXINFO_STRIDE + 64);
            int texdata = leInt32(tiBuf, i * TEXINFO_STRIDE + 68);

            if ((flags & (SURF_NODRAW | SURF_SKY | SURF_SKY2D | SURF_HINT | SURF_SKIP)) != 0) {
                hidden[i] = true;
                continue;
            }
            // Also filter by texture name if available
            if (texdata >= 0 && texdata < nTexdata && texNames[texdata] != null) {
                String n = texNames[texdata];
                if (n.startsWith("TOOLS/") || n.startsWith("TRIGGER") || n.startsWith("CLIP")
                        || n.contains("INVIS") || n.contains("NODRAW") || n.contains("SKYBOX")
                        || n.startsWith("SKY") || n.startsWith("HINT") || n.startsWith("SKIP")) {
                    hidden[i] = true;
                }
            }
        }
        return hidden;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the first vertex of the surfedge at position {@code seIdx}.
     * If the surfedge is positive, returns v0 of the edge; if negative, returns v1.
     */
    private static int surfedgeVertex(int[] surfedges, int[] ev0, int[] ev1, int seIdx) {
        int se = surfedges[seIdx];
        return se >= 0 ? ev0[se] : ev1[-se];
    }

    /** Reads a lump into a new byte array; returns null if the lump is empty or LZMA compressed. */
    private static @Nullable byte[] readLump(
            RandomAccessFile raf, int[] offsets, int[] lengths, int lumpIdx, String name)
            throws IOException {
        if (lumpIdx >= LUMP_COUNT) return null;
        int off = offsets[lumpIdx];
        int len = lengths[lumpIdx];
        if (len <= 0 || off <= 0) return null;

        raf.seek(off);
        byte[] data = new byte[len];
        raf.readFully(data);

        // Detect LZMA magic ("LZMA" = 0x4C5A4D41)
        if (len >= 4 && (data[0]&0xFF)==0x4C && (data[1]&0xFF)==0x5A
                && (data[2]&0xFF)==0x4D && (data[3]&0xFF)==0x41) {
            Umapica.LOGGER.warn("[Umapica] Source BSP: lump {} ({}) is LZMA-compressed – skipping",
                    lumpIdx, name);
            return null;
        }
        return data;
    }

    private static ByteBuffer wrap(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int leInt32(byte[] d, int off) {
        return (d[off]&0xFF) | ((d[off+1]&0xFF)<<8) | ((d[off+2]&0xFF)<<16) | ((d[off+3]&0xFF)<<24);
    }
    private static int leInt16s(byte[] d, int off) {
        return (short)((d[off]&0xFF) | ((d[off+1]&0xFF)<<8));
    }
    private static int leUInt16(byte[] d, int off) {
        return (d[off]&0xFF) | ((d[off+1]&0xFF)<<8);
    }
    private static float leFloat(byte[] d, int off) {
        return Float.intBitsToFloat(leInt32(d, off));
    }
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static FBox computeBounds(float[] pos) {
        if (pos.length < 3) return FBox.EMPTY;
        float mnX=pos[0], mnY=pos[1], mnZ=pos[2];
        float mxX=mnX, mxY=mnY, mxZ=mnZ;
        for (int i = 3; i < pos.length; i += 3) {
            if (pos[i  ]<mnX) mnX=pos[i  ]; else if (pos[i  ]>mxX) mxX=pos[i  ];
            if (pos[i+1]<mnY) mnY=pos[i+1]; else if (pos[i+1]>mxY) mxY=pos[i+1];
            if (pos[i+2]<mnZ) mnZ=pos[i+2]; else if (pos[i+2]>mxZ) mxZ=pos[i+2];
        }
        return new FBox(new FVector(mnX, mnY, mnZ), new FVector(mxX, mxY, mxZ), true);
    }
}
