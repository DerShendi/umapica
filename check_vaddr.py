import struct, lzokay

with open('run/umapica/mafia_town.umap', 'rb') as f:
    raw = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

# Re-decompress chunk 0 properly
comp_off = 1152
unc_off_virt = 816
unc_sz = 976035

off = comp_off
magic = ru32(raw, off); off += 4
block_sz = ru32(raw, off); off += 4
comp_total = ri32(raw, off); off += 4
unc_total = ri32(raw, off); off += 4

num_sub = (unc_total + block_sz - 1) // block_sz
sub_headers = []
for i in range(num_sub):
    cs = ri32(raw, off); off += 4
    us = ri32(raw, off); off += 4
    sub_headers.append((cs, us))

decomp_parts = []
for cs, us in sub_headers:
    comp_data = raw[off:off+cs]
    off += cs
    decomp_parts.append(lzokay.decompress(comp_data, us))

d = b''.join(decomp_parts)
print(f'Chunk0 decompressed: {len(d)} bytes, virtual [{unc_off_virt} .. {unc_off_virt+len(d)-1}]')

# The nameOffset = 816 (virtual).
# But chunk0 starts at virtual 816. So local 0 = virtual 816 = nameOffset?!
# But local 0 data = 02 00 00 00 -- that's int32=2, potential name "0\x00" -- NOT a valid first name

# WAIT -- maybe nameOffset = 816 is the BYTE OFFSET IN THE *COMPRESSED* STREAM
# AND the chunk's uncompOff says where that virtual address maps to in the decompressed stream
# uncompOff = 816 means: the decompressed data for this chunk starts at decompressed address 816
# The chunks[0].uncompOff = 816 could mean THE CHUNK IS AT VIRTUAL ADDRESS 816
# which would mean the first 816 virtual bytes are NOT compressed (they're in the raw file header!)

# THEN: nameOffset=816 -> this is the start of the first chunk's decompressed content
# The bytes 0..815 in the "virtual decompressed stream" = raw file bytes 0..815 (uncompressed)

# Let's verify: raw file bytes 0..815 should look like the file header
print()
print('Raw file bytes 810..825:')
for i in range(810, 826):
    print(f'  [{i}] = 0x{raw[i]:02x} = {raw[i]}')

print()
print(f'int32 at raw[810..814] = {ri32(raw, 810)}')
print(f'int32 at raw[814..818] = {ri32(raw, 814)}')
print()

# So virtual stream = raw_file[0..815] + decompressed_chunk0[0..976034] + decompressed_chunk1[...] ...
# But chunk0.uncompOff=816 and chunk1.uncompOff=976851 = 816+976035 ✓

# So to read names: nameOffset=816 virtual -> local 0 in chunk0 ✓
# BUT the local 0 in chunk0_decomp.bin = 0x02 0x00 0x00 0x00 (int32=2) 
# name "0\x00"? That's weird but let me check if that's actually the start of name data 
# or if there's some structure before the actual name strings

# Actually, in the decompressed chunk0 at local 0:
# 02 00 00 00 30 00 00 00 00 00 10 00 07 00 02 00 00 00 31 00 ...
# If this is the NAME TABLE, entry 0 would be: len=2, name="0\0", extra=?
# OR: these are NOT the name entries! Maybe name entries start differently

# Let's check: what if the name table has a DIFFERENT format at the very beginning?
# Maybe the name table starts not at virtual 816 but at virtual 816 + X

print('=== Searching for "bAcceptsLights" signature in decompressed chunk0 ===')
search = b'bAcceptsLights\x00'
idx = d.find(search)
if idx >= 0:
    print(f'Found at local offset {idx}, back 4 bytes for len:')
    off_start = idx - 4
    print(f'  Bytes [{off_start}..{off_start+30}]: {d[off_start:off_start+30].hex()}')
    slen = ri32(d, off_start)
    print(f'  len = {slen}')
    print(f'  virtual addr = {unc_off_virt + off_start}')
    print()
    print('  This means nameOffset should be', unc_off_virt + off_start, '(virtual)')
    print('  But header says nameOffset =', 816)
    print(f'  Difference = {(unc_off_virt + off_start) - 816}')
else:
    print('NOT FOUND in decompressed chunk0')

print()
# Also check: how many name entries CAN we read starting from local 0?
print('=== Try reading names from local offset 0 ===')
off = 0
entry_count = 0
for i in range(10):
    slen = ri32(d, off)
    entry_start = off
    off += 4
    if slen < 0:
        abs_len = -slen
        off += abs_len * 2
        nm = d[entry_start+4:entry_start+4+abs_len*2].decode('utf-16-le','replace').rstrip('\x00')
    elif slen == 0 or slen > 300:
        print(f'  Entry {i}: bad slen={slen} at off={entry_start}')
        break
    else:
        nm = d[entry_start+4:entry_start+4+slen].decode('ascii','replace').rstrip('\x00')
        off += slen
    off += 8  # extra
    print(f'  Entry {i}: slen={slen} -> {repr(nm)}')
    entry_count += 1
