"""Diagnose failing mesh exports: standing_torch, mafia_wall_light, mafia_wooden_rail."""
import struct, math, lzokay

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

# ---- Decompress ----
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
    ('standing_torch',         30739943,  42674),
    ('mafia_wall_light',       31348149, 104622),
    ('mafia_wooden_rail',      31486718, 140315),
    ('cardboard_roundBush',    31689455,   4762),
    ('cardboard_plant',        31681108,   4542),
    ('mafia_theatre_podium_med',31323921, 12113),
]

def find_float3_runs(D, start, end, min_mag=0.001):
    runs=[]; rstart=-1; rcnt=0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        ok = (math.isfinite(x) and -200000<x<200000
          and math.isfinite(y) and -200000<y<200000
          and math.isfinite(z) and -200000<z<200000
          and max(abs(x),abs(y),abs(z)) > min_mag)
        if ok:
            if rstart<0: rstart=p; rcnt=1
            else: rcnt+=1
        else:
            if rcnt>=9: runs.append((rstart,rcnt))
            rstart=-1; rcnt=0
    if rcnt>=9: runs.append((rstart,rcnt))
    return runs

def find_tarray_u16_after(D, pos_end, export_end, num_verts):
    """Search for uint16 index buffer after pos_end. Returns (offset, count) or None."""
    best = None
    for p in range(pos_end, export_end-8, 4):
        n = ri32(D, p)
        if n < 3 or n > 500000: continue
        if p + 4 + n*2 > export_end: continue
        ok=True; max_i=0
        for i in range(min(n, 16)):
            v = ru16(D, p+4+i*2)
            if v >= num_verts*2: ok=False; break
            max_i = max(max_i, v)
        if not ok: continue
        for i in range(max(0, n-8), n):
            v = ru16(D, p+4+i*2)
            if v >= num_verts*2: ok=False; break
        if not ok: continue
        if best is None or n > best[1]:
            best = (p, n, max_i)
    return best

def find_tarray_u32_after(D, pos_end, export_end, num_verts):
    """Search for uint32 index buffer after pos_end. Returns (offset, count) or None."""
    best = None
    for p in range(pos_end, export_end-8, 4):
        n = ri32(D, p)
        if n < 3 or n > 500000: continue
        if p + 4 + n*4 > export_end: continue
        ok=True; max_i=0
        for i in range(min(n, 16)):
            v = ru32(D, p+4+i*4)
            if v >= num_verts*2: ok=False; break
            max_i = max(max_i, v)
        if not ok: continue
        for i in range(max(0, n-8), n):
            v = ru32(D, p+4+i*4)
            if v >= num_verts*2: ok=False; break
        if not ok: continue
        if best is None or n > best[1]:
            best = (p, n, max_i)
    return best

for (name, virt_off, ser_size) in EXPORTS:
    phys_start = virt_off - BV
    phys_end   = phys_start + ser_size
    print(f"\n{'='*60}")
    print(f"Export: {name}  phys={phys_start}  size={ser_size}")

    # Find float3 runs with two thresholds
    runs_05 = find_float3_runs(D, phys_start, phys_end, min_mag=0.5)
    runs_01 = find_float3_runs(D, phys_start, phys_end, min_mag=0.001)
    print(f"  Runs (mag>0.5): {len(runs_05)}   Runs (mag>0.001): {len(runs_01)}")

    # Pick best run for each threshold
    for label, runs in [("mag>0.5", runs_05), ("mag>0.001", runs_01)]:
        if not runs: print(f"  [{label}] No runs found"); continue
        rp, rc = max(runs, key=lambda x: x[1])
        off = rp - phys_start
        nv = (rc + 2) // 3
        pos_end = rp + nv * 12
        print(f"  [{label}] Best run: +{off}  scan_count={rc}  nVerts={nv}  posEnd=+{pos_end - phys_start}")
        # Show first 3 verts
        for vi in range(min(3, nv)):
            fp = rp + vi*12
            print(f"    v[{vi}]=({rf(D,fp):.3f},{rf(D,fp+4):.3f},{rf(D,fp+8):.3f})")
        # Try uint16 index buffer after positions
        r16 = find_tarray_u16_after(D, pos_end, phys_end, nv)
        # Try uint32 index buffer after positions
        r32 = find_tarray_u32_after(D, pos_end, phys_end, nv)
        print(f"    idx u16 after: {r16}")
        print(f"    idx u32 after: {r32}")
        # Also scan from pos start (not pos end) for completeness
        r16b = find_tarray_u16_after(D, rp, phys_end, nv)
        r32b = find_tarray_u32_after(D, rp, phys_end, nv)
        print(f"    idx u16 from runStart: {r16b}")
        print(f"    idx u32 from runStart: {r32b}")

print("\nDone.")
