// SPDX-License-Identifier: GPL-2.0
/*
 * DVB driver for the GTMEDIA/Zenview "HDTV Mate" USB tuner (23e2:2b02):
 * ITE IT9306 USB bridge + Sony CXD6801 demod + Sony tuner. ATSC 1.0 (8VSB) only.
 *
 * Bridge protocol follows the IT930x command format (as documented by px4_drv).
 * Vendor firmware and chip setup sequences are NOT part of this source: they are
 * loaded at probe time from /lib/firmware/hdtvmate.bin, built by mkblob.py from
 * the owner's own captures.
 */
#include <linux/module.h>
#include <linux/usb.h>
#include <linux/firmware.h>
#include <linux/slab.h>
#include <linux/delay.h>
#include <linux/version.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
#include <linux/unaligned.h>
#else
#include <asm/unaligned.h>
#endif
#include <linux/mutex.h>
#include <linux/spinlock.h>
#include <media/dvbdev.h>
#include <media/dvb_frontend.h>
#include <media/dvb_demux.h>
#include <media/dmxdev.h>

DVB_DEFINE_MOD_OPT_ADAPTER_NR(adapter_nr);

static int debug;
module_param(debug, int, 0644);
MODULE_PARM_DESC(debug, "log failing bridge commands");

static bool force_init;
module_param(force_init, bool, 0644);
MODULE_PARM_DESC(force_init, "always run chip init at probe");

#define FW_NAME		"hdtvmate.bin"
#define EP_CMD_OUT	0x02
#define EP_CMD_IN	0x81
#define EP_TS		0x84
#define UNIT		192		/* 4-byte bridge tag + 188-byte TS packet */
#define URB_COUNT	8
#define URB_SIZE	(UNIT * 256)	/* 49152 = 96 * 512 */

#define CMD_REG_WRITE	0x0001
#define CMD_QUERYINFO	0x0022
#define CMD_I2C_READ	0x002a
#define CMD_I2C_WRITE	0x002b

#define I2C_BUS		3
#define ADDR_DEMOD	0xd8
#define ADDR_GATE	0xdc
#define ADDR_TUNER	0xc0
#define ADDR_VSB	0x30

struct seq {
	const u8 *data;
	size_t len;
};

struct hdtvmate {
	struct usb_device *udev;
	struct usb_interface *intf;
	struct mutex lock;		/* serialises bridge commands */
	struct mutex op_lock;		/* whole tune / status sequences */
	u8 seq;
	u8 *cmdbuf;			/* DMA-safe command/reply buffer */

	const struct firmware *fw;
	struct seq sec_fw, sec_init, sec_tune;

	struct dvb_adapter adapter;
	struct dvb_frontend fe;
	struct dvb_demux demux;
	struct dmxdev dmxdev;
	struct dmx_frontend fe_hw, fe_mem;

	struct urb *urbs[URB_COUNT];
	u8 *urb_buf[URB_COUNT];
	int feeds;
	bool streaming;
	spinlock_t ts_lock;		/* carry buffer */
	u8 carry[2 * UNIT];
	int carry_len;
	u8 *pkts;			/* stripped 188-byte packets */
	bool disconnected;
};

/* ---------------- bridge commands ---------------- */

static u16 csum16(const u8 *b, int n)
{
	u16 s = 0;		/* plain 16-bit wraparound sum, then inverted (no carry fold) */
	int i;

	for (i = 0; i + 1 < n; i += 2)
		s += b[i] << 8 | b[i + 1];
	if (n & 1)
		s += b[n - 1] << 8;
	return ~s & 0xffff;
}

/* Send one command; copy up to rlen reply payload bytes into rbuf. Caller holds d->lock. */
static int bridge_cmd_locked(struct hdtvmate *d, u16 code, const u8 *pl, int plen,
			     u8 *rbuf, int rlen)
{
	u8 *b = d->cmdbuf;
	int n = plen + 6, act, ret, i;
	u16 c;

	if (d->disconnected)
		return -ENODEV;
	if (plen > 250)
		return -EINVAL;
	d->seq++;
	b[0] = plen + 5;
	b[1] = code >> 8;
	b[2] = code & 0xff;
	b[3] = d->seq;
	memcpy(b + 4, pl, plen);
	c = csum16(b + 1, plen + 3);
	b[plen + 4] = c >> 8;
	b[plen + 5] = c & 0xff;
	if (debug > 1)
		dev_info(&d->intf->dev, "tx %*ph\n", min(n, 32), b);
	ret = usb_bulk_msg(d->udev, usb_sndbulkpipe(d->udev, EP_CMD_OUT), b, n, &act, 3000);
	if (ret)
		return ret;
	/* drop stale replies to earlier (timed-out) commands until ours arrives */
	for (i = 0; i < 4; i++) {
		ret = usb_bulk_msg(d->udev, usb_rcvbulkpipe(d->udev, EP_CMD_IN), b, 512, &act, 3000);
		if (ret)
			return ret;
		if (act >= 5 && b[1] == d->seq)
			break;
	}
	if (act < 5 || b[1] != d->seq)
		return -EIO;
	c = csum16(b + 1, act - 3);
	if ((b[act - 2] << 8 | b[act - 1]) != c)
		return -EIO;
	if (b[2]) {
		if (debug)
			dev_info(&d->intf->dev, "cmd %04x [%*ph] -> status %d rx %*ph\n", code, min(plen, 12), pl, b[2], min(act, 16), b);
		return -EREMOTEIO;
	}
	if (rbuf && rlen)
		memcpy(rbuf, b + 3, min(rlen, act - 5));
	return 0;
}

static int bridge_cmd(struct hdtvmate *d, u16 code, const u8 *pl, int plen, u8 *rbuf, int rlen)
{
	int ret;

	mutex_lock(&d->lock);
	ret = bridge_cmd_locked(d, code, pl, plen, rbuf, rlen);
	mutex_unlock(&d->lock);
	return ret;
}

static int reg_write_locked(struct hdtvmate *d, u16 addr, u8 val)
{
	u8 pl[] = { 1, 2, 0, 0, addr >> 8, addr & 0xff, val };

	return bridge_cmd_locked(d, CMD_REG_WRITE, pl, sizeof(pl), NULL, 0);
}

static int i2c_write_locked(struct hdtvmate *d, u8 addr, const u8 *data, int n)
{
	u8 pl[64];

	if (n > 60)
		return -EINVAL;
	pl[0] = n;
	pl[1] = I2C_BUS;
	pl[2] = addr;
	memcpy(pl + 3, data, n);
	return bridge_cmd_locked(d, CMD_I2C_WRITE, pl, n + 3, NULL, 0);
}

/* Register read: the bridge wants reg 0xf424 = 1 around the address phase. */
static int i2c_read_locked(struct hdtvmate *d, u8 addr, u8 reg, u8 *out, int n)
{
	u8 pl[3] = { n, I2C_BUS, addr };
	int ret;

	ret = reg_write_locked(d, 0xf424, 1);
	if (!ret)
		ret = i2c_write_locked(d, addr, &reg, 1);
	if (!ret)
		ret = reg_write_locked(d, 0xf424, 0);
	if (!ret)
		ret = bridge_cmd_locked(d, CMD_I2C_READ, pl, 3, out, n);
	return ret;
}

static int bank_read_locked(struct hdtvmate *d, u8 addr, u8 bank, u8 reg, u8 *out, int n)
{
	u8 sel[2] = { 0x00, bank };
	int ret = i2c_write_locked(d, addr, sel, 2);

	return ret ? ret : i2c_read_locked(d, addr, reg, out, n);
}

/* ---------------- captured sequences ---------------- */

static int parse_blob(struct hdtvmate *d)
{
	const u8 *p = d->fw->data, *end = p + d->fw->size;

	if (d->fw->size < 8 || memcmp(p, "HDTVMAT1", 8))
		return -EINVAL;
	p += 8;
	while (p + 5 <= end) {
		u8 id = p[0];
		u32 n = get_unaligned_le32(p + 1);
		struct seq s = { p + 5, n };

		if (p + 5 + n > end)
			return -EINVAL;
		if (id == 1)
			d->sec_fw = s;
		else if (id == 2)
			d->sec_init = s;
		else if (id == 3)
			d->sec_tune = s;
		p += 5 + n;
	}
	return d->sec_fw.len && d->sec_init.len && d->sec_tune.len ? 0 : -EINVAL;
}

/*
 * Replay a sequence. If khz is non-zero, patch the tuner frequency (3 bytes LE, kHz)
 * in the I2C write to tuner reg 0x68. Errors are counted, not fatal (the vendor's
 * own sequences contain a few expected NAKs).
 */
static int run_seq(struct hdtvmate *d, const struct seq *s, u32 khz, bool strict)
{
	const u8 *p = s->data, *end = s->data + s->len;
	u8 pl[256];
	int errs = 0, ret;

	while (p + 5 <= end) {
		u16 delay = get_unaligned_le16(p), code = get_unaligned_le16(p + 2);
		u8 n = p[4];

		if (p + 5 + n > end)
			return -EINVAL;
		memcpy(pl, p + 5, n);
		p += 5 + n;
		if (khz && code == CMD_I2C_WRITE && n > 14 && pl[2] == ADDR_TUNER && pl[3] == 0x68) {
			pl[12] = khz & 0xff;
			pl[13] = khz >> 8 & 0xff;
			pl[14] = khz >> 16 & 0xff;
		}
		if (delay)
			msleep(delay);
		else
			usleep_range(1000, 1500);	/* the chips need the pacing the vendor app had */
		mutex_lock(&d->lock);
		ret = bridge_cmd_locked(d, code, pl, n, NULL, 0);
		mutex_unlock(&d->lock);
		if (ret == -ENODEV)
			return ret;
		if (ret) {
			if (strict)
				return ret;
			errs++;
		}
	}
	return errs;
}

static bool demod_alive(struct hdtvmate *d)
{
	u8 sel[2] = { 0x00, 0x00 };
	int ret;

	mutex_lock(&d->lock);
	ret = i2c_write_locked(d, ADDR_DEMOD, sel, 2);
	mutex_unlock(&d->lock);
	return ret == 0;
}

static int hw_init(struct hdtvmate *d)
{
	u8 q = 1, ver[4] = {};
	int ret;

	ret = bridge_cmd(d, CMD_QUERYINFO, &q, 1, ver, 4);
	if (ret)
		return ret;
	if (!(ver[0] | ver[1] | ver[2] | ver[3])) {
		dev_info(&d->intf->dev, "loading bridge firmware\n");
		ret = run_seq(d, &d->sec_fw, 0, true);
		if (ret < 0)
			return ret;
		msleep(50);
	} else if (!force_init && demod_alive(d)) {
		return 0;
	}
	ret = run_seq(d, &d->sec_init, 0, false);
	if (ret < 0)
		return ret;
	q = 0;
	bridge_cmd(d, CMD_QUERYINFO, &q, 1, ver, 4);
	dev_info(&d->intf->dev, "firmware %u.%u.%u.%u, chips initialised (%d probe errors)\n",
		 ver[0], ver[1], ver[2], ver[3], ret);
	return 0;
}

/* ---------------- frontend ---------------- */

static int __fe_read_status(struct dvb_frontend *fe, enum fe_status *status)
{
	struct hdtvmate *d = fe->demodulator_priv;
	struct dtv_frontend_properties *c = &fe->dtv_property_cache;
	u8 lock = 0, lvl = 0;
	u8 w[3];
	int ret;

	mutex_lock(&d->lock);
	ret = bank_read_locked(d, ADDR_VSB, 0x0f, 0x11, &lock, 1);
	/* RF level from the tuner (behind the demod's I2C gate) */
	if (!ret) {
		w[0] = 0x08; w[1] = 1;
		ret = i2c_write_locked(d, ADDR_GATE, w, 2);
		w[0] = 0x87; w[1] = 0xc4; w[2] = 0x41;
		if (!ret) ret = i2c_write_locked(d, ADDR_TUNER, w, 3);
		w[0] = 0x59; w[1] = 0x05; w[2] = 0x01;
		if (!ret) ret = i2c_write_locked(d, ADDR_TUNER, w, 3);
		if (!ret) ret = i2c_read_locked(d, ADDR_TUNER, 0x5b, &lvl, 1);
		w[0] = 0x59; w[1] = 0x04;
		i2c_write_locked(d, ADDR_TUNER, w, 2);
		w[0] = 0x88; w[1] = 0x00;
		i2c_write_locked(d, ADDR_TUNER, w, 2);
		w[0] = 0x87; w[1] = 0xc0;
		i2c_write_locked(d, ADDR_TUNER, w, 2);
		w[0] = 0x08; w[1] = 0;
		i2c_write_locked(d, ADDR_GATE, w, 2);
	}
	mutex_unlock(&d->lock);
	if (ret)
		return ret;

	*status = 0;
	if (lvl > 0x60)
		*status |= FE_HAS_SIGNAL;
	if (lock == 1)
		*status |= FE_HAS_SIGNAL | FE_HAS_CARRIER | FE_HAS_VITERBI | FE_HAS_SYNC | FE_HAS_LOCK;

	/* 0x33 (nothing) .. 0x96 (strong) -> 0..65535 */
	c->strength.len = 1;
	c->strength.stat[0].scale = FE_SCALE_RELATIVE;
	c->strength.stat[0].uvalue = clamp_t(int, (lvl - 0x33) * 65535 / (0x96 - 0x33), 0, 65535);
	return 0;
}

static int fe_read_status(struct dvb_frontend *fe, enum fe_status *status)
{
	struct hdtvmate *d = fe->demodulator_priv;
	int ret;

	mutex_lock(&d->op_lock);
	ret = __fe_read_status(fe, status);
	mutex_unlock(&d->op_lock);
	return ret;
}

static int fe_read_signal_strength(struct dvb_frontend *fe, u16 *strength)
{
	enum fe_status st;
	int ret = fe_read_status(fe, &st);

	*strength = ret ? 0 : fe->dtv_property_cache.strength.stat[0].uvalue;
	return ret;
}

static void drain_ts(struct hdtvmate *d)
{
	int act, i;
	u8 *buf;

	if (d->streaming)
		return;			/* URBs are consuming the FIFO anyway */
	buf = kmalloc(URB_SIZE, GFP_KERNEL);
	if (!buf)
		return;
	for (i = 0; i < 4; i++)
		if (usb_bulk_msg(d->udev, usb_rcvbulkpipe(d->udev, EP_TS), buf, URB_SIZE, &act, 20))
			break;
	kfree(buf);
}

static int __fe_set_frontend(struct dvb_frontend *fe)
{
	struct hdtvmate *d = fe->demodulator_priv;
	u32 khz = fe->dtv_property_cache.frequency / 1000;
	int ret, i;

	mutex_lock(&d->lock);
	/* bridge TS input in ATSC 1.0 mode */
	ret = reg_write_locked(d, 0xd8e4, 1);
	if (!ret) ret = reg_write_locked(d, 0xd8e5, 1);
	if (!ret) ret = reg_write_locked(d, 0xd8e3, 1);
	mutex_unlock(&d->lock);
	if (ret)
		return ret;
	ret = run_seq(d, &d->sec_tune, khz, false);
	if (ret < 0)
		return ret;

	/* wait for lock (up to 1.5 s) */
	for (i = 0; i < 50; i++) {
		u8 lock = 0;

		mutex_lock(&d->lock);
		ret = bank_read_locked(d, ADDR_VSB, 0x0f, 0x11, &lock, 1);
		mutex_unlock(&d->lock);
		if (!ret && lock == 1)
			break;
		msleep(30);
	}

	/* drop TS still queued from the previous channel */
	mutex_lock(&d->lock);
	reg_write_locked(d, 0xda1d, 1);
	mutex_unlock(&d->lock);
	drain_ts(d);
	msleep(20);
	mutex_lock(&d->lock);
	reg_write_locked(d, 0xda1d, 0);
	mutex_unlock(&d->lock);
	spin_lock_irq(&d->ts_lock);
	d->carry_len = 0;
	spin_unlock_irq(&d->ts_lock);
	return 0;
}

static int fe_set_frontend(struct dvb_frontend *fe)
{
	struct hdtvmate *d = fe->demodulator_priv;
	int ret;

	mutex_lock(&d->op_lock);
	ret = __fe_set_frontend(fe);
	mutex_unlock(&d->op_lock);
	return ret;
}

static int fe_tune(struct dvb_frontend *fe, bool re_tune, unsigned int mode_flags,
		   unsigned int *delay, enum fe_status *status)
{
	int ret = 0;

	if (re_tune)
		ret = fe_set_frontend(fe);
	if (!ret)
		ret = fe_read_status(fe, status);
	*delay = HZ / 2;
	return ret;
}

static enum dvbfe_algo fe_get_algo(struct dvb_frontend *fe)
{
	return DVBFE_ALGO_HW;
}

static int fe_read_ucblocks(struct dvb_frontend *fe, u32 *ucb)
{
	*ucb = 0;
	return 0;
}

/* Last reference to the frontend dropped (after unplug, once userspace closed it): free everything. */
static void fe_release(struct dvb_frontend *fe)
{
	struct hdtvmate *d = fe->demodulator_priv;

	kfree(d->pkts);
	kfree(d->cmdbuf);
	kfree(d);
}

static const struct dvb_frontend_ops hdtvmate_fe_ops = {
	.release = fe_release,
	.delsys = { SYS_ATSC },
	.info = {
		.name = "HDTV Mate (CXD6801) ATSC",
		.frequency_min_hz = 54 * MHz,
		.frequency_max_hz = 700 * MHz,
		.frequency_stepsize_hz = 62500,
		.caps = FE_CAN_8VSB,
	},
	.get_frontend_algo = fe_get_algo,
	.tune = fe_tune,
	.set_frontend = fe_set_frontend,
	.read_status = fe_read_status,
	.read_signal_strength = fe_read_signal_strength,
	.read_ucblocks = fe_read_ucblocks,
};

/* ---------------- TS streaming ---------------- */

/* Strip the 4-byte bridge tag from each 192-byte unit; resync on loss. */
static void ts_feed(struct hdtvmate *d, const u8 *buf, int len)
{
	int np = 0, i = 0;

	/* finish a unit split across transfers */
	if (d->carry_len) {
		int need = UNIT - d->carry_len;

		if (len >= need) {
			memcpy(d->carry + d->carry_len, buf, need);
			if (d->carry[4] == 0x47)
				memcpy(d->pkts + 188 * np++, d->carry + 4, 188);
			i = need;
		} else {
			memcpy(d->carry + d->carry_len, buf, len);
			d->carry_len += len;
			return;
		}
		d->carry_len = 0;
	}
	while (i + UNIT <= len) {
		if (buf[i + 4] == 0x47) {
			memcpy(d->pkts + 188 * np++, buf + i + 4, 188);
			i += UNIT;
		} else {
			i++;		/* resync */
		}
		if (np == URB_SIZE / UNIT + 1) {
			dvb_dmx_swfilter_packets(&d->demux, d->pkts, np);
			np = 0;
		}
	}
	if (i < len) {
		d->carry_len = len - i;
		memcpy(d->carry, buf + i, d->carry_len);
	}
	if (np)
		dvb_dmx_swfilter_packets(&d->demux, d->pkts, np);
}

static void urb_complete(struct urb *urb)
{
	struct hdtvmate *d = urb->context;
	unsigned long flags;

	switch (urb->status) {
	case 0:
	case -ETIMEDOUT:
		break;
	case -ECONNRESET:
	case -ENOENT:
	case -ESHUTDOWN:
		return;
	default:
		dev_dbg(&d->intf->dev, "urb status %d\n", urb->status);
		break;
	}
	if (urb->actual_length && d->streaming) {
		spin_lock_irqsave(&d->ts_lock, flags);
		ts_feed(d, urb->transfer_buffer, urb->actual_length);
		spin_unlock_irqrestore(&d->ts_lock, flags);
	}
	if (d->streaming && !d->disconnected)
		usb_submit_urb(urb, GFP_ATOMIC);
}

static int stream_start(struct hdtvmate *d)
{
	int i, ret;

	d->carry_len = 0;
	d->streaming = true;
	for (i = 0; i < URB_COUNT; i++) {
		ret = usb_submit_urb(d->urbs[i], GFP_KERNEL);
		if (ret) {
			d->streaming = false;
			while (--i >= 0)
				usb_kill_urb(d->urbs[i]);
			return ret;
		}
	}
	return 0;
}

static void stream_stop(struct hdtvmate *d)
{
	int i;

	d->streaming = false;
	for (i = 0; i < URB_COUNT; i++)
		usb_kill_urb(d->urbs[i]);
}

static int start_feed(struct dvb_demux_feed *feed)
{
	struct hdtvmate *d = feed->demux->priv;

	int ret;

	if (d->feeds++ == 0) {
		ret = stream_start(d);
		if (ret)
			d->feeds--;
		return ret;
	}
	return 0;
}

static int stop_feed(struct dvb_demux_feed *feed)
{
	struct hdtvmate *d = feed->demux->priv;

	if (--d->feeds == 0)
		stream_stop(d);
	return 0;
}

/* ---------------- USB probe / disconnect ---------------- */

static void free_urbs(struct hdtvmate *d)
{
	int i;

	for (i = 0; i < URB_COUNT; i++) {
		if (d->urbs[i]) {
			usb_free_coherent(d->udev, URB_SIZE, d->urb_buf[i], d->urbs[i]->transfer_dma);
			usb_free_urb(d->urbs[i]);
		}
	}
}

static int alloc_urbs(struct hdtvmate *d)
{
	int i;

	for (i = 0; i < URB_COUNT; i++) {
		struct urb *u = usb_alloc_urb(0, GFP_KERNEL);

		if (!u)
			return -ENOMEM;
		d->urb_buf[i] = usb_alloc_coherent(d->udev, URB_SIZE, GFP_KERNEL, &u->transfer_dma);
		if (!d->urb_buf[i]) {
			usb_free_urb(u);
			return -ENOMEM;
		}
		usb_fill_bulk_urb(u, d->udev, usb_rcvbulkpipe(d->udev, EP_TS), d->urb_buf[i],
				  URB_SIZE, urb_complete, d);
		u->transfer_flags |= URB_NO_TRANSFER_DMA_MAP;
		d->urbs[i] = u;
	}
	return 0;
}

static int hdtvmate_probe(struct usb_interface *intf, const struct usb_device_id *id)
{
	struct hdtvmate *d;
	int ret;

	if (intf->cur_altsetting->desc.bInterfaceNumber != 0)
		return -ENODEV;
	d = kzalloc(sizeof(*d), GFP_KERNEL);
	if (!d)
		return -ENOMEM;
	d->udev = interface_to_usbdev(intf);
	d->intf = intf;
	mutex_init(&d->lock);
	mutex_init(&d->op_lock);
	spin_lock_init(&d->ts_lock);
	d->cmdbuf = kmalloc(512, GFP_KERNEL);
	d->pkts = kmalloc(188 * (URB_SIZE / UNIT + 2), GFP_KERNEL);
	if (!d->cmdbuf || !d->pkts) {
		ret = -ENOMEM;
		goto err_free;
	}
	ret = request_firmware(&d->fw, FW_NAME, &intf->dev);
	if (ret) {
		dev_err(&intf->dev, "needs /lib/firmware/%s (build it with mkblob.py from your own capture)\n",
			FW_NAME);
		goto err_free;
	}
	ret = parse_blob(d);
	if (ret) {
		dev_err(&intf->dev, "%s is not a valid sequence file\n", FW_NAME);
		goto err_fw;
	}
	ret = hw_init(d);
	if (ret) {
		dev_err(&intf->dev, "hardware init failed: %d\n", ret);
		goto err_fw;
	}
	ret = alloc_urbs(d);
	if (ret)
		goto err_urbs;

	ret = dvb_register_adapter(&d->adapter, "HDTV Mate", THIS_MODULE, &intf->dev, adapter_nr);
	if (ret < 0)
		goto err_urbs;
	d->adapter.priv = d;

	d->demux.dmx.capabilities = DMX_TS_FILTERING | DMX_SECTION_FILTERING;
	d->demux.priv = d;
	d->demux.filternum = 256;
	d->demux.feednum = 256;
	d->demux.start_feed = start_feed;
	d->demux.stop_feed = stop_feed;
	ret = dvb_dmx_init(&d->demux);
	if (ret)
		goto err_adapter;
	d->dmxdev.filternum = 256;
	d->dmxdev.demux = &d->demux.dmx;
	ret = dvb_dmxdev_init(&d->dmxdev, &d->adapter);
	if (ret)
		goto err_dmx;
	d->fe_hw.source = DMX_FRONTEND_0;
	d->fe_mem.source = DMX_MEMORY_FE;
	d->demux.dmx.add_frontend(&d->demux.dmx, &d->fe_hw);
	d->demux.dmx.add_frontend(&d->demux.dmx, &d->fe_mem);
	d->demux.dmx.connect_frontend(&d->demux.dmx, &d->fe_hw);

	memcpy(&d->fe.ops, &hdtvmate_fe_ops, sizeof(d->fe.ops));
	d->fe.demodulator_priv = d;
	/*
	 * dvb_frontend_detach() drops a module reference for ops.release (it expects
	 * frontends obtained through dvb_attach()), so take that reference here.
	 * Consequence: unbind/unplug the stick before rmmod.
	 */
	__module_get(THIS_MODULE);
	ret = dvb_register_frontend(&d->adapter, &d->fe);
	if (ret) {
		module_put(THIS_MODULE);
		goto err_dmxdev;
	}

	usb_set_intfdata(intf, d);
	dev_info(&intf->dev, "HDTV Mate ready as DVB adapter %d\n", d->adapter.num);
	return 0;

err_dmxdev:
	dvb_dmxdev_release(&d->dmxdev);
err_dmx:
	dvb_dmx_release(&d->demux);
err_adapter:
	dvb_unregister_adapter(&d->adapter);
err_urbs:
	free_urbs(d);
err_fw:
	release_firmware(d->fw);
err_free:
	kfree(d->pkts);
	kfree(d->cmdbuf);
	kfree(d);
	return ret;
}

static void hdtvmate_disconnect(struct usb_interface *intf)
{
	struct hdtvmate *d = usb_get_intfdata(intf);

	if (!d)
		return;
	d->disconnected = true;
	stream_stop(d);
	dvb_unregister_frontend(&d->fe);
	d->demux.dmx.close(&d->demux.dmx);
	d->demux.dmx.remove_frontend(&d->demux.dmx, &d->fe_mem);
	d->demux.dmx.remove_frontend(&d->demux.dmx, &d->fe_hw);
	dvb_dmxdev_release(&d->dmxdev);
	dvb_dmx_release(&d->demux);
	dvb_unregister_adapter(&d->adapter);
	free_urbs(d);
	release_firmware(d->fw);
	usb_set_intfdata(intf, NULL);
	dev_info(&intf->dev, "HDTV Mate disconnected\n");
	dvb_frontend_detach(&d->fe);	/* frees d via fe_release, now or when last user closes */
}

static const struct usb_device_id hdtvmate_ids[] = {
	{ USB_DEVICE(0x23e2, 0x2b02) },
	{ }
};
MODULE_DEVICE_TABLE(usb, hdtvmate_ids);

static struct usb_driver hdtvmate_driver = {
	.name = "hdtvmate",
	.probe = hdtvmate_probe,
	.disconnect = hdtvmate_disconnect,
	.id_table = hdtvmate_ids,
	.disable_hub_initiated_lpm = 1,
};
module_usb_driver(hdtvmate_driver);

MODULE_DESCRIPTION("HDTV Mate (IT9306 + CXD6801) ATSC 1.0 USB tuner");
MODULE_LICENSE("GPL");
MODULE_FIRMWARE(FW_NAME);
