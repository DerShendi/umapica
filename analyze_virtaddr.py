"""
The render mesh data is likely at a virtual address stored in the export metadata.
Export at +144: i32=7889510 → phys = 7889510-929 = 7888581. 
Check what's there and scan for render mesh data.
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
print(f"Decomp: {total} bytes  BV={BV}")

EXPORTS = {
    'standing_torch':   (30739943, 42674),
    'mafia_wooden_rail': (31486718, 140315),
    'mafia_wall_light':  (31348149, 104622),
}

def is_plausible(x,y,z, lo=-50000, hi=50000, mn=0.1):
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def scan_float3(D, start, end, mn=0.1):
    best_s, best_c = -1, 0
    cur_s, cur_c = -1, 0
    for p in range(start, end-12, 4):
        x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
        ok = (math.isfinite(x) and -50000<x<50000 and
              math.isfinite(y) and -50000<y<50000 and
              math.isfinite(z) and -50000<z<50000 and
              max(abs(x),abs(y),abs(z))>mn)
        if ok:
            if cur_s<0: cur_s=p; cur_c=1
            else: cur_c+=1
        else:
            if cur_c>best_c: best_c=cur_c; best_s=cur_s
            cur_s=-1; cur_c=0
    if cur_c>best_c: best_c=cur_c; best_s=cur_s
    return best_s, best_c

def check_at_virt(virt_addr, context, look_bytes=2000):
    """Check what's at a virtual address in the decompressed data."""
    if virt_addr < BV or virt_addr >= BV + TOTAL:
        print(f"  {context}: virt={virt_addr} OUT OF RANGE")
        return
    phys = virt_addr - BV
    print(f"\n  {context}: virt={virt_addr} phys={phys}")
    # Dump first 40 dwords
    for k in range(min(40, (TOTAL-phys)//4)):
        p = phys + k*4
        i = ri32(D, p)
        f = rf(D, p)
        fstr = f"{f:.4f}" if math.isfinite(f) and abs(f)<100000 else "---"
        print(f"    [{k*4:4d}] i32={i:12d}  f32={fstr}")
    # Float3 run scan
    scan_end = min(phys+look_bytes, TOTAL)
    bs, bc = scan_float3(D, phys, scan_end)
    if bs >= 0:
        rel = bs - phys
        n = (bc+2)//3
        x0,y0,z0 = rf(D,bs), rf(D,bs+4), rf(D,bs+8)
        print(f"  => Float3 run: {bc} steps at +{rel}, ~{n} verts, v0=({x0:.3f},{y0:.3f},{z0:.3f})")

# ===== STANDING TORCH =====
print("\n" + "="*70)
print("STANDING TORCH - Virtual address analysis")
virt, size = EXPORTS['standing_torch']
phys = virt - BV
print(f"phys={phys} size={size}")

# Values in export header that could be virtual addresses (virt in [BV, BV+TOTAL])
print("\nScanning export for virtual address values...")
for k in range(size//4):
    p = phys + k*4
    v = ri32(D, p)
    if BV < v < BV + TOTAL and v != virt:  # != own address, within range
        rel = k*4
        print(f"  +{rel}: i32={v} → phys={v-BV}")

print("\n--- Checking specific known virtual addresses from header ---")
# From dump: [144]=7889510
virt144 = ri32(D, phys+144)
check_at_virt(virt144, "+144 value")

# From dump: [48]=3683130 (suspected to be in a header too)
virt48 = ri32(D, phys+48)
check_at_virt(virt48, "+48 value")

# ===== WOODEN RAIL =====
print("\n" + "="*70)
print("MAFIA WOODEN RAIL - Virtual address analysis")
virt, size = EXPORTS['mafia_wooden_rail']
phys = virt - BV
print(f"phys={phys} size={size}")

print("\nScanning export for virtual address values...")
found_virts = []
for k in range(size//4):
    p = phys + k*4
    v = ri32(D, p)
    if BV < v < BV + TOTAL and v != virt:
        rel = k*4
        found_virts.append((rel, v))

# Show only ones that are NOT within a big index buffer
# The big idx buffer at +6516 contains many values up to 44M which could look like virt addrs
# Filter: only show those outside the index buffer range
idx_start = 6516 + 4
idx_end = idx_start + 66210 * 2
print(f"  (Idx buffer spans [{idx_start}, {idx_end}], filtering those out)")
for (rel, v) in found_virts:
    if rel < idx_start or rel >= idx_end:
        print(f"  +{rel}: i32={v} → phys={v-BV}")
    
# Check some specific ones
print("\n--- Checking values from pre-idx LOD table ---")
# From analyze_focus.py dump: the pre-idx region has large values like 44237448
# Let's scan the pre-idx region specifically
pre_idx_virts = []
for k in range(6516//4):
    p = phys + k*4
    v = ri32(D, p)
    if BV < v < BV + TOTAL and v != virt:
        pre_idx_virts.append((k*4, v))

print(f"  Found {len(pre_idx_virts)} virtual address candidates in pre-idx region")
for (rel, v) in pre_idx_virts[:20]:
    target_phys = v - BV
    print(f"  +{rel}: virtAddr={v} → targetPhys={target_phys}")
    # Quick check: what's the first plausible float3 at that target?
    if target_phys + 16 < TOTAL:
        x0,y0,z0 = rf(D,target_phys), rf(D,target_phys+4), rf(D,target_phys+8)
        fstr = f"({x0:.3f},{y0:.3f},{z0:.3f})"
        # Also check subsequent 4 bytes for count header pattern
        count_val = ri32(D, target_phys)
        stride_after = ri32(D, target_phys+4)
        print(f"    first 3 floats={fstr}  i32[0]={count_val}  i32[1]={stride_after}")

print("\nDone.")
