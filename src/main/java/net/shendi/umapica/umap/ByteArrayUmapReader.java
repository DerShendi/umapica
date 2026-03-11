package net.shendi.umapica.umap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * An in-memory variant of {@link UmapReader} backed by a byte array.
 * Used for reading UE3 packages that are LZO-compressed: the caller first
 * decompresses all chunks into a contiguous byte[] and then wraps it here.
 *
 * <p>Virtual addressing: the array starts at {@code baseVirtualOffset}.
 * All {@link #seek(long)} calls accept virtual addresses; the reader subtracts
 * the base to get the physical index into the array.
 */
public class ByteArrayUmapReader {

    private final byte[] data;
    /** Virtual address of data[0]. */
    private final long baseVirtualOffset;
    /** Current read cursor (physical index into data[]). */
    private int pos;

    /** Engine file version (EUnrealEngineObjectUE4Version). */
    public int fileVersionUE4 = 522;
    /** UE5 sub-version. 0 = UE4. */
    public int fileVersionUE5 = 0;
    /** Name table – set by UmapPackage after the name table is read. */
    public String[] names = new String[0];

    public ByteArrayUmapReader(byte[] data, long baseVirtualOffset) {
        this.data              = data;
        this.baseVirtualOffset = baseVirtualOffset;
        this.pos               = 0;
    }

    // -------------------------------------------------------- //
    //  Positioning
    // -------------------------------------------------------- //

    /** Seek to a virtual address. */
    public void seek(long virtualAddress) throws IOException {
        long phys = virtualAddress - baseVirtualOffset;
        if (phys < 0 || phys > data.length)
            throw new IOException(String.format(
                    "ByteArrayUmapReader.seek(0x%X): out of range [base=0x%X len=%d]",
                    virtualAddress, baseVirtualOffset, data.length));
        pos = (int) phys;
    }

    /** Returns the current virtual address. */
    public long position() {
        return baseVirtualOffset + pos;
    }

    /** Returns the underlying data array (for caching – do not mutate). */
    public byte[] getData() { return data; }

    /** Returns the base virtual offset of this reader. */
    public long getBaseVirt() { return baseVirtualOffset; }

    /** Skip {@code count} bytes. */
    public void skipBytes(int count) throws IOException {
        ensureRemaining(count);
        pos += count;
    }

    // -------------------------------------------------------- //
    //  Primitives
    // -------------------------------------------------------- //

    public byte readByte() throws IOException {
        ensureRemaining(1);
        return data[pos++];
    }

    public boolean readBool8() throws IOException {
        return readByte() != 0;
    }

    public int readInt32() throws IOException {
        ensureRemaining(4);
        int v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        pos += 4;
        return v;
    }

    public long readUInt32() throws IOException {
        return readInt32() & 0xFFFFFFFFL;
    }

    public long readInt64() throws IOException {
        ensureRemaining(8);
        long v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        pos += 8;
        return v;
    }

    public float readFloat() throws IOException {
        ensureRemaining(4);
        float v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        pos += 4;
        return v;
    }

    public double readDouble() throws IOException {
        ensureRemaining(8);
        double v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        pos += 8;
        return v;
    }

    public short readInt16() throws IOException {
        ensureRemaining(2);
        short v = ByteBuffer.wrap(data, pos, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
        pos += 2;
        return v;
    }

    public int readUInt16() throws IOException {
        return readInt16() & 0xFFFF;
    }

    /** Read exactly {@code count} bytes. */
    public byte[] readBytes(int count) throws IOException {
        ensureRemaining(count);
        byte[] buf = new byte[count];
        System.arraycopy(data, pos, buf, 0, count);
        pos += count;
        return buf;
    }

    // -------------------------------------------------------- //
    //  Unreal compound types
    // -------------------------------------------------------- //

    /**
     * Reads a UE3 name entry: int32 slen + slen bytes (null-terminated ASCII) + 8 bytes hash.
     * Returns the name string without the trailing null.
     */
    public String readUE3Name() throws IOException {
        int slen = readInt32();
        if (slen <= 0) {
            skipBytes(8); // hash
            return "";
        }
        byte[] chars = readBytes(slen);
        skipBytes(8); // 8-byte hash
        // strip trailing null
        int end = slen;
        if (end > 0 && chars[end - 1] == 0) end--;
        return new String(chars, 0, end, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads a UE4-style FString: int32 len + len bytes ASCII (or -len UTF-16LE).
     */
    public String readFString() throws IOException {
        int len = readInt32();
        if (len == 0) return "";
        if (len > 0) {
            byte[] chars = readBytes(len);
            return new String(chars, 0, Math.max(0, len - 1), StandardCharsets.ISO_8859_1);
        } else {
            int charCount = -len;
            byte[] chars = readBytes(charCount * 2);
            return new String(chars, 0, Math.max(0, (charCount - 1) * 2), StandardCharsets.UTF_16LE);
        }
    }

    /**
     * Reads an FName (nameIndex: int32 + nameNumber: int32).
     */
    public String readFName() throws IOException {
        int nameIndex  = readInt32();
        int nameNumber = readInt32();
        if (nameIndex < 0 || nameIndex >= names.length) return "None";
        String base = names[nameIndex];
        return nameNumber > 0 ? base + "_" + nameNumber : base;
    }

    /** Skip an FGuid (16 bytes). */
    public void skipFGuid() throws IOException {
        skipBytes(16);
    }

    // -------------------------------------------------------- //
    //  UE math types
    // -------------------------------------------------------- //

    public FVector readFVector() throws IOException {
        if (fileVersionUE5 >= 1) {
            return new FVector(readDouble(), readDouble(), readDouble());
        } else {
            return new FVector(readFloat(), readFloat(), readFloat());
        }
    }

    public FRotator readFRotator() throws IOException {
        if (fileVersionUE5 >= 1) {
            return new FRotator(readDouble(), readDouble(), readDouble());
        } else {
            return new FRotator(readFloat(), readFloat(), readFloat());
        }
    }

    public FQuat readFQuat() throws IOException {
        if (fileVersionUE5 >= 1) {
            return new FQuat(readDouble(), readDouble(), readDouble(), readDouble());
        } else {
            return new FQuat(readFloat(), readFloat(), readFloat(), readFloat());
        }
    }

    public FTransform readFTransform() throws IOException {
        return new FTransform(readFQuat(), readFVector(), readFVector());
    }

    public FBox readFBox() throws IOException {
        return new FBox(readFVector(), readFVector(), readBool8());
    }

    // -------------------------------------------------------- //
    //  Internal helper
    // -------------------------------------------------------- //

    private void ensureRemaining(int need) throws IOException {
        if (pos + need > data.length)
            throw new IOException(String.format(
                    "ByteArrayUmapReader: underflow at virt=0x%X (need %d, have %d)",
                    position(), need, data.length - pos));
    }
}
