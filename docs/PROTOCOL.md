# HDTV Mate (23e2:2b02) — protocol notes

Findings from observing the device; no vendor code or data is reproduced here.

## Hardware

- **ITE IT9306** USB 2.0 bridge (chip ID regs `0x1223/0x1224` = `06 93`), behind a built-in
  Genesys hub that also carries an SD card reader (`05e3:0749`, plain USB mass storage).
- **Sony CXD6801** ATSC 1.0/3.0 demodulator: I²C `0xd8` (main) and `0xdc` (its I²C gate for the
  tuner). The 8VSB core answers at `0x30`; a further chip at `0x98` must be programmed during tune.
- **Sony tuner** at I²C `0xc0` (reached through the demod's gate: `0xdc` reg `0x08` = 1/0).

## Bridge command protocol (IT930x family)

Endpoints: command OUT `0x02`, reply IN `0x81`, transport stream IN `0x84`.

    request: [len = payload+5][cmd hi][cmd lo][seq][payload ...][csum hi][csum lo]
    reply:   [len][seq][status][payload ...][csum hi][csum lo]

Checksum: bitwise NOT of the **plain 16-bit wraparound sum** of big-endian words over the bytes
after the length byte (an odd trailing byte is the high byte of a last word). Not a
ones'-complement carry-folded sum — that variant gets status `0xFF` on some frames.

| cmd | name | payload |
|---|---|---|
| `0x0000` | REG_READ | `[n][addr width][addr: 4 bytes BE]` |
| `0x0001` | REG_WRITE | `[n][addr width][addr: 4 bytes BE][data...]` |
| `0x0022` | QUERYINFO | `[which]` → firmware version (all zero = no firmware loaded) |
| `0x0023` | BOOT | |
| `0x0029` | FW_SCATTER_WRITE | |
| `0x002a` | I2C_READ | `[n][bus = 3][8-bit addr]` |
| `0x002b` | I2C_WRITE | `[n][bus = 3][8-bit addr][data...]` |

An I²C register read is: bridge reg `0xf424` = 1, I2C_WRITE `[reg]`, `0xf424` = 0, I2C_READ.
Banked chips (`0xd8`, `0x30`, `0x98`) select a bank by writing `[0x00, bank]` first.

Bridge firmware survives USB resets and reboots as long as the port stays powered. A chip reset is
done through bridge GPIO registers `0xd8b7`–`0xd8b9`; the setup sequence starts with it, so it is
safe to rerun on a warm device.

## Tuning (ATSC 1.0)

- Frequency: 3 bytes little-endian in **kHz**, at payload offset 12 of the I2C_WRITE to tuner
  `0xc0` register `0x68`. No band-specific bytes: VHF and UHF use the same sequence.
- Before tuning set bridge regs `0xd8e4`, `0xd8e5`, `0xd8e3` = 1 (TS input in 8VSB mode; ATSC 3.0
  uses `0xd8e3` = 0).
- The tune sequence takes ~0.4 s including its own delays; lock usually follows within ~0.3 s.
- **Purge** stale TS from the previous channel: bridge reg `0xda1d` = 1, drain EP `0x84`, `0xda1d` = 0.

## Status

| What | Where | Meaning |
|---|---|---|
| 8VSB lock | `0x30` bank `0x0f` reg `0x11` | 1 = locked |
| demod lock (alt.) | `0x30` bank `0x0d` reg `0x86` | 1 when locked |
| error counter | `0x30` bank `0x0d` reg `0x70`, 3 bytes LE | small when clean |
| RF level | tuner `0xc0`: write `0x87 c4 41`, `0x59 05 01`, read `0x5b` (gate open), then `0x59 04`, `0x88 00`, `0x87 c0` | ≈`0x33` none, `0x6c` medium, `0x90`+ strong |
| ATSC 3.0 sync | `0xd8` bank `0x90` reg `0x10` | counts 2→3→5→6; 6 = locked |

## Transport stream

EP `0x84` delivers **192-byte units**: a 4-byte bridge tag + one 188-byte TS packet. Read sizes
must be multiples of 512 bytes (otherwise libusb reports overflow). Keep several transfers queued:
single synchronous reads lose packets at the full 19.39 Mbit/s multiplex rate.

ATSC 3.0 (experimental, `python/alp.py`): the same 192-byte units carry ALP (A/330) in TS-like
packets on PID `0x2d`: on payload-unit-start `p[3]` is a pointer to the first ALP packet and the
payload starts at `p[4]`, otherwise at `p[3]`. ALP carries IPv4 → ROUTE/DASH (`python/route.py`).
