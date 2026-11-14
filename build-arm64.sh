#!/bin/bash
# 星核代理 v3.1 arm64-only 精简版打包脚本 — 由 build-nextin.sh（通用版）派生
# 只含 arm64-v8a：sing-box-arm64-v8a.gz + lib/arm64-v8a 的 hev .so
# 核心二进制与签名 keystore 的处理与通用版一致（官方下载校验、密码走环境变量）。
# 所有中间文件在 work/arm64-only/ 下，不用 /tmp。
#
# 用法：
#   export XINGHE_KEYSTORE_PASS='你的 keystore 密码'
#   export XINGHE_KEY_ALIAS_PASS='你的 key 别名密码'
#   bash build-arm64.sh            # 产物：dist/xinghe-proxy-arm64-v3.1.apk
set -eu
BASE="$(cd "$(dirname "$0")" && pwd)"
APP="$BASE/app"
WORK="$BASE/work/arm64-only"
DL="$BASE/tools/dl"
OUT_DIR="${OUT_DIR:-$BASE/dist}"
OUT_APK="$OUT_DIR/xinghe-proxy-arm64-v3.1.apk"
VERIFY="$WORK/verify-arm64.txt"
SINGBOX_VERSION="1.14.4"
SINGBOX_BASE="https://github.com/SagerNet/sing-box/releases/download/v${SINGBOX_VERSION}"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "$HOME/workspace/android16-mi6/tools/jdk" ]; then
    JAVA_HOME="$HOME/workspace/android16-mi6/tools/jdk"
  fi
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
java -version
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || SDK="$HOME/Android/Sdk"
[ -d "$SDK" ] || SDK="$HOME/workspace/android16-mi6/tools/sdk"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] || { echo "MISSING build-tools 34.0.0（请设置 ANDROID_SDK_ROOT）"; exit 1; }
[ -f "$PLAT" ] || { echo "MISSING platforms/android-34/android.jar"; exit 1; }

mkdir -p "$WORK" "$OUT_DIR" "$DL"

# ---------- sing-box arm64 核心：缺失时从官方 Releases 下载并校验 ----------
GZ="$APP/assets/sing-box-arm64-v8a.gz"
if [ ! -f "$GZ" ]; then
  ASSET="sing-box-1.14.4-android-arm64.tar.gz"
  SHA="fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f"
  TGZ="$DL/$ASSET"
  [ -f "$TGZ" ] || { if command -v curl >/dev/null 2>&1; then curl -fL -o "$TGZ" "$SINGBOX_BASE/$ASSET"; else wget -O "$TGZ" "$SINGBOX_BASE/$ASSET"; fi; }
  echo "$SHA  $TGZ" | sha256sum -c - || { echo "SHA256 校验失败：$ASSET"; rm -f "$TGZ"; exit 1; }
  TMP="$(mktemp -d)"; tar -xzf "$TGZ" -C "$TMP"; gzip -9 -n -c "$TMP/sing-box" > "$GZ"; rm -rf "$TMP"
  echo "core arm64-v8a: 已就绪 $GZ"
fi

# --- arm64-only 资产暂存目录：只放 arm64 核心 + geoip + index.html ---
ASSETS="$WORK/assets"
rm -rf "$ASSETS"; mkdir -p "$ASSETS"
cp "$APP/assets/sing-box-arm64-v8a.gz" "$ASSETS/"
cp "$APP/assets/geoip-cn.srs" "$ASSETS/"
cp "$APP/assets/index.html" "$ASSETS/"
[ -f "$ASSETS/sing-box-arm64-v8a.gz" ] || { echo "MISSING assets/sing-box-arm64-v8a.gz"; exit 1; }
[ -f "$ASSETS/geoip-cn.srs" ] || { echo "MISSING assets/geoip-cn.srs"; exit 1; }
# 断言：暂存目录里不得有其它 sing-box / mihomo 核心
if ls "$ASSETS"/sing-box-*.gz | grep -v "sing-box-arm64-v8a.gz" >/dev/null 2>&1; then
  echo "FATAL: staged assets contain non-arm64 sing-box"; ls -l "$ASSETS"; exit 1
fi
if ls "$ASSETS"/mihomo* >/dev/null 2>&1; then
  echo "FATAL: staged assets contain mihomo"; ls -l "$ASSETS"; exit 1
fi
ls -lh "$ASSETS"

# hev .so：仅 arm64-v8a（tools/dl/hev.aar，heiher/hev-socks5-tunnel 2.19.3）
HEV_AAR="$DL/hev.aar"
[ -f "$HEV_AAR" ] || { echo "MISSING tools/dl/hev.aar"; exit 1; }
mkdir -p "$WORK/libs/arm64-v8a"
unzip -p "$HEV_AAR" "jni/arm64-v8a/libhev-socks5-tunnel.so" > "$WORK/libs/arm64-v8a/libhev-socks5-tunnel.so"
ls -lh "$WORK/libs/arm64-v8a/libhev-socks5-tunnel.so"

rm -rf "$WORK/gen" "$WORK/classes" "$WORK/dex"; mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"
"$BT/aapt2" compile --dir "$APP/res" -o "$WORK/compiled.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" \
  --manifest "$APP/AndroidManifest.xml" \
  -I "$PLAT" -A "$ASSETS" "$WORK/compiled.zip" \
  --java "$WORK/gen" --min-sdk-version 24 --target-sdk-version 28 \
  --version-code 16 --version-name "3.1"
javac -encoding UTF-8 -cp "$PLAT:$DL/zxing-core.jar" -d "$WORK/classes" \
  "$WORK/gen/com/dabao/proxy/R.java" \
  "$APP/src/com/dabao/proxy/MainActivity.java" \
  "$APP/src/com/dabao/proxy/ScanActivity.java" \
  "$APP/src/com/dabao/proxy/ProxyVpnService.java" \
  "$APP/src/hev/htproxy/TProxyService.java"
(cd "$WORK/classes" && jar cf "$WORK/classes.jar" .)
"$BT/d8" --lib "$PLAT" --min-api 24 --output "$WORK/dex" "$WORK/classes.jar" "$DL/zxing-core.jar"
cp "$WORK/unsigned.apk" "$WORK/withdex.apk"
(cd "$WORK/dex" && zip -q "$WORK/withdex.apk" classes.dex)
# .so 必须以 STORED（不压缩）方式进包，Android 才能直接加载；仅 arm64-v8a
python3 - "$WORK/withdex.apk" "$WORK/libs" <<'PY'
import sys, zipfile, os
apk, libs = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED)
for abi in ("arm64-v8a",):
    p = os.path.join(libs, abi, "libhev-socks5-tunnel.so")
    z.write(p, f"lib/{abi}/libhev-socks5-tunnel.so", compress_type=zipfile.ZIP_STORED)
z.close()
print("libs added")
PY
"$BT/zipalign" -f -p 4 "$WORK/withdex.apk" "$WORK/aligned.apk"

# ---------- 签名：与通用版共用同一个 keystore（密码走环境变量，不进仓库） ----------
KS="$BASE/xinghe.keystore"
if [ ! -f "$KS" ]; then
  echo "MISSING keystore $KS（请先用通用版 build-nextin.sh 生成，或按 README 的 keytool 命令自行生成）"
  exit 1
fi
if [ -z "${XINGHE_KEYSTORE_PASS:-}" ] || [ -z "${XINGHE_KEY_ALIAS_PASS:-}" ]; then
  echo "请导出签名密码再构建：export XINGHE_KEYSTORE_PASS='...' XINGHE_KEY_ALIAS_PASS='...'"
  exit 1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$XINGHE_KEYSTORE_PASS" --key-pass "pass:$XINGHE_KEY_ALIAS_PASS" \
  --out "$OUT_APK" "$WORK/aligned.apk"

: > "$VERIFY"
echo "===== arm64-only 版 v3.1（versionCode 16，sing-box 1.14.4；仅 arm64-v8a；与通用版同源码/同核心/同功能）=====" >> "$VERIFY"
"$BT/apksigner" verify --print-certs "$OUT_APK" >> "$VERIFY"
"$BT/aapt2" dump badging "$OUT_APK" >> "$VERIFY"
sha256sum "$OUT_APK" >> "$VERIFY"
ls -lh "$OUT_APK" >> "$VERIFY"
echo "--- APK 内容检查 ---" >> "$VERIFY"
unzip -l "$OUT_APK" | grep -E "sing-box|mihomo|libhev|classes.dex|index.html|geoip-cn" >> "$VERIFY" || true
cat "$VERIFY"
echo "BUILD_OK $OUT_APK"
