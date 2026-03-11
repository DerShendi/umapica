"""Compare working vs failing mesh export structures."""
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

# WORKING: cardboard_Building posOff=2232 (Java),  virt=31652707, sz=9753
# WORKING: mafia_theatre_podium_med posOff=2852, virt=31323921, sz=12113
# Java propEnd offset = abs_posOff - export_start_posOff
# From comparison: java_posOff = 2232, python_run_offset would be 2232+something

# Let's first confirm cardboard_Building structure
print("=== cardboard_Building first 300 bytes ===")
cb_phys = 31652707 - BV
cb_sz = 9753
for i in range(75):
    p = cb_phys + i*4
    if p >= cb_phys + 300: break
    print(f"  +{i*4:4d}: i32={ri32(D,p):10d}  f32={rf(D,p):12.4f}  hex=0x{ru32(D,p):08x}")

# Java says posOff=2232 which is from propEnd
# Python's run finding would start at propEnd+something
# Let's find [12, n, 12, n] pattern in cardboard_Building
print("\n=== cardboard_Building: searching for [stride, n, stride, n] ===")
for p in range(cb_phys, cb_phys + cb_sz - 20, 4):
    a = ri32(D, p); b = ri32(D, p+4); c = ri32(D, p+8); d_ = ri32(D, p+12)
    if a == c and b == d_ and 4 <= a <= 20 and 5 < b < 50000:
        off = p - cb_phys
        data_start = p + 16
        if a == 12 and p + 16 + 12 <= cb_phys + cb_sz:
            v0 = (rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8))
            if all(math.isfinite(v) and abs(v) < 1e5 for v in v0):
                print(f"  +{off}: [{a},{b}]  v0={v0}")

print()
# Now let's find the propEnd for both working meshes by scanning for property list end
# The Java propEnd is implied by posOff values
# podium: posOff=2852, python run at +2876; propEnd = 2876-2852+correction = need to figure out

# Let's dump cardboard_Building around +2200 to +2300  (posOff=2232)
# First need to know propEnd for cardboard_Building
# Let's just dump the data around posOff=2232 (relative to propEnd)
# Assume propEnd ≈ +24 (typical small prop list) → abs_pos = +2232+24 = +2256
print("=== cardboard_Building: dump at +2200 to +2320 ===")
for off in range(2200, 2320, 4):
    p = cb_phys + off
    if p >= cb_phys + cb_sz: break
    print(f"  +{off}: i32={ri32(D,p):10d}  f32={rf(D,p):12.4f}  hex=0x{ru32(D,p):08x}")

print()
# Now look at failing mesh: standing_torch around +11878 (hypothetical render positions)
# hull index buffer ends at +324 + 4 + 3231*2 = +6790
# render index data starts at +6790 (raw, no count prefix?)
# if render has 848 tris = 2544 indices, ends at +6790 + 2544*2 = +11878
print("=== standing_torch: dump at +11850 to +11950 (hypothetical pos buffer start) ===")
torch_phys = 30739943 - BV
for off in range(11840, 11960, 4):
    p = torch_phys + off
    if p >= torch_phys + 42674: break
    v_i = ri32(D, p); v_f = rf(D, p); v_h = ru32(D, p)
    print(f"  +{off}: i32={v_i:10d}  f32={v_f:12.4f}  hex=0x{v_h:08x}")

print()
# Check if [12, n, 12, n] is near +11878
print("=== standing_torch: [12,n,12,n] search in +11800..+12000 ===")
for off in range(11800, 12000, 4):
    p = torch_phys + off
    if p >= torch_phys + 42674 - 20: break
    a = ri32(D, p); b = ri32(D, p+4); c = ri32(D, p+8); d_ = ri32(D, p+12)
    if a == c and b == d_ and 4 <= a <= 20 and 5 < b < 50000:
        data_start = p + 16
        v0 = (rf(D, data_start), rf(D, data_start+4), rf(D, data_start+8))
        print(f"  +{off}: [{a},{b},{c},{d_}]  v0={v0}")

# Also look for the render index count somewhere before +6790
# Might be in the metadata as one of the early counts
print()
print("=== Check what count=2544 or count=848 appears before +6790 in standing_torch ===")
for off in range(0, 6800, 4):
    p = torch_phys + off
    v = ri32(D, p)
    if v in (2544, 2541, 2543, 2547, 848, 850, 845, 847):
        print(f"  +{off}: i32={v}")

print("\nDone.")
