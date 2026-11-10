"""Split a decoded HDTVPlayer capture into bursts of activity separated by quiet gaps."""
import sys
exec(open(sys.argv[0].replace("phases.py", "decode_lib.py")).read()) if False else None
HEX = set("0123456789abcdef")
cmds, pending = [], {}
for line in open(sys.argv[1]):
    p = line.split()
    if len(p) < 4 or p[1] not in ("OUT", "IN") or not set(p[-1]) <= HEX:
        continue
    t, d = float(p[0]), bytes.fromhex(p[-1])
    if p[1] == "OUT" and p[2] == "ep2" and len(d) >= 6:
        pending[d[3]] = len(cmds); cmds.append((t, d[1] << 8 | d[2], d[4:-2]))
GAP = float(sys.argv[2]) if len(sys.argv) > 2 else 0.4
bursts, cur = [], [cmds[0]]
for c in cmds[1:]:
    if c[0] - cur[-1][0] > GAP:
        bursts.append(cur); cur = []
    cur.append(c)
bursts.append(cur)
def who(b):
    tally = {}
    for t, code, pl in b:
        if code in (0x2a, 0x2b) and len(pl) >= 3:
            tally[hex(pl[2])] = tally.get(hex(pl[2]), 0) + 1
    return dict(sorted(tally.items(), key=lambda x: -x[1])[:4])
print(f"{len(bursts)} bursts (gap > {GAP}s)")
for i, b in enumerate(bursts):
    if len(b) >= 40 or i < 3:
        print(f"#{i:3d} {b[0][0]:8.2f}-{b[-1][0]:8.2f}s {len(b):5d} cmds  {who(b)}")
