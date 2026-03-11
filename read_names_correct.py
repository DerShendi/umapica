import struct

with open('chunk0_decomp.bin', 'rb') as f:
    d = f.read()

def ri32(data, off):
    return struct.unpack_from('<i', data, off)[0]

# Read all 1037 names from local offset 0 (virtual 816 = nameOffset)
off = 0
names = []
name_offsets = []
for i in range(1037):
    if off + 4 > len(d):
        print(f'EOF at entry {i}, off={off}')
        break
    entry_start = off
    slen = ri32(d, off)
    off += 4
    
    if slen < 0:
        # Wide string (UTF-16LE)
        wlen = -slen
        nm_bytes = d[off:off+wlen*2]
        nm = nm_bytes.decode('utf-16-le', 'replace').rstrip('\x00')
        off += wlen * 2
    elif slen == 0:
        nm = ''
    elif slen > 500:
        print(f'SUSPICIOUS at entry {i}: slen={slen}, off={entry_start}')
        print(f'  Hex: {d[entry_start:entry_start+16].hex()}')
        break
    else:
        nm_bytes = d[off:off+slen]
        nm = nm_bytes.decode('ascii', 'replace').rstrip('\x00')
        off += slen
    
    off += 8  # skip 8-byte hash
    names.append(nm)
    name_offsets.append(entry_start)

print(f'Total names read: {len(names)} (expected 1037)')
print(f'Final offset: {off}')
print()
print(f'First 20 names:')
for i, nm in enumerate(names[:20]):
    print(f'  [{i}] off={name_offsets[i]}: {repr(nm)}')

print()
print(f'Names around bAcceptsLights:')
for i, nm in enumerate(names):
    if 'Accept' in nm or 'bAcc' in nm:
        print(f'  [{i}]: {repr(nm)}')

print()
print(f'Last 20 names:')
for i in range(max(0, len(names)-20), len(names)):
    print(f'  [{i}] off={name_offsets[i]}: {repr(names[i])}')

print()
print(f'importOffset virtual = 31052, chunk0.uncompOff = 816')
print(f'importOffset local = 31052 - 816 = {31052-816}')
print(f'After names ends at: {off} (diff from importOff local: {off - (31052-816)})')
