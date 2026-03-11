import struct, lzokay

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read()
f.close()

# Decompress chunk at 1152
coff = 1152 + 4 + 4
comp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
uncomp_total = struct.unpack_from('<i', data, coff)[0]; coff+=4
block_sz = 131072
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

# The name table has entries in a specific format.
# From the dump: local 796-820 contains:
# 0F 00 00 00  = len=15
# 62 41 63 63 65 70 74 73 4C 69 67 68 74 73 00 = "bAcceptsLights\0"
# Then 6 more bytes before next FString length?
# At local 815: 00 00 00 00 10 00 07 00
# If we skip 6 bytes after the string: 815 + ? = 821?
# Let me check local 821: bytes there
print("Bytes 815..840:", ' '.join(f'{b:02X}' for b in decomp[815:841]))
# 815: 00 00 00 00 10 00 07 00 15 00 00 00 62 41 63 63 65 70 74 73 53 74 61 74
# If 2 bytes at 815-816 = uint16(0), then 4 bytes at 817-820 = uint32(0x00070010)?
# That leaves: 15 00 00 00 = len=21 at local 821
# "bAcceptsStaticDecals" = 20 chars + null = 21
# = 62 41 63 63 65 70 74 73 53 74 61 74 69 63 44 65 63 61 6C 73 00
# Check: decomp[825:846] should be "bAcceptsStaticDecals\0"
print("Local 825-845:", decomp[825:846].decode('ascii','replace'))

# So the structure is: FString(len+chars) + uint16 HashAlgorithmId + uint32 HashValue = 6 extra bytes
# After "bAcceptsLights\0" at offset 796:
# local 796: len=15 (4B)
# local 800: 14 chars (14B)
# local 814: \x00 null (1B)  -> but FString includes null in len
# So FString data: 4 + 15 = 19 bytes, ends at offset 796+19 = 815
# Then: hash = 6 bytes? local 815..820 = 00 00 00 00 10 00
# Then next FString at 821: len=15? No, at 821 = 15 00 00 00? Let me check
print("decomp[815:823]:", decomp[815:823].hex())
print("decomp[821]:", decomp[821], "as uint32:", struct.unpack_from('<I', decomp, 821)[0])

# Or maybe the hash = 8 bytes (uint32 + uint32)?
# 815..822 = 00 00 00 00 10 00 07 00 -> 8 bytes = uint32(0) + uint32(0x00070010)?
# Then FString starts at 823: len = ?
print("decomp[823:827] as uint32:", struct.unpack_from('<I', decomp, 823)[0])
# If 21 -> "bAcceptsStaticDecals" (20+null=21) -> check!
print("decomp[827:848].decode:", decomp[827:848].decode('ascii','replace'))

# Summary: the entry format appears to be:
# FString (4 + slen bytes) + uint16 X + uint16 Y + ? 
# Let me check by trying stride=FString+6 and FString+8:
print()
print("=== Testing stride=FString+6 ===")
off = 796  # start of first entry
for i in range(10):
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen <= 0 or slen > 200: print(f'  bad slen={slen}'); break
    name = decomp[off:off+slen-1].decode('ascii','replace'); off+=slen
    # read 6 bytes hash
    ha = struct.unpack_from('<H', decomp, off)[0]; off+=2
    hv = struct.unpack_from('<I', decomp, off)[0]; off+=4
    print(f'  [{i}] name=[{name}] hash_algo={ha} hash=0x{hv:08X}  next={off}')

print()
print("=== Testing stride=FString+8 ===")
off = 796
for i in range(10):
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen <= 0 or slen > 200: print(f'  bad slen={slen}'); break
    name = decomp[off:off+slen-1].decode('ascii','replace'); off+=slen
    # read 8 bytes
    hx = struct.unpack_from('<Q', decomp, off)[0]; off+=8
    print(f'  [{i}] name=[{name}] extra=0x{hx:016X}  next={off}')

print()
print("=== Testing stride=FString only (no hash) ===")
off = 796
for i in range(10):
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen <= 0 or slen > 200: print(f'  bad slen={slen}'); break
    name = decomp[off:off+slen-1].decode('ascii','replace'); off+=slen
    print(f'  [{i}] name=[{name}]  next={off}')

# Also check without the leading null at local 795
print()
print("=== What IS the leading null byte at local 795? ===")
print("Local 780..800:", ' '.join(f'{b:02X}' for b in decomp[780:801]))
print("Local 763..795:", decomp[763:796].decode('ascii','replace'))

print()
print("=== Corrected: nameOffset=816 minus uncompstart=21 means nameTable starts at local 795 ===")
print("But local 795 = 0x00. So if the first byte at nameOff is null-terminated 'first empty string',")
print("reading with null-term+flags format:")
off = 795
for i in range(5):
    # UE3 old format: null-terminated string (variable length) + uint32 flags
    null_pos = decomp.find(b'\x00', off)
    if null_pos < 0 or null_pos - off > 200: print(f'  [{i}] no null found'); break
    name = decomp[off:null_pos].decode('ascii','replace')
    off = null_pos + 1
    flags = struct.unpack_from('<I', decomp, off)[0]; off+=4
    print(f'  [{i}] name=[{name}] flags=0x{flags:08X}  next={off}')
