import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]
def f32(d,o):  return struct.unpack_from('<f',d,o)[0]

# ---- Deep FSHP/FVTX dump for Tower ----
path = 'D:/romfs/Model/Tower.bfres.zs'
raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
print(f"Decompressed size: {len(raw):,}")

fmaa_ptr  = lo32(raw, 0x68)
fmdl_off  = lo32(raw, 0x28)
nfv       = u16(raw, fmdl_off + 0x68)
nfs       = u16(raw, fmdl_off + 0x6A)
fvtx_base = lo32(raw, fmdl_off + 0x20)
fshp_base = lo32(raw, fmdl_off + 0x28)
print(f"FMDL @ 0x{fmdl_off:X}: numFVTX={nfv} numFSHP={nfs}")
print(f"FVTX array @ 0x{fvtx_base:X}, FSHP array @ 0x{fshp_base:X}")

# FMAA and face_bio
nmaa = 0
while fmaa_ptr + nmaa*0x70 + 4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
    nmaa += 1
info_off  = fmaa_ptr + nmaa * 0x70
face_bio  = u32(raw, info_off + 0x08)
print(f"FMAA blocks={nmaa}, info_off=0x{info_off:X}, FACE_BIO=0x{face_bio:X}")

# RLT for vertex_bio
rlt_off = u32(raw, 0x18); nsec = u32(raw, rlt_off + 0x08)
vbio = None
for s in range(nsec):
    so = rlt_off + 0x10 + s*0x10
    sp = u32(raw,so); sa = u32(raw,so+4)
    if sp == face_bio and sa > 0:
        vbio = face_bio + sa
        print(f"  RLT section {s}: pos=0x{sp:X} arr=0x{sa:X} -> VERTEX_BIO=0x{vbio:X}")
        break
if vbio is None:
    print("  No RLT section matched face_bio!")

# Dump FVTX[0..2]
print()
for i in range(min(nfv, 3)):
    fo = fvtx_base + i * 0x58
    vtx_cnt   = u32(raw, fo + 0x50)
    buf_off   = u32(raw, fo + 0x48)
    # The buffer array ptr is at +0x08 in FVTX
    buf_arr   = lo32(raw, fo + 0x08)
    print(f"FVTX[{i}] @ 0x{fo:X}: vtxCnt={vtx_cnt} bufOff=0x{buf_off:X} bufArrPtr=0x{buf_arr:X}")
    if vbio:
        vp = vbio + buf_off
        if vp + 12 <= len(raw):
            x,y,z = struct.unpack_from('<fff', raw, vp)
            print(f"  vertex[0] @ 0x{vp:X}: ({x:.4f}, {y:.4f}, {z:.4f})")
        else:
            print(f"  vertex[0] @ 0x{vp:X}: OUT OF BOUNDS (raw len=0x{len(raw):X})")

# Dump FSHP[0..2]
print()
for i in range(min(nfs, 3)):
    fo = fshp_base + i * 0x60
    # dump raw bytes at this FSHP
    print(f"FSHP[{i}] @ 0x{fo:X}:")
    for off in range(0, 0x60, 8):
        vals = [f'{u32(raw,fo+off+j*4):08X}' for j in range(2) if fo+off+j*4+4<=len(raw)]
        print(f"  +0x{off:02X}: {' '.join(vals)}")
    mesh_arr = lo32(raw, fo + 0x1C)
    vtx_idx  = u16(raw, fo + 0x50)
    print(f"  -> meshArrPtr=0x{mesh_arr:X}, vtxBufIdx={vtx_idx}")
    if mesh_arr and mesh_arr + 0x30 <= len(raw):
        face_off = u32(raw, mesh_arr + 0x20)
        idx_cnt  = u32(raw, mesh_arr + 0x2C)
        print(f"  -> mesh: faceOff=0x{face_off:X} idxCnt={idx_cnt}")
        if face_bio:
            fp = face_bio + face_off
            if fp + 6 <= len(raw):
                i0,i1,i2 = struct.unpack_from('<HHH', raw, fp)
                print(f"  -> indices[0..2] = {i0},{i1},{i2}")
