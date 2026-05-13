import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]
def f32(d,o):  return struct.unpack_from('<f',d,o)[0]

def dump_rlt_and_bufinfo(name):
    path = f'D:/romfs/Model/{name}.bfres.zs'
    raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
    fmaa_ptr = lo32(raw, 0x68)
    nmaa = 0
    while fmaa_ptr+nmaa*0x70+4 < len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4] == b'FMAA':
        nmaa += 1
    info_off = fmaa_ptr + nmaa * 0x70
    
    print(f"\n=== {name} (fmaa_ptr=0x{fmaa_ptr:X}, nmaa={nmaa}, info_off=0x{info_off:X}) ===")
    print(f"  BufferInfo bytes @ 0x{info_off:X}: {raw[info_off:info_off+0x20].hex()}")
    print(f"  BUFINFO offsets: +0x00={u32(raw,info_off):08X} +0x04={u32(raw,info_off+4):08X} +0x08={u32(raw,info_off+8):08X} +0x0C={u32(raw,info_off+0xC):08X}")
    
    face_bio = u32(raw, info_off + 0x08)
    print(f"  face_bio=0x{face_bio:X} (len=0x{len(raw):X})")
    
    # Print RLT sections
    rlt_off = u32(raw, 0x18); nsec = u32(raw, rlt_off + 0x08)
    print(f"  RLT @ 0x{rlt_off:X}: {nsec} sections")
    for s in range(nsec):
        so = rlt_off + 0x10 + s * 0x10
        sp = u32(raw,so); sa = u32(raw,so+4); sb = u32(raw,so+8); sc = u32(raw,so+0xC)
        match = " *** FACE_BIO MATCH ***" if sp == face_bio else ""
        print(f"    [{s:2d}] pos=0x{sp:X} arr=0x{sa:X} 0x{sb:X} 0x{sc:X}{match}")

# Also investigate PawnShop vertex issue - check buffer stride
def dump_fvtx_buffers(name, fvtx_idx=0):
    path = f'D:/romfs/Model/{name}.bfres.zs'
    raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
    fmdl_off  = lo32(raw, 0x28)
    fvtx_base = lo32(raw, fmdl_off + 0x20)
    fo = fvtx_base + fvtx_idx * 0x58
    print(f"\n=== {name} FVTX[{fvtx_idx}] @ 0x{fo:X} ===")
    print(f"  Raw bytes: {raw[fo:fo+0x58].hex()}")
    # buffer array ptr at +0x14 (not +0x08!)
    buf_arr_ptr = lo32(raw, fo + 0x14)
    num_bufs = u16(raw, fo + 0x54 + 2)  # numBuffers is high u16 at +0x54
    num_attrs = u16(raw, fo + 0x54)
    print(f"  bufArrPtr=0x{buf_arr_ptr:X} numAttrs={num_attrs} numBuffers={num_bufs}")
    if buf_arr_ptr and buf_arr_ptr + num_bufs * 0x10 <= len(raw):
        for b in range(min(num_bufs, 4)):
            boff = buf_arr_ptr + b * 0x10
            raw_off = u32(raw, boff)
            stride  = u32(raw, boff + 4)
            print(f"    buffer[{b}]: rawOffset=0x{raw_off:X} stride={stride}")

for name in ['Market', 'Park']:
    dump_rlt_and_bufinfo(name)

for name in ['PawnShop', 'Supermarket']:
    dump_fvtx_buffers(name, 0)
    dump_fvtx_buffers(name, 2)
