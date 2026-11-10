"""HDTV Mate live TV tool (ATSC 1.0 + ATSC 3.0). Our own code; needs the owner's captured firmware/setup files.
  sudo venv/bin/python tv.py scan                       find channels, save channels.json
  sudo venv/bin/python tv.py list                       show saved channels
  sudo venv/bin/python tv.py watch 9.1 [-o out|-] [-t secs]
      ATSC 1.0 -> MPEG-TS;  ATSC 3.0 -> fragmented MP4 (HEVC + AC-4, all audio languages)
      e.g. ... watch 7.1 -o - | mpv -"""
import argparse, json, os, sys, time
import hdtvmate, alp, route, fmp4

HERE = os.path.dirname(os.path.abspath(__file__))
CHFILE = os.environ.get("HDTVMATE_CHANNELS", os.path.join(HERE, "..", "private", "channels.json"))
UNIT = 192


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# ---------------- 192-byte unit splitter (USB reads are not unit aligned) ----------------
class Units:
    def __init__(self):
        self.buf = b""

    def feed(self, chunk):
        b = self.buf + chunk
        i, out = 0, []
        while i + UNIT <= len(b):
            if b[i + 4] == 0x47 and (i + UNIT + 4 >= len(b) or b[i + UNIT + 4] == 0x47):
                out.append(b[i + 4:i + UNIT]); i += UNIT
            else:
                i += 1
        self.buf = b[i:]
        return out


# ---------------- ATSC 1.0 PSIP: terrestrial virtual channel table ----------------
class Sections:
    """Collect PSI sections on one PID."""
    def __init__(self, pid):
        self.pid, self.buf, self.out = pid, None, []

    def feed(self, p):
        if (p[1] & 0x1F) << 8 | p[2] != self.pid or not p[3] & 0x10:
            return
        o = 4 + (1 + p[4] if p[3] & 0x20 else 0)
        if p[3] & 0x20:
            o = 5 + p[4]
        if p[1] & 0x40:
            ptr = p[o]
            if self.buf:
                self._done(self.buf + p[o + 1:o + 1 + ptr])
            self.buf = p[o + 1 + ptr:]
        elif self.buf is not None:
            self.buf += p[o:]
        if self.buf and len(self.buf) >= 3:
            n = 3 + ((self.buf[1] & 0x0F) << 8 | self.buf[2])
            if len(self.buf) >= n:
                self._done(self.buf); self.buf = None

    def _done(self, b):
        if len(b) >= 3:
            n = 3 + ((b[1] & 0x0F) << 8 | b[2])
            if len(b) >= n:
                self.out.append(b[:n])


def tvct_channels(sec):
    if sec[0] not in (0xC8, 0xC9):
        return []
    n, o, out = sec[9], 10, []
    for _ in range(n):
        if o + 32 > len(sec):
            break
        name = sec[o:o + 14].decode("utf-16-be", "replace").rstrip("\x00 ")
        major = (sec[o + 14] & 0x0F) << 6 | sec[o + 15] >> 2
        minor = (sec[o + 15] & 0x03) << 8 | sec[o + 16]
        prog = sec[o + 24] << 8 | sec[o + 25]
        stype = sec[o + 27] & 0x3F
        dlen = (sec[o + 30] & 0x03) << 8 | sec[o + 31]
        out.append({"vch": f"{major}.{minor}", "name": name, "program": prog, "service_type": stype})
        o += 32 + dlen
    return out


def atsc1_names(m, secs=2.5):
    sec, units, end = Sections(0x1FFB), Units(), time.time() + secs
    for chunk in m.stream(lambda: time.time() > end):
        for p in units.feed(chunk):
            sec.feed(p)
        for s in sec.out:
            ch = tvct_channels(s)
            if ch:
                return ch
    return []


# ---------------- ATSC 3.0: SLT from LLS ----------------
def slt_services(xml):
    import re
    out = []
    for m in re.finditer(r"<Service\b(.*?)(?:/>|</Service>)", xml, re.S):
        a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(0)))
        if a.get("majorChannelNo"):
            out.append({"vch": f"{a['majorChannelNo']}.{a.get('minorChannelNo', '1')}",
                        "name": a.get("shortServiceName", ""), "service_id": int(a.get("serviceId", 0)),
                        "ip": a.get("slsDestinationIpAddress"), "port": int(a.get("slsDestinationUdpPort", 0))})
    return out


def ip_packets(m, stop):
    units, r = Units(), alp.ALPReassembler()
    for chunk in m.stream(stop):
        for p in units.feed(chunk):
            r.feed(p)
        out, r.out = r.out, []
        for t, ip in out:
            if t == 0 and alp.ip_ok(ip):
                yield ip


def atsc3_services(m, secs=5):
    import gzip
    end = time.time() + secs
    for ip in ip_packets(m, lambda: time.time() > end):
        u = alp.udp(ip)
        if u and u[:2] == ("224.0.23.60", 4937):
            for tid, body in alp.lls_tables(u[2]):
                if tid == 1:
                    try:
                        return slt_services(gzip.decompress(body).decode("utf-8", "replace"))
                    except Exception:
                        pass
    return []


# ---------------- commands ----------------
def scan(m):
    found = []
    for rf in range(2, 37):
        khz = hdtvmate.channel_khz(rf)
        if m.tune(khz):
            chans = atsc1_names(m)
            for c in chans:
                c.update(rf=rf, khz=khz, std="atsc1")
            found += chans or [{"vch": f"rf{rf}", "name": "?", "rf": rf, "khz": khz, "std": "atsc1"}]
            log(f"RF {rf:2d} ATSC 1.0: " + ", ".join(f"{c['vch']} {c['name']}" for c in chans))
        elif m.signal() > 0x60 and m.tune3(khz) >= 6:
            svcs = atsc3_services(m)
            for c in svcs:
                c.update(rf=rf, khz=khz, std="atsc3")
            found += svcs
            log(f"RF {rf:2d} ATSC 3.0: " + ", ".join(f"{c['vch']} {c['name']}" for c in svcs))
    json.dump(found, open(CHFILE, "w"), indent=1)
    log(f"{len(found)} channels saved to {CHFILE}")


def find(ch):
    chans = json.load(open(CHFILE))
    hits = [c for c in chans if c["vch"] == ch or c["name"].lower() == ch.lower()]
    # prefer ATSC 1.0 (decodable everywhere) unless the name says -NG / user asked for 3.0
    return sorted(hits, key=lambda c: c["std"] != "atsc1")


def watch_atsc1(m, c, out, stop):
    """Pass the whole multiplex through (players pick the program; PSIP/PAT stay intact)."""
    units = Units()
    for chunk in m.stream(stop):
        ps = units.feed(chunk)
        if ps:
            out.write(b"".join(ps)); out.flush()


def watch_atsc3(m, c, out, stop):
    rx, inits, kinds, pending, fr, started, sent = route.Receiver(), {}, {}, {}, None, False, set()
    for ip in ip_packets(m, stop):
        u = alp.udp(ip)
        if not u or u[0] != c["ip"]:
            continue
        for tsi, toi, data in rx.feed(u[1], u[2]):
            if tsi == 0:
                continue
            if toi == 0xFFFFFFFF:
                if tsi not in inits:
                    inits[tsi] = data
                    kinds[tsi] = next((b"vide" in data and "video") or (b"soun" in data and "audio") or "other"
                                      for _ in [0])
                continue
            pending.setdefault(toi, {})[tsi] = data
        if not started:
            tracks = sorted(t for t, k in kinds.items() if k in ("video", "audio"))
            if any(kinds[t] == "video" for t in tracks) and sum(kinds[t] == "audio" for t in tracks) >= 1 \
                    and len(pending) >= 2:
                tracks.sort(key=lambda t: kinds[t] != "video")
                order = {t: k + 1 for k, t in enumerate(tracks)}
                fr = fmp4.Fragmenter([fmp4.timescale(inits[t]) for t in tracks])
                out.write(fmp4.merge_init([inits[t] for t in tracks])); started = True
                log("tracks:", ", ".join(f"tsi {t} {kinds[t]}" for t in tracks))
        if started:
            for toi in sorted(pending):
                seg = pending[toi]
                complete = all(t in seg for t in order)
                if not sent and not complete:
                    if any(t > toi + 1 for t in pending):
                        del pending[toi]              # never start on a partial segment
                    continue
                if complete or any(t > toi + 1 for t in pending):
                    if fr.t0 is None:                 # common zero = earliest track in first segment
                        fr.t0 = min(fmp4.first_time(seg[t], fr.scales[order[t] - 1]) for t in order)
                    if toi not in sent:
                        for t in order:
                            if t in seg:
                                out.write(fr.fragment(seg[t], order[t]))
                        out.flush(); sent.add(toi)
                    del pending[toi]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["scan", "list", "watch"])
    ap.add_argument("channel", nargs="?")
    ap.add_argument("-o", "--out", default="-")
    ap.add_argument("-t", "--secs", type=float, default=0)
    a = ap.parse_args()
    if a.cmd == "list":
        for c in json.load(open(CHFILE)):
            log(f"{c['vch']:>6} {c['name']:10} RF {c['rf']:2d} {'ATSC 3.0' if c['std'] == 'atsc3' else 'ATSC 1.0'}")
        return
    m = hdtvmate.HDTVMate()
    try:
        if a.cmd == "scan":
            return scan(m)
        hits = find(a.channel)
        if not hits:
            sys.exit(f"no channel {a.channel!r} in {CHFILE}; run scan first")
        c = hits[0]
        end = time.time() + a.secs if a.secs else None
        stop = (lambda: time.time() > end) if end else None
        out = sys.stdout.buffer if a.out == "-" else open(a.out, "wb")
        if c["std"] == "atsc1":
            if not m.tune(c["khz"]):
                sys.exit("no lock")
            log(f"watching {c['vch']} {c['name']} (RF {c['rf']}, ATSC 1.0) {m.status()}")
            watch_atsc1(m, c, out, stop)
        else:
            if m.tune3(c["khz"]) < 6:
                sys.exit("no ATSC 3.0 lock")
            log(f"watching {c['vch']} {c['name']} (RF {c['rf']}, ATSC 3.0) {m.status3()}")
            watch_atsc3(m, c, out, stop)
    except (KeyboardInterrupt, BrokenPipeError):
        pass
    finally:
        m.close()


if __name__ == "__main__":
    main()
