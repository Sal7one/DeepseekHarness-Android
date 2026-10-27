# Engine ↔ App contract

The Android app (Kotlin) and the embedded dsh engine (Node 24 in process `:engine`) talk over exactly two channels. This file is the single source of truth for both sides.

## 1. Launch

`NodeRuntime.nativeStart(argv, stdinFd, stdoutFd, stderrFd)` (JNI, `libdshengine.so`) runs:

```
node <ENGINE_DIR>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile android
```

Environment (set with `Os.setenv` before start):

| Var | Value |
|---|---|
| `DSH_HOME` | `<filesDir>/dsh-home` |
| `HOME` | `<filesDir>/home` (also the default workspace / cwd) |
| `TMPDIR` | `<cacheDir>/tmp` |
| `SHELL` | `/system/bin/sh` |
| `PATH` | `<filesDir>/bin:/system/bin:/system/xbin` (`files/bin/rg` → symlink to `<nativeLibraryDir>/librg.so`) |
| `DSH_NATIVE_DIR` | `<nativeLibraryDir>` (native addons ship there as `libdsh_pty.so`, `libdsh_flock.so`, `libdsh_require_builtin.so`) |
| `DSH_BRIDGE_FD` | fd number of the capability socket (see §3) |
| `DEEPSEEK_API_KEY` | decrypted key (never logged) |
| `DSH_ANDROID_LOCALE` | e.g. `zh-CN` (system prompt language hint) |

`ENGINE_DIR` = `<filesDir>/engine/<engineVersion>/`, unpacked from the APK asset `engine.zip` (flat tree, **no symlinks**) on first start of a new version; `engine.sha256` guards integrity.

## 2. SDK channel (stdin/stdout)

Unmodified dsh SDK JSON-RPC 2.0, newline-delimited (`packages/sdk/protocol`). The app is the client:

- `initialize { cwd: HOME, provider: "deepseek-official", model, approvalRequests: true, capabilities: { userQuestions: true } }`
- `session/prompt`, `session/resume`, `session/wait`, `shutdown`
- handles server requests `approval/request` (answer `allowed-once | rejected | cancelled`; requests from subagents carry `rootSessionId`, which owns the approval) and `user-question/ask`; consumes `session.event` / `session.status` notifications.
- dsh 0.2.1-rc.2 lacks SDK approval/question requests; `js/patches/08` backports them from rc.3 (drop when upgrading).

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
- `@dsh-android/plugin-guard`: hardline command floor for the shell tool; token/cost budget enforcement from `budget/update`; system-prompt section describing the phone context and the tool-priority rule (system API > intent/deeplink > Shizuku > accessibility clicks).
- Profile `android`: dsh-base minus web-app/HMR/office/voice/inspector/browser-use/ssh/experimental bundles; shell → `/system/bin/sh`; DeepSeek web search on.
- `patches/`: platform `android` support (flock, require-builtin, node-pty loaders → `DSH_NATIVE_DIR`), `/bin/sh` fallback → `/system/bin/sh`, attachment hard-link → copy fallback, ripgrep path → `PATH`, sandbox chain: none on android (approval-only).
