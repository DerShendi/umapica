import struct, zlib

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# The compressed chunk format for UE3:
# According to UE3 source (PackageFileCache.cpp):
# struct FCompressedChunkInfo {
#   int32 CompressedSize;
#   int32 UncompressedSize;
# };
# struct FCompressedChunkHeader {
#   int32 Tag;              // PACKAGE_FILE_TAG
#   int32 BlockSize;        // usually 0x20000 = 128KB
#   FCompressedChunkInfo Summary;  // whole-chunk totals
#   // followed by: FCompressedChunkInfo Blocks[ceil(Summary.UncompressedSize/BlockSize)]
# };
# So ALL sizes are int32, not int64!

p('=== Re-reading chunk at offset 1152 with int32 sizes ===')
coff = 1152
magic = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'magic = 0x{magic:08X} ({"OK" if magic==0x9E2A83C1 else "WRONG"})')
block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'BlockSize = {block_sz} ({block_sz//1024}KB)')
comp_sz_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
p(f'CompressedSize (total) = {comp_sz_total}')
uncomp_sz_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
p(f'UncompressedSize (total) = {uncomp_sz_total}')

if 0 < comp_sz_total < 50*1024*1024 and 0 < uncomp_sz_total < 500*1024*1024 and block_sz > 0:
    num_sub = (uncomp_sz_total + block_sz - 1) // block_sz
    p(f'NumSubBlocks = {num_sub}')
    sub_sizes = []
    for i in range(min(num_sub, 10)):
        sc = struct.unpack_from('<i', data, coff)[0]; coff+=4
        su = struct.unpack_from('<i', data, coff)[0]; coff+=4
        p(f'  SubBlock[{i}]: compSz={sc} uncompSz={su}')
        sub_sizes.append((sc, su))
    
    if num_sub > 10:
        # skip remaining sub-block headers we didn't read
        coff += (num_sub - 10) * 8
    
    p(f'Compressed data starts at: {coff}')
    p(f'Data at coff: {data[coff:coff+8].hex()}')
    
    # Try decompressing first sub-block
    if sub_sizes:
        sc0, su0 = sub_sizes[0]
        cb = data[coff:coff+sc0]
        p(f' SubBlock[0]: first 8 bytes = {cb[:8].hex()}')
        for method in ['zlib', 'raw_deflate']:
            try:
                if method == 'zlib':
                    d = zlib.decompress(cb)
                elif method == 'raw_deflate':
                    d = zlib.decompress(cb, -15)
                p(f'  {method}: SUCCESS {len(d)} bytes -> first 32: {d[:32].hex()}')
                p(f'  as ascii: {d[:32].decode("ascii","replace")}')
            except Exception as e:
                p(f'  {method}: {e}')

p('')
p('=== Scanning for chunk headers (int32 sizes) ===')
magic_bytes = bytes([0xC1, 0x83, 0x2A, 0x9E])
pos = 4
valid_chunks = []
while True:
    pp = data.find(magic_bytes, pos)
    if pp < 0: break
    bs = struct.unpack_from('<I', data, pp+4)[0]
    cs = struct.unpack_from('<i', data, pp+8)[0]
    us = struct.unpack_from('<i', data, pp+12)[0]
    valid = (bs in [16384, 32768, 65536, 131072, 262144, 524288]) and (0 < cs < 50*1024*1024) and (0 < us < 500*1024*1024)
    if valid:
        p(f'  VALID chunk at {pp}: blockSz={bs}, compSz={cs}, uncompSz={us}')
        valid_chunks.append((pp, bs, cs, us))
    pos = pp + 1

p(f'Total valid chunks: {len(valid_chunks)}')

# Now try to decompress the first valid chunk
if valid_chunks:
    pp, bs, cs, us = valid_chunks[0]
    p(f'')
    p(f'=== Decompressing chunk at {pp} ===')
    coff = pp + 4 + 4 + 4 + 4  # skip magic, blockSz, compSz, uncompSz
    num_sub = (us + bs - 1) // bs
    p(f'numSub={num_sub}')
    sub_sizes = []
    for i in range(num_sub):
        sc = struct.unpack_from('<i', data, coff)[0]; coff+=4
        su = struct.unpack_from('<i', data, coff)[0]; coff+=4
        sub_sizes.append((sc, su))
        if i < 3:
            p(f'  sub[{i}]: comp={sc} uncomp={su}')
    p(f'Data at {coff}: {data[coff:coff+8].hex()}')
    if sub_sizes:
        sc0, su0 = sub_sizes[0]
        cb = data[coff:coff+sc0]
        p(f'SubBlock[0]: {len(cb)} compressed bytes -> expecting {su0}')
        try:
            d = zlib.decompress(cb)
            p(f'  zlib SUCCESS: {len(d)} bytes, first 32: {d[:32].hex()}')
            p(f'  as ASCII: [{d[:64].decode("ascii","replace")}]')
        except Exception as e:
            p(f'  zlib: {e}')
        try:
            d = zlib.decompress(cb, -15)
            p(f'  raw_deflate SUCCESS: {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  raw_deflate: {e}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\chunk_test2.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Written.')
