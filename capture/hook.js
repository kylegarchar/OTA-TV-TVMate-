// Logs USB control traffic between HDTVPlayer and the tuner (bulk EP 0x02 out / 0x81 in, plus control transfers).
// The TS stream endpoints (0x84, 0x85) are skipped. Output: one line per transfer, hex payload.
const LIB = "libusb_android_sony.so";
const SKIP = new Set([0x84, 0x85]);
const hex = (p, n) => (n > 0 && !p.isNull()) ? Array.from(new Uint8Array(p.readByteArray(Math.min(n, 512)))).map(b => b.toString(16).padStart(2, "0")).join("") : "";
let t0 = Date.now();
const ts = () => ((Date.now() - t0) / 1000).toFixed(3);
function hookAll() {
  const bulk = Module.findExportByName(LIB, "libusb_bulk_transfer");
  if (!bulk) return false;
  Interceptor.attach(bulk, {
    onEnter(a) { this.ep = a[1].toInt32() & 0xff; this.buf = a[2]; this.len = a[3].toInt32(); this.xf = a[4];
      if (!SKIP.has(this.ep) && !(this.ep & 0x80)) console.log(`${ts()} OUT ep${this.ep.toString(16)} ${hex(this.buf, this.len)}`); },
    onLeave(r) { if (SKIP.has(this.ep) || !(this.ep & 0x80)) return;
      const n = this.xf.isNull() ? 0 : this.xf.readS32();
      console.log(`${ts()} IN  ep${this.ep.toString(16)} rc=${r.toInt32()} ${hex(this.buf, n)}`); }
  });
  const ctl = Module.findExportByName(LIB, "libusb_control_transfer");
  if (ctl) Interceptor.attach(ctl, {
    onEnter(a) { this.rt = a[1].toInt32() & 0xff; this.buf = a[5];
      this.d = `rt=${this.rt.toString(16)} req=${(a[2].toInt32() & 0xff).toString(16)} val=${(a[3].toInt32() & 0xffff).toString(16)} idx=${(a[4].toInt32() & 0xffff).toString(16)} len=${a[6].toInt32() & 0xffff}`;
      if (!(this.rt & 0x80)) console.log(`${ts()} CTL OUT ${this.d} ${hex(this.buf, a[6].toInt32() & 0xffff)}`); },
    onLeave(r) { if (this.rt & 0x80) console.log(`${ts()} CTL IN  ${this.d} rc=${r.toInt32()} ${hex(this.buf, r.toInt32())}`); }
  });
  const sub = Module.findExportByName(LIB, "libusb_submit_transfer");
  if (sub) Interceptor.attach(sub, { onEnter(a) { const t = a[0]; const ep = t.add(5).readU8();
      if (!SKIP.has(ep)) console.log(`${ts()} ASYNC ep${ep.toString(16)} len=${t.add(16).readS32()}${ep & 0x80 ? "" : " " + hex(t.add(32).readPointer(), t.add(16).readS32())}`); } });
  console.log(`hooked ${LIB}: bulk=${!!bulk} control=${!!ctl} submit=${!!sub}`);
  return true;
}
const poll = setInterval(() => { if (hookAll()) clearInterval(poll); }, 20);
console.log("hook.js loaded, waiting for " + LIB);

