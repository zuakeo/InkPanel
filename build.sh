#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/build"

# 工具链路径都可以用环境变量覆盖，不写死本机目录：
#   TOOLCHAIN  JDK 与 Android SDK 的父目录（默认 ../toolchain）
#   PYTHON     跑 tools/*.py 的解释器（默认 python）
#   SIGN_STORE  签名用的调试证书（默认 ~/.android/debug.keystore）
TC="${TOOLCHAIN:-$ROOT/../toolchain}"
JAVA="$TC/jdk-17.0.21+9/bin/java.exe"
JAVAC="$TC/jdk-17.0.21+9/bin/javac.exe"
BT="$TC/sdk/build-tools/36.0.0"
APKSIGNER="$BT/lib/apksigner.jar"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
AJAR="$TC/sdk/platforms/android-36/android.jar"
PY="${PYTHON:-python}"
KS="${SIGN_STORE:-$HOME/.android/debug.keystore}"

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
    --version-code 2 --version-name 0.2 \
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
    --out "$ROOT/dist/InkPanel-0.3.apk" "$OUT/aligned.apk"

"$JAVA" -jar "$APKSIGNER" verify --print-certs "$ROOT/dist/InkPanel-0.3.apk" | head -4
ls -lh "$ROOT/dist/InkPanel-0.3.apk"
echo "OK"
