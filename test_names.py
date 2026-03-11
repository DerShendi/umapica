import struct, lzokay

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# From the header: CompressedChunks table (at offset 109-144):
# count=2
# Chunk[0]: UncompOff=21, UncompSz=816, CompOff=976035, CompSz=1152
# Chunk[1]: UncompOff=407156, UncompSz=976851, CompOff=3856917, CompSz=408308
#
# But we found the ACTUAL chunk at file offset 1152 (not 976035).
# And the chunk at 1152 has uncompSz=976035 (which == the UncompOff for chunk[1] in the table??).
# And the chunk at 408308 is the second valid chunk.
# 
# NEW INTERPRETATION: The FPackageSummary.CompressedChunks in THIS file might
# have a DIFFERENT byte order or layout:
# [113]=21 = CompressedSize (how many compressed bytes the compressed chunk uses)? No, too small.
# [117]=816 = CompressedOffset (file offset where chunk starts)? But chunk is at 1152... 
# Wait: 816 != 1152.
# 
# Hmm.  What if the CompressedChunks table in this file is:
# [113]=UncompressedOffset=21
# [117]=UncompressedSize=816  <- but chunk at 1152 has uncompSz=976035?
# 
# WHAT IF: the header stores CHUNK-LEVEL statistics (not sub-block),
# and the 4 values represent: chunkIndex_start, chunkCount, fileOff, fileSz
# and the 816 is NOT the uncompressed size but something else?
# 
# Let me just try: nameOffset=816 in the HEADER TEXT means DECOMPRESSED offset.
# From the decompressed data (chunk starting at 1152), the first sub-block (0-131071 in 
# decompressed space), the name starts at decompressed position 816.
# At decompressed position 816 in the chunk's output = local offset 816 (no subtraction needed).
# Because the chunk covers the ENTIRE decompressed file starting from byte 0.
# 
# The CompressedChunks entry [113]=21 might be an "uncompressed start = 21" meaning
# the chunk doesn't cover bytes 0..20 (those are in the header), 
# and the decompressed chunk starts at offset 21 in the virtual stream.
# So in the virtual stream: [0..20] = from the file header (uncompressed)
#                           [21..976055] = this chunk's decompressed data
# The name at virtual offset 816 = local offset 816-21 = 795 in the decompressed chunk.
# We showed that 795 has a leading 00, and 796 has the first FString len.
# 
# Let me check: maybe the FPackageSummary.CompressedChunks[0].UncompressedOffset=21 
# means the chunk corresponds to bytes [21..] in the virtual stream, 
# so virtual 816 = local 816-21 = 795. The extra byte at 795 might be 
# that the previous "section" ended at virtual offset 815 with a null byte,
# and nameOffset=816 is EXACT, but there's an END-of-previous-data byte at 815.
# 
# Actually: virtual 816 - 21 = 795 is correct. The issue is that at virtual 816,
# the first byte is 0x00. In old UE3, name entries were:
# null-terminated string + uint32 Flags
# So: \x00 = empty null-terminated string! And then uint32 Flags = 0F 00 00 00 = 15?? That makes no sense for flags.
# 
# OR: the header says these are "hash-based" name entries, not plain FStrings.
# In later UE3/UE4, FNameEntrySerialized is:
# - FString (int32 len + chars)
# - uint16 HashAlgorithmId
# - uint32 HashValue
# For ue4ver >= 504 (FReleaseObjectVersion). This is OLD format (no hash).
# 
# Our virtualOffset=816 at localOffset=795 starts with: 00 0f 00 00 00 62 41 63...
# Let's try: at virtual 817 (local 796), read as FString:
# [796] = 0f 00 00 00 = len=15: then [800..814] = 62 41 63 63 65 70 74 73 4c 69 67 68 74 73 00
#       = "bAcceptsLights\0" ✓
# Then next name at [815]: 00 00 00 00 = len=0 = empty string, then next entry, etc.
# BUT: [815] = 00, [816] = 10, [817] = 00, [818] = 07 ...
# Wait let me dump more bytes:

# Decompress all of chunk 0 first
coff = 1152
coff += 4  # magic
block_sz = struct.unpack_from('<I', data, coff)[0]; coff+=4
comp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
uncomp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
num_sub = (uncomp_total + block_sz - 1) // block_sz
sub_sizes = []
for i in range(num_sub):
    sc = struct.unpack_from('<i', data, coff)[0]; coff+=4
    su = struct.unpack_from('<i', data, coff)[0]; coff+=4
    sub_sizes.append((sc, su))
parts = []
for sc, su in sub_sizes:
    parts.append(lzokay.decompress(data[coff:coff+sc], su))
    coff += sc
decomp = b''.join(parts)
p(f'Decompressed {len(decomp)} bytes')

# Virtual offset 816 = local offset 816 - 21 = 795
# Dump bytes 790 to 870
p('Bytes local 788..867 (virtual 809..888):')
for i in range(788, 868, 16):
    h = ' '.join(f'{b:02X}' for b in decomp[i:i+16])
    a = ''.join(chr(b) if 32 <= b < 127 else '.' for b in decomp[i:i+16])
    p(f'  local[{i}] virt[{i+21}]: {h}  {a}')

p('')
p('=== Trying to read names assuming nameOffset=816 means localOff=795 === ')
# But the byte at 795 is 0x00 (null). Try reading at local 796 = virt 817:
p('At local 795 (virt 816): ' + decomp[795:796].hex() + ' = ' + str(decomp[795]))
p('At local 796 (virt 817): ' + decomp[796:800].hex() + ' = len=' + str(struct.unpack_from('<i',decomp,796)[0]))

p('')
p('=== Trying: nameOffset=816 is WRONG by 1, actual names at virt 817 = local 796 ===')
off = 796
names = []
for i in range(30):
    if off + 4 > len(decomp): break
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen <= 0 or slen > 256:
        p(f'  Entry[{i}]: bad len={slen} at local_off={off-4} (virt={off-4+21})')
        break
    name = decomp[off:off+slen-1].decode('ascii','replace')
    off += slen
    p(f'  Name[{i}]: [{name}]')
    names.append(name)
p(f'After 30 names, local off={off} (virt={off+21})')

p('')
p('=== Alternative: try with nameOffset=816 treated as 0-based local offset ===')
off = 816
for i in range(10):
    if off + 4 > len(decomp): break
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen <= 0 or slen > 256:
        p(f'  bad len={slen}'); break
    name = decomp[off:off+slen-1].decode('ascii','replace')
    off += slen
    p(f'  Name[{i}]: [{name}]')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\lzo_names.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Done.')
