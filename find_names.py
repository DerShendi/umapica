import struct
import re

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\run\umapica\mafia_town.umap','rb')
data = f.read(200000)
f.close()

print('File size (first 200KB read):', len(data))

# Search for sequences of printable ASCII bytes (likely name table strings)
# UE names are typically 4-30 chars of ASCII
pattern = re.compile(b'[A-Za-z_][A-Za-z0-9_]{3,30}')
matches = []
for m in pattern.finditer(data[:50000]):
    matches.append((m.start(), m.group().decode('ascii')))

# Group nearby matches (within 50 bytes of each other = likely a name table)
clusters = []
current = [matches[0]] if matches else []
for m in matches[1:]:
    if m[0] - current[-1][0] < 80:
        current.append(m)
    else:
        if len(current) >= 5:
            clusters.append(current)
        current = [m]
if len(current) >= 5:
    clusters.append(current)

print(f'\nFound {len(clusters)} clusters of strings')
for c in clusters[:3]:
    print(f'\n  Cluster starting at {c[0][0]}: {len(c)} matches')
    for pos, name in c[:15]:
        print(f'    [{pos:6d}] {name}')
    # Show raw bytes before first match
    start = max(0, c[0][0] - 16)
    print(f'  Raw bytes {start}-{c[0][0]+4}: {data[start:c[0][0]+4].hex()}')
