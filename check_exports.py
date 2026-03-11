"""
Check UE3 export table layout using lzokay.
"""
import struct, os, lzokay

MAGIC = 0x9E2A83C1

def ri32(d, o): return struct.unpack_from('<i', d, o)[0]
def ru32(d, o): return struct.unpack_from('<I', d, o)[0]
def ri64(d, o): return struct.unpack_from('<q', d, o)[0]

umap_path = None
for fn in os.listdir('run/umapica'):
    if fn.endswith('.umap'):
        umap_path = 'run/umapica/' + fn
        print('Using:', fn); break

raw = open(umap_path, 'rb').read()
assert ru32(raw, 0) == MAGIC

pos = 8
pos += 4
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

bv=chunks[0][0]; print(f"decomp={len(decomp)} baseVirt={bv}\n")

def ph(va): return va-bv
def vri32(va): return struct.unpack_from('<i',decomp,ph(va))[0]
def vri64(va): return struct.unpack_from('<q',decomp,ph(va))[0]

names=[]; p=ph(name_off)
for _ in range(name_count):
    sl=struct.unpack_from('<i',decomp,p)[0]; p+=4
    if sl>0:
        s=decomp[p:p+sl]; p+=sl+8; end=len(s)-(1 if s and s[-1]==0 else 0)
        names.append(s[:end].decode('latin-1'))
    else:
        p+=8; names.append("")
print(f"names[0]={names[0]!r}  names[-1]={names[-1]!r}\n")

def vfname(va):
    idx=vri32(va); num=vri32(va+4)
    return (names[idx] if 0<=idx<len(names) else f'<{idx}>'), va+8

for layout,has_ef,has_gen in [
    ("A +exportFlags +gen+guid+pkg", True,  True ),
    ("B -exportFlags +gen+guid+pkg", False, True ),
    ("C -exportFlags -gen+guid+pkg", False, False),
    ("D +exportFlags -gen+guid+pkg", True,  False),
]:
    va=export_off; ok=0; print(f"=== {layout} ===")
    for i in range(min(12,export_count)):
        try:
            va+=4; va+=4; va+=4           # classIdx superIdx outerIdx
            nm,va=vfname(va); va+=4; va+=8  # name archetype flags64
            ss=vri32(va); va+=4; so=vri32(va); va+=4
            if has_ef: va+=4
            if has_gen:
                gc2=vri32(va); va+=4; va+=gc2*4+16+4
            p2=ph(so); in_r=0<=p2<len(decomp)
            if in_r:
                fi=struct.unpack_from('<i',decomp,p2)[0]
                valid=0<=fi<len(names); ok+=valid
                peek=(f"fname={names[fi]!r}" if valid else f"INVALID({fi})")
            else:
                valid=False; peek=f"OOB(off={so})"
            print(f"  [{i:3d}] {'OK' if valid else '--'} {nm:38s} sz={ss:8d}  off={so:10d}  {peek}")
        except Exception as e: print(f"  [{i:3d}] ERR:{e}"); break
    print(f"  >>> valid: {ok}/12\n")

