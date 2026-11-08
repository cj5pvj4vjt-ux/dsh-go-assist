/**
 * Local offline ASR service: sherpa-onnx + SenseVoiceSmall, HTTP on 127.0.0.1.
 *
 * This is the copy that ships inside the engine-bridge plugin: the plugin spawns
 * it as a child of the engine process, so it comes back with every engine start
 * and never needs root.
 *
 * Contract (unchanged from the hand-started original):
 *   GET  /health           -> "ok"            (no token required)
 *   POST /transcribe       -> {"text":"…","ms":123}
 *        header x-dsh-token: <ASR_TOKEN>
 *        body   = 44-byte WAV header + PCM16 LE mono 16 kHz
 *
 * Environment:
 *   ASR_HOST         bind host, default 127.0.0.1
 *   ASR_PORT         bind port, default 18080
 *   ASR_TOKEN        shared token, default dshassist
 *   ASR_PARENT_PID   when set, exit once that process is gone (engine restart / crash)
 */

import { createRequire } from 'node:module'
import http from 'node:http'

const require = createRequire(import.meta.url)
const sherpa = require('/opt/dsh/node_modules/sherpa-onnx-node')
const R = '/root/.dsh/speech-to-text/sensevoice/models'
const MODEL = R + '/sensevoice-onnx/model.int8.onnx'
const TOKENS = R + '/sensevoice-onnx/tokens.txt'
const VAD = R + '/silero/silero_vad.onnx'
const TOKEN = process.env.ASR_TOKEN || 'dshassist'
const PORT = Number(process.env.ASR_PORT || 18080)
const HOST = process.env.ASR_HOST || '127.0.0.1'

// Leave with the engine that spawned us: a restart must not leave an orphan holding the port.
const parentPid = Number(process.env.ASR_PARENT_PID || 0)
if (parentPid > 0) {
  const watchdog = setInterval(() => {
    try {
      process.kill(parentPid, 0)
    } catch {
      process.exit(0)
    }
  }, 5000)
  watchdog.unref()
}

const nativeConfig = {
  featConfig: { sampleRate: 16000, featureDim: 80 },
  modelConfig: {
    senseVoice: { model: MODEL, language: 'auto', useInverseTextNormalization: 1 },
    tokens: TOKENS, numThreads: 2, provider: 'cpu', debug: 0
  }
}
const recognizer = new sherpa.OfflineRecognizer(nativeConfig)
const detector = new sherpa.Vad({
  sileroVad: { model: VAD, threshold: 0.5, minSilenceDuration: 0.4, minSpeechDuration: 0.25, maxSpeechDuration: 20, windowSize: 512 },
  sampleRate: 16000, numThreads: 1, provider: 'cpu', debug: 0
}, 25)

function transcribe(wav) {
  const pcm = new DataView(wav.buffer, wav.byteOffset + 44, wav.byteLength - 44)
  const samples = Float32Array.from({ length: Math.floor(pcm.byteLength / 2) }, (_, i) => pcm.getInt16(i * 2, true) / 32768)
  detector.reset()
  const texts = []
  const drain = () => {
    while (!detector.isEmpty()) {
      const seg = detector.front(false)
      const stream = recognizer.createStream()
      stream.acceptWaveform({ sampleRate: 16000, samples: seg.samples })
      recognizer.decode(stream)
      texts.push(recognizer.getResult(stream).text.trim())
      detector.pop()
    }
  }
  for (let o = 0; o < samples.length; o += 512) { detector.acceptWaveform(samples.subarray(o, o + 512)); drain() }
  detector.flush(); drain()
  return texts.filter(Boolean).join(' ').trim()
}

http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.end('ok'); return }
  if (req.headers['x-dsh-token'] !== TOKEN) { res.writeHead(403); res.end('forbidden'); return }
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    try {
      const t0 = Date.now()
      const text = transcribe(Buffer.concat(chunks))
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ text, ms: Date.now() - t0 }))
    } catch (e) {
      res.writeHead(500); res.end(String(e && e.stack || e))
    }
  })
}).listen(PORT, HOST, () => console.error(`ASR_READY ${HOST}:${PORT}`))
