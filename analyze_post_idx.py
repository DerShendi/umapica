"""
For failing meshes: check if positions are AFTER the index buffer,
or stored as FP16 (half-float) format.
Also check for interleaved vertex buffer (stride != 12).
"""
import struct, sys, math

UMAP = r"C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_hq_mu_awakening.umap"

with open(UMAP, "rb") as f:
    raw = f.read()

def i32(d, o): return struct.unpack_from("<i", d, o)[0]
def u32(d, o): return struct.unpack_from("<I", d, o)[0]
def u16(d, o): return struct.unpack_from("<H", d, o)[0]
def f32(d, o): return struct.unpack_from("<f", d, o)[0]

def fp16_to_float(h):
    """Convert half-float (uint16) to Python float."""
    sign = (h >> 15) & 1
    exp  = (h >> 10) & 0x1F
    frac = h & 0x3FF
    if exp == 0:
        v = frac / 1024.0 * (2**-14)
    elif exp == 31:
        v = float('inf') if frac == 0 else float('nan')
    else:
        v = (1 + frac/1024.0) * (2**(exp-15))
    return -v if sign else v

def is_plausible_pos(x, y, z, limit=5000.0, min_any=0.1):
    """True if at least one coord > min_any and all < limit."""
    for v in (x,y,z):
        if not math.isfinite(v): return False
        if abs(v) > limit: return False
    return max(abs(x),abs(y),abs(z)) > min_any

def scan_float3(data, start, end, name, num_verts):
    """Scan for `num_verts` consecutive plausible float3s at any alignment."""
    step = 4
    best_off, best_count = -1, 0
    run_start, run_count = -1, 0
    o = start
    while o + 12 <= end:
        x = f32(data, o)
        y = f32(data, o+4)
        z = f32(data, o+8)
        if is_plausible_pos(x, y, z):
            if run_count == 0:
                run_start = o
            run_count += 1
        else:
            if run_count > best_count:
                best_count = run_count
                best_off = run_start
            run_count = 0
            run_start = -1
        o += step
    if run_count > best_count:
        best_count = run_count
        best_off = run_start

    print(f"  float3 scan in [{start}-{end}]: best run={best_count} (need {num_verts}), off={best_off}")
    if best_off >= 0 and best_count >= num_verts:
        x0,y0,z0 = f32(data,best_off),f32(data,best_off+4),f32(data,best_off+8)
        print(f"    FOUND! v0=({x0:.3f},{y0:.3f},{z0:.3f})")
        return best_off
    return -1

def scan_fp16_xyz(data, start, end, num_verts, name):
    """Scan for num_verts consecutive half-float xyz triples (6 bytes each)."""
    step = 2
    best_off, best_count = -1, 0
    run_start, run_count = -1, 0
    o = start
    while o + 6 <= end:
        x = fp16_to_float(u16(data, o))
        y = fp16_to_float(u16(data, o+2))
        z = fp16_to_float(u16(data, o+4))
        if is_plausible_pos(x, y, z, limit=5000.0):
            if run_count == 0:
                run_start = o
            run_count += 1
        else:
            if run_count > best_count:
                best_count = run_count
                best_off = run_start
            run_count = 0
            run_start = -1
        o += step
    if run_count > best_count:
        best_count = run_count
        best_off = run_start
    print(f"  fp16 scan in [{start}-{end}]: best run={best_count} (need {num_verts})")
    if best_off >= 0 and best_count >= num_verts:
        x0 = fp16_to_float(u16(data,best_off))
        y0 = fp16_to_float(u16(data,best_off+2))
        z0 = fp16_to_float(u16(data,best_off+4))
        print(f"    FOUND! v0=({x0:.3f},{y0:.3f},{z0:.3f})")
        return best_off
    return -1

def find_idx_buffer(data, phys_start, phys_end, max_verts_hint):
    """Find TArray<uint16> that looks like valid triangle index buffer."""
    results = []
    o = phys_start
    while o + 8 <= phys_end:
        n = i32(data, o)
        if n > 0 and n % 3 == 0 and n * 2 <= (phys_end - o - 4):
            max_idx = 0
            valid = True
            for k in range(min(n, 300)):
                idx = u16(data, o+4 + k*2)
                if idx > max_idx: max_idx = idx
            # check last few  
            for k in range(max(0, n-10), n):
                idx = u16(data, o+4 + k*2)
                if idx > max_idx: max_idx = idx
            if max_idx > 0 and max_idx < 100000:
                results.append((o, n, max_idx))
        o += 4
    return results

# ===== ANALYSIS =====

# Export table for mafia_hq_mu_awakening.umap
# From prior analysis: base virt offset = 929 for this file
# But we need to find exports by scanning for known export names

# Known export names and locations from prior analysis:
# standing_torch: phys=30739014, size=42674
# mafia_wall_light: need to find
# mafia_wooden_rail: phys=31485789, size=140315
# cardboard_roundBush: small
# cardboard_plant: small

# Let's directly address the two main ones:
exports = {
    "standing_torch":  (30739014, 42674),
    "mafia_wooden_rail": (31485789, 140315),
}

for name, (phys, size) in exports.items():
    print(f"\n{'='*60}")
    print(f"EXPORT: {name}  phys={phys}  size={size}")
    data_slice = raw[phys:phys+size]
    
    # Show first 300 bytes as i32 sequence for structure
    print(f"\n  First 64 i32 values:")
    for k in range(min(64, size//4)):
        v = i32(data_slice, k*4)
        print(f"    [{k*4:4d}] = {v}")
    
    # Find all index buffer candidates
    print(f"\n  Scanning for index buffers...")
    idxs = find_idx_buffer(data_slice, 0, size, 100000)
    for (off, n, mx) in idxs[:10]:
        print(f"    off={off}: n={n}, max_idx={mx}, tris={n//3}, num_verts~={mx+1}")
        first6 = [u16(data_slice, off+4+k*2) for k in range(6)]
        print(f"      first6={first6}")
    
    if not idxs:
        print("    NO idx candidates found")
        continue
    
    # For each candidate: scan AFTER the index buffer for float3 positions
    for (idx_off, idx_n, idx_max) in idxs[:3]:
        num_verts = idx_max + 1
        idx_end = idx_off + 4 + idx_n * 2
        print(f"\n  After idx (off={idx_off}, n={idx_n}, end={idx_end}), searching for {num_verts} float3s...")
        
        # Scan after index buffer
        post_start = idx_end
        post_end = size
        remaining = post_end - post_start
        print(f"    Remaining after idx: {remaining} bytes (need {num_verts*12} for float3)")
        
        if remaining >= num_verts * 12:
            scan_float3(data_slice, post_start, post_end, name, num_verts)
        else:
            print(f"    Not enough bytes after idx for float3 (need {num_verts*12}, have {remaining})")
        
        # Also scan BEFORE index buffer
        print(f"  Before idx ({idx_off} bytes available), searching for {num_verts} float3s...")
        if idx_off >= num_verts * 12:
            scan_float3(data_slice, 0, idx_off, name, num_verts)
            scan_fp16_xyz(data_slice, 0, idx_off, num_verts, name)
        else:
            print(f"    Pre-idx space {idx_off} < needed {num_verts*12}")
            # Try fp16
            if idx_off >= num_verts * 6:
                scan_fp16_xyz(data_slice, 0, idx_off, num_verts, name)
            else:
                print(f"    Also not enough for fp16 ({num_verts*6})")
        
        break  # only check first (most likely) index buffer

print("\nDone.")
