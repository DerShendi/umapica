import struct

with open('chunk0_decomp.bin', 'rb') as f:
    d = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

# Hex dump bytes around 30210
print('Bytes 30210..30265:')
chunk = d[30210:30265]
for i in range(0, len(chunk), 16):
    offs = 30210 + i
    hex_part = ' '.join(f'{b:02x}' for b in chunk[i:i+16])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk[i:i+16])
    print(f'  {offs:6d}: {hex_part:<48}  {asc_part}')

print()

# Entry 1003 analysis
off = 30213
slen = ri32(d, off)
print(f'Entry 1003 at off={off}: len={slen} (0x{slen&0xFFFFFFFF:08x})')
print(f'  Name bytes: {d[off+4:off+4+slen].hex()} = {repr(d[off+4:off+4+slen])}')
# The name is 11 bytes, ends with null
name_end = off + 4 + slen
print(f'  Name ends at: {name_end}')
print(f'  Extra 8 bytes after name: {d[name_end:name_end+8].hex()}')
entry_end = name_end + 8  # = 30236
print(f'  Entry total end: {entry_end}')
print()
print(f'What follows at {entry_end}:')
# Print next 40 bytes
print(f'  hex: {d[entry_end:entry_end+40].hex()}')
print(f'  as int32s:')
for i in range(0, 40, 4):
    v = ri32(d, entry_end+i)
    uv = ru32(d, entry_end+i)
    print(f'    [{entry_end+i}] = {v} (0x{uv:08x})')

print()
# Check if there's maybe a different name count or the name table ends earlier
# NameCount in header = 1037, but maybe actual entries = 1004?
print('Trying nameCount = 1004:')
# Fast skip all 1004 entries
off = 796
for i in range(1004):
    slen = ri32(d, off)
    off += 4
    if slen < 0:
        off += (-slen) * 2
    else:
        off += slen
    off += 8
print(f'After 1004 entries, off = {off}')
print(f'  vs importOff(virtual) = ~31052, chunk0.uncompOff=21')
print(f'  importOff local = 31052 - 21 = 31031')
print()
print(f'Difference from importOff local 31031: off={off}, diff={off-31031}')

# What about NameCount actually being read wrong from header?
# Let me check the header region again
print()
print('Header at bytes 25-33:')
# In decompressed data, offset 0 corresponds to virtual offset 21
# So header starts at virtual 0 but we see it at local offset... wait
# chunk0.uncompOff = 21 means the CHUNK DATA starts representing virtual offset 21
# So to get local offset from virtual: local = virtual - 21
# Virtual 0 is NOT in this chunk -- this chunk starts at virtual=21

# Actually wait -- let me re-examine. The file header (before compression)
# is at the start of the file. Maybe the header IS in the chunk.
# chunk0: uncompOff=21, so this chunk contributes virtual bytes 21..21+976035-1

# nameOffset = 816 (virtual)
# local = 816 - 21 = 795
# But we've been starting at local 796 (one extra byte)

# What's at local 25-33 (virtual 46-54)?
print('Bytes at local 0..50 (virtual 21..71):')
chunk2 = d[0:50]
for i in range(0, 50, 16):
    hex_part = ' '.join(f'{b:02x}' for b in chunk2[i:i+16])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk2[i:i+16])
    print(f'  {i:4d} (virt {21+i:4d}): {hex_part:<48}  {asc_part}')
