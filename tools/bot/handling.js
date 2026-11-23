// Gun handling (issue #117): how fast each gun comes up, measured on the private range, printed as
// a table sorted as the guns come, and checked against each gun's handling in the server's items.yml
// (SERVER_DIR, default the repository's server/run) and config.yml weapons.handling.
//
//   ads     Every gun as it comes, and the M4C with each sight: right mouse pressed (a use packet at
//           once, then one every 4 ticks, as the vanilla client sends them). Measured: how long after
//           the press the server takes the aim (the aiming mark, damage 3, on the held stack), the
//           server ticks from there to the sights up (from the server log with debug: true, else from
//           the packets), and the whole time from the press to the aimed mark (damage 4). Checked:
//           the ticks are the ones handling gives (within a tick), the server takes the aim within a
//           tick, a gun with more handling never comes up slower than one with less, the SV9 is at
//           least twice as quick as the AWR, the red dot costs the M4C a tick at most and the 8x at
//           least 2, and stronger sights never come up quicker.
//   draw    From the P18 over to each primary (and from the M4C over to each pistol): the ticks from
//           the draw mark (damage 1) to the gun up (damage 0), which handling gives too, in the same
//           order.
//   sprint  Sprinting down the range with each of the SV9, M4C and AWR, then right mouse pressed: the
//           server holds the client off the sprint (food at 6; the bot stops sprinting there as the
//           vanilla client does), and the sights come up the gun's sprint-out ticks after the sprint
//           ended (from the stop the bot sent to the aiming mark, within -1..+2 ticks), the quick
//           guns sooner.
//   carry   SV9 and AWR: sprinting down the range, the sprint and the run stopped (as the vanilla
//           client does when let go), then a hip shot 100 ms later and one a second after that
//           (debug: true: how much of the sprint cone is still on the gun is in the shot's log line).
//           The first: the AWR (sprint-out 7 ticks) still carries part of it, more than the SV9 (3
//           ticks) by a fifth at least, and fires wider than its later shot; the later shot carries
//           none.
//   sway    AWR and SV9 aimed (debug: true: the size of the sway is in the shot's log line): a shot
//           from the hip has none; the AWR's first aimed shot, on the tick its sights are up, sways
//           by its full size (handling 16: 0.084 degrees), one 2.5 s later by about the rest share
//           (0.3) of it; the SV9's is under a fifth of the AWR's.
//
//   PORT=25565 RCON_PORT=25575 [SERVER_DIR=...] node handling.js [ads|draw|sprint|carry|sway ...]
//
// Server config: debug: true for exact ads ticks and for carry and sway (read from the log), the defaults
// (weapons.handling, weapons.ads.timeout-ticks 5). Without a handling key in items.yml (a server
// that kept an older copy) the bot only reports what it measured. The real client's timing, from
// the press to a settled zoom, is tools/shots/shots.py run adstime. Exit code: number of failed
// checks (2+ also when a scenario could not be set up).
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
const scenarios = wanted.length ? wanted : ['ads', 'draw', 'sprint', 'carry', 'sway']

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const name = 'Hdl' + Math.floor(10000 + Math.random() * 90000)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', r))
bot.on('error', e => log('ERROR', e.message))
let worldName = ''
const trackWorld = p => { if (p.worldState && p.worldState.name) worldName = p.worldState.name.replace(/^minecraft:/, '') }
bot._client.on('login', trackWorld)
bot._client.on('respawn', trackWorld)

// ---------------------------------------------------------------- what handling gives (items.yml, config.yml)
/** The handling of every gun in the server's items.yml, the stats of each part, and weapons.handling. */
function readHandling () {
  const items = fs.readFileSync(path.join(SERVER_DIR, 'plugins/Craftov/items.yml'), 'utf8')
  const guns = {}
  for (const m of items.matchAll(/^ {2}([a-z0-9_]+):\n((?: {4}.*\n)+)/gm)) {
    const h = m[2].match(/\bhandling:\s*(-?[\d.]+)/)
    if (/\bgun:/.test(m[2])) guns[m[1]] = h ? Number(h[1]) : null
  }
  const parts = {}
  for (const m of items.matchAll(/^ {2}((?:optic|att)_[a-z0-9]+):.*\bhandling:\s*(-?[\d.]+)/gm)) parts[m[1]] = Number(m[2])
  // weapons.handling.<stat>.slow / quick in the server's config.yml, else the jar's defaults.
  const config = fs.readFileSync(path.join(SERVER_DIR, 'plugins/Craftov/config.yml'), 'utf8')
  const section = config.match(/^ {2}handling:\n((?: {4,}.*\n|\s*#.*\n)+)/m)
  const num = (stat, key, fallback) => {
    const block = section && section[1].match(new RegExp(`^ {4}${stat}:\\n((?: {6,}.*\\n)+)`, 'm'))
    const m = block && block[1].match(new RegExp(`^ {6}${key}:\\s*([\\d.]+)`, 'm'))
    return m ? Number(m[1]) : fallback
  }
  const pair = (stat, slow, quick) => [num(stat, 'slow', slow), num(stat, 'quick', quick)]
  return { guns, parts, ads: pair('ads', 0.5, 0.1), draw: pair('draw', 0.85, 0.25), sprint: pair('sprint-out', 0.4, 0.1) }
}
let H = null
/** Ticks a stat takes at `handling`: from slow at 0 to quick at 100, in whole ticks (as Handling.java). */
const ticksAt = ([slow, quick], handling, least = 1) =>
  Math.max(least, Math.round((slow + (quick - slow) * Math.max(0, Math.min(100, handling)) / 100) * 20))

// ---------------------------------------------------------------- the view and the hand mark
const view = { speed: 0.1, walk: 0.1 }
const views = []
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
    views.push({ at: Date.now(), m: shown() })
  }
})
bot._client.on('abilities', p => {
  view.walk = p.walkingSpeed
  views.push({ at: Date.now(), m: shown() })
})
const shown = () => view.walk === 0 ? 1 : (view.speed / view.walk + 1) / 2

const marks = []
const damageOf = item => item && item.componentMap && item.componentMap.has('damage') ? item.componentMap.get('damage').data : 0
bot.once('spawn', () => bot.inventory.on('updateSlot', (slot, before, after) => {
  if (slot >= 36 && slot <= 44 && damageOf(before) !== damageOf(after)) marks.push({ at: Date.now(), slot, damage: damageOf(after) })
}))
let food = 20
bot._client.on('update_health', p => { food = p.food })
// mineflayer 4.39 sends the 1.21.11 sprint command with the old numeric id (see accuracy.js): send the
// right one, and the movement keys the way the vanilla client reports them.
const command = actionId => bot._client.write('entity_action', { entityId: bot.entity.id, actionId, jumpBoost: 0 })
const keys = (held = {}) => bot._client.write('player_input', { inputs: held })

const itemId = item => { const m = JSON.stringify(item && (item.components || item) || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const useItem = () => bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
const hold = id => bot.setQuickBarSlot(hotbar(id) - 36)
/** Right mouse held: a use packet now and every 4 ticks. Returns the stop. */
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
/** The first mark `damage` on `slot` since `from` (ms), or null. */
const markAt = (slot, damage, from) => marks.find(m => m.slot === slot && m.damage === damage && m.at >= from) || null
const ticks = ms => Math.round(ms / 50)

// ---------------------------------------------------------------- setup
async function enterRange () {
  await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (rack) bot.activateEntity(rack)
  await sleep(2500)
  const dim = await rcon(`data get entity ${name} Dimension`)
  if (!dim.includes('craftov_range')) throw new Error('not on the range: ' + dim)
  await rcon(`execute as ${name} at @s run tp @s ~ ~ ~ 180 0`)
  await sleep(500)
  start = bot.entity.position.clone()
  bot.chat('/cv fov 70 100')
  await sleep(600)
}
/** Only these on the hotbar: [id, optic] each. */
async function kit (guns) {
  await rcon(`clear ${name}`)
  await sleep(300)
  for (const [id, optic] of guns) await rcon(`cv give ${name} ${id} 1` + (optic ? ` ${optic}` : ''))
  await sleep(900)
  for (const [id] of guns) if (hotbar(id) < 0) throw new Error(id + ' did not reach the hotbar')
}
/** Back to where the range began, facing down it. */
let start = null
async function home () {
  await rcon(`execute as ${name} at @s run tp @s ${start.x.toFixed(2)} ${start.y.toFixed(2)} ${start.z.toFixed(2)} 180 0`)
}

const GUNS = ['p18', 'd50', 'sv9', 'v57', 'm4c', 'k47', 'r12', 'scr', 'kl7', 'dm14', 'awr']
/** The handling a gun has with `optic` mounted (a built-in scope is counted in the gun's own). */
const handlingOf = (gun, optic) => H.guns[gun] == null ? null : H.guns[gun] + (optic ? (H.parts[optic] || 0) : 0)

const LOG = env.LOG
const logSize = () => { try { return fs.statSync(LOG).size } catch (e) { return 0 } }
/** The server log written since `from` (bytes). */
function logSince (from) {
  try {
    const fd = fs.openSync(LOG, 'r')
    const buf = Buffer.alloc(Math.max(0, fs.fstatSync(fd).size - from))
    fs.readSync(fd, buf, 0, buf.length, from)
    fs.closeSync(fd)
    return buf.toString('utf8')
  } catch (e) {
    return ''
  }
}

/**
 * Aims the held gun once from the hip: { confirm, total } in ms from the press, and the server ticks
 * from taking the aim to the sights up: from the server log (debug: true) where it has them, else
 * from the packets.
 */
async function aimOnce (slot) {
  const from = Date.now()
  const logFrom = logSize()
  const stop = press()
  const by = from + 2500
  while (Date.now() < by && !markAt(slot, 4, from)) await sleep(2)
  await sleep(200)
  await stop()
  const aim = markAt(slot, 3, from)
  const aimed = markAt(slot, 4, from)
  await sleep(700) // the server lowers the sights, past the resume window
  if (!aim || !aimed) return null
  const text = logSince(logFrom)
  const began = text.match(new RegExp(`\\[debug\\] ${name} aims \\S+ tick=(\\d+)`))
  const up = text.match(new RegExp(`\\[debug\\] ${name} aimed \\S+ tick=(\\d+)`))
  let upTicks
  if (began && up) {
    upTicks = Number(up[1]) - Number(began[1])
  } else {
    // The server takes the aim as the use packet comes, between two ticks, and the view it steers
    // each tick from then on comes at the tick: the first after the aiming mark is the rise's second.
    const next = views.find(v => v.at > aim.at + 2)
    upTicks = next && next.at <= aimed.at ? 1 + Math.round((aimed.at - next.at) / 50) : Math.round((aimed.at - aim.at) / 50)
  }
  return { confirm: aim.at - from, upTicks, total: aimed.at - from }
}

/** Non-increasing ticks as handling goes up: the pairs out of order. */
function outOfOrder (rows, key) {
  const sorted = rows.filter(r => r.handling != null).sort((a, b) => a.handling - b.handling)
  const bad = []
  for (let i = 0; i < sorted.length; i++) {
    for (let j = i + 1; j < sorted.length; j++) {
      if (sorted[j].handling > sorted[i].handling && sorted[j][key] > sorted[i][key]) bad.push(`${sorted[j].label} (${sorted[j].handling}) ${sorted[j][key]} > ${sorted[i].label} (${sorted[i].handling}) ${sorted[i][key]}`)
    }
  }
  return bad
}

function table (rows, cols) {
  log(cols.map(c => c.padStart(9)).join(''))
  for (const r of rows) log(cols.map(c => String(r[c] == null ? '-' : r[c]).padStart(9)).join(''))
}

// ---------------------------------------------------------------- scenarios
async function ads () {
  if (!worldName.includes('craftov_range')) await enterRange()
  const cases = GUNS.map(g => [g, null]).concat(['optic_reddot', 'optic_holo', 'optic_3x', 'optic_4x', 'optic_8x'].map(o => ['m4c', o]))
  const rows = []
  for (const [gun, optic] of cases) {
    await kit([[gun, optic]])
    hold(gun)
    await sleep(1400) // the draw
    const slot = hotbar(gun)
    const runs = []
    for (let i = 0; i < 3; i++) {
      const r = await aimOnce(slot)
      if (r) runs.push(r)
    }
    const label = optic ? `${gun}+${optic.replace('optic_', '')}` : gun
    if (!runs.length) {
      check(`ads: ${label} aims`, false, 'no aiming or aimed mark')
      continue
    }
    const med = k => runs.map(r => r[k]).sort((a, b) => a - b)[Math.floor(runs.length / 2)]
    const handling = H.guns[gun] === undefined ? null : handlingOf(gun, optic)
    rows.push({
      label, gun, optic, handling,
      confirm: med('confirm'), upTicks: med('upTicks'), totalMs: med('total'),
      want: handling == null ? null : ticksAt(H.ads, handling)
    })
  }
  table(rows, ['label', 'handling', 'confirm', 'upTicks', 'want', 'totalMs'])
  if (rows.some(r => r.handling == null)) {
    log('no handling in items.yml: measured only')
    return
  }
  for (const r of rows) {
    check(`ads: ${r.label} (handling ${r.handling}) comes up in ${r.want} ticks`, Math.abs(r.upTicks - r.want) <= 1, `${r.upTicks} ticks, ${r.totalMs} ms from the press`)
    check(`ads: ${r.label} the server takes the aim within a tick of the press`, r.confirm <= 60, `${r.confirm} ms`)
  }
  const bare = rows.filter(r => !r.optic)
  const bad = outOfOrder(bare, 'upTicks')
  check('ads: guns sorted by handling come up no slower the more handling they have', !bad.length, bad.join('; ') || bare.sort((a, b) => b.handling - a.handling).map(r => `${r.label}:${r.upTicks}`).join(' '))
  const sv9 = rows.find(r => r.label === 'sv9')
  const awr = rows.find(r => r.label === 'awr')
  check('ads: the SV9 is at least twice as quick as the AWR', sv9 && awr && sv9.upTicks * 2 <= awr.upTicks, `${sv9 && sv9.upTicks} vs ${awr && awr.upTicks} ticks`)
  const iron = rows.find(r => r.label === 'm4c')
  const sight = o => rows.find(r => r.label === `m4c+${o}`)
  check('ads: the red dot costs the M4C a tick at most', iron && sight('reddot') && sight('reddot').upTicks - iron.upTicks <= 1,
    `${iron && iron.upTicks} -> ${sight('reddot') && sight('reddot').upTicks}`)
  check('ads: the 8x is at least 2 ticks slower than iron sights on the M4C', iron && sight('8x') && sight('8x').upTicks - iron.upTicks >= 2,
    `${iron && iron.upTicks} -> ${sight('8x') && sight('8x').upTicks}`)
  const optics = ['reddot', '3x', '4x', '8x'].map(sight)
  check('ads: stronger sights never come up quicker', optics.every((r, i) => r && (i === 0 || r.upTicks >= optics[i - 1].upTicks)),
    optics.map(r => r && r.upTicks).join(' <= '))
}

async function draw () {
  if (!worldName.includes('craftov_range')) await enterRange()
  const rows = []
  for (const [from, gun] of GUNS.map(g => [g === 'p18' || g === 'd50' ? 'm4c' : 'p18', g])) {
    await kit([[from, null], [gun, null]])
    hold(from)
    await sleep(1400)
    const slot = hotbar(gun)
    const runs = []
    for (let i = 0; i < 3; i++) {
      const at = Date.now()
      hold(gun)
      const by = at + 2500
      while (Date.now() < by && !markAt(slot, 0, at)) await sleep(2)
      const start = markAt(slot, 1, at)
      const up = markAt(slot, 0, at)
      if (start && up) runs.push(up.at - start.at)
      await sleep(300)
      hold(from)
      await sleep(1400)
    }
    if (!runs.length) {
      check(`draw: ${gun} comes up`, false, 'no draw or rest mark')
      continue
    }
    const ms = runs.sort((a, b) => a - b)[Math.floor(runs.length / 2)]
    const handling = H.guns[gun] === undefined ? null : H.guns[gun]
    rows.push({ label: gun, handling, drawTicks: ticks(ms), want: handling == null ? null : ticksAt(H.draw, handling) })
  }
  table(rows, ['label', 'handling', 'drawTicks', 'want'])
  if (rows.some(r => r.handling == null)) {
    log('no handling in items.yml: measured only')
    return
  }
  for (const r of rows) check(`draw: ${r.label} (handling ${r.handling}) comes up in ${r.want} ticks`, Math.abs(r.drawTicks - r.want) <= 1, `${r.drawTicks} ticks`)
  const bad = outOfOrder(rows, 'drawTicks')
  check('draw: guns sorted by handling draw no slower the more handling they have', !bad.length, bad.join('; ') || rows.map(r => `${r.label}:${r.drawTicks}`).join(' '))
}

async function sprint () {
  if (!worldName.includes('craftov_range')) await enterRange()
  const rows = []
  for (const gun of ['sv9', 'm4c', 'awr']) {
    await kit([[gun, null]])
    hold(gun)
    await sleep(1400)
    const slot = hotbar(gun)
    const runs = []
    for (let i = 0; i < 3; i++) {
      await home()
      await sleep(600)
      keys({ forward: true, sprint: true })
      bot.setControlState('forward', true)
      bot.setControlState('sprint', true)
      command('start_sprinting')
      await sleep(700)
      const at = Date.now()
      const stop = press()
      const by = at + 3000
      let sprintEnd = -1
      while (Date.now() < by && !markAt(slot, 4, at)) {
        if (sprintEnd < 0 && food <= 6) {
          // What the vanilla client does at food 6: it stops sprinting, and says so.
          sprintEnd = Date.now()
          bot.setControlState('sprint', false)
          command('stop_sprinting')
          keys({ forward: true })
        }
        await sleep(2)
      }
      bot.setControlState('forward', false)
      keys({})
      await sleep(150)
      await stop()
      const aim = markAt(slot, 3, at)
      const aimed = markAt(slot, 4, at)
      if (aim && aimed && sprintEnd > 0) runs.push({ total: aimed.at - at, out: aim.at - sprintEnd, food: sprintEnd - at })
      await sleep(800)
    }
    if (!runs.length) {
      check(`sprint: ${gun} aims out of a sprint`, false, 'no aimed mark')
      continue
    }
    const med = k => runs.map(r => r[k]).sort((a, b) => a - b)[Math.floor(runs.length / 2)]
    const handling = H.guns[gun] === undefined ? null : H.guns[gun]
    rows.push({ label: gun, handling, stopMs: med('food'), outTicks: ticks(med('out')), totalMs: med('total'), want: handling == null ? null : ticksAt(H.sprint, handling) })
  }
  table(rows, ['label', 'handling', 'stopMs', 'outTicks', 'want', 'totalMs'])
  if (rows.some(r => r.handling == null)) {
    log('no handling in items.yml: measured only')
    return
  }
  // Measured from the stop the bot sent to the aiming mark: the server hears the stop between ticks,
  // and the measured run may read on for a tick as the player slows down.
  for (const r of rows) check(`sprint: ${r.label} (handling ${r.handling}) aims ${r.want} ticks after the sprint`, r.outTicks >= r.want - 1 && r.outTicks <= r.want + 2, `${r.outTicks} ticks`)
  const bad = outOfOrder(rows, 'outTicks')
  check('sprint: the more handling, the sooner the sights come up out of a sprint', !bad.length, bad.join('; ') || rows.map(r => `${r.label}:${r.outTicks}`).join(' '))
}

// ---------------------------------------------------------------- sway
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell, face: 1, sequence: sequence++ })
/** A trigger pull on the capture cell the server keeps in front of the crosshair (TriggerService). */
function click () {
  const id = bot.registry.blocksByName.sculk_vein.id
  const cells = bot.findBlocks({ matching: id, maxDistance: 6, count: 16 })
  if (!cells.length) throw new Error('no capture cell in front of the crosshair')
  const eye = bot.entity.position.offset(0, 1.62, 0)
  const at = cells.reduce((a, b) => a.distanceTo(eye) < b.distanceTo(eye) ? a : b)
  dig(0, at)
  bot._client.write('arm_animation', { hand: 0 })
  dig(1, at)
}
/** The sway sizes of this bot's shots logged since `from` (bytes), with their aim flags. */
const swaysSince = from => [...logSince(from).matchAll(new RegExp(`\\[debug\\] ${name} shot .*? aim=(true|false) .*? sway=([0-9.]+)`, 'g'))]
  .map(m => ({ aim: m[1] === 'true', sway: Number(m[2]) }))

/** This bot's shots logged since `from` (bytes): their spread and how much sprint cone they carried. */
const carriesSince = from => [...logSince(from).matchAll(new RegExp(`\\[debug\\] ${name} shot spread=([0-9.]+) .*? carry=([0-9.]+)`, 'g'))]
  .map(m => ({ spread: Number(m[1]), carry: Number(m[2]) }))

async function carry () {
  if (!worldName.includes('craftov_range')) await enterRange()
  const first = {}
  for (const gun of ['sv9', 'awr']) {
    await kit([[gun, null]])
    hold(gun)
    await sleep(1400)
    await home()
    await sleep(600)
    keys({ forward: true, sprint: true })
    bot.setControlState('forward', true)
    bot.setControlState('sprint', true)
    command('start_sprinting')
    await sleep(900)
    bot.setControlState('sprint', false)
    bot.setControlState('forward', false)
    command('stop_sprinting')
    keys({})
    await sleep(100)
    let from = logSize()
    click()
    await sleep(gun === 'awr' ? 1700 : 1000) // the bolt
    const early = carriesSince(from)
    from = logSize()
    click()
    await sleep(400)
    const late = carriesSince(from)
    log(gun, 'out of a sprint:', JSON.stringify(early), 'a second later:', JSON.stringify(late))
    if (!early.length || !late.length) {
      check(`carry: ${gun} fires out of a sprint`, false, 'no shot in the log (debug: true?)')
      continue
    }
    first[gun] = early[0]
    check(`carry: ${gun} a second after the sprint carries none of its cone`, late[0].carry === 0, String(late[0].carry))
    if (gun === 'awr') {
      check('carry: the AWR 100 ms out of a sprint still carries part of the sprint cone', early[0].carry > 0.2 && early[0].carry < 1,
        String(early[0].carry))
      check('carry: and fires wider than a second later', early[0].spread > late[0].spread * 1.1, `${early[0].spread} vs ${late[0].spread}`)
    }
  }
  if (first.sv9 && first.awr) {
    check('carry: the SV9 sheds the sprint cone sooner than the AWR', first.sv9.carry <= first.awr.carry - 0.2,
      `sv9 ${first.sv9.carry}, awr ${first.awr.carry}`)
  }
}

async function sway () {
  if (!worldName.includes('craftov_range')) await enterRange()
  await home()
  await sleep(400)
  const full = h => Math.max(0, 0.1 * (1 - h / 100))
  const sizes = {}
  for (const gun of ['awr', 'sv9']) {
    await kit([[gun, null]])
    hold(gun)
    await sleep(1400)
    const slot = hotbar(gun)
    // From the hip.
    let from = logSize()
    click()
    await sleep(gun === 'awr' ? 1700 : 300) // the bolt
    const hip = swaysSince(from)
    check(`sway: ${gun} from the hip does not sway`, hip.length >= 1 && hip.every(x => !x.aim && x.sway === 0), JSON.stringify(hip))
    // Aimed: a shot the moment the sights are up, and (the AWR) one 2.5 s later.
    from = logSize()
    const at = Date.now()
    const stop = press()
    while (Date.now() - at < 2000 && !markAt(slot, 4, at)) await sleep(2)
    click()
    if (gun === 'awr') {
      await sleep(2500)
      click()
    }
    await sleep(300)
    await stop()
    await sleep(800)
    const aimed = swaysSince(from)
    sizes[gun] = aimed.map(x => x.sway)
    log(gun, 'aimed shots sway', sizes[gun].join(' '))
    if (gun === 'awr') {
      const want = full(H.guns.awr == null ? 16 : H.guns.awr)
      check('sway: the AWR\'s first aimed shot sways by its full size', aimed.length === 2 && aimed[0].aim && Math.abs(aimed[0].sway - want) <= want * 0.15,
        `${aimed[0] && aimed[0].sway} (want ${want.toFixed(3)})`)
      check('sway: and 2.5 s later by about the rest share (0.3) of it', aimed.length === 2 && aimed[1].sway <= want * 0.36 && aimed[1].sway >= want * 0.27,
        `${aimed[1] && aimed[1].sway}`)
    }
  }
  check('sway: the SV9 sways under a fifth of what the AWR does', sizes.sv9 && sizes.awr && sizes.sv9[0] < sizes.awr[0] / 5,
    `${sizes.sv9 && sizes.sv9[0]} vs ${sizes.awr && sizes.awr[0]}`)
}

// ---------------------------------------------------------------- run
const RUN = { ads, draw, sprint, carry, sway }
bot.once('spawn', async () => {
  await sleep(3000)
  try {
    H = readHandling()
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
    await rcon(`clear ${name}`).catch(() => {})
    bot.quit()
    setTimeout(() => process.exit(failures.length), 500)
  }
})
