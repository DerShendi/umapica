"""Detailed structural dump of failing exports to find position data."""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

# Decompress
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
print(f"Decomp: {total} bytes  BV={BV}")

# Export offsets
EXPORTS = {
    'standing_torch':   (30739943, 42674),
    'mafia_wooden_rail': (31486718, 140315),
    'mafia_wall_light':  (31348149, 104622),
}

def is_plausible(x,y,z, lo=-5000, hi=5000, mn=0.1):
    return (math.isfinite(x) and lo<x<hi and
            math.isfinite(y) and lo<y<hi and
            math.isfinite(z) and lo<z<hi and
            max(abs(x),abs(y),abs(z))>mn)

def stride_scan(D, phys, size, target_n, max_stride=40):
    """Find target_n consecutive plausible float3 positions at any stride & any vertex-start offset."""
    best = []
    for stride in range(8, max_stride+1, 4):
        for voff in range(0, stride-8, 4):  # position within vertex record
            needed = target_n * stride
            if needed > size: continue
            for base in range(phys, phys + size - needed, 4):
                ok_count = 0
                for vi in range(target_n):
                    p = base + vi*stride + voff
                    if p+12 > phys+size: break
                    x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
                    if not is_plausible(x,y,z): break
                    ok_count += 1
                if ok_count == target_n:
                    rel = base - phys
                    best.append((stride, voff, rel))
    return best

for name, (virt, size) in EXPORTS.items():
    phys = virt - BV
    print(f"\n{'='*60}")
    print(f"EXPORT: {name}  virt={virt}  phys={phys}  size={size}")

    # Dump first 80 i32 values
    print("  First 80 dwords:")
    for k in range(min(80, size//4)):
        v = ri32(D, phys + k*4)
        fv = rf(D, phys + k*4)
        fstr = f"{fv:.3f}" if math.isfinite(fv) and abs(fv)<1e6 else "---"
        print(f"    [{k*4:4d}] i32={v:10d}  u32={v&0xFFFFFFFF:10d}  float={fstr}")

    # Find first index buffer (legit: n%3==0, max < 10000, ordered indices)
    print("\n  Scanning for index buffers (max<10000):")
    for p in range(phys, phys+size-8, 2):
        n = ri32(D, p)
        if 6<=n<=500000 and (n%3)==0 and p+4+n*2 <= phys+size:
            max_i=0; ok=True
            for i in range(min(n,64)):
                v=ru16(D,p+4+i*2)
                if v>9999: ok=False; break
                if v>max_i: max_i=v
            if not ok or max_i<3: continue
            first6=[ru16(D,p+4+i*2) for i in range(6)]
            rel = p - phys
            print(f"    +{rel}: n={n} max={max_i} tris={n//3} verts~={max_i+1} first6={first6}")
            idx_end = p+4+n*2 - phys
            remaining = size - idx_end
            print(f"      ends at +{idx_end}, remaining={remaining}")
            # check 16 bytes after index
            check_after = [ri32(D, p+4+n*2+i*4) for i in range(4)] if p+4+n*2+16 < phys+size else []
            print(f"      bytes after: {check_after}")
            if remaining >= (max_i+1)*12:
                print(f"      *** {remaining} bytes remaining >= {(max_i+1)*12} needed for float3 ***")
            break  # just show first

    print(f"\n  Only checking one index buffer. Break.")
    break  # also just process standing_torch first for speed

# Now do stride scan for wooden_rail specifically (695 verts max)
print("\n\n=== STRIDE SCAN: mafia_wooden_rail ===")
virt, size = EXPORTS['mafia_wooden_rail']
phys = virt - BV
# First identify the real idx buffer
real_idx_off = 6516  # confirmed from prior analysis
real_idx_n = 66210
real_max = 670
target_n = real_max + 1  # 671

print(f"Target: {target_n} verts")
print(f"Pre-idx region: +0 to +{real_idx_off} = {real_idx_off} bytes")
print(f"Post-idx region: +{real_idx_off+4+real_idx_n*2} to +{size} = {size-(real_idx_off+4+real_idx_n*2)} bytes")

# Pre-idx stride scan (search in first 6516 bytes)
print(f"\nStride scan in pre-idx region (TARGET={target_n} verts)...")
found = stride_scan(D, phys, real_idx_off, target_n)
if found:
    for (stride, voff, rel) in found[:5]:
        print(f"  FOUND: stride={stride} voff={voff} base=+{rel}")
        # print first few verts
        for vi in range(min(4, target_n)):
            p = phys + rel + vi*stride + voff
            print(f"    v[{vi}] = ({rf(D,p):.3f}, {rf(D,p+4):.3f}, {rf(D,p+8):.3f})")
else:
    print("  Nothing found in pre-idx region.")

# Post-idx stride scan
post_start = real_idx_off + 4 + real_idx_n * 2
post_size = size - post_start
print(f"\nStride scan in post-idx region ({post_size} bytes)...")
if post_size >= target_n * 6:
    found2 = stride_scan(D, phys+post_start, post_size, target_n)
    if found2:
        for (stride, voff, rel) in found2[:5]:
            print(f"  FOUND: stride={stride} voff={voff} base=post+{rel}")
    else:
        print("  Nothing found in post-idx region.")
else:
    print("  Post-idx region too small.")

print("\nDone.")
