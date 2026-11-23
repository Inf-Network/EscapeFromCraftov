// Soldier behaviour on the proving ground, with headless bots and soldiers posted by /cv ai spawn.
//
//   node aibehaviour.js                every scenario
//   node aibehaviour.js doors sight    only these (doors sight range operator smoke boss fence nade
//                                      wake doorbody edge)
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
//   fence     On the field, A stands behind a long spruce fence two blocks high, 15 blocks from a
//             guard: the guard sees A through it (交战) but fires no round in 6 s (rounds stop in
//             fences) and steps aside looking for a clear shot; once the fence is gone it opens
//             fire within 6 s. (Two high because rays, bullets' included, only meet a block inside
//             its own cell: the top half of a one-high fence stops neither sight nor rounds.)
//   nade      A drops a frag at its own feet, leaves for the ground far below the field, and a
//             patrol is posted 3 blocks from the grenade facing away: the blast tells it roughly
//             where the grenade came from (受击/掩护), not only that something went off (警戒).
//   wake      A lands on 机密 and crouches 9 blocks behind the sleeping boss group for 3 s: still
//             asleep. One pistol shot into the sky wakes all of them within 2 s, long before 60 s.
//   doorbody  As doors, then the guard is moved back into the doorway and killed there: its body
//             lies in the doorway, and the door still swings shut within 8 s.
//   edge      On the field, A (resistance, so the shots do not down it) stands 5 blocks from a
//             guard posted next to the field's west edge, A on the inside: the guard backs off and
//             sidesteps but never off the field for 8 s.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 2.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
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
/** Whether the bot was told `text`: in chat, or on the HUD's alert banner (raid news), which `cv hud` prints. */
const told = async (bot, text) => bot.chatLog.some(l => l.includes(text)) || (await rcon(`cv hud ${bot.username}`)).includes(text)

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

/** Puts the hotbar on the first slot holding an item whose components mention {@code id}. */
function select (bot, id) {
  const slot = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || s.nbt || '').includes(id))
  if (slot < 0) return false
  bot.setQuickBarSlot(slot - 36)
  return true
}

/** Counts the soldier gunshots A hears (craftov:gun.<set>.near/.mid/.far/.muff; A fires none). */
function earShots (bot) {
  const ear = { shots: 0 }
  bot.on('soundEffectHeard', (name, pos) => {
    if (process.env.DEBUG) console.log('sound', String(name), pos && `${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)}`)
    if (/gun\.[^.]+\.(near|mid|far|muff)/.test(String(name))) ear.shots++
  })
  return ear
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
    // Keys 1-2 are the only cells a gun goes to (#10) and the kit's rifle and pistol hold them.
    await rcon(`item replace entity ${A.username} hotbar.0 with minecraft:air`)
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
    const early = await told(A, '出现在地图中')
    await sleep(Math.max(0, 50000 - (Date.now() - t0)))
    const still = (await soldiers()).filter(s => s.state === '休眠').length
    if (early) problems.push('the boss was announced at the start')
    if (still !== sleeping.length) problems.push(`only ${still} of ${sleeping.length} still asleep 50 s in`)
    let announced = false
    const woke = await waitFor(async () => {
      announced = announced || await told(A, '出现在地图中') // the banner shows for 6 s
      return announced && (await soldiers()).every(s => s.state !== '休眠')
    }, 20000, 500)
    if (!woke) problems.push(`not woken and announced by 70 s (${(await soldiers()).filter(s => s.state === '休眠').length} asleep)`)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'the boss and its guards were in place and asleep from the start, woke and were announced at 60 s'} | asleep at start ${sleeping.length}, at 50 s ${still}`]
  },

  async fence () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    await rcon(`effect give ${A.username} minecraft:resistance 90 4 true`)
    const top = FIELD.y + 1
    // A long fence two high across the line of fire, 2 blocks in front of A; the guard is 15 blocks
    // away. fill does not join fences up, so the posts get their east and west arms here (else
    // the lines run through the gaps between bare posts).
    const fence = `35 ${top} 60 86 ${top + 1} 60`
    await inWorld(A, `fill ${fence} minecraft:spruce_fence[east=true,west=true]`)
    await inWorld(A, `tp ${A.username} 60.5 ${top} 62.5 180 0`)
    await sleep(500)
    const ear = earShots(A)
    const guard = await post(A, 'guard', 60.5, top, 47.5)
    await rcon(`execute in minecraft:${A.worldName} as ${guard.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
    const spotted = await waitFor(async () => { const s = await soldier(guard.uuid); return s && s.state === '交战' }, 12000, 250)
    if (!spotted) return ['FAIL', `the guard never saw A behind the fence (${JSON.stringify(await soldier(guard.uuid))})`]
    // Every line to A runs through the fence: seen, but no round would get there.
    const shots0 = ear.shots
    const xs = []
    const states = new Set()
    await waitFor(async () => { const s = await soldier(guard.uuid); if (s) { xs.push(s.x); states.add(s.state) } return false }, 6000, 250)
    const behindShots = ear.shots - shots0
    const moved = xs.length ? Math.max(...xs) - Math.min(...xs) : 0
    // The fence gone, the guard has a clear shot and takes it.
    await inWorld(A, `fill ${fence} minecraft:air`)
    const shots1 = ear.shots
    const fired = await waitFor(() => ear.shots > shots1, 6000, 100)
    const problems = []
    if (behindShots > 0) problems.push(`fired ${behindShots} rounds into the fence`)
    if (moved < 1.5) problems.push(`never stepped aside for a clear shot (x ${Math.min(...xs).toFixed(1)}..${Math.max(...xs).toFixed(1)})`)
    if (!fired) problems.push('did not fire at A with the fence gone')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'held fire at A seen through the fence, stepped aside, fired once the fence was gone'} | behind the fence: ${behindShots} shots heard, x moved ${moved.toFixed(1)}, states ${[...states].join(',')}; fence gone: ${fired ? 'fired' : 'silent'}`]
  },

  async nade () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    const top = FIELD.y + 1
    await rcon(`cv give ${A.username} frag 1`)
    await inWorld(A, `tp ${A.username} 60.5 ${top} 40.5 0 90`)
    await sleep(800)
    if (!select(A, 'frag')) return ['FAIL', 'no frag on the hotbar']
    await sleep(300)
    // Straight down at A's feet; it goes off 3.5 s later, by which time A is far below the field.
    await A.look(0, -Math.PI / 2, true)
    A.activateItem()
    await sleep(150)
    A.deactivateItem()
    await sleep(300)
    await inWorld(A, `tp ${A.username} 30.5 65 20.5`)
    // Posted now, it has no time to wander off before the blast; it faces away from where A stood.
    const patrol = await post(A, 'patrol', 60.5, top, 37.5)
    await rcon(`execute in minecraft:${A.worldName} as ${patrol.uuid} at @s run tp @s ~ ~ ~ 180 0`)
    const states = []
    const reacted = await waitFor(async () => {
      const s = await soldier(patrol.uuid)
      if (!s) return true
      if (states[states.length - 1] !== s.state) states.push(s.state)
      return s.state === '受击' || s.state === '掩护'
    }, 6000, 150)
    const health = (await rcon(`data get entity ${patrol.uuid} Health`)).match(/: (-?[\d.]+)f/)
    const alive = !!(await soldier(patrol.uuid))
    const ok = reacted && alive
    return [ok ? 'PASS' : 'FAIL', `${ok ? 'the blast gave the patrol a threat to answer' : alive ? 'the patrol hit by the frag never reacted as to a hit' : 'the frag killed the patrol (move it farther out)'} | states ${states.join(' -> ')}; health ${health ? health[1] : '-'}`]
  },

  async wake () {
    const A = await land('CONFIDENTIAL')
    const t0 = A.raidStart || Date.now()
    const group = (await soldiers()).filter(s => s.state === '休眠')
    if (group.length !== 3) return ['FAIL', `expected the boss and two guards asleep, saw ${group.length}`]
    const boss = group.find(s => Math.hypot(s.x - 47.5, s.z - 16.5) < 1.5) || group[0]
    const p = ahead(boss, -9)
    const face = Math.atan2(-(boss.x - p.x), boss.z - p.z) * 180 / Math.PI
    A.setControlState('sneak', true)
    await inWorld(A, `tp ${A.username} ${p.x.toFixed(2)} ${boss.y} ${p.z.toFixed(2)} ${face.toFixed(1)} 0`)
    await sleep(3000)
    const quiet = (await soldiers()).filter(s => s.state === '休眠').length
    // One shot from the pistol straight up: nothing hit, but they hear it 9 blocks away.
    A.setQuickBarSlot(1)
    await sleep(300)
    await A.look(0, Math.PI / 2, true)
    await sleep(200)
    A.swingArm()
    const shotAt = (Date.now() - t0) / 1000
    const woke = await waitFor(async () => (await soldiers()).every(s => s.state !== '休眠'), 2000, 150)
    A.setControlState('sneak', false)
    const problems = []
    if (quiet !== 3) problems.push(`only ${quiet} of 3 still asleep with A crouched behind them`)
    if (!woke) problems.push(`a pistol shot 9 blocks away left ${(await soldiers()).filter(s => s.state === '休眠').length} asleep`)
    if (shotAt > 55) problems.push(`the shot came ${shotAt.toFixed(0)} s in, too close to the boss's own time`)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'asleep with A behind them, all awake within 2 s of a shot 9 blocks away'} | shot at ${shotAt.toFixed(1)} s into the raid`]
  },

  async doorbody () {
    const A = await land()
    await clearSoldiers(A)
    const door = '75 65 56'
    const isOpen = async () => /passed/i.test(await rcon(`execute in minecraft:${A.worldName} if block ${door} minecraft:spruce_door[open=true]`))
    if (await isOpen()) return ['FAIL', 'the drill building door is open before the test']
    const guard = await post(A, 'guard', 75.5, 65, 63.5)
    await inWorld(A, `tp ${A.username} 75.5 65 58.5 180 0`)
    await sleep(300)
    await rcon(`execute in minecraft:${A.worldName} as ${guard.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
    if (!await waitFor(async () => { const s = await soldier(guard.uuid); return s && s.state === '交战' }, 6000, 200)) return ['FAIL', 'the guard never spotted A']
    await inWorld(A, `tp ${A.username} 80.5 70 40.5 0 0`)
    const inside = await waitFor(async () => { const s = await soldier(guard.uuid); return s && s.z < 55.5 }, 10000, 100)
    if (!inside) return ['FAIL', 'the guard did not come in through the door']
    // Back into the doorway and dead there: its body lies across the threshold.
    await inWorld(A, `tp ${guard.uuid} 75.5 65 56.5`)
    await rcon(`kill ${guard.uuid}`)
    await sleep(300)
    const openAfter = await isOpen()
    const body = /passed/i.test(await rcon(`execute in minecraft:${A.worldName} if entity @e[type=minecraft:mannequin,x=74.8,y=65,z=55.8,dx=1.4,dy=2,dz=1.4]`))
    if (!openAfter) return ['FAIL', 'the door had already shut before the body lay in it (inconclusive)']
    if (!body) return ['FAIL', 'no body in the doorway (inconclusive)']
    const t0 = Date.now()
    const closed = await waitFor(async () => !await isOpen(), 8000, 200)
    return [closed ? 'PASS' : 'FAIL', `${closed ? 'the door shut with a body in the doorway' : 'the door stayed open over the body for 8 s'} | ${closed ? 'shut after ' + ((Date.now() - t0) / 1000).toFixed(1) + ' s' : '-'}`]
  },

  async edge () {
    const A = await land()
    await clearSoldiers(A)
    await field(A)
    await rcon(`effect give ${A.username} minecraft:resistance 60 4 true`)
    const top = FIELD.y + 1
    // The field's west edge is x = 20; the guard stands 1.5 blocks from it, A 5 blocks further in.
    const guard = await post(A, 'guard', 21.5, top, 52.5)
    await inWorld(A, `tp ${A.username} 26.5 ${top} 52.5 90 0`)
    await rcon(`execute in minecraft:${A.worldName} as ${guard.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
    if (!await waitFor(async () => { const s = await soldier(guard.uuid); return s && s.state === '交战' }, 6000, 200)) return ['FAIL', 'the guard never spotted A']
    let low = Infinity
    let west = Infinity
    const zs = []
    let gone = false
    await waitFor(async () => {
      const s = await soldier(guard.uuid)
      if (!s) { gone = true; return true }
      low = Math.min(low, s.y)
      west = Math.min(west, s.x)
      zs.push(s.z)
      return false
    }, 8000, 200)
    const problems = []
    if (gone) problems.push('the guard is gone (fell off and died?)')
    if (low < top - 0.5) problems.push(`the guard went down to y ${low.toFixed(1)}`)
    if (west < 20) problems.push(`the guard went past the edge to x ${west.toFixed(1)}`)
    const spread = zs.length ? Math.max(...zs) - Math.min(...zs) : 0
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'stayed on the field while backing off and sidestepping'} | lowest y ${low.toFixed(1)}, westmost x ${west.toFixed(1)}, z range ${spread.toFixed(1)}`]
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
    // The control: the same patrol sees A at once out of the cloud, 8 blocks to the side. It may
    // have wandered off meanwhile, so it is turned to face A again.
    await inWorld(A, `tp ${A.username} 68.5 ${FIELD.y + 1} 42.5`)
    await rcon(`execute in minecraft:${A.worldName} as ${patrol.uuid} at @s run tp @s ~ ~ ~ facing entity ${A.username}`)
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
