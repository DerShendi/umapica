package net.shendi.umapica.umap;

import net.shendi.umapica.Config;
import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

/**
 * Parses a .umap (Unreal Engine 4 / 5 package) file and extracts all placed
 * actors with their world-space transforms.
 *
 * <p>Supports UE4.16 – UE4.27 and UE5.0 – UE5.latest.
 *
 * <h2>Binary layout summary</h2>
 * <pre>
 *   Header  → NameTable (FString[])  → ImportTable → ExportTable → ExportData
 * </pre>
 *
 * <h2>Actor extraction strategy</h2>
 * For each export whose resolved class is a subclass of {@code Actor}, we:
 * <ol>
 *   <li>Read its serialized UProperty list.</li>
 *   <li>Locate its {@code RootComponent} object-property reference.</li>
 *   <li>Read that component's RelativeLocation / RelativeRotation / RelativeScale3D.</li>
 *   <li>For {@code StaticMeshComponent} exports, also capture the mesh soft-path.</li>
 * </ol>
 */
public class UmapPackage {

    // ------------------------------------------------------------------ //
    //  UE version constants referenced throughout the parser
    // ------------------------------------------------------------------ //
    private static final int VER_UE4_NAME_HASHES                       = 378;
    private static final int VER_UE4_64BIT_EXPORTMAP_SIZES             = 196;
    private static final int VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG       = 441;
    private static final int VER_UE4_PROPERTY_TAG_INNER_ARRAY_TYPE     = 282;
    private static final int VER_UE4_PROPERTY_TAG_SET_ARRAY_SIZE_TYPE  = 465;
    private static final int VER_UE4_ADDED_PACKAGE_OWNER               = 518;
    private static final int VER_UE4_ADDED_LOCALIZATION_ID             = 516;
    private static final int VER_UE4_NON_OUTER_PACKAGE_IMPORT          = 520;
    private static final int VER_UE5_LARGE_WORLD_COORDS                = 1;
    private static final int VER_UE5_OPTIONAL_RESOURCES                = 3;
    private static final int VER_UE5_FSOFTOBJECTPATH_NO_ASSET_PATH     = 9;

    public static final long MAGIC = 0x9E2A83C1L;

    // ------------------------------------------------------------------ //
    //  Package-level data
    // ------------------------------------------------------------------ //
    public final File        file;
    public final List<UmapActor> actors = new ArrayList<>();
    /** Flat name table (index → name string). */
    public String[] nameTable = new String[0];
    /** Import object names by index (negative FPackageIndex maps here). */
    public String[] importNames = new String[0];
    /** Import outer indices (FPackageIndex) by import index; used to walk the package chain. */
    public int[]    importOuters = new int[0];
    /** Import class names (e.g. "StaticMesh", "Package") by import index. */
    public String[] importClassNames = new String[0];
    /** Export class-index (FPackageIndex, may be negative → import) by export index. */
    public int[]    exportClassIndex = new int[0];
    /** Export object names by export index. */
    public String[] exportNames = new String[0];
    /** Export serial offsets/sizes by export index. */
    public long[]   exportOffset = new long[0];
    public long[]   exportSize   = new long[0];
    /** Outer index for each export (FPackageIndex). */
    public int[]    exportOuterIndex = new int[0];

    private int fileVersionUE4;
    private int fileVersionUE5;

    public UmapPackage(File file) {
        this.file = file;
    }

    // ======================================================= //
    //  Public API
    // ======================================================= //

    /**
     * Parse the package and populate {@link #actors}.
     *
     * @throws IOException  if the file cannot be read or has an unexpected format.
     * @throws IllegalArgumentException if the file magic is wrong.
     */
    public void load() throws IOException {
        try (UmapReader r = new UmapReader(file)) {
            readHeader(r);
            if (isUE3Compressed) {
                // All table/data reads go through the decompressed in-memory reader
                readNameTableUE3(ue3Reader);
                readImportTableUE3(ue3Reader);
                readExportTableUE3(ue3Reader);
                extractActorsUE3(ue3Reader);
            } else {
                readNameTable(r);
                readImportTable(r);
                readExportTable(r);
                extractActors(r);
            }
        }
        Umapica.LOGGER.info("[Umapica] Loaded {} actors from {}", actors.size(), file.getName());
    }

    // ======================================================= //
    //  Header
    // ======================================================= //

    /** Internal offsets parsed from the header – used to seek to each table. */
    private int  nameCount;   private long nameOff;
    private int  importCount; private long importOff;
    private int  exportCount; private long exportOff;

    // UE3 LZO compressed-package state
    private boolean            isUE3Compressed = false;
    private ByteArrayUmapReader ue3Reader       = null;

    /** Holds the two-field FCompressedChunk descriptor from the UE3 header. */
    private static class CompressedChunk {
        long uncompressedOffset; // virtual start address of this chunk's data
        long uncompressedSize;
        long compressedOffset;   // file offset of the compressed block
        long compressedSize;
    }

    private void readHeader(UmapReader r) throws IOException {
        long magic = r.readUInt32();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                String.format("Not a UE package file (bad magic: 0x%08X expected 0x%08X)", magic, MAGIC));
        }

        int legacyFileVersion = r.readInt32();

        Umapica.LOGGER.info("[Umapica] Header: legacyVer={} pos={}", legacyFileVersion, r.position());

        if (legacyFileVersion >= 0) {
            // ── UE3 / old-style legacy format ─────────────────────────────────
            // Layout (confirmed for mafia_town.umap):
            //   magic(4) + FileVersion(4) + LicenseeVersion(4) + FolderName(FString)
            //   + PackageFlags(4) + NameCount(4) + NameOffset(4)
            //   + ExportCount(4) + ExportOffset(4) + ImportCount(4) + ImportOffset(4)
            //   + DependsOffset(4) + SoftPkgRefsOffset(4) + 12 zero bytes
            //   + GUID(16) + GenerationCount(4) + generations[i]{ ExportCount+NameCount }
            //   + EngineVersion(4) + CookerVersion(4) + PackageSource(4)
            //   + CompressionFlags(4) + CompressedChunkCount(4)
            //   + chunks[i]{ uncompOff(4)+uncompSz(4)+compOff(4)+compSz(4) }
            fileVersionUE4 = legacyFileVersion;
            fileVersionUE5 = 0;
            r.fileVersionUE4 = fileVersionUE4;
            r.fileVersionUE5 = fileVersionUE5;

            r.readInt32();   // LicenseeVersion – discard
            r.readFString(); // FolderName – discard
            r.readInt32();   // PackageFlags – discard

            nameCount  = r.readInt32();
            nameOff    = r.readUInt32();
            exportCount = r.readInt32();
            exportOff   = r.readUInt32();
            importCount = r.readInt32();
            importOff   = r.readUInt32();

            Umapica.LOGGER.info("[Umapica] UE3-format: nameCount={} nameOff={} exportCount={} exportOff={} importCount={} importOff={}",
                    nameCount, nameOff, exportCount, exportOff, importCount, importOff);

            // Continue reading fields after importOff
            r.readInt32(); // DependsOffset
            r.readInt32(); // SoftPkgRefsOffset
            r.skipBytes(12); // 3 × int32 (zeroed padding)
            r.skipFGuid();   // GUID (16 bytes)
            int genCount = r.readInt32();
            for (int g = 0; g < genCount; g++) {
                r.readInt32(); // generation.ExportCount
                r.readInt32(); // generation.NameCount
            }
            r.readInt32(); // EngineVersion
            r.readInt32(); // CookerVersion
            r.readInt32(); // PackageSource

            int compressionFlags = r.readInt32();
            int chunkCount = r.readInt32();

            Umapica.LOGGER.info("[Umapica] UE3: compressionFlags=0x{} chunkCount={}",
                    Integer.toHexString(compressionFlags), chunkCount);

            if (compressionFlags != 0 && chunkCount > 0) {
                // Read the FCompressedChunk table
                List<CompressedChunk> chunks = new ArrayList<>(chunkCount);
                for (int c = 0; c < chunkCount; c++) {
                    CompressedChunk cc = new CompressedChunk();
                    cc.uncompressedOffset = r.readUInt32();
                    cc.uncompressedSize   = r.readUInt32();
                    cc.compressedOffset   = r.readUInt32();
                    cc.compressedSize     = r.readUInt32();
                    chunks.add(cc);
                    Umapica.LOGGER.info("[Umapica]   chunk[{}]: uncompOff={} uncompSz={} compOff={} compSz={}",
                            c, cc.uncompressedOffset, cc.uncompressedSize, cc.compressedOffset, cc.compressedSize);
                }
                // Build virtual decompressed stream
                byte[] decompData = decompressUE3Chunks(r, chunks);
                long   baseVirt   = chunks.get(0).uncompressedOffset;
                ue3Reader = new ByteArrayUmapReader(decompData, baseVirt);
                ue3Reader.fileVersionUE4 = fileVersionUE4;
                ue3Reader.fileVersionUE5 = fileVersionUE5;
                isUE3Compressed = true;
                Umapica.LOGGER.info("[Umapica] UE3 decompressed {} bytes, baseVirt={}",
                        decompData.length, baseVirt);
            }
            return;
        }

        // ── UE4 / UE5 format (legacyFileVersion is negative) ──────────────────
        if (legacyFileVersion != -4) {
            r.readInt32(); // LegacyUE3Version – discard
        }

        fileVersionUE4 = r.readInt32();
        fileVersionUE5 = 0;
        if (legacyFileVersion <= -8) {
            fileVersionUE5 = r.readInt32();
        }
        if (fileVersionUE4 == -1) {
            fileVersionUE4 = 522;
            if (fileVersionUE5 == 0) fileVersionUE5 = 10;
        }

        r.fileVersionUE4 = fileVersionUE4;
        r.fileVersionUE5 = fileVersionUE5;

        Umapica.LOGGER.info("[Umapica] UE4-format: ue4ver={} ue5ver={} pos={}",
                fileVersionUE4, fileVersionUE5, r.position());

        r.readInt32(); // FileVersionLicenseeUE – discard

        // Custom version container: TArray<FCustomVersion>
        // ue4ver >= 518: Optimized format = FGuid(16) + int32 Version(4) (no FriendlyName)
        // ue4ver <  518: Guids format     = FGuid(16) + int32 Version(4) + FString FriendlyName
        boolean customHasFriendlyName = (fileVersionUE4 < 518);
        int customCount = r.readInt32();
        Umapica.LOGGER.info("[Umapica] customCount={} hasFriendlyName={} pos={}",
                customCount, customHasFriendlyName, r.position());
        for (int i = 0; i < customCount; i++) {
            r.skipBytes(16); // FGuid
            r.readInt32();   // Version
            if (customHasFriendlyName) {
                r.readFString(); // FriendlyName
            }
        }

        Umapica.LOGGER.info("[Umapica] After custom versions pos={}", r.position());
        int totalHeaderSize = r.readInt32();
        String folderName   = r.readFString();
        int packageFlags    = r.readInt32();

        nameCount = r.readInt32();
        nameOff   = r.readUInt32();

        Umapica.LOGGER.info("[Umapica] totalHeaderSize={} folder='{}' flags=0x{} nameCount={} nameOff={}",
                totalHeaderSize, folderName, Integer.toHexString(packageFlags), nameCount, nameOff);

        // UE5.1+ adds SoftObjectPaths table just after name table offset
        if (fileVersionUE5 >= VER_UE5_FSOFTOBJECTPATH_NO_ASSET_PATH) {
            r.readInt32(); // softObjectPathsCount
            r.readInt32(); // softObjectPathsOffset
        }

        if (fileVersionUE4 >= VER_UE4_ADDED_LOCALIZATION_ID) {
            r.readFString(); // LocalizationId – discard
        }

        if (fileVersionUE4 >= VER_UE4_ADDED_LOCALIZATION_ID) {
            r.readInt32(); // GatherableTextDataCount  – discard
            r.readInt32(); // GatherableTextDataOffset – discard
        }

        exportCount = r.readInt32();
        exportOff   = r.readUInt32();
        importCount = r.readInt32();
        importOff   = r.readUInt32();
        Umapica.LOGGER.info("[Umapica] exportCount={} exportOff={} importCount={} importOff={}",
                exportCount, exportOff, importCount, importOff);
    }

    // ======================================================= //
    //  UE3 LZO chunk decompression
    // ======================================================= //

    /**
     * UE3 compressed chunk block magic: 0x9E2A83C1 (same as package magic – reused in block header).
     */
    private static final long LZO_BLOCK_MAGIC    = 0x9E2A83C1L;
    private static final int  LZO_DEFAULT_BLOCK  = 131072; // 128 KiB

    /**
     * Decompresses all UE3 FCompressedChunk blocks using LZO1X into a single contiguous
     * byte array representing virtual addresses
     * {@code [chunks[0].uncompressedOffset .. chunks[last].uncompressedOffset+uncompressedSize)}.
     *
     * <p>Each chunk's compressed data has the internal sub-block format:
     * <pre>
     *   magic(4) + blockSize(4) + compressedTotal(4) + uncompressedTotal(4)
     *   + numSub × { compressedSz(4) + uncompressedSz(4) }
     *   + numSub × compressedData
     * </pre>
     */
    private byte[] decompressUE3Chunks(UmapReader r, List<CompressedChunk> chunks) throws IOException {
        // Calculate total decompressed size
        long totalDecomp = 0;
        for (CompressedChunk cc : chunks) totalDecomp += cc.uncompressedSize;
        if (totalDecomp > Integer.MAX_VALUE)
            throw new IOException("UE3: decompressed data too large: " + totalDecomp);

        byte[] result = new byte[(int) totalDecomp];
        int    destOff = 0;

        for (int ci = 0; ci < chunks.size(); ci++) {
            CompressedChunk cc = chunks.get(ci);
            r.seek(cc.compressedOffset);

            // ── Read sub-block header ──────────────────────────────────────
            long blockMagic = r.readUInt32();
            if (blockMagic != LZO_BLOCK_MAGIC) {
                throw new IOException(String.format(
                        "UE3 LZO: bad block magic 0x%08X at chunk %d offset %d",
                        blockMagic, ci, cc.compressedOffset));
            }
            int blockSize = r.readInt32();          // max uncompressed sub-block size (e.g. 131072)
            int compTotal = r.readInt32();           // total compressed bytes across all sub-blocks
            int uncompTotal = r.readInt32();         // total uncompressed bytes for this chunk

            if (blockSize <= 0) blockSize = LZO_DEFAULT_BLOCK;
            int numSub = (uncompTotal + blockSize - 1) / blockSize;

            Umapica.LOGGER.info("[Umapica] chunk[{}]: blockSz={} compTotal={} uncompTotal={} numSub={}",
                    ci, blockSize, compTotal, uncompTotal, numSub);

            // Read sub-block size pairs
            int[] subCompSz   = new int[numSub];
            int[] subUncompSz = new int[numSub];
            for (int s = 0; s < numSub; s++) {
                subCompSz[s]   = r.readInt32();
                subUncompSz[s] = r.readInt32();
            }

            // Decompress each sub-block
            for (int s = 0; s < numSub; s++) {
                byte[] compData = r.readBytes(subCompSz[s]);
                byte[] decomp;
                if (subCompSz[s] == subUncompSz[s]) {
                    // Stored uncompressed
                    decomp = compData;
                } else {
                    decomp = Lzo1xDecompressor.decompress(compData, 0, subCompSz[s], subUncompSz[s]);
                }
                System.arraycopy(decomp, 0, result, destOff, subUncompSz[s]);
                destOff += subUncompSz[s];
            }
        }

        return result;
    }

    // ======================================================= //
    //  Name table
    // ======================================================= //

    private void readNameTable(UmapReader r) throws IOException {
        r.seek(nameOff);
        nameTable = new String[nameCount];
        for (int i = 0; i < nameCount; i++) {
            nameTable[i] = r.readFString();
            if (fileVersionUE4 >= VER_UE4_NAME_HASHES) {
                r.readUInt16(); // nonCasePreservingHash – skip
                r.readUInt16(); // casePreservingHash    – skip
            }
        }
        r.names = nameTable; // share for FName resolution
    }

    /** UE3-path name table reader (uses ByteArrayUmapReader over decompressed data). */
    private void readNameTableUE3(ByteArrayUmapReader br) throws IOException {
        br.seek(nameOff); // nameOff is a virtual address; ByteArrayUmapReader handles the mapping
        nameTable = new String[nameCount];
        for (int i = 0; i < nameCount; i++) {
            // UE3 name entry: int32 slen + slen bytes (null-terminated ASCII) + 8 bytes hash
            nameTable[i] = br.readUE3Name();
        }
        br.names = nameTable;
        Umapica.LOGGER.info("[Umapica] UE3 names read: count={} firstFew={} lastFew={}",
                nameCount,
                nameCount > 0 ? nameTable[0] : "",
                nameCount > 0 ? nameTable[nameCount - 1] : "");
    }

    // ======================================================= //
    //  Import table
    // ======================================================= //

    private void readImportTable(UmapReader r) throws IOException {
        r.seek(importOff);
        importNames = new String[importCount];
        for (int i = 0; i < importCount; i++) {
            r.readFName(); // classPackage – discard
            String className  = r.readFName();
            r.readInt32();     // outerIndex – discard
            String objectName = r.readFName();
            if (fileVersionUE4 >= VER_UE4_NON_OUTER_PACKAGE_IMPORT) {
                r.readFName(); // packageName – discard
            }
            if (fileVersionUE5 >= VER_UE5_OPTIONAL_RESOURCES) {
                r.readBool8(); // bImportOptional – discard
            }
            // We store the object name (e.g. "StaticMeshActor") as the import's display name
            importNames[i] = objectName;
        }
    }

    /**
     * UE3 import table: FObjectImport = classPackage(FName) + className(FName)
     *                     + outerIndex(int32) + objectName(FName)
     * No packageName or bImportOptional in UE3.
     */
    private void readImportTableUE3(ByteArrayUmapReader br) throws IOException {
        br.seek(importOff);
        importNames      = new String[importCount];
        importOuters     = new int[importCount];
        importClassNames = new String[importCount];
        for (int i = 0; i < importCount; i++) {
            br.readFName();                     // classPackage (e.g. "Engine") – discard
            importClassNames[i] = br.readFName(); // className (e.g. "StaticMesh", "Package")
            importOuters[i]     = br.readInt32(); // outerIndex (FPackageIndex to outer object)
            importNames[i]      = br.readFName(); // objectName (e.g. "SM_BigBuilding")
        }
        Umapica.LOGGER.info("[Umapica] UE3 imports read: count={}", importCount);
    }

    /**
     * Walks the UE3 import outer chain to build a full package-qualified path.
     * Returns "PackageName.ObjectName" where PackageName is the root .upk package,
     * or just "ObjectName" if no outer package is found.
     */
    private String getUE3ImportFullPath(int importIdx) {
        if (importIdx < 0 || importIdx >= importNames.length) return null;
        String objName = importNames[importIdx];
        // Walk outer chain to find the root Package entry (outerIndex == 0 or className=="Package")
        String pkgName = null;
        int outerPackageIdx = importOuters[importIdx]; // raw FPackageIndex of outer
        // Follow until we find a Package-class import with no outer
        int visited = 0;
        while (outerPackageIdx != 0 && visited < 16) {
            visited++;
            int outerIdx = outerPackageIdx < 0 ? (-outerPackageIdx - 1) : -1;
            if (outerIdx < 0 || outerIdx >= importNames.length) break;
            String cls = importClassNames[outerIdx];
            if ("Package".equals(cls) || importOuters[outerIdx] == 0) {
                pkgName = importNames[outerIdx];
                break;
            }
            outerPackageIdx = importOuters[outerIdx];
        }
        return (pkgName != null && !pkgName.isEmpty()) ? pkgName + "." + objName : objName;
    }

    // ======================================================= //
    //  Export table
    // ======================================================= //

    private void readExportTable(UmapReader r) throws IOException {
        r.seek(exportOff);
        exportClassIndex  = new int[exportCount];
        exportNames       = new String[exportCount];
        exportOffset      = new long[exportCount];
        exportSize        = new long[exportCount];
        exportOuterIndex  = new int[exportCount];

        for (int i = 0; i < exportCount; i++) {
            int classIdx = r.readInt32();  // FPackageIndex for class
            r.readInt32();                 // SuperIndex – discard
            if (fileVersionUE4 >= 508) {
                r.readInt32();             // TemplateIndex (added 4.19) – discard
            }
            int outerIndex = r.readInt32();// FPackageIndex for outer
            String objName = r.readFName();
            r.readInt32();                 // ObjectFlags – discard

            long serialSize;
            long serialOffset;
            if (fileVersionUE4 >= VER_UE4_64BIT_EXPORTMAP_SIZES) {
                serialSize   = r.readInt64();
                serialOffset = r.readInt64();
            } else {
                serialSize   = r.readInt32() & 0xFFFFFFFFL;
                serialOffset = r.readInt32() & 0xFFFFFFFFL;
            }

            r.readBool8(); // bForcedExport
            r.readBool8(); // bNotForClient
            r.readBool8(); // bNotForServer

            if (fileVersionUE4 >= 196 && fileVersionUE5 == 0) {
                r.skipFGuid(); // PackageGuid (removed in UE5)
            }
            r.readUInt32(); // PackageFlags (added 507)

            if (fileVersionUE4 >= 507) {
                r.readBool8(); // bNotAlwaysLoadedForEditorGame
                r.readBool8(); // bIsAsset
            }
            if (fileVersionUE5 >= 10) {
                r.readBool8(); // bIsInheritedInstance
                r.readBool8(); // bGeneratePublicHash
            }
            if (fileVersionUE4 >= 257) {
                r.readInt32(); // FirstExportDependency
                r.readBool8(); r.readBool8(); r.readBool8(); r.readBool8(); // dependency bools
            }

            exportClassIndex[i] = classIdx;
            exportNames[i]      = objName;
            exportOffset[i]     = serialOffset;
            exportSize[i]       = serialSize;
            exportOuterIndex[i] = outerIndex;
        }
    }

    /**
     * UE3 export table reader.
     * UE3 FObjectExport layout (without the UE4.19+ TemplateIndex):
     *   classIndex(int32) + superIndex(int32) + outerIndex(int32) + objectName(FName)
     *   + archetypeIndex(int32) + objectFlags(int64) + serialSize(int32) + serialOffset(int32)
     *   + exportFlags(int32) + generationNetObjectCount(TArray<int32>, 1 gen normally)
     *   + packageGuid(16) + packageFlags(int32)
     */
    private void readExportTableUE3(ByteArrayUmapReader br) throws IOException {
        br.seek(exportOff);
        exportClassIndex  = new int[exportCount];
        exportNames       = new String[exportCount];
        exportOffset      = new long[exportCount];
        exportSize        = new long[exportCount];
        exportOuterIndex  = new int[exportCount];

        for (int i = 0; i < exportCount; i++) {
            int classIdx   = br.readInt32(); // FPackageIndex for class
            br.readInt32();                  // SuperIndex
            int outerIndex = br.readInt32(); // FPackageIndex for outer
            String objName = br.readFName();
            br.readInt32();                  // archetypeIndex
            br.readInt64();                  // objectFlags (int64 in UE3)
            long serialSize   = br.readInt32() & 0xFFFFFFFFL;
            long serialOffset = br.readInt32() & 0xFFFFFFFFL;
            br.readInt32();                  // exportFlags
            int genCount = br.readInt32();   // TArray<int32> GenerationNetObjectCount
            for (int g = 0; g < genCount; g++) br.readInt32();
            br.skipBytes(16);                // packageGuid
            br.readInt32();                  // packageFlags

            exportClassIndex[i] = classIdx;
            exportNames[i]      = objName;
            exportOffset[i]     = serialOffset;
            exportSize[i]       = serialSize;
            exportOuterIndex[i] = outerIndex;
        }
        Umapica.LOGGER.info("[Umapica] UE3 exports read: count={}", exportCount);
    }

    // ======================================================= //
    //  Class-name resolution helpers
    // ======================================================= //

    /**
     * Resolves an FPackageIndex to a class name string.
     * 0 → "Class", positive → export name, negative → import name.
     */
    private String resolveClassName(int index) {
        if (index == 0) return "Class";
        if (index > 0 && (index - 1) < exportNames.length) return exportNames[index - 1];
        if (index < 0) {
            int importIdx = -index - 1;
            if (importIdx < importNames.length) return importNames[importIdx];
        }
        return "Unknown";
    }

    private boolean isActorClass(String className) {
        if (className == null) return false;
        // Never match component classes – they end with "Component".
        if (className.endsWith("Component")) return false;
        return className.contains("Actor")
                || className.equals("Brush")
                || className.equals("WorldSettings")
                || className.contains("Light")   // PointLight, SpotLight, etc.
                || className.contains("KAsset")  // physics props
                || className.equals("InterpActor"); // movable static-mesh actors
    }

    private boolean isMeshComponentClass(String className) {
        return className != null && (className.contains("MeshComponent") || className.equals("StaticMeshComponent"));
    }

    private boolean isSceneComponentClass(String className) {
        return className != null && (className.contains("Component") && !className.contains("DataComponent")
                && !className.contains("AudioComponent"));
    }

    // ======================================================= //
    //  Actor extraction
    // ======================================================= //

    private void extractActors(UmapReader r) throws IOException {
        // Build a map from export-index → parsed component data (lazy, only for actors)
        Map<Integer, ComponentData> components = new HashMap<>();

        for (int i = 0; i < exportCount; i++) {
            String cls = resolveClassName(exportClassIndex[i]);
            if (isActorClass(cls)) {
                parseActorExport(r, i, cls, components);
            }
        }
    }

    /** Holds raw data parsed from a SceneComponent export. */
    private static class ComponentData {
        FVector relativeLocation = FVector.ZERO;
        FRotator relativeRotation = FRotator.ZERO;
        FVector relativeScale3D = FVector.ONE;
        @Nullable String meshSoftPath = null;
        boolean meshInSameFile = false; // true when mesh is an export in the same package
    }

    private void parseActorExport(UmapReader r, int exportIdx,
                                   String className, Map<Integer, ComponentData> components) throws IOException {
        long dataOffset = exportOffset[exportIdx];
        long dataSize   = exportSize[exportIdx];
        if (dataSize <= 0 || dataOffset <= 0) return;

        r.seek(dataOffset);

        // Read properties, looking for RootComponent ObjectProperty
        int rootComponentIndex = 0; // FPackageIndex → 0 means none
        FTransform actorTransform = FTransform.IDENTITY;

        long endPos = dataOffset + dataSize;
        Map<String, Object> props = readProperties(r, endPos);

        Object rootCompProp = props.get("RootComponent");
        if (rootCompProp instanceof Integer rcIdx) {
            rootComponentIndex = rcIdx;
        }
        // Actors themselves rarely have location direct – it's on the component.
        // But some old actors store RelativeLocation directly here.
        FVector directLoc = null;
        FRotator directRot = null;
        FVector directScale = null;
        if (props.get("RelativeLocation") instanceof FVector v) directLoc = v;
        if (props.get("RelativeRotation") instanceof FRotator rot) directRot = rot;
        if (props.get("RelativeScale3D") instanceof FVector s) directScale = s;

        // Resolve root component
        ComponentData comp = null;
        if (rootComponentIndex != 0) {
            int compExportIdx = rootComponentIndex - 1; // positive FPackageIndex → 0-based
            if (compExportIdx >= 0 && compExportIdx < exportCount) {
                comp = components.computeIfAbsent(compExportIdx, idx -> {
                    try {
                        return parseComponentExport(r, idx);
                    } catch (IOException ex) {
                        return new ComponentData();
                    }
                });
            }
        }

        // Build the UmapActor
        UmapActor actor = new UmapActor(className, exportNames[exportIdx]);

        FVector loc   = (comp != null) ? comp.relativeLocation : (directLoc   != null ? directLoc   : FVector.ZERO);
        FRotator rot  = (comp != null) ? comp.relativeRotation : (directRot   != null ? directRot   : FRotator.ZERO);
        FVector scale = (comp != null) ? comp.relativeScale3D  : (directScale != null ? directScale : FVector.ONE);

        // Convert FRotator → FQuat using UE4's left-handed formula
        FQuat q = rot.toQuat();
        actor.transform = new FTransform(q, loc, scale);

        if (comp != null && comp.meshSoftPath != null) {
            actor.meshAssetPath = comp.meshSoftPath;
            actor.hintColor = hintColorFromPath(comp.meshSoftPath);
        }

        // Bounding box: approximate using scale (default 50 cm half-extent)
        double hx = 50.0 * scale.x(), hy = 50.0 * scale.y(), hz = 50.0 * scale.z();
        actor.localBounds = new FBox(
            new FVector(-hx, -hy, -hz),
            new FVector( hx,  hy,  hz),
            true
        );

        actors.add(actor);
    }

    private ComponentData parseComponentExport(UmapReader r, int exportIdx) throws IOException {
        long dataOffset = exportOffset[exportIdx];
        long dataSize   = exportSize[exportIdx];
        ComponentData data = new ComponentData();
        if (dataSize <= 0 || dataOffset <= 0) return data;

        r.seek(dataOffset);
        long endPos = dataOffset + dataSize;
        Map<String, Object> props = readProperties(r, endPos);

        if (props.get("RelativeLocation") instanceof FVector v)  data.relativeLocation = v;
        if (props.get("RelativeRotation") instanceof FRotator rt) data.relativeRotation = rt;
        if (props.get("RelativeScale3D")  instanceof FVector s)  data.relativeScale3D  = s;
        if (props.get("StaticMesh")       instanceof String mesh) data.meshSoftPath     = mesh;

        return data;
    }

    // ======================================================= //
    //  UE3-path actor extraction (uses ByteArrayUmapReader)
    // ======================================================= //

    private void extractActorsUE3(ByteArrayUmapReader br) throws IOException {
        Map<Integer, ComponentData> components = new HashMap<>();
        for (int i = 0; i < exportCount; i++) {
            // Skip class default objects (CDOs) – they always sit at origin and are not placed actors.
            if (exportNames[i].startsWith("Default__")) continue;
            String cls = resolveClassName(exportClassIndex[i]);
            if (isActorClass(cls)) {
                parseActorExportUE3(br, i, cls, components);
            }
        }
        // Log first few StaticMeshActors (or any actor) with position info for diagnostics
        int logged = 0;
        for (int i = 0; i < actors.size() && logged < 8; i++) {
            UmapActor a = actors.get(i);
            FVector t = a.transform.translation();
            if (a.className.contains("Mesh") || a.className.contains("Actor")) {
                Umapica.LOGGER.info("[Umapica] actor[{}] {} '{}' loc=({},{},{}) mesh={}",
                        i, a.className, a.actorName,
                        (int)t.x(), (int)t.y(), (int)t.z(),
                        a.meshAssetPath);
                logged++;
            }
        }
    }

    private void parseActorExportUE3(ByteArrayUmapReader br, int exportIdx,
                                      String className, Map<Integer, ComponentData> components) throws IOException {
        long dataOffset = exportOffset[exportIdx];
        long dataSize   = exportSize[exportIdx];
        if (dataSize <= 0 || dataOffset <= 0) return;

        br.seek(dataOffset);
        long endPos = dataOffset + dataSize;

        // Debug: dump first 32 raw bytes before property-stream search (first StaticMeshActor only).
        if (className.equals("StaticMeshActor") && actors.stream().noneMatch(a -> a.className.equals("StaticMeshActor"))) {
            try {
                byte[] peek = br.readBytes(Math.min(32, (int) dataSize));
                StringBuilder sb = new StringBuilder();
                for (byte b : peek) sb.append(String.format("%02X ", b));
                Umapica.LOGGER.info("[Umapica] DEBUG first StaticMeshActor '{}' dataOffset={} rawBytes: {}",
                        exportNames[exportIdx], dataOffset, sb.toString().trim());
            } catch (IOException ignored) {}
        }

        // UE3 actor exports have a native-data prefix before the FName property stream.
        // Scan forward to find the actual stream start.
        long propStart = findPropertyStreamUE3(br, dataOffset, endPos);
        br.seek(propStart);
        Map<String, Object> props = readPropertiesUE3(br, endPos);

        // UE3: actor position is stored directly as Location/Rotation, NOT via RootComponent.
        // Also check UE4-style names as fallback.
        FVector  directLoc   = props.get("Location")      instanceof FVector v   ? v   :
                               props.get("RelativeLocation") instanceof FVector v2  ? v2  : null;
        FRotator directRot   = props.get("Rotation")      instanceof FRotator ro  ? ro  :
                               props.get("RelativeRotation") instanceof FRotator ro2 ? ro2 : null;
        FVector  directScale = props.get("DrawScale3D")   instanceof FVector s   ? s   :
                               props.get("RelativeScale3D")  instanceof FVector s2  ? s2  : null;
        // Uniform DrawScale float → FVector
        if (directScale == null && props.get("DrawScale") instanceof Float df) {
            directScale = new FVector(df, df, df);
        }

        // UE3 StaticMeshActor stores mesh component reference as "StaticMeshComponent".
        // Also accept "RootComponent" for UE4 compatibility.
        Object compProp = props.get("StaticMeshComponent");
        if (compProp == null) compProp = props.get("CollisionComponent");
        if (compProp == null) compProp = props.get("RootComponent");
        int rootComponentIndex = compProp instanceof Integer rcIdx ? rcIdx : 0;

        ComponentData comp = null;
        if (rootComponentIndex != 0) {
            // FPackageIndex: positive = export (1-based), negative = import (-1-based)
            int compExportIdx = rootComponentIndex > 0 ? rootComponentIndex - 1 : -rootComponentIndex - 1;
            if (rootComponentIndex > 0 && compExportIdx < exportCount) {
                final ByteArrayUmapReader brFinal = br;
                comp = components.computeIfAbsent(compExportIdx, idx -> {
                    try { return parseComponentExportUE3(brFinal, idx); }
                    catch (IOException ex) { return new ComponentData(); }
                });
            }
        }

        UmapActor actor = new UmapActor(className, exportNames[exportIdx]);

        // Actor-direct location takes priority; component offsets are additive in UE3 only if non-zero.
        FVector  loc   = directLoc   != null ? directLoc   : (comp != null ? comp.relativeLocation : FVector.ZERO);
        FRotator rot   = directRot   != null ? directRot   : (comp != null ? comp.relativeRotation : FRotator.ZERO);
        FVector  scale = directScale != null ? directScale : (comp != null ? comp.relativeScale3D  : FVector.ONE);

        // Convert FRotator → FQuat using UE4's left-handed formula
        FQuat q = rot.toQuat();
        actor.transform = new FTransform(q, loc, scale);

        // Mesh asset: prefer what the component found; also check actor-direct "StaticMesh".
        String meshPath = (comp != null) ? comp.meshSoftPath : null;
        boolean meshInSameFile = (comp != null) && comp.meshInSameFile;
        if (meshPath == null) {
            Object rawSM = props.get("StaticMesh");
            meshPath = resolveUE3ObjectName(rawSM);
            if (rawSM instanceof Integer smIdx && smIdx > 0) meshInSameFile = true;
        }
        if (meshPath != null) {
            actor.meshAssetPath = meshPath;
            actor.meshInSameFile = meshInSameFile;
            actor.hintColor = hintColorFromPath(meshPath);
        }

        double hx = 50.0 * scale.x(), hy = 50.0 * scale.y(), hz = 50.0 * scale.z();
        actor.localBounds = new FBox(
            new FVector(-hx, -hy, -hz),
            new FVector( hx,  hy,  hz),
            true
        );
        actors.add(actor);
    }

    private ComponentData parseComponentExportUE3(ByteArrayUmapReader br, int exportIdx) throws IOException {
        long dataOffset = exportOffset[exportIdx];
        long dataSize   = exportSize[exportIdx];
        ComponentData data = new ComponentData();
        if (dataSize <= 0 || dataOffset <= 0) return data;

        br.seek(dataOffset);
        long endPos = dataOffset + dataSize;

        // UE3 components also have a native prefix; scan for property stream start.
        long propStart = findPropertyStreamUE3(br, dataOffset, endPos);
        br.seek(propStart);
        Map<String, Object> props = readPropertiesUE3(br, endPos);

        // UE3 component offsets (Translation/Rotation may be in component space)
        if (props.get("Translation")      instanceof FVector v)   data.relativeLocation = v;
        else if (props.get("RelativeLocation") instanceof FVector v2)  data.relativeLocation = v2;

        if (props.get("Rotation")         instanceof FRotator rt) data.relativeRotation = rt;
        else if (props.get("RelativeRotation") instanceof FRotator rt2) data.relativeRotation = rt2;

        if (props.get("Scale3D")          instanceof FVector s)   data.relativeScale3D  = s;
        else if (props.get("DrawScale3D")  instanceof FVector s2)  data.relativeScale3D  = s2;
        else if (props.get("RelativeScale3D") instanceof FVector s3) data.relativeScale3D = s3;
        if (props.get("DrawScale") instanceof Float df && data.relativeScale3D == FVector.ONE) {
            data.relativeScale3D = new FVector(df, df, df);
        }

        // UE3: StaticMesh is ObjectProperty (int FPackageIndex).
        // Positive index → export in the same file. Negative → import (separate .upk).
        Object rawSM = props.get("StaticMesh");
        if (rawSM instanceof Integer smIdx && smIdx > 0) {
            data.meshInSameFile = true; // mesh lives in this .umap, no separate .upk needed
        }
        String meshName = resolveUE3ObjectName(rawSM);
        if (meshName != null) {
            data.meshSoftPath = meshName;
            Umapica.LOGGER.info("[Umapica] UE3 mesh resolved: {} ({})", meshName, data.meshInSameFile ? "in-file" : "import");
        } else {
            if (rawSM != null) {
                Umapica.LOGGER.info("[Umapica] UE3 StaticMesh unresolved: type={} val={}", rawSM.getClass().getSimpleName(), rawSM);
            }
        }
        return data;
    }

    /**
     * Resolves a UE3 ObjectProperty value (int FPackageIndex) to a display name.
     * Returns null if the value is null, zero, or unresolvable.
     */
    private @Nullable String resolveUE3ObjectName(@Nullable Object val) {
        if (!(val instanceof Integer idx)) return null;
        if (idx == 0) return null;
        if (idx > 0) {
            int i = idx - 1;
            return (i < exportCount) ? exportNames[i] : null;
        } else {
            // Import: walk outer chain to get "PackageName.ObjectName"
            int i = -idx - 1;
            return getUE3ImportFullPath(i);
        }
    }

    /** Known UE3/UE4 property type names used when scanning for the property stream start. */
    private static final java.util.Set<String> KNOWN_PROP_TYPES = java.util.Set.of(
            "BoolProperty", "ByteProperty", "IntProperty", "FloatProperty",
            "StrProperty", "NameProperty", "ObjectProperty", "SoftObjectProperty",
            "StructProperty", "ArrayProperty", "MapProperty", "SetProperty",
            "EnumProperty", "TextProperty", "ClassProperty",
            "DelegateProperty", "MulticastDelegateProperty"
    );

    /**
     * Scans forward from {@code startVirt} to find the start of the UE3 FName-based
     * property stream. UE3 actor exports have a variable-length native-data prefix
     * (typically 82 bytes for placed StaticMeshActors) before the property list.
     *
     * <p>A position is accepted when:
     * <ul>
     *   <li>bytes[0..3] decode to a valid name-table index (the property name),</li>
     *   <li>bytes[8..11] decode to a valid name-table index whose name is a recognised
     *       UProperty type (e.g. "StructProperty", "BoolProperty", …), <em>or</em></li>
     *   <li>bytes[0..3] decode to the index for "None" (empty property list).</li>
     * </ul>
     *
     * @return virtual address of the property-stream start, or {@code startVirt} as fallback.
     */
    private long findPropertyStreamUE3(ByteArrayUmapReader br, long startVirt, long endVirt) {
        int nameCount = br.names.length;
        long limit = Math.min(endVirt, startVirt + 512); // cap scan window
        for (long virt = startVirt; virt < limit - 20; virt++) {
            try {
                br.seek(virt);
                int nameIdx = br.readInt32();
                if (nameIdx < 0 || nameIdx >= nameCount) continue;
                String name = br.names[nameIdx];
                if ("None".equals(name)) return virt;   // valid empty property list
                if (name.isEmpty()) continue;
                br.readInt32(); // skip nameNumber
                int typeIdx = br.readInt32();
                if (typeIdx < 0 || typeIdx >= nameCount) continue;
                if (!KNOWN_PROP_TYPES.contains(br.names[typeIdx])) continue;
                br.readInt32(); // skip type nameNumber
                long sz = br.readInt32() & 0xFFFFFFFFL;
                if (sz > 500_000) continue;
                int ai = br.readInt32();
                if (ai < 0 || ai > 65535) continue;
                return virt;
            } catch (IOException e) {
                break; // out of range – stop
            }
        }
        return startVirt; // fallback: original position
    }

    /**
     * UE3-path version of readProperties: uses ByteArrayUmapReader.
     * UE3 property tags do NOT include the struct GUID, and BoolProperty size = 4 (int32).
     */
    private Map<String, Object> readPropertiesUE3(ByteArrayUmapReader br, long endPos) {
        Map<String, Object> props = new HashMap<>();
        try {
            while (br.position() < endPos - 8) {
                String propName = br.readFName();
                if ("None".equals(propName) || propName == null || propName.isEmpty()) break;

                String propType  = br.readFName();
                long   propSize  = br.readInt32() & 0xFFFFFFFFL;
                br.readInt32(); // arrayIndex – discard

                PropTag tag = readPropertyTagUE3(br, propType);
                long valueStart = br.position();

                Object value = readPropertyValueUE3(br, propType, tag);

                long expectedEnd = valueStart + propSize;
                if (expectedEnd > valueStart && expectedEnd <= endPos) {
                    br.seek(expectedEnd);
                }
                if (value != null) props.put(propName, value);
            }
        } catch (IOException | RuntimeException e) {
            Umapica.LOGGER.info("[Umapica] UE3 property parse exception at virt=0x{}: {}",
                    Long.toHexString(br.position()), e.getMessage());
        }
        return props;
    }

    /** UE3-path property tag reader (no struct GUIDs). */
    private PropTag readPropertyTagUE3(ByteArrayUmapReader br, String propType) throws IOException {
        PropTag tag = new PropTag();
        switch (propType) {
            case "StructProperty" -> tag.structName = br.readFName(); // no GUID in UE3
            // Note: EnumProperty does not exist in UE3; ByteProperty carries the enum name.
            case "ByteProperty"   -> br.readFName(); // enum name (may be "None" for raw byte)
            case "BoolProperty"   -> {
                // UE3 (v880+): BoolProperty value is serialized as 1 BYTE inside the tag
                // header (after ArrayIndex), with Size=0 in the payload.
                // Reading it here prevents misaligning all subsequent properties.
                tag.boolValue = (br.readByte() != 0);
            }
            // ArrayProperty / MapProperty / SetProperty do not exist in UE3 in this form
            default -> { }
        }
        return tag;
    }

    /** UE3-path property value reader. */
    private @Nullable Object readPropertyValueUE3(ByteArrayUmapReader br, String propType,
                                                    PropTag tag) throws IOException {
        return switch (propType) {
            case "StructProperty" -> {
                String sn = tag.structName;
                yield switch (sn) {
                    case "Vector"  -> br.readFVector();
                    case "Rotator" -> {
                        // UE3: FRotator is 3 × int32 in Unreal Rotation Units (65536 = 360°)
                        int iPitch = br.readInt32();
                        int iYaw   = br.readInt32();
                        int iRoll  = br.readInt32();
                        yield new FRotator(
                            iPitch * (360.0 / 65536.0),
                            iYaw   * (360.0 / 65536.0),
                            iRoll  * (360.0 / 65536.0)
                        );
                    }
                    case "Quat"    -> br.readFQuat();
                    case "Box"     -> br.readFBox();
                    default        -> null;
                };
            }
            case "ObjectProperty"     -> br.readInt32(); // FPackageIndex
            case "SoftObjectProperty" -> {
                String assetPath = br.readFString();
                br.readFString();
                yield assetPath.isEmpty() ? null : assetPath;
            }
            case "IntProperty"    -> br.readInt32();
            case "FloatProperty"  -> (Float) br.readFloat();
            case "StrProperty"    -> br.readFString();
            case "NameProperty"   -> br.readFName();
            case "BoolProperty"   -> tag.boolValue; // value already read from tag header; Size=0
            case "ByteProperty"   -> br.readByte() & 0xFF; // UE3: byte (1-byte payload)
            default               -> null;
        };
    }

    // ======================================================= //
    //  UProperty list reader
    // ======================================================= //

    /**
     * Holds the extra type metadata parsed from the serialized property tag header
     * (the bytes between arrayIndex and the value payload).
     */
    private static class PropTag {
        String structName = "";  // only for StructProperty
        String innerType  = "";  // for ArrayProperty / SetProperty / MapProperty
        boolean boolValue = false; // only for BoolProperty
    }

    /**
     * Reads the sequential UProperty list starting at the current reader position
     * until a "None" property name is encountered or {@code endPos} is reached.
     * Returns a map of propertyName → typed value.
     */
    private Map<String, Object> readProperties(UmapReader r, long endPos) {
        Map<String, Object> props = new HashMap<>();
        try {
            while (r.position() < endPos - 8) {
                String propName = r.readFName();
                if ("None".equals(propName) || propName == null || propName.isEmpty()) break;

                String propType = r.readFName();
                long   propSize = r.readInt32() & 0xFFFFFFFFL; // size of value payload (after tag extras) – stored as int32
                /*int arrIdx =*/ r.readInt32();   // array index (0 for most) – discard

                PropTag tag = readPropertyTag(r, propType);
                long    valueStart = r.position();

                Object value = readPropertyValue(r, propType, tag, propName, propSize, valueStart);

                // Seek past this property's payload exactly so the next property starts correctly
                long expectedEnd = valueStart + propSize;
                if (expectedEnd > valueStart && expectedEnd <= endPos) {
                    r.seek(expectedEnd);
                }

                if (value != null) {
                    props.put(propName, value);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Property parsing is best-effort; partial results are fine
            Umapica.LOGGER.debug("[Umapica] Property parse exception: {}", e.getMessage());
        }
        return props;
    }

    /**
     * Reads the type-specific tag bytes after arrayIndex and returns a {@link PropTag}.
     */
    private PropTag readPropertyTag(UmapReader r, String propType) throws IOException {
        PropTag tag = new PropTag();
        switch (propType) {
            case "StructProperty" -> {
                tag.structName = r.readFName();
                if (fileVersionUE4 >= VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG) r.skipFGuid();
            }
            case "EnumProperty" -> {
                r.readFName(); // enum type name
                if (fileVersionUE4 >= VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG) r.readFName();
            }
            case "ByteProperty" -> r.readFName(); // enum name (may be "None")
            case "BoolProperty" -> tag.boolValue = r.readBool8();
            case "ArrayProperty" -> {
                if (fileVersionUE4 >= VER_UE4_PROPERTY_TAG_INNER_ARRAY_TYPE) tag.innerType = r.readFName();
                if (fileVersionUE4 >= VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG)  r.skipFGuid();
            }
            case "SetProperty" -> {
                if (fileVersionUE4 >= VER_UE4_PROPERTY_TAG_SET_ARRAY_SIZE_TYPE) tag.innerType = r.readFName();
                if (fileVersionUE4 >= VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG)      r.skipFGuid();
            }
            case "MapProperty" -> {
                if (fileVersionUE4 >= VER_UE4_PROPERTY_TAG_INNER_ARRAY_TYPE) {
                    r.readFName(); // key type
                    r.readFName(); // value type
                }
                if (fileVersionUE4 >= VER_UE4_STRUCT_GUID_IN_PROPERTY_TAG) {
                    r.skipFGuid(); r.skipFGuid();
                }
            }
            default -> { /* ObjectProperty, SoftObjectProperty, scalars: no extra tag bytes */ }
        }
        return tag;
    }

    /**
     * Reads the property value itself.  Returns a typed Java object, or {@code null} to skip.
     * For StructProperty, the {@code tag.structName} disambiguates Vector vs Rotator etc.
     * For unknown types, we return {@code null} and the caller seeks past propSize bytes.
     */
    private @Nullable Object readPropertyValue(UmapReader r, String propType,
                                               PropTag tag, String propName,
                                               long propSize, long valueStart) throws IOException {
        return switch (propType) {
            case "StructProperty" -> {
                String sn = tag.structName;
                yield switch (sn) {
                    case "Vector"    -> r.readFVector();
                    case "Rotator"   -> r.readFRotator();
                    case "Transform" -> r.readFTransform();
                    case "Box"       -> r.readFBox();
                    case "Quat"      -> r.readFQuat();
                    default          -> null; // unknown struct – caller will seek past
                };
            }
            case "ObjectProperty"     -> r.readInt32();  // FPackageIndex (positive → export, negative → import)
            case "SoftObjectProperty" -> {
                // FSoftObjectPath: FString assetPathString + FString subPathString
                String assetPath = r.readFString();
                r.readFString(); // subPathString – discard
                yield assetPath.isEmpty() ? null : assetPath;
            }
            case "IntProperty"    -> r.readInt32();
            case "UInt32Property" -> (int) r.readUInt32();
            case "Int64Property"  -> r.readInt64();
            case "FloatProperty"  -> r.readFloat();
            case "DoubleProperty" -> r.readDouble();
            case "StrProperty"    -> r.readFString();
            case "NameProperty"   -> r.readFName();
            case "BoolProperty"   -> tag.boolValue; // already read in tag; propSize == 0
            default               -> null; // caller seeks past propSize
        };
    }

    // ======================================================= //
    //  Mesh data loading
    // ======================================================= //

    /**
     * Attempts to load triangle geometry from the .uasset file referenced by each actor.
     * The .uasset must reside alongside the .umap or under a {@code Content/} directory
     * that mirrors the asset path.
     *
     * <p>Call this optionally after {@link #load()} if ghost-mesh rendering is needed.
     */
    public void loadMeshData() {
        int resolved = 0, failed = 0, missing = 0;
        int loggedMissing = 0;
        for (UmapActor actor : actors) {
            if (actor.meshAssetPath == null) continue;
            if (actor.meshData != null) continue;
            String objectName = extractObjectName(actor.meshAssetPath);
            // If the mesh is an export in this very .umap file, load directly from it.
            File assetFile = actor.meshInSameFile ? file : resolveAssetFile(actor.meshAssetPath);
            if (assetFile == null) {
                missing++;
                if (loggedMissing < 5) {
                    Umapica.LOGGER.info("[Umapica] mesh not found on disk: {}", actor.meshAssetPath);
                    loggedMissing++;
                }
                continue;
            }
            try {
                actor.meshData = UmapMeshLoader.load(assetFile, objectName);
                if (actor.meshData != null) {
                    actor.localBounds = actor.meshData.bounds;
                    resolved++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                Umapica.LOGGER.warn("[Umapica] Failed to load mesh {} ({}) : {}",
                        actor.meshAssetPath, assetFile.getName(), e.getMessage());
            }
        }
        Umapica.LOGGER.info("[Umapica] loadMeshData: resolved={} failed={} missing={}", resolved, failed, missing);
        if (missing > 0 && Config.ASSET_SEARCH_PATHS.get().isEmpty()) {
            Umapica.LOGGER.info("[Umapica] {} meshes not found. To load them, add your game's CookedPC path via " +
                    "the Umapica screen (Asset Path box) or edit: config/umapica-client.toml → assetSearchPaths", missing);
        }
    }

    /** Tries to find the .uasset or .upk file for the given asset path/name. */
    private @Nullable File resolveAssetFile(String softPath) {
        File contentDir = findContentDir(file.getParentFile());
        File mapDir = file.getParentFile();

        // ── UE3 path: "PackageName.ObjectName" (no leading slash) ──────────────
        if (!softPath.startsWith("/")) {
            // The package name is everything before the first dot
            String pkgName = softPath;
            int dotIdx = pkgName.indexOf('.');
            if (dotIdx >= 0) pkgName = pkgName.substring(0, dotIdx);
            // Search for PackageName.upk alongside the .umap and under Content/
            String upkName = pkgName + ".upk";
            File candidate = searchRecursive(mapDir, upkName);
            if (candidate != null) return candidate;
            if (contentDir != null) {
                candidate = searchRecursive(contentDir, upkName);
                if (candidate != null) return candidate;
            }
            // Also try CookedPC / CookedPCConsole subfolders common in UDK
            File parent = mapDir.getParentFile();
            while (parent != null) {
                for (String sub : new String[]{"CookedPC", "CookedPCConsole", "Cooked"}) {
                    File cookedDir = new File(parent, sub);
                    if (cookedDir.isDirectory()) {
                        candidate = searchRecursive(cookedDir, upkName);
                        if (candidate != null) return candidate;
                    }
                }
                parent = parent.getParentFile();
            }
            // Check user-configured asset search paths
            for (String configuredPath : Config.ASSET_SEARCH_PATHS.get()) {
                File dir = new File(configuredPath);
                if (dir.isDirectory()) {
                    candidate = searchRecursive(dir, upkName);
                    if (candidate != null) return candidate;
                }
            }
            return null;
        }

        // ── UE4 path: "/Game/Env/SM_Rock.SM_Rock" ──────────────────────────────
        String stripped = softPath;
        int dotIdx = stripped.lastIndexOf('.');
        if (dotIdx >= 0) stripped = stripped.substring(0, dotIdx); // remove .ObjectName
        if (stripped.startsWith("/Game/")) stripped = stripped.substring(6);
        if (stripped.startsWith("/")) stripped = stripped.substring(1);
        // Strip any leading folder to get just the asset base name for fallback search
        String baseName = stripped;
        int slashIdx = baseName.lastIndexOf('/');
        if (slashIdx >= 0) baseName = baseName.substring(slashIdx + 1);

        String relPath = stripped.replace('/', File.separatorChar);
        // Try .uasset first
        File candidate = new File(mapDir, relPath + ".uasset");
        if (candidate.exists()) return candidate;
        if (contentDir != null) {
            candidate = new File(contentDir, relPath + ".uasset");
            if (candidate.exists()) return candidate;
        }
        // Also try .upk alongside .umap and Content/
        candidate = new File(mapDir, relPath + ".upk");
        if (candidate.exists()) return candidate;
        if (contentDir != null) {
            candidate = new File(contentDir, relPath + ".upk");
            if (candidate.exists()) return candidate;
        }
        // Recursive fallback by base name
        candidate = searchRecursive(mapDir, baseName + ".uasset");
        if (candidate != null) return candidate;
        if (contentDir != null) {
            candidate = searchRecursive(contentDir, baseName + ".uasset");
            if (candidate != null) return candidate;
        }
        // Check user-configured asset search paths
        for (String configuredPath : Config.ASSET_SEARCH_PATHS.get()) {
            File dir = new File(configuredPath);
            if (dir.isDirectory()) {
                candidate = searchRecursive(dir, baseName + ".uasset");
                if (candidate != null) return candidate;
                candidate = searchRecursive(dir, baseName + ".upk");
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    /** Extracts the object name from a soft path: "Pkg.Obj" → "Obj", "/Game/Env/SM.SM" → "SM". */
    private static String extractObjectName(String softPath) {
        int dotIdx = softPath.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < softPath.length() - 1) return softPath.substring(dotIdx + 1);
        int slashIdx = softPath.lastIndexOf('/');
        return slashIdx >= 0 ? softPath.substring(slashIdx + 1) : softPath;
    }

    /** Recursively searches {@code dir} for a file named {@code target} (case-insensitive). */
    private static @Nullable File searchRecursive(File dir, String target) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (File f : entries) {
            if (f.isFile() && f.getName().equalsIgnoreCase(target)) return f;
            if (f.isDirectory()) {
                File sub = searchRecursive(f, target);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private @Nullable File findContentDir(File dir) {
        if (dir == null) return null;
        File content = new File(dir, "Content");
        if (content.isDirectory()) return content;
        return findContentDir(dir.getParentFile());
    }

    // ======================================================= //
    //  Hint colour
    // ======================================================= //

    private static int hintColorFromPath(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("rock") || lower.contains("stone")) return 0xFF888888;
        if (lower.contains("wood") || lower.contains("plank")) return 0xFF8B6040;
        if (lower.contains("grass") || lower.contains("leaf"))  return 0xFF40A040;
        if (lower.contains("water") || lower.contains("ocean")) return 0xFF4060C0;
        if (lower.contains("sand") || lower.contains("desert")) return 0xFFC8B060;
        if (lower.contains("metal") || lower.contains("iron"))  return 0xFF9090A0;
        if (lower.contains("glass") || lower.contains("window"))return 0xFF80C0FF;
        if (lower.contains("concrete") || lower.contains("wall"))return 0xFF999999;
        return 0xFFAAAAAA; // light grey default
    }

    // ======================================================= //
    //  Overrides
    // ======================================================= //

    @Override
    public String toString() {
        return String.format("UmapPackage[%s, %d actors]", file.getName(), actors.size());
    }
}
