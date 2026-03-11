"""
Find specific named exports and probe what's at their serialOffset.
Tests prefix skips of 0/4/8/12/16 before reading FName.
"""
import struct, os, lzokay

MAGIC = 0x9E2A83C1
def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]

umap_path = None
for fn in os.listdir('run/umapica'):
    if fn.endswith('.umap'):
        umap_path = 'run/umapica/' + fn
        print('Using:', fn); break

raw = open(umap_path, 'rb').read()
assert ru32(raw, 0) == MAGIC

pos = 8; pos += 4
slen = ri32(raw, pos); pos += 4
if slen > 0: pos += slen
pos += 4
name_count   = ri32(raw, pos); pos += 4
name_off     = ri32(raw, pos); pos += 4
export_count = ri32(raw, pos); pos += 4
export_off   = ri32(raw, pos); pos += 4
import_count = ri32(raw, pos); pos += 4
import_off   = ri32(raw, pos); pos += 4
pos += 4 + 4 + 12 + 16
gc = ri32(raw, pos); pos += 4 + gc * 8 + 12
comp_flags  = ru32(raw, pos); pos += 4
chunk_count = ri32(raw, pos); pos += 4
chunks = []
for i in range(chunk_count):
    uo=ru32(raw,pos); us=ru32(raw,pos+4); co=ru32(raw,pos+8); cs=ru32(raw,pos+12)
    chunks.append((uo,us,co,cs)); pos+=16

print(f"nameCount={name_count} exportCount={export_count} exportOff={export_off}")

total=sum(c[1] for c in chunks); decomp=bytearray(total); dest=0
for (uo,us,co,cs) in chunks:
    fp=co; assert ru32(raw,fp)==MAGIC; fp+=4
    bs=ri32(raw,fp); fp+=8; ut=ri32(raw,fp); fp+=4
    if bs<=0: bs=131072
    ns=(ut+bs-1)//bs; sc=[]; su=[]
    for _ in range(ns): sc.append(ri32(raw,fp)); fp+=4; su.append(ri32(raw,fp)); fp+=4
    for s in range(ns):
        cd=raw[fp:fp+sc[s]]; fp+=sc[s]
        d=cd if sc[s]==su[s] else lzokay.decompress(cd,su[s])
        decomp[dest:dest+su[s]]=d[:su[s]]; dest+=su[s]

bv = chunks[0][0]
print(f"decomp={len(decomp)} baseVirt={bv}\n")

def ph(va): return va - bv
def vri32(va): return struct.unpack_from('<i', decomp, ph(va))[0]

# Read name table
names=[]; p=ph(name_off)
for _ in range(name_count):
    sl=struct.unpack_from('<i',decomp,p)[0]; p+=4
    if sl>0:
        s=decomp[p:p+sl]; p+=sl+8; end=len(s)-(1 if s and s[-1]==0 else 0)
        names.append(s[:end].decode('latin-1'))
    else:
        p+=8; names.append("")

def vfname_raw(phys):
    idx=struct.unpack_from('<i',decomp,phys)[0]
    num=struct.unpack_from('<i',decomp,phys+4)[0]
    valid = 0<=idx<len(names)
    return (names[idx] if valid else f'<BAD:{idx}>'), valid, num

# ---------- probe an export: print skip analysis ----------
def probe_offset(label, so):
    p = ph(so)
    if not (0 <= p < len(decomp)-32):
        print(f"  {label}: SO={so} out of bounds"); return
    raw32 = decomp[p:p+32]
    hexstr = ' '.join(f'{b:02X}' for b in raw32)
    print(f"  {label}: SO={so} phys={p}")
    print(f"    hex: {hexstr}")
    for skip in [0, 4, 8, 12, 16]:
        if p+skip+8 > len(decomp): continue
        nm, ok, num = vfname_raw(p+skip)
        marker = "OK " if ok else "   "
        print(f"    skip={skip}: {marker} idx={struct.unpack_from('<i',decomp,p+skip)[0]:6d}  num={num:8d}  name={nm!r}")

# ---------- parse export table (layout A) to find named exports ----------
print("=== Layout A: finding StaticMeshActor exports ===")
va = export_off
found = []
for i in range(export_count):
    va += 4; va += 4; va += 4           # classIdx superIdx outerIdx
    idx = vri32(va); va += 4            # FName.index
    num = vri32(va); va += 4            # FName.number
    nm  = names[idx] if 0<=idx<len(names) else f'<{idx}>'
    va  += 4; va += 8                   # archetype, flags64
    ss  = vri32(va); va += 4
    so  = vri32(va); va += 4
    va  += 4                            # exportFlags
    gc2 = vri32(va); va += 4
    va  += gc2*4 + 16 + 4               # genNetObjs + GUID + pkgFlags
    if 'StaticMeshActor' in nm or 'StaticMesh' in nm:
        found.append((i, nm, ss, so))

print(f"  Found {len(found)} StaticMesh* exports")
for (i, nm, ss, so) in found[:6]:
    probe_offset(f"[{i}] {nm}", so)

# Also probe first 4 exports to understand the format
print("\n=== First 4 exports (any) ===")
va = export_off
for i in range(4):
    va += 4; va += 4; va += 4
    idx=vri32(va); va+=4; num=vri32(va); va+=4
    nm = names[idx] if 0<=idx<len(names) else f'<{idx}>'
    va  += 4; va += 8
    ss  = vri32(va); va += 4
    so  = vri32(va); va += 4
    va  += 4
    gc2 = vri32(va); va += 4
    va  += gc2*4 + 16 + 4
    probe_offset(f"[{i}] {nm}", so)

# ---------- also: scan name table for "None" and "Location" ----------
print("\n=== Searching name table ===")
for kw in ['None', 'Location', 'RelativeLocation']:
    hits = [i for i,n in enumerate(names) if n==kw]
    print(f"  '{kw}': indices = {hits[:5]}")

# ---------- also: byte scan for "None\0" at StaticMeshActor SO ----------
if found:
    so = found[0][2+1]  # (i,nm,ss,so) -> so is index 3
    so = found[0][3]
    none_idx = next((i for i,n in enumerate(names) if n=='None'), -1)
    print(f"\n=== Scanning for 'None' nameIndex ({none_idx}) near first StaticMeshActor SO ===")
    p_base = ph(so)
    for skip in range(0, 64, 2):
        if p_base+skip+4 > len(decomp): break
        v = struct.unpack_from('<i', decomp, p_base+skip)[0]
        if v == none_idx:
            print(f"  'None' nameIndex found at skip={skip} bytes from SO")
