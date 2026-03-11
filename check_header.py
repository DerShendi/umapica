import struct

with open('run/umapica/mafia_town.umap', 'rb') as f:
    raw = f.read(1200)

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

def read_fstring(data, off):
    slen = ri32(data, off)
    off += 4
    if slen < 0:
        # Wide string
        wlen = -slen
        s = data[off:off+wlen*2].decode('utf-16-le', 'replace').rstrip('\x00')
        off += wlen * 2
    elif slen == 0:
        s = ''
    else:
        s = data[off:off+slen].decode('ascii', 'replace').rstrip('\x00')
        off += slen
    return s, off

print('=== Raw file header parsing ===')
off = 0
magic = ru32(raw, off); off += 4
print(f'Magic:         0x{magic:08x} ({magic})  (expected 0x9E2A83C1={0x9E2A83C1})')
lver = ri32(raw, off); off += 4
print(f'LegacyVersion: {lver}')
licver = ri32(raw, off); off += 4
print(f'LicenseeVersion: {licver}')

folder, off = read_fstring(raw, off)
print(f'FolderName:    "{folder}" (off now {off})')

pkg_flags = ru32(raw, off); off += 4
print(f'PackageFlags:  0x{pkg_flags:08x}')

name_count = ri32(raw, off); off += 4
print(f'NameCount:     {name_count}')

name_off = ri32(raw, off); off += 4
print(f'NameOffset:    {name_off}')

exp_count = ri32(raw, off); off += 4
print(f'ExportCount:   {exp_count}')

exp_off = ri32(raw, off); off += 4
print(f'ExportOffset:  {exp_off}')

imp_count = ri32(raw, off); off += 4
print(f'ImportCount:   {imp_count}')

imp_off = ri32(raw, off); off += 4
print(f'ImportOffset:  {imp_off}')

print(f'After ImportOffset, off = {off}')  # should be 49

# Continue reading
dep_off = ri32(raw, off); off += 4
print(f'DependsOffset: {dep_off}')

soft_pkg = ri32(raw, off); off += 4
print(f'SoftPkgRefsOffset: {soft_pkg}')

# The next bytes - what are they?
print(f'Next 32 bytes at off={off}: {raw[off:off+32].hex()}')
print()

# Read GUID (16 bytes) -- but first need to skip any zero padding
# Let's see the raw bytes from offset 57 to 90
print(f'Raw bytes {off}..{off+40}:')
chunk = raw[off:off+40]
for i in range(0, len(chunk), 8):
    offs = off + i
    hex_part = ' '.join(f'{b:02x}' for b in chunk[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk[i:i+8])
    print(f'  [{offs}]: {hex_part:<24}  {asc_part}')

# Skip zeros if any
while off < len(raw) and raw[off] == 0:
    off += 1
    if off % 4 == 0: break

print(f'After skipping leading zeros, off = {off}')
print(f'Remaining header bytes {off}..{min(off+80, len(raw))}:')
chunk2 = raw[off:off+80]
for i in range(0, len(chunk2), 8):
    offs = off + i
    hex_part = ' '.join(f'{b:02x}' for b in chunk2[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk2[i:i+8])
    print(f'  [{offs}]: {hex_part:<24}  {asc_part}')
