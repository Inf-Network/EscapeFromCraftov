// Hit registration (issue #35): fences, glass, doors, planks, soldiers' sight and moving targets, with two
// bots in one raid on the proving ground. Rounds are read from the server's debug impact lines, soldiers'
// shots from its "ai ... shot" lines.
//
//   sniper     the real AI sniper on its tower in the soldier arena, the shooter on the ground 30 blocks
//              east in the open: the sniper must spot the shooter over its railing (its line of sight used
//              to test the railing's 1.5-block collision box) and fire; the shooter's rounds at its head
//              over the railing (DM14, scoped) must hit the head, and it must keep firing back.
//   parapet    the sniper moved to the ground 41 blocks south of the drill building, the target on the
//              roof right behind the 1-high stone brick parapet: the sniper must fire at it, and aim at the
//              head that shows over the wall (aim >= 1.3), not at the hidden middle.
//   soldierlag a patrol soldier (a fresh one, posted with /cv ai spawn: the map's has been fighting the
//              shooter) walks 24 blocks across the shooter's view 12 blocks away (soldiers send
//              their position every 3 ticks). The shooter aims at where a vanilla client would draw it
//              (vanillaView: each update eased into over 3 client ticks, frames drawn between ticks) and
//              fires single M4C rounds while it walks, aiming down the sights, with lag compensation on
//              and then off: on must hit at least 75% and clearly more often. Soldiers that notice a player
//              stop and fight, and a soldier shot from out of sight takes cover, so for this one the
//              server's config.yml (put back after) has soldiers never notice anyone (ai.awareness.rate
//              below 0 drains what they notice) and not answer hits (ai.threat.seconds 0), so it walks on.
//   fence      the target stands on the sniper tower right behind the railing, the shooter on the ground
//              40 blocks away aims at the middle of its head: the rounds must hit the head, and rounds
//              aimed lower, into the railing's model, must still stop on it.
//   glass      in and around the glass house: through a plain glass pane the first round must already hit
//              (through=1, 90% damage); through the tinted south wall no round may get through.
//   door       an M4C (5.56 M855, pen 3) at the chest from 9 blocks, through the open doorway of the
//              drill building and then through its closed spruce door: through the door it must hit and do
//              less damage (70%) than through the doorway, on the same body part.
//   planks     a 1-block oak plank wall built in the open: square-on and at 45 degrees (where the round
//              leaves one plank through its side into the next) an M4C round must go through as one layer
//              (through=1, 60% damage); with a second wall right behind it, neither may.
//   crouch     the target crouches 100 ms before a chest-high round, or stands up 100 ms before a
//              head-high one on its crouched body, inside the rewind (ping + extra-ms): the hit zone must be
//              the one on the pose the shooter aimed at (torso / head), not the one it has taken since.
//   lag        the target strafes left and right 8 blocks away, walking and then sprinting; the shooter
//              aims at where a vanilla client would draw it (vanillaView) and fires single M4C rounds with
//              lag compensation on, then off (/cv lagcomp). With it on the server rewinds ping + extra-ms
//              (the log must say so) and the hit rate must be clearly higher. Each series also logs how
//              far behind the server the drawn target was (vanillaView.lag), the rewind it needed.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node hitreg.js [scenario ...]
//
// Needs `debug: true` (hits are read from the log) and test mode (`test.enabled: true`, so the bots may
// queue for the proving ground) in the server's plugins/Craftov/config.yml (CRAFTOV_CONFIG, by default
// next to the log's server). A NORMAL raid has the tower sniper 80% of the time; `ai.count-multiplier:
// 1.25` makes it certain. Soldiers other than the sniper and one patrol are killed at the start (the
// scenarios that need none kill those too), cover on the lines of fire is cleared, glass broken and walls
// built: the bots' raid is ended at the end. Leaves lag compensation switched on (/cv lagcomp on). Exit
// code: number of failed checks (2+ also when the bots could not be set up).
//
// Server config (tools/bot/suite.js sets it for its run): debug true (hits are read from the log),
// test.enabled true (the bots queue for the proving ground, a test map), ai.count-multiplier 1.25
// (the tower sniper every raid), ai.operator-chance [0, 0, 0] and events.per-raid 0 (no rival squad
// or event walks into the lines of fire), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const LOG = env.LOG
const CONFIG = process.env.CRAFTOV_CONFIG || path.join(path.dirname(LOG), '../plugins/Craftov/config.yml')
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const ALL = ['sniper', 'parapet', 'soldierlag', 'fence', 'glass', 'door', 'planks', 'crouch', 'lag']
const SOLDIER_SCENARIOS = ['sniper', 'parapet', 'soldierlag']
const scenarios = wanted.length ? wanted : ALL
const tag = String(Math.floor(10000 + Math.random() * 90000))
const SHOOTER = 'Hrs' + tag
const TARGET = 'Hrt' + tag
const SNIPER = '@e[type=minecraft:husk,tag=hrsniper,limit=1]'
const PATROL = '@e[type=minecraft:zombie,tag=hrpatrol,limit=1]'

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
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
const IMPACT = new RegExp(`\\[debug\\] ${SHOOTER} impact eye=\\S+ hit=(\\S+) through=(\\d+)(?: keep=([0-9.]+) target=(\\S+) part=(\\S+) damage=([0-9.]+))? rewind=(\\d+)ms`)
/** The shooter's rounds logged since `from`: { hit, through, keep, target, part, damage, rewind }. */
function impacts (from) {
  const out = []
  for (const line of logSince(from)) {
    const m = line.match(IMPACT)
    if (m) {
      out.push({ hit: m[1], through: Number(m[2]), keep: m[3] ? Number(m[3]) : 1, target: m[4] || '-', part: m[5] || '-', damage: m[6] ? Number(m[6]) : 0, rewind: Number(m[7]) })
    }
  }
  return out
}
/** Soldiers' shots of `kind` at `victim` logged since `from`: { dist, hits, aim }. */
function aiShots (from, kind, victim) {
  const re = new RegExp(`\\[debug\\] ai ${kind} shot ${victim} dist=([0-9.]+) .*hits=(\\d+) aim=([0-9.]+)`)
  const out = []
  for (const line of logSince(from)) {
    const m = line.match(re)
    if (m) out.push({ dist: Number(m[1]), hits: Number(m[2]), aim: Number(m[3]) })
  }
  return out
}

// ---------------------------------------------------------------- server config
let configBackup = null
/**
 * Sets keys under `ai:` in the server's config.yml (`{ 'awareness.rate': -50 }`: a key of the section
 * one level down, which must be in the file) and reloads it; null puts the file back.
 */
async function setAi (values) {
  if (values === null) {
    if (configBackup === null) return
    fs.writeFileSync(CONFIG, configBackup)
    configBackup = null
  } else {
    let text = fs.readFileSync(CONFIG, 'utf8')
    if (configBackup === null) configBackup = text
    const ai = text.search(/^ai:/m)
    if (ai < 0) throw new Error(`no ai section in ${CONFIG}`)
    for (const [key, value] of Object.entries(values)) {
      const parts = key.split('.')
      const re = parts.length === 1
        ? new RegExp(`^( {2}${parts[0]}: *)\\S+`, 'm')
        : new RegExp(`^( {2}${parts[0]}:\\n(?: {4}.*\\n|\\s*#.*\\n)*? {4}${parts[1]}: *)\\S+`, 'm')
      const head = text.slice(0, ai)
      const rest = text.slice(ai)
      if (!re.test(rest)) throw new Error(`no ai.${key} in ${CONFIG}`)
      text = head + rest.replace(re, `$1${value}`)
    }
    fs.writeFileSync(CONFIG, text)
  }
  log('cv reload:', (await rcon('cv reload')).trim())
}

// ---------------------------------------------------------------- bots
function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bot.titles = []
  bot.bars = []
  bot.on('title', t => bot.titles.push(JSON.stringify(t)))
  bot._client.on('set_title_text', p => bot.titles.push(JSON.stringify(p)))
  bot._client.on('action_bar', p => bot.bars.push(JSON.stringify(p)))
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
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

let shooter = null
let target = null
let raidStarted = 0

const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
const dig = (bot, status) => bot._client.write('block_dig', { status, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
const reloadKey = bot => dig(bot, 4)
const swapKey = bot => dig(bot, 6)

/**
 * Empties the shooter's hotbar (the starter kit fills it; the knife and terminal slots stay) and hands
 * it the two guns with spare rounds: the scoped DM14 (7.62x51 M80) and the M4C (5.56 M855), both pen 3.
 */
async function equip () {
  for (const slot of [0, 1, 3, 4, 5, 6, 7]) await rcon(`item replace entity ${SHOOTER} hotbar.${slot} with minecraft:air`)
  for (const give of ['dm14', 'ammo_308_m80 40', 'm4c', 'ammo_556_m855 60', 'ammo_556_m855 60']) {
    await rcon(`cv give ${SHOOTER} ${give}`)
  }
  await sleep(800)
}

/** Has `bot` hold the gun and fill its magazine. */
async function arm (id, bot = shooter) {
  const slot = hotbar(bot, id)
  if (slot < 0) {
    const held = bot.inventory.slots.map((s, i) => s ? `${i}:${itemId(s) || s.name}x${s.count}` : null).filter(Boolean)
    throw new Error(`${id} is not in the hotbar: ${held.join(' ')}`)
  }
  bot.setQuickBarSlot(slot - 36)
  await sleep(1200)
  reloadKey(bot)
  await sleep(4500)
}

/** Holds right mouse: vanilla repeats the use packet every 4 ticks while it is down. */
function aimWhile () {
  shooter.activateItem()
  const timer = setInterval(() => shooter.activateItem(), 150)
  return () => { clearInterval(timer); shooter.deactivateItem() }
}

/** Turns `bot`'s view to `p` and tells the server at once (before the trigger that follows). */
function lookAt (p, bot = shooter) {
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

/**
 * One trigger pull: a swing is a left click (a single-shot gun fires one round). A vanilla client with
 * a block under the crosshair within reach digs it instead, and the server turns a lone swing at a block
 * that close into a click on it, which the trigger takes for a stray swing (after Q or a door): so the
 * shooter always stands more than 4.5 blocks from the block it aims through.
 */
const pull = () => shooter.swingArm()

const tp = (who, x, y, z, yaw = 0, pitch = 0) =>
  rcon(`execute as ${who} at ${SHOOTER} run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)
const targetEntity = () => Object.values(shooter.entities).find(e => e.type === 'player' && e.username === TARGET)
const near = (name, x, y, z, r) => Object.values(shooter.entities)
  .filter(e => e.name === name && e.position.distanceTo({ x, y, z }) <= r)
  .sort((a, b) => a.position.distanceTo({ x, y, z }) - b.position.distanceTo({ x, y, z }))[0]

/**
 * Kills every soldier and drone in the raid, so nobody else shoots or walks into the line of fire;
 * with `keep`, first tags the tower sniper and the patrol soldier nearest the west patrol post, and
 * spares those two.
 */
async function clearSoldiers ({ keep = false } = {}) {
  if (keep) {
    await rcon(`execute at ${SHOOTER} run tag @e[type=minecraft:husk,x=47.5,y=73,z=8.5,distance=..3,limit=1] add hrsniper`)
    await rcon(`execute at ${SHOOTER} run tag @e[type=minecraft:zombie,x=24,y=65,z=18,distance=..16,sort=nearest,limit=1] add hrpatrol`)
  }
  const spare = keep ? ',tag=!hrsniper,tag=!hrpatrol' : ''
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) {
    await rcon(`execute at ${SHOOTER} run kill @e[type=minecraft:${type},distance=..300${spare}]`)
  }
}

/** Keeps `who` alive however often it is hit: soaks 80% and heals it back every second. */
function keepAlive (who = TARGET) {
  const tick = () => {
    rcon(`effect give ${who} resistance 30 3 true`).catch(() => {})
    rcon(`effect give ${who} instant_health 1 3 true`).catch(() => {})
  }
  tick()
  const timer = setInterval(tick, 1000)
  return () => clearInterval(timer)
}

/** Waits until soldiers may engage the bots: 3 s of insertion protection and 12 s of ai.insertion-grace-ticks. */
async function waitGrace () {
  const left = raidStarted + 17000 - Date.now()
  if (left > 0) {
    log(`waiting ${(left / 1000).toFixed(1)} s for the soldiers' insertion grace`)
    await sleep(left)
  }
}

/**
 * Where a vanilla client draws entity `id` for `bot`: each position update, and each rotation-only one
 * (which restarts the easing in vanilla too), is eased into over 3 client ticks, and a frame draws
 * between the last two ticks. mineflayer itself jumps to every update the moment it arrives.
 */
function vanillaView (bot, id) {
  const entity = () => bot.entities[id]
  const start = entity().position.clone()
  const v = { prev: start.clone(), pos: start.clone(), target: start.clone(), steps: 0, tickAt: Date.now(), updatedAt: Date.now() }
  const names = ['rel_entity_move', 'entity_move_look', 'entity_look', 'entity_teleport', 'sync_entity_position']
  // Registered after mineflayer's own listeners, so the entity's position is already the new one.
  const onPacket = packet => {
    if (packet.entityId !== id || !entity()) return
    const p = entity().position.clone()
    if (!p.equals(v.target)) v.updatedAt = Date.now()
    if (p.distanceTo(v.pos) > 8) {
      v.prev = p.clone()
      v.pos = p.clone()
      v.target = p
      v.steps = 0
      return
    }
    v.target = p
    v.steps = 3
  }
  for (const n of names) bot._client.on(n, onPacket)
  const timer = setInterval(() => {
    v.prev = v.pos.clone()
    if (v.steps > 0) {
      v.pos = v.pos.plus(v.target.minus(v.pos).scaled(1 / v.steps))
      v.steps--
    }
    v.tickAt = Date.now()
  }, 50)
  return {
    at () {
      const f = Math.max(0, Math.min(1, (Date.now() - v.tickAt) / 50))
      return v.prev.plus(v.pos.minus(v.prev).scaled(f))
    },
    /**
     * How far behind the server the drawn position is, in ms, for an entity moving at `velocity`: the
     * drawn position's lag behind the last update received, plus that update's age (the server has
     * moved it on since). With no network delay that is the rewind a shot at it needs.
     */
    lag (velocity) {
      const speed = Math.hypot(velocity.x, velocity.z)
      if (speed < 1 || !entity()) return null
      const d = entity().position.minus(this.at())
      return ((d.x * velocity.x + d.z * velocity.z) / speed) / speed * 1000 + (Date.now() - v.updatedAt)
    },
    stop () {
      clearInterval(timer)
      for (const n of names) bot._client.removeListener(n, onPacket)
    }
  }
}

/** The middle value of `list`, rounded (or '-' if empty). */
function median (list) {
  if (!list.length) return '-'
  const sorted = [...list].sort((a, b) => a - b)
  return Math.round(sorted[Math.floor(sorted.length / 2)])
}

/** Speed (blocks a second) of entity `id` along x and z over the last `ms`, from its raw updates. */
function tracker (bot, id) {
  const seen = []
  const timer = setInterval(() => {
    const e = bot.entities[id]
    if (e) seen.push({ at: Date.now(), p: e.position.clone() })
    while (seen.length > 100) seen.shift()
  }, 20)
  return {
    velocity (ms = 300) {
      const now = Date.now()
      const old = seen.find(s => s.at >= now - ms)
      const last = seen[seen.length - 1]
      if (!old || !last || last.at - old.at < ms / 2) return { x: 0, z: 0 }
      const dt = (last.at - old.at) / 1000
      return { x: (last.p.x - old.p.x) / dt, z: (last.p.z - old.p.z) / dt }
    },
    stop () { clearInterval(timer) }
  }
}

/**
 * Switches the held M4C to single shots (AUTO -> BURST -> SEMI): the action bar names the mode after
 * each press. It shows one result line per 1.5 s (util/Notify), so a press waits for its own line
 * before the next: pressed sooner, the next one could skip past SEMI before its line came.
 */
async function semi (bot = shooter) {
  for (let i = 0; i < 3; i++) {
    const n = bot.bars.length
    swapKey(bot)
    const end = Date.now() + 2500
    while (Date.now() < end && !bot.bars.slice(n).some(b => b.includes('射击模式'))) await sleep(100)
    if (bot.bars.slice(n).some(b => b.includes('单发'))) return
  }
  throw new Error('the M4C did not switch to single shots')
}

// `cv raids` lists one raid a line, "R6 试验场 常规 RUNNING · ..." (once with a [Craftov] prefix).
const raidIds = async () => (await rcon('cv raids')).split('\n').map(l => (l.trim().match(/^(?:\[Craftov\]\s+)?(R\d+)\s/) || [])[1]).filter(Boolean)
let ourRaids = []

async function intoRaid () {
  const before = await raidIds()
  shooter = await make(SHOOTER)
  target = await make(TARGET)
  await sleep(2500)
  shooter.chat('/cv queue proving NORMAL SOLO')
  target.chat('/cv queue proving NORMAL SOLO')
  const started = bot => bot.titles.some(t => t.includes('行动开始'))
  const until = Date.now() + 150000
  while (Date.now() < until && !(started(shooter) && started(target))) await sleep(500)
  if (!started(shooter) || !started(target)) throw new Error('the bots did not both get into a raid')
  raidStarted = Date.now()
  const dimension = async who => ((await rcon(`data get entity ${who} Dimension`)).match(/"([^"]+)"/) || [])[1]
  const a = await dimension(SHOOTER)
  const b = await dimension(TARGET)
  log('in raid', a, b)
  if (!a || a !== b) throw new Error(`the bots are in different worlds: ${a} / ${b}`)
  ourRaids = (await raidIds()).filter(id => !before.includes(id))
  // Past the insertion protection (3 s), and no armour on the target to blur the damage.
  await sleep(3500)
  await rcon(`item replace entity ${TARGET} armor.head with minecraft:air`)
  await rcon(`item replace entity ${TARGET} armor.chest with minecraft:air`)
  await equip()
  await clearSoldiers({ keep: scenarios.some(s => SOLDIER_SCENARIOS.includes(s)) })
}

// ---------------------------------------------------------------- sniper
const sniperEntity = () => near('husk', 47.5, 73, 8.5, 3)

async function sniper () {
  await arm('dm14')
  // Sandbags may lie on the line of fire; the tower is at 47 8, its platform at y 72 and the railing
  // one block up, the sniper inside it.
  await rcon(`execute at ${SHOOTER} run fill 50 65 7 90 68 9 minecraft:air`)
  await tp(SHOOTER, 77.5, 65, 8.5, 90, 0)
  await sleep(2500)
  const s = sniperEntity()
  if (!s) throw new Error('no sniper on the tower (a NORMAL raid has one 80% of the time; ai.count-multiplier: 1.25 makes it certain)')
  log('sniper at', s.position)
  await rcon(`execute at ${SHOOTER} as ${SNIPER} run attribute @s minecraft:max_health base set 1024`)
  await rcon(`execute at ${SHOOTER} as ${SNIPER} run data merge entity @s {Health:1024f}`)
  await rcon(`execute at ${SHOOTER} as ${SNIPER} at @s run tp @s ~ ~ ~ facing entity ${SHOOTER} eyes`)
  await waitGrace()
  const keep = keepAlive(SHOOTER)
  try {
    const from = logSize()
    let spotted = []
    const until = Date.now() + 10000
    while (Date.now() < until && !spotted.length) {
      await sleep(500)
      spotted = aiShots(from, 'sniper', SHOOTER)
    }
    check('sniper: the tower sniper spots a player on the ground 30 blocks out over its railing and fires', spotted.length > 0,
      spotted.length ? `first shot ${((Date.now() - (until - 10000)) / 1000).toFixed(1)} s in, aim ${spotted[0].aim}` : 'no shot in 10 s')
    const stopAim = aimWhile()
    await sleep(800)
    const shots = logSize()
    for (let i = 0; i < 4; i++) {
      const t = sniperEntity()
      if (!t) throw new Error('lost sight of the sniper')
      // Husk eyes 1.74 up: the head zone runs from 1.42 to 1.95 above its feet.
      lookAt(t.position.offset(0, 1.65, 0))
      await sleep(100)
      pull()
      await sleep(1300)
    }
    stopAim()
    await sleep(500)
    const rounds = impacts(shots)
    log('rounds at the sniper', rounds)
    const heads = rounds.filter(r => r.target !== '-' && r.target !== TARGET && r.part === 'head')
    check('sniper: rounds at the sniper\'s head over the railing hit its head', rounds.length >= 3 && heads.length >= rounds.length - 1,
      `${heads.length} of ${rounds.length} in the head`)
    // Long enough for a dozen shots: at 30 blocks about one in eight lands once its aim has settled.
    await sleep(12000)
    const back = aiShots(from, 'sniper', SHOOTER)
    check('sniper: it keeps firing back over the railing', back.length >= 3,
      `${back.length} shots, ${back.reduce((n, s) => n + s.hits, 0)} hits, aim ${[...new Set(back.map(s => s.aim))].join('/')}`)
  } finally {
    keep()
  }
}

// ---------------------------------------------------------------- parapet
async function parapet () {
  // The drill building (x 62-88, z 36-56) has its roof at y 74 and a ring of stone brick wall on it at
  // y 75; the target stands on the roof right behind the south wall, the sniper on the ground 41 blocks
  // south (between the armory's and the gallery's boards).
  if (!/Test passed/.test(await rcon(`execute at ${SHOOTER} if entity ${SNIPER}`))) throw new Error('no sniper to move')
  await tp(TARGET, 75.5, 75, 55.4, 0, 0)
  await rcon(`execute at ${SHOOTER} as ${SNIPER} run tp @s 75.5 65 96.5 180 0`)
  await sleep(500)
  await rcon(`execute at ${SHOOTER} as ${SNIPER} at @s run tp @s ~ ~ ~ facing entity ${TARGET} eyes`)
  await waitGrace()
  const from = logSize()
  let shots = []
  const until = Date.now() + 12000
  while (Date.now() < until && shots.length < 3) {
    await sleep(500)
    shots = aiShots(from, 'sniper', TARGET)
  }
  log('sniper shots at the roof', shots)
  check('parapet: a soldier on the ground sees a player behind the drill roof\'s parapet and fires', shots.length > 0, `${shots.length} shots`)
  check('parapet: it aims at the head showing over the wall, not at the hidden middle', shots.length > 0 && shots.every(s => s.aim >= 1.3),
    shots.map(s => s.aim).join(', '))
}

// ---------------------------------------------------------------- soldierlag
// Soldiers that notice a player stop where they are and fight, so this scenario has the patrol walk on its
// own instead: it walks to where the target's shot was heard and keeps the path it has when it is hit.
// For that it must never notice the bots and not answer the hits, which it would: the insertion grace
// ends with a player's first shot, and a soldier shot from out of sight takes cover and fires back at the
// spot. So for the scenario ai.awareness.rate goes below 0 (what a soldier sees drains its awareness
// instead of raising it, the +0.5 of a hit included) and ai.threat.seconds to 0 (no answering a hit).
async function soldierlag () {
  await rcon(`cv give ${SHOOTER} ammo_556_m855 60`)
  await arm('m4c')
  await semi()
  if (hotbar(target, 'm4c') < 0) {
    for (const slot of [0, 1, 3, 4, 5, 6, 7]) await rcon(`item replace entity ${TARGET} hotbar.${slot} with minecraft:air`)
    for (const give of ['m4c', 'ammo_556_m855 60']) await rcon(`cv give ${TARGET} ${give}`)
    await sleep(800)
  }
  await arm('m4c', target)
  const W = { x: 26.5, z: 15.5 }
  const E = { x: 50.5, z: 15.5 }
  const spot = { x: 38.5, z: 27.5 }
  // Sandbags and concrete off the walk and the shooter's view of it (the airdrop at 47 20 and the crate
  // at 30 28 stay).
  await rcon(`execute at ${SHOOTER} run fill 22 65 12 54 68 19 minecraft:air`)
  await rcon(`execute at ${SHOOTER} run fill 31 65 19 46 68 27 minecraft:air`)
  await setAi({ 'awareness.rate': -50, 'threat.seconds': 0 })
  await tp(SHOOTER, spot.x, 65, spot.z, 180, 0)
  await tp(TARGET, W.x, 65, W.z, -90, 0)
  // A fresh patrol at the east end. The map's own has been fighting the shooter since sniper and
  // parapet, and a soldier keeps a target it still sees whatever ai.awareness.rate is (that only
  // drains what it has not noticed yet): it stood and fired at the shooter, stepping aside now and
  // then, and never took the walk.
  await rcon(`kill ${PATROL}`)
  const posted = await rcon(`cv ai spawn patrol ${E.x} 65 ${E.z} ${ourRaids[0]}`)
  await rcon(`execute at ${SHOOTER} run tag @e[type=minecraft:zombie,x=${E.x},y=65,z=${E.z},distance=..2,sort=nearest,limit=1] add hrpatrol`)
  const there = await rcon(`execute at ${SHOOTER} if entity ${PATROL}`)
  if (!/Test passed/.test(there)) throw new Error('no patrol soldier could be posted: ' + posted.trim())
  await rcon(`execute at ${SHOOTER} as ${PATROL} run tp @s ${E.x} 65 ${E.z} 90 0`)
  await sleep(2500)
  // It may have walked on a little from where it was put (a path it already had): look for it where the
  // server has it.
  const at = (await rcon(`execute at ${SHOOTER} as ${PATROL} run data get entity @s Pos`)).match(/\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\]/)
  const patrol = at && near('zombie', Number(at[1]), Number(at[2]), Number(at[3]), 2)
  if (!patrol) {
    const seen = Object.values(shooter.entities).filter(e => e.type !== 'player' && e.type !== 'object' && e.type !== 'orb')
      .map(e => `${e.name}@${e.position.x.toFixed(1)},${e.position.y.toFixed(1)},${e.position.z.toFixed(1)}`)
    const pos = await rcon(`execute at ${SHOOTER} as ${PATROL} run data get entity @s Pos`)
    throw new Error(`the shooter does not see the patrol at the east end: server says ${pos.trim()}; the shooter sees ${seen.join(' ') || 'nothing'}`)
  }
  await rcon(`execute at ${SHOOTER} as ${PATROL} run attribute @s minecraft:max_health base set 1024`)
  await rcon(`execute at ${SHOOTER} as ${PATROL} run data merge entity @s {Health:1024f}`)
  const view = vanillaView(shooter, patrol.id)
  const motion = tracker(shooter, patrol.id)
  const pass = async () => {
    // The target's shot draws the patrol west along the walk; the shooter fires while it walks across.
    // A soldier only takes up a new path once it has none, so it may still be on a wander it started:
    // wait for it to stand still and shoot again. The shooter aims down the sights meanwhile.
    const stopAim = aimWhile()
    let walking = false
    for (let attempt = 0; attempt < 5 && !walking; attempt++) {
      const still = Date.now() + 15000
      let since = Date.now()
      while (Date.now() < still && Date.now() - since < 1000) {
        const v = motion.velocity()
        if (Math.hypot(v.x, v.z) > 0.3) since = Date.now()
        await sleep(100)
      }
      // Into the air: a swing with a block within reach under the crosshair is a click on it, not a shot.
      lookAt(target.entity.position.offset(-10, 8, 0), target)
      await sleep(200)
      target.swingArm()
      await sleep(1500)
      walking = motion.velocity().x < -1.2
    }
    if (!walking) {
      stopAim()
      return { rounds: [], fired: 0, speed: 0, lags: [] }
    }
    const from = logSize()
    let fired = 0
    let moved = false
    const speeds = []
    const lags = []
    const until = Date.now() + 16000
    while (Date.now() < until) {
      const p = shooter.entities[patrol.id]
      if (!p) break
      const v = motion.velocity()
      const speed = Math.hypot(v.x, v.z)
      if (Math.abs(v.x) > 1.2 && Math.abs(v.z) < 0.6 * Math.abs(v.x) && p.position.x > W.x + 1) {
        moved = true
        speeds.push(speed)
        const behind = view.lag(v)
        if (behind !== null) lags.push(behind)
        lookAt(view.at().offset(0, 1.0, 0))
        pull()
        fired++
        await sleep(350)
      } else {
        if (moved && (speed < 0.3 || p.position.x <= W.x + 1)) break
        await sleep(50)
      }
    }
    stopAim()
    await sleep(600)
    const rounds = impacts(from)
    const mean = speeds.length ? speeds.reduce((a, b) => a + b, 0) / speeds.length : 0
    return { rounds, fired, speed: mean, lags }
  }
  const series = async label => {
    const all = []
    const speeds = []
    const lags = []
    for (let i = 0; i < 3 && all.length < 20; i++) {
      // Hits send it after the shooter once the walk is over: wait that out (10 s after the last hit)
      // and put it back at the east end.
      if (i > 0 || label !== 'compensated') {
        reloadKey(shooter)
        await sleep(11000)
        await rcon(`execute at ${SHOOTER} as ${PATROL} run tp @s ${E.x} 65 ${E.z} 90 0`)
        await sleep(2500)
      }
      const r = await pass()
      log(`soldierlag ${label} pass ${i + 1}: ${r.fired} fired, ${r.rounds.length} logged, ${r.rounds.filter(x => x.target === 'Zombie').length} hits, ${r.speed.toFixed(2)} blocks/s, drawn ${median(r.lags)} ms behind`)
      all.push(...r.rounds)
      lags.push(...r.lags)
      if (r.speed) speeds.push(r.speed)
    }
    const hits = all.filter(r => r.target === 'Zombie').length
    return { rounds: all.length, hits, rewinds: [...new Set(all.map(r => r.rewind))], speed: speeds.length ? speeds.reduce((a, b) => a + b, 0) / speeds.length : 0, lag: median(lags) }
  }
  const keep = keepAlive(SHOOTER)
  try {
    await rcon('cv lagcomp on')
    const on = await series('compensated')
    await rcon('cv lagcomp off')
    const off = await series('not compensated')
    await rcon('cv lagcomp on')
    const rate = r => r.rounds ? r.hits / r.rounds : 0
    log('soldierlag', { on, off })
    check('soldierlag: at least 15 rounds at the walking soldier in each series', on.rounds >= 15 && off.rounds >= 15, `${on.rounds} / ${off.rounds}`)
    check('soldierlag: aiming where a vanilla client draws a walking soldier hits it with compensation on',
      rate(on) >= 0.75, `${(rate(on) * 100).toFixed(0)}% at ${on.speed.toFixed(2)} blocks/s, rewind ${on.rewinds.join('/')} ms, drawn ${on.lag} ms behind`)
    check('soldierlag: and clearly more often than without', rate(on) >= rate(off) + 0.25,
      `${(rate(on) * 100).toFixed(0)}% vs ${(rate(off) * 100).toFixed(0)}% at ${off.speed.toFixed(2)} blocks/s`)
  } finally {
    keep()
    view.stop()
    motion.stop()
    await setAi(null)
  }
}

// ---------------------------------------------------------------- fence
async function fence () {
  await arm('dm14')
  await clearSoldiers()
  // Sandbags may lie on the line of fire; the sniper tower is at 47 8, its railing one block up from
  // the platform at y 72, the east post at x 49.
  await rcon(`execute at ${SHOOTER} run fill 50 65 7 90 68 9 minecraft:air`)
  await tp(TARGET, 48.5, 73, 8.5, 90, 0)
  await tp(SHOOTER, 88.5, 65, 8.5, 90, 0)
  await sleep(2000)
  const stopAim = aimWhile()
  await sleep(800)
  const from = logSize()
  for (let i = 0; i < 4; i++) {
    const t = targetEntity()
    if (!t) throw new Error('the shooter cannot see the target on the tower')
    // The middle of the head zone: from 1.30 (eyes 1.62 less 0.32) to 1.80 above the feet.
    const head = t.position.offset(0, 1.55, 0)
    lookAt(head)
    await sleep(100)
    pull()
    await sleep(1100)
  }
  stopAim()
  await sleep(500)
  const rounds = impacts(from)
  const heads = rounds.filter(r => r.target === TARGET && r.part === 'head')
  log('fence rounds', rounds)
  check('fence: from 40 blocks, a head over the railing of the sniper tower is hit', rounds.length >= 3 && heads.length >= rounds.length - 1,
    `${heads.length} of ${rounds.length} rounds hit the head`)
  // 0.9 above the target's feet: the line meets the post 0.3 below the top of its model.
  const low = logSize()
  const stopLow = aimWhile()
  await sleep(800)
  for (let i = 0; i < 2; i++) {
    lookAt(targetEntity().position.offset(0, 0.9, 0))
    await sleep(100)
    pull()
    await sleep(1100)
  }
  stopLow()
  await sleep(500)
  const railing = impacts(low)
  log('railing rounds', railing)
  check('fence: a round into the railing itself still stops on it', railing.length === 2 && railing.every(r => r.target === '-' && r.through === 0),
    railing.map(r => `${r.target} at ${r.hit}`).join(', '))
}

// ---------------------------------------------------------------- glass
async function glass () {
  await arm('dm14')
  await clearSoldiers()
  // West wall of the glass house: panes at x 41 from y 65 to 67, the house runs x 41-56, z 38-52;
  // the third key room's wall is at x 35.
  await tp(TARGET, 45.5, 65, 45.5, 90, 0)
  await tp(SHOOTER, 36.3, 65, 45.5, -90, 0)
  await sleep(2000)
  const stopAim = aimWhile()
  await sleep(800)
  let from = logSize()
  lookAt(targetEntity().position.offset(0, 1.15, 0))
  await sleep(100)
  pull()
  await sleep(1200)
  const pane = impacts(from)
  log('glass pane rounds', pane)
  check('glass: the first round through a glass pane hits', pane.length === 1 && pane[0].target === TARGET && pane[0].through === 1 &&
    Math.abs(pane[0].keep - 0.9) < 0.01 && pane[0].damage > 0, JSON.stringify(pane[0] || null))
  // South wall: tinted glass (full blocks) at z 52, the doorway at x 48-49 kept clear of the line; the
  // container gallery's boards stand at z 60.
  await tp(TARGET, 45.5, 65, 49.5, 0, 0)
  await tp(SHOOTER, 45.5, 65, 58.5, 180, 0)
  await sleep(2000)
  from = logSize()
  for (let i = 0; i < 3; i++) {
    lookAt(targetEntity().position.offset(0, 1.15, 0))
    await sleep(100)
    pull()
    await sleep(900)
  }
  stopAim()
  await sleep(500)
  const tinted = impacts(from)
  log('tinted glass rounds', tinted)
  check('glass: tinted glass stops every round', tinted.length === 3 && tinted.every(r => r.target === '-' && r.through === 0),
    tinted.map(r => `${r.target}/${r.through}`).join(' '))
}

// ---------------------------------------------------------------- door
async function door () {
  await arm('m4c')
  await semi()
  await clearSoldiers()
  const shots = async () => {
    const stopAim = aimWhile()
    await sleep(800)
    const from = logSize()
    for (let i = 0; i < 3; i++) {
      lookAt(targetEntity().position.offset(0, 1.15, 0))
      await sleep(100)
      pull()
      await sleep(700)
    }
    stopAim()
    await sleep(500)
    return impacts(from)
  }
  // The spruce door in the drill building's south wall at 75 56, the target 3 blocks inside and the
  // shooter 6 blocks out: first through the open doorway, then through the closed door.
  const setDoor = async open => {
    for (const [y, half] of [[65, 'lower'], [66, 'upper']]) {
      await rcon(`execute at ${SHOOTER} run setblock 75 ${y} 56 minecraft:spruce_door[facing=north,half=${half},open=${open}]`)
    }
  }
  await tp(TARGET, 75.5, 65, 53.5, 0, 0)
  await tp(SHOOTER, 75.5, 65, 62.5, 180, 0)
  await setDoor(true)
  await sleep(2000)
  const open = await shots()
  await setDoor(false)
  await sleep(1000)
  const shut = await shots()
  log('door: open', open, 'through the door', shut)
  const openHits = open.filter(r => r.target === TARGET && r.through === 0)
  const doorHits = shut.filter(r => r.target === TARGET && r.through === 1)
  check('door: an M4C round through a wooden door hits', doorHits.length >= 2, `${doorHits.length} of ${shut.length}`)
  const pairs = doorHits.map(d => [d, openHits.find(o => o.part === d.part)]).filter(([, o]) => o)
  check('door: and does less damage than in the open, on the same body part', pairs.length >= 1 && pairs.every(([d, o]) => d.damage < o.damage && d.damage > 0.6 * o.damage),
    pairs.map(([d, o]) => `${d.part} ${d.damage} vs ${o.damage}`).join(', ') || 'no matching parts')
}

// ---------------------------------------------------------------- planks
async function planks () {
  await arm('m4c')
  await semi()
  await clearSoldiers()
  // Open ground between the container gallery (boards at z 60, boxes at z 64) and the armory (boards
  // at z 74: x 45-49 and 58-62). The wall runs along x at z 69, 3 high.
  const wall = (z, block) => rcon(`execute at ${SHOOTER} run fill 40 65 ${z} 60 67 ${z} minecraft:${block}`)
  const shots = async () => {
    const stopAim = aimWhile()
    await sleep(800)
    const from = logSize()
    for (let i = 0; i < 3; i++) {
      lookAt(targetEntity().position.offset(0, 1.15, 0))
      await sleep(100)
      pull()
      await sleep(700)
    }
    stopAim()
    await sleep(500)
    return impacts(from)
  }
  const through = rounds => rounds.filter(r => r.target === TARGET && r.through === 1 && Math.abs(r.keep - 0.6) < 0.01)
  await wall(69, 'oak_planks')
  // Square-on: the shooter straight south of the target, between the armory's boards.
  await tp(TARGET, 52.5, 65, 66.5, 0, 0)
  await tp(SHOOTER, 52.5, 65, 78.5, 180, 0)
  await sleep(2000)
  const square = await shots()
  // About 45 degrees: the line crosses the wall at x 48.9-49.9, entering one plank on its face and
  // leaving through its side into the next.
  await tp(TARGET, 46.5, 65, 66.5, 0, 0)
  await tp(SHOOTER, 56.2, 65, 76.5, 135, 0)
  await sleep(2000)
  const angled = await shots()
  log('one plank wall: square-on', square, '45 degrees', angled)
  check('planks: an M4C round goes through a 1-block plank wall square-on', through(square).length === square.length && square.length === 3,
    square.map(r => `${r.target}/${r.through}/${r.keep}`).join(' '))
  check('planks: and at 45 degrees, as one layer', through(angled).length === angled.length && angled.length === 3,
    angled.map(r => `${r.target}/${r.through}/${r.keep}`).join(' '))
  // A second wall right behind the first: two layers.
  await wall(68, 'oak_planks')
  await tp(TARGET, 52.5, 65, 65.5, 0, 0)
  await tp(SHOOTER, 52.5, 65, 78.5, 180, 0)
  await sleep(2000)
  const square2 = await shots()
  await tp(TARGET, 45.5, 65, 65.5, 0, 0)
  await tp(SHOOTER, 56.2, 65, 76.5, 135, 0)
  await sleep(2000)
  const angled2 = await shots()
  log('two plank walls: square-on', square2, '45 degrees', angled2)
  check('planks: two walls back to back stop the round square-on', square2.length === 3 && square2.every(r => r.target === '-'),
    square2.map(r => `${r.target}/${r.through}`).join(' '))
  check('planks: and at 45 degrees', angled2.length === 3 && angled2.every(r => r.target === '-'),
    angled2.map(r => `${r.target}/${r.through}`).join(' '))
  await wall(68, 'air')
  await wall(69, 'air')
}

// ---------------------------------------------------------------- crouch
async function crouch () {
  await arm('m4c')
  await semi()
  await clearSoldiers()
  await rcon('cv lagcomp on')
  // Open ground between the gallery and the armory, the target 8 blocks north facing the shooter.
  await tp(TARGET, 28.5, 65, 70.5, 0, 0)
  await tp(SHOOTER, 28.5, 65, 78.5, 180, 0)
  await sleep(2000)
  const stopAim = aimWhile()
  await sleep(800)
  const shot = async (height, sneakBefore, sneakThen) => {
    target.setControlState('sneak', sneakBefore)
    await sleep(1500)
    const from = logSize()
    lookAt(targetEntity().position.offset(0, height, 0))
    target.setControlState('sneak', sneakThen)
    await sleep(100)
    pull()
    await sleep(700)
    return impacts(from)[0] || null
  }
  const crouched = []
  const stood = []
  for (let i = 0; i < 3; i++) {
    // 1.2 up a standing body (1.8 tall, head from 1.30) is chest; on a crouched one (1.5, head from
    // 0.95) the same share of the height is head.
    crouched.push(await shot(1.2, false, true))
    // 1.0 up a crouched body is head; the same share of a standing one is chest.
    stood.push(await shot(1.0, true, false))
  }
  target.setControlState('sneak', false)
  stopAim()
  log('crouched just before', crouched, 'stood up just before', stood)
  // The torso, that is: whether it is the chest or an arm depends on how the server has the body turned.
  const torso = ['chest', 'stomach', 'left_arm', 'right_arm']
  check('crouch: a chest-high hit on someone who crouched 100 ms before still counts as the torso',
    crouched.every(r => r && r.target === TARGET && torso.includes(r.part)), crouched.map(r => r ? `${r.part}@${r.rewind}ms` : 'none').join(' '))
  check('crouch: a head-high hit on someone crouched who stood up 100 ms before still counts as the head',
    stood.every(r => r && r.target === TARGET && r.part === 'head'), stood.map(r => r ? `${r.part}@${r.rewind}ms` : 'none').join(' '))
}

// ---------------------------------------------------------------- lag
async function lag () {
  // 112 rounds or more: top up what the earlier scenarios used.
  for (let i = 0; i < 2; i++) await rcon(`cv give ${SHOOTER} ammo_556_m855 60`)
  await arm('m4c')
  await semi()
  await clearSoldiers()
  // Open ground between the container gallery and the armory, seen through the gap between the
  // armory's boards (x 19-23 and 32-36 at z 74).
  const run = { x0: 23.5, x1: 33.5, z: 70.5 }
  const spot = { x: 28, y: 65, z: 78.5 }
  await tp(SHOOTER, spot.x, spot.y, spot.z, 180, 0)
  await tp(TARGET, (run.x0 + run.x1) / 2, 65, run.z, 0, 0)
  await sleep(1500)
  const t = targetEntity()
  if (!t) throw new Error('the shooter does not see the target')
  const view = vanillaView(shooter, t.id)
  const motion = tracker(shooter, t.id)
  let mode = 'walk'
  let strafing = true
  const strafe = (async () => {
    let leg = 0
    while (strafing) {
      const east = leg++ % 2 === 0
      if (mode === 'walk') {
        // Facing the shooter: right is west, left is east.
        const dir = east ? 'left' : 'right'
        target.setControlState(dir, true)
        await sleep(1100)
        target.setControlState(dir, false)
      } else {
        // Sprinting only goes forward: face along the run and sprint.
        await target.lookAt(target.entity.position.offset(east ? 10 : -10, 1.62, 0), true)
        target.setControlState('sprint', true)
        target.setControlState('forward', true)
        await sleep(900)
        target.setControlState('forward', false)
        target.setControlState('sprint', false)
      }
    }
  })()
  const series = async (label, count) => {
    await tp(TARGET, (run.x0 + run.x1) / 2, 65, run.z, 0, 0)
    reloadKey(shooter)
    await sleep(3500)
    const stopAim = aimWhile()
    await sleep(800)
    const from = logSize()
    let fired = 0
    const lags = []
    while (fired < count) {
      // Back to the middle if the strafing drifted off the run.
      const now = targetEntity()
      if (now && (now.position.x < run.x0 - 1 || now.position.x > run.x1 + 1 || Math.abs(now.position.z - run.z) > 1.5)) {
        await tp(TARGET, (run.x0 + run.x1) / 2, 65, run.z, 0, 0)
        await sleep(600)
        continue
      }
      const behind = view.lag(motion.velocity(200))
      if (behind !== null) lags.push(behind)
      lookAt(view.at().offset(0, 1.1, 0))
      pull()
      fired++
      await sleep(350)
    }
    stopAim()
    await sleep(600)
    const rounds = impacts(from)
    const hits = rounds.filter(r => r.target === TARGET).length
    const rewinds = [...new Set(rounds.map(r => r.rewind))]
    log(`lag ${label}: ${hits} hits of ${rounds.length} rounds, rewind ${rewinds.join('/')} ms, drawn ${median(lags)} ms behind (${lags.length} shots on the move)`)
    return { rounds: rounds.length, hits, rewinds }
  }
  const keep = keepAlive()
  let on, off, sprintOn, sprintOff
  try {
    await rcon('cv lagcomp on')
    on = await series('walking, compensated', 32)
    await rcon('cv lagcomp off')
    off = await series('walking, not compensated', 32)
    mode = 'sprint'
    await rcon('cv lagcomp on')
    sprintOn = await series('sprinting, compensated', 24)
    await rcon('cv lagcomp off')
    sprintOff = await series('sprinting, not compensated', 24)
  } finally {
    await rcon('cv lagcomp on')
    keep()
    strafing = false
    await strafe
    view.stop()
    motion.stop()
  }
  const rate = r => r.rounds ? r.hits / r.rounds : 0
  const pct = r => `${(rate(r) * 100).toFixed(0)}%`
  check('lag: at least 25 rounds logged in each walking series', on.rounds >= 25 && off.rounds >= 25, `${on.rounds} / ${off.rounds}`)
  check('lag: compensated rounds rewind ping + extra-ms', on.rewinds.length > 0 && on.rewinds.every(ms => ms >= 150 && ms <= 300),
    on.rewinds.join(', '))
  check('lag: uncompensated rounds do not rewind', off.rewinds.length === 1 && off.rewinds[0] === 0, off.rewinds.join(', '))
  check('lag: aiming where a vanilla client draws a walking player hits it with compensation on', rate(on) >= 0.75, pct(on))
  check('lag: and clearly more often than without', rate(on) >= rate(off) + 0.25, `${pct(on)} vs ${pct(off)}`)
  // Sprinting legs are short and turn at each end, so this rate swings more from run to run.
  check('lag: a sprinting player too', rate(sprintOn) >= 0.5 && rate(sprintOn) >= rate(sprintOff) + 0.3, `${pct(sprintOn)} vs ${pct(sprintOff)}`)
}

// ---------------------------------------------------------------- main
;(async () => {
  await intoRaid()
  const keep = keepAlive()
  // The shooter too: a stray soldier, operator or event of the raid could otherwise end the run
  // half way (its empty inventory then fails every scenario after).
  const keepShooter = keepAlive(SHOOTER)
  for (const scenario of scenarios) {
    log('---', scenario)
    try {
      if (scenario === 'sniper') await sniper()
      else if (scenario === 'parapet') await parapet()
      else if (scenario === 'soldierlag') await soldierlag()
      else if (scenario === 'fence') await fence()
      else if (scenario === 'glass') await glass()
      else if (scenario === 'door') await door()
      else if (scenario === 'planks') await planks()
      else if (scenario === 'crouch') await crouch()
      else if (scenario === 'lag') await lag()
      else log('unknown scenario', scenario)
    } catch (e) {
      check(`${scenario}: ran`, false, e.message)
    }
  }
  keep()
  keepShooter()
  await setAi(null).catch(e => log('could not put config.yml back:', e.message))
  for (const id of ourRaids) await rcon('cv end ' + id).catch(() => {})
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  shooter.quit()
  target.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(async e => {
  log('ERROR', e.stack || e.message, failures)
  await setAi(null).catch(() => {})
  if (shooter) shooter.quit()
  if (target) target.quit()
  process.exit(2 + failures.length)
})
