#!/bin/bash
# 星核代理 Android 打包脚本（通用版，4 ABI）— 无 Gradle 手动流程
# 构建时会从 sing-box 官方 GitHub Releases 自动下载核心二进制并校验 SHA256；
# 签名用的 keystore 需自行生成，密码通过环境变量传入，脚本里不写任何密码。
#
# 环境要求：JDK 17、Android SDK（含 build-tools 34.0.0 与 platforms/android-34）
#   JAVA_HOME 未设置时，依次尝试：$HOME/workspace/android16-mi6/tools/jdk、PATH 里的 java
#   SDK 未设置时，依次尝试：$ANDROID_SDK_ROOT、$ANDROID_HOME、$HOME/Android/Sdk、
#   $HOME/workspace/android16-mi6/tools/sdk
# 用法：
#   export XINGHE_KEYSTORE_PASS='你的 keystore 密码'
#   export XINGHE_KEY_ALIAS_PASS='你的 key 别名密码'
#   bash build-nextin.sh            # 产物：dist/xinghe-proxy-universal-v3.1.apk
set -eu
BASE="$(cd "$(dirname "$0")" && pwd)"
APP="$BASE/app"
WORK="$BASE/work"
DL="$BASE/tools/dl"
OUT_DIR="${OUT_DIR:-$BASE/dist}"
OUT_APK="$OUT_DIR/xinghe-proxy-universal-v3.1.apk"
SINGBOX_VERSION="1.14.4"
SINGBOX_BASE="https://github.com/SagerNet/sing-box/releases/download/v${SINGBOX_VERSION}"

# ---------- JDK / SDK 定位 ----------
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

mkdir -p "$DL" "$WORK" "$OUT_DIR"

# ---------- sing-box 核心：缺失时从官方 Releases 下载并校验 ----------
# asset 名与 SHA256 取自 GitHub API 的 asset digest（v1.14.4，2026-10-24 发布，
# 该 release 无独立 checksum 文件）。tar.gz 内含 sing-box 可执行文件 + LICENSE；
# 解压后 gzip -9 压成 app/assets/sing-box-<abi>.gz（App 运行时 gunzip 到应用目录执行）。
# 命名映射：android-arm64→arm64-v8a；android-arm→armeabi-v7a；
#           android-386→x86；android-amd64→x86_64
download_core() { # $1=abi $2=asset $3=sha256 $4=bytes
  local abi="$1" asset="$2" sha="$3" bytes="$4"
  local gz="$APP/assets/sing-box-${abi}.gz"
  if [ -f "$gz" ]; then echo "core $abi: 已存在，跳过下载"; return 0; fi
  local tgz="$DL/$asset"
  if [ ! -f "$tgz" ]; then
    echo "下载 $asset ..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL -o "$tgz" "$SINGBOX_BASE/$asset"
    else
      wget -O "$tgz" "$SINGBOX_BASE/$asset"
    fi
  fi
  echo "$sha  $tgz" | sha256sum -c - || { echo "SHA256 校验失败：$asset"; rm -f "$tgz"; exit 1; }
  local actual; actual="$(stat -c%s "$tgz")"
  [ "$actual" = "$bytes" ] || { echo "大小不符：$asset（期望 $bytes，实际 $actual）"; exit 1; }
  local tmp; tmp="$(mktemp -d)"
  tar -xzf "$tgz" -C "$tmp"
  gzip -9 -n -c "$tmp/sing-box" > "$gz"
  rm -rf "$tmp"
  echo "core $abi: 已就绪 $gz"
}
download_core arm64-v8a   sing-box-1.14.4-android-arm64.tar.gz fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f 25614079
download_core armeabi-v7a sing-box-1.14.4-android-arm.tar.gz   a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9 26140101
download_core x86         sing-box-1.14.4-android-386.tar.gz   6b715f5ee3d1ad1738da99e7c207db807a06560f623a2a44105dd3c34339fd93 28444277
download_core x86_64      sing-box-1.14.4-android-amd64.tar.gz e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac 27527825
[ -f "$APP/assets/geoip-cn.srs" ] || { echo "MISSING assets/geoip-cn.srs"; exit 1; }

# ---------- hev .so：从 tools/dl/hev.aar（heiher/hev-socks5-tunnel 2.19.3）解出 ----------
HEV_AAR="$DL/hev.aar"
[ -f "$HEV_AAR" ] || { echo "MISSING tools/dl/hev.aar"; exit 1; }
mkdir -p "$WORK/libs"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  mkdir -p "$WORK/libs/$abi"
  unzip -p "$HEV_AAR" "jni/$abi/libhev-socks5-tunnel.so" > "$WORK/libs/$abi/libhev-socks5-tunnel.so"
  ls -lh "$WORK/libs/$abi/libhev-socks5-tunnel.so"
done

rm -rf "$WORK/gen" "$WORK/classes" "$WORK/dex"; mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"
"$BT/aapt2" compile --dir "$APP/res" -o "$WORK/compiled.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" \
  --manifest "$APP/AndroidManifest.xml" \
  -I "$PLAT" -A "$APP/assets" "$WORK/compiled.zip" \
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
# .so 必须以 STORED（不压缩）方式进包，Android 才能直接加载
python3 - "$WORK/withdex.apk" "$WORK/libs" <<'PY'
import sys, zipfile, os
apk, libs = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED)
for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
    p = os.path.join(libs, abi, "libhev-socks5-tunnel.so")
    z.write(p, f"lib/{abi}/libhev-socks5-tunnel.so", compress_type=zipfile.ZIP_STORED)
z.close()
print("libs added")
PY
"$BT/zipalign" -f -p 4 "$WORK/withdex.apk" "$WORK/aligned.apk"

# ---------- 签名：keystore 与密码一律不进仓库 ----------
# 首次构建：先生成自己的 keystore（只做一次，之后每次构建复用同一个，别丢了，
# 否则已安装用户无法覆盖升级）：
#   keytool -genkeypair -keystore xinghe.keystore -alias xinghe \
#     -keyalg RSA -keysize 2048 -validity 10000 \
#     -storepass "$XINGHE_KEYSTORE_PASS" -keypass "$XINGHE_KEY_ALIAS_PASS" \
#     -dname "CN=XingHe Proxy, O=XingHe, C=CN"
# 然后导出密码再构建：
#   export XINGHE_KEYSTORE_PASS='...' XINGHE_KEY_ALIAS_PASS='...'
KS="$BASE/xinghe.keystore"
if [ ! -f "$KS" ]; then
  if [ -z "${XINGHE_KEYSTORE_PASS:-}" ] || [ -z "${XINGHE_KEY_ALIAS_PASS:-}" ]; then
    echo "MISSING keystore $KS"
    echo "请先生成 keystore（只做一次）："
    echo "  keytool -genkeypair -keystore xinghe.keystore -alias xinghe -keyalg RSA -keysize 2048 -validity 10000 \\"
    echo "    -storepass \"\$XINGHE_KEYSTORE_PASS\" -keypass \"\$XINGHE_KEY_ALIAS_PASS\" -dname \"CN=XingHe Proxy, O=XingHe, C=CN\""
    echo "然后：export XINGHE_KEYSTORE_PASS='...' XINGHE_KEY_ALIAS_PASS='...' 后重新构建。"
    exit 1
  fi
  keytool -genkeypair -keystore "$KS" -alias xinghe -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$XINGHE_KEYSTORE_PASS" -keypass "$XINGHE_KEY_ALIAS_PASS" \
    -dname "CN=XingHe Proxy, O=XingHe, C=CN"
fi
if [ -z "${XINGHE_KEYSTORE_PASS:-}" ] || [ -z "${XINGHE_KEY_ALIAS_PASS:-}" ]; then
  echo "请导出签名密码再构建：export XINGHE_KEYSTORE_PASS='...' XINGHE_KEY_ALIAS_PASS='...'"
  exit 1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$XINGHE_KEYSTORE_PASS" --key-pass "pass:$XINGHE_KEY_ALIAS_PASS" \
  --out "$OUT_APK" "$WORK/aligned.apk"

: > "$WORK/verify.txt"
echo "===== 通用版 v3.1（versionCode 16，sing-box 1.14.4）=====" >> "$WORK/verify.txt"
"$BT/apksigner" verify --print-certs "$OUT_APK" >> "$WORK/verify.txt"
"$BT/aapt2" dump badging "$OUT_APK" >> "$WORK/verify.txt"
sha256sum "$OUT_APK" >> "$WORK/verify.txt"
ls -lh "$OUT_APK" >> "$WORK/verify.txt"
echo "--- APK 内容检查 ---" >> "$WORK/verify.txt"
unzip -l "$OUT_APK" | grep -E "sing-box|libhev|classes.dex|index.html|geoip-cn" >> "$WORK/verify.txt" || true
cat "$WORK/verify.txt"
echo "BUILD_OK $OUT_APK"
