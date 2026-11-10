HDTV Mate drivers (GTMEDIA/Zenview ATSC USB tuner, 23e2:2b02) - ATSC 1.0
=========================================================================

linux/kernel-driver/   Linux DVB driver. Works with Tvheadend, Kodi, VLC, dvbv5 tools.
                       Install:  sudo sh install.sh
                       Needs the kernel headers for your kernel (apt install linux-headers-$(uname -r)
                       or raspberrypi-kernel-headers) and DKMS (installed automatically).
linux/arm64/           hdtvmate-cli for 64-bit Raspberry Pi OS / arm64 Linux (needs libusb-1.0-0).
linux/us-atsc-rf2-36.conf   frequency table for:  dvbv5-scan -o channels.conf us-atsc-rf2-36.conf
windows/x64/           hdtvmate-cli.exe (one file). First assign the WinUSB driver to
                       "HDTV Mate" (23e2:2b02) with Zadig (zadig.akeo.ie). Untested on Windows.
android/               ota-tv.apk - "OTA TV" app for Android TV / the Onn (live TV with this tuner).
source/libhdtvmate/    portable C library + CLI source (Linux, macOS, Windows, Android).
tools/                 Python driver/tools and mkblob.py.

hdtvmate-cli usage
  hdtvmate-cli scan                        channels with names (RF 2-36, VHF + UHF)
  hdtvmate-cli tune rf22 -o - | mpv -      watch (any player that reads MPEG-TS from stdin)
  hdtvmate-cli tune rf22 -o rec.ts -t 60   record 60 s
  Put hdtvmate.bin next to the program, or use  -b path/to/hdtvmate.bin

PRIVATE-firmware/hdtvmate.bin
  Tuner firmware and setup data, redistributed with the manufacturer's permission.
