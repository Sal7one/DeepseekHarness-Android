# native/ — DSH-Android native layer (android arm64)

Builds everything the `:engine` process needs to run dsh in-process:

| Output | What |
|---|---|
| `libnode.so` | Node.js v24.22.1 as a shared library (`--shared`), bionic / arm64, min API 29 |
| `libdshengine.so` | JNI wrapper: `NodeRuntime.nativeStart(String[] args, int stdin, int stdout, int stderr)` → `node::Start()` |
| `libc++_shared.so` | copied from the NDK r29 sysroot (both `.so`s above and every addon need it) |
| `node_modules/**.node` | node-pty, node-addon-system (flock), node-addon-require-builtin rebuilt for NODE_MODULE_VERSION 137 |

Outputs are **not committed**; they go to `$WORK/out/arm64-v8a/` (default `WORK=<repo>/../toolchains`).

## Reproduce

```sh
NDK=/path/to/android-ndk-r29 native/build.sh            # all stages
native/build.sh toolchain node headers engine addons check   # same, explicit
native/build.sh engine addons check                          # rebuild only the small bits
```

Environment knobs: `NDK`, `WORK`, `OUT`, `API` (29), `JOBS` (nproc), `HOST_CC`/`HOST_CXX`
(clang/clang++), `USE_NDK_CLANG=1` (use the NDK's own x86_64 clang, e.g. on an x86_64 host),
`RECONFIGURE=1` (re-run node's `./configure`). Set `TMPDIR` if `/tmp` is small (defaults to `$WORK/tmp`).

Host requirements: clang/clang++ ≥ 19, ld.lld, llvm-ar/llvm-nm/llvm-strip/llvm-readelf, python3,
ninja, curl, git, patch, xz. Network access to nodejs.org, registry.npmjs.org and github.com.

Stages:

1. **toolchain** — writes `$WORK/android-tc/bin/target-{cc,cxx}` wrappers.
2. **node** — downloads + sha256-checks `node-v24.22.1.tar.xz`, applies `patches/node/*.patch`,
   configures and builds the `libnode` ninja target, strips it into `$OUT/libnode.so`
   (unstripped copy in `$WORK/out/symbols/libnode.so`), copies `libc++_shared.so`.
3. **headers** — `tools/install.py --headers-only` into `$WORK/out/node-headers/include/node`
   (headers + `config.gypi` of *this* build; used for the JNI wrapper and all addons).
4. **engine** — `engine-jni/dshengine.cc` → `$OUT/libdshengine.so`.
5. **addons** — fetches pinned addon sources and builds them with plain clang commands (no node-gyp).
6. **check** — ELF sanity (see below).

## Toolchain

* Host: Linux aarch64 (glibc), Debian clang **19.1.7** + ld.lld 19, ninja, Python 3.13.
* Target: `--target=aarch64-linux-android29 --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot`
  with **NDK r29 (29.0.14206865)** headers/libs and its libc++ (`libc++_shared.so`).
* gyp's SOLINK step calls `$NM -gD -f p` (llvm-nm rejects `-f p`), so `NM` is GNU `nm` when available.
* Host clang ships no Android compiler-rt, so the wrapper uses a synthesized
  `-resource-dir` = host clang's `include/` + the NDK clang's `lib/linux/` (gives
  `libclang_rt.builtins-aarch64-android.a`, `libunwind.a`, `libatomic.a`), with
  `-rtlib=compiler-rt -unwindlib=libunwind -fuse-ld=lld`. Result is equivalent to the NDK
  driver: `NT_ANDROID_TYPE_IDENT` note = API 29, `-z max-page-size=16384` (16 KB pages OK).
* The NDK's prebuilt clang is x86_64-only; it is not used (only its sysroot and runtime libs).
* Node host tools (torque, gen-regexp-special-case, icu tools, mksnapshot, js2c…) build with the
  host clang (`CC_host`/`CXX_host`). Host and target are both arm64, so no V8 simulator is involved.

## Node configure

```
GYP_DEFINES="host_os=linux" ./configure --shared --dest-os=android --dest-cpu=arm64 --cross-compiling \
  --without-npm --without-corepack --with-intl=small-icu --without-node-snapshot --ninja
ninja -C out/Release libnode
```

Everything is bundled (V8, libuv, OpenSSL with arm64 asm, ICU small, zlib, c-ares, nghttp2/3,
ngtcp2, sqlite, brotli, zstd, simdjson, ada, llhttp, uvwasi…). Root CAs are Node's bundled set
(not `--openssl-use-def-ca-store`: bionic has no OpenSSL-format system store).
`--without-node-snapshot` (also implied by `--cross-compiling`) — the startup snapshot/code cache
would require running a target binary on the build host. Cost: slower cold start (~tens of ms).

## Patches (`patches/node/`)

From Termux `packages/nodejs-lts` (Node 24.22.1 recipe), applied with `patch -p1`:

| Patch | Why |
|---|---|
| `termux-avoid-ficlone-ioctl` | libuv: skip FICLONE ioctl on Android (SELinux/seccomp denials) |
| `termux-common.gypi` | drop the android branch that references undefined `android_ndk_path` |
| `termux-deps-uv-src-unix-process.c` | libuv: don't call setgroups/setgid/setuid in child (seccomp kills app processes) |
| `termux-deps-uv-uv.gyp` | include ordering + `__USE_GNU` for bionic |
| `termux-deps-uvwasi-src-uvwasi.c` | enable WASI fd_readdir on Android |
| `termux-deps-v8-src-trap-handler-trap-handler.h` | force V8 wasm trap handler off (nodejs/node#36287) |
| `termux-fix_multiple_definitions` | libuv: duplicate `uv_get_free_memory` on Android |
| `termux-src-node_internals.h` | enable POSIX credentials (`process.getuid()` etc.) on Android |
| `termux-src-node_report.cc` | `process.report` must not crash when `uv_cpu_info` fails (no /proc/stat access) |
| `termux-tools-gyp-pylib-gyp-generator-ninja.py` | separate host/target `gen/` dirs for cross ninja builds |
| `termux-tools-v8_gypfiles-toolchain.gypi` | V8 logs to stdout; no `-mbranch-protection` |
| `termux-tools-v8_gypfiles-v8.gyp` | link `-latomic` on android+clang; add platform-linux.h |
| `dsh-deps-zlib-zlib.gyp` (new) | zlib: use Linux `getauxval` CPU detection on Android instead of NDK `cpu-features.h` (not on the include path; Termux uses shared zlib so never hits this) |
| `dsh-deps-openssl-asm-android-arm64` (new) | OpenSSL: select the `linux-aarch64` asm config for android arm64 — upstream only matches linux/openharmony and otherwise falls through to **linux-x86_64** asm (Termux uses shared OpenSSL; upstream android-configure uses `--openssl-no-asm`) |
| `dsh-src-debug_utils.cc` (adapted from Termux `src-debug_utils.cc`) | Node's own stderr diagnostics go to fd 2 instead of logcat (we redirect fd 2 to the app) unless `NODE_ANDROID_LOG_STDERR_TO_LOGCAT` is defined |

Skipped Termux patches:

| Patch | Why skipped |
|---|---|
| `deps-uv-src-unix-core.c` | only rewrites the `uv_os_tmpdir` fallback to `$PREFIX/tmp`; we keep upstream `/data/local/tmp` — **the app must set `TMPDIR`** to its cache dir |
| `deps-v8-src-flags-flag-definitions.h` | only changes `/tmp` paths in the *non-Android* branch (perf/gc_fake_mmap) |
| `lib-child_process.js` | Termux changes `/system/bin/sh` → `$PREFIX/bin/sh`; upstream already uses `/system/bin/sh` on Android, which is what we want |
| `lib-os.js` | `os.tmpdir()` fallback `/tmp` → `$PREFIX/tmp`; irrelevant once `TMPDIR` is set (it always is in our engine) |
| `node.gyp` + `src-getaddrinfo` | adds a getaddrinfo shim to the `node` *executable* only, and its body is `#ifdef __TERMUX__` — no-op for libnode |
| `test-*` | tests are not built |

## JNI wrapper (`engine-jni/dshengine.cc`)

```
JNIEXPORT jint JNICALL Java_io_github_jackwener_dsh_engine_NodeRuntime_nativeStart(
    JNIEnv*, jclass, jobjectArray args, jint stdinFd, jint stdoutFd, jint stderrFd);
JNIEXPORT jint JNI_OnLoad(JavaVM*, void*);   // stores JavaVM*, returns JNI_VERSION_1_6
```

`dup2()`s each fd ≥ 0 onto 0/1/2, builds `argv = ["node", ...args]` in one contiguous block, and
returns `node::Start(argc, argv)`. A second call in the same process returns -1 (Node can't be
re-initialized): run it in a dedicated process and restart that process.

Java side: `System.loadLibrary("c++_shared"); System.loadLibrary("node"); System.loadLibrary("dshengine");`
(loading `dshengine` alone also works since its DT_NEEDED pulls the others from `nativeLibraryDir`).

## Addons

All addons are linked with `DT_NEEDED libnode.so` (`-Wl,--no-undefined -lnode`). Android's linker
namespaces don't let a dlopen()ed `.node` resolve symbols from libraries loaded by someone else,
so the explicit dependency is required. Pinned sources:

| Addon | Source | Build |
|---|---|---|
| node-pty | npm `node-pty@1.2.0-beta.15` `src/unix/pty.cc` + `node-addon-api@7.1.1` | C++17, `NAPI_CPP_EXCEPTIONS`, no `-lutil` (bionic has `forkpty`/`openpty` in libc) |
| flock | npm `@deepseek-ai/node-addon-system@0.1.2` `src/flock.c` | C11, `NAPI_VERSION=8`; landlock-run not built (device kernel lacks Landlock) |
| require-builtin | github `deepseek-ai/dsh-node-addon-require-builtin` @ `a58d359` (release 0.1.9), `packages/native/src` + `node-addon-api@8.9.0` | **nodeabi** backend (`NARB_BACKEND=2`), product require-builtin (`NARB_PRODUCT=1`), C++20, compiled against our `include/node` |

### Where each JS loader looks on android-arm64 (`process.platform==='android'`, `process.arch==='arm64'`)

Copy `$OUT/node_modules/*` over dsh's `node_modules/` (paths below are relative to `node_modules/`).

**node-pty** (`lib/utils.js` `loadNativeModule('pty')`): tries `../build/Release/pty.node`,
`./build/Release/pty.node`, `build/Debug`, then `prebuilds/android-arm64/pty.node`, relative to
`node-pty/lib/`. We ship `node-pty/build/Release/pty.node`. No `spawn-helper` is needed on Linux/Android.
Works unmodified.

**@deepseek-ai/node-addon-system/flock** (`lib/flock.js`): **rejects `android` today**
(`ERR_FLOCK_UNSUPPORTED_PLATFORM` — only linux/darwin). With the platform check relaxed to also
accept `android`, it computes `filename = report.header.glibcVersionRuntime ? 'glibc/system.node' : 'musl/system.node'`
(bionic → `musl`), then `require.resolve('@deepseek-ai/node-addon-system-android-arm64/package.json')`
→ `bin/musl/system.node`. We ship `@deepseek-ai/node-addon-system-android-arm64/{package.json,bin/musl/system.node}`
so a one-line JS patch (`platform !== 'linux' && platform !== 'darwin' && platform !== 'android'`)
is enough. (Note `process.report.getReport()` is slow on many-core devices; a dedicated android
branch that skips it would be better.)

**node-addon-require-builtin** (via `node-addon-native-custom-loader@0.1.9`
`createEntryApi`): `platformPackageSuffix()` = `android-arm64` (no libc suffix off linux). Probe
order:
1. `require('node-addon-require-builtin-android-arm64')` — package whose `index.js` calls
   `loadPrebuild(__dirname)`, which reads `prebuilds.json` and loads, for `NARB_BACKEND=auto`,
   the `nodeabi`/`node-v137` entry then the `napi`/`napi-v9` entry. We ship this package with
   `prebuilt/android-arm64-nodeabi-v137.node`.
2. `node-addon-require-builtin/../android-arm64` (workspace layout; not used).
3. Local build fallback: `node-addon-require-builtin/build/nodeabi/node-v137-android-arm64/require_builtin.node`,
   then `build/napi/napi-v9-android-arm64/require_builtin.node`. We ship the same binary at the
   nodeabi path too.

The loader validates `getNativeBindingInfo()` → `{product:'require-builtin', backend:'nodeabi', abi:'node-v137'}`.
It also copies the binary to a cache under `os.tmpdir()` before `require()`; set
`NARB_DISABLE_NATIVE_CACHE=1` (or a writable `TMPDIR`) in the engine environment. At runtime
the binding resolves `_ZNK4node14PrincipalRealm22builtin_module_requireEv` via
`dlsym(RTLD_DEFAULT)` — libnode.so exports it, and is in the addon's local group via DT_NEEDED.

## Outputs (arm64-v8a)

Built 2026-10-27 on the host above (`$WORK/out/arm64-v8a/`, all stripped, all ELF64 AArch64,
no PT_INTERP, Android ident note API 29, 16 KB-aligned LOAD segments):

| File | Bytes | NEEDED |
|---|---:|---|
| `libnode.so` | 83,540,184 | libm.so libdl.so liblog.so libc++_shared.so libc.so |
| `libdshengine.so` | 38,992 | libnode.so liblog.so libc++_shared.so libm.so libdl.so libc.so |
| `libc++_shared.so` (NDK r29) | 9,290,184 | libc.so libm.so libdl.so |
| `node_modules/node-pty/build/Release/pty.node` | 70,560 | libnode.so libc++_shared.so libm.so libdl.so libc.so |
| `node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/musl/system.node` (+ `package.json`) | 8,880 | libnode.so libdl.so libc.so |
| `node_modules/node-addon-require-builtin-android-arm64/prebuilt/android-arm64-nodeabi-v137.node` (+ `package.json`, `prebuilds.json`, `index.js`) | 68,472 | libnode.so libc++_shared.so libm.so libdl.so libc.so |
| `node_modules/node-addon-require-builtin/build/nodeabi/node-v137-android-arm64/require_builtin.node` (same binary) | 68,472 | same |

Unstripped libnode (110 MB, for symbolizing tombstones): `$WORK/out/symbols/libnode.so`.
libnode.so's 575 undefined symbols all resolve against the API-29 `libc/libm/libdl/liblog` stubs
and `libc++_shared.so`. libnode exports ~43k dynamic symbols (all of node/V8/OpenSSL/libuv;
Node's own code has default visibility) — that is what lets addons link against it.
`node::PrincipalRealm::builtin_module_require()` compiles to the same `ldr x0,[x0,#off]; ret`
shape as the official linux-arm64 build, which is what require-builtin's getter decoder parses.

Build time on this (shared, heavily loaded) 12-core host: ~6 h for libnode (≈6000 ninja steps,
roughly half of them the host-toolset V8 for mksnapshot/torque); everything else < 2 min.

## Sanity checks (`build.sh check`)

For each `.so`/`.node`: ELF machine AArch64; no `PT_INTERP`; NEEDED ⊆ {libnode.so,
libc++_shared.so, libc.so, libm.so, libdl.so, liblog.so}; `NT_ANDROID_TYPE_IDENT` note present
(API 29); all LOAD segments 16 KB aligned. Exports: `node::Start(int, char**)`,
`node::PrincipalRealm::builtin_module_require()` and N-API from libnode.so; the JNI symbol +
`JNI_OnLoad` from libdshengine.so; `napi_register_module_v1` from every addon. None of this has
been run on a device yet.
