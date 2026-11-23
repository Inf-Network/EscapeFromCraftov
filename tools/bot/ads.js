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
//            2 atan(tan(F/2) / N) / F within 0.005 for a slider declared at 100, 50 and 25%
//            (/cv fov F s), and the magnification tan(F/2) / tan(F m / 2) is N within 2% at F 70
//            and 90. Let go, the view is back to 1.000 within 8 ticks.
//   time     K47 iron sights, P18, AWR: the view narrows over the gun's ads time and not at once
//            (half way in, it is between hip and aimed); the hand mark on the held stack says
//            aiming (3) once the server takes the aim, aimed (4) when the sights are up, none once
//            let go, and the cooldown that runs the frames comes after the aiming mark (with them
//            together the client's old stack would read it as a shot's kick and flash); with no use
//            packet the server never starts aiming.
//   mode     Aiming with the 4x, the gamemode changed by RCON to creative and back: the zoom holds.
//   helmet   Aiming with the 4x while wearing a helmet, the helmet replaced by RCON (as a headshot
//            that wears it does): every head-slot update the bot gets from then on (its inventory
//            slot, or equipment about itself) still carries the scope overlay; let go, the real
//            helmet comes back.
//   heavy    Solo on the proving ground (test mode, test.enabled: true): helmet_6, armor_6, pack_3
//            and eight 3 kg reactor cells: the hip view is still 1.000 within 0.002, aimed the K47's 1.25x.
//
//   PORT=25565 RCON_PORT=25575 node ads.js [scenario ...]
//
// Exit code: number of failed checks (2+ also when a scenario could not be set up).
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['hip', 'optics', 'time', 'mode', 'helmet', 'heavy']

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
bot._client.on('set_slot', p => { if (p.windowId === 0 && p.slot === 5) heads.push({ at: Date.now(), via: 'slot', overlay: overlayOf(p.item), id: itemId(p.item) }) })
bot._client.on('set_player_inventory', p => { if (p.slotId === 39) heads.push({ at: Date.now(), via: 'inventory', overlay: overlayOf(p.contents), id: itemId(p.contents) }) })
bot._client.on('window_items', p => { if (p.windowId === 0 && p.items.length > 5) heads.push({ at: Date.now(), via: 'content', overlay: overlayOf(p.items[5]), id: itemId(p.items[5]) }) })
bot._client.on('entity_equipment', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const e of p.equipments) if (e.slot === 5) heads.push({ at: Date.now(), via: 'equipment', overlay: overlayOf(e.item), id: itemId(e.item) })
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

async function optics () {
  if (!worldName.includes('craftov_range')) await enterRange()
  for (const sight of SIGHTS) {
    await arm(sight.gun, sight.optic)
    for (const [fov, percent] of [[70, 100], [70, 50], [70, 25], [90, 100]]) {
      await declare(fov, percent)
      const s = percent / 100
      let seen = 0
      await aimFor(900, async () => { seen = shown(s) })
      const want = target(sight.power, fov)
      const got = power(seen, fov)
      check(`optics: ${sight.label} at FOV ${fov}, FOV Effects ${percent}%: ${want.toFixed(3)}`, Math.abs(seen - want) < 0.005 && Math.abs(got / sight.power - 1) < 0.02,
        `shows ${seen.toFixed(4)} = ${got.toFixed(2)}x`)
      const letGo = Date.now()
      while (Date.now() - letGo < 1500 && Math.abs(shown(s) - 1) > 0.002) await sleep(50)
      check(`optics: ${sight.label} let go: back to 1.000 within 8 ticks`, Date.now() - letGo <= 400 + 150, `${Date.now() - letGo} ms`)
      await sleep(300)
    }
  }
  await declare(70, 100)
}

/** The views the bot was shown since `from` (ms), with the time since then. */
const shownSince = from => history.filter(h => h.at >= from).map(h => ({ ms: h.at - from, m: shown(1, h) }))

async function time () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await declare(70, 100)
  for (const [gun, optic, ticks] of [['k47', null, 6], ['p18', null, 4], ['awr', null, 12]]) {
    await arm(gun, optic)
    const slot = hotbar(gun)
    const from = Date.now()
    const markFrom = marks.length
    const orderFrom = order.length
    await aimFor(ticks * 50 + 600)
    const seen = shownSince(from)
    const full = seen.length ? seen[seen.length - 1].m : 1
    const middle = seen.filter(v => v.ms > ticks * 25 - 60 && v.ms < ticks * 25 + 60).map(v => v.m)
    check(`time: ${gun} narrows over its ${ticks}-tick ads time, not at once`, seen.length >= Math.min(3, ticks - 1) && middle.some(m => m < 0.999 && m > full + 0.003),
      seen.map(v => v.ms + 'ms:' + v.m.toFixed(3)).join(' '))
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
    check('helmet: aiming the 4x puts the scope overlay on the head slot', heads.length > 0 && heads[heads.length - 1].overlay)
    await rcon(`item replace entity ${name} armor.head with minecraft:diamond_helmet`)
    await sleep(500)
    const since = heads.slice(from)
    last = since.length ? since[since.length - 1] : null
    check('helmet: the helmet replaced mid-aim, every head-slot update since still carries the overlay', since.length > 0 && since.every(h => h.overlay),
      JSON.stringify(since))
  })
  await sleep(800)
  last = heads[heads.length - 1]
  check('helmet: let go, the real helmet is back', last && !last.overlay, JSON.stringify(last))
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
const RUN = { hip, optics, time, mode, helmet, heavy }
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
