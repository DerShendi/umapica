import struct, lzokay

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# Chunk at file offset 1152:
# magic(4) blockSz(4) compSzTotal(4) uncompSzTotal(4) [subblocks...] [data...]
coff = 1152
magic = struct.unpack_from('<I', data, coff)[0]; coff+=4
block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
comp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
uncomp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
p(f'Chunk at 1152: blockSz={block_sz}, compTotal={comp_total}, uncompTotal={uncomp_total}')

num_sub = (uncomp_total + block_sz - 1) // block_sz
p(f'numSubBlocks={num_sub}')

sub_sizes = []
for i in range(num_sub):
    sc = struct.unpack_from('<i', data, coff)[0]; coff+=4
    su = struct.unpack_from('<i', data, coff)[0]; coff+=4
    sub_sizes.append((sc, su))

p(f'Sub-block sizes: {sub_sizes[:5]}...')
p(f'Compressed data starts at file offset: {coff}')

# Decompress all sub-blocks and concatenate
decompressed_parts = []
for i, (sc, su) in enumerate(sub_sizes):
    compressed = data[coff:coff+sc]
    coff += sc
    
    # Try lzokay (which does LZO1X decompression)
    try:
        d = lzokay.decompress(compressed, su)
        decompressed_parts.append(d)
        if i < 3:
            p(f'  Sub[{i}]: {sc} -> {len(d)} bytes (expected {su}) OK')
    except Exception as e:
        p(f'  Sub[{i}]: lzokay failed: {e}')
        # After failure, stop
        break

if decompressed_parts:
    decompressed = b''.join(decompressed_parts)
    p(f'Total decompressed: {len(decompressed)} bytes (expected {uncomp_total})')
    
    # The nameOffset=816 in the header refers to the UNCOMPRESSED stream.
    # But wait -- the FIRST chunk's "uncompressedOffset=21" from the CompressedChunks table.
    # So the decompressed data from this chunk starts at virtual position 21.
    # Actually NO: let me reconsider.
    # 
    # The FPackageSummary.CompressedChunks[0]:
    # UncompressedOffset=21, UncompressedSize=816 (or 976035?)
    # CompressedOffset=1152 (file offset), CompressedSize=407076 (?)
    # 
    # UncompressedSize might be 976035 (the chunk header says so).
    # So in the UNCOMPRESSED stream:
    # - chunk[0] occupies uncompressed offsets 21 to 21+976035-1 = 976055
    # - nameOffset=816 is within this range!
    # - To read names, we access decompressed_data[816 - 21] = decompressed_data[795]
    # 
    # Let's try reading name table from this decompressed data:
    name_off_virtual = 816  # from header
    chunk0_uncomp_start = 21  # from CompressedChunks[0].UncompressedOffset
    local_name_off = name_off_virtual - chunk0_uncomp_start
    p(f'Name table at local offset {local_name_off} in decompressed data')
    p(f'Bytes at local_name_off: {decompressed[local_name_off:local_name_off+32].hex()}')
    p(f'As ASCII: {decompressed[local_name_off:local_name_off+32].decode("ascii","replace")}')
    
    # Try reading as FString list
    p(f'')
    p(f'Trying FString-based name table at virtual offset {name_off_virtual}:')
    off = local_name_off
    names = []
    for i in range(20):
        if off + 4 > len(decompressed): break
        slen = struct.unpack_from('<i', decompressed, off)[0]; off+=4
        if slen <= 0 or slen > 256: 
            p(f'  Entry[{i}]: bad len={slen} at off={off-4}'); break
        name = decompressed[off:off+slen-1].decode('ascii','replace')
        off += slen
        p(f'  Name[{i}]: [{name}]')
        names.append(name)
    
    p(f'')
    p(f'Raw bytes at virtual name_off-8 to name_off+64:')
    start = max(0, local_name_off - 8)
    end = min(len(decompressed), local_name_off + 64)
    p(f'{decompressed[start:end].hex()}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\lzo_test.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Done.')
