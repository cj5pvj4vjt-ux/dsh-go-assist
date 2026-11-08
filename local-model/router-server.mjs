// 本地语音路由：千问3-0.6B 先答；只有「要操作手机」或「要实时/专业信息」才转 DSH
// 性能关键：系统提示+示例只算一次（KV 前缀缓存），每次请求只算用户那一句。
import http from 'node:http';
import { getLlama, LlamaCompletion } from 'node-llama-cpp';

const MODEL = process.env.MODEL || '/sdcard/Download/Qwen3-0.6B-Q8_0.gguf';
const PORT = Number(process.env.PORT || 18085);
const IDLE_UNLOAD_MS = 10 * 60 * 1000;

// 规则优先：命中就直接转 DSH（连模型都不加载；游戏时零开销）
const ESCALATE_RE = /打开|关闭|启动|发消息|发微信|发短信|打电话|拨号|提醒|闹钟|日程|导航|地图|路线|购买|支付|转账|下单|订票|安装|卸载|下载|搜索|搜一下|查一下|百度|天气|气温|几度|几点|时间|日期|星期|今天|明天|昨天|新闻|股价|汇率|比分|位置|定位|在哪|电量|截图|录音|播放|暂停|音量|亮度|设置|连wifi|蓝牙/i;

const SYS = '你是手机语音助手「小V」。能自己答的必须自己答：打招呼、你是谁、你能做什么、闲聊、算术、翻译、改写、给建议、出主意。'
  + '只有两类才输出 ESCALATE：① 要操作手机（打开或关闭应用、发消息、打电话、设提醒闹钟、导航、支付、装软件）；'
  + '② 要实时或专业信息（天气、时间日期、新闻、汇率比分、位置路线、需要查资料或专业深度推理的问题）。'
  + '回答用中文，一句话以内、不超过30字，不要输出思考过程。';

const FEWSHOT = [
  ['你好', '你好！有什么我能帮你的？'],
  ['在吗', '在的，你说。'],
  ['你是谁', '我是小V，你的手机语音助手。'],
  ['你能帮我干什么', '聊天、算术、翻译，也能帮你操作手机。'],
  ['一加一等于几', '等于2。'],
  ['现在几点了', 'ESCALATE'],
  ['打开微信', 'ESCALATE'],
];

// 固定前缀（系统提示 + 示例）：只算一次
function prefixText() {
  let p = `<|im_start|>system\n${SYS}<|im_end|>\n`;
  for (const [q, a] of FEWSHOT) {
    p += `<|im_start|>user\n${q}<|im_end|>\n<|im_start|>assistant\n thinking\n\n</think>\n\n${a}<|im_end|>\n`;
  }
  return p;
}

function tailText(text) {
  return `<|im_start|>user\n${text}<|im_end|>\n<|im_start|>assistant\n thinking\n\n</think>\n\n`;
}

// 会话历史（最近 6 轮），让模型在 10 分钟内不失忆
function historyText() {
  let p = '';
  for (const t of history.slice(-6)) {
    p += `<|im_start|>user\n${t.q}<|im_end|>\n<|im_start|>assistant\n thinking\n\n</think>\n\n${t.a}<|im_end|>\n`;
  }
  return p;
}

let llama = null;
let model = null;
let ctx = null;
let seq = null;
let comp = null;
let prefixTokens = 0;
let history = [];   // 会话记忆：[{q,a}]，模型卸载时清空
let lastUse = 0;
let loading = null;
// v46：流式状态（App 轮询 /partial 拿「正在生成的文字」）
let cur = { text: '', done: true, route: '', answer: '' };

function log(msg) {
  console.log(`[router] ${msg}`);
}

function disposeAll() {
  try { ctx?.dispose(); } catch (e) { /* ignore */ }
  try { model?.dispose(); } catch (e) { /* ignore */ }
  ctx = null; seq = null; comp = null; model = null; llama = null; prefixTokens = 0;
  if (history.length) log(`清空会话记忆（${history.length} 轮）`);
  history = [];
}

/** 检查模型是否已加载；没加载（或已被卸载）就重新加载，并重建前缀缓存。 */
async function ensure() {
  if (model && ctx && seq && comp) {
    lastUse = Date.now();
    return;
  }
  if (loading) {
    await loading;
    return;
  }
  loading = (async () => {
    disposeAll();
    log('加载模型…');
    llama = await getLlama({ gpu: false });
    model = await llama.loadModel({ modelPath: MODEL });
    ctx = await model.createContext({ contextSize: 1024, threads: 6, batchSize: 256 });
    seq = ctx.getSequence();
    comp = new LlamaCompletion({ contextSequence: seq });
    try {
      prefixTokens = llama.tokenize(prefixText()).length;
    } catch (e) {
      prefixTokens = 0;
    }
    // 预热：把前缀喂进 KV 缓存（这样后面每题只算用户那一句）
    await comp.generateCompletion(prefixText() + tailText('你好'), { maxTokens: 1 });
    await eraseTail();
    lastUse = Date.now();
    log(`模型就绪，前缀 ${prefixTokens} tokens 已缓存`);
  })();
  try {
    await loading;
  } finally {
    loading = null;
  }
}

async function eraseTail() {
  try {
    const used = seq && seq.tokenMeter ? seq.tokenMeter.usedInputTokens : 0;
    if (used > prefixTokens) {
      await seq.eraseContextTokenRanges([{ start: prefixTokens, end: used }]);
    }
  } catch (e) {
    log('清前缀后内容失败: ' + String(e).slice(0, 50));
  }
}

function clean(raw) {
  let txt = String(raw).split('<|im_end|>')[0].split('<|im_start|>')[0].trim();
  txt = txt.replace(/^[\s\S]*?<\/think>\s*/i, '').replace(/<｜[^｜]*｜>/g, '').trim();
  return txt;
}

async function ask(text) {
  if (ESCALATE_RE.test(text)) {
    log(`"${text.slice(0, 20)}" -> escalate (rule)`);
    return { route: 'escalate', answer: '', why: 'rule', ms: 0 };
  }
  for (let attempt = 0; attempt < 2; attempt++) {
    const t = Date.now();
    try {
      await ensure();
      const raw = await comp.generateCompletion(historyText() + tailText(text),
          {
            maxTokens: 40,
            temperature: 0.2,
            onTextChunk: (chunk) => {
              if (typeof chunk === 'string' && chunk) {
                cur.text += chunk;
              }
            }
          });
      await eraseTail();
      const txt = clean(raw);
      const ms = Date.now() - t;
      if (/ESCALATE/i.test(txt) || txt.length === 0) {
        log(`"${text.slice(0, 20)}" -> escalate (model) ${ms}ms`);
        return { route: 'escalate', answer: '', why: 'model', ms };
      }
      history.push({ q: text, a: txt });
      if (history.length > 12) history = history.slice(-12);
      log(`"${text.slice(0, 20)}" -> local "${txt.slice(0, 20)}" ${ms}ms 会话=${history.length}轮${attempt ? ' (retried)' : ''}`);
      return { route: 'local', answer: txt, ms };
    } catch (e) {
      log(`尝试 ${attempt + 1} 失败: ${String(e).slice(0, 70)}`);
      disposeAll();
      if (attempt === 1) {
        return { route: 'escalate', answer: '', why: 'infer-error', ms: Date.now() - t };
      }
      await new Promise((r) => setTimeout(r, 200));
    }
  }
  return { route: 'escalate', answer: '', why: 'infer-error', ms: 0 };
}

http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url.startsWith('/health')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, loaded: !!(model && comp), turns: history.length }));
  }
  if (req.method === 'POST' && req.url.startsWith('/unload')) {
    disposeAll();
    log('卸载模型（让出内存）');
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end('{"ok":true,"unloaded":true}');
  }
  if (req.method === 'POST' && req.url.startsWith('/reset')) {
    history = [];
    log('手动清空会话记忆');
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end('{"ok":true,"turns":0}');
  }
  if (req.method === 'GET' && req.url.startsWith('/partial')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify(cur));
  }
  if (req.method === 'POST' && req.url.startsWith('/ask-stream')) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let text = '';
      try { text = String(JSON.parse(body || '{}').text || '').trim(); } catch (e) { /* ignore */ }
      if (!text) {
        res.writeHead(400, { 'content-type': 'application/json' });
        return res.end('{"error":"empty"}');
      }
      cur = { text: '', done: false, route: '', answer: '' };
      // 后台跑，立刻返回，App 轮询 /partial 看打字机效果
      ask(text).then((r) => {
        cur.done = true;
        cur.route = r.route;
        cur.answer = r.answer || '';
        if (r.route !== 'local') { cur.text = ''; }
      }).catch((e) => {
        cur.done = true; cur.route = 'escalate'; cur.answer = '';
        log('ask-stream 失败: ' + String(e).slice(0, 60));
      });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"started":true}');
    });
    return;
  }
  if (req.method === 'POST' && req.url.startsWith('/ask')) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', async () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const text = String(parsed.text || '').trim();
        if (!text) {
          res.writeHead(400, { 'content-type': 'application/json' });
          return res.end('{"error":"empty"}');
        }
        const r = await ask(text);
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(r));
      } catch (e) {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ route: 'escalate', error: String(e).slice(0, 120) }));
      }
    });
    return;
  }
  res.writeHead(404);
  res.end('not found');
}).listen(PORT, '127.0.0.1', () => log(`listening :${PORT}`));

setInterval(() => {
  if (model && Date.now() - lastUse > IDLE_UNLOAD_MS) {
    log('闲置卸载模型');
    disposeAll();
  }
}, 60_000);
