# Engine ↔ App contract

The Android app (Kotlin) and the embedded dsh engine (Node 24 in process `:engine`) talk over exactly two channels. This file is the single source of truth for both sides.

## 1. Launch

`NodeRuntime.nativeStart(argv, stdinFd, stdoutFd, stderrFd)` (JNI, `libdshengine.so`) runs:

```
node --expose-internals <NODE_OPTIONS flags> --require <ENGINE_DIR>/node_modules/@dsh-android/bundle-android/android-env.cjs \
     <ENGINE_DIR>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile android
```

`--expose-internals` replaces the `node-addon-require-builtin` native addon (js patch 02); Node rejects it in
`NODE_OPTIONS`, so it is argv-only (`EngineRuntimeEnv.nodeArgv`). stderr goes to `<filesDir>/logs/engine.log`
(previous runs rotated to `engine.log.1..3`).

**AT_SECURE.** Zygote-forked app processes run with `AT_SECURE=1`, so Node's `SafeGetenv` ignores
`NODE_OPTIONS`, `NODE_COMPILE_CACHE`, `NODE_EXTRA_CA_CERTS` and the `TMPDIR` lookup behind `os.tmpdir()` (which
then falls back to the nonexistent `/tmp`), although `process.env` still shows them. The app therefore also
passes the `NODE_OPTIONS` flags in argv, and the `android-env.cjs` preload restores `os.tmpdir()`, the compile
cache and the extra CA certificates from `process.env` (it logs `android-env: … restored …` when it acts).

Environment (set with `Os.setenv` before start):

| Var | Value |
|---|---|
| `DSH_HOME` | `<filesDir>/dsh-home` |
| `HOME` | `<filesDir>/home` (also the default workspace / cwd) |
| `TMPDIR` | `<cacheDir>/tmp` |
| `SHELL` | `/system/bin/sh` |
| `PATH` | `<filesDir>/bin:/system/bin:/system/xbin` (`files/bin/rg` → symlink to `<nativeLibraryDir>/librg.so`) |
| `DSH_NATIVE_DIR` | `<nativeLibraryDir>` (native addons ship there as `libdsh_pty.so`, `libdsh_flock.so`) |
| `DSH_BRIDGE_FD` | fd number of the capability socket (see §3) |
| `DEEPSEEK_API_KEY` | decrypted key (never logged); **absent** when the user has no API key (account login, or nothing yet) |
| `DSH_ANDROID_PACKAGE` | the app's package name (the sign-in completion page's `intent:` link) |
| `DSH_ANDROID_LOCALE` | e.g. `zh-CN` (system prompt language hint) |
| `TZ`, `DSH_ANDROID_TZ` | the device's IANA zone (`TimeZone.getDefault().id`, e.g. `Asia/Shanghai`). Node's ICU finds no zone on Android otherwise (UTC). `android-env.cjs` assigns `process.env.TZ` from `DSH_ANDROID_TZ` when the resolved zone differs, which resets ICU; drives the time context (patch 11), budget day rollover and log times. A zone change needs an engine restart. |
| `NODE_EXTRA_CA_CERTS` | `<filesDir>/certs/android-ca.pem`: `AndroidCAStore` (system minus user-disabled, plus user-installed CAs), re-exported when its digest changes |
| `NODE_COMPILE_CACHE` | `<cacheDir>/node-compile-cache` (V8 code cache) |
| `NODE_OPTIONS` | composed only in `EngineRuntimeEnv` (app options + runtime options, de-duplicated) |

`ENGINE_DIR` = `<filesDir>/engine/<engineVersion>/`, unpacked from the APK asset `engine.zip` (flat tree, **no symlinks**) on first start of a new version; `engine.sha256` guards integrity.

## 2. SDK channel (stdin/stdout)

Unmodified dsh SDK JSON-RPC 2.0, newline-delimited (`packages/sdk/protocol`). The app is the client (notifications are handled strictly in wire order). dsh notifies `session.event`s before they are
durable: after an `:engine` crash the resumed session re-issues seqs the dead process had already sent, so the app logs
each engine generation's events at an offset past its log (`LogSeqs`), never treating a seq as a cross-generation identity.
The app is the client:

- `initialize { cwd: HOME, provider, model, approvalRequests: true, capabilities: { userQuestions: true } }` —
  `provider` is `deepseek-official` when an API key is set, else `deepseek-account` when a DeepSeek account is signed in
  (dsh's `initializeDefaultModel` precedence: the account route only without a key), else `deepseek-official` (the engine
  still starts so the user can sign in; the app refuses prompts with "当前模型暂不可用，请登录后再试"). The app reads
  `account/state` before `initialize`. A route change re-sends `initialize` while no SDK session is open in the process
  (patch-free: the server just replaces its route), otherwise restarts the idle engine.
- `session/prompt`, `session/resume`, `session/wait`, `shutdown`. `session/prompt` also accepts an optional
  `clientTimeZone` (IANA); without it the engine uses its process `TZ` (patch 11). Each request carries a
  `dsh-time-context` reading (date, time, zone, elapsed time) so relative requests ("tomorrow 7:00") resolve.
- `android/session/permission { sessionId, preset: 'read-only' | 'workspace-write' | 'danger-full-access' | null }` → `{}`
  (plugin-guard, through patch 10). **The app sends it before each `session/prompt` and whenever the user changes the
  conversation's permission mode** (`PermissionPreset.id`; `null` forgets it). With it the engine refuses actions that
  need approval up front in 仅可查看 and tells the model the mode is why (instead of "the user rejected tool …"), and
  auto-allows `write`/`edit` inside `HOME`/`TMPDIR` in the other modes. Until the app sends it the engine treats the
  mode as unknown: those writes still ask, and a refusal says "declined, or the mode is 仅可查看".
- handles server requests `approval/request` (answer `allowed-once | rejected | cancelled`; requests from subagents carry `rootSessionId`, which owns the approval) and `user-question/ask` (`ask_user_question` tool, plan mode); consumes `session.event` / `session.status` notifications.
- Approval friction (plugin-guard, before anything reaches the app): read-only shell commands (`ls`, `cat`, `grep`,
  `getprop`, `dumpsys`, `settings get`, `pm list`, … with no output redirect; hardline-parsed) run without asking in
  every mode; `write`/`edit` inside `HOME`/`TMPDIR` run without asking unless the mode is 仅可查看 or unknown; a
  `sensitive` device tool asks once per conversation (after one `allowed-once`, later calls of that tool in the same
  root session run directly; the `reason` says so). `dangerous` tools always ask.
- Unattended (`auto_…`) sessions: the model gets a runtime-context note (nobody is watching, only the rule's tools run,
  unlock-waiting tools listed from metadata, finish with a notification-sized result); `user-question/ask` is never sent
  for them — plugin-guard answers "nobody is available" itself. No engine-side auto-allow of writes there.
- dsh 0.2.1-rc.2 lacks SDK approval/question requests; `js/patches/08` backports them from rc.3 (drop when upgrading).

### 2.4 Account (DeepSeek login) — `@dsh-android/plugin-account`, patch 10

dsh-base already mounts the account provider (`deepseek-account` → `@deepseek-ai/dsh-deepseek-account-platform`: system
browser OAuth + PKCE S256 against `https://platform.deepseek.com`, grant in `$DSH_HOME`'s credential store, `resolveToken`
for model / web-search requests to `api.deepseek.com`, profile / balance, 401 → session expired, sign-out with background
revocation) and the `deepseek-account` model route. dsh's account RPC is a Web (Typert) controller, so the plugin
re-exposes the same operations as SDK methods (patch 10 lets profile plugins add SDK methods through an
`sdkExtensions` service and emit SDK notifications through the `sdk-extensions/notify` event). Methods work before
`initialize`. `client` (optional) = dsh `AccountClientMetadata` `{ locale, timezoneOffsetSeconds, version? }`
(version defaults to the engine's dsh version).

| App → engine | Result |
|---|---|
| `account/state {}` | `AccountView { status: 'signed-out' \| 'credential-stored', links: { usageUrl, topUpUrl }, attempt: null \| { id, phase, authorizeUrl?, expiresAt?, errorCode? } }` |
| `account/details { client? }` | `{ profile: null \| {status:'ready', value:{id,name,contact,avatarUrl}} \| {status:'failed'}, balance: null \| {status:'ready', value: Wallet[], bonusWallets: Wallet[]} \| {status:'failed'} }`, `Wallet = { currency: 'CNY'\|'USD', balance: decimal string }` |
| `account/sign-in/start { client? }` | `AccountView` (new or joined attempt; `phase` initializing → waiting-browser with `authorizeUrl` → exchanging → committing → succeeded \| failed \| expired \| cancelled) |
| `account/sign-in/cancel { attemptId }` | `AccountView` |
| `account/sign-out { client? }` | `AccountView` (local grant removed; Platform logout retried in the background; API keys untouched) |
| `account/tasks/running {}` | `{ running: boolean }` (running work on the account route; sign-out would interrupt it) |

Engine → app notifications: `account.changed { view }` (every state change, initial snapshot first),
`account.session-expired {}`, `account.signed-out {}`, `account.model-sign-in-required {}` (an account-model request was
refused for lack of a usable login), `account.callback { result: 'ok' | 'failed' | 'cancelled' }` (the browser reached the
callback).

Browser round trip on Android: `account/sign-in/start` opens a loopback HTTP server on `127.0.0.1:<random port>` (the
provider's `webServer`), sends `redirect_uri = http://127.0.0.1:<port>/oauth/callback` to Platform's `auth_init` and
returns; the app shows `authorizeUrl` (+ `theme=light|dark`) in its own sign-in screen, a WebView inside MainActivity
(feature/account/PlatformWebScreen.kt; no Custom Tab, so OEM browsers cannot turn it into a separate browser task).
Platform redirects the page to the loopback callback (cleartext allowed for 127.0.0.1 / localhost only, via
`network_security_config`); the provider validates `state`, exchanges the code with the PKCE verifier and commits the
grant. Instead of Platform's completion page (or dsh's "close this tab" page), the callback answers with a local page that
opens `intent://account/login?result=…#Intent;scheme=dsh-android;package=<pkg>;end` (automatically, and from a 返回 DSH
button); the WebView intercepts that navigation and closes the screen, which also closes when the attempt settles.
Closing the screen (✕, or Back with no page history) cancels the attempt (`account/sign-in/cancel`). The VIEW filter
`dsh-android://account` on MainActivity remains for a sign-in link copied into another browser.

**Session id convention:** attended sessions use a random UUID. Sessions started by automations use `auto_<ruleId>_<uuid>`; the app auto-answers `approval/request` for these (allowed only if the tool is in the rule's whitelist, else `rejected`).

## 3. Capability channel (`DSH_BRIDGE_FD`)

A `socketpair(AF_UNIX, SOCK_STREAM)`; Node opens it with `new net.Socket({ fd })`. Newline-delimited JSON-RPC 2.0, both directions.

### Node → App requests
- `capability/list {}` → `{ tools: ToolDescriptor[] }`
- `capability/invoke { sessionId, callId, tool, args, timeoutMs? }` → `ToolResult` (`timeoutMs`: the budget the plugin uses for this call, see §3.1)

```ts
interface ToolDescriptor {
  name: string            // e.g. "file_search"
  group: string           // files | apps | screen | system | notifications | comms | media | clipboard | browser | rules | vscreen
  description: string     // model-facing, English
  inputSchema: object     // JSON Schema
  risk: "read" | "write" | "sensitive" | "dangerous"
  needsUnlock: boolean
  timeoutMs: number       // the tool's time budget (§3.1); default 300000, 900000 for needsUnlock tools
}
interface ToolResult {
  ok: boolean
  content: Array<{ type: "text", text: string } | { type: "image", mimeType: string, data: string /* base64 */ }>
  error?: { code: "not_authorized" | "permission_missing" | "device_locked_timeout" | "waiting_for_user" | "timeout" | "cancelled"
                 | "invalid_args" | "shizuku_unavailable" | "needs_user" | "failed" | string, message: string }
}
```

Descriptions are static: they never carry live state (Shizuku up/down, permissions), because every description
change re-lists the tools and re-bills the cached prompt. Availability is reported in the call's result (`path=…`,
"via Shizuku") or error (`shizuku_unavailable`, `permission_missing`). `capability/changed` is only sent when the
user changes which tool groups are enabled.

The app is authoritative for: which groups are enabled, runtime permissions, lock-state waiting (a `needsUnlock` call while locked is held by the app, which posts a "continue after unlock" notification; the request resolves after unlock, or with `waiting_for_user` when the budget runs out), and unattended whitelists.

### 3.1 Timeouts and cancellation

A call that the engine has given up on must never act later (e.g. tap stale coordinates after an unlock two hours
later, or send an SMS twice after a retry). The rules:

1. **One budget per tool.** `ToolDescriptor.timeoutMs` (set in the app's `Tool` definition) is the single source of
   truth. The plugin registers the dsh tool with `timeoutMs + 15 s`, sends `timeoutMs` in `capability/invoke`, and
   times the bridge request out at `timeoutMs + 10 s`.
2. **The app answers first.** CapabilityHub runs the call with a deadline of `timeoutMs − 5 s`. Every wait for the
   user inside it — unlock, the permission card, the Shizuku card, the takeover card — is a cancellable suspension
   bounded by that deadline. When the deadline passes the call is cancelled and answered with
   `waiting_for_user` ("still waiting for the user to …; nothing was done; call again") or `timeout` (the tool's own
   work overran). The model can simply call the tool again when the user is ready.
3. **Acting re-checks.** After any wait, the hub checks that the call is still live right before invoking the tool
   (`EngineCalls.ensureCallActive()`), so a call cancelled while it waited never starts its side effect.
4. **Cancel from the engine.** When the plugin's bridge request times out, or the turn is aborted (`exec.signal`,
   which dsh also aborts at the tool deadline), the plugin sends the notification
   `capability/cancel { sessionId, callId, reason: "timeout" | "aborted" }`. The app cancels that call's job (each
   bridge call runs in its own job, keyed by `sessionId` + `callId`); every wait inside unwinds, cards and the
   "unlock to continue" notification are removed in `finally`. The app may still answer the original request with
   `cancelled`; the plugin has already moved on and ignores it. Cancelling an unknown or finished call is a no-op.
   (`capability/cancel` is also accepted as a request, answered with `{ ok: true }`.)
5. **Engine loss.** On stop, crash, restart or bridge EOF, EngineManager tears the generation down: every in-flight
   engine request is cancelled (`EngineCallCancelled`, see `core/engine/EngineCalls.kt`) and
   `CapabilityHub.detach()` cancels all bridge calls. `CapabilityHub.cancelSession(id)` cancels one session's calls.
6. **Rule steps** (fixed tool calls run by an automation, no engine) use the descriptor budget too, except that a
   `needsUnlock` step may wait up to 2 h for the user to unlock; they are cancellable the same way.

### Node → App notifications
- `capability/cancel { sessionId, callId, reason? }` — the plugin gave up on this call (§3.1); the app cancels it.
- `budget/exceeded { sessionId, kind, used, limit }` — a budget refused a step; the app shows it in the conversation.

### App → Node notifications
- `capability/changed {}` — tool set changed (tool groups toggled); the plugin re-lists.
- `budget/update { sessionTokenLimit?: number, dailyTokenLimit?: number, dailyCostLimitCny?: number }`. Token limits
  are metered **input-equivalent**: uncached input + cache writes + output, plus cache reads weighted by their price
  ratio (cacheRead / input = 0.1 by default). The cached prompt prefix (~13k tokens with all device groups) is re-read on
  every step; counting it at full weight made a 10-step task look like ~130k tokens. The cost limit uses real prices.

## 4. dsh-side responsibilities (js/)
- `@dsh-android/plugin-device`: registers every `ToolDescriptor` as a dsh tool; maps `risk` → dsh approval requirement (`read` auto; others ask); per-tool timeouts from `ToolDescriptor.timeoutMs` and `capability/cancel` on timeout/abort (§3.1).
- `@dsh-android/plugin-account`: loopback OAuth callback server + account SDK methods/notifications (§2.4).
- `@dsh-android/plugin-guard`: hardline command floor for the shell tool (relative paths resolve against the session
  cwd and any `cd` earlier in the command); approval policy (§2); token/cost budget enforcement from `budget/update`;
  system-prompt section describing the phone context and the tool-priority rule (system API > intent/deeplink >
  Shizuku > accessibility clicks), whose "works while locked" examples are derived from `needsUnlock:false`
  descriptors; unattended-run context; dsh `ctx.logger` warnings/errors mirrored to stderr (`[dsh:<name>] warn: …` in
  engine.log).
- Profile also mounts `dsh-time-context` and `dsh-tool-ask-user`, and disables `otel` and `llm-pi-ai` (cold start:
  ~1000 → ~500 modules at boot; build-engine prunes the provider SDKs only pi-ai reaches).
- Profile `android`: dsh-base minus web-app/HMR/office/voice/inspector/browser-use/ssh/experimental bundles; shell → `/system/bin/sh`; DeepSeek web search on.
- `patches/`: platform `android` support (flock loader → `DSH_NATIVE_DIR`, require-builtin → `--expose-internals`), `bash -c` → `/system/bin/sh -c`, attachment hard-link → copy fallback, ripgrep path → `PATH`, android profile template, SDK approval/user-question backport, sharp wasm, SDK extension methods, SDK prompt time zone; sandbox chain: none on android (approval-only). node-pty is not patched (no terminal row is mounted; `libdsh_pty.so` is unused).
