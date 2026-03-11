"""Look for compressed int16 vertex positions in failing mesh exports."""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ri16(d,o): return struct.unpack_from('<h',d,o)[0]
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
print(f"BV={BV}")

# Also load mafia_town.umap for the working Skydome/Moon mesh
with open('run/umapica/mafia_town.umap','rb') as f: raw2 = f.read()
pos=8; pos+=4
slen=ri32(raw2,pos); pos+=4
if slen>0: pos+=slen
pos+=4; pos+=4+4; pos+=4+4; pos+=4+4
pos+=4+4+12+16; gc=ri32(raw2,pos); pos+=4+gc*8+12
ru32(raw2,pos); pos+=4; chunk_count2=ri32(raw2,pos); pos+=4
chunks2=[]
for _ in range(chunk_count2):
    uo=ru32(raw2,pos); us=ru32(raw2,pos+4); co=ru32(raw2,pos+8); cs=ru32(raw2,pos+12); pos+=16
    chunks2.append((uo,us,co,cs))
total2=sum(c[1] for c in chunks2); D2=bytearray(total2); dest=0
for (uo,us,co,cs) in chunks2:
    fp=co; fp+=4; bs=ri32(raw2,fp); fp+=8; ut=ri32(raw2,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw2,fp)); fp+=4; su2.append(ri32(raw2,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw2[fp:fp+cs2]; fp+=cs2
        D2[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]; dest+=us2
BV2 = chunks2[0][0]

print("\n=== Comparing Skydome (working, float32) vs Wooden_Rail (failing) ===")
print("Skydome phys structure at propEnd+9576 (known position start):")
sky_base = 32845387 - BV2
# show 16 bytes before position data (the header/field before positions)
for off in range(-32, 20, 4):
    p = sky_base + 9576 + off
    print(f"  +{9576+off:6d}: i32={ri32(D2,p):8d}  f32={rf(D2,p):12.4f}  hex=0x{ru32(D2,p):08x}")

print()
print("Wooden_Rail at propEnd+0 to +300 - integers to find structure:")
rail_base = 31486718 - BV
# The propEnd for rail might be ~24 bytes in based on analysis above
# Let's scan for ALL candidates of nVerts near 671 in first 8K
print("  Scan for i32 == 671 in first 8000 bytes:")
for off in range(0, 8000, 4):
    v = ri32(D, rail_base + off)
    if v == 671:
        # show context
        ctx = [ri32(D, rail_base + off + i*4) for i in range(-4, 6)]
        print(f"    +{off}: ...{ctx}...")

print()
print("  Scan for i32 in [650..700] range:")
for off in range(0, 8000, 4):
    v = ri32(D, rail_base + off)
    if 650 <= v <= 700:
        ctx = [ri32(D, rail_base + off + i*4) for i in range(-2, 4)]
        print(f"    +{off}: val={v} ctx={ctx}")

print()
# Look for int16 patterns (compressed vertex data) - groups of 3 int16s
# that look like position data with spread values
print("  Looking for int16 position run (nVerts=671, range scan):")
# The actual vertex coords for the rail would be 333-5877 UU range
# As int16 scaled to [-32768, 32767] mapped to bounding box:
# raw_i16 is in [-32768, 32767], actual = i16/32768.0 * half_extent + center
# For any reasonable mesh, we'd see varied int16 values (not all zeros, not all same)
best_run_off = -1; best_run_len = 0
for start_off in range(0, 8000, 2):
    cnt = 0; ok = True
    for vi in range(700):  # up to 700 verts
        p = rail_base + start_off + vi*6  # 3×int16 per vert = 6 bytes
        if p+6 > rail_base + 8000: break
        x = ri16(D, p); y = ri16(D, p+2); z = ri16(D, p+4)
        # Check for "varied" signed int16 that looks like position data
        if max(abs(x), abs(y), abs(z)) < 100:  # too small - likely noise
            break
        if max(abs(x), abs(y), abs(z)) > 32500:  # probably garbage
            break
        cnt += 1
    if cnt > best_run_len:
        best_run_len = cnt; best_run_off = start_off

if best_run_off >= 0:
    print(f"  Best int16 run: +{best_run_off} len={best_run_len} verts")
    for vi in range(min(5, best_run_len)):
        p = rail_base + best_run_off + vi*6
        x,y,z = ri16(D,p), ri16(D,p+2), ri16(D,p+4)
        print(f"    v[{vi}]=({x},{y},{z})")
else:
    print("  No int16 position run found")

print("\nDone.")
