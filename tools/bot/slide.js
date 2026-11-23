// Slide (issue #9, SlideService): sprint, double-tap sneak, slide. Two bots in one proving-ground raid
// (A slides, B watches and shoots), on a stone runway built high over the map; the raid is ended at
// the end. Keys go to the server as the vanilla client sends them (player_input packets with forward,
// sprint and shift together); mineflayer's own sneak control would send shift alone.
//
//   detect   A sprints and taps sneak down, up, down (100 ms apart, the second held): A is sat on a
//            seat (set_passengers) within 300 ms; the seat (as B sees it) and the server's summary
//            (/cv slide) say it went 3.5 to 4.5 blocks in 14 to 20 ticks
//   box      the same slide's body every tick (/cv slide, sneak held all the way): its box bottom
//            is 0.6 under the ground it slides over and its top 1.2 over it (a standing box held,
//            not the 1.5 crouching one); mid-slide the server has A neither sneaking nor crouched,
//            and after it A stands on the ground
//   stamina  a slide under 25 kg costs 2 x stamina.sprint-drain (0.3) a tick, about 10; carrying
//            30 to 40 kg of collectibles the load factor (1 + (kg - 25) / 30) on top
//   refuse   no slide: within 30 ticks of the last one ended (cooldown); past that but with under
//            10 ticks of sprinting since; holding sneak (one long press); sneaking along then
//            tapping; walking (no sprint) and double-tapping; with stamina under 15 (/cv slide A
//            stamina 10); taps 400 ms apart. The same sprint and taps otherwise slide.
//   zones    B, in a pit at the end of the runway with its eyes just over it, about 8 blocks from
//            where A slides straight at it, an M4C on single shots, aimed, lag compensation off:
//            a round aimed 1.0 above the ground hits A's head, 0.4 the torso, 0.15 (into the legs
//            stretched out ahead of the body box) a leg
//   keys     mid-slide, A's Q reloads (not the ammo switch), F switches the fire mode (not the
//            operator skill) and a left click fires a round
//   hear     B 20 blocks from A hears A's slide (craftov:move.slide) though A holds sneak; 30
//            blocks away (past footsteps.slide-range 24) it does not
//   wall     A slides at a stone wall 2.5 blocks ahead: the slide stops at the wall (reason wall,
//            under 2.5 blocks); at a closed oak door the same
//   low      A slides under a roof 1.5 blocks over the runway and stops under it: A crouches there
//            (pose CROUCHING), not standing in the roof
//   rejoin   A slides and quits mid-slide: its stand-in stands on the runway (not 0.6 into it), and
//            when A comes back it is on the runway too
//   body     A slides and is killed mid-slide: the body lies on the runway
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node slide.js [scenario ...]
//
// Needs `debug: true` (impacts and slides are read from the log), test mode (`test.enabled: true`, so
// the bots may queue for the proving ground) and `match.wait-seconds` short (3) in the server's
// plugins/Craftov/config.yml. Exit code: number of failed checks (2+ also when the setup failed).
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || process.env.MC_PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const LOG = process.env.SERVER_LOG || path.join(__dirname, '../../server/run/logs/latest.log')
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
// rejoin before zones (being shot puts A in a fight, and quitting in one is death), body last.
const ALL = ['detect', 'box', 'stamina', 'refuse', 'keys', 'hear', 'wall', 'low', 'rejoin', 'zones', 'body']
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ALL
const tag = String(Math.floor(10000 + Math.random() * 90000))
const A_NAME = 'Sla' + tag
const B_NAME = 'Slb' + tag

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail !== '' ? ' :: ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''}`)
}

// ---------------------------------------------------------------- server log
function logSince (from) {
  const fd = fs.openSync(LOG, 'r')
  try {
    const size = fs.fstatSync(fd).size
    const buf = Buffer.alloc(Math.max(0, size - from))
    fs.readSync(fd, buf, 0, buf.length, from)
    return buf.toString('utf8').split('\n')
  } finally { fs.closeSync(fd) }
}
const logSize = () => fs.statSync(LOG).size

// ---------------------------------------------------------------- bots
let A = null
let B = null

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bot.titles = []
  bot.bars = []
  bot.heard = []
  bot._client.on('set_title_text', p => bot.titles.push(JSON.stringify(p)))
  bot._client.on('action_bar', p => bot.bars.push({ at: Date.now(), text: JSON.stringify(p) }))
  const onSound = p => {
    const key = (JSON.stringify(p.sound || p.soundId || p).match(/[a-z_]+:[a-z0-9_./]+/) || [null])[0]
    bot.heard.push({ at: Date.now(), key, volume: p.volume, x: p.x / 8, y: p.y / 8, z: p.z / 8 })
  }
  bot._client.on('sound_effect', onSound)
  bot._client.on('entity_sound_effect', onSound)
  // mineflayer applies player_rotation as absolute angles; recoil sends it relative, as vanilla expects.
  bot.once('login', () => {
    bot._client.removeAllListeners('player_rotation')
    bot._client.on('player_rotation', p => {
      const yaw = (p.relativeYaw ? conv.toNotchianYaw(bot.entity.yaw) : 0) + p.yaw
      const pitch = Math.max(-90, Math.min(90, (p.relativePitch ? conv.toNotchianPitch(bot.entity.pitch) : 0) + p.pitch))
      bot.entity.yaw = conv.fromNotchianYaw(yaw)
      bot.entity.pitch = conv.fromNotchianPitch(pitch)
    })
  })
  watchEntities(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

/** Every entity's position as the server sends it (mineflayer does not follow passengers or displays closely). */
function watchEntities (bot) {
  bot.seen = new Map()
  bot.rides = []
  const at = (id, x, y, z) => {
    const e = bot.seen.get(id) || { id, path: [] }
    e.x = x; e.y = y; e.z = z
    e.path.push({ at: Date.now(), x, y, z })
    if (e.path.length > 400) e.path.shift()
    bot.seen.set(id, e)
  }
  bot._client.on('spawn_entity', p => { at(p.entityId, p.x, p.y, p.z); bot.seen.get(p.entityId).type = p.type })
  bot._client.on('rel_entity_move', p => { const e = bot.seen.get(p.entityId); if (e) at(p.entityId, e.x + p.dX / 4096, e.y + p.dY / 4096, e.z + p.dZ / 4096) })
  bot._client.on('entity_move_look', p => { const e = bot.seen.get(p.entityId); if (e) at(p.entityId, e.x + p.dX / 4096, e.y + p.dY / 4096, e.z + p.dZ / 4096) })
  bot._client.on('sync_entity_position', p => at(p.entityId, p.x, p.y, p.z))
  bot._client.on('entity_teleport', p => at(p.entityId, p.x, p.y, p.z))
  bot._client.on('set_passengers', p => bot.rides.push({ at: Date.now(), vehicle: p.entityId, passengers: p.passengers }))
}

/** The keys held, as one player_input packet (what a vanilla client sends whenever one changes). */
function keys (bot, k = {}) {
  bot._client.write('player_input', { inputs: { forward: !!k.forward, backward: !!k.backward, left: !!k.left, right: !!k.right, jump: !!k.jump, shift: !!k.shift, sprint: !!k.sprint } })
}

/** The sprint command (mineflayer's own sends an action id 1.21.11 reads as something else). */
function sprinting (bot, on) {
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 'start_sprinting' : 'stop_sprinting', jumpBoost: 0 })
}

/**
 * mineflayer moves the bot itself (its physics, at sprint pace with its sprint control); the server
 * is told the keys by keys() and the sprint by sprinting().
 */
function run (bot, sprint) {
  bot.setControlState('forward', true)
  bot.setControlState('sprint', sprint)
  keys(bot, { forward: true, sprint })
  sprinting(bot, sprint)
}

function halt (bot) {
  bot.clearControlStates()
  keys(bot, {})
  sprinting(bot, false)
}

/**
 * Taps sneak twice while running (down `hold` ms, up `gap` ms, down again and held), as a player
 * does. Returns when the second press went out.
 */
async function doubleTap (bot, { sprint = true, hold = 100, gap = 100 } = {}) {
  keys(bot, { forward: true, sprint, shift: true })
  await sleep(hold)
  keys(bot, { forward: true, sprint })
  await sleep(gap)
  keys(bot, { forward: true, sprint, shift: true })
}

/** A seated on a seat: the time the set_passengers with A in it came, or null within `ms`. */
async function seatedWithin (bot, since, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const ride = bot.rides.find(r => r.at >= since && r.passengers.includes(bot.entity.id))
    if (ride) return ride
    await sleep(10)
  }
  return null
}

/** /cv slide <who>: stamina, pose, box, and the last slide's summary, as numbers. */
async function slideInfo (who) {
  const text = await rcon(`cv slide ${who}`)
  const num = re => { const m = text.match(re); return m ? Number(m[1]) : null }
  const last = text.includes('last reason=') ? {
    reason: (text.match(/last reason=(\S+)/) || [])[1],
    ticks: num(/ticks=(\d+)/),
    distance: num(/distance=(-?[\d.]+)/),
    spent: num(/spent=(-?[\d.]+)/),
    speed: num(/speed=(-?[\d.]+)/),
    ago: num(/ago=(\d+)/),
    from: (text.match(/from=(\S+)/) || [])[1],
    to: (text.match(/ to=(\S+)/) || [])[1],
    minY: (text.match(/minY=(\S+)\.\.(\S+?) /) || []).slice(1).map(Number),
    maxY: (text.match(/maxY=(\S+)\.\.(\S+)$/m) || []).slice(1).map(Number)
  } : null
  return {
    text,
    stamina: num(/stamina=(-?[\d.]+)/),
    weight: num(/weight=(-?[\d.]+)/),
    sliding: /sliding=true/.test(text),
    cooldown: num(/cooldown=(\d+)/),
    pose: (text.match(/pose=(\S+)/) || [])[1],
    sneaking: /sneaking=true/.test(text),
    vehicle: /vehicle=true/.test(text),
    y: num(/ y=(-?[\d.]+)/),
    height: num(/height=(-?[\d.]+)/),
    refused: (text.match(/refused=(.*?)(?: ago=\d+)?$/m) || [])[1],
    refusedAgo: num(/refused=.* ago=(\d+)$/m),
    last
  }
}

const tp = (who, x, y, z, yaw = 0, pitch = 0) => rcon(`execute as ${who} at ${A_NAME} run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)
const at = cmd => rcon(`execute at ${A_NAME} run ${cmd}`)

// ---------------------------------------------------------------- the runway
// A stone runway over the proving ground, along +X: blocks at y RY, standing height RY + 1.
const RX = 40
const RY = 160
const RZ = 40
const LEN = 90
const GROUND = RY + 1

async function buildRunway () {
  await at(`fill ${RX - 2} ${RY} ${RZ - 6} ${RX + LEN} ${RY} ${RZ + 6} minecraft:smooth_stone`)
  await at(`fill ${RX - 2} ${RY + 1} ${RZ - 6} ${RX + LEN} ${RY + 4} ${RZ + 6} minecraft:air`)
}

async function clearRunway () {
  await at(`fill ${RX - 2} ${RY + 1} ${RZ - 6} ${RX + LEN} ${RY + 4} ${RZ + 6} minecraft:air`)
}

/** Puts A at the start of the runway facing +X (yaw -90), and waits for it to settle. */
async function toStart (x = RX, z = RZ) {
  halt(A)
  await tp(A_NAME, x + 0.5, GROUND, z + 0.5, -90, 0)
  await sleep(900)
}

/**
 * A sprints along +X for `ms`, then double-taps sneak; returns { tapAt, ride } where ride is the
 * set_passengers that sat A down (or null). Sneak stays held unless `release`.
 */
async function sprintAndSlide ({ ms = 900, sprint = true, release = 0, tapOpts = {} } = {}) {
  run(A, sprint)
  await sleep(ms)
  const tapAt = Date.now()
  await doubleTap(A, { sprint, ...tapOpts })
  const ride = await seatedWithin(A, tapAt, 400)
  if (release) {
    await sleep(release)
    keys(A, { forward: true, sprint })
  }
  return { tapAt, ride }
}

/** Waits for A's slide to end (the server's summary changes), at most `ms`. */
async function slideEnded (ms = 3000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const info = await slideInfo(A_NAME)
    if (!info.sliding && info.last && info.last.ago !== null && info.last.ago < 200) return info
    await sleep(100)
  }
  return slideInfo(A_NAME)
}

/** The seat A rode: its positions as B received them, from the set_passengers on. */
function seatPath (bot, ride) {
  const seat = bot.seen.get(ride.vehicle)
  return seat ? seat.path.filter(p => p.at >= ride.at - 100) : []
}

// ---------------------------------------------------------------- raid
const raidIds = async () => (await rcon('cv raids')).split('\n').map(l => (l.trim().match(/^\[Craftov\]\s+(\S+)\s/) || [])[1]).filter(Boolean)
let ourRaids = []

async function intoRaid () {
  const before = await raidIds()
  A = await make(A_NAME)
  B = await make(B_NAME)
  await sleep(2500)
  A.chat('/cv queue proving NORMAL SOLO')
  B.chat('/cv queue proving NORMAL SOLO')
  const started = bot => bot.titles.some(t => t.includes('行动开始'))
  const until = Date.now() + 150000
  while (Date.now() < until && !(started(A) && started(B))) await sleep(500)
  if (!started(A) || !started(B)) throw new Error('the bots did not both get into a raid')
  const dimension = async who => ((await rcon(`data get entity ${who} Dimension`)).match(/"([^"]+)"/) || [])[1]
  const a = await dimension(A_NAME)
  const b = await dimension(B_NAME)
  log('in raid', a, b)
  if (!a || a !== b) throw new Error(`the bots are in different worlds: ${a} / ${b}`)
  ourRaids = (await raidIds()).filter(id => !before.includes(id))
  await sleep(3500) // insertion protection
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await at(`kill @e[type=minecraft:${type},distance=..400]`)
  for (const who of [A_NAME, B_NAME]) {
    await rcon(`effect give ${who} minecraft:resistance 600 4 true`)
    await rcon(`effect give ${who} minecraft:saturation 600 1 true`)
  }
  // No armour on A to blur the hits; a light load (the starter kit) so the slide costs the base rate.
  await rcon(`item replace entity ${A_NAME} armor.head with minecraft:air`)
  await rcon(`item replace entity ${A_NAME} armor.chest with minecraft:air`)
  await buildRunway()
}

// ---------------------------------------------------------------- scenarios

async function detect () {
  await toStart()
  await tp(B_NAME, RX + 0.5, GROUND, RZ + 4.5, -90, 0)
  await sleep(600)
  const { tapAt, ride } = await sprintAndSlide()
  check('detect: a sprint and a double tap seat A within 300 ms', ride && ride.at - tapAt <= 300,
    ride ? `${ride.at - tapAt} ms` : 'no set_passengers with A in it')
  const info = await slideEnded()
  halt(A)
  const s = info.last
  log('summary', s)
  check('detect: the slide went 3.5 to 4.5 blocks (server)', s && s.distance >= 3.5 && s.distance <= 4.5, s && s.distance)
  check('detect: the slide took 14 to 20 ticks (server)', s && s.ticks >= 14 && s.ticks <= 20, s && `${s.ticks} ticks, ${s.reason}`)
  if (ride) {
    const pathB = seatPath(B, ride)
    if (pathB.length >= 2) {
      const first = pathB[0]
      const last = pathB[pathB.length - 1]
      const d = Math.hypot(last.x - first.x, last.z - first.z)
      check('detect: B saw the seat go 3.5 to 4.5 blocks', d >= 3.5 && d <= 4.5, `${d.toFixed(2)} blocks in ${last.at - first.at} ms`)
      const off = Math.abs(first.y - GROUND)
      check('detect: the seat is on the ground', off < 0.01, `seat y ${first.y} ground ${GROUND}`)
    } else check('detect: B saw the seat move', false, `${pathB.length} positions`)
    const off = A.rides.find(r => r.at > ride.at && r.vehicle === ride.vehicle && !r.passengers.includes(A.entity.id))
    const gone = off ? off.at - ride.at : null
    check('detect: A was sat for 14 to 20 ticks (700-1000 ms, B side)', gone !== null && gone >= 600 && gone <= 1150, gone)
  }
  return info
}

async function box () {
  await sleep(2000) // past the cooldown
  await toStart()
  // Sneak held from the second press to the end.
  const { ride } = await sprintAndSlide()
  const during = []
  for (let i = 0; ride && i < 3; i++) {
    await sleep(120)
    const now = await slideInfo(A_NAME)
    if (now.sliding) during.push({ sneaking: now.sneaking, pose: now.pose })
  }
  check('box: mid-slide, sneak held, the server has A neither sneaking nor crouched',
    during.length > 0 && during.every(d => !d.sneaking && d.pose === 'STANDING'), during)
  const info = await slideEnded()
  halt(A)
  const s = info.last
  check('box: the box bottom stayed 0.6 under the ground every tick, sneak held',
    s && Math.abs(s.minY[0] + 0.6) < 0.01 && Math.abs(s.minY[1] + 0.6) < 0.01, s && s.minY)
  check('box: the box top stayed 1.2 over the ground every tick (a standing box, not crouching)',
    s && Math.abs(s.maxY[0] - 1.2) < 0.01 && Math.abs(s.maxY[1] - 1.2) < 0.01, s && s.maxY)
  const lines = logSince(boxFrom).filter(l => l.includes(`[debug] slide ${A_NAME} tick=`))
  log(`${lines.length} tick lines in the log, e.g.`, lines[2] || lines[0] || '-')
  await sleep(300)
  const after = await slideInfo(A_NAME)
  check('box: after the slide A stands on the runway (not in it)', after.y !== null && Math.abs(after.y - GROUND) < 0.05 && !after.vehicle,
    `y ${after.y}, vehicle ${after.vehicle}`)
}
let boxFrom = 0

async function stamina () {
  await sleep(2000)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  await toStart()
  const light = await slideInfo(A_NAME)
  await sprintAndSlide({ release: 200 })
  const a = (await slideEnded()).last
  halt(A)
  const per = 0.3 * 2
  log('light', light.weight, 'kg', a)
  check(`stamina: under 25 kg (${light.weight} kg) a slide costs 2 x 0.3 a tick (about 10)`,
    a && Math.abs(a.spent - per * a.ticks) < 0.7 && a.spent > 8 && a.spent < 12.5, a && `${a.spent} over ${a.ticks} ticks`)
  // Heavy collectibles until A carries 30 to 40 kg (over 40 it could not sprint at all).
  const given = []
  for (const id of ['lion_statue', 'eel_generator', 'gramophone', 'gold_bar', 'gold_bar', 'gold_bar', 'gold_bar']) {
    if ((await slideInfo(A_NAME)).weight >= 30) break
    await rcon(`cv give ${A_NAME} ${id}`)
    given.push(id)
    await sleep(1200) // the weight is cached for a second
  }
  await rcon(`cv slide ${A_NAME} stamina 100`)
  await sleep(2000)
  const heavy = await slideInfo(A_NAME)
  await toStart()
  await sprintAndSlide({ release: 200 })
  const b = (await slideEnded()).last
  halt(A)
  const factor = 1 + Math.max(0, heavy.weight - 25) / 30
  log('heavy', heavy.weight, 'kg factor', factor.toFixed(3), b)
  check(`stamina: at ${heavy.weight} kg the load factor (${factor.toFixed(2)}) makes it ${(per * factor).toFixed(2)} a tick`,
    b && heavy.weight > 25 && heavy.weight <= 40 && Math.abs(b.spent - per * factor * b.ticks) < 0.9, b && `${b.spent} over ${b.ticks} ticks`)
  for (const id of new Set(given)) await rcon(`clear ${A_NAME} *[minecraft:custom_data~{PublicBukkitValues:{"craftov:id":"${id}"}}]`)
}

/**
 * Does this run of keys seat A? Waits out the cooldown first unless `now`. Returns the ride (or null),
 * /cv slide afterwards, and the reason the server gave for not sliding if it gave one during the keys.
 */
async function slides (what, keysFn, { now = false } = {}) {
  if (!now) await sleep(2000)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  if (!now) await toStart()
  const since = Date.now()
  await keysFn()
  const ride = await seatedWithin(A, since, 600)
  halt(A)
  const info = await slideInfo(A_NAME)
  const ticks = Math.ceil((Date.now() - since) / 50) + 2
  const why = info.refusedAgo !== null && info.refusedAgo <= ticks ? info.refused : null
  if (ride) await slideEnded()
  log(what, ride ? 'slid' : 'no slide', '| refused:', why || '(nothing to refuse)')
  return { ride, info, why }
}

async function refuse () {
  // Right after a slide, sprinting again (a rider's sprint ends on the seat): the cooldown.
  await sleep(2000)
  await toStart()
  await sprintAndSlide({ release: 100 })
  await slideEnded()
  const again = await slides('cooldown', async () => { run(A, true); await sleep(300); await doubleTap(A); await sleep(100) }, { now: true })
  check('refuse: a double tap sprinting within 30 ticks of the last slide does not slide (cooldown)',
    !again.ride && /冷却/.test(again.why || ''), again.why)
  // Past the cooldown but without 10 ticks of sprinting since the last slide: a slide, 2 s standing,
  // then 100 ms of sprint and the taps (6 ticks of sprinting in all).
  await sleep(2000)
  await toStart()
  await sprintAndSlide({ release: 100 })
  await slideEnded()
  halt(A)
  await sleep(2000)
  const rested = await slides('no sprint since', async () => { run(A, true); await sleep(100); await doubleTap(A); await sleep(100) }, { now: true })
  check('refuse: past the cooldown, but under 10 ticks of sprinting since the last slide, does not slide',
    !rested.ride && /冲刺一会儿/.test(rested.why || ''), rested.why)
  // One long press while sprinting.
  const held = await slides('hold', async () => {
    run(A, true); await sleep(900)
    keys(A, { forward: true, sprint: true, shift: true }); await sleep(700)
    keys(A, { forward: true, sprint: true })
  })
  check('refuse: holding sneak while sprinting (one long press) does not slide', !held.ride, held.why)
  // Sneaking along, then tapping again.
  const sneaking = await slides('sneak-walk', async () => {
    A.setControlState('forward', true)
    keys(A, { forward: true, shift: true })
    await sleep(900)
    keys(A, { forward: true }); await sleep(100)
    keys(A, { forward: true, shift: true }); await sleep(100)
  })
  check('refuse: sneaking along and tapping again does not slide', !sneaking.ride, sneaking.why)
  // Walking (no sprint) and double-tapping.
  const walking = await slides('walk', async () => { run(A, false); await sleep(900); await doubleTap(A, { sprint: false }) })
  check('refuse: a double tap while walking does not slide', !walking.ride && /没在冲刺/.test(walking.why || ''), walking.why)
  // Out of breath: stamina under 15.
  const tired = await slides('stamina', async () => {
    run(A, true); await sleep(900)
    await rcon(`cv slide ${A_NAME} stamina 10`)
    await doubleTap(A)
  })
  check('refuse: with stamina under 15 there is no slide', !tired.ride && /体力/.test(tired.why || ''), tired.why)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  // A second tap too late (400 ms after the first).
  const slow = await slides('slow taps', async () => { run(A, true); await sleep(900); await doubleTap(A, { hold: 100, gap: 300 }) })
  check('refuse: taps 400 ms apart (over double-tap-ms 300) do not slide', !slow.ride, slow.why)
  // And the control: the same sprint and taps with everything in order do slide.
  const fine = await slides('control', async () => { run(A, true); await sleep(900); await doubleTap(A) })
  check('refuse: the same sprint and double tap otherwise slides', !!fine.ride, fine.why)
}

// ---------------------------------------------------------------- weapons
const dig = (bot, status) => bot._client.write('block_dig', { status, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
const dropKey = bot => dig(bot, 4)
const swapKey = bot => dig(bot, 6)
const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = (bot, id) => bot.inventory.slots.findIndex((x, i) => i >= 36 && i <= 44 && x && itemId(x) === id)

/** Gives `bot` the gun (on key 1, emptied first) and its rounds, holds it and fills the magazine. */
async function arm (bot, gun, ammo) {
  await rcon(`item replace entity ${bot.username} hotbar.0 with minecraft:air`)
  await rcon(`cv give ${bot.username} ${gun}`)
  await rcon(`cv give ${bot.username} ${ammo} 60`)
  await sleep(1000)
  const slot = hotbar(bot, gun)
  if (slot < 0) throw new Error(`${gun} is not in ${bot.username}'s hotbar`)
  bot.setQuickBarSlot(slot - 36)
  await sleep(1500)
  dropKey(bot)
  await sleep(4000)
}

/**
 * Switches the held gun to single shots: the action bar names the mode after each F (a result line
 * stays up 1.5 s before the next replaces it, so the presses are that far apart).
 */
async function semi (bot) {
  for (let i = 0; i < 4; i++) {
    const n = bot.bars.length
    swapKey(bot)
    await sleep(1700)
    if (bot.bars.slice(n).some(b => b.text.includes('单发'))) return
  }
  throw new Error('the gun did not switch to single shots')
}

/** Turns `bot`'s view to `p` and tells the server at once (before the trigger that follows). */
function lookAt (bot, p) {
  const eye = bot.entity.position.offset(0, 1.62, 0)
  const dx = p.x - eye.x
  const dy = p.y - eye.y
  const dz = p.z - eye.z
  const yaw = -Math.atan2(dx, dz) * 180 / Math.PI
  const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI
  bot.entity.yaw = conv.fromNotchianYaw(yaw)
  bot.entity.pitch = conv.fromNotchianPitch(pitch)
  bot._client.write('look', { yaw, pitch, flags: { onGround: true, hasHorizontalCollision: false } })
}

/** Holds right mouse (aiming): vanilla repeats the use packet every 4 ticks while it is down. */
function aimWhile (bot) {
  bot.activateItem()
  const timer = setInterval(() => bot.activateItem(), 150)
  return () => { clearInterval(timer); bot.deactivateItem() }
}

/** The debug impact lines of `who`'s rounds since `from`. */
function impacts (who, from) {
  const re = new RegExp(`\\[debug\\] ${who} impact eye=\\S+ hit=(\\S+) through=(\\d+)(?: keep=([0-9.]+) target=(\\S+) part=(\\S+) damage=([0-9.]+))? rewind=(\\d+)ms`)
  return logSince(from).map(l => l.match(re)).filter(Boolean)
    .map(m => ({ hit: m[1], target: m[4] || '-', part: m[5] || '-', damage: m[6] ? Number(m[6]) : 0 }))
}

async function zones () {
  await arm(B, 'm4c', 'ammo_556_m855')
  await semi(B)
  await rcon('cv lagcomp off')
  // A slides at B along the runway; B, 16 blocks on, aims down the sights at A's body from a pit 1.5
  // deep, eyes just over the runway: its rounds fly nearly level, so where A has got to by the time a
  // round goes off (A slides straight at B) hardly moves the height it meets A at.
  await at(`fill ${RX + 16} ${RY} ${RZ} ${RX + 16} ${RY + 2} ${RZ} minecraft:air`)
  await at(`setblock ${RX + 16} ${RY - 2} ${RZ} minecraft:stone`)
  await at(`setblock ${RX + 16} ${RY - 1} ${RZ} minecraft:smooth_stone_slab[type=bottom]`)
  await tp(B_NAME, RX + 16.5, RY - 0.5, RZ + 0.5, 90, 0)
  const stopAim = aimWhile(B)
  await sleep(1200)
  const results = { 1.0: [], 0.4: [], 0.15: [] }
  try {
    for (const height of [1.0, 0.4, 0.15, 1.0, 0.4, 0.15, 1.0, 0.4, 0.15]) {
      await sleep(1800)
      await rcon(`cv slide ${A_NAME} stamina 100`)
      await toStart()
      const { ride } = await sprintAndSlide({ ms: 700 })
      if (!ride) { results[height].push(null); continue }
      await sleep(250)
      const seat = B.seen.get(ride.vehicle)
      // The legs reach ahead of the body (towards B): aim at their middle.
      const ahead = height < 0.3 ? -0.6 : 0
      const from = logSize()
      lookAt(B, { x: seat.x - ahead, y: GROUND + height, z: seat.z })
      await sleep(60)
      B.swingArm()
      await sleep(700)
      const hit = impacts(B_NAME, from)[0] || null
      results[height].push(hit)
      await slideEnded()
      halt(A)
    }
  } finally {
    stopAim()
    await rcon('cv lagcomp on')
    await tp(B_NAME, RX + 20.5, GROUND, RZ + 4.5, -90, 0)
    await at(`fill ${RX + 16} ${RY - 2} ${RZ} ${RX + 16} ${RY - 1} ${RZ} minecraft:air`)
    await at(`setblock ${RX + 16} ${RY} ${RZ} minecraft:smooth_stone`)
  }
  log('zones', results)
  const parts = list => list.map(r => r ? `${r.target === A_NAME ? '' : r.target + ':'}${r.part}` : 'none').join(' ')
  const onA = r => r && r.target === A_NAME
  check('zones: 1.0 m over the ground on a sliding body is the head',
    results[1.0].every(r => onA(r) && r.part === 'head'), parts(results[1.0]))
  check('zones: 0.4 m over the ground is the torso',
    results[0.4].every(r => onA(r) && ['chest', 'stomach', 'left_arm', 'right_arm'].includes(r.part)), parts(results[0.4]))
  check('zones: 0.15 m over the ground, into the legs stretched out ahead, is a leg',
    results[0.15].every(r => onA(r) && ['left_leg', 'right_leg'].includes(r.part)), parts(results[0.15]))
}

async function keysScenario () {
  // A's own gun: the starter K47 on key 1, with two kinds of rounds (so a sneak+Q would switch them).
  await arm(A, 'k47', 'ammo_762_ps')
  await rcon(`cv give ${A_NAME} ammo_762_bp 30`)
  await sleep(500)
  // Fire a few into the air first, so a Q reloads.
  A.look(0, Math.PI / 6, true)
  await sleep(300)
  for (let i = 0; i < 3; i++) { A.swingArm(); await sleep(150) }
  await sleep(1500)
  // Q mid-slide (sneak held): a reload of the same rounds, not the switch to the other kind.
  await sleep(1500)
  await toStart()
  let from = logSize()
  let { ride } = await sprintAndSlide()
  await sleep(150)
  dropKey(A)
  await sleep(300)
  const reloads = logSince(from).filter(l => l.includes(`[debug] ${A_NAME} reload `))
  check('keys: Q mid-slide (sneak held) reloads, not the ammo switch', ride && reloads.length === 1 && /cycle=false/.test(reloads[0]) && /sliding=true/.test(reloads[0]),
    reloads[0] || (ride ? 'no reload line' : 'no slide'))
  await slideEnded()
  halt(A)
  await sleep(3500) // the reload finishes
  // F mid-slide: the fire mode, not the operator skill.
  await sleep(1500)
  await toStart()
  from = logSize()
  ;({ ride } = await sprintAndSlide())
  await sleep(150)
  swapKey(A)
  await sleep(400)
  const fs = logSince(from).filter(l => l.includes(`[debug] F ${A_NAME} -> `))
  check('keys: F mid-slide (sneak held) switches the fire mode, not the skill', ride && fs.length === 1 && /-> fire mode \(sneak=false/.test(fs[0]),
    fs[0] || (ride ? 'no F line' : 'no slide'))
  await slideEnded()
  halt(A)
  // A left click mid-slide fires a round (into the air ahead).
  await sleep(2000)
  await toStart()
  from = logSize()
  ;({ ride } = await sprintAndSlide())
  await sleep(200)
  A.look(-Math.PI / 2, Math.PI / 8, true)
  await sleep(100)
  A.swingArm()
  await sleep(500)
  const shots = impacts(A_NAME, from)
  check('keys: a left click mid-slide fires', ride && shots.length >= 1, ride ? `${shots.length} rounds` : 'no slide')
  await slideEnded()
  halt(A)
}

async function hear () {
  // B stands 20 blocks to the side of where A slides, then 30.
  const out = []
  for (const side of [20, 30]) {
    await sleep(2000)
    await at(`fill ${RX} ${RY} ${RZ + side - 1} ${RX + 14} ${RY} ${RZ + side + 1} minecraft:smooth_stone`)
    await tp(B_NAME, RX + 7.5, GROUND, RZ + side + 0.5, 0, 0)
    await sleep(1500)
    await toStart()
    const since = Date.now()
    const { ride } = await sprintAndSlide()
    await slideEnded()
    halt(A)
    const heard = B.heard.filter(h => h.at >= since && h.key === 'craftov:move.slide')
    const keysHeard = [...new Set(B.heard.filter(h => h.at >= since).map(h => h.key))]
    out.push({ side, ride: !!ride, heard: heard.length, keys: keysHeard })
  }
  log('hear', out)
  check('hear: B 20 blocks from the slide hears it (A holding sneak)', out[0].ride && out[0].heard >= 1, out[0])
  check('hear: B 30 blocks away (past footsteps.slide-range 24) does not', out[1].ride && out[1].heard === 0, out[1])
}

/**
 * A slides; once it sits, `build(x)` is run with the x it sat down at (a wall, a door, a roof ahead).
 * Returns the summary.
 */
async function slideInto (build, opts = {}) {
  await sleep(2000)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  await toStart()
  const { ride } = await sprintAndSlide(opts)
  if (!ride) return null
  const seat = A.seen.get(ride.vehicle)
  const x0 = seat ? seat.x : null
  await build(x0)
  const info = await slideEnded()
  halt(A)
  return { x0, last: info.last }
}

async function wall () {
  let wallX = 0
  const stone = await slideInto(async x0 => {
    wallX = Math.floor(x0 + 2.5)
    await at(`fill ${wallX} ${GROUND} ${RZ - 1} ${wallX} ${GROUND + 2} ${RZ + 1} minecraft:stone`)
  })
  log('wall', wallX, stone)
  const endX = stone && stone.last && Number(stone.last.to.split(',')[0])
  check('wall: a slide at a stone wall stops at once at the wall', stone && stone.last.reason === 'wall' && endX <= wallX - 0.3 + 1e-3 && endX > wallX - 0.75,
    stone && `${stone.last.reason}, ended at x ${endX}, wall at ${wallX}`)
  await clearRunway()
  let doorX = 0
  const door = await slideInto(async x0 => {
    doorX = Math.floor(x0 + 2.5)
    await at(`setblock ${doorX} ${GROUND} ${RZ} minecraft:oak_door[facing=west,half=lower,open=false]`)
    await at(`setblock ${doorX} ${GROUND + 1} ${RZ} minecraft:oak_door[facing=west,half=upper,open=false]`)
  })
  log('door', doorX, door)
  const doorEnd = door && door.last && Number(door.last.to.split(',')[0])
  check('wall: a slide at a closed door stops at the door, not through it', door && door.last.reason === 'wall' && doorEnd < doorX + 0.5,
    door && `${door.last.reason}, ended at x ${doorEnd}, door at ${doorX}`)
}

async function low () {
  let roofFrom = 0
  // Sneak let go mid-slide, so standing up is up to the room there is.
  const under = await slideInto(async x0 => {
    roofFrom = Math.floor(x0 + 1.5)
    await at(`fill ${roofFrom} ${GROUND + 1} ${RZ - 2} ${roofFrom + 8} ${GROUND + 1} ${RZ + 2} minecraft:smooth_stone_slab[type=top]`)
  }, { release: 100 })
  await sleep(400)
  const info = await slideInfo(A_NAME)
  log('low', roofFrom, under, info.text)
  const endX = under && under.last && Number(under.last.to.split(',')[0])
  check('low: a slide goes on under a roof 1.5 blocks up', under && endX > roofFrom + 0.3, under && `${under.last.reason}, ended at x ${endX}, roof from ${roofFrom}`)
  check('low: stopped under it, A crouches there (pose CROUCHING, box 1.5) on the runway',
    info.pose === 'SNEAKING' && Math.abs(info.height - 1.5) < 0.01 && Math.abs(info.y - GROUND) < 0.05 && !info.vehicle,
    `pose ${info.pose}, height ${info.height}, y ${info.y}`)
  // Walk out of it before the next scenario.
  await toStart()
}

/** A's stand-in (a mannequin holding A's UUID), or null. */
async function standIn () {
  const out = await rcon(`execute at ${B_NAME} run data get entity @e[type=minecraft:mannequin,sort=nearest,limit=1,distance=..40] Pos`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) } : null
}

async function rejoin () {
  await sleep(2000)
  await tp(B_NAME, RX + 20.5, GROUND, RZ + 4.5, -90, 0)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  await toStart()
  const { ride } = await sprintAndSlide()
  await sleep(300)
  A.quit()
  await sleep(2500)
  const body = await standIn()
  log('stand-in', body)
  check('rejoin: quitting mid-slide leaves the stand-in on the runway, not 0.6 into it', ride && body && Math.abs(body.y - GROUND) < 0.05,
    body ? `y ${body.y}` : 'no stand-in')
  A = await make(A_NAME)
  await sleep(3000)
  const y = Number(((await rcon(`data get entity ${A_NAME} Pos[1]`)).match(/(-?[\d.]+)d/) || [])[1])
  const world = ((await rcon(`data get entity ${A_NAME} Dimension`)).match(/"([^"]+)"/) || [])[1]
  check('rejoin: back in the raid, A stands on the runway', world && world.includes('craftov_raid') && Math.abs(y - GROUND) < 0.05, `${world} y ${y}`)
  await rcon(`effect give ${A_NAME} minecraft:resistance 600 4 true`)
  await sleep(3500) // the rejoin protection
}

async function body () {
  await sleep(2000)
  await tp(B_NAME, RX + 20.5, GROUND, RZ + 4.5, -90, 0)
  await rcon(`cv slide ${A_NAME} stamina 100`)
  await rcon(`effect clear ${A_NAME}`)
  await toStart()
  const { ride } = await sprintAndSlide()
  await sleep(250)
  await rcon(`damage ${A_NAME} 1000 minecraft:generic`)
  await sleep(1500)
  const out = await rcon(`execute at ${B_NAME} run data get entity @e[type=minecraft:mannequin,sort=nearest,limit=1,distance=..40,name="☠ ${A_NAME} 的遗体"] Pos`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  const y = m ? Number(m[2]) : null
  check('body: killed mid-slide, the body lies on the runway (not 0.6 into it)', ride && y !== null && Math.abs(y - GROUND) < 0.05,
    m ? `y ${y}` : out.trim())
}

// ---------------------------------------------------------------- run
;(async () => {
  try {
    await intoRaid()
  } catch (e) {
    log('setup failed:', e.message)
    process.exit(2 + failures.length)
  }
  for (const scenario of scenarios) {
    log('---', scenario)
    try {
      if (scenario === 'detect') await detect()
      else if (scenario === 'box') { boxFrom = logSize(); await box() }
      else if (scenario === 'stamina') await stamina()
      else if (scenario === 'refuse') await refuse()
      else if (scenario === 'keys') await keysScenario()
      else if (scenario === 'hear') await hear()
      else if (scenario === 'wall') await wall()
      else if (scenario === 'low') await low()
      else if (scenario === 'rejoin') await rejoin()
      else if (scenario === 'zones') await zones()
      else if (scenario === 'body') await body()
      else log('unknown scenario', scenario)
    } catch (e) {
      check(`${scenario}: ran`, false, e.stack || e.message)
    }
    halt(A)
    await clearRunway()
  }
  for (const id of ourRaids) await rcon('cv end ' + id).catch(() => {})
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  A.quit()
  B.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(e => { log('crashed:', e.stack || e.message); process.exit(2) })
