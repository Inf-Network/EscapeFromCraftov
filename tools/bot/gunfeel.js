// Gun feel (issue #8), on the private range unless said otherwise.
//
//   lobby   The trigger held in the lobby, off the range, fires nothing and kicks nothing.
//   feed    With a second bot, solo on the proving ground (test mode, test.enabled: true): the feed
//           starts empty; two soldiers frozen at 1 health and the second bot at 3 health, shot dead one
//           after the other within 6 s. The shooter's kill feed (/cv feed) holds three lines, the bot last, and
//           a streak of 3; the shooter hears kill.1, kill.2 and kill.3 and no plain kill sound; no kill
//           line reaches the chat; the other bot's feed holds its own death but not the soldiers. The
//           shooter's settlement screen (after /kill) counts every round fired as fired standing still.
//   kick    The first-person kick and flash follow the shots the server fires: every shot sends
//           exactly one craftov:reload cooldown of the gun's kick time (2 ticks for the K47 at 600
//           rpm, 3 for the P18, 6 for the AWR), as many as the server logged shots in a 1 s K47
//           burst; a P18 held down after its shot and an AWR held for 2 s through its bolt cycle get
//           one each. Q starts the reload
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
//           standing shots count no movement at all, and neither do 10 shots strafing while sneaking.
//   magazine Solo on the proving ground, after feed: the kill feed starts empty (nothing of feed's raid).
//           A K47 loaded with PS switched to AP (sneak+Q) leaves the 价值 on the HUD where it was, and
//           so does writing the magazine to the stack after: the new magazine is in memory, the stack
//           still says 30 PS, the inventory has the PS back and 30 AP fewer, and the inventory's value
//           counts the magazine in memory. It dies at the end: run it after lore, which needs the
//           starting kit's pockets for eleven guns.
//   batch   K47 single shots while strafing with the movement packets held back and sent four at a
//           time every 200 ms (a lagging or cheating client): every shot still reads as moving.
//   lore    In the lobby, every gun's tooltip has the seven ten-segment stat bars (伤害 射速 精准 开镜 后坐
//           弹匣 穿透) and the line under them; a gun with the better number never has the shorter
//           bar; in the gunsmith the vertical grip's button previews a longer accuracy bar (green), and
//           fitted to the K47 its own accuracy bar gains segments while its hip spread drops.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node gunfeel.js [scenario ...]
//
// Needs `debug: true` in the server's plugins/Craftov/config.yml (shots are read from the log).
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
//
// Server config (tools/bot/suite.js sets it for its run): debug true (shots are read from the
// log); test.enabled true (the bots queue for the proving ground, a test map) for feed and
// magazine; match.wait-seconds 3.
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
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['lobby', 'lore', 'feed', 'magazine', 'kick', 'ads', 'recoil', 'move', 'batch']

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
let worldName = ''
const trackWorld = p => { if (p.worldState && p.worldState.name) worldName = p.worldState.name.replace(/^minecraft:/, '') }
bot._client.on('login', trackWorld)
bot._client.on('respawn', trackWorld)
/** Craftov sounds the bot heard, and its chat lines: { at, name } / { at, text }. */
const sounds = []
for (const packet of ['sound_effect', 'entity_sound_effect']) {
  bot._client.on(packet, p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_./]+/); if (m) sounds.push({ at: Date.now(), name: m[0] }) })
}
const chat = []
bot.on('messagestr', m => chat.push({ at: Date.now(), text: m }))
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
// mineflayer files attribute updates under the packet's key (generic.movement_speed), but its physics
// reads minecraft:movement_speed: without this the bot ignores the server slowing it (aiming does, on
// the range too), walks faster than it may, and the server takes the strafe for running.
bot.on('entityAttributes', e => {
  if (e === bot.entity && e.attributes['generic.movement_speed']) e.attributes['minecraft:movement_speed'] = e.attributes['generic.movement_speed']
})

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

// ---------------------------------------------------------------- magazine
/**
 * The gear value the raid HUD shows (◈ in the info panel), in coins, or NaN. The HUD draws it in the
 * pixel font, which the boss bar carries as glyphs a bot cannot read, so `cv hud` prints the
 * snapshot's figure instead.
 */
async function raidValue () {
  const m = String(await rcon(`cv hud ${name}`)).match(/value=(\d+)/)
  return m ? Number(m[1]) : NaN
}

async function magazine () {
  bot.chat('/cv queue proving NORMAL SOLO')
  const by = Date.now() + 150000
  while (Date.now() < by && !inRaid(worldName)) await sleep(500)
  if (!inRaid(worldName)) throw new Error('not in a raid: ' + worldName)
  await sleep(7000) // the insertion camera and spawn protection
  try {
    const fresh = await feedOf(name)
    check('magazine: the raid starts with an empty kill feed (after feed\'s raid)', fresh.some(l => l.includes('击杀栏是空的')), fresh)
    await rcon(`clear ${name}`) // no other 7.62x39 rounds for sneak+Q to pick
    await sleep(500)
    await rcon(`cv give ${name} k47`) // loaded with 30 PS
    await rcon(`cv give ${name} ammo_762_ap 60`)
    await sleep(1000)
    hold('k47')
    await sleep(2500) // the draw, and the HUD catching up
    const before = await raidValue()
    bot.setControlState('sneak', true)
    await sleep(300)
    dig(4) // sneak+Q: the next ammo type, AP
    await sleep(300)
    bot.setControlState('sneak', false)
    await sleep(5000) // the reload
    const after = await raidValue()
    const ap = bot.inventory.items().filter(i => itemId(i) && itemId(i).includes('ammo_762_ap')).reduce((n, i) => n + i.count, 0)
    log('magazine: HUD value', before, '->', after, '· AP left in the inventory', ap)
    check('magazine: the reload to AP took 30 rounds from the inventory', ap === 30, `${ap} AP left`)
    check('magazine: switching the K47 to AP keeps the 价值 on the HUD (within its rounding)', before > 0 && Math.abs(after - before) <= 100,
      `${before} before, ${after} after`)
    bot.setQuickBarSlot((hotbar('k47') - 36 + 1) % 9) // away and back: the magazine is written to the stack
    await sleep(600)
    hold('k47')
    await sleep(2000)
    const written = await raidValue()
    check('magazine: and once the magazine is written to the stack it is still the same', Math.abs(written - before) <= 100, `${written}`)
  } finally {
    await rcon(`kill ${name}`)
    const out = Date.now() + 20000
    while (Date.now() < out && inRaid(worldName)) await sleep(500)
    await sleep(3000)
  }
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
  const sneaked = []
  for (const mode of ['stand', 'walk', 'stand-ads', 'walk-ads', 'sneak-walk']) {
    await aim(spot)
    dig(4) // a full magazine for each series
    await sleep(3200)
    if (mode === 'sneak-walk') bot.setControlState('sneak', true)
    let stopAim = null
    if (mode.endsWith('ads')) { useItem(); const t = setInterval(useItem, 200); stopAim = () => clearInterval(t) }
    await sleep(600)
    const from = logSize()
    for (let leg = 0; leg < 5; leg++) {
      const dir = leg % 2 ? 'right' : 'left'
      if (mode.includes('walk')) bot.setControlState(dir, true)
      await sleep(450)
      for (let shot = 0; shot < 2; shot++) {
        // What the bot really walks over the 200 ms before the shot, in blocks per tick.
        const a = bot.entity.position.clone()
        await sleep(200)
        const b = bot.entity.position
        if (mode === 'walk') walked.push(Math.hypot(b.x - a.x, b.z - a.z) / 4)
        if (mode === 'sneak-walk') sneaked.push(Math.hypot(b.x - a.x, b.z - a.z) / 4)
        // A strafing bot can dig a capture cell the server has just moved on: click again until it fires.
        const before = shotsSince(from).length
        for (let tries = 0; tries < 3 && shotsSince(from).length === before; tries++) { click(); await sleep(120) }
        await sleep(130)
      }
      if (mode.includes('walk')) bot.setControlState(dir, false)
      await sleep(300)
    }
    if (stopAim) stopAim()
    bot.setControlState('sneak', false)
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
  check('move: sneak-strafing shots count no movement', avg(sneaked) > 0.04 && series['sneak-walk'].every(x => x.level === 0),
    `sneaked ${avg(sneaked).toFixed(3)} blocks/tick, levels ${series['sneak-walk'].map(x => x.level.toFixed(2)).join(' ')}`)
  check('move: strafing hip fire >= 1.8x standing', sp('walk') >= 1.8 * sp('stand'), `${sp('walk').toFixed(2)} vs ${sp('stand').toFixed(2)} = ${(sp('walk') / sp('stand')).toFixed(2)}x`)
  check('move: strafing aimed fire >= 2.5x standing aimed', sp('walk-ads') >= 2.5 * sp('stand-ads'),
    `${sp('walk-ads').toFixed(2)} vs ${sp('stand-ads').toFixed(2)} = ${(sp('walk-ads') / sp('stand-ads')).toFixed(2)}x`)
}
const avg = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0

// ---------------------------------------------------------------- batch
/** While true the bot's movement packets are held back, and sent together every 200 ms. */
let holdingMoves = false
const heldMoves = []
const writePacket = bot._client.write.bind(bot._client)
bot._client.write = (packet, params) => {
  if (holdingMoves && (packet === 'position' || packet === 'position_look')) {
    heldMoves.push([packet, { ...params, flags: { ...params.flags } }]) // mineflayer reuses the object
    return
  }
  writePacket(packet, params)
}
setInterval(() => { while (heldMoves.length) writePacket(...heldMoves.shift()) }, 200)

async function batch (spot) {
  await arm('k47')
  await aim(spot)
  dig(4)
  await sleep(3200)
  const from = logSize()
  holdingMoves = true
  try {
    for (let leg = 0; leg < 4; leg++) {
      const dir = leg % 2 ? 'right' : 'left'
      bot.setControlState(dir, true)
      await sleep(450)
      for (let shot = 0; shot < 4; shot++) {
        const before = shotsSince(from).length
        for (let tries = 0; tries < 3 && shotsSince(from).length === before; tries++) { click(); await sleep(120) }
        await sleep(60 + 50 * shot) // so the shots land on every tick of a batch
      }
      bot.setControlState(dir, false)
      await sleep(300)
    }
  } finally {
    holdingMoves = false
    bot.clearControlStates()
  }
  await sleep(400)
  const shots = shotsSince(from)
  check('batch: strafing with movement sent four ticks at a time reads as moving on every shot',
    shots.length >= 10 && shots.every(x => x.speed >= 0.15), `${shots.length} shots, server read ${shots.map(x => x.speed.toFixed(3)).join(' ')}`)
}

// ---------------------------------------------------------------- lore
const nbt = require('prismarine-nbt')
const GUNS = ['p18', 'd50', 'sv9', 'v57', 'k47', 'm4c', 'scr', 'kl7', 'r12', 'dm14', 'awr']
const STATS = ['伤害', '射速', '精准', '开镜', '后坐', '弹匣', '穿透']
/** Stats where the smaller number is the better one (a longer bar). */
const SMALLER_BETTER = new Set(['精准', '开镜', '后坐'])

/** An item's lore as lines of { color, text } runs, in order, colours inherited as the client does. */
function loreLines (item) {
  const comp = ((item && item.components) || []).find(c => c.type === 'lore')
  if (!comp) return []
  return comp.data.map(line => {
    const runs = []
    const walk = (node, color) => {
      if (node === null || node === undefined) return
      if (typeof node === 'string' || typeof node === 'number') { runs.push({ color, text: String(node) }); return }
      if (Array.isArray(node)) { node.forEach(n => walk(n, color)); return }
      const c = node.color || color
      if (node.text !== undefined) runs.push({ color: c, text: String(node.text) })
      if (node[''] !== undefined) runs.push({ color: c, text: String(node['']) })
      if (node.extra) node.extra.forEach(n => walk(n, c))
    }
    let simple = line
    try { simple = nbt.simplify(line) } catch (e) { /* already plain */ }
    walk(simple, null)
    return runs
  })
}

/**
 * The stat bars of a lore: label -> { filled, gain, loss, total, value } where filled counts the bar's
 * lit segments (gains included), and value is the number after the bar (damage x pellets multiplied).
 */
function barsOf (item) {
  const out = {}
  for (const runs of loreLines(item)) {
    const text = runs.map(r => r.text).join('')
    const label = STATS.find(s => text.startsWith(s + ' '))
    if (!label || !text.includes('▍')) continue
    let filled = 0; let gain = 0; let loss = 0; let total = 0
    for (const r of runs) {
      const n = (r.text.match(/▍/g) || []).length
      total += n
      const c = String(r.color || '').toUpperCase()
      // The palette's ok, danger and line colours (gui/Style).
      if (c === '#7FD1AE') gain += n
      if (c === '#E5484D') loss += n
      if (c !== '#2C333D' && c !== '#E5484D') filled += n
    }
    const tail = text.slice(text.lastIndexOf('▍') + 1).trim()
    const nums = (tail.match(/[\d.]+/g) || []).map(Number)
    const value = nums.length === 2 && tail.includes('×') ? nums[0] * nums[1] : nums[0]
    out[label] = { filled, gain, loss, total, value, text }
  }
  return out
}

async function lore () {
  await rcon(`execute as ${name} at @s run tp @s 0.5 65 6.5 0 0`)
  await sleep(1000)
  for (const gun of GUNS) if (!bot.inventory.slots.some(s => s && itemId(s) === gun)) await rcon(`cv give ${name} ${gun}`)
  await sleep(1500)
  const bars = {}
  for (const gun of GUNS) {
    const item = bot.inventory.slots.find(s => s && itemId(s) === gun)
    bars[gun] = barsOf(item)
  }
  log('K47 bars', Object.values(bars.k47).map(b => b.text))
  log('lit segments (' + GUNS.join('/') + ')', STATS.map(s => s + ' ' + GUNS.map(g => bars[g][s] ? bars[g][s].filled : '-').join('/')))
  const complete = GUNS.filter(g => STATS.every(s => bars[g][s] && bars[g][s].total === 10))
  check('lore: every gun has the seven ten-segment stat bars', complete.length === GUNS.length,
    GUNS.filter(g => !complete.includes(g)).map(g => g + ': ' + Object.keys(bars[g]).join(',')).join(' | '))
  const hint = loreLines(bot.inventory.slots.find(s => s && itemId(s) === 'k47')).some(runs => runs.map(r => r.text).join('').includes('条越长越好'))
  check('lore: a line under the bars says how to read them', hint)
  // A gun with a better number never has a shorter bar.
  const wrong = []
  for (const stat of STATS) {
    for (const a of GUNS) {
      for (const b of GUNS) {
        const x = bars[a][stat]; const y = bars[b][stat]
        if (!x || !y) continue
        const better = SMALLER_BETTER.has(stat) ? x.value < y.value : x.value > y.value
        if (better && x.filled < y.filled) wrong.push(`${stat} ${a} ${x.value} (${x.filled}) vs ${b} ${y.value} (${y.filled})`)
      }
    }
  }
  check('lore: the bars rank the guns as their numbers do', wrong.length === 0, wrong.slice(0, 4).join(' | '))

  // A vertical grip in the gunsmith: its button previews the accuracy bar going up, and once fitted
  // the gun's own accuracy bar shows the gain.
  await rcon(`cv give ${name} att_vgrip`)
  await sleep(800)
  hold('k47')
  await sleep(1500)
  const window = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 5000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    bot.chat('/cv mod')
  })
  if (!window) throw new Error('the gunsmith did not open')
  await sleep(800)
  const partSlot = window.slots.findIndex((s, i) => i >= 45 && i < 54 && s && itemId(s) === 'att_vgrip')
  const preview = partSlot >= 0 ? barsOf(window.slots[partSlot]) : {}
  check('lore: the grip\'s button in the gunsmith previews a longer accuracy bar', !!preview['精准'] && preview['精准'].gain > 0,
    preview['精准'] ? preview['精准'].text : `no preview (part at ${partSlot})`)
  if (partSlot >= 0) await bot.clickWindow(partSlot, 0, 0)
  await sleep(1200)
  const fitted = barsOf(window.slots[22])
  bot.closeWindow(window)
  await sleep(500)
  const before = bars.k47['精准']
  const after = fitted['精准']
  check('lore: the K47 with the grip fitted shows its accuracy bar gain and a smaller spread',
    !!after && after.gain > 0 && after.value < before.value && after.filled > before.filled,
    after ? `${before.text} -> ${after.text}` : 'no bar after fitting')
}

// ---------------------------------------------------------------- feed
const inRaid = world => world.startsWith('craftov_raid_')
const feedOf = async who => (await rcon(`cv feed ${who}`)).split('\n').map(l => l.trim()).filter(Boolean)

async function feed () {
  const victimName = 'Vic' + name.slice(4)
  const victim = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: victimName, version: '1.21.11', auth: 'offline' })
  victim._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) victim._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  let victimWorld = ''
  const track = p => { if (p.worldState && p.worldState.name) victimWorld = p.worldState.name.replace(/^minecraft:/, '') }
  victim._client.on('login', track)
  victim._client.on('respawn', track)
  await new Promise(resolve => victim.once('spawn', resolve))
  await sleep(2500)
  try {
    bot.chat('/cv queue proving NORMAL SOLO')
    victim.chat('/cv queue proving NORMAL SOLO')
    log('both queued for the proving ground')
    const until = Date.now() + 150000
    while (Date.now() < until && !(inRaid(worldName) && inRaid(victimWorld))) await sleep(500)
    if (!(inRaid(worldName) && worldName === victimWorld)) throw new Error(`not in one raid: ${worldName} / ${victimWorld}`)
    await sleep(7000) // the insertion camera and spawn protection
    const fresh = await feedOf(name)
    check('feed: the raid starts with an empty feed (nothing from an earlier raid)', fresh.some(l => l.includes('击杀栏是空的')), fresh)
    const at = `execute at ${name} run`
    await rcon(`${at} fill 39 64 85 52 64 100 minecraft:stone`)
    await rcon(`${at} fill 39 65 85 52 79 100 minecraft:air`)
    // Two soldiers, frozen at 1 health in front of the shooter; every other one goes, and keeps going.
    for (const tag of ['gf_s1', 'gf_s2']) {
      const by = Date.now() + 30000
      while (Date.now() < by && !(await rcon(`execute if entity @e[tag=${tag}]`)).includes('passed')) {
        // A raid soldier (craftov:ai), in the shooter's world: range targets are zombies too.
        await rcon(`${at} tag @e[type=minecraft:zombie,tag=!gf_keep,distance=..2000,limit=1,nbt={BukkitValues:{"craftov:ai":"patrol"}}] add ${tag}`)
        await sleep(500)
      }
      await rcon(`tag @e[tag=${tag}] add gf_keep`)
    }
    const sweep = setInterval(() => {
      for (const type of ['zombie', 'husk', 'drowned', 'bee']) rcon(`${at} kill @e[type=minecraft:${type},tag=!gf_keep,distance=..2000]`).catch(() => {})
    }, 1500)
    try {
      await rcon(`data merge entity @e[tag=gf_s1,limit=1] {NoAI:1b,Health:1f}`)
      await rcon(`data merge entity @e[tag=gf_s2,limit=1] {NoAI:1b,Health:1f}`)
      await rcon(`${at} tp @e[tag=gf_s1,limit=1] 43.5 65 92.5 180 0`)
      await rcon(`${at} tp @e[tag=gf_s2,limit=1] 47.5 65 92.5 180 0`)
      await rcon(`execute as ${victimName} at @s run tp @s 45.5 65 96.5 180 0`)
      await rcon(`execute as ${name} at @s run tp @s 45.5 65 86.5 0 0`)
      await rcon(`cv give ${name} k47`)
      await sleep(1000)
      hold('k47')
      await sleep(1500)
      await rcon(`damage ${victimName} 97 minecraft:generic`)
      await sleep(300)
      const since = Date.now()
      const kills = [['gf_s1', 43.5, 92.5], ['gf_s2', 47.5, 92.5], [victimName, 45.5, 96.5]]
      for (const [who, x, z] of kills) {
        await bot.lookAt(bot.entity.position.offset(x - bot.entity.position.x, 1.1, z - bot.entity.position.z), true)
        await sleep(200)
        const sel = who.startsWith('gf_') ? `@e[tag=${who}]` : who
        for (let i = 0; i < 8; i++) {
          click()
          await sleep(160)
          const alive = who.startsWith('gf_') ? (await rcon(`execute if entity ${sel}`)).includes('passed') : victimWorld === worldName && !victim.game.gameMode.includes('spectator')
          if (!alive) break
        }
      }
      const took = Date.now() - since
      await sleep(600)
      const lines = await feedOf(name)
      const shown = lines.filter(l => l.includes('→'))
      log(`three kills in ${took} ms; feed:`, lines)
      const chimes = sounds.filter(s => s.at >= since && /craftov:(kill\.\d|hit\.kill)/.test(s.name)).map(s => s.name.replace('craftov:', ''))
      check('feed: three kills within 6 s', took < 6000 && victim.game.gameMode === 'spectator', `${took} ms, victim ${victim.game.gameMode}`)
      check('feed: three lines in the shooter\'s feed, the victim last', shown.length === 3 && shown[2].includes(victimName) && shown[2].startsWith(name), shown)
      check('feed: a streak of 3', lines.some(l => l.includes('连杀 3')), lines[lines.length - 1])
      check('feed: the kills play kill.1, kill.2, kill.3 in turn and no plain kill sound', chimes.join(',') === 'kill.1,kill.2,kill.3', chimes)
      check('feed: no kill lines in the chat', !chat.some(c => c.at >= since && c.text.includes('→')), chat.filter(c => c.at >= since).map(c => c.text))
      const theirs = (await feedOf(victimName)).filter(l => l.includes('→'))
      check('feed: the victim\'s feed has its own death and not the shooter\'s soldiers', theirs.length === 1 && theirs[0].includes(victimName), theirs)
    } finally {
      clearInterval(sweep)
    }
    // Out of the raid, the settlement screen says how many rounds were fired standing still (in the
    // outcome button's lore, slot 8).
    const settled = new Promise(resolve => {
      const timer = setTimeout(() => resolve(null), 25000)
      const seen = w => setTimeout(() => {
        const lines = loreLines(w.slots[8]).map(runs => runs.map(r => r.text).join(''))
        if (lines.some(l => l.includes('急停射击'))) { clearTimeout(timer); bot.removeListener('windowOpen', seen); resolve(lines) }
      }, 600)
      bot.on('windowOpen', seen)
    })
    await rcon(`kill ${name}`)
    const lines = await settled
    const line = (lines || []).find(l => l.includes('急停射击'))
    const m = line && line.match(/开火 (\d+) 发 · 急停射击 (\d+) 发/)
    check('feed: the settlement counts the rounds fired, all of them standing still', !!m && Number(m[1]) >= 3 && m[1] === m[2], line || lines)
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  } finally {
    victim.quit()
    await sleep(3000) // back in the lobby before the next scenario
  }
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
    if (scenario === 'magazine') { await magazine(); spot = null; continue }
    if (scenario === 'feed') { await feed(); spot = null; continue }
    if (scenario === 'lore') { await lore(); spot = null; continue }
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
    else if (scenario === 'batch') await batch(spot)
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
