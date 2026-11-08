# DSH 小V（vivo 语音助手替换）— 最终交付包

版本：APK v52 / 插件 engine-bridge / 本地模型 router
生成时间：见包内文件时间

## 目录结构
- `assist-apk/`  — App 源码 + 构建工具 + 产物
  - `src/com/dshgo/assist/` 源码（OverlayService 等）
  - `tools/` 手写 AXML 打包工具、apksigner/d8 jar
  - `out/dsh-assist-v52.apk` 最终 APK
  - `README-使用说明.md` 安装/配置/唤起/回滚
- `build-assist-apk.sh` — 一键构建脚本（跑完会自动校验 334 项）
- `engine-bridge/` — DSH 本地插件源码（ASR 托管 +  inbox 桥 + 本地模型托管）
- `llm/nlc/` — 本地模型推理（node-llama-cpp 预编译 + router-server.mjs）
- `llm/router/` — router 脚本同步副本
- `models/Qwen3-0.6B-Q8_0.gguf` — 本地小模型（610MB）
- `docs/` — 可行性报告（系统级窗口、引擎桥接等）
- `.archive/` — 已脱敏的历史死文件备份

## 关键状态（设备侧）
- 电源键：stock `short=1/long=1/very=1`（未接管）；接管后 `short=0` + 双击唤起
- 钩子：`/data/local/tmp/dsh-powerhook.sh`（root 所有）+ 看门狗 + 开机自启 `/data/adb/service.d/`
- 本地模型：`127.0.0.1:18085`（/ask, /ask-stream, /partial, /unload）
- 识别服务：`127.0.0.1:18080`
- 语音链路：识别 → 规则筛 → 本地 0.6B（流式打字）→ 答不了转 DSH

## 安全
- 包内**不含**任何 API key / token（历史死文件已脱敏归档到 `.archive/`）
