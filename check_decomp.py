import struct, lzokay

with open('run/umapica/mafia_town.umap', 'rb') as f:
    raw = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

# Chunk[0]: compOff=1152, uncompOff=816, uncompSz=976035
comp_off = 1152
unc_off_virtual = 816
unc_sz = 976035

# Read chunk header format from file
off = comp_off
magic = ru32(raw, off); off += 4
print(f'Chunk magic: 0x{magic:08x}')
block_sz = ru32(raw, off); off += 4
print(f'Block size: {block_sz}')
comp_total = ri32(raw, off); off += 4
print(f'Comp total: {comp_total}')
unc_total = ri32(raw, off); off += 4
print(f'Uncomp total: {unc_total}')

num_sub = (unc_total + block_sz - 1) // block_sz
print(f'Num sub-blocks: {num_sub}')

sub_headers = []
for i in range(num_sub):
    cs = ri32(raw, off); off += 4
    us = ri32(raw, off); off += 4
    sub_headers.append((cs, us))
    print(f'  Sub[{i}]: compSz={cs}, uncompSz={us}')

# Decompress all sub-blocks
decomp_parts = []
for i, (cs, us) in enumerate(sub_headers):
    comp_data = raw[off:off+cs]
    off += cs
    decomp = lzokay.decompress(comp_data, us)
    decomp_parts.append(decomp)

decompressed = b''.join(decomp_parts)
print(f'Total decompressed: {len(decompressed)} bytes (expected {unc_total})')

# Now the decompressed data represents virtual addresses [unc_off_virtual .. unc_off_virtual + unc_total - 1]
# i.e., local offset 0 in decompressed = virtual address 816

print()
print(f'=== Decompressed chunk represents virtual [{unc_off_virtual} .. {unc_off_virtual + unc_total - 1}] ===')
print()

# nameOffset = 816 (virtual) -> local offset 0
# BUT WE previously decompressed and names were at local 796!
# Let me check what's at local 0..30:
print(f'Bytes at local 0..32 (virtual 816..848):')
chunk = decompressed[0:32]
for i in range(0, 32, 8):
    hex_part = ' '.join(f'{b:02x}' for b in chunk[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk[i:i+8])
    print(f'  local {i:3d} (virt {unc_off_virtual+i:5d}): {hex_part:<24}  {asc_part}')

print()
print(f'Does local 0 look like name entries?')
slen0 = ri32(decompressed, 0)
print(f'  int32 at local 0: {slen0}')
if 0 < slen0 < 200:
    nm = decompressed[4:4+slen0].decode('ascii','replace').rstrip('\x00')
    print(f'  potential name (len={slen0}): {repr(nm)}')

print()
# Compare with what we saved before  
print('=== Comparing with saved chunk0_decomp.bin ===')
with open('chunk0_decomp.bin', 'rb') as f:
    old_decomp = f.read()

print(f'Old decomp size: {len(old_decomp)}')
print(f'New decomp size: {len(decompressed)}')
print(f'Old[0:32]: {old_decomp[0:32].hex()}')
print(f'New[0:32]: {decompressed[0:32].hex()}')
if old_decomp[:32] == decompressed[:32]:
    print('First 32 bytes MATCH')
else:
    print('DIFFER -- old_decomp includes some prefix bytes')
    # Find where they diverge
    for i in range(min(len(old_decomp), len(decompressed))):
        if old_decomp[i] != decompressed[i]:
            print(f'First diff at byte {i}')
            print(f'old[{i-4}:{i+12}]: {old_decomp[i-4:i+12].hex()}')
            print(f'new[{i-4}:{i+12}]: {decompressed[i-4:i+12].hex()}')
            break
