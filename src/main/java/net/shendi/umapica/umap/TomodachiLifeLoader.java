package net.shendi.umapica.umap;

import com.github.luben.zstd.ZstdInputStream;
import net.shendi.umapica.Umapica;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads Nintendo Switch BFRES NX model files from Tomodachi Life.
 *
 * <h3>Input</h3>
 * A Zstandard-compressed {@code .bfres.zs} file from {@code D:\romfs\Model\}.
 *
 * <h3>Output</h3>
 * {@link UmapMeshData} with flat float[] XYZ positions and int[] triangle indices
 * (index format matches: three consecutive entries = one triangle).
 *
 * <h3>BFRES NX binary layout (v2.2.10, little-endian)</h3>
 * <pre>
 * BFRES header:
 *   +0x00  "FRES    " magic
 *   +0x18  relocationTableOffset (u32) – offset of _RLT block
 *   +0x28  first FMDL block ptr (s64, lo32 = file offset)
 *   +0x68  BufferInfo ("FMAA") block ptr (s64, lo32 = file offset)
 *
 * FMDL block (12-byte header: magic + blockSize + relOff):
 *   +0x20  FVTX array ptr (s64, lo32)
 *   +0x28  FSHP array ptr (s64, lo32)
 *   +0x68  numFVTX (u16), numFSHP (u16), numFMAT (u16), …
 *
 * FVTX block (stride = 0x58 bytes, 12-byte header):
 *   +0x48  BufferOffset (u32) – offset of vertex data within the vertex pool
 *   +0x50  VertexCount  (u32)
 *
 * FSHP block (stride = 0x60 bytes, 12-byte header):
 *   +0x18  MeshArray ptr (s64, lo32) – first Mesh[0] struct
 *   +0x50  vtxBufIndex  (u16)        – which FVTX this shape uses
 *
 * Mesh[0] struct (no block header, immediately at MeshArray offset):
 *   +0x20  FaceBufferOffset (u32) – offset of index data within the index pool
 *   +0x28  IndexFormat      (u32) – 1 = uint16
 *   +0x2C  IndexCount       (u32)
 *
 * Buffer pools:
 *   FACE_BIO   = u32 at (FMAA_base + numFMAABlocks * 0x70 + 8)
 *   VERTEX_BIO = FACE_BIO + facePoolSize
 *              where facePoolSize = posArr field of the _RLT section whose pos == FACE_BIO
 *
 * Vertex stride = 12 bytes (float32 x, y, z only).
 * </pre>
 *
 * <h3>Empirical verification (DollHouse.bfres, decompressed 6 039 968 bytes)</h3>
 * <ul>
 *   <li>FACE_BIO   = 0x45000  (index buffer pool start)</li>
 *   <li>VERTEX_BIO = 0x10F200 (vertex buffer pool start)</li>
 *   <li>7 FVTXs × (2786 + 8395 + 3735 + 6627 + 524 + 72 + 46) = 22 185 total vertices</li>
 *   <li>7 FSHPs with LOD0 index counts: 4314 12273 5124 9504 684 36 30</li>
 * </ul>
 */
public final class TomodachiLifeLoader {

    /** BFRES file magic (little-endian bytes "FRES"). */
    private static final int MAGIC_FRES = 0x53455246;
    /** FMAA sub-block magic within the BufferInfo block. */
    private static final int MAGIC_FMAA = 0x41414D46;
    /** Byte stride between consecutive FMAA sub-blocks in the BufferInfo. */
    private static final int FMAA_SUBBLOCK_STRIDE = 0x70;
    /** Byte stride between consecutive FVTX blocks (empirically verified). */
    private static final int FVTX_BLOCK_STRIDE = 0x58;
    /** Byte stride between consecutive FSHP blocks (empirically verified). */
    private static final int FSHP_BLOCK_STRIDE = 0x60;
    /** Bytes per vertex in the position buffer (float32 x, y, z). */
    private static final int VERTEX_STRIDE = 12;
    /** Bytes per index in the face buffer (uint16). */
    private static final int INDEX_SIZE = 2;

    // FVTX field offsets (relative to block start, after 12-byte header)
    private static final int FVTX_BUFOFF     = 0x48; // u32: vertex pool offset (from faceBio)
    private static final int FVTX_VTXCNT     = 0x50; // u32: vertex count

    // FSHP field offsets
    private static final int FSHP_MESHARR = 0x18; // s64 ptr lo32: mesh array file offset
    private static final int FSHP_VTXIDX  = 0x50; // u16: FVTX index used by this shape

    // Mesh struct field offsets (no block header)
    private static final int MESH_FACEOFF = 0x20; // u32: index pool offset
    private static final int MESH_IDXCNT  = 0x2C; // u32: index count

    // ── BFRES header field offsets ────────────────────────────────────────────
    private static final int HDR_RLT_OFFSET  = 0x18; // u32: offset to _RLT block
    private static final int HDR_FMDL_PTR    = 0x28; // s64 ptr lo32: first FMDL
    private static final int HDR_FMAA_PTR    = 0x68; // s64 ptr lo32: BufferInfo (FMAA) block

    // ── FMDL header field offsets ─────────────────────────────────────────────
    private static final int FMDL_FVTX_PTR   = 0x20; // s64 ptr lo32: FVTX array
    private static final int FMDL_FSHP_PTR   = 0x28; // s64 ptr lo32: FSHP array
    private static final int FMDL_NUM_FVTX   = 0x68; // u16
    private static final int FMDL_NUM_FSHP   = 0x6A; // u16

    // ── _RLT header field offsets ─────────────────────────────────────────────
    private static final int RLT_NUM_SECTIONS = 0x08; // u32
    private static final int RLT_SECTIONS_OFF = 0x10; // start of section array
    private static final int RLT_SECTION_SIZE = 0x10; // bytes per section entry

    // ── _RLT section field offsets (within each section entry) ────────────────
    private static final int SEC_POS     = 0x00; // u32: file offset base of section
    private static final int SEC_POSARR  = 0x04; // u32: face pool size when pos==FACE_BIO

    // ── BufferInfo struct offsets (after FMAA sub-blocks) ────────────────────
    private static final int BUFINFO_FACE_BIO_OFF = 0x08; // u32: FACE_BIO (index pool start)

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads and parses a Zstd-compressed BFRES NX file, returning combined
     * geometry for all shapes in the first model (FMDL[0]).
     *
     * @param path path to a {@code .bfres.zs} file
     * @return mesh geometry with positions, indices, and bounding box
     * @throws IOException if decompression or parsing fails
     */
    public UmapMeshData load(Path path) throws IOException {
        byte[] raw = decompress(path);
        return parse(raw);
    }

    // ── Decompression ─────────────────────────────────────────────────────────

    private static byte[] decompress(Path path) throws IOException {
        try (InputStream compressed = new BufferedInputStream(Files.newInputStream(path));
             ZstdInputStream zstd    = new ZstdInputStream(compressed)) {
            return zstd.readAllBytes();
        }
    }

    // ── BFRES parsing ─────────────────────────────────────────────────────────

    private static UmapMeshData parse(byte[] raw) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // Validate magic
        if (buf.getInt(0) != MAGIC_FRES) {
            throw new IOException("Not a BFRES file (bad magic)");
        }

        // ── Locate buffer pools ──────────────────────────────────────────────
        long faceBio   = findFaceBio(buf);

        Umapica.LOGGER.debug("TomodachiLifeLoader: FACE_BIO=0x{}",
                Long.toHexString(faceBio));

        // ── Locate FMDL[0] ───────────────────────────────────────────────────
        int fmdlOff     = lo32(buf, HDR_FMDL_PTR);
        int fvtxBase    = lo32(buf, fmdlOff + FMDL_FVTX_PTR);
        int fshpBase    = lo32(buf, fmdlOff + FMDL_FSHP_PTR);
        int numFVTX     = buf.getShort(fmdlOff + FMDL_NUM_FVTX) & 0xFFFF;
        int numFSHP     = buf.getShort(fmdlOff + FMDL_NUM_FSHP) & 0xFFFF;

        // ── Read vertices ────────────────────────────────────────────────────
        // bufOff in each FVTX is an absolute offset from faceBio (the start of the
        // entire GPU buffer pool). Face data occupies [0, facePoolSize) and vertex
        // buffers are packed immediately after.
        int[] vtxBaseIndex = new int[numFVTX];
        int[] vtxCounts    = new int[numFVTX];
        int totalVerts = 0;
        for (int i = 0; i < numFVTX; i++) {
            int fvtxOff    = fvtxBase + i * FVTX_BLOCK_STRIDE;
            vtxCounts[i]   = buf.getInt(fvtxOff + FVTX_VTXCNT);
            vtxBaseIndex[i] = totalVerts;
            totalVerts     += vtxCounts[i];
        }

        float[] positions = new float[totalVerts * 3];
        for (int i = 0; i < numFVTX; i++) {
            int fvtxOff   = fvtxBase + i * FVTX_BLOCK_STRIDE;
            int bufOff    = buf.getInt(fvtxOff + FVTX_BUFOFF);
            // Vertex position buffer is a pure float32-XYZ stream (stride = 12).
            // bufOff is measured from faceBio (start of combined GPU buffer pool).
            int dataStart = (int) (faceBio + (bufOff & 0xFFFFFFFFL));
            int base      = vtxBaseIndex[i];
            if (dataStart + VERTEX_STRIDE > buf.capacity()) continue;
            for (int v = 0; v < vtxCounts[i]; v++) {
                int p = dataStart + v * VERTEX_STRIDE;
                if (p + VERTEX_STRIDE > buf.capacity()) break;
                positions[(base + v) * 3    ] = buf.getFloat(p);
                positions[(base + v) * 3 + 1] = buf.getFloat(p + 4);
                positions[(base + v) * 3 + 2] = buf.getFloat(p + 8);
            }
        }

        // ── Read face indices (LOD 0 only) ───────────────────────────────────
        int totalIndices = 0;
        int[] idxCounts    = new int[numFSHP];
        int[] faceOffsets  = new int[numFSHP];
        int[] vtxBufIdxs   = new int[numFSHP];
        for (int i = 0; i < numFSHP; i++) {
            int fshpOff       = fshpBase + i * FSHP_BLOCK_STRIDE;
            int meshArrOff    = lo32(buf, fshpOff + FSHP_MESHARR);
            vtxBufIdxs[i]     = buf.getShort(fshpOff + FSHP_VTXIDX) & 0xFFFF;
            faceOffsets[i]    = buf.getInt(meshArrOff + MESH_FACEOFF);
            idxCounts[i]      = buf.getInt(meshArrOff + MESH_IDXCNT);
            totalIndices      += idxCounts[i];
        }

        int[] indices = new int[totalIndices];
        int idxPos = 0;
        for (int i = 0; i < numFSHP; i++) {
            int faceDataStart = (int) (faceBio + (faceOffsets[i] & 0xFFFFFFFFL));
            int vtxBase       = vtxBaseIndex[vtxBufIdxs[i]];
            for (int j = 0; j < idxCounts[i]; j++) {
                indices[idxPos++] = vtxBase + (buf.getShort(faceDataStart + j * INDEX_SIZE) & 0xFFFF);
            }
        }

        // ── Bounding box ─────────────────────────────────────────────────────
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < totalVerts; i++) {
            float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        FBox bounds = new FBox(
                new FVector(minX, minY, minZ),
                new FVector(maxX, maxY, maxZ),
                totalVerts > 0);

        Umapica.LOGGER.info("TomodachiLifeLoader: {} vertices, {} indices, bounds {}",
                totalVerts, totalIndices, bounds);

        return new UmapMeshData(positions, indices, bounds);
    }

    // ── Buffer pool location helpers ──────────────────────────────────────────

    /**
     * Finds FACE_BIO by scanning FMAA sub-blocks in the BufferInfo block.
     *
     * <p>Layout of the BufferInfo block (BFRES header +0x68):
     * <pre>
     *   [FMAA block 0][FMAA block 1]...[FMAA block N]
     *   then: u32 unknown | u32 totalDataSize | u32 FACE_BIO | u32 pad
     * </pre>
     */
    private static long findFaceBio(ByteBuffer buf) throws IOException {
        int fmaaBase  = lo32(buf, HDR_FMAA_PTR);
        if (fmaaBase == 0) {
            throw new IOException("No BufferInfo block (FMAA pointer is null) – file has no geometry buffers");
        }
        int numBlocks = 0;
        while (fmaaBase + numBlocks * FMAA_SUBBLOCK_STRIDE + 4 < buf.capacity()
               && buf.getInt(fmaaBase + numBlocks * FMAA_SUBBLOCK_STRIDE) == MAGIC_FMAA) {
            numBlocks++;
        }
        if (numBlocks == 0) {
            throw new IOException("No FMAA sub-blocks found in BufferInfo");
        }
        int  infoOff = fmaaBase + numBlocks * FMAA_SUBBLOCK_STRIDE;
        long hint    = buf.getInt(infoOff + BUFINFO_FACE_BIO_OFF) & 0xFFFFFFFFL;

        // Validate hint: look for an RLT section whose pos == hint with a non-zero size.
        int rltOff      = buf.getInt(HDR_RLT_OFFSET);
        int numSections = buf.getInt(rltOff + RLT_NUM_SECTIONS);
        for (int s = 0; s < numSections; s++) {
            int  secOff = rltOff + RLT_SECTIONS_OFF + s * RLT_SECTION_SIZE;
            long secPos = buf.getInt(secOff + SEC_POS)    & 0xFFFFFFFFL;
            long secArr = buf.getInt(secOff + SEC_POSARR) & 0xFFFFFFFFL;
            if (secPos == hint && secArr > 0) return hint; // hint is valid
        }
        // Fallback: hint didn't match (e.g. Market/Park store a non-aligned value in BufferInfo).
        // Find the first page-aligned RLT section that has pos>0 and a non-zero size field —
        // that section marks the start of the face/index buffer pool.
        for (int s = 0; s < numSections; s++) {
            int  secOff = rltOff + RLT_SECTIONS_OFF + s * RLT_SECTION_SIZE;
            long secPos = buf.getInt(secOff + SEC_POS)    & 0xFFFFFFFFL;
            long secArr = buf.getInt(secOff + SEC_POSARR) & 0xFFFFFFFFL;
            if (secPos > 0 && (secPos & 0xFFF) == 0 && secArr > 0) return secPos;
        }
        throw new IOException("Could not determine face pool start from BufferInfo (hint=0x"
                + Long.toHexString(hint) + ") or _RLT sections");
    }

    /**
     * Finds VERTEX_BIO = FACE_BIO + facePoolSize, where facePoolSize is stored
     * in the _RLT section whose {@code pos} field equals FACE_BIO.
     */
    private static long findVertexBio(ByteBuffer buf, long faceBio) throws IOException {
        int rltOff      = buf.getInt(HDR_RLT_OFFSET);
        int numSections = buf.getInt(rltOff + RLT_NUM_SECTIONS);
        for (int s = 0; s < numSections; s++) {
            int  secOff    = rltOff + RLT_SECTIONS_OFF + s * RLT_SECTION_SIZE;
            long secPos    = buf.getInt(secOff + SEC_POS)    & 0xFFFFFFFFL;
            long secPosArr = buf.getInt(secOff + SEC_POSARR) & 0xFFFFFFFFL;
            if (secPos == faceBio && secPosArr > 0) {
                return faceBio + secPosArr;
            }
        }
        throw new IOException("_RLT section for face pool (pos=0x" + Long.toHexString(faceBio) + ") not found");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Reads the lower 32 bits of a little-endian s64 pointer at {@code offset}. */
    private static int lo32(ByteBuffer buf, int offset) {
        return buf.getInt(offset);
    }
}
