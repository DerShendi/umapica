"""
Comprehensive analysis:
1. WR: find real max_index (exclude 65535 strip restarts)
2. ST: scan ALL virtual addresses in full export, check for position/index buffers
3. Dump standing_torch inline data after hull end (+9686 to +42674)
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
ru32(raw,pos); pos+=4; cc=ri32(raw,pos); pos+=4
chunks=[]
for _ in range(cc):
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
print(f"BV={BV}")

def count_float3s(D, p, mn=0.001):
    n=0
    while p+12 <= len(D):
        x,y,z = rf(D,p),rf(D,p+4),rf(D,p+8)
        if not (math.isfinite(x) and -200000<x<200000 and
                math.isfinite(y) and -200000<y<200000 and
                math.isfinite(z) and -200000<z<200000 and
                max(abs(x),abs(y),abs(z))>mn):
            break
        n+=1; p+=12
    return n

# =============================================
# WOODEN RAIL: real max index
# =============================================
print("\n=== WOODEN RAIL max_index ===")
wr_phys = 31486718 - BV
n_idx = 66210
real_max = 0
for i in range(n_idx):
    v = ru16(D, wr_phys+6520+i*2)
    if v != 65535 and v > real_max:
        real_max = v
print(f"Real max_index (exc 65535): {real_max}  -> {real_max+1} render verts")
strip_restarts = sum(1 for i in range(n_idx) if ru16(D, wr_phys+6520+i*2)==65535)
print(f"65535 (strip restart) count: {strip_restarts}")

# Check: if no 65535, maybe the index buffer is at a different position
# Try reading at +6518 (2-byte shift)
first_idx_at_6520 = ru16(D, wr_phys+6520)
first_idx_at_6522 = ru16(D, wr_phys+6522)
print(f"First uint16 at +6520: {first_idx_at_6520}")
print(f"First uint16 at +6522: {first_idx_at_6522}")

# Count the 65535s and show distribution
count65535 = 0
for i in range(n_idx):
    if ru16(D, wr_phys+6520+i*2)==65535: count65535+=1
print(f"65535 count: {count65535}")

# =============================================
# STANDING TORCH: all virtual addresses
# =============================================
print("\n=== STANDING TORCH: ALL virtual addresses ===")
st_phys = 30739943 - BV
st_size = 42674

print("All dwords in export that are valid virtual addresses:")
found = []
for k in range(st_size//4):
    p = st_phys + k*4
    if p+4 > total: break
    u = ru32(D,p)
    if BV < u < BV+total and u > BV+100:
        target_phys = u-BV
        found.append((k*4, u, target_phys))

print(f"Total count: {len(found)}")
for (rel,virt,tp) in found[:100]:
    print(f"  +{rel:6d}: virt={virt}  phys={tp}")

# =============================================
# STANDING TORCH: dump bytes +9686 to +42674
# =============================================
print("\n=== STANDING TORCH inline section (+9686 to +42674) ===")
print("Scanning for float3 runs:")
best_n, best_p = 0, -1
p = st_phys+9686
end_p = st_phys+42674
while p < end_p-12:
    x,y,z = rf(D,p),rf(D,p+4),rf(D,p+8)
    if (math.isfinite(x) and -200000<x<200000 and
        math.isfinite(y) and -200000<y<200000 and
        math.isfinite(z) and -200000<z<200000 and
        max(abs(x),abs(y),abs(z))>0.001):
        n = count_float3s(D, p, mn=0.001)
        if n > 10:
            rel = p - st_phys
            print(f"  +{rel:6d}: {n} verts, v0=({x:.3f},{y:.3f},{z:.3f})")
        if n > best_n: best_n=n; best_p=p
        p += max(n*12, 4)
    else:
        p += 4

# Also scan for uint16 arrays (index buffer pattern: count followed by count uint16s < 2000)
print("\nScanning for index buffer pattern (uint32 N, then N uint16 values max<2000):")
for rel in range(9686, st_size-2, 4):
    n32 = ru32(D, st_phys+rel)
    if 100 <= n32 <= 10000:
        end_buf = st_phys+rel+4+n32*2
        if end_buf <= total:
            sample = [ru16(D, st_phys+rel+4+i*2) for i in range(min(n32, 200))]
            mx = max(sample)
            if mx < 2000 and mx > 50:
                print(f"  +{rel}: n={n32} max={mx}")

# Check if vert count 1486 appears in export
print("\nSearching for 1486 as uint32 in export:")
for rel in range(0, st_size-4, 4):
    if ru32(D, st_phys+rel) == 1486:
        print(f"  Found at +{rel}")

# Dump 80 dwords from +27518 (after presumed normals buffer)
print("\n80 dwords from +27518 (post-normals):")
for i in range(80):
    p2 = st_phys+27518+i*4
    v = ru32(D,p2); f = rf(D,p2)
    flag=""
    if math.isfinite(f) and -200000<f<200000 and abs(f)>0.001: flag=" <-- plausible float"
    if 100<=v<=50000: flag=" <-- plausible count"
    print(f"  [{27518+i*4:6d}] {v:12d}  {f:14.4f}{flag}")

print("\nDone.")
