// Moving accuracy, sprint fire and recoil return (issue #32), on the private range.
//
//   spread   SV9 on semi, 10 shots each standing, strafing and strafing while aiming: the per-shot
//            spread in the server's debug log must grow with the real (position-measured) speed,
//            walking hip fire at least 1.4x standing hip fire, walking ADS at least 1.8x standing ADS.
//   sprint   (P18) pulling the trigger while sprinting fires nothing (no shot sound, no debug shot line)
//            and says why; right after the sprint ends still nothing, a moment later the gun fires;
//            a trigger held through the end of the sprint fires once the gun is back up; running at
//            sprint pace without ever sending the sprint command fires nothing either.
//   recoil   M4C on full auto, 30 rounds without touching the mouse: a second after letting go the
//            camera must have come back down at least half of the climb, and not past where it
//            started; pulling all or half of the climb down while firing is not pulled further. A
//            second spray 0.3 s after a pulled-down one, turned 8 degrees up or 9 down to another
//            target, recovers toward its own crosshair and not past it. The bullets' own climb starts at 0, stays within 3 degrees, lifts the rounds of a
//            client that ignores the camera kick, and is gone a moment after the spray.
//   impact   (not in the default run) where rounds land on a wall 30 and 100 m away with the camera
//            held exactly on the crosshair: an M4C magazine aimed and from the hip, and DM14 shots as
//            fast as it cycles. Semi-auto rounds must not be lifted; automatic ones only a little,
//            still creeping up at the end of the magazine.
//   ai       (not in the default run, about 3 minutes) a solo raid on the proving ground: hits
//            per soldier shot while standing still versus strafing, in alternating 7 s phases.
//            Noisy: the rates swing with where the soldiers stand (seen 19% to 44% fewer hits when
//            strafing), so rerun a single failure before believing it.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node accuracy.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (the spread is read from the log),
// and for `ai` test mode (test.enabled: true) so the bot may queue for the proving ground.
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
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
const name = 'Acc' + Math.floor(10000 + Math.random() * 90000)
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['spread', 'sprint', 'recoil']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
let worldChanges = 0
bot.on('respawn', () => worldChanges++)
const bars = []
bot._client.on('action_bar', p => bars.push({ at: Date.now(), text: JSON.stringify(p) }))
// The shooter hears its own first-person report.
let shots = 0
const shotSound = p => { if (/gun\.[a-z0-9_]+\.fp/.test(JSON.stringify(p.sound || p.soundId || ''))) shots++ }
bot._client.on('entity_sound_effect', shotSound)
bot._client.on('sound_effect', shotSound)
// mineflayer applies player_rotation as absolute angles; recoil sends it relative, as vanilla expects.
// Its plugins load after createBot returns, so replace its handler once logged in.
// `deaf` plays a modified client that never applies the camera kick.
let deaf = false
bot.once('login', () => {
  bot._client.removeAllListeners('player_rotation')
  bot._client.on('player_rotation', p => {
    if (deaf) return
    const yaw = (p.relativeYaw ? conv.toNotchianYaw(bot.entity.yaw) : 0) + p.yaw
    const pitch = Math.max(-90, Math.min(90, (p.relativePitch ? conv.toNotchianPitch(bot.entity.pitch) : 0) + p.pitch))
    bot.entity.yaw = conv.fromNotchianYaw(yaw)
    bot.entity.pitch = conv.fromNotchianPitch(pitch)
  })
})
const pitchDeg = () => conv.toNotchianPitch(bot.entity.pitch)

const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
const dropKey = () => bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
const swapKey = () => bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })

/** Server log lines written since `from` (a byte offset). */
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
function shotLines (from) {
  const out = []
  for (const line of logSince(from)) {
    const m = line.match(new RegExp(`\\[debug\\] ${name} shot spread=([0-9.]+) speed=([0-9.]+) move=([0-9.]+) aim=(true|false) ground=(?:true|false) pitch=(-?[0-9.]+) climb=([0-9.]+)`))
    if (m) out.push({ spread: Number(m[1]), speed: Number(m[2]), move: Number(m[3]), aim: m[4] === 'true', pitch: Number(m[5]), climb: Number(m[6]) })
  }
  return out
}
const avg = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0

/** Holds right mouse: vanilla repeats the use packet every 4 ticks while it is down. */
function aimWhile () {
  bot.activateItem()
  const timer = setInterval(() => bot.activateItem(), 150)
  return () => { clearInterval(timer); bot.deactivateItem() }
}

/** Holds left mouse: every swing counts as a trigger input for two ticks. */
function triggerWhile () {
  bot.swingArm()
  const timer = setInterval(() => bot.swingArm(), 50)
  return () => clearInterval(timer)
}

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
  await rcon(`cv give ${name} ${id}`)
  await sleep(800)
  const slot = hotbar(id)
  if (slot < 0) throw new Error(id + ' did not reach the hotbar')
  bot.setQuickBarSlot(slot - 36)
  await sleep(600)
  dropKey()
  await sleep(4500)
}

/** Back to the firing point facing the targets, still. */
async function home (spot) {
  bot.clearControlStates()
  await rcon(`execute as ${name} at @s run tp @s ${spot.x.toFixed(2)} ${spot.y} ${spot.z.toFixed(2)} 180 0`)
  await sleep(1200)
}

// ---------------------------------------------------------------- spread
async function spread (spot) {
  await arm('sv9')
  swapKey() // AUTO -> SEMI
  await sleep(500)
  const series = {}
  for (const mode of ['stand', 'walk', 'stand-ads', 'walk-ads']) {
    await home(spot)
    dropKey() // a full magazine for each series
    await sleep(3000)
    const stopAim = mode.endsWith('ads') ? aimWhile() : null
    await sleep(600)
    const from = logSize()
    // Strafe left and right, 2 shots per leg once up to pace (bloom decays fully between shots).
    for (let leg = 0; leg < 5; leg++) {
      const dir = leg % 2 ? 'right' : 'left'
      if (mode.startsWith('walk')) bot.setControlState(dir, true)
      await sleep(450)
      bot.swingArm()
      await sleep(450)
      bot.swingArm()
      await sleep(250)
      if (mode.startsWith('walk')) bot.setControlState(dir, false)
    }
    await sleep(600)
    if (stopAim) stopAim()
    const lines = shotLines(from)
    series[mode] = lines
    log(mode, 'shots', lines.length, 'spread avg', avg(lines.map(l => l.spread)).toFixed(3), 'speed avg', avg(lines.map(l => l.speed)).toFixed(3),
      'move avg', avg(lines.map(l => l.move)).toFixed(2), 'aiming', lines.filter(l => l.aim).length)
  }
  const s = m => avg(series[m].map(l => l.spread))
  check('spread: 10 shots logged in each series', Object.values(series).every(l => l.length >= 10), Object.entries(series).map(([k, v]) => k + '=' + v.length).join(' '))
  check('spread: standing shots measure no speed', series.stand.every(l => l.speed < 0.02), series.stand.map(l => l.speed).join(','))
  check('spread: strafing measures walking pace', avg(series.walk.map(l => l.speed)) > 0.15, avg(series.walk.map(l => l.speed)).toFixed(3))
  check('spread: aiming series were aimed', series['stand-ads'].every(l => l.aim) && series['walk-ads'].every(l => l.aim))
  check('spread: walking hip fire >= 1.4x standing', s('walk') >= 1.4 * s('stand'), `${s('walk').toFixed(3)} vs ${s('stand').toFixed(3)} = ${(s('walk') / s('stand')).toFixed(2)}x`)
  check('spread: walking ADS >= 1.8x standing ADS', s('walk-ads') >= 1.8 * s('stand-ads'), `${s('walk-ads').toFixed(3)} vs ${s('stand-ads').toFixed(3)} = ${(s('walk-ads') / s('stand-ads')).toFixed(2)}x`)
}

// ---------------------------------------------------------------- sprint
async function sprint (spot) {
  await arm('p18') // semi only, whatever ran before
  await home(spot)
  // mineflayer 4.39 sends the 1.21.11 sprint command with the old numeric id, which the server reads as
  // a horse jump; send the right one as well.
  const command = actionId => bot._client.write('entity_action', { entityId: bot.entity.id, actionId, jumpBoost: 0 })
  const start = async () => {
    bot.setControlState('forward', true)
    bot.setControlState('sprint', true)
    command('start_sprinting')
    await sleep(500)
  }
  const stop = () => {
    bot.setControlState('sprint', false)
    command('stop_sprinting')
    bot.setControlState('forward', false)
  }

  // Clicks while sprinting.
  await start()
  let from = logSize()
  shots = 0
  const barsFrom = bars.length
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(150) }
  stop()
  await sleep(300)
  check('sprint: clicks while sprinting fire nothing', shots === 0 && shotLines(from).length === 0, `${shots} shot sounds, ${shotLines(from).length} logged`)
  check('sprint: the player is told why', bars.slice(barsFrom).some(b => b.text.includes('冲刺时不能开火')), bars.slice(barsFrom).map(b => b.text).join(' | '))

  // A click right as the sprint ends still does nothing; a moment later the gun fires.
  await home(spot)
  await start()
  from = logSize()
  shots = 0
  stop()
  bot.swingArm()
  await sleep(150)
  check('sprint: a click right after the sprint fires nothing', shots === 0 && shotLines(from).length === 0, `${shots} shot sounds`)
  await sleep(600)
  shots = 0
  bot.swingArm()
  await sleep(400)
  check('sprint: a click a moment after the sprint fires', shots === 1, `${shots} shot sounds`)

  // A client that runs at sprint pace but never says it started sprinting: mineflayer's own sprint
  // command is the broken one, so leaving out the right one plays it. The server goes by the speed.
  await home(spot)
  bot.setControlState('forward', true)
  bot.setControlState('sprint', true)
  await sleep(500)
  from = logSize()
  shots = 0
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(150) }
  bot.setControlState('sprint', false)
  bot.setControlState('forward', false)
  await sleep(300)
  check('sprint: running at sprint pace without the sprint command fires nothing', shots === 0 && shotLines(from).length === 0,
    `${shots} shot sounds, ${shotLines(from).length} logged`)

  // Trigger held through the end of the sprint: one shot, once the gun is back up.
  await home(spot)
  await start()
  shots = 0
  const release = triggerWhile()
  await sleep(400)
  const held = shots
  let firedAt = null
  const stopAt = Date.now()
  stop()
  while (Date.now() - stopAt < 1500 && firedAt === null) {
    if (shots > held) firedAt = Date.now() - stopAt
    await sleep(10)
  }
  release()
  await sleep(300)
  check('sprint: a trigger held while sprinting does not fire', held === 0, `${held} shot sounds`)
  check('sprint: held through the end of the sprint it fires once the gun is up (150-700 ms)', firedAt !== null && firedAt >= 150 && firedAt <= 700,
    firedAt === null ? 'never fired' : firedAt + ' ms after the sprint ended')
}

// ---------------------------------------------------------------- recoil
/** Looks at this pitch (degrees, negative = up) without turning. */
const lookPitch = deg => bot.look(bot.entity.yaw, conv.fromNotchianPitch(deg), true)

/**
 * Fires `count` rounds on full auto from wherever the bot looks. `pull` is how much of the climb the bot
 * pulls back down itself while firing (0 none, 1 all of it). Returns pitches in degrees (negative = up).
 */
async function fire (count, pull, settleMs = 1000) {
  const start = pitchDeg()
  const from = logSize()
  shots = 0
  const release = triggerWhile()
  const began = Date.now()
  let peak = start
  let seen = start
  while (shots < count && Date.now() - began < 5000) {
    await sleep(50)
    // The modified client keeps telling the server it is still looking where it was.
    if (deaf) bot._client.write('look', { yaw: conv.toNotchianYaw(bot.entity.yaw), pitch: pitchDeg(), flags: { onGround: true, hasHorizontalCollision: false } })
    peak = Math.min(peak, pitchDeg())
    // Pull back down `pull` of whatever the gun kicked up since the last look.
    if (pull > 0) {
      seen = pitchDeg() + (seen - pitchDeg()) * pull
      await lookPitch(seen)
    }
  }
  release()
  const fired = shots
  const atRelease = pitchDeg()
  await sleep(settleMs)
  const settled = pitchDeg()
  const lines = shotLines(from)
  // Where the last round went: the reported crosshair raised by the bullets' own climb.
  const last = lines[lines.length - 1] || { pitch: 0, climb: 0 }
  const shotPitch = last.pitch - last.climb
  log(`${count} rounds pull=${pull}${deaf ? ' (ignoring the kick)' : ''}: ${fired} shots (${lines.length} logged), pitch start ${start.toFixed(2)} at release ${atRelease.toFixed(2)}`,
    `highest ${peak.toFixed(2)} ${settleMs} ms later ${settled.toFixed(2)}; last round climb ${last.climb.toFixed(2)} went at pitch ${shotPitch.toFixed(2)}`)
  return { start, atRelease, peak, settled, fired, lines, shotPitch }
}

/** One 30-round M4C spray from a level crosshair with a full magazine. */
async function spray (spot, pull) {
  await home(spot)
  dropKey()
  await sleep(3500)
  await lookPitch(0)
  await sleep(500)
  return fire(30, pull)
}

/**
 * Two 10-round sprays back to back: the first fully pulled down, then 0.3 s later the bot turns `turn`
 * degrees (negative = up) to another target and fires the second without pulling.
 */
async function pair (spot, turn) {
  await home(spot)
  dropKey()
  await sleep(3500)
  await lookPitch(0)
  await sleep(500)
  await fire(10, 1, 300)
  await lookPitch(pitchDeg() + turn)
  await sleep(100)
  return fire(10, 0, 1500)
}

async function recoil (spot) {
  await arm('m4c')
  const free = await spray(spot, 0)
  const climb = free.start - free.atRelease
  const back = free.settled - free.atRelease
  check('recoil: the spray climbed', free.fired >= 28 && climb > 5, `${free.fired} shots, ${climb.toFixed(2)} degrees`)
  check('recoil: a second after letting go at least half the climb came back', back >= 0.5 * climb,
    `${back.toFixed(2)} of ${climb.toFixed(2)} degrees (${(100 * back / climb).toFixed(0)}%)`)
  check('recoil: and not past the starting crosshair', free.settled <= free.start + 0.2, `${free.settled.toFixed(2)} vs ${free.start.toFixed(2)}`)

  // Pulling all of the climb down while firing: nothing more is pulled down afterwards.
  const full = await spray(spot, 1)
  check('recoil: a fully compensated spray is not pulled further down', full.settled <= full.start + 0.3 && full.settled >= full.atRelease - 0.3,
    `start ${full.start.toFixed(2)} at release ${full.atRelease.toFixed(2)} settled ${full.settled.toFixed(2)}`)
  // Pulling half of it down: most of the rest comes back, still not past where the spray began.
  const half = await spray(spot, 0.5)
  const left = half.start - half.atRelease
  check('recoil: a half compensated spray climbs about half as far', left > 0.3 * climb && left < 0.7 * climb, `${left.toFixed(2)} of ${climb.toFixed(2)} degrees`)
  check('recoil: and settles between there and the starting crosshair', half.settled <= half.start + 0.2 && half.settled - half.atRelease >= 0.5 * left,
    `start ${half.start.toFixed(2)} at release ${half.atRelease.toFixed(2)} settled ${half.settled.toFixed(2)}`)

  // A second spray at another target 0.3 s after a pulled-down one recovers toward its own crosshair:
  // not past it after turning up, and still some of the way after turning down.
  const up = await pair(spot, -8)
  const upClimb = up.start - up.atRelease
  check('recoil: turning up 8 degrees for a second spray, it settles above where that spray began',
    up.settled <= up.start + 0.2 && up.settled - up.atRelease >= 0.5 * upClimb,
    `second spray ${up.start.toFixed(2)} -> ${up.atRelease.toFixed(2)}, settled ${up.settled.toFixed(2)}`)
  const down = await pair(spot, 9)
  const downClimb = down.start - down.atRelease
  check('recoil: turning down 9 degrees for a second spray, that spray still comes back',
    down.settled <= down.start + 0.2 && down.settled - down.atRelease >= 0.5 * downClimb,
    `second spray ${down.start.toFixed(2)} -> ${down.atRelease.toFixed(2)}, settled ${down.settled.toFixed(2)}`)

  // Part of the climb lifts only the bullets, capped, and is gone again a second after the spray.
  const climbs = free.lines.map(l => l.climb)
  check('recoil: the first round goes where the crosshair is', free.lines.length > 0 && free.lines[0].climb === 0, String(climbs[0]))
  check('recoil: the bullets climb above the crosshair, gently (1 to 2.5 degrees after 30 rounds)', Math.max(...climbs) > 1 && Math.max(...climbs) <= 2.5,
    climbs.map(c => c.toFixed(2)).join(' '))
  check('recoil: and still climb over the last 10 rounds', climbs.length >= 30 && climbs[29] - climbs[19] >= 0.15,
    climbs.length >= 30 ? (climbs[29] - climbs[19]).toFixed(2) : 'too few shots')
  // A client that ignores the camera kick still sees its rounds climb.
  deaf = true
  const cheat = await spray(spot, 0)
  deaf = false
  check('recoil: ignoring the camera kick does not keep the rounds level', Math.abs(cheat.atRelease - cheat.start) < 0.5 && cheat.shotPitch <= cheat.start - 1.0,
    `camera ${cheat.start.toFixed(2)} -> ${cheat.atRelease.toFixed(2)}, last round at pitch ${cheat.shotPitch.toFixed(2)}`)
  // And it all comes back: a single shot after the reload goes where the crosshair is.
  await sleep(2500)
  const from = logSize()
  bot.swingArm()
  await sleep(300)
  const single = shotLines(from)
  check('recoil: a second after the spray the rounds are back on the crosshair', single.length >= 1 && single[0].climb === 0, single.map(l => l.climb).join(','))
}

// ---------------------------------------------------------------- impact
const IMPACT = new RegExp(`\\[debug\\] ${name} impact eye=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+) hit=(?:(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)|none)`)
/** Impacts logged since `from`, each with the climb of the shot it belongs to. */
function impacts (from) {
  const out = []
  let climb = 0
  for (const line of logSince(from)) {
    const shot = line.match(new RegExp(`\\[debug\\] ${name} shot .* climb=([0-9.]+)`))
    if (shot) { climb = Number(shot[1]); continue }
    const m = line.match(IMPACT)
    if (m) out.push({ eye: m.slice(1, 4).map(Number), hit: m[4] === undefined ? null : m.slice(4, 7).map(Number), climb })
  }
  return out
}
const deg = rad => rad * 180 / Math.PI
const mean = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN

/**
 * Where rounds land on a wall 30 and 100 m away while the camera stays exactly on the crosshair, as
 * perfect compensation (or a client ignoring the kick) keeps it: the bot ignores the kick, standing on
 * a block above the range roof, facing north. Offsets are measured from the server's impact log
 * against the crosshair's point on the wall, in degrees up.
 */
async function impact (spot) {
  const dim = 'minecraft:craftov_range'
  const run = cmd => rcon(`execute in ${dim} run ${cmd}`)
  const x = Math.floor(spot.x)
  const z = Math.floor(spot.z)
  await run(`setblock ${x} 79 ${z} minecraft:stone`)
  const table = []
  for (const dist of [30, 100]) {
    const wall = z - dist
    await run(`fill ${x - 15} 70 ${wall} ${x + 15} 95 ${wall} minecraft:stone`)
    for (const [gun, how] of [['m4c', 'auto, aimed'], ['m4c', 'auto, hip'], ['dm14', 'semi, scoped, as fast as it cycles']]) {
      await arm(gun)
      await rcon(`execute as ${name} at @s run tp @s ${spot.x.toFixed(2)} 80 ${spot.z.toFixed(2)} 180 0`)
      await sleep(1200)
      dropKey()
      await sleep(4000)
      const stopAim = how.includes('hip') ? null : aimWhile()
      await sleep(800)
      deaf = true
      const from = logSize()
      const hold = () => bot._client.write('look', { yaw: conv.toNotchianYaw(bot.entity.yaw), pitch: 0, flags: { onGround: true, hasHorizontalCollision: false } })
      if (how.startsWith('auto')) {
        shots = 0
        const release = triggerWhile()
        const began = Date.now()
        while (shots < 30 && Date.now() - began < 5000) { hold(); await sleep(50) }
        release()
      } else {
        for (let i = 0; i < 10; i++) {
          bot.swingArm()
          for (let t = 0; t < 5; t++) { hold(); await sleep(50) }
        }
      }
      await sleep(600)
      deaf = false
      if (stopAim) stopAim()
      const hits = impacts(from).filter(i => i.hit && Math.abs(i.hit[2] - (wall + 1)) < 0.05)
      const up = hits.map(i => deg(Math.atan2(i.hit[1] - i.eye[1], i.eye[2] - i.hit[2])))
      const climbs = hits.map(i => i.climb)
      const band = (a, b) => mean(up.slice(a - 1, b))
      const row = { dist, gun, how, rounds: hits.length, up, climbs }
      table.push(row)
      const span = how.startsWith('auto')
        ? `rounds 1-5 ${band(1, 5).toFixed(2)}, 6-10 ${band(6, 10).toFixed(2)}, 11-20 ${band(11, 20).toFixed(2)}, 21-30 ${band(21, 30).toFixed(2)} deg`
        : `all ${mean(up).toFixed(3)} deg, spread ${Math.max(...up.map(Math.abs)).toFixed(3)} at most`
      const metres = how.startsWith('auto') ? band(21, 30) : mean(up)
      log(`impact ${dist} m ${gun} ${how}: ${hits.length} on the wall; mean rise ${span}; climb at round 10 ${(climbs[9] || 0).toFixed(2)}, last ${(climbs[climbs.length - 1] || 0).toFixed(2)};`,
        `${how.startsWith('auto') ? 'rounds 21-30' : 'all rounds'} land ${(dist * Math.tan(metres * Math.PI / 180)).toFixed(2)} m above the crosshair point`)
    }
    await run(`fill ${x - 15} 70 ${wall} ${x + 15} 95 ${wall} minecraft:air`)
  }
  await run(`setblock ${x} 79 ${z} minecraft:air`)
  for (const row of table) {
    if (row.how.startsWith('semi')) {
      check(`impact: ${row.dist} m semi-auto DM14 follow-ups are not lifted`, row.rounds >= 8 && row.climbs.every(c => c === 0) && Math.abs(mean(row.up)) <= 0.1,
        `${row.rounds} rounds, climbs ${row.climbs.join(',')}, mean rise ${mean(row.up).toFixed(3)} deg`)
    } else {
      const c = row.climbs
      check(`impact: ${row.dist} m M4C ${row.how}: lifted gently and still creeping up at the end`,
        row.rounds >= 28 && c[0] === 0 && c[9] >= 0.3 && c[9] <= 1.2 && c[29] <= 2.5 && c[29] - c[19] >= 0.15,
        `${row.rounds} rounds, climb at 1/10/20/30: ${[0, 9, 19, 29].map(i => (c[i] || 0).toFixed(2)).join('/')}`)
    }
  }
}

// ---------------------------------------------------------------- ai
const SOLDIERS = ['zombie', 'husk', 'drowned']
async function ai () {
  const hits = []
  bot._client.on('damage_event', p => {
    const by = bot.entities[p.sourceCauseId - 1]
    if (bot.entity && p.entityId === bot.entity.id && by && SOLDIERS.includes(by.name)) hits.push(Date.now())
  })
  // Soldiers' reports reach us as positional sounds; every soldier in the raid is shooting at us.
  const reports = []
  bot.on('soundEffectHeard', sound => { if (/craftov:gun\.[a-z0-9_]+\.(near|mid|far|muff)$/.test(String(sound))) reports.push(Date.now()) })
  if ((await rcon(`data get entity ${name} Dimension`)).includes('craftov_range')) {
    bot.clearControlStates()
    bot.setQuickBarSlot(8) // 离开靶场
    await sleep(300)
    bot.activateItem()
    await sleep(3000)
  }
  const before = worldChanges
  bot.chat('/cv queue proving NORMAL SOLO')
  const end = Date.now() + 90000
  while (Date.now() < end && !(worldChanges > before && /passed/i.test(await rcon(`execute if entity @a[name=${name},gamemode=adventure]`)))) await sleep(500)
  if (worldChanges <= before) throw new Error('not inserted into the proving ground')
  await sleep(3000)
  await rcon(`effect give ${name} resistance 600 2 true`)
  const heal = setInterval(() => rcon(`effect give ${name} instant_health 1 3 true`).catch(() => {}), 1500)
  // In the open inside the AI arena (see strobe.js), with soldiers in sight.
  await rcon(`execute as ${name} at @s run tp @s 36.5 65 30.5 180 0`)
  await sleep(6000)
  // Short alternating phases, so both conditions see the same soldiers at the same distances.
  const tally = { stand: { hits: 0, shots: 0 }, strafe: { hits: 0, shots: 0 } }
  for (let i = 0; i < 16; i++) {
    const phase = i % 2 ? 'strafe' : 'stand'
    await rcon(`execute as ${name} at @s run tp @s 36.5 65 30.5 180 0`)
    await sleep(300)
    const h0 = hits.length
    const r0 = reports.length
    const until = Date.now() + 7000
    let leg = 0
    while (Date.now() < until) {
      if (phase === 'strafe') {
        const dir = leg++ % 2 ? 'right' : 'left'
        bot.setControlState(dir, true)
        await sleep(700)
        bot.setControlState(dir, false)
      } else await sleep(700)
    }
    bot.clearControlStates()
    tally[phase].hits += hits.length - h0
    tally[phase].shots += reports.length - r0
  }
  clearInterval(heal)
  const rate = t => t.shots ? t.hits / t.shots : 0
  log('hit rate standing', rate(tally.stand).toFixed(3), tally.stand, 'strafing', rate(tally.strafe).toFixed(3), tally.strafe)
  check('ai: soldiers shot at us in both conditions', tally.stand.shots >= 20 && tally.strafe.shots >= 20, JSON.stringify(tally))
  check('ai: soldiers hit a strafing player clearly less often', rate(tally.strafe) < 0.85 * rate(tally.stand),
    `${rate(tally.strafe).toFixed(3)} vs ${rate(tally.stand).toFixed(3)}`)
  await rcon('cv end').catch(() => {})
}

// ---------------------------------------------------------------- main
;(async () => {
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  let spot = null
  if (scenarios.some(s => s !== 'ai')) {
    await enterRange()
    await sleep(500)
    spot = bot.entity.position.clone()
    log('on the range at', spot.toString())
  }
  for (const scenario of scenarios) {
    log('---', scenario)
    if (scenario === 'spread') await spread(spot)
    else if (scenario === 'sprint') await sprint(spot)
    else if (scenario === 'recoil') await recoil(spot)
    else if (scenario === 'impact') await impact(spot)
    else if (scenario === 'ai') await ai()
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
