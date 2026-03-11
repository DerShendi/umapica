"""Investigate wooden rail and standing torch export structure in detail."""
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

print("=== mafia_wooden_rail first 300 bytes ===")
base = 31486718 - BV  # virt - BV
print(f"phys base = {base}")
for i in range(75):
    p = base + i*4
    v_i = ri32(D, p)
    v_f = rf(D, p)
    v_h = ru32(D, p)
    print(f"  +{i*4:4d}: i32={v_i:8d}  f32={v_f:14.4f}  hex=0x{v_h:08x}")

print()
print("=== Scan for candidate nVerts fields (671) in wooden_rail first 8000 bytes ===")
rail_end = base + 8000
for p in range(base, rail_end, 4):
    v = ri32(D, p)
    if 600 <= v <= 750:  # around 671
        off = p - base
        sc = ri32(D, p+4)
        sc2_v = ri32(D, p-4)
        print(f"  +{off}: val={v}  prev={sc2_v}  next={sc}")

print()
print("=== standing_torch: first 400 bytes ===")
torch_base = 30739943 - BV
print(f"phys base = {torch_base}")
for i in range(100):
    p = torch_base + i*4
    v_i = ri32(D, p)
    v_f = rf(D, p)
    print(f"  +{i*4:4d}: i32={v_i:8d}  f32={v_f:.4f}")

print()
print("=== standing_torch: look after position offset for real index buffer ===")
# From earlier analysis: propEnd run at +292, 15 steps (5 verts)
# The real mesh data might be stored DIFFERENTLY - scan entire export for valid idx
torch_phys = 30739943 - BV
torch_size = 42674
torch_end = torch_phys + torch_size
# Find any n where n divisible by 3, n >= 12, max_idx < 5000, first triangle looks real
print("Index candidates with max_idx < 5000:")
for p in range(torch_phys, torch_end-8, 2):
    n = ri32(D, p)
    if 12 <= n <= 30000 and (n%3)==0 and p+4+n*2 <= torch_end:
        vals = [ru16(D, p+4+i*2) for i in range(min(n, 20))]
        max_v = max(vals)
        if max_v < 5000 and max_v >= 3:
            # Check diversity (not all same or alternating)
            unique = len(set(vals[:12]))
            if unique > 4:
                first6 = vals[:6]
                print(f"  +{p-torch_phys}: n={n} max={max_v} unique12={unique} first6={first6}")
