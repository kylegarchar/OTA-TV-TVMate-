"""Decode a HDTVPlayer USB capture (hook.js output) into IT930x bridge commands."""
import collections, sys
NAMES = {0x00: "REG_READ", 0x01: "REG_WRITE", 0x22: "QUERYINFO", 0x23: "BOOT", 0x29: "FW_SCATTER_WRITE", 0x2a: "I2C_READ", 0x2b: "I2C_WRITE"}
HEX = set("0123456789abcdef")
cmds, pending = [], {}
for line in open(sys.argv[1]):
    p = line.split()
    if len(p) < 4 or p[1] not in ("OUT", "IN") or not set(p[-1]) <= HEX:
        continue
    t, data = float(p[0]), bytes.fromhex(p[-1])
    if p[1] == "OUT" and p[2] == "ep2" and len(data) >= 6:
        pending[data[3]] = len(cmds)
        cmds.append({"t": t, "code": data[1] << 8 | data[2], "pl": data[4:-2], "reply": None})
    elif p[1] == "IN" and p[2] == "ep81" and len(data) >= 5:
        i = pending.pop(data[1], None)
        if i is not None:
            cmds[i]["reply"] = (data[2], data[3:-2])
print(f"{len(cmds)} commands over {cmds[-1]['t'] - cmds[0]['t']:.0f} s, {sum(c['reply'] is not None for c in cmds)} answered, "
      f"{sum(1 for c in cmds if c['reply'] and c['reply'][0])} with error status")
print("by command:", {NAMES.get(k, hex(k)): v for k, v in collections.Counter(c["code"] for c in cmds).most_common()})
i2c = collections.Counter((NAMES[c["code"]], c["pl"][1], c["pl"][2]) for c in cmds if c["code"] in (0x2a, 0x2b) and len(c["pl"]) >= 3)
print("I2C targets (cmd, payload[1], payload[2]):", [(a, b, hex(d), n) for (a, b, d), n in i2c.most_common(10)])
for c in [c for c in cmds if c["code"] == 0x22][:3]:
    print("QUERYINFO", c["pl"].hex(), "->", c["reply"])
fw = [c for c in cmds if c["code"] in (0x29, 0x23)]
print("firmware load commands:", len(fw), "payload bytes:", sum(len(c["pl"]) for c in fw), "first at", fw[0]["t"] if fw else None)
regs = collections.Counter((NAMES[c["code"]], c["pl"][2:6].hex()) for c in cmds if c["code"] in (0, 1) and len(c["pl"]) >= 6)
print("busiest bridge registers:", regs.most_common(10))
print("first 12 commands:")
for c in cmds[:12]:
    print(f"  {c['t']:8.3f} {NAMES.get(c['code'], hex(c['code'])):16} {c['pl'][:24].hex()}  -> {c['reply'][0] if c['reply'] else '-'} {c['reply'][1][:16].hex() if c['reply'] else ''}")
