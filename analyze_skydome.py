"""Focused analysis of Skydome StaticMesh export binary layout."""
import struct, math, lzokay

UMAP = 'run/umapica/mafia_town.umap'
PROP_END = 32844571   # known phys
EXPORT_END = PROP_END + 29737

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

def hexrow(d, off, n=16, label=""):
    row = d[off:off+n]
    hex_s = ' '.join(f'{b:02x}' for b in row)
    asc_s = ''.join(chr(b) if 32<=b<126 else '.' for b in row)
    lbl = f'{label} ' if label else ''
    print(f"  {lbl}+{off-PROP_END:<6d} {hex_s:<48} {asc_s}")

# --- Decompress (exact copy of proven working pattern) ---
with open(UMAP,'rb') as f: raw = f.read()
MAGIC = 0x9E2A83C1
pos=8; pos+=4
slen=ri32(raw,pos); pos+=4
if slen>0: pos+=slen
pos+=4
pos+=4+4; pos+=4+4; pos+=4+4  # name_count/off, exp_count/off, imp_count/off
pos+=4+4+12+16; gc=ri32(raw,pos); pos+=4+gc*8+12
comp_flags=ru32(raw,pos); pos+=4; chunk_count=ri32(raw,pos); pos+=4
chunks=[]
for _ in range(chunk_count):
    uo=ru32(raw,pos); us=ru32(raw,pos+4); co=ru32(raw,pos+8); cs=ru32(raw,pos+12); pos+=16
    chunks.append((uo,us,co,cs))
total=sum(c[1] for c in chunks); decomp=bytearray(total); dest=0
for (uo,us,co,cs) in chunks:
    fp=co; assert ru32(raw,fp)==MAGIC; fp+=4
    bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw,fp)); fp+=4; su2.append(ri32(raw,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw[fp:fp+cs2]; fp+=cs2
        decomp[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]
        dest+=us2
print(f"Decomp OK: {total} bytes, baseVirt={chunks[0][0]}")

# --- Target region ---
D = decomp

# 1) Show bytes around propEnd+9600 .. propEnd+9624 (vertex position start area)
print("\n=== Bytes around propEnd+9588..+9632 ===")
for off in range(PROP_END+9588, PROP_END+9632, 16):
    hexrow(D, off, 16)

# Also decode as int32 sequence
print("\n=== Int32 sequence propEnd+9580..+9640 ===")
for off in range(PROP_END+9580, PROP_END+9640, 4):
    v=ri32(D,off); vf=rf(D,off)
    print(f"  +{off-PROP_END:<5d} = i32={v:10d}  f={vf:.4f}", 
          "  <-- pos start here" if off==PROP_END+9604 else "")

# 2) Find float3 runs using strict filter
print("\n=== Float3 position runs (spatially plausible) ===")
runs = []
rstart=-1; rcnt=0
for p in range(PROP_END, EXPORT_END-12, 4):
    x,y,z = rf(D,p), rf(D,p+4), rf(D,p+8)
    ok = all(-200000<v<200000 and math.isfinite(v) for v in (x,y,z))
    # at least one component beyond 1.0 (filter out compressed data near-zero)
    has_magnitude = max(abs(x),abs(y),abs(z)) > 1.0
    not_sub = all(v==0.0 or abs(v)>1e-10 for v in (x,y,z))
    if ok and has_magnitude and not_sub:
        if rstart<0: rstart=p; rcnt=1
        else: rcnt+=1
    else:
        if rcnt>=9:
            runs.append((rstart,rcnt))
        rstart=-1; rcnt=0
if rcnt>=9: runs.append((rstart,rcnt))

for ri,(rp,rc) in enumerate(runs[:10]):
    print(f"  run {ri}: +{rp-PROP_END} ({rc} floats, {rc//3} verts)")
    for vi in range(min(4, rc//3)):
        vp=rp+vi*12
        print(f"    v[{vi}]=({rf(D,vp):.2f},{rf(D,vp+4):.2f},{rf(D,vp+8):.2f})")

# 3) Find uint16 runs with meaningful indices
print("\n=== uint16 index runs (len>=30, max_idx in [3,10000], no zeros at start) ===")
p = PROP_END
while p < EXPORT_END-2:
    v = ru16(D, p)
    if 1 <= v <= 10000:
        pp=p; max_i=0; cnt=0
        while pp < EXPORT_END-2:
            vv=ru16(D,pp)
            if vv > 10000: break
            max_i=max(max_i,vv); cnt+=1; pp+=2
        if cnt>=30 and max_i>=3:
            first8=[ru16(D,p+i*2) for i in range(min(8,cnt))]
            print(f"  +{p-PROP_END}: {cnt} u16 max_idx={max_i} first={first8}")
            p=pp; continue
    p+=2

# 4) Look for TArray<uint16> pattern: int32 N followed by N uint16s (all < 10000)
print("\n=== TArray<uint16> pattern search ===")
found_idx=0
for p in range(PROP_END, EXPORT_END-8, 4):
    n = ri32(D, p)
    if not (6 <= n <= 100000): continue
    # check all n uint16s
    ok=True; max_i=0
    for i in range(min(n, 5)):
        v=ru16(D, p+4+i*2)
        if v>=10000: ok=False; break
        max_i=max(max_i,v)
    if ok and max_i>=3:
        # verify last few
        for i in range(max(0,n-3),n):
            v=ru16(D, p+4+i*2)
            if v>=10000: ok=False; break
        if ok and found_idx<8:
            first6=[ru16(D, p+4+i*2) for i in range(min(6,n))]
            print(f"  +{p-PROP_END}: TArray n={n} max_first={max_i} first={first6}")
            found_idx+=1

print("\nDone.")
