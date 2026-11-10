"""Merge per-track DASH fMP4 (ATSC 3.0 ROUTE delivers video/audio as separate tracks, each track_ID 1)
into one fragmented MP4 stream. Pure box editing, no re-encode, so AC-4 audio passes through untouched."""
import struct


def boxes(b, o=0, end=None):
    end = len(b) if end is None else end
    while o + 8 <= end:
        n, t = struct.unpack(">I4s", b[o:o + 8])
        if n < 8 or o + n > end:
            return
        yield t.decode("latin1"), b[o:o + n]
        o += n


def box(t, payload):
    return struct.pack(">I4s", 8 + len(payload), t.encode()) + payload


def _child(b, name):
    return next((x for t, x in boxes(b, 8) if t == name), None)


def _set_tkhd_id(tkhd, tid):
    v = tkhd[8]
    off = 8 + 4 + (16 if v == 1 else 8)
    return tkhd[:off] + struct.pack(">I", tid) + tkhd[off + 4:]


def _retrack(container, tid, kinds):
    """Rebuild a container box, rewriting track ids in the named child boxes."""
    out = b""
    for t, x in boxes(container, 8):
        if t == "tkhd" and "tkhd" in kinds:
            x = _set_tkhd_id(x, tid)
        elif t in ("tfhd", "trex"):
            x = x[:12] + struct.pack(">I", tid) + x[16:]
        elif t in ("trak", "traf"):
            x = _retrack(x, tid, kinds)
        out += x
    return box(container[4:8].decode(), out)


def timescale(init):
    moov = next(x for t, x in boxes(init) if t == "moov")
    mdhd = _child(_child(_child(moov, "trak"), "mdia"), "mdhd")
    return struct.unpack(">I", mdhd[28:32] if mdhd[8] == 1 else mdhd[20:24])[0]


def merge_init(inits):
    """inits: list of init segments (ftyp+moov), one per track, in output track order."""
    ftyp = next(x for t, x in boxes(inits[0]) if t == "ftyp")
    moovs = [next(x for t, x in boxes(i) if t == "moov") for i in inits]
    mvhd = _child(moovs[0], "mvhd")
    v = mvhd[8]
    nt_off = len(mvhd) - 4                              # next_track_ID is the last field
    mvhd = mvhd[:nt_off] + struct.pack(">I", len(inits) + 1)
    traks, trexs = b"", b""
    for k, m in enumerate(moovs, 1):
        traks += _retrack(_child(m, "trak"), k, {"tkhd"})
        trexs += _retrack(_child(m, "mvex"), k, set())[8:]
    return ftyp + box("moov", mvhd + traks + box("mvex", trexs))


def first_time(seg, scale):
    """Decode time (seconds) of the first fragment in a media segment."""
    for t, x in boxes(seg):
        if t == "moof":
            for ct, cx in boxes(x, 8):
                if ct == "traf":
                    for tt, tx in boxes(cx, 8):
                        if tt == "tfdt":
                            v = tx[8]
                            return struct.unpack(">Q" if v == 1 else ">I", tx[12:20] if v == 1 else tx[12:16])[0] / scale
    return None


class Fragmenter:
    """scales: per-track timescale (1-based list order). The first fragment seen fixes a common start
    time; every track's decode times are shifted by that same number of seconds so A/V sync is kept."""
    def __init__(self, scales=None):
        self.seq, self.scales, self.t0 = 0, scales, None

    def fragment(self, seg, tid):
        """Media segment -> moof+mdat pairs with track id tid and fresh sequence numbers."""
        out = b""
        for t, x in boxes(seg):
            if t == "moof":
                self.seq += 1
                inner = b""
                for ct, cx in boxes(x, 8):
                    if ct == "mfhd":
                        cx = cx[:12] + struct.pack(">I", self.seq)
                    elif ct == "traf":
                        cx = self._rebase(_retrack(cx, tid, set()), tid)
                    inner += cx
                out += box("moof", inner)
            elif t == "mdat":
                out += x
        return out

    def _rebase(self, traf, tid):
        if not self.scales:
            return traf
        out = b""
        for t, x in boxes(traf, 8):
            if t == "tfdt":
                v = x[8]
                fmt = ">Q" if v == 1 else ">I"
                n = 8 if v == 1 else 4
                dt = struct.unpack(fmt, x[12:12 + n])[0]
                sc = self.scales[tid - 1]
                if self.t0 is None:
                    self.t0 = dt / sc
                dt = max(0, dt - round(self.t0 * sc))
                x = x[:12] + struct.pack(">Q", dt) if v == 1 else x[:12] + struct.pack(">I", dt & 0xFFFFFFFF)
            out += x
        return box("traf", out)


if __name__ == "__main__":
    import glob, sys
    d, out = sys.argv[1], sys.argv[2]
    tsis = [int(a) for a in sys.argv[3:]] or [10, 20]
    inits = [open(f"{d}/tsi{t}-toi4294967295", "rb").read() for t in tsis]
    fr = Fragmenter([timescale(i) for i in inits])
    with open(out, "wb") as f:
        f.write(merge_init(inits))
        tois = sorted({int(p.split("toi")[1]) for p in glob.glob(f"{d}/tsi{tsis[0]}-toi*")} - {0xFFFFFFFF})
        for toi in tois:
            for k, t in enumerate(tsis, 1):
                try:
                    f.write(fr.fragment(open(f"{d}/tsi{t}-toi{toi}", "rb").read(), k))
                except FileNotFoundError:
                    pass
