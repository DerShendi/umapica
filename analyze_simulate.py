"""
Simulate Java's readMeshSimple (mn=0.5) for failing meshes.
Report exact results to guide the Java fix.
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
pos+=4+4+12+16; gc0=ri32(raw,pos); pos+=4+gc0*8+12
ru32(raw,pos); pos+=4; cc=ri32(raw,pos); pos+=4
chunks=[]
for _ in range(cc):
    uo=ru32(raw,pos); us=ru32(raw,pos+4); co=ru32(raw,pos+8); cs_=ru32(raw,pos+12); pos+=16
    chunks.append((uo,us,co,cs_))
total=sum(c[1] for c in chunks); D=bytearray(total); dest=0
for (uo,us,co,cs_) in chunks:
    fp=co; fp+=4; bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc2=[]; su2=[]
    for _ in range(ns): sc2.append(ri32(raw,fp)); fp+=4; su2.append(ri32(raw,fp)); fp+=4
    for cs2,us2 in zip(sc2,su2):
        cd=raw[fp:fp+cs2]; fp+=cs2
        D[dest:dest+us2]=(cd if cs2==us2 else lzokay.decompress(cd,us2))[:us2]; dest+=us2
BV = chunks[0][0]
print(f"BV={BV}")

# Parsed names
p2=12
slen2=ri32(raw,p2); p2+=4
if slen2<0: p2+=abs(slen2)*2
elif slen2>0: p2+=slen2
p2+=4
nameCount=ri32(raw,p2); p2+=4
nameOff=ru32(raw,p2); p2+=4
names=[]
dp=nameOff-BV
for i in range(nameCount):
    sl=ri32(D,dp); dp+=4
    s=D[dp:dp+sl-1].decode('latin-1','replace') if sl>0 else ''
    dp+=sl if sl>0 else 0; dp+=8
    names.append(s)

def skip_prop_list(D, names, dp_start, dp_end):
    """Simulate Java skipUE3PropertyList."""
    dp=dp_start
    try:
        while dp < dp_end - 8:
            save=dp
            nameIdx=ri32(D,dp); dp+=4
            dp+=4  # nameNumber
            if nameIdx<0 or nameIdx>=len(names):
                dp=save; break
            propName=names[nameIdx]
            if propName=='None':
                return dp
            typeIdx=ri32(D,dp); dp+=4; dp+=4  # type FName
            propSize=ru32(D,dp); dp+=4
            dp+=4  # arrayIndex
            propType=names[typeIdx] if 0<=typeIdx<len(names) else ''
            if propType=='StructProperty': dp+=8
            elif propType=='BoolProperty': dp+=1
            elif propType=='ByteProperty': dp+=8
            if 0<propSize<2000000: dp+=propSize
            elif propSize>=2000000: dp=save; break
    except:
        pass
    return dp

def simulate_readMeshSimple(D, names, phys_start, phys_end, label):
    """Simulate Java readMeshSimple with mn=0.5."""
    bv=BV

    # Step 1: find longest float3 run
    best_phys=-1; best_cnt=0
    cur_phys=-1; cur_cnt=0
    for p in range(phys_start, phys_end-12+1, 4):
        x,y,z=rf(D,p),rf(D,p+4),rf(D,p+8)
        ok=(math.isfinite(x) and -200000<x<200000 and
            math.isfinite(y) and -200000<y<200000 and
            math.isfinite(z) and -200000<z<200000 and
            max(abs(x),abs(y),abs(z))>0.5)
        if ok:
            if cur_phys<0: cur_phys=p; cur_cnt=1
            else: cur_cnt+=1
        else:
            if cur_cnt>best_cnt: best_phys=cur_phys; best_cnt=cur_cnt
            cur_phys=-1; cur_cnt=0
    if cur_cnt>best_cnt: best_phys=cur_phys; best_cnt=cur_cnt

    if best_phys<0 or best_cnt<9:
        return f"FAIL: no float3 run (best_cnt={best_cnt})"

    numVerts=(best_cnt+2)//3
    pos_rel=best_phys-phys_start
    pos_end=best_phys+numVerts*12

    # Step 2: find index buffer after positions
    best_idx=-1; best_n=0
    for p in range(pos_end, phys_end-8+1, 4):
        n=ri32(D,p)
        if n<6 or n>500000 or n%3!=0: continue
        if p+4+n*2 > phys_end: continue
        ok2=True; mx=0
        for i in range(min(n,8)):
            v=ru16(D,p+4+i*2)
            if v>=numVerts*2: ok2=False; break
            if v>mx: mx=v
        if not ok2 or mx<3: continue
        for i in range(max(0,n-4),n):
            if ru16(D,p+4+i*2)>=numVerts*2: ok2=False; break
        if not ok2: continue
        if n>best_n: best_n=n; best_idx=p+4

    if best_idx<0:
        x0,y0,z0=rf(D,best_phys),rf(D,best_phys+4),rf(D,best_phys+8)
        return (f"FAIL: no idx buf (verts={numVerts} at rel={pos_rel}, "
                f"v0=({x0:.2f},{y0:.2f},{z0:.2f}), "
                f"search [{pos_end-phys_start}..{phys_end-phys_start}])")

    return (f"OK: verts={numVerts} tris={best_n//3} posOff={pos_rel} "
            f"idxOff={best_idx-4-phys_start}")

# All meshes of interest (name, phys, size)
MESHES = [
    ('cardboard_Building',       31651778, 9753),   # known working
    ('mafia_theatre_podium_med', 31322992, 12113),  # known working
    ('standing_torch',           30739014, 42674),  # FAILING
    ('mafia_wall_light',         31347220, 104622), # FAILING
    ('mafia_wooden_rail',        31485789, 140315), # FAILING
    ('cardboard_roundBush',      31688526, 4762),   # FAILING
    ('cardboard_StageBG',        31696593, 2384),   # FAILING
    ('cardboard_plant',          31680179, 4542),   # failing?
    ('CB_stageFront',            31711402, 8115),   # failing?
    ('mafia_wooden_beams',       31451842, 14303),  # has POS BUFFER
    ('mafia_wooden_beams_02',    31466145, 14306),  # no header
    ('mafia_wooden_beams_03',    31480451, 5338),   # has POS BUFFER
    ('mafia_theatre_podium',     31311147, 11845),  # no header
    ('mafia_theatre_podium_small',31335105,12115),  # no header
    ('mafia_theater_curtain_left',31626104,12837),  # has POS BUFFER
    ('mafia_theater_curtain_right',31638941,12837), # no header
    ('cardboard_cloud',          31661531, 8651),   # no header
    ('cardboard_cloud02',        31670182, 9997),   # no header
    ('cardboard_prophill',       31684721, 3805),   # no header
    ('cardboard_smRock',         31693288, 2199),   # no header
    ('cardboard_Stage',          31695487, 1106),   # no header
    ('cardboard_Sun',            31698977, 12425),  # no header
    ('mafia_sunray_theatre',     31179462, 2528),   # no header
    ('mafia_theatre_ceiling',    31291518, 11506),  # no header
    ('mafia_theatre_dome',       31303024, 8123),   # no header
]

print(f"{'Mesh':<40} {'Result'}")
print("-"*100)
for (name, phys, size) in MESHES:
    propEnd = skip_prop_list(D, names, phys, phys+size)
    propOffset = propEnd - phys
    result = simulate_readMeshSimple(D, names, propEnd, phys+size, name)
    print(f"{name:<40} propOff={propOffset:4d}, {result}")

print("\nDone.")
