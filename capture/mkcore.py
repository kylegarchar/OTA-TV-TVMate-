"""Extract a write-only command sequence (with ms delays) from a raw capture window.
Usage: python3 mkcore.py capture.log t_start t_end out.txt"""
import sys
log, t0, t1, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
rows = []
for line in open(log):
    p = line.split()
    if len(p) >= 4 and p[1] == "OUT" and p[2] == "ep2":
        t, d = float(p[0]), bytes.fromhex(p[-1])
        if t0 <= t < t1:
            rows.append((t, d[1] << 8 | d[2], d[4:-2]))
keep, i = [], 0
while i < len(rows):
    t, c, pl = rows[i]
    if c == 1 and pl[4:6] == b"\xf4\x24" and pl[6] == 1 and i + 3 < len(rows) and rows[i + 3][1] == 0x2A:
        i += 4; continue                       # read quartet
    if c in (0x2A, 0x00):
        i += 1; continue
    keep.append((t, c, pl)); i += 1
prev = keep[0][0]
with open(out, "w") as f:
    for t, c, pl in keep:
        gap, prev = t - prev, t
        f.write(f"{gap * 1000 if gap > 0.004 else 0:.0f} {c:04x} {pl.hex()}\n")
print(len(rows), "->", len(keep))
