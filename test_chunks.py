import struct, zlib

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# The compressed chunk at offset 1152:
# [1152] magic = 0x9E2A83C1
# [1156] BlockSize = 131072 = 0x20000
# [1160] CompressedSize (8 bytes)
# [1168] UncompressedSize (8 bytes)
# [1176] SubBlocks list
p('=== Compressed chunk at offset 1152 ===')
coff = 1152
magic = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'magic = 0x{magic:08X} ({"OK" if magic==0x9E2A83C1 else "WRONG"})')
block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'BlockSize = {block_sz} ({block_sz//1024}KB)')
comp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
p(f'CompressedSize = {comp_sz_total} (0x{comp_sz_total:X})')
uncomp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
p(f'UncompressedSize = {uncomp_sz_total} (0x{uncomp_sz_total:X})')

if uncomp_sz_total > 0 and uncomp_sz_total < 500*1024*1024 and block_sz > 0:
    num_sub = (uncomp_sz_total + block_sz - 1) // block_sz
    p(f'NumSubBlocks = {num_sub}')
    sub_sizes = []
    for i in range(min(num_sub, 10)):
        sc = struct.unpack_from('<I', data, coff)[0]; coff+=4
        su = struct.unpack_from('<I', data, coff)[0]; coff+=4
        p(f'  SubBlock[{i}]: compSz={sc} uncompSz={su}')
        sub_sizes.append((sc, su))
    
    p(f'Compressed data starts at: {coff}')
    p(f'Data at coff: {data[coff:coff+16].hex()}')
    
    # Try decompressing subblock 0
    if sub_sizes:
        sc0, su0 = sub_sizes[0]
        compressed_block = data[coff:coff+sc0]
        p(f'Trying to decompress subBlock[0]: {sc0} -> {su0} bytes')
        p(f'  First 4 bytes: {compressed_block[:4].hex()}')
        
        # Try zlib
        try:
            d = zlib.decompress(compressed_block)
            p(f'  zlib: SUCCESS {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  zlib: {e}')
        
        # Try raw deflate
        try:
            d = zlib.decompress(compressed_block, -15)
            p(f'  raw deflate: SUCCESS {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  raw deflate: {e}')
        
        # Try LZO - not natively available but check the header bytes
        # LZO starts with specific framing or is just raw
        # Check first bytes of compressed data
        p(f'  First 8 bytes of compressed data (hex): {compressed_block[:8].hex()}')
        
        # UE3/UE4 also uses LZ4
        # Python lz4 module: try it
        try:
            import lz4.frame
            d = lz4.frame.decompress(compressed_block)
            p(f'  lz4.frame: SUCCESS {len(d)} bytes')
        except Exception as e:
            p(f'  lz4.frame: {e}')
        
        try:
            import lz4.block
            d = lz4.block.decompress(compressed_block, uncompressed_size=su0)
            p(f'  lz4.block: SUCCESS {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  lz4.block: {e}')

# But first, let me figure out WHERE the first compressed chunk is.
# The header at [0] sets NameOffset=816, ExportOffset=39256 etc.
# These are all UNCOMPRESSED offsets.
# In UE3 chunk compression, the compressed chunks begin AFTER the header.
# The "first chunk" compresses from after-header to end of exports.
# The header itself is NOT compressed.
# 
# So: header ends at offset 49 (our current parse).
# But nameOffset=816 means the name table is at uncompressed offset 816.
# The "uncompressed" data from offset 49 to 816 must contain header continuation data.
# BUT the data at 49-815 in the FILE contains:
#   - more header fields (DependsOffset, GUID, GenerationInfo, EngineVersion, etc.)
#   - Perhaps some uncompressed data
#   - Then at some offset, the compressed chunks start
# 
# In UE3 packages, the CompressedChunks table tells you where to find compressed data.
# The header summary stays uncompressed at the start.
# The compressed data chunks cover the data section (name table, imports, exports, etc.)
# 
# So NameOffset=816 refers to the ENCODED (before decompression) offset,
# but the actual compressed chunks are stored at other locations.
# 
# After decompression, the "virtual" data stream would be:
#   header data + decompressed chunks concatenated
# 
# Let me find ALL compressed chunk headers by scanning for the magic:
p('')
p('=== Scanning for all compressed chunk headers (UE3 magic) ===')
magic_bytes = bytes([0xC1, 0x83, 0x2A, 0x9E])
pos = 4  # skip file header magic
chunk_headers = []
while True:
    pp = data.find(magic_bytes, pos)
    if pp < 0: break
    bs = struct.unpack_from('<I', data, pp+4)[0]
    cs = struct.unpack_from('<Q', data, pp+8)[0]
    us = struct.unpack_from('<Q', data, pp+16)[0]
    # Check if these look valid (blockSize should be power of 2, 16KB-512KB range)
    valid = (bs in [16384, 32768, 65536, 131072, 262144, 524288]) and (0 < cs < 100*1024*1024) and (0 < us < 500*1024*1024)
    p(f'  [offset={pp}] magic, blockSz={bs}, compSz={cs}, uncompSz={us} -> {"VALID" if valid else "dubious"}')
    if valid:
        chunk_headers.append(pp)
    pos = pp + 1
    if len(chunk_headers) > 50:
        break

p(f'Total valid chunk headers found: {len(chunk_headers)}')

# Now let's try to decompress the first valid chunk
if chunk_headers:
    p('')
    coff = chunk_headers[0]
    p(f'=== Decompressing first valid chunk at offset {coff} ===')
    magic = struct.unpack_from('<I', data, coff)[0]; coff+=4
    block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
    comp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
    uncomp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
    p(f'blockSz={block_sz}, compSz={comp_sz_total}, uncompSz={uncomp_sz_total}')
    num_sub = (uncomp_sz_total + block_sz - 1) // block_sz
    p(f'numSubBlocks={num_sub}')
    sub_sizes = []
    for i in range(num_sub):
        sc = struct.unpack_from('<I', data, coff)[0]; coff+=4
        su = struct.unpack_from('<I', data, coff)[0]; coff+=4
        sub_sizes.append((sc, su))
    p(f'Starting compressed data at offset {coff}')
    
    if sub_sizes:
        sc0, su0 = sub_sizes[0]
        cb = data[coff:coff+sc0]
        p(f'SubBlock[0]: compressed={sc0} bytes, uncompressed={su0} bytes')
        p(f'  First 8 bytes: {cb[:8].hex()}')
        
        for method in ['zlib', 'raw_deflate', 'lz4_block']:
            try:
                if method == 'zlib':
                    d = zlib.decompress(cb)
                elif method == 'raw_deflate':
                    d = zlib.decompress(cb, -15)
                elif method == 'lz4_block':
                    import lz4.block
                    d = lz4.block.decompress(cb, uncompressed_size=su0)
                p(f'  {method}: SUCCESS {len(d)} bytes -> first 32: {d[:32].hex()}')
            except Exception as e:
                p(f'  {method}: {e}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\chunk_test.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Written.')
