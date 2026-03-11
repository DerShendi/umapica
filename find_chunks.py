"""Find actual chunk boundaries using known decompressed content"""
import struct

data = open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap','rb').read()
print(f'File size: {len(data)}')

# Scan for 0x9E2A83C1 (LZO block magic = same as UE magic)
magic = b'\xC1\x83\x2A\x9E'
pos = 0
found = []
while pos < len(data):
    idx = data.find(magic, pos)
    if idx == -1: break
    found.append(idx)
    pos = idx + 1
print(f'Magic 0x9E2A83C1 at: {found}')

# Look at offset 109: compressionFlags + chunk_count
print('\nOffset 109:', ' '.join(f'{b:02X}' for b in data[109:145]))
# 02 00 00 00 = comp_flags = 2
# 15 00 00 00 = 21 chunks
# Then chunk entries...

off = 109
comp_flags = struct.unpack_from('<I', data, off)[0]; off+=4
chunk_count = struct.unpack_from('<I', data, off)[0]; off+=4
print(f'comp_flags=0x{comp_flags:08X} chunk_count={chunk_count}')

# Try int32 chunk entries (UE3 older format)
print('\nTrying int32 chunk entries:')
for i in range(chunk_count):
    uo = struct.unpack_from('<I', data, off)[0]; off+=4
    us = struct.unpack_from('<I', data, off)[0]; off+=4
    co = struct.unpack_from('<I', data, off)[0]; off+=4
    cs = struct.unpack_from('<I', data, off)[0]; off+=4
    print(f'  chunk[{i}]: uncompOff={uo} us={us} compOff={co} cs={cs}')

# Check if first chunk compOff matches the LZO magic
print(f'\nAfter chunks: offset={off}')

# We know the file has chunks. Let's also examine what's at the found magic positions
for p in found:
    print(f'\nAt {p}: {" ".join(f"{b:02X}" for b in data[p:p+20])}')
    if p > 0:
        print(f'  -4: {" ".join(f"{b:02X}" for b in data[p-4:p+20])}')
