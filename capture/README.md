# Capturing your own firmware and setup sequences

These tools record what the vendor's Android app sends to **your own** tuner, so the drivers can
reproduce it. Nothing captured here belongs in git (`.gitignore` blocks it).

1. Install the vendor's HDTVPlayer app on an Android device with a Frida gadget added (re-signed APK,
   gadget in `lib/<abi>/`, loaded from the Application class), with the tuner unplugged.
2. `adb forward tcp:27042 tcp:27042`, then
   `frida -H 127.0.0.1:27042 -n Gadget -l hook.js -o capture.log`; plug the tuner in, scan, tune a channel.
3. `decode.py capture.log` summarises it; `phases.py` / `regdump.py` show the register traffic.
4. Extract the pieces into `private/`: the firmware scatter writes + BOOT as `it9300-fw-replay.txt`,
   and the write-only init / tune sequences with `mkcore.py` as `init-core.txt` / `tune-core.txt`.
5. `python3 linux/mkblob.py private private/hdtvmate.bin`.
