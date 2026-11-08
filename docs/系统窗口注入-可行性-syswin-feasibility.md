# 悬浮窗压系统栏可行性（vivo V2415A / Android 16 / 临时 KernelSU root）

日期：2026-11-06　执行：teammate `syswin-scout`　设备现场实测
所有实验都用独立文件/独立 jar，**未改动用户 App、未安装任何 APK 到 /system、未改任何持久配置**；
测试期间写入的 `policy_control`、`wm size` 均已恢复并用 `settings get` / `wm size` 复核。

## 结论（先看这三行）

- **A 特权应用（priv-app）路线：能。** priv-app + `privapp-permissions` 白名单 XML 可以拿到 `INTERNAL_SYSTEM_WINDOW`，
  然后由 **App 自己的进程**（AMS 已登记 → 能建 WindowSession）添加 `TYPE_SYSTEM_ERROR(2010)`，mBaseLayer=27，
  实测层级表上就是**高于** vivo 手势条 `2024`(25)、通知栏 `2040`(17)、状态栏 `2000`(15) 的最高一档；
  代价是必须动 /system（erofs 只读，靠 KernelSU 模块/overlay）、**每次重启都要先有 root 才能挂载**、OTA 后全部重做、
  白名单 XML 写错会 system_server 起不来（bootloop）。
- **B root 进程直接 addView 系统窗口：不能（Android 16 上被 WMS 的进程登记校验挡死）。**
  uid=0 确实能绕过权限判定（实测 `INTERNAL_SYSTEM_WINDOW uid=0 -> 0`），但 `IWindowManager.openSession()` 在
  `Session` 构造函数里要求 **调用 pid 必须已存在于 AMS 的 ProcessMap**，root 进程/`app_process`/Shizuku UserService 都不是
  AMS 启动的进程，实测抛 `IllegalStateException: Unknown pid=… uid=0`，窗口根本加不进去。
  剩下唯一没验证的兜底是绕开 WMS 直连 SurfaceFlinger 建 Surface（成本高、无输入/无 insets 集成，不建议首批做）。
- **C 不 root 的折中：**
  - C1「用 `policy_control immersive.full=*` 临时隐藏系统栏」**不能**：Android 16 已删除 `PolicyControl` 实现
    （android16-release 里 `services/.../wm/PolicyControl.java` 已不存在，WMS 只保留了那个 setting 的 ContentObserver 注册没人消费），
    本机实测写进去、强制 relayout 后状态栏/导航栏照旧（截图 `shot-immersive2.png`）。
  - C2「把辉光画到系统栏**下面**（栏透明，Android 15+ 强制 edge-to-edge）」**能，且不需要任何权限/root**：
    现在 v21 的辉光高度写死成 `screenHeight - navBar`，加 `params.setFitInsetsTypes(0)` + 全屏高度即可铺满整屏，
    差额只有一个：状态栏图标 / 手势 pill 仍会压在辉光之上（观感可接受，多数手机 edge glow 就是这么做的）。

---

## 1. 设备与权限事实（真机证据）

```
$ id                      → uid=0(root) … context=u:r:ksu:s0
$ getenforce              → Permissive          # SELinux 不构成阻碍
$ getprop ro.control_privapp_permissions → enforce
$ getprop ro.build.type   → user ; ro.debuggable → 0 ; ro.build.version.sdk → 36
```

权限保护级别（`pm list permissions -f`）：

```
android.permission.INTERNAL_SYSTEM_WINDOW  protectionLevel:signature|privileged
android.permission.STATUS_BAR              protectionLevel:signature|privileged
android.permission.SYSTEM_ALERT_WINDOW     protectionLevel:normal
```

用系统 API 实测 `ActivityManager.checkComponentPermission(perm, uid, -1, true)`（0=GRANTED，-1=DENIED）：

| permission | uid=0 (root) | uid=1000 (system) | uid=10398 (com.dshgo.assist) | uid=10071 (SystemUI, priv-app) |
|---|---|---|---|---|
| INTERNAL_SYSTEM_WINDOW | **0** | **0** | -1 | **0** |
| STATUS_BAR | **0** | **0** | -1 | **0** |
| SYSTEM_ALERT_WINDOW | 0 | 0 | -1 | 0 |

→ 两点：① uid 0/1000 走 `canAccessUnexportedComponents()` 直接放行（AOSP `ActivityManager.java:5226-5258`）；
② 我们 App 即便被 root 执行 `pm grant … INTERNAL_SYSTEM_WINDOW`（**命令 rc=0、无报错**）实际**没有生效**，
权限判定仍是 -1，`dumpsys package` 里也没有该权限记录 —— `signature|privileged` 不是可 grant 的 runtime 权限，
**只有「平台签名」或「priv-app + 白名单」两条路**。

## 2. 窗口层级（为什么 2038 永远在下面）

AOSP android16 `WindowManagerPolicy.getWindowLayerFromTypeLw()`（低=下，高=上）：

| type | 名称 | layer |
|---|---|---|
| 2038 | APPLICATION_OVERLAY（我们现在的） | 11 |
| 2003 | SYSTEM_ALERT | 12（仅当有 INTERNAL_SYSTEM_WINDOW，否则 9） |
| 2000 | STATUS_BAR | 15 |
| 2040 | NOTIFICATION_SHADE | 17 |
| 2009 | KEYGUARD_DIALOG | 19 |
| 2006 | SYSTEM_OVERLAY | 23（有权限时；否则 10） |
| 2024 | **NAVIGATION_BAR_PANEL（vivo SideSlideGestureBar）** | **25** |
| 2019 | NAVIGATION_BAR | 24 |
| **2010** | **SYSTEM_ERROR** | **27（有 INTERNAL_SYSTEM_WINDOW 时）** |
| 2032 | ACCESSIBILITY_OVERLAY | 31（但需要无障碍服务，不能自定义内容层级） |

真机 `dumpsys window windows` 同步印证（baseLayer ≈ layer×10000+1000）：

```
type=2024 baseLayer=251000  SideSlideGestureBar-Bottom       ← 手势条，最上
type=2009 baseLayer=191000  vivo SuperCard
type=2040 baseLayer=171000  NotificationShade
type=2000 baseLayer=151000  StatusBar
type=2038 baseLayer=111000  ShellDropTarget / 我们的悬浮窗    ← 我们在这
type=2011 baseLayer=131000  InputMethod
type=1    baseLayer=21000   前台应用
```

结论：**要「压住」系统栏，必须让窗口 type 落在 layer ≥ 25 的档位**；对第三方唯一可用的就是
`TYPE_SYSTEM_ERROR(2010)`（27）。仅升到 `STATUS_BAR(2000)` 是没用的——它只有 15，仍在通知栏/手势条下面。

## 3. 路线 A：priv-app（能，但代价最大）

机制与证据：
- `/system` 属 system-as-root、分区 erofs 只读；`/system_ext`、`/vendor`、`/product` 同样是 erofs ro。
- `ro.control_privapp_permissions=enforce`，`/system/etc/permissions/` 下确实存在
  `privapp-permissions-platform.xml`、`-mediatek.xml`、`-vivo.xml` 三个白名单，格式为
  `<privapp-permissions package="…"><permission name="…"/></privapp-permissions>`。
- 现场抽查：`com.android.systemui` 等系统应用 `INTERNAL_SYSTEM_WINDOW: granted=true`（他们是平台签名，走 signature 分支）；
  AOSP 里 `signature|privileged` 的另一半就是「priv-app + 白名单」，因此**我们自己签名的 APK 放进 /system/priv-app
  并补一条白名单 XML 后能得到该权限**（这也解释了为什么必须补 XML：enforce 模式下 priv-app 申请特权权限但不在白名单 → 系统起不来）。
- 安装完 APK 后，**加窗口的是 App 自己的进程**：它是 AMS 登记过的应用进程，`IWindowManager.openSession()`
  能过（这正是 B 路线卡死的那一步），权限也已到手，于是 `TYPE_SYSTEM_ERROR` 可用，mBaseLayer=270000 > 251000。

> 标注：A 的「权限能拿到」依据是 AOSP 的 `signature|privileged` 机制 + 本机 enforce/白名单现状（现场已验证
> `checkComponentPermission(uid=10071 SystemUI)=0`、多个 priv-app granted=true）；「2010 能排在 251000 之上」
> 依据是 AOSP 层级表 + 真机各窗口 mBaseLayer 实测。**「priv-app 形态下 addView 成功」这一步按约定没有真装
> /system 去跑**，属于强推断而非实测；如果后续要落地，建议先在一台可回滚的备用机/模拟器上验证这一步。

代价（对「临时 root」用户基本不可行）：
- `/system` 不能 remount rw；只能走 KernelSU 的 overlayfs/magic-mount。
- 现场：`/data/adb/ksud` 在，`ksud module` 子命令在，但 **`/data/adb/modules` 不存在**（没有装过模块），
  当前 KSU 是运行期 late-load（dmesg 里能看到 `KernelSU: handle_setresuid …`、`hook_manager: unmark …`），
  也就是**重启后 root 本身要重新提权**。
- 而 priv-app 必须在 **system_server 启动、扫描 /system/priv-app 之前**就可见 → 必须「开机时 KSU 已加载并挂好模块」。
  临时 root 在开机后才有，顺序上天生对不上；退一步「开机后挂 overlay 再重启 zygote/system_server」虽然理论可行，
  但每次开机都要重做 + 会重启所有应用 + vivo 系统完整性校验风险高。
- OTA/ROM 更新后全部重做；白名单 XML 与 APK 不匹配会 bootloop（enforce）。
- 手工 overlayfs 挂载本次未打通（`mount -t overlay` 返回 EINVAL），要按 KernelSU 的挂载参数来，进一步说明这条路依赖 KSU 模块机制。

**A 一句话：技术上成立，工程上是「每次开机都要先 root 才能装系统级组件」，不是能给普通用户的产品方案。**

## 4. 路线 B：root 进程直接 addView（Android 16 上不能）

### 4.1 做了什么
写了最小原型 `/root/workspace/projects/系统窗口注入-syswin-proto/`（javac → d8 → `classes.dex` 打成 jar），
用 root shell（Shizuku bridge 队列 `/sdcard/Download/DSH-Go/rootq/*.sh`）执行：

```sh
/system/bin/app_process -Djava.class.path=/data/local/tmp/syswin.jar /system/bin \
    com.dshgo.syswin.SysWin 2000 t2
```

原型内部流程：`ActivityThread.systemMain()` → `getSystemContext()` → 造 View（type=2000）→ `WindowManager.addView`。

### 4.2 踩到的两个坑（都已定位）
1. **View 构造函数要读 `Settings.Global`**（`FontSymbolUtils`），而 app_process 不是 AMS 注册进程，
   任何 ContentResolver 访问都会 `SecurityException: Unable to find app for caller … when getting content provider settings`。
   → 原型里把 `Settings$Global/Secure/System` 的 `NameValueCache.mProviderHolder.mContentProvider` 换成一个
   `IContentProvider` 动态代理（返回 `value=1`，即 `ActivityThread.java:9441` 那条路的短路），View 就能造出来了。
2. **`IWindowManager.openSession()` 直接拒绝**：

```
FAIL java.lang.IllegalStateException: Unknown pid=24809 uid=0
    at android.view.IWindowManager$Stub$Proxy.openSession(IWindowManager.java:5655)
    at android.view.WindowManagerGlobal.getWindowSession(WindowManagerGlobal.java:280)
    at android.view.ViewRootImpl.<init>(ViewRootImpl.java:1460)
    at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:515)
```

### 4.3 根因（AOSP 源码，不是猜测）
`services/core/java/com/android/server/wm/Session.java:131-148`：

```java
public Session(WindowManagerService service, IWindowSessionCallback callback) {
    this(service, callback, Binder.getCallingPid(), Binder.getCallingUid());
}
...
    mProcess = service.mAtmService.mProcessMap.getProcess(mPid);
    if (mProcess == null) {
        throw new IllegalStateException("Unknown pid=" + mPid + " uid=" + mUid);
    }
```

`mProcessMap` 只装 AMS 自己 `startProcessLocked` 出来的应用/system 进程。uid=0 只绕过 `checkComponentPermission`
（第 2 节实测 0=GRANTED），**绕不过「进程必须在 AMS 花名册里」这一条**。而 `attachApplication` 也补不了：
AMS 侧 `attachApplicationLocked()` / `finishAttachApplicationInner()` 是拿 `mPidsSelfLocked.get(pid)` 找**已有**记录，
没有记录不会给你新建（`ActivityManagerService.java:4852、4863+`）。

同理：**Shizuku 的 UserService / rish shell 也是 app_process 子进程，不是 AMS 进程，走同一条死路**——
所以「用 Shizuku 起个 root 进程加窗口」这个直觉方案在 Android 16 上不成立。

### 4.4 复现方式（几分钟）
```sh
bash /root/workspace/projects/系统窗口注入-syswin-proto/build.sh                 # 生成 out/syswin.jar
cp /root/workspace/projects/系统窗口注入-syswin-proto/out/syswin.jar /sdcard/Download/DSH-Go/syswin/
# 把 /root/workspace/projects/系统窗口注入-syswin-proto/run-rootq.sh 的内容丢进 /sdcard/Download/DSH-Go/rootq/3xx-*.sh
# 2 秒后看 /sdcard/Download/DSH-Go/rootq-out/3xx-*.sh.log：应该看到 ADDED 之前的 FAIL Unknown pid
```
（本次真实日志：`/sdcard/Download/DSH-Go/rootq-out/315-syswin-run2.sh.log`。）

### 4.5 没验证但存在的兜底
绕开 WMS，直接用 `SurfaceControl`/SurfaceFlinger 建一层挂到显示顶层（`setLayer` 给极大值）：
理论可行、能覆盖全部系统栏，但没有 WMS 的 insets/输入/生命周期管理，Android 16 的 display token 获取方式也不公开，
属于「1–2 天原型 + 不一定成」的投入，建议不作为首批方案，除非 A 也被否掉。

## 5. 路线 C：不 root 的折中

### C1 沉浸式隐藏系统栏 —— 不能（Android 16 已删功能）
- `settings put global policy_control immersive.full=*` → `settings get` 回读正确；
- 为了让 WindowManager 重新套用策略，还做了一次 `wm size 1259x2799` 强制 relayout（截图 1259x2799 证明 relayout 真发生了）；
- **状态栏、手势条依然在**（截图 `shot-immersive2.png`：21:51 时间/电量图标、底部手势条都在），窗口顺序不变。
- 源码佐证：android16-release 里 `services/core/java/com/android/server/wm/PolicyControl.java` 已不存在；
  `WindowManagerService.java` 只剩 `mPolicyControlUri` 的声明与 `registerContentObserver`（819/851 行），没有任何消费点。
→ 这条「临时隐藏系统栏」的老办法在新系统上失效。附带风险（即便在旧系统也成立）：内容重排/闪一下、
  下拉通知要先唤出状态栏、上滑手势变成「第一下只唤出栏」、崩溃残留会让用户找不到状态栏。

### C2 把辉光画到系统栏下面（推荐）
vivo 这代系统栏是**透明**的（截图里状态栏区域就是 App 自己的底色，只有图标/手势 pill 是绘上去的）。
现在 `OverlayService.showEdgeGlow()` 是：

```java
int navBar = navigationBarHeight();
int glowHeight = Math.max(dp(200), screenHeight - navBar);   // ← 自己把底部让出来了
// flags: NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS, cutout=ALWAYS
// 没有调用 params.setFitInsetsTypes(0)
```

改成：
```java
params.height = WindowManager.LayoutParams.MATCH_PARENT;      // 或 screenHeight
params.setFitInsetsTypes(0);                                  // 关键：不被 systemBars insets 缩进
params.setFitInsetsSides(WindowInsets.Side.all());            // 可选，双保险
```
效果：辉光四条边真正贴到屏幕物理边缘，顶部从状态栏图标**下方**经过、底部从手势 pill **下方**经过。
不需要 root、不需要权限、不需要重启，观感上已经接近「整屏辉光」；唯一差的是图标/pill 仍浮在辉光之上。
如果产品能接受这点，**C2 就是零成本方案；只有「必须遮住图标」才需要 A**。

## 6. 风险与建议

| 方案 | 能否压住系统栏 | 依赖 | 每次重启 | 维护成本 |
|---|---|---|---|---|
| A priv-app + 白名单 + TYPE_SYSTEM_ERROR(2010) | **能（完全）** | 改 /system（KSU 模块/overlay）、privapp XML | 必须先有 root 才能挂载 | 高，OTA 重做，写错 bootloop |
| B root 进程 addView | **不能**（Session 进程登记校验） | — | — | 不建议继续 |
| C1 immersive 隐藏栏 | 不能（Android 16 已移除） | — | — | 废弃 |
| **C2 画到栏下面** | 部分（图标/pill 仍在最上） | 无 | 无 | **极低** |

建议：**默认走 C2**；A 作为「极限观感」的可选高级模式（面向愿意保持 root 的用户，且最好做成可一键回滚），
B 不要再投入。若要 A，注意三件事：一个独立包名/独立 APK、白名单 XML 用独立文件名、type 只用 2010
（2000/2019 都压不过 vivo 的 2024）。

## 附：本次实验产物
- 代码：`/root/workspace/projects/系统窗口注入-syswin-proto/`（`src/…/SysWin.java`、`build.sh`、`out/syswin.jar`）
- 真机 jar 与截图：`/sdcard/Download/DSH-Go/syswin/`
- root 队列日志（原始证据）：`/sdcard/Download/DSH-Go/rootq-out/300…331-syswin-*.log`
- 关键证据截图：`shot-immersive.png` / `shot-immersive2.png`（沉浸式开关无效），`shot-2000.png`、`shot-syswin2000.png`
- 复现脚本：`/root/workspace/projects/系统窗口注入-syswin-proto/run-rootq.sh`（丢进 rootq 队列即可复现 B 的失败点）

收尾复核（`rootq-out/342-clean2.sh.log`）：实验进程与 `/data/local/tmp` 临时文件已清空；
`policy_control=null`、`wm size=1260x2800`、`wm density=560` 与实验前一致；App 进程存活、权限清单无新增。
