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

    /** cache: texture uasset absolute path (or "file#token")  → Minecraft Identifier */
    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    /** Dummy white texture used as a fallback when loading fails. */
    private static volatile Identifier WHITE_FALLBACK = null;

    /**
     * Cached decompressed + export-indexed UE3 package (for .upk and .umap files).
     * Keyed by absolute file path.  Reuse across multiple material-name queries so
     * a 40 MB decompressed .umap is only inflated once per session.
     */
    private record UE3PkgInfo(ByteArrayUmapReader br,
                               long[]   expOff,
                               long[]   expSz,
                               String[] expCls,
                               String[] expName,
                               File     pkgFile) {}
    private static final ConcurrentHashMap<String, UE3PkgInfo> UE3_PKG_INFO
            = new ConcurrentHashMap<>();

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

    /** Builds the list of search tokens for a material name. */
    private static List<String> buildTokenList(String materialName) {
        String safe = materialName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        tokens.add(safe);
        if      (safe.startsWith("m_"))  tokens.add(safe.substring(2));
        else if (safe.startsWith("mi_")) tokens.add(safe.substring(3));
        else if (safe.startsWith("t_"))  tokens.add(safe.substring(2));
        return tokens;
    }

    private static Identifier tryLoadFromDir(File dir, String materialName) {
        List<String> tokens = buildTokenList(materialName);
        String safe     = tokens.get(0);
        String cacheKey = dir.getAbsolutePath() + "/" + safe;
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        // ── Handle UE3 package FILES passed directly (e.g. source .umap) ──────
        if (!dir.isDirectory()) {
            if (dir.isFile()) {
                String ln = dir.getName().toLowerCase(Locale.ROOT);
                if (ln.endsWith(".upk") || ln.endsWith(".umap")) {
                    UE3PkgInfo info = getUE3PkgInfo(dir);
                    if (info != null) {
                        Identifier rl = loadTexFromUE3Pkg(info, tokens);
                        if (rl != null) { CACHE.put(cacheKey, rl); return rl; }
                    }
                }
            }
            return null;
        }

        // ── Search for UE4 .uasset by filename ───────────────────────────────
        File found = findUassetRecursive(dir, tokens, 8);
        if (found != null) {
            try {
                Identifier rl = loadTextureAsset(found);
                if (rl != null) { CACHE.put(cacheKey, rl); return rl; }
                Umapica.LOGGER.debug("[Umapica] Found '{}' but it is not a Texture2D", found.getName());
            } catch (Exception e) {
                Umapica.LOGGER.debug("[Umapica] Texture parse failed {}: {}", found.getName(), e.getMessage());
            }
        }

        // ── Search for UE3 .upk by filename ─────────────────────────────────
        File foundUpk = findUpkRecursive(dir, tokens, 8);
        if (foundUpk != null) {
            UE3PkgInfo info = getUE3PkgInfo(foundUpk);
            if (info != null) {
                Identifier rl2 = loadTexFromUE3Pkg(info, tokens);
                if (rl2 != null) { CACHE.put(cacheKey, rl2); return rl2; }
            }
        }

        // ── Broad fallback: search ALL .upk files at depth-1 of this dir ─────
        // Skips very large files (e.g. Startup.upk at 221 MB) to avoid OOM.
        File[] all = dir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".upk")
                && f.length() < 100_000_000L);
        if (all != null) {
            for (File upk : all) {
                if (upk.equals(foundUpk)) continue; // already tried above
                UE3PkgInfo info = getUE3PkgInfo(upk);
                if (info == null) continue;
                Identifier rl = loadTexFromUE3Pkg(info, tokens);
                if (rl != null) { CACHE.put(cacheKey, rl); return rl; }
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

    // ------------------------------------------------------------------ //
    //  UE3 package parsing + texture loading (replaces old loadUE3TextureAsset)
    // ------------------------------------------------------------------ //

    /**
     * Decompresses and parses the export table of a UE3 {@code .upk} or {@code .umap}.
     * Result is cached so a 40 MB .umap is decompressed only once per session.
     * Returns {@code null} if the file is not a valid UE3 package or is too large
     * to fit in the 256 MB safety limit.
     */
    private static @org.jetbrains.annotations.Nullable UE3PkgInfo getUE3PkgInfo(File pkg) {
        String key = pkg.getAbsolutePath();
        UE3PkgInfo cached2 = UE3_PKG_INFO.get(key);
        if (cached2 != null) return cached2;
        try (UmapReader r = new UmapReader(pkg)) {
            long magic = r.readUInt32();
            if (magic != 0x9E2A83C1L) return null;
            int fileVersion = r.readInt32();
            r.readInt32();   // "licenseeVersion" slot (actually totalHdrSize in Hat in Time)
            if (fileVersion < 0) return null; // UE4 format – handled by loadTextureAsset
            r.readFString(); // folderName
            r.readInt32();   // packageFlags
            int nc = r.readInt32(); long nameOff  = r.readUInt32();
            int ec = r.readInt32(); long exportOff = r.readUInt32();
            int ic = r.readInt32(); long importOff = r.readUInt32();
            r.readInt32(); r.readInt32();  // dependsOffset, softPkgRefsOffset
            r.skipBytes(12); r.skipFGuid();
            int gc = r.readInt32();
            for (int g = 0; g < gc; g++) { r.readInt32(); r.readInt32(); }
            r.readInt32(); r.readInt32(); r.readInt32(); // EngineVersion, CookerVersion, PkgSource
            int compressionFlags = r.readInt32();
            int chunkCount       = r.readInt32();

            // ── Decompress LZO chunks, or use raw bytes ──────────────────────
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
                if (totalDecomp > 256L * 1024 * 1024) {
                    Umapica.LOGGER.debug("[Umapica] Skipping {} – decompressed size {} MB exceeds 256 MB limit",
                            pkg.getName(), totalDecomp / (1024 * 1024));
                    return null;
                }
                byte[] decompData = new byte[(int) totalDecomp];
                int destOff = 0;
                for (int c = 0; c < chunkCount; c++) {
                    r.seek(compOff[c]);
                    long magic2 = r.readUInt32();
                    if (magic2 != 0x9E2A83C1L) throw new IOException("UE3 LZO bad magic at chunk " + c);
                    int blockSize = r.readInt32(); r.readInt32(); int uncompTotal = r.readInt32();
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
                if (pkg.length() > 256L * 1024 * 1024) return null;
                byte[] raw = r.readBytes((int) pkg.length());
                br = new ByteArrayUmapReader(raw, 0L);
            }
            br.fileVersionUE4 = fileVersion;

            // ── Name table ───────────────────────────────────────────────────
            br.seek(nameOff);
            String[] names = new String[nc];
            for (int i = 0; i < nc; i++) names[i] = br.readUE3Name();
            br.names = names;

            // ── Import table (for class name resolution) ─────────────────────
            br.seek(importOff);
            String[] impNames = new String[ic];
            for (int i = 0; i < ic; i++) {
                br.readFName(); br.readFName(); br.readInt32(); impNames[i] = br.readFName();
            }

            // ── Export table ─────────────────────────────────────────────────
            br.seek(exportOff);
            long[]   expOffs     = new long[ec];
            long[]   expSzs      = new long[ec];
            String[] expNames    = new String[ec];
            String[] expClsNames = new String[ec];
            int[]    expClsIdx   = new int[ec];
            for (int i = 0; i < ec; i++) {
                int classIdx = br.readInt32();
                br.readInt32(); br.readInt32(); // superIndex, outerIndex
                String objName = br.readFName();
                br.readInt32(); // archetypeIndex
                br.readInt64(); // objectFlags
                long sz  = br.readInt32() & 0xFFFFFFFFL;
                long off = br.readInt32() & 0xFFFFFFFFL;
                br.readInt32(); // exportFlags
                int gcnt = br.readInt32();
                for (int g = 0; g < gcnt; g++) br.readInt32();
                br.skipFGuid();
                br.readInt32(); // packageFlags
                expOffs[i] = off;  expSzs[i]  = sz;
                expNames[i] = objName;  expClsIdx[i] = classIdx;
            }
            for (int i = 0; i < ec; i++) {
                int ci = expClsIdx[i];
                if      (ci < 0) { int ii = -ci - 1; expClsNames[i] = (ii < ic) ? impNames[ii] : "?"; }
                else if (ci > 0) { int ei = ci - 1;  expClsNames[i] = (ei < ec) ? expNames[ei] : "?"; }
                else             { expClsNames[i] = "Class"; }
            }

            UE3PkgInfo info = new UE3PkgInfo(br, expOffs, expSzs, expClsNames, expNames, pkg);
            UE3_PKG_INFO.put(key, info);
            Umapica.LOGGER.info("[Umapica] Parsed UE3 pkg {}: {} exports", pkg.getName(), ec);
            return info;
        } catch (Exception e) {
            Umapica.LOGGER.debug("[Umapica] getUE3PkgInfo {} failed: {}", pkg.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Searches a parsed UE3 package for a {@code Texture2D} export whose name
     * contains one of {@code tokens}, then decodes the mip (inline or from a
     * {@code .tfc} Texture File Cache) and registers it as a {@link DynamicTexture}.
     */
    private static @org.jetbrains.annotations.Nullable Identifier loadTexFromUE3Pkg(
            UE3PkgInfo info, List<String> tokens) {
        ByteArrayUmapReader br = info.br();
        File pkgDir = info.pkgFile().getParentFile();

        for (int i = 0; i < info.expName().length; i++) {
            if (!"Texture2D".equalsIgnoreCase(info.expCls()[i])) continue;
            String ename = info.expName()[i];
            if (ename == null || ename.isEmpty()) continue;
            String lower = ename.toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String tok : tokens) { if (!tok.isEmpty() && lower.contains(tok)) { match = true; break; } }
            if (!match) continue;

            long off = info.expOff()[i];
            long sz  = info.expSz()[i];
            if (sz < 16) continue;

            try {
                br.seek(off);
                long endPos = off + sz;
                int sizeX = 0, sizeY = 0;
                String format  = "";
                String tfcName = null;

                // Property list
                while (br.position() < endPos - 8) {
                    String propName = br.readFName();
                    if (propName == null || "None".equals(propName)) break;
                    String propType = br.readFName();
                    long   propSz   = br.readInt32() & 0xFFFFFFFFL;
                    br.readInt32(); // arrayIndex
                    if ("BoolProperty".equals(propType))      { br.readByte(); propSz = 0; }
                    else if ("ByteProperty".equals(propType)) { br.readFName(); }
                    long after = br.position() + propSz;
                    switch (propName) {
                        case "SizeX"  -> sizeX   = br.readInt32();
                        case "SizeY"  -> sizeY   = br.readInt32();
                        case "Format" -> { format  = (after > br.position()) ? br.readFName() : format;
                                           if (format == null) format = ""; }
                        case "TextureFileCacheName" -> tfcName = br.readFName();
                        default -> {}
                    }
                    if (br.position() < after) br.seek(after);
                }
                if (sizeX <= 0 || sizeY <= 0 || sizeX > 8192 || sizeY > 8192) continue;

                // Resolve TFC file (next to the .upk/.umap)
                File tfcFile = null;
                if (tfcName != null && !tfcName.isEmpty() && pkgDir != null) {
                    tfcFile = new File(pkgDir, tfcName + ".tfc");
                    if (!tfcFile.exists()) tfcFile = null;
                }

                // Mip array (TLazyArray<FTexture2DMipMap>)
                if (br.position() + 8 > endPos) continue;
                br.readInt32(); // TLazyArray skip-offset
                int numMips = br.readInt32();
                if (numMips <= 0 || numMips > 16) continue;

                byte[] mipData = null;
                int mipW = 0, mipH = 0;
                for (int m = 0; m < numMips && br.position() < endPos - 16; m++) {
                    int  bulkFlags  = br.readInt32();
                    int  elemCount  = br.readInt32();  // raw mip byte count
                    int  sizeOnDisk = br.readInt32();  // 0 = external TFC, >0 = inline
                    long bulkOff    = br.readUInt32(); // offset in TFC (uint32 handles >2 GB TFC)
                    int  mwi        = br.readInt32();
                    int  mhi        = br.readInt32();

                    if (elemCount <= 0 || mwi <= 0 || mhi <= 0) continue;

                    if (sizeOnDisk > 0 && sizeOnDisk <= 4 * 1024 * 1024) {
                        // Inline mip – data follows mwi/mhi in this UE3 variant
                        mipData = br.readBytes(sizeOnDisk);
                        mipW = mwi; mipH = mhi;
                        break;
                    } else if (sizeOnDisk == 0 && elemCount <= 32 * 1024 * 1024) {
                        // External TFC mip
                        if (tfcFile != null) {
                            try {
                                byte[] tfc = new byte[elemCount];
                                try (var fis = Files.newInputStream(tfcFile.toPath())) {
                                    fis.skipNBytes(bulkOff);
                                    int read = fis.readNBytes(tfc, 0, elemCount);
                                    if (read == elemCount) { mipData = tfc; mipW = mwi; mipH = mhi; break; }
                                }
                            } catch (Exception tfcEx) {
                                Umapica.LOGGER.debug("[Umapica] TFC read failed {}: {}",
                                        tfcFile.getName(), tfcEx.getMessage());
                            }
                        }
                    } else if (sizeOnDisk > 0 && sizeOnDisk < 33554432) {
                        br.skipBytes(sizeOnDisk); // skip too-large inline mip
                    }
                }

                if (mipData == null) {
                    Umapica.LOGGER.debug("[Umapica] UE3 tex '{}': no mip (sX={} sY={} fmt='{}' tfc={})",
                            ename, sizeX, sizeY, format, tfcName != null ? tfcName : "none");
                    continue;
                }

                int[] pixels = decodeAny(mipData, mipW, mipH, format);
                if (pixels == null) continue;

                NativeImage img = new NativeImage(NativeImage.Format.RGBA, mipW, mipH, false);
                for (int py = 0; py < mipH; py++) {
                    for (int px2 = 0; px2 < mipW; px2++) {
                        int argb = pixels[py * mipW + px2];
                        int r2 = (argb>>16)&0xFF, g2 = (argb>>8)&0xFF, b2 = argb&0xFF, a2 = (argb>>24)&0xFF;
                        img.setPixel(px2, py, (a2<<24)|(b2<<16)|(g2<<8)|r2);
                    }
                }
                DynamicTexture dt = new DynamicTexture(() -> "umapica_ue3_tex", img);
                String regName = ename.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                Identifier rl = Identifier.fromNamespaceAndPath("umapica", "dynamic/" + regName);
                Minecraft.getInstance().getTextureManager().register(rl, dt);
                Umapica.LOGGER.info("[Umapica] UE3 tex '{}' → {}x{} fmt='{}' tfc={}",
                        ename, mipW, mipH, format, tfcName != null ? tfcName : "inline");
                return rl;
            } catch (Exception e) {
                Umapica.LOGGER.debug("[Umapica] loadTexFromUE3Pkg '{}': {}", ename, e.getMessage());
            }
        }
        return null;
    }

    /** Decodes DXT1/DXT5/BGRA from {@code data} using the declared or inferred {@code fmt}. */
    private static int[] decodeAny(byte[] data, int w, int h, String fmt) {
        if (!fmt.isEmpty()) {
            if (fmt.contains("DXT1") || fmt.contains("PF_DXT1")) return DxtDecoder.decodeDXT1(data, w, h);
            if (fmt.contains("DXT5") || fmt.contains("PF_DXT5") ||
                fmt.contains("DXT3") || fmt.contains("PF_DXT3")) return DxtDecoder.decodeDXT5(data, w, h);
            if (fmt.contains("B8G8R8A8") || fmt.contains("A8R8G8B8")) return decodeBGRA(data, w * h);
        }
        return inferAndDecode(data, w, h, w * h);
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
