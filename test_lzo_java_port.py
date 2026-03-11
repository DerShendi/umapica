"""
Python port of the new Lzo1xDecompressor.java — tests correctness against lzokay.
"""
import struct, sys
import lzokay

M4_MAX_OFFSET = 0x4000

def u8(b, i): return b[i] & 0xFF


def copy_lits(src, src_off, dst, dst_off, n):
    dst[dst_off:dst_off+n] = src[src_off:src_off+n]
    return dst_off + n


def copy_match_abs(dst, dst_off, src_pos, n):
    assert src_pos >= 0 and src_pos < dst_off, f"match OOB: src={src_pos} dst={dst_off}"
    for _ in range(n):
        dst[dst_off] = dst[src_pos]
        dst_off += 1
        src_pos += 1
    return dst_off


def first_literal_run(src, op, state):
    """Processes the first_literal_run opcode sequence."""
    ip, np = state
    t = u8(src, ip); ip += 1
    if t < 16:
        # M1 match
        m_dist = 1 + M4_MAX_OFFSET + (t >> 2) + (u8(src, ip) << 2); ip += 1
        np = copy_match_abs(op, np, np - m_dist, 3)
        t = u8(src, ip - 1) & 3
        if t != 0:
            np = copy_lits(src, ip, op, np, t); ip += t
    # t >= 16: no M1, just update state
    return [ip, np]


def inner_loop(src, ip, op, np, t):
    """
    Process matches until no trailing literals remain.
    Returns (ip, np) or (ip, -1) for EOS.
    """
    while True:
        if t >= 64:
            # M2
            m_pos = np - 1 - ((t >> 2) & 7) - (u8(src, ip) << 3); ip += 1
            m_len = (t >> 5) - 1
        elif t >= 32:
            # M3
            m_len = t & 31
            if m_len == 0:
                while u8(src, ip) == 0: m_len += 255; ip += 1
                m_len += 31 + u8(src, ip); ip += 1
            m_pos = np - 1 - (u8(src, ip) >> 2) - (u8(src, ip+1) << 6)
            ip += 2
        elif t >= 16:
            # M4
            m_pos = np - ((t & 8) << 11)
            m_len = t & 7
            if m_len == 0:
                while u8(src, ip) == 0: m_len += 255; ip += 1
                m_len += 7 + u8(src, ip); ip += 1
            m_pos -= (u8(src, ip) >> 2) + (u8(src, ip+1) << 6)
            ip += 2
            if m_pos == np:
                return ip, -1  # EOS
            m_pos -= M4_MAX_OFFSET
        else:
            # M1 inside inner loop
            m_pos = np - 1 - (t >> 2) - (u8(src, ip) << 2); ip += 1
            op[np] = op[m_pos]; np += 1
            op[np] = op[m_pos+1]; np += 1
            # match_done
            t = u8(src, ip - 1) & 3
            if t == 0:
                return ip, np
            np = copy_lits(src, ip, op, np, t); ip += t
            t = u8(src, ip); ip += 1
            continue

        # copy_match
        np = copy_match_abs(op, np, m_pos, m_len + 2)

        # match_done
        t = u8(src, ip - 2) & 3
        if t == 0:
            return ip, np
        np = copy_lits(src, ip, op, np, t); ip += t
        t = u8(src, ip); ip += 1


def main_loop(src, op, state, cap):
    ip, np = state
    t = u8(src, ip); ip += 1

    while True:
        if t < 16:
            # Literal run
            if t == 0:
                while u8(src, ip) == 0: t += 255; ip += 1
                t += 15 + u8(src, ip); ip += 1
            np = copy_lits(src, ip, op, np, t + 3); ip += t + 3

            # first_literal_run
            t = u8(src, ip); ip += 1
            if t < 16:
                m_dist = 1 + M4_MAX_OFFSET + (t >> 2) + (u8(src, ip) << 2); ip += 1
                np = copy_match_abs(op, np, np - m_dist, 3)
                t = u8(src, ip - 1) & 3
                if t != 0: np = copy_lits(src, ip, op, np, t); ip += t
                t = u8(src, ip); ip += 1
                continue  # outer loop

        # match section
        ip, np = inner_loop(src, ip, op, np, t)
        if np == -1:
            return  # EOS
        t = u8(src, ip); ip += 1


def decompress(src, src_off, src_len, uncomp_size):
    op = bytearray(uncomp_size)
    state = [src_off, 0]

    first_lit_done = False
    if u8(src, state[0]) > 17:
        t = u8(src, state[0]); state[0] += 1
        t -= 17
        state[1] = copy_lits(src, state[0], op, state[1], t)
        state[0] += t
        state = first_literal_run(src, op, state)
        first_lit_done = True

    if not first_lit_done:
        state = first_literal_run(src, op, state)

    main_loop(src, op, state, uncomp_size)
    return bytes(op)


# ── Test ──────────────────────────────────────────────────────────────────────

with open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap', 'rb') as f:
    f.seek(1232)
    sub0_data = f.read(30180)

expected = lzokay.decompress(sub0_data, 131072)

try:
    result = decompress(sub0_data, 0, 30180, 131072)
    if result == expected:
        print("✓ PASS: sub[0] decompression matches lzokay exactly!")
        print(f"  First 16: {' '.join(f'{b:02X}' for b in result[:16])}")
    else:
        # Find first difference
        for i in range(min(len(result), len(expected))):
            if result[i] != expected[i]:
                print(f"✗ FAIL at byte {i}: got {result[i]:02X} expected {expected[i]:02X}")
                print(f"  Context got:      {' '.join(f'{b:02X}' for b in result[max(0,i-4):i+8])}")
                print(f"  Context expected: {' '.join(f'{b:02X}' for b in expected[max(0,i-4):i+8])}")
                break
        else:
            print(f"✗ FAIL: sizes differ: got {len(result)} expected {len(expected)}")
except Exception as e:
    print(f"✗ EXCEPTION: {e}")
    import traceback; traceback.print_exc()
