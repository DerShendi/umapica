import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(40000)
f.close()

print('=== Bytes 810-900 ===')
for i in range(810, 900, 16):
    h = ' '.join('%02X'%b for b in data[i:i+16])
    a = ''.join(chr(b) if 32<=b<127 else '.' for b in data[i:i+16])
    print(f'  {i:5d}: {h:<48}  {a}')

print()
print('=== Try UE3 FNameEntry: null-terminated string + uint32 flags ===')
off = 816
for j in range(10):
    s = b''
    start = off
    while off < len(data) and data[off] != 0 and len(s) < 128:
        s += bytes([data[off]]); off+=1
    if off < len(data): off+=1  # skip null
    flags = struct.unpack_from('<I', data, off)[0]; off+=4
    print(f'  Name[{j}]: [{s.decode("ascii","replace")}]  flags=0x{flags:08X}  (read from {start}, next={off})')

print()
print('=== Try UE4 FString name + uint32 + uint32 hashes ===')
off = 816
for j in range(6):
    start = off
    slen = struct.unpack_from('<i', data, off)[0]; off+=4
    if slen > 0 and slen < 512:
        name = data[off:off+slen-1].decode('ascii','replace'); off+=slen
    elif slen < 0:
        charCount = -slen
        bs = data[off:off+charCount*2]; off+=charCount*2
        name = bs.decode('utf-16-le','replace').rstrip('\x00')
    else:
        name='(empty)'; off+=0
    h1 = struct.unpack_from('<H', data, off)[0]; off+=2
    h2 = struct.unpack_from('<H', data, off)[0]; off+=2
    print(f'  Name[{j}]: [{name}]  h1={h1} h2={h2}  (from {start}, next={off})')
