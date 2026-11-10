/* libhdtvmate - see hdtvmate.h. IT930x command framing follows px4_drv's documentation of the bridge. */
#include "hdtvmate.h"
#include <libusb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
static void msleep(unsigned ms) { Sleep(ms); }
static double now_s(void) { return GetTickCount64() / 1000.0; }
#else
#include <time.h>
static void msleep(unsigned ms)
{
	struct timespec ts = { ms / 1000, (long)(ms % 1000) * 1000000L };
	nanosleep(&ts, NULL);
}
static double now_s(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec + ts.tv_nsec / 1e9;
}
#endif

#define VID 0x23e2
#define PID 0x2b02
#define EP_CMD_OUT 0x02
#define EP_CMD_IN 0x81
#define EP_TS 0x84
#define UNIT 192
#define READ_SIZE (UNIT * 2048)	/* 393216 = 768 * 512 */
#define NXFER 8
#define XFER_SIZE (UNIT * 256)		/* 49152 = 96 * 512 */
#define FIFO_SIZE (188 * 40000)		/* ~3 s of a 19.4 Mbit/s multiplex */

#define CMD_REG_WRITE 0x0001
#define CMD_QUERYINFO 0x0022
#define CMD_I2C_READ 0x002a
#define CMD_I2C_WRITE 0x002b
#define BUS 3
#define DEMOD 0xd8
#define GATE 0xdc
#define TUNER 0xc0
#define VSB 0x30

enum { E_NODEV = -1, E_USB = -2, E_BLOB = -3, E_PROTO = -4, E_NAK = -5, E_NOMEM = -6 };

struct seq { const uint8_t *p; size_t n; };
typedef struct hdtvmate hdtvmate_t;
static void stream_stop(hdtvmate_t *d);

struct hdtvmate {
	libusb_context *ctx;
	libusb_device_handle *h;
	uint8_t seqno;
	uint8_t *blob;
	struct seq fw, init, tune;
	uint8_t *rbuf;		/* scratch for draining */
	uint8_t carry[UNIT];
	int carry_len;
	/* async streaming: NXFER queued bulk transfers feed a FIFO of stripped 188-byte packets */
	struct libusb_transfer *xfer[NXFER];
	uint8_t *xbuf[NXFER];
	int active, streaming, usb_err;
	uint8_t *fifo;
	size_t f_head, f_len;	/* ring buffer of FIFO_SIZE bytes */
	unsigned long long overflow;
};

const char *hdtvmate_strerror(int e)
{
	switch (e) {
	case E_NODEV: return "tuner not found";
	case E_USB: return "USB transfer failed";
	case E_BLOB: return "hdtvmate.bin missing or invalid (build it with mkblob.py)";
	case E_PROTO: return "bad reply from bridge";
	case E_NAK: return "command rejected by bridge";
	case E_NOMEM: return "out of memory";
	default: return "error";
	}
}

/* ~(16-bit wraparound sum of big-endian words) */
static uint16_t csum16(const uint8_t *b, int n)
{
	uint16_t s = 0;
	int i;
	for (i = 0; i + 1 < n; i += 2)
		s += (uint16_t)(b[i] << 8 | b[i + 1]);
	if (n & 1)
		s += (uint16_t)(b[n - 1] << 8);
	return (uint16_t)~s;
}

static int cmd(hdtvmate *d, uint16_t code, const uint8_t *pl, int plen, uint8_t *out, int olen)
{
	uint8_t b[512];
	int act, r;
	uint16_t c;

	if (plen > 250)
		return E_PROTO;
	d->seqno++;
	b[0] = (uint8_t)(plen + 5);
	b[1] = code >> 8;
	b[2] = code & 0xff;
	b[3] = d->seqno;
	memcpy(b + 4, pl, plen);
	c = csum16(b + 1, plen + 3);
	b[plen + 4] = c >> 8;
	b[plen + 5] = c & 0xff;
	r = libusb_bulk_transfer(d->h, EP_CMD_OUT, b, plen + 6, &act, 3000);
	if (r)
		return E_USB;
	r = libusb_bulk_transfer(d->h, EP_CMD_IN, b, sizeof(b), &act, 3000);
	if (r)
		return E_USB;
	if (act < 5 || b[1] != d->seqno || csum16(b + 1, act - 3) != (b[act - 2] << 8 | b[act - 1]))
		return E_PROTO;
	if (b[2])
		return E_NAK;
	if (out && olen)
		memcpy(out, b + 3, olen < act - 5 ? olen : act - 5);
	return 0;
}

static int reg_write(hdtvmate *d, uint16_t addr, uint8_t v)
{
	uint8_t pl[7] = { 1, 2, 0, 0, addr >> 8, addr & 0xff, v };
	return cmd(d, CMD_REG_WRITE, pl, 7, NULL, 0);
}

static int i2c_write(hdtvmate *d, uint8_t addr, const uint8_t *data, int n)
{
	uint8_t pl[64];
	if (n > 60)
		return E_PROTO;
	pl[0] = (uint8_t)n;
	pl[1] = BUS;
	pl[2] = addr;
	memcpy(pl + 3, data, n);
	return cmd(d, CMD_I2C_WRITE, pl, n + 3, NULL, 0);
}

static int i2c_read(hdtvmate *d, uint8_t addr, uint8_t reg, uint8_t *out, int n)
{
	uint8_t pl[3] = { (uint8_t)n, BUS, addr };
	int r = reg_write(d, 0xf424, 1);
	if (!r) r = i2c_write(d, addr, &reg, 1);
	if (!r) r = reg_write(d, 0xf424, 0);
	if (!r) r = cmd(d, CMD_I2C_READ, pl, 3, out, n);
	return r;
}

static int bank_read(hdtvmate *d, uint8_t addr, uint8_t bank, uint8_t reg, uint8_t *out, int n)
{
	uint8_t sel[2] = { 0, bank };
	int r = i2c_write(d, addr, sel, 2);
	return r ? r : i2c_read(d, addr, reg, out, n);
}

/* ---- captured sequences (hdtvmate.bin) ---- */

static int load_blob(hdtvmate *d, const char *path)
{
	const char *cands[4] = { path, getenv("HDTVMATE_BLOB"), "hdtvmate.bin", "/lib/firmware/hdtvmate.bin" };
	FILE *f = NULL;
	long n;
	size_t o;
	int i;

	for (i = 0; i < 4 && !f; i++)
		if (cands[i])
			f = fopen(cands[i], "rb");
	if (!f)
		return E_BLOB;
	fseek(f, 0, SEEK_END);
	n = ftell(f);
	fseek(f, 0, SEEK_SET);
	d->blob = malloc(n > 0 ? n : 1);
	if (!d->blob || n < 8 || fread(d->blob, 1, n, f) != (size_t)n || memcmp(d->blob, "HDTVMAT1", 8)) {
		fclose(f);
		return E_BLOB;
	}
	fclose(f);
	for (o = 8; o + 5 <= (size_t)n;) {
		uint8_t id = d->blob[o];
		uint32_t len = d->blob[o + 1] | d->blob[o + 2] << 8 | d->blob[o + 3] << 16 | (uint32_t)d->blob[o + 4] << 24;
		struct seq s = { d->blob + o + 5, len };
		if (o + 5 + len > (size_t)n)
			return E_BLOB;
		if (id == 1) d->fw = s;
		else if (id == 2) d->init = s;
		else if (id == 3) d->tune = s;
		o += 5 + len;
	}
	return d->fw.n && d->init.n && d->tune.n ? 0 : E_BLOB;
}

/* Replay records [u16 delay][u16 cmd][u8 len][payload]; patch tuner reg 0x68 frequency if khz. */
static int run_seq(hdtvmate *d, const struct seq *s, uint32_t khz, int strict)
{
	size_t o = 0;
	int errs = 0;

	while (o + 5 <= s->n) {
		uint16_t delay = s->p[o] | s->p[o + 1] << 8, code = s->p[o + 2] | s->p[o + 3] << 8;
		uint8_t n = s->p[o + 4], pl[256];
		int r;

		if (o + 5 + n > s->n)
			return E_BLOB;
		memcpy(pl, s->p + o + 5, n);
		o += 5 + n;
		if (khz && code == CMD_I2C_WRITE && n > 14 && pl[2] == TUNER && pl[3] == 0x68) {
			pl[12] = khz & 0xff;
			pl[13] = (khz >> 8) & 0xff;
			pl[14] = (khz >> 16) & 0xff;
		}
		if (delay)
			msleep(delay);
		r = cmd(d, code, pl, n, NULL, 0);
		if (r == E_USB)
			return r;
		if (r) {
			if (strict)
				return r;
			errs++;
		}
	}
	return errs;
}

static int hw_init(hdtvmate *d)
{
	uint8_t q = 1, v[4] = { 0 }, sel[2] = { 0, 0 };
	int r = cmd(d, CMD_QUERYINFO, &q, 1, v, 4);

	if (r)
		return r;
	if (!(v[0] | v[1] | v[2] | v[3])) {
		r = run_seq(d, &d->fw, 0, 1);
		if (r < 0)
			return r;
		msleep(50);
	} else if (i2c_write(d, DEMOD, sel, 2) == 0) {
		return 0;		/* already running */
	}
	r = run_seq(d, &d->init, 0, 0);
	return r < 0 ? r : 0;
}

int hdtvmate_open(hdtvmate **out, const char *blob, int android_fd)
{
	hdtvmate *d = calloc(1, sizeof(*d));
	int r;

	*out = NULL;
	if (!d)
		return E_NOMEM;
	r = load_blob(d, blob);
	if (r)
		goto fail;
	if (android_fd >= 0) {
#ifdef LIBUSB_OPTION_NO_DEVICE_DISCOVERY
		libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
#endif
	}
	if (libusb_init(&d->ctx)) {
		r = E_USB;
		goto fail;
	}
	if (android_fd >= 0) {
		if (libusb_wrap_sys_device(d->ctx, (intptr_t)android_fd, &d->h))
			d->h = NULL;
	} else {
		d->h = libusb_open_device_with_vid_pid(d->ctx, VID, PID);
	}
	if (!d->h) {
		r = E_NODEV;
		goto fail;
	}
	libusb_set_auto_detach_kernel_driver(d->h, 1);	/* Linux: borrow from the hdtvmate kernel driver */
	if (libusb_claim_interface(d->h, 0)) {
		r = E_USB;
		goto fail;
	}
	d->rbuf = malloc(READ_SIZE);
	d->fifo = malloc(FIFO_SIZE);
	if (!d->rbuf || !d->fifo) {
		r = E_NOMEM;
		goto fail;
	}
	r = hw_init(d);
	if (r)
		goto fail;
	*out = d;
	return 0;
fail:
	hdtvmate_close(d);
	return r;
}

void hdtvmate_close(hdtvmate *d)
{
	int i;

	if (!d)
		return;
	if (d->h)
		stream_stop(d);
	if (getenv("HDTVMATE_DEBUG"))
		fprintf(stderr, "libhdtvmate: %llu packets dropped by FIFO overflow\n", d->overflow);
	for (i = 0; i < NXFER; i++) {
		if (d->xfer[i])
			libusb_free_transfer(d->xfer[i]);
		free(d->xbuf[i]);
	}
	if (d->h) {
		libusb_release_interface(d->h, 0);
		libusb_close(d->h);
	}
	if (d->ctx)
		libusb_exit(d->ctx);
	free(d->blob);
	free(d->rbuf);
	free(d->fifo);
	free(d);
}

/* ---- tuning / status ---- */

static int locked(hdtvmate *d)
{
	uint8_t v = 0;
	int r = bank_read(d, VSB, 0x0f, 0x11, &v, 1);
	return r ? r : v == 1;
}

static void drain(hdtvmate *d, int ms)
{
	double end = now_s() + ms / 1000.0;
	int act;
	while (now_s() < end)
		if (libusb_bulk_transfer(d->h, EP_TS, d->rbuf, READ_SIZE, &act, 20) == LIBUSB_ERROR_TIMEOUT && act == 0)
			break;
}

int hdtvmate_tune(hdtvmate *d, uint32_t khz)
{
	double end;
	int r, lk = 0;

	stream_stop(d);		/* restarted by the next hdtvmate_read_ts() */
	if ((r = reg_write(d, 0xd8e4, 1)) || (r = reg_write(d, 0xd8e5, 1)) || (r = reg_write(d, 0xd8e3, 1)))
		return r;
	r = run_seq(d, &d->tune, khz, 0);
	if (r < 0)
		return r;
	for (end = now_s() + 1.5; now_s() < end;) {
		lk = locked(d);
		if (lk < 0)
			return lk;
		if (lk)
			break;
		msleep(30);
	}
	/* drop TS still queued from the previous channel */
	reg_write(d, 0xda1d, 1);
	drain(d, 60);
	reg_write(d, 0xda1d, 0);
	d->carry_len = 0;
	d->f_head = d->f_len = 0;
	return lk;
}

int hdtvmate_get_status(hdtvmate *d, hdtvmate_status *st)
{
	uint8_t w[3], lvl = 0, e[3] = { 0 };
	int r, lk = locked(d);

	if (lk < 0)
		return lk;
	w[0] = 0x08; w[1] = 1;
	r = i2c_write(d, GATE, w, 2);
	w[0] = 0x87; w[1] = 0xc4; w[2] = 0x41;
	if (!r) r = i2c_write(d, TUNER, w, 3);
	w[0] = 0x59; w[1] = 0x05; w[2] = 0x01;
	if (!r) r = i2c_write(d, TUNER, w, 3);
	if (!r) r = i2c_read(d, TUNER, 0x5b, &lvl, 1);
	w[0] = 0x59; w[1] = 0x04; i2c_write(d, TUNER, w, 2);
	w[0] = 0x88; w[1] = 0x00; i2c_write(d, TUNER, w, 2);
	w[0] = 0x87; w[1] = 0xc0; i2c_write(d, TUNER, w, 2);
	w[0] = 0x08; w[1] = 0; i2c_write(d, GATE, w, 2);
	if (r)
		return r;
	bank_read(d, VSB, 0x0d, 0x70, e, 3);
	st->locked = lk;
	st->strength = lvl <= 0x33 ? 0 : lvl >= 0x96 ? 100 : (lvl - 0x33) * 100 / (0x96 - 0x33);
	st->errors = e[0] | e[1] << 8 | (uint32_t)e[2] << 16;
	return 0;
}

/* ---- TS (async) ---- */

static void fifo_put(hdtvmate *d, const uint8_t *p)
{
	size_t tail;
	if (d->f_len + 188 > FIFO_SIZE) {	/* reader too slow: drop oldest */
		d->f_head = (d->f_head + 188) % FIFO_SIZE;
		d->f_len -= 188;
		d->overflow++;
	}
	tail = (d->f_head + d->f_len) % FIFO_SIZE;
	memcpy(d->fifo + tail, p, 188);	/* FIFO_SIZE is a multiple of 188: no wrap inside a packet */
	d->f_len += 188;
}

/* strip 4-byte tags; resync on loss; a unit split across transfers goes through carry */
static void feed(hdtvmate *d, const uint8_t *b, int len)
{
	int i = 0;
	if (d->carry_len) {
		int need = UNIT - d->carry_len;
		if (len < need) {
			memcpy(d->carry + d->carry_len, b, len);
			d->carry_len += len;
			return;
		}
		memcpy(d->carry + d->carry_len, b, need);
		if (d->carry[4] == 0x47)
			fifo_put(d, d->carry + 4);
		i = need;
		d->carry_len = 0;
	}
	while (i + UNIT <= len) {
		if (b[i + 4] == 0x47) {
			fifo_put(d, b + i + 4);
			i += UNIT;
		} else {
			i++;
		}
	}
	if (i < len) {
		d->carry_len = len - i;
		memcpy(d->carry, b + i, d->carry_len);
	}
}

static void LIBUSB_CALL xfer_cb(struct libusb_transfer *t)
{
	hdtvmate *d = t->user_data;
	if (t->status == LIBUSB_TRANSFER_COMPLETED || t->status == LIBUSB_TRANSFER_TIMED_OUT) {
		if (t->actual_length)
			feed(d, t->buffer, t->actual_length);
	} else if (t->status != LIBUSB_TRANSFER_CANCELLED) {
		d->usb_err = 1;
	}
	if (d->streaming && !d->usb_err && libusb_submit_transfer(t) == 0)
		return;
	d->active--;
}

static int stream_start(hdtvmate *d)
{
	int i;
	d->carry_len = 0;
	d->f_head = d->f_len = 0;
	d->usb_err = 0;
	d->streaming = 1;
	for (i = 0; i < NXFER; i++) {
		if (!d->xfer[i]) {
			d->xfer[i] = libusb_alloc_transfer(0);
			d->xbuf[i] = malloc(XFER_SIZE);
			if (!d->xfer[i] || !d->xbuf[i])
				return E_NOMEM;
		}
		libusb_fill_bulk_transfer(d->xfer[i], d->h, EP_TS, d->xbuf[i], XFER_SIZE, xfer_cb, d, 0);
		if (libusb_submit_transfer(d->xfer[i]))
			return E_USB;
		d->active++;
	}
	return 0;
}

static void stream_stop(hdtvmate *d)
{
	int i;
	if (!d->streaming && !d->active)
		return;
	d->streaming = 0;
	for (i = 0; i < NXFER; i++)
		if (d->xfer[i])
			libusb_cancel_transfer(d->xfer[i]);
	while (d->active > 0) {
		struct timeval tv = { 0, 100000 };
		if (libusb_handle_events_timeout(d->ctx, &tv) < 0)
			break;
	}
	d->active = 0;
}

int hdtvmate_read_ts(hdtvmate *d, uint8_t *buf, int cap, int timeout_ms)
{
	size_t n, first;
	double end = now_s() + timeout_ms / 1000.0;

	if (!d->streaming) {
		int r = stream_start(d);
		if (r) {
			stream_stop(d);
			return r;
		}
	}
	while (d->f_len == 0) {
		struct timeval tv = { 0, 50000 };
		if (d->usb_err)
			return E_USB;
		if (now_s() >= end)
			return 0;
		libusb_handle_events_timeout(d->ctx, &tv);
	}
	n = d->f_len < (size_t)(cap / 188) * 188 ? d->f_len : (size_t)(cap / 188) * 188;
	first = FIFO_SIZE - d->f_head < n ? FIFO_SIZE - d->f_head : n;
	memcpy(buf, d->fifo + d->f_head, first);
	memcpy(buf + first, d->fifo, n - first);
	d->f_head = (d->f_head + n) % FIFO_SIZE;
	d->f_len -= n;
	return (int)n;
}

uint32_t hdtvmate_channel_khz(int rf)
{
	if (rf >= 2 && rf <= 4) return 57000 + 6000 * (rf - 2);
	if (rf >= 5 && rf <= 6) return 79000 + 6000 * (rf - 5);
	if (rf >= 7 && rf <= 13) return 177000 + 6000 * (rf - 7);
	if (rf >= 14 && rf <= 36) return 473000 + 6000 * (rf - 14);
	return 0;
}
