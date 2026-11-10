#!/bin/sh
# Cross-compile hdtvmate-cli.exe for 64-bit Windows (MinGW-w64 + static libusb).
# Needs: gcc-mingw-w64-x86-64, p7zip-full, curl.  Output: windows/build/hdtvmate-cli.exe
set -e
cd "$(dirname "$0")"
V=1.0.28
if [ ! -f libusb/include/libusb.h ]; then
    mkdir -p libusb && curl -sSL -o libusb/libusb.7z https://github.com/libusb/libusb/releases/download/v$V/libusb-$V.7z
    (cd libusb && 7z x -y libusb.7z >/dev/null)
fi
mkdir -p build
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Ilibusb/include -o build/hdtvmate-cli.exe \
    ../libhdtvmate/hdtvmate-cli.c ../libhdtvmate/hdtvmate.c libusb/MinGW64/static/libusb-1.0.a
echo "built windows/build/hdtvmate-cli.exe  (assign WinUSB to 23e2:2b02 with Zadig before use)"
