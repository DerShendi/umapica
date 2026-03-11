"""
Verify Python LZO port for all 21 chunks in mafia_town.umap.
Uses hardcoded chunk offsets from previous header analysis.
"""
import struct
import lzokay

UMAP = 'C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap'
M4_MAX_OFFSET = 0x4000

def u8(b, i): return b[i] & 0xFF

def copy_match(dst, dstOff, srcPos, n):
    assert srcPos >= 0, f"srcPos={srcPos} dstOff={dstOff}"
    for _ in range(n):
        dst[dstOff] = dst[srcPos]
        dstOff += 1; srcPos += 1
    return dstOff

def decompress_lzo1x(src, uncomp_size):
    op = bytearray(uncomp_size)
    ip = 0; np = 0; t = 0
    go_to_inner = False

    if u8(src, ip) > 17:
        t = u8(src, ip) - 17; ip += 1
        for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
        if t < 4:
            t = u8(src, ip); ip += 1
            go_to_inner = True
        else:
            t = u8(src, ip); ip += 1
            if t < 16:
                mdist = 1 + M4_MAX_OFFSET + (t>>2) + (u8(src,ip)<<2); ip+=1
                np = copy_match(op, np, np-mdist, 3)
                t = u8(src, ip-1) & 3
                if t: 
                    for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                go_to_inner = False
            else:
                go_to_inner = True

    while True:
        if not go_to_inner:
            t = u8(src, ip); ip += 1
            if t < 16:
                if t == 0:
                    while u8(src,ip)==0: t+=255; ip+=1
                    t += 15 + u8(src,ip); ip+=1
                n = t+3
                for _ in range(n): op[np]=src[ip]; np+=1; ip+=1
                # first_literal_run
                t = u8(src,ip); ip+=1
                if t < 16:
                    mdist = 1+M4_MAX_OFFSET+(t>>2)+(u8(src,ip)<<2); ip+=1
                    np = copy_match(op, np, np-mdist, 3)
                    t = u8(src, ip-1)&3
                    if t:
                        for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                    continue  # back to outer
        go_to_inner = False

        while True:
            if t >= 64:
                mpos = np-1-((t>>2)&7)-(u8(src,ip)<<3); ip+=1
                mlen = (t>>5)-1
            elif t >= 32:
                mlen = t&31
                if mlen==0:
                    while u8(src,ip)==0: mlen+=255; ip+=1
                    mlen += 31+u8(src,ip); ip+=1
                mpos = np-1-(u8(src,ip)>>2)-(u8(src,ip+1)<<6); ip+=2
            elif t >= 16:
                mpos = np - ((t&8)<<11)
                mlen = t&7
                if mlen==0:
                    while u8(src,ip)==0: mlen+=255; ip+=1
                    mlen += 7+u8(src,ip); ip+=1
                mpos -= (u8(src,ip)>>2)+(u8(src,ip+1)<<6); ip+=2
                if mpos == np: return bytes(op[:np])
                mpos -= M4_MAX_OFFSET
                assert mpos >= 0, f"M4 OOB mpos={mpos} np={np}"
            else:
                mpos = np-1-(t>>2)-(u8(src,ip)<<2); ip+=1
                assert mpos >= 0, f"M1 OOB mpos={mpos} np={np}"
                op[np]=op[mpos]; np+=1; op[np]=op[mpos+1]; np+=1
                t = u8(src, ip-2)&3
                if t==0: break
                for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                t = u8(src,ip); ip+=1
                continue
            np = copy_match(op, np, mpos, mlen+2)
            t = u8(src, ip-2)&3
            if t==0: break
            for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
            t = u8(src,ip); ip+=1

data = open(UMAP, 'rb').read()

# Find chunks by scanning for the LZO block magic 0x9E2A83C1
# and reading sub-block headers
# Use the correct header parse from check_header2.py logic
# The chunk table is at the end of the standard UE3 header

# Get file size and parse header to find chunk table
import struct

f = open(UMAP, 'rb')
magic = struct.unpack('<I', f.read(4))[0]
fv4lic, fv4 = struct.unpack('<HH', f.read(4))
fv5 = struct.unpack('<I', f.read(4))[0]
pkg_flags = struct.unpack('<I', f.read(4))[0]
name_count = struct.unpack('<I', f.read(4))[0]
name_off = struct.unpack('<I', f.read(4))[0]
export_count = struct.unpack('<I', f.read(4))[0]
export_off = struct.unpack('<I', f.read(4))[0]
import_count = struct.unpack('<I', f.read(4))[0]
import_off = struct.unpack('<I', f.read(4))[0]
depends_off = struct.unpack('<I', f.read(4))[0]
soft_pkg_off = struct.unpack('<I', f.read(4))[0]
f.read(12)  # zeros
guid = f.read(16)
gen_count = struct.unpack('<I', f.read(4))[0]
print(f"gen_count={gen_count}")
for _ in range(gen_count):
    ec, nc = struct.unpack('<II', f.read(8))
engine_ver = struct.unpack('<I', f.read(4))[0]
cooker_ver = struct.unpack('<I', f.read(4))[0]
pkg_source = struct.unpack('<I', f.read(4))[0]
comp_flags = struct.unpack('<I', f.read(4))[0]
chunk_count = struct.unpack('<I', f.read(4))[0]
print(f"comp_flags=0x{comp_flags:08X} chunk_count={chunk_count}")

chunks = []
for i in range(chunk_count):
    uo = struct.unpack('<Q', f.read(8))[0]
    us = struct.unpack('<Q', f.read(8))[0]
    co = struct.unpack('<Q', f.read(8))[0]
    cs = struct.unpack('<Q', f.read(8))[0]
    chunks.append((uo, us, co, cs))
    print(f"  chunk[{i}]: uncompOff={uo} uncompSz={us} compOff={co} compSz={cs}")

f.close()

total_pass = 0; total_fail = 0
all_decomp = bytearray()

for ci, (uo, us, co, cs) in enumerate(chunks):
    raw = data[co:co+cs]
    hdr_off = 0
    blk_magic = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
    if blk_magic != 0x9E2A83C1:
        print(f"chunk[{ci}]: bad magic {blk_magic:#010x}"); continue
    blk_sz = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
    comp_tot = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
    uncomp_tot = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
    if blk_sz == 0: blk_sz = 131072
    num_sub = (uncomp_tot + blk_sz - 1) // blk_sz
    subs = []
    for s in range(num_sub):
        sc = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
        su = struct.unpack_from('<I', raw, hdr_off)[0]; hdr_off+=4
        subs.append((sc, su))
    
    chunk_ok = True
    for s, (sc, su) in enumerate(subs):
        sub_data = bytes(raw[hdr_off:hdr_off+sc]); hdr_off+=sc
        expected = lzokay.decompress(sub_data, su)
        try:
            result = decompress_lzo1x(sub_data, su)
            if result == expected:
                total_pass += 1
            else:
                total_fail += 1
                chunk_ok = False
                for bi in range(min(len(result), len(expected))):
                    if result[bi] != expected[bi]:
                        print(f"  chunk[{ci}] sub[{s}] FAIL at byte {bi}: got {result[bi]:02X} exp {expected[bi]:02X}")
                        break
        except Exception as e:
            total_fail += 1; chunk_ok = False
            print(f"  chunk[{ci}] sub[{s}] EXCEPTION: {e}")
        all_decomp.extend(expected)

    print(f"chunk[{ci}]: {num_sub} subs {'OK' if chunk_ok else 'FAIL'}")

print(f"\nTotal: {total_pass} subs passed, {total_fail} subs failed")
print(f"Total decompressed: {len(all_decomp)} bytes")
print(f"First 32: {' '.join(f'{b:02X}' for b in all_decomp[:32])}")
