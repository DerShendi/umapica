"""Parse UE3 header format correctly for mafia_town.umap"""
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
        # UTF-16
        length = -length
        s = data[off:off+length*2].decode('utf-16-le').rstrip('\x00')
        return s, off+length*2
    return '', off

off = 0
magic, off = read_uint32(off)
print(f'magic=0x{magic:08X}')

file_version, off = read_int16(off)
licensee_version, off = read_int16(off)
print(f'fileVersion={file_version} licenseeVersion={licensee_version}')

# In some UE3 versions there's a totalHeaderSize and/or packageGroup here
# Let's check offset 8 more carefully
print(f'Offset 8 raw: {" ".join(f"{b:02X}" for b in data[8:20])}')

# Try treating next as: total_header_size(4) or package_flags(4)
total_hdr_sz, off = read_uint32(off)
print(f'next int32 = {total_hdr_sz} = 0x{total_hdr_sz:08X}')

# Try reading a FString here (packageGroup)
maybe_len, maybe_off = read_int32(off)
print(f'maybe-string-len={maybe_len}, bytes: {" ".join(f"{b:02X}" for b in data[off+4:off+4+max(0,maybe_len)])}')

if 0 < maybe_len < 100:
    s, off2 = read_fstring(off)
    print(f'FString at {off}: "{s}"  next_off={off2}')
    off = off2
    # Now: pkg_flags, name_count, name_off, export_count, export_off, import_count, import_off
    pkg_flags, off = read_uint32(off)
    name_count, off = read_uint32(off)
    name_off, off   = read_uint32(off)
    export_count, off = read_uint32(off)
    export_off, off   = read_uint32(off)
    import_count, off = read_uint32(off)
    import_off, off   = read_uint32(off)
    print(f'pkgFlags=0x{pkg_flags:08X}')
    print(f'nameCount={name_count} nameOff={name_off}')
    print(f'exportCount={export_count} exportOff={export_off}')
    print(f'importCount={import_count} importOff={import_off}')
    print(f'offset now: {off}')
    print(f'Next 40 bytes: {" ".join(f"{b:02X}" for b in data[off:off+40])}')
else:
    # No packageGroup
    off = 8
    pkg_flags, off = read_uint32(off)
    name_count, off = read_uint32(off)
    name_off, off   = read_uint32(off)
    print(f'pkgFlags=0x{pkg_flags:08X}')
    print(f'nameCount={name_count} nameOff={name_off}')
