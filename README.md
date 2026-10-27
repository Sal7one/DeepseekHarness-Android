# DSH-Android

A phone agent for Android built on [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh). Say what you want in one sentence; it finds and organises files, opens apps, reads and operates the screen, changes settings and runs automations — and asks before anything risky.

> Community project, not affiliated with or endorsed by DeepSeek.

## How it works

```
:main (Kotlin/Compose)                      :engine (foreground service)
  UI · approvals · quick panel                JNI → libnode.so (Node 24, bionic)
  CapabilityHub (device tools)  ◀─ socket ──    dsh 0.2.1-rc.2 --profile android
  TriggerHub (automations)      ◀─ stdio ──▶    + @dsh-android/plugin-device / plugin-guard
```

- The official dsh runs **unforked** inside the app's own `:engine` process (not proot, not a phantom process). The app talks to it only through dsh's SDK JSON-RPC.
- Device capabilities live in the app and reach dsh over a private socket ([contract](docs/ENGINE-CONTRACT.md)).
- Safety: risk-graded approvals (dangerous actions need biometric/screen-lock), an unoverridable hardline command floor, unattended runs limited to each automation's approved tools, token budgets.

## Repository

| Path | What |
|---|---|
| `app/` | Android app (Kotlin, Compose, Hilt, Room) |
| `js/` | dsh profile, plugins, patches, `build-engine.sh` → `engine.zip` |
| `native/` | Node 24 for Android (shared lib), JNI wrapper, native addons — `native/build.sh` |
| `docs/` | [Design](docs/DESIGN.md), [features](docs/FEATURES.md), [engine contract](docs/ENGINE-CONTRACT.md), [design tokens](docs/design-tokens.md) |

## Build

1. `native/build.sh` → `libnode.so`, `libdshengine.so`, `libc++_shared.so`, `libdsh_{pty,flock,require_builtin}.so`; plus a static `rg` as `librg.so`. Put them in `<nativeOut>/arm64-v8a/`.
2. `js/build-engine.sh` → `engine.zip` + `engine.sha256`. Put both in `<engineOut>/`.
3. `./gradlew :app:assembleRelease -Pdsh.nativeOut=<nativeOut> -Pdsh.engineOut=<engineOut>`

Requires JDK 21, Android SDK (compileSdk 37.2), NDK r29.
