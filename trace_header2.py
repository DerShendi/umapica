import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

name_off = 816
name_count = 1037

# UE4 FNameEntrySerialized format:
# For version >= 516 (roughly UE 4.12+):
#   int16 nameLen+isWide packed in upper bit
#   char[nameLen] (or wchar)
# For older versions:
#   FString (int32 len + chars)  -- but with hash after
# 
# Currently the UE3 path uses "FString at name_off" which fails.
# 
# But wait: A Hat in Time claims legacyFileVersion=328568 but IS a UE4 game.
# Its ue4ver should be a valid UE4 version (like 497-522).
# 
# Looking at the data at offset 49 more carefully:
# [49] 38 3F 02 00 = 147256 (DependsOffset)
# [53] 04 58 02 00 = 153604 
# [57-68] all zeros
# [69-84] GUID bytes
# [85] 01 00 00 00 = 1 (GenerationCount)
# [89] 33 06 00 00 = 1587 (matches ExportCount!)  <- GenerationEntry.ExportCount
# [93] 0D 04 00 00 = 1037 (matches NameCount!)    <- GenerationEntry.NameCount
# [97] E0 00 00 00 = 224   <- SavedEngineVersion or something

# So:
# [49] DependsOffset = 147256
# [53] SoftPkgRefsOffset = 153604 (or some other offset or size)
# [57] SoftPkgRefsCount = 0    (or pad)
# [61] SearchableNamesOffset = 0
# [65] ThumbnailTableOffset = 0 (or part of GUID)
# [69] PackageGuid (16 bytes)
# [85] GenerationCount = 1
# [89] FGenerationInfo[0].ExportCount = 1587
# [93] FGenerationInfo[0].NameCount = 1037
# Then at [97] = 224 = what?

# For UE3 format (PackageVersion=888), FGenerationInfo has only 2 fields:
# ExportCount(int32) + NameCount(int32) = 8 bytes per entry
# So GenerationCount=1, entry[0] = exports=1587, names=1037 -> next at [97]
# At [97], if this is EngineVersion:
#   224 in old UE3 was a reasonable engine version.
# Then maybe it's just an int32 EngineVersion = 224.
# At [101] = 12097 = 0x2F41... hmm
# Wait [97-100] = E0 00 00 00 = 224, and 224 decimal is plausible for UE3 engine version
# (UE3 had engine version 0-835 range)
# But then [101-104] = 41 2F 00 00 = 12097... doesn't fit well

# Let's look at offset 97-168 as a group of counts/offsets:
p('=== Possible FTextLocalizationResourceId or other tables ===')
p('Trying to find AssetRegistryDataOffset and more:')
off = 97
for label in ['CookerIndicator?', 'BulkDataStartOffset?', 'WorldCompositionDataOffset?', 
               'AssetRegistryData?', 'Off5', 'Off6', 'Off7', 'Off8']:
    v = struct.unpack_from('<I', data, off)[0]
    p(f'[{off}] {label} = {v}')
    off += 4

# Now let me try a completely different approach: 
# Parse the name table AT OFFSET 816 using UE4's "new" format (v >= 516):
# Each FNameEntrySerialized = int16 header (lower 15 bits = length, upper bit = wide/narrow flag)
# Then "length" chars (1 byte each for narrow, 2 bytes each for wide)
# Then a uint16 HashAlgorithmId  
# Then a uint32 HashValue
# 
# int16 at 816 = struct.unpack_from('<h', data, 816)[0]
h = struct.unpack_from('<H', data, 816)[0]
p(f'')
p(f'At nameOff=816: uint16 = {h} = 0x{h:04X}')
p(f'  Lower 15 bits (length) = {h & 0x7FFF}')
p(f'  Upper bit (isWide) = {(h>>15) & 1}')

# 816: 07 00 00 00 02 00 00 00
# As uint16: [816]=0x0007=7, [818]=0x0000=0, [820]=0x0002=2 ...
# If first entry header is 0x0007 = length 7, narrow:
# Then string = data[818:825] = bytes at 818..824
p('')
p(f'If format is uint16_header + chars:')
off = name_off
for i in range(5):
    h = struct.unpack_from('<H', data, off)[0]; off+=2
    name_len = h & 0x7FFF
    is_wide = (h >> 15) & 1
    if name_len > 0 and name_len < 200:
        if is_wide == 0:
            name = data[off:off+name_len].decode('latin-1','replace')
            off += name_len
        else:
            name = data[off:off+name_len*2].decode('utf-16-le','replace')
            off += name_len*2
        # read hash (6 bytes)
        hash_algo = struct.unpack_from('<H', data, off)[0]; off+=2
        hash_val = struct.unpack_from('<I', data, off)[0]; off+=4
        p(f'  Entry[{i}]: len={name_len} wide={is_wide} name=[{repr(name)}] hash_algo={hash_algo} hash=0x{hash_val:08X}')
    else:
        p(f'  Entry[{i}]: len={name_len} (skip -- likely wrong format)')
        break

# Try with the format that includes the trailing \0 in length:
p('')
p(f'If format is FString (int32 len) + uint16 hash_algo + uint32 hash_val:')
off = name_off
for i in range(5):
    slen = struct.unpack_from('<i', data, off)[0]; off+=4
    if slen <= 0 or slen > 200:
        p(f'  Entry[{i}]: bad len={slen} at offset={off-4}'); break
    name = data[off:off+slen-1].decode('ascii','replace')
    off += slen
    hash_algo = struct.unpack_from('<H', data, off)[0]; off+=2
    hash_val = struct.unpack_from('<I', data, off)[0]; off+=4
    p(f'  Entry[{i}]: len={slen} name=[{repr(name)}] hash_algo={hash_algo} hash=0x{hash_val:08X} next={off}')

# UE4 versions < 516 use the OLD format: FString only (no hash suffix)
p('')
p(f'If format is JUST FString (int32 len + chars, no hash):')
off = name_off
for i in range(5):
    slen = struct.unpack_from('<i', data, off)[0]; off+=4
    if slen <= 0 or slen > 200:
        p(f'  Entry[{i}]: bad len={slen} at offset={off-4}'); break
    name = data[off:off+slen-1].decode('ascii','replace')
    off += slen
    p(f'  Entry[{i}]: len={slen} name=[{repr(name)}] next={off}')

# But wait: the actual ue4ver of this file:
# legacyVer = 328568 -- this is NOT the ue4Ver! 
# In standard UE4: legacyVer < 0, then we read more standard headers.
# This file has legacyVer > 0, meaning the UE3 CODE PATH in our parser is used.
# BUT the UE3 code path cannot correctly decode the extra fields after importOffset.
# The true ue4Ver has never been read -- it's buried somewhere in those extra fields.
#
# Looking at the raw bytes from offset 49 to 816:
# [53] = 0x00025804 = 153604 = same as LicenseeVersion at [8]!
# Could [53] BE the ue4Ver read in some other way?
# 
# Actually: what if this is UE4 but with a DIFFERENT magic?
# UE4 SWAPPED TAG = 0xC12A839E (byte-swapped)
# Our magic at [0] = 0x9E2A83C1 = correct UE4 LE magic
#
# What if legacyVer = 0x00050378 was meant to be read as TWO int16s
# where lower half = 0x0378 = 888 = actual UE3 package version?
# And the engine continues reading as UE3...
# In that case, UE3 FPackageSummary after importOffset (at 49) would have:
# - DependsOffset (int32) [only in version >= 415]
# - GuidCount, GuidOffset [in some versions]
# - HeritageCount, HeritageOffset [very old UE3]
# - Guid (16 bytes)
# - GenerationCount (int32)
# - GenerationInfo (array)
# - EngineVersion (int32) [in some versions]
# - CompressionFlags (int32)
# - CompressedChunks (TArray)
# etc.

# Actually in UE3 (version 268-864 range):
# The FPackageSummary contains (in rough order by version added):
# version >= 415: DependsOffset
# version >= 516: SoftPackageRefs  
# version >= 605: BasePackageGuid (Guid)  <-- our 4 zeros at 57? and GUID at 69?
# version >= 417: Guid
# version >= 444: Generations (array)
# version >= 277: EngineVersion
# version >= 277: CookerVersion
# etc.

# UE3 version 888 would have ALL these fields. Let's re-parse with that in mind:
p('')
p('=== Re-parsing from offset 49 with full UE3 (ver=888) interpretation ===')
off = 49
v, _ = struct.unpack_from('<i', data, off), None
v = struct.unpack_from('<i', data, off)[0]; off+=4
p(f'[{off-4}] DependsOffset = {v}')
v = struct.unpack_from('<i', data, off)[0]; off+=4
p(f'[{off-4}] SoftPkgRefsOffset = {v}  <- or CustomVersionsOffset?')
for z in range(3):
    v = struct.unpack_from('<i', data, off)[0]; off+=4
    p(f'[{off-4}] zero_field_{z} = {v}')
# ver >= 605: BasePackageGuid 
g = data[off:off+16].hex(); off+=16
p(f'[{off-16}] BasePackageGuid = {g}')
# version >= 417: Guid
g = data[off:off+16].hex(); off+=16
p(f'[{off-16}] PackageGuid = {g}')
# Generations
gc = struct.unpack_from('<i', data, off)[0]; off+=4
p(f'[{off-4}] GenerationCount = {gc}')
for i in range(min(gc, 10)):
    ec = struct.unpack_from('<i', data, off)[0]; off+=4
    nc = struct.unpack_from('<i', data, off)[0]; off+=4
    p(f'  Gen[{i}]: ExportCount={ec}, NameCount={nc}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_trace2.txt','w') as fw:
    fw.write('\n'.join(out))
print('Written.')
