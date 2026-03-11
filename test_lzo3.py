"""Focused M4 debug — find the M4 OOB at np=120"""
import lzokay

M4_MAX_OFFSET = 0x4000

def u8(b, i): return b[i] & 0xFF

data = open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap', 'rb').read()
sub = data[1232:1232+30180]
src = bytes(sub)

op = bytearray(131072)
np = 0
ip = 0

t = u8(src, ip)
# t=3, not >17

# outer loop entry
t = u8(src, ip); ip += 1  # t=3
# literal run: copy t+3=6 bytes
op[np:np+6] = src[ip:ip+6]; ip += 6; np += 6

# first_literal_run:
t = u8(src, ip); ip += 1  # t=0x60=96, >=16 -> goto match

# Inner match loop (#1 M2: t=96)
# ... following the fixed algorithm. Let me just run lzokay step-by-step via
# a simplified but correct implementation, logging every M4.

def decompress_debug(src, uncomp_size):
    op = bytearray(uncomp_size)
    np = 0
    ip = 0
    mc = 0  # match count

    def u8(i): return src[i] & 0xFF

    t = u8(ip)
    if t <= 17:
        # enter main outer loop normally
        pass
    else:
        ip += 1; t -= 17
        if t < 4:
            for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
            goto_first_lit = True
        else:
            for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
            goto_first_lit = True

    # Main loop - implement as a generator of (phase, t, ip, np)
    def outer_loop():
        nonlocal ip, np
        while True:
            t = u8(ip); ip += 1
            if t >= 16:
                yield ('match', t)
                return
            # literal run
            if t == 0:
                while u8(ip) == 0: 
                    t += 255; ip += 1
                t += 15 + u8(ip); ip += 1
            n = t + 3
            for _ in range(n): op[np] = src[ip]; np += 1; ip += 1
            # first_literal_run:
            t = u8(ip); ip += 1
            if t >= 16:
                yield ('match', t)
                return
            # M1 at first_literal_run
            m_dist = 1 + M4_MAX_OFFSET + (t >> 2) + (u8(ip) << 2); ip += 1
            m_pos = np - m_dist
            if m_pos < 0: raise AssertionError(f"flr M1 OOB: {m_pos} np={np}")
            op[np] = op[m_pos]; np += 1
            op[np] = op[m_pos+1]; np += 1
            op[np] = op[m_pos+2]; np += 1
            t = u8(ip-1) & 3
            if t:
                for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
            # continue outer loop (next iteration)

    # Full implementation
    ip = 0; np = 0
    t = u8(ip)
    if t > 17:
        ip += 1; t -= 17
        for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
        if t >= 4:
            # fall to first_literal_run
            t = u8(ip); ip += 1
            if t < 16:
                m_dist = 1+M4_MAX_OFFSET+(t>>2)+(u8(ip)<<2); ip+=1
                m_pos = np-m_dist
                assert m_pos >= 0
                op[np]=op[m_pos]; np+=1; op[np]=op[m_pos+1]; np+=1; op[np]=op[m_pos+2]; np+=1
                t = u8(ip-1)&3
                if t: 
                    for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                t = u8(ip); ip+=1
            # t is now the first match opcode
        else:
            t = u8(ip); ip+=1
            # goto match_next: t < 4 literals already copied

    # Main outer loop
    in_match = (t >= 16) if t > 17 else False

    # Restart cleanly
    ip = 0; np = 0
    t = u8(ip)
    # t=3, <=17, not >17 → enter regular outer loop

    outer_t = None
    
    # outer for loop
    while True:
        # Get t for this iteration
        if outer_t is not None:
            t = outer_t
            outer_t = None
        else:
            t = u8(ip); ip += 1

        if t < 16:
            # literal run
            if t == 0:
                while u8(ip) == 0: t += 255; ip += 1
                t += 15 + u8(ip); ip += 1
            n = t + 3
            for _ in range(n): op[np]=src[ip]; np+=1; ip+=1
            # first_literal_run:
            t = u8(ip); ip += 1
            if t < 16:
                m_dist = 1+M4_MAX_OFFSET+(t>>2)+(u8(ip)<<2); ip+=1
                m_pos = np-m_dist
                assert m_pos >= 0, f"flr M1 OOB m_pos={m_pos} np={np}"
                op[np]=op[m_pos]; np+=1; op[np]=op[m_pos+1]; np+=1; op[np]=op[m_pos+2]; np+=1
                t = u8(ip-1)&3
                if t:
                    for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                # match_done goes to outer loop
                outer_t = u8(ip); ip += 1
                continue
            # fall through to match with t

        # match loop
        while True:
            mc += 1
            if t >= 64:  # M2
                extra = u8(ip); ip += 1
                m_pos = np-1-((t>>2)&7)-(extra<<3)
                t = (t>>5)-1
            elif t >= 32:  # M3
                t &= 31
                if t == 0:
                    while u8(ip) == 0: t += 255; ip += 1
                    t += 31+u8(ip); ip += 1
                m_pos = np-1-(u8(ip)>>2)-(u8(ip+1)<<6); ip += 2
            elif t >= 16:  # M4
                hi = (t&8)<<11
                t &= 7
                if t == 0:
                    while u8(ip) == 0: t += 255; ip += 1
                    t += 7+u8(ip); ip += 1
                lo = (u8(ip)>>2)+(u8(ip+1)<<6)
                if np == 0 or lo+hi == 0:
                    print(f"M4 at mc={mc}: ip={ip} np={np} hi={hi} lo={lo} t={t}")
                m_pos = np - hi - lo
                ip += 2
                if m_pos == np:
                    print(f"EOF at mc={mc} np={np}")
                    return bytes(op[:np])
                m_pos -= M4_MAX_OFFSET
                if m_pos < 0:
                    print(f"M4 OOB at mc={mc}: m_pos={m_pos} np={np} hi={hi} lo={lo}")
                    raise AssertionError(f"M4 OOB m_pos={m_pos} np={np}")
            else:  # M1
                extra = u8(ip); ip += 1
                m_pos = np-1-(t>>2)-(extra<<2)
                assert m_pos >= 0, f"M1 OOB m_pos={m_pos} np={np}"
                op[np]=op[m_pos]; np+=1; op[np]=op[m_pos+1]; np+=1
                t = u8(ip-2)&3
                if t:
                    for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                    outer_t = u8(ip); ip += 1
                    break  # back to outer, but first set t
                break  # back to outer
            # copy_match: t+2 bytes
            assert m_pos >= 0, f"copy_match OOB m_pos={m_pos} np={np}"
            mp = m_pos
            op[np]=op[mp]; np+=1; mp+=1
            op[np]=op[mp]; np+=1; mp+=1
            for _ in range(t): op[np]=op[mp]; np+=1; mp+=1
            # match_done
            t = u8(ip-2)&3
            if t:
                for _ in range(t): op[np]=src[ip]; np+=1; ip+=1
                t = u8(ip); ip += 1
                # continue inner match loop
            else:
                break  # back to outer loop

    return bytes(op[:np])

try:
    result = decompress_debug(src, 131072)
    expected = lzokay.decompress(src, 131072)
    if result == expected:
        print("PASS!")
    else:
        for i in range(min(len(result), len(expected))):
            if result[i] != expected[i]:
                print(f"FAIL at {i}: got {result[i]:02X} expected {expected[i]:02X}")
                break
except Exception as e:
    import traceback; traceback.print_exc()
