"""
decode_mesh_export.py - Decode AHiT UE3 StaticMesh export binary structure.
Run: python decode_mesh_export.py
Outputs byte-by-byte parse of the native (post-property) section for small StaticMesh exports.
"""
import struct, sys, lzokay

UMAP_FILE = 'run/umapica/mafia_town.umap'

def ri32(d, o): return struct.unpack_from('<i', d, o)[0]
def ru32(d, o): return struct.unpack_from('<I', d, o)[0]
def ri64(d, o): return struct.unpack_from('<q', d, o)[0]
def ru16(d, o): return struct.unpack_from('<H', d, o)[0]
def rfloat(d, o): return struct.unpack_from('<f', d, o)[0]

# ── Decompress entire file ─────────────────────────────────────────────────
print(f"Reading {UMAP_FILE}...")
with open(UMAP_FILE, 'rb') as f:
    raw = f.read()

# Parse header
off = 0
magic = ru32(raw, off); off += 4
file_ver = ri32(raw, off); off += 4
lic_ver  = ri32(raw, off); off += 4
folder_len = ri32(raw, off); off += 4
folder_str = raw[off:off+folder_len]; off += folder_len
pkg_flags = ru32(raw, off); off += 4
name_count = ri32(raw, off); off += 4; name_off  = ru32(raw, off); off += 4
exp_count  = ri32(raw, off); off += 4; exp_off   = ru32(raw, off); off += 4
imp_count  = ri32(raw, off); off += 4; imp_off   = ru32(raw, off); off += 4
off += 8  # dependsOff, softPkgOff
off += 12 # 3 padding ints
off += 16 # GUID
gen_count = ri32(raw, off); off += 4
off += gen_count * 8
off += 12  # EngineVersion, CookerVersion, PackageSource
comp_flags = ru32(raw, off); off += 4
chunk_count = ri32(raw, off); off += 4
print(f"fileVer={file_ver}, names={name_count}@{name_off}, exports={exp_count}@{exp_off}, imports={imp_count}@{imp_off}")
print(f"compressionFlags=0x{comp_flags:x}, chunks={chunk_count}")

chunks = []
for c in range(chunk_count):
    unc_off = ru32(raw, off); off += 4
    unc_sz  = ru32(raw, off); off += 4
    cmp_off = ru32(raw, off); off += 4
    cmp_sz  = ru32(raw, off); off += 4
    chunks.append((unc_off, unc_sz, cmp_off, cmp_sz))

BLOCK_MAGIC = 0x9E2A83C1
total_unc = sum(sz for _, sz, _, _ in chunks)
decomp = bytearray(total_unc)
dest = 0
for ci, (unc_off, unc_sz, cmp_off, cmp_sz) in enumerate(chunks):
    o = cmp_off
    assert ru32(raw,o) == BLOCK_MAGIC, f"Bad chunk magic at chunk {ci}"
    o += 4
    blk_sz   = ru32(raw,o); o += 4
    cmp_tot  = ri32(raw,o); o += 4
    unc_tot  = ri32(raw,o); o += 4
    if blk_sz == 0: blk_sz = 131072
    num_sub  = (unc_tot + blk_sz - 1) // blk_sz
    sub_hdr = []
    for _ in range(num_sub):
        cs = ri32(raw,o); o += 4
        us = ri32(raw,o); o += 4
        sub_hdr.append((cs,us))
    for cs, us in sub_hdr:
        data_in = bytes(raw[o:o+cs]); o += cs
        if cs == us:
            decomp[dest:dest+us] = data_in
        else:
            decomp[dest:dest+us] = lzokay.decompress(data_in, us)
        dest += us
base_virt = chunks[0][0]
print(f"Decompressed {total_unc} bytes, baseVirt={base_virt}")
print(f"Chunk 0: unc_off={chunks[0][0]} unc_sz={chunks[0][1]} cmp_off={chunks[0][2]} cmp_sz={chunks[0][3]}")
print(f"decomp[0:16] hex: {bytes(decomp[0:16]).hex()}")

def virt2phys(v): return v - base_virt
def phys2virt(p): return p + base_virt

# ── Name table ─────────────────────────────────────────────────────────────
def read_ue3_name(d, phys):
    slen = ri32(d, phys); phys += 4
    if slen < 0:
        wlen = -slen
        name = d[phys:phys+wlen*2].decode('utf-16-le','replace').rstrip('\x00')
        phys += wlen * 2
    elif slen == 0:
        name = ''
    else:
        name = d[phys:phys+slen].decode('ascii','replace').rstrip('\x00')
        phys += slen
    phys += 8  # 8-byte hash/flags
    return name, phys

names = []
p = virt2phys(name_off)
for _ in range(name_count):
    n, p = read_ue3_name(decomp, p)
    names.append(n)
print(f"Names read: {name_count}  first={names[0]!r}  last={names[-1]!r}")

def read_fname(d, phys):
    idx = ri32(d, phys); phys += 4
    num = ri32(d, phys); phys += 4
    if 0 <= idx < len(names):
        return names[idx], phys
    return f"<idx{idx}>", phys

# ── Export/Import tables not needed - using KNOWN_EXPORTS directly ────────
exports = []
imports_arr = []

static_mesh_exports = []


# ── Skip property list ─────────────────────────────────────────────────────
def skip_props(d, phys, end_phys):
    start = phys
    try:
        while phys < end_phys - 8:
            pos = phys
            name_idx = ri32(d, phys); phys += 4
            ri32(d, phys); phys += 4  # name number
            if name_idx < 0 or name_idx >= len(names):
                return start  # bail: no valid props, return start
            prop_name = names[name_idx]
            if prop_name == 'None':
                return phys
            type_idx = ri32(d, phys); phys += 4
            ri32(d, phys); phys += 4  # type number
            prop_size = ru32(d, phys); phys += 4
            ri32(d, phys); phys += 4  # arrayIndex
            type_name = names[type_idx] if 0 <= type_idx < len(names) else ""
            if type_name == 'StructProperty': phys += 8
            elif type_name == 'BoolProperty': phys += 1
            elif type_name == 'ByteProperty': phys += 8
            if prop_size <= 2_000_000:
                phys += prop_size
            else:
                return start
    except Exception:
        pass
    return phys

# ── Deep decode: try to find LOD structure ────────────────────────────────
def hexdump(d, phys, n, label=""):
    if label: print(f"  [{label}]")
    end = min(phys+n, len(d))
    for row in range(0, n, 16):
        p2 = phys + row
        if p2 >= len(d): break
        raw_row = d[p2:min(p2+16,len(d))]
        hex_str = ' '.join(f'{b:02x}' for b in raw_row)
        asc_str = ''.join(chr(b) if 32 <= b < 126 else '.' for b in raw_row)
        print(f"    +{row:4d}  {hex_str:<48}  {asc_str}")

def try_probe_lod0(d, phys, end_phys, section_stride):
    """Try to read LOD0 at phys assuming given section stride. Returns dict or None."""
    p = phys
    if p + 4 > end_phys: return None
    num_sections = ri32(d, p); p += 4
    if num_sections < 0 or num_sections > 200: return None
    sec_bytes = num_sections * section_stride
    if p + sec_bytes + 12 > end_phys: return None
    # Read sections raw
    sections = []
    for s in range(num_sections):
        sp = p + s * section_stride
        # Try both field orders
        mat   = ri32(d, sp+0)
        coll  = ri32(d, sp+4)
        shad  = ri32(d, sp+8)
        first = ri32(d, sp+12)
        ntris = ri32(d, sp+16)
        minv  = ri32(d, sp+20)
        maxv  = ri32(d, sp+24)
        sections.append({'mat':mat,'first':first,'ntris':ntris,'minv':minv,'maxv':maxv})
    p += sec_bytes

    # Try FRawIndexBuffer as: [int32 count + uint16[count]] (no stride)  
    ni = ri32(d, p); p += 4
    if not (3 <= ni <= 1_500_000): return None
    idx_bytes = ni * 2
    if p + idx_bytes + 12 > end_phys: return None
    p += idx_bytes

    pos_stride = ri32(d, p); p += 4
    if not (12 <= pos_stride <= 256): return None
    nv = ri32(d, p); p += 4
    if not (3 <= nv <= 500_000): return None
    if p + nv * pos_stride > end_phys: return None
    return {'num_sections': num_sections, 'sections': sections,
            'num_indices': ni, 'num_verts': nv, 'pos_stride': pos_stride}

def try_probe_lod0_with_stride_prefix(d, phys, end_phys, section_stride):
    """Variant: FRawIndexBuffer has [int32 element_size, int32 count]."""
    p = phys
    if p + 4 > end_phys: return None
    num_sections = ri32(d, p); p += 4
    if num_sections < 0 or num_sections > 200: return None
    sec_bytes = num_sections * section_stride
    if p + sec_bytes + 12 > end_phys: return None
    sections = []
    for s in range(num_sections):
        sp = p + s * section_stride
        mat   = ri32(d, sp+0)
        coll  = ri32(d, sp+4)
        shad  = ri32(d, sp+8)
        first = ri32(d, sp+12)
        ntris = ri32(d, sp+16)
        minv  = ri32(d, sp+20)
        maxv  = ri32(d, sp+24)
        sections.append({'mat':mat,'first':first,'ntris':ntris,'minv':minv,'maxv':maxv})
    p += sec_bytes

    # FRawIndexBuffer with stride prefix
    elem_sz = ri32(d, p); p += 4
    if elem_sz not in (2, 4): return None
    ni = ri32(d, p); p += 4
    if not (3 <= ni <= 1_500_000): return None
    idx_bytes = ni * elem_sz
    if p + idx_bytes + 12 > end_phys: return None
    p += idx_bytes

    pos_stride = ri32(d, p); p += 4
    if not (12 <= pos_stride <= 256): return None
    nv = ri32(d, p); p += 4
    if not (3 <= nv <= 500_000): return None
    if p + nv * pos_stride > end_phys: return None
    return {'num_sections': num_sections, 'sections': sections,
            'num_indices': ni, 'num_verts': nv, 'pos_stride': pos_stride,
            'elem_sz': elem_sz}

# ── Use known offsets from Java log (fallback, always reliable) ───────────
KNOWN_EXPORTS = [
    {'name': 'Skydome', 'ser_off': 32845387, 'ser_size': 29737},
]

# ── Pick some exports to analyze ──────────────────────────────────────────
TARGETS = ['harbour_magma_platform', 'Skydome', 'Moon', 'mole_man']

for e in KNOWN_EXPORTS:
    virt_off = e['ser_off']
    virt_end = virt_off + e['ser_size']
    phys_off = virt2phys(virt_off)
    phys_end = virt2phys(virt_end)

    print(f"\n{'='*70}")
    print(f"Export: {e['name']} @ virt={virt_off} phys={phys_off} size={e['ser_size']}")

    prop_end = skip_props(decomp, phys_off, phys_end)
    skipped  = prop_end - phys_off
    print(f"  Property list: {skipped} bytes  (propEnd phys={prop_end})")

    # Dump first 256 bytes after props
    print(f"  Post-prop bytes (first 256):")
    hexdump(decomp, prop_end, 256)

    # Brute-force scan for LOD data structure (1-byte steps for accuracy)
    print(f"\n  Scanning for LOD structure (numLODs 1-8)...")
    found_any = False
    max_scan = min(phys_end, prop_end + 200_000)  # scan up to 200KB
    for try_phys in range(prop_end, max_scan - 4, 4):
        num_lods = ri32(decomp, try_phys)
        if not (1 <= num_lods <= 8):
            continue
        after_lod_count = try_phys + 4
        skip_offset = try_phys - prop_end

        # Try both probe variants with multiple strides
        for stride in [28, 36, 32, 40]:
            for probe_fn, label in [(try_probe_lod0, 'no-stride'), 
                                     (try_probe_lod0_with_stride_prefix, 'stride-prefix')]:
                result = probe_fn(decomp, after_lod_count, phys_end, stride)
                if result:
                    print(f"  *** MATCH: numLODs={num_lods} at skip_offset={skip_offset} "
                          f"phys={try_phys} stride={stride} probe={label}")
                    print(f"      sections={result['num_sections']} indices={result['num_indices']} "
                          f"verts={result['num_verts']} posStride={result['pos_stride']}")
                    if result['sections']:
                        for si, sec in enumerate(result['sections'][:4]):
                            print(f"      section[{si}]: first={sec['first']} ntris={sec['ntris']}")
                    found_any = True
                    if not found_any:  # just find first, stop
                        break
            if found_any:
                break

    if not found_any:
        print(f"  NO LOD MATCH FOUND in scan of {max_scan - prop_end} bytes")
        # Extended structure analysis - print 200 int32s (800 bytes)
        print(f"\n  Extended structure analysis (post-props, 800 bytes):")
        p = prop_end
        try:
            for field_n in range(200):
                if p >= phys_end: break
                v_i32 = ri32(decomp, p)
                v_u32 = ru32(decomp, p)
                v_f32 = rfloat(decomp, p)
                offset_here = p - prop_end
                suffix = ""
                # Try interpreting as FString
                if 0 < v_i32 < 512:
                    s_bytes = decomp[p+4:p+4+v_i32]
                    try:
                        s_text = s_bytes.decode('latin-1').rstrip('\x00')
                        if all(32 <= c < 127 or c == 0 for c in s_bytes):
                            suffix = f'  -> FString[{v_i32}] "{s_text[:50]}"'
                    except Exception:
                        pass
                # Flag plausible floats
                if -200000 < v_f32 < 200000 and v_f32 != 0.0 and not (v_u32 < 0x10000):
                    suffix += f'  [float?={v_f32:.3f}]'
                print(f"    +{offset_here:5d}  int32={v_i32:10d}  u32=0x{v_u32:08x}  "
                      f"float={v_f32:.3f}{suffix}")
                p += 4
        except Exception as ex:
            print(f"    parse error: {ex}")

        # Scan for float3 vertex-like runs
        print(f"\n  Scanning for float3 vertex position runs (range +-200000):")
        import math
        run_start = -1
        run_count = 0
        found_runs = []
        for scan_p in range(prop_end, min(phys_end - 12, prop_end + 300000), 4):
            x = rfloat(decomp, scan_p)
            y = rfloat(decomp, scan_p + 4)
            z = rfloat(decomp, scan_p + 8)
            all_ok = all(-200000 < v < 200000 and math.isfinite(v) for v in (x, y, z))
            not_subnorm = all(v == 0.0 or abs(v) > 1e-30 for v in (x, y, z))
            if all_ok and not_subnorm:
                if run_start < 0:
                    run_start = scan_p
                    run_count = 1
                else:
                    run_count += 1
            else:
                if run_count >= 9:
                    found_runs.append((run_start, run_count))
                run_start = -1
                run_count = 0
        if run_count >= 9:
            found_runs.append((run_start, run_count))

        for ri, (rp, rc) in enumerate(found_runs[:8]):
            offset_from_prop = rp - prop_end
            print(f"    Float3 run {ri} at +{offset_from_prop} (phys={rp}): "
                  f"{rc} floats ({rc//3} verts)")
            for vi in range(min(3, rc // 3)):
                vp = rp + vi * 12
                print(f"      vert[{vi}]: ({rfloat(decomp,vp):.3f}, {rfloat(decomp,vp+4):.3f}, {rfloat(decomp,vp+8):.3f})")
            # Show 32 bytes BEFORE this run (to identify header/count)
            pre_start = max(prop_end, rp - 32)
            print(f"    Before run {ri} ({rp-pre_start} bytes):")
            for boff in range(pre_start, rp, 4):
                v = ri32(decomp, boff)
                vf = rfloat(decomp, boff)
                print(f"      [+{boff-prop_end}] i32={v:8d} u32=0x{ru32(decomp,boff):08x} f={vf:.3f}")

        # Also scan for uint16 index sequences (groups of uint16 < 10000)
        print(f"\n  Scanning for uint16 index runs (max index < 5000, min 6 consecutive):")
        idx_found = 0
        scan_p = prop_end
        while scan_p < min(phys_end - 2, prop_end + 200000) and idx_found < 5:
            v = ru16(decomp, scan_p)
            if v < 5000:
                # start of potential index run
                run_len = 0
                pp = scan_p
                max_idx = 0
                while pp < phys_end - 2:
                    vv = ru16(decomp, pp)
                    if vv >= 5000:
                        break
                    max_idx = max(max_idx, vv)
                    run_len += 1
                    pp += 2
                if run_len >= 6:
                    offset_from_prop = scan_p - prop_end
                    print(f"    uint16 run at +{offset_from_prop}: {run_len} indices, max_idx={max_idx}")
                    first6 = [ru16(decomp, scan_p + i*2) for i in range(min(6, run_len))]
                    print(f"      first 6: {first6}")
                    idx_found += 1
                    scan_p = pp
                    continue
            scan_p += 2




print("\nDone.")
