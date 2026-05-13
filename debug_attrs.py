import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]

def read_cstr(raw, off):
    end = raw.index(b'\x00', off)
    return raw[off:end].decode('utf-8', errors='replace')

def check(name, raw):
    fmdl_off   = lo32(raw, 0x28)
    nfv        = u16(raw, fmdl_off + 0x68)
    nfs        = u16(raw, fmdl_off + 0x6A)
    fvtx_base  = lo32(raw, fmdl_off + 0x20)
    fshp_base  = lo32(raw, fmdl_off + 0x28)

    # face_bio / vertex_bio with RLT fallback
    fmaa_ptr = lo32(raw, 0x68)
    nmaa = 0
    while fmaa_ptr+nmaa*0x70+4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
        nmaa += 1
    hint     = u32(raw, fmaa_ptr+nmaa*0x70+0x08)
    rlt_off  = u32(raw,0x18); nsec = u32(raw,rlt_off+0x08)
    face_bio = vbio = None
    for s in range(nsec):
        so = rlt_off+0x10+s*0x10; sp = u32(raw,so); sa = u32(raw,so+4)
        if sp == hint and sa > 0: face_bio=sp; vbio=sp+sa; break
    if face_bio is None:
        for s in range(nsec):
            so = rlt_off+0x10+s*0x10; sp = u32(raw,so); sa = u32(raw,so+4)
            if sp > 0 and (sp & 0xFFF == 0) and sa > 0: face_bio=sp; vbio=sp+sa; break

    print(f"\n{'='*60}\n{name}: len={len(raw):,} nFVTX={nfv} nFSHP={nfs}")
    if face_bio: print(f"  face_bio=0x{face_bio:X}  vbio=0x{vbio:X}")

    for fi in range(min(nfv, 5)):
        fo         = fvtx_base + fi * 0x58
        vtxCnt     = u32(raw, fo + 0x50)
        bufOff     = u32(raw, fo + 0x48)
        attrArrPtr = lo32(raw, fo + 0x08)  # attribute array ptr
        bufArrPtr  = lo32(raw, fo + 0x10)  # buffer array ptr
        # numAttrs/numBuffers
        na = raw[fo+0x57] if fo+0x57 < len(raw) else 0  # try byte at +0x57
        nb = raw[fo+0x56] if fo+0x56 < len(raw) else 0  # try byte at +0x56

        # buf[0] layout: [u32 count, u32 strideUnits, u32 0xFFFFFFFF, u32 flags]
        # strideUnits * 4 = actual byte stride (empirically: Tower=3→12)
        stride_units = u32(raw, bufArrPtr+0x04) if bufArrPtr and bufArrPtr+8 <= len(raw) else 0
        stride = stride_units * 4  # hypothesis

        # Read attribute records to find position attribute ("_p0")
        # Attribute record (0x10 bytes): namePtr(u64) + bufIdx(u8)+offset(u8)+??(u16) + format(u32)
        # OR: namePtr(u64) + format(u32) + bufIdx(u16) + bufOffset(u16)
        pos_buf_idx   = -1
        pos_buf_off   = -1
        pos_format    = -1
        pos_attr_name = "?"
        if attrArrPtr:
            # Scan attributes until we find the position one
            # Try attribute record size = 0x10
            for ai in range(10):  # max 10 attrs
                aoff = attrArrPtr + ai * 0x10
                if aoff + 0x10 > len(raw): break
                name_ptr = lo32(raw, aoff)
                if name_ptr == 0 or name_ptr >= len(raw): break
                try:
                    attr_name = read_cstr(raw, name_ptr)
                except:
                    attr_name = "?"
                # Try two layouts for the rest of the attr:
                # Layout A: +0x08=bufIdx(u8)+something, +0x0C=format
                # Layout B: +0x08=format(u32), +0x0C=bufIdx(u16)+bufOff(u16)
                b8 = raw[aoff+0x08]
                b9 = raw[aoff+0x09]
                fmt_at_0c = u32(raw, aoff+0x0C)
                fmt_at_08 = u32(raw, aoff+0x08)
                print(f"  attr[{ai}] name='{attr_name}' +0x08:{b8:02X}{b9:02X}.. fmt@0C={fmt_at_0c:08X} fmt@08={fmt_at_08:08X}")
                if '_p0' in attr_name or 'position' in attr_name.lower() or ai == 0:
                    pos_attr_name = attr_name
                    pos_buf_idx   = b8   # if layout A: bufIdx = byte at +0x08
                    pos_buf_off   = b9   # if layout A: byte offset? (actually might be wrong)
                    pos_format    = fmt_at_0c

        # Actual vertex position with current approach (stride=12 always)
        vp = (vbio or 0) + bufOff
        if vbio and vp + 12 <= len(raw):
            x,y,z = struct.unpack_from('<fff', raw, vp)
            ok = all(abs(v) < 10000 for v in (x,y,z))
            print(f"  FVTX[{fi}] vtxCnt={vtxCnt} bufOff=0x{bufOff:X} stride_units={stride_units} (={stride}B)")
            print(f"    v[0]@0x{vp:X}: ({x:.4f},{y:.4f},{z:.4f}) plausible={ok}")
            # If stride != 12, try reading at stride
            if stride > 12:
                x1,y1,z1 = struct.unpack_from('<fff', raw, vp+stride) if vp+stride+12<=len(raw) else (0,0,0)
                print(f"    v[1]@stride{stride}: ({x1:.4f},{y1:.4f},{z1:.4f})")
        else:
            print(f"  FVTX[{fi}] vtxCnt={vtxCnt} bufOff=0x{bufOff:X} NO_VBIO_OR_OOB")


for name in ['Tower','PawnShop','Lighthouse','Market']:
    path = f'D:/romfs/Model/{name}.bfres.zs'
    if not os.path.exists(path): print(f"\n{name}: NOT FOUND"); continue
    try:
        raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
        check(name, raw)
    except Exception as e:
        import traceback; traceback.print_exc()
