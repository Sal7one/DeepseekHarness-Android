// @dsh-android/plugin-guard — policy floor for DSH-Android (ENGINE-CONTRACT §4):
//  1. Hardline command floor for shell tools: blocked before approval and not
//     overridable (a prepended `tools/pre-execute` deny plus a monotonic
//     `ctx.tools.guard` backstop that no listener can bypass).
//  2. Approval for mutating built-in tools (bash, file writes): Android runs
//     without an OS sandbox, so these `ask` on every call.
//  3. Token / cost budgets from the bridge's `budget/update`, metered from the
//     durable usage that dsh records on each assistant settlement (the same
//     source dsh-token-meter folds); a turn over budget is refused at
//     `agent/pre-step`.
//  4. A system-prompt section describing the phone context.
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { checkCommand, refusalText } from './hardline.js'

export { checkCommand, refusalText } from './hardline.js'

export const name = 'android-guard'
export const inject = ['tools']

/** Shell-like tools whose `command` (or `text`) argument is checked. */
const SHELL_TOOLS = new Map([
  ['bash', 'command'], ['pwsh', 'command'], ['terminal_send', 'text'], ['terminal_open', 'command'],
])

/** Estimated CNY per 1M tokens; override with the `prices` config. */
const DEFAULT_PRICES = { default: { input: 2, cacheRead: 0.2, output: 3 } }

// SDK mode reserves stdout for JSON-RPC; plugin diagnostics go to stderr (the app logs it).
function log(_ctx, level, message) {
  process.stderr.write(`[android-guard] ${level === 'info' ? '' : level + ': '}${message}\n`)
}

function localDay(date = new Date()) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** The id the app knows for a (possibly subagent) session: its root. */
function rootSessionId(ctx, agent) {
  let session = agent?.session
  if (session === undefined) return undefined
  const seen = new Set()
  while (session.header?.parentSession !== undefined && !seen.has(String(session.id))) {
    seen.add(String(session.id))
    const parentId = String(session.header.parentSession)
    const parent = ctx.get('agents')?.list?.().find(a => String(a.session.id) === parentId)
    if (parent === undefined) return parentId
    session = parent.session
  }
  return String(session.id)
}

function rootOfSession(ctx, session) {
  const agent = ctx.get('agents')?.list?.().find(a => a.session === session || String(a.session.id) === String(session.id))
  if (agent !== undefined) return rootSessionId(ctx, agent)
  return session.header?.parentSession !== undefined ? String(session.header.parentSession) : String(session.id)
}

function usageOf(event) {
  if (event?.type !== 'assistant/message' && event?.type !== 'assistant/attempt') return undefined
  const data = event.data ?? {}
  if (data.usage !== undefined) return data.usage
  const chunks = Array.isArray(data.stream) ? data.stream : Array.isArray(data.stream?.chunks) ? data.stream.chunks : []
  for (let i = chunks.length - 1; i >= 0; i -= 1) {
    const c = chunks[i]
    if (c?.type === 'usage' && c.usage) return c.usage
  }
  return undefined
}

/** Persistent per-day and per-session usage tally ($DSH_HOME/android/budget.json). */
class Ledger {
  constructor(file) {
    this.file = file
    this.state = { day: localDay(), dailyTokens: 0, dailyCostCny: 0, sessions: {} }
    try {
      const loaded = JSON.parse(readFileSync(file, 'utf8'))
      if (loaded && typeof loaded === 'object') this.state = { ...this.state, ...loaded, sessions: loaded.sessions ?? {} }
    } catch {}
    this.rollover()
    this.timer = undefined
  }

  rollover() {
    const today = localDay()
    if (this.state.day !== today) {
      this.state.day = today
      this.state.dailyTokens = 0
      this.state.dailyCostCny = 0
    }
  }

  add(sessionId, tokens, costCny) {
    this.rollover()
    this.state.dailyTokens += tokens
    this.state.dailyCostCny += costCny
    const entry = this.state.sessions[sessionId] ?? { tokens: 0, costCny: 0, at: 0 }
    entry.tokens += tokens
    entry.costCny += costCny
    entry.at = Date.now()
    this.state.sessions[sessionId] = entry
    const ids = Object.keys(this.state.sessions)
    if (ids.length > 500) {
      ids.sort((a, b) => this.state.sessions[a].at - this.state.sessions[b].at)
      for (const id of ids.slice(0, ids.length - 500)) delete this.state.sessions[id]
    }
    this.scheduleSave()
  }

  session(sessionId) { return this.state.sessions[sessionId] ?? { tokens: 0, costCny: 0 } }

  scheduleSave() {
    if (this.timer !== undefined) return
    this.timer = setTimeout(() => { this.timer = undefined; this.save() }, 1000)
    this.timer.unref?.()
  }

  save() {
    if (this.timer !== undefined) { clearTimeout(this.timer); this.timer = undefined }
    try {
      mkdirSync(dirname(this.file), { recursive: true })
      const tmp = `${this.file}.${process.pid}.tmp`
      writeFileSync(tmp, JSON.stringify(this.state))
      renameSync(tmp, this.file)
    } catch {}
  }
}

/** The English system-prompt section (the model replies in the user's language). */
export function systemPromptSection(locale) {
  const lang = locale ? `The phone's language is ${locale}. Reply in the language the user writes in; when unsure, use ${locale}.` : 'Reply in the language the user writes in.'
  return `# Running on the user's Android phone

You run inside the DSH app on the user's own Android phone, not on a server or developer machine. The device tools (files, apps, screen, system settings, notifications, contacts/messages, media, clipboard, automation rules) act on this phone through the app; some groups may be missing because the user has not enabled them.

Tool priority — always prefer the most direct, least fragile path:
1. System API tools (e.g. alarm_set, volume_set, file_*, contacts_search, calendar_add) — they work even while the phone is locked.
2. Intents and deep links (app_open_link, app_send_intent, share_to_app).
3. Shizuku-backed tools (wifi_toggle, bluetooth_toggle, setting_put) when available.
4. Screen automation (screen_read, screen_tap, screen_type, …) only when nothing above can do the job; it needs the phone unlocked and is slow and brittle.

Approvals and safety:
- Read-only tools run directly. Tools that change the phone ask the user to approve each call; if the user rejects a call, do not retry it — ask what they want instead.
- Before deleting, moving or overwriting files, state exactly which files and folders are affected (list them, or give the count and paths) and confirm the scope with the user unless they already named the exact items. Prefer the narrowest scope; never act on whole storage roots.
- The app hard-blocks some shell commands (wiping storage roots or system directories, formatting or writing block devices, reboot/shutdown, fork bombs, executing decoded payloads, uninstalling system packages, deleting system settings): such a call is refused before it runs and returns HARDLINE_BLOCKED. Never try to work around a block.
- A tool that needs the phone unlocked may wait until the user unlocks it; tell the user when that happens.

Shell: the bash tool runs Android's /system/bin/sh (mksh) with toybox utilities, not GNU bash/coreutils; there is no root. The user's shared storage is /sdcard (= /storage/emulated/0). Your working directory is the app's private home folder.

${lang} Keep replies short and suited to a phone screen.`
}

export function apply(ctx, config = {}) {
  const askTools = new Set(Array.isArray(config.askTools) ? config.askTools : ['bash', 'write', 'edit'])
  const prices = { ...DEFAULT_PRICES, ...(config.prices ?? {}) }
  const locale = typeof config.locale === 'string' ? config.locale : (process.env.DSH_ANDROID_LOCALE ?? '')
  const home = process.env.DSH_HOME || join(homedir(), '.dsh')
  const ledger = new Ledger(config.ledgerPath ?? join(home, 'android', 'budget.json'))
  let budget = {}

  // ── 1. Hardline floor ──────────────────────────────────────────────────────
  const hardlineVerdict = exec => {
    const field = SHELL_TOOLS.get(exec.name)
    if (field === undefined) return undefined
    const text = exec.arguments?.[field]
    if (typeof text !== 'string') return undefined
    const cwd = typeof exec.arguments?.workdir === 'string' ? exec.arguments.workdir : undefined
    return checkCommand(text, { cwd })
  }
  ctx.on('tools/pre-execute', async (exec, next) => {
    const v = hardlineVerdict(exec)
    if (v !== undefined) {
      log(ctx, 'warn', `hardline blocked ${exec.name} (${v.rule})`)
      return { kind: 'deny', reason: refusalText(v), info: { name: 'HardlineBlocked', code: 'HARDLINE_BLOCKED' } }
    }
    // ── 2. approval for mutating built-ins (no OS sandbox on Android) ──────────
    const decision = await next()
    if (decision?.kind === 'allow' && askTools.has(exec.name)) {
      let detail = ''
      try {
        const a = exec.arguments ?? {}
        detail = typeof a.command === 'string' ? a.command : JSON.stringify(a)
      } catch {}
      if (detail.length > 2000) detail = detail.slice(0, 2000) + '…'
      return { kind: 'ask', reason: `${exec.name}: ${detail}` }
    }
    return decision
  }, { prepend: true })
  // Backstop: guards run after every pre-execute decision and cannot be overridden.
  if (typeof ctx.tools.guard === 'function') {
    ctx.tools.guard(exec => {
      const v = hardlineVerdict(exec)
      return v === undefined ? undefined : refusalText(v)
    })
  }

  // ── 3. Budgets ─────────────────────────────────────────────────────────────
  const priceFor = model => prices[model] ?? prices.default
  const overBudget = sessionId => {
    ledger.rollover()
    const s = ledger.session(sessionId)
    if (budget.sessionTokenLimit !== undefined && s.tokens >= budget.sessionTokenLimit) return { kind: 'sessionTokenLimit', used: s.tokens, limit: budget.sessionTokenLimit }
    if (budget.dailyTokenLimit !== undefined && ledger.state.dailyTokens >= budget.dailyTokenLimit) return { kind: 'dailyTokenLimit', used: ledger.state.dailyTokens, limit: budget.dailyTokenLimit }
    if (budget.dailyCostLimitCny !== undefined && ledger.state.dailyCostCny >= budget.dailyCostLimitCny) return { kind: 'dailyCostLimitCny', used: Math.round(ledger.state.dailyCostCny * 1e4) / 1e4, limit: budget.dailyCostLimitCny }
    return undefined
  }
  ctx.on('session/event', (session, event) => {
    const usage = usageOf(event)
    if (usage === undefined) return
    const input = Number(usage.inputTokens ?? 0)
    const output = Number(usage.outputTokens ?? 0)
    const cacheRead = Number(usage.cacheReadTokens ?? 0)
    const cacheWrite = Number(usage.cacheWriteTokens ?? 0)
    const tokens = input + output + cacheRead + cacheWrite
    if (!Number.isFinite(tokens) || tokens <= 0) return
    const model = session.requestHeader?.()?.config?.model
    const p = priceFor(model)
    const cost = ((input + cacheWrite) * p.input + cacheRead * p.cacheRead + output * p.output) / 1e6
    ledger.add(rootOfSession(ctx, session), tokens, Number.isFinite(cost) ? cost : 0)
  })
  ctx.on('agent/pre-step', async (input, next) => {
    const sessionId = rootSessionId(ctx, input.agent)
    const over = sessionId === undefined ? undefined : overBudget(sessionId)
    if (over === undefined) return next()
    log(ctx, 'warn', `budget exceeded for ${sessionId}: ${over.kind} ${over.used} >= ${over.limit}; refusing the step`)
    try {
      ctx.get('androidBridge')?.notify?.('budget/exceeded', { sessionId, ...over })
    } catch {}
    return { kind: 'reject' }
  }, { prepend: true })
  ctx.inject(['androidBridge'], child => {
    const bridge = child.get('androidBridge')
    budget = bridge.budget ?? {}
    const off = bridge.onBudget(next => { budget = next })
    child.effect(() => off, 'android-guard.budget')
  })
  ctx.effect(() => () => ledger.save(), 'android-guard.ledger')

  // ── 4. System prompt ───────────────────────────────────────────────────────
  ctx.inject(['systemPrompt'], child => {
    child.get('systemPrompt').section({
      name: 'android:phone-context',
      order: 130,
      interpolate: false,
      text: systemPromptSection(locale),
    })
  })
}
