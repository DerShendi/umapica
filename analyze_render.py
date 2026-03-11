"""
Find render index count (2544) as a TArray header in standing_torch,
and scan for variant position buffer formats with relaxed thresholds.
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
}

def is_plausible(x,y,z, lo=-50000, hi=50000, mn=0.01):
    """Relaxed plausibility - min 0.01 (allow near-origin verts)."""
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def scan_float3_run(D, start, end, mn=0.5):
    """Find longest run scanning every 4 bytes."""
    best_start, best_count = -1, 0
    cur_start, cur_count = -1, 0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        ok = (math.isfinite(x) and -50000<x<50000 and
              math.isfinite(y) and -50000<y<50000 and
              math.isfinite(z) and -50000<z<50000 and
              max(abs(x),abs(y),abs(z))>mn)
        if ok:
            if cur_start<0: cur_start=p; cur_count=1
            else: cur_count+=1
        else:
            if cur_count>best_count: best_count=cur_count; best_start=cur_start
            cur_start=-1; cur_count=0
    if cur_count>best_count: best_count=cur_count; best_start=cur_start
    return best_start, best_count

def dump_region(D, start, size_bytes, label):
    print(f"\n  {label} ({size_bytes} bytes from offset +{start-D_phys}):")
    for k in range(min(size_bytes//4, 80)):
        p = start + k*4
        i = ri32(D, p)
        f = rf(D, p)
        fstr = f"{f:.4f}" if math.isfinite(f) and abs(f)<100000 else "---"
        print(f"    [{p-D_phys:6d}] i32={i:12d}  f32={fstr}")

global D_phys

# ========== STANDING_TORCH ==========
print("\n" + "="*70)
name = 'standing_torch'
virt, size = EXPORTS[name]
phys = virt - BV
D_phys = phys
print(f"{name}: phys={phys} size={size}")

render_tri_n = 848         # from header at [184]
render_vert_n = 1486       # from header at [156]
render_idx_n = render_tri_n * 3  # = 2544

# 1. Find all occurrences of 2544 as an i32 in the export
print(f"\n1. Searching for render_idx_n={render_idx_n} as i32 anywhere in export...")
for p in range(phys, phys+size-8, 4):
    if ri32(D, p) == render_idx_n:
        rel = p - phys
        # Check if followed by valid uint16 indices
        max_i = 0; ok=True
        for k in range(min(20, render_idx_n)):
            v = ru16(D, p+4+k*2)
            if v>10000: ok=False; break
            if v>max_i: max_i=v
        status = f"max_idx={max_i}" if ok else "large_idx"
        print(f"  +{rel}: i32={render_idx_n} → {status}")

# 2. Find all occurrences of 1486 (render vert count) as an i32
print(f"\n2. Searching for render_vert_n={render_vert_n} as i32 anywhere in export...")
for p in range(phys, phys+size-4, 4):
    if ri32(D, p) == render_vert_n:
        rel = p - phys
        # Is it followed by stride=12?
        stride_after = ri32(D, p+4) if p+8<=phys+size else -1
        stride_before = ri32(D, p-4) if p-4>=phys else -1
        print(f"  +{rel}: i32={render_vert_n}  before={stride_before}  after={stride_after}")

# 3. Scan for index buffer [2544, uint16×2544 with max<2000]
print(f"\n3. Tight index buffer scan (n=2544 exactly)...")
for p in range(phys, phys+size-render_idx_n*2-4, 4):
    if ri32(D, p) == render_idx_n:
        max_i=0; ok=True
        for k in range(render_idx_n):
            v = ru16(D, p+4+k*2)
            if v >= 3000: ok=False; break
            if v > max_i: max_i=v
        if ok and max_i>50:
            rel=p-phys
            first6=[ru16(D,p+4+k*2) for k in range(6)]
            print(f"  FOUND at +{rel}: max_idx={max_i} first6={first6}")
            # Dump 160 bytes BEFORE this index buffer
            dump_region(D, p-160, 160, "160 bytes before")

# 4. Scan with relaxed float3 threshold (mn=0.01 instead of 0.5)
print(f"\n4. Relaxed float3 scan (min>0.01) on full export...")
best_s, best_c = scan_float3_run(D, phys, phys+size, mn=0.01)
if best_s >= 0:
    rel = best_s - phys
    n = (best_c+2)//3
    x0,y0,z0 = rf(D,best_s), rf(D,best_s+4), rf(D,best_s+8)
    print(f"  Best run: {best_c} steps at +{rel}, ~{n} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("  No run found.")

# 5. Scan for EVERY position of i32=12 followed by plausible vert count
print(f"\n5. Scanning for [stride=12, count=N] header (for any N in 100-5000)...")
for p in range(phys, phys+size-20, 4):
    stride = ru32(D, p)
    if stride == 12:
        n = ri32(D, p+4)
        if 100 <= n <= 5000:
            data_start = p+8
            if data_start + n*12 <= phys+size:
                x0,y0,z0 = rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8)
                if math.isfinite(x0) and math.isfinite(y0) and math.isfinite(z0):
                    if max(abs(x0),abs(y0),abs(z0)) < 50000:
                        rel=p-phys
                        print(f"  +{rel}: stride=12, n={n}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

# ========== WOODEN_RAIL ==========
print("\n" + "="*70)
name = 'mafia_wooden_rail'
virt, size = EXPORTS[name]
phys = virt - BV
D_phys = phys
print(f"{name}: phys={phys} size={size}")

# Confirmed: 671 verts (max_idx=670), 22070 tris (66210/3)
render_vert_n_wr = 671
render_idx_n_wr = 66210

# Search for 671 as render vert count
print(f"\n1. Searching for render_vert_n={render_vert_n_wr} as i32 anywhere...")
for p in range(phys, phys+size-4, 4):
    if ri32(D, p) == render_vert_n_wr:
        rel = p - phys
        stride_after = ri32(D, p+4) if p+8<=phys+size else -1
        stride_before = ri32(D, p-4) if p-4>=phys else -1
        print(f"  +{rel}: i32={render_vert_n_wr}  before={stride_before}  after={stride_after}")

# Relaxed float3 scan for wooden_rail
print(f"\n2. Relaxed float3 scan (min>0.01) on full export...")
best_s2, best_c2 = scan_float3_run(D, phys, phys+size, mn=0.01)
if best_s2 >= 0:
    rel = best_s2 - phys
    n2 = (best_c2+2)//3
    x0,y0,z0 = rf(D,best_s2), rf(D,best_s2+4), rf(D,best_s2+8)
    print(f"  Best run: {best_c2} steps at +{rel}, ~{n2} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
else:
    print("  No run found.")

# Scan for [stride=12, count] for wooden_rail
print(f"\n3. Scanning for [stride=12, count=N] header (for any N 100-5000)...")
for p in range(phys, phys+size-20, 4):
    stride = ru32(D, p)
    if stride == 12:
        n = ri32(D, p+4)
        if 100 <= n <= 5000:
            data_start = p+8
            if data_start + n*12 <= phys+size:
                x0,y0,z0 = rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8)
                if math.isfinite(x0) and math.isfinite(y0) and math.isfinite(z0):
                    if max(abs(x0),abs(y0),abs(z0)) < 50000:
                        rel=p-phys
                        print(f"  +{rel}: stride=12, n={n}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

print("\nDone.")
