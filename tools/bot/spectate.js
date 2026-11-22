// Spectator-mode escapes during the entry camera and after death, with headless bots.
//
//   node spectate.js                       every scenario
//   node spectate.js menu tp end death     only these (menu tp end death replay mate)
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bots, who are not op, can queue for the proving ground.
// match.wait-seconds: 2 keeps the run short; tiers.NORMAL.entry-fee: 1000 makes the refund
// in 'end' visible. Ports come from PORT (25565), RCON_PORT (25575) and RCON_PASSWORD
// (craftov-local); the server log is read from SERVER_DIR/logs/latest.log (default
// ../../server/run). Each scenario prints PASS or FAIL with what was seen; the exit code
// is the number of failures.
//
//   menu    the spectator menu (a spectate packet naming a player in the lobby) during the
//           entry camera does nothing, and the player lands with the squad
//   tp      a console `tp` to the overworld during the entry camera: the player is brought
//           back, both squadmates land, and the log has no "Cannot measure distance"
//   end     `/cv end` during the entry camera closes the raid within 10 s; the player is in
//           the lobby with the same gear and the entry fee back
//   death   the camera locks onto a fixed point within 2 ticks of dying, and neither the
//           spectator menu nor Shift moves the dead player off it
//   replay  killed by another player: the death camera holds until the kill replay takes
//           over, the menu does nothing during the replay, and Shift still skips it
//   mate    dead in a squad: on the squadmate's camera the menu does nothing, and Shift
//           still leaves for the lobby
//
// DEBUG=1 prints every entity spawn and camera packet.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const LOG = path.join(SERVER_DIR, 'logs/latest.log')
const MAP = process.env.MAP || 'proving'

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const live = []

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

/** A fresh player with a random name (a reused one would rejoin an old raid). */
function make () {
  const name = `s${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  bot.worlds = []    // {at, name}: every world the server put the bot in
  bot.cameras = []   // {at, id, type}: every camera packet, with what the bot knew of that entity
  bot.modes = []     // {at, mode}: game mode changes
  bot.gone = []      // {at, id}: entities removed
  const track = p => {
    if (p.worldState && p.worldState.name) {
      bot.worldName = p.worldState.name.replace(/^minecraft:/, '')
      bot.worlds.push({ at: Date.now(), name: bot.worldName })
    }
  }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('camera', p => {
    const e = bot.entities[p.cameraId]
    bot.cameras.push({ at: Date.now(), id: p.cameraId, type: p.cameraId === (bot.entity && bot.entity.id) ? 'self' : e ? e.name : 'unknown' })
  })
  bot._client.on('game_state_change', p => {
    if (p.reason === 'change_game_mode' || p.reason === 3) bot.modes.push({ at: Date.now(), mode: p.gameMode })
  })
  if (process.env.DEBUG) {
    bot._client.on('spawn_entity', p => console.log(name, Date.now() % 100000, 'spawn', p.entityId, p.type))
    bot._client.on('camera', p => console.log(name, Date.now() % 100000, 'camera', p.cameraId))
  }
  bot._client.on('entity_destroy', p => { for (const id of p.entityIds) bot.gone.push({ at: Date.now(), id }) })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  live.push(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(50) }
  return false
}

const inLobby = bot => bot.worldName === 'craftov_lobby'
const inRaidWorld = bot => bot.worldName.startsWith('craftov_raid_')
const onCamera = bot => inRaidWorld(bot) && bot.game.gameMode === 'spectator'
const landed = bot => inRaidWorld(bot) && bot.game.gameMode === 'adventure'
const queue = (bot, mode = 'SOLO') => bot.chat(`/cv queue ${MAP} NORMAL ${mode}`)
const spectate = (bot, other) => bot._client.write('spectate', { target: offlineUuid(other.username) })
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

async function quitAll () {
  for (const bot of live.splice(0)) { try { bot.end('disconnect.quitting') } catch (e) {} }
  await sleep(500)
}

/** Craftov item ids the bot carries, sorted, with amounts. */
function inventory (bot) {
  const ids = []
  for (const item of bot.inventory.slots) {
    const data = item && (item.components || []).find(c => c.type === 'custom_data')
    const pdc = data && data.data.value.PublicBukkitValues && data.data.value.PublicBukkitValues.value
    if (pdc && pdc['craftov:id']) ids.push(pdc['craftov:id'].value + 'x' + item.count)
  }
  return ids.sort()
}

async function position (name) {
  const out = await rcon(`data get entity ${name} Pos`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}

async function raidIds () {
  return [...(await rcon('cv raids')).matchAll(/\b(R\d+)\b/g)].map(m => m[1])
}

async function balance (name) {
  const m = (await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  return m ? Number(m[1].replace(/,/g, '')) : null
}

/** The server log from here on: call the returned function for what was written since. */
function logFrom () {
  const start = fs.existsSync(LOG) ? fs.statSync(LOG).size : 0
  return () => {
    if (!fs.existsSync(LOG)) return ''
    const fd = fs.openSync(LOG, 'r')
    const size = fs.statSync(LOG).size
    const buf = Buffer.alloc(Math.max(0, size - start))
    fs.readSync(fd, buf, 0, buf.length, start)
    fs.closeSync(fd)
    return buf.toString('utf8')
  }
}

// The issue's error, this fix's own warnings, and any stack trace through Craftov.
const LOG_PROBLEMS = /Cannot measure distance|Entry camera could not|Could not land|at net\.infnetwork\.snowball\.craftov/

function logProblems (text) {
  return text.split('\n').filter(l => LOG_PROBLEMS.test(l)).slice(0, 3)
}

/**
 * Kills the bot through the plugin's own lethal-damage path (spawn protection lasts 3 s after
 * landing). mineflayer's physics then stops: a real client sends no movement of its own while it
 * watches another entity, and the bot's falling would otherwise show up in its server position.
 */
async function kill (bot) {
  await rcon(`damage ${bot.username} 500 minecraft:generic`)
  // In a squad that only knocks down, and only hostile fire or an explosion finishes a downed player.
  if (!await waitFor(() => bot.game.gameMode === 'spectator', 600)) await rcon(`damage ${bot.username} 500 minecraft:explosion`)
  bot.physicsEnabled = false
}

/** The first camera packet at or after the moment the bot was made a spectator by dying. */
function deathCamera (bot, since) {
  const died = bot.modes.find(m => m.at >= since && m.mode === 3)
  const cam = died && bot.cameras.find(c => c.at >= died.at)
  return { died, cam }
}

const scenarios = {
  async menu () {
    const log = logFrom()
    const B = await make()
    const A = await make()
    await sleep(2500)
    queue(A)
    if (!await waitFor(() => onCamera(A), 40000)) return ['FAIL', 'no entry camera']
    const raidWorld = A.worldName
    const cameraAt = Date.now()
    await sleep(700)
    const pickedAt = Date.now()
    for (let i = 0; i < 3; i++) { spectate(A, B); await sleep(250) }
    const down = await waitFor(() => landed(A), 8000)
    const landedIn = (Date.now() - pickedAt) / 1000
    const problems = []
    const away = A.worlds.filter(w => w.at > cameraAt && w.name !== raidWorld)
    if (away.length) problems.push(`sent to ${away.map(w => w.name).join(',')}`)
    if (!down) problems.push(`not landed 8 s after the menu (${A.worldName} ${A.game.gameMode})`)
    const resets = A.cameras.filter(c => c.at > pickedAt && c.at < pickedAt + 1500 && c.type === 'self')
    if (resets.length) problems.push(`camera let go ${resets.length}x`)
    const raids = await rcon('cv raids')
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'stayed on the camera and landed'} | landed ${landedIn.toFixed(1)}s after the menu | ${raids.trim()}`]
  },

  async tp () {
    const log = logFrom()
    const A = await make()
    const C = await make()
    await sleep(2500)
    A.chat(`/cv party invite ${C.username}`); await sleep(600)
    C.chat('/cv party accept'); await sleep(600)
    queue(A, 'TRIO')
    if (!await waitFor(() => onCamera(A) && onCamera(C), 40000)) return ['FAIL', 'no entry camera']
    const raidWorld = A.worldName
    await sleep(700)
    const sentAt = Date.now()
    await rcon(`tp ${A.username} 0 100 0`)
    const both = await waitFor(() => landed(A) && landed(C), 12000)
    await sleep(500)
    const problems = []
    const trip = A.worlds.filter(w => w.at >= sentAt).map(w => w.name)
    if (!both) problems.push(`A ${A.worldName} ${A.game.gameMode}, C ${C.worldName} ${C.game.gameMode}`)
    if (A.worldName !== raidWorld || C.worldName !== raidWorld) problems.push(`A in ${A.worldName}, C in ${C.worldName}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'both landed, nothing in the log'} | A went ${trip.join(' -> ') || '(nowhere)'}`]
  },

  async end () {
    const log = logFrom()
    const A = await make()
    await sleep(2500)
    const gear = inventory(A)
    const money = await balance(A.username)
    const older = await raidIds()
    queue(A)
    if (!await waitFor(() => onCamera(A), 40000)) return ['FAIL', 'no entry camera']
    await sleep(700)
    // Earlier scenarios' raids can still be open (their bots count as disconnected): A's is the new one.
    const raidId = (await raidIds()).find(id => !older.includes(id))
    if (!raidId) return ['FAIL', `no new raid in ${await rcon('cv raids')}`]
    const endAt = Date.now()
    await rcon(`cv end ${raidId}`)
    const home = await waitFor(() => inLobby(A) && A.game.gameMode === 'adventure', 10000)
    const homeIn = (Date.now() - endAt) / 1000
    let closed = false
    while (Date.now() - endAt < 10000) {
      if (!(await raidIds()).includes(raidId)) { closed = true; break }
      await sleep(250)
    }
    const closedIn = (Date.now() - endAt) / 1000
    await sleep(800)
    const problems = []
    if (!home) problems.push(`not in the lobby (${A.worldName} ${A.game.gameMode})`)
    if (!closed) problems.push(`${raidId} still open after 10 s`)
    const now = inventory(A)
    if (!same(gear, now)) problems.push(`gear ${gear.join(',')} -> ${now.join(',')}`)
    const after = await balance(A.username)
    if (after !== money) problems.push(`money ${money} -> ${after}`)
    const told = A.chatLog.find(m => m.includes('落地前'))
    if (!told) problems.push('not told')
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'home with the same gear and money'} | lobby after ${homeIn.toFixed(1)}s, ${raidId} closed after ${closedIn.toFixed(1)}s | ${gear.length} items, ◈${money} | ${told || ''}`]
  },

  async death () {
    const log = logFrom()
    const B = await make()
    const A = await make()
    await sleep(2500)
    queue(A)
    if (!await waitFor(() => landed(A), 40000)) return ['FAIL', 'never landed']
    await sleep(3500)
    const killedAt = Date.now()
    await kill(A)
    if (!await waitFor(() => A.game.gameMode === 'spectator', 3000)) return ['FAIL', 'did not die']
    await sleep(300)
    const { died, cam } = deathCamera(A, killedAt)
    const problems = []
    if (!cam) problems.push('no camera packet')
    else {
      if (cam.at - died.at > 100) problems.push(`camera ${cam.at - died.at} ms after death`)
      if (cam.type !== 'item_display') problems.push(`camera on ${cam.type}`)
    }
    const raidWorld = A.worldName
    const before = await position(A.username)
    const pickedAt = Date.now()
    for (let i = 0; i < 3; i++) { spectate(A, B); await sleep(250) }
    await sleep(500)
    const after = await position(A.username)
    if (A.worldName !== raidWorld) problems.push(`sent to ${A.worldName}`)
    if (!before || !after || Math.hypot(after[0] - before[0], after[1] - before[1], after[2] - before[2]) > 0.2) problems.push(`moved ${before} -> ${after}`)
    // Shift does not leave the death camera either.
    A.physicsEnabled = true
    A.setControlState('sneak', true)
    await sleep(600)
    A.setControlState('sneak', false)
    A.physicsEnabled = false
    const freed = A.cameras.filter(c => c.at > (cam ? cam.at : killedAt) && c.at < Date.now())
    if (freed.length) problems.push(`camera changed to ${freed.map(c => c.type).join(',')} while dead`)
    // Solo, no killer: the report and the lobby come 5 s after death.
    const home = await waitFor(() => inLobby(A) && A.game.gameMode === 'adventure', 8000)
    if (!home) problems.push(`not in the lobby (${A.worldName} ${A.game.gameMode})`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'camera locked at once and held, menu did nothing'} | camera ${cam ? cam.at - died.at : '-'} ms after death on ${cam ? cam.type : '-'}`]
  },

  async replay () {
    const log = logFrom()
    const B = await make()
    const A = await make()
    const K = await make()
    await sleep(2500)
    queue(A)
    queue(K)
    if (!await waitFor(() => landed(A) && landed(K), 40000)) return ['FAIL', `not landed: A ${A.worldName} ${A.game.gameMode}, K ${K.worldName} ${K.game.gameMode}`]
    if (A.worldName !== K.worldName) return ['FAIL', `different raids: ${A.worldName} / ${K.worldName}`]
    await sleep(3500)
    // K steps up to A and stabs once with the starter knife (hotbar 3): A's last hit is then K's.
    await rcon(`execute as ${K.username} at ${A.username} run tp @s ~1 ~ ~ facing entity ${A.username}`)
    await sleep(800)
    K.setQuickBarSlot(2)
    await sleep(400)
    const target = K.players[A.username] && K.players[A.username].entity
    if (!target) return ['FAIL', 'K cannot see A']
    K.attack(target)
    await sleep(400)
    const killedAt = Date.now()
    await kill(A)
    if (!await waitFor(() => A.game.gameMode === 'spectator', 3000)) return ['FAIL', 'did not die']
    const problems = []
    // The replay starts 30 ticks after death on the killer's stand-in (a mannequin).
    const replayed = await waitFor(() => A.cameras.some(c => c.at > killedAt && c.type === 'mannequin'), 4000)
    const { died, cam } = deathCamera(A, killedAt)
    if (!cam) problems.push('no camera packet')
    else {
      if (cam.at - died.at > 100) problems.push(`camera ${cam.at - died.at} ms after death`)
      if (cam.type !== 'item_display') problems.push(`camera on ${cam.type}`)
    }
    if (!replayed) problems.push(`no replay (cameras: ${A.cameras.filter(c => c.at > killedAt).map(c => c.type).join(',')})`)
    await sleep(400)
    const between = A.cameras.filter(c => cam && c.at > cam.at && c.type !== 'mannequin')
    if (between.length) problems.push(`camera went to ${between.map(c => c.type).join(',')} before the replay`)
    if (cam && !A.gone.some(g => g.id === cam.id)) problems.push('death camera not removed')
    const raidWorld = A.worldName
    const pickedAt = Date.now()
    for (let i = 0; i < 3; i++) { spectate(A, B); await sleep(250) }
    await sleep(400)
    if (A.worldName !== raidWorld) problems.push(`sent to ${A.worldName} during the replay`)
    const lost = A.cameras.filter(c => c.at > pickedAt && c.type !== 'mannequin')
    if (lost.length) problems.push(`camera went to ${lost.map(c => c.type).join(',')} during the replay`)
    // Shift skips the replay; with no squadmate left, the lobby is next.
    const skipAt = Date.now()
    A.physicsEnabled = true // mineflayer sends the Shift key with its physics tick
    A.setControlState('sneak', true)
    const home = await waitFor(() => inLobby(A) && A.game.gameMode === 'adventure', 3000)
    A.setControlState('sneak', false)
    if (!home) problems.push(`Shift did not skip (${A.worldName} ${A.game.gameMode})`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'death camera, then the replay; menu did nothing; Shift skipped'} | camera ${cam ? cam.at - died.at : '-'} ms after death, lobby ${((Date.now() - skipAt) / 1000).toFixed(1)}s after Shift`]
  },

  async mate () {
    const log = logFrom()
    const B = await make()
    const A = await make()
    const C = await make()
    await sleep(2500)
    A.chat(`/cv party invite ${C.username}`); await sleep(600)
    C.chat('/cv party accept'); await sleep(600)
    queue(A, 'TRIO')
    if (!await waitFor(() => landed(A) && landed(C), 40000)) return ['FAIL', 'never landed']
    await sleep(3500)
    const killedAt = Date.now()
    await kill(A)
    if (!await waitFor(() => A.game.gameMode === 'spectator', 3000)) return ['FAIL', 'did not die']
    // No killer: five seconds on the death camera, then the camera on the squadmate.
    const mateId = () => A.players[C.username] && A.players[C.username].entity && A.players[C.username].entity.id
    const onMate = () => A.cameras.some(c => c.at > killedAt && c.id === mateId())
    if (!await waitFor(onMate, 8000)) {
      return ['FAIL', `not watching the squadmate (cameras: ${A.cameras.filter(c => c.at > killedAt).map(c => c.type).join(',')})`]
    }
    await sleep(500)
    const problems = []
    const raidWorld = A.worldName
    const pickedAt = Date.now()
    for (let i = 0; i < 3; i++) { spectate(A, B); await sleep(250) }
    await sleep(500)
    if (A.worldName !== raidWorld) problems.push(`sent to ${A.worldName}`)
    const lost = A.cameras.filter(c => c.at > pickedAt)
    if (lost.length) problems.push(`camera went to ${lost.map(c => c.type).join(',')} after the menu`)
    // Shift still leaves for the lobby.
    A.physicsEnabled = true
    A.setControlState('sneak', true)
    const home = await waitFor(() => inLobby(A) && A.game.gameMode === 'adventure', 3000)
    A.setControlState('sneak', false)
    if (!home) problems.push(`Shift did not leave (${A.worldName} ${A.game.gameMode})`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    await quitAll()
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || 'watched the squadmate, menu did nothing, Shift went to the lobby']
  }
}

;(async () => {
  const wanted = process.argv.slice(2)
  const names = wanted.length ? wanted : Object.keys(scenarios)
  let failures = 0
  for (const name of names) {
    let result
    try {
      result = await scenarios[name]()
    } catch (e) {
      result = ['FAIL', e.stack]
    }
    await quitAll()
    if (result[0] === 'FAIL') failures++
    console.log(`${result[0]} ${name}: ${result[1]}`)
    // Let the previous raid wind down before the next scenario queues.
    await sleep(2000)
  }
  process.exit(failures)
})()
