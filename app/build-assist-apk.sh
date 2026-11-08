#!/usr/bin/env bash
# 一键构建 DSH 小V 助手 APK（在手机上的 proot Ubuntu aarch64 里跑，无需 aapt2）。
#
#   bash /root/workspace/build-assist-apk.sh            # 全量构建 + 校验（默认 dsh-assist-v2.apk）
#   bash /root/workspace/build-assist-apk.sh --clean    # 先清掉 build/out 再构建
#   APK_NAME=dsh-assist.apk bash /root/workspace/build-assist-apk.sh   # 产物改名
#
# 依赖（缺失时自动从 dl.google.com / apt 拉取，见 README.md）：
#   openjdk-17-jdk-headless           ≈ 262 MB（装好后磁盘占用）
#   build-tools_r35_linux.zip         ≈ 62 MB（只取 lib/d8.jar、lib/apksigner.jar）
#   platform-36_r02.zip               ≈ 66 MB（只取 android.jar，javac 的 classpath）
#
# 产物：out/dsh-assist-v2.apk（v2 = v1 + MainActivity 自助申请助手角色 + 可选语音入口）
# 流程：手写 AXML manifest → javac --release 8 → d8 → zip → keytool 自签
#       → apksigner sign(v1+v2+v3) → verify.py 结构校验
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/assist-apk"
TOOLS="$ROOT/tools"
SRC="$ROOT/src"
BUILD="$ROOT/build"
OUT="$ROOT/out"
KS_DIR="$ROOT/keystore"
KS="$KS_DIR/dsh-assist.jks"
# 构建 profile：full = 全量；strip = 排查版（去 Shizuku provider/监听、BootReceiver、HealService）
# 兼容旧参数：--variant v6a 等价于 --profile strip，其它值等价 full
PROFILE="full"
prev_arg=""
for arg in "$@"; do
  if [ "$prev_arg" = "--variant" ] || [ "$prev_arg" = "--profile" ]; then
    case "$arg" in
      v6a|strip|min) PROFILE="strip" ;;
      noicon|no-icon) PROFILE="noicon" ;;
      *) PROFILE="full" ;;
    esac
  fi
  prev_arg="$arg"
done

VERSION_CODE="$(python3 -c "import sys; sys.path.insert(0, '$TOOLS'); import make_manifest as m; print(m.VERSION_CODE)")"
if [ "$PROFILE" = "strip" ]; then
  MANIFEST_VARIANT="v6a"
  ICON=0
  APK_NAME="${APK_NAME:-dsh-assist-v6a.apk}"
elif [ "$PROFILE" = "noicon" ]; then
  MANIFEST_VARIANT="v6"
  ICON=0
  APK_NAME="${APK_NAME:-dsh-assist-v$VERSION_CODE-noicon.apk}"
else
  MANIFEST_VARIANT="v6"
  ICON=1
  APK_NAME="${APK_NAME:-dsh-assist-v$VERSION_CODE.apk}"
fi
APK="$OUT/$APK_NAME"
UNALIGNED="$BUILD/unaligned.apk"

# Shizuku 客户端（4 个 AAR 只取 classes.jar；都无 res/，不需要 aapt2）
SHIZUKU_VERSION=13.1.5
SHIZUKU_DIR="$TOOLS/shizuku"
SHIZUKU_CLASSPATH="$SHIZUKU_DIR/api-classes.jar:$SHIZUKU_DIR/aidl-classes.jar:$SHIZUKU_DIR/shared-classes.jar:$SHIZUKU_DIR/provider-classes.jar"
SHIZUKU_INPUTS="$SHIZUKU_DIR/api-classes.jar $SHIZUKU_DIR/aidl-classes.jar $SHIZUKU_DIR/shared-classes.jar $SHIZUKU_DIR/provider-classes.jar"

BT_URL="https://dl.google.com/android/repository/build-tools_r35_linux.zip"
PLATFORM_URL="https://dl.google.com/android/repository/platform-36_r02.zip"

STORE_PASS="android"
KEY_ALIAS="dsh-assist"
MIN_SDK=26
TARGET_SDK=36

log()  { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK -------
pick_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    return
  fi
  for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-* ; do
    if [ -x "$candidate/bin/javac" ]; then
      JAVA_HOME="$candidate"
      export JAVA_HOME
      return
    fi
  done
  die "找不到 JDK 17，先跑: apt-get update && mkdir -p /usr/share/man/man1 && apt-get install -y openjdk-17-jdk-headless && dpkg --configure -a"
}
pick_java_home
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

# ------------------------------------------------------- download tools -----
download() {  # download <url> <dest>
  local url="$1" dest="$2"
  [ -s "$dest" ] && return
  log "下载 $(basename "$dest")"
  python3 - "$url" "$dest" <<'PY'
import sys, urllib.request
url, dest = sys.argv[1], sys.argv[2]
urllib.request.urlretrieve(url, dest)
print('  ->', dest)
PY
}

extract_from_zip() {  # extract_from_zip <zip> <member> <dest>
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile
zf, member, dest = sys.argv[1:4]
with zipfile.ZipFile(zf) as z, open(dest, 'wb') as fh:
    fh.write(z.read(member))
PY
}

fetch_tools() {
  mkdir -p "$TOOLS"
  local bt_zip=/tmp/dsh-build-tools-r35.zip
  local platform_zip=/tmp/dsh-platform-36.zip
  if [ ! -s "$TOOLS/d8.jar" ] || [ ! -s "$TOOLS/apksigner.jar" ]; then
    download "$BT_URL" "$bt_zip"
    extract_from_zip "$bt_zip" "android-15/lib/d8.jar" "$TOOLS/d8.jar"
    extract_from_zip "$bt_zip" "android-15/lib/apksigner.jar" "$TOOLS/apksigner.jar"
  fi
  if [ ! -s "$TOOLS/android.jar" ]; then
    download "$PLATFORM_URL" "$platform_zip"
    extract_from_zip "$platform_zip" "android-36/android.jar" "$TOOLS/android.jar"
    extract_from_zip "$platform_zip" "android-36/source.properties" "$TOOLS/platform-source.properties"
  fi
  fetch_shizuku
}

# Shizuku 客户端：Maven 上 dev.rikka.shizuku:{api,provider,aidl,shared}，每个 AAR 只有
# classes.jar（无 res/、R.txt 为空），正好适合没有 aapt2 的手工构建。
fetch_shizuku() {
  mkdir -p "$SHIZUKU_DIR"
  if [ -s "$SHIZUKU_DIR/api-classes.jar" ] && [ -s "$SHIZUKU_DIR/aidl-classes.jar" ] \
     && [ -s "$SHIZUKU_DIR/shared-classes.jar" ] && [ -s "$SHIZUKU_DIR/provider-classes.jar" ]; then
    return
  fi
  log "下载 Shizuku API $SHIZUKU_VERSION（4 个 AAR，仅取 classes.jar）"
  python3 - "$SHIZUKU_VERSION" "$SHIZUKU_DIR" <<'PY'
import io, os, sys, urllib.request, zipfile
version, outdir = sys.argv[1], sys.argv[2]
base = 'https://repo1.maven.org/maven2/dev/rikka/shizuku/'
for artifact in ('api', 'aidl', 'shared', 'provider'):
    url = '%s%s/%s/%s-%s.aar' % (base, artifact, version, artifact, version)
    data = urllib.request.urlopen(url, timeout=120).read()
    aar = zipfile.ZipFile(io.BytesIO(data))
    names = aar.namelist()
    if any(n.startswith('res/') for n in names) or aar.read('R.txt'):
        raise SystemExit('%s 带资源，无法在没有 aapt2 的情况下使用' % artifact)
    with open(os.path.join(outdir, artifact + '-classes.jar'), 'wb') as fh:
        fh.write(aar.read('classes.jar'))
    print('    -> %s-classes.jar' % artifact)
PY
}

# ------------------------------------------------------------- build --------
main() {
  local t0 t1
  t0=$(date +%s)

  # 只清自己的中间产物和本次目标 APK，保留 out/ 里其它版本的产物
  if [ "${1:-}" = "--clean" ]; then
    rm -rf "$BUILD"
    rm -f "$APK"
  fi
  mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT" "$KS_DIR"

  log "0/6 检查工具链"
  fetch_tools
  "$JAVA" -version 2>&1 | sed 's/^/    /'
  ls -l "$TOOLS"/*.jar "$SHIZUKU_DIR"/*.jar | sed 's/^/    /'

  log "1/6 生成二进制 AndroidManifest.xml（手写 AXML 编码器，无 aapt2）"
  if [ "$ICON" = "1" ]; then
    python3 "$TOOLS/make_manifest.py" "$BUILD/AndroidManifest.xml" --variant "$MANIFEST_VARIANT"
  else
    python3 "$TOOLS/make_manifest.py" "$BUILD/AndroidManifest.xml" --variant "$MANIFEST_VARIANT" --no-icon
  fi
  printf '    %s bytes\n' "$(stat -c%s "$BUILD/AndroidManifest.xml")"

  rm -rf "$BUILD/res" "$BUILD/resources.arsc"
  if [ "$ICON" = "1" ]; then
    log "1b/6 生成应用图标 PNG + 最小 resources.arsc（手写，无 aapt2）"
    python3 "$TOOLS/gen_icon.py" "$BUILD/res"
    python3 "$TOOLS/arsc_encode.py" "$BUILD/resources.arsc"
  else
    log "1b/6 对照包：不带图标 / resources.arsc"
  fi

  log "2/6 javac --release 8（profile=$PROFILE）"
  rm -rf "$BUILD/classes" "$BUILD/gen"
  mkdir -p "$BUILD/classes" "$BUILD/gen/com/dshgo/assist"
  local shizuku_flag="true" heal_flag="true"
  if [ "$PROFILE" = "strip" ]; then
    shizuku_flag="false"
    heal_flag="false"
  fi
  cat > "$BUILD/gen/com/dshgo/assist/BuildFlags.java" <<EOF
package com.dshgo.assist;

/** 由构建脚本按变体生成，不要手改。SHIZUKU=false 时进程启动阶段完全不碰 Shizuku。 */
final class BuildFlags {
    static final boolean SHIZUKU = $shizuku_flag;
    static final boolean HEAL = $heal_flag;
    private BuildFlags() {
    }
}
EOF
  find "$SRC" "$BUILD/gen" -name '*.java' -print0 | xargs -0 "$JAVAC" \
      --release 8 -Xlint:-options \
      -classpath "$TOOLS/android.jar:$SHIZUKU_CLASSPATH" \
      -d "$BUILD/classes"
  find "$BUILD/classes" -name '*.class' | sed 's/^/    /'

  log "3/6 d8 → classes.dex（min-api $MIN_SDK）"
  rm -rf "$BUILD/dex" && mkdir -p "$BUILD/dex"
  "$JAVA" -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 \
      --release --min-api "$MIN_SDK" \
      --lib "$TOOLS/android.jar" \
      --output "$BUILD/dex" \
      $(find "$BUILD/classes" -name '*.class') \
      $SHIZUKU_INPUTS
  local dex_size
  dex_size=$(stat -c%s "$BUILD/dex/classes.dex")

  log "4/6 打包（mkzip：arsc/PNG STORED + 4 字节对齐，dex DEFLATED）"
  if [ "$ICON" = "1" ]; then
    python3 "$TOOLS/mkzip.py" "$UNALIGNED" "$BUILD/AndroidManifest.xml" \
        "$BUILD/dex/classes.dex" "$BUILD/resources.arsc" "$BUILD/res"
  else
    python3 "$TOOLS/mkzip.py" "$UNALIGNED" "$BUILD/AndroidManifest.xml" \
        "$BUILD/dex/classes.dex" - 
  fi

  log "5/6 自签名（keytool + apksigner，v1/v2/v3）"
  if [ ! -s "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
        -validity 10000 -dname "CN=DSH Assist,O=DSHGo,OU=Local,C=CN" >/dev/null
    echo "    生成签名密钥 $KS"
  fi
  "$JAVA" -jar "$TOOLS/apksigner.jar" sign \
      --ks "$KS" --ks-key-alias "$KEY_ALIAS" \
      --ks-pass "pass:$STORE_PASS" --key-pass "pass:$STORE_PASS" \
      --min-sdk-version "$MIN_SDK" --max-sdk-version "$TARGET_SDK" \
      --v1-signing-enabled true --v2-signing-enabled true --v4-signing-enabled false \
      --out "$APK" "$UNALIGNED"
  ls -l "$APK" | sed 's/^/    /'

  log "6/6 结构校验（javap 证据 + apksigner verify + verify.py）"
  # 关键 API 调用的静态证据（防止只有字符串、没有真实调用）
  local javap_bin="$JAVA_HOME/bin/javap"
  if [ -x "$javap_bin" ]; then
    local evidence="$ROOT/evidence/javap-api-calls-$(basename "$APK" .apk).txt"
    {
      echo "=== javap -c: 关键 API 调用（$(basename "$APK")） ==="
      for cls in MainActivity ListenActivity OrbView Ui RootOps AssistantRole Dispatcher ShizukuShell; do
        echo
        echo "--- $cls ---"
        "$javap_bin" -c -p -classpath "$BUILD/classes" "com.dshgo.assist.$cls" 2>/dev/null \
          | grep -E 'createRequestRoleIntent|isRoleHeld|isRoleAvailable|ROLE_SERVICE|startActivityForResult|SpeechRecognizer|startListening|ACTION_SEND|setPackage|setClassName|getLaunchIntentForPackage|startActivity|putInt|canWrite|MANAGE_WRITE_SETTINGS|NotificationChannel|setShortCriticalText|setRequestPromotedOngoing|requestPromotedOngoing|notify|canPostPromotedNotifications|getActiveNotifications|hasPromotableCharacteristics|Shizuku|newProcess|requestPermission|pingBinder|checkSelfPermission|addBinderReceivedListener|addRequestPermissionResultListener|waitFor|startRootBridge|settRootBridge|rootq' \
          | sed 's/^ *//' | sort -u || true
      done
    } > "$evidence"
    echo "    javap 证据 → $evidence"
  fi
  "$JAVA" -jar "$TOOLS/apksigner.jar" verify --verbose --print-certs "$APK" | sed 's/^/    /'
  echo
  set +e
  python3 "$ROOT/verify.py" "$APK" --variant "$MANIFEST_VARIANT"
  local verify_rc=$?
  set -e

  t1=$(date +%s)
  echo
  log "完成：$APK（profile=$PROFILE）"
  printf '    产物大小 : %s bytes (%s)\n' "$(stat -c%s "$APK")" "$(du -h "$APK" | cut -f1)"
  printf '    classes.dex: %s bytes\n' "$dex_size"
  printf '    总耗时   : %ss\n' "$((t1 - t0))"
  echo
  echo "安装（在手机上，由 Lead 执行；不要用本脚本安装）："
  echo "    adb install -r $APK"
  echo "    或手机上：adb install 不可用时用文件管理器点开 $(basename "$APK")"
  return $verify_rc
}

main "$@"