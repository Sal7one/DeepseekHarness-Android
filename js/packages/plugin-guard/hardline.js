// Hardline command floor for shell tools on Android. Pure and synchronous:
// `checkCommand(text)` returns `undefined` (not blocked) or a verdict
// `{ rule, summary, segment }`. Callers deny before approval; nothing can
// override a verdict.
//
// The checker parses shell text approximately (quotes, escapes, operators,
// $(...)/backticks, `sh -c`, `eval`, `xargs`, common prefixes) and errs on the
// side of blocking for the listed categories only.
import { readFileSync, statSync } from 'node:fs'
import path from 'node:path'

/** rm -r of these (or of their whole contents, e.g. `/sdcard/*`) is blocked. */
export const PROTECTED_ROOTS = [
  '/', '/sdcard', '/storage', '/storage/emulated', '/storage/emulated/0', '/storage/self',
  '/storage/self/primary', '/mnt', '/mnt/sdcard', '/mnt/user', '/mnt/user/0', '/mnt/user/0/primary',
  '/data', '/data/media', '/data/media/0', '/data/data', '/data/user', '/data/user/0', '/data/user_de',
  '/data/app', '/data/local', '/data/misc', '/data/system',
]

/** Any rm (recursive or not) of these directories or anything beneath them is blocked. */
export const SYSTEM_DIRS = [
  '/system', '/system_ext', '/vendor', '/product', '/odm', '/oem', '/apex', '/boot', '/bin', '/sbin',
  '/etc', '/proc', '/sys', '/dev', '/init', '/metadata', '/persist', '/linkerconfig', '/data/system',
  '/data/misc', '/data/app', '/efs', '/firmware',
]

const SHELLS = new Set(['sh', 'bash', 'mksh', 'zsh', 'dash', 'ash', 'ksh', 'busybox-sh', 'su'])
const PREFIX_COMMANDS = new Set(['sudo', 'command', 'exec', 'nohup', 'time', 'nice', 'ionice', 'stdbuf', 'chroot', 'doas', 'setsid', 'unbuffer', 'builtin', 'then', 'do', 'else', 'elif', '!'])
const MULTICALL = new Set(['busybox', 'toybox', 'toolbox'])
const DECODERS = /\b(base64\s+(?:[^|;&]*\s)?(?:-d|--decode|-D)\b|base32\s+(?:[^|;&]*\s)?(?:-d|--decode)\b|xxd\s+(?:[^|;&]*\s)?-r\b|openssl\s+(?:enc\s+)?(?:[^|;&]*\s)?(?:-d\b|base64\s+-d)|uudecode\b|basenc\s+[^|;&]*(?:-d|--decode)\b|printf\s+['"]?(?:\\x[0-9a-fA-F]{2}|\\[0-7]{3}){4,})/
const FETCHERS = /\b(curl|wget|nc|ncat|busybox\s+wget|toybox\s+wget)\b/
const SHELL_SINK = /\|\s*(?:\S*\/)?(?:sudo\s+)?(?:sh|bash|mksh|zsh|dash|ash|ksh|su|eval|source|python3?|perl|node)\b/
const SYSTEM_PACKAGE = /^(android|com\.android\..+|com\.google\.android\..+|com\.google\..+|com\.qualcomm\..+|com\.qti\..+|com\.mediatek\..+|com\.miui\..+|com\.xiaomi\..+|com\.huawei\..+|com\.hihonor\..+|com\.honor\..+|com\.samsung\..+|com\.sec\..+|com\.oplus\..+|com\.coloros\..+|com\.oppo\..+|com\.heytap\..+|com\.vivo\..+|com\.bbk\..+|com\.iqoo\..+|com\.oneplus\..+|com\.realme\..+|com\.meizu\..+|com\.lenovo\..+|com\.motorola\..+|com\.zte\..+|com\.nubia\..+|com\.transsion\..+|com\.asus\..+|com\.sony\..+|com\.lge\..+|com\.nothing\..+|org\.codeaurora\..+|vendor\..+)$/
const POWER_COMMANDS = new Set(['reboot', 'shutdown', 'poweroff', 'halt'])
const DISK_TOOLS = new Set(['blkdiscard', 'wipefs', 'sgdisk', 'sfdisk', 'fdisk', 'gdisk', 'parted', 'flash_image', 'erase_image', 'flash_eraseall', 'mtd'])
const MKFS = /^(mkfs(\..+)?|mke2fs|mkswap|make_ext4fs|make_f2fs|mkdosfs|mkfs_vfat|newfs_msdos|mkexfatfs|mkntfs)$/
const BENIGN_DEV = /^\/dev\/(null|zero|random|urandom|stdout|stderr|stdin|tty|fd\/\d+)$/
const MAX_DEPTH = 6

/** Shell-ish tokenizer: returns simple commands as arrays of words plus redirect targets. */
export function splitCommands(text) {
  const commands = []
  const nested = []
  let words = []
  let redirects = []
  let word = ''
  let inWord = false
  let pendingRedirect = false
  const pushWord = () => {
    if (!inWord) return
    if (pendingRedirect) { redirects.push(word); pendingRedirect = false } else words.push(word)
    word = ''
    inWord = false
  }
  const endCommand = () => {
    pushWord()
    if (words.length > 0 || redirects.length > 0) commands.push({ words, redirects })
    words = []
    redirects = []
  }
  const takeSubstitution = (start, open, close) => {
    // returns [content, endIndex] for balanced $( ... ) / ` ... `
    let depth = 1
    let j = start
    for (; j < text.length; j += 1) {
      const c = text[j]
      if (c === '\\') { j += 1; continue }
      if (open !== close && c === open) depth += 1
      else if (c === close) { depth -= 1; if (depth === 0) break }
    }
    return [text.slice(start, j), j]
  }
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i]
    if (c === '\\') {
      if (text[i + 1] === '\n') { i += 1; continue }
      word += text[i + 1] ?? ''
      inWord = true
      i += 1
    } else if (c === "'") {
      const end = text.indexOf("'", i + 1)
      const stop = end < 0 ? text.length : end
      word += text.slice(i + 1, stop)
      inWord = true
      i = stop
    } else if (c === '"') {
      let j = i + 1
      for (; j < text.length && text[j] !== '"'; j += 1) {
        if (text[j] === '\\' && j + 1 < text.length) { word += text[j + 1]; j += 1; continue }
        if (text[j] === '$' && text[j + 1] === '(') {
          const [content, end] = takeSubstitution(j + 2, '(', ')')
          nested.push(content)
          word += '$(' + content + ')'
          j = end
          continue
        }
        if (text[j] === '`') {
          const [content, end] = takeSubstitution(j + 1, '`', '`')
          nested.push(content)
          j = end
          continue
        }
        word += text[j]
      }
      inWord = true
      i = j
    } else if (c === '$' && text[i + 1] === '(') {
      const [content, end] = takeSubstitution(i + 2, '(', ')')
      nested.push(content)
      word += '$(' + content + ')'
      inWord = true
      i = end
    } else if (c === '`') {
      const [content, end] = takeSubstitution(i + 1, '`', '`')
      nested.push(content)
      inWord = true
      i = end
    } else if (c === '#' && !inWord) {
      const end = text.indexOf('\n', i)
      i = end < 0 ? text.length : end - 1
    } else if (c === ';' || c === '\n' || c === '|' || c === '&' || c === '(' || c === ')' || c === '{' || c === '}') {
      if ((c === '{' || c === '}') && inWord) { word += c; continue }
      endCommand()
    } else if (c === '>' || c === '<') {
      pushWord()
      // fd-number prefixes such as 2> are absorbed by the previous word, harmless here
      while (text[i + 1] === '>' || text[i + 1] === '&' || text[i + 1] === '|') i += 1
      if (c === '>') pendingRedirect = true
    } else if (c === ' ' || c === '\t' || c === '\r') {
      pushWord()
    } else {
      word += c
      inWord = true
    }
  }
  endCommand()
  return { commands, nested }
}

function expandVars(word, env) {
  return word
    .replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)(?::?[-=?+][^}]*)?\}/g, (m, k) => env[k] ?? m)
    .replace(/\$([A-Za-z_][A-Za-z0-9_]*)/g, (m, k) => env[k] ?? m)
    .replace(/^~(?=\/|$)/, env.HOME ?? '~')
}

function baseName(word) {
  return word.slice(word.lastIndexOf('/') + 1)
}

/** Strip launcher prefixes; returns the remaining argv. */
function unwrap(argv) {
  let a = argv
  for (let guard = 0; guard < 10 && a.length > 0; guard += 1) {
    const head = baseName(a[0])
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(a[0])) { a = a.slice(1); continue }
    if (head === 'env') { a = a.slice(1); while (a.length && (/^-/.test(a[0]) || /^[A-Za-z_][A-Za-z0-9_]*=/.test(a[0]))) a = a.slice(1); continue }
    if (head === 'timeout') { a = a.slice(1); while (a.length && /^-/.test(a[0])) a = a.slice(1); a = a.slice(1); continue }
    if (head === 'nice' || head === 'ionice' || head === 'stdbuf') { a = a.slice(1); while (a.length && /^-/.test(a[0])) a = a.slice(a[0].length === 2 && /^-[nc]$/.test(a[0]) ? 2 : 1); continue }
    if (PREFIX_COMMANDS.has(head)) { a = a.slice(1); while (a.length && /^-/.test(a[0]) && head !== '!') a = a.slice(1); continue }
    if (MULTICALL.has(head) && a.length > 1) { a = a.slice(1); continue }
    if (head === 'xargs') { a = a.slice(1); while (a.length && /^-/.test(a[0])) a = a.slice(/^-[IdEnLPs]$/.test(a[0]) ? 2 : 1); continue }
    break
  }
  return a
}

/** Normalize a path operand; returns { full, wildcardParent } where wildcardParent is set for `<dir>/<glob>`. */
function normalizeTarget(target, cwd) {
  if (target === '' || target === '--') return undefined
  const abs = target.startsWith('/') ? target : path.posix.join(cwd || '/', target)
  const norm = path.posix.normalize(abs).replace(/\/+$/, '') || '/'
  const globAt = norm.search(/[*?[]/)
  if (globAt < 0) return { full: norm }
  // Static directory before the first glob segment.
  const parent = norm.slice(0, norm.lastIndexOf('/', globAt)) || '/'
  return { full: norm, wildcardParent: parent }
}

const isUnder = (p, dir) => dir === '/' ? true : p === dir || p.startsWith(dir + '/')

function verdict(rule, summary, segment) {
  return { rule, summary, segment: segment.length > 300 ? segment.slice(0, 300) + '…' : segment }
}

function checkRm(argv, ctx, segment) {
  let recursive = false
  const targets = []
  let endOfOptions = false
  for (const arg of argv.slice(1)) {
    if (!endOfOptions && arg === '--') { endOfOptions = true; continue }
    if (!endOfOptions && /^--/.test(arg)) { if (arg === '--recursive') recursive = true; continue }
    if (!endOfOptions && /^-[A-Za-z]+$/.test(arg)) { if (/[rR]/.test(arg)) recursive = true; continue }
    targets.push(arg)
  }
  for (const raw of targets) {
    const t = normalizeTarget(raw, ctx.cwd)
    if (t === undefined) continue
    for (const dir of SYSTEM_DIRS) {
      if (dir !== '/' && (isUnder(t.full, dir) || (t.wildcardParent !== undefined && isUnder(t.wildcardParent, dir)))) {
        return verdict('rm-system-dir', `deletes ${t.full}, inside the protected system directory ${dir}`, segment)
      }
    }
    if (!recursive) continue
    if (PROTECTED_ROOTS.includes(t.full)) return verdict('rm-protected-root', `recursively deletes the protected root ${t.full}`, segment)
    if (t.wildcardParent !== undefined && PROTECTED_ROOTS.includes(t.wildcardParent)) {
      return verdict('rm-protected-root', `recursively deletes the contents of the protected root ${t.wildcardParent} (${t.full})`, segment)
    }
    // A glob in the first segment (e.g. /s*) could match any root.
    if (t.wildcardParent === '/') return verdict('rm-protected-root', `recursively deletes root-level paths matching ${t.full}`, segment)
  }
  return undefined
}

function checkFind(argv, ctx, segment) {
  const deletes = argv.includes('-delete') || argv.some((a, i) => (a === '-exec' || a === '-execdir' || a === '-ok') && /(^|\/)rm$/.test(argv[i + 1] ?? ''))
  if (!deletes) return undefined
  for (const arg of argv.slice(1)) {
    if (arg.startsWith('-') || arg === '!' || arg === '(' || arg === ')') break
    const t = normalizeTarget(arg, ctx.cwd)
    if (t === undefined) continue
    if (PROTECTED_ROOTS.includes(t.full) || SYSTEM_DIRS.some(dir => isUnder(t.full, dir))) {
      return verdict('find-delete-protected', `find deletes files under the protected path ${t.full}`, segment)
    }
  }
  return undefined
}

/** Read a small text script (≤ 256 KiB) referenced by a command and check its content. */
function checkScriptFile(file, ctx, depth) {
  const read = ctx.readFile ?? (p => {
    const st = statSync(p)
    if (!st.isFile() || st.size > 256 * 1024) return undefined
    return readFileSync(p, 'utf8')
  })
  let text
  try {
    text = read(file.startsWith('/') ? file : path.posix.join(ctx.cwd || '/', file))
  } catch {
    return undefined
  }
  if (typeof text !== 'string' || text.includes('\u0000')) return undefined
  const v = checkText(text, ctx, depth + 1)
  return v === undefined ? undefined : { ...v, summary: `${v.summary} (inside the script ${file})` }
}

function blockDeviceTarget(target) {
  return target.startsWith('/dev/') && !BENIGN_DEV.test(target)
}

function checkSimple(argv, redirects, ctx, segment, depth) {
  for (const r of redirects) {
    if (blockDeviceTarget(r)) return verdict('write-block-device', `redirects output onto the device node ${r}`, segment)
  }
  const a = unwrap(argv)
  if (a.length === 0) return undefined
  const cmd = baseName(a[0])
  // Nested shells: sh -c '...', su -c '...', su 0 sh -c ...
  if (SHELLS.has(cmd)) {
    const ci = a.findIndex((w, i) => i > 0 && (w === '-c' || /^-[a-z]*c$/.test(w)))
    if (ci > 0 && a[ci + 1] !== undefined) return checkText(a[ci + 1], ctx, depth + 1)
    if (cmd === 'su') {
      const rest = a.slice(1).filter(w => !/^-/.test(w) && !/^\d+$/.test(w) && w !== 'root')
      if (rest.length > 0) return checkText(rest.join(' '), ctx, depth + 1)
      return undefined
    }
    // `sh ./script.sh`: inspect the script the shell is about to run.
    const script = a.slice(1).find(w => !w.startsWith('-'))
    return script === undefined ? undefined : checkScriptFile(script, ctx, depth)
  }
  if (cmd === 'source' || cmd === '.') return a[1] === undefined ? undefined : checkScriptFile(a[1], ctx, depth)
  // `./script.sh` / `/path/to/script`: inspect small text scripts before they run.
  if (a[0].includes('/') || /\.sh$/.test(a[0])) {
    const v = checkScriptFile(a[0], ctx, depth)
    if (v !== undefined) return v
  }
  if (cmd === 'eval') return checkText(a.slice(1).join(' '), ctx, depth + 1)
  if (cmd === 'rm' || cmd === 'unlink' || cmd === 'rmdir' || cmd === 'shred' || cmd === 'srm') return checkRm(cmd === 'rm' || cmd === 'shred' || cmd === 'srm' ? a : ['rm', ...a.slice(1)], ctx, segment)
  if (cmd === 'find') return checkFind(a, ctx, segment)
  if (cmd === 'dd') {
    const of = a.find(w => w.startsWith('of='))
    if (of !== undefined && blockDeviceTarget(of.slice(3))) return verdict('dd-block-device', `dd writes to the device node ${of.slice(3)}`, segment)
    return undefined
  }
  if (MKFS.test(cmd)) {
    if (a.slice(1).some(w => w.startsWith('/dev/'))) return verdict('mkfs-block-device', `${cmd} formats a block device`, segment)
    return undefined
  }
  if (DISK_TOOLS.has(cmd)) return verdict('disk-tool', `${cmd} rewrites disk partitions or flash`, segment)
  if (POWER_COMMANDS.has(cmd)) return verdict('power', `${cmd} powers off or restarts the phone`, segment)
  if (cmd === 'svc' && a[1] === 'power' && /^(reboot|shutdown)$/.test(a[2] ?? '')) return verdict('power', `svc power ${a[2]} restarts or powers off the phone`, segment)
  if (cmd === 'setprop' && /^sys\.powerctl$|^ctl\.(start|restart)$/.test(a[1] ?? '') && (a[1] === 'sys.powerctl' || /reboot|shutdown/.test(a[2] ?? ''))) return verdict('power', `setprop ${a[1]} restarts or powers off the phone`, segment)
  if (cmd === 'cmd' && a[1] === 'power' && /^(reboot|shutdown|reboot-safe-mode)$/.test(a[2] ?? '')) return verdict('power', `cmd power ${a[2]} restarts or powers off the phone`, segment)
  if (cmd === 'am' && a.some(w => /(android\.intent\.action\.(REBOOT|ACTION_REQUEST_SHUTDOWN|REQUEST_SHUTDOWN|MASTER_CLEAR|FACTORY_RESET))|com\.android\.internal\.intent\.action\.REQUEST_SHUTDOWN/.test(w))) {
    return verdict(a.some(w => /MASTER_CLEAR|FACTORY_RESET/.test(w)) ? 'wipe' : 'power', 'sends a reboot/shutdown/factory-reset intent', segment)
  }
  if (cmd === 'wipe') return verdict('wipe', 'wipe erases device data', segment)
  if (cmd === 'recovery' && a.some(w => /wipe/.test(w))) return verdict('wipe', 'recovery wipe erases device data', segment)
  if ((cmd === 'settings' && a[1] === 'delete') || (cmd === 'cmd' && a[1] === 'settings' && a[2] === 'delete')) return verdict('settings-delete', 'deletes a system setting', segment)
  if (cmd === 'content' && a[1] === 'delete' && a.some(w => /content:\/\/settings/.test(w))) return verdict('settings-delete', 'deletes a system setting through the settings provider', segment)
  const pmArgs = cmd === 'pm' ? a.slice(1) : cmd === 'cmd' && a[1] === 'package' ? a.slice(2) : undefined
  if (pmArgs !== undefined) {
    if (pmArgs[0] === 'uninstall-system-updates') return verdict('pm-uninstall-system', 'removes system app updates', segment)
    if (pmArgs[0] === 'uninstall') {
      const pkg = pmArgs.slice(1).filter((w, i, arr) => !/^-/.test(w) && arr[i - 1] !== '--user' && arr[i - 1] !== '--versionCode').pop()
      if (pkg !== undefined && SYSTEM_PACKAGE.test(pkg)) return verdict('pm-uninstall-system', `uninstalls the system package ${pkg}`, segment)
    }
  }
  return undefined
}

/**
 * Check one shell command string.
 * @param {string} text - the command as the model wrote it.
 * @param {{ cwd?: string, env?: Record<string,string|undefined> }} [options]
 */
export function checkCommand(text, options = {}) {
  if (typeof text !== 'string' || text.length === 0) return undefined
  const env = {
    EXTERNAL_STORAGE: '/sdcard', ANDROID_STORAGE: '/storage', ANDROID_DATA: '/data', ANDROID_ROOT: '/system',
    SECONDARY_STORAGE: '/storage', ...process.env, ...options.env,
  }
  return checkText(text, { cwd: options.cwd ?? env.HOME ?? '/', env, readFile: options.readFile }, 0)
}

function checkText(text, ctx, depth) {
  if (depth > MAX_DEPTH) return undefined
  // Whole-text patterns that span pipes.
  const compact = text.replace(/\\\n/g, ' ')
  if (/(^|[\s;&|(])([A-Za-z_:.][\w:.]*)\s*\(\)\s*\{[^}]*\2\s*\|\s*\2\s*&/.test(compact) || /:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:/.test(compact)) {
    return verdict('fork-bomb', 'defines a self-replicating fork bomb', compact)
  }
  if (DECODERS.test(compact) && (SHELL_SINK.test(compact) || /\b(eval|sh|bash|mksh|su)\b[^|;&]*\$\(/.test(compact) || /\|\s*(?:\S*\/)?(?:xargs\s+)?(?:sh|bash|mksh)\b/.test(compact))) {
    return verdict('encoded-payload', 'decodes an encoded payload and executes it with a shell', compact)
  }
  if (FETCHERS.test(compact) && SHELL_SINK.test(compact.slice(compact.search(FETCHERS)))) {
    return verdict('remote-payload', 'pipes downloaded content straight into a shell', compact)
  }
  const { commands, nested } = splitCommands(compact)
  for (const inner of nested) {
    const v = checkText(inner, ctx, depth + 1)
    if (v !== undefined) return v
  }
  // `... /sdcard ... | xargs rm -r`: targets arrive on stdin, so judge the literals upstream.
  if (/\bxargs\b[^|;&]*\brm\s+(?:-\S+\s+)*-[A-Za-z]*[rR]/.test(compact) || /\bxargs\b[^|;&]*\brm\s+[^|;&]*--recursive/.test(compact)) {
    for (const { words } of commands) {
      for (const w of words) {
        const t = normalizeTarget(expandVars(w, ctx.env), ctx.cwd)
        if (w.startsWith('/') || w.startsWith('$') || w.startsWith('~')) {
          if (t !== undefined && (PROTECTED_ROOTS.includes(t.full) || SYSTEM_DIRS.some(dir => isUnder(t.full, dir)))) {
            return verdict('rm-protected-root', `pipes the protected path ${t.full} into a recursive rm`, compact)
          }
        }
      }
    }
  }
  for (const { words, redirects } of commands) {
    const argv = words.map(w => expandVars(w, ctx.env))
    const segment = words.join(' ')
    const v = checkSimple(argv, redirects.map(r => expandVars(r, ctx.env)), ctx, segment, depth)
    if (v !== undefined) return v
  }
  return undefined
}

/** The model-facing refusal text (also used as the denial reason). */
export function refusalText(v) {
  return `HARDLINE_BLOCKED (rule: ${v.rule}): this command ${v.summary}. ` +
    'DSH-Android never runs it — not with user approval, not in any permission mode. ' +
    'Do not retry it or work around it with another command or tool. Tell the user it was blocked, and if they ' +
    'need something narrower (for example deleting specific files), propose that instead. ' +
    `Blocked segment: ${JSON.stringify(v.segment)}`
}
