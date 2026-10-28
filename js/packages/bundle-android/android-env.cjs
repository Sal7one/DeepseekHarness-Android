'use strict'
// Preloaded by the app (`node --require …/android-env.cjs …/dsh/lib/bin.js`, EngineRuntimeEnv.nodeArgv).
//
// Android app processes are forked from zygote and run with AT_SECURE=1 in their auxv. Node then
// treats the process as privileged and its native SafeGetenv() refuses every environment lookup
// that goes through it, even though the variables are set (process.env still sees them). On the
// phone that silently disables:
//   - os.tmpdir() (TMPDIR)        -> '/tmp', which does not exist: spill-local's mkdtemp failed
//                                    with EACCES on every start, so the plugin never activated
//   - NODE_COMPILE_CACHE          -> no V8 code cache
//   - NODE_EXTRA_CA_CERTS         -> the exported Android CA store (user/corporate CAs) unused
//   - NODE_OPTIONS                -> handled by the app, which passes those flags in argv instead
// This preload restores the first three from process.env before dsh loads. It changes nothing
// when Node already honours the environment (host runs), unless DSH_ANDROID_ENV_FORCE=1 (tests).

const os = require('node:os')
const env = process.env
const tmp = env.TMPDIR
const gated = env.DSH_ANDROID_ENV_FORCE === '1' || (tmp !== undefined && tmp !== '' && os.tmpdir() !== tmp.replace(/(.)\/$/, '$1'))

if (gated) {
  const restored = []
  if (tmp) {
    const dir = tmp.length > 1 && tmp.endsWith('/') ? tmp.slice(0, -1) : tmp
    os.tmpdir = function tmpdir() { return dir }
    // `import { tmpdir } from 'node:os'` bindings follow the patched CommonJS export.
    require('node:module').syncBuiltinESMExports()
    restored.push('TMPDIR')
  }
  if (env.NODE_COMPILE_CACHE) {
    try {
      const r = require('node:module').enableCompileCache(env.NODE_COMPILE_CACHE)
      if (r && r.status !== undefined) restored.push('NODE_COMPILE_CACHE')
    } catch (error) {
      process.stderr.write(`android-env: compile cache unavailable: ${error && error.message}\n`)
    }
  }
  if (env.NODE_EXTRA_CA_CERTS) {
    try {
      const tls = require('node:tls')
      const pem = require('node:fs').readFileSync(env.NODE_EXTRA_CA_CERTS, 'utf8')
      const extra = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g) ?? []
      if (extra.length > 0) {
        tls.setDefaultCACertificates([...new Set([...tls.getCACertificates('default'), ...extra])])
        restored.push(`NODE_EXTRA_CA_CERTS(${extra.length})`)
      }
    } catch (error) {
      process.stderr.write(`android-env: extra CA certificates not loaded: ${error && error.message}\n`)
    }
  }
  process.stderr.write(`android-env: Node ignores the environment (AT_SECURE); restored ${restored.join(', ') || 'nothing'}\n`)
}
