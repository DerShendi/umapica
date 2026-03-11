import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# The UE3 path currently parses to offset 49. 
# But there's a 2nd magic at offset 1152 (legacyVer=131072)!
# The data between offset 49 and the name table at 816 must contain more header fields.
# Let's figure out what fields follow ImportOffset in UE3/UE4 FPackageFileSummary:
# 
# In UE4 (PackageFileSummary.cpp), after ImportOffset:
#   DependsOffset (int32)
#   SoftPackageReferencesCount (int32) [optional, depends on version]
#   SoftPackageReferencesOffset (int32) [optional]
#   SearchableNamesOffset (int32) [optional]
#   ThumbnailTableOffset (int32) [optional]
#   Guid (FGuid, 16 bytes)
#   PersistentGuid (FGuid, 16 bytes) [optional]
#   IsUnversioned (int32/bool) [optional]
#   GenerationCount (int32)
#   GenerationInfo (array of FGenerationInfo = ExportCount(4) + NameCount(4) each)
#   ...
#   etc.
# 
# For UE3 (old format), the fields after importOffset are:
#   HeritageCount (int32)
#   HeritageOffset (int32)
# Then various optional fields depending on version number (888 in this case).
# 
# We're at offset 49 after ImportOffset. Let's treat this as UE3 format 
# and see what bytes follow:

p('=== Bytes from offset 49 to 816 ===')
off = 49

# Dump everything from 49 to estimate what's there
p(f'Total bytes from off=49 to name_off=816: {816-49} bytes')
p(f'Hex dump:')
for i in range(0, 816-49, 16):
    seg = data[49+i:49+i+16]
    hex_str = ' '.join(f'{b:02X}' for b in seg)
    asc_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in seg)
    p(f'{49+i:4d}: {hex_str:<48}  {asc_str}')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_gap.txt','w') as fw:
    fw.write('\n'.join(out))
print('Written to header_gap.txt')
