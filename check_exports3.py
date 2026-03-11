"""
Deep scan: find 'Location'(1173) and 'None'(1474) in actor property streams.
Determine FName size (4 vs 8 bytes) and property tag format.
"""
import struct, os, lzokay

MAGIC = 0x9E2A83C1
def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]

umap_path = None
for fn in os.listdir('run/umapica'):
    if fn.endswith('.umap'):
        umap_path = 'run/umapica/' + fn; break

raw = open(umap_path,'rb').read()
pos=8; pos+=4
slen=ri32(raw,pos); pos+=4
if slen>0: pos+=slen
pos+=4
name_count=ri32(raw,pos); pos+=4; name_off=ri32(raw,pos); pos+=4
export_count=ri32(raw,pos); pos+=4; export_off=ri32(raw,pos); pos+=4
import_count=ri32(raw,pos); pos+=4; import_off=ri32(raw,pos); pos+=4
pos+=4+4+12+16; gc=ri32(raw,pos); pos+=4+gc*8+12
comp_flags=ru32(raw,pos); pos+=4; chunk_count=ri32(raw,pos); pos+=4
chunks=[]
for i in range(chunk_count):
    uo=ru32(raw,pos); us=ru32(raw,pos+4); co=ru32(raw,pos+8); cs=ru32(raw,pos+12)
    chunks.append((uo,us,co,cs)); pos+=16

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

bv=chunks[0][0]
def ph(va): return va-bv
def vri32(va): return struct.unpack_from('<i',decomp,ph(va))[0]

names=[]; p=ph(name_off)
for _ in range(name_count):
    sl=struct.unpack_from('<i',decomp,p)[0]; p+=4
    if sl>0:
        s=decomp[p:p+sl]; p+=sl+8; end=len(s)-(1 if s and s[-1]==0 else 0)
        names.append(s[:end].decode('latin-1'))
    else: p+=8; names.append("")

none_idx  = next((i for i,n in enumerate(names) if n=='None'), -1)
loc_idx   = next((i for i,n in enumerate(names) if n=='Location'), -1)
rot_idx   = next((i for i,n in enumerate(names) if n=='Rotation'), -1)
scale_idx = next((i for i,n in enumerate(names) if n=='DrawScale3D'), -1)
print(f"None={none_idx}  Location={loc_idx}  Rotation={rot_idx}  DrawScale3D={scale_idx}")

# Pack as LE int32 bytes
def b4(v): return struct.pack('<i', v)
none_b4  = b4(none_idx)
loc_b4   = b4(loc_idx)
rot_b4   = b4(rot_idx)
scale_b4 = b4(scale_idx)

# Parse export table to find StaticMeshActor exports
va=export_off
actor_exports=[]
for i in range(export_count):
    va+=4; va+=4; va+=4
    idx=vri32(va); va+=4; num=vri32(va); va+=4
    nm=names[idx] if 0<=idx<len(names) else f'<{idx}>'
    va+=4; va+=8
    ss=vri32(va); va+=4; so=vri32(va); va+=4
    va+=4
    gc2=vri32(va); va+=4; va+=gc2*4+16+4
    if 'StaticMeshActor' in nm and not nm.startswith('Default__'):
        actor_exports.append((i,nm,ss,so))

print(f"Found {len(actor_exports)} StaticMeshActor exports")

# Scan each actor SO buffer for Location/Rotation name indices
def scan_buf(label, phys, sz):
    buf = decomp[phys:phys+min(sz+64, len(decomp)-phys)]
    results = {}
    for needle, name in [(loc_b4,'Location'),(rot_b4,'Rotation'),(none_b4,'None'),(scale_b4,'DrawScale3D')]:
        hits = []
        off = 0
        while True:
            pos2 = buf.find(needle, off)
            if pos2 < 0: break
            hits.append(pos2); off = pos2+1
        results[name] = hits
    return results

print("\n=== Scanning first 10 StaticMeshActor exports for Location/Rotation/None ===")
for (i,nm,ss,so) in actor_exports[:10]:
    p = ph(so)
    if not (0<=p<len(decomp)): continue
    r = scan_buf(nm, p, ss)
    loc_hits  = r.get('Location',[])
    rot_hits  = r.get('Rotation',[])
    none_hits = r.get('None',[])
    sc_hits   = r.get('DrawScale3D',[])
    print(f"  [{i}] {nm} sz={ss} so={so}:")
    print(f"    Location  offsets: {loc_hits[:5]}")
    print(f"    Rotation  offsets: {rot_hits[:5]}")
    print(f"    None      offsets: {none_hits[:5]}")
    print(f"    DrawScale offsets: {sc_hits[:5]}")

# Now: pick first actor with a Location hit and decode FName from that offset
# Determine if FName is 4-byte (nameIndex only) or 8-byte (nameIndex+number)
print("\n=== FName size probe: trying to decode a Location property tag ===")
for (i,nm,ss,so) in actor_exports[:10]:
    p = ph(so)
    if not (0<=p<len(decomp)): continue
    r = scan_buf(nm, p, ss)
    loc_hits = r.get('Location',[])
    if not loc_hits: continue
    loff = loc_hits[0]  # offset within actor buffer
    print(f"\n  [{i}] {nm}: Location FName at buf_offset={loff} (phys={p+loff})")
    # Try FName=4 bytes -> then typeName starts at loff+4
    # Try FName=8 bytes -> then typeName starts at loff+8
    for fname_sz in [4, 8]:
        toff = loff + fname_sz
        tnm  = struct.unpack_from('<i', decomp, p+toff)[0]
        tnm2 = struct.unpack_from('<i', decomp, p+toff+4)[0] if fname_sz==4 else None
        tnm_name = names[tnm] if 0<=tnm<len(names) else f'<BAD:{tnm}>'
        if fname_sz==4:
            tnm2_name = names[tnm2] if tnm2 is not None and 0<=tnm2<len(names) else f'<BAD:{tnm2}>'
            print(f"    FName={fname_sz}B -> typeFName.idx={tnm} '{tnm_name}', next4.idx={tnm2} '{tnm2_name}'")
        else:
            print(f"    FName={fname_sz}B -> typeFName.idx={tnm} '{tnm_name}'")
    # Print 32 bytes starting from the Location FName offset
    raw_seg = decomp[p+loff:p+loff+32]
    hexs = ' '.join(f'{b:02X}' for b in raw_seg)
    print(f"    raw[{loff}..{loff+31}]: {hexs}")
    break  # just first match

# Also dump the full first actor buffer to inspect structure
print("\n=== Full hex dump of first StaticMeshActor (first 128 bytes) ===")
if actor_exports:
    (i,nm,ss,so) = actor_exports[0]
    p = ph(so)
    for row in range(0, min(128, ss), 16):
        chunk2 = decomp[p+row:p+row+16]
        hexs = ' '.join(f'{b:02X}' for b in chunk2)
        ints = ' '.join(str(struct.unpack_from('<i',decomp,p+row+j*4)[0]) for j in range(4) if row+j*4+4<=ss)
        print(f"  [{row:4d}] {hexs:48s} | {ints}")
