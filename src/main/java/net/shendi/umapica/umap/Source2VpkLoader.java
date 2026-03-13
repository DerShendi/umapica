package net.shendi.umapica.umap;

import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads <b>CS2 (Counter-Strike 2) / Source 2</b> map files in the single-pak
 * {@code .vpk} format by extracting and parsing the navigation mesh
 * ({@code maps/<mapname>.nav}).
 *
 * <h3>VPK v2 format overview</h3>
 * <ul>
 *   <li>28-byte header: magic(4) version(4) tree_size(4) file_data_size(4) ×3 md5_sizes</li>
 *   <li>Tree of {@code ext → dir → filename} null-terminated strings, each file has an
 *       18-byte entry (crc, preload_bytes, archive_index, entry_offset, entry_length)</li>
 *   <li>archive_index = 0x7FFF → data is embedded immediately after the tree</li>
 * </ul>
 *
 * <h3>NAV file format (v36)</h3>
 * CS2 maps use nav version 36 with the polygon-pool geometry format introduced in v31.
 * All walkable areas are convex polygons referencing a shared corner vertex pool.
 * The full parse sequence for v36 is:
 * <ol>
 *   <li>Header: magic, version, subVersion, unk1</li>
 *   <li>(v≥36) KV3Unknown1 block (8-byte aligned)</li>
 *   <li>(v≥31) Polygon pool: cornerCount + float[3][], polygonCount + byte+uint32[] per poly</li>
 *   <li>(v≥32) unk2 = 0 uint32</li>
 *   <li>(v≥35) unkCount1 + for each (NullTermString + 48 bytes)</li>
 *   <li>(v≥36) KV3Unknown2 block (8-byte aligned)</li>
 *   <li>ReadAreas: areaCount + area records</li>
 *   <li>ReadLadders: ladderCount + ladder records</li>
 *   <li>unkCount2 × 18 floats</li>
 *   <li>NavMeshGenerationParams (variable-size, navGenVersion-dependent)</li>
 *   <li>(v≥36) KV3Unknown3 block (8-byte aligned)</li>
 *   <li>(subVersion &gt; 0) CustomData KV3 (8-byte aligned)</li>
 * </ol>
 * Each area (v≥31) stores a polygonIndex ref, one float ~0, then per-edge connection lists,
 * then two bytes of legacy count fields, then ladder-above/below ID lists.
 *
 * <h3>Coordinate mapping (Source → UE cm)</h3>
 * Source 2 / CS2 uses the same right-hand Z-up Hammer-unit scale as Source 1:
 * <pre>
 *   ue_x =  src_x × 1.905   (East, same axis)
 *   ue_y = -src_y × 1.905   (negate right-hand→left-hand)
 *   ue_z =  src_z × 1.905   (Z-up → same direction)
 * </pre>
 * {@code localToMc} then maps: MC_x = ue_x, MC_y = ue_z, MC_z = ue_y.
 */
public final class Source2VpkLoader {

    /** Scale: 1 Hammer unit ≈ 1.905 cm (same as Source 1). */
    private static final float SRC_TO_CM = 1.905f;

    /** Hard cap to prevent excessive triangle counts. */
    private static final int MAX_TRIS = 500_000;

    /**
     * Height (in Hammer units) used when extruding nav-mesh boundary edges into
     * wall quads.  Nav-area edges with zero connections mark places the AI cannot
     * cross — i.e. actual walls, ledges, or map boundaries — so extruding them
     * gives a reasonable approximation of the 3-D structure of the level.
     * 200 HU ≈ 381 cm (about 2.5× a player's standing height).
     */
    private static final float WALL_HEIGHT_HU = 200.0f;

    /** VPK v2 magic. */
    private static final int VPK_MAGIC = 0x55AA1234;

    /** NAV file magic: FEEDFACE. */
    private static final long NAV_MAGIC = 0xFEEDFACEL;

    private Source2VpkLoader() {}

    // ══════════════════════════════════════════════════════════════════
    //  Public entry point
    // ══════════════════════════════════════════════════════════════════

    /**
     * Loads a CS2 single-pak VPK map file and returns a hologram package
     * built from the map's nav mesh geometry.
     *
     * @param vpkFile the {@code .vpk} file to load
     * @return a {@link UmapPackage} with one actor whose mesh is the nav floor plan
     * @throws IOException if the file cannot be read or has an unsupported format
     */
    public static UmapPackage load(File vpkFile) throws IOException {
        String mapName = vpkFile.getName().replaceAll("(?i)\\.vpk$", "");
        Umapica.LOGGER.info("[Umapica] CS2 VPK '{}': extracting nav mesh", mapName);

        byte[] navData = extractNavFromVpk(vpkFile, mapName);
        if (navData == null) {
            throw new IOException(
                "No .nav file found in " + vpkFile.getName() +
                " (expected maps/" + mapName + ".nav inside the VPK tree)");
        }

        return parseNavFile(vpkFile, mapName, navData);
    }

    // ══════════════════════════════════════════════════════════════════
    //  VPK v2 extraction
    // ══════════════════════════════════════════════════════════════════

    /**
     * Parses the VPK v2 tree and returns the raw bytes of
     * {@code maps/<mapName>.nav}, or {@code null} if the entry is not present.
     */
    private static @Nullable byte[] extractNavFromVpk(File vpkFile, String mapName)
            throws IOException {

        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "r")) {
            // ── VPK header (28 bytes) ──────────────────────────────────
            int  magic       = readInt32LE(raf);
            int  version     = readInt32LE(raf);
            long treeSize    = readUInt32LE(raf);
            long fileDataSz  = readUInt32LE(raf); // embedded data size
            raf.skipBytes(12); // 3 × md5_section_size — not needed

            if ((magic & 0xFFFFFFFFL) != (VPK_MAGIC & 0xFFFFFFFFL))
                throw new IOException("Not a VPK file: magic=0x" + Integer.toHexString(magic));
            if (version != 2)
                throw new IOException("Unsupported VPK version: " + version +
                    " (only v2 single-pak VPKs are supported)");

            long treeStart = 28L;
            long dataStart = treeStart + treeSize; // embedded data section start

            // ── Walk the tree: ext → dir → file ───────────────────────
            raf.seek(treeStart);

            while (true) {
                String ext = readNullTermString(raf);
                if (ext.isEmpty()) break; // end-of-tree sentinel

                while (true) {
                    String dir = readNullTermString(raf);
                    if (dir.isEmpty()) break; // end of this extension

                    while (true) {
                        String file = readNullTermString(raf);
                        if (file.isEmpty()) break; // end of this directory

                        // ── 18-byte VPK entry ─────────────────────────
                        int  crc          = readInt32LE(raf);
                        int  preloadBytes = readUInt16LE(raf);
                        int  archiveIndex = readUInt16LE(raf);
                        long entryOffset  = readUInt32LE(raf);
                        long entryLength  = readUInt32LE(raf);
                        int  terminator   = readUInt16LE(raf);

                        boolean isTarget = "nav".equalsIgnoreCase(ext)
                                        && "maps".equalsIgnoreCase(dir)
                                        && mapName.equalsIgnoreCase(file);

                        if (isTarget) {
                            long totalLen = entryLength + preloadBytes;
                            if (totalLen > 100_000_000L)
                                throw new IOException("NAV file implausibly large: " + totalLen);

                            byte[] result = new byte[(int) totalLen];

                            // Preload bytes come immediately after the entry terminator
                            if (preloadBytes > 0) {
                                raf.readFully(result, 0, preloadBytes);
                            }

                            // Main data — either embedded (0x7FFF) or external archive
                            if (entryLength > 0) {
                                if (archiveIndex == 0x7FFF) {
                                    long savedPos = raf.getFilePointer();
                                    raf.seek(dataStart + entryOffset);
                                    raf.readFully(result, preloadBytes, (int) entryLength);
                                    raf.seek(savedPos);
                                } else {
                                    // External archive — we only support embedded single-pak VPKs
                                    throw new IOException(
                                        "NAV data is in external archive file " +
                                        "(archive_index=" + archiveIndex + "). " +
                                        "Only single-pak VPKs with embedded data (archive_index=0x7FFF) " +
                                        "are supported. Most CS2 map files are single-pak.");
                                }
                            }

                            Umapica.LOGGER.info("[Umapica] CS2 VPK: found maps/{}.nav ({} bytes)",
                                    mapName, totalLen);
                            return result;
                        }

                        // Not our file — skip any inline preload bytes
                        if (preloadBytes > 0) raf.skipBytes(preloadBytes);
                    }
                }
            }
        }
        return null; // not found in tree
    }

    // ══════════════════════════════════════════════════════════════════
    //  NAV v36 parser
    // ══════════════════════════════════════════════════════════════════

    private static UmapPackage parseNavFile(File vpkFile, String mapName, byte[] data)
            throws IOException {

        NavReader r = new NavReader(data);

        // ── Header ────────────────────────────────────────────────────
        long magic      = r.readUInt32();
        int  version    = r.readInt32();
        int  subVersion = r.readInt32();
        int  unk1       = r.readInt32();

        if (magic != NAV_MAGIC)
            throw new IOException("Not a NAV file: magic=0x" + Long.toHexString(magic));
        if (version < 30 || version > 36)
            throw new IOException("Unsupported NAV version: " + version +
                " (supported: 30-36)");

        Umapica.LOGGER.info("[Umapica] NAV v{} subVersion={} from '{}'",
                version, subVersion, mapName);

        // ── v≥36: KV3Unknown1 ─────────────────────────────────────────
        if (version >= 36) r.skipKv3();

        // ── v≥31: Polygon pool ────────────────────────────────────────
        // cornerPool[i] = {x, y, z}
        float[][]   cornerPool = null;
        // polygons[polyIdx] = a float[][] of N corners
        float[][][] polygons   = null;

        if (version >= 31) {
            int cornerCount = r.readInt32();
            cornerPool = new float[cornerCount][3];
            for (int i = 0; i < cornerCount; i++) {
                cornerPool[i][0] = r.readFloat();
                cornerPool[i][1] = r.readFloat();
                cornerPool[i][2] = r.readFloat();
            }

            int polyCount = r.readInt32();
            polygons = new float[polyCount][][];
            for (int i = 0; i < polyCount; i++) {
                int n = r.readByte() & 0xFF; // corner count for this polygon
                float[][] poly = new float[n][3];
                for (int k = 0; k < n; k++) {
                    int idx = r.readInt32(); // index into cornerPool
                    poly[k] = cornerPool[idx];
                }
                if (version >= 35) r.skipBytes(4); // unk uint32 per polygon
                polygons[i] = poly;
            }
        }

        // ── v≥32: unk2 = 0 (verified assert in VRF) ──────────────────
        if (version >= 32) r.skipBytes(4);

        // ── v≥35: unkCount1 items (NullTermString + 48 bytes each) ───
        if (version >= 35) {
            int unkCount1 = r.readInt32();
            for (int i = 0; i < unkCount1; i++) {
                r.skipNullTermString();
                r.skipBytes(48);
            }
        }

        // ── v≥36: KV3Unknown2 ─────────────────────────────────────────
        if (version >= 36) r.skipKv3();

        // ── Read areas ────────────────────────────────────────────────
        int areaCount = r.readInt32();
        Umapica.LOGGER.info("[Umapica] NAV: {} areas", areaCount);

        // Accumulate triangle vertex references using fan triangulation.
        // Each slot in triVerts holds a float[3] coordinate from the pool.
        List<float[]> triVerts = new ArrayList<>(areaCount * 9);

        for (int a = 0; a < areaCount; a++) {
            /*int areaId =*/ r.skipBytes(4); // AreaId (uint32) — not needed
            r.skipBytes(8);                   // DynamicAttributeFlags (int64)
            /*int hullIndex =*/ r.skipBytes(1); // HullIndex (byte)

            // ── Polygon corners ──────────────────────────────────────
            float[][] corners;
            if (version >= 31 && polygons != null) {
                int polyIdx = r.readInt32();
                corners = (polyIdx >= 0 && polyIdx < polygons.length)
                        ? polygons[polyIdx] : new float[0][0];
            } else {
                // v<31: corners stored inline in the area record
                int n = r.readInt32();
                corners = new float[n][3];
                for (int k = 0; k < n; k++) {
                    corners[k][0] = r.readFloat();
                    corners[k][1] = r.readFloat();
                    corners[k][2] = r.readFloat();
                }
            }

            r.skipBytes(4); // float ~0 (almost always zero)

            // ── Connection lists (one list per edge) ──────────────────
            // Each NavMeshConnection = AreaId(4) + EdgeId(4) = 8 bytes
            // Track zero-connection edges: these are boundary edges (walls/drops)
            // that we later extrude upward into wall geometry.
            int numEdges = corners.length;
            boolean[] isBoundaryEdge = new boolean[numEdges];
            for (int e = 0; e < numEdges; e++) {
                int connCount = r.readInt32();
                isBoundaryEdge[e] = (connCount == 0);
                r.skipBytes(connCount * 8);
            }

            r.skipBytes(1); // unk2 byte (LegacyHidingSpotData count, always 0)
            r.skipBytes(4); // unk3 uint32 (LegacySpotEncounterData count, always 0)

            // ── Ladder IDs ────────────────────────────────────────────
            int ladderAboveCount = r.readInt32();
            r.skipBytes(ladderAboveCount * 4);
            int ladderBelowCount = r.readInt32();
            r.skipBytes(ladderBelowCount * 4);

            // ── Fan triangulation (floor) ─────────────────────────────
            if (corners.length >= 3 && triVerts.size() / 3 < MAX_TRIS) {
                for (int k = 1; k < corners.length - 1; k++) {
                    triVerts.add(corners[0]);
                    triVerts.add(corners[k]);
                    triVerts.add(corners[k + 1]);
                }
            }

            // ── Wall extrusion for boundary edges ─────────────────────
            // Each boundary edge (zero connections) gets extruded upward by
            // WALL_HEIGHT_HU to form a wall quad (2 tris × 2 sides = 4 tris).
            // Both faces are emitted so walls are visible from any camera angle.
            if (corners.length >= 2 && triVerts.size() / 3 < MAX_TRIS) {
                for (int e = 0; e < numEdges; e++) {
                    if (!isBoundaryEdge[e]) continue;
                    float[] c0   = corners[e];
                    float[] c1   = corners[(e + 1) % numEdges];
                    // Skip degenerate edges (zero-length)
                    if (c0[0] == c1[0] && c0[1] == c1[1] && c0[2] == c1[2]) continue;
                    float[] top0 = { c0[0], c0[1], c0[2] + WALL_HEIGHT_HU };
                    float[] top1 = { c1[0], c1[1], c1[2] + WALL_HEIGHT_HU };
                    // Front face (outward normal for CCW nav polygons)
                    triVerts.add(c0);   triVerts.add(c1);   triVerts.add(top1);
                    triVerts.add(c0);   triVerts.add(top1); triVerts.add(top0);
                    // Back face (inward facing — visible from inside the nav area)
                    triVerts.add(c0);   triVerts.add(top0); triVerts.add(top1);
                    triVerts.add(c0);   triVerts.add(top1); triVerts.add(c1);
                }
            }
        }

        // ── Read ladders (just skip them) ─────────────────────────────
        // Ladder struct sizes:
        //   Id(4) + Width(4) + Top(12) + Bottom(12) + Length(4) + Direction(4)
        //   + 5 area ID fields (20) = 60 bytes for v<35
        //   + BottomLeft(4) + BottomRight(4) = 68 bytes for v≥35
        int ladderCount = r.readInt32();
        int ladderBytes = (version >= 35) ? 68 : 60;
        r.skipBytes(ladderCount * ladderBytes);

        // ── unkCount2 × 18 floats ─────────────────────────────────────
        int unkCount2 = r.readInt32();
        r.skipBytes(unkCount2 * 18 * 4);

        // ── GenerationParams ──────────────────────────────────────────
        // NavMeshGenerationParams.Read(binaryReader, this)
        // Must be skipped before KV3Unknown3 (always present regardless of version).
        {
            int navGenVersion = r.readInt32();
            r.skipBytes(4);   // UseProjectDefaults (uint32)
            r.skipBytes(12);  // TileSize(f), CellSize(f), CellHeight(f)
            r.skipBytes(8);   // MinRegionSize(int32), MergedRegionSize(int32)
            r.skipBytes(8);   // MeshSampleDistance(f), MaxSampleError(f)
            r.skipBytes(12);  // MaxEdgeLength(int32), MaxEdgeError(f), VertsPerPoly(int32)
            if (navGenVersion >= 7)  r.skipBytes(4);  // SmallAreaOnEdgeRemoval(f)
            if (navGenVersion >= 12) {
                r.skipNullTermString(); // HullPresetName
                r.skipNullTermString(); // HullDefinitionsFile
            }
            int hullCount   = r.readInt32();
            int hullsToRead = (navGenVersion <= 11) ? Math.max(hullCount, 3) : hullCount;
            for (int h = 0; h < hullsToRead; h++) {
                if (navGenVersion >= 9)  r.skipBytes(1);  // Enabled (byte)
                r.skipBytes(8);                            // Radius(f), Height(f)
                if (navGenVersion >= 9)  r.skipBytes(5);  // ShortHeightEnabled(byte), ShortHeight(f)
                if (navGenVersion >= 13) r.skipBytes(5);  // unk byte + unk float
                r.skipBytes(20); // MaxClimb(f), MaxSlope(int32), MaxJumpDownDist(f),
                                 //   MaxJumpHorizDistBase(f), MaxJumpUpDist(f)
                if (navGenVersion >= 11) r.skipBytes(4);  // BorderErosion(int32)
            }
            if (navGenVersion >= 12) r.skipBytes(1); // unkByte
        }

        // ── v≥36: KV3Unknown3 ─────────────────────────────────────────
        if (version >= 36) r.skipKv3();

        // ── SubVersion > 0: CustomData KV3 ────────────────────────────
        if (subVersion > 0) r.skipKv3();

        // ── Build mesh ────────────────────────────────────────────────
        int nTris = triVerts.size() / 3;
        Umapica.LOGGER.info("[Umapica] NAV: {} triangles from {} areas", nTris, areaCount);

        if (nTris == 0)
            throw new IOException("No nav geometry found in " + vpkFile.getName());

        // Flat unindexed layout: each triangle gets 3 independent vertex slots.
        // This avoids complex deduplication and the total vertex count is modest
        // (de_dust2: ~3000 areas × ~4 tris × 3 verts ≈ 36k verts — perfectly fine).
        float[] posArr = new float[nTris * 9]; // nTris × 3 verts × 3 floats
        int[]   idxArr = new int  [nTris * 3];

        for (int t = 0; t < nTris; t++) {
            for (int v = 0; v < 3; v++) {
                float[] c  = triVerts.get(t * 3 + v);
                int     vi = t * 3 + v;
                // Source → UE cm coordinate mapping (same as Source BSP)
                posArr[vi*3    ] =  c[0] * SRC_TO_CM;  // ue_x =  src_x × K
                posArr[vi*3 + 1] = -c[1] * SRC_TO_CM;  // ue_y = -src_y × K  (axis flip)
                posArr[vi*3 + 2] =  c[2] * SRC_TO_CM;  // ue_z =  src_z × K
                idxArr[t*3 + v] = vi;
            }
        }

        FBox      bounds = computeBounds(posArr);
        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, bounds);
        mesh.sections = new UmapMeshData.Section[0];

        Umapica.LOGGER.info("[Umapica] CS2 NAV '{}': {} verts {} tris bounds={}",
                mapName, posArr.length / 3, nTris, bounds);

        UmapPackage pkg   = new UmapPackage(vpkFile);
        UmapActor   actor = new UmapActor("CS2NavMesh", mapName);
        actor.transform   = FTransform.IDENTITY;
        actor.localBounds = bounds;
        actor.meshData    = mesh;
        pkg.actors.add(actor);
        return pkg;
    }

    // ══════════════════════════════════════════════════════════════════
    //  NavReader — byte-array binary reader for nav file parsing
    // ══════════════════════════════════════════════════════════════════

    /**
     * Minimal binary reader over a byte array, little-endian.
     * Supports the operations needed by the NAV v36 parser.
     */
    private static final class NavReader {

        private final byte[] buf;
        private int pos;

        NavReader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

        int readByte() {
            return buf[pos++] & 0xFF;
        }

        int readInt32() {
            int v = (buf[pos] & 0xFF)
                  | ((buf[pos+1] & 0xFF) << 8)
                  | ((buf[pos+2] & 0xFF) << 16)
                  | ((buf[pos+3] & 0xFF) << 24);
            pos += 4;
            return v;
        }

        long readUInt32() {
            return readInt32() & 0xFFFFFFFFL;
        }

        float readFloat() {
            return Float.intBitsToFloat(readInt32());
        }

        /** Skips {@code n} bytes. */
        void skipBytes(int n) { pos += n; }

        /** Skips a null-terminated ASCII string (including the null terminator). */
        void skipNullTermString() {
            while (pos < buf.length && buf[pos] != 0) pos++;
            pos++; // consume null
        }

        /**
         * Aligns to the next 8-byte boundary (relative to pos=0) then skips a
         * BinaryKV3 block.  Supports all KV3 versions 1–5 used by Source 2.
         *
         * <p>BinaryKV3 magic bytes (little-endian uint32):
         * <pre>
         *   v1 = 0x4B563301  v2 = 0x4B563302  v3 = 0x4B563303
         *   v4 = 0x4B563304  v5 = 0x4B563305
         * </pre>
         * CS2 nav files use v5.  Header layout (after magic, 4 bytes):
         * <pre>
         *   FormatGUID        16 bytes
         *   compressionMethod  4 bytes  (0=none, 1=LZ4, 2=ZSTD)
         *   ── common fields (v2-v5) ──
         *   dictId+frameSize   4 bytes  (two uint16s)
         *   countBytes1/4/8    12 bytes  (three int32s)
         *   countTypes         4 bytes
         *   countObj+countArr  4 bytes  (two uint16s)
         *   sizeUncompTotal    4 bytes
         *   sizeCompTotal      4 bytes
         *   countBlocks        4 bytes
         *   sizeBinaryBlobs    4 bytes
         *   ── v4+ additional ──
         *   countBytes2+compSizes 8 bytes
         *   ── v5+ additional ──
         *   sizeUncBuf1        4 bytes
         *   sizeCmpBuf1        4 bytes
         *   sizeUncBuf2        4 bytes
         *   sizeCmpBuf2        4 bytes
         *   8 more int32 fields 32 bytes
         * </pre>
         * Data bytes: buffer1 + buffer2 (v5 only) + binaryBlobs (if countBlocks&gt;0)
         * + 4-byte trailer (0xFFEEDD00).
         */
        void skipKv3() throws IOException {
            // Align to 8-byte boundary
            int rem = pos & 7;
            if (rem != 0) pos += (8 - rem);

            if (pos + 4 > buf.length) return;

            int magic = readInt32();

            // KV3 v0 (VKV3, magic 0x03564B56) is legacy Source 2 and not used in CS2
            if (magic == 0x03564B56)
                throw new IOException("KV3 v0 (VKV3) not supported for skip, offset " + (pos - 4));

            // All other versions: 0x4B5633xx where xx = version (1–5)
            if ((magic & 0xFFFFFF00) != 0x4B563300)
                throw new IOException("Unrecognised KV3 magic: 0x" + Integer.toHexString(magic)
                        + " in nav file at offset " + (pos - 4));

            int version = magic & 0xFF;
            if (version < 1 || version > 5)
                throw new IOException("Unsupported KV3 version " + version + " at offset " + (pos - 4));

            // Format GUID (16 bytes) — only one GUID lives in the stream for v1–v5
            skipBytes(16);

            // compressionMethod: 0=uncompressed, 1=LZ4, 2=ZSTD
            int compressionMethod = readInt32();

            int sizeUncBuf1 = 0, sizeCmpBuf1 = 0;
            int sizeUncBuf2 = 0, sizeCmpBuf2 = 0;
            int countBlocks = 0, sizeBinaryBlobs = 0, sizeCompTotal = 0;

            if (version == 1) {
                // v1: countBytes1(4) + countBytes4(4) + countBytes8(4) + sizeUncompressedTotal(4)
                skipBytes(12);
                int sizeUnc = readInt32();
                // Uncompressed v1: buffer size = sizeUncompressedTotal
                // Compressed v1: size not determinable without the resource block Size field;
                // CS2 does not use v1, so this is a best-effort fallback.
                sizeCmpBuf1 = sizeUnc;
                sizeUncBuf1 = sizeUnc;
            } else {
                // v2–v5 common header fields (40 bytes)
                skipBytes(4);  // compressionDictionaryId (u16) + compressionFrameSize (u16)
                skipBytes(12); // countBytes1, countBytes4, countBytes8
                skipBytes(4);  // countTypes
                skipBytes(4);  // countObjects (u16) + countArrays (u16)
                sizeUncBuf1   = readInt32(); // sizeUncompressedTotal  (also sizeUncBuf1 for v2-4)
                sizeCompTotal = readInt32(); // sizeCompressedTotal
                countBlocks   = readInt32(); // countBlocks (binary blobs)
                sizeBinaryBlobs = readInt32(); // sizeBinaryBlobsBytes

                sizeCmpBuf1 = sizeCompTotal;  // default for v2–4

                if (version >= 4) {
                    skipBytes(8); // countBytes2 (int32) + sizeBlockCompressedSizesBytes (int32)
                }
                if (version >= 5) {
                    // v5 replaces the single buffer with two separate buffers
                    sizeUncBuf1 = readInt32();
                    sizeCmpBuf1 = readInt32();
                    sizeUncBuf2 = readInt32();
                    sizeCmpBuf2 = readInt32();
                    // 8 remaining v5 fields: countBytes1_buf2 … unk16
                    skipBytes(32);
                }
            }

            // ── Skip data section ──────────────────────────────────────────
            // Total stream bytes after the header:
            //   buffer1 data + buffer2 data (v5) + binary-blob data + 4-byte trailer
            if (compressionMethod == 0) { // uncompressed
                skipBytes(sizeUncBuf1);
                if (version >= 5) skipBytes(sizeUncBuf2);
                if (countBlocks > 0) skipBytes(sizeBinaryBlobs);
            } else if (compressionMethod == 1) { // LZ4
                skipBytes(sizeCmpBuf1);
                if (version >= 5) {
                    skipBytes(sizeCmpBuf2);
                    // v5 LZ4: binary-blob frames are separate compressed stream
                    if (countBlocks > 0) {
                        int sizeCmpBlobs = sizeCompTotal - sizeCmpBuf1 - sizeCmpBuf2;
                        if (sizeCmpBlobs > 0) skipBytes(sizeCmpBlobs);
                    }
                }
                // v2–4 LZ4: blob frames are embedded as variable-length records after
                // the buffer1 decompress (from bufferWithBinaryBlobSizes); they ARE separate
                // stream reads but of unknown total compressed size without parsing frames.
                // CS2 nav KV3 blocks use uncompressed encoding, so this path is rarely hit.
            } else if (compressionMethod == 2) { // ZSTD
                if (version < 5) {
                    // v2–4 ZSTD: buffer1 and binary blobs are compressed together
                    skipBytes(sizeCmpBuf1);
                } else {
                    // v5 ZSTD: three separate compressed streams
                    skipBytes(sizeCmpBuf1);
                    skipBytes(sizeCmpBuf2);
                    if (countBlocks > 0) {
                        int sizeCmpBlobs = sizeCompTotal - sizeCmpBuf1 - sizeCmpBuf2;
                        if (sizeCmpBlobs > 0) skipBytes(sizeCmpBlobs);
                    }
                }
            } else {
                throw new IOException("Unknown KV3 compressionMethod " + compressionMethod
                        + " at offset " + pos);
            }

            // Trailer: 4 bytes (0xFFEEDD00) is a SEPARATE stream read only
            // when countBlocks > 0 (it follows the binary blob data).
            // When countBlocks == 0 the trailer is embedded within the buffer bytes
            // themselves and is already accounted for in sizeUncBuf1/sizeUncBuf2.
            if (countBlocks > 0) skipBytes(4);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Utility helpers
    // ══════════════════════════════════════════════════════════════════

    private static FBox computeBounds(float[] pos) {
        if (pos.length < 3) return FBox.EMPTY;
        float mnX = pos[0], mnY = pos[1], mnZ = pos[2];
        float mxX = pos[0], mxY = pos[1], mxZ = pos[2];
        for (int i = 3; i < pos.length; i += 3) {
            if (pos[i  ] < mnX) mnX = pos[i  ]; else if (pos[i  ] > mxX) mxX = pos[i  ];
            if (pos[i+1] < mnY) mnY = pos[i+1]; else if (pos[i+1] > mxY) mxY = pos[i+1];
            if (pos[i+2] < mnZ) mnZ = pos[i+2]; else if (pos[i+2] > mxZ) mxZ = pos[i+2];
        }
        return new FBox(new FVector(mnX, mnY, mnZ), new FVector(mxX, mxY, mxZ), true);
    }

    // ── RandomAccessFile helpers (little-endian) ─────────────────────

    private static int readInt32LE(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static int readUInt16LE(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[2];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    private static long readUInt32LE(RandomAccessFile raf) throws IOException {
        return readInt32LE(raf) & 0xFFFFFFFFL;
    }

    /**
     * Reads a null-terminated US-ASCII string from {@code raf}.
     * Returns an empty string ({@code ""}) when the first byte is the null terminator.
     */
    private static String readNullTermString(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        int c;
        while ((c = raf.read()) > 0) baos.write(c);
        // c == 0 consumed the null terminator; c == -1 is EOF (treated as terminator)
        return baos.toString(StandardCharsets.US_ASCII);
    }
}
