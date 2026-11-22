// Server stop mid-raid (issue #19). Seven bots set the scene, then the server is stopped over RCON and
// started again, and the saves are checked on disk and in game:
//   raider  - in a solo raid on the test map: picks something up (cv give) and moves a bandage into
//             the secure container. Must keep both, with no "raid voided by a crash" on rejoin.
//   dead    - insured, killed in the same raid while it is still running. The insured rifle and
//             armour left on the body must come back to the mailbox when the server stops.
//   gone    - insured, in the same raid: throws the insured rifle on the ground and disconnects, so
//             the stop finds them inside the reconnect window. Shutdown restores what they carried
//             and then pays out the insurance for the same offline profile: both must stick (raid
//             not voided on rejoin, rifle in the mailbox once, not also back in the loadout).
//   flyer   - insured, queued last so the stop lands while the insertion camera is still running.
//             The secure container must stay shut meanwhile; it must go home with the gear and
//             get the insurance premium back.
//   drop    - insured, queued with the flyer, disconnects during the insertion camera. Never
//             landed either: gear kept and the fees back, although offline at the stop.
//   ranger  - on the private range. Must get the lobby kit back, no range loaners.
//   boss    - posts an escort contract; cv money +1000 shortly before the stop. Must end with the
//             balance from before posting plus the 1000.
// Also: no "Error occurred while disabling Craftov" or failed shutdown step in the log, a new
// -shutdown.zip backup, and no craftov_raid_* folder left once the server is back.
//
//   PORT=25565 RCON_PORT=25575 SERVER_DIR=../../server/run node shutdown.js [--crash]
//
// --crash adds two steps that kill the server outright (SIGKILL; Linux only, the process is found in
// /proc): once with an escort contract open, checking that the bounty comes back when its owner
// rejoins; and once a second after the boss of a contract died in a raid whose escort had already
// died and logged off, checking that the escort keeps the share and the boss is not refunded again.
// On the way the dead escort, spectating the boss, must find the secure container and stash shut.
//
// The server must be running. After the stop the script starts it again with SERVER_DIR/start.sh
// (detached, output appended to SERVER_DIR/console.log); with NO_START=1 it only waits for somebody
// else (a restart loop) to bring it back. Exits 0 when every check passes, 1 otherwise. For the run
// SERVER_DIR/plugins/Craftov/config.yml gets insurance.loss-chance and return-minutes 0 (every
// insured item back at once); the file is put back at the end.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const pinInsurance = require('./insurance')
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
const NAMES = { raider: 'SdRaid' + tag, dead: 'SdDead' + tag, gone: 'SdGone' + tag, flyer: 'SdFly' + tag, drop: 'SdDrop' + tag, ranger: 'SdRng' + tag, boss: 'SdBoss' + tag }
// Only joins for the --crash escort step.
const EXTRA = { guard: 'SdGrd' + tag }
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

// Results of a click or a command now come on the action bar (util/Notify), not in chat: both are
// "what the player was told", so the bots log them together.
function flatText (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flatText).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flatText(n.value && n.value.value)
  if (n.type === 'compound') return flatText(n.value)
  return flatText(n.text) + flatText(n.extra)
}

const bots = {}
function make (role) {
  const name = NAMES[role] || EXTRA[role]
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.chatLog = []
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLog.push(m); log(role, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flatText(p.text); if (s.trim()) { bot.chatLog.push(s); log(role, 'BAR', s) } })
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
  for (const item of ['armor_3', 'm4c']) await rcon(`cv give ${NAMES.gone} ${item}`)
  for (const item of ['armor_3', 'm4c']) await rcon(`cv give ${NAMES.flyer} ${item}`)
  for (const item of ['armor_3', 'm4c']) await rcon(`cv give ${NAMES.drop} ${item}`)
  for (const item of ['helmet_3', 'armor_3', 'm4c', 'medkit_s']) await rcon(`cv give ${NAMES.ranger} ${item}`)
  await sleep(2500)
  await insure('dead')
  await insure('gone')
  await insure('flyer')
  await insure('drop')

  // The raid: raider, dead and gone queue together and land in one raid.
  const raiders = [NAMES.raider, NAMES.dead, NAMES.gone]
  await queueSolo('raider')
  await queueSolo('dead')
  await queueSolo('gone')
  let landed = true
  for (const name of raiders) {
    landed = landed && await waitFor(async () => await inRaidWorld(name) && await inMode(name, 'adventure'), 120000, 1000)
  }
  check('raider, dead and gone land in a raid', landed, (await Promise.all(raiders.map(dimension))).join(' / '))
  if (!landed) return false
  check('all three are in the same raid', new Set(await Promise.all(raiders.map(dimension))).size === 1)
  expect.raiderMoney = await balance(NAMES.raider)
  await sleep(3500) // spawn protection

  // Gone: the insured rifle goes on the ground (from the inventory screen; Q alone reloads), then
  // the connection drops. The stop must come inside the 120 s reconnect window.
  const g = bots.gone
  const rifle = g.inventory.items().find(i => itemId(i) === 'm4c')
  if (rifle) {
    await g.tossStack(rifle).catch(e => log('gone', 'toss', e.message))
    await sleep(800)
  }
  const thrown = !g.inventory.items().some(i => itemId(i) === 'm4c')
  check('gone throws the insured rifle on the ground', !!rifle && thrown, g.inventory.items().map(itemId).join(','))
  g.quit()
  delete bots.gone
  await sleep(1500)

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
  // The kill feed is on the HUD, for the dead player's own squad: `cv hud` prints it.
  const died = await waitFor(async () => /feedlog=[^|]*☠/.test(await rcon(`cv hud ${NAMES.dead}`)), 4000)
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

  // Flyer and drop: a second raid (nobody joins a running one); stop while its insertion camera runs.
  expect.flyerMoney = await balance(NAMES.flyer)
  expect.dropMoney = await balance(NAMES.drop)
  await queueSolo('flyer')
  await queueSolo('drop')
  const flying = await waitFor(async () => await inRaidWorld(NAMES.flyer) && await inMode(NAMES.flyer, 'spectator'), 15000, 200)
  check('flyer is in the insertion camera', flying, await dimension(NAMES.flyer))
  const dropFlying = await waitFor(async () => await inRaidWorld(NAMES.drop) && await inMode(NAMES.drop, 'spectator'), 3000, 200)
  check('drop is in the insertion camera', dropFlying, await dimension(NAMES.drop))
  bots.drop.quit()
  delete bots.drop
  await sleep(800)
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
  check('no failed shutdown step', !/Shutdown step .*failed/.test(text), (text.match(/Shutdown step .*failed.*/) || [''])[0])
  check('no IllegalPluginAccessException', !text.includes('IllegalPluginAccessException'))
  const zips = backups().filter(f => f.endsWith('-shutdown.zip') && !zipsBefore.includes(f))
  check('a -shutdown.zip backup was written', zips.length === 1, zips.join(' '))

  // The saves as they are on disk now.
  const raider = profile(NAMES.raider)
  check('raider: activeRaid cleared', raider.activeRaid == null, String(raider.activeRaid))
  check('raider: kept what was picked up in the raid', ids(raider.loadout).includes('splint') && ids(raider.loadout).includes('m4c'), ids(raider.loadout).join(','))
  check('raider: secure container change kept', ids(raider.secure).includes('bandage'), ids(raider.secure).join(','))
  check('raider: balance unchanged', raider.money === expect.raiderMoney, `${expect.raiderMoney} -> ${raider.money}`)

  const goneP = profile(NAMES.gone)
  const count = (records, id) => Object.values(records || {}).filter(r => r.id === id).length
  check('gone: activeRaid cleared (the restore was not undone)', goneP.activeRaid == null, String(goneP.activeRaid))
  check('gone: carried gear kept, without the thrown rifle', ids(goneP.loadout).includes('armor_3') && !ids(goneP.loadout).includes('m4c'), ids(goneP.loadout).join(','))
  check('gone: thrown insured rifle in the mailbox exactly once', count(goneP.mailbox, 'm4c') === 1, ids(goneP.mailbox).join(','))

  const dead = profile(NAMES.dead)
  check('dead: insured rifle and armour in the mailbox', ['m4c', 'armor_3'].every(i => ids(dead.mailbox).includes(i)), ids(dead.mailbox).join(','))
  check('dead: activeRaid cleared', dead.activeRaid == null)

  const flyer = profile(NAMES.flyer)
  check('flyer: activeRaid cleared', flyer.activeRaid == null, String(flyer.activeRaid))
  check('flyer: insurance premium refunded', flyer.money === expect.flyerMoney, `${expect.flyerMoney} -> ${flyer.money}`)
  check('flyer: gear kept', ['m4c', 'armor_3'].every(i => ids(flyer.loadout).includes(i)), ids(flyer.loadout).join(','))

  const drop = profile(NAMES.drop)
  check('drop: activeRaid cleared', drop.activeRaid == null, String(drop.activeRaid))
  check('drop: fees refunded although offline', drop.money === expect.dropMoney, `${expect.dropMoney} -> ${drop.money}`)
  check('drop: gear kept', ['m4c', 'armor_3'].every(i => ids(drop.loadout).includes(i)), ids(drop.loadout).join(','))

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
  const g = await make('gone')
  await sleep(4000)
  check('gone: no "voided by a crash" message on rejoin', !g.chatLog.some(m => m.includes('因服务器中断已作废')))
  const worn = g.inventory.items().map(itemId)
  check('gone: back with the armour and without the rifle', worn.includes('armor_3') && !worn.includes('m4c'), worn.join(','))
  g.quit()
  delete bots.gone
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

// --crash, second kill: the boss dies in a raid after the escort died and logged off. The escort's
// share is written at once; the boss's escrow must be released on disk no later than that, or the
// boss would get the whole bounty back on rejoin while the escort keeps the share.
async function crashEscort () {
  if (serverPids() === null) return
  const c = bots.boss
  const e = await make('guard')
  await sleep(3000)
  const reward = 20000 // first button in the escort hall
  const share = Math.round(reward * 0.3) // escort.failure-share, all of it to the one escort
  await openWindow(c, () => c.chat('/cv'))
  const hall = await openWindow(c, () => c.clickWindow(16, 0, 0))
  if (hall) { await c.clickWindow(38, 0, 0); await sleep(600); c.closeWindow(hall) }
  await openWindow(e, () => e.chat('/cv'))
  const list = await openWindow(e, () => e.clickWindow(16, 0, 0))
  if (list) { await e.clickWindow(0, 0, 0); await sleep(800); e.closeWindow(list) }
  check('escort crash: guard accepts the contract', e.chatLog.some(m => m.includes('已接取') && m.includes(NAMES.boss)))
  c.chat('/cv queue proving NORMAL TRIO')
  let landed = true
  for (const name of [NAMES.boss, EXTRA.guard]) {
    landed = landed && await waitFor(async () => await inRaidWorld(name) && await inMode(name, 'adventure'), 120000, 1000)
  }
  check('escort crash: boss and guard land in a raid', landed)
  if (!landed) return
  await sleep(3500) // spawn protection

  // With a squadmate still up the kill only downs the guard. Only hostile fire finishes a downed
  // player (and a squadmate's does not count), so a stand-in lands the last hit. It is summoned and
  // hits in one command: stray entities do not last until the next one.
  await rcon(`kill ${EXTRA.guard}`)
  // A squadmate going down is an alert on the boss's HUD banner.
  if (await waitFor(async () => { const h = await rcon(`cv hud ${c.username}`); return h.includes(EXTRA.guard) && h.includes('倒地') }, 2000)) {
    await sleep(500)
    log('finish', await rcon(`execute at ${EXTRA.guard} summon armor_stand run damage ${EXTRA.guard} 1000 minecraft:mob_attack by @s`))
  }
  // After the death report the guard watches the boss. That is no longer "in the raid", but the
  // secure container and the lobby menus must stay shut until they are back in the lobby.
  // The death report comes first; a dead player still in the raid world in spectator mode after it
  // is watching a squadmate (otherwise they would be back in the lobby).
  const reported = await waitFor(() => e.chatLog.some(m => m.trim().startsWith('行动失败')), 15000)
  await sleep(1000)
  const watching = reported && await inRaidWorld(EXTRA.guard) && await inMode(EXTRA.guard, 'spectator')
  check('escort crash: dead guard spectates the boss', watching, await dimension(EXTRA.guard))
  if (watching) {
    let from = e.chatLog.length
    const secure = await openWindow(e, () => e.chat('/cv secure'), 1500)
    if (secure) e.closeWindow(secure)
    check('spectating: the secure container stays shut', !secure && e.chatLog.slice(from).some(m => m.includes('无法打开安全箱')))
    from = e.chatLog.length
    const stash = await openWindow(e, () => e.chat('/cv stash'), 1500)
    if (stash) e.closeWindow(stash)
    check('spectating: the stash stays shut', !stash && e.chatLog.slice(from).some(m => m.includes('观战中无法使用')))
  }
  e.quit()
  delete bots.guard
  await sleep(2000) // the quit save
  const guardBefore = profile(EXTRA.guard).money
  const bossBefore = await balance(NAMES.boss)
  const from = c.chatLog.length
  await rcon(`kill ${NAMES.boss}`)
  await sleep(1000) // well inside the 10 s profile flush
  const killedAt = Date.now()
  for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  check('escort crash: the server is gone', await waitFor(() => serverPids().length === 0, 20000, 500))
  c.quit()
  delete bots.boss
  await sleep(1500)
  // A level-up on death pays a reward too.
  const levelUp = c.chatLog.slice(from).map(m => (m.match(/账号升级.*奖励 ◈([\d,]+)/) || [, '0'])[1]).reduce((a, b) => a + Number(b.replace(/,/g, '')), 0)
  const boss = profile(NAMES.boss)
  check('escort crash: guard keeps the share', profile(EXTRA.guard).money === guardBefore + share, `${guardBefore} -> ${profile(EXTRA.guard).money}`)
  check('escort crash: boss escrow released on disk', !boss.escortEscrow, String(boss.escortEscrow))
  check('escort crash: boss got the rest back once', boss.money === bossBefore + reward - share + levelUp, `expected ${bossBefore + reward - share + levelUp}, got ${boss.money}`)
  if (!(await start(killedAt))) return
  const back = await make('boss')
  await sleep(4000)
  check('escort crash: no second refund on rejoin', !back.chatLog.some(m => m.includes('护航委托因服务器中断已撤销')))
  const money = await balance(NAMES.boss)
  check('escort crash: balance after rejoin', money === boss.money, `expected ${boss.money}, got ${money}`)
}

;(async () => {
  // Every insured item must come back at once for the checks above (insurance.js): pinned for the
  // run, read again on the restart, and put back at the end.
  let restoreInsurance = async () => {}
  try {
    restoreInsurance = await pinInsurance(path.join(SERVER_DIR, 'plugins', 'Craftov'), rcon, log)
    if (await setup() && await stopAndRestart()) {
      await afterRestart()
      if (process.argv.includes('--crash')) {
        await crash()
        await crashEscort()
      }
    }
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  await restoreInsurance()
  for (const bot of Object.values(bots)) { try { bot.quit() } catch { } }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
setTimeout(() => { log('timeout'); process.exit(1) }, 600000)
