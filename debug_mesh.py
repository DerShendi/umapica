import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]
def f32(d,o):  return struct.unpack_from('<f',d,o)[0]

def check_mesh_struct(raw, fshp_base, face_bio):
    """Verify mesh struct layout for first FSHP using corrected offset 0x18."""
    fshp0 = fshp_base
    mesh_arr = lo32(raw, fshp0 + 0x18)  # corrected from 0x1C
    print(f"  FSHP[0] meshArrPtr @ +0x18 = 0x{mesh_arr:X}")
    if mesh_arr and mesh_arr + 0x30 <= len(raw):
        print(f"  Mesh bytes: {raw[mesh_arr:mesh_arr+0x30].hex()}")
        face_off  = u32(raw, mesh_arr + 0x20)
        idx_fmt   = u32(raw, mesh_arr + 0x28)
        idx_cnt   = u32(raw, mesh_arr + 0x2C)
        print(f"  Mesh: faceOff=0x{face_off:X} idxFmt={idx_fmt} idxCnt={idx_cnt}")
        fp = face_bio + face_off
        if fp + 6 <= len(raw):
            i0,i1,i2 = struct.unpack_from('<HHH', raw, fp)
            print(f"  First triangle indices: {i0},{i1},{i2}")
    else:
        print(f"  Bad meshArr or out of bounds!")

def check_vertex_issue(name, raw):
    """Check why vertex coords may be wrong."""
    fmdl_off  = lo32(raw, 0x28)
    nfv       = u16(raw, fmdl_off + 0x68)
    fvtx_base = lo32(raw, fmdl_off + 0x20)
    
    # FMAA/face_bio/vertex_bio
    fmaa_ptr = lo32(raw, 0x68)
    nmaa = 0
    while fmaa_ptr + nmaa*0x70 + 4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
        nmaa += 1
    face_bio = u32(raw, fmaa_ptr + nmaa*0x70 + 0x08)
    rlt_off = u32(raw, 0x18); nsec = u32(raw, rlt_off + 0x08)
    vbio = None
    face_pool_size = 0
    for s in range(nsec):
        so = rlt_off + 0x10 + s * 0x10
        sp = u32(raw, so); sa = u32(raw, so + 4)
        if sp == face_bio and sa > 0:
            face_pool_size = sa
            vbio = face_bio + sa
            break
    
    print(f"\n=== {name} ===")
    print(f"  len={len(raw):,}, fmaa_ptr=0x{fmaa_ptr:X}, nmaa={nmaa}")
    vbio_str = f"0x{vbio:X}" if vbio else "None"
    print(f"  face_bio=0x{face_bio:X}, face_pool_size=0x{face_pool_size:X}, vbio={vbio_str}")
    
    for i in range(min(nfv, 4)):
        fo = fvtx_base + i * 0x58
        vtx_cnt = u32(raw, fo + 0x50)
        buf_off = u32(raw, fo + 0x48)
        vp = (vbio or 0) + buf_off
        status = "OK" if vbio and vp + 12 <= len(raw) else f"OOB(0x{vp:X}>0x{len(raw):X})"
        if vbio and vp + 12 <= len(raw):
            x,y,z = struct.unpack_from('<fff', raw, vp)
            coord = f"({x:.3f},{y:.3f},{z:.3f})"
        else:
            coord = "N/A"
        print(f"  FVTX[{i}]: vtxCnt={vtx_cnt} bufOff=0x{buf_off:X} vp=0x{vp:X} {status} {coord}")

# 1) Verify mesh struct fix for Tower
print("=== TOWER mesh struct verification ===")
raw = zstandard.ZstdDecompressor().decompress(open('D:/romfs/Model/Tower.bfres.zs','rb').read(), max_output_size=50_000_000)
fmdl_off = lo32(raw, 0x28)
fshp_base = lo32(raw, fmdl_off + 0x28)
fmaa_ptr = lo32(raw, 0x68)
nmaa = 0
while fmaa_ptr+nmaa*0x70+4<len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4]==b'FMAA': nmaa+=1
face_bio = u32(raw, fmaa_ptr+nmaa*0x70+0x08)
check_mesh_struct(raw, fshp_base, face_bio)

# 2) Check problematic models
for name in ['Market', 'Park', 'PawnShop', 'Supermarket']:
    path = f'D:/romfs/Model/{name}.bfres.zs'
    if not os.path.exists(path): print(f"{name}: NOT FOUND"); continue
    raw2 = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
    check_vertex_issue(name, raw2)
