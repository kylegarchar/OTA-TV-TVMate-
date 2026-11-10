# OTA TV — drivers and app for the TVMate tuner stick

Independent, from-scratch drivers for the **GTMEDIA / Zenview "HDTV Mate"** USB ATSC tuner
(USB `23e2:2b02`: ITE IT9306 USB bridge + Sony CXD6801 demodulator + Sony tuner), plus
**OTA TV**, a live-TV app for Android TV.

ATSC 1.0 (8VSB) is fully supported: VHF + UHF (RF 2–36), lock/signal, PSIP channel names,
1080i / 720p / SD MPEG-2 with AC-3 audio. Live playback only — no DVR, no network sharing.

| Part | Folder | Status |
|---|---|---|
| **OTA TV** Android TV app (pure Java USB driver, direct hardware decode, low latency) | [`android/`](android) | working on Onn 4K Pro (Android 14) |
| Linux DVB kernel driver (Tvheadend, Kodi, VLC, `dvbv5-*`) + DKMS installer | [`linux/`](linux) | working on Raspberry Pi 4/5 (kernel 6.18) |
| Portable C library + `hdtvmate-cli` (libusb: Linux, macOS, Windows, Android) | [`libhdtvmate/`](libhdtvmate), [`windows/`](windows) | Linux tested; Windows builds, untested |
| Python reference driver, scanner and ATSC 3.0 experiments | [`python/`](python) | reference / research |
| Capture & reverse-engineering tools | [`capture/`](capture) | research |

Protocol notes: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Firmware and chip setup data are not included

The bridge firmware and the chip setup sequences belong to the tuner's vendor and are **not in
this repository**. Each owner captures them from their own tuner and the vendor's own app
(see [`capture/`](capture)); `linux/mkblob.py` then packs them into `hdtvmate.bin`, which every
driver here loads at run time. Keep that file for your own use — don't publish it.

Local layout (git-ignored): `private/` holds `it9300-fw-replay.txt`, `init-core.txt`,
`tune-core.txt` (and `hdtvmate.bin` once built).

    python3 linux/mkblob.py private private/hdtvmate.bin

## Quick start

**Linux kernel driver** (Raspberry Pi OS / Debian):

    sudo sh linux/install.sh private/hdtvmate.bin   # DKMS build + firmware + udev rule
    dvbv5-scan -o channels.conf linux/us-atsc-rf2-36.conf
    dvbv5-zap -c channels.conf -r "WLS-HD"           # or use Tvheadend / Kodi / VLC

**CLI** (any OS with libusb):

    make -C libhdtvmate
    libhdtvmate/hdtvmate-cli scan
    libhdtvmate/hdtvmate-cli tune rf22 -o - | mpv -

**OTA TV app** (Android TV): build with `android/build.sh` (no Gradle; needs `android.jar` and
`r8.jar` in `$ANDROID_SDK_DIR`), install, plug the tuner in, pick OTA TV. Put `hdtvmate.bin` in
`PRIVATE-firmware/` on a card in the tuner's built-in card reader (or in the app's files folder);
the app copies it on first run.

## How OTA TV keeps latency low

No HTTP server and no MediaPlayer: the app reads the tuner over USB with 8 queued requests,
demuxes the chosen subchannel in memory and feeds the hardware MPEG-2 / AC-3 decoders through
`MediaCodec`. Audio plays as soon as it is decoded and is the master clock (always kept within
0.2 s of arrival, so it never drifts behind live); video frames are shown at their timestamps
against it and late frames are dropped. Measured on a 1080i station: ~0.85 s from a video frame
arriving to it being on screen, of which ~0.6 s is the broadcaster's own video-ahead-of-audio
multiplexing that any in-sync receiver has to wait out. First picture ~0.65 s after a channel's
data starts (plus ~0.7 s to tune).
