"""
Master analysis - clean version with correct chunk parsing.
"""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

with open('run/umapica/mafia_hq_mu_awakening.umap','rb') as f: raw = f.read()

# ---- exact same chunk parsing as working scripts ----
pos=8; pos+=4
slen=ri32(raw,pos); pos+=4
if slen>0: pos+=slen
pos+=4; pos+=4+4; pos+=4+4; pos+=4+4
pos+=4+4+12+16; gc=ri32(raw,pos); pos+=4+gc*8+12
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

# ---- Parse export table directly from the header file ----
# Need proper UE3 export table parsing
p2=4
ver2=ru32(raw,p2); p2+=4  
slen2=ri32(raw,p2); p2+=4
if slen2>0: p2+=slen2
p2+=4  # packageFlags
nameCount=ri32(raw,p2); p2+=4
nameOffset=ri32(raw,p2); p2+=4
exportCount=ri32(raw,p2); p2+=4
exportOffset=ri32(raw,p2); p2+=4
importCount=ri32(raw,p2); p2+=4
importOffset=ri32(raw,p2); p2+=4
print(f"exports={exportCount} at raw[{exportOffset}], imports={importCount}, names={nameCount}")

# Read names
names2=[]
rp=nameOffset
for _ in range(nameCount):
    ln=ri32(raw,rp); rp+=4
    name=(raw[rp:rp+abs(ln)-1] if ln>0 else b'').decode('latin-1','replace')
    rp+=abs(ln); rp+=4  # skip flags
    names2.append(name)

# Read imports (for class names)
imports2=[]
rp=importOffset
for _ in range(importCount):
    classPackage=ri32(raw,rp); rp+=4
    className=ri32(raw,rp); rp+=4
    outerIndex=ri32(raw,rp); rp+=4
    objName=ri32(raw,rp); rp+=4
    imports2.append((classPackage, className, outerIndex, objName))

def get_imp(idx):
    i=-idx-1
    if 0<=i<len(imports2):
        cn=imports2[i][1]
        return names2[cn] if 0<=cn<len(names2) else str(cn)
    return ''

# Read exports: UE3 format has netObjectCount which needs dynamic handling
# Safe approach: iterate and check bounds
rp=exportOffset
exp_list=[]
for e in range(exportCount):
    if rp+44 > len(raw): break
    classIndex=ri32(raw,rp); rp+=4
    superIndex=ri32(raw,rp); rp+=4
    outerIndex=ri32(raw,rp); rp+=4
    nameIndex=ri32(raw,rp); rp+=4
    archetype=ri32(raw,rp); rp+=4
    rp+=8  # objectFlags uint64
    serialSize=ri32(raw,rp); rp+=4
    serialOffset=ri32(raw,rp); rp+=4
    exportFlags=ri32(raw,rp); rp+=4
    netObjects=ri32(raw,rp); rp+=4
    if netObjects>0 and netObjects<10000: rp+=netObjects*4
    rp+=16+4  # guid + unknown

    name = names2[nameIndex] if 0<=nameIndex<len(names2) else f'?{nameIndex}'
    cname = get_imp(classIndex) if classIndex<0 else ''
    phys = serialOffset - BV
    exp_list.append((name, cname, phys, serialSize))

print(f"Parsed {len(exp_list)} exports")

# Find which exports contain the 9 [12,N,12,N] hits
hits_hp = [30397368, 30464652, 30643880, 31325860, 31454240, 31481508, 31629316, 31681272, 31713224]
hits_n  = [373,       3189,     1872,     311,       374,       130,       272,       116,       214]

print("\n9 [12,N,12,N] hits - owner export:")
for (hp,hn) in zip(hits_hp, hits_n):
    found=False
    for (name,cname,phys,size) in exp_list:
        if phys<=hp<phys+size:
            print(f"  phys={hp} N={hn}: '{name}' (class={cname}) +{hp-phys}")
            found=True; break
    if not found:
        print(f"  phys={hp} N={hn}: NOT IN ANY EXPORT")

# List all exports 25M-35M 
print("\nAll exports 25M-35M phys:")
for (name,cname,phys,size) in sorted(exp_list, key=lambda x: x[2]):
    if 25000000<=phys<=35000000:
        has_hit = any(phys<=h<phys+size for h in hits_hp)
        tag = " *** HAS [12,N,12,N] ***" if has_hit else ""
        print(f"  [{phys:10d}+{size:7d}] '{name}' class={cname}{tag}")

# Search entire file for [12, 1486, 12, 1486]
print("\nSearching for [12, 1486, 12, 1486] in full decompressed file:")
found_any=False
for p in range(0, total-16, 4):
    if ri32(D,p)==12 and ri32(D,p+4)==1486 and ri32(D,p+8)==12 and ri32(D,p+12)==1486:
        print(f"  FOUND at phys={p}")
        found_any=True
if not found_any:
    print("  Not found.")

# Search for index buffer with max~1485 within 200KB of standing_torch
print("\nLooking for idx buffer (max~1485) within 250KB of standing_torch:")
st_phys = 30739014
for p in range(max(0,st_phys-50000), min(total-8, st_phys+250000)):
    n = ru32(D, p)
    if n and n%3==0 and 100<=n<=10000:
        if p+4+n*2 > total: continue
        sample=[ru16(D,p+4+i*2) for i in range(min(n,100))]
        mx=max(sample)
        if 1400<=mx<=1500:
            print(f"  p={p} rel_st={p-st_phys}: n={n} max={mx}")

# For standing_torch, also check 4-byte aligned search for uint32 indices (idx32)
print("\nLooking for idx buffer (uint32, max~1485) within 250KB of st:")
for p in range(max(0,st_phys-50000), min(total-8, st_phys+250000)):
    n = ru32(D, p)
    if n and n%3==0 and 100<=n<=10000:
        if p+4+n*4 > total: continue
        sample=[ru32(D,p+4+i*4) for i in range(min(n,100))]
        mx=max(sample)
        if 1400<=mx<=1500:
            print(f"  p={p} rel_st={p-st_phys}: n={n} max={mx} (uint32)")

print("\nDone.")
