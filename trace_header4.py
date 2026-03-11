import struct, zlib

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# Chunk[0]: uncompOff=21 uncompSz=816 compOff=976035 compSz=1152
# Chunk[1]: uncompOff=407156 uncompSz=976851 compOff=3856917 compSz=408308
# 
# Wait - these numbers seem like the ENTIRE uncompressed/compressed sizes:
# uncompOff=21 means the chunk's UNCOMPRESSED data starts at position 21 within the decompressed output?
# uncompSz=816 means this chunk is 816 bytes when uncompressed?
# compOff=976035 means the compressed data starts at file offset 976035?  
# compSz=1152 means the compressed block is 1152 bytes? 
#
# But 976035 + 1152 = 977187, and file size is 44MB+ so that's plausible.
# Let's look at file size first:
p(f'File size: {len(data)} bytes = {len(data)//1024//1024} MB')

# Check what's at file offset 976035:
comp_off0 = 976035
comp_sz0 = 1152
comp_data0 = data[comp_off0:comp_off0+comp_sz0]
p(f'Chunk[0] compressed data at {comp_off0}, {comp_sz0} bytes')
p(f'  First 16 bytes: {comp_data0[:16].hex()}')
p(f'  Last 4 bytes: {comp_data0[-4:].hex()}')

# Try zlib decompression
try:
    decomp = zlib.decompress(comp_data0)
    p(f'  zlib decompression SUCCESS: {len(decomp)} bytes')
except Exception as e:
    p(f'  zlib failed: {e}')

# Try zlib with different window sizes
try:
    decomp = zlib.decompress(comp_data0, -15)
    p(f'  zlib (raw deflate) SUCCESS: {len(decomp)} bytes')
except Exception as e:
    p(f'  zlib raw deflate failed: {e}')

# The second magic is at offset 1152 in the file. 
# The second magic: file[1152:1156] should be C1 83 2A 9E
second_magic = data[1152:1156]
p(f'')
p(f'At offset 1152 (compSz of chunk0): {second_magic.hex()}')
is_magic = second_magic == bytes([0xC1, 0x83, 0x2A, 0x9E])
p(f'  Is UE magic: {is_magic}')

# From the previous analysis, the 2nd magic has legacyVer=131072 = 0x00020000
# Let's read it:
lv2 = struct.unpack_from('<i', data, 1152+4)[0]
p(f'  LegacyVer at 1152+4 = {lv2}')

# Actually wait! The 2nd magic at 1152 and compSz=1152 might just be coincidence.
# Let me re-read the CompressedChunks struct more carefully.
# In UE3, FCompressedChunk = 
#   UncompressedOffset (int32) + UncompressedSize (int32) + CompressedOffset (int32) + CompressedSize (int32)
# From our parse at offset 113:
# [113]=21 = UncompressedOffset
# [117]=816 = UncompressedSize
# [121]=976035 = CompressedOffset
# [125]=1152 = CompressedSize
# 
# So Chunk[0]: uncompressed starts at output pos 21, size=816 bytes; 
#              compressed data in file at offset 976035, size=1152 bytes
# 
# BUT: the NameOffset=816 from the HEADER refers to the COMPRESSED file offset!
# So we need to read from compressed offset 816 in the FILE.
# 
# Actually no: if the file is compressed, then Header says NameOffset=816,
# which refers to offset in the DECOMPRESSED data.
# After decompression, the full file would be the normal UE3 package.
#
# But then: where does the compressed data start?
# Looking at the chunks:
# Chunk[0]: compOff=976035, compSz=1152 -> this is somewhere deep in the file
# Chunk[1]: compOff=3856917, compSz=408308 -> also deep in the file
# 
# This is STRANGE. Usually in UE3 packages, the compressed chunks contain the
# post-header data and the offsets are in the HEADER (first few KB) with 
# compressed data referring to file positions.
#
# What if we have the INT32 ORDER WRONG for FCompressedChunk?
# Alternative: UE3 FCompressedChunk = ONLY CompressedOffset + CompressedSize
# (UncompressedOffset+Size are calculated based on chunk order)
# Then our "CompressedChunks" starting at [109]=2 would be:
# count=2
# Chunk[0].CompressedOffset = 21, CompressedSize = 816
# Chunk[1].CompressedOffset = 976035, CompressedSize = 1152
# 
# With Chunk[0] starting at file offset 21, size 816:
chunk0_try = data[21:21+816]
p(f'')
p(f'Alternative: Chunk[0] = data[21:837]:')
p(f'  First 16 bytes: {chunk0_try[:16].hex()}')
try:
    decomp = zlib.decompress(chunk0_try)
    p(f'  zlib SUCCESS: {len(decomp)} bytes')
except Exception as e:
    p(f'  zlib failed: {e}')
try:
    decomp = zlib.decompress(chunk0_try, -15)
    p(f'  raw deflate SUCCESS: {len(decomp)} bytes')
except Exception as e:
    p(f'  raw deflate failed: {e}')

# Maybe the WHOLE file after the header is compressed?
# Let's look for zlib signature 0x78 somewhere:
p(f'')
p(f'Looking for zlib signature (78 9C or 78 01 or 78 DA):')
for needle in [b'\x78\x9c', b'\x78\x01', b'\x78\xda']:
    pos = data.find(needle, 100)
    if pos >= 0:
        p(f'  Found {needle.hex()} at offset {pos}')
        # try to decompress from here
        try:
            d = zlib.decompress(data[pos:pos+100000])
            p(f'    zlib from {pos}: SUCCESS, {len(d)} bytes')
        except:
            pass

# Let me look at what format the compressed chunk data uses.
# In UE3/UE4, COMPRESS_ZLIB is 0x01, COMPRESS_GZIP is 0x02, COMPRESS_LZO is 0x10, COMPRESS_LZ4 is 0x20
# CompressionFlags at [105] = 0x88 = 0b10001000 = maybe multiple flags?
# bit 3 (0x08) = ??? bit 7 (0x80) = ???
# 0x88 doesn't match standard UE3 compression flags.
# In UE4: COMPRESS_ZLIB = 0x01, COMPRESS_GZIP = 0x02, COMPRESS_Oodle = 0x40, COMPRESS_LZ4 = 0x10
# 0x88 = COMPRESS_LZ4 (0x10) | COMPRESS_Oodle (0x40) | ??? = doesn't make obvious sense

# Wait: could CompressionFlags=0x88 mean something else entirely?
# Let me reconsider. At [105] = 136 = 0x88 only if CompressedChunks is actually ParseD
# BEFORE this field. But what if the structure is different?
# 
# Actually: UE3 FPackageFileSummary stores CompressionFlags as the flags byte BEFORE
# CompressedChunks. What if the sequence is:
# [97]=EngineVersion=224
# [101]=CookerVersion=12097
# [105]=PackageSource=0x88 (uint32) [this is a hash/magic usually large]
# [109]=CompressedChunks.count=2
# [113..144]=chunks
# [145]=PackageSource (uint32) or EngineVersion high?
# WAIT the PackageSource in UE3 was added around version 482 and is a uint32 CRC.
# 0x88 = 136 is a very small CRC, unlikely. 
# 
# Maybe instead CompressionFlags is absent from this version (888),
# and we have:
# [105]=EngineVersion (additional field?)=136
# [109]=count (what?) = 2
# ...
# 
# WAIT. Re-reading the UE3 source: 
# In UE3 version >= 516 (well, this file is ver=888 which is > 516):
# After Generations array:
# - EngineVersion (uint32)
# - CookerVersion (uint32) [version >= 277]
# - PackageSource (uint32) [version >= 482]
# - CompressionFlags (uint32) [version >= 334]
# - CompressedChunks (TArray<FCompressedChunk>) [version >= 334]
# 
# UE3 FCompressedChunk = int32 UncompressedOffset + int32 UncompressedSize + int32 CompressedOffset + int32 CompressedSize
# (4 * int32 = 16 bytes per chunk)
#
# From [97]:
# [97] EngineVersion=224
# [101] CookerVersion=12097
# [105] PackageSource=0x88 (=136 -- unusually small for a uint32 source hash)
# [109] CompressionFlags=2 (COMPRESS_ZLIB! UE3 COMPRESS_ZLIB was 1, COMPRESS_LZO was 2... or was it vice versa?)
# [113] CompressedChunks.count=21 (!!!)  <- previously we read [113]=21
# 
# Wait -- what if CompressedChunks directly follows CookerVersion without PackageSource?
# Then:
# [105] CompressionFlags=136=0x88 [odd]
# [109] CompressedChunks.count=2
# [113-176] 2 chunks * 16 bytes each (32 bytes total)
# => ends at [177]
#
# OR: PackageSource is NOT present in this version, so:
# [105] CompressionFlags=136
# [109] CompressedChunks.count=2
# => Chunk[0] at [113]: UncompOff=21, UncompSz=816, CompOff=976035, CompSz=1152
# => Chunk[1] at [129]: UncompOff=407156, UncompSz=976851, CompOff=3856917, CompSz=408308
p('')
p('CompressionFlags=136=0x88 = ??? in UE3. UE3 flags: LZO=0x02 means ZLIB?')
p('Checking data at CompressedOffset from chunk[0] = 976035:')
p(f'  data[976035:976035+16] = {data[976035:976035+16].hex()}')
p(f'  data[976035:976035+4] as uint32 = {struct.unpack_from("<I",data,976035)[0]}')
# In UE3, each "compressed chunk" in the FILE is Further divided into sub-blocks.
# Each actual compressed block in the file starts with:
#   uint32 magic = 0x9E2A83C1 (again!)
#   uint32 blockSize = 0x20000 (128KB typical)
#   uint64 compressedSize
#   uint64 uncompressedSize
# Then the actual compressed sub-blocks follow.
# So let's check if 976035 starts with the UE3 chunk magic.
coff = 976035
p(f'At compOff={coff}: magic bytes = {data[coff:coff+4].hex()} (expect c1 83 2a 9e = {"YES" if data[coff:coff+4]==bytes([0xC1,0x83,0x2A,0x9E]) else "NO"})')

# From the earlier scan: 2nd magic at offset 1152 with legacyVer=131072
# Let's check: offset 1152 = compSz from chunk[0] description above (our previous reading had compSz=1152!)
# But wait, does offset 1152 have the magic because it's the SECOND CHUNK HEADER
# in the compressed chunk format?
# If the file after offset 21 is structured as:
#   [21..1151]: compressed chunk block header/data for chunk 0 
#   [1152..]: compressed chunk block header/data for chunk 1
# Let me check if [1152] = C1 83 2A 9E (already known from earlier: YES, legacyVer=131072)
# 131072 = 0x00020000 = this looks like a block size marker!
# 
# In UE3's compressed chunk format:
# PACKAGE_FILE_TAG (4) | BlockSize (4) | CompressedSize (8) | UncompressedSize (8)
# Then sub-blocks: [CompressedSubBlockSize(4) + UncompressedSubBlockSize(4)] * N
# Then actual compressed data
#
# 1152+4=1156: value = 131072 = 0x20000 = 128KB -- IS THE BLOCKSIZE!
block_size_check = struct.unpack_from('<I', data, 1156)[0]
p(f'At 1156 (after 2nd magic): value = {block_size_check} (expect 131072=0x20000 for 128KB blocks)')

# So the structure is clearer now:
# [21..1151] = first compressed chunk 
# [1152..]: second compressed chunk
# Each chunk starts with: magic(4) + BlockSize(4) + CompressedSize(8) + UncompressedSize(8) = 24 bytes header
# Then optional sub-block size pairs
# Then actual compressed data

p(f'')
p(f'Reading chunk at offset 21:')
coff = 21
magic_c = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'  magic = 0x{magic_c:08X} ({"OK" if magic_c==0x9E2A83C1 else "WRONG"})')
block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
p(f'  BlockSize = {block_sz} ({block_sz//1024}KB)')
comp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
p(f'  CompressedSize (full block) = {comp_sz_total}')
uncomp_sz_total = struct.unpack_from('<Q', data, coff)[0]; coff+=8
p(f'  UncompressedSize (full block) = {uncomp_sz_total}')
# Then a list of sub-blocks (each 8 bytes): CompressedSize(4) + UncompressedSize(4)
# Number of sub-blocks = ceil(UncompressedSize / BlockSize)
import math
if block_sz > 0 and uncomp_sz_total > 0 and uncomp_sz_total < 100*1024*1024:
    num_blocks = (uncomp_sz_total + block_sz - 1) // block_sz
    p(f'  NumSubBlocks = {num_blocks}')
    for sb in range(min(num_blocks, 5)):
        sc = struct.unpack_from('<I', data, coff)[0]; coff+=4
        su = struct.unpack_from('<I', data, coff)[0]; coff+=4
        p(f'  SubBlock[{sb}]: compSz={sc} uncompSz={su}')
    p(f'  After sub-block headers: offset={coff}')
    p(f'  Data at {coff}: {data[coff:coff+16].hex()}')
    # Try to decompress the first sub-block
    if num_blocks > 0:
        sc = struct.unpack_from('<I', data, coff-num_blocks*8)[0]  # go back to first sub-block size
        sc0 = struct.unpack_from('<I', data, coff-num_blocks*8)[0]
        su0 = struct.unpack_from('<I', data, coff-num_blocks*8+4)[0]
        data_start = coff  # actual compressed data
        p(f'  First sub-block: compSz={sc0} uncompSz={su0} at offset {data_start}')
        # try zlib
        try:
            d = zlib.decompress(data[data_start:data_start+sc0])
            p(f'  -> zlib decompressed: {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  -> zlib failed: {e}')
        try:
            d = zlib.decompress(data[data_start:data_start+sc0], -15)
            p(f'  -> raw deflate: {len(d)} bytes, first 32: {d[:32].hex()}')
        except Exception as e:
            p(f'  -> raw deflate failed: {e}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_trace4.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Written.')
