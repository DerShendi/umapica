import struct, zstandard, os

MODELS = ['Lighthouse','Market','ItemShop','FamilyRestaurant','Supermarket',
          'PawnShop','ClockTower','Park','FerrisWheelFrame','Tower']

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]

def analyze(name):
    path = f'D:/romfs/Model/{name}.bfres.zs'
    if not os.path.exists(path): return 'NOT FOUND'
    raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
    if raw[:4] != b'FRES': return 'BAD MAGIC'
    fmaa_ptr = lo32(raw,0x68)
    if fmaa_ptr == 0: return 'NO FMAA'
    fmdl_off = lo32(raw,0x28)
    nfv = u16(raw,fmdl_off+0x68); nfs = u16(raw,fmdl_off+0x6A)
    fvtx_base = lo32(raw,fmdl_off+0x20); fshp_base = lo32(raw,fmdl_off+0x28)
    nmaa = 0
    while fmaa_ptr+nmaa*0x70+4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
        nmaa += 1
    face_bio = u32(raw, fmaa_ptr+nmaa*0x70+0x08)
    rlt_off = u32(raw,0x18); nsec = u32(raw,rlt_off+0x08)
    vbio = None
    for s in range(nsec):
        so = rlt_off+0x10+s*0x10; sp = u32(raw,so); sa = u32(raw,so+4)
        if sp == face_bio and sa > 0: vbio = face_bio+sa; break
    tv = sum(u32(raw,fvtx_base+i*0x58+0x50) for i in range(nfv))
    ti = sum(u32(raw,lo32(raw,fshp_base+i*0x60+0x1C)+0x2C) for i in range(nfs))
    # buffer array ptr at fvtx+0x08, stride is at buf_entry+4
    bap = lo32(raw, fvtx_base+0x08)
    buf_stride = u32(raw,bap+4) if bap and bap+8 <= len(raw) else -1
    # first vertex xyz using stride=12
    if vbio:
        vbo = u32(raw,fvtx_base+0x48); vp = vbio+vbo
        xyz = struct.unpack_from('<fff',raw,vp) if vp+12 <= len(raw) else None
    else:
        xyz = None
    return f'FMAA={nmaa} FVTX={nfv} FSHP={nfs} verts={tv} idx={ti} bufStride={buf_stride} xyz0={xyz}'

for m in MODELS:
    try:
        r = analyze(m)
    except Exception as e:
        r = f'ERR: {e}'
    print(f'{m}: {r}')
