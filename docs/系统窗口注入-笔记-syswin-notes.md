# 系统级窗口可行性：Lead 侧预研结论（真机取证）

## 权限层
- `android.permission.INTERNAL_SYSTEM_WINDOW` → **signature|privileged**（不是纯 signature）→ 理论上有两条路可拿。
- `android.permission.STATUS_BAR` → signature|privileged。
- `android.permission.SYSTEM_ALERT_WINDOW` → normal（我们已有）。

## 窗口层级实测（dumpsys window windows，从上到下）
```
SideSlideGestureBar-Bottom / Right / Left   type=2024
SideSlideDisplayPanel                       type=2024
SuperCard                                   type=2009
NotificationShade                           type=2040
StatusBar                                   type=2000
com.dshgo.assist (我们的悬浮窗)             type=2038
前台应用窗口                                 type=1
```

## 路线 A：放进 /system/priv-app（改系统）
- `ro.control_privapp_permissions = enforce` → 必须同时提供 `privapp-permissions` 白名单 XML。
- `/system_ext` 实测 **erofs (ro)**，`/system` 同级只读 → 必须靠 KernelSU 的 overlayfs 模块挂载。
- 代价：改系统分区 + 每次 ROM 更新重做 + **每次重启要重新提权**（临时 root 用户尤其痛）。
- 结论：能，但不推荐（脆弱、不可持续）。

## 路线 B：root(uid=0) 进程直接 addView 系统类型窗口（不改系统）
- 依据：AOSP `ActivityManager.checkComponentPermission()` 对 uid 0 直接返回 GRANTED；窗口权限检查走的就是它。
- 前提：需要能起一个 uid=0 且带可用 Context/Display 的进程（Shizuku UserService 或 root shell + app_process）。
- 状态：**由 syswin-scout 做最小原型验证**（本机 proot 跑 Android 二进制有坑：缺 /vendor、/linkerconfig，之前实测 app_process 起不来 → 优先在 Android 侧用 root shell 做）。
- 结论：待验证。

## 路线 C：不 root 的折中
- 会话期间 `settings put global policy_control immersive.full=*` 临时隐藏系统栏 → 辉光可铺满整屏；结束时恢复。
- 风险：系统栏隐藏期间上滑手势/返回手势行为变化、可能有闪动；且需要 root 写 Global。
- 或者：辉光底边贴着导航栏上沿（v21 已实现），视觉上像一个完整的框。
