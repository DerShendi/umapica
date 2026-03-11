"""Search for FPositionVertexBuffer with ANY stride, and also try simple [stride, nVerts] headers."""
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

def search_any_stride_nv(D, phys, sz, label):
    end = phys + sz
    print(f"\n--- {label} ({sz} bytes): [stride, n, stride, n, float3...] for any stride 4-16 ---")
    found = []
    for p in range(phys, end - 24, 4):
        a = ri32(D, p)
        b = ri32(D, p+4)
        c = ri32(D, p+8)
        d_ = ri32(D, p+12)
        if a == c and b == d_ and 4 <= a <= 16 and 5 < b < 200000:
            data_start = p + 16
            stride = a
            nv = b
            # Try reading first 3 verts if stride=12 (float3)
            results = []
            if stride == 12:
                for vi in range(min(3, nv)):
                    fp = data_start + vi*12
                    if fp+12 > end: break
                    x,y,z = rf(D,fp), rf(D,fp+4), rf(D,fp+8)
                    results.append((x,y,z))
            elif stride >= 6:
                # try reading as int16 triples
                import struct as st
                for vi in range(min(3, nv)):
                    fp = data_start + vi*stride
                    if fp+6 > end: break
                    x = st.unpack_from('<h', D, fp)[0]
                    y = st.unpack_from('<h', D, fp+2)[0]
                    z = st.unpack_from('<h', D, fp+4)[0]
                    results.append((x,y,z))
            print(f"  +{p-phys}: stride={stride} nVerts={nv}  first3={results}")
            found.append((p-phys, stride, nv, results))
    if not found:
        print("  None found")
    return found

def search_simple_header(D, phys, sz, label):
    """Try [stride, nVerts] 2-field header (8 bytes) only, then verify float3."""
    end = phys + sz
    print(f"\n--- {label}: [stride, nVerts] simple header ---")
    for p in range(phys, end - 16, 4):
        a = ri32(D, p)    # stride
        b = ri32(D, p+4)  # numVerts
        if a == 12 and 5 < b < 100000:
            data_start = p + 8
            x0, y0, z0 = rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8)
            if all(math.isfinite(v) and abs(v) < 1e5 for v in (x0,y0,z0)):
                x1, y1, z1 = rf(D, data_start+12), rf(D, data_start+16), rf(D, data_start+20)
                if all(math.isfinite(v) and abs(v) < 1e5 for v in (x1,y1,z1)):
                    print(f"  +{p-phys}: stride=12 nVerts={b}  v0=({x0:.3f},{y0:.3f},{z0:.3f})  v1=({x1:.3f},{y1:.3f},{z1:.3f})")

def dump_range(D, phys, start_off, n_ints, label):
    """Dump n_ints int32s starting at phys+start_off."""
    print(f"\n--- {label}: dump at +{start_off} ---")
    for i in range(n_ints):
        p = phys + start_off + i*4
        print(f"  +{start_off+i*4:6d}: i32={ri32(D,p):10d}  f32={rf(D,p):12.4f}  hex=0x{ru32(D,p):08x}")

EXPORTS = [
    ('standing_torch',         30739943,  42674),
    ('mafia_wall_light',       31348149, 104622),
    ('mafia_wooden_rail',      31486718, 140315),
]

for name, virt, sz in EXPORTS:
    phys = virt - BV
    search_any_stride_nv(D, phys, sz, name)

# Also dump wooden_rail around +208 area more carefully
print("\n\n=== Wooden_rail dump at +148 to +240 (just before float data) ===")
rail_phys = 31486718 - BV
dump_range(D, rail_phys, 148, 30, "wooden_rail")

print("\n=== standing_torch dump at +132 to +300 ===")
torch_phys = 30739943 - BV
dump_range(D, torch_phys, 132, 50, "standing_torch")

print("\nDone.")
