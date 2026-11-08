# SystemHelper 追问：门槛条件 / 运行期取证 / 禁用可行性

> 对象：`/system/app/SystemHelper/SystemHelper.apk`｜`com.mobiletools.systemhelper` 3.0.1-20250515
> 方法：androguard 4.1.4 反汇编 + 反编译，全部结论给「类名 → 方法名 → 原始串/指令」
> **全程只读，未对手机做任何修改，未执行任何 disable/root 命令。**
> 本机实测属性（提问方提供）：`gsm.sim.operator.numeric=45400,46011`、`gsm.operator.numeric=46001,46011`、`ro.vivo.op.entry=FULL_SC`

---

## 问题 1｜一张非中国卡会不会挡住上报？

### 1.1 结论（先给答案）

**会挡住。** 本机 45400（卡1，香港 MCC 454）+ 46011（卡2，电信）的组合下，
`c.d.a.b.l.a(control.b)`（= `isChinaNetwork`）在**第一道判断就返回 false**，
`control.g.b()` 随后打印 `"mnc is not in china!"` 并 `return 0` —— **既不上报，也不落库**。

判据用的是 **`getSimOperator()`（SIM 侧 MCC+MNC）**，不是 `getNetworkOperator()`。
所以「卡1 漫游注册在 46001」**救不了它**：45400 在 SIM 侧就是 45400。

### 1.2 数据是怎么取到的（字段来源，逐条）

`Lc/d/a/a/a;->a(Landroid/content/Context;ILcom/mobiletools/systemhelper/control/b;)V`
（`DeviceInfoReaderBase`，按 slot 号调用；先做权限检查，再 `createForSubscriptionId(subId)`）

```java
// 前置：ACCESS_COARSE_LOCATION 与 READ_PHONE_STATE 都授予才继续，否则整段跳过
invoke-static v0, "android.permission.ACCESS_COARSE_LOCATION", Lb/b/c/a/a;->a(...)I
invoke-static v0, "android.permission.READ_PHONE_STATE",     Lb/b/c/a/a;->a(...)I
// 取 slot 的 subId（反射 SubscriptionManager.getSubId）
invoke-static/range v18, Lc/d/a/a/a;->a(I)I
invoke-virtual v0, v3, TelephonyManager;->createForSubscriptionId(I)Landroid/telephony/TelephonyManager;
invoke-virtual v0, TelephonyManager;->getSimSerialNumber()   // → ICCID
invoke-virtual v0, TelephonyManager;->getSubscriberId()      // → IMSI
invoke-virtual v0, TelephonyManager;->getNetworkOperator()   // → 网络侧 MNC  ★
invoke-virtual v0, TelephonyManager;->getSimOperator()       // → SIM 侧 MNC  ★★
invoke-virtual v0, TelephonyManager;->getServiceState()
  → ServiceState.getNetworkRegistrationInfoList() → CellIdentity{Lte,Nr,Gsm,Wcdma,Tdscdma,Cdma} → TAC/CID/PCI
```

**槽位落库（实锤，`iput-object` 原文）：**

```java
if (slot == 0) { b.h=ICCID1; b.i=IMSI1; b.j=simOperator; b.k=networkOperator;
                 b.n=CID1; b.m=TAC1; b.o=PCI1; b.l=NetworkType1; b.r=VoNR1; }
if (slot == 1) { b.t=ICCID2; b.u=IMSI2; b.v=simOperator; b.w=networkOperator;
                 b.z=CID2; b.y=TAC2; b.A=PCI2; b.x=NetworkType2; b.D=VoNR2; }
```

→ **`j` = 卡1 的 `getSimOperator()`，`k` = 卡1 的 `getNetworkOperator()`；`v`/`w` 同理是卡2。**
（`k`、`w` 不进 JSON，只在本地做判断用 —— 载荷里只有 `MNC1`=`j`、`MNC2`=`v`。）

**取不到的兜底**：`getSimSerialNumber()` 空 → `"ex01"`；`getSubscriberId()` 空 → `"ex01"`；
`getSimOperator()` 空 → `"ex01"`。（`"ex01"` 是「读不到」的占位符，后面会被**放行**，见 1.4。）

### 1.3 判断逻辑源码级还原

`Lc/d/a/b/l;->a(Lcom/mobiletools/systemhelper/control/b;)Ljava/lang/Boolean;`（`isChinaNetwork`）
反编译原文（缩进整理）：

```java
log("deviceinfo.mnc1: " + b.j + "mnc2:" + b.v);

// —— 第一道：SIM 侧，两路都要过 ——
if (b.j == null || b.j.isEmpty() || b.j.equals("ex00") || b.j.equals("ex01") || b.j.startsWith("460")) {
    if (b.v == null || b.v.isEmpty() || b.v.equals("ex00") || b.v.equals("ex01") || b.v.startsWith("460")) {

        // —— 第二道：只校验「默认数据卡」的网络侧 ——
        if (b.E != null && !b.E.isEmpty()) {           // b.E = DataSlot
            if (!b.E.equals("1")) {
                if (!b.E.equals("2")) {                 // DataSlot 既不是 1 也不是 2
                    log("isUnicomNetwork->DataSlot：" + b.E); return false;
                } else {                                // 数据卡 = 卡2
                    if (b.w != null && !b.w.isEmpty() && !b.w.startsWith("460")) {
                        log("register mnc2 is not startwith 460!"); return false;
                    }
                }
            } else {                                    // 数据卡 = 卡1
                if (b.k != null && !b.k.isEmpty() && !b.k.startsWith("460")) {
                    log("register mnc1 is not startwith 460!"); return false;
                }
            }
        }
        log("success —— in china network!");  return true;

    } else { log("MNC2 is not startwith 460!"); return false; }
} else   { log("MNC1 is not startwith 460!"); return false; }
```

**所以：**
- 判据是 **数字前缀 `startsWith("460")`**，不是 MNC 枚举；
- **两路（卡1/卡2）都要过**（是「全部命中」，不是「任一命中」）；
- 第二道才看「默认数据卡」那一路的网络侧 MNC；
- `null` / `""` / `"ex00"` / `"ex01"` 一律**放行**（这是一个 fail-open 设计）。

### 1.4 本机 45400 + 46011 的实际走向（含一个坑）

| 步骤 | 本机值 | 结果 |
|---|---|---|
| `b.j` = 卡1 `getSimOperator()` | `"45400"`（SIM 侧 MCC=454 香港；`gsm.sim.operator.numeric` 已证） | ❌ 不是 null/空/ex00/ex01，也不 `startsWith("460")` |
| → 直接命中 `else` | — | `log("MNC1 is not startwith 460!")` → **return false** |
| `b.v` = 卡2 `getSimOperator()` | `"46011"` | 不会被检查到（已提前 return） |
| `b.k` = 卡1 `getNetworkOperator()` | `"46001"` | **不会被检查到**（第二道到不了） |
| `control.g.b()` | `if (!isChinaNetwork) { log("mnc is not in china!"); return 0; }` | **不上报、不落库** |

**确定性结论：本机当前 SIM 组合下 SystemHelper 不会向 `dm.wo.com.cn` 发送任何东西。**
证据强度：代码路径 **实锤**；运行期字段值 **强推断**（依据 `gsm.sim.operator.numeric=45400,46011`，
且 `getSimOperator()` 正是该属性的来源；未读它的 prefs/DB，无法 100% 排除动态换卡）。

> ⚠️ **两个必须知道的坑**：
> 1. **顺序无关**：`Lc/d/a/a/n;->a(control.b)` 会先按 `IMEI1.compareTo(IMEI2)` 把两张卡「排序」
>    （`if (g.compareTo(s) > 0) { 整体交换 slot1/slot2 字段，并把 DataSlot 1↔2 翻转 }`）。
>    所以卡1/卡2 可能被互换后再判断 —— 但**两路都要过**，45400 无论落到 `j` 还是 `v` 都会挡。
> 2. **读不到 = 放行**：如果哪天 `getSimOperator()` 返回空（SIM 未 READY、权限被撤、eSIM 未加载），
>    代码会把它置成 `"ex01"`，**被当作中国卡放行**，此时只剩网络侧 `k/w` 判断 —— 而漫游注册在 46001 会通过。
>    也就是说：**「拔卡/读不到卡」比「插香港卡」更容易触发上报。**

### 1.5 完整触发条件表（RegType）

`Lc/d/a/a/n;->a(Context;String)` 是唯一的分发口，`b.K = RegType`。

| RegType | 含义 | 触发源（组件 → 方法） | 代码里的关键条件 |
|---|---|---|---|
| **F** | 首次开机 | `OnBootReceiver.onReceive` | `firstPowerOn` prefs 为空 → 写 `"F"`，再写 `firstPowerOn` |
| **B** | 普通开机 | 同上；且 `BOOT_COMPLETED`/`QUICKBOOT_POWERON` 后 `a.a(ctx,240000)` → `" boot delayed for 4 minutes-->"` → 4 分钟后再进 `OnBootReceiver` | 开机 + 4 分钟延迟 |
| **S** | 定时 | `ScheduleRegisterReceiver.onReceive` | 闹钟到点，**(SIM 变更检测不参与)**，只判 `n.a(ctx)!=null` |
| **T** | 换卡 | `SIMStateReceiver.onReceive` | SIM_STATE_CHANGED 且 **ICCID1 或 ICCID2 变化**；都不变 → `"SIM not changed，not post！"` return |
| **A** | 应用清单 | `AppInfoRegisterReceiver.onReceive` | 闹钟到点；**`d.e`(isUserPermissionOpen) 为 true** 才 `new f(...).start()`，否则走 `control.g` |
| **R** | 信号历史 | `SignalInfoRegisterReceiver.onReceive` | 闹钟到点；`SigInfo` 数组非空 |

**SIM_STATE_CHANGED 的细分**（`SIMStateReceiver.onReceive`，反编译原文）：

```java
int s = ((TelephonyManager)ctx.getSystemService("phone")).getSimState();
log("SIM_STATE_CHANGED, state:" + s);
if (s == 0 /*UNKNOWN*/ || buf == null) { a.f(ctx)×3; a.c(ctx, 180000); return; }  // 3 分钟后再来
if (s == 1 /*ABSENT*/) { a.e(ctx); log("wait for sim ready for 6 minutes-->"); a.c(ctx,180000); return; }
if (s == 5 /*READY*/) { 同上 }
if (s == 6 /*LOADED*/){ 同上 }
// 其余状态（2 PIN_REQUIRED / 3 PUK_REQUIRED / 4 NETWORK_LOCKED / 7 NOT_READY …）
n.c(); if (n.a(ctx) != null) control.b.a(ctx, "T");
```

> 注意：**READY/LOADED 不立即上报**，只重新排 180000ms（3 分钟）的 `SIMStateReceiver` 闹钟，
> 等卡就绪后那一跳再判 `T`。日志串写「6 minutes」但传的值是 `180000`（3 分钟）。

**定时器参数（`c.d.a.b.d` 静态字段初值 + `RegisterUtils` 取值）**

| 用途 | 默认值（首次） | 之后 | 代码 |
|---|---|---|---|
| 开机后延迟 | `240000` ms（4 min） | — | `a.a(ctx,240000)` + 串 `" boot delayed for 4 minutes-->"` |
| Schedule（S） | `d.s = 86,400,000`（24h）+ 随机 `d.n∈[0,86400000)` | prefs `scheduleInterval` / `scheduleInterval2` | `l.g()` / `l.e()` |
| AppInfo（A） | `d.t = 86,400,000`（24h）+ 随机 `d.m` | prefs `appInfoInterval` / `appInfoInterval2` | `l.c()` / `l.d()` |
| SignalInfo（R） | `d.u = 43,200,000`（12h）+ 随机 `d.o` | prefs `signalInfoInterval` / `signalInfoInterval2` | `l.i()` / `l.f()` |
| GetSignalInfo | `d.v = 1,800,000`（30min） | prefs `GetsignalInfoInterval` | `l.j()` |
| SIM 就绪轮询 | `180000`（3min） | — | `a.c(ctx,180000)` |

`d.<m/n/o>` 都是 `new Random().nextInt(86400000)` —— **首次触发时刻带 0~24h 随机抖动**，
所以「开机后到底什么时候第一次发」不可预测，只能靠 DB/prefs 时间戳反推。

---

## 问题 2｜能不能确认「已经上报过」

### 2.1 它把状态写在哪（实锤）

**A. SharedPreferences**
文件：`/data/data/com.mobiletools.systemhelper/shared_prefs/com_mobiletools_systemhelper_preference.xml`
（名字来自 `c.d.a.b.l` 里 `getSharedPreferences("com_mobiletools_systemhelper_preference", 4)`
和常量串 `/data/data/com.mobiletools.systemhelper/shared_prefs/com_mobiletools_systemhelper_preference.xml`）

| 键 | 类型 | 含义 | 出处 |
|---|---|---|---|
| `iccid1` | String | 上次见到的卡1 ICCID（换卡检测用） | `n.a(Context,b)Z` 里 `const-string "iccid1"` + 串 `"SIM1 Changed,old iccid:"` |
| `iccid2` | String | 同上，卡2 | `n.b(Context,b)Z` |
| `firstPowerOn` | String | 首次开机标记（决定 F/B） | `OnBootReceiver`：`l.a(ctx,"firstPowerOn","")` / `l.b(ctx,"firstPowerOn","firstPowerOn")` |
| `serverAddress` | String | **上报地址可被覆盖**（默认 `d.l`） | `l.a(Context,String)` 写；`l.h(ctx)` 读，`if (!d.b) return a(ctx,"serverAddress",d.l); else return d.l;` |
| `preference_enable_log` | Boolean | 打开后才会 `Log.d` | `OnBootReceiver`：`l.a(ctx,"preference_enable_log",false)` → `d.a` |
| `appInfoInterval` / `appInfoInterval2` | long | A 类间隔 | `l.c()/l.d()` |
| `scheduleInterval` / `scheduleInterval2` | long | S 类间隔 | `l.g()/l.e()` |
| `signalInfoInterval` / `signalInfoInterval2` | long | R 类间隔 | `l.i()/l.f()` |
| `GetsignalInfoInterval` | long | GetSignalInfo 间隔 | `l.j()` |

> **没有 `lastReportTime` / `lastPostTime` / `firstPost` 这类键。**
> 载荷里的 `Time` 字段（`b.N`）由 `l.a()` 用 `SimpleDateFormat("yyyy-MM-dd HH:mm:ss")` 现算，**不落盘**。
> 所以「上次上报时间」**无法从 prefs 直接读到**，只能间接推（见 2.3）。

**B. SQLite（补发队列）**
`c.d.a.b.e` 继承 `SQLiteOpenHelper`：

```java
public static Integer b = 1;                    // DB version
public static String  c = "systemhelper";       // DB 名
public static String  d = "dmregister";         // 表名
// onCreate: create table if not exists dmregister (ID integer primary key autoincrement, jsonString text)
```

→ 文件 `/data/data/com.mobiletools.systemhelper/databases/systemhelper.db`，表 `dmregister(ID, jsonString)`。
写入点：`control.g.b()` 里 `log("sava to database!")` 那段 `e.a(...).a(p5.a())`；
读出点：`e.a()` 里 `log("queryOldestRecord:")` 后 `delete`（先进先出）。
**注意：只有「中国网络 + 联网 + 非联通网络且非 WiFi 命中」等组合才会落库；`mnc is not in china!` 是直接 return，不落库。**

### 2.2 可执行命令（本机有临时 root，直接复制）

```bash
PKG=com.mobiletools.systemhelper
BASEDIR=/data/data/$PKG

# ---- 1. 包状态：装没装、启没启、uid ----
adb shell dumpsys package $PKG | grep -E 'userId=|enabled=|User 0|versionName'
adb shell pm list packages -e | grep mobiletools      # 出现在 enabled 列表
adb shell pm list packages -d | grep mobiletools      # 出现在 disabled 列表

# ---- 2. 进程/组件是否在跑 ----
adb shell ps -A | grep -i mobiletools
adb shell dumpsys activity services $PKG              # 有没有 RegisterHelperService 实例
adb shell dumpsys activity broadcasts | grep -i mobiletools   # 动态注册的 SIMStateReceiver/ConnectStateReceiver
adb shell dumpsys alarm | grep -i mobiletools         # 有没有排着的精确闹钟（setExactAndAllowWhileIdle）

# ---- 3. SharedPreferences 全量（最关键）----
adb shell su -c "cat $BASEDIR/shared_prefs/com_mobiletools_systemhelper_preference.xml"
adb shell su -c "stat -c '%y %s' $BASEDIR/shared_prefs/com_mobiletools_systemhelper_preference.xml"

# ---- 4. 补发队列：有没有积压（= 有未发出的记录）----
adb shell su -c "ls -l $BASEDIR/databases/"
adb shell su -c "sqlite3 $BASEDIR/databases/systemhelper.db 'select count(*) from dmregister;'"
adb shell su -c "sqlite3 $BASEDIR/databases/systemhelper.db \
  'select ID, length(jsonString), substr(jsonString,1,300) from dmregister order by ID desc limit 3;'"

# ---- 5. 打开它自己的日志，然后等一次触发（可选，有写入风险，需 root）----
adb shell su -c "sed -i 's|<boolean name=\"preference_enable_log\" value=\"false\" />|<boolean name=\"preference_enable_log\" value=\"true\" />|' \
  $BASEDIR/shared_prefs/com_mobiletools_systemhelper_preference.xml"
adb logcat -v time | grep -E 'RegisterThread|RegisterUtils|DeviceManager|AlarmHelper|HistoryDatabaseHelper|DeviceInfoReader|OnBootReceiver|SIMStateReceiver|ScheduleRegisterReceiver|AppInfoRegisterReceiver|SignalInfoRegisterReceiver'

# ---- 6. 网络侧：它到底发了多少字节 ----
adb shell dumpsys package $PKG | grep userId=          # 拿 uid，例如 u0aXXX
adb shell dumpsys netstats detail | grep -A6 "uid=10xxx"   # 需要把 u0aXXX 换成数字 uid
# 只看 18080 端口：加一条计数规则（root），这条同时是最精准的「是否上报」探针
adb shell su -c "iptables -I OUTPUT -p tcp --dport 18080 -j ACCEPT"
sleep 86400   # 或等一次开机/换卡
adb shell su -c "iptables -L OUTPUT -v -n | grep 18080"   # pkts/bytes 非 0 = 确实发过
# 也可以直接封死（见问题 3 的 B 档）
adb shell su -c "iptables -I OUTPUT -p tcp --dport 18080 -j REJECT"
```

### 2.3 能拿到 / 拿不到（诚实边界）

| 想证明 | 能不能 | 依据 |
|---|---|---|
| 包已安装、当前 enabled/disabled | ✅ 能 | `dumpsys package` / `pm list packages -e/-d` |
| 是否正在运行（进程、绑定的 service、动态 receiver） | ✅ 能 | `ps -A`、`dumpsys activity services/broadcasts` |
| 是否排着定时上报闹钟 | ✅ 能 | `dumpsys alarm \| grep mobiletools` |
| **最近一次是否上报成功** | ⚠️ **间接** | prefs 无时间字段；只能看 (a) `systemhelper.db` 行数变化、(b) prefs 文件 mtime、(c) 第 6 步的 18080 端口计数 |
| 上报了哪些字段 | ⚠️ 只有开日志后 | 默认 `preference_enable_log=false` → `h.a()` 直接 return（见 2.4），`logcat` 一片空 |
| **目的域名是不是 dm.wo.com.cn** | ❌ 无 root 抓包拿不到 | `dumpsys netstats` 只给「uid + 接口 + 字节数」，不给主机名；要坐实只能：`su -c tcpdump -i any -s0 -w /sdcard/dm.pcap port 18080`（需 tcpdump 二进制）或 18080 端口计数 + `getent hosts dm.wo.com.cn` 双向印证 |
| 历史（昨天/上周）发过没有 | ❌ 基本拿不到 | 没有本地审计表；`dmregister` 只存**未发出**的，发完就删 |

### 2.4 一个反直觉的点：**默认不打日志**

`c.d.a.b.h`（日志包装）：

```java
public static void a(String tag, String msg) {
    if (c.d.a.b.d.a && tag != null && msg != null) {   // ← d.a = preference_enable_log
        if (msg.length() > 3072) { /* 按 3072 字符分片 */ }
        android.util.Log.d(tag, msg);                  // LOG.D，且 tag 由调用方给
    }
}
```

`d.a` 初值 `false`，只在 `OnBootReceiver.onReceive` 里由 prefs `preference_enable_log` 决定。
→ **默认情况下 logcat 里连 tag 都不会出现**，不要用「logcat 干净」当「没上报」的证据。

日志 tag 全集（想看时 grep 这些）：`RegisterThread`、`RegisterUtils`、`DeviceManager`、
`AlarmHelper`、`HistoryDatabaseHelper`、`DeviceInfoReaderBase`、`DeviceInfoReaderVIVO`、
`OnBootReceiver`、`SIMStateReceiver`、`ScheduleRegisterReceiver`、`AppInfoRegisterReceiver`、
`SignalInfoRegisterReceiver`、`ConnectStateReceiver`。

---

## 问题 3｜彻底禁用的可行性与副作用

### 3.1 `pm disable-user` 能不能成功

**预判：能成功（强推断）。** 逐条理由：
- 声明里**没有 `android:persistent`**（`<application>` 属性只有 `allowBackup=0 / supportsRtl / extractNativeLibs=0 / usesCleartextTraffic=true / roundIcon / appComponentFactory`）→ 不在「不可禁用」的 persistent 白名单里
- 装在 `/system/app`（**非 priv-app**），也**不在** `/system/etc/permissions/privapp-permissions-*.xml` 里
- `sharedUserId="com.mobiletools.systemhelper.uid"` 是它**独有**的，禁用不会波及别的包
- 全盘扫描：**没有任何其它 APK 引用它的 AIDL action `com.mobiletools.systemhelper.service.IRegisterHelperService`**（只有它自己），不存在上游依赖
- 它不在 `/system/etc/uepp/.../pending_action` 之类的「系统关键」名单里（只在 `frozen_list/black_list` 与 `op_entry_alarm_white_list`）

```bash
adb shell pm disable-user --user 0 com.mobiletools.systemhelper
adb shell pm list packages -d | grep mobiletools        # 验证
adb shell dumpsys package com.mobiletools.systemhelper | grep -E 'enabled=|stopped='
```

**可能看到的报错与对策**

| 报错 | 含义 | 备选 |
|---|---|---|
| `java.lang.SecurityException: Permission Denial: ... CHANGE_COMPONENT_ENABLED_STATE` | adb shell 被 ROM 削权 | `adb root` 后重试 |
| `Failure [not installed for 0]` | 包名/用户号不对 | `adb shell pm list users` 确认 user 0；`pm list packages \| grep mobiletools` |
| `java.lang.IllegalArgumentException: Cannot disable a protected package` | ROM 把它列为保护包 | 转 `pm uninstall -k --user 0` 或 `pm suspend` |
| 无输出但 `-d` 列表里没有 | 被 ROM 的 `uepp` 拉起层拦了 | 用下面的「等价替代」 |

**等价替代（按侵入性从低到高）**

```bash
# ① 只关「上报开关」——最小改动，不碰包
adb shell settings put secure operator_service_improve_plan 0
adb shell settings get secure operator_service_improve_plan    # 期望 0

# ② 单用户卸载（对 /system/app 应用有效，等价于彻底 disabled）
adb shell pm uninstall -k --user 0 com.mobiletools.systemhelper
adb shell pm install-existing --user 0 com.mobiletools.systemhelper    # 恢复

# ③ suspend（需要 ROM 支持；不支持会报 "Suspend not supported"）
adb shell cmd package suspend --user 0 com.mobiletools.systemhelper

# ④ root 下物理隔离（不删文件，只 overlay 空目录，OTA 后需重做）
adb shell su -c 'mount --bind /data/local/tmp/empty /system/app/SystemHelper'
```

> 区别：`pm disable` 是旧命令、**通常需要 root**；**`pm disable-user --user 0` 才是 adb shell 可用的那条**。
> 想「真卸载」系统应用（从 /system 删掉）必须 root + remount。

### 3.2 禁用后的副作用

| 方面 | 影响 | 依据/强度 |
|---|---|---|
| 开机流程 | 无影响。它只是 `BOOT_COMPLETED` 的一个普通 receiver；SetupWizard `Q()` 里 `N(pkg)` 判空后**跳过**写 `operator_service_improve_plan`（`c4.a.e(this).n(pkg) == null → return 0`），不会卡向导 | 实锤（代码） |
| 通话 / 短信 / 上网 | 无影响。它不注册任何 `TelephonyManager` 的必要回调给系统，也不提供 APN/IMS 能力 | 强推断 |
| 运营商配置 | `com.vivo.devicereg`（vivo 自己的设备注册）是另一个包，不受影响；它只是「联通 DM 注册」这一条链路断掉 | 强推断 |
| 系统稳定性 | 低风险：无其它包 bind 它的 AIDL；`frozen_list`/`op_entry_alarm_white_list` 里残留条目会被忽略 | 实锤（无引用方）+ 强推断 |
| OTA | **两个风险**：① 大版本更新可能重置 `disabled` 状态或把包重新启用；② 若你改过 `/system`（C 档），OTA 完整性校验会失败。只做 `pm disable-user`/`uninstall --user 0` 不影响 OTA | 强推断 |
| 恢复出厂 | `disabled` 标记和 `uninstall --user 0` **都会被清掉**，且开机向导会重新 `putInt(...,1)` → 回到原始状态 | 实锤（SetupWizard 代码） |

### 3.3 能不能单独掐掉「读取已安装应用列表」

#### 先把权限性质说准（本机 `framework-res.apk` 实测解析结果）

| 权限 | protectionLevel（原始整数） | 解读 |
|---|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | **`0`** | **`normal`，不带任何 appop 标志** |
| `android.permission.PACKAGE_USAGE_STATS` | `16777330`（=0x1000042，含 `0x2` signature、`0x40` **appop**） | 签名类 + **appop 型** |
| `android.permission.READ_PHONE_STATE` | `1` | `dangerous` |
| `android.permission.READ_PRECISE_PHONE_STATE` | `18`（=0x12 = signature\|privileged） | 平台签名即授予 |
| `android.permission.READ_PRIVILEGED_PHONE_STATE` | `67108882`（=0x4000012，含 `0x2` signature、`0x10` privileged） | **平台签名即授予** |
| `android.permission.READ_LOGS` | `50`（=0x32 = signature\|privileged\|development） | — |
| `android.permission.INTERNET` | `4096`（=0x1000 preinstalled） | normal |

> 这张表同时把原报告里「平台签名 → 拿到 READ_PRIVILEGED_PHONE_STATE」从**强推断升为实锤**：
> protectionLevel 含 `signature` 位（0x2），而 APK 的 `META-INF/SIG-vSigner.txt` = `[signer: platform,old_platform]`。

#### 结论：**不能单独掐掉应用清单**

- `QUERY_ALL_PACKAGES` = `normal`、**无 appop 位** → `appops set` 没有对应 op 可设；
  `pm revoke` 对非 runtime 权限会失败，报错形如：
  ```
  Exception occurred while executing 'revoke':
  java.lang.SecurityException: Permission android.permission.QUERY_ALL_PACKAGES is not a changeable permission type
  ```
  证据强度：**实锤**（framework-res 声明）+ **强推断**（revoke 行为，未实机执行）
- 能掐的是**它的数据来源**（`PACKAGE_USAGE_STATS` 的 appop）：

```bash
# 掐掉「使用时长 + 每 App 流量」（NetworkStatsManager.querySummary 也要这个 appop）
adb shell appops set com.mobiletools.systemhelper GET_USAGE_STATS deny
adb shell appops get com.mobiletools.systemhelper GET_USAGE_STATS
# 期望输出：GET_USAGE_STATS: deny

# 掐掉电话状态（IMEI/IMSI 读取会拿不到 → 字段变成 null/空）
adb shell pm revoke com.mobiletools.systemhelper android.permission.READ_PHONE_STATE
# 后台定位
adb shell appops set com.mobiletools.systemhelper COARSE_LOCATION deny
adb shell appops set com.mobiletools.systemhelper FINE_LOCATION deny
```

**掐掉之后还剩什么**（代码级推演）：

| 载荷字段 | `GET_USAGE_STATS=deny` 后 | 依据 |
|---|---|---|
| `AppInfo[]`（包名/应用名/前台分钟/流量） | **整个数组会空** —— 清单是从 `queryAndAggregateUsageStats()` 的结果反查已装应用得来的，没有 usage 就没有条目 | `control.f.a()` / `control.f.b()` 反编译 |
| `IMEI/ICCID/IMSI` | 仍会发（`READ_PHONE_STATE` 是另一个口子） | 分属不同权限 |
| `MNC1/2`、`TAC/CID/PCI`、`model/manuf`、`Time` | 仍会发 | 同上 |

→ **appops 只能减量，不能止损。要彻底停，只能 3.1 的 disable。**

### 3.4 `ro.vivo.op.entry = FULL_SC` 时开关可见吗？

**可见。** 按 `LogSystem`（`com.bbk.iqoo.logsystem`）的 `p3.a.d(Context)` 逐句推：

```java
String v0_2 = l3.a.b("ro.vivo.op.entry", "").toUpperCase();          // → "FULL_SC"
int    v4_7 = p3.a.a(ctx, "com.mobiletools.systemhelper");           // 装机则 1，未装则 0
log("opEntry=" + v0_2 + ", installState=" + v4_7);
if (v0_2 == null || v4_7 == 0 || ((!v0_2.contains("UNICOM")) && (!v0_2.contains("FULL"))))
    return false;
else return true;
```

- `v0_2` = `"FULL_SC"` → 不为 null
- `v4_7` = 1（`p3.a.a` 内部：`p3.r.c(ctx,pkg,0) != null` → 已安装）
- `!contains("UNICOM")` = `true`；`!contains("FULL")` = **`false`** → `(true && false)` = **false**
- 三个条件全 false → **`return true`**

→ `q0.a.S1()` 里那个 `if (!p3.a.d(this.X0)) { M1().removePreference(V0); ... }` **不会执行**，
勾选框 `operator_service` 与 `about_operator`、`operator_tip` **保留显示**。

**所以本机路径确实是：设置 → 更多设置 → 用户体验改进计划 → 「运营商服务」开关**（可关）。
证据强度：**实锤**（代码 + `ro.vivo.op.entry=FULL_SC` 实值；仅"你手机设置里的字面位置"未亲眼确认）。

> 补充：`c.d.a.a.j`（`DeviceInfoReaderVIVO`）里还有一个**只认 UNICOM** 的分支：
> ```java
> private boolean d() { return c.d.a.b.l.a("ro.vivo.op.entry").toUpperCase().contains("UNICOM"); }
> ```
> 在 `FULL_SC` 下它是 `false`，作用是「决定 SWVersion 读 `ro.vivo.op.entry.version` 还是 `ro.vivo.product.version`」，
> **不是上报门禁** —— 门禁只有 `device_provisioned && operator_service_improve_plan` 这一条，别被它误导。

---

## 附：这次追问答到的关键新增事实（相对原报告）

| # | 事实 | 证据 | 强度 |
|---|---|---|---|
| 1 | 中国网络判据是**两路 SIM 侧 MNC 都必须 `startsWith("460")`**，全部命中才过 | `c.d.a.b.l.a(control.b)` 反编译 | 实锤 |
| 2 | `b.j/b.v` = `getSimOperator()`；`b.k/b.w` = `getNetworkOperator()`（后者只用于数据卡那一路、且不进 JSON） | `c.d.a.a.a.a(Context,int,b)` 的 `iput` + `getSimOperator/getNetworkOperator` 调用 | 实锤 |
| 3 | 判定前会按 IMEI 字典序**互换两张卡**，但顺序不影响「两路都要过」的结论 | `c.d.a.a.n.a(control.b)` | 实锤 |
| 4 | 读不到 SIM 时置 `"ex01"` 且**被放行**（fail-open）→ 无卡比香港卡更容易触发 | `l.a(control.b)` + 读取方法的 `"ex01"` 分支 | 实锤 |
| 5 | 不满足中国网络时**不落库也不上报**，直接 `return 0` | `control.g.b()` 的 `"mnc is not in china!"` 分支 | 实锤 |
| 6 | 补发队列 = `systemhelper.db` / 表 `dmregister(ID, jsonString)` | `c.d.a.b.e` 静态字段与 `onCreate` | 实锤 |
| 7 | 换卡检测靠 prefs `iccid1` / `iccid2`；**没有 lastReportTime**，`Time` 字段不落盘 | `n.a/n.b(Context,b)` + `l.a()` | 实锤 |
| 8 | **默认不打日志**（`preference_enable_log=false` → `h.a()` 直接 return） | `c.d.a.b.h.a(String,String)` | 实锤 |
| 9 | `QUERY_ALL_PACKAGES` = `normal`、无 appop → 无法单独关闭 | `framework-res.apk` 解析 | 实锤 |
| 10 | 平台签名 → `READ_PRIVILEGED_PHONE_STATE` / `READ_PRECISE_PHONE_STATE` 必授予（含 signature 位） | 同上 + `SIG-vSigner.txt` | 实锤 |
| 11 | `ro.vivo.op.entry=FULL_SC` → 「运营商服务」开关**可见** | `p3.a.d()` 逻辑 + `p3.a.a()` 定义 | 实锤 |
| 12 | 没有任何其它预装 APK 引用它的 AIDL action → 禁用无上游依赖 | 225 APK 全量扫串 | 实锤 |