// Gun feel (issue #8), on the private range unless said otherwise.
//
//   kick    The first-person kick and flash follow the shots the server fires: every shot sends
//           exactly one craftov:reload cooldown of the gun's kick time (2 ticks for the K47 at 600
//           rpm, 3 for the P18, 6 for the AWR), as many as the server logged shots in a 1 s K47
//           burst; a P18 held down after its shot and an AWR held for 2 s through its bolt cycle get
//           one each; the trigger held in the lobby, off the range, gets none. Q starts the reload
//           with the kick cleared (a 0 cooldown), then the reload mark (damage 2) on the gun's
//           stack, and only then the long reload cooldown; the mark is gone once the reload is done.
//   ads     K47 on full auto for 2 s with right mouse held (a use packet every 4 ticks, as the
//           vanilla client sends them): every shot is logged as aimed.
//   recoil  K47 10 rounds from the hip without touching the mouse: the camera eases into each kick
//           (at least two turns per shot fired), never turns more than 1.5 degrees in one tick, kick
//           or recovery, and once let go comes back down: some of the way 0.6 s later, and by 1.5 s
//           to the gun's recoil-return share (0.65 for the K47, so 25-50% of the climb is left).
//   move    K47, 10 single shots each standing and strafing, from the hip and aimed: the speed the
//           server measured for most strafing shots is within 10% of what the bot really walked, strafing
//           hip fire is at least 1.8x as wide as standing and strafing aimed fire at least 2.5x;
//           standing shots count no movement at all.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node gunfeel.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (shots are read from the log).
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const LOG = process.env.SERVER_LOG || path.join(__dirname, '../../server/run/logs/latest.log')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['lobby', 'kick', 'ads', 'recoil', 'move']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const name = 'Feel' + Math.floor(10000 + Math.random() * 90000)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
// mineflayer applies player_rotation as absolute angles; recoil sends it relative, as vanilla expects.
/** Camera turns the server sent: { at, pitch, yaw } in degrees (pitch negative = up). */
const turns = []
bot.once('login', () => {
  bot._client.removeAllListeners('player_rotation')
  bot._client.on('player_rotation', p => {
    const yaw = (p.relativeYaw ? conv.toNotchianYaw(bot.entity.yaw) : 0) + p.yaw
    const pitch = Math.max(-90, Math.min(90, (p.relativePitch ? conv.toNotchianPitch(bot.entity.pitch) : 0) + p.pitch))
    if (p.relativePitch) turns.push({ at: Date.now(), pitch: p.pitch, yaw: p.yaw })
    bot.entity.yaw = conv.fromNotchianYaw(yaw)
    bot.entity.pitch = conv.fromNotchianPitch(pitch)
  })
})
const pitchDeg = () => conv.toNotchianPitch(bot.entity.pitch)

/**
 * What the gun's item model is driven by, in the order the bot received it: craftov:reload cooldowns
 * { kind: 'cool', ticks } and the hand mark on hotbar stacks { kind: 'mark', slot, damage }.
 */
const events = []
bot._client.on('set_cooldown', p => { if (JSON.stringify(p).includes('reload')) events.push({ at: Date.now(), kind: 'cool', ticks: p.cooldownTicks }) })
const damageOf = item => item && item.componentMap && item.componentMap.has('damage') ? item.componentMap.get('damage').data : 0
bot.once('spawn', () => bot.inventory.on('updateSlot', (slot, before, after) => {
  if (damageOf(before) !== damageOf(after)) events.push({ at: Date.now(), kind: 'mark', slot, damage: damageOf(after) })
}))
const bars = []
bot._client.on('action_bar', p => bars.push({ at: Date.now(), text: JSON.stringify(p) }))

const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
const START = 0
const ABORT = 1
const swing = () => bot._client.write('arm_animation', { hand: 0 })
const useItem = () => bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
const hold = id => bot.setQuickBarSlot(hotbar(id) - 36)

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
/** This bot's shots logged since `from`: { tick, aim, spread, speed, move }. */
const shotsSince = from => logSince(from).filter(l => l.includes(`[debug] ${name} shot `)).map(l => ({
  tick: Number((l.match(/ tick=(\d+)/) || [])[1]),
  aim: / aim=true/.test(l),
  spread: Number((l.match(/ spread=([0-9.]+)/) || [])[1]),
  speed: Number((l.match(/ speed=([0-9.]+)/) || [])[1]),
  move: Number((l.match(/ move=([0-9.]+)/) || [])[1]),
  level: Number((l.match(/ level=([0-9.]+)/) || [])[1])
}))

// ---------------------------------------------------------------- the trigger
/** The capture cell the server keeps in front of the crosshair (TriggerService), as the client would dig it. */
function cell () {
  const id = bot.registry.blocksByName.sculk_vein.id
  const cells = bot.findBlocks({ matching: id, maxDistance: 6, count: 16 })
  if (!cells.length) throw new Error('no capture cell in front of the crosshair')
  const eye = bot.entity.position.offset(0, 1.62, 0)
  return cells.reduce((a, b) => a.distanceTo(eye) < b.distanceTo(eye) ? a : b)
}
/** The held stack as vanilla compares it to the one a dig began with (ItemStack.isSameItemSameComponents). */
const heldKey = () => JSON.stringify(bot.heldItem ? [bot.heldItem.name, bot.heldItem.components] : null)
/**
 * The trigger held for `ms`: a dig start, a swing every tick, the abort at the end. `at` is the block to
 * dig (the capture cell by default). `aiming` also sends a use packet every 4 ticks, as right mouse held.
 */
async function press (ms, { at, aiming } = {}) {
  at = at || cell()
  dig(START, at)
  let digging = heldKey()
  const until = Date.now() + ms
  let ticks = 0
  while (Date.now() < until) {
    // The vanilla client starts the dig over once the held stack differs in any component.
    if (heldKey() !== digging) {
      digging = heldKey()
      dig(ABORT, at)
      dig(START, at)
    }
    swing()
    if (aiming && ticks % 4 === 0) useItem()
    ticks++
    await sleep(50)
  }
  dig(ABORT, at)
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

/** Gives the gun if it is not on the hotbar yet, holds it and fills its magazine (Q). */
async function arm (id) {
  if (hotbar(id) < 0) await rcon(`cv give ${name} ${id}`)
  await sleep(800)
  if (hotbar(id) < 0) throw new Error(id + ' did not reach the hotbar')
  hold(id)
  await sleep(1200) // the draw
  dig(4) // Q
  await sleep(6500)
}

async function aim (spot) {
  bot.clearControlStates()
  await rcon(`execute as ${name} at @s run tp @s ${spot.x.toFixed(3)} ${spot.y} ${spot.z.toFixed(3)} 180 0`)
  await sleep(500)
}

/** Cooldowns started since event `from` (the server also sends a 0 when one runs out by itself). */
const cools = from => events.slice(from).filter(e => e.kind === 'cool' && e.ticks > 0).map(e => e.ticks)

// ---------------------------------------------------------------- kick
async function kick (spot) {
  // K47 on full auto: one kick cooldown per shot fired.
  await arm('k47')
  await aim(spot)
  let from = logSize()
  let ev = events.length
  await press(1000)
  await sleep(400)
  let shots = shotsSince(from)
  let c = cools(ev)
  check('kick: K47 1 s burst sends one 2-tick cooldown per shot fired', shots.length >= 8 && c.length === shots.length && c.every(t => t === 2),
    `${shots.length} shots logged, cooldowns ${c.join(',')}`)

  // P18 held down: one shot, one kick.
  await arm('p18')
  await aim(spot)
  from = logSize()
  ev = events.length
  await press(1500)
  await sleep(300)
  shots = shotsSince(from)
  c = cools(ev)
  check('kick: P18 held 1.5 s fires once and kicks once (3 ticks)', shots.length === 1 && c.length === 1 && c[0] === 3,
    `${shots.length} shots, cooldowns ${c.join(',')}`)

  // AWR held through its bolt cycle: one shot, one 6-tick kick, nothing while the bolt is worked.
  await arm('awr')
  await aim(spot)
  from = logSize()
  ev = events.length
  await press(2000)
  await sleep(300)
  shots = shotsSince(from)
  c = cools(ev)
  check('kick: AWR held 2 s fires once and kicks once (6 ticks)', shots.length === 1 && c.length === 1 && c[0] === 6,
    `${shots.length} shots, cooldowns ${c.join(',')}`)

  // Reload: the kick cleared, then the mark, then the long cooldown; the mark comes off at the end.
  await arm('k47')
  await aim(spot)
  await press(300)
  await sleep(400)
  const slot = hotbar('k47')
  ev = events.length
  dig(4) // Q
  await sleep(4500)
  const seq = events.slice(ev).filter(e => e.kind === 'cool' || e.slot === slot)
  log('reload sequence', seq.map(e => e.kind === 'cool' ? 'cool ' + e.ticks : 'mark ' + e.damage).join(', '))
  const mark = seq.findIndex(e => e.kind === 'mark' && e.damage === 2)
  const long = seq.findIndex(e => e.kind === 'cool' && e.ticks > 6)
  const clear = seq.findIndex(e => e.kind === 'cool' && e.ticks === 0)
  const off = seq.findIndex((e, i) => i > mark && e.kind === 'mark' && e.damage === 0)
  check('kick: the reload clears the kick, marks the gun, then starts the reload cooldown', clear >= 0 && mark > clear && long > mark,
    `clear at ${clear}, mark at ${mark}, reload cooldown at ${long}`)
  check('kick: the reload mark comes off when the reload is done', off > long && damageOf(bot.inventory.slots[slot]) === 0,
    off > 0 ? `${seq[off].at - seq[mark].at} ms after it went on` : 'never')

}

/** In the lobby, off the range: the trigger fires nothing and kicks nothing. */
async function lobby () {
  await rcon(`execute as ${name} at @s run tp @s 0.5 65 6.5 0 30`)
  await sleep(1500)
  if (hotbar('k47') < 0) await rcon(`cv give ${name} k47`)
  await sleep(800)
  hold('k47')
  await sleep(1000)
  const from = logSize()
  const ev = events.length
  const ahead = bot.blockAtCursor(5)
  await press(1000, { at: ahead ? ahead.position : bot.entity.position.offset(0, -1, 2).floored() })
  await sleep(300)
  const shots = shotsSince(from)
  const c = cools(ev)
  check('lobby: the trigger held in the lobby fires and kicks nothing', shots.length === 0 && c.length === 0,
    `${shots.length} shots, cooldowns ${c.join(',')}`)
}

// ---------------------------------------------------------------- recoil
/** Camera turns since `from` grouped by server tick (turns that arrive within 25 ms of each other). */
function turnTicks (from) {
  const out = []
  for (const t of turns.slice(from)) {
    const last = out[out.length - 1]
    if (last && t.at - last.at < 25) last.pitch += t.pitch
    else out.push({ at: t.at, pitch: t.pitch })
  }
  return out
}

async function recoil (spot) {
  await arm('k47')
  await aim(spot)
  await bot.look(bot.entity.yaw, 0, true)
  await sleep(800)
  const start = pitchDeg()
  const from = logSize()
  const turnFrom = turns.length
  const at = cell()
  dig(START, at)
  let peak = start
  while (shotsSince(from).length < 10) {
    swing()
    await sleep(50)
    peak = Math.min(peak, pitchDeg())
  }
  dig(ABORT, at)
  const released = Date.now()
  const fired = shotsSince(from).length
  const firingTurns = turns.slice(turnFrom).filter(t => t.at <= released + 60).length
  await sleep(100)
  peak = Math.min(peak, pitchDeg())
  await until(released, 600)
  const at600 = pitchDeg()
  await until(released, 1500)
  const at1500 = pitchDeg()
  const climb = start - peak
  const steps = turnTicks(turnFrom)
  const biggest = Math.max(...steps.map(s => Math.abs(s.pitch)))
  log(`recoil: ${fired} shots, climbed ${climb.toFixed(2)} deg; left ${(start - at600).toFixed(2)} at 0.6 s, ${(start - at1500).toFixed(2)} at 1.5 s;`,
    `${firingTurns} camera turns while firing, biggest tick ${biggest.toFixed(2)} deg`)
  check('recoil: each shot eases in over two ticks', firingTurns >= 1.8 * fired, `${firingTurns} turns for ${fired} shots`)
  check('recoil: no tick turns the camera more than 1.5 degrees', biggest <= 1.5, `${biggest.toFixed(2)} deg`)
  check('recoil: 0.6 s after letting go it is coming back down', start - at600 < 0.85 * climb, `${((start - at600) / climb * 100).toFixed(0)}% of the climb left`)
  check('recoil: by 1.5 s the recoil-return share is back (25-50% of the climb left)', start - at1500 >= 0.25 * climb && start - at1500 <= 0.5 * climb,
    `${((start - at1500) / climb * 100).toFixed(0)}% of the climb left`)
}
/** Waits until `ms` after `from` (a Date.now()). */
const until = (from, ms) => sleep(Math.max(0, from + ms - Date.now()))

// ---------------------------------------------------------------- move
/** Single shots: dig start, a swing and the abort in one go. */
function click () {
  const at = cell()
  dig(START, at)
  swing()
  dig(ABORT, at)
}

async function move (spot) {
  await arm('k47')
  const series = {}
  const walked = []
  for (const mode of ['stand', 'walk', 'stand-ads', 'walk-ads']) {
    await aim(spot)
    dig(4) // a full magazine for each series
    await sleep(3200)
    let stopAim = null
    if (mode.endsWith('ads')) { useItem(); const t = setInterval(useItem, 200); stopAim = () => clearInterval(t) }
    await sleep(600)
    const from = logSize()
    for (let leg = 0; leg < 5; leg++) {
      const dir = leg % 2 ? 'right' : 'left'
      if (mode.startsWith('walk')) bot.setControlState(dir, true)
      await sleep(450)
      for (let shot = 0; shot < 2; shot++) {
        // What the bot really walks over the 200 ms before the shot, in blocks per tick.
        const a = bot.entity.position.clone()
        await sleep(200)
        const b = bot.entity.position
        if (mode === 'walk') walked.push(Math.hypot(b.x - a.x, b.z - a.z) / 4)
        // A strafing bot can dig a capture cell the server has just moved on: click again until it fires.
        const before = shotsSince(from).length
        for (let tries = 0; tries < 3 && shotsSince(from).length === before; tries++) { click(); await sleep(120) }
        await sleep(130)
      }
      if (mode.startsWith('walk')) bot.setControlState(dir, false)
      await sleep(300)
    }
    if (stopAim) stopAim()
    await sleep(400)
    series[mode] = shotsSince(from)
    const s = series[mode]
    log(mode, 'shots', s.length, 'spread avg', avg(s.map(x => x.spread)).toFixed(3), 'speed avg', avg(s.map(x => x.speed)).toFixed(3),
      'level avg', avg(s.map(x => x.level)).toFixed(2), 'aimed', s.filter(x => x.aim).length)
  }
  const sp = m => avg(series[m].map(x => x.spread))
  const real = avg(walked)
  // The bot's movement packets bunch up now and then (its physics loop catches up on a busy machine),
  // and a shot taken right then reads slow: most must read within 10%.
  const close = series.walk.filter(x => Math.abs(x.speed - real) / real < 0.1).length
  check('move: 10+ shots in each series', Object.values(series).every(s => s.length >= 10), Object.entries(series).map(([k, v]) => k + '=' + v.length).join(' '))
  check('move: the server measures the strafing pace within 10% (7 of 10 shots)', real > 0.1 && close >= 0.7 * series.walk.length,
    `walked ${real.toFixed(3)} blocks/tick, server read ${series.walk.map(x => x.speed.toFixed(3)).join(' ')}`)
  check('move: standing shots count no movement', series.stand.concat(series['stand-ads']).every(x => x.level === 0))
  check('move: strafing hip fire >= 1.8x standing', sp('walk') >= 1.8 * sp('stand'), `${sp('walk').toFixed(2)} vs ${sp('stand').toFixed(2)} = ${(sp('walk') / sp('stand')).toFixed(2)}x`)
  check('move: strafing aimed fire >= 2.5x standing aimed', sp('walk-ads') >= 2.5 * sp('stand-ads'),
    `${sp('walk-ads').toFixed(2)} vs ${sp('stand-ads').toFixed(2)} = ${(sp('walk-ads') / sp('stand-ads')).toFixed(2)}x`)
}
const avg = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0

// ---------------------------------------------------------------- ads
async function ads (spot) {
  await arm('k47')
  await aim(spot)
  const stop = (() => { useItem(); const t = setInterval(useItem, 200); return () => clearInterval(t) })()
  await sleep(600)
  const from = logSize()
  await press(2000, { aiming: true })
  stop()
  await sleep(300)
  const shots = shotsSince(from)
  check('ads: K47 full auto for 2 s with right mouse held stays aimed', shots.length >= 15 && shots.every(s => s.aim),
    `${shots.length} shots, ${shots.filter(s => s.aim).length} aimed`)
}

;(async () => {
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  let spot = null
  for (const scenario of scenarios) {
    log('---', scenario)
    if (scenario === 'lobby') { await lobby(); continue }
    if (!spot || (await rcon(`data get entity ${name} Dimension`)).indexOf('craftov_range') < 0) {
      await enterRange()
      await sleep(500)
      spot = bot.entity.position.clone()
      log('on the range at', spot.toString())
    }
    if (scenario === 'kick') await kick(spot)
    else if (scenario === 'ads') await ads(spot)
    else if (scenario === 'recoil') await recoil(spot)
    else if (scenario === 'move') await move(spot)
    else log('unknown scenario', scenario)
  }
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  bot.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(e => {
  log('ERROR', e.stack || e.message, failures)
  bot.quit()
  setTimeout(() => process.exit(2 + failures.length), 500)
})
