"""
Try to decode property stream from different start offsets.
Verify by matching known landmarks (Location at 82, DrawScale3D at 126, None at 230).
"""
import struct, os, lzokay

MAGIC = 0x9E2A83C1
def ri32(d,o): return struct.unpack_from('<i',d,o)[0]
def ru32(d,o): return struct.unpack_from('<I',d,o)[0]
def rf32(d,o): return struct.unpack_from('<f',d,o)[0]

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

none_idx = next((i for i,n in enumerate(names) if n=='None'), -1)
bool_idx = next((i for i,n in enumerate(names) if n=='BoolProperty'), -1)
byte_idx = next((i for i,n in enumerate(names) if n=='ByteProperty'), -1)
int_idx  = next((i for i,n in enumerate(names) if n=='IntProperty'), -1)
flt_idx  = next((i for i,n in enumerate(names) if n=='FloatProperty'), -1)
obj_idx  = next((i for i,n in enumerate(names) if n=='ObjectProperty'), -1)
str_idx  = next((i for i,n in enumerate(names) if n=='StrProperty'), -1)
strc_idx = next((i for i,n in enumerate(names) if n=='StructProperty'), -1)
arr_idx  = next((i for i,n in enumerate(names) if n=='ArrayProperty'), -1)
print("Key indices:")
for lbl, val in [('None',none_idx),('BoolProperty',bool_idx),('ByteProperty',byte_idx),
                 ('IntProperty',int_idx),('FloatProperty',flt_idx),('ObjectProperty',obj_idx),
                 ('StrProperty',str_idx),('StructProperty',strc_idx),('ArrayProperty',arr_idx)]:
    print(f"  {lbl}: {val}")

# Known struct type sizes
struct_sizes = {}
for n,v in [('Vector',12),('Rotator',12),('Vector2D',8),('Color',4),('LinearColor',16),
            ('Plane',16),('Box',28),('Sphere',16),('Guid',16)]:
    idx2 = next((i for i,nm in enumerate(names) if nm==n), -1)
    if idx2>=0: struct_sizes[idx2]=v

def decode_props(buf, start):
    """Try to decode properties from 'start' offset in buf. Return (props_list, end_offset) or None."""
    pos2 = start
    props = []
    while pos2 + 8 <= len(buf):
        ni = struct.unpack_from('<i', buf, pos2)[0]
        nn = struct.unpack_from('<i', buf, pos2+4)[0]
        if ni == none_idx: # 'None' - end of properties
            return props, pos2+8
        if not (0 <= ni < len(names)):
            return None, pos2  # invalid
        pname = names[ni]
        pos2 += 8
        if pos2 + 8 > len(buf): return None, pos2
        ti = struct.unpack_from('<i', buf, pos2)[0]
        tn = struct.unpack_from('<i', buf, pos2+4)[0]
        if not (0 <= ti < len(names)): return None, pos2
        tname = names[ti]
        pos2 += 8
        if pos2 + 8 > len(buf): return None, pos2
        sz  = struct.unpack_from('<i', buf, pos2)[0]
        ai  = struct.unpack_from('<i', buf, pos2+4)[0]
        pos2 += 8
        # type-specific extras
        if ti == bool_idx:  # BoolProperty: 1-byte value in tag, size=0
            bv2 = buf[pos2] if pos2 < len(buf) else 0
            pos2 += 1; sz = 0
            props.append((pname, tname, bool(bv2)))
        elif ti == byte_idx:  # ByteProperty: extra FName for enum type
            if pos2+8 > len(buf): return None, pos2
            ei = struct.unpack_from('<i', buf, pos2)[0]
            pos2 += 8
            if sz > 0 and pos2+sz <= len(buf):
                data = buf[pos2:pos2+sz]; pos2 += sz
            else: data = b''; pos2 += max(0,sz)
            props.append((pname, 'ByteProperty', data))
        elif ti == strc_idx:  # StructProperty: extra FName for struct type
            if pos2+8 > len(buf): return None, pos2
            si2 = struct.unpack_from('<i', buf, pos2)[0]
            pos2 += 8
            if sz < 0 or pos2+sz > len(buf): return None, pos2
            data = buf[pos2:pos2+sz]; pos2 += sz
            if pname in ('Location','RelativeLocation') and sz==12:
                x,y,z = struct.unpack_from('<fff', data)
                props.append((pname, 'StructProperty', (x,y,z)))
            elif pname in ('Rotation','RelativeRotation') and sz==12:
                pitch,yaw,roll = struct.unpack_from('<iii', data)
                props.append((pname, 'StructProperty', (pitch,yaw,roll)))
            elif pname == 'DrawScale3D' and sz==12:
                x,y,z = struct.unpack_from('<fff', data)
                props.append((pname, 'StructProperty', (x,y,z)))
            else:
                props.append((pname, 'StructProperty', data[:min(12,len(data))]))
        else:  # generic: just skip sz bytes
            if sz < 0 or pos2+sz > len(buf): return None, pos2
            data = buf[pos2:pos2+sz]; pos2 += sz
            props.append((pname, tname, data[:min(8,len(data))]))
    return None, pos2

# Parse export table for StaticMeshActor
va=export_off; actor_exports=[]
for i in range(export_count):
    va+=4; va+=4; va+=4
    idx=vri32(va); va+=4; num=vri32(va); va+=4
    nm=names[idx] if 0<=idx<len(names) else f'<{idx}>'
    va+=4; va+=8
    ss=vri32(va); va+=4; so=vri32(va); va+=4
    va+=4; gc2=vri32(va); va+=4; va+=gc2*4+16+4
    if 'StaticMeshActor' in nm and not nm.startswith('Default__'):
        actor_exports.append((i,nm,ss,so))

print(f"\nFound {len(actor_exports)} StaticMeshActor exports")

# Try decoding first actor with different start offsets
print("\n=== Trying different property stream start offsets for first actor ===")
(i0,nm0,ss0,so0) = actor_exports[0]
p0 = ph(so0)
buf0 = bytes(decomp[p0:p0+ss0+64])
print(f"Actor [{i0}] {nm0}: ss={ss0} so={so0}")

for start in range(0, 24):
    result, end = decode_props(buf0, start)
    if result is not None:
        loc  = next((v for n,t,v in result if n=='Location'),   None)
        rot  = next((v for n,t,v in result if n=='Rotation'),   None)
        sc3d = next((v for n,t,v in result if n=='DrawScale3D'), None)
        prop_names = [n for n,t,v in result]
        print(f"  start={start:3d}: OK! {len(result)} props, end={end}  Location={loc}  Rotation={rot}  DrawScale3D={sc3d}")
        print(f"    props: {prop_names}")

# ---- Show full decode for the winning start offset ----
print("\n=== Decode 10 actors (trying start=0..20) ===")
for start in range(21):
    result, _ = decode_props(buf0, start)
    if result is not None:
        best_start = start; break

print(f"Best start offset: {best_start}")
print("\nFirst 10 actors with Location/Rotation:")
for (i,nm,ss,so) in actor_exports[:10]:
    p = ph(so)
    buf = bytes(decomp[p:p+ss+64])
    result, _ = decode_props(buf, best_start)
    if result is not None:
        loc  = next((v for n,t,v in result if n=='Location'),    '---')
        rot  = next((v for n,t,v in result if n=='Rotation'),    '---')
        sc3d = next((v for n,t,v in result if n=='DrawScale3D'), '---')
        print(f"  [{i}] {nm}: Location={loc}  Rotation={rot}  DrawScale3D={sc3d}")
    else:
        print(f"  [{i}] {nm}: DECODE FAILED")
