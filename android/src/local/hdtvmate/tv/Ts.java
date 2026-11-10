package local.hdtvmate.tv;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MPEG-TS helpers: PSI section assembly, PAT/PMT/PSIP-TVCT parsing, single-program filter. */
public final class Ts {

    /** A virtual channel found by the scan. */
    public static final class Channel {
        public int rf, khz, major, minor, program;
        public String name;
        public String label() { return major + "." + minor + "  " + name; }
        public String key() { return rf + ":" + program; }
    }

    /** Collects complete PSI sections on chosen PIDs. */
    public static final class Sections {
        private final Map<Integer, ByteArrayOutputStream> bufs = new HashMap<>();
        public interface Handler { void section(int pid, byte[] s, int len); }
        private final Handler h;
        public Sections(Handler h) { this.h = h; }
        public void watch(int pid) { if (!bufs.containsKey(pid)) bufs.put(pid, null); }
        public boolean watching(int pid) { return bufs.containsKey(pid); }

        public void packet(byte[] p, int o) {
            int pid = (p[o + 1] & 0x1f) << 8 | (p[o + 2] & 0xff);
            if (!bufs.containsKey(pid) || (p[o + 3] & 0x10) == 0) return;
            int i = 4;
            if ((p[o + 3] & 0x20) != 0) i = 5 + (p[o + 4] & 0xff);
            if (i >= 188) return;
            ByteArrayOutputStream b = bufs.get(pid);
            if ((p[o + 1] & 0x40) != 0) {
                int ptr = p[o + i] & 0xff;
                if (b != null && i + 1 + ptr <= 188) { b.write(p, o + i + 1, ptr); flush(pid, b); }
                b = new ByteArrayOutputStream();
                i += 1 + ptr;
                if (i < 188) b.write(p, o + i, 188 - i);
                bufs.put(pid, b);
            } else if (b != null) {
                b.write(p, o + i, 188 - i);
            }
            if (b != null) flush(pid, b);
        }

        private void flush(int pid, ByteArrayOutputStream b) {
            byte[] s = b.toByteArray();
            if (s.length < 3 || (s[0] & 0xff) == 0xff) return;
            int need = 3 + ((s[1] & 0x0f) << 8 | (s[2] & 0xff));
            if (s.length >= need) { bufs.put(pid, null); h.section(pid, s, need); }
        }
    }

    /** PAT: program_number -> PMT pid. */
    public static Map<Integer, Integer> parsePat(byte[] s, int len) {
        Map<Integer, Integer> m = new HashMap<>();
        if ((s[0] & 0xff) != 0) return m;
        for (int i = 8; i + 4 <= len - 4; i += 4) {
            int prog = (s[i] & 0xff) << 8 | (s[i + 1] & 0xff), pid = (s[i + 2] & 0x1f) << 8 | (s[i + 3] & 0xff);
            if (prog != 0) m.put(prog, pid);
        }
        return m;
    }

    /** PMT: PCR pid + elementary stream pids. */
    public static Set<Integer> parsePmtPids(byte[] s, int len) {
        Set<Integer> pids = new HashSet<>();
        if ((s[0] & 0xff) != 2) return pids;
        pids.add((s[8] & 0x1f) << 8 | (s[9] & 0xff));
        int i = 12 + ((s[10] & 0x0f) << 8 | (s[11] & 0xff));
        while (i + 5 <= len - 4) {
            pids.add((s[i + 1] & 0x1f) << 8 | (s[i + 2] & 0xff));
            i += 5 + ((s[i + 3] & 0x0f) << 8 | (s[i + 4] & 0xff));
        }
        return pids;
    }

    /** PSIP terrestrial VCT (table 0xc8) -> channels (digital TV + audio services, not hidden). */
    public static List<Channel> parseTvct(byte[] s, int len, int rf, int khz) {
        List<Channel> out = new ArrayList<>();
        if ((s[0] & 0xff) != 0xc8 && (s[0] & 0xff) != 0xc9) return out;
        int n = s[9] & 0xff, o = 10;
        for (int k = 0; k < n && o + 32 <= len; k++) {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                char c = (char) ((s[o + 2 * i] & 0xff) << 8 | (s[o + 2 * i + 1] & 0xff));
                if (c != 0) name.append(c);
            }
            Channel ch = new Channel();
            ch.rf = rf; ch.khz = khz;
            ch.major = (s[o + 14] & 0x0f) << 6 | (s[o + 15] & 0xff) >> 2;
            ch.minor = (s[o + 15] & 0x03) << 8 | (s[o + 16] & 0xff);
            ch.program = (s[o + 24] & 0xff) << 8 | (s[o + 25] & 0xff);
            ch.name = name.toString().trim();
            boolean hidden = (s[o + 26] & 0x10) != 0;
            int type = s[o + 27] & 0x3f;
            int dlen = (s[o + 30] & 0x03) << 8 | (s[o + 31] & 0xff);
            if (!hidden && (type == 2 || type == 3) && ch.program != 0 && ch.program != 0xffff) out.add(ch);
            o += 32 + dlen;
        }
        return out;
    }

    // ---------------- CRC32/MPEG-2 ----------------
    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int j = 0; j < 8; j++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04c11db7 : c << 1;
            CRC[i] = c;
        }
    }
    static int crc32(byte[] b, int off, int len) {
        int c = 0xffffffff;
        for (int i = 0; i < len; i++) c = (c << 8) ^ CRC[((c >>> 24) ^ b[off + i]) & 0xff];
        return c;
    }

    /**
     * Keeps one program: emits a rewritten single-program PAT, that program's PMT and its streams.
     * Everything else (other subchannels, PSIP, nulls) is dropped.
     */
    public static final class ProgramFilter {
        private final int program;
        private int pmtPid = -1, tsid = 1, patCc;
        private final Set<Integer> pass = new HashSet<>();
        private final Sections sections;
        private final byte[] patPkt = new byte[188];
        private boolean havePat;

        public ProgramFilter(int program) {
            this.program = program;
            sections = new Sections(new Sections.Handler() {
                public void section(int pid, byte[] s, int len) { onSection(pid, s, len); }
            });
            sections.watch(0);
        }

        private void onSection(int pid, byte[] s, int len) {
            if (pid == 0) {
                Integer p = parsePat(s, len).get(program);
                tsid = (s[3] & 0xff) << 8 | (s[4] & 0xff);
                if (p != null && p != pmtPid) {
                    pmtPid = p;
                    pass.clear();
                    sections.watch(pmtPid);
                    buildPat();
                }
            } else if (pid == pmtPid) {
                if ((s[0] & 0xff) != 2 || ((s[3] & 0xff) << 8 | (s[4] & 0xff)) != program) return;
                pass.clear();
                pass.addAll(parsePmtPids(s, len));
            }
        }

        private void buildPat() {
            byte[] sec = new byte[16];
            int l = 13;                                       // section_length
            sec[0] = 0; sec[1] = (byte) (0xb0 | l >> 8); sec[2] = (byte) l;
            sec[3] = (byte) (tsid >> 8); sec[4] = (byte) tsid; sec[5] = (byte) 0xc1; sec[6] = 0; sec[7] = 0;
            sec[8] = (byte) (program >> 8); sec[9] = (byte) program;
            sec[10] = (byte) (0xe0 | pmtPid >> 8); sec[11] = (byte) pmtPid;
            int c = crc32(sec, 0, 12);
            sec[12] = (byte) (c >> 24); sec[13] = (byte) (c >> 16); sec[14] = (byte) (c >> 8); sec[15] = (byte) c;
            java.util.Arrays.fill(patPkt, (byte) 0xff);
            patPkt[0] = 0x47; patPkt[1] = 0x40; patPkt[2] = 0; patPkt[4] = 0;
            System.arraycopy(sec, 0, patPkt, 5, 16);
            havePat = true;
        }

        /** Filter a run of 188-byte packets; returns bytes written to out. */
        public int filter(byte[] in, int off, int len, byte[] out) {
            int o = 0;
            for (int i = off; i + 188 <= off + len; i += 188) {
                if (in[i] != 0x47) continue;
                int pid = (in[i + 1] & 0x1f) << 8 | (in[i + 2] & 0xff);
                if (pid == 0 || pid == pmtPid) sections.packet(in, i);
                if (pid == 0) {
                    if (havePat) {
                        patPkt[3] = (byte) (0x10 | (patCc++ & 0x0f));
                        System.arraycopy(patPkt, 0, out, o, 188); o += 188;
                    }
                } else if (pid == pmtPid || pass.contains(pid)) {
                    if (!havePat) continue;
                    System.arraycopy(in, i, out, o, 188); o += 188;
                }
            }
            return o;
        }
    }
}
