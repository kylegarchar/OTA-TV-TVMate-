/* libhdtvmate: portable user-space driver for the HDTV Mate USB tuner (23e2:2b02), ATSC 1.0.
 * Builds on Linux, macOS, Windows (WinUSB) and Android (libusb + UsbDeviceConnection fd).
 * Vendor firmware/setup data are not included: pass the path of hdtvmate.bin built by
 * mkblob.py from your own capture. */
#ifndef HDTVMATE_H
#define HDTVMATE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct hdtvmate hdtvmate;

typedef struct {
	int locked;		/* 1 when the 8VSB demod has lock */
	int strength;		/* 0..100 (RF level from the tuner) */
	uint32_t errors;	/* demod error counter (raw, small when clean) */
} hdtvmate_status;

/* Open the first tuner found (android_fd < 0), or wrap an Android USB fd.
 * blob: path to hdtvmate.bin (NULL tries $HDTVMATE_BLOB, ./hdtvmate.bin, /lib/firmware/hdtvmate.bin).
 * Loads bridge firmware / initialises chips when needed. Returns 0 or a negative error. */
int hdtvmate_open(hdtvmate **out, const char *blob, int android_fd);
void hdtvmate_close(hdtvmate *d);

/* Tune to a centre frequency in kHz; waits up to 1.5 s for lock. Returns 1 locked, 0 not, <0 error. */
int hdtvmate_tune(hdtvmate *d, uint32_t khz);
int hdtvmate_get_status(hdtvmate *d, hdtvmate_status *st);

/* Read whole 188-byte TS packets into buf (cap >= 188). Returns bytes (multiple of 188),
 * 0 on timeout, <0 on error. */
int hdtvmate_read_ts(hdtvmate *d, uint8_t *buf, int cap, int timeout_ms);

/* US broadcast RF channel (2..36) -> kHz, 0 if invalid. */
uint32_t hdtvmate_channel_khz(int rf);
const char *hdtvmate_strerror(int err);

#ifdef __cplusplus
}
#endif
#endif
