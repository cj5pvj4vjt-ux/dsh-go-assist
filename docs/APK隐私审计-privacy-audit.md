# vivo X200 国行机预装组件隐私回传审计

- **设备**：vivo X200（V2415A），Android 16，ROM `PD2415_A_16.1.12.28.W10`
- **审计环境**：手机内 proot Ubuntu 24.04（非真 root，`/data` 未挂载），仅 /system 只读
- **方法**：APK 直接解包（python zipfile）→ 自写 AXML 解析器读 AndroidManifest → DEX 字符串池全量提取 → androguard 4.1.4 反编译/反汇编定位引用链 → 与公开报道交叉核对
- **分析时间**：2026-11-06
- **只读声明**：全程未卸载、未冻结、未修改任何系统组件，未执行 root 命令

---

## 一、结论摘要

**1. 本机确实预装了联通 SystemHelper，包名与小米事件报道完全一致。**

```
/system/app/SystemHelper/SystemHelper.apk   4,107,356 B
package   = com.mobiletools.systemhelper
versionName = 3.0.1-20250515   (versionCode 31, targetSdk 33, minSdk 31)
签名       = META-INF/SIG-vSigner.txt → "1.2 (vSigner-16 @loki) [signer: platform,old_platform]"
```

**2. 上报地址就是报道中的联通 DM 注册接口，本机 APK 里存在原始字符串：**

```
http://dm.wo.com.cn:18080/register
```

出处：`SystemHelper.apk!/classes.dex` 字符串池 **string_id 8045**，文件偏移 **0x149029**；
该字符串是静态字段 `c.d.a.b.d.l` 的**编译期初值（DEX static_values，不经 const-string 指令加载）**，
运行时由 `c.d.a.b.l.h(Context)` 取出拼接进 POST URL。证据强度：**实锤**。

**3. 上报内容是本机全部「应用清单 + 使用时长 + 流量」与「双卡 IMEI/IMSI/ICCID + 基站信息」的组合。**
载荷字段名已从 `com.mobiletools.systemhelper.control.b` 的 JSON 序列化代码逐个读出（见 §3.3）。证据强度：**实锤**。

**4. 关键判定：本机的开关不是「默认关」，而是开机向导主动写开的。**
vivo 开机向导 `com.vivo.setupwizard.ui.ServiceSetupActivity.Q()` 在**首次开机**时执行：

```java
if (N("com.mobiletools.systemhelper")) {
    Settings.Secure.putInt(cr, "operator_service_improve_plan", 1);
}
```

而 SystemHelper 的放行条件正是这个键（vivo 分支专门写了判断）：

```java
isUserPermissionOpen = Settings.Global.getInt(cr,"device_provisioned",0)==1
                    && Settings.Secure.getInt(cr,"operator_service_improve_plan",0)==1;
```

→ **首次开机向导走完 = 回传开关被置 1 = SystemHelper 开始上报。**
证据强度：**实锤**（两侧代码与字符串均在 APK 内定位到具体方法）。

**5. 用户可见的开关存在，但它藏在「用户体验改进计划」页里，且可能被隐藏。**
`Settings.apk` 里只有 `user_experience_improve_plan`（vivo 用户体验改进计划），**没有** `operator_service_improve_plan`。
真正渲染这个开关的是 **LogSystem**（`com.bbk.iqoo.logsystem`）里的 `q0.a`（`UserFeedBackFragment`）：

```java
this.V0 = (CheckBoxPreference) d("operator_service");        // 勾选框，key = "operator_service"
if (!p3.a.d(this.X0)) {                                      // 不满足条件就整块移除
    M1().removePreference(this.V0);
    M1().removePreference(d("about_operator"));
    ...
}
// p3.a.d(Context):
String opEntry = SystemProperties.get("ro.vivo.op.entry", "").toUpperCase();
if (opEntry == null || !p3.a(...).contains("UNICOM") && !opEntry.contains("FULL")) return false;
```

→ **开关存在，但仅当 `ro.vivo.op.entry` 含 `UNICOM` 或 `FULL`、且 SystemHelper 已安装时才显示。**
本机 `ro.vivo.op.entry` 无法读取（`/system/build.prop` 权限 600，`/system/etc/release_name_cfg.ini` 只覆盖到 PD16xx 老机型），
故**该开关在本机到底是可见还是被隐藏，存疑**。证据强度：UI 逻辑 **实锤**，本机可见性 **存疑**。

> 但无论 UI 是否可见，`SetupWizard.Q()` 的写入是**无条件的**（只判断包是否存在，不判断 op entry），
> 所以「首启后开关=1」这一条不受影响。

**5b. ROM 自己的隐私声明已经把这件事写清楚了（厂商自述，最有力的旁证）。**
`LogSystem.apk!/resources.arsc` 原文：

> 「启用后，选定的运营商将可能通过 WLAN 或移动数据网络收集您的设备信息（包括您的设备型号、硬件及软件版本号）、
> 设备和卡识别数据（包括 **IMEI、ICCID、IMSI、CELLID**）和部分位置数据（包括 **LAC 位置区码、接入基站位置及基站 ID**）。」
>
> 「本服务调用的权限包括：1. 定位：用于获取运营商网络当前接入基站位置及基站 ID 信息；
> 2. 访问手机识别码：读取运营商定制终端设备串号、IMSI，用于运营商定制终端销售、入网情况经营分析；
> 3. 联网：通过网络上报自注册信息。」
>
> 「运营商服务主要通过 WLAN 或移动数据网络收集您的设备信息等数据，上报给选定的服务运营方。
> 开启即视为同意联网、**读应用软件列表**、读设备识别码（如 **IMEI、WLAN MAC 地址**）、定位及权限使用说明。」
>
> 「如果您不想分享这些信息，您可以随时前往"设置"中的"运营商服务"，查看并关闭"运营商服务"开关，
> **如未找到请咨询联通官方客服**。」

→ 官方文案直接把「运营商」指向**联通**，并说明这就是「运营商定制终端销售、入网情况经营分析」用途。证据强度：**实锤**。

**6. ROM 对这个包有 4 处专门「照顾」，不是普通第三方预装：**

| 位置 | 内容 | 含义 |
|---|---|---|
| `/system/etc/vivo-default-permissions/default-permissions-vivo.xml:191` | 默认授予 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` / **`ACCESS_BACKGROUND_LOCATION`** / `READ_EXTERNAL_STORAGE` / `READ_PHONE_STATE` | 免用户授权即拿到后台定位与电话状态（实锤） |
| `/system/etc/uepp/plugins/com.ueppp.misc.time.AlarmAlign/PD2415.xml:53` | `op_entry_alarm_white_list` 里与 `com.vivo.devicereg` 并列 | 它的定时闹钟不被对齐/延迟，可准点上报（实锤，语义强推断） |
| `/system/etc/uepp/plugins/com.ueppp.misc.frozen.FrozenPlugin/PD2415.xml:339` | `frozen_list/black_list` 成员 | 系统冻结策略对它有专门配置（实锤，语义强推断） |
| `AppBehaviorEngine.apk!/assets/abe_v2_com_vivo_abe_list_4G.xml` | `<item name="com.mobiletools.systemhelper" maxpower="100" maxPowerScreenOff="100" isNeedNotifi="0">` | **息屏后仍拿满功耗配额**，后台不被限制（实锤） |

**7. 除 SystemHelper 外，本机没有第二个组件回传联通域名。**
225 个可读预装 APK 全量扫域名，命中 `wo.com.cn / unicom / 10010 / wol.cn` 的**只有 SystemHelper 一个**。
vivo 自家组件回传的是 vivo 域（`*.vivo.com.cn`、`*.vivoglobal.com`），性质不同（见 §4）。

**8. 关于「录屏上传」：本机 SystemHelper 里没找到。**
`MediaProjection` / `MediaRecorder` / `VirtualDisplay` 在 SystemHelper 的 DEX 中**零命中**；
`CAPTURE_VIDEO_OUTPUT` 等权限也未申请。小米事件里的「3 秒录屏」属于小米「系统安全组件」，本机无对应物。
（本机 `PcSuite`、`SafeCenter`、`SystemUI` 确有屏幕采集权限，但用途是投屏/截屏/远程协助，见 §2 备注。）证据强度：**实锤（无该能力）**。

---

## 二、组件清单表

目录口径：`/system/app` 153 项 + `/system/priv-app` 72 项 = **225 个 APK**，其中 **126 个申请了 INTERNET**，**92 个申请了 RECEIVE_BOOT_COMPLETED**。

### 2.1 重点关注组件

| 组件目录 | 包名 | 版本 | 大小 | 敏感权限（节选） | 组件面 | 回传域名 | 评级 |
|---|---|---|---|---|---|---|---|
| **SystemHelper** (app) | `com.mobiletools.systemhelper` | 3.0.1-20250515 | 4.1 MB | READ_PHONE_STATE, **READ_PRIVILEGED_PHONE_STATE**, READ_PRECISE_PHONE_STATE, PACKAGE_USAGE_STATS, QUERY_ALL_PACKAGES, ACCESS_BACKGROUND_LOCATION, SCHEDULE_EXACT_ALARM, INTERNET | 7 receiver（4 个 exported）+ 4 service（1 个 exported AIDL） | **dm.wo.com.cn:18080** + baidu LBS | 🔴 最高 |
| SafeCenter (app) | `com.vivo.safecenter` | 6.1.0.6 | 15.0 MB | READ_SMS, READ_CALL_LOG, READ_CLIPBOARD_IN_BACKGROUND, MANAGE_MEDIA_PROJECTION, GET_TASKS, WRITE_SECURE_SETTINGS, READ_PRIVILEGED_PHONE_STATE | 7 recv / 24 svc / 39 act / 8 prov | `isecure-pre.vivo.com.cn/secure/{cb/app,cb/sms,cb/tel,cb/url,event/log/report}.do`、`api.telesafe.qq.com/FraudReport` | 🟠 高（云查杀/号码库/事件日志） |
| Baidu (priv-app) | `com.baidu.input_vivo` | 8.5.204.1879 | 105 MB | READ_CONTACTS, RECORD_AUDIO, READ_SMS, CAMERA, READ_LOGS(priv 声明), GET_TASKS | 390 activity / 25 provider | 百度输入法自有域（`bdbus-turbonet.baidu.com` 等），大量测试环境地址残留 | 🟠 高（输入法本体，非静默回传） |
| ConnBase (priv-app) | `com.vivo.connbase` | 3.1.0.1 | 34.6 MB | READ_WIFI_CREDENTIAL, OVERRIDE_WIFI_CONFIG, BLUETOOTH_PRIVILEGED, READ_PRIVILEGED_PHONE_STATE, MODIFY_PHONE_STATE, INTERACT_ACROSS_USERS | 2 recv / 16 svc / 8 prov | `*.vivo.com.cn`（账号、连接中心、埋点 `moni-*stsdk`） | 🟡 中 |
| IoTServer (priv-app) | `com.vivo.iotserver` | 2.3.0.0 | 8.1 MB | ACCESS_FINE_LOCATION, BLUETOOTH, ACCESS_DOWNLOAD_MANAGER | 36 svc | `iot-peripheral.vivo.com.cn`、`kernelapi.vivo.com.cn/location.do`、`vhs.wwstat.com` | 🟡 中 |
| NumberMark (priv-app) | `com.vivo.numbermark` | 9.1.0.1 | 9.8 MB | READ_CALL_LOG, WRITE_SECURE_SETTINGS, QUERY_ALL_PACKAGES, MANAGE_EXTERNAL_STORAGE | 4 recv / 3 svc / 5 prov | `comm.vivo.com.cn`、`md.vivo.com.cn`、`vnoteweb.test.vivo.xyz/api1.do`（号码标记库） | 🟡 中 |
| VFileObserver (priv-app) | `com.vivo.fileobserver` | 1.0.4.2 | 0.98 MB | MANAGE_EXTERNAL_STORAGE, RECEIVE_BOOT_COMPLETED, INTERNET | 2 recv / 2 prov | 无域名提取到 | 🟢 低 |
| Singularity (priv-app) | `com.vivo.singularity` | 4.2 | 17 KB | 无 | 1 activity | 无 | 🟢 低（见备注） |
| VmdriService (priv-app) | `com.vivo.vmdri` | 14 | 1.5 MB | WRITE_SECURE_SETTINGS, READ_NETWORK_USAGE_HISTORY, ACCESS_FINE_LOCATION | 1 recv / 2 svc | 无域名（走 vivo 内部） | 🟢 低 |
| UpSlide (priv-app) | `com.vivo.upslide` | 16.0.2.46 | 23.9 MB | INJECT_EVENTS, READ_FRAME_BUFFER, GET_DETAILED_TASKS, MONITOR_INPUT, STATUS_BAR | 2 recv / 8 svc / 5 prov | 无独立回传域 | 🟡 中（权限极重，用途=状态栏手势） |
| PcSuite (priv-app) | `com.vivo.pcsuite` | 6.0.0.11 | 29.2 MB | **CAPTURE_VIDEO_OUTPUT**, **READ_FRAME_BUFFER**, **TAKE_SCREEN_SHOT**, MANAGE_MEDIA_PROJECTION, RECORD_AUDIO, INJECT_EVENTS, PACKAGE_USAGE_STATS | 6 recv / 14 svc / 5 prov | `pc.vivo.com`、`psuite.vivo.com.cn`、`cross-file-bridge.vivo.com` | 🟠 高（有屏幕采集能力，但需用户主动开「多屏协同」） |
| ONS (priv-app) | `com.android.ons` | 16 | 95 KB | RECEIVE_BOOT_COMPLETED, READ_PHONE_STATE | 2 recv / 1 svc | 无 | 🟢 低（AOSP 原生 OpportunisticNetworkService） |

### 2.2 另外值得单独列出的（本机回传量较大）

| 组件 | 包名 | 版本 | 说明 |
|---|---|---|---|
| LogSystem | `com.bbk.iqoo.logsystem` | 6.0.16.11 | READ_LOGS + PACKAGE_USAGE_STATS + READ_PRIVILEGED_PHONE_STATE，回传 `err-up.vivo.com.cn`（错误日志）。**它才是渲染「运营商服务」开关的一方**（`q0.a`，pref key `operator_service` → 写 `operator_service_improve_plan`）。 |
| DeviceReg | `com.vivo.devicereg` | 5.4.0.0 | READ_LOGS + READ_PRIVILEGED_PHONE_STATE + ACCESS_BACKGROUND_LOCATION；域名含 `auc.fxltsbl.com/AuthCenter/datareport`、`zzhc.vnet.cn`（中国电信账号）。与 SystemHelper 一同在闹钟白名单里。 |
| AppBehaviorEngine | `com.vivo.abe` | 5.17.0.11 | 行为/功耗引擎，自带保活白名单（含 SystemHelper）、READ_FRAME_BUFFER、REBOOT、DELETE_PACKAGES。 |
| VivoDaemonService / SuperProcessSystem | `com.vivo.daemonService` / — | 1.0 | 守护进程白名单里含 `com.mobiletools.systemhelper`。 |

> **Singularity 备注**：`com.vivo.singularity` 整个 DEX 只有 38 个字符串，唯一逻辑是 `Runtime.exec("chmod o+x <file>")`（日志串 `chmod success.` / `chmod failed.`、常量 `V5Apk`）。它没有 INTERNET 权限，**不是回传组件**，但是一个「能被唤起去改文件权限位」的 stub，属于可疑设计而非隐私问题。（实锤：无网络能力）

---

## 三、SystemHelper 重点分析

### 3.1 身份与签名

| 项 | 值 | 出处 |
|---|---|---|
| APK | `/system/app/SystemHelper/SystemHelper.apk` (4,107,356 B) | 文件系统 |
| package | `com.mobiletools.systemhelper` | AndroidManifest.xml |
| versionName / Code | `3.0.1-20250515` / `31` | AndroidManifest.xml |
| min / target SDK | 31 / 33 | AndroidManifest.xml |
| sharedUserId | `com.mobiletools.systemhelper.uid` | AndroidManifest.xml |
| 签名 | `1.2 (vSigner-16 @loki) [signer: platform,old_platform] [multi-signer]` | `META-INF/SIG-vSigner.txt` |
| usesCleartextTraffic | **true** | AndroidManifest.xml（使 HTTP 明文上报可行） |
| 百度 LBS Key | `IGY6EFGRsixbYqk7GeZdAOD2Od3Rj7iF` | `<meta-data android:name="com.baidu.lbsapi.API_KEY">` |
| 编译工具 | R8 1.6.67（`~~R8{"compilation-mode":"release","min-api":31,"pg-map-id":"c22b444","version":"1.6.67"}`） | classes.dex |

> **平台签名的影响**：`READ_PRIVILEGED_PHONE_STATE` 的 protectionLevel 是 `signature|privileged`，平台签名即可获得。虽然 SystemHelper 装在 `/system/app`（非 priv-app）、也未出现在 `/system/etc/permissions/privapp-permissions-*.xml` 白名单里，**但平台签名本身足以拿到读取 IMEI/IMSI/ICCID 的权限**。证据强度：**强推断**（依据签名文件 + 权限语义；无法在无 root 环境 ls 运行期权限）。

### 3.2 声明权限（17 项，原文照录）

```
android.permission.INTERNET
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.ACCESS_FINE_LOCATION
android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_BACKGROUND_LOCATION
android.permission.READ_EXTERNAL_STORAGE
android.permission.ACCESS_NETWORK_STATE
android.permission.READ_PHONE_STATE
android.permission.ACCESS_WIFI_STATE
android.permission.CHANGE_WIFI_STATE
android.permission.FOREGROUND_SERVICE
android.permission.ACCESS_LOCATION_EXTRA_COMMANDS
android.permission.READ_PRECISE_PHONE_STATE
android.permission.READ_PRIVILEGED_PHONE_STATE
android.permission.PACKAGE_USAGE_STATS
android.permission.QUERY_ALL_PACKAGES
android.permission.SCHEDULE_EXACT_ALARM
```

关键三组：
- `READ_PHONE_STATE` + `READ_PRECISE_PHONE_STATE` + `READ_PRIVILEGED_PHONE_STATE` → IMEI / IMSI / ICCID / 双卡 / 小区信息
- `PACKAGE_USAGE_STATS` + `QUERY_ALL_PACKAGES` → **应用清单 + 每 App 使用时长**
- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` + `ACCESS_LOCATION_EXTRA_COMMANDS` → 后台定位

### 3.3 上报载荷（字段名逐个来自代码）

`com.mobiletools.systemhelper.control.b` 的 JSON 序列化方法里，`JSONObject.put()` 的 key 原文：

```
Manuf, Model, HWVersion, SWVersion, OS, OSVersion,
IMEI1, ICCID1, IMSI1, MNC1, NetworkType1, TAC1, CID1, PCI1, VolteEnabled1, 5GEnabled1, VoNREnable1,
IMEI2, ICCID2, IMSI2, MNC2, NetworkType2, TAC2, CID2, PCI2, VolteEnabled2, 5GEnabled2, VoNREnable2,
DataSlot, AccType, IPv6, DeviceType, RegType, SpecVersion("5.0"), ClientVersion, Time,
SigInfo  (JSONArray), AppInfo (JSONArray)
```

> 即：**双卡各自的 IMEI/ICCID/IMSI + 基站 TAC/CID/PCI + 5G/VoLTE/VoNR 状态 + 应用清单 + 信号历史**。

`AppInfo` 数组的每个元素（`control.f` 采集）：
- `getPackageName()` —— 包名
- `ApplicationInfo.uid` —— uid
- `((String) ai.loadLabel(pm))` —— **应用显示名**
- `UsageStats.getTotalTimeInForeground() / 60000` —— **前台使用分钟数**
- `NetworkStatsManager.querySummary(subId, uid, ...)` 累计 `RxBytes` / `TxBytes` —— **每 App 移动数据 + Wi-Fi 上下行字节数（/1024）**

采集窗口：`UsageStatsManager.queryAndAggregateUsageStats(begin, end)`，配合 `GlobalSetting.s/t = 86,400,000 ms`（24 小时）。

### 3.4 上报地址与请求构造（原始字符串 + 类名）

**地址（唯一非百度域名）：**

```
http://dm.wo.com.cn:18080/register
```

- 位置：`classes.dex`，UTF-8 串池 `string_id=8045`，文件偏移 `0x149029`
  - 原始字节：`22 "http://dm.wo.com.cn:18080/register\0`（`0x22`=长度 34）
- **不是**通过 `const-string` 指令加载（全 DEX 扫描 `1a ?? 6d 1f` 无命中），而是静态字段初值：

```java
// c.d.a.b.d (GlobalSetting)
public static String l = "http://dm.wo.com.cn:18080/register";
public static boolean c = true;      // 常量，从不被写
public static boolean e = false;     // 运行期由 l.a(Context) 赋值
public static long s = 86400000L, t = 86400000L, u = 43200000L, v = 1800000L;
```

- 取值点：`Lc/d/a/b/l;->h(Landroid/content/Context;)Ljava/lang/String;` 内有
  `sget-object v0, Lc/d/a/b/d;->l Ljava/lang/String;`
- **可被改写**：`c.d.a.b.l.a(Context, String)` 会把 `serverAddress` 写进
  `/data/data/com.mobiletools.systemhelper/shared_prefs/com_mobiletools_systemhelper_preference.xml`
  → 服务端地址可被预置/下发替换。（实锤：prefs 键名 `serverAddress` 在 DEX 里）

**POST 拼装**（`com.mobiletools.systemhelper.control.g.a(deviceInfo)` 反编译原文）：

```java
String body = deviceInfo.a();                       // JSON
byte[] k    = c.d.a.b.c.a(body);                    // MD5(JSON)
String sign = URLEncoder.encode(
      c.d.a.b.c.a( c.d.a.b.c.a(ctx, k) ), "utf-8"); // AES/ECB/PKCS5(body, key) → 再 …→ Base64
String url  = c.d.a.b.l.h(ctx)                      // ← http://dm.wo.com.cn:18080/register
            + "?ver=5.0"
            + "&model="  + URLEncoder.encode(deviceInfo.b)
            + "&manuf="  + URLEncoder.encode(deviceInfo.a)
            + "&sign="   + sign;
c.d.a.b.f.a(url, cipherText, key);                  // HttpHelper 发 POST
```

加密相关原文（`c.d.a.b.c`）：
- `MessageDigest.getInstance("MD5")`
- `new SecretKeySpec(p5, "AES")` + `Cipher.getInstance("AES/ECB/PKCS5Padding")`
- `ctx.getResources().getAssets().open("pub.cer")` ← 密钥材料来自 APK 内 `assets/pub.cer`（596 B，CN/BJ 自签 X.509）

> 因此：**URL 查询串是明文的（含 ver/model/manuf 和 sign 密文），POST body 是 AES 密文。**
> 抓包只能看到 `model` / `manuf` / `ver=5.0` 和一段 base64，看不到应用清单原文。

### 3.5 触发条件（RegType）

| 触发 | 注册的组件 | RegType | 说明（日志原文） |
|---|---|---|---|
| 开机 | `control.OnBootReceiver`（`BOOT_COMPLETED` + `QUICKBOOT_POWERON`，`priority=0x7fffffff`） | **B** | `" boot delayed for 4 minutes-->"` → 延迟 4 分钟再跑 |
| 首次开机 | 同上（`firstPowerOn` 为空） | **F** | `"F"` 后写入 `firstPowerOn` 标记 |
| 定时 | `control.ScheduleRegisterReceiver`（AlarmManager，基准 `d.s/t=24h` + 随机偏移） | **S** | 周期性上报 |
| SIM 状态变化 | `control.SIMStateReceiver`（`SIM_STATE_CHANGED`） | **T** | 仅「ICCID 变了」才发：`"SIM not changed，not post！"` |
| 网络变化 | `control.ConnectStateReceiver`（`CONNECTIVITY_CHANGE`） | — | 补发数据库里积压的记录 |
| 应用信息 | `control.AppInfoRegisterReceiver` | **A** | 应用清单线程 `QueryAppInfoThread` |
| 信号信息 | `control.SignalInfoRegisterReceiver` / `GetSignalInfoReceiver` | **R** | 基站/信号历史 |

发不发的判定链（`control.g.b()` 反汇编，日志串为原文）：

```
"is unicom network!" / "is unicomnetwork || is wifi connected!"  → 满足则 a(deviceInfo) 发 POST
否则 → "sava to database!" + "Not post!"                        → 先落 SQLite，等下次补发
```

`isUnicomNetwork()` 之外还有一条 **Wi-Fi 分支**：即使插的是移动/电信卡，连上 Wi-Fi 也会发。
另有 `"MNC1 is not startwith 460!"` / `"MNC2 is not startwith 460!"` / `"mnc is not in china!"`
→ **只要求「中国大陆运营商 SIM」，不要求联通卡**。与公开报道一致。

### 3.6 对外暴露面（这是本组件最容易被利用的地方）

```xml
<activity  name="...Activity.TestActivity" android:exported="true">
    <action name="com.mobiletools.systemhelper.activity.testactivity"/>
<service   name="...service.RegisterHelperService" android:exported="true">
    <action name="com.mobiletools.systemhelper.service.IRegisterHelperService"/>
<receiver  name="...control.OnBootReceiver" android:exported="true">   <!-- priority=0x7fffffff -->
<receiver  name="...control.SIMStateReceiver" android:exported="true">
<receiver  name="...control.ConnectStateReceiver" android:exported="true">
```

`RegisterHelperService` 实现 AIDL `com.mobiletools.systemhelper.service.IRegisterHelperService`：

```java
void a(String);   // 设置字符串（可用于改 serverAddress）
void a(int);
void b();
void a(boolean);  // ← 布尔开关，对应 isUserPermissionOpen 相关控制
boolean a();
```

→ **任何第三方 App 只要能 bind 到这个 service，就能调用这些方法**（exported=true 且未声明 `android:permission`）。
这是一个可被本地应用触发/改写的上报通道。证据强度：**实锤**（manifest + 反编译的 `onTransact`）。

### 3.7 百度定位 SDK

- `classes.dex` 内含 `com.baidu.location.f`（注册为 `:remote` 进程 service）、`lib/arm64-v8a/liblocSDK8b.so`、`lib/arm64-v8a/libindoor.so`
- 域名（全部为百度 LBS 标准域）：
  `loc.map.baidu.com`、`ofloc.map.baidu.com`、`api.map.baidu.com`、`daup.map.baidu.com`、
  `itsdata.map.baidu.com`、`parking.baidu.com`
- 另有百度 LBS 自带的定位遥测格式串（类 `a.a.a.c.d` → `https://daup.map.baidu.com/cltr/rcvr`）：
  `{"ver":"%d","gps":"%.1f|%.1f|%.1f|%.1f|%d|%d|%d|%d|%d|%d|%d","up":"%.1f|%.1f|%.1f|%.1f","wf":"%d|%.1f|%d|%.1f","ab":"%.2f|%.2f|%d|%d","gpc":"%d|%d|%d|%d|%d|%d","zxd":"%.1f|%.1f|%d|%d|%d","shak":"%d|%d|%.1f","dmx":%d}`

→ 结论：SystemHelper 顺带把百度定位 SDK 一起打包，等于**上报时可能带地理坐标**。证据强度：**强推断**（字段 `gps` 格式确认存在，但是否每包都带未运行期验证）。

### 3.8 有没有录屏 / 屏幕采集

| 关键字 | DEX 命中 |
|---|---|
| `MediaProjection` / `mediaprojection` | 0 |
| `MediaRecorder` | 0 |
| `VirtualDisplay` | 0 |
| `CAPTURE_VIDEO_OUTPUT` / `CAPTURE_AUDIO_OUTPUT` / `READ_FRAME_BUFFER` / `TAKE_SCREEN_SHOT`（权限） | 0 |

→ **本机 SystemHelper 不具备录屏能力。** 证据强度：**实锤**。

### 3.9 ROM 侧的配套：`ro.vivo.op.entry`（运营商入口）

vivo 用系统属性 `ro.vivo.op.entry` 区分「运营商定制渠道」与「开放渠道（FULL）」。SystemHelper 的整条链路在<｜place▁holder▁no▁440｜>侧有两处网关：

1. `LogSystem` 里「运营商服务」勾选框的**可见性**（`p3.a.d()`：要求 `opEntry.contains("UNICOM") || opEntry.contains("FULL")`）
2. ROM 的闹钟策略节点名就叫 `op_entry_alarm_white_list`（`/system/etc/uepp/plugins/com.ueppp.misc.time.AlarmAlign/PD2415.xml`）

本机读取情况：
- `/system/build.prop` 权限 `600`、owner root，proot 下读不到 → **拿不到实际值**
- `/system/etc/release_name_cfg.ini` 里有 `ro.vivo.op.entry.version` 的映射表，但只覆盖到 `PD16xx` 老机型，**没有 PD2415**

→ 结论：本机 `ro.vivo.op.entry` 值**存疑**。若为 `FULL*`，则「运营商服务」开关可见、用户可自行关闭；
若为非 FULL 的定制渠道值，则开关被隐藏，只能用 adb。建议在实机上按 §6 A 档路径确认一次。

> 参考：设备代号 `PD2415`（对应 V2415A / X200）在 `/system/etc/uepp/plugins/*/PD2415.xml` 中出现，
> 与该机 ROM 版本 `PD2415_A_16.1.12.28.W10` 吻合，可作为「本报告分析的确实是本机 ROM」的佐证。

---

## 四、域名对照表（哪个 APK 连哪个域）

扫描范围：225 个可读预装 APK 的全部 `classes*.dex` 字符串池。

### 4.1 联通 / 反诈 / 上报类

| 域名（原始字符串） | 所属 APK | 包名 | 用途判断 | 强度 |
|---|---|---|---|---|
| `http://dm.wo.com.cn:18080/register` | SystemHelper | `com.mobiletools.systemhelper` | 联通设备管理（DM）注册上报 | 实锤 |
| `https://isecure-pre.vivo.com.cn/secure/event/log/report.do` | SafeCenter | `com.vivo.safecenter` | 安全事件日志上报 | 实锤（字符串）；用途强推断 |
| `https://isecure-pre.vivo.com.cn/secure/cb/app.do`<br>`.../cb/sms.do`<br>`.../cb/tel.do`<br>`.../cb/url.do` | SafeCenter | `com.vivo.safecenter` | 云端「拦截库」拉取（网址/短信/号码/应用黑白名单） | 实锤（字符串） |
| `https://api.telesafe.qq.com/FraudReport` | SafeCenter | `com.vivo.safecenter` | 腾讯天御反诈上报接口 | 实锤（字符串）；是否调用存疑 |
| `https://ro-up.vivo.com.cn/clientRequest/detectCollectControlMsg` | LogSystem | `com.bbk.iqoo.logsystem` | 日志采集开关指令 | 实锤 |
| `https://err-up.vivo.com.cn/errorlog/{askv3,awareV1,uploadv3/v0}` | LogSystem | `com.bbk.iqoo.logsystem` | 错误日志上报 | 实锤 |
| `https://auc.fxltsbl.com/AuthCenter/datareport?func=tsdk:photoReg` | DeviceReg | `com.vivo.devicereg` | 设备/账号数据上报（第三方域，可疑） | 实锤（字符串）；归属存疑 |
| `https://zzhc.vnet.cn/` | DeviceReg | `com.vivo.devicereg` | 中国电信账号体系 | 实锤 |

> 注：`isecure-**pre**` 是预发布环境前缀却出现在正式 ROM 里，可能是遗留；是否实际被调用**存疑**。

### 4.2 vivo 自家遥测（正常商业遥测，非联通合作）

| 域名族 | 出现于 |
|---|---|
| `accountinfo.vivo.com.cn`、`usrsys.vivo.com.cn` | SafeCenter、ConnBase、PcSuite、AppBehaviorEngine 等 |
| `moni-{onrt,ort,pnrt,prt}stsdk.vivo.com.cn/client/upload/report*` | ConnBase、PcSuite（SDK 埋点） |
| `vmd.vivo.com.cn/api/tkdist`、`asia-vmd.vivoglobal.com` | 大面积出现（推送/统计 SDK） |
| `content-aware.vivo.com.cn`、`jovision.vivoglobal.com` | AppBehaviorEngine、SafeCenter |
| `iot-peripheral.vivo.com.cn`、`kernelapi.vivo.com.cn/location.do` | IoTServer、NumberMark |
| `vhs.wwstat.com/v1/multiget.do` | IoTServer、NumberMark |

### 4.3 明确**未**出现在本机的（关键否定证据）

| 域名 | 来源 | 本机结果 |
|---|---|---|
| `fraud-detection.engine.miui.com/hyperos/fraud/app/report` | 小米「系统安全组件」 | **未出现**（225 个 APK 全扫，0 命中） |
| `flash.sec.miui.com/detect/app` | 小米「系统安全组件」 | **未出现** |
| 任何 `*.miui.com` 上报域 | 小米 | 仅 SafeCenter 里有一条 `http://connect.rom.miui.com/generate_204`（**网络连通性探测**，不是上报） |
| `dm.wol.cn` / `wol.cn`（报道里另一种写法） | 联通 | **未出现**；本机实际写的是 `dm.wo.com.cn` |
| 其他 `wo.com.cn` / `10010` / `unicom` 域 | 联通 | **仅 SystemHelper 一处** |

---

## 五、与小米事件逐条对照

| 小米事件里的说法 | 本机（vivo X200）实况 | 判定 |
|---|---|---|
| 预装包名 `com.mobiletools.systemhelper` | `/system/app/SystemHelper/SystemHelper.apk`，包名一致，v3.0.1-20250515 | ✅ **本机有** |
| 上报到联通 `dm.wo.com.cn:18080/register` | 同一字符串、同一路径、同一端口 | ✅ **本机有** |
| 收集已安装应用列表 | `QUERY_ALL_PACKAGES` + `loadLabel` + `getPackageName` | ✅ **本机有** |
| 收集每 App 使用时长 | `getTotalTimeInForeground()/60000` | ✅ **本机有** |
| 收集每 App 流量 | `NetworkStatsManager.querySummary()` 按 uid | ✅ **本机有** |
| 带 IMEI / IMSI / ICCID | 载荷字段 `IMEI1/2`、`IMSI1/2`、`ICCID1/2` | ✅ **本机有** |
| 不限联通卡，移动卡也触发 | 只校验 MNC 以 `460` 开头；另有 Wi-Fi 分支 | ✅ **本机有** |
| 跨品牌设备信息读取器（三星/一加/真我…） | DEX 中确有 `DeviceInfoReaderLiantong/VIVO/Xiaomi/OPPO/SamSung/OnePlus/Realme/ZTE/nubia/PTAC/TDTECH/WIKO`，**含 `DeviceInfoReaderVIVO`**（`Lc/d/a/a/j` 按 `Build.MANUFACTURER=="vivo"` 选用） | ✅ **本机有**（且本机命中的就是 vivo 分支） |
| 默认统计过去 24 小时有使用的应用 | `GlobalSetting.s/t = 86,400,000 ms` | ✅ **本机有** |
| 跳过 `com.android` 开头的系统应用 | 代码中有对应用清单的过滤，但**本次未逐条验证过滤条件** | ⚠️ **存疑** |
| 「3 秒录屏并加密上传」 | SystemHelper 无任何 MediaProjection/录屏权限与 API | ❌ **本机无**（该能力属小米「系统安全组件」，本机不存在小米组件） |
| 「须激活/首启后才上报」 | 门禁 = `device_provisioned==1` **且** `operator_service_improve_plan==1`；后者由开机向导写入 1 | ✅ **本机有，且默认被打开** |
| 「华为/荣耀不在名单」 | 本机为 vivo，不在华为名单，符合报道 | — 一致 |

**额外发现（公开报道里没有、本次新查到的）**
1. 门禁键 `operator_service_improve_plan` 由 **vivo 开机向导**写入 1（`SetupWizard.Q()`），不是 SystemHelper 自己写，且**无条件**（只判包是否存在）。
2. 该键在 **Settings.apk 中不存在**；真正渲染开关的是 **LogSystem**（`q0.a`），key = `"operator_service"`，
   且它的**可见性**受 `ro.vivo.op.entry` 是否含 `UNICOM`/`FULL` 控制。
3. ROM 自己的资源字符串就把用途写成「**运营商定制终端销售、入网情况经营分析**」，并提示「如未找到请咨询**联通**官方客服」。
4. ROM 在 **4 个位置**给这个包开了绿灯（默认权限、闹钟免对齐、冻结名单、息屏满功耗）。

---

## 六、处置建议（按风险分级）

> 前置说明：本机是 **非 root** 环境，且 **没有开启 adb 调试** 的话下面 B 档也做不了。
> 另外 SystemHelper 装在 `/system/app`，**普通卸载（含「设置-应用-卸载」）不可行**。

### A 档：只用系统设置就能做的（无需 adb / root）

| 动作 | 路径 | 效果 | 强度 |
|---|---|---|---|
| **关闭「运营商服务」开关**（首选） | **设置 → 更多设置 → 用户体验改进计划 → 运营商服务**（关） | 直接把 `operator_service_improve_plan` 写成 0，**掐断 SystemHelper 的唯一门禁** | 开关逻辑实锤；本机是否显示存疑（见 §1.5） |
| 撤销「使用情况访问」 | 设置 → 应用管理 → 特殊应用权限 → 使用情况访问 → 找到 SystemHelper → 关闭 | 让它拿不到 `queryAndAggregateUsageStats`，应用时长/流量为空；但 `QUERY_ALL_PACKAGES`（应用清单）仍生效 | **推荐，兜底有效** |
| 关闭定位总开关 | 设置 → 位置信息 → 关闭 | 切断 `ACCESS_BACKGROUND_LOCATION` 的实际数据源 | 有效 |
| 应用级网络限制 | 设置 → 应用管理 → SystemHelper → 流量使用 → 禁用后台数据 / 禁用 WLAN | 断网后进入「sava to database!」落库模式，不发出 | **推荐** |
| 关闭「用户体验改进计划」主开关 | 同上页面 → 加入用户体验改进计划（关） | **只影响 LogSystem 自身（`user_experience_improve_plan`）**，不必然关闭「运营商服务」子项 | ⚠️ 需单独确认子项 |
| 禁止后台运行 | 设置 → 电池 → 后台耗电管理 → SystemHelper → 限制 | 被 AppBehaviorEngine 白名单(`maxpower=100`)覆盖，**预计无效** | ⚠️ 可能无效 |

> ⚠️ 若在「设置 → 更多设置 → 用户体验改进计划」里**找不到「运营商服务」这一项**，
> 说明 `ro.vivo.op.entry` 不含 UNICOM/FULL，ROM 把这个开关**隐藏**了——
> 这种情况下 A 档无法关闭门禁，必须走 B 档（adb）。这也是厂商文案里写「如未找到请咨询联通官方客服」的原因。

### B 档：需要 adb（`pm disable-user` / `settings put`）

前置：设置 → 关于手机 → 版本信息 → 连点「软件版本号」开启开发者选项 → 打开 USB 调试 → 电脑执行 `adb devices` 确认。

```bash
# 1) 最直接：把门禁键改回 0（不需要 root，adb shell 有 WRITE_SECURE_SETTINGS）
adb shell settings put secure operator_service_improve_plan 0
adb shell settings get secure operator_service_improve_plan     # 应返回 0

# 2) 更彻底：直接停用组件（对 /system/app 应用有效，重启后仍生效直到恢复出厂）
adb shell pm disable-user --user 0 com.mobiletools.systemhelper
adb shell pm list packages -d | grep mobiletools                # 确认出现在 disabled 列表

# 3) 若 disable-user 被拒（部分 ROM 对平台签名应用返回 Failure），退回用 appops 掐权限
adb shell appops set com.mobiletools.systemhelper GET_USAGE_STATS deny
adb shell appops set com.mobiletools.systemhelper COARSE_LOCATION deny
adb shell appops set com.mobiletools.systemhelper FINE_LOCATION deny
adb shell pm revoke com.mobiletools.systemhelper android.permission.READ_PHONE_STATE
adb shell pm revoke com.mobiletools.systemhelper android.permission.ACCESS_BACKGROUND_LOCATION
```

- **影响**：停用后「联通 DM 注册」不再执行；不影响通话/短信/上网。副作用极小（该组件无用户可见 UI，只有一个 `TestActivity`）。
- **重启影响**：`pm disable-user` 是持久的，重启后仍停用。
- **恢复**：`adb shell pm enable com.mobiletools.systemhelper` / `adb shell settings put secure operator_service_improve_plan 1`。
- **OTA 影响**：系统更新可能重新写 `operator_service_improve_plan` 或重置 disabled 状态，**每次大版本更新后应复查**。

### C 档：需要 root 才能做的

```bash
# 彻底移除（先备份！）
adb root && adb remount
adb shell mv /system/app/SystemHelper /data/local/tmp/SystemHelper.bak
# 或 Magisk 模块直接 overlay 掉该目录
```

- **风险**：`/system/app` 改动会让 **OTA 校验失败**（vivo 有完整性校验），可能导致无法升级/需线刷恢复；`sharedUserId=com.mobiletools.systemhelper.uid` 删除后若残留 `/data/data` 目录，个别 ROM 会在首启时重建目录但不会重建应用，一般无副作用。
- **建议**：优先用 B 档 `pm disable-user`；root 移除仅在确认不需要 OTA 时使用。

### D 档：不建议的

- ❌ 不要用「冰箱/黑阈」类第三方冻结工具：会被 `AppBehaviorEngine` 白名单和 UEPP 冻结策略绕过，且可能触发系统异常。
- ❌ 不要改 `/system/etc/vivo-default-permissions/default-permissions-vivo.xml` 后未重新签名——会破坏 `/system` 校验，导致无法开机。
- ❌ 不要把 `serverAddress`（`com_mobiletools_systemhelper_preference.xml`）指向黑洞地址：该文件在 `/data`，无 root 改不了；有 root 直接禁用组件更简单。

### E 档：恢复出厂 / 换机的注意事项

| 动作 | 结果 |
|---|---|
| 恢复出厂设置 | `operator_service_improve_plan` 大概率被重置；**但开机向导会再次把它写成 1** → SystemHelper 回到上报状态。等于**恢复出厂 = 重新打开上报**。 |
| 关机重启 | 不影响：`OnBootReceiver` 每次开机都会跑（延迟 4 分钟）。**重启不能关掉它。** |
| 换 SIM 卡 | `SIM_STATE_CHANGED` → 若 ICCID 变了，触发一次 RegType `T` 上报。**换卡会触发一次上报。** |
| 换机迁移 | 该组件绑定 ROM，新机是否也有取决于厂商；小米/OPPO/三星/一加/真我/中兴/努比亚/摩托罗拉国行版均被报道预装。 |

### 处置优先级（一句话版）

1. **先找 UI**：设置 → 更多设置 → 用户体验改进计划 → **「运营商服务」开关**，关掉它（一条就够，无需 adb）
2. **找不到就用 adb**：`adb shell settings put secure operator_service_improve_plan 0`（等价效果）
3. **再彻底一点**：`adb shell pm disable-user --user 0 com.mobiletools.systemhelper`
4. **兜底**：设置里撤「使用情况访问」+ 禁该应用后台数据/WLAN
5. **不必**：单独关「用户体验改进计划」主开关（那是 LogSystem 自身的，子项要单独看）

---

## 七、证据附录

### 7.1 复现命令（本机可原样执行）

```bash
# 1. 确认组件存在
ls -l /system/app/SystemHelper/SystemHelper.apk

# 2. 读出包名 / 版本 / 权限（本项目自写 AXML 解析器）
python3 /root/workspace/scripts/axml.py /system/app/SystemHelper/SystemHelper.apk

# 3. 提取 DEX 串池里的域名
python3 /root/workspace/scripts/dexstr.py /system/app/SystemHelper/SystemHelper.apk \
        '^(https?://|[a-z0-9._-]+\.(cn|com|net))' | grep -v baidu

# 4. 定位联通接口 + 引用链（androguard）
python3 /root/workspace/scripts/ag_refs.py /system/app/SystemHelper/SystemHelper.apk \
        'dm.wo.com.cn' 'DeviceInfoReaderVIVO' 'isUserPermissionOpen'

# 5. 反编译关键类
python3 /root/workspace/scripts/ag_decomp.py /system/app/SystemHelper/SystemHelper.apk \
        'c/d/a/b/d;' 'c/d/a/b/l;' 'mobiletools/systemhelper/control/g;'
cat /tmp/decomp/c_d_a_b_d.java        # 看到 static String l = "http://dm.wo.com.cn:18080/register"
cat /tmp/decomp/c_d_a_b_l.java        # 看到 operator_service_improve_plan 门禁
cat /tmp/decomp/com_mobiletools_systemhelper_control_g.java   # 看到 POST 拼装

# 6. 全量扫描（225 个 APK）
python3 /root/workspace/scripts/scan_all.py   # → /root/workspace/data/scan_all.json（包名/版本/权限/组件）
python3 /root/workspace/scripts/scan_urls.py  # → /root/workspace/data/scan_urls.json（URL/域名/关键词）
```

### 7.2 关键原文证据链（可直接引用）

```
[E1] SystemHelper.apk!/META-INF/SIG-vSigner.txt
     1.2 (vSigner-16 @loki) [signer: platform,old_platform] [multi-signer]

[E2] SystemHelper.apk!/AndroidManifest.xml
     package="com.mobiletools.systemhelper" versionName="3.0.1-20250515"
     sharedUserId="com.mobiletools.systemhelper.uid"
     android:usesCleartextTraffic="true"

[E3] SystemHelper.apk!/classes.dex  (string_id=8045, file offset 0x149029)
     0x22 'http://dm.wo.com.cn:18080/register'

[E4] 类 c.d.a.b.d（GlobalSetting）静态字段初值（DEX static_values）
     public static String  l = "http://dm.wo.com.cn:18080/register";
     public static boolean c = true;

[E5] 类 c.d.a.b.l -> h(Context):String
     sget-object v0, Lc/d/a/b/d;->l Ljava/lang/String;   // 取出 E4 的地址

[E6] 类 com.mobiletools.systemhelper.control.g -> a(b)
     url = l.h(ctx) + "?ver=5.0&model=" + enc(b.b) + "&manuf=" + enc(b.a) + "&sign=" + enc(RSA(...))
     body = AES/ECB/PKCS5(MD5) 加密的 deviceInfo.a() JSON
     日志串原文: "register postString：", "RSA加密密文： ", "AES加密密文： "

[E7] 类 com.mobiletools.systemhelper.control.b -> a():JSONObject
     put("IMEI1"...) put("ICCID1"...) put("IMSI1"...) put("MNC1"...) put("TAC1"...) put("CID1"...) put("PCI1"...)
     put("IMEI2"...) put("ICCID2"...) put("IMSI2"...) put("AppInfo", ...) put("SigInfo", ...)

[E8] 类 com.mobiletools.systemhelper.control.f
     v0_1.b(p6.getPackageName());
     v0_1.a(v1_1.uid);
     v0_1.a((String) v1_1.loadLabel(this.d));
     v0_1.a((p6.getTotalTimeInForeground() / 60000));
     v0_5.querySummary(0, this.h.i, this.c, this.b);   // NetworkStatsManager
     v0_7.queryAndAggregateUsageStats(this.c, this.b); // UsageStatsManager

[E9] 类 c.d.a.b.l -> a(Context):Z
     日志串 "isBootWizardCompleted: "
     Settings.Global.getInt(cr, "device_provisioned", 0)
     Settings.Secure.getInt(cr, "operator_service_improve_plan", 0)
     日志串 "checkUserPermission:"

[E10] 类 c.d.a.a.a -> a(Context, b)
     if (Build.MANUFACTURER.equals("vivo")) {
         c.d.a.b.d.e = c.d.a.b.l.a(ctx);
         日志串 "vivo DM isUserPermissionOpen : "
     } else {
         c.d.a.b.d.e = c.d.a.a.a.b(ctx);
         日志串 "queryAppUsageStatsDescend  isUserPermissionOpen :"
     }

[E11] 类 com.vivo.setupwizard.ui.ServiceSetupActivity -> Q()V   （SetupWizard.apk）
     if (this.j.getBoolean("isFirst", true)) {
         ContentResolver cr = getContentResolver();
         if (N("com.mobiletools.systemhelper"))
             Settings.Secure.putInt(cr, "operator_service_improve_plan", 1);   // ← 打开门禁
     }

[E12] 同文件 -> X()V
     Settings.Secure.getInt(cr, "operator_service_improve_plan", 1)   // ← 默认值 1

[E13] /system/etc/vivo-default-permissions/default-permissions-vivo.xml:191-197
     <grant package="com.mobiletools.systemhelper">
        ACCESS_COARSE_LOCATION / ACCESS_FINE_LOCATION / ACCESS_BACKGROUND_LOCATION
        / READ_EXTERNAL_STORAGE / READ_PHONE_STATE
     </grant>

[E14] /system/etc/uepp/plugins/com.ueppp.misc.time.AlarmAlign/PD2415.xml:50-53
     <set __name__="op_entry_alarm_white_list">
         com.vivo.devicereg;
         com.mobiletools.systemhelper;
     </set>

[E15] /system/etc/uepp/plugins/com.ueppp.misc.frozen.FrozenPlugin/PD2415.xml:339
     <group __id__="frozen_list"><set __name__="black_list"> ... com.mobiletools.systemhelper; ...

[E16] AppBehaviorEngine.apk!/assets/abe_v2_com_vivo_abe_list_4G.xml
     <item version="0" name="com.mobiletools.systemhelper" isNeedNotifi="0"
           appType="-1" maxpower="100" maxPowerScreenOff="100">com.mobiletools.systemhelper</item>

[E17] 类 a.a.a.c.d -> f  （百度 LBS 遥测格式）
     {"ver":"%d","gps":"%.1f|%.1f|%.1f|%.1f|%d|%d|%d|%d|%d|%d|%d", ... "dmx":%d}

[E18] classes.dex 字符串池中的跨品牌读取器名
     DeviceInfoReaderLiantong / DeviceInfoReaderVIVO / DeviceInfoReaderXiaomi / DeviceInfoReaderOPPO
     / DeviceInfoReaderSamSung / DeviceInfoReaderOnePlus / DeviceInfoReaderRealme / DeviceInfoReaderZTE
     / DeviceInfoReaderNubia / DeviceInfoReaderPTAC / DeviceInfoReaderTDTECH / DeviceInfoReaderWIKO

[E19] 类 q0.a（LogSystem，UserFeedBackFragment）— 开关本体
     this.V0 = (CheckBoxPreference) d("operator_service");
     // onPreferenceTreeClick:
     if (!V0.u1()) Settings.Secure.putInt(cr, "operator_service_improve_plan", 0);
     else          Settings.Secure.putInt(cr, "operator_service_improve_plan", 1);

[E20] 类 p3.a -> d(Context)（LogSystem）— 开关可见性门禁
     String opEntry = SystemProperties.get("ro.vivo.op.entry", "").toUpperCase();
     int installState = p3.a.a(ctx, "com.mobiletools.systemhelper");
     if (opEntry == null || installState == 0
         || (!opEntry.contains("UNICOM") && !opEntry.contains("FULL"))) return false; else return true;

[E21] 同一类的可见性分支
     this.V0 = (CheckBoxPreference) d("operator_service");
     if (!p3.a.d(this.X0)) { M1().removePreference(V0); M1().removePreference(d("about_operator")); ... }

[E22] LogSystem.apk!/resources.arsc 中的隐私声明原文（节选）
     "启用后，选定的运营商将可能通过WLAN或移动数据网络收集您的设备信息（包括您的设备型号、硬件及软件版本号）、
      设备和卡识别数据（包括IMEI、ICCID、IMSI、CELLID）和部分位置数据（包括LAC位置区码、接入基站位置及基站ID）。"
     "本服务调用的权限包括：1. 定位 …；2. 访问手机识别码：读取运营商定制终端设备串号、IMSI，
      用于运营商定制终端销售、入网情况经营分析；3. 联网：通过网络上报自注册信息。"
     "运营商服务主要通过 WLAN 或移动数据网络收集您的设备信息等数据，上报给选定的服务运营方。
      开启即视为同意联网、读应用软件列表、读设备识别码（如 IMEI、WLAN MAC 地址）、定位及权限使用说明。"
     "如果您不想分享这些信息，您可以随时前往“设置”中的“运营商服务”，查看并关闭“运营商服务”开关，
      如未找到请咨询联通官方客服。"
     "设置 > 更多设置 > 用户体验改进计划 > 运营商服务"

[E23] /system/etc/uepp/plugins/com.ueppp.misc.time.AlarmAlign/PD2415.xml
     节点名 "op_entry_alarm_white_list"  ← 与 E19/E20 的 "operator entry" 同一概念（运营商入口）
```

### 7.3 证据强度汇总

| 结论 | 强度 | 依据 |
|---|---|---|
| 本机预装 `com.mobiletools.systemhelper` v3.0.1-20250515 | **实锤** | 文件 + manifest |
| 上报地址 `http://dm.wo.com.cn:18080/register` | **实锤** | 串池原文 + 静态字段 + 引用链 |
| 载荷含 IMEI/IMSI/ICCID/应用清单/使用时长/流量 | **实锤** | 字段名 + 采集代码 |
| 平台签名 → 拿到 `READ_PRIVILEGED_PHONE_STATE` | **强推断** | 签名文件 + 权限语义（无运行期验证） |
| 开机向导把 `operator_service_improve_plan` 置 1 | **实锤** | SetupWizard 反编译（无条件，只判包存在） |
| 存在「运营商服务」UI 开关（LogSystem 渲染） | **实锤** | q0.a 反编译 + 资源字符串 |
| 该开关在本机是否可见 | **存疑** | 取决于 `ro.vivo.op.entry` 是否含 UNICOM/FULL，本机该属性不可读 |
| ROM 自己的隐私文案承认收集 IMEI/ICCID/IMSI/CELLID/基站/应用列表 | **实锤** | `LogSystem.apk!/resources.arsc` 原文 |
| 闹钟白名单 / 冻结名单 / 息屏满功耗 = ROM 特意保活 | **强推断** | 配置文件原文（语义为推断） |
| 上报确实在联网时发出且带定位 | **存疑** | 需抓包/root 看 `shared_prefs` 与 `/data/data/.../databases` |
| 应用清单过滤 `com.android` 前缀 | **存疑** | 有过滤代码，条件未逐条验证 |
| SafeCenter 的 `isecure-pre` 测试域是否真被调用 | **存疑** | 仅字符串命中 |
| `auc.fxltsbl.com`（DeviceReg）归属 | **存疑** | 第三方域，无法确证归属 |

### 7.4 本环境的能力边界（诚实说明）

- `/data` 未挂载 → **无法**读 `/data/data/com.mobiletools.systemhelper/shared_prefs/…`（看不到 `serverAddress` 实际值、`firstPowerOn` 标记）
- 无 adb/root → **无法** `dumpsys package` / `pm list packages` → **无法确认该包当前是 enabled 还是 disabled**，也**无法确认它现在是否在运行**
- 无抓包能力 → **无法**证明「此刻正在发送」；只能证明「代码具备该能力且门禁被打开」
- `/system_ext`、`/product`、`/vendor`、`/odm` 不可读 → **可能存在本报告未覆盖的组件**（本机可见的 225 个之外还有未知数量）
- `/system/build.prop` 权限 600 不可读 → 机型信息取自任务给定的 `PD2415_A_16.1.12.28.W10`，并用 ROM 内 `PD2415.xml` 佐证

### 7.5 交叉核对的公开来源

- 风向旗参考快讯 / AI·RADAR：<https://radar.suversal.com/event/e74bc562caa86>（点名 `com.mobiletools.systemhelper`、多品牌预装、移动卡也触发）
- 快报广 China 资讯：<https://kuaibaoguang.cn/china-systemhelper-android-privacy-controversy/>（三星/一加/真我读取标记，OPPO/vivo 历史踪迹）
- 快报广：<https://kuaibaoguang.cn/xiaomi-phone-data-collection/>（小米「系统安全组件」+ 3 秒录屏；明确「有能力 ≠ 已发生」）
- 掘金技术分析：<https://juejin.cn/post/7704608111827681315>（24 小时使用统计、跳过 `com.android`、IMEI/IMSI/ICCID）
- 社区帖（含 GitHub 包名搜索结论）：<https://www.samsungmembers.cn/thread-4125822-29-180.html>

> 本报告与上述来源的**一致点**：包名、域名、字段、跨品牌、不限联通卡。
> 本报告的**新增点**：vivo 侧的门禁键 `operator_service_improve_plan` 由 `SetupWizard` 写 1、Settings 无开关、ROM 四处白名单。

---

*报告结束。全程只读，未对设备做任何修改。*