// Server stop mid-raid (issues #19, #67): a raid the server stops in is void for everyone still in it.
// Eight bots set the scene, the server is stopped over RCON and started again, and the saves are
// checked on disk and in game:
//   raider  - ranked solo raid on the test map, insured, with a bandage in the secure container and
//             a daily contract one soldier kill from done (and one knife kill), one level-up away.
//             In the raid: "loots" a splint and moves it into the secure container, knifes a soldier
//             (contracts done, a level-up and its reward), fires the rifle and reloads it, uses a
//             bandage and throws the insured pistol on the ground. After the stop: loadout and
//             secure container slot for slot as they went in, the splint gone, the balance as before
//             queueing (premium back, contract, level and bounty money taken back), no insurance claim for
//             the pistol, rank points, level, experience, season pass, contracts, stats and reports
//             as before, and one letter "服务器维护补偿" whose ◈5,000 lands on claiming it.
//   dead    - insured, with a bounty on its head, knifed by the raider in the same raid while it
//             runs: settled as a death (insured rifle and armour from the body in the mailbox when
//             the server stops), no letter. The raider collects the bounty; since the raider's raid
//             is voided, the kill does not count and the bounty goes back on dead's head.
//   gone    - insured, same raid: throws the insured rifle on the ground and disconnects, so the stop
//             finds them inside the reconnect window. Voided offline: the loadout as it went in (the
//             rifle back once), no insurance claim for it, the premium back, one letter.
//   flyer   - insured, still on the entry camera at the stop: voided the same way. The secure
//             container stays shut on the camera.
//   drop    - insured, disconnects during the entry camera: voided the same way, offline.
//   boss    - posts an escort contract that guard accepts, both go into a trio raid; cv money +1000
//             shortly before the stop. Voided: the contract is called off and its reward refunded,
//             the late 1000 kept, one letter.
//   guard   - the escort, downed (a squadmate still up) when the server stops: voided, one letter.
//   ranger  - on the private range: the lobby kit back, no range loaners, no letter.
//   giver   - takes two gold bars, 60 .338 rounds and the master chip in with the kit. In the raid it
//             leaves the pistol, the gold bars and the rounds in the proving ground's armory box for
//             taker, and the chip for keeper, and uses a bandage. Voided: none of what it handed over
//             comes back (the chip went home in keeper's secure container), the bandage does (taker's
//             two "found" bandages do not count against it), the rest slot for slot as it went in, and
//             the letter lists what was not handed back. The chat line says so too.
//   taker   - takes the pistol, gold bars and rounds from the box, "finds" two bandages and extracts.
//   keeper  - takes the chip, puts it in its secure container and is killed: it keeps the chip.
// Also: no "Error occurred while disabling Craftov" or failed shutdown step in the log, a new
// -shutdown.zip backup, and no craftov_raid_* folder left once the server is back.
//
//   PORT=25565 RCON_PORT=25575 SERVER_DIR=../../server/run node shutdown.js [--crash]
//
// --crash adds three steps that kill the server outright (SIGKILL; Linux only, the process is found
// in /proc): once with an escort contract open, checking that the reward comes back when its owner
// rejoins; once a second after the boss of a contract died in a raid whose escort had already died
// and logged off, checking that the escort keeps the share and the boss is not refunded again (on
// the way the dead escort, spectating the boss, must find the secure container and stash shut); and
// once with a player in a raid whose rifle a squadmate has just extracted with, checking that their
// next login voids it like a stop (loadout and secure container as they went in but for the rifle,
// which the letter and the chat line say was not handed back, the premium back, one letter) and
// that logging in again does nothing more.
//
// COMPENSATION (5000) must match raid.void-compensation.money. Profiles of the bots are written
// before they first join (insurance on, the raider's contracts and level). The server must be
// running, with test mode on (test.enabled) so the bots can queue for the proving ground. After
// each stop the script starts it again with SERVER_DIR/start.sh (detached, output appended to
// SERVER_DIR/console.log); with NO_START=1 it only waits for somebody else (a restart loop) to bring
// it back. Exits 0 when every check passes, 1 otherwise.
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
const COMPENSATION = Number(process.env.COMPENSATION || 5000)
const SERVER_REAL = fs.realpathSync(SERVER_DIR)
const rcon = cmd => rconRaw(cmd, { port: RCON_PORT, password: RCON_PASSWORD }).then(s => s.replace(/§./g, ''))
const tag = String(Math.floor(1000 + Math.random() * 9000))
const NAMES = {
  raider: 'SdRaid' + tag, dead: 'SdDead' + tag, gone: 'SdGone' + tag, flyer: 'SdFly' + tag, drop: 'SdDrop' + tag,
  ranger: 'SdRng' + tag, boss: 'SdBoss' + tag, guard: 'SdGrd' + tag,
  giver: 'SdGive' + tag, taker: 'SdTake' + tag, keeper: 'SdKeep' + tag
}
// Only join for the --crash steps.
const EXTRA = { escort: 'SdEsc' + tag, crasher: 'SdCrash' + tag, mate: 'SdMate' + tag }
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
const count = (records, id) => Object.values(records || {}).filter(r => r.id === id).length
const letters = p => (p.mail || []).filter(m => m.title === '服务器维护补偿')
// What other players have put on dead's head before the raid.
const BOUNTY = 10000
const profileBounty = name => profile(name).bounty || 0
const backups = () => { try { return fs.readdirSync(path.join(SERVER_DIR, 'plugins', 'Craftov', 'backups')) } catch { return [] } }
const raidFolders = () => fs.readdirSync(SERVER_DIR).filter(f => f.startsWith('craftov_raid_'))
const latestLog = () => { try { return fs.readFileSync(path.join(SERVER_DIR, 'logs', 'latest.log'), 'utf8') } catch { return '' } }
const balance = async name => Number(((await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/) || [, 'NaN'])[1].replace(/,/g, ''))

// A record as the void hands it back: instance ids are given out at deploy (the lobby copy may not
// have one yet) and insurance marks never come home.
function clean (record) {
  const r = JSON.parse(JSON.stringify(record))
  delete r.uid
  delete r.insured
  delete r.insuredAs
  if (r.contents) r.contents = r.contents.map(clean)
  return r
}
const layout = loadout => JSON.stringify(Object.keys(loadout || {}).sort((a, b) => a - b).map(k => [k, clean(loadout[k])]))
const records = list => JSON.stringify((list || []).map(clean))
const stats = s => JSON.stringify(Object.entries(s || {}).filter(([, v]) => v !== 0).sort())
// How many of an item the records hold, packed contents included.
function deep (recs, fn) {
  let n = 0
  for (const r of Object.values(recs || {})) { n += fn(r); if (r.contents) n += deep(r.contents, fn) }
  return n
}
const amountOf = (recs, id) => deep(recs, r => r.id === id ? (r.amount || 1) : 0)
const held = (p, id) => amountOf(p.loadout, id) + amountOf(p.secure, id)
// A loadout without the given items (packed contents included), to compare the rest of it.
const stripRec = (r, gone) => { const c = JSON.parse(JSON.stringify(r)); if (c.contents) c.contents = c.contents.filter(x => !gone.includes(x.id)).map(x => stripRec(x, gone)); return c }
const strip = (loadout, gone) => Object.fromEntries(Object.entries(loadout || {}).filter(([, r]) => !gone.includes(r.id)).map(([k, r]) => [k, stripRec(r, gone)]))

// The contract day as TaskService counts it (it rolls over at 04:00 local time).
const today = () => Math.floor((Date.now() - 4 * 3600e3 - new Date().getTimezoneOffset() * 60e3) / 86400e3)
const task = (kind, target, reward) => ({ kind, target, progress: 0, reward, xp: 300 + Math.floor(reward / 40), done: false })
// Writes a profile for a player who has not joined yet: insurance on, plus whatever else is given.
function seed (name, extra = {}) {
  fs.writeFileSync(profileFile(name), JSON.stringify(Object.assign({ uuid: offlineUuid(name), name, autoInsure: true }, extra), null, 2))
}

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
  const name = NAMES[role] || EXTRA[role]
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
function quit (role) {
  const bot = bots[role]
  delete bots[role]
  if (bot) bot.quit()
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
const hotbar = (bot, id) => { for (let i = 0; i < 9; i++) { if (itemId(bot.inventory.slots[36 + i]) === id) return i } return -1 }
const dimension = async name => (await rcon(`data get entity ${name} Dimension`)).replace(/.*: /, '').replace(/"/g, '').trim()
const inRaidWorld = async name => /craftov_raid_/.test(await dimension(name))
// Game mode as the server has it (the client's copy can lag behind a world change).
const inMode = async (name, mode) => /passed/i.test(await rcon(`execute if entity @a[name=${name},gamemode=${mode}]`))
const position = async name => (((await rcon(`data get entity ${name} Pos`)).match(/\[(.*)\]/) || [, ''])[1]).split(',').map(v => parseFloat(v))
// The item id Craftov stores on the stack (custom data), for items whose model path differs from it.
const cid = item => { const m = JSON.stringify(item ? item.components || '' : '').match(/"craftov:id":\{"type":"string","value":"([^"]+)"/); return m ? m[1] : null }
/** Window slot of player inventory index i (0-8 hotbar, 9-35 main) in an open container window. */
const bottom = (w, i) => i < 9 ? w.inventoryStart + 27 + i : w.inventoryStart + i - 9
function findCarried (w, pred) {
  for (let i = 0; i < 36; i++) {
    if (i === 8) continue // the raid terminal
    const it = w.slots[bottom(w, i)]
    if (it && pred(it)) return i
  }
  return -1
}
const nameOf = role => NAMES[role] || EXTRA[role]
/** Opens the proving ground's armory box (8.5 65 77.5), a container both players can reach. */
async function openArmory (role) {
  const bot = bots[role]
  await rcon(`execute as ${nameOf(role)} at @s run tp @s 8.5 65 78.9 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return openWindow(bot, () => bot.activateEntity(target))
}
/** Puts every carried stack of the wanted items in the armory box; returns [box slot, item id] pairs. */
async function deposit (role, wanted) {
  const bot = bots[role]
  const w = await openArmory(role)
  if (!w) { check(`${role}: the armory box opens`, false); return [] }
  const empty = new Set()
  for (let s = 0; s < w.inventoryStart; s++) if (!w.slots[s]) empty.add(s)
  for (let round = 0; round < 12; round++) {
    const i = findCarried(w, it => wanted.includes(cid(it)))
    if (i < 0) break
    await bot.clickWindow(bottom(w, i), 0, 0) // a click moves the stack into the box
    await sleep(400)
  }
  const box = [...empty].filter(s => wanted.includes(cid(w.slots[s]))).map(s => [s, cid(w.slots[s])])
  bot.closeWindow(w)
  await sleep(400)
  return box
}
async function takeFromBox (role, slots) {
  const bot = bots[role]
  const w = await openArmory(role)
  if (!w) { check(`${role}: the armory box opens`, false); return }
  for (const s of slots) { await bot.clickWindow(s, 0, 0); await sleep(400) }
  bot.closeWindow(w)
  await sleep(400)
}
const carries = (role, id) => bots[role].inventory.items().filter(i => cid(i) === id).reduce((a, i) => a + i.count, 0)
/** Out through the proving ground's always-open extract. */
async function extract (role) {
  await rcon(`execute as ${nameOf(role)} at @s run tp @s 106.5 65 8.5`)
  return waitFor(async () => (await dimension(nameOf(role))).includes('craftov_lobby'), 30000, 500)
}
const killSoldiers = async world => { for (const type of ['zombie', 'husk', 'drowned']) await rcon(`execute in minecraft:${world} run kill @e[type=minecraft:${type}]`) }

async function queueSolo (role, ranked = false) {
  bots[role].chat(`/cv queue proving NORMAL SOLO${ranked ? ' true' : ''}`)
}
async function landed (names, ms = 120000) {
  let ok = true
  for (const name of names) ok = ok && await waitFor(async () => await inRaidWorld(name) && await inMode(name, 'adventure'), ms, 1000)
  return ok
}

// ------------------------------------------------------------------ before the stop

const before = {}
const expect = {}

/** What the void must bring back: the profile as the lobby last saved it, and the live balance. */
async function remember (role) {
  const p = profile(NAMES[role])
  before[role] = { p, money: await balance(NAMES[role]) }
}

async function setup () {
  // The raider's contracts: one soldier kill, one knife kill (both done by one stab kill, with a
  // level-up from their experience), and a third that stays open (no all-done bonus).
  seed(NAMES.raider, {
    level: 3,
    xp: 3000,
    taskDay: today(),
    tasks: [task('AI_KILL', 1, 1500), task('MELEE_KILL', 1, 9000), task('SEARCH', 50, 45000)],
    secure: [{ id: 'bandage', amount: 1 }]
  })
  seed(NAMES.dead, { bounty: BOUNTY })
  for (const role of ['gone', 'flyer', 'drop', 'boss', 'guard', 'giver', 'taker', 'keeper']) seed(NAMES[role])
  for (const role of Object.keys(NAMES)) { await make(role); await sleep(1500) }
  // What the giver hands over in the raid goes in with its kit.
  for (const gift of ['gold_bar 2', 'ammo_338_fmj 30', 'ammo_338_fmj 30', 'master_chip 1']) await rcon(`cv give ${NAMES.giver} ${gift}`)
  // The starter kit (rifle, pistol, medkit, bandages, ammo, rig, backpack, armour, helmet) is the
  // loadout; the lobby saves it every 2 s and the profile reaches the disk within 10 s.
  await sleep(12000)
  for (const role of Object.keys(NAMES)) await remember(role)
  check('raider: the bandage is in the secure container before deploying', ids(before.raider.p.secure).includes('bandage'),
    ids(before.raider.p.secure).join(','))

  // The raid: raider (ranked), dead and gone queue together and land in one raid.
  const raiders = [NAMES.raider, NAMES.dead, NAMES.gone]
  await queueSolo('raider', true)
  await queueSolo('dead')
  await queueSolo('gone')
  const down = await landed(raiders)
  check('raider, dead and gone land in a raid', down, (await Promise.all(raiders.map(dimension))).join(' / '))
  if (!down) return false
  check('all three are in the same raid', new Set(await Promise.all(raiders.map(dimension))).size === 1)
  const world = (await dimension(NAMES.raider)).replace(/^minecraft:/, '')
  const premium = before.raider.money - await balance(NAMES.raider)
  check('raider paid an insurance premium', premium > 0, String(premium))
  expect.raiderFees = premium
  // Only the soldier posted below: the map's own would hunt the bots once the raider has fired.
  for (const type of ['zombie', 'husk', 'drowned']) await rcon(`execute in minecraft:${world} run kill @e[type=minecraft:${type}]`)
  await sleep(3500) // spawn protection

  // Gone: the insured rifle goes on the ground (from the inventory screen; Q alone reloads), then
  // the connection drops. The stop must come inside the 120 s reconnect window.
  const g = bots.gone
  const rifle = g.inventory.items().find(i => itemId(i) === 'k47')
  if (rifle) {
    await g.tossStack(rifle).catch(e => log('gone', 'toss', e.message))
    await sleep(800)
  }
  check('gone throws the insured rifle on the ground', !!rifle && !g.inventory.items().some(i => itemId(i) === 'k47'),
    g.inventory.items().map(itemId).join(','))
  quit('gone')
  await sleep(1500)

  // Dead: knifed by the raider with the insured gear on, while the raid keeps running for the
  // raider. The raider collects the bounty on dead's head; voided, it goes back on that head.
  const r = bots.raider
  r.setQuickBarSlot(2) // the melee slot
  for (let i = 0; i < 12 && !r.chatLog.some(m => m.includes('→') && m.includes(NAMES.dead)); i++) {
    await rcon(`execute as ${NAMES.raider} at ${NAMES.dead} run tp @s ^ ^ ^-1.2`)
    const seen = () => r.players[NAMES.dead] && r.players[NAMES.dead].entity
    await waitFor(() => seen() && seen().position.distanceTo(r.entity.position) < 3, 3000, 100)
    const victim = seen()
    if (!victim) continue
    await r.lookAt(victim.position.offset(0, 1.2, 0), true)
    await sleep(80)
    r.attack(victim)
    await sleep(700)
  }
  const died = await waitFor(() => r.chatLog.some(m => m.includes('→') && m.includes(NAMES.dead)), 4000)
  check('the raider kills dead in the raid', died)
  check('the raider collects the bounty on dead', r.chatLog.some(m => m.includes('领取了') && m.includes(NAMES.dead)))

  if (!await raiderActs(world)) return false
  // Saved with the next profile flush (every 10 s), well before the stop.
  const collected = await waitFor(() => profileBounty(NAMES.dead) === 0, 12000, 500)
  check('dead: the bounty is collected', collected, String(profileBounty(NAMES.dead)))

  // Ranger: kit captured in the lobby, then onto the private range.
  await rcon(`execute as ${NAMES.ranger} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bots.ranger.nearestEntity(e => e.name === 'interaction')
  if (rack) bots.ranger.activateEntity(rack)
  const onRange = await waitFor(async () => (await dimension(NAMES.ranger)).includes('craftov_range'), 6000, 500)
  check('ranger is on the private range', onRange, await dimension(NAMES.ranger))

  // Boss and guard: an escort contract, then a trio raid together; the guard is downed in it.
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
  check('boss posts an escort contract (reward held)', posted < expect.bossMoney, `${expect.bossMoney} -> ${posted}`)
  const e = bots.guard
  await openWindow(e, () => e.chat('/cv'))
  const list = await openWindow(e, () => e.clickWindow(16, 0, 0))
  if (list) { await e.clickWindow(0, 0, 0); await sleep(800); e.closeWindow(list) }
  check('guard accepts the contract', e.chatLog.some(m => m.includes('已接取') && m.includes(NAMES.boss)))
  before.guard.money = await balance(NAMES.guard)
  c.chat('/cv queue proving NORMAL TRIO')
  const together = await landed([NAMES.boss, NAMES.guard])
  check('boss and guard land in a raid', together)
  if (together) {
    await sleep(3500) // spawn protection
    await rcon(`kill ${NAMES.guard}`)
    check('guard is downed', await waitFor(() => c.chatLog.some(m => m.includes(NAMES.guard) && m.includes('倒地')), 3000))
  }

  if (!await handoff()) return false

  // Flyer and drop: one more raid (nobody joins a running one); stop while its entry camera runs.
  await queueSolo('flyer')
  await queueSolo('drop')
  const flying = await waitFor(async () => await inRaidWorld(NAMES.flyer) && await inMode(NAMES.flyer, 'spectator'), 15000, 200)
  check('flyer is in the entry camera', flying, await dimension(NAMES.flyer))
  const dropFlying = await waitFor(async () => await inRaidWorld(NAMES.drop) && await inMode(NAMES.drop, 'spectator'), 3000, 200)
  check('drop is in the entry camera', dropFlying, await dimension(NAMES.drop))
  quit('drop')
  await sleep(800)
  // Anything moved in the secure container now would be overwritten on landing: it stays shut.
  const f = bots.flyer
  const from = f.chatLog.length
  const shut = await openWindow(f, () => f.chat('/cv secure'), 800)
  if (shut) f.closeWindow(shut)
  check('flyer cannot open the secure container during the fly-in', !shut && f.chatLog.slice(from).some(m => m.includes('无法打开安全箱')))
  return true
}

/**
 * Giver, taker and keeper in one raid: the giver hands the taker the pistol, the gold bars and the
 * .338 rounds through the armory box, and the keeper the master chip, and uses a bandage. The taker
 * "finds" two bandages and extracts; the keeper puts the chip in its secure container and is
 * killed. The giver stays in the raid until the stop.
 */
async function handoff () {
  const trio = ['giver', 'taker', 'keeper'].map(nameOf)
  for (const role of ['giver', 'taker', 'keeper']) await queueSolo(role)
  const down = await landed(trio)
  check('handoff: giver, taker and keeper land in a raid', down, (await Promise.all(trio.map(dimension))).join(' / '))
  if (!down) return false
  check('handoff: all three are in the same raid', new Set(await Promise.all(trio.map(dimension))).size === 1)
  await killSoldiers((await dimension(NAMES.giver)).replace(/^minecraft:/, ''))
  await sleep(3500) // spawn protection

  const handed = ['p18', 'gold_bar', 'ammo_338_fmj', 'master_chip']
  const box = await deposit('giver', handed)
  check('handoff: the giver leaves the pistol, gold bars, .338 and the chip in the box', handed.every(id => box.some(([, i]) => i === id)),
    JSON.stringify(box))
  await takeFromBox('taker', box.filter(([, id]) => id !== 'master_chip').map(([slot]) => slot))
  await takeFromBox('keeper', box.filter(([, id]) => id === 'master_chip').map(([slot]) => slot))
  check('handoff: the taker holds the pistols, 2 gold bars and 60 .338', carries('taker', 'p18') === 2 && carries('taker', 'gold_bar') === 2
    && carries('taker', 'ammo_338_fmj') === 60, ['p18', 'gold_bar', 'ammo_338_fmj'].map(id => id + ' ' + carries('taker', id)).join(', '))
  check('handoff: the keeper holds the chip', carries('keeper', 'master_chip') === 1)

  // The giver uses up a bandage; the taker finds two of its own.
  const g = bots.giver
  const hadBandages = carries('giver', 'bandage')
  await rcon(`cv injure ${NAMES.giver} left_arm light`)
  await sleep(500)
  const med = hotbar(g, 'bandage')
  if (med >= 0) {
    g.setQuickBarSlot(med)
    await sleep(300)
    g.activateItem()
    await sleep(4000)
    g.deactivateItem()
    await sleep(800)
  }
  check('handoff: the giver uses a bandage', carries('giver', 'bandage') === hadBandages - 1, `${hadBandages} -> ${carries('giver', 'bandage')}`)
  await rcon(`cv give ${NAMES.taker} bandage 2`)
  await sleep(800)
  check('handoff: the taker extracts', await extract('taker'))

  // The keeper: the chip into the secure container, then killed.
  const k = bots.keeper
  const secure = await openWindow(k, () => k.chat('/cv secure'))
  if (secure) {
    const slot = secure.slots.findIndex((it, i) => i >= secure.inventoryStart && it && cid(it) === 'master_chip')
    if (slot >= 0) { await k.clickWindow(slot, 0, 0); await sleep(500) }
    check('handoff: the keeper moves the chip into its secure container', secure.slots.slice(0, 9).some(it => it && cid(it) === 'master_chip'))
    k.closeWindow(secure)
    await sleep(500)
  } else check('handoff: the keeper opens the secure container', false)
  await rcon(`kill ${NAMES.keeper}`)
  check('handoff: the keeper dies with the chip in the secure container',
    await waitFor(() => { const p = profile(NAMES.keeper); return p.activeRaid == null && ids(p.secure).includes('master_chip') }, 8000, 250))

  // On disk at once: what left the raid with the others is on the giver's deployment record.
  const noted = await waitFor(() => {
    const d = profile(NAMES.giver).deployment || {}
    return (d.gone || []).length === 2 && (d.goneStacks || {}).gold_bar === 2 && (d.goneStacks || {}).ammo_338_fmj === 60
  }, 5000, 250)
  const record = profile(NAMES.giver).deployment || {}
  check('handoff: the pistol, the chip, 2 gold bars and 60 .338 are on the giver\'s record as gone', noted,
    JSON.stringify({ gone: record.gone, goneStacks: record.goneStacks }))
  check('handoff: the bandage it used is not', !(record.goneStacks || {}).bandage)
  check('handoff: the giver is still in the raid', await inMode(NAMES.giver, 'adventure') && await inRaidWorld(NAMES.giver))
  return true
}

/**
 * The raider loots, knifes a soldier (contracts done, a level-up), spends rounds and a bandage
 * from the kit and drops the insured pistol.
 */
async function raiderActs (world) {
  const r = bots.raider
  const name = NAMES.raider
  await rcon(`cv give ${name} splint`)
  await sleep(800)
  const secure = await openWindow(r, () => r.chat('/cv secure'))
  if (secure) {
    const slot = secure.slots.findIndex((s, i) => i >= secure.inventoryStart && s && itemId(s) === 'splint')
    if (slot >= 0) { await r.clickWindow(slot, 0, 0); await sleep(500) }
    check('raider moves the splint into the secure container mid-raid', secure.slots.slice(0, 9).some(s => s && itemId(s) === 'splint'))
    r.closeWindow(secure)
    await sleep(500)
  } else check('raider opens the secure container mid-raid', false)

  // A soldier posted a few metres off, knifed from behind (it only sees what is ahead of it, and a
  // patrol backs off to fight at range): both contracts done, a level-up from their experience.
  const soldierNear = () => r.nearestEntity(en => ['zombie', 'husk', 'drowned'].includes(en.name) && en.position.distanceTo(r.entity.position) < 4)
  let at = await position(name)
  log('soldier', await rcon(`cv ai spawn patrol ${(at[0] + 3).toFixed(1)} ${at[1].toFixed(1)} ${at[2].toFixed(1)} ${world}`))
  await sleep(800)
  r.setQuickBarSlot(2) // the melee slot
  await sleep(300)
  const from = r.chatLog.length
  for (let i = 0; i < 8; i++) {
    await rcon(`execute as ${name} at @s at @e[type=minecraft:zombie,sort=nearest,limit=1,distance=..30] run tp @s ^ ^ ^-1.3`)
    await sleep(350)
    const soldier = soldierNear()
    if (!soldier) break
    await r.lookAt(soldier.position.offset(0, 1.0, 0), true)
    await sleep(80)
    r.attack(soldier)
    await sleep(750)
  }
  const done = await waitFor(() => r.chatLog.slice(from).some(m => m.includes('每日任务完成')), 4000)
  await sleep(500)
  check('raider kills a soldier: a contract done', done, r.chatLog.slice(from).join(' | ').slice(0, 300))
  check('raider levels up from the contract', r.chatLog.slice(from).some(m => m.includes('账号升级')))

  // A second soldier as a target: two taps of the rifle, then Q. The rounds come out of the ammo
  // stack. The soldier is removed before it can do much about it.
  const ammo = () => r.inventory.items().filter(i => itemId(i) === 'ammo_762_ps').reduce((a, i) => a + i.count, 0)
  const ammoBefore = ammo()
  const rifle = hotbar(r, 'k47')
  if (rifle >= 0) {
    at = await position(name)
    await rcon(`cv ai spawn patrol ${(at[0] + 4).toFixed(1)} ${at[1].toFixed(1)} ${at[2].toFixed(1)} ${world}`)
    r.setQuickBarSlot(rifle)
    await sleep(900)
    const target = r.nearestEntity(en => ['zombie', 'husk', 'drowned'].includes(en.name) && en.position.distanceTo(r.entity.position) < 8)
    for (let i = 0; i < 2 && target; i++) {
      await r.lookAt(target.position.offset(0, 1.0, 0), true)
      r.attack(target) // a left click on an entity with a gun in hand pulls the trigger
      await sleep(400)
    }
    for (const type of ['zombie', 'husk', 'drowned']) await rcon(`execute in minecraft:${world} run kill @e[type=minecraft:${type}]`)
    await sleep(500)
    r._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }) // Q: reload
    await sleep(4500)
  }
  check('raider fires and reloads (rounds leave the ammo stack)', ammo() < ammoBefore, `${ammoBefore} -> ${ammo()}`)

  // A bleeding arm, then a bandage from the kit: one fewer.
  const bandages = () => r.inventory.items().filter(i => itemId(i) === 'bandage').reduce((a, i) => a + i.count, 0)
  const hadBandages = bandages()
  await rcon(`cv injure ${name} left_arm light`)
  await sleep(500)
  const med = hotbar(r, 'bandage')
  if (med >= 0) {
    r.setQuickBarSlot(med)
    await sleep(300)
    r.activateItem()
    await sleep(4000)
    r.deactivateItem()
    await sleep(800)
  }
  check('raider uses a bandage from the kit', bandages() < hadBandages, `hotbar ${med}: ${hadBandages} -> ${bandages()}`)

  // The insured pistol goes on the ground and stays there.
  const pistol = r.inventory.items().find(i => itemId(i) === 'p18')
  if (pistol) { await r.tossStack(pistol).catch(err => log('raider', 'toss', err.message)); await sleep(800) }
  check('raider throws the insured pistol on the ground', !!pistol && !r.inventory.items().some(i => itemId(i) === 'p18'))

  check('raider is still alive in the raid', await inMode(name, 'adventure') && await inRaidWorld(name))
  expect.raiderPaid = (await balance(name)) - before.raider.money
  log('raider balance change in the raid', expect.raiderPaid)
  return true
}

// ------------------------------------------------------------------ the stop

async function stopAndRestart () {
  const zipsBefore = backups().filter(f => f.endsWith('-shutdown.zip'))
  await rcon(`cv money ${NAMES.boss} 1000`)
  expect.bossAfter = expect.bossMoney + 1000
  const stoppedAt = Date.now()
  await Promise.race([rcon('stop').catch(() => {}), sleep(5000)])
  const down = await waitFor(async () => {
    const pids = serverPids()
    return (pids === null || pids.length === 0) && !(await portOpen(PORT)) && !(await portOpen(RCON_PORT))
  }, 90000, 500)
  check('the server stops', down)
  await sleep(1000)
  log('stopped after', ((Date.now() - stoppedAt) / 1000).toFixed(1) + 's')

  // What the players saw before the connection dropped.
  const saw = (role, text) => bots[role] && bots[role].chatLog.some(m => m.includes(text))
  for (const role of ['raider', 'flyer', 'boss', 'guard', 'giver']) {
    check(`${role} is told the raid was voided`, saw(role, '服务器维护：本次行动已作废'))
  }
  const line = (bots.raider.chatLog.find(m => m.includes('服务器维护：本次行动已作废')) || '')
  const clawed = Number(((line.match(/悬赏共 ◈([\d,]+) 已扣回/) || [, 'NaN'])[1]).replace(/,/g, ''))
  check('raider: the message says how much of what the raid paid was taken back', clawed === expect.raiderPaid + expect.raiderFees,
    `expected ${expect.raiderPaid + expect.raiderFees}: ${line}`)
  check('giver: the message says some gear was not handed back', saw('giver', '已被其他干员带出本局的不再退回'))
  check('flyer: no word of anything taken back', !saw('flyer', '已扣回') && !saw('flyer', '不再退回'))
  check('dead is told the insured gear came back', saw('dead', '保险理赔'))

  const text = latestLog()
  check('no "Error occurred while disabling Craftov"', !text.includes('Error occurred while disabling Craftov'))
  check('no failed shutdown step', !/Shutdown step .*failed/.test(text), (text.match(/Shutdown step .*failed.*/) || [''])[0])
  check('no failed void', !text.includes('Could not void raid'), (text.match(/Could not void raid.*/) || [''])[0])
  check('no IllegalPluginAccessException', !text.includes('IllegalPluginAccessException'))
  const zips = backups().filter(f => f.endsWith('-shutdown.zip') && !zipsBefore.includes(f))
  check('a -shutdown.zip backup was written', zips.length === 1, zips.join(' '))

  // The saves as they are on disk now. Everyone voided: out of the raid, gear as it went in, the
  // balance from before queueing, one letter with the compensation.
  for (const role of ['raider', 'gone', 'flyer', 'drop', 'guard']) {
    const p = profile(NAMES[role])
    const b = before[role]
    check(`${role}: out of the raid`, p.activeRaid == null && p.deployment == null, `${p.activeRaid} ${JSON.stringify(p.deployment || null).slice(0, 60)}`)
    check(`${role}: loadout slot for slot as it went in`, layout(p.loadout) === layout(b.p.loadout),
      `${layout(b.p.loadout).slice(0, 400)}\n   -> ${layout(p.loadout).slice(0, 400)}`)
    check(`${role}: secure container as it went in`, records(p.secure) === records(b.p.secure), `${records(b.p.secure)} -> ${records(p.secure)}`)
    check(`${role}: balance as before deploying (fees back)`, p.money === b.money, `${b.money} -> ${p.money}`)
    const mine = letters(p)
    check(`${role}: one compensation letter, unclaimed, ◈${COMPENSATION} attached`,
      mine.length === 1 && !mine[0].claimed && mine[0].money === COMPENSATION, JSON.stringify(mine).slice(0, 300))
    check(`${role}: no insurance claim on top`, (p.mailbox || []).length === (b.p.mailbox || []).length, ids(p.mailbox).join(','))
    check(`${role}: the raid is off the stats`, stats(p.stats) === stats(b.p.stats), `${stats(b.p.stats)} -> ${stats(p.stats)}`)
  }

  const raider = profile(NAMES.raider)
  const rb = before.raider.p
  check('raider: the splint from the raid is gone', !ids(raider.loadout).includes('splint') && !ids(raider.secure).includes('splint'))
  check('raider: the pistol it threw is back once', count(raider.loadout, 'p18') === count(rb.loadout, 'p18') && count(rb.loadout, 'p18') === 1)
  check('raider: contract, level and bounty money from the raid taken back', expect.raiderPaid > 0 && raider.money === before.raider.money,
    `paid +${expect.raiderPaid} in the raid; ${before.raider.money} -> ${raider.money}`)
  check('raider: level and experience as before', raider.level === rb.level && raider.xp === rb.xp, `${rb.level}/${rb.xp} -> ${raider.level}/${raider.xp}`)
  check('raider: season pass as before', (raider.seasonXp || 0) === (rb.seasonXp || 0), `${rb.seasonXp} -> ${raider.seasonXp}`)
  check('raider: rank points as before', raider.rankPoints === rb.rankPoints, `${rb.rankPoints} -> ${raider.rankPoints}`)
  check('raider: contracts as before', JSON.stringify(raider.tasks) === JSON.stringify(rb.tasks), JSON.stringify(raider.tasks).slice(0, 300))
  check('raider: no raid report', (raider.reports || []).length === (rb.reports || []).length)
  const rl = letters(raider)[0] || { body: '' }
  check('raider: the letter says the raid\'s rewards and the bounty were taken back',
    rl.body.includes('已从余额扣回') && rl.body.includes('领取的悬赏退回到被击杀者头上'), rl.body)

  const giver = profile(NAMES.giver)
  const gb = before.giver.p
  const handed = ['p18', 'master_chip', 'gold_bar', 'ammo_338_fmj']
  check('giver: went in with the pistol, the chip, 2 gold bars and 60 .338',
    held(gb, 'p18') === 1 && held(gb, 'master_chip') === 1 && held(gb, 'gold_bar') === 2 && held(gb, 'ammo_338_fmj') === 60,
    handed.map(id => id + ' ' + held(gb, id)).join(', '))
  check('giver: out of the raid', giver.activeRaid == null && giver.deployment == null)
  check('giver: none of what it handed over comes back', handed.every(id => held(giver, id) === 0), handed.map(id => id + ' ' + held(giver, id)).join(', '))
  check('giver: the rest of the loadout slot for slot as it went in', layout(strip(giver.loadout, handed)) === layout(strip(gb.loadout, handed)),
    `${layout(strip(gb.loadout, handed)).slice(0, 300)}\n   -> ${layout(strip(giver.loadout, handed)).slice(0, 300)}`)
  check('giver: the bandage it used comes back (the taker\'s finds do not count against it)', held(giver, 'bandage') === held(gb, 'bandage'),
    `${held(gb, 'bandage')} -> ${held(giver, 'bandage')}`)
  check('giver: secure container as it went in', records(giver.secure) === records(gb.secure))
  check('giver: balance as before deploying (fees back)', giver.money === before.giver.money, `${before.giver.money} -> ${giver.money}`)
  const gl = letters(giver)
  check('giver: one letter that lists what was not handed back', gl.length === 1 && gl[0].body.includes('已被其他干员带出本局的不再退回：2 件装备')
    && gl[0].body.includes('金条 ×2') && gl[0].body.includes('.338 FMJ ×60') && !gl[0].body.includes('绷带'), gl[0] && gl[0].body)
  const taker = profile(NAMES.taker)
  check('taker: extracted with the pistol, the gold bars and the rounds',
    held(taker, 'p18') === 2 && held(taker, 'gold_bar') === 2 && held(taker, 'ammo_338_fmj') === 60, ['p18', 'gold_bar', 'ammo_338_fmj'].map(id => id + ' ' + held(taker, id)).join(', '))
  check('taker: no letter', letters(taker).length === 0)
  const keeper = profile(NAMES.keeper)
  check('keeper: the chip stays in its secure container', ids(keeper.secure).includes('master_chip'))
  check('keeper: no letter', letters(keeper).length === 0)

  const gone = profile(NAMES.gone)
  check('gone: the rifle it threw is back once', count(gone.loadout, 'k47') === 1, ids(gone.loadout).join(','))

  const dead = profile(NAMES.dead)
  check('dead: settled as a death (insured rifle and armour in the mailbox)', ['k47', 'armor_2'].every(i => ids(dead.mailbox).includes(i)), ids(dead.mailbox).join(','))
  check('dead: the death counts', (dead.stats || {}).deaths === 1, JSON.stringify(dead.stats))
  check('dead: no compensation letter', letters(dead).length === 0)
  check('dead: the bounty the voided raider collected is back on its head', (dead.bounty || 0) === BOUNTY, String(dead.bounty))
  check('dead: out of the raid', dead.activeRaid == null && dead.deployment == null)

  const ranger = profile(NAMES.ranger)
  check('ranger: lobby kit back', layout(ranger.loadout) === layout(before.ranger.p.loadout), ids(ranger.loadout).join(','))
  check('ranger: no letter', letters(ranger).length === 0)

  const boss = profile(NAMES.boss)
  check('boss: contract called off, reward refunded and the late +1000 kept', boss.money === expect.bossAfter, `expected ${expect.bossAfter}, got ${boss.money}`)
  check('boss: nothing left in escrow', !boss.escortEscrow, String(boss.escortEscrow))
  check('boss: one compensation letter', letters(boss).length === 1)
  check('boss: loadout slot for slot as it went in', layout(boss.loadout) === layout(before.boss.p.loadout))
  check('boss: out of the raid', boss.activeRaid == null && boss.deployment == null)
  check('guard: no escort pay', profile(NAMES.guard).money === before.guard.money)

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

/** Opens /cv mail and claims the newest letter; returns the balance change. */
async function claimLetter (role) {
  const bot = bots[role]
  const name = NAMES[role] || EXTRA[role]
  const money = await balance(name)
  const w = await openWindow(bot, () => bot.chat('/cv mail'))
  if (!w) { check(`${role}: the mailbox opens`, false); return NaN }
  const first = w.slots[0]
  check(`${role}: the letter is first in the mailbox`, !!first && JSON.stringify(first.components || '').includes('服务器维护补偿'))
  await bot.clickWindow(0, 0, 0)
  await sleep(800)
  bot.closeWindow(w)
  return (await balance(name)) - money
}

async function afterRestart () {
  const r = await make('raider')
  await make('boss')
  const g = await make('gone')
  const d = await make('dead')
  await sleep(5000)
  for (const [role, bot] of [['raider', r], ['gone', g]]) {
    check(`${role}: no "voided by a crash" on rejoin (voided once, at the stop)`, !bot.chatLog.some(m => m.includes('因服务器中断已作废')))
    check(`${role}: told of the unread letter`, bot.chatLog.some(m => m.includes('封未读邮件')))
  }
  check('dead: no unread letter', !d.chatLog.some(m => m.includes('封未读邮件')))
  quit('dead')
  const worn = g.inventory.items().map(itemId).concat([g.inventory.slots[6]].map(itemId)) // 6: the chest armour slot
  check('gone: back with the rifle and the armour', worn.includes('k47') && worn.includes('armor_2'), worn.join(','))
  quit('gone')
  check('raider: back in the lobby', (await dimension(NAMES.raider)).includes('craftov_lobby'), await dimension(NAMES.raider))
  const held = r.inventory.items().map(itemId)
  check('raider: holds the rifle and pistol, not the splint', held.includes('k47') && held.includes('p18') && !held.includes('splint'), held.join(','))
  const got = await claimLetter('raider')
  check(`raider: claiming the letter pays ◈${COMPENSATION}`, got === COMPENSATION, String(got))
  const again = await claimLetter('raider')
  check('raider: a claimed letter pays nothing more', again === 0, String(again))
  const money = await balance(NAMES.boss)
  check('boss: balance after the restart', money === expect.bossAfter, `expected ${expect.bossAfter}, got ${money}`)
  quit('raider')
}

// --crash: the boss posts another contract, the server is killed outright (SIGKILL, so no shutdown
// code runs at all) once the profile has been flushed, and the reward must come back on rejoin.
async function crash () {
  if (serverPids() === null) return log('SKIP --crash needs /proc to find the server process')
  const c = bots.boss
  const was = await balance(NAMES.boss)
  await openWindow(c, () => c.chat('/cv'))
  const hall = await openWindow(c, () => c.clickWindow(16, 0, 0))
  if (hall) { await c.clickWindow(38, 0, 0); await sleep(600); c.closeWindow(hall) }
  const held = await balance(NAMES.boss)
  check('crash: boss posts a second contract', held < was, `${was} -> ${held}`)
  await sleep(11000) // the profile flush runs every 10 s
  const killedAt = Date.now()
  for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  check('crash: the server is gone', await waitFor(() => serverPids().length === 0, 20000, 500))
  quit('boss')
  await sleep(1500)
  check('crash: the reward is still held on disk', profile(NAMES.boss).escortEscrow === was - held, String(profile(NAMES.boss).escortEscrow))
  if (!(await start(killedAt))) return
  const back = await make('boss')
  await sleep(4000)
  check('crash: boss is told the contract was dropped', back.chatLog.some(m => m.includes('护航委托因服务器中断已撤销')))
  const money = await balance(NAMES.boss)
  check('crash: the reward is back on rejoin', money === was, `expected ${was}, got ${money}`)
}

// --crash, second kill: the boss dies in a raid after the escort died and logged off. The escort's
// share is written at once; the boss's escrow must be released on disk no later than that, or the
// boss would get the whole reward back on rejoin while the escort keeps the share.
async function crashEscort () {
  if (serverPids() === null) return
  const c = bots.boss
  const e = await make('escort')
  await sleep(3000)
  const reward = 20000 // first button in the escort hall
  const share = Math.round(reward * 0.3) // escort.failure-share, all of it to the one escort
  await openWindow(c, () => c.chat('/cv'))
  const hall = await openWindow(c, () => c.clickWindow(16, 0, 0))
  if (hall) { await c.clickWindow(38, 0, 0); await sleep(600); c.closeWindow(hall) }
  await openWindow(e, () => e.chat('/cv'))
  const list = await openWindow(e, () => e.clickWindow(16, 0, 0))
  if (list) { await e.clickWindow(0, 0, 0); await sleep(800); e.closeWindow(list) }
  check('escort crash: escort accepts the contract', e.chatLog.some(m => m.includes('已接取') && m.includes(NAMES.boss)))
  c.chat('/cv queue proving NORMAL TRIO')
  const down = await landed([NAMES.boss, EXTRA.escort])
  check('escort crash: boss and escort land in a raid', down)
  if (!down) return
  await sleep(3500) // spawn protection

  // With a squadmate still up the kill only downs the escort. Only hostile fire finishes a downed
  // player (and a squadmate's does not count), so a stand-in lands the last hit. It is summoned and
  // hits in one command: stray entities do not last until the next one.
  await rcon(`kill ${EXTRA.escort}`)
  if (await waitFor(() => c.chatLog.some(m => m.includes(EXTRA.escort) && m.includes('倒地')), 2000)) {
    await sleep(500)
    log('finish', await rcon(`execute at ${EXTRA.escort} summon armor_stand run damage ${EXTRA.escort} 1000 minecraft:mob_attack by @s`))
  }
  // After the death report the escort watches the boss. That is no longer "in the raid", but the
  // secure container and the lobby menus must stay shut until they are back in the lobby.
  // The death report comes first; a dead player still in the raid world in spectator mode after it
  // is watching a squadmate (otherwise they would be back in the lobby).
  const reported = await waitFor(() => e.chatLog.some(m => m.trim().startsWith('结果')), 15000)
  await sleep(1000)
  const watching = reported && await inRaidWorld(EXTRA.escort) && await inMode(EXTRA.escort, 'spectator')
  check('escort crash: dead escort spectates the boss', watching, await dimension(EXTRA.escort))
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
  quit('escort')
  await sleep(2000) // the quit save
  const escortBefore = profile(EXTRA.escort).money
  const bossBefore = await balance(NAMES.boss)
  const from = c.chatLog.length
  await rcon(`kill ${NAMES.boss}`)
  await sleep(1000) // well inside the 10 s profile flush
  const killedAt = Date.now()
  for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  check('escort crash: the server is gone', await waitFor(() => serverPids().length === 0, 20000, 500))
  quit('boss')
  await sleep(1500)
  // A level-up on death pays a reward too.
  const levelUp = c.chatLog.slice(from).map(m => (m.match(/账号升级.*奖励 ◈([\d,]+)/) || [, '0'])[1]).reduce((a, b) => a + Number(b.replace(/,/g, '')), 0)
  const boss = profile(NAMES.boss)
  check('escort crash: escort keeps the share', profile(EXTRA.escort).money === escortBefore + share, `${escortBefore} -> ${profile(EXTRA.escort).money}`)
  check('escort crash: boss escrow released on disk', !boss.escortEscrow, String(boss.escortEscrow))
  check('escort crash: boss got the rest back once', boss.money === bossBefore + reward - share + levelUp, `expected ${bossBefore + reward - share + levelUp}, got ${boss.money}`)
  check('escort crash: a death brings no letter (only the one from the stop)', letters(boss).length === 1, String(letters(boss).length))
  if (!(await start(killedAt))) return
  const back = await make('boss')
  await sleep(4000)
  check('escort crash: no second refund on rejoin', !back.chatLog.some(m => m.includes('护航委托因服务器中断已撤销')))
  check('escort crash: no "voided by a crash" for a settled death', !back.chatLog.some(m => m.includes('因服务器中断已作废')))
  const money = await balance(NAMES.boss)
  check('escort crash: balance after rejoin', money === boss.money, `expected ${boss.money}, got ${money}`)
  quit('boss')
}

// --crash, third kill: a player in a raid. The next login voids it the way a stop would, once.
async function crashVoid () {
  if (serverPids() === null) return
  seed(EXTRA.crasher)
  seed(EXTRA.mate)
  const k = await make('crasher')
  await make('mate')
  await sleep(12000) // the starter kit reaches the disk
  const was = profile(EXTRA.crasher)
  const money = await balance(EXTRA.crasher)
  k.chat('/cv queue proving NORMAL SOLO')
  bots.mate.chat('/cv queue proving NORMAL SOLO')
  const down = await landed([EXTRA.crasher, EXTRA.mate])
  check('crash void: crasher and mate land in a raid', down)
  if (!down) return
  check('crash void: in the same raid', (await dimension(EXTRA.crasher)) === (await dimension(EXTRA.mate)))
  check('crash void: an insurance premium was paid', await balance(EXTRA.crasher) < money)
  await killSoldiers((await dimension(EXTRA.crasher)).replace(/^minecraft:/, ''))
  await sleep(3500) // spawn protection
  // The crasher's rifle goes home with the mate, whose save lands first; the crasher's record notes
  // it right after.
  const box = await deposit('crasher', ['k47'])
  await takeFromBox('mate', box.map(([slot]) => slot))
  check('crash void: the mate holds both rifles', carries('mate', 'k47') === 2, String(carries('mate', 'k47')))
  check('crash void: the mate extracts with them', await extract('mate'))
  check('crash void: the rifle is on the crasher\'s record as gone, on disk at once',
    await waitFor(() => ((profile(EXTRA.crasher).deployment || {}).gone || []).length === 1, 5000, 250),
    JSON.stringify((profile(EXTRA.crasher).deployment || {}).gone))
  quit('mate')
  await rcon(`cv give ${EXTRA.crasher} splint`)
  await sleep(11000) // the profile flush runs every 10 s
  const killedAt = Date.now()
  for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  check('crash void: the server is gone', await waitFor(() => serverPids().length === 0, 20000, 500))
  quit('crasher')
  await sleep(1500)
  const left = profile(EXTRA.crasher)
  check('crash void: the profile still names the raid, with what went in', left.activeRaid != null && left.deployment != null
    && layout(left.deployment.loadout) === layout(was.loadout), `${left.activeRaid} ${layout((left.deployment || {}).loadout).slice(0, 200)}`)
  if (!(await start(killedAt))) return
  const back = await make('crasher')
  await sleep(5000)
  check('crash void: told the raid was voided', back.chatLog.filter(m => m.includes('因服务器中断已作废')).length === 1)
  check('crash void: told the rifle the mate took out was not handed back', back.chatLog.some(m => m.includes('因服务器中断已作废') && m.includes('已被其他干员带出本局的不再退回')))
  check('crash void: told of the unread letter', back.chatLog.some(m => m.includes('封未读邮件')))
  quit('crasher')
  await sleep(2000) // the quit save
  const p = profile(EXTRA.crasher)
  check('crash void: out of the raid', p.activeRaid == null && p.deployment == null)
  check('crash void: no rifle back (the mate took it out)', count(was.loadout, 'k47') === 1 && count(p.loadout, 'k47') === 0, ids(p.loadout).join(','))
  check('crash void: the rest of the loadout slot for slot as it went in', layout(strip(p.loadout, ['k47'])) === layout(strip(was.loadout, ['k47'])),
    `${layout(strip(was.loadout, ['k47'])).slice(0, 300)}\n   -> ${layout(strip(p.loadout, ['k47'])).slice(0, 300)}`)
  check('crash void: secure container as it went in', records(p.secure) === records(was.secure))
  check('crash void: balance as before deploying (premium back)', p.money === money, `${money} -> ${p.money}`)
  check('crash void: one compensation letter', letters(p).length === 1 && letters(p)[0].money === COMPENSATION, JSON.stringify(letters(p)).slice(0, 200))
  check('crash void: the letter says one piece of gear was not handed back', (letters(p)[0] || { body: '' }).body.includes('已被其他干员带出本局的不再退回：1 件装备'),
    (letters(p)[0] || {}).body)
  check('crash void: the raid is off the stats', stats(p.stats) === stats(was.stats), `${stats(was.stats)} -> ${stats(p.stats)}`)
  const again = await make('crasher')
  await sleep(4000)
  check('crash void: logging in again voids nothing more', !again.chatLog.some(m => m.includes('因服务器中断已作废')))
  check('crash void: balance unchanged on the second login', await balance(EXTRA.crasher) === money)
  quit('crasher')
  await sleep(2000)
  check('crash void: still one letter', letters(profile(EXTRA.crasher)).length === 1)
}

;(async () => {
  try {
    if (await setup() && await stopAndRestart()) {
      await afterRestart()
      if (process.argv.includes('--crash')) {
        await crash()
        await crashEscort()
        await crashVoid()
      }
    }
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  for (const bot of Object.values(bots)) { try { bot.quit() } catch { } }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
setTimeout(() => { log('timeout'); process.exit(1) }, 1500000)
