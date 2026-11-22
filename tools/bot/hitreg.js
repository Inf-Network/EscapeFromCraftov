// Hit registration (issue #35): fences, glass, doors and moving targets, with two bots in one raid on
// the proving ground. The shooter's rounds are read from the server's debug impact lines.
//
//   fence   the target stands on the sniper tower in the soldier arena right behind the railing, the
//           shooter on the ground 40 blocks away aims at the middle of its head (DM14, scoped): the
//           line passes over the fence post 0.2 blocks above its model but under its 1.5-block
//           collision box. The rounds must hit the target in the head (they used to spark in the air),
//           and rounds aimed lower, into the railing's model, must still stop on it.
//   glass   in and around the glass house: through a plain glass pane the first round must already hit
//           (through=1, 90% damage); through the tinted south wall no round may get through.
//   door    an M4C (5.56 M855, pen 3) at the chest from 9 blocks, through the open doorway of the
//           drill building and then through its closed spruce door: through the door it must hit and do
//           less damage (70%) than through the doorway, on the same body part.
//   lag     the target strafes left and right at walking pace 8 blocks away; the shooter aims at where it
//           saw the target 100 ms earlier (a lagging client) and fires 32 single M4C rounds with lag
//           compensation on, then 32 with it off (/cv lagcomp). With it on the server rewinds
//           ping + extra-ms (150 by default; the log must say so) and the hit rate must be clearly
//           higher.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node hitreg.js [scenario ...]
//
// Needs `debug: true` (hits are read from the log) and test mode (`test.enabled: true`, so the bots may
// queue for the proving ground) in the server's plugins/Craftov/config.yml. Soldiers in the raid are
// killed first, sandbags on the fence line cleared and glass broken: the bots' raid is ended at the end.
// Leaves lag compensation switched on (/cv lagcomp on). Exit code: number of failed checks (2+ also
// when the bots could not be set up).
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const LOG = process.env.SERVER_LOG || path.join(__dirname, '../../server/run/logs/latest.log')
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['fence', 'glass', 'door', 'lag']
const tag = String(Math.floor(10000 + Math.random() * 90000))
const SHOOTER = 'Hrs' + tag
const TARGET = 'Hrt' + tag

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

/** Holds the gun and fills its magazine. */
async function arm (id) {
  const slot = hotbar(shooter, id)
  if (slot < 0) throw new Error(id + ' did not reach the hotbar')
  shooter.setQuickBarSlot(slot - 36)
  await sleep(1200)
  reloadKey(shooter)
  await sleep(4500)
}

/** Holds right mouse: vanilla repeats the use packet every 4 ticks while it is down. */
function aimWhile () {
  shooter.activateItem()
  const timer = setInterval(() => shooter.activateItem(), 150)
  return () => { clearInterval(timer); shooter.deactivateItem() }
}

/** Turns the shooter's view to `p` and tells the server at once (before the trigger that follows). */
function lookAt (p) {
  const eye = shooter.entity.position.offset(0, 1.62, 0)
  const dx = p.x - eye.x
  const dy = p.y - eye.y
  const dz = p.z - eye.z
  const yaw = -Math.atan2(dx, dz) * 180 / Math.PI
  const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI
  shooter.entity.yaw = conv.fromNotchianYaw(yaw)
  shooter.entity.pitch = conv.fromNotchianPitch(pitch)
  shooter._client.write('look', { yaw, pitch, flags: { onGround: true, hasHorizontalCollision: false } })
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

/** Kills every soldier and drone in the raid, so nobody else shoots or walks into the line of fire. */
async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) {
    await rcon(`execute at ${SHOOTER} run kill @e[type=minecraft:${type},distance=..300]`)
  }
}

/** Keeps the target alive however often it is hit: soaks 80% and heals it back every second. */
function keepAlive () {
  const tick = () => {
    rcon(`effect give ${TARGET} resistance 30 3 true`).catch(() => {})
    rcon(`effect give ${TARGET} instant_health 1 3 true`).catch(() => {})
  }
  tick()
  const timer = setInterval(tick, 1000)
  return () => clearInterval(timer)
}

// "[Craftov] <id> 试验场 NORMAL RUNNING · ...", one line per raid.
const raidIds = async () => (await rcon('cv raids')).split('\n').map(l => (l.trim().match(/^\[Craftov\]\s+(\S+)\s/) || [])[1]).filter(Boolean)
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
  await clearSoldiers()
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
/** Switches the held M4C to single shots: the action bar names the mode after each press. */
async function semi () {
  for (let i = 0; i < 3; i++) {
    const n = shooter.bars.length
    swapKey(shooter)
    await sleep(500)
    if (shooter.bars.slice(n).some(b => b.includes('单发'))) return
  }
  throw new Error('the M4C did not switch to single shots')
}

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

// ---------------------------------------------------------------- lag
async function lag () {
  await arm('m4c')
  await semi()
  await clearSoldiers()
  // Open ground between the container gallery and the armory, clear of the armory's boxes (x 24-31).
  const run = { x0: 25.5, x1: 30.5, z: 70.5 }
  const spot = { x: 28, y: 65, z: 78.5 }
  await tp(SHOOTER, spot.x, spot.y, spot.z, 180, 0)
  // Where the shooter saw the target, as it heard about it.
  const seen = []
  const watch = setInterval(() => {
    const t = targetEntity()
    if (!t) return
    const last = seen[seen.length - 1]
    if (!last || !last.p.equals(t.position)) seen.push({ at: Date.now(), p: t.position.clone() })
    while (seen.length > 200) seen.shift()
  }, 5)
  /** Where the shooter saw the target `ms` ago. */
  const sawAt = ms => {
    const when = Date.now() - ms
    let before = null
    for (const s of seen) {
      if (s.at <= when) before = s
      else return before || s
    }
    return before
  }
  let strafing = true
  const strafe = (async () => {
    let leg = 0
    while (strafing) {
      const dir = leg++ % 2 ? 'left' : 'right'
      target.setControlState(dir, true)
      await sleep(1100)
      target.setControlState(dir, false)
    }
  })()
  const series = async (label) => {
    await tp(TARGET, (run.x0 + run.x1) / 2, 65, run.z, 0, 0)
    reloadKey(shooter)
    await sleep(3500)
    const stopAim = aimWhile()
    await sleep(800)
    const from = logSize()
    let fired = 0
    while (fired < 32) {
      // Back to the middle if the strafing drifted off the run.
      const now = targetEntity()
      if (now && (now.position.x < run.x0 - 1 || now.position.x > run.x1 + 1 || Math.abs(now.position.z - run.z) > 1.5)) {
        await tp(TARGET, (run.x0 + run.x1) / 2, 65, run.z, 0, 0)
        await sleep(400)
        continue
      }
      const s = sawAt(100)
      if (!s) { await sleep(50); continue }
      lookAt(s.p.offset(0, 1.1, 0))
      pull()
      fired++
      await sleep(350)
    }
    stopAim()
    await sleep(600)
    const rounds = impacts(from)
    const hits = rounds.filter(r => r.target === TARGET).length
    const rewinds = [...new Set(rounds.map(r => r.rewind))]
    log(`lag ${label}: ${hits} hits of ${rounds.length} rounds, rewind ${rewinds.join('/')} ms`)
    return { rounds: rounds.length, hits, rewinds }
  }
  const keep = keepAlive()
  await rcon('cv lagcomp on')
  const on = await series('compensated')
  await rcon('cv lagcomp off')
  const off = await series('not compensated')
  await rcon('cv lagcomp on')
  keep()
  strafing = false
  await strafe
  clearInterval(watch)
  const rate = r => r.rounds ? r.hits / r.rounds : 0
  check('lag: at least 25 rounds logged in each series', on.rounds >= 25 && off.rounds >= 25, `${on.rounds} / ${off.rounds}`)
  check('lag: compensated rounds rewind ping + extra-ms', on.rewinds.length > 0 && on.rewinds.every(ms => ms >= 100 && ms <= 300),
    on.rewinds.join(', '))
  check('lag: uncompensated rounds do not rewind', off.rewinds.length === 1 && off.rewinds[0] === 0, off.rewinds.join(', '))
  check('lag: aiming where the target was seen 100 ms ago hits clearly more often compensated',
    rate(on) >= 0.6 && rate(on) >= rate(off) + 0.25, `${(rate(on) * 100).toFixed(0)}% vs ${(rate(off) * 100).toFixed(0)}%`)
}

// ---------------------------------------------------------------- main
;(async () => {
  await intoRaid()
  const keep = keepAlive()
  for (const scenario of scenarios) {
    log('---', scenario)
    try {
      if (scenario === 'fence') await fence()
      else if (scenario === 'glass') await glass()
      else if (scenario === 'door') await door()
      else if (scenario === 'lag') await lag()
      else log('unknown scenario', scenario)
    } catch (e) {
      check(`${scenario}: ran`, false, e.message)
    }
  }
  keep()
  for (const id of ourRaids) await rcon('cv end ' + id).catch(() => {})
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  shooter.quit()
  target.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(e => {
  log('ERROR', e.stack || e.message, failures)
  if (shooter) shooter.quit()
  if (target) target.quit()
  process.exit(2 + failures.length)
})
