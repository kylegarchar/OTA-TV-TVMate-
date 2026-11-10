#!/bin/sh
# Copy current builds onto an SD card / USB stick (default: the HDTVMATE card mounted on the Pi).
# Private firmware goes in PRIVATE-firmware/ on the card: for your own use, do not share.
set -e
R=$(cd "$(dirname "$0")/.." && pwd); P=$R/private; C=${1:-/media/$USER/HDTVMATE}
mountpoint -q "$C" || { echo "card not mounted at $C"; exit 1; }
mkdir -p "$C/linux/kernel-driver" "$C/linux/arm64" "$C/windows/x64" "$C/source" "$C/tools" "$C/PRIVATE-firmware" "$C/android"
cp $R/linux/hdtvmate.c $R/linux/Makefile $R/linux/dkms.conf $R/linux/install.sh $R/linux/60-hdtvmate.rules "$C/linux/kernel-driver/"
cp $R/linux/us-atsc-rf2-36.conf "$C/linux/"
[ -f $R/libhdtvmate/hdtvmate-cli ] && cp $R/libhdtvmate/hdtvmate-cli "$C/linux/arm64/"
[ -f $R/windows/build/hdtvmate-cli.exe ] && cp $R/windows/build/hdtvmate-cli.exe "$C/windows/x64/"
rm -rf "$C/source/libhdtvmate" && cp -r $R/libhdtvmate "$C/source/" && rm -f "$C/source/libhdtvmate/"*.o "$C/source/libhdtvmate/"*.a "$C/source/libhdtvmate/hdtvmate-cli"
cp $R/linux/mkblob.py $R/python/*.py "$C/tools/"
[ -f $P/hdtvmate.bin ] && cp $P/hdtvmate.bin "$C/PRIVATE-firmware/"
[ -f $R/android/build/hdtvmate.apk ] && { rm -f "$C/android/hdtvmate.apk"; cp $R/android/build/hdtvmate.apk "$C/android/ota-tv.apk"; }
cp $R/tools/card-README.txt "$C/README.txt"
sync
echo "card updated: $(du -sh "$C" | cut -f1)"
