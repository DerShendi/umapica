import struct, sys

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# Check magic
magic = struct.unpack_from('<I', data, 0)[0]
p(f'magic=0x{magic:08X} (expected 0x9E2A83C1 = {"OK" if magic==0x9E2A83C1 else "WRONG"})')

# Check all occurrences of the magic bytes
magic_bytes = bytes([0xC1, 0x83, 0x2A, 0x9E])
pos = 0
while True:
    pp = data.find(magic_bytes, pos+1)
    if pp < 0: break
    lv = struct.unpack_from('<i', data, pp+4)[0]
    p(f'  2nd magic at offset {pp}, legacyVer={lv}')
    pos = pp

# Current header bytes 0..40
p('')
p('Header bytes [0..60]:')
p(' '.join(f'{b:02X}' for b in data[0:60]))

# The current parsing: at offset 4 we read legacyFileVersion = 328568
# In the Java code:
# [0] = magic
# [4] = legacyFileVersion = readInt32() = 328568 (positive, so UE3 path)
# UE3 path currently reads:
#   readInt32() -> LicenseeVersion = data[8..11] = value
#   readFString() -> FolderName starting at data[12]
#   readInt32() -> PackageFlags
#   readInt32() -> NameCount
#   readUInt32() -> NameOffset
#   readInt32() -> ExportCount
#   readUInt32() -> ExportOffset
#   readInt32() -> ImportCount
#   readUInt32() -> ImportOffset

p('')
p('=== Current UE3-path parsing ===')
off = 4
legacy_ver = struct.unpack_from('<i', data, off)[0]; off+=4
p(f'[{off-4}] LegacyFileVersion = {legacy_ver}')

lic_ver = struct.unpack_from('<i', data, off)[0]; off+=4
p(f'[{off-4}] LicenseeVersion = {lic_ver}')

# FString at offset 12
fstr_len = struct.unpack_from('<i', data, off)[0]; off+=4
if fstr_len > 0:
    folder = data[off:off+fstr_len-1].decode('ascii','replace')
    off += fstr_len
elif fstr_len < 0:
    folder = data[off:off+(-fstr_len)*2-2].decode('utf-16-le','replace')
    off += (-fstr_len)*2
else:
    folder = ''
p(f'[{off-4-4}] FolderName len={fstr_len} value=[{folder}]')

pkg_flags = struct.unpack_from('<I', data, off)[0]; off+=4
p(f'[{off-4}] PackageFlags = 0x{pkg_flags:08X}')

name_count = struct.unpack_from('<i', data, off)[0]; off+=4
name_off = struct.unpack_from('<I', data, off)[0]; off+=4
p(f'[{off-8}] NameCount={name_count}  [offset={off-4}] NameOffset={name_off}')

export_count = struct.unpack_from('<i', data, off)[0]; off+=4
export_off = struct.unpack_from('<I', data, off)[0]; off+=4
p(f'[{off-8}] ExportCount={export_count}  [offset={off-4}] ExportOffset={export_off}')

import_count = struct.unpack_from('<i', data, off)[0]; off+=4
import_off = struct.unpack_from('<I', data, off)[0]; off+=4
p(f'[{off-8}] ImportCount={import_count}  [offset={off-4}] ImportOffset={import_off}')

p(f'Current header-end position: {off}')

# Are those values valid?
file_size = len(data)
p(f'File size (first 200KB read): {file_size}')
p(f'  NameOffset={name_off} - is it a valid offset? {0 < name_off < file_size}')
p(f'  ExportOffset={export_off} - is it a valid offset? {0 < export_off < file_size}')
p(f'  ImportOffset={import_off} - is it a valid offset? {0 < import_off < file_size}')

# Dump bytes at the claimed NameOffset
p('')
p(f'=== Bytes at claimed NameOffset={name_off} (first 64 bytes) ===')
p(' '.join(f'{b:02X}' for b in data[name_off:name_off+64]))

# Now try reading 1 name entry in UE3 format: null-terminated string + uint32 flags
p('')
p(f'Attempt: UE3 name entry = null-terminated string + uint32 flags')
o = name_off
for i in range(5):
    null_pos = data.find(b'\x00', o)
    if null_pos < 0 or null_pos - o > 200:
        p(f'  Entry {i}: no null terminator found near offset {o}'); break
    name_str = data[o:null_pos].decode('ascii','replace')
    o = null_pos + 1
    flags = struct.unpack_from('<I', data, o)[0]; o+=4
    p(f'  Entry {i}: name=[{name_str}] flags=0x{flags:08X} next_off={o}')

# Now try reading at the ExportOffset
p('')
p(f'=== Bytes at ExportOffset={export_off} (first 32 bytes) ===')
p(' '.join(f'{b:02X}' for b in data[export_off:export_off+32]))

# Let's check what version numbers UE4 uses for custom maps:
# 888 is NOT a valid UE4 version. Standard UE4 versions are ~500-522.
# standard "Legacy" versions are all NEGATIVE: -4, -5, -6, -7, -8, -9.
# But 328568 could be interpreted as TWO int16: 0x0378 and 0x0005
p('')
p('=== Interpretation as two int16 ===')
ver_lo = struct.unpack_from('<H', data, 4)[0]   # 0x0378 = 888
ver_hi = struct.unpack_from('<H', data, 6)[0]   # 0x0005 = 5
p(f'Bytes[4..5] as uint16 = {ver_lo} (decimal), 0x{ver_lo:04X}')
p(f'Bytes[6..7] as uint16 = {ver_hi} (decimal), 0x{ver_hi:04X}')
# 888 is in the UE3 era. 5 could be LicenseeTag.
# If true UE4 format would start from offset 8 (reading legacyVer=0x00025804??)
legacy_from8 = struct.unpack_from('<i', data, 8)[0]
p(f'Bytes[8..11] as int32 = {legacy_from8} (what if THIS is the real UE4 legacyVersion?)')

# Standard UE4 approach starting from offset 8:
# This is the trick: some games write extra custom header data BEFORE the standard header
# by using game-specific magic or prefix bytes.
# bytes[0..3] = real magic(0x9E2A83C1)   <-- standard
# bytes[4..7] = GAME_SPECIFIC prefix (0x00050378 = custom game version)
# bytes[8..11] = REAL UE4 LegacyFileVersion (should be negative!)
if legacy_from8 < 0:
    p(f'  The real LegacyFileVersion starts at offset 8! Value={legacy_from8}')
    p(f'  This means A Hat in Time inserts a 4-byte custom prefix after the magic!')
    
    # Parse standard UE4 from offset 8
    p('')
    p('=== Standard UE4 parse from offset 8 ===')
    off = 8
    legacy = struct.unpack_from('<i', data, off)[0]; off+=4
    p(f'  [8] LegacyFileVersion={legacy}')
    if legacy <= -4:
        p('  [12] Next should be -3 or file is UE4...')
        legacy_ue3 = struct.unpack_from('<i', data, off)[0]; off+=4
        p(f'  [12] LegacyUE3Version={legacy_ue3}')
        ue4ver = struct.unpack_from('<i', data, off)[0]; off+=4
        p(f'  [16] FileVersionUE4={ue4ver}')
        ue5ver = 0
        if legacy <= -8:
            ue5ver = struct.unpack_from('<i', data, off)[0]; off+=4
            p(f'  [20] FileVersionUE5={ue5ver}')
        # If ue4ver > 0, we have legitimate data
        if ue4ver > 0:
            p(f'  UE4 version looks valid: {ue4ver}')
else:
    p(f'  Bytes[8..11] = {legacy_from8} (not negative, so not a prefix trick)')

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\header_analysis.txt','w') as fw:
    fw.write('\n'.join(out))
print('Written to header_analysis.txt')
