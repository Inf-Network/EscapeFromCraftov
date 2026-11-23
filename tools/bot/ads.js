// Aiming down sights (issue #5): the zoom each sight shows, worked out from the packets the vanilla
// client builds its field of view from, on the private range unless said otherwise.
//
// The 1.21.11 client's field-of-view multiplier is lerp(s, 1, (speed / walk + 1) / 2): speed its
// movement-speed attribute (base and every modifier the server sent), walk the walking speed of the
// last abilities packet, s the FOV Effects slider. This bot keeps both as the client would and
// computes what a client with slider s shows.
//
//   hip      In the lobby and on the range, standing, sneaking and after Q: the hip view is 1.000
//            (within 0.002) at every slider setting, so nothing that slows the player narrows it.
//   optics   Every sight on the range (iron sights of the P18, R12 and K47, the red dot and holo on
//            the K47, 3x and 4x on the K47, the DM14's 4x and the AWR's 8x), right mouse held (a use
//            packet every 4 ticks): once up, the view is the sight's target multiplier
//            2 atan(tan(F/2) / N) / F within 0.005, and the magnification tan(F/2) / tan(F m / 2) is
//            N within 2%, at F 70 and 90 (/cv fov F 100). No declaration zooms further than vanilla's
//            defaults: FOV Effects declared at 50% sends the same view as 100% (the server does not
//            compensate unless weapons.ads.fov-effects-compensation is on, see effects), and a
//            declared FOV of 50 the view for 70. Let go, the view is back to 1.000 within 8 ticks.
//   effects  The compensation turned on in the server's config.yml (SERVER_DIR, default the
//            repository's server/run; restored after) and /cv reload: the K47's iron sights, its 4x
//            and the AWR's 8x show their target for a slider declared at 50 and 25%, and the
//            declaration is in the server log.
//   resume   The AWR (8x, 9 ticks up): up, then the use packets stop until the server lowers the
//            sights, and right mouse comes straight back (a late packet): no new rise (no aiming
//            mark), the zoom (the first view sent is the scope's zoom, or steered past it) and the
//            scope picture are back at once. Back only after 8 ticks: a new rise from the hip. One
//            use packet only (the server lowers the sights part way up), then back: the rise goes on
//            from there, up in 1 to 6 ticks rather than 9, its frames' cooldown again after the
//            aiming mark.
//   time     K47 iron sights, P18, AWR (5, 3 and 9 ticks by their handling): the view the vanilla
//            client shows (worked out from the views sent: it goes half way to the last one each
//            tick) narrows over the gun's ads time and not at once (half way through the n - 1 ticks
//            it shows an n-tick rise in, it is between hip and aimed), and is at the sight's zoom
//            (95% of it) by the tick after the sights are up;
//            the hand mark on the held stack says aiming (3) once the server takes the aim, aimed
//            (4) when the sights are up, none once let go, and the cooldown that runs the frames
//            comes after the aiming mark (with them together the client's old stack would read it
//            as a shot's kick and flash); with no use packet the server never starts aiming.
//   mode     Aiming with the 4x, the gamemode changed by RCON to creative and back: the zoom holds.
//   helmet   Aiming with the 4x while wearing a helmet, the helmet replaced by RCON (as a headshot
//            that wears it does): every head-slot update the bot gets from then on (its inventory
//            slot, or equipment about itself) still carries the scope overlay, on the helmet now
//            worn (the new one, not the old); taken off, on the blank item; let go, the real
//            helmet comes back.
//   heavy    Solo on the proving ground (test mode, test.enabled: true): helmet_6, armor_6, pack_3
//            and eight 3 kg reactor cells: the hip view is still 1.000 within 0.002, aimed the K47's 1.25x.
//
//   PORT=25565 RCON_PORT=25575 [SERVER_DIR=...] node ads.js [scenario ...]
//
// Server config: the defaults (weapons.ads.timeout-ticks 5, scope-out-ticks 2, resume-ticks 3,
// fov-effects-compensation false).
//
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')
const fs = require('fs')
const path = require('path')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const SERVER_DIR = env.SERVER_DIR
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['hip', 'optics', 'effects', 'time', 'resume', 'mode', 'helmet', 'heavy']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const name = 'Ads' + Math.floor(10000 + Math.random() * 90000)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
let worldName = ''
const trackWorld = p => { if (p.worldState && p.worldState.name) worldName = p.worldState.name.replace(/^minecraft:/, '') }
bot._client.on('login', trackWorld)
bot._client.on('respawn', trackWorld)
const chat = []
bot.on('messagestr', m => chat.push({ at: Date.now(), text: m }))

// ---------------------------------------------------------------- what the client builds its view from
/** Movement speed as the client has it (base and every modifier the server sent), and the walking speed. */
const view = { speed: 0.1, walk: 0.1, flying: false }
/** Every change: { at, speed, walk }. */
const history = []
bot._client.on('entity_update_attributes', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const prop of p.properties) {
    if (!/movement_speed$/.test(String(prop.key))) continue
    let base = prop.value
    let scaled = 0
    let total = 1
    for (const m of prop.modifiers) {
      if (m.operation === 0) base += m.amount
      else if (m.operation === 1) scaled += m.amount
      else total *= 1 + m.amount
    }
    view.speed = (base + base * scaled) * total
    history.push({ at: Date.now(), speed: view.speed, walk: view.walk })
  }
})
bot._client.on('abilities', p => {
  view.walk = p.walkingSpeed
  view.flying = (p.flags & 2) !== 0
  history.push({ at: Date.now(), speed: view.speed, walk: view.walk })
})
/** What a client with FOV Effects at `s` shows now (1 = the normal view). */
function shown (s = 1, v = view) {
  let f = v.flying ? 1.1 : 1
  if (v.walk !== 0) f *= (v.speed / v.walk + 1) / 2
  return 1 + s * (f - 1)
}
/** The multiplier that shows `power` times at a vertical field of view `fov` (degrees). */
const target = (power, fov = 70) => power <= 1 ? 1 : 2 * Math.atan(Math.tan(fov / 2 * Math.PI / 180) / power) / (fov * Math.PI / 180)
/** The magnification a multiplier `m` gives at `fov`. */
const power = (m, fov = 70) => Math.tan(fov / 2 * Math.PI / 180) / Math.tan(fov * m / 2 * Math.PI / 180)

// ---------------------------------------------------------------- the hand mark and the head slot
const marks = []
/** The hand marks and craftov:reload cooldowns in the order they arrived: 'm3', 'c6', ... */
const order = []
const damageOf = item => item && item.componentMap && item.componentMap.has('damage') ? item.componentMap.get('damage').data : 0
bot.once('spawn', () => bot.inventory.on('updateSlot', (slot, before, after) => {
  if (slot >= 36 && slot <= 44 && damageOf(before) !== damageOf(after)) {
    marks.push({ at: Date.now(), slot, damage: damageOf(after) })
    order.push('m' + damageOf(after))
  }
}))
bot._client.on('set_cooldown', p => { if (JSON.stringify(p).includes('reload')) order.push('c' + p.cooldownTicks) })
/** Head-slot updates the bot got (its own inventory slot 5 and equipment packets about itself), in order. */
const heads = []
const overlayOf = item => JSON.stringify(item || '').includes('camera_overlay') || JSON.stringify(item || '').includes('misc/scope')
/** The vanilla item a slot carries (diamond_helmet, paper ...), or null for an empty one. */
const kindOf = item => item && item.itemCount > 0 && bot.registry.items[item.itemId] ? bot.registry.items[item.itemId].name : null
const head = (via, item) => ({ at: Date.now(), via, overlay: overlayOf(item), kind: kindOf(item), blank: JSON.stringify(item || '').includes('ui/blank') })
bot._client.on('set_slot', p => { if (p.windowId === 0 && p.slot === 5) heads.push(head('slot', p.item)) })
bot._client.on('set_player_inventory', p => { if (p.slotId === 39) heads.push(head('inventory', p.contents)) })
bot._client.on('window_items', p => { if (p.windowId === 0 && p.items.length > 5) heads.push(head('content', p.items[5])) })
bot._client.on('entity_equipment', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const e of p.equipments) if (e.slot === 5) heads.push(head('equipment', e.item))
})

const itemId = item => { const m = JSON.stringify(item && (item.components || item) || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const dig = (status) => bot._client.write('block_dig', { status, location: { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
const useItem = () => bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
const hold = id => bot.setQuickBarSlot(hotbar(id) - 36)

/** Right mouse held for `ms`: a use packet every 4 ticks, as the vanilla client repeats them. `during` runs once it is held. */
async function aimFor (ms, during) {
  let stop = false
  const pump = (async () => {
    while (!stop) {
      useItem()
      await sleep(200)
    }
  })()
  try {
    await sleep(ms)
    if (during) await during()
  } finally {
    stop = true
    await pump
  }
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
  // Facing down the range, nothing within reach: a right click there aims.
  await rcon(`execute as ${name} at @s run tp @s ~ ~ ~ 180 0`)
  await sleep(500)
}

/** Gives the gun (with `optic` mounted) unless it is on the hotbar already, holds it and waits out the draw. */
async function arm (id, optic) {
  const key = optic ? `${id}+${optic}` : id
  if (hotbar(id) < 0 || armed !== key) {
    await rcon(`clear ${name}`)
    await sleep(300)
    await rcon(`cv give ${name} ${id}` + (optic ? ` 1 ${optic}` : ''))
    armed = key
  }
  await sleep(800)
  if (hotbar(id) < 0) throw new Error(id + ' did not reach the hotbar')
  hold(id)
  await sleep(1300) // the draw
}
let armed = ''

async function declare (fov, percent) {
  bot.chat(`/cv fov ${fov} ${percent}`)
  await sleep(600)
}

// ---------------------------------------------------------------- scenarios
async function hip () {
  const at = [1, 0.5, 0.25]
  const report = () => at.map(s => shown(s).toFixed(4)).join('/')
  check('hip: in the lobby the view is 1.000', at.every(s => Math.abs(shown(s) - 1) < 0.002), report())
  await enterRange()
  await arm('k47')
  check('hip: on the range with a K47 the view is 1.000', at.every(s => Math.abs(shown(s) - 1) < 0.002), report())
  bot.setControlState('sneak', true)
  await sleep(800)
  check('hip: sneaking the view is 1.000', at.every(s => Math.abs(shown(s) - 1) < 0.002), report())
  bot.setControlState('sneak', false)
  await sleep(500)
}

const SIGHTS = [
  { gun: 'p18', power: 1.1, label: 'P18 iron sights' },
  { gun: 'r12', power: 1.1, label: 'R12 iron sights' },
  { gun: 'k47', power: 1.25, label: 'K47 iron sights' },
  { gun: 'k47', optic: 'optic_reddot', power: 1.5, label: 'K47 red dot' },
  { gun: 'k47', optic: 'optic_holo', power: 1.5, label: 'K47 holo' },
  { gun: 'k47', optic: 'optic_3x', power: 3, label: 'K47 3x' },
  { gun: 'k47', optic: 'optic_4x', power: 4, label: 'K47 4x' },
  { gun: 'dm14', power: 4, label: 'DM14 built-in 4x' },
  { gun: 'awr', power: 8, label: 'AWR built-in 8x' }
]

/** Aims with the sight held for each declaration and checks the view the server sends against `expect`. */
async function aimEach (sight, cases, prefix) {
  for (const { fov, percent, s, want, power: wantPower, label } of cases) {
    await declare(fov, percent)
    let seen = 0
    await aimFor(900, async () => { seen = shown(s) })
    const got = power(seen, fov)
    check(`${prefix}: ${sight.label} ${label}: ${want.toFixed(3)}`, Math.abs(seen - want) < 0.005 && (!wantPower || Math.abs(got / wantPower - 1) < 0.02),
      `shows ${seen.toFixed(4)} = ${got.toFixed(2)}x at FOV ${fov}`)
    const letGo = Date.now()
    while (Date.now() - letGo < 1500 && Math.abs(shown(s) - 1) > 0.002) await sleep(50)
    check(`${prefix}: ${sight.label} let go: back to 1.000 within 8 ticks`, Date.now() - letGo <= 400 + 150, `${Date.now() - letGo} ms`)
    await sleep(300)
  }
}

async function optics () {
  if (!worldName.includes('craftov_range')) await enterRange()
  for (const sight of SIGHTS) {
    await arm(sight.gun, sight.optic)
    const at70 = target(sight.power, 70)
    await aimEach(sight, [
      { fov: 70, percent: 100, s: 1, want: at70, power: sight.power, label: 'at FOV 70' },
      { fov: 90, percent: 100, s: 1, want: target(sight.power, 90), power: sight.power, label: 'at FOV 90' },
      // What the server sends, as a client at 100% would show it: a claimed low slider gains nothing.
      { fov: 70, percent: 50, s: 1, want: at70, label: 'declared at FOV Effects 50%, no stronger than at 100%' },
      // Declared narrower than 70, the view for 70: never more zoom than vanilla's default gets.
      { fov: 50, percent: 100, s: 1, want: at70, label: 'declared at FOV 50, the view for FOV 70' }
    ], 'optics')
  }
  await declare(70, 100)
}

/** The server's config.yml with weapons.ads.fov-effects-compensation set to `on`, or null if it cannot be. */
function withCompensation (text, on) {
  const key = /^(\s*)fov-effects-compensation:.*$/m
  if (key.test(text)) return text.replace(key, `$1fov-effects-compensation: ${on}`)
  // An older config without the key: add it to the weapons.ads section, which then holds the default.
  const lines = text.split('\n')
  let inWeapons = false
  for (let i = 0; i < lines.length; i++) {
    if (/^weapons:\s*$/.test(lines[i])) inWeapons = true
    else if (/^\S/.test(lines[i])) inWeapons = false
    const m = inWeapons && lines[i].match(/^(\s+)ads:\s*$/)
    if (m) {
      lines.splice(i + 1, 0, `${m[1]}  fov-effects-compensation: ${on}`)
      return lines.join('\n')
    }
  }
  return null
}

async function effects () {
  const file = path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
  let original
  try { original = fs.readFileSync(file, 'utf8') } catch (e) { throw new Error('cannot read ' + file) }
  const on = withCompensation(original, true)
  if (!on) throw new Error('no weapons.ads section in ' + file)
  fs.writeFileSync(file, on)
  try {
    if (!(await rcon('cv reload')).includes('已重新加载')) throw new Error('cv reload failed')
    if (!worldName.includes('craftov_range')) await enterRange()
    for (const sight of SIGHTS.filter(x => ['K47 iron sights', 'K47 4x', 'AWR built-in 8x'].includes(x.label))) {
      await arm(sight.gun, sight.optic)
      const at70 = target(sight.power, 70)
      await aimEach(sight, [50, 25].map(percent => ({
        fov: 70, percent, s: percent / 100, want: at70, power: sight.power, label: `compensated for FOV Effects ${percent}%`
      })), 'effects')
    }
    const logFile = path.join(SERVER_DIR, 'logs/latest.log')
    const logged = fs.existsSync(logFile) && fs.readFileSync(logFile, 'utf8').includes(`${name} declared FOV 70, FOV Effects 25% (zoom compensated)`)
    check('effects: the declaration is in the server log', logged)
  } finally {
    fs.writeFileSync(file, original)
    await rcon('cv reload').catch(() => {})
    await declare(70, 100)
  }
}

/** Starts the use packets of right mouse held (one at once, then every 4 ticks); returns the stop. */
function press () {
  let on = true
  const done = (async () => {
    while (on) {
      useItem()
      await sleep(200)
    }
  })()
  return async () => { on = false; await done }
}
/** Waits until the server has put the view back to the hip (it lowered the sights); false on a timeout. */
async function lowered (ms = 1500) {
  const by = Date.now() + ms
  while (Date.now() < by) {
    if (Math.abs(shown() - 1) < 0.002) return true
    await sleep(5)
  }
  return false
}
const heldMark = () => damageOf(bot.heldItem)

async function resume () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await declare(70, 100)
  await arm('awr')
  const slot = hotbar('awr')
  const want = target(8)
  const marksOf = from => marks.slice(from).filter(m => m.slot === slot).map(m => m.damage)

  // A late packet: the sights were up, the server lowered them, right mouse comes straight back.
  let stop = press()
  await sleep(1100)
  await stop()
  const upBefore = heldMark() === 4 && Math.abs(shown() - want) < 0.005 && heads.length && heads[heads.length - 1].overlay
  check('resume: the AWR is up (aimed mark, 8x, scope picture) before the packets stop', upBefore, `mark ${heldMark()} shows ${shown().toFixed(4)}`)
  const stopAt = marks.length
  const headsAt = heads.length
  if (!await lowered()) throw new Error('the server never lowered the sights')
  const back = Date.now()
  stop = press()
  // The server steers the view back past the client's easing: the first view it sends is the
  // scope's zoom or past it (never one on the way, which would be a new rise).
  let zoomBack = -1
  while (Date.now() - back < 600) {
    if (zoomBack < 0 && shown() <= want + 0.005) zoomBack = Date.now() - back
    await sleep(5)
  }
  const seq = marksOf(stopAt)
  const headsSince = heads.slice(headsAt)
  check('resume: back at once, no new rise (no aiming mark), aimed mark held', !seq.includes(3) && heldMark() === 4, `marks ${seq.join(',') || 'none'}, now ${heldMark()}`)
  check('resume: the 8x zoom is asked for at once', zoomBack >= 0 && zoomBack <= 60, `${zoomBack} ms`)
  check('resume: the scope picture is on the head slot', heads.length > 0 && heads[heads.length - 1].overlay,
    headsSince.map(h => h.overlay ? 'scope' : 'plain').join(',') || 'never taken down')
  await stop()
  await lowered()
  await sleep(800)

  // Back only after 8 ticks: from the hip again.
  stop = press()
  await sleep(1100)
  await stop()
  if (!await lowered()) throw new Error('the server never lowered the sights')
  await sleep(400)
  const lateAt = marks.length
  stop = press()
  await sleep(1000)
  await stop()
  const late = marksOf(lateAt)
  check('resume: back after 8 ticks, the sights come up from the hip (aiming, then aimed)', late.join(',').startsWith('3,4'), late.join(','))
  await lowered()
  await sleep(800)

  // Half way up: one use packet, the server lowers the sights 6 ticks in, then right mouse is back.
  useItem()
  const by = Date.now() + 1000
  while (Date.now() < by && shown() > 0.999) await sleep(5)
  if (shown() > 0.999) throw new Error('one use packet never started the aim')
  if (!await lowered(1000)) throw new Error('the server never lowered the sights')
  const halfAt = marks.length
  const orderAt = order.length
  const again = Date.now()
  stop = press()
  let upAfter = -1
  while (Date.now() - again < 1200) {
    if (upAfter < 0 && heldMark() === 4) upAfter = Date.now() - again
    await sleep(5)
  }
  await stop()
  const half = marksOf(halfAt)
  check('resume: part way up and back, the rise goes on: up in 1-6 ticks rather than 9', upAfter >= 30 && upAfter <= 330,
    `${upAfter} ms, marks ${half.join(',')}`)
  const got = order.slice(orderAt)
  const rise = got.findIndex(e => /^c[1-9]/.test(e))
  check('resume: and the frames\' cooldown comes after the aiming mark', got.indexOf('m3') >= 0 && rise > got.indexOf('m3'), got.join(' '))
  await lowered()
  await sleep(500)
}

/** The views the bot was shown since `from` (ms), with the time since then. */
const shownSince = from => history.filter(h => h.at >= from).map(h => ({ ms: h.at - from, m: shown(1, h) }))
/**
 * What the vanilla client shows from the views sent since `from`: from the first one on, each 50 ms
 * tick it goes half the way to the last one it has (GameRenderer.tickFov). { ms, m } a tick.
 */
function clientSince (from, ticks = 30) {
  const sent = shownSince(from)
  if (!sent.length) return []
  const out = []
  let m = 1
  for (let k = 0; k < ticks; k++) {
    const at = sent[0].ms + 50 * k
    const last = sent.filter(v => v.ms <= at + 1).pop()
    m += (last.m - m) * 0.5
    out.push({ ms: at, m })
  }
  return out
}
/** How much of the zoom from the hip to `want` the view `m` shows, in magnification (0 hip, 1 there). */
const zoomed = (m, want) => Math.log(power(m)) / Math.log(power(want))

async function time () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await declare(70, 100)
  for (const [gun, optic, ticks, sight] of [['k47', null, 5, 1.25], ['p18', null, 3, 1.1], ['awr', null, 9, 8]]) {
    await arm(gun, optic)
    const slot = hotbar(gun)
    const from = Date.now()
    const markFrom = marks.length
    const orderFrom = order.length
    await aimFor(ticks * 50 + 600)
    const want = target(sight)
    const client = clientSince(from)
    const up = marks.slice(markFrom).find(m => m.slot === slot && m.damage === 4)
    // The server takes the aim between two ticks and steers the view from the next one, so the client
    // shows a rise of n ticks in n - 1 steps (one sent between ticks would never be shown on its own):
    // the one half way through them must be part way.
    const middle = client.filter(v => v.ms === client[0].ms + 50 * Math.floor((ticks - 2) / 2))
    check(`time: ${gun} narrows over its ${ticks}-tick ads time, not at once`,
      middle.length > 0 && middle.every(v => zoomed(v.m, want) > 0.1 && zoomed(v.m, want) < 0.97),
      client.slice(0, ticks + 2).map(v => v.ms + 'ms:' + (zoomed(v.m, want) * 100).toFixed(0) + '%').join(' '))
    const there = up ? client.find(v => v.ms >= up.at - from) : null
    check(`time: ${gun} and the client shows the sight's zoom by the tick after the sights are up`,
      !!there && zoomed(there.m, want) >= 0.95, there ? `${(zoomed(there.m, want) * 100).toFixed(1)}% at ${there.ms} ms` : 'never up')
    await sleep(600)
    const seq = marks.slice(markFrom).filter(m => m.slot === slot).map(m => m.damage)
    check(`time: ${gun} hand mark goes aiming (3), aimed (4), then none`, seq.join(',') === '3,4,0', seq.join(','))
    const got = order.slice(orderFrom)
    const rise = got.findIndex(e => /^c[1-9]/.test(e))
    check(`time: ${gun} the aiming mark reaches the client before the ads cooldown (or the hip model would flash)`,
      got.indexOf('m3') >= 0 && rise > got.indexOf('m3'), got.join(' '))
  }
  // No use packet: no aim, whatever the client's own key says.
  await arm('k47')
  const markFrom = marks.length
  await sleep(1000)
  check('time: without use packets the server never aims', marks.length === markFrom && Math.abs(shown() - 1) < 0.002)
}

async function mode () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await arm('k47', 'optic_4x')
  let during = 0
  let after = 0
  await aimFor(900, async () => {
    await rcon(`gamemode creative ${name}`)
    await sleep(400)
    during = shown()
    await rcon(`gamemode adventure ${name}`)
    await sleep(400)
    after = shown()
  })
  const want = target(4)
  check('mode: the 4x zoom holds through a gamemode change by RCON', Math.abs(during - want) < 0.005 && Math.abs(after - want) < 0.005,
    `creative ${during.toFixed(4)}, back ${after.toFixed(4)}, want ${want.toFixed(4)}`)
}

async function helmet () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await arm('k47', 'optic_4x')
  await rcon(`item replace entity ${name} armor.head with minecraft:iron_helmet`)
  await sleep(800)
  let last = null
  await aimFor(900, async () => {
    const from = heads.length
    last = heads[heads.length - 1]
    check('helmet: aiming the 4x puts the scope overlay on the head slot, on a copy of the helmet worn',
      last && last.overlay && last.kind === 'iron_helmet', JSON.stringify(last))
    await rcon(`item replace entity ${name} armor.head with minecraft:diamond_helmet`)
    await sleep(500)
    const since = heads.slice(from)
    last = since.length ? since[since.length - 1] : null
    check('helmet: the helmet replaced mid-aim, every head-slot update since still carries the overlay', since.length > 0 && since.every(h => h.overlay),
      JSON.stringify(since))
    check('helmet: and the slot shows the new helmet, not the old one', last && last.kind === 'diamond_helmet', JSON.stringify(last))
    const bare = heads.length
    await rcon(`item replace entity ${name} armor.head with minecraft:air`)
    await sleep(500)
    const off = heads.slice(bare)
    last = off.length ? off[off.length - 1] : null
    check('helmet: taken off mid-aim, the slot holds the blank overlay item, not the helmet', last && last.overlay && last.kind === 'paper' && last.blank,
      JSON.stringify(off))
    await rcon(`item replace entity ${name} armor.head with minecraft:diamond_helmet`)
    await sleep(300)
  })
  await sleep(800)
  last = heads[heads.length - 1]
  check('helmet: let go, the real helmet is back', last && !last.overlay && last.kind === 'diamond_helmet', JSON.stringify(last))
  await rcon(`item replace entity ${name} armor.head with minecraft:air`)
}

async function heavy () {
  if (worldName.includes('craftov_range')) {
    // Off the range by a teleport: the session ends and the lobby kit comes back (RangeService).
    await rcon(`execute in minecraft:craftov_lobby run tp ${name} 0.5 65 0.5`)
    await sleep(2500)
  }
  bot.chat('/cv queue proving NORMAL SOLO')
  const by = Date.now() + 150000
  while (Date.now() < by && !worldName.startsWith('craftov_raid_')) await sleep(500)
  if (!worldName.startsWith('craftov_raid_')) throw new Error('not in a raid: ' + worldName)
  await sleep(7000) // the insertion camera and spawn protection
  const at = `execute at ${name} run`
  await rcon(`${at} fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone`)
  await rcon(`${at} fill ~-6 ~ ~-6 ~6 ~5 ~6 minecraft:air`)
  const sweep = setInterval(() => {
    for (const type of ['zombie', 'husk', 'drowned', 'bee']) rcon(`${at} kill @e[type=minecraft:${type},distance=..2000]`).catch(() => {})
  }, 1500)
  try {
    await rcon(`clear ${name}`)
    for (const id of ['helmet_6', 'armor_6', 'pack_3']) await rcon(`cv give ${name} ${id}`)
    await sleep(800)
    for (const [id, where] of [['helmet_6', 'head'], ['armor_6', 'torso']]) {
      const item = bot.inventory.items().find(i => itemId(i) === id)
      if (item) await bot.equip(item, where).catch(e => log('equip', id, e.message))
      await sleep(400)
    }
    await rcon(`cv give ${name} k47`)
    await sleep(800)
    for (let i = 0; i < 8; i++) await rcon(`cv give ${name} reactor_cell`)
    await sleep(1500)
    hold('k47')
    await sleep(2500)
    log('speed', view.speed.toFixed(4), 'walk', view.walk.toFixed(4))
    check('heavy: loaded down, the hip view is still 1.000', [1, 0.5].every(s => Math.abs(shown(s) - 1) < 0.002) && view.speed < 0.085,
      `speed ${view.speed.toFixed(4)} shows ${shown().toFixed(4)}`)
    let seen = 0
    await aimFor(900, async () => { seen = shown() })
    check('heavy: loaded down, the K47 aims at 1.25x', Math.abs(seen - target(1.25)) < 0.005, `${seen.toFixed(4)} want ${target(1.25).toFixed(4)}`)
  } finally {
    clearInterval(sweep)
    await rcon(`kill ${name}`)
    await sleep(3000)
  }
}

// ---------------------------------------------------------------- run
const RUN = { hip, optics, effects, time, resume, mode, helmet, heavy }
bot.once('spawn', async () => {
  await sleep(3000)
  try {
    for (const scenario of scenarios) {
      log('=== ' + scenario)
      try {
        await RUN[scenario]()
      } catch (e) {
        failures.push(scenario + ' (setup)', scenario + ' (setup)')
        log('FAIL ' + scenario + ' could not run: ' + e.message)
      }
    }
  } finally {
    log(failures.length ? `${failures.length} failed: ${failures.join(' | ')}` : 'all passed')
    bot.quit()
    setTimeout(() => process.exit(failures.length), 500)
  }
})
