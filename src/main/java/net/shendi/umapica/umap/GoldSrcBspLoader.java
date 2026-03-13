package net.shendi.umapica.umap;

import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Loads <b>GoldSrc engine</b> BSP v30 files — the format used by
 * Half-Life 1, Counter-Strike 1.6, Counter-Strike: Condition Zero,
 * Half-Life: Blue Shift, Half-Life: Opposing Force, Day of Defeat, and
 * other GoldSrc mods.
 *
 * <h3>File identification</h3>
 * GoldSrc BSP does <em>not</em> start with a magic string.  Instead the first
 * 4 bytes are the version integer (little-endian 30 = {@code 0x1E000000}).
 *
 * <h3>Header layout</h3>
 * <pre>
 *   [0]   int32  version = 30
 *   [4]   lump_t lumps[15]   // 15 × 8 bytes  (offset + length)
 * </pre>
 * Total header: 4 + 15 × 8 = 124 bytes.
 *
 * <h3>Key lump IDs</h3>
 * <ul>
 *   <li>2: TEXTURES (miptex lump – contains texture names)</li>
 *   <li>3: VERTEXES (float[3] per vertex)</li>
 *   <li>6: TEXINFO  (40 bytes each)</li>
 *   <li>7: FACES    (20 bytes each)</li>
 *   <li>12: EDGES   (4 bytes, 2 × uint16)</li>
 *   <li>13: SURFEDGES (4 bytes, signed int32)</li>
 * </ul>
 *
 * <h3>Coordinate mapping</h3>
 * GoldSrc uses the same right-hand Z-up unit system as Source Engine
 * (~52.49 units per metre):
 * <pre>
 *   ue_x =  gs_x × 1.905
 *   ue_y = -gs_y × 1.905
 *   ue_z =  gs_z × 1.905
 * </pre>
 */
public final class GoldSrcBspLoader {

    public static final int VERSION = 30;

    // ── Lump indices ──────────────────────────────────────────────────────
    private static final int LUMP_TEXTURES  =  2;
    private static final int LUMP_VERTEXES  =  3;
    private static final int LUMP_TEXINFO   =  6;
    private static final int LUMP_FACES     =  7;
    private static final int LUMP_EDGES     = 12;
    private static final int LUMP_SURFEDGES = 13;
    private static final int LUMP_COUNT     = 15;

    // ── TexInfo surface flags ─────────────────────────────────────────────
    private static final int SURF_SKY     = 0x04;
    private static final int SURF_SPECIAL = 0x20;

    /** 1 GoldSrc unit = 1.905 cm  (same scale as Source Engine). */
    private static final float GS_TO_CM = 1.905f;

    /** Hard cap on triangle count to prevent OOM on mods with huge maps. */
    private static final int MAX_TRIS = 1_000_000;

    // ── Struct strides ────────────────────────────────────────────────────
    private static final int FACE_STRIDE     = 20;
    private static final int TEXINFO_STRIDE  = 40;
    private static final int VERTEX_STRIDE   = 12;
    private static final int EDGE_STRIDE     =  4;
    private static final int SURFEDGE_STRIDE =  4;

    private GoldSrcBspLoader() {}

    // ─────────────────────────────────────────────────────────────────────
    //  Public entry point
    // ─────────────────────────────────────────────────────────────────────

    public static UmapPackage load(File bspFile) throws IOException {
        String mapName = bspFile.getName().replaceAll("(?i)\\.bsp$", "");

        try (RandomAccessFile raf = new RandomAccessFile(bspFile, "r")) {
            // ── Validate header ───────────────────────────────────────────
            byte[] hdr = new byte[4];
            raf.readFully(hdr);
            int version = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (version != VERSION) {
                throw new IOException(bspFile.getName() + " is not a GoldSrc BSP (version=" + version + ", expected 30)");
            }
            Umapica.LOGGER.info("[Umapica] GoldSrc BSP '{}': version {}", mapName, version);

            // ── Read 15 lump entries (8 bytes each: offset + length) ──────
            int[] lumpOff = new int[LUMP_COUNT];
            int[] lumpLen = new int[LUMP_COUNT];
            for (int i = 0; i < LUMP_COUNT; i++) {
                byte[] le = new byte[8];
                raf.readFully(le);
                ByteBuffer lb = ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN);
                lumpOff[i] = lb.getInt();
                lumpLen[i] = lb.getInt();
            }

            // ── Read our lumps ────────────────────────────────────────────
            byte[] vBuf  = readLump(raf, lumpOff, lumpLen, LUMP_VERTEXES,  "vertexes");
            byte[] eBuf  = readLump(raf, lumpOff, lumpLen, LUMP_EDGES,     "edges");
            byte[] seBuf = readLump(raf, lumpOff, lumpLen, LUMP_SURFEDGES, "surfedges");
            byte[] fBuf  = readLump(raf, lumpOff, lumpLen, LUMP_FACES,     "faces");
            byte[] tiBuf = readLump(raf, lumpOff, lumpLen, LUMP_TEXINFO,   "texinfo");
            byte[] txBuf = readLump(raf, lumpOff, lumpLen, LUMP_TEXTURES,  "textures");

            if (vBuf == null || eBuf == null || seBuf == null || fBuf == null)
                throw new IOException("Required BSP lumps missing from " + bspFile.getName());

            return buildPackage(bspFile, mapName, vBuf, eBuf, seBuf, fBuf, tiBuf, txBuf);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Geometry extraction
    // ─────────────────────────────────────────────────────────────────────

    private static UmapPackage buildPackage(
            File bspFile, String mapName,
            byte[] vBuf, byte[] eBuf, byte[] seBuf, byte[] fBuf,
            @Nullable byte[] tiBuf, @Nullable byte[] txBuf) throws IOException {

        int nVerts     = vBuf.length  / VERTEX_STRIDE;
        int nEdges     = eBuf.length  / EDGE_STRIDE;
        int nSurfedges = seBuf.length / SURFEDGE_STRIDE;
        int nFaces     = fBuf.length  / FACE_STRIDE;
        int nTexinfo   = tiBuf != null ? tiBuf.length / TEXINFO_STRIDE : 0;

        // ── Build texture name hidden-flags lookup ────────────────────────
        boolean[] texHidden = buildTexHiddenFlags(tiBuf, txBuf, nTexinfo);

        // ── Vertex positions ──────────────────────────────────────────────
        float[] vx = new float[nVerts];
        float[] vy = new float[nVerts];
        float[] vz = new float[nVerts];
        ByteBuffer vb = wrap(vBuf);
        for (int i = 0; i < nVerts; i++) {
            vx[i] =  vb.getFloat() * GS_TO_CM;
            vy[i] = -vb.getFloat() * GS_TO_CM;
            vz[i] =  vb.getFloat() * GS_TO_CM;
        }

        // ── Edges ─────────────────────────────────────────────────────────
        int[] edgeV0 = new int[nEdges];
        int[] edgeV1 = new int[nEdges];
        ByteBuffer eb = wrap(eBuf);
        for (int i = 0; i < nEdges; i++) {
            edgeV0[i] = eb.getShort() & 0xFFFF;
            edgeV1[i] = eb.getShort() & 0xFFFF;
        }

        // ── Surfedges ─────────────────────────────────────────────────────
        int[] surfedges = new int[nSurfedges];
        ByteBuffer seb = wrap(seBuf);
        for (int i = 0; i < nSurfedges; i++) surfedges[i] = seb.getInt();

        // ── Pre-scan for triangle count ───────────────────────────────────
        int estimatedTris = 0;
        for (int fi = 0; fi < nFaces; fi++) {
            int ne = leUInt16(fBuf, fi * FACE_STRIDE + 8); // numedges
            if (ne >= 3) estimatedTris += ne - 2;
        }
        estimatedTris = Math.min(estimatedTris, MAX_TRIS) + 8;

        int[] tri0 = new int[estimatedTris];
        int[] tri1 = new int[estimatedTris];
        int[] tri2 = new int[estimatedTris];
        int nTris  = 0;
        int skippedHidden = 0, skippedBadRef = 0;

        for (int fi = 0; fi < nFaces && nTris < tri0.length; fi++) {
            int base = fi * FACE_STRIDE;
            // uint16 planenum(0) uint16 side(2) int32 firstedge(4) uint16 numedges(8) uint16 texinfo(10) ...
            int firstedge = leInt32(fBuf, base + 4);
            int numedges  = leUInt16(fBuf, base + 8);
            int texinfo   = leUInt16(fBuf, base + 10);

            if (numedges < 3) continue;

            // Check hidden flag
            if (texinfo < nTexinfo && texHidden[texinfo]) { skippedHidden++; continue; }

            if (firstedge < 0 || firstedge + numedges > nSurfedges) { skippedBadRef++; continue; }

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

        Umapica.LOGGER.info("[Umapica] GoldSrc '{}': {} faces → {} tris (hidden={}, badref={})",
                mapName, nFaces, nTris, skippedHidden, skippedBadRef);

        if (nTris == 0)
            throw new IOException("No renderable geometry found in " + bspFile.getName());

        // ── Dense vertex remap ────────────────────────────────────────────
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
            posArr[ni*3    ] = vx[oi];
            posArr[ni*3 + 1] = vy[oi];
            posArr[ni*3 + 2] = vz[oi];
        }
        for (int t = 0; t < nTris; t++) {
            idxArr[t*3    ] = oldToNew[tri0[t]];
            idxArr[t*3 + 1] = oldToNew[tri1[t]];
            idxArr[t*3 + 2] = oldToNew[tri2[t]];
        }

        FBox bounds = computeBounds(posArr);
        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, bounds);
        mesh.sections = new UmapMeshData.Section[0];

        Umapica.LOGGER.info("[Umapica] GoldSrc BSP '{}': {} verts {} tris bounds={}",
                mapName, newVtxCount, nTris, bounds);

        UmapPackage pkg   = new UmapPackage(bspFile);
        UmapActor actor   = new UmapActor("GoldSrcMap", mapName);
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
            @Nullable byte[] tiBuf, @Nullable byte[] txBuf, int nTexinfo) {

        if (tiBuf == null || nTexinfo == 0) return new boolean[0];

        // Parse miptex names from LUMP_TEXTURES if available
        String[] texNames = parseTextureNames(txBuf);

        boolean[] hidden = new boolean[nTexinfo];
        for (int i = 0; i < nTexinfo; i++) {
            int flags  = leInt32(tiBuf, i * TEXINFO_STRIDE + 36); // flags  at offset 36
            int miptex = leInt32(tiBuf, i * TEXINFO_STRIDE + 32); // miptex at offset 32

            // Sky surfaces
            if ((flags & SURF_SKY) != 0) { hidden[i] = true; continue; }

            // Filter by texture name if available
            String name = (texNames != null && miptex >= 0 && miptex < texNames.length)
                    ? texNames[miptex] : null;
            if (name != null) {
                String n = name.toLowerCase(java.util.Locale.ROOT);
                if (n.startsWith("sky")    || n.equals("aaatrigger")
                        || n.startsWith("clip")   || n.equals("origin")
                        || n.startsWith("hint")   || n.startsWith("skip")
                        || n.startsWith("null")   || n.equals("black")
                        || n.startsWith("noclip") || n.startsWith("trigger")) {
                    hidden[i] = true;
                }
            }
        }
        return hidden;
    }

    /**
     * Parses the LUMP_TEXTURES miptex lump and returns the texture name for each index.
     * GoldSrc texture lump layout:
     * <pre>
     *   int32 nummiptex
     *   int32 offsets[nummiptex]   (offset from start of lump, -1 = external)
     *   [miptex_t structs at each offset]
     *     char[16] name
     *     uint32 width, height
     *     uint32 offsets[4]
     * </pre>
     */
    private static @Nullable String[] parseTextureNames(@Nullable byte[] txBuf) {
        if (txBuf == null || txBuf.length < 4) return null;
        try {
            int numTex = leInt32(txBuf, 0);
            if (numTex <= 0 || numTex > 4096) return null;
            String[] names = new String[numTex];
            for (int i = 0; i < numTex; i++) {
                int ptrOff = 4 + i * 4;
                if (ptrOff + 4 > txBuf.length) break;
                int miptexOff = leInt32(txBuf, ptrOff);
                if (miptexOff < 0 || miptexOff + 16 > txBuf.length) continue;
                // Read null-terminated char[16] name at miptexOff
                int end = miptexOff;
                while (end < miptexOff + 16 && end < txBuf.length && txBuf[end] != 0) end++;
                names[i] = new String(txBuf, miptexOff, end - miptexOff,
                        java.nio.charset.StandardCharsets.US_ASCII);
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers  (same algorithms as SourceBspLoader)
    // ─────────────────────────────────────────────────────────────────────

    private static int surfedgeVertex(int[] surfedges, int[] ev0, int[] ev1, int seIdx) {
        int se = surfedges[seIdx];
        return se >= 0 ? ev0[se] : ev1[-se];
    }

    private static @Nullable byte[] readLump(
            RandomAccessFile raf, int[] offsets, int[] lengths, int idx, String name)
            throws IOException {
        if (idx >= LUMP_COUNT) return null;
        int off = offsets[idx];
        int len = lengths[idx];
        if (len <= 0 || off <= 0) return null;
        raf.seek(off);
        byte[] d = new byte[len];
        raf.readFully(d);
        return d;
    }

    private static ByteBuffer wrap(byte[] d) {
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int leInt32(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }
    private static int leUInt16(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8);
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
