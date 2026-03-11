"""
Correct Python port of lzo1x_d.ch (LZO1X) — exactly following the C source.
Tests sub[0] of mafia_town.umap against lzokay.
"""
import lzokay

M1_MAX_OFFSET = 0x0400
M2_MAX_OFFSET = 0x0800
M3_MAX_OFFSET = 0x4000  # == M4_MAX_OFFSET
M4_MAX_OFFSET = 0x4000


def u8(b, i): return b[i] & 0xFF


def decompress_lzo1x(src, uncomp_size):
    op = bytearray(uncomp_size)
    np = 0   # output pointer
    ip = 0   # input pointer

    # ── initial literal run if first byte > 17 ────────────────────────────────
    t = u8(src, ip)
    if t > 17:
        ip += 1
        t -= 17
        if t < 4:
            # goto match_next (copy t literals then re-enter main loop)
            for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
            t = u8(src, ip); ip += 1
            # fall through to match handler below
            # We handle this by jumping into the match-dispatch with current t
            # — done by setting a flag and skipping the outer loop first iteration
            goto_match = True
        else:
            for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
            # fall through to first_literal_run
            goto_match = False
            t = u8(src, ip); ip += 1
            if t >= 16:
                goto_match = True  # enter match dispatch directly
            else:
                # first_literal_run M1
                m_pos = np - (1 + M4_MAX_OFFSET) - (t >> 2) - (u8(src, ip) << 2); ip += 1
                assert m_pos >= 0
                op[np] = op[m_pos]; np += 1
                op[np] = op[m_pos+1]; np += 1
                op[np] = op[m_pos+2]; np += 1
                t = u8(src, ip - 1) & 3
                if t != 0:
                    for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
                t = u8(src, ip); ip += 1
                goto_match = True  # now in match loop
    else:
        goto_match = False

    # ── outer for(;;) loop ────────────────────────────────────────────────────
    while True:
        if not goto_match:
            # literal run entry
            t = u8(src, ip); ip += 1
            if t < 16:
                if t == 0:
                    while u8(src, ip) == 0:
                        t += 255; ip += 1
                    t += 15 + u8(src, ip); ip += 1
                # copy t+3 literals
                n = t + 3
                for _ in range(n): op[np] = src[ip]; np += 1; ip += 1
                # first_literal_run:
                t = u8(src, ip); ip += 1
                if t >= 16:
                    goto_match = True
                else:
                    m_pos = np - (1 + M4_MAX_OFFSET) - (t >> 2) - (u8(src, ip) << 2); ip += 1
                    assert m_pos >= 0
                    op[np] = op[m_pos]; np += 1
                    op[np] = op[m_pos+1]; np += 1
                    op[np] = op[m_pos+2]; np += 1
                    # match_done
                    t = u8(src, ip - 2) & 3
                    if t == 0:
                        goto_match = False
                        continue  # back to outer for
                    # match_next: copy t literals
                    for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
                    t = u8(src, ip); ip += 1
                    goto_match = True

        if goto_match:
            goto_match = False
            # match dispatch
            while True:
                if t >= 64:    # M2
                    m_pos = np - 1 - ((t >> 2) & 7) - (u8(src, ip) << 3); ip += 1
                    t = (t >> 5) - 1
                elif t >= 32:  # M3
                    t &= 31
                    if t == 0:
                        while u8(src, ip) == 0: t += 255; ip += 1
                        t += 31 + u8(src, ip); ip += 1
                    m_pos = np - 1 - (u8(src, ip) >> 2) - (u8(src, ip+1) << 6)
                    ip += 2
                elif t >= 16:  # M4
                    m_pos = np - ((t & 8) << 11)
                    t &= 7
                    if t == 0:
                        while u8(src, ip) == 0: t += 255; ip += 1
                        t += 7 + u8(src, ip); ip += 1
                    m_pos -= (u8(src, ip) >> 2) + (u8(src, ip+1) << 6)
                    ip += 2
                    if m_pos == np:
                        return bytes(op[:np])  # eof_found
                    m_pos -= M4_MAX_OFFSET
                else:  # M1
                    m_pos = np - 1 - (t >> 2) - (u8(src, ip) << 2); ip += 1
                    assert m_pos >= 0, f"M1 OOB: {m_pos} np={np}"
                    op[np] = op[m_pos]; np += 1
                    op[np] = op[m_pos+1]; np += 1
                    # match_done
                    t = u8(src, ip - 2) & 3
                    if t == 0:
                        break  # back to outer for
                    for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
                    t = u8(src, ip); ip += 1
                    continue  # inner match loop

                # copy_match: copy t+2 bytes
                assert m_pos >= 0, f"match OOB: m_pos={m_pos} np={np}"
                mp = m_pos
                op[np] = op[mp]; np += 1; mp += 1
                op[np] = op[mp]; np += 1; mp += 1
                for _ in range(t): op[np] = op[mp]; np += 1; mp += 1

                # match_done
                t = u8(src, ip - 2) & 3
                if t == 0:
                    break  # back to outer for
                for _ in range(t): op[np] = src[ip]; np += 1; ip += 1
                t = u8(src, ip); ip += 1
                # continue inner match loop

    return bytes(op[:np])


# ── Test ─────────────────────────────────────────────────────────────────────

with open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap', 'rb') as f:
    f.seek(1232)
    sub0_data = f.read(30180)

expected = lzokay.decompress(sub0_data, 131072)
print(f"Expected first 16: {' '.join(f'{b:02X}' for b in expected[:16])}")

try:
    result = decompress_lzo1x(bytes(sub0_data), 131072)
    if result == expected:
        print("✓ PASS: sub[0] matches lzokay exactly!")
    else:
        for i in range(min(len(result), len(expected))):
            if result[i] != expected[i]:
                print(f"✗ FAIL at byte {i}: got {result[i]:02X} expected {expected[i]:02X}")
                print(f"  context got:      {' '.join(f'{b:02X}' for b in result[max(0,i-4):i+8])}")
                print(f"  context expected: {' '.join(f'{b:02X}' for b in expected[max(0,i-4):i+8])}")
                break
        else:
            print(f"Sizes: got {len(result)} expected {len(expected)}")
except Exception as e:
    import traceback; traceback.print_exc()
