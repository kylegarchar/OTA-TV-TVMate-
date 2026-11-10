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

## Firmware

`firmware/hdtvmate.bin` holds the bridge firmware and chip setup sequences, redistributed with the
manufacturer's written permission. Every driver here loads it at run time. To rebuild it from your
own tuner instead, see [`capture/`](capture) and `linux/mkblob.py`.

## Quick start

**Linux kernel driver** (Raspberry Pi OS / Debian):

    sudo sh linux/install.sh                        # DKMS build + firmware + udev rule
    dvbv5-scan -o channels.conf linux/us-atsc-rf2-36.conf
    dvbv5-zap -c channels.conf -r "WLS-HD"           # or use Tvheadend / Kodi / VLC

**CLI** (any OS with libusb):

    make -C libhdtvmate
    libhdtvmate/hdtvmate-cli scan
    libhdtvmate/hdtvmate-cli tune rf22 -o - | mpv -

**OTA TV app** (Android TV): build with `android/build.sh` (no Gradle; needs `android.jar` and
`r8.jar` in `$ANDROID_SDK_DIR`), install, plug the tuner in, pick OTA TV. Put `firmware/hdtvmate.bin` in
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

## Picture modes

Open the channel list and press OK on **Picture** to step through the modes (the choice is saved):

| Mode | What it does |
|---|---|
| **Direct** (default) | Decoded frames go straight to the box's video layer. The Amlogic S905X5M's own pipeline does the work: motion-adaptive deinterlacing, block / mosquito noise reduction and the scaler. The app cannot adjust those; they follow the box's firmware. |
| **Clean** | Deblocking on the MPEG-2 8x8 grid plus an edge-preserving smoother for mosquito noise and ringing around edges and on-screen graphics. |
| **Sharp** | Contrast-adaptive sharpening that leaves flat areas alone and cannot overshoot (no halos). |
| **Clean + Sharp** | Both. |

The three app modes draw each frame through one GPU pass (`Enhancer.java`) at the broadcast's resolution.
The filters only look at the frame being shown, so they add no frame of delay; the frame is handed to the
GPU about 25 ms before it is due and audio stays the clock. They are tuned for HD (720p / 1080i). Trade-off:
in these modes the picture bypasses the box's video layer, so its deinterlacer and scaler are not used. If
the decoder then hands 1080i over as woven frames, the filter rebuilds combed areas from one field. If the
GPU cannot keep up it drops to a lighter 5-tap filter, and if the GPU path fails the app returns to Direct.

In every mode the app asks the display for the broadcast's exact frame rate (59.94 Hz), which removes the
repeated frame every ~17 s if *Match content frame rate* is enabled in the box's display settings, and logs
the decoder's vendor parameters (`adb logcat -s HDTVMate`).
