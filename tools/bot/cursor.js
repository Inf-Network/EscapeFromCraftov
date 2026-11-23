// Items held on the cursor (issue #60): when deploying and when the server stops.
//
//   node cursor.js deploy | stop | all
//
// The bots pick an item up onto the cursor in their own inventory screen (as far as the server is
// concerned, the screen stays open: a bot never sends the close packet) and leave it there.
//
// deploy: two bots queue for the proving ground holding an item on the cursor.
//   insured - auto insurance on, a loaded m4c and armour; holds the m4c. The deploy must insure
//             both ("已为 2 件装备投保"), the deployment record on disk must hold the m4c, and once
//             it dies the insurance claim must send both back to the mailbox.
//   carrier - holds a gold bar and extracts at once: the start value counts the bar, so the
//             report's net profit is 0 rather than the bar's ◈68,000, and the bar comes home once.
// stop: the server is stopped over RCON while
//   lobby   - in the lobby holds its gold bar: afterwards it is in the loadout, once.
//   full    - in the lobby holds its server drive, and the pocket it came from has been filled
//             since (cv give): afterwards the drive is in the mailbox, once.
//   own     - in a raid holds the gold bar it brought in: the raid is void, the loadout comes back
//             as it went in, the bar once.
//   finder  - in the same raid holds a pocket watch found there (cv give): it stays in the raid,
//             in neither the loadout nor the mailbox.
//   Then the server is started again (SERVER_DIR/start.sh, detached, output appended to
//   SERVER_DIR/console.log) and lobby must find the gold bar in its inventory on joining.
//
// Env: PORT / RCON_PORT / RCON_PASS of the local test server (default 25565 / 25575 / craftov-local),
// SERVER_DIR = its folder (default ../../server/run). The server must be running with test mode on
// (test.enabled) so the bots can queue for the proving ground, and without a whitelist. deploy sets
// insurance.loss-chance and return-minutes to 0 in config.yml while it runs (see insurance.js).
// Exits 0 when every check passes, 1 otherwise.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const net = require('net')
const path = require('path')
const { spawn } = require('child_process')
const rconRaw = require('./rcon')
const pinInsurance = require('./insurance')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const SERVER_DIR = path.resolve(process.env.SERVER_DIR || path.join(__dirname, '..', '..', 'server', 'run'))
const SERVER_REAL = fs.realpathSync(SERVER_DIR)
const DATA = path.join(SERVER_DIR, 'plugins', 'Craftov')
const scenario = process.argv[2] || 'all'
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
let failures = 0
const check = (label, ok, extra) => { if (!ok) failures++; log(ok ? 'PASS' : 'FAIL', label, extra === undefined ? '' : extra) }

// ------------------------------------------------------------------ profiles

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(DATA, 'data/players', offlineUuid(name) + '.json')
const readProfile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const writeProfile = (name, p) => fs.writeFileSync(profileFile(name), JSON.stringify(p, null, 2))
const rec = (id, extra = {}) => Object.assign({ id, amount: 1 }, extra)
const loaded = (id, ammoType) => rec(id, { uid: crypto.randomUUID(), ammo: 20, ammoType })
/** How many of `id` the records hold, packed ones included. */
function countIds (records, id) {
  let n = 0
  for (const r of Object.values(records || {})) {
    if (!r) continue
    if (r.id === id) n += r.amount || 1
    n += countIds(r.contents, id)
  }
  return n
}
const ids = records => Object.values(records || {}).map(r => r.id).sort()

// ------------------------------------------------------------------ bots

/** The Craftov item id of a stack (its custom data), or its model path. */
function cid (item) {
  if (!item) return null
  const json = JSON.stringify(item.components || '')
  const data = json.match(/"craftov:id":\{"type":"string","value":"([^"]+)"/)
  if (data) return data[1]
  const model = json.match(/craftov:([a-z0-9_/]+)/)
  return model ? model[1] : item.name
}

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldChanges = 0
  bot.chats = []
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chats.push(m); log(name, 'CHAT', m) } })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r).slice(0, 200)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    bot.once('spawn', async () => { await sleep(3000); resolve(bot) })
    bot.once('end', r => reject(new Error(name + ' disconnected: ' + r)))
  })
}

async function leave (bot) {
  const file = profileFile(bot.username)
  const before = fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0
  bot.quit()
  await waitFor(() => fs.existsSync(file) && fs.statSync(file).mtimeMs > before, 8000)
  await sleep(500)
}

/** Joins once so the profile exists, then lets `mutate` edit it offline. */
async function prepare (name, mutate) {
  const bot = await make(name)
  await leave(bot)
  const p = readProfile(name)
  mutate(p)
  writeProfile(name, p)
}

async function waitFor (pred, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(step) }
  return false
}

/** Window slot (player inventory window) holding `id`, or -1. */
const slotOf = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 5 && i < 45 && cid(s) === id)
const cursor = bot => cid(bot.inventory.selectedItem)
const carried = bot => bot.inventory.slots.slice(5, 45).filter(s => s && !String(cid(s)).startsWith('ui/')).map(cid)

/** Picks `id` up onto the cursor with a left click in the inventory screen. */
async function pickUp (bot, id) {
  const slot = slotOf(bot, id)
  if (slot < 0) { check(`${bot.username}: has a ${id} to pick up`, false, carried(bot)); return }
  await bot.clickWindow(slot, 0, 0)
  await sleep(800)
  check(`${bot.username}: ${id} held on the cursor`, cursor(bot) === id && slotOf(bot, id) < 0, { cursor: cursor(bot), carried: carried(bot) })
}

/** Queues the bots for the proving ground (each solo; one raid takes them all) and waits until they land. */
async function deploy (bots) {
  const running = await rcon('cv raids')
  if (running.includes('RUNNING')) log('WARNING: a raid is still running and the bots may be put into it:', running)
  const before = bots.map(b => b.worldChanges)
  for (const b of bots) b.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => bots.every((b, i) => b.game.gameMode === 'adventure' && b.worldChanges > before[i]), 90000)
  check('deployed into one raid', ok, await rcon('cv raids'))
  await sleep(4000) // past the spawn protection
}

// ------------------------------------------------------------------ server

const latestLog = () => { try { return fs.readFileSync(path.join(SERVER_DIR, 'logs', 'latest.log'), 'utf8') } catch { return '' } }
const portOpen = port => new Promise(resolve => {
  const s = net.createConnection({ host: '127.0.0.1', port })
  s.once('connect', () => { s.destroy(); resolve(true) })
  s.once('error', () => resolve(false))
})
/** Java processes running paper.jar from SERVER_DIR (Linux /proc); null where /proc is missing. */
function serverPids () {
  if (!fs.existsSync('/proc/self/cwd')) return null
  const out = []
  for (const pid of fs.readdirSync('/proc').filter(p => /^\d+$/.test(p))) {
    try {
      if (fs.readlinkSync(`/proc/${pid}/cwd`) === SERVER_REAL && fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('paper.jar')) out.push(pid)
    } catch { }
  }
  return out
}

async function stopServer () {
  await Promise.race([rcon('stop').catch(() => {}), sleep(5000)])
  const down = await waitFor(async () => {
    const pids = serverPids()
    return (pids === null || pids.length === 0) && !(await portOpen(PORT)) && !(await portOpen(RCON.port))
  }, 90000, 500)
  check('the server stops', down)
  await sleep(1000)
}

async function startServer (since) {
  const out = fs.openSync(path.join(SERVER_DIR, 'console.log'), 'a')
  spawn(path.join(SERVER_DIR, 'start.sh'), [], { cwd: SERVER_DIR, detached: true, stdio: ['ignore', out, out] }).unref()
  const up = await waitFor(async () => {
    if (!(await portOpen(RCON.port))) return false
    return /Done \(/.test(latestLog()) && fs.statSync(path.join(SERVER_DIR, 'logs', 'latest.log')).mtimeMs > since
  }, 180000, 1000)
  check('the server is back', up)
  if (up) await sleep(2000)
}

// ------------------------------------------------------------------ scenarios

async function deployScenario () {
  const restore = await pinInsurance(DATA, rcon, log)
  try {
    await deployOne()
  } finally {
    await restore()
  }
}

async function deployOne () {
  const names = { insured: 'Ci' + tag, carrier: 'Cg' + tag }
  await prepare(names.insured, p => {
    p.autoInsure = true
    p.money = 1000000
    p.mailbox = []
    p.claims = []
    p.loadout = { 0: loaded('m4c', 'ammo_556_m855'), 38: rec('armor_3', { dura: 80 }) }
  })
  await prepare(names.carrier, p => {
    p.autoInsure = false
    p.mailbox = []
    p.loadout = { 0: rec('gold_bar') }
  })
  const insured = await make(names.insured)
  const carrier = await make(names.carrier)
  await pickUp(insured, 'm4c')
  await pickUp(carrier, 'gold_bar')
  await deploy([insured, carrier])

  check('insured: the m4c on the cursor is insured with the armour', insured.chats.some(c => c.includes('已为 2 件装备投保')),
    insured.chats.filter(c => c.includes('投保')))
  const record = readProfile(names.insured).deployment
  check('insured: the deployment record holds the m4c', !!record && countIds(record.loadout, 'm4c') === 1, record && ids(record.loadout))
  check('insured: the m4c is carried in the raid', slotOf(insured, 'm4c') >= 0 && !cursor(insured), carried(insured))

  // The carrier extracts with what it brought in: no profit.
  const before = carrier.worldChanges
  await rcon(`execute as ${names.carrier} at @s run tp @s 106.5 65 8.5`)
  check('carrier: extracted', await waitFor(() => carrier.worldChanges > before, 30000))
  await sleep(2500)
  const line = carrier.chats.find(c => c.includes('撤离成功')) || ''
  check('carrier: the report shows a net profit of 0', line.includes('净收益 +◈0 '), line)

  await rcon('kill ' + names.insured)
  check('insured: the claim is announced', await waitFor(() => insured.chats.some(c => c.includes('保险理赔')), 30000),
    insured.chats.filter(c => c.includes('保险')))
  await sleep(1000)
  await leave(insured)
  await leave(carrier)

  const c = readProfile(names.carrier)
  const report = (c.reports || [])[0]
  check('carrier: the saved report has no profit', !!report && report.extractedValue === 0, report && report.extractedValue)
  check('carrier: the gold bar came home once', countIds(c.loadout, 'gold_bar') === 1 && countIds(c.mailbox, 'gold_bar') === 0,
    { loadout: ids(c.loadout), mailbox: ids(c.mailbox) })
  const i = readProfile(names.insured)
  check('insured: the claim sent the m4c and the armour back', JSON.stringify(ids(i.mailbox)) === JSON.stringify(['armor_3', 'm4c']), ids(i.mailbox))
}

async function stopScenario () {
  const names = { lobby: 'Cl' + tag, full: 'Cf' + tag, own: 'Co' + tag, finder: 'Cw' + tag }
  await prepare(names.lobby, p => { p.mailbox = []; p.loadout = { 0: rec('gold_bar') } })
  // Every pocket full but for the server drive's, and no rig or backpack.
  await prepare(names.full, p => {
    p.mailbox = []
    p.loadout = { 0: rec('server_drive') }
    for (const s of [1, 3, 4, 5, 6, 7]) p.loadout[s] = rec('screws')
  })
  await prepare(names.own, p => { p.mailbox = []; p.loadout = { 0: rec('gold_bar'), 1: rec('screws') } })
  await prepare(names.finder, p => { p.mailbox = []; p.loadout = { 1: rec('screws') } })

  const own = await make(names.own)
  const finder = await make(names.finder)
  await deploy([own, finder])
  const wentIn = readProfile(names.own).deployment
  check('own: deployment recorded', !!wentIn && countIds(wentIn.loadout, 'gold_bar') === 1, wentIn && ids(wentIn.loadout))
  await pickUp(own, 'gold_bar')
  await rcon(`cv give ${names.finder} pocket_watch`)
  await sleep(800)
  await pickUp(finder, 'pocket_watch')

  const lobby = await make(names.lobby)
  const full = await make(names.full)
  await pickUp(lobby, 'gold_bar')
  await pickUp(full, 'server_drive')
  await rcon(`cv give ${names.full} dossier`)
  await sleep(800)
  check('full: every pocket taken again', slotOf(full, 'dossier') >= 0, carried(full))
  await sleep(3000) // a periodic lobby save, which leaves the cursor alone

  const stoppedAt = Date.now()
  log('stopping the server')
  await stopServer()
  const text = latestLog()
  check('no failed shutdown step', !/Shutdown step .*failed/.test(text), (text.match(/Shutdown step .*failed.*/) || [''])[0])
  check('no "Error occurred while disabling Craftov"', !text.includes('Error occurred while disabling Craftov'))

  const l = readProfile(names.lobby)
  check('lobby: the gold bar is in the loadout, once', countIds(l.loadout, 'gold_bar') === 1 && countIds(l.mailbox, 'gold_bar') === 0,
    { loadout: ids(l.loadout), mailbox: ids(l.mailbox) })
  const f = readProfile(names.full)
  check('full: the server drive went to the mailbox, once', countIds(f.mailbox, 'server_drive') === 1 && countIds(f.loadout, 'server_drive') === 0,
    { loadout: ids(f.loadout), mailbox: ids(f.mailbox) })
  check('full: the rest of the loadout is kept', countIds(f.loadout, 'dossier') === 1 && countIds(f.loadout, 'screws') === 6, ids(f.loadout))
  const o = readProfile(names.own)
  check('own: the raid was voided', !o.deployment && !o.activeRaid, { deployment: !!o.deployment, activeRaid: o.activeRaid })
  check('own: the loadout as it went in, the gold bar once',
    JSON.stringify(ids(o.loadout)) === JSON.stringify(ids(wentIn && wentIn.loadout)) && countIds(o.loadout, 'gold_bar') === 1
      && countIds(o.mailbox, 'gold_bar') === 0, { loadout: ids(o.loadout), mailbox: ids(o.mailbox) })
  const w = readProfile(names.finder)
  const watches = countIds(w.loadout, 'pocket_watch') + countIds(w.mailbox, 'pocket_watch') + countIds(w.stash, 'pocket_watch')
  check('finder: the watch found in the raid stayed there', watches === 0 && !w.deployment,
    { loadout: ids(w.loadout), mailbox: ids(w.mailbox), stash: ids(w.stash) })

  await startServer(stoppedAt)
  const back = await make(names.lobby)
  check('lobby: carries the gold bar after the restart', slotOf(back, 'gold_bar') >= 0 && !cursor(back), carried(back))
  await leave(back)
}

const scenarios = {
  deploy: deployScenario,
  stop: stopScenario,
  all: async () => { await deployScenario(); await stopScenario() }
}

;(async () => {
  try {
    if (!scenarios[scenario]) throw new Error('unknown scenario ' + scenario + ' (deploy | stop | all)')
    await scenarios[scenario]()
  } catch (e) {
    failures++
    log('ERROR', e.stack)
  }
  log(failures === 0 ? 'ALL PASSED' : failures + ' FAILED')
  process.exit(failures === 0 ? 0 : 1)
})()
