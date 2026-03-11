"""Manual trace of LZO sub[0] to debug M4 OOB at np=120"""
data = open('C:/Users/Shendi/Documents/rojects/Umapica/run/umapica/mafia_town.umap','rb').read()
sub = data[1232:1232+30180]

def u8(b,i): return b[i]&0xFF

M4_MAX_OFFSET = 0x4000
ip = 0
np = 0

print(f"First byte: {u8(sub,0):#04x} = {u8(sub,0)}")
# first byte = 3 < 17, skip initial literal run

# outer loop iteration 1:
t = u8(sub,ip); ip+=1
print(f"\n=== outer loop: t={t:#04x}={t} ip→{ip} ===")
# t=3 < 16 → literal run, t==0? no
n = t+3
print(f"literal run: copy {n} lits from ip={ip}")
# just advance ip, np
ip += n; np += n
print(f"after lits: ip={ip} np={np}")

# first_literal_run:
t = u8(sub,ip); ip+=1
print(f"\nfirst_literal_run: t={t:#04x}={t} ip→{ip}")
# t=96 >= 16 → goto match

# Match dispatch: t=0x60=96 >= 64 → M2
print(f"\n=== match: t={t:#04x} ===")
if t >= 64:
    extra = u8(sub,ip); ip+=1
    m_pos = np-1 - ((t>>2)&7) - (extra<<3)
    t_copy = (t>>5)-1
    print(f"M2: extra={extra:#04x} m_pos={m_pos} t_copy={t_copy} (copy {t_copy+2} bytes)")
    # copy match (assume valid)
    np += t_copy + 2
    # match_done
    md_byte = u8(sub, ip-2)
    t = md_byte & 3
    print(f"match_done: src[ip-2={ip-2}]={md_byte:#04x} t={t}")
    if t == 0:
        print("→ break to outer loop")
    else:
        print(f"→ match_next: copy {t} lits")
        ip += t; np += t
        t = u8(sub,ip); ip+=1
        print(f"→ new t={t:#04x}={t}")

# outer loop iteration 2:
print(f"\n=== outer loop iter 2: ip={ip} np={np} ===")
t_outer2 = u8(sub,ip); ip+=1
print(f"t={t_outer2:#04x}={t_outer2}")

# trace next several ops
import lzokay
import struct

print(f"\n--- Now tracing with running np ---")
# Restart with actual execution
op = bytearray(131072)
ip = 0; np = 0

# byte 0 = 3, skip initial literal run
t = u8(sub,ip); ip+=1
# t=3 < 16 → literal run
n = t+3
op[np:np+n] = sub[ip:ip+n]; ip+=n; np+=n
print(f"[lits] np={np} ip={ip}")

# first_literal_run: t
t = u8(sub,ip); ip+=1
print(f"[flr] t={t:#04x} ip={ip}")
# t=96 >= 16 → goto match

count = 0
while count < 30:
    count += 1
    print(f"\n--- match dispatch #{count}: t={t:#04x}={t} ip={ip} np={np} ---")
    if t >= 64:  # M2
        extra = u8(sub,ip); ip+=1
        m_pos = np-1-((t>>2)&7)-(extra<<3)
        t_len = (t>>5)-1
        print(f"  M2: extra={extra:#04x} m_pos={m_pos} len={t_len+2}")
        assert 0<=m_pos< np, f"M2 OOB m_pos={m_pos} np={np}"
        for i in range(t_len+2): op[np]=op[m_pos+i]; np+=1
        md = u8(sub,ip-2)&3
        print(f"  match_done: t={md}")
        if md == 0:
            # back to outer loop
            t2 = u8(sub,ip); ip+=1
            print(f"  outer loop: t2={t2:#04x}")
            if t2 < 16:
                # literal run
                n2 = t2+3
                if t2==0:
                    while u8(sub,ip)==0: t2+=255; ip+=1
                    t2 += 15+u8(sub,ip); ip+=1
                    n2=t2+3
                op[np:np+n2]=sub[ip:ip+n2]; ip+=n2; np+=n2
                print(f"  [lits] np={np} ip={ip}")
                t = u8(sub,ip); ip+=1
                print(f"  [flr/cont] t={t:#04x}")
                if t >= 16:
                    pass  # go to match
                else:
                    # first_literal_run M1 or continue outer loop M1
                    dist = 1+M4_MAX_OFFSET+(t>>2)+(u8(sub,ip)<<2); ip+=1
                    m_pos2 = np - dist
                    print(f"  flr M1: dist={dist} m_pos={m_pos2} np={np}")
                    assert m_pos2 >= 0
                    op[np]=op[m_pos2]; np+=1
                    op[np]=op[m_pos2+1]; np+=1
                    op[np]=op[m_pos2+2]; np+=1
                    t = u8(sub,ip-1)&3
                    if t:
                        op[np:np+t]=sub[ip:ip+t]; ip+=t; np+=t
                    t = u8(sub,ip); ip+=1
            else:
                t = t2
        else:
            for i in range(md): op[np]=sub[ip]; np+=1; ip+=1
            t = u8(sub,ip); ip+=1
    elif t >= 32:  # M3
        t_len = t&31
        if t_len==0:
            while u8(sub,ip)==0: t_len+=255; ip+=1
            t_len += 31+u8(sub,ip); ip+=1
        m_pos = np-1-(u8(sub,ip)>>2)-(u8(sub,ip+1)<<6)
        ip+=2
        print(f"  M3: m_pos={m_pos} len={t_len+2}")
        assert 0<=m_pos<np, f"M3 OOB m_pos={m_pos} np={np}"
        for i in range(t_len+2): op[np]=op[m_pos+i]; np+=1
        t = u8(sub,ip-2)&3
        if t:
            for i in range(t): op[np]=sub[ip]; np+=1; ip+=1
        t2 = u8(sub,ip); ip+=1
        if t==0:
            # outer loop
            if t2 < 16:
                n2=t2+3; op[np:np+n2]=sub[ip:ip+n2]; ip+=n2; np+=n2
                t = u8(sub,ip); ip+=1
                if t>=16: pass
                else:
                    dist=1+M4_MAX_OFFSET+(t>>2)+(u8(sub,ip)<<2); ip+=1
                    m_pos2=np-dist
                    assert m_pos2>=0
                    op[np]=op[m_pos2]; np+=1; op[np]=op[m_pos2+1]; np+=1; op[np]=op[m_pos2+2]; np+=1
                    td=u8(sub,ip-1)&3
                    if td: op[np:np+td]=sub[ip:ip+td]; ip+=td; np+=td
                    t=u8(sub,ip); ip+=1
            else: t=t2
        else: t=t2
    elif t >= 16:  # M4
        m_pos = np - ((t&8)<<11)
        t_len = t&7
        if t_len==0:
            while u8(sub,ip)==0: t_len+=255; ip+=1
            t_len += 7+u8(sub,ip); ip+=1
        d = (u8(sub,ip)>>2)+(u8(sub,ip+1)<<6); ip+=2
        print(f"  M4: t_orig={t:#04x} (t&8)<<11={(t&8)<<11} t_len={t_len} dist_bytes={d} m_pos_before_dist={m_pos} ip_after={ip}")
        m_pos -= d
        print(f"  M4: m_pos_before_M4OFF={m_pos}")
        if m_pos == np:
            print("  EOF"); break
        m_pos -= M4_MAX_OFFSET
        print(f"  M4: final m_pos={m_pos} len={t_len+2}")
        if m_pos < 0:
            print(f"  *** M4 OOB! ***"); break
        for i in range(t_len+2): op[np]=op[m_pos+i]; np+=1
        t = u8(sub,ip-2)&3
        if t:
            for i in range(t): op[np]=sub[ip]; np+=1; ip+=1
        t2=u8(sub,ip); ip+=1
        if t==0:
            if t2<16:
                n2=t2+3; op[np:np+n2]=sub[ip:ip+n2]; ip+=n2; np+=n2
                t=u8(sub,ip); ip+=1
                if t<16:
                    dist=1+M4_MAX_OFFSET+(t>>2)+(u8(sub,ip)<<2); ip+=1
                    m_pos2=np-dist; assert m_pos2>=0
                    op[np]=op[m_pos2]; np+=1; op[np]=op[m_pos2+1]; np+=1; op[np]=op[m_pos2+2]; np+=1
                    td=u8(sub,ip-1)&3
                    if td: op[np:np+td]=sub[ip:ip+td]; ip+=td; np+=td
                    t=u8(sub,ip); ip+=1
            else: t=t2
        else: t=t2
    else:  # M1
        extra=u8(sub,ip); ip+=1
        m_pos = np-1-(t>>2)-(extra<<2)
        print(f"  M1: t={t} extra={extra:#04x} m_pos={m_pos}")
        if m_pos < 0: print("  M1 OOB!"); break
        op[np]=op[m_pos]; np+=1; op[np]=op[m_pos+1]; np+=1
        t=u8(sub,ip-2)&3
        if t:
            for i in range(t): op[np]=sub[ip]; np+=1; ip+=1
        t2=u8(sub,ip); ip+=1
        if t==0:
            if t2<16:
                n2=t2+3; op[np:np+n2]=sub[ip:ip+n2]; ip+=n2; np+=n2
                t=u8(sub,ip); ip+=1
                if t<16:
                    dist=1+M4_MAX_OFFSET+(t>>2)+(u8(sub,ip)<<2); ip+=1
                    m_pos2=np-dist; assert m_pos2>=0
                    op[np]=op[m_pos2]; np+=1; op[np]=op[m_pos2+1]; np+=1; op[np]=op[m_pos2+2]; np+=1
                    td=u8(sub,ip-1)&3
                    if td: op[np:np+td]=sub[ip:ip+td]; ip+=td; np+=td
                    t=u8(sub,ip); ip+=1
            else: t=t2
        else: t=t2

# compare
expected = lzokay.decompress(bytes(sub), 131072)
print(f"\nResult np={np}")
print(f"First 16: {' '.join(f'{b:02X}' for b in bytes(op[:16]))}")
print(f"Expected: {' '.join(f'{b:02X}' for b in expected[:16])}")
