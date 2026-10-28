# DSH-Android engine (js/)

The JavaScript engine layer of DSH-Android: DeepSeek Harness (dsh) **0.2.1-rc.2**, installed unmodified from npm,
customized only by a dsh profile/bundle, two dsh plugins, and a small set of patches applied at build time.
The Kotlin side of the interface is specified in [`../docs/ENGINE-CONTRACT.md`](../docs/ENGINE-CONTRACT.md);
this directory implements the dsh side of it.

## Layout

| Path | What |
|---|---|
| `engine/package.json`, `engine/package-lock.json` | npm project whose production install *is* the engine tree. dsh is pinned exactly; the lockfile pins every transitive package. |
| `packages/bundle-android/` | `@dsh-android/bundle-android` — the dsh bundle (patch layer) behind profile `android`. |
| `packages/plugin-device/` | `@dsh-android/plugin-device` — capability bridge client (`DSH_BRIDGE_FD`) → dynamic dsh tools. |
| `packages/plugin-guard/` | `@dsh-android/plugin-guard` — hardline shell floor, approvals for mutating built-ins, budgets, phone system prompt. |
| `packages/plugin-account/` | `@dsh-android/plugin-account` — DeepSeek account login: loopback OAuth callback server + account SDK methods/notifications. |
| `patches/*.patch` | unified diffs against `node_modules/` (applied with `patch -p1`, in order, dry-run checked first). |
| `scripts/fetch-locked.mjs` | installs lockfile-pinned packages `npm ci` skips on the host (sharp's WebAssembly build), sha512-verified. |
| `scripts/make-zip.py` | deterministic zip (sorted, fixed mtimes, rejects symlinks). |
| `build-engine.sh` | the build (below). |
| `test/hardline.test.mjs` | unit tests for the hardline checker (`node --test js/test/hardline.test.mjs`). |
| `test/host-e2e.mjs` | host end-to-end run of the built `engine.zip` with a fake capability bridge and the real DeepSeek API; phase E repeats it with `process.platform` faked to `android` (approval policy, time zone, unattended runs). |

The plugins are plain ESM JavaScript with no dependencies outside `node:` built-ins, so there is no compile step.

## Build

```sh
js/build-engine.sh
```

Outputs (default `$AGENT/toolchains/out/engine/`, override with `OUT_DIR`):
`engine.zip`, `engine.sha256` (`sha256sum` format), `engine-version.txt`, `size-report.txt`.
Requires node/npm, `patch`, `python3`, network access to the npm registry. `/tmp` is not used (`TMPDIR` → `toolchains/tmp`).

Steps:

1. `npm ci --omit=dev --ignore-scripts` from `engine/` (`overrides: {zod: 4.4.3}` — dsh pins zod 4.4.3 exactly in ~60
   packages; without the override npm hoists a newer zod and nests 50 copies, ~250 MiB) (flat, hoisted npm layout, never pnpm symlinks). Install scripts are
   unnecessary: node-pty/koffi only select prebuilds (pruned anyway), `dsh-subprocess-local` only chmods the macOS
   `spawn-helper`, the others are no-ops.
2. Copy `@dsh-android/{bundle-android,plugin-device,plugin-guard,plugin-account}` into `node_modules/@dsh-android/` (copies, not links) and add
   them to the root `package.json`.
3. Add `@img/sharp-freebsd-wasm32` + `@img/sharp-wasm32` + `@emnapi/runtime` + `tslib` at their lockfile versions (see patch 09).
4. Save the host's linux-arm64 bindings to `engine-build/host-native/libdsh_*.so` (test only; never zipped).
5. Apply `patches/*.patch`; fail on any reject/fuzz leftovers.
6. Prune: `@deepseek-ai/libreoffice-kit-wasm`, `sherpa-onnx*`, the browser client UI (`@deepseek-ai/dsh-client-ui-*`,
   `dsh-web-frontend`) and `dsh-experimental-inspector` (never loaded by this profile; boot verified without them), every `@img/sharp-*` native/libvips package, other-platform and
   host native packages (`node-addon-system-*`, `node-addon-require-builtin-*`, `@koromix/koffi-*`, `@vscode/ripgrep-*`,
   `@vscode/ripgrep/bin`), node-pty prebuilds/sources, every `*.node/.dll/.dylib/.exe`, `.bin`, `*.map`, `*.d.ts`,
   READMEs/CHANGELOGs, and package-root `docs|test|tests|examples|benchmark` dirs.
7. Verify: **zero symlinks** (hard failure otherwise), entry point present, patch markers present.
8. `engine-version.txt` = `<dsh version>-a<sha256 of all inputs, 10 hex>`; deterministic zip; sha256; size report.

Zip layout (= `ENGINE_DIR`):

```
package.json  package-lock.json  engine-version.txt
node_modules/@deepseek-ai/dsh/lib/bin.js       ← entry point
node_modules/@dsh-android/{bundle-android,plugin-device,plugin-guard,plugin-account}/
node_modules/…                                  ← flat npm tree, no symlinks
```

## Profile `android`

Launch: `node <ENGINE_DIR>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile android`.

dsh profiles live in `$DSH_HOME/profiles/<name>/` (`package.json` with `dsh.profile.bundles`, plus the user's
`cordis.patch.yml`). Shipped templates (`web`, `sdk`, …) are created on first use; patch 07 adds `android` to that
template table, so **the profile self-initializes** on first launch — the app needs no init step. The created
`$DSH_HOME/profiles/android/package.json` lists three bundle layers:

1. `@deepseek-ai/dsh-base` — core rows (tools, persistence, approval, web search via `deepseek-official`, …)
2. `@deepseek-ai/dsh-sdk-app` — stdio JSON-RPC SDK server (HMR off, session-title LLM off)
3. `@dsh-android/bundle-android` — this project's layer ([`cordis.patch.yml`](packages/bundle-android/cordis.patch.yml)):
   - persona: "DSH, an AI agent running on the user's own Android phone";
   - `sandbox-policy` → `danger-full-access` with `workspaceRoot = $HOME` (Android has no bwrap/landlock/seatbelt; sandbox-local
     would fail closed), `approval` → `ask`, permission preset `android` = {danger-full-access, ask}. Safety comes from
     approvals (plugin-guard / plugin-device) plus the hardline floor, not from an OS sandbox;
   - disabled: `plugin-manager`, `session-telemetry-otel`, `ptc-runtime`/`workflow-ptc`/`tool-workflow`,
     `skill-office`, `workspace-dependencies`, `tmux-context`. (web-app, HMR, office, voice, inspector, browser-use, ssh and
     experimental bundles are not part of dsh-base/sdk-app at all, so they are simply not stacked);
   - inserts `android-device` (`@dsh-android/plugin-device`), `android-guard` (`@dsh-android/plugin-guard`) and
     `android-account` (`@dsh-android/plugin-account`).
   - Model default stays dsh-base's `deepseek-official` / `deepseek-flash`; DeepSeek web search (`web_search`, `web_fetch`) is on.

Bundles resolve from the dsh installation first, so the bundle and plugins always come from the same engine version.
Because only the bundle *list* is persisted in `DSH_HOME`, later engine versions can change everything inside
`bundle-android` without migrating the profile. User overrides go in `$DSH_HOME/profiles/android/cordis.patch.yml`.
Inspect the composed tree with `… bin.js --profile android --dump-config`.

## Plugins

### `@dsh-android/plugin-device`

- Opens `new net.Socket({ fd: DSH_BRIDGE_FD })` (unref'd — SDK stdin owns process lifetime); newline JSON-RPC both ways.
- `capability/list` at activation (retries with backoff) and again on every `capability/changed` (serialized, coalesced);
  each `ToolDescriptor` becomes a dsh tool (`ctx.tools.register`, raw JSON-Schema `parameters`); changed/vanished tools
  are unregistered first. dsh emits the tool-addition/removal deltas to the model itself. Name collisions with built-in
  dsh tools are skipped with a warning. The description gets a footer with group/risk/unlock so the model can follow the
  priority rule.
- Risk → approval through dsh's real pipeline: a `tools/pre-execute` listener returns `{kind:'ask'}` for
  `write|sensitive|dangerous` (only if the rest of the chain allowed), dsh calls `ctx.approval.request`, the SDK server
  sends `approval/request` to the app. `read` runs directly. Every ask is one-shot (dsh has no "allow for session"; the
  app implements session-allow by auto-answering).
- `capability/invoke { sessionId, callId, tool, args }` — `sessionId` is the **root** SDK session (subagents map to
  their parent chain, so `auto_…` whitelists keep working). Timeout: 120 s, or 15 min for `needsUnlock` tools
  (`invokeTimeoutMs`/`unlockTimeoutMs` in the bundle row). Aborts follow dsh cancellation.
- `ok:false` → tool error `"[<code>] <message>"` (the model sees it, the session logs `isError`).
- Images: stored with `ctx.attachments.saveImage` and returned as dsh image blocks (`{type:'image', attachment}`) when the
  session's model route declares image input; otherwise replaced by a short text note (DeepSeek text models).
- `budget/update` is re-published through the `androidBridge` service consumed by plugin-guard.
- Without `DSH_BRIDGE_FD`: logs once on stderr, registers nothing, everything else works.

### `@dsh-android/plugin-guard`

- **Hardline** ([`hardline.js`](packages/plugin-guard/hardline.js)) for `bash` (`command`), `pwsh`, `terminal_*`: shell-aware
  parse (quotes, `$(…)`, backticks, `sh -c`, `su -c`, `eval`, `xargs`, `env`/`sudo`/`busybox`/`toybox` prefixes, `$EXTERNAL_STORAGE`-style
  variables, globs). Blocks: recursive rm of `/`, `/sdcard`, `/storage[/emulated[/0]]`, `/data`, `/data/media/0`, … and their
  whole contents (`/sdcard/*`); any rm under `/system`, `/vendor`, `/product`, `/apex`, `/proc`, `/dev`, …; `find … -delete` there;
  `dd of=/dev/…`, `> /dev/block/…`, `mkfs*`/`make_ext4fs` on `/dev/…`, partition tools; `reboot`/`shutdown`/`svc power`/
  `setprop sys.powerctl`/reboot intents; fork bombs; decoded payloads piped/evaluated into a shell (`base64 -d | sh`, `xxd -r`,
  `sh -c "$(… base64 -d)"`) and `curl|sh`; `pm uninstall` (and `cmd package uninstall`) of system packages,
  `pm uninstall-system-updates`; `settings delete`; `wipe`, factory-reset intents.
  Enforcement: a **prepended** `tools/pre-execute` listener returns `deny` before anything can `ask`, plus a monotonic
  `ctx.tools.guard` backstop that no listener can override. The model gets a structured refusal:
  `Error: HARDLINE_BLOCKED (rule: rm-protected-root): this command recursively deletes the protected root /sdcard. … Do not retry …`
  (error info `{name:'HardlineBlocked', code:'HARDLINE_BLOCKED'}`).
- **Approval for mutating built-ins**: `askTools` (default `bash`, `write`, `edit`, …) return `ask` on every call — needed
  because there is no OS sandbox. Read-only built-ins (`read`, `glob`, `grep`, `web_search`, …) run directly.
- **Budgets**: limits from `budget/update` (`sessionTokenLimit`, `dailyTokenLimit`, `dailyCostLimitCny`). Usage is metered from
  the durable `assistant/message`/`assistant/attempt` usage that dsh-token-meter also folds (dsh's token-meter emits no events
  in rc.2), attributed to the root session, persisted in `$DSH_HOME/android/budget.json` (local-day rollover). When a limit
  is reached the next step is refused at `agent/pre-step` (`{kind:'reject'}` → the turn ends `blocked`) and the plugin sends
  the additive notification `budget/exceeded { sessionId, kind, used, limit }` on the bridge. Cost is an estimate:
  CNY per 1M tokens `{input: 2, cacheRead: 0.2, output: 3}` by default, overridable per model via the row config `prices`.
- **System prompt** section `android:phone-context` (English): running on the user's phone; tool priority
  system API > intent/deep link > Shizuku > screen automation; approvals; confirm the exact scope of destructive file
  operations; shell is `/system/bin/sh` + toybox; reply in the user's language (`DSH_ANDROID_LOCALE` as the hint).

### `@dsh-android/plugin-account`

DeepSeek account login exactly as dsh does it — the provider rows come unchanged from dsh-base (`deepseek-account` =
`dsh-deepseek-account-platform`: OAuth + PKCE S256 against `https://platform.deepseek.com`, grant in the local credential
store, `resolveToken` for model and web-search requests, profile/balance, 401 → session expired, sign-out with background
revocation; `llm-deepseek-account` = the `deepseek-account` model route). The plugin adds what an SDK composition lacks:

- **Loopback callback server** — provides the `webServer` service the provider registers `/oauth/callback` on:
  `127.0.0.1`, OS-assigned port, opened on the first sign-in (logged as `login callback server on http://127.0.0.1:<port>`).
  dsh's success redirect / failure page / cancel answer on that route are replaced by a local completion page that opens
  the app link `intent://account/login?result=ok|failed|cancelled#Intent;scheme=dsh-android;package=$DSH_ANDROID_PACKAGE;end`,
  so the app's sign-in WebView (or a browser the link was copied to) hands control back to the app (other answers — 400 bad state, 410 stale — pass through).
- **SDK surface** (via patch 10's `sdkExtensions` service): `account/state`, `account/details`, `account/sign-in/start`,
  `account/sign-in/cancel`, `account/sign-out`, `account/tasks/running`; notifications `account.changed`,
  `account.session-expired`, `account.signed-out`, `account.model-sign-in-required`, `account.callback`.
  Contract: [`ENGINE-CONTRACT.md` §2.4](../docs/ENGINE-CONTRACT.md).
- Precedence (dsh `initializeDefaultModel`): the app initializes the SDK with `deepseek-account` only when no API key is
  set; the engine boots without `DEEPSEEK_API_KEY` so the login itself can run.

## Patches

Patch targets must be stable file names (`lib/index.js`, …). `build-engine.sh` refuses a patch whose target is
missing and resolves a content-hashed chunk name (`name-XXXXXXXX.js`) to the single current chunk of that name, so a dsh
upgrade that renames chunks fails loudly or keeps working instead of silently skipping. Patch 08 is a backport of
rc.3 and must be dropped when upgrading (or it double-registers `approval/request`).

All are written so they are inert on desktop Linux unless the corresponding env var is set (the host e2e sets
`DSH_NATIVE_DIR` to exercise the exact Android code paths with host bindings).

| Patch | Package(s) | What / why |
|---|---|---|
| `01-flock-android` | `@deepseek-ai/node-addon-system` | `flock` threw `ERR_FLOCK_UNSUPPORTED_PLATFORM` on `android` (session write lease depends on it). Loads `$DSH_NATIVE_DIR/libdsh_flock.so` via `process.dlopen` (Node's `require` only treats `*.node` as native). |
| `02-require-builtin-expose-internals` | `node-addon-require-builtin` | dsh-app-boot, cordis-plugin-loader and dsh-hmr use this addon only to obtain Node *internal* modules (`internal/modules/esm/loader`, `…/cjs/loader`, `…/helpers`, `…/esm/utils`, `…/esm/resolve`, `internal/bootstrap/realm`) — not reachable through `process.getBuiltinModule`, which serves public builtins only. The engine is started with `--expose-internals` (argv; Node refuses it in `NODE_OPTIONS`), and the patched entry resolves ids with plain `require()`, so no native binding (which decoded Node getter machine code to find struct offsets) is loaded or shipped. Without the flag the upstream native path runs unchanged (except on android, where it throws a clear error). |
| `04-shell-system-bin-sh` | `@deepseek-ai/dsh-bash-local` | The `bash` tool hardcodes `bash -c`; on android it uses `/system/bin/sh -c` (override: `DSH_BASH_SHELL`). No config knob exists for this. (`dsh-bash-sandbox` needs no patch: in `danger-full-access` it short-circuits to the local executor, and in any confining mode `sandbox-local` fails closed on android anyway.) |
| `05-hardlink-copy-fallback` | `dsh-attachment-local`, `dsh-session-persistence-jsonl` | `link(2)` is denied (EACCES/EPERM) in Android app storage. Both packages publish files with `link` (session generations too, not just attachments). Fallback: exclusive copy to a sibling temp + rename, keeping link's EEXIST semantics. |
| `06-ripgrep-from-path` | `dsh-tool-fs-search` | `@vscode/ripgrep` has no android build (and its binaries are pruned): use `DSH_RG_PATH`, else `rg` from `PATH` (app provides `files/bin/rg → librg.so`). |
| `07-android-profile-template` | `dsh-app-boot` | Adds `android` to the shipped profile templates so `--profile android` self-initializes in `DSH_HOME`. |
| `08-sdk-approval-user-questions` | `dsh-sdk-jsonrpc-server` | **rc.2's SDK server has no `approval/request` / `user-question/ask`** (they first appear in 0.2.1-rc.3). Backport of the rc.3 bridge: `initialize { approvalRequests, capabilities.userQuestions }`, server→client `approval/request {sessionId, toolName, callId?, reason?, rootSessionId?}` → `{outcome}` and `user-question/ask` → `{answers}` + `user-question.cancelled`. Without it every approval fails closed (`unavailable`). |
| `09-sharp-wasm-android` | `sharp` | Attachment storage (images from tools *and* prompt images) needs sharp; there is no bionic libvips build. On `android-*` sharp loads its WebAssembly build (`@img/sharp-freebsd-wasm32` → `@img/sharp-wasm32`, added by the build). |
| `10-sdk-extension-methods` | `dsh-sdk-jsonrpc-server` | The SDK server has a closed method switch. Unknown methods now resolve through an optional `sdkExtensions` service (`{ resolve(method) }`) or a `sdk-extensions/resolve` bail listener (after the loader settles, no `initialize` needed), and plugins can emit SDK notifications with `ctx.emit('sdk-extensions/notify', method, params)`. Used by plugin-account (service) and plugin-guard (listener). Upgrade risk: upstream may add its own extension seam or `account/*` names — re-check on every dsh bump. |
| `11-sdk-prompt-time-zone` | `dsh-sdk-jsonrpc-server` | `dsh-time-context` takes the user's zone from a user-rpc message source (the Web host's `clientTimeZone`); SDK prompts carried none, so every time reading said "time zone unavailable, ask the user to clarify dates". SDK prompts now carry `{ rpcId, clientTimeZone }` from the optional `session/prompt` `clientTimeZone` param, else the process `TZ` (the app sets `TZ` / `DSH_ANDROID_TZ`; android-env applies it). |

Not patched (and why):
- `terminal-bash` `DEFAULT_BASH_SHELL=/bin/bash` and `dsh-api-workspace-controller` (no android branch): not mounted by
  dsh-base/sdk-app; if ever enabled, `terminal-bash` has a `shellPath` config and the workspace controller a `documentsDirectory` config.
- `subprocess-local` `/bin/sh` ENOEXEC fallback: only in the Linux-scope runner, which is selected for `platform === 'linux'`
  only; Android spawns through plain `child_process.spawn`. (subprocess-local logs a one-time warning about weaker
  process-tree containment on android — expected.)
- `sandbox-local` (no android chain): handled by profile config (danger-full-access + ask), not by a patch.

## What the Kotlin side must do

- Unzip `engine.zip` to `<filesDir>/engine/<engine-version.txt>/` after checking `engine.sha256`; launch exactly as in the contract.
- Environment per ENGINE-CONTRACT §1. Notes: `DSH_NATIVE_DIR` is **required** on Android (flock loads from
  there; name `libdsh_flock.so`; `libdsh_pty.so` is unused until a terminal row is mounted). Start Node with `--expose-internals` (patch 02). `HOME` must exist (it is the workspace root);
  `TMPDIR` must exist. `files/bin/rg` must be on `PATH` for `grep`/`glob`. Optional: `DSH_TELEMETRY_DISABLED=1`
  (telemetry row is already disabled by the bundle).
- No profile init step: first launch writes `$DSH_HOME/profiles/android/`.
- `initialize` exactly as in the contract (`approvalRequests: true`, `capabilities.userQuestions: true`); answer
  `approval/request` with `{outcome: 'allowed-once' | 'rejected' | 'cancelled'}`. `reason` is human-readable English
  (`Android files tool "file_write" (risk: write) wants to run with {…}` / `bash: <command>`); the app already knows each
  device tool's `risk`/`group` from `capability/list`, and `toolName` + `callId` match the `tool/call` session event.
  Approval requests from subagents carry the child `sessionId` plus `rootSessionId`.
- Send `android/session/permission {sessionId, preset}` before each `session/prompt` and on every permission-mode
  change (ENGINE-CONTRACT §2); without it 仅可查看 refusals cannot name the mode and HOME writes keep asking.
- Set `TZ` and `DSH_ANDROID_TZ` to the device zone (ENGINE-CONTRACT §1).
- Bridge: answer `capability/list` / `capability/invoke`; send `capability/changed` and `budget/update` notifications; ignore
  unknown notifications from Node (`budget/exceeded` is sent when a budget refuses a step).
- `user-question/ask { requestId, sessionId, questions }` → `{ answers: [{ id, selected: string[], custom? }] }`.

## Host verification

```sh
node js/test/host-e2e.mjs            # uses toolchains/out/engine/engine.zip and engine-build/host-native
E2E_PHASES=C,D node js/test/host-e2e.mjs   # account phases only (no API key used)
```

Extracts the zip, sets a fresh `DSH_HOME`, `DSH_NATIVE_DIR=<host-native>` (host bindings under the Android file names, so the
patched loaders are exercised), `DSH_RG_PATH`, and the API key from `~/.config/kevin-secrets/deepseek_api_key` (never printed).
Phase A boots without a bridge; phase B runs a fake bridge on fd 3 (a socketpair from `stdio: 'pipe'`) with three tools
(`file_list` read, `file_write` write, `screen_capture` read+needsUnlock), two `deepseek-flash` prompts and one
budget-refused prompt (no model call). Phase B passes a **test-only** `--patch` overlay that adds a "QA harness" note to the
persona: with the shipped prompt the model (correctly) refuses to even attempt `rm -rf /sdcard`, so the overlay is what makes
it issue the command whose refusal is being verified. It also checks that sharp, when it sees `platform === 'android'`,
loads the WebAssembly build and encodes an image.

Last run (25/25 checks passed, ~37 s, ≈30k tokens):

```
PASS engine.zip extracts with dsh entry point 0.2.1-rc.2-a4c20ecd65e
PASS extracted tree has zero symlinks
PASS sharp loads its WebAssembly build as android-arm64 and encodes
PASS A: android profile boots and initializes (self-initialized profile)
PASS A: profile written to DSH_HOME/profiles/android
PASS A: session working directory is HOME
PASS A: missing bridge logged exactly once
PASS B: capability/list called at startup / capability/changed triggers re-list / device tools registered
     approval/request {"toolName":"file_write","reason":"Android files tool \"file_write\" (risk: write) wants to run with {…}"}
PASS B: model called file_list via capability/invoke {"path":"Download"}; read-risk tool ran without approval
PASS B: write-risk tool produced approval/request; approved write reached the app after approval
PASS B: tool result flowed back into the session; assistant replied
     "Listed your Download folder and saved the three file names to Download/files.txt."
PASS B: model issued bash `rm -rf /sdcard`; hardline blocked it with a structured refusal; never reached approval
     reply: The tool returned: "Error: HARDLINE_BLOCKED (rule: rm-protected-root): this command recursively deletes the protected root /sdcard. …"
PASS B: over-budget turn refused before any model request {"kind":"blocked"}; bridge received budget/exceeded
PASS B: budget ledger metered token usage tokens=29989 costCny≈0.0173
```

Phases C and D (account login, engine started **without** `DEEPSEEK_API_KEY`; engine `0.2.1-rc.2-ad60cb8c03e`, 47/47 checks
with A+B, ~90 s):

- **C — fake Platform** (local HTTP server implementing `auth_init`, `auth_exchange`, `auth_cancel`, `users/current`,
  `get_user_summary`, `users/logout`; `--patch` sets `platformOrigin` to it with `allowLoopbackHttp`): `account/state` before
  `initialize`; start → `authorizeUrl` + loopback `redirect_uri` (PKCE S256, `login_source: web`, `zh_CN`); wrong `state` → 400;
  rejected exchange → `failed` + local failure page with the `intent:` app link; valid callback → PKCE-checked exchange → grant
  stored, success page with `result=ok`; `account/details` profile + balances (`12345.678`, `0E-16`); Platform client headers
  (`web`, `zh_CN`); re-`initialize` onto `deepseek-account`; a prompt with a development grant → `account.model-sign-in-required`;
  HTTP 401 → `account.session-expired` + `account.signed-out`; sign-out → Platform logout with the old token; cancel →
  `auth_cancel`, late callback cannot sign in.
- **D — real `https://platform.deepseek.com`**: real `auth_init` → authorize URL `https://platform.deepseek.com/dsh/authorize?authorize_id=…`
  (TTL ≈ 600 s); forged/incomplete callbacks → 400 while waiting; cancel → `cancelled`. Completing a real login needs a person.

Not covered on the host (needs the phone): the bionic native addons themselves, `/system/bin/sh`, `link(2)` EACCES (the
fallback code path is only reached on Android), image *attachments* reaching an image-capable model (DeepSeek's text models
get the placeholder note), `user-question/ask` round trip; the in-app sign-in WebView → `http://127.0.0.1:<port>/oauth/callback` → app-link return of a real login.

## Size

```
node_modules before prune: 535 MiB   (850 MiB before the zod dedupe)
node_modules after prune:  113 MiB   10 395 files, 0 symlinks
engine.zip:                23.0 MiB  (24 061 178 bytes, sha256 dff78433…a9858d)
largest: openai 10.2 MiB, @img/sharp-wasm32 8.9 MiB, @google/genai 5.8 MiB, @anthropic-ai/sdk 5.3 MiB, zod 5.0 MiB
```

The full per-package list is written to `size-report.txt` next to the zip on every build. Further cuts are possible
(the pi-ai multi-provider SDKs — openai/anthropic/google/aws — are mounted dormant by dsh-base) but would need a profile
row change plus pruning; not done to keep dsh-base intact.

## Known gaps / open issues

- Cold start on the host is ~9 s for `boot + initialize` on first launch (profile init + runtime-resolution scan of
  10k files); AC1 (≤ 4 s) must be measured on the phone. If it is too slow, the next lever is trimming the tree further.
- `sharp` WebAssembly on the phone is untested (works on host with `platform` forced to `android`); it needs `WebAssembly`
  + worker threads in the embedded Node.
- dsh has no "allow for this session" approval outcome; the app must implement it by auto-answering.
- The hardline parser is heuristic (variables assigned inside the same command line, or commands assembled at runtime in a
  way the parser cannot see, are not resolved). It also inspects small scripts run via `sh x.sh`/`./x.sh`/`. x.sh`.

## Contract notes (proposed additions to ENGINE-CONTRACT)

All additive; nothing in the contract had to change for the engine to conform.
- `approval/request` / `user-question/ask` exist in the pinned dsh 0.2.1-rc.2 **only via patch 08** (backported from rc.3).
  Upgrading dsh to ≥ 0.2.1-rc.3 makes the patch unnecessary (drop it then).
- `approval/request` params may carry `rootSessionId` (when the asking agent is a subagent; `sessionId` is then the child).
- `capability/invoke.sessionId` is always the root SDK session id (subagent calls are attributed to their root).
- New Node → App notification on the capability channel: `budget/exceeded { sessionId, kind, used, limit }`
  (`kind` ∈ `sessionTokenLimit | dailyTokenLimit | dailyCostLimitCny`); the refused turn ends with `turn/end` reason `blocked`.
- `DSH_RG_PATH` (optional) overrides the ripgrep path; `DSH_BASH_SHELL` (optional) overrides `/system/bin/sh`.
