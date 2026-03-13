package net.shendi.umapica.umap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shendi.umapica.Umapica;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Loads Blender-exported mesh files ({@code .obj}, {@code .glb}/{@code .gltf}) into a
 * {@link UmapPackage} containing a single actor whose {@code meshData} is set directly.
 *
 * <h3>Coordinate mapping (Blender → UE → MC)</h3>
 * <pre>
 *   Blender OBJ/GLTF (right-hand, Y-up, -Z forward)
 *     ue_x =  bl_x × 100   (metres → UE centimetres)
 *     ue_y = -bl_z × 100
 *     ue_z =  bl_y × 100
 *   UE/MC renderer then maps  ue_z → mc_y,  ue_y → mc_z,  ue_x → mc_x.
 * </pre>
 * A 1 m tall Blender object therefore spans 1 MC block at the default scale of 100.
 */
public final class BlenderModelLoader {

    private BlenderModelLoader() {}

    // ------------------------------------------------------------------ //
    //  Public entry point
    // ------------------------------------------------------------------ //

    /**
     * Loads a Blender model file and returns a populated {@link UmapPackage} with one actor.
     * Supported extensions: {@code .obj}, {@code .glb}, {@code .gltf}.
     */
    public static UmapPackage load(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        UmapMeshData mesh;
        if (name.endsWith(".obj")) {
            mesh = loadObj(file);
        } else if (name.endsWith(".glb")) {
            mesh = loadGlb(file);
        } else if (name.endsWith(".gltf")) {
            mesh = loadGltf(file);
        } else {
            throw new IOException("Unsupported model format: " + file.getName());
        }
        if (mesh == null || mesh.positions.length == 0) {
            throw new IOException("No geometry found in " + file.getName());
        }
        Umapica.LOGGER.info("[Umapica] Blender model '{}': {} verts {} tris bounds={}",
                file.getName(), mesh.vertexCount(), mesh.triangleCount(), mesh.bounds);
        return wrap(file, mesh);
    }

    // ------------------------------------------------------------------ //
    //  OBJ loader
    // ------------------------------------------------------------------ //

    private static @Nullable UmapMeshData loadObj(File file) throws IOException {
        // Parse v / vt / f lines
        List<float[]> rawPos = new ArrayList<>();   // [x,y,z]
        List<float[]> rawUv  = new ArrayList<>();   // [u,v]

        // Unique (v_idx_1based, vt_idx_1based) → final vertex index
        Map<Long, Integer>  vertMap  = new LinkedHashMap<>();
        List<float[]>       finalPos = new ArrayList<>();
        List<float[]>       finalUv  = new ArrayList<>();
        List<Integer>       idxList  = new ArrayList<>();

        List<UmapMeshData.Section> sections = new ArrayList<>();
        String curMaterial = null;
        int sectionStart   = 0;
        boolean hasUvs     = false;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("v ") || line.startsWith("v\t")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    if (p.length >= 3) {
                        float x =  Float.parseFloat(p[0]);
                        float y = -Float.parseFloat(p[2]); // Blender -Z → UE Y
                        float z =  Float.parseFloat(p[1]); // Blender  Y → UE Z
                        // metres → UE centimetres
                        rawPos.add(new float[]{ x * 100f, y * 100f, z * 100f });
                    }
                } else if (line.startsWith("vt ") || line.startsWith("vt\t")) {
                    String[] p = line.substring(3).trim().split("\\s+");
                    if (p.length >= 2) {
                        float u = Float.parseFloat(p[0]);
                        float v = 1f - Float.parseFloat(p[1]); // OBJ UV origin bottom-left → flip V
                        rawUv.add(new float[]{ u, v });
                        hasUvs = true;
                    }
                } else if (line.startsWith("usemtl ") || line.startsWith("usemtl\t")) {
                    // Flush section
                    int newCount = idxList.size() - sectionStart;
                    if (newCount > 0) {
                        sections.add(new UmapMeshData.Section(sectionStart, newCount, sections.size(), curMaterial));
                        sectionStart = idxList.size();
                    }
                    curMaterial = line.substring(7).trim();
                } else if (line.startsWith("f ") || line.startsWith("f\t")) {
                    String[] verts = line.substring(2).trim().split("\\s+");
                    int[] faceIdx = new int[verts.length];
                    for (int i = 0; i < verts.length; i++) {
                        String[] comp = verts[i].split("/");
                        int vi = Integer.parseInt(comp[0]);   // 1-based
                        int ti = 0;                           // 0 = no UV
                        if (comp.length > 1 && !comp[1].isEmpty()) {
                            ti = Integer.parseInt(comp[1]);   // 1-based
                        }

                        long key = ((long) vi << 32) | (ti & 0xFFFFFFFFL);
                        Integer idx = vertMap.get(key);
                        if (idx == null) {
                            idx = finalPos.size();
                            vertMap.put(key, idx);
                            int posI = vi - 1;
                            finalPos.add(posI >= 0 && posI < rawPos.size() ? rawPos.get(posI) : new float[3]);
                            int uvI = ti - 1;
                            finalUv.add(uvI >= 0 && uvI < rawUv.size() ? rawUv.get(uvI) : new float[2]);
                        }
                        faceIdx[i] = idx;
                    }
                    // Fan-triangulate
                    for (int i = 1; i + 1 < faceIdx.length; i++) {
                        idxList.add(faceIdx[0]);
                        idxList.add(faceIdx[i]);
                        idxList.add(faceIdx[i + 1]);
                    }
                }
            }
        }

        int lastSectionCount = idxList.size() - sectionStart;
        if (lastSectionCount > 0) {
            sections.add(new UmapMeshData.Section(sectionStart, lastSectionCount, sections.size(), curMaterial));
        }
        if (finalPos.isEmpty() || idxList.isEmpty()) return null;

        // Build flat arrays
        float[] posArr  = new float[finalPos.size() * 3];
        float[] uvArr   = hasUvs ? new float[finalUv.size() * 2] : null;
        int[]   idxArr  = new int[idxList.size()];

        for (int i = 0; i < finalPos.size(); i++) {
            float[] p = finalPos.get(i);
            posArr[i*3] = p[0]; posArr[i*3+1] = p[1]; posArr[i*3+2] = p[2];
        }
        if (uvArr != null) {
            for (int i = 0; i < finalUv.size(); i++) {
                float[] t = finalUv.get(i);
                uvArr[i*2] = t[0]; uvArr[i*2+1] = t[1];
            }
        }
        for (int i = 0; i < idxList.size(); i++) idxArr[i] = idxList.get(i);

        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, computeBounds(posArr));
        mesh.uvs      = uvArr;
        mesh.sections = sections.toArray(new UmapMeshData.Section[0]);
        return mesh;
    }

    // ------------------------------------------------------------------ //
    //  GLB loader (binary GLTF 2.0)
    // ------------------------------------------------------------------ //

    private static @Nullable UmapMeshData loadGlb(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length < 20) throw new IOException("GLB too small: " + file.getName());
        if (le32(data, 0) != 0x46546C67) throw new IOException("Not a GLB file: " + file.getName());
        // int version = le32(data, 4); // should be 2

        // Chunk 0 – JSON
        int chunk0Len  = le32(data, 12);
        int chunk0Type = le32(data, 16);
        if (chunk0Type != 0x4E4F534A) throw new IOException("GLB chunk-0 is not JSON");
        String jsonStr = new String(data, 20, chunk0Len, java.nio.charset.StandardCharsets.UTF_8);

        // Chunk 1 – BIN (optional)
        byte[] bin = null;
        int c1Start = 20 + chunk0Len;
        if (c1Start + 8 <= data.length) {
            int c1Len  = le32(data, c1Start);
            int c1Type = le32(data, c1Start + 4);
            if (c1Type == 0x004E4942 && c1Len > 0) {
                bin = Arrays.copyOfRange(data, c1Start + 8, c1Start + 8 + c1Len);
            }
        }
        return parseGltf(jsonStr, bin, file.getName());
    }

    /** Loads a plain-JSON .gltf with an external .bin buffer next to it. */
    private static @Nullable UmapMeshData loadGltf(File file) throws IOException {
        String jsonStr = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        // Try to load a companion .bin file with the same base name
        String binName = file.getName().replaceAll("(?i)\\.gltf$", ".bin");
        File binFile = new File(file.getParentFile(), binName);
        byte[] bin = binFile.exists() ? Files.readAllBytes(binFile.toPath()) : null;
        return parseGltf(jsonStr, bin, file.getName());
    }

    // ------------------------------------------------------------------ //
    //  GLTF JSON mesh extraction
    // ------------------------------------------------------------------ //

    private static @Nullable UmapMeshData parseGltf(String jsonStr, @Nullable byte[] bin, String srcName)
            throws IOException {
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

        JsonArray meshes = root.has("meshes") ? root.getAsJsonArray("meshes") : null;
        if (meshes == null || meshes.isEmpty())
            throw new IOException("No meshes in " + srcName);

        JsonArray accessors   = root.has("accessors")   ? root.getAsJsonArray("accessors")   : new JsonArray();
        JsonArray bufferViews = root.has("bufferViews") ? root.getAsJsonArray("bufferViews") : new JsonArray();
        JsonArray materials   = root.has("materials")   ? root.getAsJsonArray("materials")   : new JsonArray();

        // Collect all primitives across all meshes
        List<float[]> allPos = new ArrayList<>();
        List<float[]> allUv  = new ArrayList<>();
        List<Integer> allIdx = new ArrayList<>();
        List<UmapMeshData.Section> sections = new ArrayList<>();
        int sectionStart = 0;
        boolean hasUvs = false;

        for (JsonElement meshEl : meshes) {
            JsonArray primitives = meshEl.getAsJsonObject().getAsJsonArray("primitives");
            if (primitives == null) continue;

            for (JsonElement primEl : primitives) {
                JsonObject prim  = primEl.getAsJsonObject();
                JsonObject attrs = prim.has("attributes") ? prim.getAsJsonObject("attributes") : null;
                if (attrs == null) continue;

                // Material name
                String matName = null;
                if (prim.has("material")) {
                    int mi = prim.get("material").getAsInt();
                    if (mi >= 0 && mi < materials.size()) {
                        JsonObject mat = materials.get(mi).getAsJsonObject();
                        matName = mat.has("name") ? mat.get("name").getAsString() : null;
                    }
                }

                // POSITION accessor
                if (!attrs.has("POSITION")) continue;
                int posAccIdx = attrs.get("POSITION").getAsInt();
                float[][] positions = readAccessorVec3(accessors, bufferViews, bin, posAccIdx, srcName);
                if (positions == null || positions.length == 0) continue;

                // TEXCOORD_0 accessor
                float[][] uvs = null;
                if (attrs.has("TEXCOORD_0")) {
                    int uvAccIdx = attrs.get("TEXCOORD_0").getAsInt();
                    uvs = readAccessorVec2(accessors, bufferViews, bin, uvAccIdx, srcName);
                    if (uvs != null) hasUvs = true;
                }

                // indices accessor
                int[]  indices;
                if (prim.has("indices")) {
                    int idxAccIdx = prim.get("indices").getAsInt();
                    indices = readAccessorIndices(accessors, bufferViews, bin, idxAccIdx, srcName);
                } else {
                    // Non-indexed: sequential triangles
                    indices = new int[positions.length];
                    for (int i = 0; i < indices.length; i++) indices[i] = allPos.size() + i;
                }
                if (indices == null || indices.length < 3) continue;

                // Remap indices to global vertex array
                int baseVertex = allPos.size();
                for (float[] pos : positions) {
                    // Apply Blender→UE coordinate transform (same as OBJ)
                    allPos.add(new float[]{ pos[0] * 100f, -pos[2] * 100f, pos[1] * 100f });
                }
                if (uvs != null) {
                    for (float[] uv : uvs) {
                        allUv.add(new float[]{ uv[0], 1f - uv[1] }); // flip V
                    }
                } else {
                    for (int i = 0; i < positions.length; i++) allUv.add(new float[2]);
                }

                for (int idx : indices) allIdx.add(baseVertex + idx);

                int sectionCount = allIdx.size() - sectionStart;
                if (sectionCount > 0) {
                    sections.add(new UmapMeshData.Section(sectionStart, sectionCount, sections.size(), matName));
                    sectionStart = allIdx.size();
                }
            }
        }

        if (allPos.isEmpty() || allIdx.isEmpty()) return null;

        float[] posArr = new float[allPos.size() * 3];
        for (int i = 0; i < allPos.size(); i++) {
            float[] p = allPos.get(i);
            posArr[i*3] = p[0]; posArr[i*3+1] = p[1]; posArr[i*3+2] = p[2];
        }
        float[] uvArr = null;
        if (hasUvs) {
            uvArr = new float[allUv.size() * 2];
            for (int i = 0; i < allUv.size(); i++) {
                float[] t = allUv.get(i);
                uvArr[i*2] = t[0]; uvArr[i*2+1] = t[1];
            }
        }
        int[] idxArr = new int[allIdx.size()];
        for (int i = 0; i < allIdx.size(); i++) idxArr[i] = allIdx.get(i);

        UmapMeshData mesh = new UmapMeshData(posArr, idxArr, computeBounds(posArr));
        mesh.uvs      = uvArr;
        mesh.sections = sections.toArray(new UmapMeshData.Section[0]);
        return mesh;
    }

    // ------------------------------------------------------------------ //
    //  GLTF accessor helpers
    // ------------------------------------------------------------------ //

    /** Reads a VEC3 float accessor; returns float[count][3] or null on error. */
    private static float[][] readAccessorVec3(JsonArray accessors, JsonArray bufferViews,
                                               @Nullable byte[] bin, int accIdx, String src) {
        if (accIdx < 0 || accIdx >= accessors.size()) return null;
        JsonObject acc = accessors.get(accIdx).getAsJsonObject();
        int count    = acc.get("count").getAsInt();
        int compType = acc.get("componentType").getAsInt(); // 5126 = FLOAT
        if (compType != 5126) { Umapica.LOGGER.warn("[Umapica] GLB {}: POSITION is not FLOAT", src); return null; }

        byte[] buf = resolveBuffer(acc, bufferViews, bin, count * 12);
        if (buf == null) return null;
        int byteOffset = acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0;

        float[][] result = new float[count][3];
        for (int i = 0; i < count; i++) {
            int off = byteOffset + i * 12;
            result[i][0] = leFloat(buf, off);
            result[i][1] = leFloat(buf, off + 4);
            result[i][2] = leFloat(buf, off + 8);
        }
        return result;
    }

    /** Reads a VEC2 float accessor; returns float[count][2] or null on error. */
    private static float[][] readAccessorVec2(JsonArray accessors, JsonArray bufferViews,
                                               @Nullable byte[] bin, int accIdx, String src) {
        if (accIdx < 0 || accIdx >= accessors.size()) return null;
        JsonObject acc = accessors.get(accIdx).getAsJsonObject();
        int count    = acc.get("count").getAsInt();
        int compType = acc.get("componentType").getAsInt();
        if (compType != 5126) return null;

        byte[] buf = resolveBuffer(acc, bufferViews, bin, count * 8);
        if (buf == null) return null;
        int byteOffset = acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0;

        float[][] result = new float[count][2];
        for (int i = 0; i < count; i++) {
            int off = byteOffset + i * 8;
            result[i][0] = leFloat(buf, off);
            result[i][1] = leFloat(buf, off + 4);
        }
        return result;
    }

    /** Reads a SCALAR uint16/uint32 index accessor; returns int[] or null on error. */
    private static int[] readAccessorIndices(JsonArray accessors, JsonArray bufferViews,
                                              @Nullable byte[] bin, int accIdx, String src) {
        if (accIdx < 0 || accIdx >= accessors.size()) return null;
        JsonObject acc   = accessors.get(accIdx).getAsJsonObject();
        int count        = acc.get("count").getAsInt();
        int compType     = acc.get("componentType").getAsInt(); // 5121=ubyte, 5123=ushort, 5125=uint

        int elemBytes = (compType == 5125) ? 4 : (compType == 5123) ? 2 : 1;
        byte[] buf = resolveBuffer(acc, bufferViews, bin, count * elemBytes);
        if (buf == null) return null;
        int byteOffset = acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0;

        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int off = byteOffset + i * elemBytes;
            result[i] = switch (elemBytes) {
                case 4 -> le32(buf, off);
                case 2 -> leUint16(buf, off);
                default -> buf[off] & 0xFF;
            };
        }
        return result;
    }

    /** Resolves a bufferView to the backing byte array slice. */
    private static byte[] resolveBuffer(JsonObject acc, JsonArray bufferViews,
                                         @Nullable byte[] bin, int minBytes) {
        if (!acc.has("bufferView")) return null;
        int bvIdx = acc.get("bufferView").getAsInt();
        if (bvIdx < 0 || bvIdx >= bufferViews.size()) return null;
        JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
        int byteOffset = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
        if (bin != null && bv.has("byteLength")) {
            int byteLength = bv.get("byteLength").getAsInt();
            if (byteOffset + byteLength <= bin.length) {
                return Arrays.copyOfRange(bin, byteOffset, byteOffset + byteLength);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /** Wraps a loaded mesh into a UmapPackage with a single actor. */
    private static UmapPackage wrap(File file, UmapMeshData mesh) {
        UmapPackage pkg  = new UmapPackage(file);
        String meshName  = file.getName().replaceAll("(?i)\\.[^.]+$", "");
        UmapActor actor  = new UmapActor("BlenderMesh", meshName);
        actor.transform  = FTransform.IDENTITY;
        actor.localBounds = mesh.bounds;
        actor.meshData   = mesh;
        pkg.actors.add(actor);
        return pkg;
    }

    private static FBox computeBounds(float[] pos) {
        if (pos.length == 0) return FBox.EMPTY;
        float mnX = pos[0], mnY = pos[1], mnZ = pos[2];
        float mxX = mnX,    mxY = mnY,    mxZ = mnZ;
        for (int i = 3; i < pos.length; i += 3) {
            if (pos[i]   < mnX) mnX = pos[i];   if (pos[i]   > mxX) mxX = pos[i];
            if (pos[i+1] < mnY) mnY = pos[i+1]; if (pos[i+1] > mxY) mxY = pos[i+1];
            if (pos[i+2] < mnZ) mnZ = pos[i+2]; if (pos[i+2] > mxZ) mxZ = pos[i+2];
        }
        return new FBox(new FVector(mnX, mnY, mnZ), new FVector(mxX, mxY, mxZ), true);
    }

    private static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8)
             | ((d[off+2] & 0xFF) << 16) | ((d[off+3] & 0xFF) << 24);
    }
    private static int leUint16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8);
    }
    private static float leFloat(byte[] d, int off) {
        return Float.intBitsToFloat(le32(d, off));
    }
}
