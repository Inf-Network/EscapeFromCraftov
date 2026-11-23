// Trigger capture, burst and full-auto rhythm (issue #33), on the private range.
//
// The bot plays a vanilla client holding left mouse: each tick it picks what the crosshair lands on
// first, a capture block the server sent (a sculk vein on all six faces, whose outline is a shell of
// six 1/16-block plates) or an entity, as vanilla's GameRenderer.pick does, and then digs
// (start, a swing per tick, abort when it moves to another cell or lets go) or attacks once and goes
// quiet, as Minecraft.continueAttack does.
//
//   cadence  offline, no server: the tick loop at the items.yml rates, with the multi-round report
//            table that gunsamples.py and GunAudio.java must agree on. Every full-auto gun has one
//            interval between reports, and every report holds the same number of rounds.
//   lag      DM14 (semi): start, a swing every tick, then 8 and then 30 ticks with neither swing nor
//            abort (a lag spike), swings again and the abort: one shot each time. A click after the
//            abort fires again.
//   burst    M4C on burst: start, one swing and the abort in the same tick: three shots per click.
//   close    K47 facing a range target 0.8 to 2.7 blocks away (centre to centre), the target moved
//            through five places in its block: from 1.3 blocks out the crosshair must land on a capture
//            block before the target wherever they stand (closer, only when the target's face is past
//            the cell the eye is in), and none is ever by the feet (the client would take its silenced
//            step sound instead of the floor's). Then, the target back in place, the trigger held at 1,
//            1.5, 2 and 2.5 blocks with the recoil pulled back down every tick: the magazine must run
//            dry, the target never picked first.
//   rhythm   M4C, SV9 and K47 held on full auto: the reports heard hold as many rounds as were fired,
//            M4C and SV9 one report every 150 and 200 ms, K47 one every 100 ms; a quick click on full
//            auto fires exactly the rounds of one report.
//   open     with a gun out, right-clicking the range's weapon rack 2.5 blocks away opens it although
//            the crosshair lands on a capture block, and does not aim the gun as well. With a range target moved
//            in front of the rack, the same right-click aims at the target and leaves the rack shut.
//   fall     a 12-block fall looking straight down with a gun out is a free fall (18 ticks; the cobweb
//            used before held it up), and no capture block is by the feet where it lands.
//   stray    looking at the floor with a K47 on full auto, Q on a full magazine followed by the arm
//            swing a vanilla client sends after it, and then a swing alone (as after opening a door or
//            pulling a lever): neither fires, although in adventure mode the server turns each swing
//            into a left-click on the floor. Then a DM14 gets the same, and a real click after it fires
//            once (a stray hold used to leave the next click swallowed).
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node trigger.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (shots are counted from the log).
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
//
// Server config (tools/bot/suite.js sets it for its run): debug true (shots are counted from the
// log).
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const fs = require('fs')
const path = require('path')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const LOG = env.LOG
const ROOT = path.join(__dirname, '../..')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['cadence', 'lag', 'burst', 'close', 'rhythm', 'open', 'fall', 'stray']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

// ---------------------------------------------------------------- cadence (offline)
function cadence () {
  const items = fs.readFileSync(path.join(ROOT, 'src/main/resources/items.yml'), 'utf8')
  const guns = {}
  let id = null
  for (const line of items.split('\n')) {
    const head = line.match(/^ {2}([a-z0-9_]+):/)
    if (head) id = head[1]
    const gun = line.match(/rpm: (\d+), modes: \[([A-Z, ]+)\]/)
    if (gun && id) guns[id] = { rpm: Number(gun[1]), modes: gun[2].split(',').map(s => s.trim()) }
  }
  const py = fs.readFileSync(path.join(ROOT, 'tools/art/gunsamples.py'), 'utf8').match(/^GROUPS = \{(.*)\}$/m)
  const java = fs.readFileSync(path.join(ROOT, 'src/main/java/net/infnetwork/snowball/craftov/weapon/GunAudio.java'), 'utf8')
  const fromPy = {}
  for (const m of (py ? py[1] : '').matchAll(/'([a-z0-9_]+)': \((\d+), (\d+)\)/g)) fromPy[m[1]] = [Number(m[2]), Number(m[3])]
  const fromJava = {}
  for (const m of java.matchAll(/"([a-z0-9_]+)", new Rounds\((\d+), (\d+)\)/g)) fromJava[m[1]] = [Number(m[2]), Number(m[3])]
  check('cadence: gunsamples.py GROUPS and GunAudio.SAMPLES agree', Object.keys(fromPy).length > 0 && JSON.stringify(fromPy) === JSON.stringify(
    Object.fromEntries(Object.keys(fromPy).map(k => [k, fromJava[k]]))) && Object.keys(fromJava).length === Object.keys(fromPy).length,
  `py ${JSON.stringify(fromPy)} java ${JSON.stringify(fromJava)}`)
  for (const [gid, [rounds, ticks]] of Object.entries(fromPy)) {
    check(`cadence: ${gid} sample matches its rate`, guns[gid] && guns[gid].rpm * ticks === rounds * 1200, `${guns[gid] && guns[gid].rpm} rpm vs ${rounds} rounds / ${ticks} ticks`)
  }
  for (const [gid, gun] of Object.entries(guns)) {
    if (!gun.modes.includes('AUTO')) continue
    const sample = fromPy[gid] && gun.rpm * fromPy[gid][1] === fromPy[gid][0] * 1200 ? fromPy[gid][0] : 1
    // WeaponService.tick: the first shot at once, then 50 x rpm banked per tick, 60000 per shot.
    const shotTicks = [0]
    let budget = 0
    for (let t = 1; t < 120; t++) {
      budget += 50 * gun.rpm
      for (let guard = 0; budget >= 60000 && guard < 4; guard++) { budget -= 60000; shotTicks.push(t) }
    }
    const reports = shotTicks.filter((_, i) => i % sample === 0)
    const gaps = new Set(reports.slice(1).map((t, i) => t - reports[i]))
    const shotGaps = new Set(shotTicks.slice(1).map((t, i) => t - shotTicks[i]))
    check(`cadence: ${gid} (${gun.rpm} rpm) one interval between reports`, gaps.size === 1,
      `reports every ${[...gaps].join('/')} ticks holding ${sample} round(s); shots ${[...shotGaps].join('/')} ticks apart`)
  }
}

if (scenarios.length === 1 && scenarios[0] === 'cadence') {
  cadence()
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  process.exit(failures.length)
}

// ---------------------------------------------------------------- the bot
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const name = 'Trg' + Math.floor(10000 + Math.random() * 90000)

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
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
// First-person reports the shooter hears: gun.<id>.fp, or gun.<id>.x<n>.fp holding n rounds.
const reports = []
/** When the bot heard its gun go to aim-down-sights (WeaponService.setAds plays gun.ads). */
const adsHeard = []
const onSound = p => {
  const sound = JSON.stringify(p.sound || p.soundId || '')
  const m = sound.match(/gun\.([a-z0-9_]+?)(?:\.x(\d))?\.fp\b(?!_)/)
  if (m) reports.push({ at: Date.now(), gun: m[1], rounds: m[2] ? Number(m[2]) : 1 })
  if (/gun\.ads\b/.test(sound)) adsHeard.push(Date.now())
}
bot._client.on('entity_sound_effect', onSound)
bot._client.on('sound_effect', onSound)

const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
const START = 0
const ABORT = 1
const dropKey = () => dig(4)
const swapKey = () => dig(6)
const swing = () => bot._client.write('arm_animation', { hand: 0 })
/**
 * One right-click as the vanilla client sends it: on a block the crosshair is on, a use on that block
 * (the capture block and a gun both pass), then a use of the item in hand.
 */
function rightClick (cell) {
  if (cell) {
    bot._client.write('block_place', { hand: 0, location: cell, direction: 2, cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, worldBorderHit: false, sequence: sequence++ })
  }
  bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
}

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
/** Server ticks of this bot's shots logged since `from` (the debug shot line). */
const shotsSince = from => logSince(from).filter(l => l.includes(`[debug] ${name} shot `)).map(l => Number((l.match(/ tick=(\d+)/) || [])[1]))

// ---------------------------------------------------------------- vanilla crosshair pick
/** Distance along the ray at which it enters the box, or null if it misses. */
function rayBox (o, d, min, max) {
  let tin = 0
  let tout = Infinity
  for (const a of ['x', 'y', 'z']) {
    if (Math.abs(d[a]) < 1e-9) {
      if (o[a] < min[a] || o[a] > max[a]) return null
      continue
    }
    let t1 = (min[a] - o[a]) / d[a]
    let t2 = (max[a] - o[a]) / d[a]
    if (t1 > t2) [t1, t2] = [t2, t1]
    tin = Math.max(tin, t1)
    tout = Math.min(tout, t2)
  }
  return tin <= tout ? tin : null
}
const eye = () => bot.entity.position.offset(0, 1.62, 0)
function look () {
  const yaw = conv.toNotchianYaw(bot.entity.yaw) * Math.PI / 180
  const pitch = conv.toNotchianPitch(bot.entity.pitch) * Math.PI / 180
  return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch))
}
const captureId = () => bot.registry.blocksByName.sculk_vein.id
const captures = () => bot.findBlocks({ matching: captureId(), maxDistance: 6, count: 64 })
/**
 * Where the ray meets a capture block's outline in cell c (six 1/16-block plates on its faces), as
 * vanilla's VoxelShape.clip finds it: a probe point a thousandth of the 4.5-block reach ahead that is
 * inside a plate is a hit right there; otherwise the nearest plate face the ray crosses strictly ahead
 * (AABB.clip). Null if it misses. So a ray starting inside the cell meets the plate it leaves by, and
 * one starting on that face itself meets nothing in this cell.
 */
function shell (o, d, c) {
  const P = 1 / 16
  const plates = [
    [c, c.offset(P, 1, 1)], [c.offset(1 - P, 0, 0), c.offset(1, 1, 1)],
    [c, c.offset(1, P, 1)], [c.offset(0, 1 - P, 0), c.offset(1, 1, 1)],
    [c, c.offset(1, 1, P)], [c.offset(0, 0, 1 - P), c.offset(1, 1, 1)]
  ]
  const probe = o.plus(d.scaled(0.001 * 4.5))
  if (plates.some(([min, max]) => ['x', 'y', 'z'].every(a => probe[a] >= min[a] && probe[a] < max[a]))) return 0.001 * 4.5
  let best = null
  for (const [min, max] of plates) {
    for (const a of ['x', 'y', 'z']) {
      if (Math.abs(d[a]) <= 1e-7) continue
      const t = ((d[a] > 0 ? min[a] : max[a]) - o[a]) / d[a]
      if (!(t > 0) || (best !== null && t >= best)) continue
      if (['x', 'y', 'z'].every(b => b === a || (o[b] + t * d[b] > min[b] - 1e-7 && o[b] + t * d[b] < max[b] + 1e-7))) best = t
    }
  }
  return best
}
/** Capture blocks by the bot's feet, where TriggerService.feet keeps them out. */
function byFeet () {
  const p = bot.entity.position
  return captures().filter(c => c.x < p.x + 0.6 && c.x + 1 > p.x - 0.6 && c.y < p.y + 1.0 && c.y + 1 > p.y - 0.2 &&
    c.z < p.z + 0.6 && c.z + 1 > p.z - 0.6).length
}
/** Hitbox of an entity as the client has it. */
function box (e) {
  const w = (e.width || 0.6) / 2
  return [new Vec3(e.position.x - w, e.position.y, e.position.z - w), new Vec3(e.position.x + w, e.position.y + (e.height || 1.95), e.position.z + w)]
}
/** What the crosshair lands on first within block reach: { kind: 'cell'|'entity'|'miss', cell, entity, t, tCell, tEntity }. */
function pick (entities = []) {
  const o = eye()
  const d = look()
  let cell = null
  let tCell = Infinity
  for (const c of captures()) {
    const t = shell(o, d, c)
    if (t !== null && t < tCell) { tCell = t; cell = c }
  }
  let entity = null
  let tEntity = Infinity
  for (const e of entities) {
    const [min, max] = box(e)
    const t = rayBox(o, d, min, max)
    if (t !== null && t < tEntity) { tEntity = t; entity = e }
  }
  if (cell && tCell < tEntity && tCell <= 4.5) return { kind: 'cell', cell, t: tCell, tCell, tEntity }
  if (entity && tEntity <= 4.5) return { kind: 'entity', entity, t: tEntity, tCell, tEntity }
  return { kind: 'miss', tCell, tEntity }
}

/**
 * Holds left mouse for `ms` the way the vanilla client does, picking every tick. `steady` turns the
 * view back to where it started every tick (perfect recoil control). Returns how many ticks the
 * crosshair picked an entity first while the button was down.
 */
async function hold (ms, entities = [], steady = false) {
  const yaw = bot.entity.yaw
  const pitch = bot.entity.pitch
  let digging = null
  let entityTicks = 0
  const press = pick(entities)
  if (press.kind === 'cell') { dig(START, press.cell); digging = press.cell } else if (press.kind === 'entity') { bot.attack(press.entity); entityTicks++ }
  swing()
  const until = Date.now() + ms
  while (Date.now() < until) {
    await sleep(50)
    if (steady) await bot.look(yaw, pitch, true)
    const now = pick(entities)
    if (now.kind === 'cell') {
      if (!digging || !digging.equals(now.cell)) {
        if (digging) dig(ABORT, digging)
        dig(START, now.cell)
        digging = now.cell
      }
      swing()
    } else {
      if (now.kind === 'entity') entityTicks++
      if (digging) dig(ABORT, digging)
      digging = null
    }
  }
  if (digging) dig(ABORT, digging)
  return entityTicks
}

// ---------------------------------------------------------------- setup
async function enterRange () {
  await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (rack) bot.activateEntity(rack)
  await sleep(2500)
  const dim = await rcon(`data get entity ${name} Dimension`)
  if (!dim.includes('craftov_range')) throw new Error('not on the range: ' + dim)
}

/** Gives the gun, holds it and fills its magazine. */
async function arm (id) {
  if (hotbar(id) < 0) await rcon(`cv give ${name} ${id}`)
  await sleep(800)
  const slot = hotbar(id)
  if (slot < 0) throw new Error(id + ' did not reach the hotbar')
  bot.setQuickBarSlot(slot - 36)
  await sleep(600)
  await reload()
}
async function reload () {
  dropKey()
  await sleep(6500)
}

async function tp (x, y, z, yaw, pitch) {
  bot.clearControlStates()
  await rcon(`execute as ${name} at @s run tp @s ${x.toFixed(3)} ${y} ${z.toFixed(3)} ${yaw} ${pitch}`)
  await sleep(450)
}
const home = spot => tp(spot.x, spot.y, spot.z, 180, 0)

/** The capture cell the crosshair lands on from where the bot stands (no entity close by). */
async function cell () {
  for (let i = 0; i < 20; i++) {
    const p = pick()
    if (p.kind === 'cell') return p.cell
    await sleep(100)
  }
  throw new Error('no capture cell in front of the crosshair')
}

// ---------------------------------------------------------------- lag
async function lag (spot) {
  await arm('dm14')
  await home(spot)
  const at = await cell()
  for (const pause of [8, 30]) {
    const from = logSize()
    dig(START, at)
    for (let i = 0; i < 10; i++) { swing(); await sleep(50) }
    await sleep(pause * 50) // nothing at all: the lag spike
    for (let i = 0; i < 10; i++) { swing(); await sleep(50) }
    dig(ABORT, at)
    await sleep(400)
    const shots = shotsSince(from).length
    check(`lag: DM14 held through a ${pause}-tick spike fires once`, shots === 1, `${shots} shots`)
  }
  const from = logSize()
  dig(START, at)
  swing()
  await sleep(100)
  dig(ABORT, at)
  await sleep(400)
  check('lag: a click after the abort fires again', shotsSince(from).length === 1, `${shotsSince(from).length} shots`)
}

// ---------------------------------------------------------------- burst
async function burst (spot) {
  await arm('m4c')
  swapKey() // AUTO -> BURST
  await sleep(400)
  await home(spot)
  const at = await cell()
  const counts = []
  const heard = []
  for (let i = 0; i < 3; i++) {
    const from = logSize()
    const reportsFrom = reports.length
    dig(START, at)
    swing()
    dig(ABORT, at)
    await sleep(700)
    counts.push(shotsSince(from).length)
    heard.push(reports.slice(reportsFrom).reduce((n, r) => n + r.rounds, 0))
  }
  check('burst: a click with the abort in the same tick fires three rounds', counts.every(n => n === 3), counts.join(','))
  check('burst: the reports heard hold three rounds', heard.every(n => n === 3), heard.join(','))
  swapKey() // BURST -> SEMI
  await sleep(300)
  swapKey() // SEMI -> AUTO
  await sleep(300)
}

// ---------------------------------------------------------------- close
async function close (spot) {
  const targets = Object.values(bot.entities).filter(e => e.name === 'zombie')
  if (!targets.length) throw new Error('no range target')
  const target = targets.reduce((a, b) => a.position.distanceTo(spot) < b.position.distanceTo(spot) ? a : b)
  const tp0 = target.position.clone()
  log('target at', tp0.toString())
  await arm('k47')
  const nearest = '@e[type=minecraft:zombie,sort=nearest,limit=1]'
  const serverPos = async () => {
    const m = (await rcon(`execute as ${name} at @s run data get entity ${nearest} Pos`)).match(/\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\]/)
    return m ? new Vec3(Number(m[1]), Number(m[2]), Number(m[3])) : null
  }
  const moveTarget = async (x, y, z) => {
    await rcon(`execute as ${name} at @s run tp ${nearest} ${x.toFixed(3)} ${y} ${z.toFixed(3)}`)
    await sleep(300)
    const there = await serverPos()
    log(`target moved to z ${z.toFixed(2)}: server ${there && there.z.toFixed(2)}, bot sees ${target.position.z.toFixed(2)}`)
  }
  const sweep = {}
  let feet = 0
  try {
    for (const shift of [0, 0.2, 0.4, 0.6, 0.8]) {
      const tz = tp0.z - shift
      await moveTarget(tp0.x, tp0.y, tz)
      for (let d = 0.8; d <= 2.71; d += 0.1) {
        const key = d.toFixed(1)
        sweep[key] = sweep[key] || { cell: 0, of: 0 }
        await tp(tp0.x, tp0.y, tz + d, 180, 0)
        const p = pick([target])
        sweep[key].of++
        if (p.kind === 'cell') sweep[key].cell++
        feet += byFeet()
      }
    }
  } finally {
    await moveTarget(tp0.x, tp0.y, tp0.z)
  }
  log('capture block before the target (target places out of 5):', Object.entries(sweep).map(([d, s]) => `${d}:${s.cell}`).join(' '))
  check('close: from 1.3 blocks out the crosshair always lands on a capture block first',
    Object.entries(sweep).filter(([d]) => Number(d) >= 1.3).every(([, s]) => s.cell === s.of))
  check('close: no capture block by the feet', feet === 0, `${feet} cells by the feet over the sweep`)
  for (const d of [1.0, 1.5, 2.0, 2.5]) {
    await reload()
    await tp(tp0.x, tp0.y, tp0.z + d, 180, 0)
    const first = pick([target])
    const from = logSize()
    const entityTicks = await hold(3600, [target], true)
    await sleep(300)
    const shots = shotsSince(from).length
    check(`close: K47 held ${d} blocks from the target empties the magazine`, shots === 30,
      `${shots} shots; first pick ${first.kind} (capture block at ${first.tCell.toFixed(2)}, target at ${first.tEntity.toFixed(2)}); target picked first on ${entityTicks} ticks`)
  }
}

// ---------------------------------------------------------------- rhythm
async function rhythm (spot) {
  for (const [gid, rounds, gap] of [['m4c', 2, 150], ['sv9', 3, 200], ['k47', 1, 100]]) {
    await arm(gid)
    await home(spot)
    const from = logSize()
    const reportsFrom = reports.length
    await hold(1500, [], true)
    await sleep(500)
    const ticks = shotsSince(from)
    const heard = reports.slice(reportsFrom).filter(r => r.gun === gid)
    const heardRounds = heard.reduce((n, r) => n + r.rounds, 0)
    const gaps = heard.slice(1).map((r, i) => r.at - heard[i].at)
    const mean = gaps.length ? gaps.reduce((a, b) => a + b, 0) / gaps.length : 0
    check(`rhythm: ${gid} reports hold the rounds fired`, ticks.length > 5 && heardRounds === ticks.length && heard.every(r => r.rounds === rounds || r === heard[heard.length - 1]),
      `${ticks.length} shots, ${heard.length} reports holding ${heardRounds} rounds (${heard.map(r => r.rounds).join('')})`)
    check(`rhythm: ${gid} one report every ${gap} ms`, Math.abs(mean - gap) <= 20 && gaps.every(g => Math.abs(g - gap) <= 60),
      `mean ${mean.toFixed(0)} ms, gaps ${gaps.join(',')}; shot ticks ${ticks.slice(1).map((t, i) => t - ticks[i]).join(',')}`)
    if (rounds > 1) {
      await reload()
      const at = await cell()
      const tapFrom = logSize()
      const tapReports = reports.length
      dig(START, at)
      swing()
      dig(ABORT, at)
      await sleep(600)
      const tapShots = shotsSince(tapFrom).length
      const tapHeard = reports.slice(tapReports).reduce((n, r) => n + r.rounds, 0)
      check(`rhythm: a quick click on full auto ${gid} fires the ${rounds} rounds heard`, tapShots === rounds && tapHeard === rounds,
        `${tapShots} shots, ${tapHeard} rounds heard`)
    }
  }
}

// ---------------------------------------------------------------- open
async function open () {
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (!rack) throw new Error('no weapon rack on the range')
  await arm('k47')
  const r = rack.position
  /** Right-clicks where the crosshair is; what opened and how many times the gun went to aim. */
  const click = async entities => {
    const p = pick(entities)
    let opened = null
    const onOpen = w => { opened = w }
    bot.once('windowOpen', onOpen)
    const adsFrom = adsHeard.length
    rightClick(p.kind === 'cell' ? p.cell : null)
    await sleep(800)
    bot.removeListener('windowOpen', onOpen)
    if (opened) bot.closeWindow(opened)
    await sleep(500)
    return { p, opened, ads: adsHeard.length - adsFrom }
  }
  await tp(r.x, r.y, r.z - 2.5, 0, 10)
  const box = await click([rack])
  log('rack pick', box.p.kind, 'capture block at', box.p.tCell.toFixed(2), 'rack at', box.p.tEntity.toFixed(2))
  check('open: the crosshair lands on a capture block in front of the rack', box.p.kind === 'cell')
  check('open: right-clicking the rack with a gun out opens it', box.opened !== null, box.opened ? JSON.stringify(box.opened.title).slice(0, 80) : 'no window')
  check('open: opening the rack does not aim the gun as well', box.ads === 0, `${box.ads} gun.ads sounds`)

  const target = Object.values(bot.entities).filter(e => e.name === 'zombie')
    .reduce((a, b) => !a || b.position.distanceTo(r) < a.position.distanceTo(r) ? b : a, null)
  if (!target) throw new Error('no range target')
  const home = target.position.clone()
  const nearest = '@e[type=minecraft:zombie,sort=nearest,limit=1]'
  try {
    await rcon(`execute as ${name} at @s positioned ${home.x} ${home.y} ${home.z} run tp ${nearest} ${r.x.toFixed(3)} ${r.y.toFixed(3)} ${(r.z - 0.6).toFixed(3)}`)
    await sleep(500)
    const aim = await click([rack, target])
    log('target pick', aim.p.kind, 'capture block at', aim.p.tCell.toFixed(2), 'target at', aim.p.tEntity.toFixed(2))
    check('open: a target in front of the rack gets aimed at, the rack stays shut', aim.opened === null && aim.ads > 0,
      `${aim.opened ? 'opened ' + JSON.stringify(aim.opened.title).slice(0, 60) : 'nothing opened'}, ${aim.ads} gun.ads sounds`)
  } finally {
    await rcon(`execute as ${name} at @s positioned ${r.x} ${r.y} ${r.z} run tp ${nearest} ${home.x.toFixed(3)} ${home.y.toFixed(3)} ${home.z.toFixed(3)}`)
    await sleep(300)
  }
}

// ---------------------------------------------------------------- stray
async function stray (spot) {
  await arm('k47')
  await tp(spot.x, spot.y, spot.z, 180, 50) // the floor about 1.4 blocks ahead, well inside the server's 4.5
  let from = logSize()
  dropKey() // full magazine: no reload
  swing()
  await sleep(1500)
  check('stray: Q on a full magazine and its swing fire nothing', shotsSince(from).length === 0, `${shotsSince(from).length} shots`)
  await sleep(300)
  from = logSize()
  swing()
  await sleep(1500)
  check('stray: an arm swing alone fires nothing', shotsSince(from).length === 0, `${shotsSince(from).length} shots`)
  await arm('dm14')
  await tp(spot.x, spot.y, spot.z, 180, 50)
  from = logSize()
  dropKey()
  swing()
  await sleep(1500)
  const phantom = shotsSince(from).length
  await home(spot)
  const at = await cell()
  from = logSize()
  dig(START, at)
  swing()
  await sleep(100)
  dig(ABORT, at)
  await sleep(500)
  check('stray: a DM14 fires nothing on Q and its swing, and once on the next click', phantom === 0 && shotsSince(from).length === 1,
    `${phantom} shots on Q, ${shotsSince(from).length} on the click`)
}

// ---------------------------------------------------------------- fall
async function fall (spot) {
  await arm('k47')
  const height = 12
  for (let dy = 1; dy <= height + 2; dy++) {
    const b = bot.blockAt(spot.offset(0, dy, 0))
    if (b && b.name !== 'air') throw new Error('no room to fall from above the firing point: ' + b.name + ' at +' + dy)
  }
  await home(spot)
  await rcon(`execute as ${name} at @s run tp @s ${spot.x.toFixed(3)} ${spot.y + height} ${spot.z.toFixed(3)} 180 90`)
  const asked = Date.now()
  while (bot.entity.position.y < spot.y + height - 0.5 && Date.now() - asked < 2000) await new Promise(resolve => bot.once('physicsTick', resolve))
  let cellsSeen = 0
  const began = Date.now()
  let landed = null
  while (Date.now() - began < 4000) {
    await new Promise(resolve => bot.once('physicsTick', resolve))
    cellsSeen += captures().length
    if (bot.entity.onGround && bot.entity.position.y < spot.y + 0.5) { landed = Date.now() - began; break }
  }
  await sleep(300)
  // A free fall of 12 blocks takes 18 ticks (900 ms); one tick in cobweb stopped it dead (seen 1.3 s).
  check('fall: the fall takes no longer than a free fall', landed !== null && landed < 1150, `${landed} ms, ${cellsSeen} cell-ticks seen`)
  check('fall: no capture block by the feet where it lands', byFeet() === 0, `${byFeet()} cells`)
}

;(async () => {
  if (scenarios.includes('cadence')) cadence()
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  await enterRange()
  await sleep(500)
  const spot = bot.entity.position.clone()
  log('on the range at', spot.toString())
  for (const scenario of scenarios) {
    log('---', scenario)
    if (scenario === 'cadence') continue
    else if (scenario === 'lag') await lag(spot)
    else if (scenario === 'burst') await burst(spot)
    else if (scenario === 'close') await close(spot)
    else if (scenario === 'rhythm') await rhythm(spot)
    else if (scenario === 'open') await open(spot)
    else if (scenario === 'fall') await fall(spot)
    else if (scenario === 'stray') await stray(spot)
    else log('unknown scenario', scenario)
  }
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  bot.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(e => {
  log('ERROR', e.stack || e.message, failures)
  bot.quit()
  process.exit(2 + failures.length)
})
