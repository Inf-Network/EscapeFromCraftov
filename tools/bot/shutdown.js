// Server stop mid-raid (issue #19). Five bots set the scene, then the server is stopped over RCON and
// started again, and the saves are checked on disk and in game:
//   raider  - in a solo raid on the test map: picks something up (cv give) and moves a bandage into
//             the secure container. Must keep both, with no "raid voided by a crash" on rejoin.
//   dead    - insured, killed in the same raid while it is still running. The insured rifle and
//             armour left on the body must come back to the mailbox when the server stops.
//   flyer   - insured, queued last so the stop lands while the insertion camera is still running.
//             The secure container must stay shut meanwhile; it must go home with the gear and
//             get the insurance premium back.
//   ranger  - on the private range. Must get the lobby kit back, no range loaners.
//   boss    - posts an escort contract; cv money +1000 shortly before the stop. Must end with the
//             balance from before posting plus the 1000.
// Also: no "Error occurred while disabling Craftov" or failed shutdown step in the log, a new
// -shutdown.zip backup, and no craftov_raid_* folder left once the server is back.
//
//   PORT=25565 RCON_PORT=25575 SERVER_DIR=../../server/run node shutdown.js [--crash]
//
// --crash adds a last step that kills the server outright (SIGKILL) with an escort contract open and
// checks that the bounty comes back when its owner rejoins (Linux only: the process is found in /proc).
//
// The server must be running. After the stop the script starts it again with SERVER_DIR/start.sh
// (detached, output appended to SERVER_DIR/console.log); with NO_START=1 it only waits for somebody
// else (a restart loop) to bring it back. Exits 0 when every check passes, 1 otherwise.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const fs = require('fs')
const net = require('net')
const path = require('path')
const crypto = require('crypto')
const { spawn } = require('child_process')

const PORT = Number(process.env.PORT || 25565)
const RCON_PORT = Number(process.env.RCON_PORT || 25575)
const RCON_PASSWORD = process.env.RCON_PASSWORD || 'craftov-local'
const SERVER_DIR = path.resolve(process.env.SERVER_DIR || path.join(__dirname, '..', '..', 'server', 'run'))
const NO_START = !!process.env.NO_START
const SERVER_REAL = fs.realpathSync(SERVER_DIR)
const rcon = cmd => rconRaw(cmd, { port: RCON_PORT, password: RCON_PASSWORD }).then(s => s.replace(/§./g, ''))
const tag = String(Math.floor(1000 + Math.random() * 9000))
const NAMES = { raider: 'SdRaid' + tag, dead: 'SdDead' + tag, flyer: 'SdFly' + tag, ranger: 'SdRng' + tag, boss: 'SdBoss' + tag }
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

// ------------------------------------------------------------------ helpers

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(SERVER_DIR, 'plugins', 'Craftov', 'data', 'players', offlineUuid(name) + '.json')
const profile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const ids = records => Object.values(records || {}).map(r => r.id)
const backups = () => { try { return fs.readdirSync(path.join(SERVER_DIR, 'plugins', 'Craftov', 'backups')) } catch { return [] } }
const raidFolders = () => fs.readdirSync(SERVER_DIR).filter(f => f.startsWith('craftov_raid_'))
const latestLog = () => { try { return fs.readFileSync(path.join(SERVER_DIR, 'logs', 'latest.log'), 'utf8') } catch { return '' } }
const balance = async name => Number(((await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/) || [, 'NaN'])[1].replace(/,/g, ''))

async function waitFor (pred, ms, every = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}
const portOpen = port => new Promise(resolve => {
  const s = net.createConnection({ host: '127.0.0.1', port })
  s.once('connect', () => { s.destroy(); resolve(true) })
  s.once('error', () => resolve(false))
})
// Java processes running paper.jar from SERVER_DIR (Linux /proc); null where /proc is missing.
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

const bots = {}
function make (role) {
  const name = NAMES[role]
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.chatLog = []
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLog.push(m); log(role, 'CHAT', m) } })
  bot.on('kicked', r => log(role, 'KICKED', JSON.stringify(r).slice(0, 200)))
  bot.on('error', e => log(role, 'ERROR', e.message))
  bots[role] = bot
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(role + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(timer); resolve(bot) })
  })
}
function openWindow (bot, fn, timeout = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, timeout)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}
const itemId = (item) => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : (item ? item.name : null) }
const dimension = async name => (await rcon(`data get entity ${name} Dimension`)).replace(/.*: /, '').replace(/"/g, '').trim()
const inRaidWorld = async name => /craftov_raid_/.test(await dimension(name))
// Game mode as the server has it (the client's copy can lag behind a world change).
const inMode = async (name, mode) => /passed/i.test(await rcon(`execute if entity @a[name=${name},gamemode=${mode}]`))

async function insure (role) {
  const bot = bots[role]
  const w = await openWindow(bot, () => bot.chat('/cv deploy'))
  if (!w) return check(`${role}: deploy menu opens`, false)
  const before = JSON.stringify(w.slots[34] && w.slots[34].components || '')
  await bot.clickWindow(34, 0, 0)
  await sleep(500)
  const after = JSON.stringify(w.slots[34] && w.slots[34].components || '')
  bot.closeWindow(w)
  check(`${role}: insurance switched on`, after.includes('●') || (before.includes('○') && before !== after), after.slice(0, 120))
}

async function queueSolo (role) {
  bots[role].chat('/cv queue proving NORMAL SOLO')
}

// ------------------------------------------------------------------ before the stop

const expect = {}

async function setup () {
  for (const role of Object.keys(NAMES)) { await make(role); await sleep(1500) }
  await sleep(3000)

  // Kits. Everyone gets something insurable so the premium is not zero.
  for (const item of ['armor_3', 'm4c', 'ammo_556_m855 60', 'bandage 3']) await rcon(`cv give ${NAMES.raider} ${item}`)
  for (const item of ['armor_3', 'm4c']) await rcon(`cv give ${NAMES.dead} ${item}`)
  for (const item of ['armor_3', 'm4c']) await rcon(`cv give ${NAMES.flyer} ${item}`)
  for (const item of ['helmet_3', 'armor_3', 'm4c', 'medkit_s']) await rcon(`cv give ${NAMES.ranger} ${item}`)
  await sleep(2500)
  await insure('dead')
  await insure('flyer')

  // The raid: raider and dead queue together and land in one raid.
  await queueSolo('raider')
  await queueSolo('dead')
  const landed = await waitFor(async () => await inRaidWorld(NAMES.raider) && await inRaidWorld(NAMES.dead)
    && await inMode(NAMES.raider, 'adventure') && await inMode(NAMES.dead, 'adventure'), 120000, 1000)
  check('raider and dead land in a raid', landed, `${await dimension(NAMES.raider)} / ${await dimension(NAMES.dead)}`)
  if (!landed) return false
  check('both are in the same raid', await dimension(NAMES.raider) === await dimension(NAMES.dead))
  expect.raiderMoney = await balance(NAMES.raider)
  await sleep(3500) // spawn protection

  // Raider: "loot" a splint and move a bandage into the secure container.
  await rcon(`cv give ${NAMES.raider} splint`)
  await sleep(800)
  const r = bots.raider
  const secure = await openWindow(r, () => r.chat('/cv secure'))
  if (secure) {
    const slot = secure.slots.findIndex((s, i) => i >= secure.inventoryStart && s && itemId(s) === 'bandage')
    if (slot >= 0) { await r.clickWindow(slot, 0, 0); await sleep(500) }
    const moved = secure.slots.slice(0, 9).some(s => s && itemId(s) === 'bandage')
    check('raider moves a bandage into the secure container mid-raid', moved)
    r.closeWindow(secure)
  } else check('raider opens the secure container mid-raid', false)

  // Dead: killed with the insured gear on, while the raid keeps running for the raider.
  await rcon(`kill ${NAMES.dead}`)
  const died = await waitFor(() => bots.raider.chatLog.some(m => m.includes('☠') && m.includes(NAMES.dead)), 4000)
  check('dead is killed in the raid (kill feed, spectating)', died && await inMode(NAMES.dead, 'spectator'))

  // Ranger: kit captured in the lobby, then onto the private range.
  await rcon(`execute as ${NAMES.ranger} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bots.ranger.nearestEntity(e => e.name === 'interaction')
  if (rack) bots.ranger.activateEntity(rack)
  const onRange = await waitFor(async () => (await dimension(NAMES.ranger)).includes('craftov_range'), 6000, 500)
  check('ranger is on the private range', onRange, await dimension(NAMES.ranger))

  // Boss: post an escort contract (terminal -> escort hall -> first reward button).
  const c = bots.boss
  expect.bossMoney = await balance(NAMES.boss)
  await openWindow(c, () => c.chat('/cv'))
  const hall = await openWindow(c, () => c.clickWindow(16, 0, 0))
  if (hall) {
    await c.clickWindow(38, 0, 0)
    await sleep(600)
    c.closeWindow(hall)
  }
  const posted = await balance(NAMES.boss)
  check('boss posts an escort contract (bounty held)', posted < expect.bossMoney, `${expect.bossMoney} -> ${posted}`)

  // Flyer: backfills into the running raid; stop while the insertion camera runs.
  expect.flyerMoney = await balance(NAMES.flyer)
  await queueSolo('flyer')
  const flying = await waitFor(async () => await inRaidWorld(NAMES.flyer) && await inMode(NAMES.flyer, 'spectator'), 15000, 200)
  check('flyer is in the insertion camera', flying, await dimension(NAMES.flyer))
  // Anything moved in the secure container now would be overwritten on landing: it stays shut.
  const f = bots.flyer
  const from = f.chatLog.length
  const shut = await openWindow(f, () => f.chat('/cv secure'), 800)
  if (shut) f.closeWindow(shut)
  check('flyer cannot open the secure container during the fly-in', !shut && f.chatLog.slice(from).some(m => m.includes('无法打开安全箱')))
  return true
}

// ------------------------------------------------------------------ the stop

async function stopAndRestart () {
  const zipsBefore = backups().filter(f => f.endsWith('-shutdown.zip'))
  await rcon(`cv money ${NAMES.boss} 1000`)
  expect.bossAfter = expect.bossMoney + 1000
  const stoppedAt = Date.now()
  await Promise.race([rcon('stop').catch(() => {}), sleep(5000)])
  const gone = await waitFor(async () => {
    const pids = serverPids()
    return (pids === null || pids.length === 0) && !(await portOpen(PORT)) && !(await portOpen(RCON_PORT))
  }, 90000, 500)
  check('the server stops', gone)
  await sleep(1000)
  log('stopped after', ((Date.now() - stoppedAt) / 1000).toFixed(1) + 's')

  // What the players saw before the connection dropped.
  const saw = (role, text) => bots[role].chatLog.some(m => m.includes(text))
  check('raider is told the raid was stopped and the gear kept', saw('raider', '服务器维护：本次行动已中止，你携带的物资已保留'))
  check('flyer is told the fees were refunded', saw('flyer', '行动在入场前中止，入场费和保险费'))
  check('dead is told the insured gear came back', saw('dead', '保险理赔'))

  const text = latestLog()
  check('no "Error occurred while disabling Craftov"', !text.includes('Error occurred while disabling Craftov'))
  check('no failed shutdown step', !text.includes('Shutdown step failed'), (text.match(/Shutdown step failed.*/) || [''])[0])
  check('no IllegalPluginAccessException', !text.includes('IllegalPluginAccessException'))
  const zips = backups().filter(f => f.endsWith('-shutdown.zip') && !zipsBefore.includes(f))
  check('a -shutdown.zip backup was written', zips.length === 1, zips.join(' '))

  // The saves as they are on disk now.
  const raider = profile(NAMES.raider)
  check('raider: activeRaid cleared', raider.activeRaid == null, String(raider.activeRaid))
  check('raider: kept what was picked up in the raid', ids(raider.loadout).includes('splint') && ids(raider.loadout).includes('m4c'), ids(raider.loadout).join(','))
  check('raider: secure container change kept', ids(raider.secure).includes('bandage'), ids(raider.secure).join(','))
  check('raider: balance unchanged', raider.money === expect.raiderMoney, `${expect.raiderMoney} -> ${raider.money}`)

  const dead = profile(NAMES.dead)
  check('dead: insured rifle and armour in the mailbox', ['m4c', 'armor_3'].every(i => ids(dead.mailbox).includes(i)), ids(dead.mailbox).join(','))
  check('dead: activeRaid cleared', dead.activeRaid == null)

  const flyer = profile(NAMES.flyer)
  check('flyer: activeRaid cleared', flyer.activeRaid == null, String(flyer.activeRaid))
  check('flyer: insurance premium refunded', flyer.money === expect.flyerMoney, `${expect.flyerMoney} -> ${flyer.money}`)
  check('flyer: gear kept', ['m4c', 'armor_3'].every(i => ids(flyer.loadout).includes(i)), ids(flyer.loadout).join(','))

  const ranger = profile(NAMES.ranger)
  check('ranger: lobby kit back', ['helmet_3', 'armor_3', 'm4c', 'medkit_s'].every(i => ids(ranger.loadout).includes(i)), ids(ranger.loadout).join(','))

  const boss = profile(NAMES.boss)
  check('boss: escort bounty refunded and the late +1000 kept', boss.money === expect.bossAfter, `expected ${expect.bossAfter}, got ${boss.money}`)
  check('boss: nothing left in escrow', !boss.escortEscrow, String(boss.escortEscrow))

  if (!(await start(stoppedAt))) return false
  check('no craftov_raid_* folder left', raidFolders().length === 0, raidFolders().join(' '))
  return true
}

// Starts the server again (unless NO_START) and waits until a log written after `since` says Done.
async function start (since) {
  if (!NO_START) {
    const out = fs.openSync(path.join(SERVER_DIR, 'console.log'), 'a')
    const child = spawn(path.join(SERVER_DIR, 'start.sh'), [], { cwd: SERVER_DIR, detached: true, stdio: ['ignore', out, out] })
    child.unref()
    log('started', path.join(SERVER_DIR, 'start.sh'))
  }
  const up = await waitFor(async () => {
    if (!(await portOpen(RCON_PORT))) return false
    return /Done \(/.test(latestLog()) && fs.statSync(path.join(SERVER_DIR, 'logs', 'latest.log')).mtimeMs > since
  }, 180000, 1000)
  check('the server is back', up)
  if (up) await sleep(2000)
  return up
}

// ------------------------------------------------------------------ after the restart

async function afterRestart () {
  const r = await make('raider')
  await make('boss')
  await sleep(4000)
  check('raider: no "voided by a crash" message on rejoin', !r.chatLog.some(m => m.includes('因服务器中断已作废')))
  check('raider: back in the lobby', (await dimension(NAMES.raider)).includes('craftov_lobby'), await dimension(NAMES.raider))
  const held = r.inventory.items().map(itemId)
  check('raider: holds the splint from the raid', held.includes('splint'), held.join(','))
  const money = await balance(NAMES.boss)
  check('boss: balance after the restart', money === expect.bossAfter, `expected ${expect.bossAfter}, got ${money}`)
  r.quit()
  delete bots.raider
}

// --crash: the boss posts another contract, the server is killed outright (SIGKILL, so no shutdown
// code runs at all) once the profile has been flushed, and the bounty must come back on rejoin.
async function crash () {
  if (serverPids() === null) return log('SKIP --crash needs /proc to find the server process')
  const c = bots.boss
  const before = await balance(NAMES.boss)
  await openWindow(c, () => c.chat('/cv'))
  const hall = await openWindow(c, () => c.clickWindow(16, 0, 0))
  if (hall) { await c.clickWindow(38, 0, 0); await sleep(600); c.closeWindow(hall) }
  const held = await balance(NAMES.boss)
  check('crash: boss posts a second contract', held < before, `${before} -> ${held}`)
  await sleep(11000) // the profile flush runs every 10 s
  const killedAt = Date.now()
  for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  check('crash: the server is gone', await waitFor(() => serverPids().length === 0, 20000, 500))
  c.quit()
  delete bots.boss
  await sleep(1500)
  check('crash: the bounty is still held on disk', profile(NAMES.boss).escortEscrow === before - held, String(profile(NAMES.boss).escortEscrow))
  if (!(await start(killedAt))) return
  const back = await make('boss')
  await sleep(4000)
  check('crash: boss is told the contract was dropped', back.chatLog.some(m => m.includes('护航委托因服务器中断已撤销')))
  const money = await balance(NAMES.boss)
  check('crash: the bounty is back on rejoin', money === before, `expected ${before}, got ${money}`)
}

;(async () => {
  try {
    if (await setup() && await stopAndRestart()) {
      await afterRestart()
      if (process.argv.includes('--crash')) await crash()
    }
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  for (const bot of Object.values(bots)) { try { bot.quit() } catch { } }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
setTimeout(() => { log('timeout'); process.exit(1) }, 600000)
