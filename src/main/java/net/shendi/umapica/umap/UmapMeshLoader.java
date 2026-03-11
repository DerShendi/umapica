package net.shendi.umapica.umap;

import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads minimal triangle geometry from a UE4/UE5 StaticMesh .uasset file.
 *
 * <p>The geometry is stored in LOD0 (the highest-detail level of detail).
 * UE4 StaticMesh vertex data lives inside the export whose class is
 * {@code StaticMesh} (or {@code StaticMesh3}). Inside that export the
 * position buffer is immediately followed by the index buffer.
 *
 * <p>Since the bulk data (actual vertices + indices) may live in a separate
 * {@code .ubulk} or {@code .uexp} file, this loader handles both the
 * inline (payload within the .uasset) and the split-bulk-data formats.
 */
public class UmapMeshLoader {

    private static final int INVALID_ENTRY = Integer.MIN_VALUE;

    // ── UE3 package cache ─────────────────────────────────────────────────────
    // Decompressing a 39MB .umap LZO file for every mesh lookup is very slow.
    // Cache the decompressed byte[] + parsed tables keyed by canonical file path.
    private record ParsedUE3Package(
            byte[]   decompData,
            long     baseVirt,
            int      fileVersion,
            String[] names,
            long[]   expOffsets,
            long[]   expSizes,
            String[] expNames,
            String[] expClassNames
    ) {}
    private static final ConcurrentHashMap<String, ParsedUE3Package> UE3_CACHE = new ConcurrentHashMap<>();

    // Per-export mesh result cache: key = "filePath#exportIndex", value = parsed mesh (or EMPTY sentinel for failures)
    private static final UmapMeshData EMPTY_SENTINEL = new UmapMeshData(new float[0], new int[0], FBox.EMPTY);
    private static final ConcurrentHashMap<String, UmapMeshData> MESH_RESULT_CACHE = new ConcurrentHashMap<>();
    private static int hexDumpCount = 0;
    private static final int MAX_HEX_DUMPS = 5;

    /** Clears the UE3 decompression cache (call when reloading a file). */
    public static void clearUE3Cache() { UE3_CACHE.clear(); MESH_RESULT_CACHE.clear(); hexDumpCount = 0; }

    /**
     * Loads mesh data from a {@code .uasset} file.
     *
     * @return parsed mesh data, or {@code null} if geometry could not be found.
     */
    public static @Nullable UmapMeshData load(File uasset) throws IOException {
        return load(uasset, null);
    }

    /**
     * Loads mesh data from a {@code .uasset} or {@code .upk} file.
     *
     * @param objectName name of the specific mesh export within the package, or {@code null}
     *                   to auto-detect by filename.
     * @return parsed mesh data, or {@code null} if geometry could not be found.
     */
    public static @Nullable UmapMeshData load(File uasset, @Nullable String objectName) throws IOException {
        try (UmapReader r = new UmapReader(uasset)) {
            long magic = r.readUInt32();
            if (magic != UmapPackage.MAGIC) return null;
            int legacyFV = r.readInt32();
            r.seek(0); // rewind for full parse

            if (legacyFV >= 0) {
                // UE3 / UDK package
                return loadUE3(r, uasset, objectName);
            } else {
                // UE4 / UE5 package
                return loadUE4(r, uasset, objectName);
            }
        }
    }

    // ================================================================== //
    //  UE4 loading (original logic, refactored to loadUE4)
    // ================================================================== //

    private static @Nullable UmapMeshData loadUE4(UmapReader r, File uasset,
                                                   @Nullable String objectName) throws IOException {
        PackageInfo info = parseHeader(r);
        if (info == null) return null;

        r.fileVersionUE4 = info.versionUE4;
        r.fileVersionUE5 = info.versionUE5;

        // Read the name table
        r.seek(info.nameOff);
        String[] names = new String[info.nameCount];
        for (int i = 0; i < info.nameCount; i++) {
            names[i] = r.readFString();
            if (info.versionUE4 >= 378) { r.readUInt16(); r.readUInt16(); } // hashes
        }
        r.names = names;

        // Read import table to extract material names (used to populate section.materialPath)
        String[] materialImports = readMaterialImports(r, info);

        // Find the StaticMesh export
        String meshName = objectName != null ? objectName
                : uasset.getName().replaceAll("\\.(uasset|upk)$", "");
        int meshExportIdx = findMeshExport(r, info, names, meshName);
        if (meshExportIdx < 0) {
            Umapica.LOGGER.debug("[Umapica] No StaticMesh export found in {}", uasset.getName());
            return null;
        }

        long exportOff  = info.exportOffsets[meshExportIdx];
        long exportSize = info.exportSizes[meshExportIdx];
        if (exportOff <= 0 || exportSize <= 0) return null;

        r.seek(exportOff);
        return parseMeshExport(r, exportOff, exportSize, info, uasset, materialImports);
    }

    /**
     * Reads the import table and returns all import object names keyed by import-table index
     * (0-based).  Only Material/MaterialInstance class entries are included since that is the
     * only class we use for texture lookup; other imports are skipped but still consume their
     * entry so the key numbering stays aligned with the raw table.
     *
     * <p>The returned map is keyed by <em>import index</em> (NOT material slot number).
     * To map a section.materialIndex to a name use {@code matByImportIdx}; to use it
     * you also need to know which import index each material slot references – that
     * comes from reading the {@code StaticMaterials} UProperty (done in {@link #readLOD0}).</p>
     *
     * <p>Simpler fallback: just return them in discovery order (index 0 = first material
     * import found).  Most StaticMesh assets have one material per section in import-table
     * order, so index 0 = slot 0 matches the vast majority of real assets.</p>
     */
    private static String[] readMaterialImports(UmapReader r, PackageInfo info) {
        if (info.importCount <= 0 || info.importOff <= 0) return null;
        try {
            r.seek(info.importOff);
            // Collect ALL import entries: non-materials get null, materials get their name.
            // We keep the full array so rs.materialIndex can reference the correct slot.
            String[] all = new String[info.importCount];
            for (int i = 0; i < info.importCount; i++) {
                r.readFName(); // classPackage
                String className = r.readFName();
                r.readInt32();  // outerIndex
                String objName  = r.readFName();
                if ("Material".equals(className)
                        || "MaterialInstanceConstant".equals(className)
                        || "MaterialInstance".equals(className)
                        || "MaterialInterface".equals(className)) {
                    all[i] = objName;
                }
            }
            // Compact: build densely-packed list for sequential slot assignment
            List<String> mats = new ArrayList<>();
            for (String s : all) { if (s != null) mats.add(s); }
            return mats.isEmpty() ? null : mats.toArray(new String[0]);
        } catch (IOException e) {
            return null;
        }
    }

    // ================================================================== //
    //  UE3 / UDK loading
    // ================================================================== //

    /**
     * Loads a StaticMesh from a UE3/UDK .upk file, handling LZO compression.
     * A Hat in Time cooked packages are always LZO-compressed.
     * Uses a per-file cache so the 39MB decompression only happens once.
     */
    private static @Nullable UmapMeshData loadUE3(UmapReader r, File upk,
                                                   @Nullable String objectName) throws IOException {
        String cacheKey;
        try { cacheKey = upk.getCanonicalPath(); } catch (IOException e) { cacheKey = upk.getAbsolutePath(); }

        ParsedUE3Package pkg = UE3_CACHE.get(cacheKey);
        if (pkg == null) {
            pkg = buildUE3Package(r, upk);
            if (pkg == null) return null;
            UE3_CACHE.put(cacheKey, pkg);
            Umapica.LOGGER.info("[Umapica] UE3 upk '{}': cached {} exports", upk.getName(), pkg.expNames().length);
        }

        // Create a fresh reader wrapping the cached data (no copy – O(1))
        ByteArrayUmapReader br = new ByteArrayUmapReader(pkg.decompData(), pkg.baseVirt());
        br.names       = pkg.names();
        br.fileVersionUE4 = pkg.fileVersion();

        // ── Find StaticMesh export ─────────────────────────────────────────
        String   target        = objectName != null ? objectName : upk.getName().replaceAll("\\.(upk|uasset|umap)$", "");
        String[] expNames      = pkg.expNames();
        String[] expClassNames = pkg.expClassNames();
        long[]   expOffsets    = pkg.expOffsets();
        long[]   expSizes      = pkg.expSizes();
        int      exportCount   = expNames.length;

        int  meshExpIdx = -1;
        long bestSize   = 0;
        for (int i = 0; i < exportCount; i++) {
            if ("StaticMesh".equalsIgnoreCase(expClassNames[i])) {
                if (expNames[i].equalsIgnoreCase(target)) { meshExpIdx = i; break; }
                if (expSizes[i] > bestSize) { bestSize = expSizes[i]; meshExpIdx = i; }
            }
        }
        // Fallback: largest export if no StaticMesh class found
        if (meshExpIdx < 0) {
            for (int i = 0; i < exportCount; i++) {
                if (expSizes[i] > bestSize) { bestSize = expSizes[i]; meshExpIdx = i; }
            }
            if (meshExpIdx >= 0)
                Umapica.LOGGER.warn("[Umapica] No 'StaticMesh' class in '{}'; using largest export[{}]='{}'",
                        upk.getName(), meshExpIdx, expNames[meshExpIdx]);
        }
        if (meshExpIdx < 0) {
            Umapica.LOGGER.warn("[Umapica] No usable export in '{}'", upk.getName());
            return null;
        }

        long dataOff  = expOffsets[meshExpIdx];
        long dataSize = expSizes[meshExpIdx];
        Umapica.LOGGER.info("[Umapica] UE3 mesh export '{}' class='{}' at virt={} size={}",
                expNames[meshExpIdx], expClassNames[meshExpIdx], dataOff, dataSize);
        if (dataOff <= 0 || dataSize <= 0) return null;

        // Check per-export result cache
        String meshKey = cacheKey + "#" + meshExpIdx;
        UmapMeshData cached = MESH_RESULT_CACHE.get(meshKey);
        if (cached != null) return cached == EMPTY_SENTINEL ? null : cached;

        UmapMeshData result = parseUE3MeshExport(br, dataOff, dataSize, pkg.fileVersion(),
                upk.getName() + ":" + expNames[meshExpIdx]);
        MESH_RESULT_CACHE.put(meshKey, result != null ? result : EMPTY_SENTINEL);
        return result;
    }

    /**
     * Decompresses and parses a UE3 package, returning a cached record of its tables.
     * Called once per file; subsequent mesh lookups reuse the cached data.
     */
    private static @Nullable ParsedUE3Package buildUE3Package(UmapReader r, File upk) throws IOException {
        r.readUInt32(); // magic (already verified by caller)
        int fileVersion = r.readInt32();
        r.readInt32(); // licenseeVersion
        r.readFString(); // folderName
        r.readInt32(); // packageFlags

        int nameCount   = r.readInt32(); long nameOff   = r.readUInt32();
        int exportCount = r.readInt32(); long exportOff = r.readUInt32();
        int importCount = r.readInt32(); long importOff = r.readUInt32();

        r.readInt32(); r.readInt32(); // dependsOffset, softPkgRefsOffset
        r.skipBytes(12);             // 3 × padding ints
        r.skipFGuid();               // package GUID
        int genCount = r.readInt32();
        for (int g = 0; g < genCount; g++) { r.readInt32(); r.readInt32(); }
        r.readInt32(); r.readInt32(); r.readInt32(); // EngineVersion, CookerVersion, PackageSource

        int compressionFlags = r.readInt32();
        int chunkCount       = r.readInt32();

        Umapica.LOGGER.info("[Umapica] UE3 upk '{}': ver={} names={} exports={} imports={} compressionFlags=0x{} chunks={}",
                upk.getName(), fileVersion, nameCount, exportCount, importCount,
                Integer.toHexString(compressionFlags), chunkCount);

        // ── Decompress or read raw ─────────────────────────────────────────
        ByteArrayUmapReader br;
        if (compressionFlags != 0 && chunkCount > 0) {
            long[] uncompOff = new long[chunkCount], uncompSz = new long[chunkCount];
            long[] compOff   = new long[chunkCount], compSz   = new long[chunkCount];
            for (int c = 0; c < chunkCount; c++) {
                uncompOff[c] = r.readUInt32(); uncompSz[c] = r.readUInt32();
                compOff[c]   = r.readUInt32(); compSz[c]   = r.readUInt32();
            }
            long totalDecomp = 0;
            for (long sz : uncompSz) totalDecomp += sz;
            byte[] decompData = new byte[(int) totalDecomp];
            int destOff = 0;
            final long BLOCK_MAGIC = 0x9E2A83C1L;
            final int  DEFAULT_BLOCK = 131072;
            for (int c = 0; c < chunkCount; c++) {
                r.seek(compOff[c]);
                long magic2 = r.readUInt32();
                if (magic2 != BLOCK_MAGIC)
                    throw new IOException(String.format("UE3 LZO bad magic at chunk %d: 0x%08X", c, magic2));
                int blockSize   = r.readInt32();
                int compTotal   = r.readInt32();
                int uncompTotal = r.readInt32();
                if (blockSize <= 0) blockSize = DEFAULT_BLOCK;
                int numSub = (uncompTotal + blockSize - 1) / blockSize;
                int[] subComp = new int[numSub], subUncomp = new int[numSub];
                for (int s = 0; s < numSub; s++) { subComp[s] = r.readInt32(); subUncomp[s] = r.readInt32(); }
                for (int s = 0; s < numSub; s++) {
                    byte[] cdata = r.readBytes(subComp[s]);
                    byte[] decomp = (subComp[s] == subUncomp[s]) ? cdata
                            : Lzo1xDecompressor.decompress(cdata, 0, subComp[s], subUncomp[s]);
                    System.arraycopy(decomp, 0, decompData, destOff, subUncomp[s]);
                    destOff += subUncomp[s];
                }
            }
            long baseVirt = uncompOff[0];
            br = new ByteArrayUmapReader(decompData, baseVirt);
            Umapica.LOGGER.info("[Umapica] UE3 upk '{}': decompressed {} bytes, baseVirt={}",
                    upk.getName(), totalDecomp, baseVirt);
        } else {
            r.seek(0);
            byte[] raw = r.readBytes((int) upk.length());
            br = new ByteArrayUmapReader(raw, 0L);
        }
        br.fileVersionUE4 = fileVersion;

        // ── Name table ────────────────────────────────────────────────────
        br.seek(nameOff);
        String[] names = new String[nameCount];
        for (int i = 0; i < nameCount; i++) names[i] = br.readUE3Name();
        br.names = names;

        // ── Export table ──────────────────────────────────────────────────
        br.seek(exportOff);
        long[]   expOffsets    = new long[exportCount];
        long[]   expSizes      = new long[exportCount];
        String[] expNames      = new String[exportCount];
        String[] expClassNames = new String[exportCount];
        int[]    expClassIdx   = new int[exportCount];

        for (int i = 0; i < exportCount; i++) {
            int classIdx = br.readInt32();
            br.readInt32(); // superIndex
            br.readInt32(); // outerIndex
            String objName = br.readFName();
            br.readInt32(); // archetypeIndex
            br.readInt64(); // objectFlags
            long serialSize   = br.readInt32() & 0xFFFFFFFFL;
            long serialOffset = br.readInt32() & 0xFFFFFFFFL;
            br.readInt32(); // exportFlags
            int gc = br.readInt32();
            for (int g = 0; g < gc; g++) br.readInt32();
            br.skipFGuid();  // packageGuid
            br.readInt32();  // packageFlags
            expOffsets[i]  = serialOffset;
            expSizes[i]    = serialSize;
            expNames[i]    = objName;
            expClassIdx[i] = classIdx;
        }

        // ── Import table: resolve class names ──────────────────────────────
        br.seek(importOff);
        String[] impNames = new String[importCount];
        for (int i = 0; i < importCount; i++) {
            br.readFName(); br.readFName(); br.readInt32();
            impNames[i] = br.readFName();
        }
        for (int i = 0; i < exportCount; i++) {
            int ci = expClassIdx[i];
            if (ci < 0)      { int ii = -ci - 1; expClassNames[i] = (ii < importCount) ? impNames[ii] : "?"; }
            else if (ci > 0) { int ei = ci - 1;  expClassNames[i] = (ei < exportCount) ? expNames[ei] : "?"; }
            else             { expClassNames[i] = "Class"; }
        }

        // Extract decompData and baseVirt from the reader for caching
        // (ByteArrayUmapReader holds these internally; expose via a helper call)
        return new ParsedUE3Package(
                br.getData(), br.getBaseVirt(), fileVersion,
                names, expOffsets, expSizes, expNames, expClassNames);
    }

    /**
     * Parses a cooked UDK StaticMesh export from the decompressed ByteArrayUmapReader.
     */
    private static @Nullable UmapMeshData parseUE3MeshExport(ByteArrayUmapReader br,
            long dataOff, long dataSize, int fileVersion, String upkName) throws IOException {
        long endPos = dataOff + dataSize;

        // Scan the ENTIRE export for vertex/index data instead of relying on
        // the property-list skip.  UE3 StaticMesh exports have complex native
        // prefix data (kDOP tree, sections, etc.) that can confuse the property
        // skipper, causing it to advance past the real vertex buffer.
        return readMeshSimple(br, dataOff, endPos, upkName);
    }

    /**
     * Finds vertex positions and triangle indices in a UE3 StaticMesh export.
     *
     * <p>Scans for the {@code FPositionVertexBuffer} header: {@code int32(12)} (stride)
     * followed by one of three UE3 bulk-data serialisation formats:
     * <ul>
     *   <li><b>BulkSerialize</b>: stride, numV, elemSize(12), count(=numV), data</li>
     *   <li><b>FBulkData/int64</b>: stride, numV, flags, elemCnt, sizeOnDisk, offset(8), data</li>
     *   <li><b>FBulkData/int32</b>: stride, numV, flags, elemCnt, sizeOnDisk, offset(4), data</li>
     * </ul>
     *
     * <p>After locating positions the first valid {@code TArray&lt;uint16&gt;} index
     * buffer is selected (BulkSerialize: elemSize(2) + count + data).
     */
    private static @Nullable UmapMeshData readMeshSimple(ByteArrayUmapReader br,
            long scanStart, long endPos, String upkName) {
        final byte[] data     = br.getData();
        final long   baseVirt = br.getBaseVirt();
        final int    physStart = (int)(scanStart - baseVirt);
        final int    physEnd   = (int)(endPos    - baseVirt);

        // ══════════════════ PASS 1: Separate FPositionVertexBuffer (stride=12) ══════════════
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            if (ileInt32(data, p) != 12) continue;          // Stride must be 12
            int numV = ileInt32(data, p + 4);
            if (numV < 3 || numV > 500_000) continue;
            long dataBytes = (long) numV * 12;

            int dataStart = -1;
            String patternName = null;

            // ── Pattern A: TArray<FVector>::BulkSerialize ──────────────────
            // [stride=12][numV][elemSize=12][count=numV][N×float3]
            if (p + 16 + dataBytes <= physEnd
                    && ileInt32(data, p + 8) == 12
                    && ileInt32(data, p + 12) == numV) {
                dataStart   = p + 16;
                patternName = "BulkSerialize";
            }

            // ── Pattern B: FBulkData with int64 offset ────────────────────
            // [stride=12][numV][flags][elemCnt=numV][sizeOnDisk=numV*12][offset(8)][N×float3]
            if (dataStart < 0 && p + 28 + dataBytes <= physEnd) {
                int flags   = ileInt32(data, p + 8);
                int elemCnt = ileInt32(data, p + 12);
                int sizeDsk = ileInt32(data, p + 16);
                if (elemCnt == numV && sizeDsk == numV * 12
                        && flags >= 0 && (flags & 0x07) == 0 && flags <= 0xFFFF) {
                    dataStart   = p + 28;
                    patternName = "BulkData64";
                }
            }

            // ── Pattern C: FBulkData with int32 offset ────────────────────
            // [stride=12][numV][flags][elemCnt=numV][sizeOnDisk=numV*12][offset(4)][N×float3]
            if (dataStart < 0 && p + 24 + dataBytes <= physEnd) {
                int flags   = ileInt32(data, p + 8);
                int elemCnt = ileInt32(data, p + 12);
                int sizeDsk = ileInt32(data, p + 16);
                if (elemCnt == numV && sizeDsk == numV * 12
                        && flags >= 0 && (flags & 0x07) == 0 && flags <= 0xFFFF) {
                    dataStart   = p + 24;
                    patternName = "BulkData32";
                }
            }

            // ── Pattern E: TArray serialisation [stride=12][numV][count=numV][N×float3]
            // Used when VertexData uses operator<< instead of BulkSerialize
            if (dataStart < 0 && p + 12 + dataBytes <= physEnd
                    && ileInt32(data, p + 8) == numV) {
                dataStart   = p + 12;
                patternName = "TArray";
            }

            // ── Pattern D: bare BulkSerialize [elemSize=12][count][data] ──
            if (dataStart < 0 && p + 8 + dataBytes <= physEnd) {
                dataStart   = p + 8;
                patternName = "BareBulk";
            }

            if (dataStart < 0 || dataStart + dataBytes > physEnd) continue;

            // Validate float3 data
            if (!checkFloat3Range(data, dataStart, numV, physEnd)) continue;
            float[] positions = readFloat3Array(data, dataStart, numV);
            if (!hasSpatialExtent(positions, numV)) continue;

            // ── Find index buffer ──────────────────────────────────────────
            int posDataEnd = dataStart + numV * 12;
            int[] idxResult = findFirstValidIndexBuffer(data, posDataEnd, physEnd, numV);
            if (idxResult == null) continue;

            return buildMeshResult(positions, data, numV, idxResult, upkName, patternName);
        }

        // ══════════════════ PASS 2: FStaticMeshVertexBuffer (full header) ════════════════
        // In older UE3 (and A Hat in Time), there's no separate FPositionVertexBuffer.
        // Positions are interleaved inside FStaticMeshVertexBuffer:
        //   [numTexCoords:int32][stride:int32][numVerts:int32][bFullPrec:int32]
        //   [elemSize=stride:int32][count=numVerts:int32][vertex data]
        // Each vertex: float3 pos (12) + PackedNormal tangentX (4) + PackedNormal tangentZ (4)
        //              + numTexCoords × UV (4 half or 8 full)
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            int numTC   = ileInt32(data, p);       // numTexCoords: 1-8
            if (numTC < 1 || numTC > 8) continue;
            int stride  = ileInt32(data, p + 4);   // stride: 24-80
            if (stride < 24 || stride > 80 || (stride & 3) != 0) continue;
            int numV    = ileInt32(data, p + 8);   // numVertices
            if (numV < 3 || numV > 500_000) continue;
            int bFull   = ileInt32(data, p + 12);  // bUseFullPrecisionUVs
            if (bFull != 0 && bFull != 1) continue;

            // Validate stride matches numTexCoords + bFullPrec
            int expectedStride = 12 + 4 + 4 + numTC * (bFull == 1 ? 8 : 4);
            if (stride != expectedStride) continue;

            // Check BulkSerialize header follows
            if (p + 24 > physEnd) continue;
            int elemSz  = ileInt32(data, p + 16);  // must equal stride
            int count   = ileInt32(data, p + 20);   // must equal numV
            if (elemSz != stride || count != numV) continue;

            long totalBytes = (long) numV * stride;
            int dataStart = p + 24;
            if (dataStart + totalBytes > physEnd) continue;

            if (!checkInterleavedFloat3(data, dataStart, numV, stride, physEnd)) continue;
            float[] positions = readInterleavedPositions(data, dataStart, numV, stride);
            if (!hasSpatialExtent(positions, numV)) continue;

            int vbEnd = dataStart + numV * stride;
            int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, numV);
            if (idxResult == null) continue;

            String patName = "FStaticMeshVB_tc" + numTC + "_s" + stride;
            return buildMeshResult(positions, data, numV, idxResult, upkName, patName);
        }

        // ══════════════════ PASS 3: Interleaved BulkSerialize (bare) ═══════════════════════
        // [elemSize=S][count][data] where S > 12 and first 12 bytes per elem are positions
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            int elemSize = ileInt32(data, p);
            if (elemSize < 20 || elemSize > 80 || (elemSize & 3) != 0) continue;
            int count = ileInt32(data, p + 4);
            if (count < 3 || count > 500_000) continue;
            long totalBytes = (long) count * elemSize;
            int dataStart = p + 8;
            if (dataStart + totalBytes > physEnd) continue;

            if (!checkInterleavedFloat3(data, dataStart, count, elemSize, physEnd)) continue;
            float[] positions = readInterleavedPositions(data, dataStart, count, elemSize);
            if (!hasSpatialExtent(positions, count)) continue;

            int vbEnd = dataStart + count * elemSize;
            int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, count);
            if (idxResult == null) continue;

            String patName = "InterleavedBare_" + elemSize;
            return buildMeshResult(positions, data, count, idxResult, upkName, patName);
        }

        // ══════════════════ PASS 4: stride+numV+BulkSerialize interleaved ══════════════════
        // [stride=S][numV][elemSize=S][count=numV][data]
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            int stride = ileInt32(data, p);
            if (stride < 20 || stride > 80 || (stride & 3) != 0) continue;
            int numV = ileInt32(data, p + 4);
            if (numV < 3 || numV > 500_000) continue;
            if (p + 16 > physEnd) continue;
            if (ileInt32(data, p + 8) != stride) continue;
            if (ileInt32(data, p + 12) != numV) continue;
            long totalBytes = (long) numV * stride;
            int dataStart = p + 16;
            if (dataStart + totalBytes > physEnd) continue;

            if (!checkInterleavedFloat3(data, dataStart, numV, stride, physEnd)) continue;
            float[] positions = readInterleavedPositions(data, dataStart, numV, stride);
            if (!hasSpatialExtent(positions, numV)) continue;

            int vbEnd = dataStart + numV * stride;
            int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, numV);
            if (idxResult == null) continue;

            String patName = "InterleavedBS_" + stride;
            return buildMeshResult(positions, data, numV, idxResult, upkName, patName);
        }

        // ══════════════════ PASS 5: Plain TArray<FVector> positions ════════════════════════
        // Format: [count][count × float3]  (no preceding stride=12 marker)
        // This covers meshes where FPositionVertexBuffer serializes as a simple TArray<FVector>
        for (int p = physStart & ~3; p <= physEnd - 16; p += 4) {
            int numV = ileInt32(data, p);
            if (numV < 3 || numV > 500_000) continue;
            long dataBytes = (long) numV * 12;
            int dataStart = p + 4;
            if (dataStart + dataBytes > physEnd) continue;

            if (!checkFloat3Range(data, dataStart, numV, physEnd)) continue;
            float[] positions = readFloat3Array(data, dataStart, numV);
            if (!hasSpatialExtent(positions, numV)) continue;

            int posDataEnd = dataStart + numV * 12;
            int[] idxResult = findFirstValidIndexBuffer(data, posDataEnd, physEnd, numV);
            if (idxResult == null) continue;

            return buildMeshResult(positions, data, numV, idxResult, upkName, "TArrayVec");
        }

        // ══════════════════ PASS 6: TArray<BYTE>::BulkSerialize positions ═══════════════════
        // Format: [1][byteCount][data]  where byteCount = numV × stride (stride 12..48)
        for (int p = physStart & ~3; p <= physEnd - 16; p += 4) {
            if (ileInt32(data, p) != 1) continue;
            int byteCount = ileInt32(data, p + 4);
            if (byteCount < 36) continue;
            int dataStart = p + 8;
            if (dataStart + byteCount > physEnd) continue;

            // Try stride-12 first (pure positions), then interleaved strides
            for (int testStride : new int[]{12, 20, 24, 28, 32, 36, 40, 44, 48}) {
                if (byteCount % testStride != 0) continue;
                int numV = byteCount / testStride;
                if (numV < 3 || numV > 500_000) continue;

                if (testStride == 12) {
                    if (!checkFloat3Range(data, dataStart, numV, physEnd)) continue;
                    float[] positions = readFloat3Array(data, dataStart, numV);
                    if (!hasSpatialExtent(positions, numV)) continue;
                    int posEnd = dataStart + byteCount;
                    int[] idxResult = findFirstValidIndexBuffer(data, posEnd, physEnd, numV);
                    if (idxResult == null) continue;
                    return buildMeshResult(positions, data, numV, idxResult, upkName, "ByteBS12");
                } else {
                    if (!checkInterleavedFloat3(data, dataStart, numV, testStride, physEnd)) continue;
                    float[] positions = readInterleavedPositions(data, dataStart, numV, testStride);
                    if (!hasSpatialExtent(positions, numV)) continue;
                    int vbEnd = dataStart + byteCount;
                    int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, numV);
                    if (idxResult == null) continue;
                    return buildMeshResult(positions, data, numV, idxResult, upkName, "ByteBS" + testStride);
                }
            }
        }

        // ══════════════════ PASS 7: Interleaved FStaticMeshVB with TArray<BYTE> BulkSerialize ═══
        // [numTC:1-8][stride:24-80][numV:3-500k][bFull:0|1][1][numV*stride][data]
        for (int p = physStart & ~3; p <= physEnd - 28; p += 4) {
            int numTC  = ileInt32(data, p);
            if (numTC < 1 || numTC > 8) continue;
            int stride = ileInt32(data, p + 4);
            if (stride < 20 || stride > 80 || (stride & 3) != 0) continue;
            int numV   = ileInt32(data, p + 8);
            if (numV < 3 || numV > 500_000) continue;
            int bFull  = ileInt32(data, p + 12);
            if (bFull != 0 && bFull != 1) continue;
            // Require TArray<BYTE>::BulkSerialize: [1][numV*stride]
            if (ileInt32(data, p + 16) != 1) continue;
            long totalBytes = (long) numV * stride;
            if (totalBytes > Integer.MAX_VALUE) continue;
            if (ileInt32(data, p + 20) != (int) totalBytes) continue;
            int dataStart = p + 24;
            if (dataStart + totalBytes > physEnd) continue;

            if (!checkInterleavedFloat3(data, dataStart, numV, stride, physEnd)) continue;
            float[] positions = readInterleavedPositions(data, dataStart, numV, stride);
            if (!hasSpatialExtent(positions, numV)) continue;

            int vbEnd = (int)(dataStart + totalBytes);
            int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, numV);
            if (idxResult == null) continue;

            String patName = "SMVB_BS_tc" + numTC + "_s" + stride;
            return buildMeshResult(positions, data, numV, idxResult, upkName, patName);
        }

        // ── Diagnostic: scan for raw consecutive valid float3 triples ──────
        if (hexDumpCount < MAX_HEX_DUMPS) {
            hexDumpCount++;
            int exportSize = physEnd - physStart;

            // Scan every 4-aligned offset for longest run of consecutive valid float3s
            int best4Run = 0, best4Off = -1;
            for (int p = physStart & ~3; p <= physEnd - 48; p += 4) {
                if (!isValidFloat3(data, p)) continue;  // fast skip if first triple invalid
                int runLen = 1;
                for (int v = 1; v < 512; v++) {
                    int off = p + v * 12;
                    if (off + 12 > physEnd || !isValidFloat3(data, off)) break;
                    runLen++;
                }
                if (runLen > best4Run) {
                    best4Run = runLen;
                    best4Off = p - physStart;
                    if (best4Run >= 10) break;
                }
            }
            int bestRun = best4Run;
            int bestRunOff = best4Off;

            // Sample data at best offset
            String bestSample = "none";
            if (best4Run >= 4) {
                int ds = physStart + best4Off;
                float x0 = ileFloat(data, ds), y0 = ileFloat(data, ds+4), z0 = ileFloat(data, ds+8);
                float x1 = ileFloat(data, ds+12), y1 = ileFloat(data, ds+16), z1 = ileFloat(data, ds+20);
                bestSample = String.format("(%.1f,%.1f,%.1f),(%.1f,%.1f,%.1f)", x0,y0,z0,x1,y1,z1);
            }

            // Find [2][N%3==0, fits] and [4][N%3==0, fits] index candidates
            int idx16off = -1, idx16cnt = 0, idx32off = -1, idx32cnt = 0;
            for (int p = physStart & ~3; p <= physEnd - 8 && (idx16off < 0 || idx32off < 0); p += 4) {
                int es = ileInt32(data, p);
                if (idx16off < 0 && es == 2) {
                    int n = ileInt32(data, p+4);
                    if (n >= 3 && n <= 2_000_000 && (n%3)==0 && p+8+(long)n*2 <= physEnd) { idx16off=p-physStart; idx16cnt=n; }
                }
                if (idx32off < 0 && es == 4) {
                    int n = ileInt32(data, p+4);
                    if (n >= 3 && n <= 2_000_000 && (n%3)==0 && p+8+(long)n*4 <= physEnd) { idx32off=p-physStart; idx32cnt=n; }
                }
            }

            Umapica.LOGGER.warn("[Umapica] UE3 '{}': FAIL sz={} best4run={} at={} anyRun={} at={} idx16@{}({}tris) idx32@{}({}tris) sample={}",
                    upkName, exportSize, best4Run, best4Off, bestRun, bestRunOff,
                    idx16off, idx16cnt/3, idx32off, idx32cnt/3, bestSample);
        }
        return null;
    }

    /** Builds the final UmapMeshData from validated positions and index buffer result. */
    private static UmapMeshData buildMeshResult(float[] positions, byte[] data,
            int numV, int[] idxResult, String upkName, String patternName) {
        int idxDataPhys = idxResult[0], idxCount = idxResult[1], maxIdx = idxResult[2];
        int[] indices = readUint16Array(data, idxDataPhys, idxCount);

        // Trim positions to max used vertex
        int trueVerts = maxIdx + 1;
        if (trueVerts < numV) {
            float[] trimmed = new float[trueVerts * 3];
            System.arraycopy(positions, 0, trimmed, 0, trueVerts * 3);
            positions = trimmed;
            numV = trueVerts;
        }

        FBox bounds = computeBounds(positions);
        Umapica.LOGGER.info("[Umapica] UE3 '{}': {} verts={} tris={} bounds={}",
                upkName, patternName, numV, idxCount / 3, bounds);
        UmapMeshData mesh = new UmapMeshData(positions, indices, bounds);
        mesh.sections = new UmapMeshData.Section[]{
                new UmapMeshData.Section(0, idxCount, 0, null)
        };
        return mesh;
    }

    // ── readMeshSimple helper methods ─────────────────────────────────────

    /** Spot-check first few + last few float3s are finite and in range. */
    private static boolean checkFloat3Range(byte[] data, int dataStart, int numV, int physEnd) {
        int sampled = Math.min(numV, 8);
        for (int v = 0; v < sampled; v++) {
            int off = dataStart + v * 12;
            if (off + 12 > physEnd) return false;
            if (!isValidFloat3(data, off)) return false;
        }
        for (int v = Math.max(sampled, numV - 4); v < numV; v++) {
            int off = dataStart + v * 12;
            if (off + 12 > physEnd) return false;
            if (!isValidFloat3(data, off)) return false;
        }
        return true;
    }

    private static boolean isValidFloat3(byte[] data, int off) {
        return isReasonableCoord(ileFloat(data, off))
            && isReasonableCoord(ileFloat(data, off + 4))
            && isReasonableCoord(ileFloat(data, off + 8));
    }

    /** Checks that a float is a finite, normal (non-subnormal) coordinate in game range. */
    private static boolean isReasonableCoord(float f) {
        if (f == 0.0f) return true;  // zero is a valid position
        if (!Float.isFinite(f)) return false;
        float abs = Math.abs(f);
        // Reject subnormal floats (< ~1.18e-38) and values > 200000
        return abs >= 1e-6f && abs < 200000f;
    }

    /** Spot-check first few + last few float3s within an interleaved vertex buffer. */
    private static boolean checkInterleavedFloat3(byte[] data, int dataStart,
            int numV, int stride, int physEnd) {
        int sampled = Math.min(numV, 8);
        for (int v = 0; v < sampled; v++) {
            int off = dataStart + v * stride;
            if (off + 12 > physEnd) return false;
            if (!isValidFloat3(data, off)) return false;
        }
        for (int v = Math.max(sampled, numV - 4); v < numV; v++) {
            int off = dataStart + v * stride;
            if (off + 12 > physEnd) return false;
            if (!isValidFloat3(data, off)) return false;
        }
        return true;
    }

    /** Extracts position float3s from an interleaved vertex buffer (positions at offset 0 of each vertex). */
    private static float[] readInterleavedPositions(byte[] data, int dataStart, int numV, int stride) {
        float[] arr = new float[numV * 3];
        for (int v = 0; v < numV; v++) {
            int off = dataStart + v * stride;
            arr[v * 3]     = ileFloat(data, off);
            arr[v * 3 + 1] = ileFloat(data, off + 4);
            arr[v * 3 + 2] = ileFloat(data, off + 8);
        }
        return arr;
    }

    /** Reads numV × float3 from a byte array starting at offset. */
    private static float[] readFloat3Array(byte[] data, int offset, int numV) {
        float[] arr = new float[numV * 3];
        for (int v = 0; v < numV; v++) {
            int off = offset + v * 12;
            arr[v * 3]     = ileFloat(data, off);
            arr[v * 3 + 1] = ileFloat(data, off + 4);
            arr[v * 3 + 2] = ileFloat(data, off + 8);
        }
        return arr;
    }

    /** Reads numIdx × uint16 from a byte array starting at offset. */
    private static int[] readUint16Array(byte[] data, int offset, int numIdx) {
        int[] arr = new int[numIdx];
        for (int i = 0; i < numIdx; i++) {
            arr[i] = ileUInt16(data, offset + i * 2);
        }
        return arr;
    }

    /** Returns true if the position array has non-trivial spatial extent in at least one axis. */
    private static boolean hasSpatialExtent(float[] positions, int numVerts) {
        if (numVerts < 2) return false;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int v = 0; v < numVerts; v++) {
            float px = positions[v * 3], py = positions[v * 3 + 1], pz = positions[v * 3 + 2];
            if (px < minX) minX = px; if (px > maxX) maxX = px;
            if (py < minY) minY = py; if (py > maxY) maxY = py;
            if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz;
        }
        return (maxX - minX >= 0.1f) || (maxY - minY >= 0.1f) || (maxZ - minZ >= 0.1f);
    }

    /**
     * Scans for the first valid triangle index buffer after position data.
     * Tries multiple UE3 serialisation patterns:
     * <ol>
     *   <li>{@code BulkSerialize}: int32(elemSize=2) + int32(count) + count×uint16</li>
     *   <li>{@code Direct TArray}: int32(count) + count×uint16</li>
     *   <li>{@code BulkData}: int32(flags) + int32(count) + int32(sizeOnDisk=count*2) + offset + count×uint16</li>
     * </ol>
     * Returns {@code int[]{dataPhysOffset, count, maxIndex}} or {@code null}.
     */
    private static int @Nullable [] findFirstValidIndexBuffer(byte[] data, int searchStart,
                                                               int searchEnd, int numVerts) {
        // Allow generous search window past position data (vertex buffer + optional colour buffer)
        int limit = (int) Math.min((long) searchEnd,
                (long) searchStart + (long) numVerts * 80 + 65536);
        for (int q = searchStart & ~3; q <= limit - 8; q += 4) {
            // Pattern 1: BulkSerialize   int32(2) + int32(count) + count × uint16
            if (ileInt32(data, q) == 2) {
                int n = ileInt32(data, q + 4);
                if (n >= 3 && n <= 1_000_000 && (n % 3) == 0
                        && q + 8 + (long) n * 2 <= searchEnd) {
                    int[] r = validateIndexBuffer(data, q + 8, n, numVerts);
                    if (r != null) return r;
                }
            }
            // Pattern 2: Direct TArray   int32(count) + count × uint16
            int n = ileInt32(data, q);
            if (n >= 3 && n <= 1_000_000 && (n % 3) == 0
                    && q + 4 + (long) n * 2 <= searchEnd) {
                int[] r = validateIndexBuffer(data, q + 4, n, numVerts);
                if (r != null) return r;
            }
            // Pattern 3: FBulkData with int32 offset
            // [flags][elemCnt][sizeOnDisk=elemCnt*2][offset(4)][data]
            if (q + 16 <= limit) {
                int flags = ileInt32(data, q);
                int elemCnt = ileInt32(data, q + 4);
                int sizeOnDisk = ileInt32(data, q + 8);
                if (elemCnt >= 3 && elemCnt <= 1_000_000 && (elemCnt % 3) == 0
                        && sizeOnDisk == elemCnt * 2
                        && flags >= 0 && (flags & 0x07) == 0 && flags <= 0xFFFF
                        && q + 16 + (long) elemCnt * 2 <= searchEnd) {
                    int[] r = validateIndexBuffer(data, q + 16, elemCnt, numVerts);
                    if (r != null) return r;
                }
            }
            // Pattern 4: FBulkData with int64 offset
            // [flags][elemCnt][sizeOnDisk=elemCnt*2][offset(8)][data]
            if (q + 20 <= limit) {
                int flags = ileInt32(data, q);
                int elemCnt = ileInt32(data, q + 4);
                int sizeOnDisk = ileInt32(data, q + 8);
                if (elemCnt >= 3 && elemCnt <= 1_000_000 && (elemCnt % 3) == 0
                        && sizeOnDisk == elemCnt * 2
                        && flags >= 0 && (flags & 0x07) == 0 && flags <= 0xFFFF
                        && q + 20 + (long) elemCnt * 2 <= searchEnd) {
                    int[] r = validateIndexBuffer(data, q + 20, elemCnt, numVerts);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }

    /**
     * Validates a candidate uint16 index buffer: every index must be &lt; numVerts
     * and at least 3 distinct vertices must be referenced.
     * Returns {@code int[]{dataPhys, count, maxIndex}} on success, {@code null} otherwise.
     */
    private static int @Nullable [] validateIndexBuffer(byte[] data, int dataPhys,
                                                         int count, int numVerts) {
        int maxIdx = 0;
        for (int i = 0; i < count; i++) {
            int v = ileUInt16(data, dataPhys + i * 2);
            if (v >= numVerts) return null;
            if (v > maxIdx) maxIdx = v;
        }
        if (maxIdx < 2) return null;
        return new int[]{dataPhys, count, maxIdx};
    }

    /** Read little-endian int32 from byte array without bounds check. */
    private static int ileInt32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8)
             | ((d[off+2] & 0xFF) << 16) | ((d[off+3] & 0xFF) << 24);
    }
    /** Read little-endian float32 from byte array. */
    private static float ileFloat(byte[] d, int off) {
        return Float.intBitsToFloat(ileInt32(d, off));
    }
    /** Read unsigned little-endian uint16 from byte array. */
    private static int ileUInt16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8);
    }



    /**
     * Skips a UE3 FProperty list (FName-based, no struct GUIDs).
     * Returns the virtual position just after the terminating "None" property.
     */
    private static long skipUE3PropertyList(ByteArrayUmapReader br, long endPos) {
        try {
            while (br.position() < endPos - 8) {
                long pos = br.position();
                int nameIdx = br.readInt32();
                br.readInt32(); // name number
                if (nameIdx < 0 || nameIdx >= br.names.length) { br.seek(pos); break; }
                String propName = br.names[nameIdx];
                if ("None".equals(propName)) return br.position();

                int typeIdx = br.readInt32(); br.readInt32(); // type FName
                long propSize = br.readInt32() & 0xFFFFFFFFL;
                br.readInt32(); // arrayIndex

                String propType = (typeIdx >= 0 && typeIdx < br.names.length) ? br.names[typeIdx] : "";
                switch (propType) {
                    case "StructProperty" -> { br.readInt32(); br.readInt32(); } // structName FName
                    case "BoolProperty"   -> br.readByte();                      // bool in tag
                    case "ByteProperty"   -> { br.readInt32(); br.readInt32(); } // enum name FName
                    default -> { }
                }
                if (propSize > 0 && propSize < 2_000_000) br.skipBytes((int) propSize);
                else if (propSize >= 2_000_000) { br.seek(pos); break; }
            }
        } catch (IOException e) { /* stop on error */ }
        return br.position();
    }

    // ------------------------------------------------------------------ //
    //  Header parsing (minimal – just what we need)
    // ------------------------------------------------------------------ //

    private static class PackageInfo {
        int  versionUE4, versionUE5;
        int  nameOff, nameCount;
        int  importOff, importCount;
        int  exportOff, exportCount;
        long[]  exportOffsets;
        long[]  exportSizes;
        int[]   exportClassIdx;
        String[] exportObjectNames;
    }

    private static @Nullable PackageInfo parseHeader(UmapReader r) throws IOException {
        long magic = r.readUInt32();
        if (magic != UmapPackage.MAGIC) return null;

        int legacyFV = r.readInt32();
        if (legacyFV != -4) r.readInt32(); // legacyUE3
        int vUE4 = r.readInt32();
        int vUE5 = 0;
        if (legacyFV <= -8) vUE5 = r.readInt32();
        if (vUE4 == -1) { vUE4 = 522; vUE5 = (vUE5 == 0) ? 10 : vUE5; }

        r.fileVersionUE4 = vUE4;
        r.fileVersionUE5 = vUE5;

        r.readInt32(); // licenseVersion
        int customCount = r.readInt32();
        r.skipBytes(customCount * 20);
        r.readInt32();   // TotalHeaderSize
        r.readFString(); // FolderName
        r.readInt32();   // PackageFlags

        int nameCount = r.readInt32();
        int nameOff   = r.readInt32();

        if (vUE5 >= 9) { r.readInt32(); r.readInt32(); } // soft object paths
        if (vUE4 >= 516) r.readFString(); // LocalizationId
        if (vUE4 >= 518) { r.readInt32(); r.readInt32(); } // gatherable text

        int exportCount = r.readInt32(), exportOff = r.readInt32();
        int importCount = r.readInt32(), importOff = r.readInt32();

        PackageInfo info = new PackageInfo();
        info.versionUE4  = vUE4;
        info.versionUE5  = vUE5;
        info.nameOff     = nameOff;
        info.nameCount   = nameCount;
        info.importOff   = importOff;
        info.importCount = importCount;
        info.exportOff   = exportOff;
        info.exportCount = exportCount;
        return info;
    }

    private static int findMeshExport(UmapReader r, PackageInfo info,
                                      String[] names, String meshName) throws IOException {
        // Read export table to find the StaticMesh export
        r.seek(info.exportOff);
        info.exportOffsets     = new long[info.exportCount];
        info.exportSizes       = new long[info.exportCount];
        info.exportClassIdx    = new int[info.exportCount];
        info.exportObjectNames = new String[info.exportCount];

        int meshIdx = -1;
        for (int i = 0; i < info.exportCount; i++) {
            int classIdx = r.readInt32(); // classIndex
            r.readInt32();                // superIndex
            if (info.versionUE4 >= 508) r.readInt32(); // templateIndex
            r.readInt32();                // outerIndex
            String objName = r.readFName();
            r.readInt32();                // ObjectFlags

            long serialSize, serialOffset;
            if (info.versionUE4 >= 196) {
                serialSize   = r.readInt64();
                serialOffset = r.readInt64();
            } else {
                serialSize   = r.readInt32() & 0xFFFFFFFFL;
                serialOffset = r.readInt32() & 0xFFFFFFFFL;
            }
            r.readBool8(); r.readBool8(); r.readBool8(); // forced/notclient/notserver
            if (info.versionUE4 >= 196 && info.versionUE5 == 0) r.skipFGuid();
            r.readUInt32(); // PackageFlags
            if (info.versionUE4 >= 507) { r.readBool8(); r.readBool8(); }
            if (info.versionUE5 >= 10)  { r.readBool8(); r.readBool8(); }
            if (info.versionUE4 >= 257) { r.readInt32(); r.readBool8(); r.readBool8(); r.readBool8(); r.readBool8(); }

            info.exportOffsets[i]     = serialOffset;
            info.exportSizes[i]       = serialSize;
            info.exportClassIdx[i]    = classIdx;
            info.exportObjectNames[i] = objName;

            // Primary mesh export: name matches file, or first export
            if (objName.equalsIgnoreCase(meshName) || objName.equalsIgnoreCase(meshName + "_0")) {
                meshIdx = i;
            }
        }
        // Fallback: pick first export with greatest serial size (likely the mesh data)
        if (meshIdx < 0 && info.exportCount > 0) {
            long maxSize = 0;
            for (int i = 0; i < info.exportCount; i++) {
                if (info.exportSizes[i] > maxSize) { maxSize = info.exportSizes[i]; meshIdx = i; }
            }
        }
        return meshIdx;
    }

    // ------------------------------------------------------------------ //
    //  Mesh export data parsing
    // ------------------------------------------------------------------ //

    /**
     * Parses the StaticMesh export data to extract LOD0 vertex positions and indices.
     * We skip UProperty list at the start by scanning for the geometry markers.
     */
    private static @Nullable UmapMeshData parseMeshExport(UmapReader r, long dataOff,
                                                           long dataSize, PackageInfo info,
                                                           File uasset,
                                                           String[] matImports) throws IOException {
        // Skip UProperties first
        long endPos = dataOff + dataSize;
        skipPropertyList(r, endPos, info.versionUE4);

        // After properties, StaticMesh stores LODResources as a TArray.
        // We look for: int32 numLODs, then LOD data for LOD0.
        // LOD0 contains sections, vertex buffers, index buffer.
        // This is highly version-dependent; we use a heuristic scan for vertex/index data.

        // Try to read numLODs
        long posBeforeLOD = r.position();
        int numLODs = 0;
        try { numLODs = r.readInt32(); } catch (IOException e) { return null; }
        if (numLODs <= 0 || numLODs > 10) {
            // Unexpected – may be different data; scan for the count
            r.seek(posBeforeLOD);
            return scanForGeometry(r, endPos, info, uasset);
        }

        return readLOD0(r, endPos, info, uasset, matImports);
    }

    /** Holds section data temporarily during LOD0 parsing. */
    private static final class RawSection {
        int materialIndex, firstIndex, numTriangles;
    }

    /**
     * Reads LOD0 vertex positions, UVs, sections and indices.
     * Handles UE4 StaticMesh LOD layout.
     */
    private static @Nullable UmapMeshData readLOD0(UmapReader r, long endPos,
                                                    PackageInfo info, File uasset,
                                                    String[] matImports) throws IOException {
        // FStaticMeshSection array
        int numSections = r.readInt32();
        if (numSections < 0 || numSections > 10000) return null;
        RawSection[] rawSections = new RawSection[numSections];
        for (int s = 0; s < numSections; s++) {
            RawSection rs = new RawSection();
            rs.materialIndex = r.readInt32(); // material index
            rs.firstIndex    = r.readInt32(); // first index
            rs.numTriangles  = r.readInt32(); // num triangles
            r.readInt32(); // min vertex index
            r.readInt32(); // max vertex index
            r.readBool8();  // enable collision
            if (info.versionUE4 >= 488) r.readBool8(); // cast shadow
            if (info.versionUE5 >= 1)   r.readBool8(); // force opaque
            rawSections[s] = rs;
        }

        // Max deviation
        r.readFloat();

        // PositionVertexBuffer
        int posStride  = r.readInt32(); // bytes per vertex (usually 12 UE4, 24 UE5)
        int numVerts   = r.readInt32();
        if (numVerts <= 0 || numVerts > 5_000_000) return null;

        int bulkFlags    = r.readInt32();
        int bulkElements = r.readInt32();
        long bulkSize    = r.readInt64();
        long bulkOffset  = r.readInt64();

        float[] positions = new float[numVerts * 3];
        if ((bulkFlags & 0x10) != 0 || bulkSize == 0) {
            // Separate .ubulk file
            File ubulk = new File(uasset.getParentFile(),
                    uasset.getName().replace(".uasset", ".ubulk"));
            if (!ubulk.exists()) return buildFromBounds(numVerts, info);
            try (UmapReader br = new UmapReader(ubulk)) {
                br.fileVersionUE4 = info.versionUE4;
                br.fileVersionUE5 = info.versionUE5;
                br.seek(bulkOffset);
                readPositions(br, positions, numVerts, posStride, info);
            }
        } else {
            readPositions(r, positions, numVerts, posStride, info);
        }

        // StaticMeshVertexBuffer – contains UVs and tangents
        float[] uvs = readVertexBuffer(r, endPos, info, numVerts);

        // Index buffer
        int numIndices = r.readInt32();
        if (numIndices <= 0 || numIndices > 30_000_000) return null;
        boolean use32bit = (r.readInt32() == 4); // stride 4 = 32-bit, 2 = 16-bit

        int ibBulkFlags2 = r.readInt32();
        r.readInt32(); // element count
        r.readInt64(); // size
        r.readInt64(); // offset

        int[] indices = new int[numIndices];
        if ((ibBulkFlags2 & 0x10) == 0) {
            // Inline
            for (int i = 0; i < numIndices; i++) {
                indices[i] = use32bit ? r.readInt32() : r.readUInt16();
            }
        }

        FBox bounds = computeBounds(positions);
        UmapMeshData mesh = new UmapMeshData(positions, indices, bounds);
        mesh.uvs = uvs; // may be null if extraction failed

        // Build section array – populate materialPath from import table when available
        mesh.sections = new UmapMeshData.Section[numSections];
        for (int s = 0; s < numSections; s++) {
            RawSection rs = rawSections[s];
            String matPath = (matImports != null
                    && rs.materialIndex >= 0
                    && rs.materialIndex < matImports.length)
                    ? matImports[rs.materialIndex] : null;
            mesh.sections[s] = new UmapMeshData.Section(
                    rs.firstIndex,
                    rs.numTriangles * 3,
                    rs.materialIndex,
                    matPath
            );
        }
        return mesh;
    }

    private static void readPositions(UmapReader r, float[] positions,
                                       int numVerts, int stride, PackageInfo info) throws IOException {
        boolean useDouble = info.versionUE5 >= 1;
        for (int v = 0; v < numVerts; v++) {
            if (useDouble) {
                positions[v * 3]     = (float) r.readDouble();
                positions[v * 3 + 1] = (float) r.readDouble();
                positions[v * 3 + 2] = (float) r.readDouble();
            } else {
                positions[v * 3]     = r.readFloat();
                positions[v * 3 + 1] = r.readFloat();
                positions[v * 3 + 2] = r.readFloat();
            }
            // Skip extra bytes if stride > 12 (unlikely for position buffer)
            int extra = stride - (useDouble ? 24 : 12);
            if (extra > 0) r.skipBytes(extra);
        }
    }

    /**
     * Reads the StaticMeshVertexBuffer (tangents + UV channels) that follows the
     * PositionVertexBuffer.  Extracts UV channel 0 as a flat float array
     * (u0,v0, u1,v1, …) and returns it.  Returns {@code null} on any parse error.
     */
    private static float[] readVertexBuffer(UmapReader r, long endPos,
                                             PackageInfo info, int expectedVerts) {
        long savedPos = -1;
        long dataEnd  = -1;
        try {
            savedPos = r.position();
            int numTexCoords = r.readInt32();
            int stride       = r.readInt32();     // bytes per vertex (may be 0 = unset)
            int numVerts     = r.readInt32();
            if (numVerts <= 0 || numVerts > 5_000_000) { r.seek(savedPos); return null; }
            boolean fullUVs      = r.readBool8();   // useFullPrecisionUVs
            boolean highTangents = r.readBool8();   // useHighPrecisionTangentBasis

            int  uvBulkFlags = r.readInt32();
            int  uvBulkElems = r.readInt32();
            long uvBulkSize  = r.readInt64();
            r.readInt64(); // bulk offset
            // ↑ r is now positioned right before the inline vertex data (or at end if external)

            if ((uvBulkFlags & 0x10) != 0 || uvBulkSize == 0) {
                // External .ubulk – no inline data; stream is already at the right position.
                return null;
            }

            // Record the exact span of inline vertex data so we can always seek past it.
            dataEnd = r.position() + uvBulkSize;

            if (numTexCoords <= 0 || numTexCoords > 8) numTexCoords = 1;
            int tangentBytes = highTangents ? 8 : 4;
            int uvBytesEach  = fullUVs ? 8 : 4;

            float[] uvs = new float[numVerts * 2];
            for (int v = 0; v < numVerts; v++) {
                r.skipBytes(tangentBytes * 2); // skip tangentX + tangentZ
                float u, vv;
                if (fullUVs) {
                    u  = r.readFloat();
                    vv = r.readFloat();
                } else {
                    u  = halfToFloat(r.readUInt16());
                    vv = halfToFloat(r.readUInt16());
                }
                uvs[v * 2]     = u;
                uvs[v * 2 + 1] = vv;
                r.skipBytes(uvBytesEach * (numTexCoords - 1));
            }
            // Always seek to the declared end of vertex data to stay aligned for the index buffer.
            r.seek(dataEnd);
            return uvs;

        } catch (IOException e) {
            // Seek to the declared end of vertex data if we managed to read the bulk header.
            try { if (dataEnd > 0) r.seek(dataEnd); }
            catch (IOException ignored) {}
            return null;
        }
    }

    /** Convert a 16-bit half-precision float to a Java float. */
    private static float halfToFloat(int bits) {
        int mant = bits & 0x03FF;
        int exp  = (bits >> 10) & 0x1F;
        int sign = (bits >> 15) & 1;
        if (exp == 0) {
            if (mant == 0) return (sign == 0) ? 0f : -0f;
            while ((mant & 0x0400) == 0) { mant <<= 1; exp--; }
            mant &= 0x03FF; exp++;
        } else if (exp == 31) {
            return (sign == 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }
        return Float.intBitsToFloat((sign << 31) | ((exp + 112) << 23) | (mant << 13));
    }

    // Fallback: create a placeholder mesh (unit cube) when bulk data is external
    private static UmapMeshData buildFromBounds(int vertHint, PackageInfo info) {
        float[] pos = {
            -50f,-50f,-50f,  50f,-50f,-50f,  50f,50f,-50f,  -50f,50f,-50f,
            -50f,-50f, 50f,  50f,-50f, 50f,  50f,50f, 50f,  -50f,50f, 50f
        };
        int[] idx = { 0,1,2, 2,3,0, 4,5,6, 6,7,4, 0,4,7, 7,3,0,
                      1,5,6, 6,2,1, 0,1,5, 5,4,0, 3,2,6, 6,7,3 };
        return new UmapMeshData(pos, idx,
                new FBox(new FVector(-50,-50,-50), new FVector(50,50,50), true));
    }

    /** Scan the raw bytes for recognisable vertex-count markers (best-effort fallback). */
    private static @Nullable UmapMeshData scanForGeometry(UmapReader r, long endPos,
                                                           PackageInfo info, File uasset) {
        return null; // fallback: no geometry found
    }

    // ------------------------------------------------------------------ //
    //  Bound computation
    // ------------------------------------------------------------------ //

    private static FBox computeBounds(float[] positions) {
        if (positions.length == 0) return FBox.EMPTY;
        float minX = positions[0], minY = positions[1], minZ = positions[2];
        float maxX = minX, maxY = minY, maxZ = minZ;
        for (int i = 3; i < positions.length; i += 3) {
            if (positions[i]   < minX) minX = positions[i];
            if (positions[i+1] < minY) minY = positions[i+1];
            if (positions[i+2] < minZ) minZ = positions[i+2];
            if (positions[i]   > maxX) maxX = positions[i];
            if (positions[i+1] > maxY) maxY = positions[i+1];
            if (positions[i+2] > maxZ) maxZ = positions[i+2];
        }
        return new FBox(new FVector(minX, minY, minZ), new FVector(maxX, maxY, maxZ), true);
    }

    // ------------------------------------------------------------------ //
    //  Property list skip
    // ------------------------------------------------------------------ //

    /**
     * Skips the UProperty list at the start of a StaticMesh export.
     * Reads FName pairs (propName, propType), propSize (int64), arrayIndex (int32),
     * type-specific tag extras, then seeks past propSize value bytes.
     * Stops on "None" property name or on IO error.
     */
    private static void skipPropertyList(UmapReader r, long endPos, int vUE4) {
        try {
            while (r.position() < endPos - 8) {
                // propName FName
                int nameIdx = r.readInt32();
                r.readInt32(); // name number
                if (nameIdx < 0 || nameIdx >= r.names.length) return;
                String propName = r.names[nameIdx];
                if ("None".equals(propName)) return;

                // propType FName
                int typeIdx = r.readInt32();
                r.readInt32(); // type name number
                String propType = (typeIdx >= 0 && typeIdx < r.names.length) ? r.names[typeIdx] : "";

                long propSize   = r.readInt64(); // bytes of VALUE (after tag extras)
                r.readInt32();                    // arrayIndex

                // Type-specific tag extras (mirror of UmapPackage.readPropertyTag)
                switch (propType) {
                    case "StructProperty" -> {
                        r.readInt32(); r.readInt32(); // structName FName
                        if (vUE4 >= 441) r.skipFGuid();
                    }
                    case "EnumProperty" -> {
                        r.readInt32(); r.readInt32(); // enum type FName
                        if (vUE4 >= 441) { r.readInt32(); r.readInt32(); } // inner type FName
                    }
                    case "ByteProperty"  -> { r.readInt32(); r.readInt32(); } // enum name FName
                    case "BoolProperty"  -> r.readByte(); // bool in tag
                    case "ArrayProperty" -> {
                        if (vUE4 >= 282) { r.readInt32(); r.readInt32(); } // inner type FName
                        if (vUE4 >= 441) r.skipFGuid();
                    }
                    case "SetProperty" -> {
                        if (vUE4 >= 465) { r.readInt32(); r.readInt32(); } // element type FName
                        if (vUE4 >= 441) r.skipFGuid();
                    }
                    case "MapProperty" -> {
                        if (vUE4 >= 282) {
                            r.readInt32(); r.readInt32(); // key type
                            r.readInt32(); r.readInt32(); // value type
                        }
                        if (vUE4 >= 441) { r.skipFGuid(); r.skipFGuid(); }
                    }
                    default -> { /* no extras */ }
                }

                // Skip value bytes
                if (propSize <= 0 || propSize > 10_000_000) return; // sanity guard
                r.skipBytes((int) propSize);
            }
        } catch (IOException ignored) {}
    }
}
