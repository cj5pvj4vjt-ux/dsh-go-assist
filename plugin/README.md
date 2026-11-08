# engine-bridge

一个 DSH 本地插件（Cordis bundle）：把「DSH 小V」语音助手的每一句话**在已经跑着的引擎进程里**跑完，
不再为每条语音 `dsh --profile headless` 新起一个进程；同时把两个本地服务
（离线语音识别 ASR、本地小模型路由 router）也挂到引擎进程下，**重启手机后随引擎自动起来**。

- 延迟：实测**暖场 1.2–1.8 秒**（首次 2.5–3.4 秒，含创建会话），原来 9–25 秒。
- 随 App 启动的引擎自动加载，**不需要 root**，不需要单独的 worker 脚本，不需要手工 `setsid nohup`。
- 不改任何 profile 文件——安装动作由 `plugin_manager` / `dsh plugin` 完成。

## 它做三件事

1. **HTTP**：`POST http://127.0.0.1:18081/ask`，body `{"text":"...","sessionId":"可选"}`，
   返回 `{"reply":"...","sessionId":"...","ms":123,"reason":{"kind":"completed"}}`。
   `GET /health` 看状态（含 `asr` / `router` 两个子服务状态）。
2. **inbox/outbox 契约**（和 `voice/worker.sh` 完全一致，App 不用改）：

   ```
   dsh-inbox/<ts>.txt   →  dsh-outbox/<ts>.txt      (UTF-8 纯文本回复)
   dsh-inbox/reset-*.txt（或正文含「新对话/重新开始/忘掉之前/清空上下文/换个话题」）→ 开新会话
   dsh-outbox/session-info.txt                       (sid=… / reset_after=600s / workspace=…)
   消费过的文件移到 dsh-inbox/done/
   ```

3. **两个本地子服务**（都是先探活、通了就复用，不通才由引擎拉起；起不来只写日志，不影响上面两项）：

   | 子服务 | 默认地址 | 启动方式 | 说明 |
   |---|---|---|---|
   | ASR（离线识别） | `127.0.0.1:18080` | 插件自带的 `asr-server.mjs`（SenseVoice + Silero VAD） | `GET /health`→`ok`；`POST /transcribe`，头 `x-dsh-token`，body = 44 字节 WAV 头 + PCM16 LE |
   | router（本地小模型） | `127.0.0.1:18085` | `/root/llm/nlc/router-server.mjs`（`node-llama-cpp` + `Qwen3-0.6B-Q8_0.gguf`） | `GET /health`；`POST /ask {text}` → `{"route":"local"|"escalate","answer":"…"}` |

   两个子服务都是引擎的子进程，插件卸载（`ctx.effect` 清理）或引擎退出（`process.on('exit')`）时一起 kill；
   ASR 脚本还带一个父进程看门狗，引擎被强杀也不会留下占着 18080 的孤儿。

同一个 Agent（同一个 session）在引擎生命周期内复用，所以多轮上下文、工具调用都正常；
上下文就是引擎自己的 Session，会正常落盘到 `$DSH_HOME/sessions/`。

## 安装

先**停掉旧的 `voice/worker.sh`**（两个消费者会抢 inbox 文件；文件被先拿到的一方处理）。

推荐用插件管理器（需要 Full access / 一次审批）：

```
plugin_manager action=install_bundle target=/root/workspace/engine-bridge
```

它会把这个目录 link 进 profile 的 `node_modules`，并把 bundle 写进
`dsh.profile.bundles`。等价的命令行（已实测可用）：

```
dsh plugin --profile web add /root/workspace/engine-bridge
```

装完**重启引擎**（App 里重开一次，或重启 App）后生效；`GET /health` 能通、并且日志里出现
`voice bridge ready: asr=… router=… inbox=…` 即已加载。

卸载：

```
plugin_manager action=remove_bundle target=@local/engine-bridge
# 或
dsh plugin --profile web remove @local/engine-bridge
```

## 配置

行 id 是 `engine-bridge`，字段都可以在 profile 的 `cordis.patch.yml` 里覆盖（和 `cordis.patch.yml` 里的默认值同名字段）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `httpEnabled` / `httpHost` / `httpPort` | `true` / `127.0.0.1` / `18081` | 本机 `/ask` 接口 |
| `watchInbox` | `true` | 是否监听 inbox 目录 |
| `inboxDir` / `outboxDir` | `/sdcard/Android/media/com.dshgo.assist/dsh-{inbox,outbox}` | App 的目录 |
| `workspace` | `/root/voice-assistant` | 会话的工作目录（和旧 worker 一致） |
| `pollMs` | `1000` | inbox 轮询间隔（`/sdcard` 上 FUSE 的 inotify 不可靠，用轮询） |
| `asrEnabled` | `true` | 是否托管识别服务 |
| `asrHost` / `asrPort` | `127.0.0.1` / `18080` | 识别服务地址 |
| `asrToken` | `dshassist` | `/transcribe` 的 `x-dsh-token` |
| `asrScript` | 插件目录下的 `asr-server.mjs` | 换脚本时填绝对路径 |
| `asrStartTimeoutMs` | `90000` | 等 `/health` 的超时 |
| `routerEnabled` | `true` | 是否托管本地小模型路由 |
| `routerHost` / `routerPort` | `127.0.0.1` / `18085` | 路由服务地址 |
| `routerScript` | 自动找 `/root/llm/nlc/router-server.mjs`，其次 `/root/llm/router/router-server.mjs` | 脚本要和它自己的 `node_modules`（`node-llama-cpp`）在一起，不要搬走 |
| `routerCwd` | 脚本所在目录 | router 的启动 cwd |
| `routerModel` | `/sdcard/Download/Qwen3-0.6B-Q8_0.gguf` | 传给 router 的 `MODEL` |
| `routerStartTimeoutMs` | `90000` | 等 `/health` 的超时（模型是懒加载，健康检查很快） |
| `partialEnabled` | `true` | 生成过程中写 `<outboxDir>/<name>.partial.txt` 流式旁路 |
| `partialThrottleMs` | `150` | partial 写盘节流：距上次写盘不足这个毫秒数就跳过 |

## 流式 partial（打字机旁路）

回答生成过程中，插件把**当前累计的文本**持续写到 `<outboxDir>/<name>.partial.txt`
（和 inbox 文件同名 + `.partial`，UTF-8，覆盖写）：

- **turn 开始**先写一个空的 partial，清掉上一轮/上一次运行的残留；
- 生成期间每次模型吐出增量文本就更新，**距上次写盘 <150ms 的更新直接跳过**（`partialThrottleMs`），
  避免刷爆 `/sdcard`；每 10 次写盘打一行 `partial write len=… t=…ms`；
- **最终回答照旧**一次性写 `<outboxDir>/<name>.txt`（时机、原子 rename、`done/` 归档全部不变），
  写完 final 之后**删除** partial，并打一行 `partial done, final len=…`。

选择「删除」而不是留一个 done 标记：`<name>.txt` 是否存在就是「本轮结束」的既有信号，
partial 消失即表示这轮已经落定，不需要 App 侧新增解析逻辑。

数据来源是引擎的 `agent/assistant-stream`（`text-delta` 分片）+ 每个 attempt 结束时落盘的
`assistant/message` 事件（后者是权威值，会覆盖此前的增量）。所以：

- **工具轮**：模型先去调工具、期间没有文本增量，partial 会一直保持为空；等它开始输出文字才「打字」——
  这是正常现象，不是卡住。
- partial 与 final 的语义一致：都取**本回合最后一个非空 assistant 消息**的文本（多步时前一步的文本会被后一步替换）。
- HTTP `POST /ask` 不写 partial（没有文件名），仍是一次性返回。

验证方式（另一个终端，边发问题边看字节数增长）：

```
f=/sdcard/Android/media/com.dshgo.assist/dsh-outbox/<ts>   # <ts> = 你刚扔进 inbox 的文件名
echo "用 5 句话介绍一下你自己" > /sdcard/Android/media/com.dshgo.assist/dsh-inbox/$ts.txt
while [ -f "$f.partial.txt" ]; do printf '%s %s\n' "$(date +%T.%3N)" "$(wc -c < "$f.partial.txt")"; sleep 0.2; done
wc -c < "$f.txt"   # 最终整文件
```


## 隔离验证（不碰用户 profile）

```
export DSH_HOME=/tmp/ebproto-home
mkdir -p "$DSH_HOME" && cp /root/.dsh/.credentials.yaml "$DSH_HOME/"
dsh plugin --profile web add /root/workspace/engine-bridge
dsh web --patch /root/workspace/engine-bridge/tools/verify-overlay.yml \
        --host 127.0.0.1 --port 18099 --no-open &
python3 /root/workspace/engine-bridge/tools/verify.py
```

`tools/verify-overlay.yml` 只用 `--patch` 叠加，inbox/outbox 指向 `/tmp/ebproto/*`，
子服务用 18082 / 18086（不碰真机上的 18080 / 18085），插件 HTTP 用 18084；
用完把 `/tmp/ebproto-home`（里面有凭据副本）删掉。

## 已知限制

- 改了 `index.js` 需要重启引擎才会加载新代码（bundle 里 JS 不做热替换）。
- `reset-*` 只切换会话，旧 Agent 留在进程里 idle 不销毁（一次引擎生命周期内的少量常驻）。
- 会用到工具的回合仍然要 6–11 秒（模型自己决定调工具），这部分和旧方案一样，只是省掉了 7–9 秒启动。
- 插件按引擎的默认模型跑；引擎没配置默认模型时会报 `no default model is configured`。
- 两个子服务只做「探活 + 拉起 + 退出清理」，不重启：子服务中途崩了只写日志，下次引擎启动会重新拉起。

