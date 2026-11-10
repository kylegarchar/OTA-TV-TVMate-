"""ATSC 3.0 output of the HDTV Mate: 192-byte units ('za3' tag + 188-byte TS-style packet carrying ALP),
-> ALP packets (A/330) -> IPv4 datagrams. Our own code.
Usage: python3 alp.py in.raw [out.pcap]   (prints a UDP flow summary and the LLS service list)"""
import gzip, re, struct, sys
from collections import Counter

UNIT, TAG = 192, b"za3"


def ts_packets(raw):
    i = raw.find(TAG + b"\x00\x47")
    while 0 <= i and i + UNIT <= len(raw):
        if raw[i:i + 3] == TAG and raw[i + 4] == 0x47:
            yield raw[i + 4:i + UNIT]
            i += UNIT
        else:
            i += 1


class ALPReassembler:
    """Feed 188-byte packets; collects complete ALP packets as (type, payload)."""
    def __init__(self):
        self.buf, self.synced, self.out, self.seg, self.resyncs = b"", False, [], None, 0

    def feed(self, p):
        if p[1] & 0x40:                                   # payload unit start: p[3] = pointer to an ALP start
            ptr = p[3]
            if self.synced:
                self.buf += p[4:4 + ptr]                  # tail of the packet in progress
                self._parse()
                if self.buf:                              # boundary disagrees: reception error, resync
                    self.resyncs += 1; self.seg = None
            self.buf, self.synced = p[4 + ptr:], True
        elif self.synced:
            self.buf += p[3:]
        self._parse()

    def _parse(self):
        b = self.buf
        while len(b) >= 2:
            ptype, pc = b[0] >> 5, b[0] >> 4 & 1
            if pc == 0:
                hm, length = b[0] >> 3 & 1, (b[0] & 7) << 8 | b[1]
                hdr = 2
                if hm:
                    if len(b) < 3: break
                    length |= (b[2] >> 3) << 11
                    sif, hef = b[2] >> 1 & 1, b[2] & 1
                    hdr = 3 + sif + (self._hef_len(b, 3 + sif) if hef else 0)
                if len(b) < hdr + length: break
                self.out.append((ptype, b[hdr:hdr + length]))
                b = b[hdr + length:]
            else:                                         # segmentation / concatenation
                sc, length = b[0] >> 3 & 1, (b[0] & 7) << 8 | b[1]
                if len(b) < 3: break
                if sc == 0:                               # segment
                    seg_sn, lsi, sif, hef = b[2] >> 3, b[2] >> 2 & 1, b[2] >> 1 & 1, b[2] & 1
                    hdr = 3 + sif + (self._hef_len(b, 3 + sif) if hef else 0)
                    if len(b) < hdr + length: break
                    chunk = b[hdr:hdr + length]
                    if seg_sn == 0:
                        self.seg = [ptype, chunk]
                    elif self.seg:
                        self.seg[1] += chunk
                    if lsi and self.seg:
                        self.out.append(tuple(self.seg)); self.seg = None
                    b = b[hdr + length:]
                else:                                     # concatenation
                    length |= (b[2] >> 4) << 11
                    count, sif = (b[2] >> 1 & 7) + 2, b[2] & 1
                    nl = (count * 12 + 7) // 8
                    if len(b) < 3 + nl: break
                    bits = int.from_bytes(b[3:3 + nl], "big") >> (nl * 8 - count * 12)
                    lens = [bits >> (12 * (count - 1 - k)) & 0xFFF for k in range(count)]
                    hdr = 3 + nl + sif
                    if len(b) < hdr + length: break
                    off = hdr
                    for n in lens:
                        self.out.append((ptype, b[off:off + n])); off += n
                    b = b[hdr + length:]
        self.buf = b

    @staticmethod
    def _hef_len(b, o):
        # header extension: type(1) + length_minus1(1) + data
        return 2 + b[o + 1] + 1 if len(b) > o + 1 else 0


def lls_tables(d):
    """LLS (A/331): 4-byte header then gzip'd XML; table 0xFE = SignedMultiTable holding several."""
    if d[0] != 0xFE:
        return [(d[0], d[4:])]
    out, o = [], 5
    for _ in range(d[4]):
        tid, ln = d[o], struct.unpack(">H", d[o + 2:o + 4])[0]
        out.append((tid, d[o + 4:o + 4 + ln])); o += 4 + ln
    return out


def write_pcap(path, ips):
    with open(path, "wb") as f:
        f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 228))   # LINKTYPE_IPV4
        for k, ip in enumerate(ips):
            f.write(struct.pack("<IIII", k // 1000, k % 1000 * 1000, len(ip), len(ip)) + ip)


def ip_ok(ip):
    if len(ip) < 20 or ip[0] >> 4 != 4: return False
    ihl = (ip[0] & 15) * 4
    if struct.unpack(">H", ip[2:4])[0] != len(ip): return False
    s = sum(struct.unpack(f">{ihl // 2}H", ip[:ihl]))
    while s >> 16: s = (s & 0xFFFF) + (s >> 16)
    return s == 0xFFFF


def udp(ip):
    if len(ip) < 28 or ip[0] >> 4 != 4 or ip[9] != 17:
        return None
    ihl = (ip[0] & 15) * 4
    dst = ".".join(map(str, ip[16:20]))
    sport, dport, ulen = struct.unpack(">HHH", ip[ihl:ihl + 6])
    return dst, dport, ip[ihl + 8:ihl + ulen]


if __name__ == "__main__":
    raw = open(sys.argv[1], "rb").read()
    r = ALPReassembler()
    for p in ts_packets(raw):
        r.feed(p)
    types = Counter(t for t, _ in r.out)
    ip_all = [pl for t, pl in r.out if t == 0]
    ips = [ip for ip in ip_all if ip_ok(ip)]
    print(f"{len(r.out)} ALP packets, types {dict(types)}, {len(ips)}/{len(ip_all)} IPv4 good, {r.resyncs} resyncs")
    flows = Counter()
    lls = {}
    for ip in ips:
        u = udp(ip)
        if not u: continue
        flows[(u[0], u[1])] += 1
        if u[:2] == ("224.0.23.60", 4937) and len(u[2]) > 4:
            for tid, body in lls_tables(u[2]):
                try: lls[tid] = gzip.decompress(body).decode("utf-8", "replace")
                except Exception: pass
    for (dst, port), n in flows.most_common(15):
        print(f"  {dst}:{port}  {n} packets")
    for tid, xml in lls.items():
        print(f"LLS table {tid}:")
        for m in re.finditer(r"<Service\b[^>]*>", xml):
            a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(0)))
            print(f"  {a.get('majorChannelNo')}.{a.get('minorChannelNo')} {a.get('shortServiceName')} "
                  f"(serviceId {a.get('serviceId')}, category {a.get('serviceCategory')})")
        if "<Service" not in xml:
            print("  " + xml[:300].replace("\n", " "))
    if len(sys.argv) > 2:
        write_pcap(sys.argv[2], ips); print("wrote", sys.argv[2])
