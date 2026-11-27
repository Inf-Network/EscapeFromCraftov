// Moving accuracy (issue #32), run-and-gun (#105) and recoil return, on the private range.
//
//   spread   SV9 on semi, 10 shots each standing, strafing, strafing while aiming, sprinting and a
//            tick or two into a stop: the per-shot spread in the server's debug log must grow with the
//            real (position-measured) speed, walking hip fire at least 1.4x standing hip fire, walking
//            ADS at least 1.8x standing ADS, sprinting hip fire at least 1.3x walking (sprint > walk >
//            stand); a steady strafe never reads as stopping, a shot right after letting go does, and
//            costs less than half the walking part. Shots near the top of a standing jump read as
//            airborne (the server's own check under the feet) with 2.5x the standing cone, no other.
//   sprint   sprinting along the firing line: P18 clicks fire, every shot with the server still
//            sprinting at sprint pace, and no "cannot fire" hint; M4C held 1.2 s the way the vanilla
//            client holds left mouse (digging the capture cells, which move as the player runs) fires
//            full auto with the sprint cone while the sprint goes on; running at sprint pace without
//            the sprint command fires with the sprint cone as well. Right mouse on a sprint drops the
//            food level to 6 (the vanilla client stops sprinting there), the sights come up 0.2-0.7 s
//            after the press, and the food level is back at 20 once right mouse is let go. The newer
//            press wins: the sprint key pressed with forward while aiming (right mouse still held) drops
//            the sights and puts the food level back at 20 at once, the sprint that follows fires from
//            the hip and right mouse held on does not stop it, and once it is over the sights come up
//            again; the sprint key without forward changes nothing. A client that runs at sprint pace
//            with the sights up (ignoring the food level and the aiming slowdown, and never saying it
//            sprints) loses them: its shots go from the hip with the sprint cone.
//   held     Modified clients (issue #155), P18 clicks on a sprint (plain clicks first, for their cone),
//   defer    the bot sending tick_end after every tick as a vanilla client does. held: two ticks
//   micro    without movement before each click (their tick ends sent), then the catch-up at once;
//   mimic    defer: then four ticks at a sprint still two ticks behind, then the rest; micro: steps of
//   piecewise 0.011 instead of none, clicks among them, then the dump; mimic: a stop acted out
//            (steps 0.55 and 0.3 of a sprint's), then the catch-up; piecewise: half of it repaid
//            with a click between. In each the server logs the catch-up as held back, and every
//            shot judged after it (logged careful) has the plain sprint cone (0.9x at least) and
//            does not read as stopping; shots before it are only logged (the server fires a shot
//            when it hears it).
//   stall    two ticks of nothing at all (movement and tick ends kept), a click in the second, then
//            the burst: no shot reads as stopping, all have the sprint cone, nothing is logged as held
//            back, and a real stop afterwards (a strafe let go, the shot 100 ms later) reads as
//            stopping.
//   fallback the same bot sending no tick ends (measured per server tick, as before): a real stop
//            reads as stopping, two ticks without any movement before each click are not logged
//            (they still buy the stop cone there: the fallback's gap), micro-steps then a dump are
//            logged and the shots after it careful.
//            spread, sprint, stance, recoil and aimed also check that the server never logs the
//            bot's movement as held back (but in sprint's two modified-client parts).
//   stance   a spectator next to the runway sees the M4C carried low while standing (#3), low on a
//            sprint, up while firing on the sprint, low half a second after the last shot, still low
//            once the sprint is over; a short burst standing puts it up, and it is low again 2 s after
//            the last shot.
//   recoil   M4C on full auto, 30 rounds without touching the mouse: a second after letting go the
//            camera must have come back down at least half of the climb, and not past where it
//            started; pulling all or half of the climb down while firing is not pulled further. A
//            second spray 0.3 s after a pulled-down one, turned 8 degrees up or 9 down to another
//            target, recovers toward its own crosshair and not past it. The bullets' own climb starts at 0, stays within 3 degrees, lifts the rounds of a
//            client that ignores the camera kick, and is gone a moment after the spray.
//   aimed    the kick with the sights up (issue #118): M4C full auto, 10 rounds from the hip and aimed
//            through the red dot, the 4x and the 8x without touching the mouse. Per round the view
//            climbs (the pitch the server logs at each shot) about the M4C's ads-recoil (0.45) of the
//            hip climb through the red dot (between 0.3 and 0.6 of it), and as many degrees through
//            the 4x and the 8x (each 0.8-1.25 of the red dot's: a scope does not soften the kick);
//            every aimed shot is logged as aimed. One AWR shot through its 8x kicks the view 1.5 to
//            2.5 degrees (its ads-recoil 0.4 of 5), and with the sights still up and the mouse left
//            alone, 1.5 s later the view has come back 75-95% of the way (its recoil-return, 0.85).
//   impact   (not in the default run) where rounds land on a wall 30 and 100 m away with the camera
//            held exactly on the crosshair: an M4C magazine aimed and from the hip, and DM14 shots as
//            fast as it cycles. Semi-auto rounds must not be lifted; automatic ones only a little,
//            still creeping up at the end of the magazine.
//   ai       (not in the default run, about 3 minutes) a solo raid on the proving ground: hits
//            per soldier shot while standing still versus strafing, in alternating 7 s phases. The
//            overall rates swing with where the soldiers stand (seen 19% to 44% fewer hits when
//            strafing): within 10 m nearly every round hits either way. So the check reads the
//            server's log of soldier shots: against a strafing bot the cone must be about 1.4x as
//            wide, and from 10 to 30 m the hit rate under 0.75x the standing one (skipped when the
//            standing bot took fewer than 20 hits there: too few to tell).
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node accuracy.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (the spread is read from the log),
// and for `ai` test mode (test.enabled: true) so the bot may queue for the proving ground.
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
//
// Server config (tools/bot/suite.js sets it for its run): debug true (the spread is read from the
// log); test.enabled true (the bots queue for the proving ground, a test map) for ai, and there no
// rival operators (ai.operator-chance [0, 0, 0]) or raid events (events.per-raid 0), whose shots and
// barrages would count as the soldiers' hits. The suite runs impact and ai as entries of their own.
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
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const name = 'Acc' + Math.floor(10000 + Math.random() * 90000)
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['spread', 'sprint', 'held', 'defer', 'micro', 'mimic', 'piecewise', 'stall', 'fallback', 'stance', 'recoil', 'aimed']
/** Scenarios that play an honest client throughout, but for the spans they mark in cheatSpans: the server must never log their movement as held back. */
const HONEST = ['spread', 'sprint', 'stance', 'recoil', 'aimed']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
// mineflayer moves the bot from setInterval(doPhysics, 50), started on login. Each call comes a little
// late on a busy machine (51 to 56 ms apart), and once the delay adds up to a tick it runs two at once.
// The server then gets a tick with no movement that the ticks around it do not make up for (steps x,
// x, 0, x), which its speed tracker reads as the player stopping: the run drops out for a few ticks and
// a shot in them gets neither the run nor its cone (issue #144: 6 of 30 three-shot runs with the server
// and the bot on two busy cores, none of 30 with this). A vanilla client's ticks keep to the clock, and
// so does this loop: each call is due 50 ms after the last one was due, half a tick into it, so a late
// call still moves the bot once. This listener runs before mineflayer's (its plugins load later).
// A call later than that (the bot process held up) still sends two ticks at once: those are noted, and
// each scenario logs them, so a disturbed run can be told from the server's doing.
const lateMoves = []
let hooked = false
// A vanilla 1.21.2+ client ends every tick with a tick_end packet, right after that tick's movement,
// even when it runs several ticks in one frame; the server measures movement by them (MotionTracker).
// mineflayer announces a physics tick (physicsTick) just before it sends that tick's movement, so the
// end owed for the tick before goes out there, and the last one once the clock's call is over: when a
// late call runs two ticks, their packets still go move, end, move, end. `tickEnds` false plays a
// client that sends none (the server falls back to its own ticks).
let tickEnds = true
let endOwed = false
const endTick = () => { if (endOwed && tickEnds && bot._client.state === 'play') bot._client.write('tick_end', {}); endOwed = false }
bot.on('physicsTick', () => { endTick(); endOwed = true })
bot.once('login', () => {
  const plain = global.setInterval
  global.setInterval = (fn, ms, ...rest) => {
    if (fn.name !== 'doPhysics') return plain(fn, ms, ...rest)
    global.setInterval = plain
    let due = performance.now() + 75
    let ended = false
    const step = () => {
      if (ended) return
      const late = performance.now() - due
      if (late > 25) lateMoves.push(Math.round(late))
      fn()
      endTick()
      due += 50
      setTimeout(step, Math.max(0, due - performance.now()))
    }
    setTimeout(step, 75)
    bot.once('end', () => { ended = true })
    hooked = true
    return { ref () {}, unref () {} } // mineflayer only keeps it to clear it when the bot ends
  }
  setImmediate(() => {
    global.setInterval = plain
    // A mineflayer that names its physics loop otherwise, or starts it another way, would run on
    // its own timer again with nothing late logged, which reads like a clean run: say so.
    if (!hooked) log('WARN: the steady physics clock did not take: mineflayer moves the bot on its own timer')
  })
})
// mineflayer files attribute updates under the packet's key (generic.movement_speed), but its physics
// reads minecraft:movement_speed: without this the bot ignores the server slowing it (aiming does, on
// the range too), walks faster than it may, and the server takes the strafe for running. The server's
// own sprint modifier (minecraft:sprinting) is left out: mineflayer adds its own under another id, and
// with both the bot sprinted at 0.36 blocks a tick, faster than a vanilla client (0.28) can.
// `ignoreSlow` plays a modified client that keeps its full pace whatever the server says.
let ignoreSlow = false
bot.on('entityAttributes', e => {
  const speed = e === bot.entity && e.attributes['generic.movement_speed']
  if (speed && !ignoreSlow) {
    e.attributes['minecraft:movement_speed'] = { ...speed, modifiers: (speed.modifiers || []).filter(m => !String(m.uuid).includes('sprinting')) }
  }
})
let worldChanges = 0
bot.on('respawn', () => worldChanges++)
const bars = []
bot._client.on('action_bar', p => bars.push({ at: Date.now(), text: JSON.stringify(p) }))
// The shooter hears its own first-person report: gun.<id>.fp, or gun.<id>.x<n>.fp holding n rounds.
let shots = 0
/** When the bot heard its gun go to aim-down-sights (WeaponService.setAds plays gun.ads). */
const adsHeard = []
const shotSound = p => {
  const sound = JSON.stringify(p.sound || p.soundId || '')
  const m = sound.match(/gun\.[a-z0-9_]+?(?:\.x(\d))?\.fp/)
  if (m) shots += m[1] ? Number(m[1]) : 1
  if (/gun\.ads\b/.test(sound)) adsHeard.push(Date.now())
}
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
  // The server marks the gun in hand while it reloads (ItemFactory.setHandMark: damage 2, for the
  // reload frames) and takes the mark off once the magazine is in: when each reload ended, by its word.
  bot.inventory.on('updateSlot', (slot, before, after) => {
    if (slot === bot.quickBarSlot + 36 && handMark(before) === RELOAD_MARK && after && itemId(after) === itemId(before) && handMark(after) !== RELOAD_MARK) {
      reloadsDone.push(Date.now())
    }
  })
})
const RELOAD_MARK = 2
const handMark = item => { const d = item && item.componentMap && item.componentMap.get('damage'); return d ? d.data : 0 }
const reloadsDone = []
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
    if (!m) continue
    const flag = key => (line.match(new RegExp(` ${key}=(true|false)`)) || [])[1] === 'true'
    out.push({ spread: Number(m[1]), speed: Number(m[2]), move: Number(m[3]), aim: m[4] === 'true', pitch: Number(m[5]), climb: Number(m[6]),
      sprint: flag('sprint'), run: flag('run'), stop: flag('stop'), ground: flag('ground'), held: flag('held') })
  }
  return out
}
/**
 * Our shots logged since `from`, once there are `count` of them or `ms` have passed: the server logs a
 * shot when it fires it, which on a busy server can be a while after the bot pulled the trigger.
 */
async function loggedShots (from, count, ms = 5000) {
  const end = Date.now() + ms
  let lines = shotLines(from)
  while (lines.length < count && Date.now() < end) {
    await sleep(50)
    lines = shotLines(from)
  }
  return lines
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

/**
 * Gives the gun, holds it and fills its magazine. Guns go to the two weapon slots only (hotbar 0 and
 * 1, then a backpack: CarryService.route), so both are emptied first for a third gun to land.
 */
async function arm (id, optic = null) {
  for (const slot of [0, 1]) await rcon(`item replace entity ${name} hotbar.${slot} with minecraft:air`)
  await sleep(200)
  await rcon(`cv give ${name} ${id}` + (optic ? ` 1 ${optic}` : ''))
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

/**
 * To the east end of the firing line, facing west along it: flat floor for a 1.2 s sprint (toward the
 * targets a sprint runs into the slab at the edge of the range and goes up it).
 */
async function runway (spot) {
  bot.clearControlStates()
  await rcon(`execute as ${name} at @s run tp @s ${(spot.x + 5).toFixed(2)} ${spot.y} ${(spot.z - 1).toFixed(2)} 90 0`)
  await sleep(1200)
}

// ---------------------------------------------------------------- spread
async function spread (spot) {
  await arm('sv9')
  swapKey() // AUTO -> SEMI
  await sleep(500)
  const series = {}
  for (const mode of ['stand', 'walk', 'stand-ads', 'walk-ads', 'sprint', 'stop', 'jump']) {
    await home(spot)
    dropKey() // a full magazine for each series
    await sleep(3000)
    const stopAim = mode.endsWith('ads') ? aimWhile() : null
    await sleep(600)
    const from = logSize()
    if (mode === 'sprint') {
      // Sprint toward the targets, 2 shots per run once up to pace, and back to the firing point.
      for (let leg = 0; leg < 5; leg++) {
        await runway(spot)
        await startSprint()
        bot.swingArm()
        await sleep(350)
        bot.swingArm()
        await sleep(100)
        stopSprint()
      }
    } else if (mode === 'jump') {
      // Standing jumps, a shot near the top of each.
      for (let leg = 0; leg < 10; leg++) {
        bot.setControlState('jump', true)
        await sleep(150)
        bot.setControlState('jump', false)
        bot.swingArm()
        await sleep(1000)
      }
    } else if (mode === 'stop') {
      // Strafe to full pace, let go and fire as the player slows down, two ticks into the stop (on the
      // first the measured speed has not moved yet: one short step reads the same as a late packet).
      for (let leg = 0; leg < 10; leg++) {
        const dir = leg % 2 ? 'right' : 'left'
        bot.setControlState(dir, true)
        await sleep(600)
        bot.setControlState(dir, false)
        await sleep(100)
        bot.swingArm()
        await sleep(600)
      }
    }
    // Strafe left and right, 2 shots per leg once up to pace (bloom decays fully between shots).
    for (let leg = 0; leg < (['sprint', 'stop', 'jump'].includes(mode) ? 0 : 5); leg++) {
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
      'move avg', avg(lines.map(l => l.move)).toFixed(2), 'aiming', lines.filter(l => l.aim).length,
      'sprinting', lines.filter(l => l.sprint).length, 'stopping', lines.filter(l => l.stop).length)
  }
  const s = m => avg(series[m].map(l => l.spread))
  check('spread: 10 shots logged in each series', Object.values(series).every(l => l.length >= 10), Object.entries(series).map(([k, v]) => k + '=' + v.length).join(' '))
  check('spread: standing shots measure no speed', series.stand.every(l => l.speed < 0.02), series.stand.map(l => l.speed).join(','))
  check('spread: strafing measures walking pace', avg(series.walk.map(l => l.speed)) > 0.15, avg(series.walk.map(l => l.speed)).toFixed(3))
  check('spread: aiming series were aimed', series['stand-ads'].every(l => l.aim) && series['walk-ads'].every(l => l.aim))
  check('spread: walking hip fire >= 1.4x standing', s('walk') >= 1.4 * s('stand'), `${s('walk').toFixed(3)} vs ${s('stand').toFixed(3)} = ${(s('walk') / s('stand')).toFixed(2)}x`)
  check('spread: walking ADS >= 1.8x standing ADS', s('walk-ads') >= 1.8 * s('stand-ads'), `${s('walk-ads').toFixed(3)} vs ${s('stand-ads').toFixed(3)} = ${(s('walk-ads') / s('stand-ads')).toFixed(2)}x`)
  check('spread: no shot on the ground reads as airborne', ['stand', 'walk', 'stand-ads', 'walk-ads', 'sprint', 'stop'].every(m => series[m].every(l => l.ground)))
  check('spread: shots near the top of a jump do, with the jump cone (2.5x standing)', series.jump.every(l => !l.ground) && s('jump') >= 2.2 * s('stand'),
    `${series.jump.filter(l => !l.ground).length}/${series.jump.length} airborne, ${s('jump').toFixed(3)} vs ${s('stand').toFixed(3)}`)
  check('spread: a steady strafe never reads as stopping', series.walk.every(l => !l.stop) && series['walk-ads'].every(l => !l.stop))
  check('spread: the sprint series was sprinting, at sprint pace', series.sprint.every(l => l.sprint && l.speed > 0.25),
    series.sprint.map(l => `${l.sprint ? 'S' : '-'}${l.speed.toFixed(2)}`).join(' '))
  check('spread: sprinting hip fire >= 1.3x walking (SV9: x1.4)', s('sprint') >= 1.3 * s('walk'), `${s('sprint').toFixed(3)} vs ${s('walk').toFixed(3)} = ${(s('sprint') / s('walk')).toFixed(2)}x`)
  check('spread: so sprint > walk > stand', s('sprint') > s('walk') && s('walk') > s('stand'),
    `${s('sprint').toFixed(2)} > ${s('walk').toFixed(2)} > ${s('stand').toFixed(2)}`)
  // A shot a tick or two into a stop: the measured speed still reads part of the walk, of which only
  // weapons.spread.stop (0.5) counts while it falls.
  const stops = series.stop.filter(l => l.stop)
  const stopPart = avg(stops.map(l => l.spread - s('stand')))
  const walkPart = avg(series.walk.map(l => l.spread - s('stand')))
  check('spread: most shots fired right after letting go read as stopping', stops.length >= series.stop.length / 2, `${stops.length}/${series.stop.length}`)
  check('spread: and cost less than half of the walking part', stops.length > 0 && stopPart < 0.5 * walkPart,
    `${stopPart.toFixed(3)} vs ${walkPart.toFixed(3)} degrees over standing; moves ${stops.map(l => l.move.toFixed(2)).join(' ')}`)
}

// ---------------------------------------------------------------- sprint
// mineflayer 4.39 sends the 1.21.11 sprint command with the old numeric id, which the server reads as
// a horse jump; send the right one as well.
const command = actionId => bot._client.write('entity_action', { entityId: bot.entity.id, actionId, jumpBoost: 0 })
/** The movement keys held, as the vanilla client reports them (mineflayer sends none of its own but sneak). */
const keys = (held = {}) => bot._client.write('player_input', { inputs: held })
/** Waits up to `ms` for `test` to hold, polling; the milliseconds it took, or null. */
async function within (ms, test) {
  const from = Date.now()
  while (Date.now() - from < ms) {
    if (test()) return Date.now() - from
    await sleep(10)
  }
  return test() ? Date.now() - from : null
}
/** Sprints forward, as a vanilla client does with W and the sprint key (`flag` false: without saying so). */
async function startSprint (flag = true) {
  bot.setControlState('forward', true)
  bot.setControlState('sprint', true)
  if (flag) command('start_sprinting')
  await sleep(600)
}
function stopSprint (flag = true) {
  bot.setControlState('sprint', false)
  if (flag) command('stop_sprinting')
  bot.setControlState('forward', false)
}

let digSequence = 1
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell, face: 1, sequence: digSequence++ })
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
/**
 * The capture cell the crosshair meets first within block reach, or null. The cells sit three blocks
 * ahead, so the ray enters one from outside, where its outline's face plate is (tools/bot/trigger.js
 * has the full shell for an eye inside a cell).
 */
function crosshairCell () {
  const vein = bot.registry.blocksByName.sculk_vein.id
  const o = bot.entity.position.offset(0, 1.62, 0)
  const yaw = conv.toNotchianYaw(bot.entity.yaw) * Math.PI / 180
  const pitch = conv.toNotchianPitch(bot.entity.pitch) * Math.PI / 180
  const d = { x: -Math.sin(yaw) * Math.cos(pitch), y: -Math.sin(pitch), z: Math.cos(yaw) * Math.cos(pitch) }
  let best = null
  let bestT = 4.5
  for (const c of bot.findBlocks({ matching: vein, maxDistance: 6, count: 64 })) {
    const t = rayBox(o, d, c, c.offset(1, 1, 1))
    if (t !== null && t < bestT) { best = c; bestT = t }
  }
  return best
}
/**
 * Holds left mouse for `ms` the way the vanilla client does: a dig start on the capture cell under the
 * crosshair, a swing every tick, an abort and a new start when the crosshair moves to another cell (it
 * does as the player runs), an abort at the end. Returns the ticks no cell was under the crosshair.
 */
async function holdTrigger (ms) {
  let digging = null
  let misses = 0
  const step = () => {
    const cell = crosshairCell()
    if (cell) {
      if (!digging || !digging.equals(cell)) {
        if (digging) dig(1, digging)
        dig(0, cell)
        digging = cell
      }
      bot._client.write('arm_animation', { hand: 0 })
    } else {
      misses++
      if (digging) dig(1, digging)
      digging = null
    }
  }
  step()
  const until = Date.now() + ms
  while (Date.now() < until) {
    await sleep(50)
    step()
  }
  if (digging) dig(1, digging)
  return misses
}

async function sprint (spot) {
  const horizontal = () => { const v = bot.entity.position; return Math.hypot(v.x - spot.x - 5, v.z - spot.z + 1) }

  // Semi-auto: clicks while sprinting fire, and the sprint goes on.
  await arm('p18')
  await runway(spot)
  await startSprint()
  let from = logSize()
  shots = 0
  const barsFrom = bars.length
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(200) }
  stopSprint()
  const p18 = await loggedShots(from, 3)
  await within(5000, () => shots >= 3)
  await sleep(300)
  check('sprint: three clicks while sprinting fire three rounds', shots === 3 && p18.length === 3, `${shots} shot sounds, ${p18.length} logged`)
  check('sprint: the server still has the player sprinting at every shot', p18.length > 0 && p18.every(l => l.sprint),
    p18.map(l => l.sprint).join(','))
  check('sprint: and running at sprint pace (over 0.25 blocks a tick)', p18.length > 0 && p18.every(l => l.speed > 0.25), p18.map(l => l.speed).join(','))
  check('sprint: no "cannot fire while sprinting" hint', !bars.slice(barsFrom).some(b => b.text.includes('冲刺时不能开火')))

  // Full auto, the trigger held the vanilla way (digging the capture cells, which move as the player runs).
  await arm('m4c')
  await runway(spot)
  await startSprint()
  from = logSize()
  shots = 0
  const before = horizontal()
  const began = Date.now()
  const misses = await holdTrigger(1200)
  const ran = (horizontal() - before) / ((Date.now() - began) / 50)
  const m4c = shotLines(from)
  stopSprint()
  await sleep(400)
  log(`M4C held 1.2 s on a sprint: ${shots} rounds heard, ${m4c.length} logged, ${ran.toFixed(3)} blocks a tick, ${misses} ticks with no capture cell under the crosshair`)
  check('sprint: a trigger held on a sprint fires full auto (at least 12 rounds in 1.2 s)', m4c.length >= 12, `${m4c.length} logged`)
  check('sprint: the capture cells stay under the crosshair while running', misses <= 1, `${misses} ticks without one`)
  check('sprint: the sprint goes on through the spray', m4c.length > 0 && m4c.every(l => l.sprint) && ran > 0.25,
    `sprint flags ${m4c.filter(l => l.sprint).length}/${m4c.length}, ${ran.toFixed(3)} blocks a tick`)
  check('sprint: every round of it has the sprint cone', m4c.length > 0 && m4c.every(l => !l.aim && l.spread > 5),
    m4c.map(l => l.spread.toFixed(1)).join(' '))

  // Running at sprint pace without ever sending the sprint command (a modified client): it fires too,
  // with the sprint cone, since the server goes by the measured speed.
  await arm('p18')
  await runway(spot)
  const slyFrom = logSize()
  await startSprint(false)
  from = logSize()
  shots = 0
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(200) }
  stopSprint(false)
  const sly = await loggedShots(from, 3)
  await sleep(300)
  cheatSpans.push([slyFrom, logSize()])
  check('sprint: running at sprint pace without the sprint command fires, with the sprint cone',
    sly.length === 3 && sly.every(l => !l.sprint && l.run && l.spread > 4.5), sly.map(l => `${l.spread.toFixed(2)} run=${l.run}`).join(', ') || 'no shot logged')

  // Right mouse on a sprint ends it: the food level drops to 6 (where the vanilla client cannot sprint),
  // the sights come up a moment after the sprint is over, and letting go lets the sprint start again.
  await arm('m4c')
  await runway(spot)
  await startSprint()
  adsHeard.length = 0
  const pressed = Date.now()
  const stopAim = aimWhile()
  let food = null
  let stoppedAt = null
  while (Date.now() - pressed < 1500 && !adsHeard.length) {
    if (food === null && bot.food <= 6) {
      food = Date.now() - pressed
      stopSprint() // what the vanilla client does at food 6: it stops sprinting
      stoppedAt = Date.now()
    }
    await sleep(10)
  }
  const aimedAt = adsHeard.length ? adsHeard[0] - pressed : null
  await sleep(300)
  const stillLow = bot.food <= 6
  stopAim()
  await sleep(800)
  check('sprint: right mouse on a sprint holds the food level at 6, so the sprint ends', food !== null && food < 400,
    food === null ? `food ${bot.food}` : `${food} ms after the press`)
  check('sprint: the sights come up once the sprint is over (0.2 to 0.7 s after the press)', aimedAt !== null && aimedAt >= 200 && aimedAt <= 700,
    aimedAt === null ? 'never aimed' : `${aimedAt} ms after the press, ${stoppedAt ? adsHeard[0] - stoppedAt : '?'} ms after the sprint stopped`)
  check('sprint: no sprint while aiming, and it may start again once right mouse is let go', stillLow && bot.food === 20, `food ${stillLow ? 6 : 'not 6'} while aiming, ${bot.food} after`)

  // The newer press wins: the sprint key pressed while aiming, right mouse still held, takes the player
  // out of the sights and lets the client sprint; once that sprint is over the sights come up again.
  await arm('m4c')
  await runway(spot)
  keys({})
  adsHeard.length = 0
  let stopHold = aimWhile()
  const up = await within(1500, () => adsHeard.length > 0)
  await sleep(300)
  check('sprint: (sprint key) aimed, with the food level at 6', up !== null && bot.food <= 6, `${up} ms, food ${bot.food}`)
  keys({ sprint: true }) // the sprint key alone: the vanilla client does not sprint standing still
  await sleep(400)
  check('sprint: the sprint key without forward leaves the sights up', bot.food <= 6, `food ${bot.food}`)
  keys({ forward: true, sprint: true })
  const freed = await within(400, () => bot.food === 20)
  check('sprint: forward with the sprint key held puts the food level back at 20 at once', freed !== null && freed < 300, `${freed} ms`)
  await startSprint() // what the vanilla client does at food 20 with both keys held
  from = logSize()
  const heardBefore = adsHeard.length
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(200) }
  // Read once the timed part below is over: waiting for the log here would stretch the sprint.
  const wonLogged = loggedShots(from, 3)
  const held = { ok: adsHeard.length === heardBefore && bot.food === 20, detail: `ads sounds ${adsHeard.length - heardBefore}, food ${bot.food}` }
  stopSprint()
  keys({})
  const stopped = Date.now()
  const back = await within(1500, () => adsHeard.length > heardBefore)
  await sleep(200)
  const won = await wonLogged
  check('sprint: that sprint fires from the hip with the sprint cone', won.length >= 3 && won.every(l => !l.aim && l.sprint && l.spread > 5),
    won.map(l => `${l.spread.toFixed(1)}${l.aim ? 'A' : ''}${l.sprint ? 'S' : ''}`).join(' ') || 'no shot logged')
  check('sprint: right mouse held on neither aims nor stops it', held.ok, held.detail)
  check('sprint: once that sprint is over the sights come up again (right mouse still held)', back !== null && back <= 900 && bot.food <= 6,
    `${back === null ? 'never' : back + ' ms'} after the stop, food ${bot.food}`)
  stopHold()
  await sleep(800)
  log(`sprint key: food back at ${bot.food} ${Date.now() - stopped} ms after the stop, right mouse let go`)

  // A client running at sprint pace with the sights up, which a vanilla client cannot do: it ignores the
  // food level and the aiming slowdown and never says it sprints. It holds the trigger from before the
  // run, which keeps an aim up without the use packets (they do not hold it on a run). The sights drop
  // as for a sprint once the server measures the run.
  await arm('m4c')
  await runway(spot)
  adsHeard.length = 0
  stopHold = aimWhile()
  const aimedUp = await within(1500, () => adsHeard.length > 0)
  await sleep(400)
  const cheatFrom = logSize()
  ignoreSlow = true
  bot.entity.attributes['minecraft:movement_speed'] = { value: 0.1, modifiers: [] }
  from = logSize()
  const stopTrigger = triggerWhile()
  await sleep(150)
  await startSprint(false)
  await sleep(500)
  stopTrigger()
  stopSprint(false)
  stopHold()
  ignoreSlow = false
  const speed = bot.entity.attributes['generic.movement_speed']
  if (speed) bot.entity.attributes['minecraft:movement_speed'] = { ...speed, modifiers: (speed.modifiers || []).filter(m => !String(m.uuid).includes('sprinting')) }
  await sleep(600)
  cheatSpans.push([cheatFrom, logSize()])
  const cheat = shotLines(from)
  const running = cheat.filter(l => l.run)
  log('sights up on a run: ' + cheat.map(l => `${l.spread.toFixed(1)}${l.aim ? 'A' : ''}${l.run ? 'R' : ''}`).join(' '))
  check('sprint: aimed before the run, the first rounds aimed', aimedUp !== null && cheat.length > 0 && cheat[0].aim && !cheat[0].run,
    `aimed ${aimedUp} ms, first ${cheat.length ? (cheat[0].aim ? 'aimed' : 'hip') : 'none'}`)
  check('sprint: running at sprint pace with the sights up drops them: every round on the run from the hip, with the sprint cone',
    running.length >= 4 && running.every(l => !l.aim && l.spread > 5), `${running.length} rounds on the run, ${running.filter(l => l.aim).length} aimed`)
}

// ---------------------------------------------------------------- held
// Modified clients (issue #155). Each physics tick the bot sends its movement (position or
// position_look, none while it stands still) and then tick_end, as a vanilla client does. Two tools:
//
//   lie(fn)    a client that lies about where it is: fn(real, declared, k) runs for the k-th movement
//              packet from now with the real position and the last one sent, and returns where to say
//              the bot is, null to send nothing that tick (its tick_end still goes), or 'real' to send
//              the real position and stop lying (the catch-up). What fn sends itself (a click) goes out
//              before that tick's movement, so the server fires on that tick's step.
//   stall(n)   a connection that stalls: the next n ticks' movement and tick_end packets are kept and
//              go out together with the tick after them. A click still goes out at once, so the server
//              fires inside the stall (as full auto does through one, the trigger held without swings).
let lying = null
let lastDeclared = null
let stalling = 0
const stalled = []
const writeRaw = bot._client.write.bind(bot._client)
bot._client.write = (packet, params) => {
  const move = packet === 'position' || packet === 'position_look'
  if (stalling > 0 && (move || packet === 'tick_end')) {
    stalled.push([packet, move ? { ...params, flags: { ...params.flags } } : params]) // mineflayer reuses the object
    if (packet === 'tick_end' && --stalling === 0) stalling = -1 // flushed with the next tick's movement
    return
  }
  if (move && stalling < 0) {
    stalling = 0
    for (const [p, q] of stalled.splice(0)) writeRaw(p, q)
  }
  if (move && lying) {
    const real = { x: params.x, y: params.y, z: params.z }
    const to = lying.fn(real, lastDeclared || real, lying.k++)
    if (to === null) return
    if (to === 'real') {
      const done = lying.done
      lying = null
      done()
    } else {
      params = { ...params, flags: { ...params.flags }, x: to.x, y: to.y, z: to.z }
    }
  }
  if (move) lastDeclared = { x: params.x, y: params.y, z: params.z }
  writeRaw(packet, params)
}
const lie = fn => new Promise(resolve => { lying = { fn, k: 0, done: resolve } })
const stall = ticks => { stalling = ticks }
/** `from` moved `d` blocks horizontally toward `to`. */
const toward = (from, to, d) => {
  const dx = to.x - from.x
  const dz = to.z - from.z
  const len = Math.hypot(dx, dz) || 1
  return { x: from.x + dx / len * d, y: to.y, z: from.z + dz / len * d }
}
const SPRINT_STEP = 0.2806

/** Server log lines saying it caught our movement held back, between two log offsets. */
function heldBackLines (from, to = logSize()) {
  const fd = fs.openSync(LOG, 'r')
  try {
    const buf = Buffer.alloc(Math.max(0, to - from))
    fs.readSync(fd, buf, 0, buf.length, from)
    return buf.toString('utf8').split('\n').filter(l => l.includes(`[debug] ${name} held back`))
  } finally { fs.closeSync(fd) }
}
/** Log spans where a scenario plays a modified client on purpose: left out of the honest check. */
const cheatSpans = []

/** The cone of plain P18 clicks on a sprint, for the cases below to compare with. */
let plainCone = 0
async function plainSprintCone (spot) {
  await arm('p18')
  await runway(spot)
  await startSprint()
  const from = logSize()
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(300) }
  stopSprint()
  const plain = await loggedShots(from, 3)
  plainCone = avg(plain.map(l => l.spread))
  check('held: plain sprint shots fire with the sprint cone, never stopping', plain.length === 3 && plain.every(l => l.sprint && !l.stop && l.spread > 4.5),
    plain.map(l => `${l.spread.toFixed(2)} v${l.speed.toFixed(3)}`).join(', ') || 'no shot logged')
  await sleep(300)
}

const shotList = lines => lines.map(l => `${l.spread.toFixed(2)}${l.stop ? ' stop' : ''}${l.held ? ' careful' : ''} v${l.speed.toFixed(2)}`).join(', ')

/**
 * One case: `legs` sprints down the runway, each running `play` (which fires and lies), then the
 * checks. A client that ends its ticks but holds its movement back reads as stopping, as it says it
 * is, until the movement arrives (the server fires a shot the moment it hears it, and cannot judge it
 * again); the catch-up then covers more ground in one client tick than vanilla physics allows, which
 * the server must log, and every shot judged after that (logged careful) must have the plain sprint
 * cone and not read as stopping. Shots fired before the catch-up are logged only. It reloads and
 * waits 3.5 s first, so the careful spell of the case before has run out.
 */
async function cheatCase (spot, label, legs, play, expect = { caught: true }) {
  await home(spot)
  dropKey() // a full magazine for each case
  await sleep(3500)
  const from = logSize()
  for (let leg = 0; leg < legs; leg++) {
    await runway(spot)
    await startSprint()
    await play(leg)
    stopSprint()
    await sleep(200)
  }
  await sleep(300)
  const to = logSize()
  cheatSpans.push([from, to])
  const lines = shotLines(from)
  const caught = heldBackLines(from, to)
  log(`${label}: ${shotList(lines)}`)
  for (const line of caught) log('  server: ' + line.slice(line.indexOf('[debug]')))
  if (expect.caught) {
    const careful = lines.filter(l => l.held)
    check(`${label}: the catch-up is logged as movement held back`, caught.length >= 1, `${caught.length} logged`)
    check(`${label}: every shot after it has the plain sprint cone and none reads as stopping`,
      careful.length >= expect.after && careful.every(l => !l.stop && l.spread >= 0.9 * plainCone),
      `${careful.length} after the catch-up: ${shotList(careful)} vs ${plainCone.toFixed(2)}`)
  } else {
    check(`${label}: nothing is logged as held back`, caught.length === 0, `${caught.length} logged`)
    if (expect.stopsAllowed) {
      check(`${label}: every shot fired`, lines.length >= expect.shots, `${lines.length} shots`)
    } else {
      check(`${label}: no shot reads as stopping, every one has the plain sprint cone`,
        lines.length >= expect.shots && lines.every(l => !l.stop && l.spread >= 0.9 * plainCone),
        `${lines.length} shots: ${shotList(lines)} vs ${plainCone.toFixed(2)}`)
    }
  }
  return lines
}

/** A real stop (a strafe let go, the shot 100 ms later), four times: most must read as stopping, none careful. */
async function realStop (spot, label) {
  await home(spot)
  dropKey()
  await sleep(3500)
  const from = logSize()
  for (let leg = 0; leg < 4; leg++) {
    const dir = leg % 2 ? 'right' : 'left'
    bot.setControlState(dir, true)
    await sleep(600)
    bot.setControlState(dir, false)
    await sleep(100)
    bot.swingArm()
    await sleep(600)
  }
  const stops = await loggedShots(from, 4)
  check(`${label}: a real stop reads as stopping`, stops.length === 4 && stops.filter(l => l.stop && !l.held).length >= 2 && !stops.some(l => l.held),
    stops.map(l => `${l.stop ? 'stop' : 'moving'}${l.held ? ' careful' : ''} m${l.move.toFixed(2)}`).join(', ') || 'no shot logged')
}

// The cases, on a sprint at 0.28 blocks a tick, three P18 clicks a leg.
/** The issue: two ticks with no movement (their ends sent), the click on the second, then the catch-up. */
const holdTwo = async () => {
  for (let i = 0; i < 3; i++) {
    await lie((real, said, k) => { if (k === 1) bot.swingArm(); return k < 2 ? null : 'real' })
    await sleep(300)
  }
}

/**
 * Issue #155 as filed: on a sprint, two ticks of movement held back before each click and sent with
 * the next (steps 0.28, 0.28, 0, 0 read 0.14: the stop cone). Every case below starts the same way,
 * then catches up differently, as the review of the first fix found it could (B1 to B4).
 */
async function held (spot) {
  await plainSprintCone(spot)
  await cheatCase(spot, 'held', 2, holdTwo, { caught: true, after: 4 })
}

/** B1: held two ticks, the click, then four ticks at a sprint still two ticks behind, then the rest. */
async function defer (spot) {
  if (!plainCone) await plainSprintCone(spot)
  const reals = []
  await cheatCase(spot, 'defer', 2, async () => {
    reals.length = 0
    await lie((real, said, k) => {
      reals.push(real)
      if (k === 1 || k === 4) bot.swingArm()
      if (k < 2) return null
      return k < 6 ? reals[k - 2] : 'real'
    })
    await sleep(300)
    bot.swingArm()
    await sleep(150)
  }, { caught: true, after: 3 })
}

/** B2: steps of 0.011 a tick instead of none (standing, as far as the server can tell), clicks among them, then the dump. */
async function micro (spot) {
  if (!plainCone) await plainSprintCone(spot)
  await cheatCase(spot, 'micro', 2, async () => {
    await lie((real, said, k) => {
      if (k === 1 || k === 5 || k === 9) bot.swingArm()
      return k < 11 ? toward(said, real, 0.011) : 'real'
    })
    await sleep(300)
    bot.swingArm()
    await sleep(150)
  }, { caught: true, after: 1 })
}

/** B3: a stop acted out (steps 0.55 and 0.3 of a sprint's, as letting go of the keys), the click, then the catch-up at once. */
async function mimic (spot) {
  if (!plainCone) await plainSprintCone(spot)
  await cheatCase(spot, 'mimic', 2, async () => {
    for (let i = 0; i < 3; i++) {
      await lie((real, said, k) => {
        if (k === 1) bot.swingArm()
        if (k === 0) return toward(said, real, 0.546 * SPRINT_STEP)
        if (k === 1) return toward(said, real, 0.298 * SPRINT_STEP)
        return 'real'
      })
      await sleep(300)
    }
  }, { caught: true, after: 4 })
}

/** B4: held two ticks, the click, half of it repaid with another click, then the rest. */
async function piecewise (spot) {
  if (!plainCone) await plainSprintCone(spot)
  await cheatCase(spot, 'piecewise', 2, async () => {
    for (let i = 0; i < 2; i++) {
      await lie((real, said, k) => {
        if (k === 1 || k === 3) bot.swingArm()
        if (k < 2) return null
        if (k === 2) return toward(said, real, Math.hypot(real.x - said.x, real.z - said.z) / 2)
        if (k === 3) return toward(said, real, Math.hypot(real.x - said.x, real.z - said.z) / 2)
        return 'real'
      })
      await sleep(300)
    }
  }, { caught: true, after: 2 })
}

/**
 * A stall: two ticks of nothing at all from the client (movement and tick ends kept), a click in the
 * second, then all of it at once. Nothing is known about the stalled ticks, so the shot reads the
 * sprint the bot was on; the burst brings three ticks' ends with three ticks' movement, which the
 * physics allows: no shot reads as stopping, nothing is logged as held back, and a real stop
 * afterwards still reads as stopping.
 */
async function stalls (spot) {
  if (!plainCone) await plainSprintCone(spot)
  await cheatCase(spot, 'stall', 2, async () => {
    for (let i = 0; i < 3; i++) {
      stall(2)
      await sleep(60)
      bot.swingArm()
      await sleep(240)
    }
  }, { caught: false, shots: 6 })
  await realStop(spot, 'stall: afterwards')
}

/**
 * A client that sends no tick ends (a bot, or a modified client that drops them) is measured per
 * server tick, as before the client's clock was used. A real stop still reads as stopping. Two ticks
 * without any movement before each click read as stopping, as they always did without the clock (the
 * fallback's known gap): nothing may be logged for them, since the burst after is what a stall looks
 * like too. Steps of 0.011 instead, then the dump, are more than the physics allows even with a tick
 * to spare for packets bunched up: logged, and the shots after them careful.
 */
async function fallback (spot) {
  if (!plainCone) await plainSprintCone(spot)
  tickEnds = false
  try {
    await sleep(2500) // the server stops counting on tick ends after 40 ticks without one
    await realStop(spot, 'fallback (no tick ends)')
    await cheatCase(spot, 'fallback: held two ticks', 2, holdTwo, { caught: false, shots: 6, stopsAllowed: true })
    await cheatCase(spot, 'fallback: micro-steps', 2, async () => {
      await lie((real, said, k) => {
        if (k === 2) bot.swingArm()
        return k < 6 ? toward(said, real, 0.011) : 'real'
      })
      await sleep(300)
      bot.swingArm()
      await sleep(150)
    }, { caught: true, after: 1 })
  } finally {
    tickEnds = true
  }
}

// ---------------------------------------------------------------- stance
/**
 * What another player sees: a spectator next to the runway watches the shooter's main hand. A gun
 * held up is sent as a charged crossbow (StanceService), carried low as the real item (paper): low
 * unless it is aimed or has fired in the last 1.5 s (0.5 s on a sprint).
 */
async function stance (spot) {
  const viewerName = 'Vw' + name.slice(3)
  const viewer = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: viewerName, version: '1.21.11', auth: 'offline' })
  viewer._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) viewer._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  viewer.on('error', e => log('viewer ERROR', e.message))
  const seen = []
  viewer._client.on('entity_equipment', p => {
    if (p.entityId !== bot.entity.id) return
    for (const e of p.equipments) {
      if (e.slot !== 0) continue
      const item = e.item && e.item.itemId ? viewer.registry.items[e.item.itemId] : null
      seen.push({ at: Date.now(), raised: !!item && item.name === 'crossbow' })
    }
  })
  await new Promise(resolve => viewer.once('spawn', resolve))
  try {
    await sleep(1500)
    await rcon(`gamemode spectator ${viewerName}`)
    await arm('m4c')
    await runway(spot)
    await rcon(`execute as ${viewerName} at ${name} run tp @s ~-5 ~1 ~3 -120 10`)
    await sleep(2000)
    const raisedNow = () => seen.length ? seen[seen.length - 1].raised : null
    const standing = raisedNow()
    await startSprint()
    await sleep(300)
    const sprinting = raisedNow()
    const firing = holdTrigger(700)
    await sleep(400)
    const sprintFiring = raisedNow()
    await firing
    await sleep(800)
    const after = raisedNow()
    stopSprint()
    await sleep(2000)
    const stopped = raisedNow()
    const burst = holdTrigger(200)
    await sleep(350)
    const standFiring = raisedNow()
    await burst
    await sleep(2200)
    const standAfter = raisedNow()
    log('viewer saw', seen.map(e => e.raised ? 'up' : 'low').join(' '))
    check('stance: others see the gun carried low while standing', standing === false, String(standing))
    check('stance: carried low on a sprint', sprinting === false, String(sprinting))
    check('stance: shouldered while firing on the sprint', sprintFiring === true, String(sprintFiring))
    check('stance: and low again half a second after the last shot, still sprinting', after === false, String(after))
    check('stance: still low once the sprint is over', stopped === false, String(stopped))
    check('stance: a burst standing shoulders it', standFiring === true, String(standFiring))
    check('stance: and it is low again 2 s after the last shot', standAfter === false, String(standAfter))
  } finally {
    viewer.quit()
  }
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
  // And it all comes back: a single shot after the reload goes where the crosshair is. The spray ran
  // the magazine dry, and it reloads by itself only if the trigger is still held when it does: the bot
  // lets go once it has heard 30 rounds, which can come before the server has fired the last of them,
  // and then the shot below only started the reload and was never fired (issue #144). So Q (during a
  // reload already under way it changes nothing), and the shot waits for the server to end the reload.
  const sprayed = Date.now()
  dropKey()
  const reloaded = await within(15000, () => reloadsDone.some(at => at >= sprayed))
  const from = logSize()
  bot.swingArm()
  const single = await loggedShots(from, 1)
  check('recoil: a second after the spray the rounds are back on the crosshair', single.length >= 1 && single[0].climb === 0,
    (single.map(l => l.climb).join(',') || 'no shot logged') + (reloaded === null ? ' (no reload ended after the spray)' : ''))
}

/**
 * 10 M4C rounds on full auto from a level crosshair, aimed through `optic` (null: from the hip), the
 * mouse left alone. Returns the climb per round the server saw (its logged pitch, first to tenth
 * round, over nine kicks) and whether every round was logged as aimed.
 */
async function aimedSpray (spot, optic) {
  await arm('m4c', optic === 'hip' ? null : optic)
  await home(spot)
  await lookPitch(0)
  await sleep(400)
  const stopAim = optic === 'hip' ? null : aimWhile()
  await sleep(1300) // the sights up (0.35 s at most for the M4C) and settled
  const shot = await fire(10, 0, 1200)
  if (stopAim) stopAim()
  await sleep(600)
  const lines = shot.lines.slice(0, 10)
  const perRound = lines.length === 10 ? (lines[0].pitch - lines[9].pitch) / 9 : NaN
  return { perRound, aimed: lines.length > 0 && lines.every(l => l.aim) }
}

async function aimed (spot) {
  const hip = await aimedSpray(spot, 'hip')
  const sights = {}
  for (const optic of ['optic_reddot', 'optic_4x', 'optic_8x']) sights[optic] = await aimedSpray(spot, optic)
  const climbs = ['hip', 'optic_reddot', 'optic_4x', 'optic_8x'].map(o => `${o} ${(o === 'hip' ? hip : sights[o]).perRound.toFixed(3)}`).join(', ')
  log('aimed: climb per round (degrees):', climbs)
  check('aimed: the hip spray climbed', hip.perRound > 0.3 && !hip.aimed, hip.perRound.toFixed(3))
  for (const [optic, s] of Object.entries(sights)) check(`aimed: every ${optic} round was aimed`, s.aimed)
  const dot = sights.optic_reddot.perRound / hip.perRound
  check('aimed: through the red dot the view climbs the M4C\'s ads-recoil of the hip climb (0.3-0.6)', dot >= 0.3 && dot <= 0.6, dot.toFixed(2))
  // A scope only shows each degree larger: the gun kicks as many degrees through it.
  for (const optic of ['optic_4x', 'optic_8x']) {
    const same = sights[optic].perRound / sights.optic_reddot.perRound
    check(`aimed: through the ${optic} as many degrees as through the red dot (0.8-1.25 of it)`, same >= 0.8 && same <= 1.25, same.toFixed(2))
  }

  // One AWR shot through its built-in 8x: the view kicks its ads-recoil (0.4) of 5 degrees, and
  // once it has drifted back, with the sights still up, 85% of that is given back (recoil-return).
  await arm('awr')
  await home(spot)
  await lookPitch(0)
  await sleep(400)
  const stopAim = aimWhile()
  await sleep(1500)
  const start = pitchDeg()
  const from = logSize()
  let peak = start
  bot.swingArm()
  const began = Date.now()
  while (Date.now() - began < 500) {
    await sleep(25)
    peak = Math.min(peak, pitchDeg())
  }
  await sleep(1000)
  const settled = pitchDeg()
  stopAim()
  const awr = shotLines(from)
  const kick = start - peak
  const back = kick > 0 ? (settled - peak) / kick : NaN
  log(`aimed: AWR pitch ${start.toFixed(2)} -> highest ${peak.toFixed(2)} -> 1.5 s after the shot ${settled.toFixed(2)}`)
  check('aimed: one AWR shot through its 8x kicks the view 1.5-2.5 degrees', awr.length === 1 && awr[0].aim && kick >= 1.5 && kick <= 2.5,
    `${awr.length} shot(s), aimed ${awr.length && awr[0].aim}, kick ${kick.toFixed(2)}`)
  check('aimed: 1.5 s after it the view has come back 75-95% of the kick (recoil-return 0.85)', back >= 0.75 && back <= 0.95,
    `${(100 * back).toFixed(0)}%, ${(start - settled).toFixed(2)} degrees still above`)
  await sleep(1500)
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
    // A soldier's hit on a player names no attacking entity, only where it came from
    // (DamageService.deal keeps vanilla's difficulty scaling out of it); nothing else hurts us here.
    const by = bot.entities[p.sourceCauseId - 1]
    if (bot.entity && p.entityId === bot.entity.id && (by ? SOLDIERS.includes(by.name) : !!p.sourcePosition)) hits.push(Date.now())
  })
  // Soldiers' reports reach us as positional sounds; every soldier in the raid is shooting at us.
  // One shot can arrive as two sounds in the same tick (two distance bands crossfading, or the
  // muffled report with a little of the open one): those count once.
  const reports = []
  let lastReport = null
  bot.on('soundEffectHeard', sound => {
    const m = String(sound).match(/craftov:gun\.([a-z0-9_]+)\.(near|mid|far|muff)$/)
    if (!m) return
    const now = Date.now()
    if (!lastReport || now - lastReport.at > 10 || lastReport.gun !== m[1]) reports.push(now)
    lastReport = { at: now, gun: m[1] }
  })
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
  const logFrom = logSize()
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
  // The server logs every soldier shot with the target's measured speed and whether it hit. Within
  // 10 m most rounds hit either way (the body is wider than the cone), and how many shots come from
  // there swings with where the soldiers stand, which is what makes the overall rates above noisy;
  // from 10 to 30 m the 1.4x cone should cut hits to about half.
  const shotsLogged = []
  for (const line of logSince(logFrom)) {
    const m = line.match(new RegExp(`\\[debug\\] ai \\S+ shot ${name} dist=([0-9.]+) speed=([0-9.]+) moving=([0-9.]+) settle=[0-9.]+ spread=([0-9.]+) pellets=\\d+ hits=(\\d+)`))
    if (m) shotsLogged.push({ dist: Number(m[1]), speed: Number(m[2]), moving: Number(m[3]), spread: Number(m[4]), hit: Number(m[5]) > 0 })
  }
  const group = (still, lo, hi) => shotsLogged.filter(r => (still ? r.speed < 0.05 : r.speed >= 0.15) && r.dist >= lo && r.dist < hi)
  const hitRate = rs => rs.length ? rs.filter(r => r.hit).length / rs.length : 0
  for (const [lo, hi] of [[0, 10], [10, 30], [30, 200]]) {
    const st = group(true, lo, hi)
    const mv = group(false, lo, hi)
    log(`soldier shots ${lo}-${hi} m: at us standing ${st.length}, hit ${hitRate(st).toFixed(3)}; strafing ${mv.length}, hit ${hitRate(mv).toFixed(3)},`,
      `cone x${mv.length ? (mean(mv.map(r => r.moving))).toFixed(2) : '-'}`)
  }
  const st = group(true, 10, 30)
  const mv = group(false, 10, 30)
  check('ai: soldiers widen their cone about 1.4x against a strafing player', mv.length >= 20 && mean(mv.map(r => r.moving)) >= 1.3,
    `${mv.length} shots, x${mean(mv.map(r => r.moving)).toFixed(2)}`)
  const detail = `${hitRate(mv).toFixed(3)} (${mv.length} shots) vs ${hitRate(st).toFixed(3)} (${st.length} shots)`
  if (st.filter(r => r.hit).length >= 20 && mv.length >= 50) {
    check('ai: and from 10 to 30 m hit a strafing player clearly less often (under 0.75x)', hitRate(mv) < 0.75 * hitRate(st), detail)
  } else {
    // A handful of hits says nothing either way (one run had 12 standing hits at that range).
    log(`SKIP ai: too few hits from 10 to 30 m to compare (needs 20 on the standing bot) :: ${detail}`)
  }
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
    const scenarioFrom = logSize()
    const spansBefore = cheatSpans.length
    if (scenario === 'spread') await spread(spot)
    else if (scenario === 'sprint') await sprint(spot)
    else if (scenario === 'held') await held(spot)
    else if (scenario === 'defer') await defer(spot)
    else if (scenario === 'micro') await micro(spot)
    else if (scenario === 'mimic') await mimic(spot)
    else if (scenario === 'piecewise') await piecewise(spot)
    else if (scenario === 'stall') await stalls(spot)
    else if (scenario === 'fallback') await fallback(spot)
    else if (scenario === 'stance') await stance(spot)
    else if (scenario === 'recoil') await recoil(spot)
    else if (scenario === 'aimed') await aimed(spot)
    else if (scenario === 'impact') await impact(spot)
    else if (scenario === 'ai') await ai()
    else log('unknown scenario', scenario)
    const late = lateMoves.splice(0)
    if (late.length) log(`${scenario}: the bot's movement went out late ${late.length} time(s), up to ${Math.max(...late)} ms (two ticks at once)`)
    if (HONEST.includes(scenario)) {
      await sleep(300)
      const spans = cheatSpans.slice(spansBefore)
      const all = heldBackLines(scenarioFrom)
      const inCheats = spans.reduce((n, [a, b]) => n + heldBackLines(a, b).length, 0)
      const honest = all.length - inCheats
      check(`${scenario}: an honest client's movement is never logged as held back`, honest === 0,
        `${honest} logged${inCheats ? `, ${inCheats} more where it plays a modified client` : ''}`)
    }
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
