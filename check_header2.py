import struct

with open('run/umapica/mafia_town.umap', 'rb') as f:
    raw = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

def read_fstring(data, off):
    slen = ri32(data, off); off += 4
    if slen < 0:
        wlen = -slen
        s = data[off:off+wlen*2].decode('utf-16-le','replace').rstrip('\x00')
        off += wlen*2
    elif slen == 0:
        s = ''
    else:
        s = data[off:off+slen].decode('ascii','replace').rstrip('\x00')
        off += slen
    return s, off

# Parse header
off = 4  # skip magic
lver = ri32(raw, off); off += 4
licver = ri32(raw, off); off += 4
folder, off = read_fstring(raw, off)  # off=21
pkg_flags = ru32(raw, off); off += 4
name_count = ri32(raw, off); off += 4
name_off = ri32(raw, off); off += 4
exp_count = ri32(raw, off); off += 4
exp_off = ri32(raw, off); off += 4
imp_count = ri32(raw, off); off += 4
imp_off = ri32(raw, off); off += 4
dep_off = ri32(raw, off); off += 4
soft_off = ri32(raw, off); off += 4

# Now off=57. Unclear what's here. Let me parse byte-by-byte
print(f'At off={off}: {raw[off:off+80].hex()}')
print()

# Skip 12 zero bytes (3 uint32s = 0)
print(f'[{off}] = {ri32(raw,off)} (=0?)')
print(f'[{off+4}] = {ri32(raw,off+4)} (=0?)')
print(f'[{off+8}] = {ri32(raw,off+8)} (=0?)')
off += 12

# GUID: 16 bytes
guid = raw[off:off+16].hex()
print(f'GUID [{off}]: {guid}')
off += 16

gen_count = ri32(raw, off); off += 4
print(f'GenerationCount [{off-4}]: {gen_count}')

for i in range(gen_count):
    gen_exp = ri32(raw, off); off += 4
    gen_name = ri32(raw, off); off += 4
    print(f'  Gen[{i}]: ExportCount={gen_exp}, NameCount={gen_name}')

eng_ver = ri32(raw, off); off += 4
print(f'EngineVersion [{off-4}]: {eng_ver}')

cook_ver = ri32(raw, off); off += 4
print(f'CookerVersion [{off-4}]: {cook_ver}')

pkg_src = ru32(raw, off); off += 4
print(f'PackageSource [{off-4}]: 0x{pkg_src:08x}')

comp_flags = ri32(raw, off); off += 4
print(f'CompressionFlags [{off-4}]: {comp_flags} (0x{comp_flags:08x})')

comp_count = ri32(raw, off); off += 4
print(f'CompressedChunkCount [{off-4}]: {comp_count}')

print()
print('Compressed chunks:')
for i in range(comp_count):
    uo = ri32(raw, off); off += 4
    us = ri32(raw, off); off += 4
    co = ri32(raw, off); off += 4
    cs = ri32(raw, off); off += 4
    print(f'  Chunk[{i}]: uncompOff={uo}, uncompSz={us}, compOff={co}, compSz={cs}')

print()
print(f'After all chunks, off = {off}')
print(f'Next bytes: {raw[off:off+16].hex()}')
print()
print(f'Summary:')
print(f'  NameCount = {name_count}')
print(f'  NameOffset = {name_off} (virtual)')
print(f'  ExportCount = {exp_count}')
print(f'  ExportOffset = {exp_off} (virtual)')
print(f'  ImportCount = {imp_count}')
print(f'  ImportOffset = {imp_off} (virtual)')
print(f'  CompressionFlags = {comp_flags}')
print(f'  ChunkCount = {comp_count}')
