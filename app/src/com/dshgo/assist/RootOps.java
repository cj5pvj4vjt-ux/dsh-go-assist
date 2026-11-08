package com.dshgo.assist;

/**
 * v11：所有走 root（Shizuku）执行的动作集中在这里。
 *
 * 关键变化（相对 v9/v10）：
 *   1. 守护脚本 / pid 一律放 /sdcard/Download/DSH-Go/bin/（不再写 临时目录，
 *      真机上会被清空 / 命名空间不一致）；
 *   2. `power_button_long_press` 统一写 **0**（框架那条助手路会延迟触发、被电源菜单插队），
 *      保留 `power_button_very_long_press=1` 给 5 秒电源菜单；
 *   3. 新增「电源键钩子」：getevent 守护进程，按住 350ms~3.5s 直接拉起 OverlayService。
 */
final class RootOps {

    static final String KEY_VIVO_POWER = "vivo_jovi_power_wakeup_switch";
    static final String KEY_SHORT_PRESS = "power_button_short_press";
    static final String KEY_LONG_PRESS = "power_button_long_press";
    static final String KEY_VERY_LONG_PRESS = "power_button_very_long_press";
    static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";
    static final String PACKAGE = "com.dshgo.assist";
    static final String NEXT_APK = "dsh-assist-next.apk";

    /** 长按判定区间（毫秒）：350ms ~ 4900ms 视为「唤起助手」
     *  （用户实测自然按住 3.3~4.0s；>4900ms 会走框架电源菜单，只记日志不动作）。 */
    static final int HOOK_MIN_MS = 300;
    static final int HOOK_MAX_MS = 4900;

    /**
     * 在脚本里定位 /sdcard（root 的 /sdcard 有时不可用，按顺序退化），
     * 并保证 $BIN = <base>/Download/DSH-Go/bin 存在。
     */
    private static final String FIND_BASE = ""
            + "B=''\n"
            + "for b in /sdcard /storage/emulated/0 /data/media/0; do\n"
            + "  if [ -d \"$b/Download/DSH-Go\" ]; then B=\"$b/Download/DSH-Go\"; break; fi\n"
            + "done\n"
            + "[ -n \"$B\" ] || B=/storage/emulated/0/Download/DSH-Go\n"
            + "BIN=\"$B/bin\"\n"
            + "mkdir -p \"$BIN\" \"$B/rootq/done\" \"$B/rootq-out\" 2>/dev/null\n";

    // v18：电源键两档模式；v33 增加 stock（完全原生）
    static final String PREF_POWER_MODE = "power_mode";
    static final String MODE_STOCK = "stock";
    static final String MODE_SYSTEM = "system";
    static final String MODE_ROOT = "root";

    private RootOps() {
    }

    /**
     * 按电源键模式幂等写入设置并（去）启动钩子：
     *   - stock（v33 默认，推荐）：short=1 / long=1 / very_long=1 —— 完全原生行为，
     *     不装钩子、不动任何按键，电源键永远正常（重启后也不会变砖）；
     *   - system：short=1 / long=5，框架尝试拉起 AssistActivity（这台 vivo 会吞长按，慎用）；
     *   - root 钩子：short=0 / long=0 + 装「双击电源键」钩子（钩子只在双击时唤起，不注入任何按键）。
     */
    static String powerModeScript(String mode) {
        boolean root = MODE_ROOT.equals(mode);
        boolean stock = MODE_STOCK.equals(mode);
        return ""
                + "settings put system " + KEY_VIVO_POWER + " 0\n"
                + "settings put global " + KEY_SHORT_PRESS + " " + (root ? "0" : "1") + "\n"
                + "settings put global " + KEY_LONG_PRESS + " 1\n"
                + "settings put global " + KEY_VERY_LONG_PRESS + " 1\n"
                + "cmd role add-role-holder --user 0 " + ROLE_ASSISTANT + " " + PACKAGE + "\n"
                + (root ? installPowerHookScript() : stopPowerHookScript());
    }

    /** 兼容旧调用：true=root 钩子，false=system。 */
    static String powerModeScript(boolean rootMode) {
        return powerModeScript(rootMode ? MODE_ROOT : MODE_SYSTEM);
    }

    static boolean rootReady() {
        return ShizukuShell.binderReady() && ShizukuShell.hasPermission();
    }

    /**
     * 幂等 apply：vivo 开关=0 + 电源键三件套（short=0 / long=0 / very_long=1）+ 助手角色。
     * 三件套全部交给 DSH 钩子负责，框架层完全不动（very_long=1 仍保留 5s 电源菜单）。
     */
    static String applyScript() {
        return ""
                + "settings put system " + KEY_VIVO_POWER + " 0\n"
                + "settings put global " + KEY_SHORT_PRESS + " 0\n"
                + "settings put global " + KEY_LONG_PRESS + " 0\n"
                + "settings put global " + KEY_VERY_LONG_PRESS + " 1\n"
                + "cmd role add-role-holder --user 0 " + ROLE_ASSISTANT + " " + PACKAGE + "\n";
    }

    static String diagnosticsScript() {
        return FIND_BASE
                + "echo '$ id'; id 2>&1\n"
                + "echo; echo '$ 电源键相关设置'\n"
                + "echo -n '" + KEY_VIVO_POWER + "='; settings get system "
                + KEY_VIVO_POWER + " 2>&1\n"
                + "echo -n '" + KEY_SHORT_PRESS + "='; settings get global "
                + KEY_SHORT_PRESS + " 2>&1\n"
                + "echo -n '" + KEY_LONG_PRESS + "='; settings get global "
                + KEY_LONG_PRESS + " 2>&1\n"
                + "echo -n '" + KEY_VERY_LONG_PRESS + "='; settings get global "
                + KEY_VERY_LONG_PRESS + " 2>&1\n"
                + "echo; echo '$ 守护进程'\n"
                + "for f in \"$BIN/dsh-powerhook.pid\" \"$BIN/dsh-bridge.pid\"; do\n"
                + "  if [ -f \"$f\" ]; then\n"
                + "    p=$(cat \"$f\" 2>/dev/null)\n"
                + "    if kill -0 \"$p\" 2>/dev/null; then echo \"$f pid=$p ALIVE\"; "
                + "else echo \"$f pid=$p DEAD\"; fi\n"
                + "  else echo \"$f missing\"; fi\n"
                + "done\n"
                + "echo; echo '$ dumpsys package " + PACKAGE + " | grep -A3 -i assist'\n"
                + "dumpsys package " + PACKAGE + " 2>&1 | grep -A3 -i assist\n"
                + "echo; echo '$ pm list packages -3 | head'\n"
                + "pm list packages -3 2>&1 | head\n"
                + "echo; echo '$ getprop ro.build.version.sdk'; getprop ro.build.version.sdk 2>&1\n";
    }

    static String silentInstallScript() {
        return FIND_BASE
                + "APK=\"$B/" + NEXT_APK + "\"\n"
                + "if [ ! -f \"$APK\" ]; then echo \"没找到 $APK\"; exit 1; fi\n"
                + "ls -l \"$APK\" 2>&1\n"
                + "pm install -r \"$APK\" 2>&1; echo \"[exit=$?]\"\n";
    }

    // -------------------------------------------------------- power key hook --
    /**
     * 电源键钩子（v13 定稿 v5 逻辑，同步真机上已验证的脚本）：
     *
     * v33 设计（双击唤起，零干扰）：
     *   - 设置保持 stock：power_button_short_press=1、long_press=1、very_long_press=1，
     *     短按锁屏/亮屏、长按电源菜单全部交给系统框架，钩子不碰。
     *   - 钩子监听 /dev/input/event1(PMIC) 与 event3(指纹) 的 KEY_POWER DOWN：
     *     两次 DOWN 间隔 50~500ms 判定为「双击」→ 记 "DOUBLE TAP"，
     *     先 `input keyevent 224`（点亮屏幕，幂等），再
     *     `am start -n .../.AssistActivity` 唤起胶囊。
     *   - 钩子放在 /data/local/tmp（root 所有），App 用 shell 身份覆盖不了；
     *     pid 文件也在那里，App 的 stopPowerHookScript 杀不到它（由看门狗保证常驻）。
     *   - 绝不注入任何锁屏/电源键事件（历史教训：注入睡眠键会导致亮屏后又立刻灭）。
     */
    static String installPowerHookScript() {
        // 接管后（用户验证过的最佳状态）：
        //   short=0 → 短按交给钩子；单击【延后 1.3 秒】熄屏（双击可取消）→ 第一下不会立刻锁屏。
        //   两次按下间隔 <1.2 秒 = 双击 → 唤醒 + 唤起胶囊。
        //   长按 ≥1.3 秒 = 交给系统电源菜单。松手时绝不清 LAST（否则双击判不出来）。
        return FIND_BASE
                + "HOOK=/data/local/tmp/dsh-powerhook.sh\n"
                + "cat > \"$HOOK\" <<'DSHEOF'\n"
                + "#!/system/bin/sh\n"
                + "LOG=/sdcard/Download/DSH-Go/rootq-out/powerhook.log\n"
                + "PEND=/data/local/tmp/dsh-pend\n"
                + "mkdir -p /sdcard/Download/DSH-Go/rootq-out 2>/dev/null\n"
                + "echo $$ > /data/local/tmp/dsh-powerhook.pid 2>/dev/null\n"
                + "echo \"powerhook app-final $(date) uid=$(id -u)\" >> \"$LOG\"\n"
                + "LASTRAW=0; LAST=0; DOWN=0\n"
                + "echo none > $PEND\n"
                + "do_toggle() {\n"
                + "  if dumpsys power 2>/dev/null | grep -m1 mWakefulness= | grep -q Awake; then\n"
                + "    input keyevent 223 >/dev/null 2>&1\n"
                + "    echo \"$(date '+%H:%M:%S') screen sleep(223)\" >> \"$LOG\"\n"
                + "  else\n"
                + "    input keyevent 224 >/dev/null 2>&1\n"
                + "    echo \"$(date '+%H:%M:%S') screen wake(224)\" >> \"$LOG\"\n"
                + "  fi\n"
                + "}\n"
                + "summon() {\n"
                + "  input keyevent 224 >/dev/null 2>&1\n"
                + "  am start -n " + PACKAGE + "/.AssistActivity >/dev/null 2>&1\n"
                + "  echo \"  summon rc=$?\" >> \"$LOG\"\n"
                + "}\n"
                + "getevent -lt /dev/input/event1 2>/dev/null | while read -r line; do\n"
                + "  case \"$line\" in\n"
                + "    *KEY_POWER*DOWN*)\n"
                + "      NOW=$(date +%s%3N)\n"
                + "      if [ $LASTRAW -ne 0 ] && [ $(( NOW - LASTRAW )) -lt 150 ]; then continue; fi\n"
                + "      LASTRAW=$NOW\n"
                + "      echo cancel > $PEND\n"
                + "      if [ $LAST -ne 0 ] && [ $(( NOW - LAST )) -lt 1200 ]; then\n"
                + "        echo \"$(date '+%H:%M:%S') DOUBLE TAP ($(( NOW - LAST ))ms) -> summon\" >> \"$LOG\"\n"
                + "        summon\n"
                + "        LAST=0; DOWN=0\n"
                + "        continue\n"
                + "      fi\n"
                + "      LAST=$NOW; DOWN=$NOW\n"
                + "      ;;\n"
                + "    *KEY_POWER*UP*)\n"
                + "      if [ $DOWN -ne 0 ]; then\n"
                + "        NOW=$(date +%s%3N); HOLD=$(( NOW - DOWN )); DOWN=0\n"
                + "        if [ $HOLD -lt 1300 ]; then\n"
                + "          TOKEN=$NOW; echo $TOKEN > $PEND\n"
                + "          ( sleep 1.3\n"
                + "            if [ \"$(cat $PEND 2>/dev/null)\" = \"$TOKEN\" ]; then\n"
                + "              echo none > $PEND; do_toggle\n"
                + "            else\n"
                + "              echo \"$(date '+%H:%M:%S') 熄屏已取消（双击）\" >> \"$LOG\"\n"
                + "            fi ) &\n"
                + "        else\n"
                + "          echo \"$(date '+%H:%M:%S') long hold ${HOLD}ms -> 交给系统\" >> \"$LOG\"\n"
                + "        fi\n"
                + "      fi\n"
                + "      ;;\n"
                + "  esac\n"
                + "done\n"
                + "DSHEOF\n"
                + "chmod 755 \"$HOOK\"\n"
                + "pkill -f 'tmp/dsh-powerhook' 2>/dev/null\n"
                + "sleep 0.3\n"
                + "setsid nohup sh \"$HOOK\" >/dev/null 2>&1 < /dev/null &\n"
                + "echo \"hook installed (short=0, delayed sleep 1.3s, double-tap summon)\"\n";
    }

    static String stopPowerHookScript() {
        return FIND_BASE
                + "if [ -f \"$BIN/dsh-powerhook.pid\" ]; then\n"
                + "  kill -9 \"$(cat \"$BIN/dsh-powerhook.pid\")\" 2>/dev/null\n"
                + "fi\n"
                + "rm -f \"$BIN/dsh-powerhook.pid\"\n"
                + "echo 'power hook stopped'\n";
    }

    /** 钩子是否在跑：pid 文件存在且进程活着。 */
    static String hookStatusScript() {
        return FIND_BASE
                + "if [ -f \"$BIN/dsh-powerhook.pid\" ] && kill -0 "
                + "\"$(cat \"$BIN/dsh-powerhook.pid\")\" 2>/dev/null; then echo HOOK=ON; "
                + "else echo HOOK=OFF; fi\n";
    }

    // ------------------------------------------------------------ root bridge --
    static String startBridgeScript() {
        return FIND_BASE
                + "Q=\"$B/rootq\"; O=\"$B/rootq-out\"\n"
                + "cat > \"$BIN/dsh-bridge.sh\" <<'DSHEOF'\n"
                + "#!/system/bin/sh\n"
                + "BIN=\"$(dirname \"$0\")\"\n"
                + "Q=\"$1\"; O=\"$2\"\n"
                + "mkdir -p \"$Q/done\" \"$O\"\n"
                + "echo $$ > \"$BIN/dsh-bridge.pid\"\n"
                + "while [ -f \"$Q/.bridge\" ]; do\n"
                + "  for f in \"$Q\"/*.sh; do\n"
                + "    [ -f \"$f\" ] || continue\n"
                + "    n=$(basename \"$f\")\n"
                + "    {\n"
                + "      echo \"+ $(date) $n\"\n"
                + "      echo '--- script ---'\n"
                + "      cat \"$f\"\n"
                + "      echo '--- output ---'\n"
                + "      sh \"$f\" 2>&1\n"
                + "      echo \"--- exit=$? ---\"\n"
                + "    } > \"$O/$n.log\" 2>&1\n"
                + "    mv \"$f\" \"$Q/done/$n\"\n"
                + "  done\n"
                + "  sleep 2\n"
                + "done\n"
                + "rm -f \"$Q/.bridge\" \"$BIN/dsh-bridge.pid\"\n"
                + "DSHEOF\n"
                + "chmod 755 \"$BIN/dsh-bridge.sh\"\n"
                + "if [ -f \"$BIN/dsh-bridge.pid\" ]; then\n"
                + "  kill -9 \"$(cat \"$BIN/dsh-bridge.pid\")\" 2>/dev/null\n"
                + "fi\n"
                + "rm -f \"$BIN/dsh-bridge.pid\"\n"
                + "rm -f \"$Q/.bridge\"\n"
                + "touch \"$Q/.bridge\"\n"
                + "nohup sh \"$BIN/dsh-bridge.sh\" \"$Q\" \"$O\" >/dev/null 2>&1 &\n"
                + "echo \"root bridge started: base=$B marker=$Q/.bridge\"\n";
    }

    static String stopBridgeScript() {
        return FIND_BASE
                + "Q=\"$B/rootq\"\n"
                + "rm -f \"$Q/.bridge\"\n"
                + "if [ -f \"$BIN/dsh-bridge.pid\" ]; then\n"
                + "  kill -9 \"$(cat \"$BIN/dsh-bridge.pid\")\" 2>/dev/null\n"
                + "fi\n"
                + "rm -f \"$BIN/dsh-bridge.pid\"\n"
                + "echo \"root bridge stop requested ($Q)\"\n";
    }

    /** 权威状态：读 rootq/.bridge 标记文件是否存在（由 root shell 代读）。 */
    static String bridgeStatusScript() {
        return FIND_BASE
                + "if [ -f \"$B/rootq/.bridge\" ]; then echo BRIDGE=ON; "
                + "else echo BRIDGE=OFF; fi\n"
                + "if [ -f \"$BIN/dsh-bridge.pid\" ]; then echo -n 'BRIDGEPID='; "
                + "cat \"$BIN/dsh-bridge.pid\"; fi\n";
    }

    // ------------------------------------------------------- wake interception --
    /** 实验：root 起 logcat 管道，命中 vivo 唤醒关键字就拉起 AssistActivity（悬浮胶囊）。 */
    static String startWakeInterceptScript() {
        return FIND_BASE
                + "LOG=\"$B/wake-intercept.log\"\n"
                + "M=\"$B/.wake-intercept\"\n"
                + "cat > \"$BIN/dsh-wake.sh\" <<'DSHEOF'\n"
                + "#!/system/bin/sh\n"
                + "BIN=\"$(dirname \"$0\")\"\n"
                + "LOG=\"$1\"; M=\"$2\"\n"
                + "touch \"$M\"\n"
                + "echo \"start $(date)\" >> \"$LOG\"\n"
                + "while [ -f \"$M\" ]; do\n"
                + "  logcat -b main -b system -v time 2>/dev/null | while read -r line; do\n"
                + "    [ -f \"$M\" ] || break\n"
                + "    case \"$line\" in\n"
                + "      *voicewakeup*|*VOICEWAKEUP*|*hotword*|*HOTWORD*|*jovi*|*JOVI*|*wakeup*|*WAKEUP*|*copilot*|*COPILOT*)\n"
                + "        echo \"$(date) HIT: $line\" >> \"$LOG\"\n"
                + "        am start -n " + PACKAGE + "/.AssistActivity >/dev/null 2>&1\n"
                + "        sleep 2\n"
                + "        ;;\n"
                + "    esac\n"
                + "  done\n"
                + "done\n"
                + "rm -f \"$M\" \"$BIN/dsh-wake.pid\"\n"
                + "DSHEOF\n"
                + "chmod 755 \"$BIN/dsh-wake.sh\"\n"
                + "if [ -f \"$BIN/dsh-wake.pid\" ]; then\n"
                + "  kill -9 \"$(cat \"$BIN/dsh-wake.pid\")\" 2>/dev/null\n"
                + "fi\n"
                + "rm -f \"$M\"\n"
                + "nohup sh \"$BIN/dsh-wake.sh\" \"$LOG\" \"$M\" >/dev/null 2>&1 &\n"
                + "echo $! > \"$BIN/dsh-wake.pid\"\n"
                + "echo \"wake intercept started: log=$LOG pid=$!\"\n";
    }

    static String stopWakeInterceptScript() {
        return FIND_BASE
                + "rm -f \"$B/.wake-intercept\"\n"
                + "if [ -f \"$BIN/dsh-wake.pid\" ]; then\n"
                + "  kill -9 \"$(cat \"$BIN/dsh-wake.pid\")\" 2>/dev/null\n"
                + "fi\n"
                + "rm -f \"$BIN/dsh-wake.pid\"\n"
                + "for p in $(pidof logcat 2>/dev/null); do kill -9 \"$p\" 2>/dev/null; done\n"
                + "echo 'wake intercept stopped'\n";
    }

    static String cleanWakeInterceptLogScript() {
        return FIND_BASE + "rm -f \"$B/wake-intercept.log\"; echo 'log cleared'\n";
    }

    // ------------------------------------------------------------------ prefs --
    static String prefKeyBridge() {
        return "root_bridge_running";
    }

    static String prefKeyWakeIntercept() {
        return "wake_intercept";
    }

    static String prefKeyHealApplied() {
        return "heal_applied_at";
    }

    // v13：本地离线识别服务（DSH proot 里的 SenseVoice）
    static final String DEFAULT_ASR_ENDPOINT = "http://127.0.0.1:18080/transcribe";
    static final String DEFAULT_ASR_TOKEN = "dshassist";

    static String prefKeyAsrEndpoint() {
        return "asr_endpoint";
    }

    static String prefKeyAsrToken() {
        return "asr_token";
    }

    /** v11：胶囊收回后是否保留 8dp 小圆点（默认开）。 */
    static String prefKeyOverlayDot() {
        return "overlay_dot";
    }

    /** v34：回复后自动朗读。 */
    static String prefKeyAutoSpeak() {
        return "auto_speak";
    }

    /** v37：先问本地小模型（千问3-0.6B），答不了再转 DSH。 */
    static String prefKeyLocalFirst() {
        return "local_first";
    }

    /** 一键清理后台：杀后台进程 + 清掉最近任务里除保留名单外的任务。 */
    static String clearBackgroundScript() {
        return ""
                + "B=/sdcard/Download/DSH-Go/bin/clear-bg.sh\n"
                + "if [ -f \"$B\" ]; then sh \"$B\"; else am kill-all; "
                + "echo 'clear-bg.sh 缺失，只做了 am kill-all'; fi\n";
    }
}