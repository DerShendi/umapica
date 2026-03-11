import struct, re

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

# Strings start around 1263. In UE4 format name entry is: FString (int32 len + chars) + uint16 + uint16
# Let's scan backwards from 1263 to find where FString entries begin.
# A valid FString before 1263 would have: int32 len at (1263 - len - 4), value = len
# Try to find valid FStrings by working backward

# First: print bytes 1230-1300
print('=== Bytes 1230-1340 ===')
for i in range(1230, 1340, 16):
    h = ' '.join('%02X'%b for b in data[i:i+16])
    a = ''.join(chr(b) if 32<=b<127 else '.' for b in data[i:i+16])
    print(f'  {i:5d}: {h:<48}  {a}')

print()

# Try reading as UE4 FString + 4 byte hash starting at various offsets
for start_off in range(1220, 1270, 1):
    off = start_off
    ok = True
    names = []
    for i in range(8):
        if off + 4 > len(data): ok=False; break
        slen = struct.unpack_from('<i', data, off)[0]; off+=4
        if slen <= 0 or slen > 64: ok=False; break
        if off + slen > len(data): ok=False; break
        chars = data[off:off+slen-1]
        if not all(32 <= b < 128 or b == 0 for b in chars): ok=False; break
        name = chars.decode('ascii','replace')
        if not re.match(r'^[A-Za-z_][A-Za-z0-9_]*$', name): ok=False; break
        off += slen
        # hash suffix (4 bytes)
        off += 4
        names.append(name)
    if ok and len(names) >= 5:
        print(f'Valid FString entries starting at {start_off}: {names[:8]}')

print()
# Also: try without hash suffix
for start_off in range(1220, 1270, 1):
    off = start_off
    ok = True
    names = []
    for i in range(8):
        if off + 4 > len(data): ok=False; break
        slen = struct.unpack_from('<i', data, off)[0]; off+=4
        if slen <= 0 or slen > 64: ok=False; break
        if off + slen > len(data): ok=False; break
        chars = data[off:off+slen-1]
        if not all(32 <= b < 128 for b in chars): ok=False; break
        name = chars.decode('ascii','replace')
        if not re.match(r'^[A-Za-z_][A-Za-z0-9_]*$', name): ok=False; break
        off += slen
        names.append(name)
    if ok and len(names) >= 6:
        print(f'Valid FString entries (no hash) starting at {start_off}: {names[:10]}')
