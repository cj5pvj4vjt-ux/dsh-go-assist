# 临时 root 后的安全加固清单（本机实测）

设备：vivo X200（V2415A）· Android 16 · `PD2415_A_16.1.12.28.W10`
内核：`6.6.89-android15-8-gb57af212129c-abogki457297774-4k`
时间：2026-11-06

## 一、现在实际暴露了什么（实测，不是推测）

| 项 | 实测状态 | 证据 |
|---|---|---|
| SELinux | **宽容模式（Permissive）** | KernelSU 管理器「SELinux 状态：宽容模式」 |
| 内核 | KernelSU 已加载，`在工作中 <LKM> [越狱模式]` v32525-2 | KernelSU 主页 |
| 超级用户 | **已授权 2 个应用** | KernelSU 主页「超级用户 2」 |
| 模块 | 0 个 | KernelSU 主页「模块 0」 |
| su 落位 | `/system/bin/su`（4556352 B，实为 ksud） | `ls -la /system/bin/su` |
| Shizuku | 运行中（13.5，adb 模式），已授权 2 个应用 | Shizuku 界面 |
| 调试通道 | USB 调试开着，手机正连着 Mac 的 adb | 起 Shizuku 的方式 |
| 残留 | `/data/adb/ksud` 等写在 /data（重启不消失） | 项目文档 STAGE6 |

**一句话风险等级**：现在是「内核已可被任意提权 + SELinux 不再拦」的状态。
宽容模式是跑这套链的必然副作用（`permissive_restore` 模块 25 秒后把 `enforcing` 写成 0，
好让 KernelSU 的 sepolicy 不把网络/热点打死）。它不会自己恢复。

## 二、立刻做（5 分钟，越小越好）

1. **恢复 SELinux 强制模式**（最划算的一步）。Mac 上：
   ```bash
   adb shell su -c setenforce 1
   adb shell getenforce        # 期望 Enforcing
   ```
2. **看一眼超级用户名单**：KernelSU → 超级用户，只留你确实要用的；
   不认识的条目直接撤销（KernelSU 3.2.5 里点进条目可撤销）。
3. **收紧 Shizuku 授权**：Shizuku → 「已授权 2 个应用」→ 撤掉不用的
   （注意：GhostLock 那条先留着，重启后重跑还要用）。
4. **关掉不用的调试通道**：设置 → 开发者选项 → 关「USB 调试」（或手机上
   「撤销 USB 调试授权」），Mac 上 `adb kill-server`。要用时再开。

## 三、用完就做（根治）

- **重启一次**。重启后：三个内核模块卸载、KernelSU 失效、SELinux 回 Enforcing、
  `/system/bin/su` 消失（它是 overlay）。
- 想连残留一起清（可选，`/data/adb` 下的文件重启不会消失；清掉后再想 root 得重跑链）：
  ```bash
  adb shell su -c "ls -la /data/adb"      # 先看
  # 确认后： adb shell su -c "rm -rf /data/adb/ksud"   # 谨慎，删了不可恢复
  ```
- 不用了就卸载 GhostLock X200；KernelSU 管理器可留可删（删了不影响内核模块，重启即清）。

## 四、这期间的行为纪律

- 不要在宽容模式 + 已 root 的窗口里装来源不明的 App、点陌生链接、装来路不明的「模块」——
  提权门槛已经被降到接近 0。
- 不要把 root 当成日常状态用；这套就是短期研究用的，文档自己也说「不适合日常持久 root」。
- 别在开着 adb + USB 调试时把手机连到不可信的电脑。

## 五、每次重启之后（临时 root 的代价）

1. 插线 → `adb devices`
2. `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
3. GhostLock X200 → 「启动 Root」，等 1–2 分钟
4. 用完重复第二节第 1 步（`setenforce 1`）

## 六、一个必须说清的权衡

你这台 `16.1.12.28.W10` **没有**修掉 CVE-2026-43499（实测能跑通就是证据）。
- 好处：随时能用这套临时 root。
- 代价：**任何**拿到 shell 的东西（恶意 App、被诱导开的调试权限）都能用同类手法提权。
- 官方修复要等内核 ≥ 6.6.140。长期看，升级系统 = 永久关掉这个洞；要不要留，你自己权衡。
