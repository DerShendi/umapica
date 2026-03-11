"""
Verify and analyze exact vertex buffer locations for wooden_rail and standing_torch.
"""
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
    fp=co; fp+=4; bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw,fp)); fp+=4; su2.append(ri32(raw,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw[fp:fp+cs2]; fp+=cs2
        D[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]; dest+=us2
BV = chunks[0][0]
TOTAL = total
print(f"BV={BV}  TOTAL={TOTAL}")

def is_ok(x,y,z,mn=0.01):
    return (math.isfinite(x) and -200000<x<200000 and
            math.isfinite(y) and -200000<y<200000 and
            math.isfinite(z) and -200000<z<200000 and
            max(abs(x),abs(y),abs(z))>mn)

def count_float3_run(D, start, mn=0.01):
    """Count contiguous float3 run from start."""
    n=0
    p=start
    while p+12 <= TOTAL:
        x,y,z = rf(D,p),rf(D,p+4),rf(D,p+8)
        if not is_ok(x,y,z,mn): break
        n+=1; p+=12
    return n

def find_best_float3_run(D, start, end, mn=0.01, min_verts=50):
    best=(0,-1)
    p=start
    while p < end-12:
        x,y,z=rf(D,p),rf(D,p+4),rf(D,p+8)
        if is_ok(x,y,z,mn):
            n=count_float3_run(D,p,mn)
            nv=(n+2)//3
            if nv>best[0]: best=(nv,p)
            p+=max(n*4,4)
        else:
            p+=4
    return best

# -------------------------------------------------------------------------
# WOODEN RAIL
# -------------------------------------------------------------------------
print("\n=== WOODEN RAIL ===")
wr_phys = 31486718 - BV
wr_size = 140315

# LOD0 index buffer inline at +6520 with n=66210, max_idx=670 -> 671 verts
wr_idx_n = ru32(D, wr_phys+6520)
print(f"LOD0 idx buf count: {wr_idx_n}")
max_idx = max(ru16(D, wr_phys+6520+4+i*2) for i in range(min(wr_idx_n,66210)))
print(f"LOD0 max_index: {max_idx}  -> need {max_idx+1} verts for LOD0")

# Check post-index candidate: phys=15072563 (run at +22200, ~651 verts)
# This is from export +139608, virt=15073492
cand_phys = 15073492 - BV  # =15072563
print(f"\n[+139608] cand phys={cand_phys}")
# Check with mn=0.01 at +22200
offset = 22200
nv = count_float3_run(D, cand_phys + offset, mn=0.01)
x0,y0,z0 = rf(D,cand_phys+offset), rf(D,cand_phys+offset+4), rf(D,cand_phys+offset+8)
print(f"  mn=0.01: {nv} verts at +{offset}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
# Also try searching nearby for the peak
print("  Searching ±500 bytes around +22200:")
best = find_best_float3_run(D, cand_phys+21700, cand_phys+22700, mn=0.01, min_verts=100)
if best[1]>0:
    rx0,ry0,rz0 = rf(D,best[1]),rf(D,best[1]+4),rf(D,best[1]+8)
    print(f"  Best: {best[0]} verts at abs={best[1]} rel={(best[1]-cand_phys)}, v0=({rx0:.3f},{ry0:.3f},{rz0:.3f})")

# Check [+139604] phys=15139028-929=15138099 (run at +2352, ~564 verts) 
cand2_phys = 15139028 - BV  # =15138099
print(f"\n[+139604] cand2 phys={cand2_phys}")
offset2 = 2352
nv2 = count_float3_run(D, cand2_phys + offset2, mn=0.01)
x0,y0,z0 = rf(D,cand2_phys+offset2), rf(D,cand2_phys+offset2+4), rf(D,cand2_phys+offset2+8)
print(f"  mn=0.01: {nv2} verts at +{offset2}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

# Also check whether indices of LOD0 inline buffer match any of the above (scan a few)
# If the indices go up to max_idx, the vertex buffer with max_idx+1 verts is the match.
# We expect 671 verts -> need to find 671 consecutive plausible float3s
# Let's brute-force search all 5 candidates for a run >= 671:
candidates = [
    (15139028-BV, "cand[+139604]"),
    (15073492-BV, "cand[+139608]"),
    (15075251-BV, "cand[+139600]"),
    (49677043-BV, "cand[+139628]"),
    (19857723-BV, "cand[+140152]"),
]
print(f"\nSearching for ≥{max_idx+1}-vert run in all 5 candidates:")
for (ph, lbl) in candidates:
    if ph < 0 or ph > TOTAL: continue
    best = find_best_float3_run(D, ph, ph+100000, mn=0.01, min_verts=max_idx+1)
    if best[0] >= max_idx+1:
        rx0,ry0,rz0 = rf(D,best[1]),rf(D,best[1]+4),rf(D,best[1]+8)
        print(f"  FOUND! {lbl}: {best[0]} verts at rel={(best[1]-ph)}, v0=({rx0:.3f},{ry0:.3f},{rz0:.3f})")
    elif best[0] > 50:
        rx0,ry0,rz0 = rf(D,best[1]),rf(D,best[1]+4),rf(D,best[1]+8)
        print(f"  {lbl}: best {best[0]} verts at rel={(best[1]-ph)}, v0=({rx0:.3f},{ry0:.3f},{rz0:.3f})")

# -------------------------------------------------------------------------
# STANDING TORCH
# -------------------------------------------------------------------------
print("\n=== STANDING TORCH ===")
st_phys = 30739943 - BV
# render count at +156
st_rend_v = ri32(D, st_phys+156)
print(f"Render vert count at +156: {st_rend_v}")

# Virtual address at +48: 3683130 -> phys=3682201
virt48 = ri32(D, st_phys+48)
phys48 = virt48 - BV
print(f"\n+48 virt={virt48} phys={phys48}")
# Scan it for float3 runs
best48 = find_best_float3_run(D, phys48, phys48+200000, mn=0.01, min_verts=50)
if best48[1]>0:
    rx0,ry0,rz0 = rf(D,best48[1]),rf(D,best48[1]+4),rf(D,best48[1]+8)
    print(f"  Best run: {best48[0]} verts at rel={(best48[1]-phys48)}, v0=({rx0:.3f},{ry0:.3f},{rz0:.3f})")

# How many verts does torch render LOD0 need?
# find index buffer for torch
# torch has no inline idx buf found before... let's check inline section
# post-hull physics at +9686 – look for index buffer after the hull
# Scan for a uint16 array with max ~1485
print("\n  Scanning torch export for uint16 index patterns (max~1485, count~848*3=2544):")
for rel in range(0, 42674-2, 4):
    n32 = ru32(D, st_phys+rel)
    if n32 == 2544:  # render index count
        print(f"  Found n=2544 at export +{rel}")

# Also check phys48 to see if it's a vertex buffer
print(f"\n  phys48={phys48} first 80 dwords:")
for i in range(80):
    v = ru32(D, phys48+i*4)
    f = rf(D, phys48+i*4)
    flag = "  <-- PLAUSIBLE FLOAT" if (math.isfinite(f) and -200000<f<200000 and abs(f)>0.01) else ""
    print(f"  [{i*4:4d}]: {v:12d}  f={f:12.4f}{flag}")

print("\nDone.")
