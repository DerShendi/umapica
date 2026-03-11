"""Parse UE3 header completely to find chunk table"""
import struct

data = open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap','rb').read()

def read_int32(off):
    return struct.unpack_from('<i', data, off)[0], off+4

def read_uint32(off):
    return struct.unpack_from('<I', data, off)[0], off+4

def read_int16(off):
    return struct.unpack_from('<h', data, off)[0], off+2

def read_fstring(off):
    length, off = read_int32(off)
    if length > 0:
        s = data[off:off+length].decode('latin1').rstrip('\x00')
        return s, off+length
    elif length < 0:
        length = -length
        s = data[off:off+length*2].decode('utf-16-le').rstrip('\x00')
        return s, off+length*2
    return '', off

off = 0
magic, off = read_uint32(off)
file_version, off = read_int16(off)
licensee_version, off = read_int16(off)
total_hdr_sz, off = read_uint32(off)  # totalHeaderSize
group, off = read_fstring(off)  # packageGroup
pkg_flags, off = read_uint32(off)
name_count, off = read_uint32(off)
name_off, off = read_uint32(off)
export_count, off = read_uint32(off)
export_off, off = read_uint32(off)
import_count, off = read_uint32(off)
import_off, off = read_uint32(off)
print(f'After import_off: offset={off}')
# depends_off
depends_off, off = read_uint32(off)
print(f'depends_off={depends_off} offset={off}')
print(f'Bytes {off}..{off+60}: {" ".join(f"{b:02X}" for b in data[off:off+60])}')

# In UE3, the standard header continues with:
# thumbnailTableOffset (4)? Or package GUID (16)?
# Let's check if next is a 0-padded area or GUID
# totalHeaderSize=153604, that's the total size of header including all tables
# The chunk table is near end of header

# Let's parse: after dependsOff:
# There could be: softPackageReferenceTableOffset (added in UE3.5+)
# Or directly: GUID (16 bytes)
# The 4 zero bytes at off look like another offset or GUID start

# Let me dump from off to off+80:
print(f'Next 80 bytes at {off}:')
for i in range(0, 80, 16):
    hex_part = ' '.join(f'{b:02X}' for b in data[off+i:off+i+16])
    print(f'  {off+i:4d}: {hex_part}')

# In mafia_town.umap previous analysis showed:
# At offset 49 we had: 38 3F 02 00 | 04 58 02 00 | 00 00 00 00 | ...
# 0x00023F38 = 147256 = ? (could be dependsOff)
# 0x00025804 = 153604 = totalHeaderSize (repeated? or thumbnailTableOffset?)

# After depends_off and some offsets, there should be:
# 12 zero bytes (padding or unknown)
# GUID (16 bytes)
# generationCount (int32)
# generations (array of FGenerationInfo)
# engineVersion (int32)
# cookerVersion (int32)  
# packageSource (int32)
# compressionFlags (int32)
# compressedChunks (TArray<FCompressedChunk>)

# The key is to find compressionFlags and compressedChunks
# Let's search for compressionFlags != 0 followed by chunk_count=21
for scan_off in range(40, min(200, len(data)-40)):
    comp_flags = struct.unpack_from('<I', data, scan_off)[0]
    chunk_count = struct.unpack_from('<I', data, scan_off+4)[0]
    if chunk_count == 21 and comp_flags in [2, 4, 0x100, 0x200]:
        print(f'Found! compressionFlags=0x{comp_flags:08X} chunkCount={chunk_count} at offset {scan_off}')
        # Read chunk table
        chunk_off = scan_off + 8
        for i in range(21):
            uo = struct.unpack_from('<Q', data, chunk_off)[0]; chunk_off += 8
            us = struct.unpack_from('<Q', data, chunk_off)[0]; chunk_off += 8
            co = struct.unpack_from('<Q', data, chunk_off)[0]; chunk_off += 8
            cs = struct.unpack_from('<Q', data, chunk_off)[0]; chunk_off += 8
            print(f'  chunk[{i}]: uncompOff={uo} us={us} compOff={co} cs={cs}')
        break

# Also try with int32 chunk entries
for scan_off in range(40, min(200, len(data)-40)):
    comp_flags = struct.unpack_from('<I', data, scan_off)[0]
    chunk_count = struct.unpack_from('<I', data, scan_off+4)[0]
    if chunk_count == 21 and comp_flags != 0:
        print(f'Candidate: compressionFlags=0x{comp_flags:08X} chunkCount={chunk_count} at offset {scan_off}')
        break
