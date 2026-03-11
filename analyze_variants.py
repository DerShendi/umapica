"""Search for UE3 position buffer variants: [stride=12, n, n, data] or [stride=12, n, data]."""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
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

def check_float3_run(D, start, n, max_bytes):
    """Check if n float3s starting at 'start' are all plausible vertex positions."""
    if start + n*12 > start + max_bytes: return False
    ok_count = 0
    for vi in range(n):
        p = start + vi*12
        x, y, z = rf(D, p), rf(D, p+4), rf(D, p+8)
        if (math.isfinite(x) and abs(x) < 1e5 and
            math.isfinite(y) and abs(y) < 1e5 and
            math.isfinite(z) and abs(z) < 1e5):
            ok_count += 1
        else:
            return False
    return ok_count == n

def search_pos_variants(D, phys, sz, label):
    end = phys + sz
    print(f"\n--- {label} ({sz} bytes): searching all position buffer variants ---")
    found_any = False

    for p in range(phys, end - 24, 4):
        a = ri32(D, p)     # stride
        b = ri32(D, p+4)   # numVerts

        # Variant 1: [stride=12, numVerts, elementSize=12, count=numVerts, data]  (KNOWN WORKING)
        if a == 12 and 10 < b < 100000:
            c = ri32(D, p+8)
            d_ = ri32(D, p+12)
            if c == 12 and d_ == b:
                data_start = p + 16
                if check_float3_run(D, data_start, min(b, 5), sz):
                    v0 = (rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8))
                    print(f"  +{p-phys}: VAR1 [12,{b},12,{b}] v0={v0}")
                    found_any = True

        # Variant 2: [stride=12, numVerts, count=numVerts (no repeat stride), data]
        if a == 12 and 50 < b < 100000:
            c = ri32(D, p+8)
            if c == b:  # c is count (same as numVerts), d_ = start of data
                data_start = p + 12
                if check_float3_run(D, data_start, min(b, 5), sz):
                    v0 = (rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8))
                    print(f"  +{p-phys}: VAR2 [12,{b},{b}] v0={v0}")
                    found_any = True

        # Variant 3: [stride=12, numVerts, data] (only 8-byte header)
        if a == 12 and 50 < b < 100000:
            data_start = p + 8
            if data_start + b*12 <= end:
                if check_float3_run(D, data_start, min(b, 8), sz):
                    # Further verify: all b verts valid
                    if check_float3_run(D, data_start, b, sz):
                        v0 = (rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8))
                        print(f"  +{p-phys}: VAR3 [12,{b}] v0={v0}")
                        found_any = True

        # Variant 4: [numVerts, data] (only 4-byte header, no stride)
        if 50 < a < 100000:
            data_start = p + 4
            if data_start + a*12 <= end:
                if check_float3_run(D, data_start, min(a, 8), sz):
                    if check_float3_run(D, data_start, a, sz):
                        v0 = (rf(D,data_start), rf(D,data_start+4), rf(D,data_start+8))
                        print(f"  +{p-phys}: VAR4 [count={a}] v0={v0}")
                        found_any = True

        # Variant 5: [stride=16, numVerts, elementSize=16, count, data] (FLOAT3 + padding)
        if a == 16 and 10 < b < 100000:
            c = ri32(D, p+8)
            d_ = ri32(D, p+12)
            if c == 16 and d_ == b:
                data_start = p + 16
                # Read float3 with 16-byte stride (x,y,z, pad)
                ok = True
                verts = []
                for vi in range(min(b, 5)):
                    fp = data_start + vi*16
                    if fp+12 > end: ok=False; break
                    x,y,z = rf(D,fp), rf(D,fp+4), rf(D,fp+8)
                    if not (math.isfinite(x) and abs(x)<1e5 and math.isfinite(y) and abs(y)<1e5 and math.isfinite(z) and abs(z)<1e5):
                        ok=False; break
                    verts.append((x,y,z))
                if ok:
                    print(f"  +{p-phys}: VAR5 [16,{b},16,{b}] stride16 v0={verts[0]}")
                    found_any = True

    if not found_any:
        print("  NONE FOUND")

EXPORTS = [
    ('standing_torch',         30739943,  42674),
    ('mafia_wall_light',       31348149, 104622),
    ('mafia_wooden_rail',      31486718, 140315),
    ('cardboard_roundBush',    31689455,   4762),
    ('cardboard_plant',        31681108,   4542),
    ('cardboard_Building',     31652707,   9753),  # confirmed working
    ('mafia_theatre_podium_med', 31323921, 12113),  # confirmed working
]

for name, virt, sz in EXPORTS:
    search_pos_variants(D, virt - BV, sz, name)

print("\nDone.")
