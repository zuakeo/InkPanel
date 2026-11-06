#!/usr/bin/env bash
set -e
ROOT="D:/hisense_root/inkpanel"
SRC="$ROOT/src"
OUT="$ROOT/build"
JAVA="D:/hisense_root/toolchain/jdk-17.0.21+9/bin/java.exe"
JAVAC="D:/hisense_root/toolchain/jdk-17.0.21+9/bin/javac.exe"
BT="D:/hisense_root/toolchain/sdk/build-tools/36.0.0"
APKSIGNER="$BT/lib/apksigner.jar"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
AJAR="D:/hisense_root/toolchain/sdk/platforms/android-36/android.jar"
PY="C:/Users/23636/.dsh/dsh-runtimes/dsh-plugin-desktop-primary-runtime/dependencies/python/python.exe"
KS="C:/Users/23636/.android/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$ROOT/dist"

echo "[1/8] stubs"
find "$SRC/stubs" -name "*.java" > "$OUT/stub-src.txt"
"$JAVAC" -encoding UTF-8 --release 8 -nowarn -d "$OUT/stubs" @"$OUT/stub-src.txt"

echo "[2/8] compile resources"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[3/8] link (generate R.java)"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    -A "$SRC/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 30 --target-sdk-version 30 \
    --version-code 1 --version-name 0.1 \
    "$OUT/res.zip"

echo "[4/8] compile java"
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/src.txt"
"$JAVAC" -encoding UTF-8 --release 8 -nowarn -cp "$AJAR;$OUT/stubs" -d "$OUT/classes" @"$OUT/src.txt"

echo "[5/8] dex"
find "$OUT/classes" -name "*.class" > "$OUT/cls.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$AJAR" --output "$OUT/dex" @"$OUT/cls.txt"

echo "[6/8] inject dex + xposed meta"
"$PY" "$ROOT/tools/inject.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$SRC/META-INF" "$OUT/merged.apk"

echo "[7/8] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/merged.apk" "$OUT/aligned.apk"

echo "[8/8] sign"
"$JAVA" -jar "$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$ROOT/dist/InkPanel-0.1.apk" "$OUT/aligned.apk"

"$JAVA" -jar "$APKSIGNER" verify --print-certs "$ROOT/dist/InkPanel-0.1.apk" | head -4
ls -lh "$ROOT/dist/InkPanel-0.1.apk"
echo "OK"
