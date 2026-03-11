import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))
def ri32(o): return struct.unpack_from('<i', data, o)[0], o+4
def ru32(o): return struct.unpack_from('<I', data, o)[0], o+4
def rs16(o): return struct.unpack_from('<h', data, o)[0], o+2
def rfstr(o):
    n = struct.unpack_from('<i', data, o)[0]; o+=4
    if n > 0:
        s = data[o:o+n-1].decode('ascii','replace'); o+=n
    elif n < 0:
        s = data[o:o+(-n)*2-2].decode('utf-16-le','replace'); o+=(-n)*2
    else:
        s = ''
    return s, o

# From offset 97, we have (per UE3 FPackageSummary v888):
# EngineVersion (int32) = 224
# Then what? In UE3:
# - CompressionFlags (int32)  [added in some version]
# - CompressedChunks (TArray<FCompressedChunk>) [count(int32) + array]
# - PackageSource (uint32) [version >= 482]
# - AdditionalPackagesToCook (TArray<FString>) [count + array of FStrings]
# etc.
# 
# The KEY question: knowing nameOff=816 from the header, and that offset 97-815 holds
# "extra" header data, we just need to correctly SKIP it in our parser.
# We don't need to fully decode all those fields.
# 
# Strategy: just SEEK to nameOff and read names there.
# But the data at 816 doesn't look like name strings either!
# 
# At 816: 07 00 00 00 02 00 00 00 01 00 00 00 01 00 00 00 1B 06 00 00 ...
# 
# Wait -- what if the NameOffset is CORRECT but references a DIFFERENT format?
# Maybe this is a "compressed names" format used by UE3:
# In some UE3 versions, the name table is stored as a bulk hash table, not a flat array.
# 
# Let me check: in A Hat in Time's ACTUAL format, what is the UE4 version?
# The legacyFileVersion=328568 makes UE4 think it's unrecognized.
# 
# Actually, let me look at CUE4Parse source for A Hat in Time (GAME_AHatInTime):
# In CUE4Parse, EGame enum includes GAME_AHatInTime = GAME_UE4_25
# And in AssetRegistry.cs or PackageReader.cs, it reads standard UE4 format.
# So AHiT IS a standard UE4 game (v4.25 = ue4ver ~518).
# 
# But mafia_town.umap shows LegacyFileVersion=328568 (positive!).
# That means either:
# a) This file was saved by a very old tool in UE3 format (unlikely for 2017 game)
# b) The file has a custom header prepended before the standard UE4 data
# c) The file was processed/packed differently
# 
# WAIT: Let me check if there's a different "signature" or "tag" for A Hat In Time.
# Some games use a custom magic value instead of 0x9E2A83C1.
# The file starts with C1 83 2A 9E which IS the standard magic.
# 
# Actually: what if bytes 4-7 (0x00050378) is NOT the LegacyFileVersion,
# but instead this is an ENCRYPTED or COMPRESSED header that starts with
# the custom game version, and the REAL UE4 header starts elsewhere?
# 
# Let me check if there's a repeating pattern that could be an XOR key:
# bytes 4-7: 78 03 05 00
# bytes 8-11: 04 58 02 00
# Hmm, 78+04=7C, 03+58=5B -- no obvious XOR.

# New approach: let me SEEK to nameOff=816 and try reading with
# UE4 FNameEntrySerialized format (version < 516, i.e., FString-only, no hash):
# FNameEntrySerialized (old, no hash) = FString (int32 len + chars including null)
# At 816: 07 00 00 00 = len=7, then 6 chars + null = data[820:826]
p('Trying FNameEntrySerialized (FString only) at offset 816:')
name_off = 816
off = name_off
for i in range(10):
    slen, off = ri32(off)
    if slen <= 0 or slen > 256:
        p(f'  Entry[{i}]: BAD len={slen} at pos={off-4}'); break
    chars = data[off:off+slen]
    ascii_chars = chars[:-1].decode('ascii','replace')  # exclude null terminator
    off += slen
    p(f'  Entry[{i}]: len={slen}, name=[{repr(ascii_chars)}] ({chars.hex()})')

p('')
p('Bytes at 816 decoded as possible UE3 raw name table:')
p('In UE3 (ver >= 0, < UE4), FNameEntry is:')
p('  StringData (null terminated, variable) then some flags.')
p('But at offset 816, bytes are: 07 00 00 00 02... suggesting length prefix not null term.')

# Let me try: offset 816 data is actually an ARRAY header:
# int32 count = 7, then 7 items of some sort?
# [816] = 07 00 00 00 = 7
# Then 7 entries? of what size?
# If each entry is 28 bytes (a common UE size):
# 7 * 28 = 196 bytes -> 816 + 4 + 196 = 1016
# Then at 1016 what do we have?
p('')
count = struct.unpack_from('<I', data, 816)[0]
p(f'If [816] is an array count: count={count}')
p('What follows?')
for stride in [4, 8, 12, 16, 20, 24, 28, 32]:
    end_off = 816 + 4 + count * stride
    if end_off + 4 < len(data):
        next_val = struct.unpack_from('<I', data, end_off)[0]
        # check if next_val could be a string length (< 200) 
        if 0 < next_val < 200:
            peek = data[end_off+4:end_off+4+next_val].decode('ascii','replace')
            p(f'  stride={stride}: array ends at {end_off}, next uint32={next_val}, next fstr=[{peek}]')
        else:
            p(f'  stride={stride}: array ends at {end_off}, next uint32={next_val}')

# If those 767 bytes (from 49 to 816) contain header data,
# maybe they include:
# - depends(4) + softPkg(4) + 3*zeros(12) = 20 bytes [49-68]
# - GUID(16) [69-84]
# - genCount(4) + gen0(8) = 12 bytes [85-96]
# - EngineVersion(4) [97-100]
# Total so far: 49 + 20 + 16 + 12 + 4 = 101 offset
# Then at 101: value = 12097... 
# Maybe a TArray<FCompressedChunk>?
# Count=12097?! No that's too large.
# Or: maybe it's TWO fields of int16 or 2 bytes each?
# 101: 41 2F = Bytes; as two uint16: [101]=0x41 and [102]=0x2F
# or as int32: 12097 = 0x2F41
# 2F41 hex could be flags or a version...

# Let me look at [101] differently:
# After the EngineVersion(224) at [97]:
# Maybe CookerVersion(int32) at [101] = 12097? Plausible for UE3.
# Then CompressionFlags(int32) at [105] = 136 = 0x88...
# Then CompressedChunks count at [109] = 2
# With 2 compressed chunks, each FCompressedChunk = UncompressedOffset(4) + UncompressedSize(4) + CompressedOffset(4) + CompressedSize(4) = 16 bytes
p('')
p('=== Trying EngineVersion+CookerVersion+Compression path from offset 97 ===')
off = 97
ev, off = ri32(off); p(f'[{off-4}] EngineVersion = {ev}')
cv, off = ri32(off); p(f'[{off-4}] CookerVersion = {cv}')
cf, off = ru32(off); p(f'[{off-4}] CompressionFlags = 0x{cf:08X}')
cc, off = ri32(off); p(f'[{off-4}] CompressedChunks.count = {cc}')
if 0 <= cc <= 20:
    for i in range(cc):
        uo, off = ri32(off); us, off = ri32(off); co, off = ri32(off); cs, off = ri32(off)
        p(f'  Chunk[{i}]: uncompOff={uo} uncompSz={us} compOff={co} compSz={cs}')
    p(f'After chunks: offset={off}')
    # After CompressedChunks, maybe PackageSource (uint32)?
    ps, off = ru32(off); p(f'[{off-4}] PackageSource = 0x{ps:08X}')
    # AdditionalPackagesToCook (TArray<FString>)
    apc, off = ri32(off); p(f'[{off-4}] AdditionalPackages.count = {apc}')
    if 0 <= apc <= 10:
        for i in range(apc):
            s, off = rfstr(off); p(f'  Pkg[{i}] = [{s}]')
    p(f'After AdditionalPackages: offset={off}')
    v, off = ri32(off); p(f'[{off-4}] next_field = {v}')
    v, off = ri32(off); p(f'[{off-4}] next_field = {v}')
    v, off = ri32(off); p(f'[{off-4}] next_field = {v}')
    v, off = ri32(off); p(f'[{off-4}] next_field = {v}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_trace3.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
print('Written.')
