# vivo 原子岛 / 原子通知 —— DSH 接入可行性静态分析

设备：vivo V2415A / Funtouch 16 (Android 16, SDK 36)　方法：纯静态（proot 只读 /system）
分析对象：`/system/framework/framework.jar`、`/system/priv-app/SystemUI/SystemUI.apk`、
`/system/priv-app/SystemUIPlugin/SystemUIPlugin.apk`、`/system/app/SuperCard`、以及 225 个系统 APK 的清单。

---

## 结论（先看这里）

**能试，但走的是 AOSP Live Updates 这条路，不是 vivo 私有 SDK 那条。**

| 路线 | 需要什么 | DSH 能不能用 |
|---|---|---|
| **A. AOSP Live Updates**（`setShortCriticalText` + `setRequestPromotedOngoing`，即 promoted ongoing） | 普通通知权限；vivo 的 SystemUI 扩展会**主动读取** | ✅ **可以试，无需签名权限** |
| B. vivo 私有 `IslandService` 直推（航旅纵横/12306/外卖那种精细模板） | 签名级权限 `com.vivo.island.ISLAND_SERVICE` | ❌ 第三方拿不到 |
| C. 自定义模板 extra（`notification.superx.customTemplate` 等） | 预置在通知 extras 里，需要 vivo 认可的模板 id | ⚠️ 可尝试但模板 id 未知，基本不现实 |

**最关键的一条证据**：vivo 的原子岛内部代号是 **SuperX**，而
`com.vivo.systemuiplugin.superx.notification.ongoing.SuperXOnGoingNotificationPolicy`
里**直接调用了 AOSP 的 `Notification.hasPromotableCharacteristics()`**，
并会把符合条件的通知渲染成状态栏胶囊（capsule）→ 再推到 island 进程。
所以 **AOSP Live Updates 是被 vivo 采纳的**，第三方发 promoted ongoing 通知有真实机会上岛。

---

## 一、实现架构：原子岛是谁在渲染

### 1) 独立进程 + 私有 AIDL（vivo 自家/白名单伙伴用）
`/system/priv-app/SystemUIPlugin/SystemUIPlugin.apk`（包名 `com.vivo.systemuiplugin`，130 MB）

清单里明确：

```
SERVICE: com.vivo.island.IslandService | exported= True | perm= com.vivo.island.ISLAND_SERVICE | proc= :island
```

- 跑在独立进程 **`:island`**
- 导出但要 **签名级权限 `com.vivo.island.ISLAND_SERVICE`** → 第三方无法绑定
- 相关类：`com.vivo.island.IslandService` / `IslandServiceIf` / `IslandServiceListener` /
  `IslandServiceManager`、action `com.vivo.island.action.ISLAND_SERVICE`、
  `IslandServiceProxy.postIslandDataBean(...)`、`com.vivo.island.view.IslandWindowView`
- 数据模型：`com.vivo.island.bean.{IslandDataBean, LeftIslandBean, RightIslandBean}`，
  带 `setLeftTemplate/setRightTemplate`、`setPriority`、`setAtomIslandsType`、`setLandingInfo`

### 2) 通知驱动的 SuperX 管线（**这条对第三方开放**）
同一 APK 的 `classes3.dex`：

- `SuperXOnGoingNotificationPolicy`（日志 tag **`SuperXLiveNotificationPolicy`**）
  - `init(Context)` → `PluginNotificationManager.getNotificationListener()` → `registerListener()`
    即：**vivo 自己注册通知监听，第三方什么都不用做**
  - `handleProgressStyleNotification(StatusBarNotification, boolean)`：
    - 先查黑名单 `IGNORE_NOTIFICATION_PACKAGES`（static Set，目前只见 `com.android.egg`）
    - 受 `SystemPropertiesFacade.supportAtomIsland()` 总开关约束
    - 读 `Notification.getShortCriticalText()`，日志打 `"ShortCriticalText:"` / `"appLabel:"`
    - 组装 `com.vivo.assistant.vcorentsdk.entity.CapsuleElement`
      → `SuperXCapsuleDataController.addChipsCapsuleToShow(...)`，日志 `"show capsule <pkg>"`
  - `isOngoingCallStyle(sbn)`：看 extras `android.template` / `android.callType`
  - `isOverseasTravel(sbn)`：看 extras `progress_style_business_type`
  - `saveToOnGoingAppList()` / `removeFromOnGoingAppList()`：维护
    `Settings.Secure."key_ongoing_app_list"`（逗号分隔包名）
- 视图层：`SuperXCapsuleViewController`、`SuperXCapsuleBaseView`、`StatusBarCapsuleImpl`、
  `StatusBarIslandImpl`、`LegacySuperXComponentImpl`、`SuperXManager`、`SuperXDataManager`
- 模板场景字符串（vivo 预置）：
  `vivo_superx_template_scene_flight / train / takeout / delivery / queue / timer /
   navigation / weather / meeting / health / incalling / focusmode / appstore / gamecube ...`
- 专用适配器：**`SuperXIslandUmetripController`（航旅纵横）**、
  `SuperXIslandRailWayController`（12306）、`SimilarNewSuperXTemplate12306`
  → 这解释了"航旅纵横/外卖能显示"：这些是 vivo 逐个适配的预置模板，不是通用接口。

### 3) 总开关（build property）
`com.vivo.systemuiplugin.common.utils.SystemPropertiesFacade`：

- `IS_SUPERX_ENABLED` ← `persist.vivo.superx_enabled`
- `supportAtomIsland()` ← `vivo.support.island` / `vivo.software.disable_island`

另外 `onSuperXNotificationArrived()` 开头有 `SDK_INT` 判断 + **`FtBuild.isOverSeas()`**：
**海外版直接整条跳过**，国行不受影响（本机是国行）。

---

## 二、AOSP Live Updates 支持情况

### framework.jar（全都有）
`/system/framework/framework.jar → classes.dex` 命中：

```
setShortCriticalText        setRequestPromotedOngoing     FLAG_PROMOTED_ONGOING
hasPromotableCharacteristics    isPromotedOngoing
EXTRA_SHORT_CRITICAL_TEXT       PROMOTED_ONGOING  ×2      shortCriticalText ×2
```

→ **API 是齐的，`Notification.Builder` 上可以直接用。**

### SystemUI（AOSP promoted 管线保留）
`SystemUI.apk → classes3.dex` 命中：

- `com.android.systemui.statusbar.notification.promoted.*`
  （`PromotedNotificationLogger`、`...promoted.domain.interactor.*`、`...promoted.ui.viewmodel.AODPromoted...`）
- `com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor`
- `AodPromotedNotificationSection`
- 日志字符串 `": Can't show chip because promotedContent=null, which shouldn't happen"`
  → 说明状态栏 chip 渲染路径是活的

### vivo 扩展（关键：它读 AOSP 的判定）
`SystemUIPlugin.apk → classes3.dex`：

- `SuperXOnGoingNotificationPolicy$1$$ExternalSyntheticApiModelOutline0.m()`
  → 包装 `android.app.Notification.hasPromotableCharacteristics()`
- `onSuperXNotificationArrived` 日志：
  `"onSuperXNotificationArrived hasPromotableCharacteristics " + flag + ",pkg name is " + pkg`
  以及 `",hasPromotableCharacteristics = "`、`",isOngoing = "`
- `handleProgressStyleNotification` 里用 API-outline 读 `Notification.getShortCriticalText()`

→ **vivo 没有另起炉灶，而是消费 AOSP Live Updates。**

---

## 三、第三方接入条件（能/不能/需要什么）

### ✅ 能：发一条 promoted ongoing 通知
- **不需要签名权限**，不需要白名单注册。
- 门槛只有：功能总开关（`persist.vivo.superx_enabled` / `vivo.support.island`，国行默认开）、
  黑名单 `IGNORE_NOTIFICATION_PACKAGES`（当前只有 `com.android.egg`）、
  以及可能存在的 `Settings.Secure."key_ongoing_app_list"` 名单。

### ⚠️ 不确定：`key_ongoing_app_list`
`saveToOnGoingAppList` / `removeFromOnGoingAppList` 会往这个 Secure 设置里写"逗号分隔包名"，
静态看主要在**进程被杀后的清理**路径（`handlePackageDied`）里用。
它是否也是"**必须先在这个名单里才显示**"的硬门槛，**无法单靠静态确定**——这正是实验要验证的第一件事。

### ❌ 不能：直接调 vivo 的 IslandService
`com.vivo.island.IslandService` 需要签名级权限 `com.vivo.island.ISLAND_SERVICE`；
第三方 APK 无法持有（除非平台签名或系统白名单）。

### ⚠️ 备选：自定义模板 extra
`SuperXNotificationDataUtils.validShortCardRemoteViewsInfo()` 会找这些 extras：

```
notification.superx.customTemplate      island.superx.customTemplate
keyguard.superx.customTemplate          nightPearl.superx.customTemplate
launcher.superx.customTemplate          keyguardFlip.superx.customTemplate
gameCube.superx.customTemplate
```

值应为 vivo 认可的模板 id（对应 `vivo_superx_template_scene_*`），第三方基本猜不到，**不推荐**。

---

## 四、给「测试原子岛通知」按钮的落地方案（最短代码）

### 方案 A（首选）：promoted ongoing + short critical text
```java
Notification n = new Notification.Builder(ctx, CHANNEL_ID)   // CHANNEL 重要度 >= LOW
        .setSmallIcon(R.drawable.ic_stat_test)
        .setContentTitle("DSH 原子岛测试")
        .setContentText("进行中")
        .setOngoing(true)                    // 必须 ongoing
        .setShortCriticalText("12:30")       // ★ API 36：岛/胶囊上的关键文本
        .setRequestPromotedOngoing(true)     // ★ API 36：请求走 promoted ongoing 管线
        .build();
NotificationManager nm = ctx.getSystemService(NotificationManager.class);
nm.notify(1001, n);
```

### 方案 B（更像"外卖/行程"）：ProgressStyle
```java
Notification.ProgressStyle ps = new Notification.ProgressStyle()
        .setProgress(30)                     // API 36
        .setProgressPoints(...)              // 可选
        .setStyledByProgress(false);
Notification n = new Notification.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_test)
        .setContentTitle("DSH 进度测试")
        .setOngoing(true)
        .setStyle(ps)                        // ★ ProgressStyle 会被 vivo 单独处理（handleProgressStyleNotification）
        .setShortCriticalText("30%")
        .setRequestPromotedOngoing(true)
        .build();
```

### 实验时必须一起看的 3 件事
1. **vivo 判定日志**（最能说明问题，tag 是 `SuperXLiveNotificationPolicy`）：
   ```bash
   adb logcat -s SuperXLiveNotificationPolicy   # 或手机上的日志查看器
   ```
   期望看到：
   ```
   onSuperXNotificationArrived hasPromotableCharacteristics true,pkg name is io.github.ccch1mneyyy.dsh_app
   ... hasAction = ... ,isOngoing = true
   handleProgressStyleNotification ...  /  ShortCriticalText:12:30
   show capsule io.github.ccch1mneyyy.dsh_app
   ```
   - 只有第一行没后面 → 说明被 `key_ongoing_app_list` 或优先级/过滤挡了。
   - 一行都没有 → 说明功能总开关或 listener 没生效。

2. **功能开关**：
   ```bash
   getprop persist.vivo.superx_enabled
   getprop vivo.support.island
   getprop vivo.software.disable_island
   ```
   以及确认 `io.github.ccch1mneyyy.dsh_app` 是否出现在
   `settings get secure key_ongoing_app_list` 里。

3. **通知本身是否合规**：`setOngoing(true)` + 渠道重要度不能是 NONE，
   `shortCriticalText` 要短（vivo 模板按 `island_double_line_*_text_size` 渲染），
   太长会被截断或不予显示。

### 逐步排查顺序（建议 v3 APK 按钮按这个来）
1. 只发一条普通 ongoing 通知 → 看岛上有没有反应（验证 listener 是否工作）
2. 加 `setShortCriticalText` → 看是否出现胶囊/chip
3. 加 `setRequestPromotedOngoing(true)` → 看 `hasPromotableCharacteristics` 是否为 true
4. 换成 `ProgressStyle` → 看 `handleProgressStyleNotification`
5. 每步都抓 `SuperXLiveNotificationPolicy` 日志

---

## 五、不确定项（要真机动态验证 / 特权才能确认）

1. `Settings.Secure."key_ongoing_app_list"` 当前值，以及它是不是显示门槛。
2. `persist.vivo.superx_enabled` / `vivo.support.island` 的实际值。
3. vivo 的 `IGNORE_NOTIFICATION_PACKAGES` 完整集合（静态只看出 `com.android.egg`，可能还有运行时追加）。
4. 是否存在**应用级开关** `superx_notification_<包名点转下划线>`（在 dex 里见到
   `superx_notification_com_android_bbksoundrecorder`、
   `superx_notification_com_vivo_assistant_focusmode` 这种 key）——
   这类 per-app 开关如果默认关闭，第三方可能需要在 vivo 设置里手动允许。
5. `com.vivo.systemuiplugin` 是否在启动时按 `FeatureOption` 动态关掉 SuperX。

---

## 六、复现方法

```bash
# 1. 定位承载 APK
ls /system/priv-app/SystemUI /system/priv-app/SystemUIPlugin
# 2. 解 dex
python3 - <<'EOF'
import zipfile,os
for tag,p in [('sui','/system/priv-app/SystemUI/SystemUI.apk'),
              ('plugin','/system/priv-app/SystemUIPlugin/SystemUIPlugin.apk'),
              ('fw','/system/framework/framework.jar')]:
    os.makedirs('/tmp/a5/'+tag,exist_ok=True)
    z=zipfile.ZipFile(p)
    for n in z.namelist():
        if n.endswith('.dex'):
            open('/tmp/a5/%s/%s'%(tag,os.path.basename(n)),'wb').write(z.read(n))
EOF
# 3. 清单（UTF-16LE！）
python3 /root/workspace/scripts/axml.py /system/priv-app/SystemUIPlugin/SystemUIPlugin.apk | grep -i island
# 4. 反汇编 + 归属（dexdump 是 Android 自带，proot 内可直接跑）
export ANDROID_ROOT=/system
export LD_LIBRARY_PATH=/system/lib64:/apex/com.android.art/lib64:/apex/com.android.runtime/lib64
/apex/com.android.art/bin/dexdump -d /tmp/a5/plugin/classes3.dex > /tmp/a5/plugin3.txt
grep -n "hasPromotableCharacteristics\|ISLAND_SERVICE\|key_ongoing_app_list\|superx.customTemplate" /tmp/a5/plugin3.txt
```

工具：Android 自带 `dexdump`、`/root/workspace/scripts/axml.py`（二进制清单）、`/root/workspace/arsc.py`
（resources.arsc，现代 FLAG_OFFSET16 需要 `androguard`；Ubuntu 的 aapt v0.2 会解析错）。
