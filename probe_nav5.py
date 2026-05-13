import struct, zstandard, os

def lo32(d,o): return struct.unpack_from("<I",d,o)[0]
def u16(d,o):  return struct.unpack_from("<H",d,o)[0]
def u32(d,o):  return struct.unpack_from("<I",d,o)[0]

for name in ["Lighthouse", "Tower", "PawnShop"]:
    path = f"D:/romfs/Model/{name}.bfres.zs"
    raw = zstandard.ZstdDecompressor().decompress(open(path,"rb").read(), max_output_size=50_000_000)
    fmdl_off = lo32(raw,0x28)
    fvtx_base = lo32(raw,fmdl_off+0x20)
    print(f"\n=== {name} ===")
    
    fo = fvtx_base
    bufArrPtr = lo32(raw, fo+0x10)
    attrArrPtr = lo32(raw, fo+0x08)
    vtxCnt = u32(raw,fo+0x50)
    
    print("FVTX[0] raw:")
    for off in range(0, 0x58, 8):
        a,b = u32(raw,fo+off), u32(raw,fo+off+4) if fo+off+8<=len(raw) else 0
        print(f"  +0x{off:02X}: {a:08X} {b:08X}")
    
    print(f"Buffer array @ 0x{bufArrPtr:X}:")
    for i in range(4):
        off = bufArrPtr + i*0x10
        if off+0x10 > len(raw): break
        a,b,c,d = [u32(raw,off+j*4) for j in range(4)]
        u16s = [u16(raw,off+j*2) for j in range(8)]
        print(f"  entry[{i}]: {a:08X} {b:08X} {c:08X} {d:08X}  u16:{u16s}")
    
    print(f"Attr array @ 0x{attrArrPtr:X}:")
    for i in range(8):
        off = attrArrPtr + i*0x10
        if off+0x10 > len(raw): break
        a,b,c,d = [u32(raw,off+j*4) for j in range(4)]
        if a == 0: break
        ns = ""
        try: ne=raw.index(b"\x00",a); ns=raw[a:ne].decode("utf-8","replace")
        except: pass
        print(f"  attr[{i}]: [{ns}] {a:08X} {b:08X} {c:08X} {d:08X}")
