/**
 * engine-bridge — run a voice-assistant conversation *inside* the running DSH
 * engine process instead of spawning `dsh --profile headless` for every line.
 *
 * Two front doors share one warm Agent:
 *  1. `POST http://127.0.0.1:18081/ask` with `{"text":"...","sessionId":"<optional>"}`
 *     answers `{"reply":"...","sessionId":"...","ms":123,"reason":{...}}`.
 *  2. The DSH Go inbox/outbox contract:
 *     `<inboxDir>/<ts>.txt` (UTF-8 text) -> `<outboxDir>/<ts>.txt` (UTF-8 reply),
 *     plus `<outboxDir>/session-info.txt`; consumed files move to `<inboxDir>/done/`.
 *     A file whose name starts with `reset-`, or whose text contains a reset
 *     phrase, starts a fresh conversation.
 *
 * Everything runs on `ctx.agents` / `ctx.sessions`: no child process, no CLI
 * boot, no root.
 *
 * @module engine-bridge
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { access, mkdir, readdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

/** Directory of this plugin, used to find the bundled ASR server script. */
const here = dirname(fileURLToPath(import.meta.url));

/** Stable plugin name. */
export const name = 'engine-bridge';

/** Core services: the live Agent registry and the Session store. */
export const inject = ['agents', 'sessions'];

const RESET_PHRASES = ['新对话', '重新开始', '忘掉之前', '清空上下文', '换个话题'];
const ASK_TIMEOUT_MS = 120000;

/** Plugin defaults; every field can be overridden by the row's `config`. */
const DEFAULTS = {
	httpEnabled: true,
	httpHost: '127.0.0.1',
	httpPort: 18081,
	watchInbox: true,
	inboxDir: '/sdcard/Android/media/com.dshgo.assist/dsh-inbox',
	outboxDir: '/sdcard/Android/media/com.dshgo.assist/dsh-outbox',
	workspace: '/root/voice-assistant',
	pollMs: 1000,
	sessionIdPrefix: 'session-',
	asrEnabled: true,
	asrHost: '127.0.0.1',
	asrPort: 18080,
	asrToken: 'dshassist',
	asrScript: '',
	asrStartTimeoutMs: 90000,
	/** `spawn` runs the bundled service; `bridge` reuses the engine's own speech service. */
	asrMode: 'spawn',
	asrTranscribeTimeoutMs: 120000,
	asrMaxAudioBytes: 4 * 1024 * 1024,
	routerEnabled: true,
	routerHost: '127.0.0.1',
	routerPort: 18085,
	routerScript: '',
	routerCwd: '',
	routerModel: '/sdcard/Download/Qwen3-0.6B-Q8_0.gguf',
	routerStartTimeoutMs: 90000,
	partialEnabled: false,
	partialThrottleMs: 150,
};

/** How long to wait for one `/health` probe. */
const ASR_PROBE_TIMEOUT_MS = 1200;

/**
 * Join one message's text blocks.
 * @param message - a session message, or anything else.
 * @returns the concatenated text, or '' when there is none.
 */
function assistantText(message) {
	const content = message?.content;
	if (!Array.isArray(content)) return '';
	return content
		.filter((block) => block?.type === 'text')
		.map((block) => block.text)
		.join('');
}

/**
 * Read the final assistant text of the turns committed after `firstSeq`.
 * Mirrors `@deepseek-ai/dsh-headless`'s own summary so replies match the CLI.
 * @param session - live Session whose durable events are read.
 * @param firstSeq - first sequence number owned by this ask.
 * @returns the last non-empty assistant text and the last turn-end reason.
 */
function summarize(session, firstSeq) {
	let text = '';
	let reason;
	let started = false;
	const events = typeof session.snapshotEvents === 'function'
		? session.snapshotEvents(firstSeq, session.seq)
		: undefined;
	const length = session.seq;
	const read = (seq) => (events === undefined ? session.eventAt(seq) : events[seq - firstSeq]);
	for (let seq = firstSeq; seq < length; seq++) {
		const event = read(seq);
		if (event === undefined) continue;
		if (event.type === 'turn/start') {
			started = true;
			reason = undefined;
			continue;
		}
		if (!started) continue;
		if (event.type === 'assistant/message') {
			const joined = assistantText(event.data.message);
			if (joined !== '') text = joined;
		}
		if (event.type === 'turn/end') reason = event.data.reason;
	}
	return { text, reason };
}

/** Build the user message the loop accepts; `@deepseek-ai/dsh-llm` does the same. */
function userMessage(text) {
	return {
		id: randomUUID(),
		role: 'user',
		content: [{ type: 'text', text }],
		source: { kind: 'user' },
	};
}

/**
 * Mount the bridge.
 * @param ctx - host context carrying `agents` and `sessions`.
 * @param config - optional overrides for the DEFAULTS above.
 */
export function apply(ctx, config = {}) {
	const settings = { ...DEFAULTS, ...config };
	const state = {
		agent: undefined,
		sessionId: undefined,
		queue: Promise.resolve(),
		lastAskAt: 0,
		scanning: false,
		started: new Date().toISOString(),
	};

	const now = () => Date.now();

	/** One always-visible log line: the engine captures plugin stderr in its own log. */
	function log(message) {
		const line = `[engine-bridge] ${message}`;
		console.error(line);
		try {
			ctx.logger?.info?.(line);
		} catch {
			/* logging must never break the bridge */
		}
	}

	/**
	 * Probe one local service's `/health`: any 2xx answer counts as healthy.
	 * @param host - bind host.
	 * @param port - bind port.
	 * @returns whether something healthy already answers there.
	 */
	async function probeHealth(host, port) {
		const controller = new AbortController();
		const timer = setTimeout(() => controller.abort(), ASR_PROBE_TIMEOUT_MS);
		try {
			const response = await fetch(`http://${host}:${port}/health`, { signal: controller.signal });
			return response.ok;
		} catch {
			return false;
		} finally {
			clearTimeout(timer);
		}
	}

	/** First existing path out of the candidates, or undefined. */
	async function firstExisting(paths) {
		for (const path of paths) {
			if (path === '') continue;
			try {
				await access(path);
				return path;
			} catch {
				/* try the next candidate */
			}
		}
		return undefined;
	}

	/**
	 * Supervise one local child service: reuse a healthy instance already on the
	 * port, otherwise spawn it as a child of the engine process and wait for its
	 * health endpoint. Failures are logged only — the bridge keeps working.
	 * @param spec - label, enabled flag, host/port, command, args, cwd, env, timeout.
	 * @returns the service handle with `start()` and `stop()`.
	 */
	function superviseService(spec) {
		// Copy the spec: `startServices()` may fill in args/cwd after construction.
		const service = {
			...spec,
			state: spec.enabled ? 'starting' : 'disabled',
			child: undefined,
		};
		service.stop = () => {
			const child = service.child;
			service.child = undefined;
			if (child === undefined) return;
			try {
				child.kill('SIGTERM');
			} catch {
				/* already gone */
			}
			const escalate = setTimeout(() => {
				try {
					child.kill('SIGKILL');
				} catch {
					/* already gone */
				}
			}, 3000);
			escalate.unref?.();
			child.once('exit', () => clearTimeout(escalate));
		};
		service.start = async () => {
			if (!service.enabled) {
				service.state = 'disabled';
				return;
			}
			if (await probeHealth(service.host, service.port)) {
				service.state = `reused@${service.port}`;
				return;
			}
			let child;
			try {
				child = spawn(service.command, service.args, {
					cwd: service.cwd,
					env: { ...process.env, ...service.env },
					stdio: ['ignore', 'pipe', 'pipe'],
				});
			} catch (error) {
				service.state = `failed: ${error.message}`;
				log(`${service.label} failed to spawn: ${error.message}`);
				return;
			}
			service.child = child;
			let exited = false;
			const forward = (chunk) => {
				const line = String(chunk).trim();
				if (line !== '') log(`${service.label}: ${line}`);
			};
			child.stdout?.on('data', forward);
			child.stderr?.on('data', forward);
			child.on('error', (error) => {
				service.state = `failed: ${error.message}`;
				log(`${service.label} child error: ${error.message}`);
			});
			child.on('exit', (code, signal) => {
				exited = true;
				if (service.child === child) service.child = undefined;
				if (service.state.startsWith('spawned')) {
					service.state = `exited(${signal ?? code})`;
					log(`${service.label} exited (${signal ?? code}); the rest of the bridge keeps running`);
				}
			});
			const deadline = now() + service.startTimeoutMs;
			while (now() < deadline) {
				if (exited) {
					if (!service.state.startsWith('failed')) service.state = 'failed: exited during startup';
					return;
				}
				if (await probeHealth(service.host, service.port)) {
					service.state = `spawned@${service.port}`;
					return;
				}
				await new Promise((resolve) => setTimeout(resolve, 250));
			}
			service.state = 'failed: startup timeout';
			log(`${service.label} was not healthy before the startup timeout`);
		};
		return service;
	}

	/**
	 * In `bridge` mode the identical contract is answered from the engine's speech
	 * service. When that service is missing the bundled server stays the fallback,
	 * so the voice assistant never loses ASR over this optimisation.
	 */
	const engineSpeech = ctx.get('speechToText');
	const asrBridged = Boolean(settings.asrEnabled) && settings.asrMode === 'bridge' && engineSpeech !== undefined;
	if (Boolean(settings.asrEnabled) && settings.asrMode === 'bridge' && !asrBridged) {
		log('asrMode=bridge but the engine speech service is missing; using the bundled asr-server');
	}

	/** ASR: the script ships inside this plugin and needs no extra install. */
	const asrService = superviseService({
		label: 'asr',
		enabled: Boolean(settings.asrEnabled) && !asrBridged,
		host: settings.asrHost,
		port: settings.asrPort,
		command: process.execPath,
		args: [settings.asrScript === '' ? join(here, 'asr-server.mjs') : settings.asrScript],
		env: {
			ASR_HOST: settings.asrHost,
			ASR_PORT: String(settings.asrPort),
			ASR_TOKEN: settings.asrToken,
			ASR_PARENT_PID: String(process.pid),
		},
		startTimeoutMs: settings.asrStartTimeoutMs,
	});

	/** Local small-model router: lives beside its own node_modules, so keep it there. */
	const routerService = superviseService({
		label: 'router',
		enabled: settings.routerEnabled,
		host: settings.routerHost,
		port: settings.routerPort,
		command: process.execPath,
		args: [], // resolved in startServices(): the script must sit next to node-llama-cpp
		env: { PORT: String(settings.routerPort), MODEL: settings.routerModel },
		startTimeoutMs: settings.routerStartTimeoutMs,
	});

	const services = [asrService, routerService];

	/**
	 * Run one WAV through the engine's own speech service. That service owns its
	 * worker (started on demand, released when idle), so this process keeps no model.
	 * @param wav - complete WAV bytes exactly as the voice assistant sends them.
	 * @returns the trimmed transcript text.
	 */
	async function transcribeViaEngine(wav) {
		const speech = ctx.get('speechToText');
		if (speech === undefined) throw new Error('engine speech service is unavailable');
		const spec = speech.resolve({ audio: new Uint8Array(wav) });
		const signal = AbortSignal.timeout(settings.asrTranscribeTimeoutMs);
		const transcript = await speech.transcribe(spec, signal);
		return typeof transcript?.text === 'string' ? transcript.text.trim() : '';
	}

	/**
	 * Answer the voice assistant's ASR contract from the engine's speech service:
	 * `GET /health` is tokenless, every other request carries `x-dsh-token`, and a
	 * successful POST answers `{ text, ms }` — the same shape `asr-server.mjs` used.
	 * @returns the listening HTTP server.
	 */
	function startAsrBridge() {
		let bridgeRetries = 0;
		const server = createServer((request, response) => {
			const sendText = (code, body) => {
				response.writeHead(code, { 'content-type': 'text/plain; charset=utf-8' });
				response.end(body);
			};
			if (request.method === 'GET' && request.url === '/health') {
				sendText(200, 'ok');
				return;
			}
			if (request.headers['x-dsh-token'] !== settings.asrToken) {
				sendText(403, 'forbidden');
				return;
			}
			const chunks = [];
			let size = 0;
			let overflow = false;
			request.on('data', (chunk) => {
				size += chunk.length;
				if (size > settings.asrMaxAudioBytes) {
					overflow = true;
					chunks.length = 0;
					return;
				}
				chunks.push(chunk);
			});
			request.on('end', () => {
				if (overflow) {
					sendText(413, 'audio too large');
					return;
				}
				const started = now();
				transcribeViaEngine(Buffer.concat(chunks))
					.then((text) => {
						const body = JSON.stringify({ text, ms: now() - started });
						response.writeHead(200, {
							'content-type': 'application/json; charset=utf-8',
							'content-length': Buffer.byteLength(body),
						});
						response.end(body);
					})
					.catch((error) => {
						log(`asr bridge transcribe failed: ${error?.message ?? error}`);
						sendText(500, String(error?.stack ?? error));
					});
			});
		});
		server.on('error', (error) => {
			// The previous engine's child may hold the port for a few seconds after a restart.
			if (error.code === 'EADDRINUSE' && bridgeRetries < 10) {
				bridgeRetries += 1;
				log(`asr bridge port busy, retry ${bridgeRetries}/10 in 2s`);
				setTimeout(() => server.listen(settings.asrPort, settings.asrHost), 2000).unref?.();
				return;
			}
			log(`asr bridge failed: ${error.message}`);
		});
		server.listen(settings.asrPort, settings.asrHost, () => {
			log(`asr bridge on http://${settings.asrHost}:${settings.asrPort} (engine speech service)`);
		});
		return server;
	}

	/** Resolve the router script/cwd, then start every supervised service. */
	async function startServices() {
		if (settings.routerEnabled) {
			const script = settings.routerScript === ''
				? await firstExisting(['/root/llm/nlc/router-server.mjs', '/root/llm/router/router-server.mjs'])
				: await firstExisting([settings.routerScript]);
			if (script === undefined) {
				routerService.state = 'failed: router-server.mjs not found';
				log('router script not found; set `routerScript` in the plugin config');
			} else {
				routerService.args = [script];
				routerService.cwd = settings.routerCwd === '' ? dirname(script) : settings.routerCwd;
			}
		}
		await Promise.all(services.map((service) => service.start()));
		log(`voice bridge ready: asr=${asrBridged ? 'bridged@' + settings.asrPort : asrService.state} router=${routerService.state} `
			+ `inbox=${settings.watchInbox ? settings.inboxDir : 'off'}`);
	}

	// Kill supervised children when the engine process dies without an orderly dispose.
	const onProcessExit = () => {
		for (const service of services) {
			const child = service.child;
			if (child === undefined) continue;
			try {
				child.kill('SIGKILL');
			} catch {
				/* already gone */
			}
		}
	};
	process.on('exit', onProcessExit);

	/** Resolve provider/model plus the profile's Agent-preset composition. */
	async function composition() {
		const defaults = ctx.get('agentDefaultModel');
		const selection = defaults?.currentSelection?.() ?? {};
		const agentOptions = {
			provider: selection.provider,
			model: selection.model,
			...(selection.reasoningEffort === undefined ? {} : { reasoningEffort: selection.reasoningEffort }),
		};
		const presets = ctx.get('agentPresets');
		let agentPreset;
		let setup;
		if (presets === undefined) {
			setup = () => {};
		} else {
			const resolved = await presets.resolve();
			agentPreset = resolved.id;
			setup = async (agentCtx) => {
				await presets.mount(agentCtx, resolved.id);
			};
		}
		return { agentOptions, agentPreset, setup };
	}

	/** Create the one long-lived Agent for this engine process. */
	async function ensureAgent() {
		if (state.sessionId !== undefined) {
			const live = ctx.agents.get(state.sessionId);
			if (live !== undefined) {
				state.agent = live;
				return live;
			}
		}
		const { agentOptions, agentPreset, setup } = await composition();
		if (agentOptions.provider === undefined || agentOptions.model === undefined) {
			throw new Error('engine-bridge: no default model is configured (ctx.agentDefaultModel is empty)');
		}
		await mkdir(settings.workspace, { recursive: true });
		const sessionId = `${settings.sessionIdPrefix}${randomUUID()}`;
		const { agent } = await ctx.agents.create({
			sessionId,
			agentOptions,
			meta: {
				cwd: settings.workspace,
				...(agentPreset === undefined ? {} : { agentPreset }),
			},
			setup,
		});
		await agent.whenIdle();
		state.agent = agent;
		state.sessionId = agent.id;
		return agent;
	}

	/**
	 * Run one turn on the warm Agent and return its final assistant text.
	 * @param text - user text.
	 * @param sessionId - optional live Session to run the turn on.
	 * @param hooks - optional `onPartial(text)`, called with the growing answer.
	 * @returns the final reply, the turn-end reason, and the Session id.
	 */
	async function ask(text, sessionId, hooks = {}) {
		if (sessionId !== undefined && sessionId !== '' && sessionId !== state.sessionId) {
			const live = ctx.agents.get(sessionId);
			if (live === undefined) throw new Error(`engine-bridge: session "${sessionId}" is not live in this process`);
			state.agent = live;
			state.sessionId = live.id;
		}
		const agent = await ensureAgent();
		const firstSeq = agent.session.seq;
		const onPartial = typeof hooks?.onPartial === 'function' ? hooks.onPartial : undefined;
		const stops = [];
		const emitPartial = (value) => {
			try {
				onPartial(value);
			} catch {
				/* a partial-write failure must never disturb the turn */
			}
		};
		if (onPartial !== undefined) {
			// Live token stream. The committed `assistant/message` only lands when an
			// attempt finishes, so the deltas are what makes the typing visible.
			let attemptText = '';
			stops.push(ctx.on('agent/assistant-stream', ({ agent: subject, frame }) => {
				if (subject !== agent || frame === undefined) return;
				if (frame.type === 'start') {
					attemptText = '';
					return;
				}
				if (frame.type !== 'chunk') return;
				const chunk = frame.chunk;
				if (chunk?.type === 'text-delta' && chunk.text !== '' && chunk.text !== undefined) {
					attemptText += chunk.text;
					emitPartial(attemptText);
				}
			}));
			// The committed message is authoritative: it supersedes the streamed deltas.
			stops.push(ctx.on('session/event', (session, event) => {
				if (session !== agent.session || event.seq < firstSeq) return;
				if (event.type !== 'assistant/message') return;
				const joined = assistantText(event.data.message);
				if (joined === '') return;
				attemptText = '';
				emitPartial(joined);
			}));
		}
		agent.followup(userMessage(text));
		const timer = setTimeout(() => agent.cancel({ kind: 'user' }), ASK_TIMEOUT_MS);
		try {
			await agent.whenIdle();
		} finally {
			clearTimeout(timer);
			for (const stop of stops) {
				try {
					stop?.();
				} catch {
					/* teardown is best effort */
				}
			}
		}
		const outcome = summarize(agent.session, firstSeq);
		if (outcome.text === '' && outcome.reason?.kind === 'error') {
			const error = outcome.reason.error;
			throw new Error(`${error?.code ?? 'error'}: ${error?.message ?? 'turn failed'}`);
		}
		return { reply: outcome.text, reason: outcome.reason, sessionId: agent.id };
	}

	/** Serialize asks so concurrent requests share one orderly conversation. */
	function enqueue(operation) {
		const run = state.queue.then(operation, operation);
		state.queue = run.then(() => undefined, () => undefined);
		return run;
	}

	function wantsReset(text) {
		return RESET_PHRASES.some((phrase) => text.includes(phrase));
	}

	/** Start a fresh conversation; the previous Agent stays live but idle. */
	function reset() {
		if (state.agent !== undefined && state.agent.status === 'running') {
			state.agent.cancel({ kind: 'user' });
		}
		state.agent = undefined;
		state.sessionId = undefined;
	}

	async function publishSessionInfo() {
		const body = `sid=${state.sessionId ?? ''}\nreset_after=600s\nworkspace=${settings.workspace}\n`;
		await writeFile(join(settings.outboxDir, 'session-info.txt'), body, 'utf8').catch(() => {});
	}

	/**
	 * Throttled writer for `<outboxDir>/<name>.partial.txt`, a side channel that
	 * lets a reader watch one answer grow. It never throws and never blocks the
	 * turn; the final `<name>.txt` stays the authoritative artifact.
	 * @param name - the inbox file's base name.
	 * @returns `begin()` (clear leftovers), `onText(text)`, `finish(finalLength)`.
	 */
	function disabledCreatePartialWriter(name) {
		const path = join(settings.outboxDir, `${name}.partial.txt`);
		const started = now();
		let lastWriteAt = 0;
		let writes = 0;
		let finished = false;
		return {
			begin() {
				lastWriteAt = 0;
				writes = 0;
				finished = false;
				writeFile(path, '', 'utf8').catch(() => {});
			},
			onText(text) {
				if (finished) return;
				const at = now();
				if (at - lastWriteAt < settings.partialThrottleMs) return;
				lastWriteAt = at;
				writes += 1;
				if (writes === 1 || writes % 10 === 0) log(`partial write len=${text.length} t=${at - started}ms`);
				writeFile(path, text, 'utf8').catch(() => {});
			},
			async finish(finalLength) {
				finished = true;
				await unlink(path).catch(() => {});
				log(`partial done, final len=${finalLength}`);
			},
		};
	}

	/** Serve the inbox contract: text file in, reply file out, consumed file moved. */
	async function handleInboxFile(dir, fileName) {
		const name = basename(fileName, '.txt');
		const source = join(dir, fileName);
		const claimed = join(dir, 'done', fileName);
		const started = now();
		let text;
		try {
			// Claim the file first: an atomic rename keeps a slow answer from being
			// picked up twice by a later scan (or by the old worker script).
			text = await readFile(source, 'utf8');
			await rename(source, claimed);
		} catch {
			return;
		}
		text = text.trim();
		const partial = settings.partialEnabled ? disabledCreatePartialWriter(name) : undefined;
		let reply;
		try {
			if (name.startsWith('reset-')) {
				reset();
				await publishSessionInfo();
				reply = '已开始新对话';
			} else if (text === '') {
				reply = '(DSH 收到的内容是空的)';
			} else {
				if (wantsReset(text)) reset();
				partial?.begin();
				const result = await enqueue(() => ask(text, undefined, {
					onPartial: partial === undefined ? undefined : (value) => partial.onText(value),
				}));
				reply = result.reply === '' ? '(DSH 无输出)' : result.reply;
				state.lastAskAt = now();
				await publishSessionInfo();
			}
		} catch (error) {
			reply = `DSH 这次没能回答。原因：${error instanceof Error ? error.message : String(error)}`;
		}
		await writeFile(join(settings.outboxDir, `${name}.txt`), reply, 'utf8').catch(() => {});
		if (partial !== undefined) await partial.finish(reply.length);
		log(`inbox ${name} answered in ${now() - started}ms`);
	}

	async function scanInbox() {
		if (state.scanning) return;
		state.scanning = true;
		try {
			const dir = settings.inboxDir;
			let entries;
			try {
				entries = await readdir(dir, { withFileTypes: true });
			} catch {
				return;
			}
			const files = entries
				.filter((entry) => entry.isFile() && entry.name.endsWith('.txt'))
				.map((entry) => entry.name)
				.sort();
			for (const fileName of files) {
				await handleInboxFile(dir, fileName);
			}
		} finally {
			state.scanning = false;
		}
	}

	const cleanups = [];
	ctx.effect(() => {
		if (settings.watchInbox) {
			mkdir(join(settings.inboxDir, 'done'), { recursive: true }).catch(() => {});
			mkdir(settings.outboxDir, { recursive: true }).catch(() => {});
			const timer = setInterval(() => {
				scanInbox().catch(() => {});
			}, Math.max(200, settings.pollMs));
			cleanups.push(() => clearInterval(timer));
		}
		if (settings.httpEnabled) {
			const server = createServer((request, response) => {
				const url = new URL(request.url ?? '/', `http://${settings.httpHost}`);
				const send = (code, payload) => {
					const body = JSON.stringify(payload);
					response.writeHead(code, {
						'content-type': 'application/json; charset=utf-8',
						'content-length': Buffer.byteLength(body),
					});
					response.end(body);
				};
				if (request.method === 'GET' && url.pathname === '/health') {
					send(200, {
						ok: true,
						sessionId: state.sessionId ?? null,
						status: state.agent?.status ?? 'unstarted',
						startedAt: state.started,
						lastAskAt: state.lastAskAt || null,
						asr: asrService.state,
						router: routerService.state,
					});
					return;
				}
				if (request.method !== 'POST' || url.pathname !== '/ask') {
					send(404, { error: 'not found' });
					return;
				}
				const chunks = [];
				request.on('data', (chunk) => chunks.push(chunk));
				request.on('end', () => {
					let payload;
					try {
						payload = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
					} catch {
						send(400, { error: 'invalid JSON body' });
						return;
					}
					const text = typeof payload.text === 'string' ? payload.text : '';
					if (text.trim() === '') {
						send(400, { error: 'text is required' });
						return;
					}
					const started = now();
					enqueue(() => ask(text, typeof payload.sessionId === 'string' ? payload.sessionId : undefined))
						.then((result) => {
							state.lastAskAt = now();
							send(200, { ...result, ms: now() - started });
						})
						.catch((error) => {
							send(500, {
								error: error instanceof Error ? error.message : String(error),
								ms: now() - started,
							});
						});
				});
			});
			server.on('error', (error) => {
				ctx.logger?.error?.('engine-bridge: HTTP server failed: %s', error.message);
			});
			server.listen(settings.httpPort, settings.httpHost, () => {
				log(`http on http://${settings.httpHost}:${settings.httpPort}/ask`);
			});
			cleanups.push(() => server.close());
		}
		if (asrBridged) {
			const server = startAsrBridge();
			cleanups.push(() => server.close());
		}
		// ASR and the local router are best effort: a failure only shows in the log.
		startServices().catch((error) => {
			log(`service startup failed: ${error?.message ?? error}`);
		});
		cleanups.push(() => {
			for (const service of services) service.stop();
			process.off('exit', onProcessExit);
		});
		return () => {
			for (const cleanup of cleanups.splice(0).reverse()) {
				try {
					cleanup();
				} catch {
					/* teardown is best effort */
				}
			}
		};
	}, 'engine-bridge.resources()');

	log(`mounted (plugin ${here})`);
}
