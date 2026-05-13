import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]

def check(name, raw, fi=0):
    fmdl_off   = lo32(raw, 0x28)
    fvtx_base  = lo32(raw, fmdl_off + 0x20)
    fo         = fvtx_base + fi * 0x58
    vtxCnt     = u32(raw, fo + 0x50)
    bufOff     = u32(raw, fo + 0x48)
    attrArrPtr = lo32(raw, fo + 0x08)
    bufArrPtr  = lo32(raw, fo + 0x10)  # correct: +0x10, NOT +0x08

    # Determine face_bio / vertex_bio via RLT (with fallback)
    fmaa_ptr = lo32(raw, 0x68)
    nmaa = 0
    while fmaa_ptr+nmaa*0x70+4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
        nmaa += 1
    hint = u32(raw, fmaa_ptr+nmaa*0x70+0x08)
    rlt_off = u32(raw,0x18); nsec = u32(raw,rlt_off+0x08)
    face_bio = vbio = None
    # Try hint match first
    for s in range(nsec):
        so = rlt_off+0x10+s*0x10; sp = u32(raw,so); sa = u32(raw,so+4)
        if sp == hint and sa > 0: face_bio = sp; vbio = sp+sa; break
    # Fallback: first page-aligned section with pos>0 and arr>0
    if face_bio is None:
        for s in range(nsec):
            so = rlt_off+0x10+s*0x10; sp = u32(raw,so); sa = u32(raw,so+4)
            if sp > 0 and (sp & 0xFFF == 0) and sa > 0: face_bio = sp; vbio = sp+sa; break

    print(f"\n{name} FVTX[{fi}] @ 0x{fo:X}: vtxCnt={vtxCnt} bufOff=0x{bufOff:X}")
    print(f"  attrArrPtr=0x{attrArrPtr:X}  bufArrPtr=0x{bufArrPtr:X}")
    fb_str = f"0x{face_bio:X}" if face_bio else "None"
    vb_str = f"0x{vbio:X}" if vbio else "None"
    print(f"  hint=0x{hint:X}  face_bio={fb_str}  vbio={vb_str}")

    # Dump buffer array (format: each entry = u64 gpuAddr + u32 size + u32 stride)
    if bufArrPtr and bufArrPtr+0x40 <= len(raw):
        print(f"  Buffer array raw (3 entries × 0x10 bytes):")
        for b in range(3):
            off = bufArrPtr + b*0x10
            if off+0x10 > len(raw): break
            d0,d1,d2,d3 = [u32(raw, off+j*4) for j in range(4)]
            print(f"    buf[{b}]: {d0:08X} {d1:08X} {d2:08X} {d3:08X}")
        # Try layout A: (size u32, stride u32, count u32, pad u32)
        sizeA  = u32(raw, bufArrPtr+0x00)
        strA   = u32(raw, bufArrPtr+0x04)
        # Try layout B: (gpuAddr lo32, gpuAddr hi32, size u32, stride u32)
        sizeB  = u32(raw, bufArrPtr+0x08)
        strB   = u32(raw, bufArrPtr+0x0C)
        print(f"  Layout A: size={sizeA} stride={strA}  →  size/vtxCnt={sizeA/vtxCnt:.2f}")
        print(f"  Layout B: size={sizeB} stride={strB}  →  size/vtxCnt={sizeB/vtxCnt:.2f}")
        if strA > 0: print(f"  Layout A derived stride = {strA}")
        if strB > 0: print(f"  Layout B derived stride = {strB}")

    # Also check attribute array first entry (name ptr, bufIdx, bufOff, format)
    if attrArrPtr and attrArrPtr+0x10 <= len(raw):
        print(f"  Attr[0] bytes: {raw[attrArrPtr:attrArrPtr+0x10].hex()}")
        a0,a1,a2,a3 = [u32(raw,attrArrPtr+j*4) for j in range(4)]
        print(f"  Attr[0]: namePtr_lo=0x{a0:X} namePtr_hi=0x{a1:X} bufIdx=0x{a2:X} format=0x{a3:X}")
        # Try attr size 0x18 or 0x14 or 0x10
        # Alternative layout: namePtr(u64) + bufIdx(u16) + bufOffset(u16) + format(u32) + pad
        bi  = u16(raw, attrArrPtr+0x08)
        bof = u16(raw, attrArrPtr+0x0A)
        fmt = u32(raw, attrArrPtr+0x0C)
        print(f"  Attr[0] alt: bufIdx={bi} bufOffset={bof} format=0x{fmt:X}")

    # First vertex at (vbio+bufOff) using stride 12 vs stride from layout A/B
    if vbio:
        vp = vbio + bufOff
        if vp+12 <= len(raw):
            x,y,z = struct.unpack_from('<fff', raw, vp)
            print(f"  vertex[0] @0x{vp:X} (stride=12): ({x:.4f},{y:.4f},{z:.4f})")
        if bufArrPtr and bufArrPtr+0x10 <= len(raw):
            strA = u32(raw, bufArrPtr+0x04)
            if strA > 0 and strA != 12 and vp+12 <= len(raw):
                x,y,z = struct.unpack_from('<fff', raw, vp)
                print(f"  vertex[0] same XYZ since position at offset 0 within stride={strA} record")
                # Check if XYZ makes sense (all within plausible range)
                ok = all(abs(v) < 1000 for v in (x,y,z))
                print(f"  XYZ plausible: {ok}")


for name in ['Tower','PawnShop','Supermarket','Lighthouse','Market','Park','FamilyRestaurant','ClockTower']:
    path = f'D:/romfs/Model/{name}.bfres.zs'
    if not os.path.exists(path): print(f"\n{name}: NOT FOUND"); continue
    try:
        raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
        check(name, raw, 0)
    except Exception as e:
        print(f"\n{name}: ERR {e}")
