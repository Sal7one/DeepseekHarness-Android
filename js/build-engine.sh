#!/usr/bin/env bash
# Build the DSH-Android engine tree and package it as engine.zip.
#
#   js/build-engine.sh            # -> $OUT_DIR/engine.zip, engine.sha256, engine-version.txt, size-report.txt
#
# Steps: npm ci (exact lockfile, production, no install scripts) -> add our
# packages -> add the sharp WebAssembly build -> apply patches/ -> prune ->
# verify (no symlinks, patches present) -> deterministic zip.
#
# Env overrides: OUT_DIR (default <agent>/toolchains/out/engine),
#                WORK_DIR (default <agent>/toolchains/out/engine-build),
#                TMPDIR (default <agent>/toolchains/tmp; /tmp is not used).
set -euo pipefail

JS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_DIR="${AGENT_DIR:-/home/jzm/.slock/agents/abb9a111-c448-49cf-9c6b-9462eb0e445e}"
OUT_DIR="${OUT_DIR:-$AGENT_DIR/toolchains/out/engine}"
WORK_DIR="${WORK_DIR:-$AGENT_DIR/toolchains/out/engine-build}"
export TMPDIR="${TMPDIR_OVERRIDE:-$AGENT_DIR/toolchains/tmp}"
mkdir -p "$TMPDIR" "$OUT_DIR" "$WORK_DIR"

STAGE="$WORK_DIR/stage"
NM="$STAGE/node_modules"
HOST_NATIVE="$WORK_DIR/host-native"

log() { printf '\033[1m[build-engine]\033[0m %s\n' "$*" >&2; }
die() { printf '[build-engine] ERROR: %s\n' "$*" >&2; exit 1; }

DSH_VERSION="$(node -p 'require(process.argv[1]).dependencies["@deepseek-ai/dsh"]' "$JS_DIR/engine/package.json")"
[[ "$DSH_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "dsh must be pinned to an exact version (got $DSH_VERSION)"

# ── 1. production install from the committed lockfile ────────────────────────
log "npm ci @deepseek-ai/dsh@$DSH_VERSION (production, --ignore-scripts)"
rm -rf "${STAGE:?}"
mkdir -p "$STAGE"
cp "$JS_DIR/engine/package.json" "$JS_DIR/engine/package-lock.json" "$STAGE/"
# Install scripts are not needed: node-pty/koffi pick prebuilds (pruned anyway),
# dsh-subprocess-local's only fixes the macOS spawn-helper mode, protobufjs/genai are no-ops.
(cd "$STAGE" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error)

# ── 2. our packages, copied (never linked) ───────────────────────────────────
log "adding @dsh-android packages"
for pkg in bundle-android plugin-device plugin-guard plugin-account; do
  src="$JS_DIR/packages/$pkg"
  dst="$NM/@dsh-android/$pkg"
  mkdir -p "$dst"
  node -e '
    const [src, dst] = process.argv.slice(1)
    const fs = require("node:fs"), path = require("node:path")
    for (const f of require(path.join(src, "package.json")).files) fs.copyFileSync(path.join(src, f), path.join(dst, f))
  ' "$src" "$dst"
done
# Record them as dependencies of the engine root so npm/dsh resolution sees a consistent tree.
node -e '
  const fs = require("node:fs"), p = process.argv[1]
  const j = JSON.parse(fs.readFileSync(p, "utf8"))
  for (const n of ["bundle-android", "plugin-device", "plugin-guard", "plugin-account"]) j.dependencies["@dsh-android/" + n] = "0.1.0"
  fs.writeFileSync(p, JSON.stringify(j, null, 2) + "\n")
' "$STAGE/package.json"

# ── 3. sharp WebAssembly build (attachment images on android; see patch 09) ──
log "adding @img/sharp-freebsd-wasm32 closure (lockfile-pinned, integrity-checked)"
node "$JS_DIR/scripts/fetch-locked.mjs" "$STAGE/package-lock.json" "$NM" \
  @img/sharp-freebsd-wasm32 @img/sharp-wasm32 @emnapi/runtime tslib

# ── 4. host-native test bindings (only used by test/host-e2e.mjs, never zipped) ─
rm -rf "${HOST_NATIVE:?}"
if [[ "$(uname -s)-$(uname -m)" == "Linux-aarch64" ]]; then
  mkdir -p "$HOST_NATIVE"
  cp "$NM/@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node" "$HOST_NATIVE/libdsh_flock.so"
  cp "$NM/node-pty/prebuilds/linux-arm64/pty.node" "$HOST_NATIVE/libdsh_pty.so"
  log "host test bindings -> $HOST_NATIVE"
fi

# ── 5. patches ───────────────────────────────────────────────────────────────
for p in "$JS_DIR"/patches/*.patch; do
  log "patch $(basename "$p")"
  patch -p1 -d "$NM" --forward --batch --no-backup-if-mismatch --dry-run --silent < "$p" >/dev/null \
    || die "patch $(basename "$p") does not apply cleanly"
  patch -p1 -d "$NM" --forward --batch --no-backup-if-mismatch --silent < "$p"
done
find "$NM" \( -name '*.rej' -o -name '*.orig' \) -print -quit | grep -q . && die "patch left .rej/.orig files"

# ── 6. prune ─────────────────────────────────────────────────────────────────
log "pruning"
before_kb=$(du -sk "$NM" | cut -f1)
rm_pkg() { for d in "$@"; do [[ -e "$NM/$d" ]] && rm -rf "${NM:?}/$d"; done; return 0; }
# Big optional payloads the android profile never loads.
rm_pkg @deepseek-ai/libreoffice-kit-wasm
# Browser client UI and the experimental inspector (Web/desktop surfaces only; boot verified without them).
for d in "$NM"/@deepseek-ai/dsh-client-ui-*; do rm -rf "$d"; done
rm_pkg @deepseek-ai/dsh-web-frontend @deepseek-ai/dsh-experimental-inspector
for d in "$NM"/sherpa-onnx*; do [[ -e "$d" ]] && rm -rf "$d"; done
# Host/other-platform native packages (the app ships android builds in nativeLibraryDir).
for d in "$NM"/@img/sharp-*; do
  case "$(basename "$d")" in sharp-freebsd-wasm32|sharp-wasm32) ;; *) rm -rf "$d" ;; esac
done
for d in "$NM"/@deepseek-ai/node-addon-system-* "$NM"/node-addon-require-builtin-* "$NM"/@koromix/koffi-* \
         "$NM"/@vscode/ripgrep-* "$NM"/@esbuild "$NM"/esbuild; do
  [[ -e "$d" ]] && rm -rf "$d"
done
rm -rf "$NM/@vscode/ripgrep/bin" "$NM/node-pty/prebuilds" "$NM/node-pty/build" "$NM/node-pty/third_party" \
       "$NM/node-pty/src" "$NM/node-pty/deps" "$NM/koffi/build" "$NM/koffi/src"
find "$NM" -type d -name prebuilds -prune -exec rm -rf {} +
find "$NM" -path "$NM/@img/sharp-wasm32" -prune -o -type f \( -name '*.node' -o -name '*.dylib' -o -name '*.dll' -o -name '*.exe' -o -name '*.pdb' \) -exec rm -f {} +
# Tooling links, source maps, type declarations, docs, tests.
find "$NM" -type d -name .bin -prune -exec rm -rf {} +
find "$NM" -type f \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' -o -name '*.tsbuildinfo' \) -delete
find "$NM" -type f \( -iname 'README*' -o -iname 'CHANGELOG*' -o -iname 'HISTORY.md' -o -iname 'CONTRIBUTING*' -o -name 'README.i18n.yaml' \) -delete
for pkgdir in "$NM"/[!@.]* "$NM"/@*/*; do
  for sub in docs doc test tests __tests__ example examples benchmark benchmarks .github; do
    [[ -d "$pkgdir/$sub" ]] && rm -rf "${pkgdir:?}/$sub"
  done
done
find "$NM" -type d -empty -delete
after_kb=$(du -sk "$NM" | cut -f1)

# ── 7. verify ────────────────────────────────────────────────────────────────
links=$(find "$STAGE" -type l | wc -l)
[[ "$links" -eq 0 ]] || { find "$STAGE" -type l | head >&2; die "$links symlinks remain in the engine tree"; }
[[ -f "$NM/@deepseek-ai/dsh/lib/bin.js" ]] || die "missing dsh entry point"
grep -q 'libdsh_flock.so' "$NM/@deepseek-ai/node-addon-system/lib/flock.js" || die "flock patch missing"
grep -q '"@dsh-android/bundle-android"' "$NM/@deepseek-ai/dsh-app-boot/lib/index.js" || die "profile template patch missing"
grep -q 'sdkExtensions' "$NM/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js" || die "SDK extension patch missing"
grep -q 'expose-internals' "$NM/node-addon-require-builtin/lib/index.js" || die "require-builtin patch missing"
[[ -f "$NM/@img/sharp-wasm32/package.json" ]] || die "sharp wasm build missing"
log "no symlinks; entry point and patches verified"

# ── 8. version, zip, digest, report ──────────────────────────────────────────
INPUT_HASH=$( (cd "$JS_DIR" && cat engine/package-lock.json patches/*.patch \
  packages/bundle-android/cordis.patch.yml packages/*/package.json packages/plugin-*/*.js scripts/*.mjs build-engine.sh) | sha256sum | cut -c1-10)
ENGINE_VERSION="${DSH_VERSION}-a${INPUT_HASH}"
printf '%s\n' "$ENGINE_VERSION" > "$STAGE/engine-version.txt"
printf '%s\n' "$ENGINE_VERSION" > "$OUT_DIR/engine-version.txt"
log "engine version $ENGINE_VERSION"

rm -f "$OUT_DIR/engine.zip"
python3 -I "$JS_DIR/scripts/make-zip.py" "$STAGE" "$OUT_DIR/engine.zip"
(cd "$OUT_DIR" && sha256sum engine.zip > engine.sha256)

{
  echo "DSH-Android engine $ENGINE_VERSION (dsh $DSH_VERSION)"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -sm), node $(node -v)"
  echo
  echo "node_modules before prune: $((before_kb / 1024)) MiB"
  echo "node_modules after prune:  $((after_kb / 1024)) MiB"
  echo "files: $(find "$STAGE" -type f | wc -l), symlinks: $links"
  echo "engine.zip: $(du -k "$OUT_DIR/engine.zip" | cut -f1) KiB ($(stat -c %s "$OUT_DIR/engine.zip") bytes)"
  echo "sha256: $(cut -d' ' -f1 "$OUT_DIR/engine.sha256")"
  echo
  echo "largest packages (KiB):"
  (cd "$NM" && du -sk -- [!@]* @*/* 2>/dev/null | sort -rn | head -25)
} > "$OUT_DIR/size-report.txt"
cat "$OUT_DIR/size-report.txt" >&2
log "done: $OUT_DIR/engine.zip"
