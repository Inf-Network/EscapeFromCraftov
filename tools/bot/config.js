// Reads and edits the test server's plugins/Craftov/config.yml line by line, so its comments and
// layout survive (the server's ConfigMigration keys on them). Keys are dotted paths through block
// mappings ("match.wait-seconds", "tiers.NORMAL.entry-fee"); values are written as YAML (numbers,
// booleans and arrays as they are, strings verbatim).
//
//   const config = require('./config')
//   const restore = await config.pin({ 'match.wait-seconds': 3, 'test.enabled': true }, { rcon })
//   try { ... } finally { await restore() }
const fs = require('fs')
const path = require('path')
const env = require('./env')

const FILE = path.join(env.DATA, 'config.yml')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const indentOf = line => line.match(/^ */)[0].length
const blank = line => /^\s*(#.*)?$/.test(line)
const keyOf = line => { const m = line.trim().match(/^(['"]?)([^'":#]+)\1:(\s|$)/); return m ? m[2] : null }
const yaml = v => typeof v === 'string' ? v : JSON.stringify(v).replace(/,/g, ', ')

/** First line after `from` that is not blank or a comment and is indented at most `indent`, or the end. */
function blockEnd (lines, from, indent) {
  for (let i = from; i < lines.length; i++) if (!blank(lines[i]) && indentOf(lines[i]) <= indent) return i
  return lines.length
}

/**
 * Walks `key` down the file. Returns { lines, found, depth, at, indent }: found is the line index of
 * the key (or -1), depth how many parts matched, at the line of the deepest part that matched (-1 for
 * the root) and indent that line's indentation (-1 for the root).
 */
function locate (text, key) {
  const lines = text.split('\n')
  const parts = key.split('.')
  let start = 0
  let end = lines.length
  let at = -1
  let indent = -1
  for (let depth = 0; depth < parts.length; depth++) {
    let child = null
    let hit = -1
    for (let i = start; i < end; i++) {
      if (blank(lines[i])) continue
      const ind = indentOf(lines[i])
      if (child === null) child = ind
      if (ind === child && keyOf(lines[i]) === parts[depth]) { hit = i; break }
    }
    if (hit < 0) return { lines, found: -1, depth, at, indent, child }
    at = hit
    indent = indentOf(lines[hit])
    start = hit + 1
    end = blockEnd(lines, start, indent)
  }
  return { lines, found: at, depth: parts.length, at, indent }
}

/** The raw YAML value of a scalar key, or undefined when the file has no such key. */
function get (text, key) {
  const { lines, found } = locate(text, key)
  if (found < 0) return undefined
  const m = lines[found].match(/^\s*[^:]+:\s*(.*?)\s*(#.*)?$/)
  return m ? m[1] : undefined
}

/** `text` with `key` set to `value`; a missing key (and the sections above it) is added. */
function set (text, key, value) {
  const parts = key.split('.')
  const loc = locate(text, key)
  const lines = loc.lines
  if (loc.found >= 0) {
    const line = lines[loc.found]
    if (blockEnd(lines, loc.found + 1, loc.indent) > loc.found + 1 && !/:\s*\S/.test(line.replace(/#.*$/, ''))) {
      throw new Error(`config.yml: ${key} is a section, not a value`)
    }
    const m = line.match(/^(\s*[^:]+:)\s*(?:[^#]*?)(\s+#.*)?$/)
    lines[loc.found] = `${m[1]} ${yaml(value)}${m[2] || ''}`
    return lines.join('\n')
  }
  if (loc.at >= 0 && /:\s*[^\s#]/.test(lines[loc.at])) {
    throw new Error(`config.yml: ${parts.slice(0, loc.depth).join('.')} is written inline; set ${key} by hand`)
  }
  // The missing parts go right under the deepest section that exists, indented like its other
  // children; a missing top-level section goes at the end of the file.
  const base = loc.child != null ? loc.child : loc.at < 0 ? 0 : loc.indent + 2
  const add = []
  for (let d = loc.depth, ind = base; d < parts.length; d++, ind += 2) {
    add.push(' '.repeat(ind) + parts[d] + ':' + (d === parts.length - 1 ? ' ' + yaml(value) : ''))
  }
  if (loc.at < 0) return text.replace(/\n*$/, '\n') + add.join('\n') + '\n'
  lines.splice(loc.at + 1, 0, ...add)
  return lines.join('\n')
}

/** Reads a key from the server's config.yml (FILE unless `file` is given). */
function read (key, file = FILE) {
  return get(fs.readFileSync(file, 'utf8'), key)
}

/**
 * Sets every key of `values` in the server's config.yml and runs `cv reload` (a stopped server reads
 * them on its next start). Returns an async function that puts the file back as it was and reloads
 * it if the server is still up.
 */
async function pin (values, { file = FILE, rcon = require('./rcon'), log = console.log } = {}) {
  const before = fs.readFileSync(file, 'utf8')
  let text = before
  for (const [key, value] of Object.entries(values)) text = set(text, key, value)
  fs.writeFileSync(file, text)
  const answer = await Promise.race([rcon('cv reload'), sleep(5000).then(() => 'no answer')]).catch(() => 'server down')
  log('config.yml:', Object.entries(values).map(([k, v]) => `${k} ${yaml(v)}`).join(', '), '|', String(answer).replace(/§./g, ''))
  return async () => {
    fs.writeFileSync(file, before)
    const back = await Promise.race([rcon('cv reload'), sleep(5000).then(() => 'no answer')]).catch(() => 'server down')
    log('config.yml put back:', String(back).replace(/§./g, ''))
  }
}

module.exports = { FILE, get, set, read, pin }
