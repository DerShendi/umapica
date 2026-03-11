"""
Final validation: test Python LZO port for all 21 chunks in mafia_town.umap.
"""
import struct
import lzokay

UMAP = 'C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap'
M4_MAX_OFFSET = 0x4000

def u8(b, i): return b[i] & 0xFF

def copy_match(dst, dstOff, srcPos, n):
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
                t = u8(src,ip); ip+=1
                if t < 16:
                    mdist = 1+M4_MAX_OFFSET+(t>>2)+(u8(src,ip)<<2); ip+=1
                    np = copy_match(op, np, np-mdist, 3)
                    t = u8(src, ip-1)&3
                    if t:
                        for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                    continue
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
            else:
                mpos = np-1-(t>>2)-(u8(src,ip)<<2); ip+=1
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

# Known chunks (from header parse)
CHUNKS = [
    (816, 976035, 1152, 407156),
    (976851, 3856917, 408308, 3864747),
    (4833768, 7338309, 4273055, 7354048),
    (12172077, 6504800, 11627103, 6519017),
    (18676877, 276092, 18146120, 275149),
    (18952969, 4510253, 18421269, 4518577),
    (23463222, 2932874, 22939846, 2938643),
    (26396096, 3916561, 25878489, 3924544),
    (30312657, 349898, 29803033, 348060),
    (30662555, 1847970, 30151093, 1851268),
    (32510525, 531299, 32002361, 435965),
    (33041824, 1740196, 32438326, 1130842),
    (34782020, 549623, 33569168, 113988),
    (35331643, 1049141, 33683156, 859423),
    (36380784, 1049221, 34542579, 781960),
    (37430005, 8389303, 35324539, 4280056),
    (45819308, 1049221, 39604595, 821397),
    (46868529, 1049141, 40425992, 901058),
    (47917670, 1049166, 41327050, 762156),
    (48966836, 1049141, 42089206, 667026),
    (50015977, 3696, 42756232, 1842),
]

data = open(UMAP, 'rb').read()
total_pass = total_fail = 0
all_decomp = bytearray()

for ci, (uo, us, co, cs) in enumerate(CHUNKS):
    raw = bytes(data[co:co+cs])
    # Parse sub-block header
    hdr = 0
    magic = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
    assert magic == 0x9E2A83C1, f"bad magic chunk {ci}"
    blk_sz = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
    comp_tot = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
    uncomp_tot = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
    if blk_sz == 0: blk_sz = 131072
    num_sub = (uncomp_tot + blk_sz - 1) // blk_sz
    subs = []
    for s in range(num_sub):
        sc = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
        su = struct.unpack_from('<I', raw, hdr)[0]; hdr+=4
        subs.append((sc, su))
    
    chunk_ok = True
    for s, (sc, su) in enumerate(subs):
        sub_data = raw[hdr:hdr+sc]; hdr+=sc
        expected = lzokay.decompress(sub_data, su)
        try:
            result = decompress_lzo1x(sub_data, su)
            if result == expected:
                total_pass += 1
                all_decomp.extend(expected)
            else:
                total_fail += 1
                chunk_ok = False
                for bi in range(min(len(result),len(expected))):
                    if result[bi] != expected[bi]:
                        print(f"  chunk[{ci}] sub[{s}] FAIL byte {bi}: got {result[bi]:02X} exp {expected[bi]:02X}")
                        break
                all_decomp.extend(expected)
        except Exception as e:
            total_fail += 1; chunk_ok = False
            print(f"  chunk[{ci}] sub[{s}] EXCEPTION: {e}")
            import traceback; traceback.print_exc()
            all_decomp.extend(expected)
    
    status = 'OK' if chunk_ok else 'FAIL'
    print(f"chunk[{ci:2d}]: {num_sub:2d} subs {status}  uncompTot={uncomp_tot}")

print(f"\nPassed: {total_pass}  Failed: {total_fail}")
print(f"Total decompressed: {len(all_decomp)} bytes (expected ~{sum(us for _,us,_,_ in CHUNKS)})")
print(f"First 32: {' '.join(f'{b:02X}' for b in all_decomp[:32])}")
