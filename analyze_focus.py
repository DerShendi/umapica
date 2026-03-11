"""
Focus on:
1. standing_torch: examine post-hull region (+9686+) for render positions
2. wooden_rail: decode the LOD pointer table and follow pointers
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
print(f"Decomp: {total} bytes  BV={BV}")

EXPORTS = {
    'standing_torch':   (30739943, 42674),
    'mafia_wooden_rail': (31486718, 140315),
    'mafia_wall_light':  (31348149, 104622),
}

def is_plausible(x, y, z, lo=-50000, hi=50000, mn=0.5):
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def dump_dwords(D, start, count, label):
    """Dump count dwords from start."""
    print(f"\n  {label}:")
    for k in range(count):
        p = start + k*4
        i = ri32(D, p)
        f = rf(D, p)
        fstr = f"{f:.3f}" if math.isfinite(f) and abs(f)<100000 else "---"
        print(f"    [{p-start:4d}] i32={i:10d}  f32={fstr}")

def find_float3_run(D, start, end, min_run=50):
    """Find longest run of plausible float3s with stride=4."""
    best_start, best_count = -1, 0
    cur_start, cur_count = -1, 0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        if is_plausible(x,y,z):
            if cur_start<0: cur_start=p; cur_count=1
            else: cur_count+=1
        else:
            if cur_count > best_count: best_count=cur_count; best_start=cur_start
            cur_start=-1; cur_count=0
    if cur_count > best_count: best_count=cur_count; best_start=cur_start
    return best_start, best_count

def find_pos_buf_header(D, start, end, min_n=5, max_n=10000):
    """Scan for [stride=12, n, elementSize=12, n, float3_data] pattern."""
    results = []
    for p in range(start, end-20, 4):
        s1 = ru32(D, p)
        n1 = ru32(D, p+4)
        s2 = ru32(D, p+8)
        n2 = ru32(D, p+12)
        if s1 == 12 and s1 == s2 and n1 == n2 and min_n <= n1 <= max_n:
            data_start = p + 16
            if data_start + n1*12 <= end:
                x0,y0,z0 = rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8)
                if is_plausible(x0,y0,z0, mn=0.01):
                    results.append((p-start, n1, x0, y0, z0))
    return results

# ========== STANDING TORCH ==========
print("\n" + "="*70)
print("STANDING TORCH")
name = 'standing_torch'
virt, size = EXPORTS[name]
phys = virt - BV
print(f"phys={phys}  size={size}")

# From structural analysis:
# [148]=244 (hull verts), [156]=1486 (render verts), [172]=3231 (hull idx count), [184]=848 (render tris)
# Hull verts start at +292, end at +292+244*12=+3220
# Hull idx (with 4-byte count prefix) at +3220, 3231 indices → ends at +3220+4+3231*2=+9686 

hull_n = ri32(D, phys+172)  # 3231
hull_vert_n_at148 = ri32(D, phys+148)  # 244
render_vert_n = ri32(D, phys+156)  # 1486
render_tri_n = ri32(D, phys+184)  # 848

print(f"\n  Header values: hull_vert_n={hull_vert_n_at148} render_vert_n={render_vert_n}")
print(f"                hull_idx_n={hull_n} render_tri_n={render_tri_n}")

# Verify hull vert start at +292
print(f"\n  Hull vert[0] at +292: ({rf(D,phys+292):.3f}, {rf(D,phys+296):.3f}, {rf(D,phys+300):.3f})")
print(f"  Hull vert[1] at +304: ({rf(D,phys+304):.3f}, {rf(D,phys+308):.3f}, {rf(D,phys+312):.3f})")

hull_verts_end = 292 + hull_vert_n_at148 * 12
print(f"  Hull verts from +292 to +{hull_verts_end}")

# Verify hull idx count at hull_verts_end
actual_hull_idx_count = ri32(D, phys + hull_verts_end)
print(f"  Hull idx count at +{hull_verts_end}: {actual_hull_idx_count} (expected {hull_n})")

hull_idx_end = hull_verts_end + 4 + hull_n * 2
print(f"  Hull idx from +{hull_verts_end+4} to +{hull_idx_end}")

# Dump 80 dwords after hull idx end
render_start = hull_idx_end
remaining = size - render_start
print(f"\n  Render mesh starts at +{render_start}, {remaining} bytes remaining")

dump_dwords(D, phys + render_start, 80, "Dwords after hull section")

# Check for position buffer header in the post-hull region
print(f"\n  Searching for [12,n,12,n] position buffer header after +{render_start}...")
scan_start = phys + render_start
scan_end   = phys + size
results = find_pos_buf_header(D, scan_start, scan_end)
if results:
    for (rel, n, x0, y0, z0) in results:
        print(f"    FOUND at +{render_start+rel}: n={n} v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("    Not found.")

# Also try float3 run scan after hull section
print(f"\n  Float3 run scan after +{render_start}...")
best_start, best_count = find_float3_run(D, scan_start, scan_end, min_run=50)
if best_start >= 0:
    rel = best_start - phys
    n = (best_count+2)//3
    x0,y0,z0 = rf(D,best_start), rf(D,best_start+4), rf(D,best_start+8)
    print(f"    Best run: {best_count} steps at +{rel}, ~{n} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("    No significant run found.")

# ========== WOODEN RAIL ==========
print("\n" + "="*70)
print("MAFIA_WOODEN_RAIL")
name = 'mafia_wooden_rail'
virt, size = EXPORTS[name]
phys = virt - BV
print(f"phys={phys}  size={size}")

# The "LOD table" from +0 to +6516 contains pairs of (count, virtAddr)
# Let's parse it as pairs of i32 values and follow any virtAddr within our data range
print("\n  Pre-idx LOD table (pairs of i32 read 4 bytes at a time):")
# Actual first dwords
for k in range(20):
    p = phys + k*4
    print(f"    [+{k*4}] i32={ri32(D,p)} u32={ru32(D,p)}")

# The confirmed index buffer starts at +6516 with n=66210, max=670
# Let's verify what's there
real_idx_off = 6516
n66k = ri32(D, phys + real_idx_off)
print(f"\n  At +{real_idx_off}: n={n66k}")
first6 = [ru16(D, phys+real_idx_off+4+i*2) for i in range(6)]
print(f"  First 6 indices: {first6}")
idx_data_end = real_idx_off + 4 + n66k * 2
print(f"  Idx data ends at +{idx_data_end}")
print(f"  Remaining: {size - idx_data_end} bytes")

# Look for position buffer header [12,671,12,671] anywhere in the export
print(f"\n  Searching for [12,671,12,671] or any [12,n,12,n] pattern...")
results = find_pos_buf_header(D, phys, phys+size)
if results:
    for (rel, n, x0, y0, z0) in results:
        print(f"    FOUND at +{rel}: n={n} v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("    NOT FOUND")

# NOW: try to follow the virt pointers in the pre-idx region
# Each pair (small_count, large_virt) – the large_virt might be within total decompressed data
print("\n  Following pointers in pre-idx region:")
TOTAL_SIZE = total  # decompressed total
for k in range(0, real_idx_off//8):
    base = phys + k * 8
    v1 = ru32(D, base)
    v2 = ru32(D, base+4)
    # If v2 looks like a virt address within compressed data
    if 929 < v2 < total+929 and v1 % 3 == 0 and 6 <= v1 <= 100000:
        virt_ptr = v2
        phys_ptr = virt_ptr - BV
        if phys_ptr + v1*2 < total:
            max_i = 0
            for i in range(min(v1, 32)):
                idx = ru16(D, phys_ptr + i*2)
                if idx > max_i: max_i = idx
            if max_i < 2000:  # plausible index range
                rel_k = k*8
                print(f"    [+{rel_k}]: count={v1} virtPtr={virt_ptr} physPtr={phys_ptr} max_idx={max_i}")

# Search for float3 run anywhere in the export
print(f"\n  Float3 run scan across entire export:")
best_start, best_count = find_float3_run(D, phys, phys+size, min_run=50)
if best_start >= 0:
    rel = best_start - phys
    n = (best_count+2)//3
    x0,y0,z0 = rf(D,best_start), rf(D,best_start+4), rf(D,best_start+8)
    print(f"    Best run: {best_count} steps at +{rel}, ~{n} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("    No run found.")

print("\nDone.")
