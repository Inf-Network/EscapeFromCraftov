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
const fs = require('fs')
const net = require('net')
const path = require('path')
const { spawn } = require('child_process')
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

  return { dir, port, rconPort, rcon, pids, running, portOpen, up, stop, launch, bringBack, restart }
}

module.exports = { ...at(), at }
