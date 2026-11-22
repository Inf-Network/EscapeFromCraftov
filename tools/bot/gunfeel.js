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
const scenarios = wanted.length ? wanted : ['lobby', 'kick', 'ads']

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
  move: Number((l.match(/ move=([0-9.]+)/) || [])[1])
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
