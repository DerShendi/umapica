package net.shendi.umapica.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.shendi.umapica.Umapica;
import net.shendi.umapica.umap.FBox;
import net.shendi.umapica.umap.UmapActor;
import net.shendi.umapica.umap.UmapMeshData;
import net.shendi.umapica.umap.ByteArrayUmapReader;
import net.shendi.umapica.umap.Lzo1xDecompressor;
import net.shendi.umapica.umap.UmapReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches textures from UE4 {@code .uasset} texture files and
 * registers them as Minecraft {@link DynamicTexture} instances.
 *
 * <p>Should only be called from the Minecraft main thread (or handed off to it)
 * because {@link DynamicTexture} must be created on the GL thread.</p>
 */
public final class UmapTextureManager {

    // package-pixel-format values used inside UE4 texture properties
    private static final int PF_DXT1     = 5;
    private static final int PF_DXT5     = 11;
    private static final int PF_B8G8R8A8 = 4;

    /** cache: texture uasset absolute path  → Minecraft Identifier */
    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    /** Dummy white texture used as a fallback when loading fails. */
    private static volatile Identifier WHITE_FALLBACK = null;

    private UmapTextureManager() {}

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Tries to load the texture for a given actor mesh section into Minecraft
     * and stores the resulting {@link ResourceLocation} into
     * {@link UmapMeshData.Section#textureResourceId}.
     *
     * <p>Call this after mesh data has been loaded (on the GL thread or scheduled
     * via {@code Minecraft.getInstance().execute(…)}).</p>
     *
     * @param section     the section to populate
     * @param searchRoots directories to search for texture uasset files
     */
    public static void resolveTexture(UmapMeshData.Section section, File[] searchRoots) {
        if (section.textureResourceId != null) return; // already resolved
        if (section.materialPath == null) return;

        // Try each search root
        for (File root : searchRoots) {
            Identifier rl = tryLoadFromDir(root, section.materialPath);
            if (rl != null) {
                section.textureResourceId = rl.toString();
                return;
            }
        }
        // Fallback: use a solid colour derived from material name
        section.textureResourceId = colorFallback(section.materialPath).toString();
    }

    /**
     * Returns the {@link ResourceLocation} for a section, or the white fallback
     * if the section has no resolved texture yet.
     */
    public static Identifier getTexture(UmapMeshData.Section section) {
        if (section.textureResourceId != null) {
            // Parse the cached string back to an Identifier – fast path via cache
            Identifier rl = Identifier.tryParse(section.textureResourceId);
            if (rl != null) return rl;
        }
        return getWhiteFallback();
    }

    /** Returns a plain white 1×1 texture suitable as a fallback. */
    public static Identifier getWhiteFallback() {
        if (WHITE_FALLBACK == null) {
            WHITE_FALLBACK = createSolidTexture(0xFFFFFFFF, 4, 4);
        }
        return WHITE_FALLBACK;
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers
    // ------------------------------------------------------------------ //

    private static Identifier tryLoadFromDir(File dir, String materialName) {
        // Build a set of candidate search tokens from the material name:
        //   "M_BuildingBrick"  → ["m_buildingbrick", "buildingbrick"]
        //   "MI_Rock_001"      → ["mi_rock_001",     "rock_001"]
        //   "T_Grass_D"        → ["t_grass_d",        "grass_d"]
        // We search for any .uasset whose filename CONTAINS at least one token.
        String safe = materialName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        tokens.add(safe);
        // Strip common material/texture prefix (M_, MI_, T_)
        if (safe.startsWith("m_")) {
            tokens.add(safe.substring(2));
        } else if (safe.startsWith("mi_")) {
            tokens.add(safe.substring(3));
        } else if (safe.startsWith("t_")) {
            tokens.add(safe.substring(2));
        }

        String cacheKey = dir.getAbsolutePath() + "/" + safe;
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        // Search recursively for any .uasset matching one of our tokens
        File found = findUassetRecursive(dir, tokens, 8);
        if (found != null) {
            try {
                Identifier rl = loadTextureAsset(found);
                if (rl != null) {
                    CACHE.put(cacheKey, rl);
                    return rl;
                }
                Umapica.LOGGER.debug("[Umapica] Found '{}' but it is not a Texture2D (maybe a Material?)", found.getName());
            } catch (Exception e) {
                Umapica.LOGGER.debug("[Umapica] Texture parse failed {}: {}", found.getName(), e.getMessage());
            }
        }

        // Also search for UE3 .upk texture packages
        File foundUpk = findUpkRecursive(dir, tokens, 8);
        if (foundUpk != null) {
            try {
                Identifier rl2 = loadUE3TextureAsset(foundUpk, tokens.get(0));
                if (rl2 != null) {
                    CACHE.put(cacheKey, rl2);
                    return rl2;
                }
            } catch (Exception e) {
                Umapica.LOGGER.debug("[Umapica] UE3 upk tex failed {}: {}", foundUpk.getName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * Recursively searches {@code dir} (up to {@code depth} levels) for a .uasset
     * whose lower-case base name CONTAINS any of the given {@code tokens}.
     */
    private static File findUassetRecursive(File dir, List<String> tokens, int depth) {
        if (depth <= 0 || !dir.isDirectory()) return null;
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        // Check files in current directory first
        for (File f : entries) {
            if (!f.isFile()) continue;
            String ln = f.getName().toLowerCase(Locale.ROOT);
            if (!ln.endsWith(".uasset")) continue;
            for (String tok : tokens) {
                if (!tok.isEmpty() && ln.contains(tok)) return f;
            }
        }
        // Recurse into subdirectories
        for (File f : entries) {
            if (!f.isDirectory()) continue;
            File hit = findUassetRecursive(f, tokens, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Recursively searches {@code dir} (up to {@code depth} levels) for a .upk
     * whose lower-case base name CONTAINS any of the given {@code tokens}.
     */
    private static File findUpkRecursive(File dir, List<String> tokens, int depth) {
        if (depth <= 0 || !dir.isDirectory()) return null;
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (File f : entries) {
            if (!f.isFile()) continue;
            String ln = f.getName().toLowerCase(Locale.ROOT);
            if (!ln.endsWith(".upk")) continue;
            for (String tok : tokens) {
                if (!tok.isEmpty() && ln.contains(tok)) return f;
            }
        }
        for (File f : entries) {
            if (!f.isDirectory()) continue;
            File hit = findUpkRecursive(f, tokens, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Parses a UE4 texture .uasset, decompresses the highest available mip,
     * and registers it as a DynamicTexture.
     */
    private static Identifier loadTextureAsset(File uasset) throws IOException {
        String cacheKey = uasset.getAbsolutePath();
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        try (UmapReader r = new UmapReader(uasset)) {
            long magic = r.readUInt32();
            if (magic != 0x9E2A83C1L) return null;

            int legacyFV = r.readInt32();
            if (legacyFV != -4) r.readInt32();
            int vUE4 = r.readInt32();
            int vUE5 = 0;
            if (legacyFV <= -8) vUE5 = r.readInt32();
            r.fileVersionUE4 = vUE4;
            r.fileVersionUE5 = vUE5;

            r.readInt32(); // licenseVersion
            int customCount = r.readInt32();
            r.skipBytes(customCount * 20);
            r.readInt32();    // TotalHeaderSize
            r.readFString();  // FolderName
            r.readInt32();    // PackageFlags

            int nameCount = r.readInt32(), nameOff = r.readInt32();
            if (vUE5 >= 9) { r.readInt32(); r.readInt32(); }
            if (vUE4 >= 516) r.readFString();
            if (vUE4 >= 518) { r.readInt32(); r.readInt32(); }

            int exportCount = r.readInt32(), exportOff = r.readInt32();
            int importCount = r.readInt32(), importOff = r.readInt32();

            // Read name table
            r.seek(nameOff);
            String[] names = new String[nameCount];
            for (int i = 0; i < nameCount; i++) {
                names[i] = r.readFString();
                if (vUE4 >= 378) { r.readUInt16(); r.readUInt16(); }
            }
            r.names = names;

            // Read export table to find UTexture2D
            r.seek(exportOff);
            long texOffset = -1, texSize = -1;
            for (int i = 0; i < exportCount; i++) {
                r.readInt32(); // classIndex
                r.readInt32(); // superIndex
                if (vUE4 >= 508) r.readInt32(); // templateIndex
                r.readInt32(); // outerIndex
                r.readFName(); // objectName
                r.readInt32(); // ObjectFlags
                long sz, off;
                if (vUE4 >= 196) {
                    sz  = r.readInt64();
                    off = r.readInt64();
                } else {
                    sz  = r.readInt32() & 0xFFFFFFFFL;
                    off = r.readInt32() & 0xFFFFFFFFL;
                }
                r.readBool8(); r.readBool8(); r.readBool8();
                if (vUE4 >= 196 && vUE5 == 0) r.skipFGuid();
                r.readUInt32();
                if (vUE4 >= 507) { r.readBool8(); r.readBool8(); }
                if (vUE5 >= 10)  { r.readBool8(); r.readBool8(); }
                if (vUE4 >= 257) { r.readInt32(); r.readBool8(); r.readBool8(); r.readBool8(); r.readBool8(); }

                if (texOffset < 0 && sz > 64) { texOffset = off; texSize = sz; }
            }
            if (texOffset < 0) return null;

            // Parse the texture export
            r.seek(texOffset);
            long endPos = texOffset + texSize;
            // Skip UProperty list
            int sizeX = 0, sizeY = 0;
            String pixelFormat = "PF_DXT5";
            skipPropsForTexture: while (r.position() < endPos - 8) {
                int ni = r.readInt32(); r.readInt32(); // FName
                if (ni < 0 || ni >= names.length || "None".equals(names[ni])) break;
                int ti = r.readInt32(); r.readInt32();
                String propType = (ti >= 0 && ti < names.length) ? names[ti] : "";
                long propSz = r.readInt64();
                r.readInt32(); // arrayIndex
                switch (propType) {
                    case "StructProperty" -> { r.readInt32(); r.readInt32(); if (vUE4 >= 441) r.skipFGuid(); }
                    case "EnumProperty"   -> { r.readInt32(); r.readInt32(); }
                    case "ByteProperty"   -> { r.readInt32(); r.readInt32(); }
                    case "BoolProperty"   -> { r.readByte(); propSz = 0; }
                }
                long after = r.position() + propSz;
                // Read important texture properties
                String pn = (ni >= 0 && ni < names.length) ? names[ni] : "";
                switch (pn) {
                    case "SizeX"       -> sizeX       = r.readInt32();
                    case "SizeY"       -> sizeY       = r.readInt32();
                    case "PixelFormat" -> pixelFormat = readEnumString(r, names);
                    default -> { /* skip */ }
                }
                if (r.position() < after) r.seek(after);
            }

            // Now parse FTexturePlatformData
            if (sizeX <= 0 || sizeY <= 0 || sizeX > 8192 || sizeY > 8192) return null;
            // Skip the cooked platform data header: FString pixelFormatStr
            r.readFString(); // cooked suffix / pixelFormat string
            // Skip optional extra header bytes (varies by version)
            // numMips (int32)
            int numMips = r.readInt32();
            if (numMips <= 0 || numMips > 16) return null;

            // Read first (largest) mip
            boolean inline = !r.readBool8(); // bCooked   [0=inline, 1=not stored]
            int mipBulkFlags = r.readInt32();
            r.readInt32(); // num elements
            long mipSize    = r.readInt64();
            long mipBulkOff = r.readInt64();
            int mipSizeX    = r.readInt32();
            int mipSizeY    = r.readInt32();
            r.readInt32();  // slices

            if (mipSizeX <= 0 || mipSizeY <= 0 || mipSize <= 0) return null;

            byte[] mipData;
            if ((mipBulkFlags & 0x10) != 0) {
                // External ubulk
                File ubulk = new File(uasset.getParentFile(),
                        uasset.getName().replace(".uasset", ".ubulk"));
                if (!ubulk.exists()) return null;
                mipData = new byte[(int) mipSize];
                try (var fis = Files.newInputStream(ubulk.toPath())) {
                    fis.skipNBytes(mipBulkOff);
                    fis.readNBytes(mipData, 0, (int) mipSize);
                }
            } else if (mipSize <= endPos - r.position()) {
                mipData = r.readBytes((int) mipSize);
            } else {
                return null;
            }

            // Decode
            int[] pixels;
            if (pixelFormat.contains("DXT1") || pixelFormat.contains("BC1")) {
                pixels = DxtDecoder.decodeDXT1(mipData, mipSizeX, mipSizeY);
            } else if (pixelFormat.contains("DXT5") || pixelFormat.contains("BC3")) {
                pixels = DxtDecoder.decodeDXT5(mipData, mipSizeX, mipSizeY);
            } else if (pixelFormat.contains("B8G8R8A8") || pixelFormat.contains("R8G8B8A8")) {
                // Raw BGRA / RGBA 8bpp
                pixels = new int[mipSizeX * mipSizeY];
                boolean bgra = pixelFormat.contains("B8");
                for (int p = 0; p < pixels.length; p++) {
                    int b0 = mipData[p*4]   & 0xFF;
                    int b1 = mipData[p*4+1] & 0xFF;
                    int b2 = mipData[p*4+2] & 0xFF;
                    int b3 = mipData[p*4+3] & 0xFF;
                    pixels[p] = bgra ? ((b3<<24)|(b2<<16)|(b1<<8)|b0) : ((b3<<24)|(b0<<16)|(b1<<8)|b2);
                }
            } else {
                return null; // unsupported format
            }

            // Build NativeImage and upload
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, mipSizeX, mipSizeY, false);
            for (int py = 0; py < mipSizeY; py++) {
                for (int px = 0; px < mipSizeX; px++) {
                    int argb = pixels[py * mipSizeX + px];
                    // NativeImage RGBA pixel: setPixelRGBA expects ABGR (little-endian)
                    int r2 = (argb >> 16) & 0xFF;
                    int g2 = (argb >>  8) & 0xFF;
                    int b2 =  argb        & 0xFF;
                    int a2 = (argb >> 24) & 0xFF;
                    img.setPixel(px, py, (a2 << 24) | (b2 << 16) | (g2 << 8) | r2);
                }
            }

            DynamicTexture dt = new DynamicTexture(() -> "umapica_tex", img);
            String name = uasset.getName().replace(".uasset", "").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_]", "_");
            Identifier rl = Identifier.fromNamespaceAndPath("umapica", "dynamic/" + name);
            Minecraft.getInstance().getTextureManager().register(rl, dt);
            CACHE.put(cacheKey, rl);
            Umapica.LOGGER.info("[Umapica] Loaded texture {} → {}×{} {}", name, mipSizeX, mipSizeY, pixelFormat);
            return rl;
        }
    }

    /**
     * Loads a texture from a UE3 {@code .upk} file. Searches the package for a
     * Texture2D export whose name contains {@code tokenHint}, then decodes the
     * first inline mip and registers it as a DynamicTexture.
     */
    @SuppressWarnings("unused")
    private static Identifier loadUE3TextureAsset(File upk, String tokenHint) throws IOException {
        String cacheKey = upk.getAbsolutePath() + "#" + tokenHint;
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        try (UmapReader r = new UmapReader(upk)) {
            long magic = r.readUInt32();
            if (magic != 0x9E2A83C1L) return null;

            int fileVersion   = r.readInt32();
            r.readInt32(); // licenseeVersion
            if (fileVersion >= 0) return null; // UE4 .uasset handled elsewhere
            r.readFString(); // folderName
            r.readInt32();   // packageFlags

            int  nameCount   = r.readInt32(); long nameOff   = r.readUInt32();
            int  exportCount = r.readInt32(); long exportOff = r.readUInt32();
            int  importCount = r.readInt32(); long importOff = r.readUInt32();

            r.readInt32(); r.readInt32(); // dependsOffset, softPkgRefsOffset
            r.skipBytes(12);             // 3 × padding ints
            r.skipFGuid();               // package GUID
            int genCount = r.readInt32();
            for (int g = 0; g < genCount; g++) { r.readInt32(); r.readInt32(); }
            r.readInt32(); r.readInt32(); r.readInt32(); // EngineVersion, CookerVersion, PackageSource

            int compressionFlags = r.readInt32();
            int chunkCount       = r.readInt32();

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
                for (int c = 0; c < chunkCount; c++) {
                    r.seek(compOff[c]);
                    long magic2 = r.readUInt32();
                    if (magic2 != 0x9E2A83C1L)
                        throw new IOException("UE3 LZO bad magic at chunk " + c);
                    int blockSize   = r.readInt32();
                    r.readInt32(); // compTotal
                    int uncompTotal = r.readInt32();
                    if (blockSize <= 0) blockSize = 131072;
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
                br = new ByteArrayUmapReader(decompData, uncompOff[0]);
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
                br.readInt32(); br.readInt32(); // superIndex, outerIndex
                String objName = br.readFName();
                br.readInt32(); // archetypeIndex
                br.readInt64(); // objectFlags
                long serialSize   = br.readInt32() & 0xFFFFFFFFL;
                long serialOffset = br.readInt32() & 0xFFFFFFFFL;
                br.readInt32(); // exportFlags
                int gc = br.readInt32();
                for (int g = 0; g < gc; g++) br.readInt32();
                br.skipFGuid();
                br.readInt32(); // packageFlags
                expOffsets[i]  = serialOffset;
                expSizes[i]    = serialSize;
                expNames[i]    = objName;
                expClassIdx[i] = classIdx;
            }

            // ── Import table: resolve class names ─────────────────────────────
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

            // ── Find Texture2D export matching tokenHint ──────────────────────
            long texOff = -1, texSz = -1;
            String texExportName = null;
            for (int i = 0; i < exportCount; i++) {
                if (!expClassNames[i].equalsIgnoreCase("Texture2D")) continue;
                String en = expNames[i] == null ? "" : expNames[i].toLowerCase(Locale.ROOT);
                if (!en.contains(tokenHint.toLowerCase(Locale.ROOT))) continue;
                texOff = expOffsets[i];
                texSz  = expSizes[i];
                texExportName = expNames[i];
                break;
            }
            // Fallback: any Texture2D export
            if (texOff < 0) {
                for (int i = 0; i < exportCount; i++) {
                    if (!expClassNames[i].equalsIgnoreCase("Texture2D")) continue;
                    if (expSizes[i] < 64) continue;
                    texOff = expOffsets[i];
                    texSz  = expSizes[i];
                    texExportName = expNames[i];
                    break;
                }
            }
            if (texOff < 0) return null;

            // ── Parse Texture2D property list ─────────────────────────────────
            br.seek(texOff);
            long endPos = texOff + texSz;
            int sizeX = 0, sizeY = 0;
            String format = "";
            while (br.position() < endPos - 8) {
                String propName = br.readFName();
                if (propName == null || propName.equals("None")) break;
                String propType = br.readFName();
                long propSz     = br.readInt32() & 0xFFFFFFFFL;
                br.readInt32(); // arrayIndex
                if ("BoolProperty".equals(propType)) { br.readByte(); propSz = 0; }
                else if ("ByteProperty".equals(propType)) { br.readFName(); }
                long after = br.position() + propSz;
                switch (propName) {
                    case "SizeX"  -> sizeX  = br.readInt32();
                    case "SizeY"  -> sizeY  = br.readInt32();
                    case "Format" -> {
                        // ByteProperty enum: the value is already read (readFName above reads the enum type name)
                        // The actual string enum value comes from readFName
                        format = after > br.position() ? br.readFName() : format;
                        if (format == null) format = "";
                    }
                    default -> { /* skip */ }
                }
                if (br.position() < after) br.seek(after);
            }

            if (sizeX <= 0 || sizeY <= 0 || sizeX > 8192 || sizeY > 8192) return null;

            // ── Parse mip array (TLazyArray) ──────────────────────────────────
            if (br.position() + 8 > endPos) return null;
            br.readInt32();            // TLazyArray skip-offset
            int numMips = br.readInt32();
            if (numMips <= 0 || numMips > 16) return null;

            byte[] mipData = null;
            int mipW = 0, mipH = 0;
            for (int m = 0; m < numMips && br.position() < endPos - 16; m++) {
                int  bulkFlags  = br.readInt32();
                int  elemCount  = br.readInt32(); // byte count for DXT data
                int  sizeOnDisk = br.readInt32();
                br.readInt32(); // bulkOffset in file
                int mwi = br.readInt32();
                int mhi = br.readInt32();
                if (elemCount > 0 && sizeOnDisk > 0 && sizeOnDisk <= 4 * 1024 * 1024
                        && mwi > 0 && mhi > 0) {
                    mipData = br.readBytes(sizeOnDisk);
                    mipW    = mwi;
                    mipH    = mhi;
                    break;
                } else if (sizeOnDisk > 0 && sizeOnDisk < 33554432 /* 32 MB */) {
                    br.skipBytes(sizeOnDisk); // skip inline but too-large mip
                }
            }
            if (mipData == null || mipW <= 0 || mipH <= 0) {
                Umapica.LOGGER.debug("[Umapica] UE3 tex {} in {}: no inline mip found (may be in TFC)",
                        texExportName, upk.getName());
                return null;
            }

            // ── Infer pixel format from data sizes ────────────────────────────
            int pixCount = mipW * mipH;
            int[] pixels;
            if (!format.isEmpty()) {
                // Use declared format if available
                if (format.contains("DXT1") || format.contains("PF_DXT1")) {
                    pixels = DxtDecoder.decodeDXT1(mipData, mipW, mipH);
                } else if (format.contains("DXT5") || format.contains("PF_DXT5") ||
                           format.contains("DXT3") || format.contains("PF_DXT3")) {
                    pixels = DxtDecoder.decodeDXT5(mipData, mipW, mipH);
                } else if (format.contains("B8G8R8A8") || format.contains("A8R8G8B8")) {
                    pixels = decodeBGRA(mipData, pixCount);
                } else {
                    // Infer from size
                    pixels = inferAndDecode(mipData, mipW, mipH, pixCount);
                }
            } else {
                pixels = inferAndDecode(mipData, mipW, mipH, pixCount);
            }
            if (pixels == null) return null;

            // ── Build NativeImage and register ───────────────────────────────
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, mipW, mipH, false);
            for (int py = 0; py < mipH; py++) {
                for (int px2 = 0; px2 < mipW; px2++) {
                    int argb = pixels[py * mipW + px2];
                    int r2 = (argb >> 16) & 0xFF, g2 = (argb >> 8) & 0xFF, b2 = argb & 0xFF, a2 = (argb >> 24) & 0xFF;
                    img.setPixel(px2, py, (a2 << 24) | (b2 << 16) | (g2 << 8) | r2);
                }
            }
            DynamicTexture dt = new DynamicTexture(() -> "umapica_ue3_tex", img);
            String regName = (texExportName != null ? texExportName : tokenHint)
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            Identifier rl = Identifier.fromNamespaceAndPath("umapica", "dynamic/" + regName);
            Minecraft.getInstance().getTextureManager().register(rl, dt);
            CACHE.put(cacheKey, rl);
            Umapica.LOGGER.info("[Umapica] UE3 tex loaded: {} → {}×{} fmt='{}'", regName, mipW, mipH, format);
            return rl;
        }
    }

    /** Infers DXT1/DXT5/BGRA by comparing data size to pixel count. */
    private static int[] inferAndDecode(byte[] data, int w, int h, int pixCount) {
        if (data.length == pixCount / 2) return DxtDecoder.decodeDXT1(data, w, h);
        if (data.length == pixCount)     return DxtDecoder.decodeDXT5(data, w, h);
        if (data.length == pixCount * 4) return decodeBGRA(data, pixCount);
        // Fallback: try DXT5
        if (data.length >= pixCount) return DxtDecoder.decodeDXT5(data, w, h);
        if (data.length >= pixCount / 2) return DxtDecoder.decodeDXT1(data, w, h);
        return null;
    }

    /** Decodes raw BGRA byte array to ARGB int array. */
    private static int[] decodeBGRA(byte[] data, int pixCount) {
        int[] pixels = new int[pixCount];
        for (int p = 0; p < pixCount && p * 4 + 3 < data.length; p++) {
            int b = data[p*4]   & 0xFF, g = data[p*4+1] & 0xFF,
                rr= data[p*4+2] & 0xFF, a = data[p*4+3] & 0xFF;
            pixels[p] = (a << 24) | (rr << 16) | (g << 8) | b;
        }
        return pixels;
    }

    /** Read an EnumProperty value – reads the FName (8 bytes) and returns the string. */
    private static String readEnumString(UmapReader r, String[] names) throws IOException {
        int idx = r.readInt32(); r.readInt32();
        return (idx >= 0 && idx < names.length) ? names[idx] : "";
    }

    /** Creates a solid-colour 1×1 DynamicTexture and registers it. */
    private static Identifier createSolidTexture(int argb, int w, int h) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >> 24) & 0xFF;
        int px = (a << 24) | (b << 16) | (g << 8) | r; // ABGR for NativeImage
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, px);
        DynamicTexture dt = new DynamicTexture(() -> "umapica_solid", img);
        String name = "solid_" + Integer.toHexString(argb & 0xFFFFFF);
        Identifier rl = Identifier.fromNamespaceAndPath("umapica", "dynamic/" + name);
        Minecraft.getInstance().getTextureManager().register(rl, dt);
        return rl;
    }

    /** Register a DynamicTexture under an identifier. */
    private static Identifier register(String id, DynamicTexture dt) {
        Identifier rl = Identifier.tryParse(id);
        if (rl != null) Minecraft.getInstance().getTextureManager().register(rl, dt);
        return rl;
    }

    /** Generate a repeatable solid-colour fallback for a given material name. */
    private static Identifier colorFallback(String materialName) {
        // Hash the material name to a hue, then convert to ARGB
        int h = Math.abs(materialName.hashCode());
        float hue = (h % 360) / 360.0f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.55f, 0.75f);
        int argb = 0xFF000000 | rgb;
        String key = "fallback_" + Integer.toHexString(argb & 0xFFFFFF);
        return CACHE.computeIfAbsent(key, k -> createSolidTexture(argb, 4, 4));
    }
}
