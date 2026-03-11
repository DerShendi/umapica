"""Find index buffers first, then find positions before them."""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

with open('run/umapica/mafia_hq_mu_awakening.umap','rb') as f: raw = f.read()
pos=8; pos+=4
slen=ri32(raw,pos); pos+=4
if slen>0: pos+=slen
pos+=4; pos+=4+4; pos+=4+4; pos+=4+4
pos+=4+4+12+16; gc=ri32(raw,pos); pos+=4+gc*8+12
ru32(raw,pos); pos+=4; chunk_count=ri32(raw,pos); pos+=4
chunks=[]
for _ in range(chunk_count):
    uo=ru32(raw,pos); us=ru32(raw,pos+4); co=ru32(raw,pos+8); cs=ru32(raw,pos+12); pos+=16
    chunks.append((uo,us,co,cs))
total=sum(c[1] for c in chunks); D=bytearray(total); dest=0
for (uo,us,co,cs) in chunks:
    fp=co; fp+=4
    bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw,fp)); fp+=4; su2.append(ri32(raw,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw[fp:fp+cs2]; fp+=cs2
        D[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]; dest+=us2
BV = chunks[0][0]
print(f"Decomp: {total} bytes  BV={BV}")

EXPORTS = [
    ('standing_torch',     30739943, 42674),
    ('mafia_wall_light',   31348149, 104622),
    ('mafia_wooden_rail',  31486718, 140315),
]

def scan_idx_u16(D, phys, sz):
    end = phys + sz
    candidates = []
    for p in range(phys, end-6, 2):
        n = ri32(D, p)
        if 6 <= n <= 200000 and (n%3)==0 and p+4+n*2 <= end:
            max_i = 0
            ok = True
            for i in range(min(n, 32)):
                v = ru16(D, p+4+i*2)
                if v >= 65535: ok = False; break
                if v > max_i: max_i = v
            if ok and max_i >= 3:
                candidates.append((p-phys, n, max_i))
    candidates.sort(key=lambda x: -x[1])
    return candidates

def find_float3_strided(D, phys, sz, stride, nv, tolerance=0.5):
    """Try to find float3 positions at given stride and nVerts."""
    end = phys + sz
    results = []
    for start in range(phys, end - nv*stride, 4):
        ok = True
        for vi in range(nv):
            p = start + vi*stride
            if p+12 > end: ok=False; break
            x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
            if not (math.isfinite(x) and -1e6<x<1e6 and
                    math.isfinite(y) and -1e6<y<1e6 and
                    math.isfinite(z) and -1e6<z<1e6 and
                    max(abs(x),abs(y),abs(z)) > tolerance):
                ok=False; break
        if ok:
            results.append(start - phys)
    return results

for (name, virt_off, ser_size) in EXPORTS:
    phys = virt_off - BV
    print(f"\n{'='*60}")
    print(f"Export: {name}  phys={phys}  size={ser_size}")

    # Step 1: Find all index buffer candidates
    idxs = scan_idx_u16(D, phys, ser_size)
    print(f"  Index buffer candidates: {len(idxs)}")
    for (off, n, max_i) in idxs[:8]:
        first6 = [ru16(D, phys+off+4+i*2) for i in range(min(6,n))]
        print(f"    +{off}: n={n} max={max_i} first6={first6}")

    if not idxs:
        print("  No index buffers found!"); continue

    # Step 2: For best candidate, determine nVerts and look for positions before it
    best_off, best_n, best_max = idxs[0]
    nv_expected = best_max + 1
    idx_phys = phys + best_off
    print(f"\n  Best idx: +{best_off} n={best_n} max={best_max}")
    print(f"  Expected ~{nv_expected} verts")

    # Look for FPositionVertexBuffer header [stride:u32, nVerts:u32, data] before idx
    print("  Scanning for FPositionVertexBuffer header before idx:")
    search_start = phys
    search_end = idx_phys
    for p in range(search_start, search_end-8, 4):
        stride_v = ru32(D, p)
        nv_v = ru32(D, p+4)
        if stride_v == 12 and 5 < nv_v < 10000:
            data_start = p + 8
            data_end = data_start + nv_v * 12
            if data_end <= search_end:
                # Verify first few verts
                ok = True
                for vi in range(min(3, nv_v)):
                    fp = data_start + vi*12
                    x,y,z = rf(D,fp), rf(D,fp+4), rf(D,fp+8)
                    if not (math.isfinite(x) and math.isfinite(y) and math.isfinite(z)):
                        ok=False; break
                if ok:
                    off_from_start = p - phys
                    print(f"    +{off_from_start}: stride=12 nVerts={nv_v} -> data at +{data_start-phys}")
                    for vi in range(min(4, nv_v)):
                        fp = data_start + vi*12
                        print(f"      v[{vi}]=({rf(D,fp):.3f},{rf(D,fp+4):.3f},{rf(D,fp+8):.3f})")
        elif stride_v == 16 and 5 < nv_v < 10000:
            data_start = p + 8
            data_end = data_start + nv_v * 16
            if data_end <= search_end:
                ok = True
                for vi in range(min(3, nv_v)):
                    fp = data_start + vi*16
                    x,y,z = rf(D,fp), rf(D,fp+4), rf(D,fp+8)
                    if not (math.isfinite(x) and math.isfinite(y) and math.isfinite(z)):
                        ok=False; break
                if ok:
                    off_from_start = p - phys
                    print(f"    +{off_from_start}: stride=16 nVerts={nv_v} -> data at +{data_start-phys}")
                    for vi in range(min(4, nv_v)):
                        fp = data_start + vi*16
                        print(f"      v[{vi}]=({rf(D,fp):.3f},{rf(D,fp+4):.3f},{rf(D,fp+8):.3f})")

    # Show 64 bytes just before the idx buffer
    print(f"  Bytes -64..-0 from idx:")
    for dk in range(16, 0, -1):
        p = idx_phys - dk*4
        if p >= phys:
            vals_i = [ru32(D, p+i*4) for i in range(4)]
            vals_f = [rf(D, p+i*4) for i in range(4)]
            print(f"    -{dk*4:3d}: ints={vals_i} floats={[f'{v:.3f}' for v in vals_f]}")

print("\nDone.")
