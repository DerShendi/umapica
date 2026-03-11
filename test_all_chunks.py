"""
Verify that the Python port of the Java LZO algorithm decompresses ALL chunks
correctly (matching lzokay) for mafia_town.umap.
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
                    continue  # back to outer with fresh t
        go_to_inner = False

        # inner match loop
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
                if mpos == np: return bytes(op[:np])  # eof
                mpos -= M4_MAX_OFFSET
                assert mpos >= 0
            else:
                mpos = np-1-(t>>2)-(u8(src,ip)<<2); ip+=1
                assert mpos >= 0
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

# Parse full header to find all 21 chunks
off = 0
magic, = struct.unpack_from('<I', data, off); off += 4
fv4, fv4lic, fv5 = struct.unpack_from('<HHI', data, off); off += 8
pkg_flags, name_count, name_off, export_count, export_off, import_count, import_off = struct.unpack_from('<IIIIIII', data, off); off += 28
off += 8  # dependsOff, softPkgOff
off += 12 # zeros
off += 16 # GUID
gen_count, = struct.unpack_from('<I', data, off); off += 4
off += gen_count * 8  # generations (exportCount, nameCount each)
off += 4+4+4  # engineVer, cookerVer, packageSource
comp_flags, = struct.unpack_from('<I', data, off); off += 4
chunk_count, = struct.unpack_from('<I', data, off); off += 4

print(f"comp_flags=0x{comp_flags:08X} chunk_count={chunk_count}")
chunks = []
for i in range(chunk_count):
    uo, us, co, cs = struct.unpack_from('<QQQQ', data, off); off += 32
    chunks.append((uo, us, co, cs))
    print(f"  chunk[{i}]: uncompOff={uo} uncompSz={us} compOff={co} compSz={cs}")

total_pass = 0
total_fail = 0
all_decomp = bytearray()

for ci, (uo, us, co, cs) in enumerate(chunks):
    raw = data[co:co+cs]
    # Parse sub-block header
    hdr_off = 0
    blk_magic, = struct.unpack_from('<I', raw, hdr_off); hdr_off+=4
    blk_sz, comp_tot, uncomp_tot = struct.unpack_from('<III', raw, hdr_off); hdr_off+=12
    if blk_sz == 0: blk_sz = 131072
    num_sub = (uncomp_tot + blk_sz - 1) // blk_sz
    subs = []
    for s in range(num_sub):
        sc, su = struct.unpack_from('<II', raw, hdr_off); hdr_off+=8
        subs.append((sc, su))
    # Decompress subs
    chunk_decomp = bytearray()
    ok = True
    for s, (sc, su) in enumerate(subs):
        sub_data = raw[hdr_off:hdr_off+sc]; hdr_off+=sc
        expected = lzokay.decompress(sub_data, su)
        result = decompress_lzo1x(bytes(sub_data), su)
        if result == expected:
            total_pass += 1
        else:
            total_fail += 1
            for bi in range(min(len(result), len(expected))):
                if result[bi] != expected[bi]:
                    print(f"  chunk[{ci}] sub[{s}] FAIL at byte {bi}: got {result[bi]:02X} exp {expected[bi]:02X}")
                    ok = False
                    break
        chunk_decomp.extend(expected)
    print(f"chunk[{ci}]: {num_sub} subs {'OK' if ok else 'FAIL'}, decomp={len(chunk_decomp)}")
    all_decomp.extend(chunk_decomp)

print(f"\nTotal: {total_pass} passed, {total_fail} failed")
print(f"Total decompressed: {len(all_decomp)} bytes")
print(f"First 32: {' '.join(f'{b:02X}' for b in all_decomp[:32])}")
