import struct, zlib, sys

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# The first sub-block bytes: 03 02 00 00 00 30 00 60
# In LZO1X (most common LZO variant in UE3):
# LZO1X-1 compressed data is raw, no header.
# The first byte "03 02" in LZO1X is a valid starting sequence.
# 
# Let's check available decompression modules:
p('Checking available decompression modules...')
for mod_name in ['lzo', 'pylzo', 'lz4', 'lz4.block', 'lz4.frame', 'zstandard', 'brotli']:
    try:
        __import__(mod_name.split('.')[0])
        if '.' in mod_name:
            import importlib; importlib.import_module(mod_name)
        p(f'  {mod_name}: AVAILABLE')
    except ImportError:
        p(f'  {mod_name}: not available')

# The first 8 bytes are: 03 02 00 00 00 30 00 60
# In LZO1X compressed format, this makes sense (03 = copy/match indicators)
# 
# Since we may not have lzo, let's try another approach:
# Use lz4 if available (UE4 uses LZ4 heavily), or check if this could be
# a different format.
# 
# Actually, the CompressionFlags value might tell us:
# From our header parse: 
# [109] CompressionFlags=2
# In UE3 engine source (Compression.h):
# COMPRESS_NONE  = 0
# COMPRESS_ZLIB  = 1 << 0 = 1
# COMPRESS_LZO   = 1 << 1 = 2   <-- flags=2 means LZO!
# COMPRESS_LZX   = 1 << 2 = 4
# COMPRESS_LZ4   = 1 << 3 = 8   (added later)
# 
p('')
p('CompressionFlags=2 = COMPRESS_LZO (bit 1)')
p('This file uses LZO compression!')

# Try installing lzo:
p('')
p('Trying pip install python-lzo...')

# Since we confirmed LZO, let's check if we can at least verify the nameOffset manually
# by computing what the decompressed offsets would be.
# 
# The CompressedChunks from the header (earlier parse, offsets 113-144):
# Chunk[0]: uncompOff=21, uncompSz=816, compOff=976035, compSz=1152
# Chunk[1]: uncompOff=407156, uncompSz=976851, compOff=3856917, compSz=408308
# 
# WAIT. I misread those earlier! Let me re-check from the actual UE3 FCompressedChunk layout:
# FCompressedChunk = int32 UncompressedOffset + int32 UncompressedSize + int32 CompressedOffset + int32 CompressedSize
# But these are CHUNK METADATA stored IN THE HEADER (not in the chunk data itself).
# So the header at [113] = 21, [117] = 816, [121] = 976035, [125] = 1152 means:
#   Chunk[0]: uncompOff=21, uncompSz=816, compOff=976035 (wrong!), compSz=1152 (wrong!)
# 
# OR -- actually, looking at the 21 valid chunks we found:
# The FIRST chunk at offset 1152 has compSz=407076, uncompSz=976035
# Note: 976035 is the same value that appeared in [121] = 976035!
# And 407076 ≈ chunk header at 1152.
# 
# In the FPackageSummary.CompressedChunks table, each entry stores:
# - UncompressedOffset: where in the virtual uncompressed data this chunk starts
# - UncompressedSize: how large the uncompressed chunk is
# - CompressedOffset: where in the FILE the chunk starts (its header+data)
# - CompressedSize: file size of the compressed chunk (including its OWN header!)
# 
# Chunk[0] from header: uncompOff=21, uncompSz=816, compOff=976035, compSz=1152
# This doesn't match what we see:
#   - The chunk at offset 1152 has uncompSz=976035 which != 816
#   - The chunk at offset 976035 doesn't have magic at that offset
# 
# Wait -- let me re-read the header fields. Our trace at [113]:
# [113] = 21
# But maybe [109] = 2 as the CompressedChunks COUNT affects what comes next.
# If CompressedChunks.count = 2, then the 2 entries follow at [113]:
# Entry[0]: [113-128] = 4 4 4 4 = UncompOff UncompSz CompOff CompSz
# [113]=21, [117]=816, [121]=976035, [125]=1152
# Entry[1]: [129-144] = ...
# [129]=407156, [133]=976851, [137]=3856917, [141]=408308
# 
# But comparing with the actual chunk scan:
# Chunk at file offset 1152: compSz=407076, uncompSz=976035
# => This chunk's SIZE in file = 1152 (header) + 407076 (data) ≈ 408228...
#    and [125]=1152 is the compressedOFFSET of the chunk?? 
# 
# NO! In UE3, CompressedOffset = byte offset IN THE FILE where the chunk (including its own header) starts.
# So FPackageSummary.CompressedChunks[0].CompressedOffset = 1152 (offset in file where the first chunk is)!
# And FPackageSummary.CompressedChunks[0].CompressedSize = 976035... but the chunk at 1152 has compSz=407076? 
# 
# Hmm. 976035 vs 407076. These don't match.
# 
# WAIT -- I'm confusing the FPackageSummary.CompressedChunks entries with the compressed chunk HEADER structure!
# In UE3: 
# FPackageSummary.CompressedChunks = tells you WHERE to find the chunks and corresponding uncompressed positions
# But the individual chunk headers (FCompressedChunkHeader) also store the sizes for verification.
# 
# Let me re-examine the header parse values:
# [113] = 21 = UncompressedOffset of chunk[0]
# [117] = 816 = UncompressedSize of chunk[0]  <-- this matches NameOffset! NameOffset = 816!
# [121] = 976035 = CompressedOffset of chunk[0]  <-- NOT file offset 1152?
# [125] = 1152 = CompressedSize of chunk[0]
#
# So according to the header: Chunk[0] is at file offset 976035, size 1152.
# But I found a valid chunk (magic + valid sizes) at file offset 1152.
# And at 976035 there's NO magic.
# 
# Let me check offset 976035 directly:
p('')
p(f'Data at offset 976035:')
o = 976035
p(f'  bytes: {data[o:o+16].hex()}')
p(f'  as uint32: {struct.unpack_from("<I",data,o)[0]}')
# And also: just before it:
p(f'  bytes at 976035-8: {data[o-8:o+8].hex()}')

# And check: is the header CompressedChunks parsing perhaps SWAPPED?
# What if [109]=2 is NOT CompressionFlags but CompressedChunks.count,
# and [105]=136 (=0x88) is CompressionFlags (a composite bitflag)?
# Then entry[0]: [113..128] = 21, 816, 976035, 1152
# But 976035 as CompressedOffset vs the actual chunk at 1152 in file...
#
# UNLESS the Header has an OFFSET BIAS -- that is, the "CompressedOffset" values
# in the header are RELATIVE to right after the file header (offset X), 
# not from the start of the file!
# 
# If the header ends at offset N, then CompressedOffset = file_offset - N.
# Let's check: if bias = 976035 - 1152 = 974883, then header ends at 974883? No that's too big.
# Wait: if CompressedOffset=1152 refers to file offset 1152, then the VALUE at [125] is the offset!
# Let me swap [121] and [125]:
# [121]=976035 = CompressedSize? No that's huge for a 1152-byte chunk.
# Hmm. 
# 
# Actually wait: let me re-examine what values are in [113..145]:
p('')
p('Header values [109..148]:')
for i in range(109, 149, 4):
    v = struct.unpack_from('<i', data, i)[0]
    u = struct.unpack_from('<I', data, i)[0]
    p(f'  [{i}] = {v} (signed) = {u} (unsigned)')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\chunk_test3.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Written.')
