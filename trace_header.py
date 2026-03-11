import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))
def ri32(o): return struct.unpack_from('<i', data, o)[0], o+4
def ru32(o): return struct.unpack_from('<I', data, o)[0], o+4
def rfstr(o):
    n = struct.unpack_from('<i', data, o)[0]; o+=4
    if n > 0:
        s = data[o:o+n-1].decode('ascii','replace'); o+=n
    elif n < 0:
        s = data[o:o+(-n)*2-2].decode('utf-16-le','replace'); o+=(-n)*2
    else:
        s = ''
    return s, o
def rguid(o):
    return data[o:o+16].hex(), o+16

# We know the base header ends at offset 49:
# [0] magic
# [4] legacyFileVersion=328568
# [8] licVer=153604
# [12] FolderName len=5 "None"
# [21] PackageFlags=0x228A0009
# [25] nameCount=1037
# [29] nameOffset=816
# [33] exportCount=1587
# [37] exportOffset=39256
# [41] importCount=293
# [45] importOffset=31052
# [49] ??? start of extra fields

# It looks like the format at 49 continues with more UE3 header fields.
# For UE3 package version ~888, the fields after importOffset include:
# - DependsOffset (int32)
# - HeritageCount + HeritageOffset (old format)
# OR in later UE3/early UE4:
# - DependsOffset (int32)
# - f_5F7 (guid related)
# - GenerationInfo (count + array)
# etc.

# Let's just read them systematically and see when we can match "nameOffset=816" data:
off = 49
v, off = ri32(off); p(f'[{off-4}] int32 = {v} = 0x{v&0xFFFFFFFF:08X}   (DependsOffset?)')
v, off = ri32(off); p(f'[{off-4}] int32 = {v} = 0x{v&0xFFFFFFFF:08X}   (SoftPkgRefCount or AdditionalPkgCount?)')
v, off = ri32(off); p(f'[{off-4}] int32 = {v}   (zero)')
v, off = ri32(off); p(f'[{off-4}] int32 = {v}   (zero)')
v, off = ri32(off); p(f'[{off-4}] int32 = {v}   (zero)')
g, off = rguid(off)
p(f'[{off-16}] GUID = {g}')
v, off = ri32(off); p(f'[{off-4}] int32 = {v}   (PersistentGuid bool? or generation count?)')
v, off = ri32(off); p(f'[{off-4}] int32 = {v} = 0x{v&0xFFFFFFFF:08X}')
v2, off2 = ri32(off); 
p(f'[{off}] int32 = {v2} -- if generationCount={v}, generationEntry[0].exportCount={v2}?')

# Try: bytes at 81 = 01 00 00 00 33 06 00 00 0D 04 00 00 E0 00 00 00
# 01 00 00 00 = 1 (GenerationCount)  
# 33 06 00 00 = 1587 (ExportCount from GenerationEntry?) -- matches exportCount!
# 0D 04 00 00 = 1037 (NameCount from GenerationEntry?) -- matches nameCount!
# Then at 97: E0 00 00 00 = 224 (GenerationEntry for NetObjectCount? or next field)
off = 81
p('')
p('=== Trying UE3 GenerationInfo interpretation at offset 81 ===')
gen_count, off = ri32(off); p(f'GenerationCount = {gen_count}')
for i in range(gen_count):
    ec, off = ri32(off)
    nc, off = ri32(off)
    noc, off = ri32(off)  # only in some versions -- UE3 had 3-element FGenerationInfo
    p(f'  Gen[{i}]: ExportCount={ec}, NameCount={nc}, NetObjectCount={noc}')
p(f'After GenerationInfo: offset={off}')

# At offset 97 we have E0 00 00 00 = 224.
# After the GenerationInfo we might have:
# EngineVersion (int32 or FEngineVersion struct)
# SavedByEngineVersion (FEngineVersion struct)
# etc.
v1 = struct.unpack_from('<i', data, 97)[0]
v2 = struct.unpack_from('<i', data, 101)[0]
p(f'[97] = {v1},  [101] = {v2}')

# After gen info complete at offset 97, continue:
# Try reading as: EngineVersion(struct) or int32
# UE3 FEngineVersion = Major(uint16) + Minor(uint16) + Patch(uint16) + Changelist(uint32) + Branch(FString)
# OR in very early format: just an int32 EngineVersion

# Let me trace from offset 97 more carefully
off = 97
p('')
p('=== Tracing from offset 97 ===')
for i in range(50):
    v = struct.unpack_from('<i', data, off)[0]
    u = struct.unpack_from('<I', data, off)[0]
    p(f'[{off}] = {v} ({u} unsigned, 0x{u:08X})')
    off += 4
    if off >= 500: break

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_trace.txt','w') as fw:
    fw.write('\n'.join(out))
print('Written.')
