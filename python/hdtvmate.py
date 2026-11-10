"""User-space driver core for the HDTV Mate (IT9306 bridge + Sony CXD6801 demod), ATSC 1.0.
Our own code. Vendor firmware/setup data are NOT included: they are read from the owner's own
captures (it9300-fw-replay.txt, init-template.txt, tune-core.txt)."""
import os, time, usb.core
from it930x import IT930x, BridgeError

HERE = os.path.dirname(os.path.abspath(__file__))
# Capture-derived files (firmware replay + chip sequences) are private and not in git:
# put them in <repo>/private/ or point HDTVMATE_DATA at them.
DATA = os.environ.get("HDTVMATE_DATA", os.path.join(HERE, "..", "private"))
DEMOD, GATE, TUNER, ATSC1 = 0xD8, 0xDC, 0xC0, 0x30
TS_EP, PKT = 0x84, 192            # 4-byte tag + 188-byte TS packet


def _load(path, timed):
    out = []
    for line in open(os.path.join(DATA, path)):
        p = line.split()
        if not p:
            continue
        if timed:
            p = p[1:]
        out.append((int(p[0], 16), bytes.fromhex(p[1]) if len(p) > 1 else b""))
    return out


class HDTVMate:
    def __init__(self):
        self.b = IT930x()
        if self.b.firmware_version(1) == (0, 0, 0, 0):
            self.b.load_firmware(os.path.join(DATA, "it9300-fw-replay.txt"))
            time.sleep(0.05)
            self.init_chips()
        elif not self.demod_alive():
            self.init_chips()                 # bridge kept firmware but the demod was reset
        self.tune_seq = self._seq("tune-core.txt")
        self.tune3_seq = self._seq("atsc3-core.txt")
        self.mode = None
        self._buf = b""

    def init_chips(self):
        """Chip setup: GPIO reset of the demod, then the vendor's register writes (init-core.txt)."""
        for delay, code, pl in self._seq("init-core.txt"):
            if delay:
                time.sleep(delay)
            try:
                self.b.cmd(code, pl, timeout=3000)
            except BridgeError:
                pass

    def demod_alive(self):
        try:
            self.i2c_write(DEMOD, [0x00, 0x00])
            return True
        except BridgeError:
            return False

    def close(self):
        self.b.close()

    # ---- I2C helpers (bus 3; reads are wrapped by bridge reg 0xf424 = 1 / 0) ----
    def i2c_write(self, addr, data):
        self.b.cmd(0x2B, bytes([len(data), 3, addr]) + bytes(data))

    def i2c_read(self, addr, reg, n=1):
        self.b.write_regs(0xF424, [1])
        self.i2c_write(addr, [reg])
        self.b.write_regs(0xF424, [0])
        return self.b.cmd(0x2A, bytes([n, 3, addr]))

    def bank_read(self, addr, bank, reg, n=1):
        self.i2c_write(addr, [0x00, bank])
        return self.i2c_read(addr, reg, n)

    @staticmethod
    def _seq(name):
        return [(float(p[0]) / 1000, int(p[1], 16), bytes.fromhex(p[2]) if len(p) > 2 else b"")
                for p in (l.split() for l in open(os.path.join(DATA, name))) if p]

    def _run(self, seq, khz):
        f = khz.to_bytes(3, "little")
        for delay, code, pl in seq:
            if delay:
                time.sleep(delay)
            if code == 0x2B and len(pl) > 14 and pl[2] == TUNER and pl[3] == 0x68:
                pl = pl[:12] + f + pl[15:]
            try:
                self.b.cmd(code, pl, timeout=3000)
            except BridgeError:
                pass        # the app's own sequence has an expected failure or two

    # ---- tuning ----
    def tune(self, khz, wait=1.5):
        """ATSC 1.0 (8VSB) tune; returns True when locked."""
        if self.mode != 1:
            for r in (0xD8E4, 0xD8E5, 0xD8E3):
                self.b.write_regs(r, [1])     # bridge TS input back to ATSC 1.0 mode
        self.mode = 1
        self._run(self.tune_seq, khz)
        end = time.time() + wait
        while time.time() < end:
            if self.locked():
                break
            time.sleep(0.03)
        self.purge()
        return self.locked()

    def tune3(self, khz, wait=3.0):
        """ATSC 3.0 tune; returns the demod sync state (6 = locked)."""
        self.mode = 3
        self._run(self.tune3_seq, khz)
        end, st = time.time() + wait, 0
        while time.time() < end:
            st = self.sync3()
            if st >= 6:
                break
            time.sleep(0.02)
        self.purge()
        return st

    def sync3(self):
        return self.bank_read(DEMOD, 0x90, 0x10)[0]

    def status3(self):
        return {"sync": self.sync3(), "plp": self.bank_read(DEMOD, 0x95, 0x40)[0],
                "snr_raw": self.bank_read(DEMOD, 0x90, 0x28, 3).hex()}

    def purge(self, secs=0.0):
        """Drop TS already queued from the previous channel."""
        self.b.write_regs(0xDA1D, [1])
        self._drain(0.05 + secs)
        self._buf = b""
        self.b.write_regs(0xDA1D, [0])

    def _drain(self, secs):
        end = time.time() + secs
        while time.time() < end:
            try:
                self.b.dev.read(TS_EP, 512 * 64, 50)
            except usb.core.USBTimeoutError:
                pass

    # ---- status ----
    def locked(self):
        return self.bank_read(ATSC1, 0x0F, 0x11)[0] == 1

    def signal(self):
        """Raw tuner RF level (about 0x33 = nothing, 0x6c medium, 0x90+ strong)."""
        self.i2c_write(GATE, [0x08, 1])
        try:
            self.i2c_write(TUNER, [0x87, 0xC4, 0x41])
            self.i2c_write(TUNER, [0x59, 0x05, 0x01])
            v = self.i2c_read(TUNER, 0x5B)[0]
            self.i2c_write(TUNER, [0x59, 0x04])
            self.i2c_write(TUNER, [0x88, 0x00])
            self.i2c_write(TUNER, [0x87, 0xC0])
        finally:
            self.i2c_write(GATE, [0x08, 0])
        return v

    def errors(self):
        return int.from_bytes(self.bank_read(ATSC1, 0x0D, 0x70, 3), "little")

    def status(self):
        return {"locked": self.locked(), "signal": self.signal(), "errors": self.errors()}

    # ---- raw stream with a background reader (keeps up with ~26 Mbit/s ATSC 3.0) ----
    def stream(self, stop=None):
        """Yield raw 192-byte-unit chunks from a reader thread until stop() is true."""
        import queue, threading
        q, done = queue.Queue(maxsize=256), threading.Event()
        def reader():
            while not done.is_set():
                try:
                    q.put(bytes(self.b.dev.read(TS_EP, 1 << 20, 500)))
                except usb.core.USBTimeoutError:
                    pass
                except Exception as e:
                    q.put(e); return
        th = threading.Thread(target=reader, daemon=True); th.start()
        try:
            while not (stop and stop()):
                try:
                    c = q.get(timeout=1)
                except queue.Empty:
                    continue
                if isinstance(c, Exception):
                    raise c
                yield c
        finally:
            done.set(); th.join(2)

    # ---- transport stream ----
    def read_ts(self, timeout_ms=1000):
        """Return whole 188-byte TS packets (tags stripped); b'' on timeout."""
        try:
            self._buf += bytes(self.b.dev.read(TS_EP, 512 * 96, timeout_ms))
        except usb.core.USBTimeoutError:
            return b""
        c, out, i = self._buf, [], 0
        while i + PKT <= len(c):
            if c[i + 4] == 0x47 and (i + 2 * PKT > len(c) or c[i + PKT + 4] == 0x47):
                out.append(c[i + 4:i + PKT]); i += PKT
            else:
                i += 1                        # resync
        self._buf = c[i:]
        return b"".join(out)


def channel_khz(ch):
    """US broadcast RF channel number -> centre frequency in kHz."""
    if 2 <= ch <= 4: return 57000 + 6000 * (ch - 2)
    if 5 <= ch <= 6: return 79000 + 6000 * (ch - 5)
    if 7 <= ch <= 13: return 177000 + 6000 * (ch - 7)
    if 14 <= ch <= 36: return 473000 + 6000 * (ch - 14)
    raise ValueError(f"no US TV channel {ch}")


def scan(m, channels=range(2, 37)):
    """Yield (channel, kHz, status, tsid) for each channel that locks."""
    for ch in channels:
        khz = channel_khz(ch)
        if m.tune(khz):
            ts = b"".join(m.read_ts(500) for _ in range(12))
            yield ch, khz, m.status(), tsid(ts)


def tsid(ts):
    for i in range(0, len(ts) - 187, 188):
        p = ts[i:i + 188]
        if p[0] == 0x47 and (p[1] & 0x1F) << 8 | p[2] == 0 and p[1] & 0x40 and p[3] & 0x10:
            s = p[5 + p[4]:]
            if s[0] == 0:
                return s[3] << 8 | s[4]
    return None


if __name__ == "__main__":
    import sys
    m = HDTVMate()
    if sys.argv[1:] == ["scan"]:
        for ch, khz, st, t in scan(m):
            print(f"ch {ch:2d} {khz / 1000:5.0f} MHz  tsid={t}  signal={st['signal']}", flush=True)
        sys.exit(m.close())
    for khz in map(int, sys.argv[1:]):
        t0 = time.time(); lk = m.tune(khz); dt = time.time() - t0
        first = m.read_ts(); more = first + b"".join(m.read_ts() for _ in range(8))
        print(f"{khz:7d} locked={lk!s:5} in {dt:.2f}s  first-tsid={tsid(first)} tsid={tsid(more)} {m.status()}", flush=True)
    m.close()
