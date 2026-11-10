#!/bin/sh
# Build HDTV Mate TV without Gradle. ./build.sh install  also installs it on the Onn.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_SDK_DIR:?set ANDROID_SDK_DIR (contains android-34/android.jar and r8.jar)}
JAR=$SDK/android-34/android.jar
rm -rf build && mkdir -p build/classes build/dex build/gen
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$JAR"
javac --release 11 -Xlint:-options -Xlint:deprecation -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
java -cp $SDK/r8.jar com.android.tools.r8.D8 --release --min-api 28 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
mkdir -p build/assets && cp ../firmware/hdtvmate.bin build/assets/
aapt package -f -M AndroidManifest.xml -S res -A build/assets -I "$JAR" -F build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
[ -f hdtvmate.keystore ] || keytool -genkeypair -keystore hdtvmate.keystore -storepass hdtvmate -keypass hdtvmate \
    -alias hdtvmate -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OTA TV" >/dev/null 2>&1
apksigner sign --ks hdtvmate.keystore --ks-pass pass:hdtvmate --key-pass pass:hdtvmate --out build/hdtvmate.apk build/aligned.apk
echo "built build/hdtvmate.apk"
[ "$1" = install ] && adb -s ${ONN:?set ONN=<onn-ip>:5555} install -r build/hdtvmate.apk
true
