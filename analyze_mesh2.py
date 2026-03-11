"""Analyze StaticMesh export binary: find vertex positions + index buffers."""
import struct, math, lzokay

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

# ---- Decompress (proven pattern) ----
with open('run/umapica/mafia_town.umap','rb') as f: raw = f.read()
MAGIC = 0x9E2A83C1
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
print(f"Decomp: {total} bytes")

EXPORTS = [
    ('mole_man',              32516839, 262170),
    ('harbour_magma_platform',33020744,  15770),
    ('Moon',                  32875124,  31257),
    ('Skydome',               32845387,  29737),
]

def find_float3_runs(D, start, end):
    """Find runs of spatially plausible float3 values. Returns list of (offset, run_scan_count)."""
    runs=[]
    rstart=-1; rcnt=0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        ok = all(-200000<v<200000 and math.isfinite(v) for v in (x,y,z))
        has_mag = max(abs(x),abs(y),abs(z)) > 0.5
        not_sub = all(v==0.0 or abs(v)>1e-10 for v in (x,y,z))
        if ok and has_mag and not_sub:
            if rstart<0: rstart=p; rcnt=1
            else: rcnt+=1
        else:
            if rcnt>=9: runs.append((rstart,rcnt))
            rstart=-1; rcnt=0
    if rcnt>=9: runs.append((rstart,rcnt))
    return runs

def find_tarray_u16(D, start, end):
    """Find TArray<uint16> = [int32 count] + [uint16 * count]. Returns list of (offset, count)."""
    results=[]
    p=start
    while p < end-8:
        n=ri32(D,p)
        if 6 <= n <= 500000:
            # Quick check: first 6 uint16 all < 500000 and fit in export
            if p+4+n*2 <= end:
                ok=True; max_i=0
                for i in range(min(n, 8)):
                    v=ru16(D, p+4+i*2)
                    if v >= 65535: ok=False; break
                    max_i=max(max_i,v)
                if ok and max_i >= 3:
                    results.append((p, n, max_i))
        p+=4
    return results

for (name, ser_off, ser_size) in EXPORTS:
    prop_end = ser_off - BV          # phys index
    export_end = prop_end + ser_size
    print(f"\n{'='*60}")
    print(f"Export: {name}  propEnd_phys={prop_end}  size={ser_size}")

    # Find float3 runs
    runs = find_float3_runs(D, prop_end, export_end)
    print(f"  Float3 runs ({len(runs)} total):")
    # Show top 3 by run count
    for ri,(rp,rc) in enumerate(sorted(runs, key=lambda x:-x[1])[:5]):
        off = rp - prop_end
        nverts = rc // 3  # approximate
        print(f"    run[{ri}] +{off} : scan_count={rc} (~{nverts} verts)")
        for vi in range(min(2, nverts)):
            rfp = rp + vi*12
            print(f"      v[{vi}]=({rf(D,rfp):.2f},{rf(D,rfp+4):.2f},{rf(D,rfp+8):.2f})")

    # Find index buffers
    idx_arrays = find_tarray_u16(D, prop_end, export_end)
    # Filter: keep only those where count is divisible by 3 (triangle list) 
    # and max_idx looks reasonable
    print(f"  TArray<u16> candidates ({len(idx_arrays)} total):")
    for (ip, n, max_i) in sorted(idx_arrays, key=lambda x:x[1], reverse=True)[:5]:
        off = ip - prop_end
        first6=[ru16(D, ip+4+i*2) for i in range(min(6,n))]
        is_tri = (n % 3 == 0)
        print(f"    +{off}: count={n} max_idx={max_i} div3={is_tri} first6={first6}")

print("\nDone.")
