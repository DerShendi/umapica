"""
Scans a Hat in Time .umap for UE3 StaticMesh vertex/index buffer patterns.
Usage: python scan_upk.py <path_to_umap>
"""
import struct, math, sys, os

PATH = r"D:\Steam\steamapps\common\HatinTime\HatinTimeGame\CookedPC\Maps\hub\hub_spaceship_ending_mafiatown.umap"

b = open(PATH, 'rb').read()
print(f"File size: {len(b)}")

magic = struct.unpack_from('<I',b,0)[0]
ver = struct.unpack_from('<H',b,4)[0]
lic = struct.unpack_from('<H',b,6)[0]
totalHdr = struct.unpack_from('<i',b,8)[0]
print(f"Magic=0x{magic:08X} ver={ver} lic={lic} totalHdr={totalHdr}")

# PackageFlags is after FolderName. FolderName is at offset 12.
fnlen = struct.unpack_from('<i',b,12)[0]
print(f"FolderNameLen={fnlen}")
if 0 < fnlen < 64:
    fn_bytes = b[16:16+fnlen]
    fn = fn_bytes[:-1].decode('latin-1','replace')
    print(f"FolderName={fn!r}")
    off = 16 + fnlen
elif fnlen < 0:
    # Unicode: length is -(numChars+1), each char is 2 bytes
    off = 16 + (-fnlen) * 2
    print(f"FolderName=Unicode")
else:
    off = 16
    print(f"FolderName=empty")

pkgflags = struct.unpack_from('<I',b,off)[0]
print(f"PackageFlags=0x{pkgflags:08X} at off={off}")
if pkgflags & 0x02000000:
    print("  --> PKG_StoreCompressed flag set!")
else:
    print("  --> Not compressed at package level")
if pkgflags & 0x00200000:
    print("  --> PKG_RequireImportsAlreadyLoaded")

# Read counts
nameCount = struct.unpack_from('<i',b,off+4)[0]
nameOff   = struct.unpack_from('<i',b,off+8)[0]
expCount  = struct.unpack_from('<i',b,off+12)[0]
expOff    = struct.unpack_from('<i',b,off+16)[0]
impCount  = struct.unpack_from('<i',b,off+20)[0]
impOff    = struct.unpack_from('<i',b,off+24)[0]
print(f"names={nameCount}@{nameOff} exports={expCount}@{expOff} imports={impCount}@{impOff}")

# Check for compression chunk table (UE3 stores it after depends table etc.)
# In UE3, after ImportOffset/ExportOffset/DependsOffset there is:
# ThumbnailTableOffset, GUID (16 bytes), GenerationCount, generations[], EngineVersion,
# CookerVersion, PackageFlags2, CompressionFlags, CompressedChunks[]
# For version 888, offset sequence after the main counts:
# off+28 = DependsOffset, off+32 = ThumbnailTableOffset, off+36 = GUID (16 bytes)

dependsOff = struct.unpack_from('<i',b,off+28)[0]
thumbnailOff = struct.unpack_from('<i',b,off+32)[0]
print(f"dependsOff={dependsOff} thumbnailOff={thumbnailOff}")

# GUID at off+36
guidOff = off + 36
guid = b[guidOff:guidOff+16].hex()
print(f"GUID={guid}")

# After GUID: GenerationCount, then generations (each = 2 ints), then EngineVersion, CookerVersion
genCountOff = guidOff + 16
genCount = struct.unpack_from('<i',b,genCountOff)[0]
print(f"GenCount={genCount}")
after_gen = genCountOff + 4 + genCount * 8  # each generation is 2 int32s
engineVer = struct.unpack_from('<i',b,after_gen)[0]
cookerVer = struct.unpack_from('<i',b,after_gen+4)[0]
print(f"EngineVer={engineVer} CookerVer={cookerVer}")

# CompressionFlags + CompressedChunks
compFlagsOff = after_gen + 8
compFlags = struct.unpack_from('<i',b,compFlagsOff)[0]
chunkCount = struct.unpack_from('<i',b,compFlagsOff+4)[0]
print(f"CompressionFlags={compFlags} CompressedChunkCount={chunkCount}")

if chunkCount > 0 and chunkCount < 10000:
    print(f"Compressed chunks found - file IS chunked!")
    for ci in range(min(chunkCount, 5)):
        coff = compFlagsOff + 8 + ci * 16
        uncoff = struct.unpack_from('<i',b,coff)[0]
        uncsz  = struct.unpack_from('<i',b,coff+4)[0]
        cmpoff = struct.unpack_from('<i',b,coff+8)[0]
        cmpsz  = struct.unpack_from('<i',b,coff+12)[0]
        print(f"  chunk[{ci}]: uncOff={uncoff} uncSz={uncsz} cmpOff={cmpoff} cmpSz={cmpsz}")
else:
    print("No compression chunks - data is stored inline")
    # Best float3 run scan
    best = 0; bestoff = -1
    LIMIT = min(len(b), 4_000_000)
    i = 0
    while i < LIMIT - 36:
        try:
            v = struct.unpack_from('<fff',b,i)
        except:
            i += 4; continue
        if not all(math.isfinite(x) and (x == 0 or 1e-6 <= abs(x) <= 200000) for x in v):
            i += 4; continue
        run = 1
        for j in range(1,200):
            o2 = i + j*12
            if o2 + 12 > LIMIT: break
            try:
                v2 = struct.unpack_from('<fff',b,o2)
            except:
                break
            if not all(math.isfinite(x) and (x == 0 or 1e-6 <= abs(x) <= 200000) for x in v2):
                break
            run += 1
        if run > best:
            best = run; bestoff = i
        if best >= 20:
            break
        i += 4
    print(f"Best consecutive float3 run: {best} verts at offset {bestoff}")
    if bestoff >= 0 and best >= 4:
        for vi in range(min(best, 6)):
            x,y,z = struct.unpack_from('<fff',b,bestoff+vi*12)
            print(f"  v{vi}: ({x:.2f},{y:.2f},{z:.2f})")
        # look for index buffer after this
        dataEnd = bestoff + best*12
        print(f"Looking for index buffer after offset {dataEnd}...")
        for q in range(dataEnd & ~3, min(dataEnd + best*80 + 65536, len(b)-8), 4):
            n = struct.unpack_from('<i',b,q)[0]
            if 9 <= n <= 1_000_000 and n % 3 == 0:
                if q + 4 + n*2 <= len(b):
                    # check all indices < best
                    indices = struct.unpack_from(f'<{n}H',b,q+4)
                    if all(v < best for v in indices):
                        maxI = max(indices)
                        print(f"  idx (bare n={n}) at {q+4}: maxIdx={maxI} / {best}")
                        break
            if struct.unpack_from('<i',b,q)[0] == 2:
                n = struct.unpack_from('<i',b,q+4)[0]
                if 9 <= n <= 1_000_000 and n % 3 == 0 and q+8+n*2 <= len(b):
                    indices = struct.unpack_from(f'<{n}H',b,q+8)
                    if all(v < best for v in indices):
                        maxI = max(indices)
                        print(f"  idx (BulkSer n={n}) at {q+8}: maxIdx={maxI} / {best}")
                        break
