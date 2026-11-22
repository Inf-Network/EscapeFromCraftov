// Switching guns (issue #34), on the private range: a switch neither skips a bolt or pump cycle nor
// clears bloom, and a gun takes its draw time (weapons.draw-ticks) to come up before it fires or aims.
//
//   bolt   AWR (42 rpm: 29 ticks between shots, 18 to draw): one shot, then over to the P18 and
//          straight back. Clicks 0.4 to 1.25 s after the shot fire nothing before the cycle is over
//          and the gun is up again (the last one comes after the draw but inside the bolt cycle); by
//          a click at 1.6 s it has fired again, at least 29 ticks after the first. Shots are judged by
//          the server ticks in the log, not by when the clicks were sent.
//   pump   R12 (70 rpm: 17 ticks, 14 to draw): the same, clicks up to 0.7 s and one at 1.2 s.
//   draw   M4C to P18 (6 ticks to draw): clicks in the first 200 ms after the switch fire nothing
//          before the tick the gun is up, one at 500 ms fires, and the action bar says the gun is
//          being drawn; the pistol's stack carries the draw mark (damage 1, which keeps its model
//          from aiming or flashing) from the switch until it is up, and a gun switched away from
//          mid-draw loses it; the trigger held from the moment of the switch fires once, 6 ticks
//          after the draw began; right mouse held from the switch aims on the tick the gun is up.
//   bloom  M4C sprayed on full auto for 1.2 s, over to the P18 and back: the first shot after the draw
//          still has the spray's bloom, less 0.08 for every tick since the spray's last shot.
//   cut    M4C three rounds down, Q, and over to the P18 0.4 s into the reload: the action bar says
//          the reload was cut short and the reload animation (the craftov:reload cooldown) stops.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node swap.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (shots and draws are read from the
// log) and the default weapons.draw-ticks. Exit code: number of failed checks (2+ also when a
// scenario could not be set up).
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
const scenarios = wanted.length ? wanted : ['bolt', 'pump', 'draw', 'bloom', 'cut']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const name = 'Swp' + Math.floor(10000 + Math.random() * 90000)
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
/** When the bot heard its gun go to aim-down-sights (WeaponService.setAds plays gun.ads). */
const adsHeard = []
const onSound = p => { if (/gun\.ads\b/.test(JSON.stringify(p.sound || p.soundId || ''))) adsHeard.push(Date.now()) }
bot._client.on('entity_sound_effect', onSound)
bot._client.on('sound_effect', onSound)
/** Action bar lines and craftov:reload cooldowns the bot was sent: { at, text } and { at, ticks }. */
const bars = []
const cooldowns = []
bot._client.on('action_bar', p => bars.push({ at: Date.now(), text: JSON.stringify(p) })) // raw: the event misses NBT text
bot._client.on('set_cooldown', p => { if (JSON.stringify(p).includes('reload')) cooldowns.push({ at: Date.now(), ticks: p.cooldownTicks }) })

/** The draw mark on a stack (ItemFactory.setDrawing): its damage component. */
const damageOf = item => item && item.componentMap && item.componentMap.has('damage') ? item.componentMap.get('damage').data : 0
/** Every slot update the bot was sent: { at, slot, damage }. */
const slotUpdates = []
bot.once('spawn', () => bot.inventory.on('updateSlot', (slot, before, after) => slotUpdates.push({ at: Date.now(), slot, damage: damageOf(after) })))
const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
const START = 0
const ABORT = 1
const swing = () => bot._client.write('arm_animation', { hand: 0 })
const useItem = () => bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
/** Switches the held hotbar slot to the gun, as a number key does. */
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
/** This bot's shots logged since `from`: { tick, bloom }. */
const shotsSince = from => logSince(from).filter(l => l.includes(`[debug] ${name} shot `))
  .map(l => ({ tick: Number((l.match(/ tick=(\d+)/) || [])[1]), bloom: Number((l.match(/ bloom=([0-9.]+)/) || [])[1]) }))
/** Guns this bot drew since `from`: { gun, tick, ready, next, bloom }. */
const drawsSince = from => logSince(from).map(l => l.match(new RegExp(`\\[debug\\] ${name} draws ([a-z0-9_]+) tick=(\\d+) ready=(\\d+) next=(-?\\d+) bloom=([0-9.]+)`)))
  .filter(Boolean).map(m => ({ gun: m[1], tick: Number(m[2]), ready: Number(m[3]), next: Number(m[4]), bloom: Number(m[5]) }))

/** Guns this bot brought up since `from`: { gun, tick, aim }. */
const upsSince = from => logSince(from).map(l => l.match(new RegExp(`\\[debug\\] ${name} up ([a-z0-9_]+) tick=(\\d+) aim=(true|false)`)))
  .filter(Boolean).map(m => ({ gun: m[1], tick: Number(m[2]), aim: m[3] === 'true' }))

// ---------------------------------------------------------------- the trigger
/** The capture cell the server keeps in front of the crosshair (TriggerService), as the client would dig it. */
function cell () {
  const id = bot.registry.blocksByName.sculk_vein.id
  const cells = bot.findBlocks({ matching: id, maxDistance: 6, count: 16 })
  if (!cells.length) throw new Error('no capture cell in front of the crosshair')
  const eye = bot.entity.position.offset(0, 1.62, 0)
  return cells.reduce((a, b) => a.distanceTo(eye) < b.distanceTo(eye) ? a : b)
}
/** A quick click: dig start, a swing and the abort in one go. */
function click () {
  const at = cell()
  dig(START, at)
  swing()
  dig(ABORT, at)
}
/** The trigger held for `ms`: a dig start, a swing every tick, the abort at the end. */
async function press (ms) {
  const at = cell()
  dig(START, at)
  const until = Date.now() + ms
  while (Date.now() < until) { swing(); await sleep(50) }
  dig(ABORT, at)
}
/** Waits until `ms` after `from` (a Date.now()). */
const until = (from, ms) => sleep(Math.max(0, from + ms - Date.now()))

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
  await sleep(1200) // the draw, and any reload a slot switch cut short
  dig(4) // Q
  await sleep(6500)
}

async function aim (spot) {
  bot.clearControlStates()
  await rcon(`execute as ${name} at @s run tp @s ${spot.x.toFixed(3)} ${spot.y} ${spot.z.toFixed(3)} 180 0`)
  await sleep(500)
}

// ---------------------------------------------------------------- bolt / pump
/**
 * One shot from `gid`, over to the P18 and straight back, clicks at `quiet` ms after the shot that must
 * not fire, then one at `fires` ms that must, `cycle` ticks or more after the first.
 */
async function cycle (spot, gid, cycleTicks, quiet, fires) {
  await arm('p18')
  await arm(gid)
  await aim(spot)
  const from = logSize()
  click()
  const shotAt = Date.now()
  await sleep(100)
  hold('p18')
  await sleep(100)
  hold(gid)
  for (const ms of quiet) {
    await until(shotAt, ms)
    click()
  }
  await sleep(300)
  const early = shotsSince(from)
  const draws = drawsSince(from).filter(d => d.gun === gid)
  await until(shotAt, fires)
  click()
  await sleep(500)
  const shots = shotsSince(from)
  const back = draws[draws.length - 1]
  const first = shots[0]
  log(`${gid}: shots at ticks ${shots.map(s => s.tick).join(',')}; drawn again at tick ${back && back.tick}, up at ${back && back.ready}, may fire at ${back && back.next}`)
  // By server ticks: a loaded server can run a few ticks back to back, so a click sent at a given
  // millisecond may land a tick or two later than the wall clock says.
  check(`${gid}: switched away and back, clicks up to ${quiet[quiet.length - 1]} ms after a shot fire nothing before the cycle is over`,
    !!back && !!first && early.slice(1).every(s => s.tick >= first.tick + cycleTicks && s.tick >= back.ready),
    `${early.length - 1} shots from them${early.length > 1 ? ' at ' + early.slice(1).map(s => s.tick).join(',') : ''}; cycle over at ${first && first.tick + cycleTicks}, up at ${back && back.ready}`)
  check(`${gid}: the gun drawn again keeps its cycle`, !!back && !!first && back.next === first.tick + cycleTicks,
    back ? `may fire at ${back.next}, shot at ${first && first.tick}` : 'no draw logged')
  check(`${gid}: by ${fires} ms it has fired again, ${cycleTicks}+ ticks after the first`, shots.length === 2 && shots[1].tick - shots[0].tick >= cycleTicks,
    `${shots.length} shots${shots.length === 2 ? ', ' + (shots[1].tick - shots[0].tick) + ' ticks apart' : ''}`)
}

// ---------------------------------------------------------------- draw
async function draw (spot) {
  await arm('p18')
  await arm('m4c')
  await aim(spot)
  // Clicks before the pistol is up do nothing; one after it does.
  let from = logSize()
  const barsFrom = bars.length
  const slotsFrom = slotUpdates.length
  const pistolSlot = hotbar('p18')
  hold('p18')
  let swapped = Date.now()
  for (const ms of [50, 120, 200]) {
    await until(swapped, ms)
    click()
  }
  await sleep(150)
  const early = shotsSince(from)
  const mid = logSize()
  await until(swapped, 500)
  click()
  await sleep(300)
  let d = drawsSince(from).find(x => x.gun === 'p18')
  const late = shotsSince(mid)
  check('draw: P18 draws in 6 ticks', !!d && d.ready - d.tick === 6, d ? `${d.ready - d.tick} ticks` : 'no draw logged')
  // By server ticks, as in cycle(): a click 200 ms in may land on the tick the pistol is up.
  check('draw: clicks in the first 200 ms after the switch fire nothing before the gun is up', !!d && early.every(s => s.tick >= d.ready),
    `drawn at ${d && d.tick}, up at ${d && d.ready}, ${early.length} shots from them${early.length ? ' at ' + early.map(s => s.tick).join(',') : ''}`)
  check('draw: a click at 500 ms fires', late.length === 1, `${late.length} shots`)
  check('draw: a click while drawing says so', bars.slice(barsFrom).some(b => b.at - swapped < 400 && b.text.includes('拔枪中')),
    `${bars.length - barsFrom} action bar lines`)
  // The mark goes on with the switch and comes off when the gun is up (6 ticks), by the slot updates.
  const marks = slotUpdates.slice(slotsFrom).filter(u => u.slot === pistolSlot)
  const on = marks.find(u => u.damage === 1)
  const off = on && marks.find(u => u.at > on.at && u.damage === 0)
  check('draw: the pistol carries the draw mark from the switch until it is up', !!on && !!off && on.at - swapped < 200 &&
    off.at - on.at >= 200 && off.at - on.at <= 450 && damageOf(bot.inventory.slots[pistolSlot]) === 0,
    on ? `marked ${on.at - swapped} ms after the switch, cleared ${off ? off.at - on.at + ' ms later' : 'never'}` : 'never marked')

  // A gun switched away from before it is up loses the mark.
  const rifleSlot = hotbar('m4c')
  const leaveFrom = slotUpdates.length
  hold('m4c')
  await sleep(150) // the M4C takes 12 ticks
  hold('p18')
  await sleep(700)
  const rifle = slotUpdates.slice(leaveFrom).filter(u => u.slot === rifleSlot).map(u => u.damage)
  check('draw: a gun switched away from mid-draw loses the mark', rifle.includes(1) && damageOf(bot.inventory.slots[rifleSlot]) === 0,
    `M4C slot damage over the switch: ${rifle.join(',')}`)

  // The trigger held from the moment of the switch fires once, as the gun comes up.
  hold('m4c')
  await sleep(1000)
  from = logSize()
  hold('p18')
  await press(700)
  await sleep(300)
  d = drawsSince(from).find(x => x.gun === 'p18')
  const held = shotsSince(from)
  check('draw: the trigger held through the draw fires once, when the gun is up', !!d && held.length === 1 && held[0].tick >= d.ready && held[0].tick <= d.ready + 2,
    `${held.length} shots at ${held.map(s => s.tick).join(',')}; drawn at ${d && d.tick}, up at ${d && d.ready}`)

  // Right mouse held from the switch (a use packet every 4 ticks, as vanilla repeats it) aims once the gun is up.
  hold('m4c')
  await sleep(1000)
  const adsFrom = adsHeard.length
  from = logSize()
  hold('p18')
  swapped = Date.now()
  for (let i = 0; i < 5; i++) { useItem(); await sleep(200) }
  await sleep(300)
  const ads = adsHeard.slice(adsFrom)
  d = drawsSince(from).find(x => x.gun === 'p18')
  const up = upsSince(from).find(x => x.gun === 'p18')
  // It now aims on the very tick the gun is up (6 ticks, 300 ms), and the server's ticks are not all
  // 50 ms long: a tick of slack here, the exact tick is the next check.
  check('draw: right mouse held from the switch aims only once the gun is up', ads.length >= 1 && ads[0] - swapped >= 250,
    ads.length ? `aimed ${ads[0] - swapped} ms after the switch` : 'never aimed')
  check('draw: and aims on the tick it is up, not with the next use packet', !!d && !!up && up.tick === d.ready && up.aim,
    up ? `up at tick ${up.tick} (ready ${d && d.ready}), aiming ${up.aim}` : 'no up logged')
}

// ---------------------------------------------------------------- bloom
async function bloom (spot) {
  await arm('p18')
  await arm('m4c')
  await aim(spot)
  const from = logSize()
  await press(1200)
  const spray = shotsSince(from)
  hold('p18')
  await sleep(100)
  hold('m4c')
  await sleep(800) // M4C draws in 12 ticks
  const after = logSize()
  click()
  await sleep(400)
  const next = shotsSince(after)
  const last = spray[spray.length - 1]
  const first = next[0]
  const expected = last && first ? Math.max(0, Math.min(3, last.bloom + 0.35) - 0.08 * (first.tick - last.tick)) : NaN
  log(`spray: ${spray.length} shots, last at tick ${last && last.tick} with bloom ${last && last.bloom}; after the switch: ${next.map(s => `tick ${s.tick} bloom ${s.bloom}`).join(', ')}`)
  check('bloom: a spray built bloom', spray.length >= 10 && last.bloom >= 2.5, `${spray.length} shots, bloom ${last && last.bloom}`)
  check('bloom: switching away and back keeps it, less 0.08 a tick', !!first && first.bloom > 0 && Math.abs(first.bloom - expected) <= 0.2,
    first ? `bloom ${first.bloom}, expected about ${expected.toFixed(2)}` : 'no shot after the switch')
  await sleep(2500)
}

// ---------------------------------------------------------------- cut
async function cut (spot) {
  await arm('p18')
  await arm('m4c')
  await aim(spot)
  const at = cell()
  for (let i = 0; i < 3; i++) { dig(START, at); swing(); dig(ABORT, at); await sleep(250) }
  await sleep(300)
  const barsFrom = bars.length
  const coolFrom = cooldowns.length
  dig(4) // Q
  await sleep(400)
  hold('p18')
  await sleep(500)
  const said = bars.slice(barsFrom).map(b => b.text)
  const cools = cooldowns.slice(coolFrom).map(c => c.ticks)
  check('cut: switching away mid-reload says the reload was cut short', said.some(t => t.includes('换弹已中断')), `${said.length} action bar lines`)
  check('cut: and stops the reload animation', cools.length >= 2 && cools[0] > 0 && cools[cools.length - 1] === 0, `reload cooldowns ${cools.join(',')}`)
}

;(async () => {
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  await enterRange()
  await sleep(500)
  const spot = bot.entity.position.clone()
  log('on the range at', spot.toString())
  for (const scenario of scenarios) {
    log('---', scenario)
    if (scenario === 'bolt') await cycle(spot, 'awr', 29, [400, 700, 1000, 1250], 1600)
    else if (scenario === 'pump') await cycle(spot, 'r12', 17, [300, 400, 500, 600, 700], 1200)
    else if (scenario === 'draw') await draw(spot)
    else if (scenario === 'bloom') await bloom(spot)
    else if (scenario === 'cut') await cut(spot)
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
