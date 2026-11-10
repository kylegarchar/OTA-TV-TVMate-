# libhdtvmate — HDTV Mate (23e2:2b02) ATSC 1.0, user space, any OS

Our own driver for the GTMEDIA/Zenview "HDTV Mate" (ITE IT9306 bridge + Sony CXD6801 demod).
Uses `../firmware/hdtvmate.bin` (bundled with permission): keep a copy next to the program, set
`$HDTVMATE_BLOB`, or install it in `/lib/firmware/`.

## Use
    hdtvmate-cli scan                       # RF 2-36, signal, PSIP names (~1 min)
    hdtvmate-cli tune rf22 -o - | mpv -     # stream a multiplex (MPEG-TS) to a player
    hdtvmate-cli tune 521000 -o rec.ts -t 60
    hdtvmate-cli status

API: `hdtvmate.h` (open / tune / get_status / read_ts). Streaming uses 8 queued async bulk
transfers into a 3 s FIFO, like the kernel driver (full 19.39 Mbit/s, no USB loss).

## Build
- **Linux / Raspberry Pi:** `apt install libusb-1.0-0-dev && make`. Install `60-hdtvmate.rules`
  in `/etc/udev/rules.d/` for non-root use. If the `hdtvmate` kernel driver is loaded, the
  library detaches it while running and re-attaches it on exit.
- **macOS:** `brew install libusb pkg-config && make` (no kernel driver needed).
- **Windows:** install the WinUSB driver for 23e2:2b02 (e.g. with Zadig), then build with
  MSYS2/MinGW: `pacman -S mingw-w64-x86_64-libusb mingw-w64-x86_64-pkgconf && make`.
- **Android:** build with the NDK against libusb; get the fd from
  `UsbManager.openDevice(dev).getFileDescriptor()` and call `hdtvmate_open(&d, blobPath, fd)`.

Only the Linux build has been tested on hardware so far.
