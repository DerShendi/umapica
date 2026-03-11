"""
Test the LZO1X-1 decompressor logic equivalent to the Java implementation.
Compare output with Python lzokay for sub-block[0] of mafia_town.umap.
"""
import struct
import lzokay

def lzo1x_decompress_java(src, src_off, src_len, uncomp_size):
    """Python translation of the Java Lzo1xDecompressor.decompress() method."""
    dst = bytearray(uncomp_size)
    ip = src_off
    ip_end = src_off + src_len
    op = 0
    
    def copy_lits(src_pos, count):
        nonlocal op
        if op + count > uncomp_size:
            raise ValueError(f"literal overflow: op={op} count={count}")
        dst[op:op+count] = src[src_pos:src_pos+count]
        op += count
    
    def copy_match(dist, length):
        nonlocal op
        src_pos = op - dist
        if src_pos < 0:
            raise ValueError(f"match dist too large: op={op} dist={dist}")
        if op + length > uncomp_size:
            raise ValueError(f"match overflow: op={op} len={length}")
        for i in range(length):
            dst[op] = dst[src_pos + i]
            op += 1
    
    if ip >= ip_end:
        raise ValueError("empty input")
    
    t = src[ip]; ip += 1
    
    if t > 17:
        t -= 17
        copy_lits(ip, t)
        ip += t
        t = src[ip]; ip += 1
        # fall through to main loop
    
    # main loop
    ended = False
    while not ended:
        if t < 16:
            # Short literal run
            if t == 0:
                while src[ip] == 0:
                    t += 255
                    ip += 1
                t += 15 + src[ip]; ip += 1
            copy_lits(ip, t + 3)
            ip += t + 3
            t = src[ip]; ip += 1
            # Now decode the following match
            # (fall through to the H2/H3 decode below for the NEW t)
            # BUT the match decode follows immediately — loop continues
            # Actually in lzo1x_d.c the literal run is followed directly by
            # the match that triggered the run end; t is the new opcode
            # which we handle next iteration. But we need to handle the match
            # that follows the literal run NOW (the "do_match" goto).
            # In the reference: after reading literals, we check if t >= 16.
            # If t < 16 again, we go back to short literal. The match is
            # encoded in a separate opcode that we'll read next.
            # Actually the literal run opcode itself IS the continuation —
            # the current t after reading it IS the next opcode.
            # So we just continue the while loop with the new t.
            continue
        
        if t >= 64:
            # H2 (M2) match: 3 high bits encode distance+length
            match_len = ((t >> 5) & 7) - 1  # should be 1 or 2... wait
            # From actual lzo1x: t >= 0x40:
            #   match_len = 3 + ((t >> 5) - 2)   ... hmm
            # Let me look at this differently.
            # In lzo1x_d.c (GPL reference):
            #   t = *ip++;
            #   if (t >= M3_MARKER) goto match_m3;
            #   if (t >= M2_MARKER) goto match_m2;
            # M2_MARKER = 0x40, M3_MARKER = 0x20
            # M2 match:
            #   t >= 0x40
            #   ml = 3 + ((t >> 5) - 2)   hmm, but t>>5 can be 2 or 3 (for 64..127)
            #   t>>5 for 64-95 = 2; for 96-127 = 3
            #   ml = (2-2)+3=3 or (3-2)+3=4
            match_len = 2 + ((t >> 5) - 1)   # 2 for t<96, 3 for t>=96?? 
            # Actually lzo1x has different formulas. Let me use the known-good one from
            # multiple sources:
            # M2: bit pattern 1xx_ddd_ll where xx=len-1(1..3), ddd=dist[2:0], ll=dist[8:3]?
            # This is getting complicated. Let me just trace with actual lzokay output.
            pass
        
        # For now, just return None to indicate incomplete
        return None
    
    return bytes(dst)


# The correct reference output:
with open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap', 'rb') as f:
    f.seek(1232)
    sub0_data = f.read(30180)

correct = lzokay.decompress(sub0_data, 131072)
print(f"Correct size: {len(correct)}")
print(f"First 32 bytes: {' '.join(f'{b:02X}' for b in correct[:32])}")

# Let me trace through the first few opcodes manually
src = sub0_data
ip = 0
print(f"\nFirst 16 bytes of compressed: {' '.join(f'{b:02X}' for b in src[:16])}")
t = src[ip]; ip += 1
print(f"t[0] = 0x{t:02X} = {t}")
# t=0x03 < 18, so no initial literal
# Enter main loop: t=3 < 16, t != 0
# copy t+3=6 literals from ip=1
print(f"t<16, copy {t+3}=6 literals from ip={ip}: {' '.join(f'{b:02X}' for b in src[ip:ip+t+3])}")
ip += t+3  # ip=7
t = src[ip]; ip += 1
print(f"t[7] = 0x{t:02X} = {t}  (ip now {ip})")
