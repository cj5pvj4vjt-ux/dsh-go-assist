# GhostLock-X200 独立核验报告

- 核验人：verifier（独立核验，未参与文档撰写）
- 核验时间：2026-11-06（Asia/Shanghai）
- 核验对象：`xiaohj233/ghostlock-x200-root`（PC 版）、`xiaohj233/ghostlock-x200-app`（手机端 App）
- 核验方式：**只读**。抓取原文 + 真机只读属性读取；**未安装任何软件、未修改系统、未在真机上执行 exploit、未改动 `/root/workspace` 下其他文件**。
- 一句话结论：**内核与机型逐字段吻合（b57af212129c、PD2415/X200、Dimensity 9400），但本机装机版本是 `PD2415_A_16.1.12.28.W10`，并不等于项目唯一「已实测」的 `16.1.12.2.W10`，因此本机属于官方口径的「未实测，可尝试」档；能否成功 UNVERIFIED（本任务禁止实机跑 exploit）。**

---

## 0. 本机实测事实（第一手证据）

| 项 | 实测值 | 来源 |
|---|---|---|
| `uname -r` | `6.6.89-android15-8-gb57af212129c-abogki457297774-4k` | 本机 `uname -r` |
| `uname -a` | `Linux localhost 6.6.89-android15-8-gb57af212129c-abogki457297774-4k #1 SMP PREEMPT Mon Nov 3 14:51:49 UTC 2025 aarch64 aarch64 aarch64 GNU/Linux` | 本机 `uname -a` |
| 型号 | `V2415A`（`ro.product.model`），品牌 vivo | `/system/bin/getprop` |
| 设备代号 | `PD2415`（`ro.product.device` = `ro.product.vendor.device` = `ro.product.name`） | 同上 |
| SoC | `mt6991`（`ro.board.platform`，即天玑 9400） | 同上 |
| Android | `16` / SDK `36` | `ro.build.version.release` / `ro.build.version.sdk` |
| 装机版本 | **`PD2415_A_16.1.12.28.W10`**（`ro.build.display.id` = `ro.build.software.version`） | 同上 |
| 系统皮肤 | `OriginOS 6`（`ro.vivo.os.build.display.id`） | 同上 |
| 安全补丁 | `2026-06-01` | `ro.build.version.security_patch` |
| 系统构建指纹 | `vivo/PD2415/PD2415:16/BP2A.250605.031.A3_V000L1/compiler260717051329:user/release-keys`，`ro.build.date = Fri Jul 17 05:13:29 CST 2026` | 同上 |
| Bootloader | `ro.boot.verifiedbootstate=green`、`ro.boot.flash.locked=1`（**已锁**） | 同上 |
| 构建类型 | `ro.build.type=user`、`ro.build.tags=release-keys` | 同上 |
| 设备行（DSH 诊断） | `设备：vivo · vivo · V2415A · Android 16 (SDK 36)` | `/root/.dsh/dsh-app/diagnostics.md` 第 7 行 |

读取方式说明（可复现）：

```
uname -a
/system/bin/getprop ro.build.display.id   # => PD2415_A_16.1.12.28.W10
/system/bin/getprop ro.product.device     # => PD2415
grep -n "设备" /root/.dsh/dsh-app/diagnostics.md
```

> 注意：在 proot 里 `cat /proc/version`、`/proc/uptime`、`/proc/modules`、`/proc/cmdline` 均为 `Permission denied`；`/sys/fs/selinux/enforce` 不存在（proot 未暴露），所以 `getenforce` 返回的 `Disabled` **不能当作真机 SELinux 状态**（见 §6 UNVERIFIED）。装机版本可以用 `/system/bin/getprop` 读到（会有 linker 警告，但值正常）。

---

## 1. 支持条件逐字段核对

### 1.1 项目原文的口径

`ghostlock-x200-root` README（<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/README.md>）「支持的设备」表：

| 设备 | 系统 / 内核 | 状态 |
|---|---|---|
| vivo X200 (PD2415) | 16.1.12.2.W10（已实测验证） | ✅ 可用 |
| vivo X200s | 16.1.12.12.W10（b57 同内核…；**未实体机实测**） | ⚠️ 可尝试 |
| vivo X200 (PD2415) | 16.1.12.12.W10（同内核构建 b57） | ⚠️ 未实测 |
| vivo X200 (PD2415) | 其他 16.1.x，内核 b57af212129c | ⚠️ 未实测，可尝试 |
| vivo X200 (PD2415) | 内核 >= 6.6.140 | ❌ 已修复，不可用 |

> 原文：「前提：内核 `< 6.6.140`（CVE-2026-43499 未修复）且构建为 `6.6.89-android15-8-gb57af212129c`。未实测的机型/构建需要自行验证。」
> 原文（适配口径）：「本项目**仅对表中标 ✅ 的 vivo X200（PD2415 / 16.1.12.2.W10 / b57 内核）做过完整真机适配**。其余所有机型…均为**未实测 / 需自行适配**」

`docs/USAGE.zh-CN.md`（<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/docs/USAGE.zh-CN.md>）同义复述，并给出检查命令：

> 「```adb shell cat /proc/version  # 期望: 6.6.89-android15-8-gb57af212129c-...```」
> 「内核 `< 6.6.140`（漏洞未修复）且构建为 `b57` 是必要条件，但最终是否可用仍需在具体设备上实测。」

### 1.2 逐字段比对结果

| 条件字段 | 项目要求 | 本机实测 | 判定 |
|---|---|---|---|
| 内核版本上限 | `< 6.6.140` | `6.6.89` | ✅ 吻合（89 < 140） |
| 内核构建 | `6.6.89-android15-8-gb57af212129c` | `6.6.89-android15-8-gb57af212129c-abogki457297774-4k` | ✅ **精确命中**（前缀全等；且与 App `docs/DESIGN.md` 硬编码 `DEV_KERNEL_RELEASE` 字符串逐字相同） |
| 机型 | vivo X200 / PD2415 | `ro.product.device=PD2415`、型号 `V2415A` | ✅ 吻合（V2415A 即 X200 的商用型号；`ro.board.platform=mt6991`=天玑 9400） |
| 系统构建（ROM 版本） | 「已实测」= `16.1.12.2.W10` | **`16.1.12.28.W10`** | ❌ **不吻合**，落在「其他 16.1.x，内核 b57af212129c → ⚠️ 未实测，可尝试」行 |

**结论 1（档位判定）**：
- 「已实测」档 = **仅** vivo X200 (PD2415) + `16.1.12.2.W10` + b57 内核（README/USAGE 双处注明，v1.0.0 Release 说明也写「OriginOS 16.1.12.2.W10 / Kernel:6.6.89-android15-8-gb57af212129c」）。
- 「未实测可尝试」档 = X200s（同内核）、`16.1.12.12.W10`、以及**其他任何 16.1.x 且内核为 b57af212129c 的构建**。
- **本机属于「未实测，可尝试」档**：内核条件全等、机型属目标机型，但**装机版本不是唯一实测项**（本机 `16.1.12.28.W10` ≠ `16.1.12.2.W10`）。任务前提中「本机装机版本 16.1.12.2.W10」不成立，特此纠正。
- 能否真正 root：**UNVERIFIED**。禁止实机执行 exploit，且项目文档自述「除 X200 b57 外…能否 root 不保证」。
- 补充证据（降低「内核已修复」的可能）：本机运行内核的编译时间为 **2025-11-03**（`uname -a` 的 `#1 SMP PREEMPT Mon Nov 3 14:51:49 UTC 2025`），而 CVE-2026-43499 的修复是 **2026-04** 才进主线的（见 §7 第三方来源）。内核二进制早于补丁 5 个月，**理论上无法包含该修复**（除非厂商重新编译内核，而那会改变 build 串/时间戳）。这是**推断**，不是实测 exploit 结论。

---

## 2. 临时 root 重启后到底什么会消失

| 对象 | 重启后 | 证据 |
|---|---|---|
| 内核模块（`permissive_restore.ko` / `kernelsu.ko` / `clear_vr_tag.ko`） | **消失**（insmod 不持久） | root 仓库 v1.3.5 Release 说明：「在线卸载内核模块 (rmmod) 会触发 kernel panic; 工具不提供在线卸载, **重启设备即清除**。」<https://github.com/xiaohj233/ghostlock-x200-root/releases> |
| SELinux permissive 状态 | **消失**（`selinux_state` 在内核内存，且 STAGE1 是内存写） | README：「临时 root：**重启即失效，不写入系统分区**」；USAGE：「无持久化、**重启即卸载**」 |
| `su` / KernelSU 能力 | **消失**（依赖 `kernelsu.ko`） | App README：「重启后 root 失效，重复步骤 1-3」 |
| root 通道 `rootcmd` socket | 消失（进程/内存态） | USAGE：「rootcmd 是本项目默认的 root 通道，不经过 su」 |
| **`/data/adb/ksud` 文件** | **保留**（写在 /data，属持久残留） | App `docs/DESIGN.md` STAGE6：「ksud 落位 `/data/adb/ksud`」；Release v1.3.5：「ksud 在 root 窗口内落位 /data/adb (STAGE6.0)」 |
| 提权二进制（glt/w2host/ksud） | 由工具每次重新 push/解压 | USAGE「预编译资产」节；App `payload/` |

**是否需要重跑整条链：需要。** 每次冷启动后必须完整重跑 STAGE1–7（模块要重新 insmod、SELinux 要重新写、cred 要重新泄漏）。原文：

> USAGE 已知限制 1：「**临时 root**：每次冷启动后需重跑（约 1-2 分钟）。」
> USAGE FAQ：「不是。这是临时 root：每次冷启动后需重跑一次（约 1-2 分钟）。适合短期研究/测试，**不适合当作日常持久 root 使用**。」
> App `docs/DESIGN.md` §1.1 非目标：「**不做持久 root**（BL 未解锁，kprobe 临时提权重启失效，为物理约束）。」

---

## 3. 重启后的具体步骤与耗时

### 3.1 PC 路线（ghostlock-x200-root）

重启后步骤（依据 README 快速开始 + USAGE 手动脚本 + v1.3.2 Release 说明）：

1. 手机开机，连 USB（或无线 adb），确认 `adb devices` 能识别、授权弹窗已允许；
2. **等待设备稳定**：脚本内置 `uptime>240s` 门禁（即开机后约 4 分钟），v1.3.2 起「设备稳定等待（uptime>240s）：等待期间每 30 秒打印进度提示；支持按 `S` 键跳过…并明确提示『设备未稳定可能导致内核 panic』」；
3. 双击 `run_root.bat`（或 `powershell -ExecutionPolicy Bypass -File root.ps1`）→ 自动检测内核、跑 STAGE1–7；
4. `adb shell getenforce` 期望 `Permissive`；有 KSU 时 `adb shell /data/local/tmp/ksud debug version` → `32525`。

耗时（项目自述）：链本身「约 1-2 分钟」+ 开机稳定等待 ~4 分钟（uptime 240s）+ STAGE7 的 25 秒 permissive 校验 → **约 5-6 分钟**（跳过等待则约 1-2 分钟，但有 panic 风险提示）。
失败时脚本还会等待设备重启回来（v1.3.2 起上限从 12 分钟缩短为 3 分钟）。

引用：USAGE 快速开始/验证节（<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/docs/USAGE.zh-CN.md>）；v1.3.2 Release 说明（<https://github.com/xiaohj233/ghostlock-x200-root/releases>）。

### 3.2 App 路线（ghostlock-x200-app）

重启后步骤（App README「使用」节逐步）：

1. **启动 Shizuku**（Android 16 需 GitHub 版 13.6.0+；无线调试方式，需重新开启无线调试并点启动）；
2. 打开 App，按引导授予 Shell 权限；
3. 点「启动 Root」，等待各阶段完成（**约 1-2 分钟**）；
4. 终端 `su -c id` 应输出 `uid=0(root)`。

前置提醒原文：「注意：**开机后请先等约 4 分钟再运行**；root 过程中可能触发内核重启（可自动恢复，无变砖风险）。」
已知问题：「1.1.0 …第 9 步可能等待较久并显示验证未通过，但实际功能可能已经可用。当前仍保留约 **120 秒验证上限**」。
runner 侧门禁：「开机 uptime < 240 s 时 STAGE1 前等待（避免开机风暴下竞速部分命中触发 panic）」。
耗时合计：**约 4 分钟等待 + 1-2 分钟执行（+ 最多 120 秒末检）≈ 5-8 分钟**。

引用：App README（<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/README.md>）、App `docs/DESIGN.md` §4。

---

## 4. App 路线前置条件：Shizuku

| 问题 | 项目原文 | 独立核验 |
|---|---|---|
| Shizuku 版本要求（Android 16） | 「安装并启动 Shizuku（**Android 16 需 GitHub 版 13.6.0+**）」 | ✅ 与 Shizuku 官方 release 一致：v13.6.0「Support Android16 QPR1」（2025-05-24）<https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0> |
| 重启后 Shizuku 是否需重新启动 | 隐含在「重启后 root 失效，**重复步骤 1-3**」（步骤 1 = 安装并启动 Shizuku） | ✅ 官方手册明确：「Starting with wireless debugging works on Android 11 or above… **Due to system limitations, the startup steps need to be performed again after each reboot.**」<https://shizuku.rikka.app/guide/setup/> |
| 能否无线调试自举 / 是否必须电脑 | App README：「通过 Shizuku（支持无线调试自举）授权后即可使用，**无需电脑 adb**」 | ✅ 官方手册：「This startup method **does not require a connection to a computer**」；「Pairing (**only needs once**)」——即**配对只用做一次，但每次重启都要重新执行「启动」步骤**（Android 系统限制，无线调试开关重启后复位） |
| 是否自动识别 KernelSU/SukiSU/ReSukiSU | App README：「支持自动识别已安装的 KernelSU / SukiSU / ReSukiSU，并使用该版本的管理器加载程序；多个管理器时选择其中一个」 | ✅ 另有 `docs/MANAGER_LOADING.md`、`CHANGELOG.md` 1.1.0「自动识别已安装的 KernelSU、SukiSU、ReSukiSU；安装多个时可选择」；DESIGN 也提到 ksud 来自管理器（三个 ko 之一）。<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/docs/MANAGER_LOADING.md> |

补充：Shizuku 官方 FAQ 建议开启「Disable adb authorization timeout」、保持 USB 调试/开发者选项常开，否则 Shizuku 会随机停止（<https://shizuku.rikka.app/guide/setup/>）。项目文档未提到这两点。

---

## 5. 其他重启后需要注意的

| 事项 | 结论 / 证据 |
|---|---|
| 开机后等多久 | **约 4 分钟**（`uptime>240s`）。App README「开机后请先等约 4 分钟再运行」；App `DESIGN.md`「开机 uptime < 240 s 时 STAGE1 前等待」；PC 脚本 v1.3.2 起同样等待，可 `S` 跳过（提示可能 panic） |
| `permissive_restore` 的 25 秒恢复行为 | 模块加载时不立刻 permissive，而是「一个延迟线程在 **25 秒后**字节写 `enforcing = 0`（单字节；不碰 `initialized`/`policycap`）」；主链 STAGE7 做「**25s permissive 验证** + 网络」。所以：加载后 25 秒内 `getenforce` 可能仍是 `Enforcing`，不要误判失败；顺序必须先 insmod `permissive_restore` 再 `kernelsu`（否则 KSU 阻断 socket 后无法改回 permissive）。引用：USAGE「permissive_restore 模块」节、`docs/ARCHITECTURE.zh-CN.md`「worker：25s 后 enforcing=0」 |
| 异常重启可能影响的数据 | USAGE 已知限制 3：「**异常重启副作用**：强制重启（如 sysrq）可能损坏 /data 数据（脚本使用唯一后缀避免 inode 问题；**蓝牙配对可能需要重新做一次**）」。FAQ 同义：「异常重启…可能损坏 /data inode…蓝牙配对可能需要重做一次」 |
| 本机是否已有残留 | 从 proot 视角 `/data/adb` **不存在** → 未发现 KernelSU/ksud 残留（也间接说明本机此前未做过这套 root）。注意 proot 未必完整暴露 /data，此结论强度有限 |
| USB / 无线调试是否要重新授权 | **UNVERIFIED（项目未文档化）**。可确定的相邻事实：Shizuku 官方手册要求「每次重启后重新执行启动步骤」（无线调试需重新开启）；官方 FAQ 建议开启「Disable adb authorization timeout」。adb RSA 授权本身通常跨重启保留，但本项目文档**没有**说明，不能替其断言 |
| 在线卸载模块 | 不要 `rmmod`：「在线卸载内核模块 (rmmod) 会触发 kernel panic; 工具不提供在线卸载, 重启设备即清除」（v1.3.5 Release） |

---

## 6. 风险、禁忌与「不适合当日常持久 root」的原因

1. **不要升级系统**：USAGE 已知限制 4「**不要升级系统**：较新的 vivo 构建可能修复 CVE-2026-43499（>= 6.6.140 已修复）」；FAQ「内核 >= 6.6.140 已修复 CVE-2026-43499，无法使用。升级前先 `adb shell cat /proc/version` 确认内核版本」。**本机若升级到内核 ≥ 6.6.140 即永久失效。**
2. **不是持久 root、不适合日常使用**：每次冷启动都要重跑；原文「适合短期研究/测试，不适合当作日常持久 root 使用」；DESIGN「不做持久 root…为物理约束」。且**每重启一次就要重新 Shizuku → App → 1-2 分钟链**。
3. **panic / 异常重启风险**：工具自述「root 过程中可能触发内核重启（可自动恢复，无变砖风险）」、「STAGE5 写链为概率性时序原语…设备负载过高时（loadavg≥20）直接中止」；非 b57 构建强跑有 panic 风险（`-Force`「其他构建有 panic 风险」）。
4. **数据风险**：异常重启可能损坏 `/data` inode、需重做蓝牙配对（见 §5）。项目建议「操作前请备份重要数据」（v1.0.0 Release）。
5. **`su` 被厂商检测**：「KernelSU su 在某些场景可能触发 vivo 检测 panic；默认改用 rootcmd」；另有 `clear_vr_tag.ko` 专门对抗 vivo 的 `vr.ko` 反 root 模块（kretprobe `commit_creds`）——说明**固件层存在主动对抗**，ROM 越新越可能加强。
6. **`rmmod` 会 panic**（见 §5）。
7. **法律/保修**：README 顶部声明「仅用于安全研究与教育目的…只允许在你自己拥有、或已获得明确书面授权的设备上使用…可能导致设备异常重启、数据损坏、系统异常、**保修失效**」。工具本身不解锁 BL、不刷机，但 `ro.boot.flash.locked=1` 下仍属高风险内核利用。
8. **对非实测构建无保证**：本项目口径「仅 X200 (PD2415) b57 真机完整验证（STAGE1-7 全通过）」，其余均「未实测」。**本机（`16.1.12.28.W10`）正属此类。**

---

## 7. CVE-2026-43499（GhostLock）背景的独立来源核验

| 事实 | 独立来源 |
|---|---|
| CVE 真实存在，是 rtmutex/futex 的 stack-UAF，2011 年（2.6.39）引入、2026 年修复 | NebuSec 原文：<https://nebusec.ai/research/ionstack-part-2> —「GhostLock (CVE-2026-43499)… introduced in Linux 2.6.39 and fixed in Linux 7.1… every major distribution since 2011」 |
| 受影响/修复版本：`2.6.39–6.6.139` 受影响，`6.6.140+` 已修复 | AhnLab ASEC 公告：<https://asec.ahnlab.com/en/94452/> —「Affected… 2.6.39 Through 6.6.139… Fixed versions include 6.6.140 Or higher…」（与项目 USAGE 的「已修复：6.1.175 / 6.6.140 / 6.12.86…」方向一致） |
| 6.6 稳定分支已在 6.6.140 修复 | Debian kernel-sec：<https://kernel-team.pages.debian.net/kernel-sec/CVE-2026-43499.html> —「6.6-upstream-stable | released (6.6.140) [8a1fc8d698ac…]」 |
| 本机固件版本真实存在 | 第三方固件站：<https://onfix.cn/rom/1057916> —「PD2415_A_16.1.12.28.W10.V000L1… Android 16 安全补丁：2026-06-01… OTA全量包」（与本机 `ro.build.version.security_patch=2026-06-01`、指纹 `…V000L1` 完全一致） |
| 同题材第三方项目存在（佐证可行性，但**不含本机内核**） | YuKongA/ghostlock-app 支持列表 68 条内核里**没有** `b57af212129c`（也无 PD2415/V2415），最接近的是 X200 Pro `6.6.30…`：<https://raw.githubusercontent.com/YuKongA/ghostlock-app/main/docs/kernel_profiles/SUPPORTED_DEVICES.md> |

Release 资产真实可下载（已核 HTTP 200）：

- `ghostlock-x200-root-v1.3.5.zip`（6.43 MB，sha256 `0189a9ab…`）<https://github.com/xiaohj233/ghostlock-x200-root/releases/expanded_assets/v1.3.5>
- `GhostLockX200-v1.1.0.apk`（6.63 MB，sha256 `9d415719…`）<https://github.com/xiaohj233/ghostlock-x200-app/releases/expanded_assets/v1.1.0>

---

## 8. 存疑 / 文档缺陷清单（UNVERIFIED 与不一致）

1. **能否成功 root：UNVERIFIED。** 未实机执行（任务禁止）；本机 ROM 版本又不属实测项，只能标注为「未实测，可尝试」。要真正确认需实机跑一次，且建议先备份数据。
2. **装机版本与实测版本不一致（关键）。** 本机 `PD2415_A_16.1.12.28.W10`，项目唯一实测项是 `16.1.12.2.W10`。项目表格里的 `16.1.12.12.W10`/`16.1.12.28` 之间的关系、以及 `28` 是否只是 `2` 的笔误，**无法从原文判定**——但按字符串并不相等，安全起见按「未实测」处理。建议向作者确认（附上 `/system/bin/getprop ro.build.display.id` 输出）。
3. **内核是否已修复：UNVERIFIED（但有强推断）。** 项目唯一门禁是 `uname -r` 字符串（App `DESIGN.md` STAGE0 `gate_kernel()`），**无法发现「厂商回移植补丁但保留旧版本串」的情况**。本机内核编译时间（2025-11-03）早于补丁进入主线（2026-04），故「未修复」的可能性很大；如要硬证，需检查设备内核 `remove_waiter()` 是否含 `3bfdc63936dd` 修复（参考第三方做法：`ghostlock-extract` 对已修补的 `remove_waiter()` 以 exit code 6 拒绝）。
4. **真机 SELinux/模块现状：UNVERIFIED。** proot 内 `/proc/modules`、`/sys/fs/selinux/enforce` 不可读，`getenforce` 返回的 `Disabled` 不可作为设备真实状态（只是 proot 未暴露该节点）。
5. **USB / 无线调试重启后是否要重新授权：UNVERIFIED（项目未文档化）。** 只能确认 Shizuku 服务每次重启都要重新启动。
6. **文档不一致 1**：README「快速开始」让下载 `ghostlock-x200-root-v1.3.4.zip`，但文档标题是 v1.3.5、且实际 Release 资产是 `v1.3.5.zip` 与 `v1.3.4.1.zip`，**不存在 `v1.3.4.zip`**。
7. **文档不一致 2**：USAGE 写「预编译 `permissive_restore.ko` 随 Git 树分发…其余二进制作为 GitHub Release 资产发布，不进 Git 树」，而 README 快速开始说 Release zip 内含 `prebuilt/` 与 `modules/*.ko`「缺一不可」——两处对「哪些在 Git 树、哪些在 Release」的表述需按实际解包核对。
8. **`docs/CHANGELOG.md` / `docs/DESIGN.md` / `docs/MANAGER_LOADING.md`（root 仓库）不存在（HTTP 404）**；对应内容在 App 仓库与 GitHub Releases 页面。任务里提到的这几个文件名在 root 仓库未命中。
9. **本机 `ro.build.description` 为空、`getprop ro.kernel.version` 只返回 `6.6`**，不能作为完整版本判据，以 `uname -r` 为准。

---

## 9. 参考链接汇总

- root 仓库 README：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/README.md>
- root 仓库使用说明：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/docs/USAGE.zh-CN.md>
- root 仓库架构：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/docs/ARCHITECTURE.zh-CN.md>
- root 仓库来源清单：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-root/main/SOURCE-URLS.md>
- root 仓库 Releases：<https://github.com/xiaohj233/ghostlock-x200-root/releases>
- App 仓库 README：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/README.md>
- App 仓库设计：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/docs/DESIGN.md>
- App 仓库管理器加载：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/docs/MANAGER_LOADING.md>
- App 仓库更新日志：<https://raw.githubusercontent.com/xiaohj233/ghostlock-x200-app/main/CHANGELOG.md>
- App 仓库 Releases：<https://github.com/xiaohj233/ghostlock-x200-app/releases>
- CVE 原始研究（NebuSec IonStack part II）：<https://nebusec.ai/research/ionstack-part-2>
- AhnLab ASEC 公告：<https://asec.ahnlab.com/en/94452/>
- Debian kernel-sec：<https://kernel-team.pages.debian.net/kernel-sec/CVE-2026-43499.html>
- Shizuku 官方手册：<https://shizuku.rikka.app/guide/setup/>
- Shizuku v13.6.0 Release：<https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0>
- 第三方同题材项目（不含本机内核）：<https://raw.githubusercontent.com/YuKongA/ghostlock-app/main/docs/kernel_profiles/SUPPORTED_DEVICES.md>
