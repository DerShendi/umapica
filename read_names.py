import struct

f = open(r'C:\Users\Shendi\Documents\rojects\Umapica\chunk0_decomp.bin','rb')
decomp = f.read()
f.close()

out = []
def p(*a): out.append(' '.join(str(x) for x in a))

# The name table from virtual offset 816 = local 816-21=795 in this chunk.
# But names start at local 796 using FString+8 format.
# At local 795 = 0x00 which is the null terminator of a previous entry.
# 
# Let me try to read names starting from the very beginning (local 0) to understand
# the structure and find where names truly start.
# 
# The chunk covers VIRTUAL positions 21..997055.
# The header that was read by our parser covers virtual offsets 0..48 (file header).
# The "uncompressed stream" from virtual 21 includes the REST of the header fields
# (DependsOffset, GUIDs, etc.) plus the actual data tables (names, imports, exports).
# 
# From virtual header offset 49..815 = local 28..794 would contain header continuations.
# The name table starts at virtual 816 = local 795 (or 796 off by one).
# 
# Let's try to read the FULL name table starting at local 796:
p('=== Full name table: FString+8 bytes, starting at local 796 (virt 817) ===')
off = 796
name_count = 1037  # from header
names = []
for i in range(name_count):
    if off + 4 > len(decomp): 
        p(f'  Ran out of data at entry {i}'); break
    slen = struct.unpack_from('<i', decomp, off)[0]; off+=4
    if slen < 0:
        # Negative = UTF-16LE
        byte_len = -slen * 2
        if off + byte_len > len(decomp): p(f'  Entry {i}: wide string too long'); break
        name = decomp[off:off+byte_len-2].decode('utf-16-le','replace')
        off += byte_len
    elif slen > 0:
        if off + slen > len(decomp): p(f'  Entry {i}: string too long'); break
        name = decomp[off:off+slen-1].decode('ascii','replace')
        off += slen
    else:
        name = ''
    # Skip 8-byte hash/extra
    off += 8
    names.append(name)
    if i < 30 or (i % 100 == 0):
        p(f'  [{i}] [{name}]')
if len(names) == name_count:
    p(f'SUCCESS: Read all {name_count} names!')
p(f'Last few names:')
for n in names[-10:]:
    p(f'  [{n}]')
p(f'Final offset after names: {off} (should be near importOffset=31052 minus 21 = 31031)')
p(f'importOffset=31052, local = 31052-21 = 31031')
p(f'Difference: {off} vs {31031}')

# Also verify: does the name at index 0 look right?
# In a UE3/UE4 package, the first few names are usually:
# "None", "ByteProperty", "IntProperty", "FloatProperty", etc. (engine names)
# OR could be map-specific names. Let's see!

with open(r'C:\Users\Shendi\Documents\rojects\Umapica\names_found.txt','w',encoding='utf-8') as fw:
    fw.write('\n'.join(out))
    fw.write('\n\n=== ALL NAMES ===\n')
    fw.write('\n'.join(f'{i}: {n}' for i,n in enumerate(names)))
print('Done.')
