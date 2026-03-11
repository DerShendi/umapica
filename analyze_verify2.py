"""
Final verification: check exact position buffer offsets and index buffers.
Also scan standing_torch's +48 virtual address.
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
# WOODEN RAIL
# =============================================
print("\n=== WOODEN RAIL ===")
wr_phys = 31486718 - BV   # =31485789
wr_size = 140315

# LOD0 index buffer at +6516
n_idx = ru32(D, wr_phys+6516)
print(f"LOD0 index count at +6516: {n_idx}")
max_idx = 0
sum_triangles = n_idx // 3
for i in range(n_idx):
    v = ru16(D, wr_phys+6520+i*2)
    if v > max_idx: max_idx = v
print(f"LOD0 max_index: {max_idx}  -> {max_idx+1} render verts")
print(f"LOD0 triangles: {sum_triangles}")

# Position buffer -- at target of export +139608
pos_virt = ru32(D, wr_phys+139608)
pos_phys = pos_virt - BV
pos_run_start = pos_phys + 22200
print(f"\nPosition buf virt={pos_virt} phys={pos_phys}")
print(f"Float3 run start: phys={pos_run_start}")

# Check header bytes BEFORE the run start
print("16 bytes before float3 run (at run-16):")
for i in range(4):
    v32 = ru32(D, pos_phys+22200-16+i*4)
    f32 = rf(D, pos_phys+22200-16+i*4)
    print(f"  [{-16+i*4}]: int={v32} float={f32:.4f}")

# Count float3s with low threshold
nv = count_float3s(D, pos_run_start, mn=0.001)
print(f"Float3 count (mn=0.001): {nv}")
nv2 = count_float3s(D, pos_run_start, mn=0.0)
print(f"Float3 count (mn=0.0, just finite+bounded): {nv2}")

# Show first 5 verts
print("First 5 verts:")
for i in range(5):
    p2 = pos_run_start+i*12
    print(f"  [{i}] ({rf(D,p2):.3f}, {rf(D,p2+4):.3f}, {rf(D,p2+8):.3f})")

# Show last 5 verts at index position
end_pos = pos_run_start + nv2*12
print(f"Bytes after float3 run: examining +{nv2*12} to +{nv2*12+32}")
for i in range(8):
    p2 = end_pos+i*4
    print(f"  [{nv2*12+i*4}]: int={ru32(D,p2)} float={rf(D,p2):.4f}")

# ALSO: check for inline position buffer header [12, N, 12, N] before float3 run
print(f"\nChecking for [12,N,12,N] header at pos_run_start-16:")
v1 = ri32(D, pos_run_start-16)
v2 = ri32(D, pos_run_start-12)
v3 = ri32(D, pos_run_start-8)
v4 = ri32(D, pos_run_start-4)
print(f"  [{v1}, {v2}, {v3}, {v4}]")
if v1==12 and v1==v3 and v2==v4:
    print(f"  *** MATCH! FPositionVertexBuffer header: stride=12, count={v2} ***")

# =============================================
# STANDING TORCH
# =============================================
print("\n=== STANDING TORCH ===")
st_phys = 30739943 - BV  # =30739014
st_size = 42674

# Virtual address at +48
virt48 = ru32(D, st_phys+48)
if BV < virt48 < BV+total:
    phys48 = virt48 - BV
    print(f"+48 virt={virt48} phys={phys48}")
    
    # Scan phys48 for float3 runs
    best_n, best_p = 0, -1
    p = phys48
    end = min(phys48+300000, total-12)
    while p < end:
        x,y,z = rf(D,p),rf(D,p+4),rf(D,p+8)
        if (math.isfinite(x) and -200000<x<200000 and
            math.isfinite(y) and -200000<y<200000 and
            math.isfinite(z) and -200000<z<200000 and
            max(abs(x),abs(y),abs(z))>0.01):
            n = count_float3s(D, p, mn=0.01)
            if n > best_n: best_n=n; best_p=p
            p += n*12
        else:
            p += 4
    if best_p > 0:
        rel = best_p - phys48
        x0,y0,z0 = rf(D,best_p),rf(D,best_p+4),rf(D,best_p+8)
        print(f"  Best float3 run: {best_n} verts at rel=+{rel}, v0=({x0:.3f},{y0:.3f},{z0:.3f})")
        # Check header
        if rel >= 16:
            v1=ri32(D,best_p-16); v2=ri32(D,best_p-12); v3=ri32(D,best_p-8); v4=ri32(D,best_p-4)
            print(f"  Header check [-16,-12,-8,-4]: [{v1},{v2},{v3},{v4}]")
            if v1==12 and v1==v3 and v2==v4:
                print(f"  *** FPositionVertexBuffer header! count={v2} ***")
    else:
        print(f"  No float3 run >= 1 found at phys48.")
    
    # First 40 dwords
    print(f"  First 20 dwords at phys48:")
    for i in range(20):
        v32=ru32(D,phys48+i*4); f32=rf(D,phys48+i*4)
        print(f"  [{i*4:4d}] int={v32:12d} f={f32:14.4f}")

# Check inline: render vert count at +156 = 1486?
st_rend_v = ri32(D, st_phys+156)
print(f"\nST render vert count at +156: {st_rend_v}")

# Scan the full export for 2544 or count near it
for try_n in [2544, 2543, 2545, 4488, 4487]:
    for rel in range(0, st_size-4, 4):
        if ru32(D, st_phys+rel) == try_n:
            print(f"  Found {try_n} at export +{rel}")

# Inline scan for index buffer
print("\nSearching inline export for uint16 max near 1485:")
for rel in range(9686, st_size-2, 4):
    n32 = ru32(D, st_phys+rel)
    if 100 <= n32 <= 20000:
        # Check if next n32 uint16s have max close to st_rend_v-1
        if st_phys+rel+4+n32*2 < total:
            mx = max(ru16(D, st_phys+rel+4+i*2) for i in range(min(n32,1000)))
            if abs(mx - (st_rend_v-1)) <= 5:
                print(f"  +{rel}: n={n32} max_idx={mx}")

print("\nDone.")
