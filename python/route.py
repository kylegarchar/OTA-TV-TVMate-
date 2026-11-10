"""Minimal ATSC 3.0 ROUTE/ALC receiver (A/331, RFC 5651/5775) over the IP packets from alp.py.
Reassembles delivery objects per (dest, port, TSI, TOI) and writes the complete ones to a folder.
Usage: python3 route.py in.raw 239.255.9.1 outdir"""
import os, struct, sys
import alp


def lct(d):
    """Return (tsi, toi, close_object, tol, offset, payload) or None."""
    if len(d) < 12 or d[0] >> 4 != 1:
        return None
    c, s, o, h = d[0] >> 2 & 3, d[1] >> 7, d[1] >> 5 & 3, d[1] >> 4 & 1
    b_flag = d[1] & 1
    hlen = d[2] * 4
    p = 4 + 4 * (c + 1)
    tl, ol = 4 * s + 2 * h, 4 * o + 2 * h
    tsi = int.from_bytes(d[p:p + tl], "big"); p += tl
    toi = int.from_bytes(d[p:p + ol], "big"); p += ol
    tol = None
    while p < hlen:                                   # header extensions
        het = d[p]
        if het >= 128:
            if het == 194:                            # EXT_TOL, 24-bit
                tol = int.from_bytes(d[p + 1:p + 4], "big")
            p += 4
        else:
            hel = d[p + 1] * 4
            if het == 67:                             # EXT_TOL, 48-bit
                tol = int.from_bytes(d[p + 2:p + 8], "big")
            elif het == 64 and hel >= 16:             # EXT_FTI: transfer length (48 bits)
                tol = int.from_bytes(d[p + 2:p + 8], "big")
            p += max(hel, 4)
    off = int.from_bytes(d[hlen:hlen + 4], "big")     # Compact No-Code FEC payload id = start offset
    return tsi, toi, b_flag, tol, off, d[hlen + 4:]


class Receiver:
    """Incremental ROUTE object reassembly. feed(port, udp_payload) -> list of (tsi, toi, data) completed."""
    def __init__(self, keep=64):
        self.objs, self.keep, self.done = {}, keep, set()

    def feed(self, port, d):
        x = lct(d)
        if not x:
            return []
        tsi, toi, close, tol, off, pl = x
        key = (port, tsi, toi)
        if key in self.done and toi != 0xFFFFFFFF and tsi != 0:
            return []
        o = self.objs.get(key)
        if o is None:
            o = self.objs[key] = {"chunks": {}, "tol": None, "close": False, "have": 0}
            if len(self.objs) > self.keep:                # forget the oldest unfinished object
                del self.objs[next(iter(self.objs))]
        if off not in o["chunks"]:
            o["chunks"][off] = pl; o["have"] += len(pl)
        o["tol"] = tol or o["tol"]
        o["close"] |= bool(close)
        if o["tol"] and o["have"] >= o["tol"]:
            data = assemble(o)
            if data:
                del self.objs[key]; self.done.add(key)
                if len(self.done) > 4096:
                    self.done.clear()
                return [(tsi, toi, data)]
        return []


def receive(raw, dst):
    r = alp.ALPReassembler()
    for p in alp.ts_packets(raw):
        r.feed(p)
    objs = {}
    for t, ip in r.out:
        if t != 0 or not alp.ip_ok(ip):
            continue
        u = alp.udp(ip)
        if not u or u[0] != dst:
            continue
        x = lct(u[2])
        if not x:
            continue
        tsi, toi, close, tol, off, pl = x
        o = objs.setdefault((u[1], tsi, toi), {"chunks": {}, "tol": None, "close": False})
        o["chunks"][off] = pl
        o["tol"] = tol or o["tol"]
        o["close"] |= bool(close)
    return objs


def assemble(o):
    data, pos = bytearray(), 0
    for off in sorted(o["chunks"]):
        if off > pos:
            return None                               # gap: missing packets
        data[off:off + len(o["chunks"][off])] = o["chunks"][off]
        pos = max(pos, off + len(o["chunks"][off]))
    if o["tol"] is not None and len(data) < o["tol"]:
        return None
    return bytes(data[:o["tol"]] if o["tol"] else data)


if __name__ == "__main__":
    raw, dst, outdir = open(sys.argv[1], "rb").read(), sys.argv[2], sys.argv[3]
    os.makedirs(outdir, exist_ok=True)
    objs = receive(raw, dst)
    done = 0
    for (port, tsi, toi), o in sorted(objs.items()):
        data = assemble(o)
        state = "ok" if data else "incomplete"
        print(f"port {port} tsi {tsi:4d} toi {toi:10d} tol {o['tol']} {state} {len(data) if data else ''}")
        if data:
            open(os.path.join(outdir, f"tsi{tsi}-toi{toi}"), "wb").write(data); done += 1
    print(done, "objects written")
