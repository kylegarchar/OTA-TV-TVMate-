package local.hdtvmate.tv;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/** Java driver for the HDTV Mate (IT9306 bridge + Sony CXD6801), ATSC 1.0. Same protocol as libhdtvmate. */
public final class Tuner {
    public static final int VID = 0x23e2, PID = 0x2b02;
    private static final int UNIT = 192, XFER = UNIT * 256, NREQ = 8;
    private static final int CMD_REG_WRITE = 0x0001, CMD_QUERYINFO = 0x0022, CMD_I2C_READ = 0x002a, CMD_I2C_WRITE = 0x002b;
    private static final int BUS = 3, DEMOD = 0xd8, GATE = 0xdc, TUNER = 0xc0, VSB = 0x30;

    public interface TsSink { void packets(byte[] buf, int off, int len); }

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private UsbEndpoint epOut, epIn, epTs;
    private final Blob blob;
    private int seq;
    private final byte[] cmdBuf = new byte[512];

    private volatile boolean streaming;
    private Thread streamThread;

    public static final class Status { public boolean locked; public int strength; }

    /** Seq records from hdtvmate.bin: [u16 delay][u16 cmd][u8 len][payload]. */
    public static final class Blob {
        final byte[] data; int fwOff, fwLen, initOff, initLen, tuneOff, tuneLen;
        public Blob(byte[] d) throws IOException {
            data = d;
            if (d.length < 8 || !new String(d, 0, 8, "US-ASCII").equals("HDTVMAT1")) throw new IOException("not an hdtvmate.bin");
            int o = 8;
            while (o + 5 <= d.length) {
                int id = d[o] & 0xff;
                int n = (d[o + 1] & 0xff) | (d[o + 2] & 0xff) << 8 | (d[o + 3] & 0xff) << 16 | (d[o + 4] & 0xff) << 24;
                if (o + 5 + n > d.length) throw new IOException("truncated hdtvmate.bin");
                if (id == 1) { fwOff = o + 5; fwLen = n; }
                else if (id == 2) { initOff = o + 5; initLen = n; }
                else if (id == 3) { tuneOff = o + 5; tuneLen = n; }
                o += 5 + n;
            }
            if (fwLen == 0 || initLen == 0 || tuneLen == 0) throw new IOException("incomplete hdtvmate.bin");
        }
    }

    public Tuner(UsbManager mgr, UsbDevice dev, Blob blob) throws IOException {
        this.blob = blob;
        intf = dev.getInterface(0);
        conn = mgr.openDevice(dev);
        if (conn == null) throw new IOException("cannot open tuner (USB permission?)");
        if (!conn.claimInterface(intf, true)) { conn.close(); throw new IOException("cannot claim tuner"); }
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            switch (e.getAddress()) {
                case 0x02: epOut = e; break;
                case 0x81: epIn = e; break;
                case 0x84: epTs = e; break;
            }
        }
        if (epOut == null || epIn == null || epTs == null) { close(); throw new IOException("unexpected USB layout"); }
        hwInit();
    }

    public void close() {
        stopStream();
        try { conn.releaseInterface(intf); } catch (Exception ignored) { }
        conn.close();
    }

    // ---------------- bridge commands ----------------

    private static int csum16(byte[] b, int off, int n) {
        int s = 0;
        for (int i = 0; i + 1 < n; i += 2) s = (s + ((b[off + i] & 0xff) << 8 | (b[off + i + 1] & 0xff))) & 0xffff;
        if ((n & 1) != 0) s = (s + ((b[off + n - 1] & 0xff) << 8)) & 0xffff;
        return ~s & 0xffff;
    }

    /** Returns 0 ok, >0 bridge status (NAK), throws on USB failure. */
    private synchronized int cmd(int code, byte[] pl, int plen, byte[] out, int olen) throws IOException {
        byte[] b = cmdBuf;
        seq = (seq + 1) & 0xff;
        b[0] = (byte) (plen + 5); b[1] = (byte) (code >> 8); b[2] = (byte) code; b[3] = (byte) seq;
        System.arraycopy(pl, 0, b, 4, plen);
        int c = csum16(b, 1, plen + 3);
        b[plen + 4] = (byte) (c >> 8); b[plen + 5] = (byte) c;
        if (conn.bulkTransfer(epOut, b, plen + 6, 3000) != plen + 6) throw new IOException("USB write failed");
        int n = conn.bulkTransfer(epIn, b, b.length, 3000);
        if (n < 5 || (b[1] & 0xff) != seq || csum16(b, 1, n - 3) != ((b[n - 2] & 0xff) << 8 | (b[n - 1] & 0xff)))
            throw new IOException("bad reply from tuner");
        if (b[2] != 0) return b[2] & 0xff;
        if (out != null) System.arraycopy(b, 3, out, 0, Math.min(olen, n - 5));
        return 0;
    }

    private int regWrite(int addr, int v) throws IOException {
        return cmd(CMD_REG_WRITE, new byte[]{1, 2, 0, 0, (byte) (addr >> 8), (byte) addr, (byte) v}, 7, null, 0);
    }

    private int i2cWrite(int addr, int... data) throws IOException {
        byte[] pl = new byte[data.length + 3];
        pl[0] = (byte) data.length; pl[1] = BUS; pl[2] = (byte) addr;
        for (int i = 0; i < data.length; i++) pl[3 + i] = (byte) data[i];
        return cmd(CMD_I2C_WRITE, pl, pl.length, null, 0);
    }

    private int i2cRead(int addr, int reg, byte[] out, int n) throws IOException {
        int r = regWrite(0xf424, 1);
        if (r == 0) r = i2cWrite(addr, reg);
        if (r == 0) r = regWrite(0xf424, 0);
        if (r == 0) r = cmd(CMD_I2C_READ, new byte[]{(byte) n, BUS, (byte) addr}, 3, out, n);
        return r;
    }

    private int bankRead(int addr, int bank, int reg, byte[] out, int n) throws IOException {
        int r = i2cWrite(addr, 0, bank);
        return r != 0 ? r : i2cRead(addr, reg, out, n);
    }

    private int runSeq(int off, int len, int khz, boolean strict) throws IOException {
        byte[] d = blob.data, pl = new byte[256];
        int o = off, end = off + len, errs = 0;
        while (o + 5 <= end) {
            int delay = (d[o] & 0xff) | (d[o + 1] & 0xff) << 8, code = (d[o + 2] & 0xff) | (d[o + 3] & 0xff) << 8, n = d[o + 4] & 0xff;
            System.arraycopy(d, o + 5, pl, 0, n);
            o += 5 + n;
            if (khz != 0 && code == CMD_I2C_WRITE && n > 14 && (pl[2] & 0xff) == TUNER && (pl[3] & 0xff) == 0x68) {
                pl[12] = (byte) khz; pl[13] = (byte) (khz >> 8); pl[14] = (byte) (khz >> 16);
            }
            if (delay > 0) sleep(delay);
            int r = cmd(code, pl, n, null, 0);
            if (r != 0) { if (strict) throw new IOException("firmware load failed (status " + r + ")"); errs++; }
        }
        return errs;
    }

    private void hwInit() throws IOException {
        byte[] v = new byte[4];
        cmd(CMD_QUERYINFO, new byte[]{1}, 1, v, 4);
        if ((v[0] | v[1] | v[2] | v[3]) == 0) {
            runSeq(blob.fwOff, blob.fwLen, 0, true);
            sleep(50);
        } else if (i2cWrite(DEMOD, 0, 0) == 0) {
            return;                                    // already running
        }
        runSeq(blob.initOff, blob.initLen, 0, false);
    }

    // ---------------- tuning / status ----------------

    public boolean locked() throws IOException {
        byte[] v = new byte[1];
        return bankRead(VSB, 0x0f, 0x11, v, 1) == 0 && v[0] == 1;
    }

    /** Tune (kHz). Stops streaming; returns true when locked within 1.5 s. */
    public boolean tune(int khz) throws IOException {
        stopStream();
        regWrite(0xd8e4, 1); regWrite(0xd8e5, 1); regWrite(0xd8e3, 1);
        runSeq(blob.tuneOff, blob.tuneLen, khz, false);
        boolean lk = false;
        for (long end = System.currentTimeMillis() + 1500; System.currentTimeMillis() < end; sleep(30))
            if (lk = locked()) break;
        regWrite(0xda1d, 1);                           // purge the previous channel's queued TS
        byte[] junk = new byte[XFER];
        for (int i = 0; i < 4 && conn.bulkTransfer(epTs, junk, junk.length, 20) > 0; i++) { }
        regWrite(0xda1d, 0);
        return lk;
    }

    public Status status() throws IOException {
        Status s = new Status();
        s.locked = locked();
        byte[] lvl = new byte[1];
        i2cWrite(GATE, 0x08, 1);
        i2cWrite(TUNER, 0x87, 0xc4, 0x41);
        i2cWrite(TUNER, 0x59, 0x05, 0x01);
        i2cRead(TUNER, 0x5b, lvl, 1);
        i2cWrite(TUNER, 0x59, 0x04);
        i2cWrite(TUNER, 0x88, 0x00);
        i2cWrite(TUNER, 0x87, 0xc0);
        i2cWrite(GATE, 0x08, 0);
        int l = lvl[0] & 0xff;
        s.strength = l <= 0x33 ? 0 : l >= 0x96 ? 100 : (l - 0x33) * 100 / (0x96 - 0x33);
        return s;
    }

    public static int channelKhz(int rf) {
        if (rf >= 2 && rf <= 4) return 57000 + 6000 * (rf - 2);
        if (rf >= 5 && rf <= 6) return 79000 + 6000 * (rf - 5);
        if (rf >= 7 && rf <= 13) return 177000 + 6000 * (rf - 7);
        if (rf >= 14 && rf <= 36) return 473000 + 6000 * (rf - 14);
        return 0;
    }

    // ---------------- streaming: 8 queued requests, tags stripped ----------------

    public synchronized void startStream(final TsSink sink) {
        stopStream();
        streaming = true;
        streamThread = new Thread(new Runnable() { public void run() { streamLoop(sink); } }, "hdtvmate-ts");
        streamThread.setPriority(Thread.MAX_PRIORITY);
        streamThread.start();
    }

    public void stopStream() {
        Thread t = streamThread;
        streaming = false;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { }
            streamThread = null;
        }
    }

    private void streamLoop(TsSink sink) {
        UsbRequest[] reqs = new UsbRequest[NREQ];
        byte[] carry = new byte[UNIT], arr = new byte[XFER], out = new byte[XFER + UNIT];
        int carryLen = 0, queued = 0;
        try {
            for (int i = 0; i < NREQ; i++) {
                reqs[i] = new UsbRequest();
                reqs[i].initialize(conn, epTs);
                ByteBuffer buf = ByteBuffer.allocateDirect(XFER);
                reqs[i].setClientData(buf);
                if (reqs[i].queue(buf)) queued++;
            }
            while (streaming) {
                UsbRequest r;
                try { r = conn.requestWait(500); } catch (TimeoutException te) { continue; }
                if (r == null) break;
                ByteBuffer buf = (ByteBuffer) r.getClientData();
                int n = buf.position();
                buf.flip(); buf.get(arr, 0, n); buf.clear();
                if (streaming && !r.queue(buf)) queued--;
                // strip the 4-byte tag of each 192-byte unit, resync on loss
                int i = 0, o = 0;
                if (carryLen > 0) {
                    int need = UNIT - carryLen;
                    if (n < need) { System.arraycopy(arr, 0, carry, carryLen, n); carryLen += n; continue; }
                    System.arraycopy(arr, 0, carry, carryLen, need);
                    if (carry[4] == 0x47) { System.arraycopy(carry, 4, out, o, 188); o += 188; }
                    i = need; carryLen = 0;
                }
                while (i + UNIT <= n) {
                    if (arr[i + 4] == 0x47) { System.arraycopy(arr, i + 4, out, o, 188); o += 188; i += UNIT; }
                    else i++;
                }
                if (i < n) { carryLen = n - i; System.arraycopy(arr, i, carry, 0, carryLen); }
                if (o > 0) sink.packets(out, 0, o);
            }
        } catch (Exception e) {
            android.util.Log.w("HDTVMate", "stream stopped", e);
        } finally {
            for (UsbRequest r : reqs) if (r != null) r.cancel();
            for (int k = 0; k < queued; k++) {
                try { if (conn.requestWait(200) == null) break; } catch (Exception e) { break; }
            }
            for (UsbRequest r : reqs) if (r != null) r.close();
        }
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
