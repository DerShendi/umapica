import struct, zstandard, os

def lo32(d,o): return struct.unpack_from('<I',d,o)[0]
def u16(d,o):  return struct.unpack_from('<H',d,o)[0]
def u32(d,o):  return struct.unpack_from('<I',d,o)[0]
def f32(d,o):  return struct.unpack_from('<f',d,o)[0]

# Check Tower and Lighthouse – these load OK but have bad bounds
# We need to find why good FVTXs have bad max/min coords
for name in ['Tower', 'Lighthouse', 'PawnShop']:
    path = f'D:/romfs/Model/{name}.bfres.zs'
    raw = zstandard.ZstdDecompressor().decompress(open(path,'rb').read(), max_output_size=50_000_000)
    fmdl_off = lo32(raw,0x28)
    nfv = u16(raw,fmdl_off+0x68); nfs = u16(raw,fmdl_off+0x6A)
    fvtx_base = lo32(raw,fmdl_off+0x20)
    fshp_base = lo32(raw,fmdl_off+0x28)
    
    fmaa_ptr = lo32(raw,0x68); nmaa=0
    while fmaa_ptr+nmaa*0x70+4<len(raw) and raw[fmaa_ptr+nmaa*0x70:fmaa_ptr+nmaa*0x70+4]==b'FMAA': nmaa+=1
    hint = u32(raw, fmaa_ptr+nmaa*0x70+0x08)
    rlt_off = u32(raw,0x18); nsec = u32(raw,rlt_off+0x08)
    face_bio=vbio=None
    for s in range(nsec):
        so=rlt_off+0x10+s*0x10; sp=u32(raw,so); sa=u32(raw,so+4)
        if sp==hint and sa>0: face_bio=sp; vbio=sp+sa; break
    if face_bio is None:
        for s in range(nsec):
            so=rlt_off+0x10+s*0x10; sp=u32(raw,so); sa=u32(raw,so+4)
            if sp>0 and (sp&0xFFF==0) and sa>0: face_bio=sp; vbio=sp+sa; break
    
    print(f"\n=== {name}: vbio={'0x%X'%vbio if vbio else 'None'} ===")
    
    total_verts=0; min_coords=[1e38]*3; max_coords=[-1e38]*3
    bad_fvtxs=[]
    for i in range(nfv):
        fo = fvtx_base + i*0x58
        vtxCnt = u32(raw,fo+0x50)
        bufOff = u32(raw,fo+0x48)
        bufArrPtr = lo32(raw, fo+0x10)
        strideUnits = u32(raw,bufArrPtr+4) if bufArrPtr and bufArrPtr+8<=len(raw) else 3
        stride = strideUnits * 4 if 3<=strideUnits<=50 else 12
        
        if not vbio: continue
        dataStart = vbio + bufOff
        if dataStart+stride > len(raw): 
            print(f"  FVTX[{i}]: OOB dataStart=0x{dataStart:X}")
            continue
        
        # Check first vertex plausibility
        x0,y0,z0 = struct.unpack_from('<fff',raw,dataStart)
        plaus = all(abs(v)<10000 and v==v and abs(v)!=float('inf') for v in (x0,y0,z0))
        
        # Find actual range within this FVTX
        fx_min=fy_min=fz_min=1e38; fx_max=fy_max=fz_max=-1e38
        n_bad=0
        for v in range(vtxCnt):
            p = dataStart + v*stride
            if p+12 > len(raw): break
            x,y,z = struct.unpack_from('<fff',raw,p)
            if any(abs(c)>1e6 or c!=c for c in (x,y,z)): n_bad+=1; continue
            fx_min=min(fx_min,x); fx_max=max(fx_max,x)
            fy_min=min(fy_min,y); fy_max=max(fy_max,y)
            fz_min=min(fz_min,z); fz_max=max(fz_max,z)
        
        total_verts+=vtxCnt
        print(f"  FVTX[{i}]: cnt={vtxCnt} stride={stride} bufOff=0x{bufOff:X} plaus0={plaus} n_bad={n_bad}")
        if fx_min < 1e38:
            print(f"    range x=[{fx_min:.3f},{fx_max:.3f}] y=[{fy_min:.3f},{fy_max:.3f}] z=[{fz_min:.3f},{fz_max:.3f}]")
        else:
            print(f"    ALL BAD VERTICES")
        
        # If stride != 12, check if maybe the position is NOT at offset 0
        # Try reading at different offsets
        if not plaus or n_bad > vtxCnt*0.1:
            bad_fvtxs.append(i)
            print(f"    RAW bytes at dataStart: {raw[dataStart:dataStart+24].hex()}")
            # Try offset 4 (skip some header byte)
            if dataStart+4+12 <= len(raw):
                x,y,z = struct.unpack_from('<fff',raw,dataStart+4)
                print(f"    At +4: ({x:.4f},{y:.4f},{z:.4f})")
            if dataStart+8+12 <= len(raw):
                x,y,z = struct.unpack_from('<fff',raw,dataStart+8)
                print(f"    At +8: ({x:.4f},{y:.4f},{z:.4f})")
    
    # Also check if index references are sane for first FSHP
    fo0=fshp_base
    mesh_arr = lo32(raw,fo0+0x18)
    vtx_idx  = u16(raw,fo0+0x50)
    if mesh_arr and mesh_arr+0x30<=len(raw):
        face_off = u32(raw,mesh_arr+0x20); idx_cnt=u32(raw,mesh_arr+0x2C)
        if face_bio:
            fp = face_bio+face_off
            idxs = [u16(raw,fp+j*2) for j in range(min(6,idx_cnt)) if fp+j*2+2<=len(raw)]
            print(f"  FSHP[0]: vtxIdx={vtx_idx} faceOff=0x{face_off:X} idxCnt={idx_cnt} first_idxs={idxs}")
