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
            String[] expClassNames,
            String[] impNames
    ) {}
    private static final ConcurrentHashMap<String, ParsedUE3Package> UE3_CACHE = new ConcurrentHashMap<>();

    // Per-export mesh result cache: key = "filePath#exportIndex", value = parsed mesh (or EMPTY sentinel for failures)
    private static final UmapMeshData EMPTY_SENTINEL = new UmapMeshData(new float[0], new int[0], FBox.EMPTY);
    private static final ConcurrentHashMap<String, UmapMeshData> MESH_RESULT_CACHE = new ConcurrentHashMap<>();
    private static int hexDumpCount = 0;
    private static final int MAX_HEX_DUMPS = 50;

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
                upk.getName() + ":" + expNames[meshExpIdx], pkg.impNames());
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
                names, expOffsets, expSizes, expNames, expClassNames, impNames);
    }

    /**
     * Parses a cooked UDK StaticMesh export from the decompressed ByteArrayUmapReader.
     * Also reads the UE3 property list to extract material references for section.materialPath.
     */
    private static @Nullable UmapMeshData parseUE3MeshExport(ByteArrayUmapReader br,
            long dataOff, long dataSize, int fileVersion, String upkName,
            @Nullable String[] impNames) throws IOException {
        long endPos = dataOff + dataSize;

        // Read material names from the property list before scanning for geometry
        String[] matNames = (impNames != null && br.names != null)
                ? readUE3MaterialNames(br, dataOff, endPos, br.names, impNames)
                : new String[0];

        // Scan the ENTIRE export for vertex/index data
        UmapMeshData mesh = readMeshSimple(br, dataOff, endPos, upkName);

        // Post-process sections: assign materialPath from resolved import names
        if (mesh != null && matNames.length > 0 && mesh.sections != null) {
            UmapMeshData.Section[] oldSecs = mesh.sections;
            UmapMeshData.Section[] newSecs = new UmapMeshData.Section[oldSecs.length];
            for (int i = 0; i < oldSecs.length; i++) {
                UmapMeshData.Section s = oldSecs[i];
                String matName = i < matNames.length ? matNames[i] : matNames[0];
                newSecs[i] = new UmapMeshData.Section(s.firstIndex, s.indexCount, s.materialIndex, matName);
            }
            mesh.sections = newSecs;
        }
        return mesh;
    }

    /**
     * Scans the UE3 property list at the start of a StaticMesh export and returns
     * the resolved names of all entries in the {@code Materials} array.
     * Import references (negative object indices) are resolved via {@code impNames}.
     */
    private static String[] readUE3MaterialNames(ByteArrayUmapReader br,
            long dataOff, long endPos, String[] names, String[] impNames) {
        List<String> mats = new ArrayList<>();
        try {
            br.seek(dataOff);
            while (br.position() < endPos - 8) {
                long pos = br.position();
                int ni = br.readInt32(); br.readInt32();           // propName FName
                if (ni < 0 || ni >= names.length) { br.seek(pos); break; }
                String propName = names[ni];
                if ("None".equals(propName)) break;

                int ti = br.readInt32(); br.readInt32();           // propType FName
                long propSize = br.readInt32() & 0xFFFFFFFFL;     // size in bytes
                br.readInt32();                                    // arrayIndex
                String propType = (ti >= 0 && ti < names.length) ? names[ti] : "";

                // Consume type-specific tag extras
                if ("StructProperty".equals(propType))            { br.readInt32(); br.readInt32(); }
                else if ("ByteProperty".equals(propType))         { br.readInt32(); br.readInt32(); }
                else if ("BoolProperty".equals(propType))         { br.readByte(); propSize = 0; }

                long valStart = br.position();

                if ("Materials".equals(propName) && "ArrayProperty".equals(propType)) {
                    // TArray<MaterialInterface*>: [int32 numElems][int32 objRef × numElems]
                    int numElems = br.readInt32();
                    for (int e = 0; e < numElems && e < 64; e++) {
                        int objRef = br.readInt32();
                        if (objRef < 0) {
                            int impIdx = -objRef - 1;
                            if (impIdx < impNames.length) mats.add(impNames[impIdx]);
                        }
                    }
                    break; // found what we need
                }

                long after = valStart + propSize;
                if (after > endPos || after <= pos) break;
                br.seek(after);
            }
        } catch (Exception e) { /* stop gracefully */ }
        return mats.toArray(new String[0]);
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
            // UVs may live in the FStaticMeshVertexBuffer that immediately follows
            float[] uvs1 = tryParseUE3StaticMeshVB(data, posDataEnd, numV, physEnd);
            return buildMeshResultWithUVs(positions, uvs1, data, numV, idxResult, upkName, patternName);
        }

        // ══════════════════ PASS 2: FStaticMeshVertexBuffer (full header) ════════════════
        // In older UE3 (and A Hat in Time), there's no separate FPositionVertexBuffer.
        // Positions are interleaved inside FStaticMeshVertexBuffer:
        //   [numTexCoords:int32][stride:int32][numVerts:int32][bFullPrec:int32]
        //   [elemSize=stride:int32][count=numVerts:int32][vertex data]
        // Each vertex: float3 pos (12) + PackedNormal tangentX (4) + PackedNormal tangentZ (4)
        //              + optional FColor (4) + numTexCoords × UV (4 half or 8 full)
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            int numTC   = ileInt32(data, p);       // numTexCoords: 1-8
            if (numTC < 1 || numTC > 8) continue;
            int stride  = ileInt32(data, p + 4);   // stride: 24-96
            if (stride < 24 || stride > 96 || (stride & 3) != 0) continue;
            int numV    = ileInt32(data, p + 8);   // numVertices
            if (numV < 3 || numV > 500_000) continue;
            int bFull   = ileInt32(data, p + 12);  // bUseFullPrecisionUVs (any non-zero = true)
            bFull = bFull != 0 ? 1 : 0;

            // Accept stride matching either without or with a per-vertex FColor (+4 bytes)
            int expectedStride    = 12 + 4 + 4 + numTC * (bFull == 1 ? 8 : 4);
            int expectedStrideCol = expectedStride + 4;
            if (stride != expectedStride && stride != expectedStrideCol) continue;

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
            float[] uvs2 = extractUVsInterleavedUE3(data, dataStart, numV, stride, bFull == 1, stride == expectedStrideCol);
            return buildMeshResultWithUVs(positions, uvs2, data, numV, idxResult, upkName, patName);
        }

        // ══════════════════ PASS 2b: FStaticMeshVertexBuffer WITHOUT BulkSerialize wrapper ════
        // Some UE3 builds write [numTC][stride][numV][bFull] then vertex data directly,
        // omitting the [elemSize][count] BulkSerialize header that PASS 2 requires.
        for (int p = physStart & ~3; p <= physEnd - 20; p += 4) {
            int numTC  = ileInt32(data, p);
            if (numTC < 1 || numTC > 8) continue;
            int stride = ileInt32(data, p + 4);
            if (stride < 24 || stride > 96 || (stride & 3) != 0) continue;
            int numV   = ileInt32(data, p + 8);
            if (numV < 3 || numV > 500_000) continue;
            int bFull  = ileInt32(data, p + 12);
            bFull = bFull != 0 ? 1 : 0;
            int expectedStride    = 12 + 4 + 4 + numTC * (bFull == 1 ? 8 : 4);
            int expectedStrideCol = expectedStride + 4;
            if (stride != expectedStride && stride != expectedStrideCol) continue;
            long totalBytes = (long) numV * stride;
            int dataStart = p + 16;
            if (dataStart + totalBytes > physEnd) continue;
            if (!checkInterleavedFloat3(data, dataStart, numV, stride, physEnd)) continue;
            float[] positions = readInterleavedPositions(data, dataStart, numV, stride);
            if (!hasSpatialExtent(positions, numV)) continue;
            int vbEnd = (int)(dataStart + totalBytes);
            int[] idxResult = findFirstValidIndexBuffer(data, vbEnd, physEnd, numV);
            if (idxResult == null) continue;
            String patName = "SMVB_direct_tc" + numTC + "_s" + stride;
            float[] uvs2b = extractUVsInterleavedUE3(data, dataStart, numV, stride, bFull == 1, stride == expectedStrideCol);
            return buildMeshResultWithUVs(positions, uvs2b, data, numV, idxResult, upkName, patName);
        }

        // ══════════════════ PASS 3: Interleaved BulkSerialize (bare) ═══════════════════════
        // [elemSize=S][count][data] where S > 12 and first 12 bytes per elem are positions
        for (int p = physStart & ~3; p <= physEnd - 32; p += 4) {
            int elemSize = ileInt32(data, p);
            if (elemSize < 20 || elemSize > 96 || (elemSize & 3) != 0) continue;
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
            if (stride < 20 || stride > 96 || (stride & 3) != 0) continue;
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
        // [numTC:1-8][stride:24-96][numV:3-500k][bFull:0|1][1][numV*stride][data]
        for (int p = physStart & ~3; p <= physEnd - 28; p += 4) {
            int numTC  = ileInt32(data, p);
            if (numTC < 1 || numTC > 8) continue;
            int stride = ileInt32(data, p + 4);
            if (stride < 20 || stride > 96 || (stride & 3) != 0) continue;
            int numV   = ileInt32(data, p + 8);
            if (numV < 3 || numV > 500_000) continue;
            int bFull  = ileInt32(data, p + 12);
            bFull = bFull != 0 ? 1 : 0;
            int expectedStride7    = 12 + 4 + 4 + numTC * (bFull == 1 ? 8 : 4);
            int expectedStrideCol7 = expectedStride7 + 4;
            if (stride != expectedStride7 && stride != expectedStrideCol7) continue;
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
            float[] uvs7 = extractUVsInterleavedUE3(data, dataStart, numV, stride, bFull == 1, stride == expectedStrideCol7);
            return buildMeshResultWithUVs(positions, uvs7, data, numV, idxResult, upkName, patName);
        }

        // ══════════════ PASS 8: Index-first brute-force ════════════════════════════════
        // Scans for uint16 index buffers using TWO patterns:
        //   Pattern A: BulkSerialize  [elemSize=2][count][uint16*count]
        //   Pattern B: Bare TArray    [count][uint16*count]  (no elemSize prefix)
        // Pattern A and B are collected into SEPARATE pools so that the far more
        // numerous Pattern-B false positives (any int in [9..30000] divisible by 3)
        // cannot crowd out a real Pattern-A hit that appears later in large exports.
        // Pattern A is tried first; Pattern B is a secondary fallback.
        {
            // Pattern A: [2][n][data] — relatively rare in random binary, so allow up to 256
            int[][] idxCandA = new int[256][3]; // [dataPhys, count, maxIdx]
            int numCandA = 0;
            // Pattern B: bare [n][data] — very noisy; limit to 64 as secondary attempt
            int[][] idxCandB = new int[64][3];
            int numCandB = 0;

            for (int q = physStart & ~3; q <= physEnd - 8; q += 4) {
                // ── Pattern A: BulkSerialize [elemSize=2][count][data] ──────
                if (numCandA < 256 && ileInt32(data, q) == 2) {
                    int n = ileInt32(data, q + 4);
                    if (n >= 9 && n <= 2_000_000 && (n % 3) == 0
                            && q + 8 + (long) n * 2 <= physEnd) {
                        int maxI = 0;
                        for (int i = 0; i < n; i++) {
                            int v = ileUInt16(data, q + 8 + i * 2);
                            if (v > maxI) maxI = v;
                        }
                        if (maxI >= 2 && maxI < 500_000) {
                            idxCandA[numCandA][0] = q + 8;
                            idxCandA[numCandA][1] = n;
                            idxCandA[numCandA][2] = maxI;
                            numCandA++;
                        }
                    }
                }

                // ── Pattern B: Bare TArray [count][data] (no [2] prefix) ───
                if (numCandB < 64) {
                    int n = ileInt32(data, q);
                    if (n >= 9 && n <= 2_000_000 && (n % 3) == 0
                            && q + 4 + (long) n * 2 <= physEnd) {
                        int maxI = 0;
                        for (int i = 0; i < n; i++) {
                            int v = ileUInt16(data, q + 4 + i * 2);
                            if (v > maxI) maxI = v;
                        }
                        if (maxI >= 2 && maxI < 500_000) {
                            // Avoid duplicating a Pattern-A hit at the same data offset
                            boolean alreadyFoundB = false;
                            for (int ci = 0; ci < numCandA; ci++) {
                                if (idxCandA[ci][0] == q + 4) { alreadyFoundB = true; break; }
                            }
                            if (!alreadyFoundB) {
                                idxCandB[numCandB][0] = q + 4;
                                idxCandB[numCandB][1] = n;
                                idxCandB[numCandB][2] = maxI;
                                numCandB++;
                            }
                        }
                    }
                }
            }

            // Merge: A candidates first, then B
            int totalCand = numCandA + numCandB;
            int[][] idxCandidates = new int[totalCand][3];
            System.arraycopy(idxCandA, 0, idxCandidates, 0, numCandA);
            System.arraycopy(idxCandB, 0, idxCandidates, numCandA, numCandB);

            for (int ci = 0; ci < totalCand; ci++) {
                int idxDataPhys = idxCandidates[ci][0];
                int idxCount    = idxCandidates[ci][1];
                int maxIdx      = idxCandidates[ci][2];
                int numV        = maxIdx + 1;

                // Search entire export for matching float3 positions
                for (int testStride : new int[]{12, 16, 20, 24, 28, 32, 36, 40, 44, 48}) {
                    long needed = (long) numV * testStride;
                    if (needed > physEnd - physStart) continue;

                    for (int p = physStart & ~3; p + needed <= physEnd; p += 4) {
                        // Avoid the index buffer region itself
                        if (p + needed > idxDataPhys - 8 && p < idxDataPhys + idxCount * 2) continue;

                        // FULLY validate all vertices (not just samples) to avoid garbage hits
                        boolean posOk;
                        if (testStride == 12) {
                            posOk = checkAllFloat3s(data, p, numV, physEnd);
                        } else {
                            posOk = checkAllInterleavedFloat3s(data, p, numV, testStride, physEnd);
                        }
                        if (!posOk) continue;

                        float[] positions = (testStride == 12)
                                ? readFloat3Array(data, p, numV)
                                : readInterleavedPositions(data, p, numV, testStride);
                        if (!hasSpatialExtent(positions, numV)) continue;

                        FBox bounds = computeBounds(positions);
                        // Reject implausibly large per-axis extents (world-space data leaked in)
                        double extX = bounds.max().x() - bounds.min().x();
                        double extY = bounds.max().y() - bounds.min().y();
                        double extZ = bounds.max().z() - bounds.min().z();
                        if (extX > 100_000 || extY > 100_000 || extZ > 100_000) continue;
                        // Reject if centroid is far from origin (world-space coordinates)
                        double ctrX = (bounds.max().x() + bounds.min().x()) * 0.5;
                        double ctrY = (bounds.max().y() + bounds.min().y()) * 0.5;
                        double ctrZ = (bounds.max().z() + bounds.min().z()) * 0.5;
                        if (Math.abs(ctrX) > 50_000 || Math.abs(ctrY) > 50_000 || Math.abs(ctrZ) > 50_000) continue;

                        // Reject wireframe edge-pair buffers (>50% degenerate triangles)
                        if (idxCount > 6) {
                            int degCnt = 0;
                            for (int t = 0; t + 2 < idxCount; t += 3) {
                                int v0 = ileUInt16(data, idxDataPhys + t * 2);
                                int v1 = ileUInt16(data, idxDataPhys + (t + 1) * 2);
                                int v2 = ileUInt16(data, idxDataPhys + (t + 2) * 2);
                                if (v0 == v1 || v1 == v2 || v0 == v2) degCnt++;
                            }
                            if (degCnt * 2 > idxCount / 3) continue;
                        }

                        Umapica.LOGGER.info("[Umapica] UE3 '{}': BruteForce_s{} verts={} tris={} bounds={}",
                                upkName, testStride, numV, idxCount / 3, bounds);
                        int[] indices = readUint16Array(data, idxDataPhys, idxCount);
                        UmapMeshData mesh = new UmapMeshData(positions, indices, bounds);
                        mesh.sections = new UmapMeshData.Section[]{
                                new UmapMeshData.Section(0, idxCount, 0, null)};
                        return mesh;
                    }
                }
            }
        }

        // ══════════════ PASS 9: uint32 index-first brute-force ════════════════════════════
        // Identical to PASS 8 but for 4-byte index elements.  Handles large meshes that
        // exceed 65535 unique vertices (uint32 required) or builds whose FRawStaticIndexBuffer
        // was serialised with TArray<uint32> instead of TArray<uint16>.
        //   Pattern A9: BulkSerialize [elemSize=4][count][uint32*count]
        //   Pattern B9: Bare TArray   [count][uint32*count]
        {
            int[][] idxCandA9 = new int[256][3];
            int numCandA9 = 0;
            int[][] idxCandB9 = new int[64][3];
            int numCandB9 = 0;

            for (int q = physStart & ~3; q <= physEnd - 8; q += 4) {
                if (numCandA9 < 256 && ileInt32(data, q) == 4) {
                    int n = ileInt32(data, q + 4);
                    if (n >= 9 && n <= 2_000_000 && (n % 3) == 0
                            && q + 8 + (long) n * 4 <= physEnd) {
                        int maxI = 0; boolean valid = true;
                        for (int i = 0; i < n; i++) {
                            int v = ileInt32(data, q + 8 + i * 4);
                            if (v < 0 || v >= 500_000) { valid = false; break; }
                            if (v > maxI) maxI = v;
                        }
                        if (valid && maxI >= 2) {
                            idxCandA9[numCandA9][0] = q + 8;
                            idxCandA9[numCandA9][1] = n;
                            idxCandA9[numCandA9][2] = maxI;
                            numCandA9++;
                        }
                    }
                }
                if (numCandB9 < 64) {
                    int n = ileInt32(data, q);
                    if (n >= 9 && n <= 2_000_000 && (n % 3) == 0
                            && q + 4 + (long) n * 4 <= physEnd) {
                        int maxI = 0; boolean valid = true;
                        for (int i = 0; i < n; i++) {
                            int v = ileInt32(data, q + 4 + i * 4);
                            if (v < 0 || v >= 500_000) { valid = false; break; }
                            if (v > maxI) maxI = v;
                        }
                        if (valid && maxI >= 2) {
                            boolean already = false;
                            for (int ci = 0; ci < numCandA9; ci++) {
                                if (idxCandA9[ci][0] == q + 4) { already = true; break; }
                            }
                            if (!already) {
                                idxCandB9[numCandB9][0] = q + 4;
                                idxCandB9[numCandB9][1] = n;
                                idxCandB9[numCandB9][2] = maxI;
                                numCandB9++;
                            }
                        }
                    }
                }
            }

            int totalCand9 = numCandA9 + numCandB9;
            int[][] idxCand9 = new int[totalCand9][3];
            System.arraycopy(idxCandA9, 0, idxCand9, 0, numCandA9);
            System.arraycopy(idxCandB9, 0, idxCand9, numCandA9, numCandB9);

            for (int ci = 0; ci < totalCand9; ci++) {
                int idxDataPhys9 = idxCand9[ci][0];
                int idxCount9    = idxCand9[ci][1];
                int maxIdx9      = idxCand9[ci][2];
                int numV9        = maxIdx9 + 1;

                for (int testStride : new int[]{12, 16, 20, 24, 28, 32, 36, 40, 44, 48}) {
                    long needed9 = (long) numV9 * testStride;
                    if (needed9 > physEnd - physStart) continue;

                    for (int p = physStart & ~3; p + needed9 <= physEnd; p += 4) {
                        if (p + needed9 > idxDataPhys9 - 8 && p < idxDataPhys9 + idxCount9 * 4) continue;

                        boolean posOk9 = (testStride == 12)
                                ? checkAllFloat3s(data, p, numV9, physEnd)
                                : checkAllInterleavedFloat3s(data, p, numV9, testStride, physEnd);
                        if (!posOk9) continue;

                        float[] positions9 = (testStride == 12)
                                ? readFloat3Array(data, p, numV9)
                                : readInterleavedPositions(data, p, numV9, testStride);
                        if (!hasSpatialExtent(positions9, numV9)) continue;

                        FBox bounds9 = computeBounds(positions9);
                        double extX9 = bounds9.max().x() - bounds9.min().x();
                        double extY9 = bounds9.max().y() - bounds9.min().y();
                        double extZ9 = bounds9.max().z() - bounds9.min().z();
                        if (extX9 > 100_000 || extY9 > 100_000 || extZ9 > 100_000) continue;
                        double ctrX9 = (bounds9.max().x() + bounds9.min().x()) * 0.5;
                        double ctrY9 = (bounds9.max().y() + bounds9.min().y()) * 0.5;
                        double ctrZ9 = (bounds9.max().z() + bounds9.min().z()) * 0.5;
                        if (Math.abs(ctrX9) > 50_000 || Math.abs(ctrY9) > 50_000 || Math.abs(ctrZ9) > 50_000) continue;

                        // Reject wireframe-like buffers (>50% degenerate triangles)
                        if (idxCount9 > 6) {
                            int deg9 = 0;
                            for (int t = 0; t + 2 < idxCount9; t += 3) {
                                int v0 = ileInt32(data, idxDataPhys9 + t * 4);
                                int v1 = ileInt32(data, idxDataPhys9 + (t + 1) * 4);
                                int v2 = ileInt32(data, idxDataPhys9 + (t + 2) * 4);
                                if (v0 == v1 || v1 == v2 || v0 == v2) deg9++;
                            }
                            if (deg9 * 2 > idxCount9 / 3) continue;
                        }

                        Umapica.LOGGER.info("[Umapica] UE3 '{}': BruteForce32_s{} verts={} tris={} bounds={}",
                                upkName, testStride, numV9, idxCount9 / 3, bounds9);
                        int[] indices9 = readUint32Array(data, idxDataPhys9, idxCount9);
                        UmapMeshData mesh9 = new UmapMeshData(positions9, indices9, bounds9);
                        mesh9.sections = new UmapMeshData.Section[]{
                                new UmapMeshData.Section(0, idxCount9, 0, null)};
                        return mesh9;
                    }
                }
            }
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
        return buildMeshResultWithUVs(positions, null, data, numV, idxResult, upkName, patternName);
    }

    /** Like {@link #buildMeshResult} but also sets {@code mesh.uvs} (trimmed to match verts). */
    private static UmapMeshData buildMeshResultWithUVs(float[] positions, @Nullable float[] uvs,
            byte[] data, int numV, int[] idxResult, String upkName, String patternName) {
        int idxDataPhys = idxResult[0], idxCount = idxResult[1], maxIdx = idxResult[2];
        int[] indices = readUint16Array(data, idxDataPhys, idxCount);

        // Trim positions (and UVs) to highest-referenced vertex
        int trueVerts = maxIdx + 1;
        if (trueVerts < numV) {
            float[] trimmed = new float[trueVerts * 3];
            System.arraycopy(positions, 0, trimmed, 0, trueVerts * 3);
            positions = trimmed;
            if (uvs != null) {
                float[] ut = new float[trueVerts * 2];
                System.arraycopy(uvs, 0, ut, 0, Math.min(uvs.length, trueVerts * 2));
                uvs = ut;
            }
            numV = trueVerts;
        }

        FBox bounds = computeBounds(positions);
        Umapica.LOGGER.info("[Umapica] UE3 '{}': {} verts={} tris={} uvs={} bounds={}",
                upkName, patternName, numV, idxCount / 3, uvs != null ? "yes" : "no", bounds);
        UmapMeshData mesh = new UmapMeshData(positions, indices, bounds);
        mesh.uvs = uvs;
        mesh.sections = new UmapMeshData.Section[]{
                new UmapMeshData.Section(0, idxCount, 0, null)
        };
        return mesh;
    }

    /**
     * Extracts UV channel 0 from a UE3 interleaved vertex buffer where each vertex is:
     * [pos:12][tangentX:4][tangentZ:4][?FColor:4][UV0:4 half or 8 full][otherUVs...]
     */
    private static float[] extractUVsInterleavedUE3(byte[] data, int dataStart,
            int numV, int stride, boolean fullUVs, boolean hasColor) {
        int uvOff = 12 + 4 + 4 + (hasColor ? 4 : 0); // 20 (no color) or 24 (with color)
        float[] uvs = new float[numV * 2];
        for (int v = 0; v < numV; v++) {
            int base = dataStart + v * stride + uvOff;
            float u, vv;
            if (fullUVs) {
                u  = ileFloat(data, base);
                vv = ileFloat(data, base + 4);
            } else {
                u  = halfToFloat(ileUInt16(data, base));
                vv = halfToFloat(ileUInt16(data, base + 2));
            }
            uvs[v * 2]     = u;
            uvs[v * 2 + 1] = vv;
        }
        return uvs;
    }

    /**
     * Tries to parse a UE3 {@code FStaticMeshVertexBuffer} header at {@code offset} and
     * extract UV channel 0.  Used in PASS 1 where a separate {@code FPositionVertexBuffer}
     * precedes the UV buffer.  Returns {@code null} if validation fails.
     */
    private static @Nullable float[] tryParseUE3StaticMeshVB(byte[] data, int offset,
            int expectedVerts, int physEnd) {
        try {
            if (offset < 0 || offset + 24 > physEnd) return null;
            int numTC  = ileInt32(data, offset);
            if (numTC < 1 || numTC > 8) return null;
            int stride = ileInt32(data, offset + 4);
            if (stride < 24 || stride > 96 || (stride & 3) != 0) return null;
            int numV   = ileInt32(data, offset + 8);
            if (numV != expectedVerts) return null;
            int bFull  = ileInt32(data, offset + 12) != 0 ? 1 : 0;
            int expected    = 12 + 4 + 4 + numTC * (bFull == 1 ? 8 : 4);
            boolean hasColor = (stride == expected + 4);
            if (stride != expected && !hasColor) return null;
            // BulkSerialize header: [elemSize=stride][count=numV]
            int elemSz = ileInt32(data, offset + 16);
            int count  = ileInt32(data, offset + 20);
            if (elemSz != stride || count != numV) return null;
            long totalBytes = (long) numV * stride;
            int  dataStart  = offset + 24;
            if (dataStart + totalBytes > physEnd) return null;
            return extractUVsInterleavedUE3(data, dataStart, numV, stride, bFull == 1, hasColor);
        } catch (Exception e) { return null; }
    }

    // ── readMeshSimple helper methods ─────────────────────────────────────

    /** Spot-check first few + last few float3s are finite and in range. */
    /**
     * Validates ALL float3s in a contiguous position array (no sampling).
     * More expensive than the sampled version; used in PASS 8 to eliminate garbage hits.
     */
    private static boolean checkAllFloat3s(byte[] data, int dataStart, int numV, int physEnd) {
        if (dataStart + (long) numV * 12 > physEnd) return false;
        for (int v = 0; v < numV; v++) {
            if (!isValidFloat3(data, dataStart + v * 12)) return false;
        }
        return true;
    }

    /**
     * Validates ALL float3s at stride-offset-0 in an interleaved vertex buffer (no sampling).
     * Used in PASS 8 to eliminate garbage hits.
     */
    private static boolean checkAllInterleavedFloat3s(byte[] data, int dataStart,
            int numV, int stride, int physEnd) {
        if (dataStart + (long) numV * stride > physEnd) return false;
        for (int v = 0; v < numV; v++) {
            if (!isValidFloat3(data, dataStart + v * stride)) return false;
        }
        return true;
    }

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

    /** Reads numIdx × uint32 from a byte array starting at offset (stored as signed int, safe for values < 2^31). */
    private static int[] readUint32Array(byte[] data, int offset, int numIdx) {
        int[] arr = new int[numIdx];
        for (int i = 0; i < numIdx; i++) {
            arr[i] = ileInt32(data, offset + i * 4);
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

        // ── PHASE 1: BulkSerialize and FBulkData patterns ────────────────────
        // UE3 FRawStaticIndexBuffer::Serialize always writes [int32=2][count][uint16*count].
        // Patterns 3/4 require sizeOnDisk == elemCnt*2 which is also highly specific.
        // These have very low false-positive rates even inside tangent/UV vertex data,
        // so we scan the entire range with these patterns first.
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

        // ── PHASE 2: Bare TArray [count][uint16*count] – fallback only ────────
        // This pattern has a high false-positive rate: a 4-byte value that happens
        // to be a valid count can appear anywhere inside FStaticMeshVertexBuffer's
        // packed tangent/UV data, producing wrong-corner triangles.  Only attempt
        // this if Phase 1 found nothing valid.
        for (int q = searchStart & ~3; q <= limit - 8; q += 4) {
            int n = ileInt32(data, q);
            if (n >= 3 && n <= 1_000_000 && (n % 3) == 0
                    && q + 4 + (long) n * 2 <= searchEnd) {
                int[] r = validateIndexBuffer(data, q + 4, n, numVerts);
                if (r != null) return r;
            }
        }

        return null;
    }

    /**
     * Validates a candidate uint16 index buffer: every index must be &lt; numVerts,
     * at least 3 distinct vertex indices must appear, and fewer than half of the
     * triangles may be degenerate (same vertex twice), filtering out UE3 wireframe
     * edge-pair buffers that come before the real render index buffer in the stream.
     * Returns {@code int[]{dataPhys, count, maxIndex}} on success, {@code null} otherwise.
     */
    private static int @Nullable [] validateIndexBuffer(byte[] data, int dataPhys,
                                                         int count, int numVerts) {
        int maxIdx = 0, degenTris = 0, totalTris = 0;
        for (int i = 0; i < count; i++) {
            int v = ileUInt16(data, dataPhys + i * 2);
            if (v >= numVerts) return null;
            if (v > maxIdx) maxIdx = v;
            if (i % 3 == 2) {
                int v0 = ileUInt16(data, dataPhys + (i - 2) * 2);
                int v1 = ileUInt16(data, dataPhys + (i - 1) * 2);
                if (v0 == v1 || v1 == v || v0 == v) degenTris++;
                totalTris++;
            }
        }
        if (maxIdx < 2) return null;
        // Reject if >50% of triangles are degenerate (wireframe edge-pair pattern)
        if (totalTris > 4 && degenTris * 2 > totalTris) return null;
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
