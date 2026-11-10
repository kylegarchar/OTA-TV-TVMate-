"""Minimal user-space IT930x bridge driver (our own code). Protocol per px4_drv's it930x.c.
Firmware is not included: it is replayed from it9300-fw-replay.txt, captured from the user's own HDTVPlayer."""
import struct, usb.core, usb.util

CMD_REG_READ, CMD_REG_WRITE, CMD_QUERYINFO, CMD_BOOT = 0x00, 0x01, 0x22, 0x23
CMD_FW_SCATTER_WRITE, CMD_I2C_READ, CMD_I2C_WRITE = 0x29, 0x2A, 0x2B

class BridgeError(Exception):
    pass

def _csum(b):
    s = 0
    for i in range(0, len(b) - 1, 2):
        s = (s + (b[i] << 8 | b[i + 1])) & 0xFFFF
    if len(b) % 2:
        s = (s + (b[-1] << 8)) & 0xFFFF
    return ~s & 0xFFFF

class IT930x:
    def __init__(self, vid=0x23E2, pid=0x2B02):
        self.dev = usb.core.find(idVendor=vid, idProduct=pid)
        if self.dev is None:
            raise BridgeError("tuner not found")
        usb.util.claim_interface(self.dev, 0)
        self.seq = 0

    def close(self):
        usb.util.release_interface(self.dev, 0)

    def cmd(self, code, payload=b"", timeout=1000):
        self.seq = (self.seq + 1) & 0xFF
        frame = bytes([len(payload) + 5, code >> 8 & 0xFF, code & 0xFF, self.seq]) + payload
        frame += struct.pack(">H", _csum(frame[1:]))
        self.dev.write(0x02, frame, timeout)
        r = bytes(self.dev.read(0x81, 256, timeout))
        if len(r) < 5 or r[1] != self.seq or _csum(r[1:-2]) != struct.unpack(">H", r[-2:])[0]:
            raise BridgeError(f"bad reply to cmd {code:#x}: {r.hex()}")
        if r[2]:
            raise BridgeError(f"cmd {code:#x} failed, status {r[2]}")
        return r[3:-2]

    @staticmethod
    def _addr(addr):
        width = 4 if addr >> 24 else 3 if addr >> 16 else 2 if addr >> 8 else 1
        return bytes([width]) + struct.pack(">I", addr)

    def read_regs(self, addr, n=1):
        return self.cmd(CMD_REG_READ, bytes([n]) + self._addr(addr))

    def write_regs(self, addr, data):
        return self.cmd(CMD_REG_WRITE, bytes([len(data)]) + self._addr(addr) + bytes(data))

    def firmware_version(self, which=1):
        return tuple(self.cmd(CMD_QUERYINFO, bytes([which]))[:4])

    def chip_id(self):
        return int.from_bytes(self.read_regs(0x1223, 2), "big")

    def load_firmware(self, replay_path):
        """Send the captured firmware scatter writes and the boot command, in the captured order."""
        n = 0
        for line in open(replay_path):
            parts = line.split()
            if not parts:
                continue
            self.cmd(int(parts[0], 16), bytes.fromhex(parts[1]) if len(parts) > 1 else b"", timeout=3000)
            n += 1
        return n
