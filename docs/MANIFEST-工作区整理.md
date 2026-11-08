# MANIFEST — 工作区整理留档（2026-11-07 01:0x）

`/root/workspace` 根目录做过一次整理：**41 项**散落文件与旧项目目录被移入
`docs/ scripts/ data/ projects/ attic/`，**没有删除任何文件**（连 0 字节的 `err*.txt` 也只移动）。
活跃目录与 `engine-bridge/` 一律未动。

## 交付物位置
| 内容 | 路径 |
|---|---|
| MOVE 清单（旧→新 + 原因 + 引用同步 + 自检证据） | `/sdcard/Download/DSH-Go/MOVE-清单.md` |
| 刻意未动清单 + 原因 | `/sdcard/Download/DSH-Go/刻意未动-清单.md` |
| 目录树整理前后对照 | `/sdcard/Download/DSH-Go/目录树-整理前后.md` |
| 一键回滚脚本（干跑 / `--apply`） | `/sdcard/Download/DSH-Go/rollback-工作区整理.sh` |
| 全量备份（整理前） | `/sdcard/Download/DSH-Go/backups/workspace-full-20261107-010008.tar.gz` |

回滚命令：`bash /sdcard/Download/DSH-Go/rollback-工作区整理.sh --apply`
（不带 `--apply` 为干跑，只打印不执行；旧路径已存在时会跳过而非覆盖）

备份指纹：81,628,040 字节 / `tar -tzf` 759 条目、635 文件 /
sha256 `ce991bfcf41332015bac84f465395bb9bc5cb4ca0b046bb666d6687449190ece`

## 现在的目录约定
- `docs/`　　分析报告 .md，文件名带项目前缀（如 `APK隐私审计-privacy-audit.md`）
- `scripts/`　一次性 APK 解析 / 扫描工具（`axml.py`、`dexstr.py`、`scan_all.py` …）
- `data/`　　　扫描结果 JSON（`scan_all.json`、`scan_urls.json`）与未被引用的图片 `cat.png`
- `projects/`　不活跃旧项目：`虚拟定位-FakeTraveler/`、`系统窗口注入-syswin-proto/`、
  `Shizuku提权-privshell/`、`图片生成-generated/`、`网络探测-nettest/`
- `attic/`　　空 `err*.txt`、自动生成的 `__pycache__-2026-11-06/`（只留存，不删除）
- 根目录保留：`arsc.py`、`build-assist-apk.sh`、`hardening.md`
  —— 它们被活跃脚本 / 活跃目录硬编码引用（见《刻意未动-清单.md》）

## 未动（禁止动的）
`engine-bridge/`（web profile `link:` 依赖 + asr-server.mjs pid 22113 运行中）、
`assist-apk/`、`env-setup/`、`voice/`、`asr/`、`abs-scan/`（整理期间仍在被写入）；
另 `/root/.dsh` 不属本任务。

## 计数对照
- 根目录条目：50 → 15（含 5 个新建目录 + 本 MANIFEST）
- 文件总数（口径 `find ! -type d`，排除持续增长的 `assist-apk/`、`env-setup/`、`abs-scan/`）：**256 → 257**
  （+1 即本 MANIFEST；与备份做“施加 move map 后”的精确 diff，除本文件外**零差异**，41 项移动 0 丢失）
