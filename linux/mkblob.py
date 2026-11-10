"""Pack the owner's own captured sequences into /lib/firmware/hdtvmate.bin for the kernel driver.
Format: magic b"HDTVMAT1", then sections [u8 id][u32 LE nbytes] of records [u16 LE delay_ms][u16 LE cmd][u8 len][payload].
Sections: 1 = bridge firmware (scatter writes + boot), 2 = chip init (write-only, from init-core.txt), 3 = ATSC 1.0 tune (frequency patched by the driver).
Usage: python3 mkblob.py ~/hdtv-re out.bin"""
import os, struct, sys
d, out = sys.argv[1], sys.argv[2]

def rec(delay, cmd, pl):
    assert len(pl) < 256
    return struct.pack("<HHB", min(int(delay), 65535), cmd, len(pl)) + pl

fw = b"".join(rec(0, int(p[0], 16), bytes.fromhex(p[1]) if len(p) > 1 else b"")
              for p in (l.split() for l in open(os.path.join(d, "it9300-fw-replay.txt"))) if p)
init = b"".join(rec(float(p[0]), int(p[1], 16), bytes.fromhex(p[2]) if len(p) > 2 else b"")
                for p in (l.split() for l in open(os.path.join(d, "init-core.txt"))) if p)
tune = b"".join(rec(float(p[0]), int(p[1], 16), bytes.fromhex(p[2]) if len(p) > 2 else b"")
                for p in (l.split() for l in open(os.path.join(d, "tune-core.txt"))) if p)
blob = b"HDTVMAT1" + b"".join(struct.pack("<BI", i, len(s)) + s for i, s in ((1, fw), (2, init), (3, tune)))
open(out, "wb").write(blob)
print(f"{out}: {len(blob)} bytes (fw {len(fw)}, init {len(init)}, tune {len(tune)})")
