"""Read-only probe of the tuner's IT930x-style USB bridge: asks for the running firmware version."""
import struct, sys, usb.core, usb.util

def csum(b):
    s = 0
    for i in range(0, len(b) - 1, 2):
        s = (s + (b[i] << 8 | b[i + 1])) & 0xFFFF
    if len(b) % 2:
        s = (s + (b[-1] << 8)) & 0xFFFF
    return ~s & 0xFFFF

dev = usb.core.find(idVendor=0x23E2, idProduct=0x2B02)
if dev is None:
    sys.exit("device not found")
usb.util.claim_interface(dev, 0)
seq = 0

def cmd(code, payload=b""):
    global seq
    seq = (seq + 1) & 0xFF
    frame = bytes([len(payload) + 5, (code >> 8) & 0xFF, code & 0xFF, seq]) + payload
    frame += struct.pack(">H", csum(frame[1:]))
    dev.write(0x02, frame, 1000)
    r = bytes(dev.read(0x81, 256, 1000))
    ok = len(r) >= 5 and r[1] == seq and csum(r[1:-2]) == struct.unpack(">H", r[-2:])[0]
    return ok, (r[2] if len(r) > 2 else None), r[3:-2], r

for which in (1, 2):
    ok, st, pl, raw = cmd(0x22, bytes([which]))
    print(f"QUERYINFO({which}) checksum_ok={ok} status={st} payload={pl.hex()}")
usb.util.release_interface(dev, 0)
