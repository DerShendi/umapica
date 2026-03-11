package net.shendi.umapica.umap;

import java.io.IOException;

/**
 * Pure-Java LZO1X decompressor.
 *
 * <p>Ported directly from the reference C implementation in
 * {@code lzo-2.10/src/lzo1x_d.ch} (GPL-2, Markus F.X.J. Oberhumer).
 *
 * <p>Corresponds exactly to the Python implementation verified against
 * the lzokay library on mafia_town.umap chunk data.
 */
public final class Lzo1xDecompressor {

    private static final int M4_MAX_OFFSET = 0x4000;

    private Lzo1xDecompressor() {}

    /**
     * Decompress {@code src[srcOff..srcOff+srcLen)} to a new byte array.
     *
     * @param src              compressed data buffer
     * @param srcOff           start of compressed data in buffer
     * @param srcLen           length of compressed data (informational)
     * @param uncompressedSize expected output size
     * @return decompressed byte array of exactly {@code uncompressedSize} bytes
     * @throws IOException on corrupt or truncated data
     */
    public static byte[] decompress(byte[] src, int srcOff, int srcLen,
                                    int uncompressedSize) throws IOException {
        final byte[] op = new byte[uncompressedSize];
        int ip = srcOff; // input pointer
        int np = 0;      // output write pointer

        int t;

        boolean goToInnerMatch = false; // simulates C-style goto
        t = 0; // will be set before use

        // ── Optional first-literal-run: src[0] > 17 ──────────────────────────
        if (u8(src, ip) > 17) {
            t = u8(src, ip++) - 17;
            // copy t literals
            for (int i = 0; i < t; i++) op[np++] = src[ip++];

            if (t < 4) {
                // match_next: t literals copied, now read next t then enter inner loop
                t = u8(src, ip++);
                goToInnerMatch = true;
            } else {
                // fall through to first_literal_run
                t = u8(src, ip++);
                if (t < 16) {
                    // M1 at first_literal_run
                    int mDist = 1 + M4_MAX_OFFSET + (t >> 2) + (u8(src, ip++) << 2);
                    np = copyMatch(op, np, np - mDist, 3);
                    t = u8(src, ip - 1) & 3;
                    if (t != 0) {
                        for (int i = 0; i < t; i++) op[np++] = src[ip++];
                    }
                    // match_done: t=0, go to outer loop (outer reads t fresh)
                    goToInnerMatch = false; // outer loop will read t
                } else {
                    // t >= 16: enter inner match loop with this t
                    goToInnerMatch = true;
                }
            }
        }

        // ── Outer for(;;) loop ────────────────────────────────────────────────
        //noinspection InfiniteLoopStatement
        outer:
        while (true) {
            if (!goToInnerMatch) {
                t = u8(src, ip++);

                if (t < 16) {
                    // ── Literal run ──────────────────────────────────────────
                    if (t == 0) {
                        while (u8(src, ip) == 0) { t += 255; ip++; }
                        t += 15 + u8(src, ip++);
                    }
                    int n = t + 3;
                    for (int i = 0; i < n; i++) op[np++] = src[ip++];

                    // ── first_literal_run ─────────────────────────────────────
                    t = u8(src, ip++);
                    if (t < 16) {
                        int mDist = 1 + M4_MAX_OFFSET + (t >> 2) + (u8(src, ip++) << 2);
                        np = copyMatch(op, np, np - mDist, 3);
                        t = u8(src, ip - 1) & 3;
                        if (t != 0) {
                            for (int i = 0; i < t; i++) op[np++] = src[ip++];
                        }
                        // match_done with t==0 → continue outer (outer reads new t)
                        continue outer;
                    }
                    // t >= 16 → fall into match section
                }
            }
            goToInnerMatch = false;

            // ── Inner match loop ──────────────────────────────────────────────
            inner:
            while (true) {
                int mPos, mLen;

                if (t >= 64) {
                    // M2
                    mPos = np - 1 - ((t >> 2) & 7) - (u8(src, ip++) << 3);
                    mLen = (t >> 5) - 1;
                } else if (t >= 32) {
                    // M3
                    mLen = t & 31;
                    if (mLen == 0) {
                        while (u8(src, ip) == 0) { mLen += 255; ip++; }
                        mLen += 31 + u8(src, ip++);
                    }
                    mPos = np - 1 - (u8(src, ip) >> 2) - (u8(src, ip + 1) << 6);
                    ip += 2;
                } else if (t >= 16) {
                    // M4
                    mPos = np - ((t & 8) << 11);
                    mLen = t & 7;
                    if (mLen == 0) {
                        while (u8(src, ip) == 0) { mLen += 255; ip++; }
                        mLen += 7 + u8(src, ip++);
                    }
                    mPos -= (u8(src, ip) >> 2) + (u8(src, ip + 1) << 6);
                    ip += 2;
                    if (mPos == np) {
                        return op; // eof_found
                    }
                    mPos -= M4_MAX_OFFSET;
                    if (mPos < 0)
                        throw new IOException(
                            "LZO1X: M4 lookbehind underflow mPos=" + mPos
                            + " np=" + np + " ip=" + ip);
                } else {
                    // M1 (appears only inside inner loop)
                    mPos = np - 1 - (t >> 2) - (u8(src, ip++) << 2);
                    if (mPos < 0)
                        throw new IOException(
                            "LZO1X: M1 lookbehind underflow mPos=" + mPos
                            + " np=" + np + " ip=" + ip);
                    op[np++] = op[mPos];
                    op[np++] = op[mPos + 1];
                    // match_done for M1: uses ip[-2] — the byte BEFORE the extra byte
                    t = u8(src, ip - 2) & 3;
                    if (t == 0) break inner;
                    for (int i = 0; i < t; i++) op[np++] = src[ip++];
                    t = u8(src, ip++);
                    continue inner;
                }

                // copy_match: copy mLen+2 bytes (byte-by-byte supports overlap)
                np = copyMatch(op, np, mPos, mLen + 2);

                // match_done: ip-2 because we read 2-byte dist (or ip-1 after extend)
                t = u8(src, ip - 2) & 3;
                if (t == 0) break inner;
                for (int i = 0; i < t; i++) op[np++] = src[ip++];
                t = u8(src, ip++);
                // continue inner loop with new t
            }
            // outer loop reads t at top (goToInnerMatch == false)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }

    /**
     * Byte-by-byte copy from {@code dst[srcPos..)} to {@code dst[dstOff..)}.
     * Handles overlapping (run-length expansion) correctly.
     */
    private static int copyMatch(byte[] dst, int dstOff, int srcPos, int n)
            throws IOException {
        if (srcPos < 0)
            throw new IOException(
                "LZO1X: match lookbehind underflow srcPos=" + srcPos
                + " dstOff=" + dstOff);
        for (int i = 0; i < n; i++) dst[dstOff++] = dst[srcPos++];
        return dstOff;
    }
}
