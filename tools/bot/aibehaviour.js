// Soldier behaviour on the proving ground, with headless bots and soldiers posted by /cv ai spawn.
//
//   node aibehaviour.js                every scenario
//   node aibehaviour.js doors sight    only these (doors sight range operator smoke boss)
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bots, who are not op, can queue for the proving ground.
// match.wait-seconds: 2 keeps the run short. Ports come from PORT (25565), RCON_PORT (25575)
// and RCON_PASSWORD (craftov-local). Each scenario prints PASS or FAIL with what was seen; the
// exit code is the number of failures. DEBUG=1 prints the soldier lines it polls.
//
// Every scenario lands one fresh bot (A) on the proving ground, clears the map's own soldiers
// and posts the ones it needs with /cv ai spawn, so nothing else interferes. Soldier state is
// read from /cv ai (position, facing, 交战 when it fights someone, 察觉 values).
//
//   doors     A shows itself to a guard outside the drill building's south door, then goes inside
//             and upstairs (the door stays shut behind it). The guard opens the door and comes in
//             within 10 s, and the door swings shut again a few seconds after it went through.
//   sight     On a stone field high above the map in daylight, A crouches still 20 blocks in
//             front of a patrol (kept in front as the patrol turns): spotted within 8 s. Then A
//             crouches still 8 blocks behind a fresh patrol for 10 s: never spotted, no awareness.
//   range     On the field, A crouches 60 blocks from a guard's post with a DM14 and shoots until
//             it hits, then ducks behind a wall. The guard reacts (受击), ends up at least 5 blocks
//             closer to A than its post and sits in cover behind one of the low walls (掩护).
//   operator  A rival operator posted between the gallery and the armory starts looting; A shoots
//             it from behind, then vanishes up onto the field. It is looting again within 15 s.
//   smoke     On the field, A throws a smoke grenade at its feet and stands in the cloud, a patrol
//             15 blocks away turned to face it: not spotted in 8 s; stepping 8 blocks out of the
//             cloud gets A spotted within 8 s.
//   boss      A lands on 机密 (two rifles give it the gear value): the proving ground's boss and its
//             two guards are asleep (休眠) at the boss post from the start and still at 50 s into
//             the raid, then wake and are announced (出现在地图中) by 70 s. Takes over a minute.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const MAP = 'proving'
// A flat stone field high above the proving ground (inside its bounds), lit by the sky.
const FIELD = { x1: 20, x2: 100, z1: 16, z2: 88, y: 120 }

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const live = []
// The world of the raid the current scenario plays in (/cv ai takes it in place of a raid id).
let raidWorld = ''

/** A fresh player with a random name (a reused one would rejoin an old raid). */
function make () {
  const name = `ab${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  const track = p => {
    if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '')
    // Moved into the raid world for the entry camera: the raid's clock starts about now.
    if (bot.worldName.startsWith('craftov_raid_') && !bot.raidStart) bot.raidStart = Date.now()
  }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  live.push(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function quitAll () {
  for (const bot of live.splice(0)) { try { bot.quit('disconnect.quitting') } catch (e) {} }
  await sleep(500)
}

async function waitFor (pred, ms, step = 100) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(step) }
  return false
}

const landed = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'

/** A fresh player landed on the proving ground, past the landing protection and the soldiers' grace. */
async function land (tier = 'NORMAL') {
  const bot = await make()
  await sleep(2500)
  if (tier !== 'NORMAL') {
    // Enough gear value for 机密 (60,000): two rifles in the backpack-less pockets.
    await rcon(`cv give ${bot.username} scr`)
    await rcon(`cv give ${bot.username} kl7`)
    await sleep(500)
  }
  bot.chat(`/cv queue ${MAP} ${tier} SOLO`)
  if (!await waitFor(() => landed(bot), 40000)) throw new Error(`not landed: ${bot.worldName} ${bot.game.gameMode}`)
  raidWorld = bot.worldName
  await sleep(3500)
  // A shot ends the soldiers' insertion grace at once (nothing is near the spawn bays to hear it).
  bot.setQuickBarSlot(1)
  await sleep(300)
  bot.swingArm()
  await sleep(500)
  return bot
}

const inWorld = (bot, cmd) => rcon(`execute in minecraft:${bot.worldName} run ${cmd}`)

/** Removes the map's own soldiers so only the ones a scenario posts are about. */
async function clearSoldiers (bot) {
  for (const type of ['zombie', 'husk', 'drowned']) await inWorld(bot, `kill @e[type=minecraft:${type}]`)
  await sleep(300)
}

/** Every soldier line of /cv ai, parsed. */
async function soldiers () {
  const out = await rcon(`cv ai ${raidWorld}`)
  const list = []
  for (const line of out.split('\n')) {
    const m = line.match(/^(.+?) (\w+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) 朝向 (-?[\d.]+) (\S+)(.*) ([0-9a-f-]{36})\s*$/)
    if (!m) continue
    const aware = {}
    const a = m[8].match(/察觉 (.*)$/)
    if (a) for (const kv of a[1].trim().split(' ')) { const [k, v] = kv.split('='); aware[k] = Number(v) }
    const target = (m[8].match(/目标 (\S+)/) || [])[1] || null
    list.push({ name: m[1], kind: m[2], x: +m[3], y: +m[4], z: +m[5], yaw: +m[6], state: m[7], target, aware, uuid: m[9] })
  }
  if (process.env.DEBUG) console.log(list.map(s => `${s.kind} ${s.x},${s.y},${s.z} yaw ${s.yaw} ${s.state} ${s.target || ''} ${JSON.stringify(s.aware)}`).join('\n'))
  return list
}

/** Posts one soldier and returns its parsed line. */
async function post (bot, kind, x, y, z) {
  const before = new Set((await soldiers()).map(s => s.uuid))
  const reply = await rcon(`cv ai spawn ${kind} ${x} ${y} ${z} ${raidWorld}`)
  const fresh = (await soldiers()).find(s => !before.has(s.uuid))
  if (!fresh) throw new Error(`no soldier posted: ${reply}`)
  return fresh
}

const soldier = async uuid => (await soldiers()).find(s => s.uuid === uuid) || null

/** Where a soldier facing {@code yaw} has a point {@code dist} blocks ahead (negative: behind). */
function ahead (s, dist) {
  const r = s.yaw * Math.PI / 180
  return { x: s.x - Math.sin(r) * dist, z: s.z + Math.cos(r) * dist }
}

async function field (bot) {
  await inWorld(bot, `fill ${FIELD.x1} ${FIELD.y} ${FIELD.z1} ${FIELD.x2} ${FIELD.y} ${FIELD.z2} minecraft:smooth_stone`)
}

/**
 * Keeps A crouching, still, {@code dist} blocks ahead of (or, negative, behind) the soldier as it
 * turns, for up to {@code ms}; returns when the soldier fights A (time in s) or null.
 */
async function hold (bot, uuid, dist, ms, seen) {
  bot.setControlState('sneak', true)
  const start = Date.now()
  let spotted = null
  while (Date.now() - start < ms) {
    const s = await soldier(uuid)
    if (!s) break
    seen.push(s)
    if (s.state === '交战' && s.target === bot.username) { spotted = (Date.now() - start) / 1000; break }
    const p = ahead(s, dist)
    const face = Math.atan2(-(s.x - p.x), s.z - p.z) * 180 / Math.PI
    await inWorld(bot, `tp ${bot.username} ${p.x.toFixed(2)} ${FIELD.y + 1} ${p.z.toFixed(2)} ${face.toFixed(1)} 0`)
    await sleep(250)
  }
  bot.setControlState('sneak', false)
  return spotted
}

const scenarios = {
  async doors () {
    const A = await land()
    await clearSoldiers(A)
    // The drill building's south door: a spruce door at 75 65 56, the inside is north of it.
    const door = '75 65 56'
    const isOpen = async () => /passed/i.test(await rcon(`execute in minecraft:${A.worldName} if block ${door} minecraft:spruce_door[open=true]`))
    if (await isOpen()) return ['FAIL', 'the drill building door is open before the test']
    const guard = await post(A, 'guard', 75.5, 65, 63.5)
    await inWorld(A, `tp ${A.username} 75.5 65 58.5 180 0`)
    await sleep(300)
    await rcon(`execute in minecraft:${A.worldName} as ${guard.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
    const spotted = await waitFor(async () => { const s = await soldier(guard.uuid); return s && s.state === '交战' }, 6000, 200)
    if (!spotted) return ['FAIL', `the guard never spotted A outside the door (${JSON.stringify(await soldier(guard.uuid))})`]
    // Into the building through the shut door and up to the first floor, out of sight from the
    // doorway: the guard saw where A went, so it has to come through the door and up the stairs.
    await inWorld(A, `tp ${A.username} 80.5 70 40.5 0 0`)
    const t0 = Date.now()
    let opened = null
    let inside = null
    await waitFor(async () => {
      const open = await isOpen()
      if (open && opened === null) opened = (Date.now() - t0) / 1000
      const s = await soldier(guard.uuid)
      if (s && s.z < 55.5 && inside === null) inside = (Date.now() - t0) / 1000
      return inside !== null
    }, 10000, 150)
    if (inside === null) return ['FAIL', `the guard did not come in within 10 s (door opened: ${opened === null ? 'never' : opened.toFixed(1) + ' s'}; ${JSON.stringify(await soldier(guard.uuid))})`]
    // Out of the doorway the door closes again behind it.
    const closed = await waitFor(async () => !await isOpen(), 8000, 200)
    const problems = []
    if (opened === null) problems.push('never saw the door open')
    if (!closed) problems.push('the door stayed open')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'the guard opened the door, came in and the door shut behind it'} | door open at ${opened === null ? '-' : opened.toFixed(1)} s, guard inside at ${inside.toFixed(1)} s`]
  },

  async sight () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    const problems = []
    const front = await post(A, 'patrol', 60.5, FIELD.y + 1, 52.5)
    const seenFront = []
    const spotted = await hold(A, front.uuid, 20, 12000, seenFront)
    if (spotted === null || spotted > 8) problems.push(`crouching 20 blocks in front: ${spotted === null ? 'not spotted in 12 s' : 'spotted only after ' + spotted.toFixed(1) + ' s'} (awareness ${seenFront.length ? JSON.stringify(seenFront[seenFront.length - 1].aware) : '-'})`)
    await clearSoldiers(A)
    await inWorld(A, `tp ${A.username} 30.5 ${FIELD.y + 1} 20.5`)
    await sleep(1500)
    const back = await post(A, 'patrol', 60.5, FIELD.y + 1, 52.5)
    const seenBack = []
    const caught = await hold(A, back.uuid, -8, 10000, seenBack)
    const peak = Math.max(0, ...seenBack.map(s => s.aware[A.username] || 0))
    if (caught !== null) problems.push(`crouching 8 blocks behind: spotted after ${caught.toFixed(1)} s`)
    else if (peak > 0) problems.push(`crouching 8 blocks behind: awareness reached ${peak.toFixed(2)}`)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'seen from the front at 20, never from behind at 8'} | front: spotted after ${spotted === null ? '-' : spotted.toFixed(1)} s; behind: ${seenBack.length} looks, peak awareness ${peak.toFixed(2)}`]
  },

  async range () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    const top = FIELD.y + 1
    // Low walls between a guard post and a firing spot 60 blocks south of it, and one to duck behind.
    for (const [x1, x2, z] of [[57, 59, 32], [62, 64, 36], [56, 58, 40], [61, 63, 45], [59, 61, 83]]) {
      await inWorld(A, `fill ${x1} ${top} ${z} ${x2} ${top + 1} ${z} minecraft:stone_bricks`)
    }
    await rcon(`cv give ${A.username} dm14`) // loaded: ten rounds
    await inWorld(A, `tp ${A.username} 62.5 ${top} 84.5 180 0`)
    await sleep(800)
    const slot = A.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || s.nbt || '').includes('dm14'))
    if (slot < 0) return ['FAIL', 'no DM14 on the hotbar']
    A.setQuickBarSlot(slot - 36)
    const guard = await post(A, 'guard', 60.5, top, 24.5)
    await sleep(1500)
    const health = async () => { const m = (await rcon(`data get entity ${guard.uuid} Health`)).match(/: (-?[\d.]+)f/); return m ? Number(m[1]) : null }
    const hp0 = await health()
    let hit = false
    let shots = 0
    // Crouched, so the guard does not spot A at this range once it starts looking.
    A.setControlState('sneak', true)
    await sleep(300)
    for (let i = 0; i < 8 && !hit; i++) {
      const s = await soldier(guard.uuid)
      if (!s) break
      // The chest through the 4x scope (one DM14 round does not kill it): aiming is a held right click.
      await A.lookAt(new (require('vec3').Vec3)(s.x, s.y + 1.2, s.z), true)
      for (let k = 0; k < 5; k++) { A.activateItem(); await sleep(120) }
      A.swingArm()
      shots++
      await sleep(250)
      A.deactivateItem()
      await sleep(1200)
      const hp = await health()
      hit = hp !== null && hp0 !== null && hp < hp0
    }
    if (!hit) return ['FAIL', `no hit on the guard 60 blocks out in ${shots} shots (${await health()} HP)`]
    // Down behind the wall: out of its sight, so it only knows roughly where the shot came from.
    await inWorld(A, `tp ${A.username} 60.5 ${top} 84.5 180 0`)
    A.setControlState('sneak', true)
    // From its post 60 blocks out (missed shots already draw it a little that way, within its leash).
    const shooter = { x: 60.5, z: 84.5 }
    const dist = s => Math.hypot(s.x - shooter.x, s.z - shooter.z)
    const start = guard
    const states = new Set()
    let covered = null
    let closest = dist(start)
    await waitFor(async () => {
      const s = await soldier(guard.uuid)
      if (!s) return true
      states.add(s.state)
      closest = Math.min(closest, dist(s))
      if (s.state === '掩护' && covered === null) covered = s
      return false
    }, 8000, 250)
    A.setControlState('sneak', false)
    const problems = []
    if (!states.has('受击') && !states.has('掩护')) problems.push(`never reacted to the hit (states ${[...states].join(',')})`)
    if (dist(start) - closest < 5) problems.push(`did not move towards the shooter (${dist(start).toFixed(1)} -> closest ${closest.toFixed(1)} blocks)`)
    if (!covered) problems.push('never took cover')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'reacted to a hit from 60 blocks, closed in and took cover'} | hit after ${shots} shots; states ${[...states].join(',')}; distance ${dist(start).toFixed(1)} -> ${closest.toFixed(1)}; cover at ${covered ? `${covered.x},${covered.z}` : '-'}`]
  },

  async operator () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    // Between the container gallery and the armory, plenty to loot within reach.
    const op = await post(A, 'operator', 60.5, 65, 69.5)
    const looting = await waitFor(async () => { const s = await soldier(op.uuid); return s && s.state === '搜刮' }, 6000, 250)
    if (!looting) return ['FAIL', `the operator never started looting (${JSON.stringify(await soldier(op.uuid))})`]
    const health = async () => { const m = (await rcon(`data get entity ${op.uuid} Health`)).match(/: (-?[\d.]+)f/); return m ? Number(m[1]) : null }
    const hp0 = await health()
    const { Vec3 } = require('vec3')
    let hit = false
    for (let i = 0; i < 4 && !hit; i++) {
      const s = await soldier(op.uuid)
      if (!s) break
      const p = ahead(s, -6) // from behind it, close enough for the starter pistol
      await inWorld(A, `tp ${A.username} ${p.x.toFixed(2)} 65 ${p.z.toFixed(2)}`)
      await sleep(250)
      const now = await soldier(op.uuid)
      await A.lookAt(new Vec3(now.x, now.y + 1.2, now.z), true)
      await sleep(100)
      A.swingArm()
      await sleep(400)
      hit = (await health()) < hp0
    }
    if (!hit) return ['FAIL', `could not hit the operator (${await health()} HP)`]
    const t0 = Date.now()
    // Gone: up onto the stone field far above, out of its sight.
    await inWorld(A, `tp ${A.username} 60.5 ${FIELD.y + 1} 40.5`)
    const states = []
    let back = null
    await waitFor(async () => {
      const s = await soldier(op.uuid)
      if (!s) return true
      if (states[states.length - 1] !== s.state) states.push(s.state)
      if (s.state === '搜刮' && states.length > 1) back = (Date.now() - t0) / 1000
      return back !== null
    }, 20000, 250)
    const ok = back !== null && back <= 15
    return [ok ? 'PASS' : 'FAIL', `${ok ? 'back to looting after the fight' : 'not looting again within 15 s of the hit'} | states ${states.join(' -> ')}; looting again after ${back === null ? '-' : back.toFixed(1) + ' s'}`]
  },

  async boss () {
    const A = await land('CONFIDENTIAL')
    const t0 = A.raidStart || Date.now()
    // The proving ground's boss (试验场教官 at 47.5 65 16.5, two guards) is due 60 s into the raid.
    const sleeping = (await soldiers()).filter(s => s.state === '休眠')
    const problems = []
    if (sleeping.length !== 3) problems.push(`expected the boss and two guards asleep at the start, saw ${sleeping.length} (${(await soldiers()).map(s => s.state).join(',')})`)
    else if (sleeping.some(s => Math.hypot(s.x - 47.5, s.z - 16.5) > 3)) problems.push(`a sleeper is away from the boss post (${sleeping.map(s => `${s.x},${s.z}`).join(' ')})`)
    const early = A.chatLog.some(l => l.includes('出现在地图中'))
    await sleep(Math.max(0, 50000 - (Date.now() - t0)))
    const still = (await soldiers()).filter(s => s.state === '休眠').length
    if (early) problems.push('the boss was announced at the start')
    if (still !== sleeping.length) problems.push(`only ${still} of ${sleeping.length} still asleep 50 s in`)
    const woke = await waitFor(async () => (await soldiers()).every(s => s.state !== '休眠') && A.chatLog.some(l => l.includes('出现在地图中')), 20000, 500)
    if (!woke) problems.push(`not woken and announced by 70 s (${(await soldiers()).filter(s => s.state === '休眠').length} asleep)`)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'the boss and its guards were in place and asleep from the start, woke and were announced at 60 s'} | asleep at start ${sleeping.length}, at 50 s ${still}`]
  },

  async smoke () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    await rcon(`cv give ${A.username} smoke 2`)
    await inWorld(A, `tp ${A.username} 60.5 ${FIELD.y + 1} 40.5 0 90`)
    await sleep(800)
    const slot = A.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || s.nbt || '').includes('smoke'))
    if (slot < 0) return ['FAIL', 'no smoke grenade on the hotbar']
    A.setQuickBarSlot(slot - 36)
    await sleep(300)
    // Straight down: it lands at A's feet and goes off 1.5 s later.
    await A.look(0, -Math.PI / 2, true)
    A.activateItem()
    await sleep(200)
    A.deactivateItem()
    await sleep(2800)
    const patrol = await post(A, 'patrol', 60.5, FIELD.y + 1, 55.5)
    await rcon(`execute in minecraft:${A.worldName} as ${patrol.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
    const looks = []
    await waitFor(async () => { const s = await soldier(patrol.uuid); if (s) looks.push(s); return s && s.state === '交战' }, 8000, 250)
    const inSmoke = looks.filter(s => s.state === '交战' || (s.aware[A.username] || 0) >= 1).length
    const peak = Math.max(0, ...looks.map(s => s.aware[A.username] || 0))
    // The control: the same patrol sees A at once out of the cloud, 8 blocks to the side.
    await inWorld(A, `tp ${A.username} 68.5 ${FIELD.y + 1} 42.5`)
    const t0 = Date.now()
    const outside = await waitFor(async () => { const s = await soldier(patrol.uuid); return s && s.state === '交战' }, 8000, 250)
    const problems = []
    if (inSmoke) problems.push(`spotted in the smoke (peak awareness ${peak.toFixed(2)})`)
    if (!outside) problems.push('not spotted out of the smoke either (the patrol is not looking)')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'unseen inside the smoke for 8 s, seen as soon as out of it'} | in smoke: ${looks.length} looks, peak awareness ${peak.toFixed(2)}; out of it: ${outside ? 'spotted after ' + ((Date.now() - t0) / 1000).toFixed(1) + ' s' : '-'}`]
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
    // Let the previous raid wind down before the next scenario queues.
    await sleep(2000)
    if (result[0] !== 'PASS') failures++
    console.log(`${result[0]} ${name}: ${result[1]}`)
  }
  process.exit(failures)
})()
