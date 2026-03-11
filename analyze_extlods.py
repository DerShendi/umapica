"""
Scan virtual addresses pointed to from export metadata for LOD0 positions.
Look for 671-vert run for wooden_rail and 1486-vert run for standing_torch.
"""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

# Decompress
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
    fp=co; fp+=4; bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw,fp)); fp+=4; su2.append(ri32(raw,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw[fp:fp+cs2]; fp+=cs2
        D[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]; dest+=us2
BV = chunks[0][0]
TOTAL = total
print(f"Decomp: {TOTAL} bytes  BV={BV}")

def is_plausible(x,y,z, lo=-200000, hi=200000, mn=0.1):
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def find_float3_runs(D, start, end, mn=0.1, min_run=20):
    """Find ALL float3 runs ≥ min_run steps in region."""
    runs = []
    cur_s, cur_c = -1, 0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        ok = (math.isfinite(x) and -200000<x<200000 and
              math.isfinite(y) and -200000<y<200000 and
              math.isfinite(z) and -200000<z<200000 and
              max(abs(x),abs(y),abs(z))>mn)
        if ok:
            if cur_s<0: cur_s=p; cur_c=1
            else: cur_c+=1
        else:
            if cur_c >= min_run:
                runs.append((cur_s-start, cur_c, (cur_c+2)//3))
            cur_s=-1; cur_c=0
    if cur_c >= min_run:
        runs.append((cur_s-start, cur_c, (cur_c+2)//3))
    return runs

def scan_exterior_buffer(target_phys, label, search_bytes=100000, mn=0.1):
    """Look for all significant float3 runs in a buffer."""
    if target_phys < 0 or target_phys + search_bytes > TOTAL:
        search_bytes = TOTAL - target_phys
    print(f"\n  {label}: phys={target_phys}, scanning {search_bytes} bytes")
    runs = find_float3_runs(D, target_phys, target_phys+search_bytes, mn=mn)
    for (rel, steps, verts) in runs:
        abs_phys = target_phys + rel
        x0,y0,z0 = rf(D,abs_phys), rf(D,abs_phys+4), rf(D,abs_phys+8)
        print(f"    +{rel:6d}: {steps:5d} steps, ~{verts:4d} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

# ====================================================================
# WOODEN RAIL: vertex data starts at phys=5783205 (from export +148)
# ====================================================================
print("\n" + "="*70)
print("WOODEN RAIL - exterior vertex buffer analysis")
wr_virt, wr_size = (31486718, 140315)
wr_phys = wr_virt - BV

# Main vertex buffer pointer
wr_vtx_virt = ri32(D, wr_phys+148)
wr_vtx_phys = wr_vtx_virt - BV
print(f"Vertex buffer virt={wr_vtx_virt} phys={wr_vtx_phys}")

scan_exterior_buffer(wr_vtx_phys, "WR vertex buffer", search_bytes=200000)

# Also check the post-index table entries looking for position-type data
# Specifically entries that point to large data with plausible float3 runs
print("\n  Post-index virtual addresses with plausible float3 data:")
for k in range((wr_size-138996)//4):
    rel = 138996 + k*4
    p = wr_phys + rel
    if p+4 > wr_phys+wr_size: break
    u = ru32(D, p)
    if u > 1000000 and BV < u < BV+TOTAL:
        target_phys = u - BV
        if target_phys + 24 < TOTAL:
            # Quick check: does this region have a float3 run of >200 steps?
            runs = find_float3_runs(D, target_phys, min(target_phys+50000, TOTAL), mn=0.1, min_run=200)
            if runs:
                best = max(runs, key=lambda r: r[1])
                rel2, steps, verts = best
                print(f"  [+{rel}] virt={u} -> phys={target_phys}: float3 run at +{rel2}, {steps} steps, ~{verts} verts")

# ====================================================================
# STANDING TORCH: scan for render positions
# ====================================================================
print("\n" + "="*70)
print("STANDING TORCH - exterior vertex buffer analysis")
st_virt, st_size = (30739943, 42674)
st_phys = st_virt - BV

# From previous: +144 = 7889510 -> phys=7888581 (normals/UV)
# Need to find position buffer
st_normals_virt = ri32(D, st_phys+144)
st_normals_phys = st_normals_virt - BV
print(f"Normals buffer at virt={st_normals_virt} phys={st_normals_phys}")

# Are there other virtual addresses in the first 300 bytes?
print("\n  Virtual addresses in [0..300] range >1M:")
for k in range(75):
    p = st_phys + k*4
    u = ru32(D, p)
    if u > 1000000 and BV < u < BV+TOTAL:
        print(f"  +{k*4}: {u} -> phys={u-BV}")

# Scan the normals buffer for float3 runs
scan_exterior_buffer(st_normals_phys, "ST normals buffer", search_bytes=200000)

# Check post-physics section of standing_torch export (after hull data)
# Hull is roughly at +292 to ~+9686.
# After +9686 the INLINE data starts... scan it for any run
print("\n  Inline post-hull region (+9686 to +42674):")
inline_start = st_phys + 9686
inline_end = st_phys + st_size
runs_inline = find_float3_runs(D, inline_start, inline_end, mn=0.1, min_run=50)
for (rel, steps, verts) in runs_inline:
    abs_phys = inline_start + rel
    x0,y0,z0 = rf(D,abs_phys), rf(D,abs_phys+4), rf(D,abs_phys+8)
    print(f"  +{9686+rel:6d}: {steps:5d} steps, ~{verts:4d} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

print("\nDone.")
