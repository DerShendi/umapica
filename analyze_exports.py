"""
Parse export table correctly from decompressed data.
Find which exports own the [12,N,12,N] hits and identify failing mesh formats.
"""
import struct, lzokay, math

def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def ru16(d,o): return struct.unpack_from('<H',d,o)[0]
def ri64(d,o): return struct.unpack_from('<q',d,o)[0]
def rf(d,o):   return struct.unpack_from('<f',d,o)[0]

with open('run/umapica/mafia_hq_mu_awakening.umap','rb') as f: raw = f.read()
# ---- chunk parsing (exact same as working scripts) ----
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
print(f"BV={BV}  TOTAL={total}")

# ---- Get nameOff, exportOff, importOff by reading raw header correctly ----
# raw: magic(4)+ver(4)+licensee(4)+folderName_len(4)+...+nameCount+nameOff+exportCount+exportOff+importCount+importOff
p2=12  # skip magic(4)+ver(4)+licensee(4)
slen2=ri32(raw,p2); p2+=4  # folderName length
if slen2<0: p2+=abs(slen2)*2  # unicode
elif slen2>0: p2+=slen2       # ascii
p2+=4  # packageFlags
nameCount=ri32(raw,p2); p2+=4
nameOff=ru32(raw,p2); p2+=4
exportCount=ri32(raw,p2); p2+=4
exportOff=ru32(raw,p2); p2+=4
importCount=ri32(raw,p2); p2+=4
importOff=ru32(raw,p2); p2+=4
print(f"nameOff={nameOff} exportOff={exportOff} importOff={importOff}")
print(f"nameCount={nameCount} exportCount={exportCount} importCount={importCount}")

# Address → physical (for decompressed buffer D)
def phys(virt): return virt - BV

# ---- Read name table from decompressed data ----
names = []
dp = phys(nameOff)
for i in range(nameCount):
    slen3 = ri32(D, dp); dp += 4
    if slen3 > 0:
        s = D[dp:dp+slen3-1].decode('latin-1','replace')
        dp += slen3
    else:
        s = ''
    dp += 8  # 8-byte hash
    names.append(s)
print(f"Names ok: {len(names)}, first 5: {names[:5]}")

# ---- Read import table ----
dp = phys(importOff)
imports = []
for i in range(importCount):
    classPkg = ri32(D,dp); dp+=4
    className = ri32(D,dp); dp+=4
    outerIdx  = ri32(D,dp); dp+=4
    objName   = ri32(D,dp); dp+=4
    dp+=4  # nameNumber
    imports.append((classPkg, className, outerIdx, objName))

def get_imp_name(idx):
    i=-idx-1
    if 0<=i<len(imports):
        cn=imports[i][1]
        return names[cn] if 0<=cn<len(names) else ''
    return ''

# ---- Read export table ----
dp = phys(exportOff)
exp_list = []
for e in range(exportCount):
    base = dp
    classIdx = ri32(D,dp); dp+=4
    superIdx = ri32(D,dp); dp+=4
    outerIdx = ri32(D,dp); dp+=4
    nameIdx  = ri32(D,dp); dp+=4
    nameNum  = ri32(D,dp); dp+=4  # FName = nameIdx + nameNumber
    archetype = ri32(D,dp); dp+=4
    objFlags = ri64(D,dp); dp+=8
    serialSize = ri32(D,dp); dp+=4
    serialOffset = ri32(D,dp) & 0xFFFFFFFF; dp+=4
    exportFlags = ri32(D,dp); dp+=4
    gc_ = ri32(D,dp); dp+=4
    dp += gc_*4  # skip netObjectIndices
    dp += 16+4   # GUID + packageFlags
    
    name = names[nameIdx] if 0<=nameIdx<len(names) else f'?{nameIdx}'
    cname = get_imp_name(classIdx) if classIdx<0 else ''
    exp_phys = serialOffset - BV
    exp_list.append((name, cname, exp_phys, serialSize, serialOffset))

print(f"Export table ok: {len(exp_list)} exports parsed")

# ---- Find which exports contain the 9 [12,N,12,N] hits ----
hits_hp = [30397368, 30464652, 30643880, 31325860, 31454240, 31481508, 31629316, 31681272, 31713224]
hits_n  = [373,       3189,     1872,     311,       374,       130,       272,       116,       214]

print("\n9 [12,N,12,N] hits - owner exports:")
for (hp,hn) in zip(hits_hp, hits_n):
    for (name,cname,ep,sz,virt) in exp_list:
        if ep<=hp<ep+sz:
            print(f"  phys={hp} N={hn}  -> '{name}' (class={cname}) +{hp-ep}")
            break
    else:
        print(f"  phys={hp} N={hn}  -> NOT IN ANY EXPORT")

# ---- List all exports 25M-35M phys ----
print("\nAll exports 25M-35M phys (StaticMesh-likely):")
for (name,cname,ep,sz,virt) in sorted(exp_list, key=lambda x: x[2]):
    if 25000000<=ep<=35000000:
        has_hit = any(ep<=h<ep+sz for h in hits_hp)
        tag = " *** HAS POS BUFFER ***" if has_hit else ""
        print(f"  [{ep:10d}+{sz:7d}] '{name}' class={cname}{tag}")

# ---- Search for [12, 1486, 12, 1486] anywhere ----
print("\nSearching for [12, 1486, 12, 1486] in full decompressed file:")
cnt=0
for p in range(0, total-16, 4):
    if ri32(D,p)==12 and ri32(D,p+4)==1486 and ri32(D,p+8)==12 and ri32(D,p+12)==1486:
        print(f"  FOUND at phys={p}")
        cnt+=1
if cnt==0: print("  Not found.")

# ---- Search for index buffer (max~1485) within 250KB of standing_torch ----
print("\nIndex buffer search (max~1485) within 250KB of standing_torch phys=30739014:")
st_phys=30739014
for p in range(max(0,st_phys-50000), min(total-8, st_phys+250000)):
    n = ru32(D, p)
    if n%3==0 and 100<=n<=10000:
        if p+4+int(n)*2 > total: continue
        sample=[ru16(D,p+4+i*2) for i in range(min(int(n),100))]
        mx=max(sample)
        if 1400<=mx<=1500:
            print(f"  phys={p} rel={p-st_phys} n={n} max_idx={mx}")

print("\nDone.")
