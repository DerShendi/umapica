"""
Master analysis:
1. List ALL StaticMesh exports and identify which have inline [12,N,12,N] headers
2. For standing_torch specifically: scan entire decompressed file for any [12, 1486, 12, 1486] 
   or index buffer with max ~1485 within 200KB of export
3. Check the 9 known [12,N,12,N] hits to identify which exports they belong to
"""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

with open('run/umapica/mafia_hq_mu_awakening.umap','rb') as f: raw = f.read()
# ---- Parse header ----
pos=8; pos+=4
slen=ri32(raw,pos); pos+=4
if slen>0: pos+=slen
pos+=4; ver=ru32(raw,pos); pos+=4
lv = ver&0xFFFF; uv = (ver>>16)&0xFFFF
pos+=4+4; pos+=4+4; pos+=4+4
pos+=4+4+12+16
gc=ri32(raw,pos); pos+=4+gc*8+12
ru32(raw,pos); pos+=4; cc=ri32(raw,pos); pos+=4
chunks=[]
for _ in range(cc):
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
print(f"BV={BV}  version={lv}/{uv}")

# ---- Parse name table ----
# Reset and reparse with name table
def parse_package():
    p=4; p+=4  # tag + version
    slen=ri32(raw,p); p+=4
    if slen>0: p+=slen
    p+=4+4+4+4+4+4  # licenseeVer, packageFlags, nameCount, nameOffset, exportCount, exportOffset
    # Read: packageFlags(4), nameCount(4), nameOffset(4), exportCount(4), exportOffset(4), importCount(4), importOffset(4)
    p=4
    ver2=ru32(raw,p); p+=4  # version+licensee
    slen=ri32(raw,p); p+=4  # root group name len
    if slen>0: p+=slen
    p+=4  # packageFlags
    nameCount=ri32(raw,p); p+=4
    nameOffset=ri32(raw,p); p+=4
    exportCount=ri32(raw,p); p+=4
    exportOffset=ri32(raw,p); p+=4
    importCount=ri32(raw,p); p+=4
    importOffset=ri32(raw,p); p+=4
    return nameCount, nameOffset, exportCount, exportOffset, importCount, importOffset

nc, noff, ec, eoff, ic, ioff = parse_package()
print(f"exports={ec} at raw[{eoff}], imports={ic} at raw[{ioff}], names={nc} at raw[{noff}]")

# Read name table
names=[]
rp=noff
for _ in range(nc):
    ln=ri32(raw,rp); rp+=4
    name=raw[rp:rp+ln-1].decode('latin-1') if ln>0 else ''
    rp+=ln; rp+=4  # skip flags
    names.append(name)

# Read import table (for class names)
imports = []
rp=ioff
for _ in range(ic):
    classPackage=ri32(raw,rp); rp+=4
    className=ri32(raw,rp); rp+=4
    outerIndex=ri32(raw,rp); rp+=4
    objName=ri32(raw,rp); rp+=4
    imports.append((classPackage, className, outerIndex, objName))

def get_import_name(idx):
    # idx is 1-based negative
    i = -idx-1
    if 0 <= i < len(imports):
        cn = imports[i][1]
        return names[cn] if 0 <= cn < len(names) else str(cn)
    return "?"

# Read export table
rp=eoff
exp_list=[]
for e in range(ec):
    classIndex=ri32(raw,rp); rp+=4
    superIndex=ri32(raw,rp); rp+=4
    outerIndex=ri32(raw,rp); rp+=4
    nameIndex=ri32(raw,rp); rp+=4
    archetype=ri32(raw,rp); rp+=4
    flags_hi=ri32(raw,rp); rp+=4
    flags_lo=ri32(raw,rp); rp+=4
    serialSize=ri32(raw,rp); rp+=4
    serialOffset=ri32(raw,rp); rp+=4
    exportFlags=ri32(raw,rp); rp+=4
    netObjects=ri32(raw,rp); rp+=4
    if netObjects>0: rp+=netObjects*4
    rp+=16+4  # guid + unknown
    
    name = names[nameIndex] if 0<=nameIndex<len(names) else f"?{nameIndex}"
    cname = get_import_name(classIndex) if classIndex < 0 else (names[classIndex] if 0<=classIndex<len(names) else f"Exp{classIndex}")
    phys = serialOffset - BV
    exp_list.append((name, cname, phys, serialSize, serialOffset))

# Find the 9 known [12,N,12,N] hits
hits = [30397368, 30464652, 30643880, 31325860, 31454240, 31481508, 31629316, 31681272, 31713224]
hits_n = [373, 3189, 1872, 311, 374, 130, 272, 116, 214]

print("\n9 [12,N,12,N] hits owner exports:")
for (hp, hn) in zip(hits, hits_n):
    for (name, cname, phys, size, virt) in exp_list:
        if phys <= hp < phys+size:
            rel = hp - phys
            print(f"  phys={hp} N={hn}: export '{name}' (class={cname}) at phys={phys}, rel=+{rel}")
            break
    else:
        print(f"  phys={hp} N={hn}: NOT IN ANY EXPORT")

# List all StaticMesh exports
print("\nAll exports (showing StaticMesh-like and nearby):")
sm_names = [n for n in ['standing_torch','wall_light','wooden_rail','cardboard_Building','podium',
                         'roundBush','plant','stageFront','StageBG','stageBG','mafia'] if True]
for (name, cname, phys, size, virt) in sorted(exp_list, key=lambda x: x[2]):
    if phys < 25000000 or phys > 35000000: continue
    has_hit = any(phys <= h < phys+size for h in hits)
    flag = " ***HAS_HEADER***" if has_hit else ""
    print(f"  [{phys:10d}+{size:7d}] '{name}' class={cname}{flag}")

# Now: search for [12, 1486, 12, 1486] ANYWHERE in the full file
print("\nSearching for [12, 1486, 12, 1486] anywhere in full decompressed file:")
for p in range(0, total-16, 4):
    if ri32(D,p)==12 and ri32(D,p+4)==1486 and ri32(D,p+8)==12 and ri32(D,p+12)==1486:
        print(f"  Found at phys={p}!")
        # Which export?
        for (name, cname, phys2, size2, virt2) in exp_list:
            if phys2 <= p < phys2+size2:
                print(f"    In export '{name}' +{p-phys2}")
                break

# Search for index buffer with max near 1485 within 1MB of standing_torch
print("\nLooking for index buffer (max~1485) within 100KB of standing_torch phys=30739014:")
st_phys = 30739014
st_sz = 42674
for p in range(max(0, st_phys-50000), min(total-8, st_phys+st_sz+50000)):
    n = ru32(D, p)
    if n and n%3==0 and 1500<=n<=10000:
        sample = [ru16(D, p+4+i*2) for i in range(min(n,200))]
        mx = max(sample)
        if 1400 <= mx <= 1500:
            print(f"  p={p} rel_to_st={p-st_phys} n={n} max={mx}")

print("\nDone.")
