"""Search for FPositionVertexBuffer [stride=12, n, 12, n] pattern and check post-index region."""
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

def find_pos_buffer_header(D, phys, sz, label):
    """Search for [stride=12, nVerts, 12, nVerts, float3_data...] pattern."""
    end = phys + sz
    print(f"\n--- {label}: searching for [12, n, 12, n] header in {sz} bytes ---")
    found = []
    for p in range(phys, end - 20, 4):
        a = ri32(D, p)
        b = ri32(D, p+4)
        c = ri32(D, p+8)
        d_ = ri32(D, p+12)
        # pattern: stride=12, numVerts, elementSize=12, count=numVerts
        if a == 12 and c == 12 and b == d_ and 5 < b < 200000:
            data_start = p + 16
            # verify first few float3s
            x0, y0, z0 = rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8)
            ok = all(math.isfinite(v) and abs(v) < 1e6 for v in (x0, y0, z0))
            found.append((p - phys, b, x0, y0, z0, ok))
            if ok:
                print(f"  +{p-phys}: numVerts={b}  v0=({x0:.3f},{y0:.3f},{z0:.3f}) ✓")
            else:
                print(f"  +{p-phys}: numVerts={b}  v0=({x0},{y0},{z0}) (not float)")
        # Also check stride != 12 (might be 6 for packed)
        if a in (6, 8) and c == a and b == d_ and 5 < b < 200000:
            data_start = p + 16
            # read as int16 if stride=6
            if a == 6:
                x0 = struct.unpack_from('<h', D, data_start)[0]
                y0 = struct.unpack_from('<h', D, data_start+2)[0]
                z0 = struct.unpack_from('<h', D, data_start+4)[0]
                print(f"  +{p-phys}: PACKED stride=6 numVerts={b}  vi0=({x0},{y0},{z0})")
    if not found:
        print("  NOT FOUND")
    return found

# Also look for just [stride=12, numVerts=X] followed by valid float data at various positions
def find_simple_pos_header(D, phys, sz, label, max_nv=50000):
    end = phys + sz
    print(f"\n--- {label}: searching for [12, nVerts, float3...] or [nVerts, 12, float3...] ---")
    for p in range(phys, end - 20, 4):
        stride = ri32(D, p)
        nv = ri32(D, p+4)
        if stride == 12 and 5 < nv < max_nv:
            data_start = p + 8
            x0, y0, z0 = rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8)
            if all(math.isfinite(v) and -1e5 < v < 1e5 for v in (x0, y0, z0)):
                x1, y1, z1 = rf(D, data_start+12), rf(D, data_start+16), rf(D, data_start+20)
                ok1 = all(math.isfinite(v) and -1e5 < v < 1e5 for v in (x1, y1, z1))
                if ok1:
                    print(f"  +{p-phys}: [12, {nv}]  v0=({x0:.3f},{y0:.3f},{z0:.3f})  v1=({x1:.3f},{y1:.3f},{z1:.3f})")

EXPORTS = [
    ('standing_torch',         30739943,  42674),
    ('mafia_wall_light',       31348149, 104622),
    ('mafia_wooden_rail',      31486718, 140315),
    ('mafia_theatre_podium_med', 31323921, 12113),  # WORKING - for comparison
]

for name, virt, sz in EXPORTS:
    phys = virt - BV
    find_pos_buffer_header(D, phys, sz, name)

print("\n=== Checking post-index-buffer region for wooden_rail ===")
rail_phys = 31486718 - BV
rail_sz = 140315
# Index buffer at +6516, n=66210 => ends at +6516 + 4 + 66210*2 = +138940
idx_end_off = 6516 + 4 + 66210 * 2
post_start = rail_phys + idx_end_off
post_end = rail_phys + rail_sz
print(f"Post-index region: +{idx_end_off} to +{rail_sz}  ({post_end - post_start} bytes)")
print("Bytes at post-index region:")
for i in range(min((post_end - post_start) // 4, 60)):
    p = post_start + i*4
    if p+4 > post_end: break
    print(f"  +{idx_end_off + i*4}: i32={ri32(D,p):8d}  f32={rf(D,p):12.4f}  hex=0x{ru32(D,p):08x}")

print("\nDone.")
