"""Deep dive into standing_torch structure - search for actual render mesh data."""
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

torch_phys = 30739943 - BV
torch_sz = 42674
torch_end = torch_phys + torch_sz

# The index buffer at +324 is n=3231 (collision hull?).
# That takes up 4 + 3231*2 = 6466 bytes, ending at +324+6466 = +6790.
# Let's look at what's at +6790 in standing_torch.
idx_hull_end = 324 + 4 + 3231 * 2
print(f"Hull index buffer ends at +{idx_hull_end}")
print(f"Export total: {torch_sz} bytes, remaining after hull idx: {torch_sz - idx_hull_end}")

print(f"\n=== standing_torch: dump at +{idx_hull_end} to +{idx_hull_end + 300} ===")
for i in range(75):
    p = torch_phys + idx_hull_end + i*4
    if p+4 > torch_end: break
    print(f"  +{idx_hull_end+i*4:6d}: i32={ri32(D,p):10d}  f32={rf(D,p):12.4f}  hex=0x{ru32(D,p):08x}")

# Search for FPositionVertexBuffer [stride, numVerts, elementSize, count] in the render section
render_start = torch_phys + idx_hull_end
print(f"\n=== Search for [stride, n, stride, n] AFTER hull section ===")
for p in range(render_start, torch_end - 20, 4):
    a = ri32(D, p)
    b = ri32(D, p+4)
    c = ri32(D, p+8)
    d_ = ri32(D, p+12)
    if a == c and b == d_ and 4 <= a <= 20 and 5 < b < 200000:
        off = p - torch_phys
        data_start = p + 16
        if a == 12:
            v0 = (rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8))
            print(f"  +{off}: [{a},{b},{c},{d_}]  v0={v0}")
        else:
            raw_vals = [ru16(D, data_start+i*2) for i in range(6)]
            print(f"  +{off}: [{a},{b},{c},{d_}]  firstU16={raw_vals}")

# Also look for large index buffers in the render section
print(f"\n=== Valid index buffers after hull section (max_idx < 5000) ===")
for p in range(render_start, torch_end - 8, 2):
    n = ri32(D, p)
    if 12 <= n <= 100000 and (n%3)==0 and p + 4 + n*2 <= torch_end:
        vals = [ru16(D, p+4+i*2) for i in range(min(n, 20))]
        max_v = max(vals)
        if max_v < 5000 and max_v >= 3:
            unique = len(set(vals[:12]))
            if unique > 4:
                off = p - torch_phys
                first6 = vals[:6]
                print(f"  +{off}: n={n} max={max_v} first6={first6}")

print("\nDone.")
