"""
probe_nav5.py – Trace de_dust2.nav with correct KV3 v5 layout to verify Java fix.
Extracts nav from single-file VPK.
"""
import struct, os

VPK_PATH = r"run\umapica\source maps\csgo_community_addons\de_dust2.vpk"

def u32(d, p): return struct.unpack_from('<I', d, p)[0]
def i32(d, p): return struct.unpack_from('<i', d, p)[0]
def f32(d, p): return struct.unpack_from('<f', d, p)[0]

# ── Extract nav from VPK ──────────────────────────────────────────────
with open(VPK_PATH, "rb") as f:
    vpk = f.read()

tree_size = u32(vpk, 8)
data_start = 28 + tree_size

pos = 28
nav = None
while pos < 28 + tree_size:
    ext_end  = vpk.index(b'\x00', pos);  ext = vpk[pos:ext_end].decode('latin-1'); pos = ext_end+1
    if not ext: break
    while True:
        dir_end  = vpk.index(b'\x00', pos);  dir_ = vpk[pos:dir_end].decode('latin-1'); pos = dir_end+1
        if not dir_: break
        while True:
            fn_end = vpk.index(b'\x00', pos); fn = vpk[pos:fn_end].decode('latin-1'); pos = fn_end+1
            if not fn: break   # end of this dir's files, read BEFORE extracting struct
            crc, pb, idx, off, length = struct.unpack_from('<IHHII', vpk, pos); pos += 18
            full = (dir_+'/'+fn+'.'+ext) if dir_ != ' ' else (fn+'.'+ext)
            if full == 'maps/de_dust2.nav':
                assert idx == 0x7FFF, f"External archive {idx}"
                nav = vpk[data_start+off : data_start+off+length]
            pos += pb  # skip inline preload bytes

assert nav is not None, "maps/de_dust2.nav not found"
print(f"NAV size: {len(nav)} bytes")

p = 0
def rd_u32():
    global p; v = u32(nav, p); p+=4; return v
def rd_i32():
    global p; v = i32(nav, p); p+=4; return v
def rd_f32():
    global p; v = f32(nav, p); p+=4; return v
def rd_u16():
    global p; v = struct.unpack_from('<H', nav, p)[0]; p+=2; return v
def rd_byte():
    global p; v = nav[p]; p+=1; return v
def skip(n):
    global p; p+=n
def align8():
    global p
    rem = p & 7
    if rem: p += 8 - rem

# ── NAV Header ────────────────────────────────────────────────────────
magic   = rd_u32(); ver = rd_u32(); subver = rd_u32(); unk1 = rd_u32()
assert magic == 0xFEEDFACE
print(f"NAV: version={ver} subVersion={subver} unk1=0x{unk1:08X}")

# ── skipKv3 (v5 layout) ───────────────────────────────────────────────
def skip_kv3(label):
    global p
    align8()
    start = p
    magic = rd_u32()
    assert (magic & 0xFFFFFF00) == 0x4B563300, f"{label}: bad magic 0x{magic:08X} @ {start}"
    version = magic & 0xFF
    print(f"\n{label} @ {start}: KV3 v{version}")
    skip(16)  # Format GUID
    comp = rd_u32()
    print(f"  compMethod={comp}")
    # common fields (v2-v5)
    skip(4)   # dictId+frameSize
    skip(12)  # countBytes1/4/8
    skip(4)   # countTypes
    skip(4)   # countObjects/Arrays
    suc_buf1 = rd_i32()  # sizeUncompTotal
    sct_total = rd_i32() # sizeCompTotal
    cnt_blks  = rd_i32() # countBlocks
    sz_blobs  = rd_i32() # sizeBinaryBlobs
    print(f"  sizeUncTotal={suc_buf1} sizeCmpTotal={sct_total} countBlocks={cnt_blks} sizeBlobs={sz_blobs}")
    if version >= 4:
        skip(8)   # countBytes2 + sizeBlockCompressedSizes
    suf_unc1 = suc_buf1; scf_cmp1 = sct_total
    suf_unc2 = 0; scf_cmp2 = 0
    if version >= 5:
        suf_unc1 = rd_i32(); scf_cmp1 = rd_i32()
        suf_unc2 = rd_i32(); scf_cmp2 = rd_i32()
        skip(32)  # 8 more fields
        print(f"  (v5) sizeUncBuf1={suf_unc1} sizeCmpBuf1={scf_cmp1} sizeUncBuf2={suf_unc2} sizeCmpBuf2={scf_cmp2}")
    header_end = p
    print(f"  header_end={header_end}")
    # Data section
    if comp == 0:
        skip(suf_unc1)
        if version >= 5:
            skip(suf_unc2)
        if cnt_blks > 0:
            skip(sz_blobs)
    elif comp == 1:  # LZ4
        skip(scf_cmp1)
        if version >= 5:
            skip(scf_cmp2)
            if cnt_blks > 0:
                blobs_cmp = sct_total - scf_cmp1 - scf_cmp2
                if blobs_cmp > 0: skip(blobs_cmp)
    elif comp == 2:  # ZSTD
        if version < 5:
            skip(scf_cmp1)
        else:
            skip(scf_cmp1); skip(scf_cmp2)
            if cnt_blks > 0:
                blobs_cmp = sct_total - scf_cmp1 - scf_cmp2
                if blobs_cmp > 0: skip(blobs_cmp)
    # Trailer (0xFFEEDD00) is only a SEPARATE stream read when countBlocks > 0.
    # When countBlocks == 0 it is embedded within the buffer data itself.
    if cnt_blks > 0:
        skip(4)  # trailer
    print(f"  block_end={p}  (total={p-start} bytes)")

# Parse
if ver >= 36: skip_kv3("KV3Unknown1")

print(f"\nPolygon pool @ pos={p}")
corner_count = rd_u32()
print(f"  cornerCount={corner_count}")
corners = []
for _ in range(corner_count):
    x, y, z = rd_f32(), rd_f32(), rd_f32()
    corners.append((x, y, z))
print(f"  First 3 corners: {corners[:3]}")

poly_count = rd_u32()
polygons = []
print(f"  polyCount={poly_count}")
for i in range(poly_count):
    n = rd_byte()
    idxs = [rd_u32() for _ in range(n)]
    if ver >= 35: skip(4)
    polygons.append([corners[k] for k in idxs])
print(f"  After polygon pool: pos={p}")

if ver >= 32: skip(4)  # unk2

unk_count1 = 0
if ver >= 35:
    unk_count1 = rd_u32()
    print(f"\nunkCount1={unk_count1} @ pos={p-4}")
    for i in range(unk_count1):
        s_start = p
        while nav[p] != 0: p += 1
        s = nav[s_start:p].decode('ascii','replace'); p += 1
        skip(48)
        print(f"  item[{i}]: '{s}'")
    print(f"  After unkCount1: pos={p}")

if ver >= 36: skip_kv3("KV3Unknown2")

area_count = rd_u32()
print(f"\nareaCount={area_count}")

tri_count = 0
for a in range(area_count):
    skip(4)   # AreaId
    skip(8)   # DynamicAttributeFlags
    skip(1)   # HullIndex
    if ver >= 31:
        poly_idx = rd_i32()
        crns = polygons[poly_idx] if 0 <= poly_idx < len(polygons) else []
    else:
        n = rd_i32()
        crns = [(rd_f32(), rd_f32(), rd_f32()) for _ in range(n)]
    skip(4)   # float~0
    for _ in range(len(crns)):
        cc = rd_u32(); skip(cc * 8)
    skip(1)   # unk2 byte
    skip(4)   # unk3 uint32
    la = rd_u32(); skip(la * 4)
    lb = rd_u32(); skip(lb * 4)
    if len(crns) >= 3:
        tri_count += len(crns) - 2

print(f"Triangles from fan triangulaion: {tri_count}")
print(f"  pos after areas: {p}")
lc = rd_u32()
print(f"ladderCount={lc}  pos={p}")
if ver >= 35: skip(lc * 68)
else: skip(lc * 60)
print(f"  pos after ladders: {p}")
uc2 = rd_u32(); skip(uc2 * 18 * 4)
print(f"unkCount2={uc2}  pos after unkCount2 data: {p}")

# ── GenerationParams ──────────────────────────────────────────────────
# NavMeshGenerationParams.Read()
ngv = rd_i32()  # NavGenVersion
skip(4)   # UseProjectDefaults
skip(12)  # TileSize, CellSize, CellHeight
skip(8)   # MinRegionSize, MergedRegionSize
skip(8)   # MeshSampleDistance, MaxSampleError
skip(12)  # MaxEdgeLength, MaxEdgeError, VertsPerPoly
if ngv >= 7:  skip(4)   # SmallAreaOnEdgeRemoval
if ngv >= 12:
    while nav[p]: p+=1
    p+=1  # skip NUL (HullPresetName)
    while nav[p]: p+=1
    p+=1  # skip NUL (HullDefinitionsFile)
hc = rd_i32()  # HullCount
hulls_to_read = max(hc, 3) if ngv <= 11 else hc
for _ in range(hulls_to_read):
    if ngv >= 9:  skip(1)  # Enabled
    skip(8)   # Radius, Height
    if ngv >= 9:  skip(5)  # ShortHeightEnabled (byte), ShortHeight (f)
    if ngv >= 13: skip(5)  # unk byte + unk float
    skip(20)  # MaxClimb, MaxSlope, MaxJumpDownDist, MaxJumpHorizDistBase, MaxJumpUpDist
    if ngv >= 11: skip(4)  # BorderErosion
if ngv >= 12: skip(1)  # unkByte
print(f"GenerationParams: navGenVersion={ngv} hullCount={hc}  pos after: {p}")
if ver >= 36: skip_kv3("KV3Unknown3")
if subver > 0: skip_kv3("CustomData")
print(f"\nFinal pos={p}  nav size={len(nav)}  match={p == len(nav)}")
