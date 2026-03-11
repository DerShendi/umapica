import struct, re

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

# The file has legacyFileVersion = 0x00050378 = 328568
# This is NOT a standard UE4 file. Let me check if this is a
# "versioned UE3" package file. UE3 packages after version 269 use:
#   Tag(4) + LicenseeVersion(4) + PackageVersion(4) + FolderName(FString) ...
# Wait, let me read ONE proper reference. After the magic (0x9E2A83C1):
# UE4 always has: int32 LegacyFileVersion (NEGATIVE value like -7)
# UE3 had: int32 Version (positive, means just the engine version)
#
# For UE3: the header layout after magic is:
#   Version(int32) -> at offset 4
#   Then: sometimes more fields depending on version number
#
# A Hat in Time is UE4.25. So legacyVer MUST be negative. 
# legacyVer = 328568 = 0x0005 0378... 
# What if these 4 bytes are NOT the legacyFileVersion but some custom prefix?
# A Hat in Time might prepend custom data. 
# 
# Let's check: is there a second copy of the magic in the file?
magic = bytes([0xC1, 0x83, 0x2A, 0x9E])
pos = 0
while True:
    p = data.find(magic, pos+1)
    if p < 0: break
    print(f'Magic at offset {p}')
    # Read legacyFileVersion after this magic
    lv = struct.unpack_from('<i', data, p+4)[0]
    print(f'  legacyVer = {lv} (0x{lv&0xFFFFFFFF:08X})')
    pos = p

print()
# So the magic at 0 is the only one. legacyVer is definitely 328568.
# Let's look at what the UE4 unreal source says about positive legacyFileVersion:
# In UnrealEngine/Source/Runtime/CoreUObject/Private/UObject/PackageFileSummary.cpp:
# if (Summary.Tag != PACKAGE_FILE_TAG)
#     { ar.SetError(); return; }
# ar << Summary.LegacyFileVersion;
# if (Summary.LegacyFileVersion == PACKAGE_FILE_TAG_SWAPPED)
#     { bSwappedTag = true; ... }
# else if (Summary.LegacyFileVersion != -4 && Summary.LegacyFileVersion > 0)
#     { UE_LOG(LogLinker, Warning, "...UnknownPackageVersion..."); ar.SetError(); return; }
#
# So UE4 treats LegacyFileVersion > 0 as an OLDER INCOMPATIBLE file.
# But the game clearly loads it. That means A Hat in Time's engine is patched to
# handle this positive version.
#
# The key insight: From the bytes, at offset 4 we have 0x00050378.
# The upper 2 bytes are 0x0005 and lower 2 bytes are 0x0378.
# In old UE2/UE3 the "Tag" was 4 bytes and "FileVersion" followed as INT32 (not int16).
# But FileVersion 888 means this IS UE3 data (version ~864 was latest UDK).
# A Hat in Time might be using a UE3-derived format for maps!
# 
# In standard UE3, after magic + FileVersion (which is the combined version),
# the struct is unpacked differently.
# In CUE4Parse and FModel which DO parse A Hat in Time correctly:
# For a uasset from AHiT, the file uses "GAME_AHatInTime" which internally
# sets a custom version offset. Looking at CUE4Parse source:
# AHiT has: game = GAME_UE4(25), Custom versions used = standard.
# But the file header shows FileVersion = 888 which is NOT UE4 era.
# 
# ACTUALLY: I think we might be reading the wrong value. Let me check carefully.
# Bytes 0-11: C1 83 2A 9E | 78 03 05 00 | 04 58 02 00
# [0..3] = magic = 0x9E2A83C1 ✓
# [4..7] = 0x00050378 = ??? 
# What if [4..5] is uint16 fileVersion = 0x0378 = 888
# and [6..7] is uint16 licenseeVersion = 0x0005 = 5
# That would be UE3's old combined version field (32 bits, upper = file, lower = licensee)
# In UE2/3 the format was: Tag(4) + FileVersion(2) + LicenseeVersion(2) + ...
# With FileVersion=888, that's in the old UE3 range.
# But AHiT is a 2017 game using UE4! Why would it use UE3 version numbers?
# 
# ANSWER: A Hat in Time uses their OWN custom legacy version number (888),
# NOT the standard UE4 negative values. Their engine fork uses a positive
# LegacyFileVersion as a custom game identifier. The rest of the format
# follows the standard UE4 layout but with this custom first field.
# 
# From CUE4Parse, the AHiT package reading code reads it as:
#   LegacyFileVersion (int32) -- but accepts 888 as valid
#   Then it reads TWO more int32s before the standard CustomVersions
#   OR it reads the standard UE4 structure but with custom version numbers.
#
# Let me try: read int32 at 4 => 328568 (ok, custom)
# Skip it, read fresh: at offset 8 we have 0x00025804 = 153604
# Skip that, read at 12: we have 0x00000005 = 5 -> is this customVersionsCount?
# 5 custom versions * 20 bytes = 100 bytes -> skip to offset 12+4+100 = 116
print("Trying: magic + skip8 + customCount at offset 12:")
off = 12
cv_count = struct.unpack_from('<i', data, off)[0]; off+=4
print(f"  customCount={cv_count}")
for i in range(min(cv_count, 20)):
    guid = data[off:off+16].hex(); off+=16
    ver = struct.unpack_from('<i', data, off)[0]; off+=4
    print(f"  custom[{i}]: guid={guid[:8]}... ver={ver}")
print(f"After custom versions: offset={off}")
total = struct.unpack_from('<i', data, off)[0]; off+=4
print(f"TotalHeaderSize={total}")
fl = struct.unpack_from('<i', data, off)[0]; off+=4
folder = data[off:off+fl-1].decode('ascii','replace') if fl > 0 else ''; off+=max(0,fl)
print(f"FolderName=[{folder}]")
pkflags = struct.unpack_from('<I', data, off)[0]; off+=4
nc = struct.unpack_from('<i', data, off)[0]; off+=4
no = struct.unpack_from('<I', data, off)[0]; off+=4
print(f"PackageFlags=0x{pkflags:08X} NameCount={nc} NameOffset={no}")
