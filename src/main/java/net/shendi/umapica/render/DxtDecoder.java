package net.shendi.umapica.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decompresses DXT1 (BC1) and DXT5 (BC3) block-compressed textures to RGBA8.
 *
 * <p>These are the two most common formats found in UE4/UE5 .uasset texture exports.</p>
 */
public final class DxtDecoder {

    private DxtDecoder() {}

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Decompresses a DXT1-encoded mip level to a flat RGBA8 array.
     *
     * @param src    raw compressed bytes (width/4 * height/4 * 8 bytes)
     * @param width  texture width in pixels (must be a multiple of 4)
     * @param height texture height in pixels (must be a multiple of 4)
     * @return int[] of length width*height, each int = 0xAARRGGBB
     */
    public static int[] decodeDXT1(byte[] src, int width, int height) {
        int[] out = new int[width * height];
        int blockW = Math.max(1, width  >> 2);
        int blockH = Math.max(1, height >> 2);
        int srcOff = 0;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                decodeBC1Block(src, srcOff, out, bx * 4, by * 4, width, false);
                srcOff += 8;
            }
        }
        return out;
    }

    /**
     * Decompresses a DXT5-encoded mip level to a flat RGBA8 array.
     *
     * @param src    raw compressed bytes (width/4 * height/4 * 16 bytes)
     * @param width  texture width in pixels (must be a multiple of 4)
     * @param height texture height in pixels (must be a multiple of 4)
     * @return int[] of length width*height, each int = 0xAARRGGBB
     */
    public static int[] decodeDXT5(byte[] src, int width, int height) {
        int[] out = new int[width * height];
        int blockW = Math.max(1, width  >> 2);
        int blockH = Math.max(1, height >> 2);
        int srcOff = 0;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                // 8-byte alpha block
                int[] alphas = decodeBC4Block(src, srcOff);
                srcOff += 8;
                // 8-byte color block (DXT1 without punch-through)
                decodeBC1Block(src, srcOff, out, bx * 4, by * 4, width, true);
                srcOff += 8;
                // Blit alpha values into existing pixels
                int px = bx * 4, py = by * 4;
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        if (px + x >= width || py + y >= height) continue;
                        int pi = (py + y) * width + (px + x);
                        out[pi] = (out[pi] & 0x00FFFFFF) | (alphas[y * 4 + x] << 24);
                    }
                }
            }
        }
        return out;
    }

    /** Convert RGBA8 int array to a raw RGBA byte array (for NativeImage). */
    public static byte[] toRGBABytes(int[] argb, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < argb.length; i++) {
            int v = argb[i];
            rgba[i * 4]     = (byte) ((v >> 16) & 0xFF); // R
            rgba[i * 4 + 1] = (byte) ((v >>  8) & 0xFF); // G
            rgba[i * 4 + 2] = (byte) ( v        & 0xFF); // B
            rgba[i * 4 + 3] = (byte) ((v >> 24) & 0xFF); // A
        }
        return rgba;
    }

    // ------------------------------------------------------------------ //
    //  Internal block decoders
    // ------------------------------------------------------------------ //

    /**
     * Decodes one 4×4 BC1 (DXT1) color block into the output array.
     *
     * @param noAlpha when {@code true}, the "transparent black" code-word
     *                is still decoded as opaque (used when alpha comes from BC4).
     */
    private static void decodeBC1Block(byte[] src, int srcOff,
                                        int[] out, int bx, int by,
                                        int width, boolean noAlpha) {
        int c0 = (src[srcOff]     & 0xFF) | ((src[srcOff + 1] & 0xFF) << 8);
        int c1 = (src[srcOff + 2] & 0xFF) | ((src[srcOff + 3] & 0xFF) << 8);

        int r0 = expand5to8((c0 >> 11) & 31), g0 = expand6to8((c0 >> 5) & 63), b0 = expand5to8(c0 & 31);
        int r1 = expand5to8((c1 >> 11) & 31), g1 = expand6to8((c1 >> 5) & 63), b1 = expand5to8(c1 & 31);

        int[] colors = new int[4];
        colors[0] = 0xFF000000 | (r0 << 16) | (g0 << 8) | b0;
        colors[1] = 0xFF000000 | (r1 << 16) | (g1 << 8) | b1;
        if (c0 > c1 || noAlpha) {
            colors[2] = 0xFF000000 | (((2*r0+r1)/3) << 16) | (((2*g0+g1)/3) << 8) | ((2*b0+b1)/3);
            colors[3] = 0xFF000000 | (((r0+2*r1)/3) << 16) | (((g0+2*g1)/3) << 8) | ((b0+2*b1)/3);
        } else {
            colors[2] = 0xFF000000 | (((r0+r1)/2) << 16) | (((g0+g1)/2) << 8) | ((b0+b1)/2);
            colors[3] = 0; // transparent black
        }

        int rowBits = srcOff + 4;
        for (int y = 0; y < 4; y++) {
            int row = src[rowBits + y] & 0xFF;
            for (int x = 0; x < 4; x++) {
                if (bx + x >= width) continue;
                int pi = (by + y) * width + (bx + x);
                if (pi >= out.length) continue;
                out[pi] = colors[(row >> (x * 2)) & 3];
            }
        }
    }

    /**
     * Decodes one 4×4 BC4 (single-channel) alpha block.
     *
     * @return int[16] of alpha values 0–255 in row-major order
     */
    private static int[] decodeBC4Block(byte[] src, int srcOff) {
        int a0 = src[srcOff]     & 0xFF;
        int a1 = src[srcOff + 1] & 0xFF;
        int[] alphas = new int[8];
        alphas[0] = a0;
        alphas[1] = a1;
        if (a0 > a1) {
            for (int i = 2; i < 8; i++) alphas[i] = ((8-i)*a0 + (i-1)*a1) / 7;
        } else {
            for (int i = 2; i < 6; i++) alphas[i] = ((6-i)*a0 + (i-1)*a1) / 5;
            alphas[6] = 0;
            alphas[7] = 255;
        }
        // 48-bit index block = 6 bytes
        long idx = 0;
        for (int k = 0; k < 6; k++) idx |= ((long)(src[srcOff + 2 + k] & 0xFF)) << (k * 8);
        int[] result = new int[16];
        for (int k = 0; k < 16; k++) result[k] = alphas[(int)((idx >> (k * 3)) & 7)];
        return result;
    }

    // ------------------------------------------------------------------ //
    //  Bit-depth expanders
    // ------------------------------------------------------------------ //

    private static int expand5to8(int v) { return (v << 3) | (v >> 2); }
    private static int expand6to8(int v) { return (v << 2) | (v >> 4); }
}
