"""
Search for FPositionVertexBuffer header [12, N, 12, N] for all failing meshes.
Also check FByteBulkData structure (flags, elementCount, sizeOnDisk, serialOffset).
And dump the 30 dwords before/after each found virtual address in exports.
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

# Search the whole decompressed file for [12, N, 12, N] pattern
# where N is between 100 and 100000
print("\nSearching for [12, N, 12, N] FPositionVertexBuffer headers in full decompressed file:")
hits = []
for p in range(0, total-16, 4):
    v1 = ri32(D,p)
    if v1 == 12:
        v2 = ri32(D,p+4)
        if 100 <= v2 <= 100000:
            v3 = ri32(D,p+8)
            v4 = ri32(D,p+12)
            if v3 == 12 and v4 == v2:
                hits.append((p, v2))

print(f"Total hits: {len(hits)}")

EXPORTS = {
    'cardboard_Building':        (25427396-929, 57436),
    'podium':                    (25754060-929, 67776),
    'standing_torch':            (30739943-929, 42674),
    'mafia_wall_light':          (31348149-929, 104622),
    'mafia_wooden_rail':         (31486718-929, 140315),
}

for (phys, n) in hits:
    # Identify which export this is near
    label = "?"
    rel_str = ""
    for name,(ep,es) in EXPORTS.items():
        if ep <= phys < ep+es:
            label = name
            rel_str = f" [INLINE +{phys-ep}]"
            break
        # Check if it's in a region pointed to from one of our exports
    nv = count_float3s(D, phys+16, mn=0.001)
    print(f"  phys={phys} N={n}{rel_str} (label={label}) -> float3_run_after={nv}")

# For working meshes (cardboard, podium) find their FPositionVertexBuffer
print("\n\nVerifying working meshes:")
cb_phys = 25427396-929; cb_size=57436
pd_phys = 25754060-929; pd_size=67776

for p in range(0, total-16, 4):
    v1 = ri32(D,p); v3 = ri32(D,p+8)
    if v1 != 12 or v3 != 12: continue
    v2 = ri32(D,p+4); v4 = ri32(D,p+12)
    if v2 != v4 or not (100<=v2<=100000): continue
    if cb_phys <= p < cb_phys+cb_size:
        print(f"  cardboard: +{p-cb_phys}, N={v2}")
        fn = count_float3s(D, p+16, mn=0.001)
        print(f"    float3 run after header: {fn}")
    if pd_phys <= p < pd_phys+pd_size:
        print(f"  podium: +{p-pd_phys}, N={v2}")
        fn = count_float3s(D, p+16, mn=0.001)
        print(f"    float3 run after header: {fn}")

# For standing_torch: check FByteBulkData pattern around +144 and +48
print("\n\nStanding torch - FByteBulkData check:")
st_phys = 30739943-929
for rel in [48, 144]:
    print(f"\n  at +{rel}: 16 preceding bytes (presumed FByteBulkData header):")
    for i in range(-4,5):
        off = rel + i*4
        if off >= 0:
            v = ri32(D, st_phys+off)
            fv = rf(D, st_phys+off)
            print(f"    [{off:4d}] int={v:12d}  f={fv:.4f}")

# For wooden rail: check FByteBulkData around +148 and +139608
print("\n\nWooden rail - FByteBulkData check:")
wr_phys = 31486718-929
for rel in [148, 139608]:
    print(f"\n  at +{rel}: surrounding 16 bytes:")
    for i in range(-4,5):
        off = rel + i*4
        if off >= 0 and off < 140315:
            v = ri32(D, wr_phys+off)
            fv = rf(D, wr_phys+off)
            print(f"    [{off:4d}] int={v:12d}  f={fv:.4f}")

# Also: check the virtual addresses in ST at +1888-+2552 range - are they hull indices?
print("\n\nST hull indices sample (first 10 at +1888):")
for i in range(10):
    v = ri32(D, st_phys+1888+i*4)
    print(f"  [{1888+i*4}] {v}")

# And inline scan from +9686 for first 80 dwords
print("\nST inline +9686 first 80 dwords:")
for i in range(80):
    off = 9686+i*4
    v = ru32(D, st_phys+off)
    fv = rf(D, st_phys+off)
    ok=""
    if math.isfinite(fv) and -200000<fv<200000 and abs(fv)>0.001: ok=" <--"
    if 100<=v<=50000: ok=f" <-- count:{v}"
    print(f"  [{off:6d}] {v:12d}  {fv:14.4f}{ok}")

print("\nDone.")
