"""
Check virtual addresses found in wooden_rail and standing_torch metadata,
and determine the exact structure of the mesh format with external geometry storage.
"""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rs16(d,o): return struct.unpack_from('<h',d,o)[0]
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

EXPORTS = {
    'standing_torch':   (30739943, 42674),
    'mafia_wooden_rail': (31486718, 140315),
    'mafia_wall_light':  (31348149, 104622),
}

def is_plausible(x,y,z, lo=-200000, hi=200000, mn=0.01):
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def scan_float3_best(D, start, end, mn=0.1):
    """Find best float3 run in region."""
    best_s, best_c = -1, 0
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
            if cur_c>best_c: best_c=cur_c; best_s=cur_s
            cur_s=-1; cur_c=0
    if cur_c>best_c: best_c=cur_c; best_s=cur_s
    return best_s, best_c

def dump_at(phys, label, n_dwords=32, look_float3=True, look_bytes=3000):
    if phys < 0 or phys+4 > TOTAL:
        print(f"  {label}: phys={phys} out of range")
        return
    print(f"\n  [{label}] phys={phys} virt={phys+BV}")
    for k in range(min(n_dwords, (TOTAL-phys)//4)):
        p = phys + k*4
        i = ri32(D, p)
        f = rf(D, p)
        u = ru32(D, p)
        fstr = f"{f:.4f}" if math.isfinite(f) and abs(f) < 200000 else "---"
        print(f"    [{k*4:4d}] i32={i:12d}  u32={u:10d}  f32={fstr}")
    if look_float3:
        scan_end = min(phys+look_bytes, TOTAL)
        bs, bc = scan_float3_best(D, phys, scan_end)
        if bs >= 0:
            rel = bs-phys
            n = (bc+2)//3
            x0,y0,z0 = rf(D,bs), rf(D,bs+4), rf(D,bs+8)
            print(f"  => Float3 run at +{rel}: {bc} steps, ~{n} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

# ============================================================
# WOODEN RAIL
# ============================================================
print("\n" + "="*70)
print("MAFIA WOODEN RAIL")
virt, size = EXPORTS['mafia_wooden_rail']
phys = virt - BV

# From virt scan:
# +148: 5784134 -> phys=5783205  (likely vertex buffer, count nearby?)
# +152: 1150 (maybe vertex buffer count for a specific LOD?)
# +160: 1096
# +208: 1474
# The LOD table at +6416-+6516 contains (virtAddr, indexCount) pairs

# What's the full structure starting from the beginning of the LOD table?
print("\n  LOD TABLE REGION (+6400 to +6520):")
for k in range(30):
    rel = 6400 + k*4
    p = phys + rel
    i = ri32(D, p)
    u = ru32(D, p)
    f = rf(D, p)
    fstr = f"{f:.3f}" if math.isfinite(f) and abs(f) < 200000 else "---"
    # Check if it looks like a virtual address
    is_virt = BV < u < BV+TOTAL
    print(f"    [+{rel}] i32={i:10d}  f32={fstr}  {'<-- VIRT' if is_virt else ''}")

# Check virtual address at +148
target1_virt = ri32(D, phys+148)
target1_phys = target1_virt - BV
print(f"\n  Value at +148: {target1_virt} -> phys={target1_phys}")
count_at_152 = ri32(D, phys+152)
count_at_160 = ri32(D, phys+160)
print(f"  Values at +152,+160: {count_at_152}, {count_at_160}")
dump_at(target1_phys, "+148 target (position buffer?)", n_dwords=40, look_bytes=15000)

# Follow the vertex count hint: if the target is a [count, data] array
# Try interpreting as: i32 count, then float3 × count
n_at_target = ri32(D, target1_phys)
print(f"\n  First i32 at target: {n_at_target}")
if 100 < n_at_target < 10000:
    data_start = target1_phys + 4
    x0,y0,z0 = rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8)
    print(f"  If count-prefixed array: count={n_at_target}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
    if is_plausible(x0,y0,z0):
        print("  *** PLAUSIBLE float3 v0 - this might be [count, float3 x count]! ***")

# Check post-index virt addresses (found at +139016, +139024, etc.)
print("\n  POST-INDEX VIRTUAL ADDRESSES (+138996 to end):")
for k in range((size-138996)//4):
    rel = 138996 + k*4
    p = phys + rel
    u = ru32(D, p)
    if BV < u < BV+TOTAL:
        target_phys = u - BV
        print(f"  [+{rel}] virt={u} -> phys={target_phys}")
        # Quick check: first 4 floats at target
        if target_phys + 16 < TOTAL:
            x0,y0,z0 = rf(D,target_phys), rf(D,target_phys+4), rf(D,target_phys+8)
            i0 = ri32(D, target_phys)
            fstr0 = f"{x0:.3f}" if math.isfinite(x0) and abs(x0)<200000 else "---"
            fstr1 = f"{y0:.3f}" if math.isfinite(y0) and abs(y0)<200000 else "---"
            fstr2 = f"{z0:.3f}" if math.isfinite(z0) and abs(z0)<200000 else "---"
            print(f"    -> first float3: ({fstr0},{fstr1},{fstr2}), i32[0]={i0}")

# ============================================================
# STANDING TORCH
# ============================================================
print("\n" + "="*70)
print("STANDING TORCH")
virt, size = EXPORTS['standing_torch']
phys = virt - BV

# From previous analysis:
# +144: 7889510 -> phys=7888581 (normals/UV buffer - CONFIRMED)
# +48: 3683130 -> phys=3682201 (might be position buffer?)

# Dump the full header structure (first 300 bytes) carefully
print("\n  First 80 dwords (4-byte aligned):")
for k in range(80):
    rel = k*4
    p = phys + rel
    i = ri32(D, p)
    u = ru32(D, p)
    f = rf(D, p)
    fstr = f"{f:.4f}" if math.isfinite(f) and abs(f) < 200000 else "---"
    is_virt = BV < u < BV+TOTAL and u > 1000000  # 1M+ to skip small indices
    print(f"    [+{rel:3d}] i32={i:10d}  f32={fstr}  {'<-- VIRT' if is_virt else ''}")

# Check all "large" virtual addresses in the metadata (first 300 bytes)
print("\n  Virtual addresses in metadata (first 300 bytes, >1M):")
for k in range(75):
    rel = k*4
    p = phys + rel
    u = ru32(D, p)
    if u > 1000000 and BV < u < BV+TOTAL:
        target_phys = u - BV
        print(f"  [+{rel}] virt={u} -> phys={target_phys}")
        next_val = ri32(D, p+4)
        dump_at(target_phys, f"+{rel} target", n_dwords=16, look_bytes=5000)

print("\nDone.")
