# Engine ↔ App contract

The Android app (Kotlin) and the embedded dsh engine (Node 24 in process `:engine`) talk over exactly two channels. This file is the single source of truth for both sides.

## 1. Launch

`NodeRuntime.nativeStart(argv, stdinFd, stdoutFd, stderrFd)` (JNI, `libdshengine.so`) runs:

```
node --expose-internals <ENGINE_DIR>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile android
```

`--expose-internals` replaces the `node-addon-require-builtin` native addon (js patch 02); Node rejects it in
`NODE_OPTIONS`, so it is argv-only (`EngineRuntimeEnv.NODE_ARGV`). stderr goes to `<filesDir>/logs/engine.log`
(previous runs rotated to `engine.log.1..3`).

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
| `NODE_EXTRA_CA_CERTS` | `<filesDir>/certs/android-ca.pem`: `AndroidCAStore` (system minus user-disabled, plus user-installed CAs), re-exported when its digest changes |
| `NODE_COMPILE_CACHE` | `<cacheDir>/node-compile-cache` (V8 code cache) |
| `NODE_OPTIONS` | composed only in `EngineRuntimeEnv` (app options + runtime options, de-duplicated) |

`ENGINE_DIR` = `<filesDir>/engine/<engineVersion>/`, unpacked from the APK asset `engine.zip` (flat tree, **no symlinks**) on first start of a new version; `engine.sha256` guards integrity.

## 2. SDK channel (stdin/stdout)

Unmodified dsh SDK JSON-RPC 2.0, newline-delimited (`packages/sdk/protocol`). The app is the client:

- `initialize { cwd: HOME, provider, model, approvalRequests: true, capabilities: { userQuestions: true } }` —
  `provider` is `deepseek-official` when an API key is set, else `deepseek-account` when a DeepSeek account is signed in
  (dsh's `initializeDefaultModel` precedence: the account route only without a key), else `deepseek-official` (the engine
  still starts so the user can sign in; the app refuses prompts with "当前模型暂不可用，请登录后再试"). The app reads
  `account/state` before `initialize`. A route change re-sends `initialize` while no SDK session is open in the process
  (patch-free: the server just replaces its route), otherwise restarts the idle engine.
- `session/prompt`, `session/resume`, `session/wait`, `shutdown`
- handles server requests `approval/request` (answer `allowed-once | rejected | cancelled`; requests from subagents carry `rootSessionId`, which owns the approval) and `user-question/ask`; consumes `session.event` / `session.status` notifications.
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
returns; the app opens `authorizeUrl` (+ `theme=light|dark`) in a Chrome Custom Tab from MainActivity (so the tab sits on
the app's task). Platform redirects the tab to the loopback callback; the provider validates `state`, exchanges the code
with the PKCE verifier and commits the grant. Instead of Platform's completion page (or dsh's "close this tab" page), the
tab gets a local page that opens `intent://account/login?result=…#Intent;scheme=dsh-android;package=<pkg>;end`
(automatically, and from a 返回 DSH button); MainActivity (`singleTask`, VIEW filter `dsh-android://account`) comes forward
and clears the tab. The app also tries to bring MainActivity forward itself when the attempt settles.

**Session id convention:** attended sessions use a random UUID. Sessions started by automations use `auto_<ruleId>_<uuid>`; the app auto-answers `approval/request` for these (allowed only if the tool is in the rule's whitelist, else `rejected`).

## 3. Capability channel (`DSH_BRIDGE_FD`)

A `socketpair(AF_UNIX, SOCK_STREAM)`; Node opens it with `new net.Socket({ fd })`. Newline-delimited JSON-RPC 2.0, both directions.

### Node → App requests
- `capability/list {}` → `{ tools: ToolDescriptor[] }`
- `capability/invoke { sessionId, callId, tool, args }` → `ToolResult`

```ts
interface ToolDescriptor {
  name: string            // e.g. "file_search"
  group: string           // files | apps | screen | system | notifications | comms | media | clipboard | browser | rules | vscreen
  description: string     // model-facing, English
  inputSchema: object     // JSON Schema
  risk: "read" | "write" | "sensitive" | "dangerous"
  needsUnlock: boolean
}
interface ToolResult {
  ok: boolean
  content: Array<{ type: "text", text: string } | { type: "image", mimeType: string, data: string /* base64 */ }>
  error?: { code: "not_authorized" | "permission_missing" | "device_locked_timeout" | "invalid_args" | "failed", message: string }
}
```

The app is authoritative for: which groups are enabled, runtime permissions, lock-state waiting (a `needsUnlock` call while locked is held by the app, which posts a "continue after unlock" notification; the request resolves after unlock, or with `device_locked_timeout`), and unattended whitelists.

### Node → App notifications
- `budget/exceeded { sessionId, kind, used, limit }` — a budget refused a step; the app shows it in the conversation.

### App → Node notifications
- `capability/changed {}` — tool set changed (groups toggled, permission granted); the plugin re-lists.
- `budget/update { sessionTokenLimit?: number, dailyTokenLimit?: number, dailyCostLimitCny?: number }`

## 4. dsh-side responsibilities (js/)
- `@dsh-android/plugin-device`: registers every `ToolDescriptor` as a dsh tool; maps `risk` → dsh approval requirement (`read` auto; others ask); long timeout for `needsUnlock` tools.
- `@dsh-android/plugin-account`: loopback OAuth callback server + account SDK methods/notifications (§2.4).
- `@dsh-android/plugin-guard`: hardline command floor for the shell tool; token/cost budget enforcement from `budget/update`; system-prompt section describing the phone context and the tool-priority rule (system API > intent/deeplink > Shizuku > accessibility clicks).
- Profile `android`: dsh-base minus web-app/HMR/office/voice/inspector/browser-use/ssh/experimental bundles; shell → `/system/bin/sh`; DeepSeek web search on.
- `patches/`: platform `android` support (flock, require-builtin, node-pty loaders → `DSH_NATIVE_DIR`), `/bin/sh` fallback → `/system/bin/sh`, attachment hard-link → copy fallback, ripgrep path → `PATH`, sandbox chain: none on android (approval-only).
