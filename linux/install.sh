#!/bin/sh
# Install the HDTV Mate kernel driver (DKMS) and its data file. Run: sudo sh install.sh [path/to/hdtvmate.bin]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
BLOB=${1:-$HERE/../private/hdtvmate.bin}
VER=0.1
[ -f "$BLOB" ] || { echo "need hdtvmate.bin (build with mkblob.py from your own capture)"; exit 1; }
command -v dkms >/dev/null || apt-get install -y dkms
install -m 644 "$BLOB" /lib/firmware/hdtvmate.bin
dkms remove hdtvmate/$VER --all 2>/dev/null || true
rm -rf /usr/src/hdtvmate-$VER && mkdir -p /usr/src/hdtvmate-$VER
cp "$HERE/hdtvmate.c" "$HERE/Makefile" "$HERE/dkms.conf" /usr/src/hdtvmate-$VER/
dkms add hdtvmate/$VER && dkms install hdtvmate/$VER
[ -f "$HERE/60-hdtvmate.rules" ] && install -m 644 "$HERE/60-hdtvmate.rules" /etc/udev/rules.d/ && udevadm control --reload
modprobe hdtvmate && echo "installed; replug the tuner if /dev/dvb/adapter* does not appear"
