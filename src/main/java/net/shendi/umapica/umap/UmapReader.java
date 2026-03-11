package net.shendi.umapica.umap;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Low-level little-endian binary reader for Unreal Engine package files.
 * Supports all primitive Unreal types: FString, FName, FGuid, etc.
 */
public class UmapReader implements Closeable {

    private final RandomAccessFile raf;
    /** Engine file version (EUnrealEngineObjectUE4Version). */
    public int fileVersionUE4 = 522;
    /** UE5 sub-version (EUnrealEngineObjectUE5Version). 0 = UE4. */
    public int fileVersionUE5 = 0;
    /** Name table – populated by UmapPackage after the header is read. */
    public String[] names = new String[0];

    public UmapReader(File file) throws IOException {
        raf = new RandomAccessFile(file, "r");
    }

    // ------------------------------------------------------------------ //
    //  Positioning
    // ------------------------------------------------------------------ //

    public void seek(long pos) throws IOException {
        raf.seek(pos);
    }

    public long position() throws IOException {
        return raf.getFilePointer();
    }

    public void skip(long bytes) throws IOException {
        raf.seek(raf.getFilePointer() + bytes);
    }

    // ------------------------------------------------------------------ //
    //  Primitives
    // ------------------------------------------------------------------ //

    public byte readByte() throws IOException {
        return raf.readByte();
    }

    public boolean readBool8() throws IOException {
        return raf.readByte() != 0;
    }

    public int readInt32() throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Reads a 32-bit integer and returns it as an unsigned long. */
    public long readUInt32() throws IOException {
        return readInt32() & 0xFFFFFFFFL;
    }

    public long readInt64() throws IOException {
        byte[] b = new byte[8];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public float readFloat() throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    public double readDouble() throws IOException {
        byte[] b = new byte[8];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    public short readInt16() throws IOException {
        byte[] b = new byte[2];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    public int readUInt16() throws IOException {
        return readInt16() & 0xFFFF;
    }

    /** Skip bytes without reading them. */
    public void skipBytes(int count) throws IOException {
        raf.seek(raf.getFilePointer() + count);
    }

    // ------------------------------------------------------------------ //
    //  Unreal compound types
    // ------------------------------------------------------------------ //

    /**
     * Reads an Unreal FString.
     * Positive length = ASCII (including null), negative length = UTF-16LE.
     */
    public String readFString() throws IOException {
        int len = readInt32();
        if (len == 0) return "";
        if (len > 0) {
            // ASCII (actually Latin-1 / single-byte encoding)
            byte[] chars = new byte[len];
            raf.readFully(chars);
            // strip trailing null
            return new String(chars, 0, Math.max(0, len - 1), StandardCharsets.ISO_8859_1);
        } else {
            // UTF-16LE, length is number of uint16 code units (including null)
            int charCount = -len;
            byte[] chars = new byte[charCount * 2];
            raf.readFully(chars);
            return new String(chars, 0, Math.max(0, (charCount - 1) * 2), StandardCharsets.UTF_16LE);
        }
    }

    /**
     * Reads an FName (nameIndex: int32 + nameNumber: int32).
     * Resolves via the pre-loaded name table.  Returns "None" if index is out of range.
     */
    public String readFName() throws IOException {
        int nameIndex  = readInt32();
        int nameNumber = readInt32(); // usually 0; non-zero = "Name_N" suffix
        if (nameIndex < 0 || nameIndex >= names.length) return "None";
        String base = names[nameIndex];
        return nameNumber > 0 ? base + "_" + nameNumber : base;
    }

    /** Reads an FGuid (four int32s) – returns them as a hex string for debugging. */
    public String readFGuid() throws IOException {
        int a = readInt32(), b = readInt32(), c = readInt32(), d = readInt32();
        return String.format("%08X-%08X-%08X-%08X", a, b, c, d);
    }

    /** Skip an FGuid (16 bytes). */
    public void skipFGuid() throws IOException {
        skipBytes(16);
    }

    // ------------------------------------------------------------------ //
    //  UE math types
    // ------------------------------------------------------------------ //

    /**
     * Reads an FVector, using double precision for UE5 (fileVersionUE5 >= 1 = VER_UE5_LARGE_WORLD_COORDINATES)
     * or single precision for UE4.
     */
    public FVector readFVector() throws IOException {
        if (fileVersionUE5 >= 1) {
            double x = readDouble(), y = readDouble(), z = readDouble();
            return new FVector(x, y, z);
        } else {
            double x = readFloat(), y = readFloat(), z = readFloat();
            return new FVector(x, y, z);
        }
    }

    /**
     * Reads an FRotator (pitch, yaw, roll), using double precision for UE5.
     * Note: UE internal order is Pitch, Yaw, Roll.
     */
    public FRotator readFRotator() throws IOException {
        if (fileVersionUE5 >= 1) {
            double pitch = readDouble(), yaw = readDouble(), roll = readDouble();
            return new FRotator(pitch, yaw, roll);
        } else {
            double pitch = readFloat(), yaw = readFloat(), roll = readFloat();
            return new FRotator(pitch, yaw, roll);
        }
    }

    /**
     * Reads an FQuat (x, y, z, w), using double precision for UE5.
     */
    public FQuat readFQuat() throws IOException {
        if (fileVersionUE5 >= 1) {
            double x = readDouble(), y = readDouble(), z = readDouble(), w = readDouble();
            return new FQuat(x, y, z, w);
        } else {
            double x = readFloat(), y = readFloat(), z = readFloat(), w = readFloat();
            return new FQuat(x, y, z, w);
        }
    }

    /**
     * Reads an FTransform: FQuat rotation + FVector translation + FVector scale3D.
     */
    public FTransform readFTransform() throws IOException {
        FQuat   rot   = readFQuat();
        FVector trans = readFVector();
        FVector scale = readFVector();
        return new FTransform(rot, trans, scale);
    }

    /**
     * Reads an FBox: FVector min + FVector max + uint8 isValid.
     */
    public FBox readFBox() throws IOException {
        FVector min     = readFVector();
        FVector max     = readFVector();
        boolean isValid = readBool8();
        return new FBox(min, max, isValid);
    }

    // ------------------------------------------------------------------ //
    //  Bulk helpers
    // ------------------------------------------------------------------ //

    /** Read exactly {@code count} bytes and return them as a byte array. */
    public byte[] readBytes(int count) throws IOException {
        byte[] buf = new byte[count];
        raf.readFully(buf);
        return buf;
    }

    /** Current file length. */
    public long length() throws IOException {
        return raf.length();
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
