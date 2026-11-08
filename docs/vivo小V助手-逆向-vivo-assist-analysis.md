# vivo 长按电源键 → 助手：静态分析结论

设备：vivo V2415A / Funtouch 16 (Android 16, SDK 36)　分析方式：纯静态（proot 内可读 /system）
分析对象：`/system/framework/{services,vivo-services,framework,vivo-framework}.jar`、
`/system/{app,priv-app}/**`（共 225 个 APK 的 manifest + dex）。

---

## 结论（先看这里）

**不走标准 `ROLE_ASSISTANT`。长按电源键走的是 vivo 私有广播通路，目标包名写死。**

具体链路：

```
PowerKey 长按
  └─ SingleKeyGestureDetector（vivo 改造，多了一条 MSG_KEY_LONG_PRESS_FOR_JOVI 消息）
      ├─ MSG_KEY_LONG_PRESS_FOR_JOVI → PhoneWindowManager → VivoPhoneWindowManagerImpl
      │     → VivoInputPolicy.startJoviVoiceByPowerKey() → startJoviVoice(KEYCODE_POWER=26)
      │        → 发广播 com.vivo.intent.action.WAKEUP_AGENT_BY_POWER
      │          并 setPackage(**com.vivo.ai.copilot**)      ← 写死
      └─ MSG_KEY_LONG_PRESS（AOSP 标准）→ PowerKeyRule.onLongPress → powerLongPress()
            → launchAssistAction()   ← 标准 role 通路，仍在，但会被上面先吃掉
```

**但标准通路没有被删掉**，只是被 vivo 私有通路抢在前面。所以：
- 单纯「拿到 ASSISTANT 角色」**不会自动让长按电源键生效**；
- 要让它生效，必须先把 vivo 那条私有通路关掉，开关是一个 **`Settings.System` key：`vivo_jovi_power_wakeup_switch`**（关掉 = 0）。

---

## 一、核心证据（文件 + 类 + 方法 + 字符串）

### 1) 写死的目标包名：`VivoInputPolicy.startJoviVoice(int)`

`/system/framework/vivo-services.jar` → `classes.dex`（14,018,068 B）
类 `Lcom/android/server/policy/VivoInputPolicy;`，方法 `startJoviVoice(J? 实际是 I)`，方法体地址 `0x48bfa0`。
关键常量字段（类静态字段）：

| 字段 | 值 |
|---|---|
| `GPT_CLIENT_PKG` | `com.vivo.ai.copilot` |
| `GPT_BASE_CLIENT_PKG` | `com.vivo.ai.base.copilot` |
| `GPT_ACTION` | `copilot_support_jovi_action` |

方法内 `const-string` 顺序（dexdump -d 原文）：

```
0x48c0de  const-string/jumbo v3, "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER"
0x48c0ea  invoke-direct {v5}, VivoInputPolicy;.checkJoviVoiceInstalled:()Z
0x48c0fe  const-string/jumbo v3, "com.vivo.ai.copilot"        → Intent.setPackage
0x48c10c  const-string/jumbo v3, "com.vivo.ai.base.copilot"   → 回退
0x48c122  const-string/jumbo v3, "com.vivo.agent"             → 再回退
0x48c138  const-string/jumbo v3, "com.vivo.base.agent"        → 再回退
0x48c1ae  Context.startServiceAsUser(Intent, UserHandle.CURRENT)
```

→ **包名硬编码，没有任何一处读 `Settings.Secure.ASSISTANT` 或 role 来选目标。**

### 2) 触发入口：vivo 改造过的电源键长按实现

- `com.android.server.policy.SingleKeyGestureDetector`（vivo 改造版，在 `services.jar classes2.dex`）
  多了一个消息常量 **`MSG_KEY_LONG_PRESS_FOR_JOVI`**，字段 `mJoviPowerWakeupSwitchEnabled`，
  常量 `JOVI_VOICE_TRIGGER_TIME_OUT`。
- `SingleKeyGestureDetector$KeyHandler.handleMessage` 里该分支的日志：
  `"Detect long press for jovi "` → 调
  `IVivoPhoneWindowManager$IVivoPhoneWindowManagerExport.startJoviVoiceByPowerKey:()Z`
  → 结果写回 `mJoviPowerWakeupSwitchEnabled`。
- `PhoneWindowManager$PowerKeyRule`（KEYCODE_POWER=26）`onLongPress` 仍走 AOSP：
  `powerLongPress()`；其 `getLongPressTimeoutMs()` 里显式比较 **`resolvedBehavior == 5`**（= `LONG_PRESS_POWER_LAUNCH_ASSISTANT`）。
- `PhoneWindowManager.powerLongPress(J)`（`services.jar classes2.dex`，地址 `0x5c3288`）保留了全部分支：

```
"Power - Long Press - Go To Assistant"  → launchAssistAction(...)     ← 标准 role
"Power - Long Press - Go To Voice Assist" → launchVoiceAssist(...)
"Power - Long Press - Shut Off"          → shutdown
"Power - Long Press - Global Actions"    → showGlobalActions
```

- `SingleKeyGestureDetector.interceptKeyUp` 里：
  ```
  if (!mHandledByLongPress) {
      if (eventTime < lastDownTime + 500) removeMessages(MSG_KEY_LONG_PRESS_FOR_JOVI);
      else if (mJoviPowerWakeupSwitchEnabled) mHandledByLongPress = true;
  }
  ```
  → 说明「jovi 长按」是与标准长按**并列的一条独立 gesture**，由开关决定是否算"已处理"。

### 3) 控制开关：`vivo_jovi_power_wakeup_switch`

`VivoInputPolicy.updatePowerKeyTriggerJoviVoiceSettings()`：

```
0x48cab4  const-string/jumbo v1, "vivo_jovi_power_wakeup_switch"
0x48cabe  Settings$System.getIntForUser(cr, v1, default, userId) → mPowerKeyTriggerJoviVoiceEnabled
0x48cad6  const-string/jumbo v1, "com.vivo.ai.base.copilot" + "copilot_support_jovi_action" → checkAppMetaData(...)
0x48caea  const-string/jumbo v5, "vivo_jovi_power_wakeup_guide_shown" → mJoviGuideShown
```

`startJoviVoice()` 里对应判断：

```
0x48c07c  iget-boolean mPowerKeyTriggerJoviVoiceEnabled
0x48c0bc  if (!enabled) { VLog "power key trigger jovi voice not enable"; return; }
```

→ **把 `vivo_jovi_power_wakeup_switch` 设为 0，vivo 就不再消费长按电源键**，`mPowerKeyHandledByJovi=false`。

### 4) 标准 role 的读取点（证明体系仍在用 role，只是不在电源键这条路上）

`VivoInputPolicy.isShowGoogleAssistGuide()`：

```
const-string "com.android.systemui"
const-string "assistant"
Settings$Secure.getStringForUser(cr, "assistant", userId)
... 与 "com.google.android.googlequicksearchbox" 比较
日志: "only default GoogleAssist support show GoogleAssisGuide , current assistant is "
```

即 vivo 只在"是否弹 Google 助手引导"时读当前 assistant/role，**没有用它来决定长按电源键启谁**。

### 5) 预装 APK 没有任何一个声明标准助手

扫了 225 个 `/system/{app,priv-app}/*.apk` 的 `AndroidManifest.xml`（UTF-16 字符串池）：

- `android.intent.action.ASSIST` / `android.service.voice.VoiceInteractionService`：**0 个命中**
- `com.vivo.intent.action.WAKEUP_AGENT_BY_POWER`：命中的只有
  - `/system/app/BaseCopilot/BaseCopilot.apk` → 包名 **`com.vivo.ai.base.copilot`**
  - `/system/priv-app/AiAgent/AiAgent.apk` → 包名 **`com.vivo.agent`**

BaseCopilot 清单里的相关 action（同一字符串池）：

```
com.vivo.intent.action.WAKEUP_AGENT_BY_POWER
com.vivo.intent.action.WAKEUP_AGENT_BY_BACKSTAGE
com.vivo.intent.action.WAKEUP_AGENT_BY_SLIP_UP
com.vivo.intent.action.WAKEUP_ORIGIN_5_JOVI
com.vivo.intent.action.POWER_KEY_GUIDE
android.intent.action.VOICE_COMMAND
vivo.assistant.setting.search
com.vivo.ai.base.copilot.settings.SettingsActivity
```

→ copilot 系列**靠私有广播被唤起**，不是标准 assistant。
（重要限制：设备上实际在跑的 `com.vivo.ai.copilot` 是 `/data/app` 里的更新包，proot 读不到，无法直接核它的 manifest；上面结论基于系统镜像里的 `com.vivo.ai.base.copilot`/`com.vivo.agent` 以及 framework 里写死的逻辑。）

### 6) 之前那条 `allow-in-power-save` 线索

`/system/etc/permissions/platform.xml:380`

```xml
<!-- liuchanglong app needs to run in the background begin-->
<allow-in-power-save package="com.vivo.ai.copilot" />
```

只是电源白名单（让 copilot 常驻后台），**不决定唤起来源**，但侧面印证 copilot 是厂商重度定制应用。

---

## 二、「如果我们已经拿到 ASSISTANT 角色，长按电源键会生效吗？」

**默认不会。** 需要同时满足两条：

1. `Settings.System.vivo_jovi_power_wakeup_switch == 0`（vivo 私有通路关闭）；
   否则 `startJoviVoiceByPowerKey()` 返回 true，长按被 vivo 吃掉（`mHandledByLongPress=true`）。
2. `config_longPressOnPowerBehavior` resolve 成 **5（LONG_PRESS_POWER_LAUNCH_ASSISTANT）**；
   若被设成 1（全局菜单）/2（关机）等，长按也不会走 `launchAssistAction`。
   *（该资源在 `framework-res.apk` 里存在；本次未解析 arsc 取实际整数值——要确认得用 `settings`/`aapt` 或特权 shell。）*

另外，即使两条都满足，也只是回到 AOSP 的 `launchAssistAction` → 读 `Settings.Secure.ASSISTANT` / ASSISTANT role。

---

## 三、如果写死，除了 `pm disable-user` 还有什么办法（按推荐度）

1. **关掉 vivo 自己的开关（首选）**
   `settings put system vivo_jovi_power_wakeup_switch 0`
   - 需要 shell/root，或一个持有 `WRITE_SETTINGS` 且该 key 未被 SettingsProvider 限制的 App。
   - 改完 vivo 会走标准 `launchAssistAction`，第三方助手才有机会。
   - 值可以用 `settings get system vivo_jovi_power_wakeup_switch` 先看默认。
2. **走 vivo 自己的设置入口**
   BaseCopilot 有 `com.vivo.ai.base.copilot.settings.SettingsActivity` 和
   `vivo.assistant.setting.search`（设置内搜索索引）；AiAgent 声明了 `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS`。
   在「设置」里搜「助手 / 数字助理 / 电源键 / Jovi」应能找到对应开关（多半就是上面那个 key 的 UI）。
3. **只 `pm disable-user com.vivo.ai.copilot` 不够** ⚠️
   `startJoviVoice()` 是"哪家 vivo agent 装着就用谁"的回退链：
   `com.vivo.ai.copilot` → `com.vivo.ai.base.copilot` → `com.vivo.agent` → `com.vivo.base.agent`。
   停掉 copilot 只会把长按交给 `com.vivo.ai.base.copilot` 或 `com.vivo.agent`，**仍然不是第三方助手**。
   要停就得整条链一起停，代价大且容易连带系统 UI/语音功能异常。
4. **拿 ASSISTANT + 关开关** 组合，是唯一"干净"的路线。

---

## 四、复现（全部只读，不改系统）

```bash
# 1. 解出 dex
python3 - <<'EOF'
import zipfile
for tag,p in [('services','/system/framework/services.jar'),
              ('vivo-services','/system/framework/vivo-services.jar')]:
    z=zipfile.ZipFile(p)
    for n in z.namelist():
        if n.endswith('.dex'): open('/tmp/%s_%s'%(tag,n),'wb').write(z.read(n))
EOF

# 2. 反汇编（dexdump 来自 /apex/com.android.art/bin，proot 内可直接跑）
export ANDROID_ROOT=/system
export LD_LIBRARY_PATH=/system/lib64:/apex/com.android.art/lib64:/apex/com.android.runtime/lib64
/apex/com.android.art/bin/dexdump -d /tmp/vivo-services_classes.dex > /tmp/vs.txt
/apex/com.android.art/bin/dexdump -d /tmp/services_classes2.dex  > /tmp/s2.txt

# 3. 定位
grep -n "com.vivo.ai.copilot\|vivo_jovi_power_wakeup_switch\|WAKEUP_AGENT_BY_POWER" /tmp/vs.txt | head
grep -n "MSG_KEY_LONG_PRESS_FOR_JOVI\|startJoviVoiceByPowerKey\|launchAssistAction" /tmp/s2.txt | head

# 4. 清单扫描
python3 - <<'EOF'
import zipfile,glob
for a in sorted(set(glob.glob('/system/priv-app/*/*.apk')+glob.glob('/system/app/*/*.apk'))):
    try: m=zipfile.ZipFile(a).read('AndroidManifest.xml')
    except Exception: continue
    hits=[k for k in ('android.intent.action.ASSIST','VoiceInteractionService','WAKEUP_AGENT_BY_POWER')
          if k.encode('utf-16-le') in m or k.encode() in m]
    if hits: print(a,hits)
EOF
```

工具：`binutils`(as/ld，本次没用上)、Android 自带 `dexdump`、`/root/workspace/scripts/axml.py`（二进制清单解析）、`/root/workspace/scripts/dexscan.py`（dex 字符串→方法归属，备用）。

## 五、不确定项（要特权的）

- `com.vivo.ai.copilot`（/data/app 更新包）的真实 manifest：是否声明了 ASSIST / VoiceInteractionService。
- 当前 `vivo_jovi_power_wakeup_switch` 的实际值（`settings get system ...`）。
- `config_longPressOnPowerBehavior` 的实际整数值（resolve 结果）。
- 当前 ASSISTANT role holder（`cmd role get-role-holders android.app.role.ASSISTANT`）。

这四项都能在拿到 shell（`rish` 打通后）一条命令确认。**在此之前，"要不要停用蓝心小V"的答案：不用停它，先关 `vivo_jovi_power_wakeup_switch`。**