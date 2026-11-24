// Stopping and starting the local test server (SERVER_DIR, env.js) from a bot script.
//
// A script that stops the server needs it back. Some servers run inside a restart loop (the Mac
// test server's run-forever.sh brings it back 5 s after it exits), most local ones do not: after
// the stop, bringBack() waits LOOP_WAIT seconds (15) for something else to start it and only then
// runs SERVER_DIR/start.sh itself (detached, output appended to SERVER_DIR/console.log). NO_START=1
// never starts it (only waits, up to 4 minutes); START=1 starts it at once without waiting.
//
//   const server = require('./server')
//   const back = await server.restart({ log })     // RCON stop, then bringBack
//   await server.restart({ kill: true, log })      // SIGKILL instead (Linux, finds it in /proc)
//   server.at({ dir, port, rconPort }).stop()      // another server than env.js names (suite.js shards)
//   server.foreign()                               // why the server on PORT is not SERVER_DIR's, or null
const fs = require('fs')
const net = require('net')
const path = require('path')
const { spawn, spawnSync } = require('child_process')
const env = require('./env')
const rconRaw = require('./rcon')

const LOOP_WAIT = Number(process.env.LOOP_WAIT || 15) * 1000
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function waitFor (pred, ms, every = 500) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}

const portOpen = port => new Promise(resolve => {
  const s = net.createConnection({ host: env.HOST, port })
  s.once('connect', () => { s.destroy(); resolve(true) })
  s.once('error', () => resolve(false))
})

/** When process `pid` started, in ms since the epoch (Linux /proc), or null. */
function startedAt (pid) {
  try {
    const btime = Number(fs.readFileSync('/proc/stat', 'utf8').match(/^btime (\d+)/m)[1])
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8')
    const ticks = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[19]) // field 22, starttime
    const hz = Number(String(spawnSync('getconf', ['CLK_TCK']).stdout || '').trim()) || 100
    return (btime + ticks / hz) * 1000
  } catch { return null }
}

/**
 * The process listening on TCP `port` (Linux /proc): its pid, null when nothing listens, 0 when the
 * socket belongs to a process this user cannot see, undefined where there is no /proc. The pids in
 * `first` are looked at before the rest.
 */
function listener (port, first = []) {
  if (!fs.existsSync('/proc/net/tcp')) return undefined
  const sockets = new Set()
  for (const table of ['/proc/net/tcp', '/proc/net/tcp6']) {
    let rows = []
    try { rows = fs.readFileSync(table, 'utf8').split('\n').slice(1) } catch { }
    for (const row of rows) {
      const f = row.trim().split(/\s+/)
      if (f.length > 9 && f[3] === '0A' && parseInt(f[1].split(':').pop(), 16) === port) sockets.add(`socket:[${f[9]}]`)
    }
  }
  if (!sockets.size) return null
  const rest = fs.readdirSync('/proc').filter(p => /^\d+$/.test(p)).map(Number).filter(p => !first.includes(p))
  for (const pid of first.concat(rest)) {
    let fds = []
    try { fds = fs.readdirSync(`/proc/${pid}/fd`) } catch { continue }
    for (const fd of fds) {
      try { if (sockets.has(fs.readlinkSync(`/proc/${pid}/fd/${fd}`))) return pid } catch { }
    }
  }
  return 0
}

/** The functions below for the server in `dir` on `port` / `rconPort` (env.js's by default). */
function at ({ dir = env.SERVER_DIR, port = env.PORT, rconPort = env.RCON_PORT, password = env.RCON_PASSWORD } = {}) {
  const LOG = path.join(dir, 'logs', 'latest.log')
  const rcon = cmd => rconRaw(cmd, { port: rconPort, password })

  /** Java processes running paper.jar in the folder (Linux /proc); null where there is no /proc. */
  function pids () {
    if (!fs.existsSync('/proc/self/cwd')) return null
    let real
    try { real = fs.realpathSync(dir) } catch { return [] }
    const out = []
    for (const pid of fs.readdirSync('/proc').filter(p => /^\d+$/.test(p))) {
      try {
        if (fs.readlinkSync(`/proc/${pid}/cwd`) === real && fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('paper.jar')) out.push(Number(pid))
      } catch { }
    }
    return out
  }

  /** The folder's server.properties as { key: value }, {} when it has none. */
  function properties () {
    let text = ''
    try { text = fs.readFileSync(path.join(dir, 'server.properties'), 'utf8') } catch { return {} }
    return Object.fromEntries(text.split('\n').filter(l => /^[^#=\s][^=]*=/.test(l)).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
  }

  /**
   * Why the server on port / rconPort is not this folder's, or null when it is (or nothing listens
   * there yet): the folder's server.properties must name those ports, and whatever listens on them
   * must be the folder's own paper.jar (Linux /proc; elsewhere only server.properties is compared).
   * Callers edit the folder's files (config.yml, profiles, logs), so a server of another folder on
   * those ports, the main checkout's on 25565 when PORT was forgotten in a worktree, is refused
   * rather than driven: it would run the checks on one server and change the files of another.
   */
  function foreign () {
    const props = properties()
    for (const [key, want] of [['server-port', port], ['rcon.port', rconPort]]) {
      if (props[key] && Number(props[key]) !== want) return `${path.join(dir, 'server.properties')} has ${key}=${props[key]}, not ${want} (set PORT / RCON_PORT, or SERVER_DIR)`
    }
    const mine = pids()
    if (mine === null) return null
    for (const p of [port, rconPort]) {
      const pid = listener(p, mine)
      if (pid == null || mine.includes(pid)) continue
      let who = 'a process this user cannot see'
      if (pid) { try { who = `pid ${pid}, running in ${fs.readlinkSync(`/proc/${pid}/cwd`)}` } catch { who = `pid ${pid}` } }
      return `port ${p} is held by ${who}, not by a server in ${dir}`
    }
    return null
  }

  /**
   * The running server loaded an older plugin than the one in its folder now: plugins/Craftov.jar
   * was replaced after the server started (a rebuild copied in, a shard synced), so what it runs is
   * not what a result would record. False when nothing runs or it cannot tell.
   */
  function jarChanged () {
    let mtime
    try { mtime = fs.statSync(path.join(dir, 'plugins', 'Craftov.jar')).mtimeMs } catch { return false }
    // Boot time is in whole seconds, so a start time is only good to about a second: a jar copied
    // in just before start.sh ran must not read as newer. Plugins load seconds after the start.
    return (pids() || []).some(pid => { const t = startedAt(pid); return t !== null && t + 2000 < mtime })
  }

  /** Something is running: a server process in the folder, or (without /proc) the RCON port open. */
  async function running () {
    const p = pids()
    return p === null ? portOpen(rconPort) : p.length > 0
  }

  const logStamp = () => { try { return fs.statSync(LOG).mtimeMs } catch { return 0 } }

  /** True once RCON answers and a latest.log written after `since` (ms) says Done. */
  async function up (since = 0, ms = 180000) {
    return waitFor(async () => {
      if (!(await portOpen(rconPort))) return false
      let text = ''
      try { text = fs.readFileSync(LOG, 'utf8') } catch { return false }
      if (!/Done \(/.test(text) || logStamp() <= since) return false
      return rcon('list').then(() => true, () => false)
    }, ms, 1000)
  }

  /** Stops the server (RCON stop, or SIGKILL with kill) and waits until it is gone. */
  async function stop ({ kill = false, log = console.log } = {}) {
    if (kill) {
      const p = pids()
      if (p === null) throw new Error('SIGKILL needs /proc to find the server')
      for (const pid of p) { log('SIGKILL', pid); process.kill(pid, 'SIGKILL') }
    } else {
      await Promise.race([rcon('stop').catch(() => {}), sleep(5000)])
    }
    return waitFor(async () => !(await running()) && !(await portOpen(port)), 120000)
  }

  /** Runs the folder's start.sh, detached, its output appended to console.log. */
  function launch (log = console.log) {
    const out = fs.openSync(path.join(dir, 'console.log'), 'a')
    spawn(path.join(dir, 'start.sh'), [], { cwd: dir, detached: true, stdio: ['ignore', out, out] }).unref()
    log('started', path.join(dir, 'start.sh'))
  }

  /**
   * After a stop: a restart loop gets LOOP_WAIT to bring the server back, otherwise start.sh is run
   * here. Resolves true once it is up again (a new log that says Done, RCON answering).
   */
  async function bringBack ({ since = Date.now(), log = console.log } = {}) {
    if (process.env.NO_START) {
      log('waiting for the server to be started by something else (NO_START)')
    } else if (process.env.START || !(await waitFor(running, LOOP_WAIT, 1000))) {
      launch(log)
    } else {
      log('the server came back by itself (a restart loop)')
    }
    const back = await up(since, process.env.NO_START ? 240000 : 180000)
    if (back) await sleep(2000)
    return back
  }

  /** Stop (or kill) and bring back; false when it did not stop or did not come back. */
  async function restart ({ kill = false, log = console.log } = {}) {
    const since = Date.now()
    if (!(await stop({ kill, log }))) { log('the server did not stop'); return false }
    await sleep(1000)
    return bringBack({ since, log })
  }

  return { dir, port, rconPort, rcon, pids, properties, foreign, jarChanged, running, portOpen, up, stop, launch, bringBack, restart }
}

module.exports = { ...at(), at, listener }
