// Runs every bot script against the local test server and says which ones fail and which ones
// flake (pass in one round and fail in another).
//
//   node suite.js                       every script once
//   node suite.js --repeat 3            three rounds of every script
//   node suite.js --only fire,escort    only these (a name, or a name:variant from the list below)
//   node suite.js --skip luck,market    all but these
//   node suite.js --list                the scripts, their arguments and the config each one gets
//   node suite.js --repeat 3 --shards 28011:28021,28111:28121,28211:28221
//                                       the same on four servers at once (this one and three copies)
//   node suite.js --repeat 3 --out DIR --resume [--redo hitreg@2,economy@*]
//                                       keep the results DIR/summary.json has and run only the rest
//                                       (--redo drops those first, named as for --only, so
//                                       itemloss@* is every itemloss variant; they stay listed
//                                       under "redone"; --redo-stale drops every result recorded
//                                       with another version of its script than the one there now,
//                                       or on another plugin jar than SERVER_DIR's now)
//   --dry-run                           check the shards, print the order the runs would be taken
//                                       in, and stop there
//
// env: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR (env.js), as every script takes them.
//
// Before each script the server's plugins/Craftov/config.yml is put back as the suite found it, with
// BASE and the script's own `config` (its header's "Server config") set on top, and `cv reload` run;
// at the end the file is put back as it was found. A script killed before its own cleanup (its
// timeout, a signal) skips its finally blocks: the suite then stops the server, puts back the files
// it guards (items.yml, loot.yml, market.json and the map files as they were before the script, the
// files it added to the proving ground's template removed), starts the server again with start.sh
// and logs what it put back (recover). The server must be a throwaway local one: several
// scripts stop, start and kill it (they say so), and when one leaves it down the suite starts it again
// (server.js). Each run's output goes to OUT/<round>/<name>.log (OUT defaults to tools/bot/out/suite-
// <date>), and summary.json there holds every result (exit code, time, checks, the server's port and
// hashes of the script and of the plugin jar, so a table that mixes versions says so). A server
// whose plugins/Craftov.jar was replaced after it started is restarted first. A script passes when it
// exits with 0. market, marketio and persistence write their detail log (LOG) next to it.
//
// Shards: --shards takes more servers as [dir=]port:rcon-port, comma-separated. One without dir is a
// copy of SERVER_DIR at SERVER_DIR-shard-<port>, made when it is missing (SERVER_DIR's server is
// stopped for the copy, so the worlds are consistent; its shard starts it again) with server-port and
// rcon.port changed in its server.properties; a port another server listens on is refused. Every
// server, SERVER_DIR's included, gets a child suite (this file with --worker) with its own env,
// config.yml handling, OUT/shard-<port> folder and summary.json, and the pending (round, script)
// runs form one shared queue: whenever a child is free it asks the parent for the next run, so no
// server sits idle while another still has a backlog, and all of them finish at about the same time.
// The queue is longest first (by the times OUT/summary.json already holds, two minutes for a script
// it has none for; ties in round order), which keeps the last runs short. A child whose server does
// not come up takes nothing, and a child that ends early leaves the rest of the queue to the others;
// one whose server cannot be brought back between two runs hands the run it just took back to the
// queue and stops. A run a child was given but did not finish is reported as not run (as is one no
// server took), and --resume runs those again. Two rounds of one script may run at
// once on different servers. The parent prints the children's lines prefixed with the port and
// merges their results into OUT/summary.json. At the end the copies made by this run are stopped and
// deleted (--keep-shards keeps them, and the next run copies SERVER_DIR's plugins/Craftov.jar,
// items.yml and loot.yml into them before they start); SERVER_DIR's server is left running. The scripts share nothing
// else across servers (bot names are random, every file they write is under their SERVER_DIR or
// OUT), so they need no exclusive run. Each server takes about 3.5 GB of memory.
//
// SERVER_DIR's server.properties must name PORT and RCON_PORT, and a server listening there must be
// SERVER_DIR's own (server.js foreign()): forgetting PORT in a worktree would otherwise run every
// script on the main checkout's server and edit that one's config.yml. Such a server is refused.
//
// Exit code: the number of scripts that failed, or did not run, in any round; 125 when the suite
// could not run. A run that did not run (no server took it, Ctrl-C cut it off) shows as "not run"
// and counts as neither a pass nor a failure.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const readline = require('readline')
const { spawn, spawnSync } = require('child_process')
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
  // Not in accuracy's default run: where rounds land on a wall, and soldiers' hits on a standing
  // versus a strafing bot (only soldiers may shoot it there: no operators, no events).
  { name: 'accuracy:impact', file: 'accuracy.js', args: ['impact'], config: { debug: true } },
  { name: 'accuracy:ai', file: 'accuracy.js', args: ['ai'], config: { debug: true, 'ai.operator-chance': [0, 0, 0], 'events.per-raid': 0 } },
  { name: 'ads' },
  { name: 'aibehaviour', config: { 'match.wait-seconds': 2 }, minutes: 40 },
  { name: 'aidamage', config: { 'match.wait-seconds': 2 } },
  { name: 'armor' },
  { name: 'beats' },
  { name: 'carry' },
  { name: 'carryui', minutes: 40 },
  { name: 'credit', minutes: 40 },
  { name: 'cursor' },
  { name: 'deploy', config: { 'match.wait-seconds': 2, 'raid.reconnect-seconds': 30, 'tiers.NORMAL.entry-fee': 1000 }, env: { RECONNECT: 30 }, minutes: 40 },
  { name: 'disconnect', config: { 'match.wait-seconds': 2, 'raid.reconnect-seconds': 30 }, env: { RECONNECT: 30 }, minutes: 40 },
  { name: 'doors' },
  { name: 'econ' },
  { name: 'economy', minutes: 60 },
  { name: 'editor', config: { 'match.wait-seconds': 5 }, minutes: 40 },
  { name: 'escort' },
  { name: 'escortbusy' },
  { name: 'feedback' },
  { name: 'fire' },
  { name: 'flashhome' },
  { name: 'footsteps' },
  { name: 'grenade' },
  { name: 'guide', config: QUIET },
  { name: 'gunaudio' },
  { name: 'gunfeel', config: { debug: true } },
  { name: 'handling', config: { debug: true } },
  { name: 'hitreg', config: { debug: true, 'ai.count-multiplier': 1.25, 'ai.operator-chance': [0, 0, 0], 'events.per-raid': 0 }, minutes: 40 },
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
  { name: 'slide', config: { debug: true }, minutes: 40 },
  { name: 'spectate', config: { 'match.wait-seconds': 2, 'tiers.NORMAL.entry-fee': 1000 }, minutes: 40 },
  { name: 'squad' },
  { name: 'stacking', config: QUIET },
  { name: 'stances' },
  { name: 'strobe' },
  { name: 'swap', config: { debug: true } },
  { name: 'test' },
  { name: 'testmap', minutes: 40 },
  { name: 'training' },
  // Not in training's default run: no rival operators on the training course, which proves something
  // only when every other raid would get them.
  { name: 'training:solo', file: 'training.js', args: ['solo'], config: { 'ai.operator-chance': [1, 1, 1] } },
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
const byName = Object.fromEntries(SCRIPTS.map(e => [e.name, e]))
const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 16)
const OUT = path.resolve(opt('--out', path.join(__dirname, 'out', 'suite-' + stamp)))
const WORKER = argv.includes('--worker')
/** This invocation, on every result it records: a shard's summary.json from an earlier run is not merged. */
const RUN_ID = opt('--run', `${stamp}-${process.pid}`)

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
/** The exit code when the suite itself could not run (a refused server, a shard it could not make). */
const EXIT_BROKEN = 125
const t0 = Date.now()
const clock = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${clock()}]`, ...a)
const yaml = v => typeof v === 'string' ? v : JSON.stringify(v).replace(/,/g, ', ')
const logName = name => name.replace(/:/g, '-')
const shaOf = entry => crypto.createHash('sha1').update(fs.readFileSync(path.join(__dirname, entry.file || entry.name + '.js'))).digest('hex').slice(0, 10)
const hashFile = file => { try { return crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex').slice(0, 10) } catch { return null } }
/** The hash of the plugin jar in `dir` (SERVER_DIR's by default), recorded with every result. */
const jarOf = (dir = env.SERVER_DIR) => hashFile(path.join(dir, 'plugins', 'Craftov.jar'))
/**
 * What a kept shard copy must share with SERVER_DIR before it starts: the plugin, and the item and
 * loot tables (a server copies those from the jar only when they are missing, so a copy made before
 * a rebuild would keep the old ones).
 */
const SYNCED = ['plugins/Craftov.jar', 'plugins/Craftov/items.yml', 'plugins/Craftov/loot.yml']

if (argv.includes('--list')) {
  for (const e of SCRIPTS) {
    const cfg = Object.entries({ ...BASE, ...(e.config || {}) }).map(([k, v]) => `${k} ${yaml(v)}`).join(', ')
    console.log(`${e.name.padEnd(22)} node ${e.file || e.name + '.js'} ${(e.args || []).join(' ')}`.padEnd(60) +
      `${Object.entries(e.env || {}).map(([k, v]) => `${k}=${v}`).join(' ')}  [${cfg}] ${e.minutes || 30} min`)
  }
  process.exit(0)
}

// ------------------------------------------------------------------ one server (env.js's)

/**
 * Makes sure SERVER_DIR's server answers RCON: waits for one that is starting, starts one that is
 * not there. A server on PORT / RCON_PORT that is not SERVER_DIR's (server.foreign) is refused, even
 * one that answers: the suite would run every script on it and edit another server's config.yml.
 */
async function ensureUp () {
  const why = server.foreign()
  if (why) {
    log('refusing this server: ' + why)
    return false
  }
  if (server.jarChanged()) {
    // A result records the folder's jar, so the server must be running that one.
    log('plugins/Craftov.jar was replaced after the server started: restarting it')
    if (!await server.restart({ log })) return false
  }
  if (await rcon('list').then(() => true, () => false)) return true
  const mine = server.pids()
  if (mine !== null && mine.length === 0 && (await server.portOpen(env.PORT) || await server.portOpen(env.RCON_PORT))) {
    log(`port ${env.PORT} or ${env.RCON_PORT} is taken by a server that is not ${env.SERVER_DIR}: not starting one`)
    return false
  }
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

// ------------------------------------------------------------------ after a killed script

/**
 * What a script may leave behind when it is killed before its own cleanup (its timeout's SIGTERM, a
 * signal: neither runs its finally blocks). Files it edits while it runs, under SERVER_DIR: itemloss
 * breaks and renames items.yml, persistence garbles market.json and rustport.yml, marketio replaces
 * market.json, editor and security write map files. Folders where it adds files that must go again:
 * launch pads the proving ground's template with 20,000+ files (every raid would copy them).
 * config.yml is not here: every run starts by writing it afresh (configure).
 */
const GUARDED_FILES = ['plugins/Craftov/items.yml', 'plugins/Craftov/loot.yml', 'plugins/Craftov/data/market.json']
const GUARDED_DIRS = ['plugins/Craftov/maps', 'plugins/Craftov/maps/drafts'] // every file's content, and its new files
const PADDED_DIRS = ['craftov_tpl_proving/pad'] // only its new files
const under = rel => path.join(env.SERVER_DIR, rel)
const readOrNull = file => { try { return fs.readFileSync(file) } catch { return null } }
const namesOrNull = dir => { try { return new Set(fs.readdirSync(dir)) } catch { return null } }

/** The guarded files and folders as they are before a run. */
function snapshot () {
  const files = new Map(GUARDED_FILES.map(rel => [rel, readOrNull(under(rel))]))
  const dirs = new Map()
  for (const rel of GUARDED_DIRS.concat(PADDED_DIRS)) {
    const names = namesOrNull(under(rel))
    dirs.set(rel, names)
    if (names && GUARDED_DIRS.includes(rel)) {
      for (const name of names) {
        let file = false
        try { file = fs.statSync(path.join(under(rel), name)).isFile() } catch { } // gone since the listing
        if (file) files.set(path.join(rel, name), readOrNull(path.join(under(rel), name)))
      }
    }
  }
  return { files, dirs }
}

/** Puts the files and folders back as `snap` had them (synchronously); returns what it did. */
function restoreSnapshot (snap) {
  const done = []
  for (const [rel, before] of snap.files) {
    const now = readOrNull(under(rel))
    if (before === null && now !== null) { fs.rmSync(under(rel), { force: true }); done.push('removed ' + rel) }
    if (before !== null && (now === null || !before.equals(now))) { fs.writeFileSync(under(rel), before); done.push('put back ' + rel) }
  }
  for (const [rel, before] of snap.dirs) {
    const now = namesOrNull(under(rel))
    if (!now) continue
    if (before === null) {
      fs.rmSync(under(rel), { recursive: true, force: true })
      done.push(`removed ${rel} (${now.size} files)`)
      continue
    }
    const added = [...now].filter(name => !before.has(name) && !snap.files.has(path.join(rel, name)))
    for (const name of added) fs.rmSync(path.join(under(rel), name), { recursive: true, force: true })
    if (added.length) done.push(`removed ${added.length} new file(s) from ${rel}${added.length <= 3 ? ' (' + added.join(', ') + ')' : ''}`)
  }
  return done
}

/** JVM settings of the running server that start.sh would not give it (a script's own start). */
function serverFlags () {
  // start.sh runs java -Xmx${MEMORY:-4G}: what the suite's own start would give.
  let memory = null
  try { memory = (fs.readFileSync(under('start.sh'), 'utf8').match(/-Xmx\$\{MEMORY:-(\w+)\}/) || [])[1] || null } catch { }
  const want = '-Xmx' + (process.env.MEMORY || memory)
  const flags = []
  for (const pid of server.pids() || []) {
    try {
      const environ = fs.readFileSync(`/proc/${pid}/environ`, 'utf8').split('\0').find(v => v.startsWith('JAVA_TOOL_OPTIONS='))
      if (environ && environ !== 'JAVA_TOOL_OPTIONS=' + (process.env.JAVA_TOOL_OPTIONS || '')) flags.push(environ)
      const xmx = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').find(a => a.startsWith('-Xmx'))
      if (xmx && memory && xmx !== want) flags.push(xmx)
    } catch { }
  }
  return flags
}

/**
 * After a script was killed: the server is stopped (so its final save cannot write over what is put
 * back), the guarded files and folders and config.yml are put back, and the server is started the
 * way the suite starts it (start.sh, no JVM flags of a script's own start: marketio's slow-disk
 * write delay, persistence's memory). Logs what it did.
 */
async function recover (snap, why) {
  const flags = serverFlags()
  log(`${why}: stopping the server to put back what the script left${flags.length ? ' (it ran with ' + flags.join(' ') + ')' : ''}`)
  if (!await server.stop({ log }) && !await server.stop({ kill: true, log })) log('the server did not stop')
  const done = restoreSnapshot(snap)
  fs.writeFileSync(config.FILE, found)
  log(done.length ? 'put back: ' + done.join('; ') : 'nothing of the guarded files had changed')
  const since = Date.now()
  log(await server.bringBack({ since, log }) ? 'the server is back' : 'the server did not come back')
}

// ------------------------------------------------------------------ one script

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
        // The detail log of market, marketio and persistence: next to the run's own, not in /tmp,
        // where two servers running the same script would write over each other.
        LOG: file.replace(/\.log$/, '.detail.log'),
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
      // Killed by a signal that was not the suite's own timeout (Ctrl-C, the OOM killer, a kill from
      // outside): the script did not get to say anything, so the run is not a result.
      const killed = code === null && !timedOut
      resolve({
        code: timedOut ? 'timeout' : killed ? 'not run' : code,
        ...(signal ? { signal } : {}),
        seconds: Math.round((Date.now() - started) / 1000),
        passes: lines.filter(l => /\bPASS\b/.test(l)).length,
        fails: killed ? [`killed by ${signal}, not by the suite`].concat(fails) : fails
      })
    })
  })
}

/**
 * Runs jobs ({ round, name }) one after another on env.js's server, output under `out`, taking each
 * from `next` (an async function, null when there are no more) once the one before is done; results
 * carry their log path relative to `rel` (the folder the merged summary.json lives in), and `done`
 * hears each one as it comes. Takes nothing when the server does not come up, and returns [] then.
 * When the server cannot be brought back between two runs, the job just taken goes to `giveBack`
 * (another server may run it; otherwise it ends up as "not run") and no more are taken: a dead
 * server would only turn the rest of the queue into failures that --resume never runs again.
 */
async function runQueue (next, out, rel, { done = () => {}, giveBack = () => {} } = {}) {
  fs.mkdirSync(out, { recursive: true })
  const results = []
  if (!await ensureUp()) {
    log('the server did not come up: taking no runs')
    return results
  }
  found = fs.readFileSync(config.FILE, 'utf8')
  const save = () => fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify({ run: RUN_ID, port: env.PORT, server: env.SERVER_DIR, results }, null, 2))
  let stopping = false
  let current = null // the snapshot of the run going on
  // Ctrl-C at the terminal reaches the script as well (one process group): that one dies with it
  // and is recorded as not run (runOne), so --resume runs it again. A second Ctrl-C quits at once,
  // with config.yml and what the killed script left put back first: synchronously, as nothing after
  // process.exit runs (the server is not restarted then).
  process.on('SIGINT', () => {
    if (stopping) {
      try { fs.writeFileSync(config.FILE, found); log('config.yml put back; quitting') } catch (e) { log('config.yml could not be put back:', e.message) }
      if (current) { try { const done = restoreSnapshot(current); if (done.length) log('put back: ' + done.join('; ') + ' (restart the server)') } catch (e) { log('could not put back:', e.message) } }
      process.exit(130)
    }
    stopping = true
    log('Ctrl-C: no more runs after this one (a script the Ctrl-C reached is recorded as not run); Ctrl-C again quits at once')
  })
  try {
    for (let job = await next(); job && !stopping; job = stopping ? null : await next()) {
      const entry = byName[job.name]
      const base = { round: job.round, name: job.name, run: RUN_ID, port: env.PORT, sha: shaOf(entry), jar: jarOf() }
      if (!await ensureUp()) {
        log(`no usable server (see above): handing round ${job.round} ${job.name} back and taking no more runs`)
        giveBack(job)
        break
      }
      await configure(entry)
      const file = path.join(out, String(job.round), logName(job.name) + '.log')
      fs.mkdirSync(path.dirname(file), { recursive: true })
      log(`round ${job.round}: ${job.name} ...`)
      current = snapshot()
      const r = await runOne(entry, file)
      results.push({ ...base, ...r, log: path.relative(rel, file) })
      done(results[results.length - 1])
      save()
      log(`round ${job.round}: ${job.name} ${r.code === 0 ? 'PASS' : r.code === 'not run' ? 'NOT RUN (' + r.signal + ')' : 'FAIL (' + r.code + ')'} in ${r.seconds}s, ${r.passes} checks passed${r.fails.length ? ', failed: ' + r.fails.slice(0, 3).join(' | ') : ''}`)
      if (r.code === 'timeout' || r.signal) await recover(current, r.code === 'timeout' ? `${job.name} timed out` : `${job.name} was killed by ${r.signal}`)
      current = null
      await sleep(3000)
    }
  } finally {
    fs.writeFileSync(config.FILE, found)
    await Promise.race([rcon('cv reload'), sleep(10000)]).catch(() => {})
    save()
  }
  return results
}

// ------------------------------------------------------------------ shards

/** --shards: [dir=]port:rcon entries, each { dir, port, rconPort, copy } (copy: made from SERVER_DIR). */
function parseShards () {
  return opt('--shards', '').split(',').filter(Boolean).map(spec => {
    const m = spec.match(/^(?:(.+)=)?(\d+):(\d+)$/)
    if (!m) throw new Error('--shards takes [dir=]port:rcon-port entries, not ' + spec)
    const port = Number(m[2])
    return { dir: path.resolve(m[1] || `${env.SERVER_DIR}-shard-${port}`), port, rconPort: Number(m[3]), copy: !m[1] }
  })
}

/** Sets key=value lines in a server.properties file. */
function setProperties (file, values) {
  let text = fs.readFileSync(file, 'utf8')
  for (const [k, v] of Object.entries(values)) {
    const re = new RegExp(`^${k.replace(/\./g, '\\.')}=.*$`, 'm')
    text = re.test(text) ? text.replace(re, `${k}=${v}`) : text.replace(/\n*$/, '\n') + `${k}=${v}\n`
  }
  fs.writeFileSync(file, text)
}

/**
 * A copy kept from an earlier run (--keep-shards) gets SERVER_DIR's jar, items.yml and loot.yml
 * where they differ, stopped first if it runs (its child suite starts it again).
 */
async function syncShard (s) {
  const stale = SYNCED.filter(rel => fs.existsSync(path.join(env.SERVER_DIR, rel)) && hashFile(path.join(env.SERVER_DIR, rel)) !== hashFile(path.join(s.dir, rel)))
  if (!stale.length) return
  const at = server.at(s)
  if (await at.running()) {
    log(`stopping the kept shard on ${s.port}: ${stale.join(', ')} differ from ${env.SERVER_DIR}'s`)
    if (!await at.stop({ log })) throw new Error(`the shard on ${s.port} did not stop`)
  }
  for (const rel of stale) fs.copyFileSync(path.join(env.SERVER_DIR, rel), path.join(s.dir, rel))
  log(`copied ${stale.join(', ')} from ${env.SERVER_DIR} into ${s.dir}`)
}

/**
 * Every shard checked before anything is stopped or copied: no port given to two servers (SERVER_DIR's
 * included), no folder given twice; a folder that exists must have server.properties naming its ports
 * and nothing but its own server listening on them (server.js foreign); a copy still to be made needs
 * both of its ports free. Throws the first problem.
 */
async function checkShards (shards) {
  const owner = new Map()
  const claim = (port, who) => {
    if (owner.has(port)) throw new Error(`port ${port} is given twice: to ${owner.get(port)} and to ${who}`)
    owner.set(port, who)
  }
  claim(env.PORT, env.SERVER_DIR)
  claim(env.RCON_PORT, `${env.SERVER_DIR} (RCON)`)
  const dirs = new Set([path.resolve(env.SERVER_DIR)])
  for (const s of shards) {
    claim(s.port, `the shard ${s.dir}`)
    claim(s.rconPort, `the shard ${s.dir} (RCON)`)
    if (dirs.has(s.dir)) throw new Error(`${s.dir} is given for two servers`)
    dirs.add(s.dir)
    if (fs.existsSync(s.dir)) {
      const why = server.at(s).foreign()
      if (why) throw new Error(why)
    } else if (!s.copy) {
      throw new Error('no server at ' + s.dir)
    } else {
      for (const port of [s.port, s.rconPort]) {
        const held = server.listener(port)
        if (held != null || await server.portOpen(port)) throw new Error(`port ${port} for the new shard ${s.dir} is taken${held ? ' (pid ' + held + ')' : ''}`)
      }
    }
  }
}

/**
 * Makes the shard copies that are missing (after checkShards passed for all of them) and syncs the
 * kept ones; returns the copies this run made. If anything fails on the way, the copies it made are
 * deleted again and SERVER_DIR's server, if it was stopped for them, is started again.
 */
async function prepareShards (shards) {
  await checkShards(shards)
  const main = server.at()
  const made = []
  let stopped = false
  try {
    for (const s of shards) {
      if (fs.existsSync(s.dir)) {
        if (s.copy) await syncShard(s)
        else if (jarOf(s.dir) !== jarOf()) log(`${s.dir} has another plugins/Craftov.jar than ${env.SERVER_DIR} (its results record it)`)
        continue
      }
      if (!stopped && await main.running()) {
        log(`stopping ${env.SERVER_DIR} to copy it`)
        stopped = true
        if (!await main.stop({ log })) throw new Error(`${env.SERVER_DIR}'s server did not stop`)
      }
      log(`copying ${env.SERVER_DIR} to ${s.dir}`)
      made.push(s)
      const cp = spawnSync('cp', ['-a', env.SERVER_DIR, s.dir], { stdio: 'inherit' })
      if (cp.status !== 0) throw new Error(`cp to ${s.dir} failed`)
      setProperties(path.join(s.dir, 'server.properties'), { 'server-port': s.port, 'rcon.port': s.rconPort, 'query.port': s.port, 'server-ip': '127.0.0.1' })
    }
  } catch (e) {
    log('could not prepare the shards: ' + e.message)
    for (const s of made) { fs.rmSync(s.dir, { recursive: true, force: true }); log('deleted ' + s.dir) }
    if (stopped && !await main.running()) {
      const since = Date.now()
      main.launch(log)
      log(await main.up(since, 240000) ? `${env.SERVER_DIR}'s server is back` : `${env.SERVER_DIR}'s server did not come back`)
    }
    throw e
  }
  return made
}

/**
 * The pending runs as one queue, longest first (ties in round order), each with its estimate in
 * seconds: the mean time of the script's `past` runs (kept and redone ones), two minutes without.
 */
function order (pending, past) {
  const seconds = name => {
    const times = past.filter(r => r.name === name && typeof r.seconds === 'number' && r.seconds > 0).map(r => r.seconds)
    return times.length ? times.reduce((a, b) => a + b, 0) / times.length : 120
  }
  return pending.map(job => ({ ...job, estimate: Math.round(seconds(job.name)) }))
    .sort((a, b) => b.estimate - a.estimate || a.round - b.round || SCRIPTS.findIndex(e => e.name === a.name) - SCRIPTS.findIndex(e => e.name === b.name))
}

/** What the queue would do on `servers` free servers: each run to the one that frees up first. */
function simulate (queue, servers) {
  const load = Array.from({ length: servers }, () => 0)
  const runs = load.map(() => [])
  for (const job of queue) {
    const i = load.indexOf(Math.min(...load))
    load[i] += job.estimate
    runs[i].push(job)
  }
  return runs.map((jobs, i) => ({ jobs, minutes: Math.round(load[i] / 60) }))
}

/**
 * Runs a child suite on one target that takes its runs from `take` (the shared queue: the next run,
 * or null) over IPC, one whenever it is free; a run it hands back (its server is gone) goes to
 * `putBack`, for the others. Resolves the results, with "not run" for a run it was given and did not
 * finish.
 */
function runShard (target, take, putBack, folder) {
  return new Promise(resolve => {
    fs.mkdirSync(folder, { recursive: true })
    // An earlier run's summary.json here would otherwise be read back below as this run's.
    const summary = path.join(folder, 'summary.json')
    fs.rmSync(summary, { force: true })
    const child = spawn(process.execPath, [__filename, '--worker', '--out', folder, '--rel', OUT, '--run', RUN_ID], {
      cwd: __dirname,
      stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
      env: { ...process.env, PORT: String(target.port), RCON_PORT: String(target.rconPort), SERVER_DIR: target.dir, CRAFTOV_RUN: '', CRAFTOV_DATA: '', SERVER_LOG: '' }
    })
    const prefix = line => console.log(`{${target.port}} ${line}`)
    readline.createInterface({ input: child.stdout }).on('line', prefix)
    readline.createInterface({ input: child.stderr }).on('line', prefix)
    const given = []
    const results = []
    child.on('message', m => {
      if (m && m.type === 'next') {
        const job = take()
        if (job) given.push(job)
        child.send({ type: 'job', job: job || null })
      } else if (m && m.type === 'result') {
        results.push(m.result)
      } else if (m && m.type === 'return' && m.job) {
        const i = given.findIndex(j => j.round === m.job.round && j.name === m.job.name)
        if (i >= 0) given.splice(i, 1)
        putBack(m.job)
      }
    })
    child.on('exit', () => {
      // A result the child saved but could not send (it was ending) is in its summary.json; only
      // this invocation's count.
      let saved = []
      try { saved = JSON.parse(fs.readFileSync(summary, 'utf8')).results || [] } catch { }
      for (const r of saved) if (r.run === RUN_ID && !results.some(x => x.round === r.round && x.name === r.name)) results.push(r)
      for (const job of given) {
        if (!results.some(r => r.round === job.round && r.name === job.name)) {
          results.push({ round: job.round, name: job.name, port: target.port, code: 'not run', seconds: 0, passes: 0, fails: ['the shard stopped before it'] })
        }
      }
      resolve(results)
    })
  })
}

// ------------------------------------------------------------------ main

function table (results) {
  const names = [...new Set(results.map(r => r.name))].sort((a, b) => SCRIPTS.findIndex(e => e.name === a) - SCRIPTS.findIndex(e => e.name === b))
  const rounds = Math.max(1, ...results.map(r => r.round))
  const failed = []
  const flaky = []
  const unrun = []
  const mixed = []
  const mixedJars = []
  console.log('\nscript                  ' + Array.from({ length: rounds }, (_, i) => `round ${i + 1}`.padEnd(16)).join('') + 'time')
  for (const name of names) {
    const rs = Array.from({ length: rounds }, (_, i) => results.find(r => r.name === name && r.round === i + 1))
    // A run that never ran (or was cut off by Ctrl-C) neither passed nor failed: --resume runs it.
    const ran = rs.filter(r => r && r.code !== 'not run')
    const ok = ran.map(r => r.code === 0)
    if (ok.some(x => !x)) failed.push(name)
    if (ok.some(x => x) && ok.some(x => !x)) flaky.push(name)
    if (ran.length < rs.filter(Boolean).length) unrun.push(name)
    // Results from before the suite recorded hashes have none: only recorded ones are compared.
    const shas = new Set(rs.filter(r => r && r.sha).map(r => r.sha))
    const jars = new Set(rs.filter(r => r && r.jar).map(r => r.jar))
    if (shas.size > 1) mixed.push(name)
    if (jars.size > 1) mixedJars.push(name)
    const cells = rs.map(r => (!r ? '-' : r.code === 0 ? 'pass' : r.code === 'not run' ? 'not run' : `FAIL(${r.code})`).padEnd(16)).join('')
    console.log((name + (shas.size > 1 ? ' *' : '') + (jars.size > 1 ? ' +' : '')).padEnd(24) + cells + rs.filter(Boolean).map(r => r.seconds + 's').join(' / '))
  }
  console.log(`\n${names.filter(n => !failed.includes(n) && !unrun.includes(n)).length}/${names.length} passed every round`)
  if (flaky.length) console.log('flaky (passed and failed): ' + flaky.join(', '))
  const always = failed.filter(n => !flaky.includes(n))
  if (always.length) console.log('failed every round it ran: ' + always.join(', '))
  if (unrun.length) console.log('not run in some round (--resume runs them): ' + unrun.join(', '))
  if (mixed.length) console.log('* ran in more than one version of the script: ' + mixed.join(', '))
  if (mixedJars.length) console.log('+ ran on more than one plugin jar: ' + mixedJars.join(', '))
  console.log('logs: ' + OUT)
  return new Set(failed.concat(unrun)).size
}

;(async () => {
  // A child suite (--shards): runs on env.js's server whatever the parent's queue hands it, one
  // at a time, asking for the next when it is free.
  if (WORKER) {
    log(`taking runs from the shared queue on port ${env.PORT} (RCON ${env.RCON_PORT}), server ${env.SERVER_DIR}`)
    let waiting = null
    process.on('message', m => { if (m && m.type === 'job' && waiting) { const w = waiting; waiting = null; w(m.job) } })
    const next = () => new Promise(resolve => {
      if (!process.connected) return resolve(null)
      waiting = resolve
      process.send({ type: 'next' })
    })
    const results = await runQueue(next, OUT, path.resolve(opt('--rel', OUT)), {
      done: r => { if (process.connected) process.send({ type: 'result', result: r }) },
      giveBack: job => { if (process.connected) process.send({ type: 'return', job }) }
    })
    log(`the queue is empty: ${results.length} runs here`)
    if (process.connected) process.disconnect()
    process.exit(results.filter(r => r.code !== 0).length)
  }

  const refused = server.foreign()
  if (refused) {
    log('refusing to run on this server: ' + refused)
    process.exit(EXIT_BROKEN)
  }
  fs.mkdirSync(OUT, { recursive: true })
  const summaryFile = path.join(OUT, 'summary.json')
  let kept = []
  const redone = []
  if (argv.includes('--resume') && fs.existsSync(summaryFile)) {
    const old = JSON.parse(fs.readFileSync(summaryFile, 'utf8'))
    // Placeholders for runs a stopped shard never started are not results: those runs are pending.
    // So are the "no server" ones older suites recorded for every run after their server died.
    kept = (old.results || []).filter(r => r.code !== 'not run' && r.code !== 'no server').map(r => ({ log: `${r.round}/${logName(r.name)}.log`, ...r }))
    redone.push(...(old.redone || []))
    if (argv.includes('--redo-stale')) {
      // Results recorded with another version of their script (its hash) than the one there now,
      // or on another plugin jar than SERVER_DIR's now.
      const jar = jarOf()
      const why = r => r.sha && byName[r.name] && r.sha !== shaOf(byName[r.name]) ? 'stale script' : r.jar && jar && r.jar !== jar ? 'stale jar' : null
      redone.push(...kept.filter(why).map(r => ({ ...r, redoneAt: new Date().toISOString(), why: why(r) })))
      kept = kept.filter(r => !why(r))
    }
    for (const spec of opt('--redo', '').split(',').filter(Boolean)) {
      // A name matches as it does for --only: itemloss@2 is every itemloss:* variant's round 2.
      const [name, round] = spec.split('@')
      const drop = r => matches(r, [name]) && (round === '*' || r.round === Number(round))
      redone.push(...kept.filter(drop).map(r => ({ ...r, redoneAt: new Date().toISOString() })))
      kept = kept.filter(r => !drop(r))
    }
  }
  const pending = []
  for (let round = 1; round <= ROUNDS; round++) {
    for (const entry of chosen) if (!kept.some(r => r.round === round && r.name === entry.name)) pending.push({ round, name: entry.name })
  }
  const save = results => fs.writeFileSync(summaryFile, JSON.stringify({ port: env.PORT, rounds: ROUNDS, results, redone }, null, 2))
  const shards = parseShards()
  log(`${pending.length} runs (${chosen.length} scripts x ${ROUNDS} round(s), ${kept.length} kept) on ${1 + shards.length} server(s); output in ${OUT}`)
  if (argv.includes('--dry-run')) {
    // The shards checked (nothing is stopped or copied), the order the runs would be taken in and,
    // with the times known so far, how it might share out.
    await checkShards(shards)
    const queue = shards.length ? order(pending, kept.concat(redone)) : pending
    log('queue: ' + queue.map(j => `${j.name}@${j.round}${j.estimate ? ` (${j.estimate}s)` : ''}`).join(' '))
    if (shards.length) simulate(queue, 1 + shards.length).forEach((l, i) => log(`a server's share, about ${l.minutes} min: ${l.jobs.map(j => `${j.name}@${j.round}`).join(' ')}`))
    process.exit(0)
  }

  let results
  if (!shards.length) {
    // One server: in this process, round by round, as listed.
    const queue = pending.slice()
    const fresh = await runQueue(async () => queue.shift() || null, OUT, OUT, { giveBack: job => queue.unshift(job) })
    for (const job of queue) fresh.push({ round: job.round, name: job.name, port: env.PORT, code: 'not run', seconds: 0, passes: 0, fails: ['not run'] })
    results = kept.concat(fresh)
    save(results)
  } else {
    let made = []
    try {
      made = await prepareShards(shards)
      const targets = [{ dir: env.SERVER_DIR, port: env.PORT, rconPort: env.RCON_PORT }].concat(shards)
      const queue = order(pending, kept.concat(redone))
      const total = queue.reduce((a, j) => a + j.estimate, 0)
      log(`one queue of ${queue.length} runs, longest first, for ${targets.length} servers: about ${Math.round(total / 60 / targets.length)} min each`)
      let stopping = false
      // Ctrl-C reaches the whole process group, the shards and their scripts with it: the scripts
      // die and are recorded as not run. To stop the shards cleanly from outside, send SIGINT to
      // their child suites (suite.js --worker) alone: each finishes its current script, puts
      // config.yml back and saves what it ran. The runs it never started are merged as "not run",
      // which --resume runs again.
      process.on('SIGINT', () => { if (!stopping) { stopping = true; log('every shard stops after its current script') } })
      const take = () => (stopping ? null : queue.shift() || null)
      const putBack = job => { log(`round ${job.round} ${job.name} is back in the queue`); queue.unshift(job) }
      const fresh = await Promise.all(targets.map(t => runShard(t, take, putBack, path.join(OUT, `shard-${t.port}`))))
      // Runs no server took (every one of them ended or never came up).
      const untaken = queue.splice(0).map(job => ({ round: job.round, name: job.name, port: null, code: 'not run', seconds: 0, passes: 0, fails: ['no server took it'] }))
      results = kept.concat(...fresh, untaken)
      save(results)
    } finally {
      if (!argv.includes('--keep-shards')) {
        for (const s of made) {
          const at = server.at(s)
          if (await at.running()) { log(`stopping the shard on ${s.port}`); await at.stop({ log }) }
          if ((at.pids() || []).length === 0) { fs.rmSync(s.dir, { recursive: true, force: true }); log('deleted ' + s.dir) }
        }
      }
    }
  }
  log(`done in ${((Date.now() - t0) / 60000).toFixed(0)} min`)
  process.exit(table(results))
})().catch(e => {
  log('the suite could not run: ' + (e && (e.stack || e.message)))
  process.exit(EXIT_BROKEN)
})
