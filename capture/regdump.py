"""Register-level dump of a capture window: I2C writes/reads (with bank + reply) and bridge regs.
Usage: python3 regdump.py capture.log t_start t_end"""
import sys
log, t0, t1 = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
cmds, pending = [], {}
for line in open(log):
    p = line.split()
    if len(p) < 4 or p[1] not in ("OUT", "IN"):
        continue
    try:
        t, d = float(p[0]), bytes.fromhex(p[-1])
    except ValueError:
        continue
    if p[1] == "OUT" and p[2] == "ep2" and len(d) >= 6:
        pending[d[3]] = len(cmds); cmds.append([t, d[1] << 8 | d[2], d[4:-2], None])
    elif p[1] == "IN" and p[2] == "ep81" and len(d) >= 5:
        i = pending.pop(d[1], None)
        if i is not None:
            cmds[i][3] = (d[2], d[3:-2])
bank, lastreg = {}, {}
for t, code, pl, rep in cmds:
    if not t0 <= t < t1:
        continue
    st = "" if rep is None or rep[0] == 0 else f" !ERR{rep[0]}"
    if code == 0x2B:
        a, data = pl[2], pl[3:]
        if len(data) == 2 and data[0] == 0 and a != 0xC0:
            bank[a] = data[1]; continue
        if len(data) == 1:
            lastreg[a] = data[0]; continue
        print(f"{t:8.3f} W {a:02x} b{bank.get(a, 0):02x} r{data[0]:02x} = {data[1:].hex()}{st}")
    elif code == 0x2A:
        print(f"{t:8.3f} R {pl[2]:02x} b{bank.get(pl[2], 0):02x} r{lastreg.get(pl[2], 0):02x} -> {rep[1].hex() if rep else '?'}{st}")
    elif code in (0, 1):
        w = pl[1]; addr = int.from_bytes(pl[2 + 4 - w:6], "big")
        if addr == 0xF424:
            continue
        print(f"{t:8.3f} {'REG_W' if code else 'REG_R'} {addr:04x} {pl[6:].hex() if code else ''} {'-> ' + rep[1].hex() if rep and not code else ''}{st}")
    else:
        print(f"{t:8.3f} CMD {code:04x} {pl.hex()} {st}")
