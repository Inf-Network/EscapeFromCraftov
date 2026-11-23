// Runs every bot script against the local test server, one after another, and says which ones fail
// and which ones flake (pass in one round and fail in another).
//
//   node suite.js                       every script once
//   node suite.js --repeat 3            three rounds of every script
//   node suite.js --only fire,escort    only these (a name, or a name:variant from the list below)
//   node suite.js --skip luck,market    all but these
//   node suite.js --list                the scripts, their arguments and the config each one gets
//
// env: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR (env.js), as every script takes them.
//
// Before each script the server's plugins/Craftov/config.yml is put back as the suite found it, with
// BASE and the script's own `config` (its header's "Server config") set on top, and `cv reload` run;
// at the end the file is put back as it was found. The server must be a throwaway local one: several
// scripts stop, start and kill it (they say so), and when one leaves it down the suite starts it again
// (server.js). Each run's output goes to OUT/<round>/<name>.log (OUT defaults to tools/bot/out/suite-
// <date>), and summary.json there holds every result. A script passes when it exits with 0.
// Exit code: the number of scripts that failed in any round.
const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')
const env = require('./env')
const config = require('./config')
const server = require('./server')
const rcon = require('./rcon')

// What every script gets unless its entry says otherwise: queues launch in 3 s, and the proving
// ground (a test map) is open to bots that are not op.
const BASE = { 'test.enabled': true, 'match.wait-seconds': 3 }
// No soldiers, operators or events: for scripts that stand around in a raid and must not be shot.
const QUIET = { 'cinematic.enabled': false, 'ai.count-multiplier': 0, 'ai.operator-chance': [0, 0, 0], 'events.per-raid': 0 }

/**
 * name, file (default name.js), args, env, config (on top of BASE), minutes (timeout, default 30).
 * Scripts that are not tests: env, rcon, config, insurance, server, common, suite (helpers) and
 * puppet (a second player for tools/shots).
 */
const SCRIPTS = [
  { name: 'accuracy', config: { debug: true } },
  { name: 'ads' },
  { name: 'aibehaviour', config: { 'match.wait-seconds': 2 }, minutes: 40 },
  { name: 'aidamage', config: { 'match.wait-seconds': 2 } },
  { name: 'armor' },
  { name: 'carry' },
  { name: 'carryui', minutes: 40 },
  { name: 'credit', minutes: 40 },
  { name: 'cursor' },
  { name: 'deploy', config: { 'match.wait-seconds': 2, 'match.join-window-seconds': -1, 'raid.reconnect-seconds': 30, 'tiers.NORMAL.entry-fee': 1000 }, env: { RECONNECT: 30 }, minutes: 40 },
  { name: 'disconnect', config: { 'match.wait-seconds': 2, 'raid.reconnect-seconds': 30 }, env: { RECONNECT: 30 }, minutes: 40 },
  { name: 'doors' },
  { name: 'econ' },
  { name: 'economy', minutes: 60 },
  { name: 'editor', config: { 'match.wait-seconds': 5 }, minutes: 40 },
  { name: 'escort' },
  { name: 'escortbusy' },
  { name: 'fire' },
  { name: 'grenade' },
  { name: 'guide', config: QUIET },
  { name: 'gunaudio' },
  { name: 'gunfeel', config: { debug: true } },
  { name: 'hitreg', config: { debug: true, 'ai.count-multiplier': 1.25 }, minutes: 40 },
  { name: 'hits' },
  { name: 'inspect' },
  ...['reload', 'unknown', 'raid', 'cursorpack', 'missing', 'preset', 'season'].map(s => ({ name: 'itemloss:' + s, file: 'itemloss.js', args: [s] })),
  ...['end', 'stop', 'offline', 'two'].map(v => ({ name: 'itemloss:insure-' + v, file: 'itemloss.js', args: ['insure', v] })),
  { name: 'knifevalue' },
  { name: 'launch', minutes: 40 },
  { name: 'loopholes', minutes: 40 },
  { name: 'luck', minutes: 90 },
  { name: 'mail' },
  { name: 'market', minutes: 45 },
  { name: 'marketio', minutes: 45 },
  { name: 'match', config: { 'match.wait-seconds': 6, 'match.max-squads': 4, 'rank.min-level': 1 }, env: { MATCH_WAIT: 6 }, minutes: 40 },
  { name: 'meds' },
  { name: 'melee' },
  { name: 'onboarding', config: { 'test.enabled': false } },
  { name: 'party', config: { 'match.wait-seconds': 5, 'raid.reconnect-seconds': 10 }, env: { RECONNECT: 10, WAIT: 5 }, minutes: 40 },
  { name: 'persistence', minutes: 60 },
  { name: 'proving' },
  { name: 'range' },
  { name: 'reload' },
  { name: 'replayhit', config: { ...QUIET, 'match.wait-seconds': 6 } },
  { name: 'revive', config: { debug: true }, minutes: 40 },
  { name: 'security' },
  { name: 'shutdown', args: ['--crash'], minutes: 40 },
  { name: 'skins' },
  { name: 'spectate', config: { 'match.wait-seconds': 2, 'tiers.NORMAL.entry-fee': 1000 }, minutes: 40 },
  { name: 'squad' },
  { name: 'stacking', config: QUIET },
  { name: 'strobe' },
  { name: 'swap', config: { debug: true } },
  { name: 'test' },
  { name: 'testmap', minutes: 40 },
  { name: 'training' },
  { name: 'trigger', config: { debug: true } },
  { name: 'ui' },
  { name: 'vanilla' }
]

// ------------------------------------------------------------------ options

const argv = process.argv.slice(2)
const opt = (flag, fallback) => { const i = argv.indexOf(flag); return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback }
const ROUNDS = Number(opt('--repeat', 1))
const only = opt('--only', '').split(',').filter(Boolean)
const skip = opt('--skip', '').split(',').filter(Boolean)
const matches = (entry, names) => names.some(n => entry.name === n || entry.name.split(':')[0] === n)
const chosen = SCRIPTS.filter(e => (!only.length || matches(e, only)) && !matches(e, skip))
const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 16)
const OUT = path.resolve(opt('--out', path.join(__dirname, 'out', 'suite-' + stamp)))

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const clock = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${clock()}]`, ...a)
const yaml = v => typeof v === 'string' ? v : JSON.stringify(v).replace(/,/g, ', ')

if (argv.includes('--list')) {
  for (const e of SCRIPTS) {
    const cfg = Object.entries({ ...BASE, ...(e.config || {}) }).map(([k, v]) => `${k} ${yaml(v)}`).join(', ')
    console.log(`${e.name.padEnd(22)} node ${e.file || e.name + '.js'} ${(e.args || []).join(' ')}`.padEnd(60) +
      `${Object.entries(e.env || {}).map(([k, v]) => `${k}=${v}`).join(' ')}  [${cfg}] ${e.minutes || 30} min`)
  }
  process.exit(0)
}

// ------------------------------------------------------------------ server

/** Makes sure the server answers RCON: waits for one that is starting, starts one that is not there. */
async function ensureUp () {
  if (await rcon('list').then(() => true, () => false)) return true
  if (await server.running()) {
    log('waiting for the server to answer')
    if (await server.up(0, 180000)) return true
    log('the server does not answer: stopping it')
    await server.stop({ kill: server.pids() !== null })
  }
  log('starting the server')
  const since = Date.now()
  server.launch(log)
  return server.up(since, 240000)
}

let found = null
/** config.yml as found, with BASE and the entry's config on top, then `cv reload`. */
async function configure (entry) {
  let text = found
  for (const [key, value] of Object.entries({ ...BASE, ...(entry.config || {}) })) text = config.set(text, key, value)
  fs.writeFileSync(config.FILE, text)
  await Promise.race([rcon('cv reload'), sleep(10000)]).catch(() => {})
}

/** Runs one script to its end (or its timeout); resolves { code, seconds, passes, fails }. */
function runOne (entry, file) {
  return new Promise(resolve => {
    const out = fs.openSync(file, 'w')
    const started = Date.now()
    const child = spawn(process.execPath, [entry.file || entry.name + '.js', ...(entry.args || [])], {
      cwd: __dirname,
      stdio: ['ignore', out, out],
      env: {
        ...process.env,
        PORT: String(env.PORT),
        RCON_PORT: String(env.RCON_PORT),
        RCON_PASSWORD: env.RCON_PASSWORD,
        SERVER_DIR: env.SERVER_DIR,
        ...Object.fromEntries(Object.entries(entry.env || {}).map(([k, v]) => [k, String(v)]))
      }
    })
    let timedOut = false
    const limit = (entry.minutes || 30) * 60000
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGTERM')
      setTimeout(() => child.kill('SIGKILL'), 5000)
    }, limit)
    child.on('exit', (code, signal) => {
      clearTimeout(timer)
      fs.closeSync(out)
      const text = fs.readFileSync(file, 'utf8')
      const lines = text.split('\n')
      // The failed checks, without the summary lines some scripts end with ("FAILED 2: …", "3 FAILED").
      const fails = lines.filter(l => /\bFAIL\b/.test(l) && !/FAIL(ED)? \d+:|\d+ FAILED/.test(l)).map(l => l.replace(/^\[[^\]]*\]\s*/, '').slice(0, 300))
      resolve({
        code: timedOut ? 'timeout' : code === null ? signal : code,
        seconds: Math.round((Date.now() - started) / 1000),
        passes: lines.filter(l => /\bPASS\b/.test(l)).length,
        fails
      })
    })
  })
}

// ------------------------------------------------------------------ main

;(async () => {
  fs.mkdirSync(OUT, { recursive: true })
  log(`${chosen.length} scripts x ${ROUNDS} round(s) on port ${env.PORT} (RCON ${env.RCON_PORT}), server ${env.SERVER_DIR}; output in ${OUT}`)
  if (!await ensureUp()) { log('the server did not come up'); process.exit(100) }
  found = fs.readFileSync(config.FILE, 'utf8')
  const results = [] // { round, name, code, seconds, passes, fails }
  const save = () => fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify({ port: env.PORT, rounds: ROUNDS, results }, null, 2))
  let stopping = false
  process.on('SIGINT', () => { stopping = true; log('stopping after this script (Ctrl-C again to quit at once)'); process.once('SIGINT', () => process.exit(130)) })
  try {
    for (let round = 1; round <= ROUNDS && !stopping; round++) {
      fs.mkdirSync(path.join(OUT, String(round)), { recursive: true })
      for (const entry of chosen) {
        if (stopping) break
        if (!await ensureUp()) {
          results.push({ round, name: entry.name, code: 'no server', seconds: 0, passes: 0, fails: ['the server did not come up'] })
          continue
        }
        await configure(entry)
        const file = path.join(OUT, String(round), entry.name.replace(/:/g, '-') + '.log')
        log(`round ${round}: ${entry.name} ...`)
        const r = await runOne(entry, file)
        results.push({ round, name: entry.name, ...r })
        save()
        log(`round ${round}: ${entry.name} ${r.code === 0 ? 'PASS' : 'FAIL (' + r.code + ')'} in ${r.seconds}s, ${r.passes} checks passed${r.fails.length ? ', failed: ' + r.fails.slice(0, 3).join(' | ') : ''}`)
        await sleep(3000)
      }
    }
  } finally {
    fs.writeFileSync(config.FILE, found)
    await Promise.race([rcon('cv reload'), sleep(10000)]).catch(() => {})
    save()
  }

  // Summary: per script, the outcome of every round.
  const names = [...new Set(results.map(r => r.name))]
  const failed = []
  const flaky = []
  console.log('\nscript                  ' + Array.from({ length: ROUNDS }, (_, i) => `round ${i + 1}`.padEnd(16)).join('') + 'time')
  for (const name of names) {
    const rs = results.filter(r => r.name === name)
    const ok = rs.map(r => r.code === 0)
    if (ok.some(x => !x)) failed.push(name)
    if (ok.some(x => x) && ok.some(x => !x)) flaky.push(name)
    const cells = rs.map(r => (r.code === 0 ? 'pass' : `FAIL(${r.code})`).padEnd(16)).join('')
    console.log(name.padEnd(24) + cells + rs.map(r => r.seconds + 's').join(' / '))
  }
  console.log(`\n${names.length - failed.length}/${names.length} passed every round in ${((Date.now() - t0) / 60000).toFixed(0)} min`)
  if (flaky.length) console.log('flaky (passed and failed): ' + flaky.join(', '))
  const always = failed.filter(n => !flaky.includes(n))
  if (always.length) console.log('failed every round: ' + always.join(', '))
  console.log('logs: ' + OUT)
  process.exit(failed.length)
})()
