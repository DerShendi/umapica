import struct
data = open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap','rb').read()
print('First 128 bytes:')
for i in range(0, 128, 16):
    hex_part = ' '.join(f'{b:02X}' for b in data[i:i+16])
    print(f'  {i:3d}: {hex_part}')
print()
print('Bytes 4-7:', ' '.join(f'{b:02X}' for b in data[4:8]))
print('As int32:', struct.unpack_from('<I', data, 4)[0])
print('As 2xint16:', struct.unpack_from('<HH', data, 4))
v = struct.unpack_from('<I', data, 4)[0]
print(f'version as single int32: {v} = 0x{v:08X}')
print(f'  low16={v & 0xFFFF}  high16={(v>>16) & 0xFFFF}')
