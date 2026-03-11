import struct

with open('chunk0_decomp.bin', 'rb') as f:
    d = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

def ru32(data, off):
    return struct.unpack_from('<I', data, off)[0]

def ru16(data, off):
    return struct.unpack_from('<H', data, off)[0]

# Look at local offset 0..60 -- this is after chunk header (virtual start=21)
# Shows start of "decompressed content"
print('=== Start of decompressed chunk (local 0..60) ===')
chunk = d[0:60]
for i in range(0, len(chunk), 8):
    hex_part = ' '.join(f'{b:02x}' for b in chunk[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk[i:i+8])
    print(f'  local {i:3d} (virt {21+i:4d}): {hex_part:<24}  {asc_part}')

print()
# The virtual offset of nameOffset=816 -> local 795
# But there's 1 extra byte at local 795 (null terminator from some string before name table)
# The actual header fields in this file are stored in the UNCOMPRESSED form
# They're between virtual 0 and virtualNameOffset (816-1 = 815)
# But chunk 0 starts at virtual 21, NOT virtual 0! 

# SO: the bytes at local 0..814 (virtual 21..835) are the CONTINUATION of the header
# The first part of the header (virtual 0..20) is OUTSIDE the chunks

# Wait... let me reconsider. The actual file has:
# bytes 0..1151: file header (not compressed)
# bytes 1152..: compressed chunks
# But CompressedChunks[0] says uncompOff=21, meaning virtual address 21 starts there
# What about virtual addresses 0..20?

# Let me look at what's in the actual file at offsets 0..20
print('=== Actual file bytes 0..40 (raw, uncompressed header area) ===')
with open('run/umapica/mafia_town.umap', 'rb') as f:
    raw = f.read(200)

for i in range(0, 80, 8):
    hex_part = ' '.join(f'{b:02x}' for b in raw[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in raw[i:i+8])
    print(f'  file[{i:3d}]: {hex_part:<24}  {asc_part}')

print()
print('=== nameOffset in header = 816 ===')
print('=== chunk0 contributes virtual 21..21+976035-1 ===')
print('=== So nameTable at virtual 816 = local 816-21 = 795 in decompressed chunk ===')
print()
print('=== Bytes at local 793..830 ===')
region = d[793:830]
for i in range(0, len(region), 8):
    offs = 793 + i
    hex_part = ' '.join(f'{b:02x}' for b in region[i:i+8])
    asc_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in region[i:i+8])
    print(f'  local {offs}: {hex_part:<24}  {asc_part}')

print()
# Entry 0 at local 796 - let's see the full pattern of first 5 entries
print('=== First 5 name entries (starting at local 796) ===')
off = 796
for i in range(5):
    slen = ri32(d, off)
    entry_start = off
    off += 4
    name = d[off:off+slen]
    off += slen
    extra = d[off:off+8]
    off += 8
    # Parse extra as two uint32 or uint16s
    e0 = ru32(extra, 0) if len(extra) >= 4 else 0
    e1 = ru32(extra, 4) if len(extra) >= 8 else 0
    e0u16a = ru16(extra, 0) if len(extra) >= 2 else 0
    e0u16b = ru16(extra, 2) if len(extra) >= 4 else 0
    e1u16a = ru16(extra, 4) if len(extra) >= 6 else 0
    e1u16b = ru16(extra, 6) if len(extra) >= 8 else 0
    print(f'  [{i}] off={entry_start}: len={slen}, name={repr(name)}, extra={extra.hex()}')
    print(f'       extra as uint16s: {e0u16a:04x} {e0u16b:04x} {e1u16a:04x} {e1u16b:04x}')
    print(f'       extra as uint32s: {e0:08x} {e1:08x}')

print()
print('=== Last 5 name entries (1000..1003 out of 1004) ===')
off = 796
for i in range(1000):
    slen = ri32(d, off)
    off += 4
    if slen < 0: off += (-slen) * 2
    else: off += slen
    off += 8

for i in range(1000, 1004):
    slen = ri32(d, off)
    entry_start = off
    off += 4
    if slen < 0:
        name = d[off:off+(-slen)*2].decode('utf-16-le', 'replace').rstrip('\x00')
        off += (-slen)*2
    else:
        name = d[off:off+slen].decode('ascii', 'replace').rstrip('\x00')
        off += slen
    extra = d[off:off+8]
    off += 8
    e0u16a = struct.unpack_from('<H', extra, 0)[0]
    e0u16b = struct.unpack_from('<H', extra, 2)[0]
    e1u16a = struct.unpack_from('<H', extra, 4)[0]
    e1u16b = struct.unpack_from('<H', extra, 6)[0]
    print(f'  [{i}] off={entry_start}: len={slen}, name={repr(name)}, extra={extra.hex()}')
    print(f'       extra as uint16s: {e0u16a:04x} {e0u16b:04x} {e1u16a:04x} {e1u16b:04x}')

print()
print(f'=== After 1004 entries, off={off} ===')
print(f'=== Next 40 bytes at off={off}: {d[off:off+40].hex()} ===')
print()

# What if the "extra" after each name is NOT always 8 bytes?
# Let's check if some UE3 name entries have different extra structure
# In UE3 Engine source: FNameEntrySerialized has:
#   FString Name + uint32 NonCasePreservingHash + uint32 CasePreservingHash
# The Hash approach changed over versions. Let me check if entries have 4-byte extra

print('=== Test with 4-byte extra per entry ===')
off = 796
names_4 = []
for i in range(1037):
    if off + 4 > len(d): break
    slen = ri32(d, off)
    entry_start = off
    off += 4
    if slen < 0:
        off += (-slen)*2
        name = '(wide)'
    elif slen == 0 or slen > 500:
        print(f'Entry {i}: bad slen={slen} at {entry_start}')
        break
    else:
        nm_bytes = d[off:off+slen]
        name = nm_bytes.decode('ascii','replace').rstrip('\x00')
        off += slen
    off += 4  # only 4 bytes extra
    names_4.append(name)

print(f'With 4-byte extra: read {len(names_4)} names')
print(f'Last 5: {[repr(n) for n in names_4[-5:]]}')
