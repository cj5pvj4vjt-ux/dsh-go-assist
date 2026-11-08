# DSH 小V 引擎内桥接（engine-bridge）可行性结论

**结论：可行，已在真机（Android 16 + proot Ubuntu + 正在运行的 `dsh web` 引擎）验证通过。**
一个本地 Cordis 插件可以在**引擎进程内**直接拿到 `ctx.agents` / `ctx.sessions`，用引擎自己的
Agent 跑一轮对话并取回最终回复；同时监听 `/sdcard/.../dsh-inbox/*.txt` 写回 `dsh-outbox/<ts>.txt`。
实测延迟 **暖场 1.2–1.8 秒**（首问 2.5–3.4 秒，含建会话），原来 `dsh --profile headless` 是 **9–25 秒**；
随 App 启动的引擎自动加载，**不需要 root**，也不需要旧的 worker 脚本。

原型：`/root/workspace/engine-bridge/`（插件目录 + 安装说明 + 隔离验证脚本）。

---

## 1. 延迟实测（真机，aiping.cn / DeepSeek-V4.1-Flash，同一台手机、同一引擎）

| 场景 | 旧方案（`worker.sh` + `dsh --profile headless`） | 插件（引擎内） |
|---|---|---|
| 每次启动引擎的开销 | 7–9 s/次 | **0**（复用已跑的引擎） |
| 简单问答（暖会话） | ~9–17 s | **1.03 / 1.16 / 1.42 / 1.48 / 1.59 / 1.69 / 1.73 / 1.83 s** |
| 首次问答（含建会话 + 预热） | ~9–17 s | **2.52 / 3.36 / 3.59 s** |
| 模型决定调工具的回合 | ~15–25 s | **6.4 / 10.5 / 11.2 s**（省掉启动的 7–9 s） |
| inbox→outbox 端到端（含 0.5–1 s 轮询） | 9–25 s | **1.5–3.4 s** |

原始记录（`tools/verify.py` 与手工 `/ask`、inbox 文件测试）：

```
/ask 冷启动  2.52s reply='2'    (ms=2495)
/ask 暖      1.42s reply='2'    (ms=1411)
/ask 暖      1.16s reply='2'    (ms=1150)
inbox 文件   3.36s→'2'  1.59s→'4'  1.48s→'6'  1.69s→'你好！有什么我可以帮你的吗？'  0.10s→空输入
上下文连续   1.83s→'好的'  1.73s→'小明'（同一 session 记住了名字）  6.40s→时间问题（用了工具/时间上下文）
首轮实测     3.59s / 11.19s（工具）/ 1.03s
```

冷/暖差 ≈ 1.2–1.3 s，就是「建 Agent + 组装 system prompt + 装 tools/preset」的成本；之后每轮只剩模型推理。

## 2. 凭什么可行：引擎里已有的编程接口（问题 2 的答案）

本机是安装版实现（`/opt/dsh/node_modules/@deepseek-ai/*`，含 `lib/*.js` 源码 + JSDoc），关键三处：

1. **Agent 就是可编程的**：`dsh-agent-loop` 的 `Agent` 有
   `followup(message)`（`lib/index.js:600`）、`whenIdle()`（`:674`）、`cancel()`（`:645`），
   状态 `agent.status`（`idle`/`running`）。`ctx.agents`（`dsh-agent`）提供
   `create(options)` / `resume(options)` / `get(sessionId)` / `list()`。
2. **回复文本来自 Session 事件**：一轮结束后，`assistant/message` 事件带最终文本，`turn/end` 带 `reason`。
   `dsh-headless` 自己就是这么取结果的（`dsh-headless/lib/index.js` 的 `summarize()`），插件照抄即可
   （`session.snapshotEvents(fromSeq, seq)`）。
3. **组合方式（最小可运行序列，实测有效）**：

   ```js
   const { agent } = await ctx.agents.create({ sessionId, agentOptions, meta: { cwd, agentPreset }, setup });
   await agent.whenIdle();
   const firstSeq = agent.session.seq;
   agent.followup({ id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } });
   await agent.whenIdle();
   // 读 firstSeq..session.seq 里的 assistant/message = 最终回复
   ```

   之后 `ctx.agents.get(sessionId)` 拿到同一个 Agent 继续下一轮 → 这就是「暖」路径；
   同进程内**没有任何 CLI/子进程**。

没有现成的「发一条消息并等回复」单一 API（问题 2 的第三问：**没有**），但上面 3 个调用就够了；
`ctx.llm` 只是模型适配层（自己拼 prompt 反而丢掉了 system prompt/tools/审批），不用它。

**插件能拿到的服务**（`ctx.get(...)`，本机 `web` profile 实有）：
`agents`、`agentLoop`、`sessions`、`sessionPersistence`、`sessionQuery`、`sessionProjections`、
`agentDefaultModel`、`agentPresets`、`systemPrompt`、`tools`、`llm`、`approval`、`subagents`、
`webServer`（可把路由挂在 3080 上，但要走 token/信任围栏）、`timer` 等。

原型为了不干扰用户：HTTP 用独立的 `node:http` 监听 `127.0.0.1:18081`（只用 `ctx.agents/sessions`），
inbox 用 1 s 轮询（`/sdcard` 是 FUSE，inotify 不可靠）。

## 3. 本地插件怎么装、怎么启用（问题 2 的答案）

- **bundle 形态**：目录里有 `package.json`（`dsh.bundle.patch: ./cordis.patch.yml`）+
  `cordis.patch.yml`（`insert` 一行 `id: engine-bridge, name: '@local/engine-bridge'`）。
- **安装**：`plugin_manager action=install_bundle target=/root/workspace/engine-bridge`（需 Full access/审批）；
  等等价命令行 **已实测**：`dsh plugin --profile web add /root/workspace/engine-bridge`
  —— pnpm 把目录 link 进 profile 的 `node_modules`，并把 `@local/engine-bridge` 写进
  `package.json` 的 `dependencies` **和** `dsh.profile.bundles`，下次启动引擎自动加载。
- **加载器支持绝对路径**：验证期我用 `--patch` overlay（不写任何 profile 文件）插入
  `name: /root/workspace/engine-bridge/index.js`，加载器把它解析成 `file:///...`；
  App 自己的 `/etc/dsh-app/dsh-web.patch.yml` 就是用绝对路径加载 `/opt/dsh-go/dsh-go-bridge/lib/index.js`。
- **不需要动** `$DSH_HOME/plugins`（那个目录不存在）；启用=出现在 `dsh.profile.bundles`，禁用/卸载用
  `plugin_manager remove_bundle` 或 `dsh plugin --profile web remove @local/engine-bridge`。
- 改 `index.js` 后要**重启引擎**才加载新代码（bundle 的 JS 不热替换）。

## 4. 原型与验证过程（含「没写坏用户环境」的证据）

验证用完全隔离的临时环境，全程 **没有碰用户的 `web` profile、没有重启/杀掉用户正在用的引擎**：

1. `DSH_HOME=/tmp/ebproto-home`（临时 home，自动生成一份全新 web profile；只复制了 `.credentials.yaml` 以便调模型）。
2. 启动：`dsh web --patch tools/verify-overlay.yml --host 127.0.0.1 --port 18099 --no-open`
   —— 独立端口 18099 + 独立 `--patch` 叠加；overlay 把 inbox/outbox 指到 `/tmp/ebproto/*`。
3. 验证项：`GET /health`；`POST /ask`（冷/暖/上下文连续）；inbox 文件→outbox 回复→移到 `done/`；
   `reset-*` 开新会话；空文件；**以包名安装**（`dsh plugin add` 后不靠 overlay 的 `insert`，只用 bundle patch）再跑一遍。
4. 过程中修掉两个真实缺陷：扫描重入导致同一文件被回答多次（改成先 `rename` 认领 + 扫描互斥）、
   空文件被当成提问。
5. 收尾清理：删掉 `/tmp/ebproto-home`（含凭据副本）与 `/tmp/ebproto`，停掉全部后台引擎。
   用户引擎（pid 7020）始终在跑；`/root/.dsh/profiles/web` 下 `cordis.patch.yml`、`package.json` 等
   时间戳未变（只有 CLI 重新生成的 `cordis.yml` 脚手架，与全新模板逐字节相同）。

## 5. 风险与注意

- **引擎不在就没桥**：插件靠 App 启动的引擎活着；引擎崩溃/未启动时 App 看不到回复（旧 worker 反而更独立）。
  装完建议**停掉 `voice/worker.sh`**，否则两个消费者抢 inbox 文件。
- `/ask` 没有鉴权，只绑 `127.0.0.1`；本机其他进程可调用（可加 `x-token` 头，和 ASR 服务同样的做法）。
- 每轮都会走引擎的完整 system prompt/tools（和 headless 一致），所以模型自选工具时仍要 6–11 s。
- `reset-*` 只切换会话，旧 Agent 在进程里 idle 常驻（一次引擎生命周期内少量对象）。
- 插件用引擎的默认模型；引擎没配默认模型时会返回 `no default model is configured`。
- 装到这个 profile 对**所有会话生效**（和所有 bundle 一样）。

## 6. 如果当初不可行的次优方案（备查）

- **复用已跑引擎的 RPC**：3080 带 token 的 `/api` 已经能把用户消息送进某个 session 并流式取回复；
  写个本机小脚本调它也能省掉 7–9 s 启动，代价是要处理 token/信任围栏与流式事件解析，且仍是一个外部进程。
- **压 boot**：`dsh --profile headless` 的 7–9 s ≈ node 启动 + 模块图加载 + profile 组装；
  可裁 bundle、用 `--import` 预热快照（App 已经在用 `/opt/dsh-go/boot-cache.mjs`）、复用 `--session-id`
  免冷启动，但实践上很难压到 2 s 以下——而插件把这段开销直接变成 0。

---

## 7. 追加：ASR 与本地小模型路由也由插件托管

同一套 `superviseService()` 现在托管两个本地子服务，规则一致：**先 `GET /health` 探活，
通了就复用、不重复起；不通就 `spawn` 成引擎的子进程；起不来只写日志，inbox 桥照常工作；
插件卸载（`ctx.effect` 清理）或引擎退出（`process.on('exit')`）时一起 kill。**

| 子服务 | 地址 | 脚本 | 备注 |
|---|---|---|---|
| ASR | `127.0.0.1:18080` | 插件自带 `asr-server.mjs`（SenseVoice + Silero VAD） | 另带父进程看门狗，引擎被强杀也不留孤儿 |
| router | `127.0.0.1:18085` | `/root/llm/nlc/router-server.mjs`（node-llama-cpp + Qwen3-0.6B-Q8_0） | 必须和它自己的 `node_modules` 同目录，所以不搬进插件目录；脚本/cwd/端口/模型都可配 |

实测（隔离环境：临时 `DSH_HOME` + `--patch` + 端口 18099/18084/18082/18086，未动用户 profile）：

```
[engine-bridge] voice bridge ready: asr=reused@18080 router=spawned@18086 inbox=/tmp/ebproto/inbox
plugin /health → {"asr":"reused@18080","router":"spawned@18086", ...}
router POST /ask {"text":"打开微信"} → {"route":"escalate","answer":"","why":"rule"}  0.044s（规则命中，不加载模型）
ASR POST /transcribe（复用生产实例，last-voice.wav）→ {"text":"你好，你是谁？能回答我吗？","ms":460}
inbox→outbox 2.8s（临时引擎冷会话）
再启动一次（指向生产端口）→ asr=reused@18080 router=reused@18085，且没有新增子进程
杀掉临时引擎 → 它的 router 子进程（18086）随之消失；生产的 18080/18085 子服务不受影响
```

生产侧证据（App 诊断里的引擎日志）：

```
[engine-bridge] asr: ASR_READY 127.0.0.1:18080
[engine-bridge] voice bridge ready: asr=spawned@18080 inbox=/sdcard/Android/media/com.dshgo.assist/dsh-inbox
```

两个注意点：

- **要找对 router 脚本**：`/root/llm/nlc/router-server.mjs` 才是能跑的那份（旁边有
  `node-llama-cpp` 的 `node_modules`，而且修掉了「上下文越滚越慢」的版本）；
  `/root/llm/router/router-server.mjs` 是更早的副本、没有 `node_modules`，直接 spawn 会
  因 `ERR_MODULE_NOT_FOUND` 立刻退出。插件默认按 `nlc` → `router` 的顺序取存在的那个，可用
  `routerScript` 覆盖。
- 过程中修掉一个真 bug：`superviseService()` 原来把启动参数读自传入的 spec 对象，而
  `startServices()` 是在返回的 service 对象上补 `args`/`cwd`，导致 router 被以「无参数 node」
  启动（秒退、只落到 `failed: exited during startup`）。改成成员全部复制后再启动。

---

## 8. 追加：流式 partial（打字机旁路）

**结论：真流式，已实测。** 生成过程中插件把「当前累计文本」持续写到
`<outboxDir>/<name>.partial.txt`（同名 + `.partial`，覆盖写，150ms 节流），
最终仍是一次性写 `<outbox>/<name>.txt`，写完 final 就删掉 partial。

数据来源有两路，缺一不可：

- **`agent/assistant-stream` 的 `text-delta` 分片**——真正的逐字流。实测 `assistant/message`
  会话事件**不是**「随生成不断增长的」，它只在每个 attempt 结束时落盘一次（Event 契约：
  “Chunks are transient; the loop appends one final v2 `assistant/message`…”）；所以只订阅
  session 事件的话，一次回答只会跳一两下，看不到打字机。
- **`assistant/message` 事件**作为权威值：attempt 结束/多步时用它的完整文本覆盖已累计的增量。

实测采样（隔离环境：临时 `DSH_HOME` + `--patch`，端口 18099/18084，`asrEnabled=false`
`routerEnabled=false`，`partialThrottleMs=150`；未动生产 profile 与 App）：

问题「写一段 500 字左右的杭州西湖介绍，直接开始写正文」→ partial 字节数随时间单调增长：

```
t=0.31s bytes=0      (turn 开始先写空 partial，清残留)
t=2.53s bytes=6      t=3.55s bytes=380    t=5.41s bytes=970    t=7.55s bytes=1827   t=9.20s bytes=2831
t=2.80s bytes=147    t=3.92s bytes=488    t=5.68s bytes=1086   t=7.71s bytes=1890   t=9.51s bytes=2945
t=2.96s bytes=195    t=4.29s bytes=619    t=6.00s bytes=1238   t=7.87s bytes=1990   t=9.94s bytes=3133
t=3.18s bytes=229    t=4.67s bytes=745    t=6.71s bytes=1464   t=8.19s bytes=2187   t=10.31s bytes=3610
t=3.39s bytes=323    t=5.20s bytes=922    t=7.13s bytes=1656   t=8.82s bytes=2559   t=10.78s bytes=3877
（43 个不同字节数，200ms 采样；最后一个 partial 3877B → final 4015B，partial 随即删除）
```

引擎日志同步：

```
[engine-bridge] partial write len=2 t=2220ms
[engine-bridge] partial write len=209 t=3975ms
... (每 10 次一条，另有第一次) ...
[engine-bridge] partial done, final len=1491
[engine-bridge] inbox 1750000010012 answered in 10647ms
```

边界（已写进 README）：

- **工具轮**：模型先调工具、期间没有文本增量，partial 一直是空的，等出字才开始「打字」。
  实测一次会用到工具的提问：partial 全程 0 字节（1 个采样点），最后该轮在 120s 上限被取消，
  final 落成 `(DSH 无输出)`——这是既有的超时行为，与 partial 无关，只是现在「没字」这件事
  变得更可见了。
- **App 侧零干扰（已核对源码）**：`OverlayService.startReplyPoll()` 只轮询
  `new File(OUTBOX_DIR, taskId + ".txt")` 这个**精确文件名**，不做 `*.txt` 通配，
  所以 `<taskId>.partial.txt` 不会被 App 当成回复，原有链路完全不变。


---

**产物**：`/root/workspace/engine-bridge/`（`index.js` / `package.json` / `cordis.patch.yml` /
`asr-server.mjs` / `locale/{zh,en}.json` / `icon.svg` / `README.md` / `tools/verify.py` / `tools/verify-overlay.yml`）。
