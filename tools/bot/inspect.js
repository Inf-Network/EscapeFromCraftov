// Knife inspect (issues #82, #104 and #116). F with a knife in hand puts the craftov:inspect cooldown
// group on cooldown for combat.melee.inspect-ticks (the client plays the inspect frames of the item
// model from it); a swing, a slot switch, being downed or reviving clears it at once, a sprint does not
// (#116). The bot watches the cooldown packets and the action bar, and asks the server through RCON
// whether the player still sprints.
//
// A real client sends F from the middle of its tick: Shift follows at the end of the same tick, a
// hotbar key pressed in the same tick only at the start of the next, and every tick ends with a
// tick_end packet. The bot sends tick_end every 50 ms like a client, and the "one client tick" checks
// write a tick's packets in exactly that order (tools/shots/scenes/inspectkeys.py presses the same
// keys with the real client).
//
//   1. lobby, each of the four knives: the knife carries the inspect cooldown group, F starts the
//      inspect (cooldown > 0) and nothing clears it before it has run out;
//   2. F again 0.5 s into an inspect starts it over (a second start), and so does F as it ends;
//   3. a swing and a slot switch each clear a running inspect (cooldown 0) within 300 ms; starting
//      to sprint 0.5 s into an inspect clears nothing, the inspect runs out and the player sprints
//      all through it (#116: the sprint ended it);
//   4. the same client tick: F as Shift goes up inspects (it fired the operator skill); Shift going
//      down with F fires the skill (it inspected); 3 then F from a gun inspects the knife and leaves
//      the gun's fire mode alone (it switched the fire mode), also with the next client tick 250 ms
//      late (a client at a few frames a second, or jitter: it was read after 150 ms, before the slot
//      change);
//   5. F 50 ms after a swing inspects once the swing is over: at least 200 ms after a karambit's (a
//      6-tick stab), at least 350 ms after the starter knife's (an 8-tick stab, where vanilla's
//      6-tick swing let the inspect start over the end of it);
//   6. F while sprinting inspects, nothing clears it before it has run out, the action bar gives no
//      reason, and the player sprints all through it and after (#116: it was refused with
//      冲刺中无法检视); none with sneak+F (the operator skill); with a gun in hand F switches the fire
//      mode and does not inspect, and F in two adjacent client ticks switches it twice (the second F
//      was dropped);
//   7. in the proving ground, with the karambit against a second bot: a cut during an inspect lands
//      and ends the inspect; a cut, F right after it, and a second cut one swing interval after the
//      first: the inspect starts in between and the second cut lands too (the inspect does not hold
//      up the knife);
//   8. with a squadmate (a third bot) knocked down: holding right click on it ends a running inspect,
//      and F while reviving does not inspect, with 救援中无法检视 on the revive bar; so does F while
//      holding right click on it from too far away, with the reason on the 离得太远 line; the downed
//      bot's F while it is being revived does not inspect, with 倒地时无法检视 on its 正在救援你 bar;
//      then, carrying a self-revive injector, the first bot is knocked down itself: that ends a
//      running inspect, and F while downed does not inspect, with 倒地时无法检视 on the bleed-out bar
//      (the hint alone was swallowed by those bars).
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node inspect.js [--no-raid]
//
// Needs test mode on (test.enabled, the proving ground is a test map) and, to keep it short,
// match.wait-seconds of a few seconds. The bots are made op for the test armory (full health between
// cuts) and deopped when done. Exit code 0 when every check passes, 1 when one fails, 2 when the
// scenario could not be set up.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const RAID = !process.argv.includes('--no-raid')
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []
const KNIVES = ['knife_starter', 'knife_tactical', 'knife_machete', 'knife_karambit']
const GROUP = 'craftov:inspect'

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  // Every inspect cooldown packet: when it came and for how many ticks (0 clears it).
  bot.inspects = []
  bot._client.on('set_cooldown', p => { if (p.cooldownGroup === GROUP) bot.inspects.push({ at: Date.now(), ticks: p.cooldownTicks }) })
  bot.bars = []
  bot._client.on('action_bar', p => bot.bars.push({ at: Date.now(), text: JSON.stringify(p.text) }))
  bot.titles = []
  bot._client.on('set_title_text', p => bot.titles.push(JSON.stringify(p.text)))
  // The click of each fire mode switch (WeaponService.nextFireMode plays gun.mode): one per step, where
  // a second action bar line may wait behind the first or give way to another.
  bot.modeClicks = []
  const click = p => { if (/gun\.mode\b/.test(JSON.stringify(p))) bot.modeClicks.push(Date.now()) }
  bot._client.on('sound_effect', click)
  bot._client.on('entity_sound_effect', click)
  // A client ends every tick with tick_end; the server reads F by them. A check that writes its own
  // ticks sets bot.paused.
  bot.paused = false
  bot.ticker = setInterval(() => { if (bot._client.state === 'play' && !bot.paused) tickEnd(bot) }, 50)
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(250) }
  return false
}

const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const pdc = (item, key) => { const m = JSON.stringify(item && item.components || '').match(new RegExp(`"craftov:${key}":\\{"type":"\\w+","value":"?([^",}]+)`)); return m ? m[1] : null }
const cooldownGroup = item => { const c = ((item && item.components) || []).find(c => c.type === 'use_cooldown'); return c ? c.data.cooldownGroup : null }

/** F: the swap-hands action. */
const pressF = bot => bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
/** The last packet of a client tick. */
function tickEnd (bot) { bot._client.write('tick_end', {}) }
/** The keys held this tick (forward, backward, left, right, jump, shift, sprint). */
const input = (bot, keys = {}) => bot._client.write('player_input', { inputs: keys })
/** Sprint on or off. mineflayer's setControlState('sprint') sends the 1.21.5 action id (3, a riding
 * jump in 1.21.11), so the player command goes out by name. */
const sprint = (bot, on) => bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 'start_sprinting' : 'stop_sprinting', jumpBoost: 0 })
/** Starts or ends a sprint the way a client does: the sprint command and W with the sprint key held. */
function sprintKeys (bot, on) {
  sprint(bot, on)
  input(bot, on ? { forward: true, sprint: true } : {})
}
/** Whether the server has the player sprinting (an entity_properties predicate through RCON). */
async function sprinting (name) {
  const out = await rcon(`execute as ${name} if predicate {condition:"minecraft:entity_properties",entity:"this",predicate:{flags:{is_sprinting:true}}}`)
  return /passed/i.test(out)
}

/** Inspect packets that arrive while `fn` runs and for `ms` after. */
async function inspectsDuring (bot, fn, ms) {
  const from = Date.now()
  await fn()
  await sleep(ms)
  return bot.inspects.filter(p => p.at >= from).map(p => ({ after: p.at - from, ticks: p.ticks }))
}

const barsSince = (bot, from) => bot.bars.filter(b => b.at >= from).map(b => b.text)

/** Ends a running inspect (a slot switch away and back) and waits until nothing is in flight. */
async function settle (bot) {
  const slot = bot.quickBarSlot
  bot.setQuickBarSlot((slot + 1) % 9)
  await sleep(150)
  bot.setQuickBarSlot(slot)
  await sleep(400)
}

/** Puts `id` into the melee slot (hotbar key 3) and holds it. */
async function holdKnife (bot, id) {
  if (pdc(bot.inventory.slots[38], 'id') !== id) {
    await rcon(`item replace entity ${bot.username} hotbar.2 with minecraft:air`)
    await sleep(200)
    await rcon(`cv give ${bot.username} ${id}`)
  }
  bot.setQuickBarSlot(2)
  return waitFor(() => pdc(bot.heldItem, 'id') === id, 5000)
}

/** Full health and no wounds: the test armory's "满血并清除伤病" button (slot 46 of /cv items). */
async function restore (bot) {
  const window = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    bot.chat('/cv items')
  })
  if (!window) return false
  await bot.clickWindow(46, 0, 0)
  await sleep(250)
  bot.closeWindow(window)
  await sleep(250)
  return true
}

async function health (name) {
  const out = await rcon(`data get entity ${name} Health`)
  const m = out.match(/entity data: (-?[\d.]+)f?\s*$/)
  return m ? Number(m[1]) : null
}

const tp = (bot, x, y, z, yaw = 0, pitch = 0) => rcon(`execute as ${bot.username} at @s run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)

/** Clears the test area around (45, 65, 93) of the proving ground: stone floor, air above. */
async function clearArea (bot) {
  await rcon(`execute at ${bot.username} run fill 39 64 85 52 64 100 minecraft:stone`)
  await rcon(`execute at ${bot.username} run fill 39 65 85 52 70 100 minecraft:air`)
}

/** Holds right click on `target` for `ms`, the way a held button repeats (a raw interact every 150 ms). */
async function holdOn (bot, target, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    bot._client.write('use_entity', { target: target.id, mouse: 0, hand: 0, sneaking: false })
    await sleep(150)
  }
}

async function killSoldiers (bot) {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) {
    await rcon(`execute at ${bot.username} run kill @e[type=minecraft:${type},distance=..512]`)
  }
}

;(async () => {
  const a = await make(`in${run}a`)
  const b = RAID ? await make(`in${run}b`) : null
  const c = RAID ? await make(`in${run}c`) : null // a's squadmate, to knock down and revive
  for (const bot of bots) await rcon(`op ${bot.username}`)
  await sleep(500)

  // 1. Each knife: the group, a full inspect, nothing clearing it early.
  let ticks = null
  for (const knife of KNIVES) {
    if (!await holdKnife(a, knife)) {
      log(`SETUP: could not hold ${knife}`)
      return finish(2)
    }
    await sleep(600)
    const group = cooldownGroup(a.heldItem)
    const got = await inspectsDuring(a, () => pressF(a), 300)
    const start = got.find(p => p.ticks > 0)
    ticks = start ? start.ticks : ticks
    const rest = start ? await inspectsDuring(a, async () => {}, Math.max(0, start.ticks * 50 - 600)) : []
    check(`${knife}: carries ${GROUP}, F starts the inspect and nothing clears it early`,
      group === GROUP && !!start && got.length === 1 && rest.length === 0, { group, got, early: rest })
    await sleep(800)
  }
  if (!ticks) {
    log('SETUP: no inspect started at all')
    return finish(2)
  }
  log(`inspect runs ${ticks} ticks`)

  // 2. F again during an inspect, and as it ends.
  await settle(a)
  {
    const got = await inspectsDuring(a, async () => { pressF(a); await sleep(500); pressF(a) }, 400)
    check('F again 0.5 s into an inspect starts it over (two start packets)', got.filter(p => p.ticks > 0).length === 2, got)
  }
  await sleep(ticks * 50)
  {
    pressF(a)
    await sleep(ticks * 50 - 150)
    const got = await inspectsDuring(a, () => pressF(a), 400)
    check('F as an inspect ends starts it over', got.some(p => p.ticks > 0), got)
  }
  await sleep(ticks * 50)

  // 3. Interruptions.
  for (const [what, act] of [
    ['a swing', async () => a.swingArm('right')],
    ['a slot switch', async () => { a.setQuickBarSlot(3) }]
  ]) {
    await settle(a)
    if (!await holdKnife(a, 'knife_karambit')) return finish(2)
    await sleep(300)
    const start = await inspectsDuring(a, () => pressF(a), 600)
    const cleared = await inspectsDuring(a, act, 300)
    check(`${what} ends a running inspect at once`, start.length === 1 && start[0].ticks > 0 && cleared.some(p => p.ticks === 0), { start, cleared })
    await sleep(800)
  }
  // Not a sprint (#116): it runs on, and so does the sprint.
  await settle(a)
  if (!await holdKnife(a, 'knife_karambit')) return finish(2)
  await sleep(300)
  {
    const from = Date.now()
    const start = await inspectsDuring(a, () => pressF(a), 500)
    sprintKeys(a, true)
    await sleep(300)
    const during = await sprinting(a.username)
    await sleep(Math.max(0, from + ticks * 50 - 250 - Date.now()))
    const late = await sprinting(a.username)
    const got = a.inspects.filter(p => p.at >= from).map(p => ({ after: p.at - from, ticks: p.ticks }))
    sprintKeys(a, false)
    check('starting to sprint 0.5 s into an inspect clears nothing, and the player sprints all through it',
      start.length === 1 && start[0].ticks > 0 && got.length === 1 && during && late, { got, during, late })
    await sleep(800)
  }

  // A gun next to the knife, for the fire mode. Guns go to the two weapon slots only (hotbar 0 and 1,
  // then a backpack, CarryService.route), and a lobby player may have both filled already.
  await settle(a)
  await rcon(`item replace entity ${a.username} hotbar.1 with minecraft:air`)
  await sleep(200)
  await rcon(`cv give ${a.username} m4c`)
  let gunSlot = -1
  await waitFor(() => (gunSlot = a.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && pdc(s, 'id') === 'm4c')) >= 0, 5000)
  if (gunSlot < 0) {
    log('SETUP: no m4c in the hotbar')
    return finish(2)
  }
  gunSlot -= 36
  if (!await holdKnife(a, 'knife_karambit')) return finish(2)
  await sleep(500)

  // 4. What one client tick sends, in its order.
  {
    input(a, { shift: true })
    await sleep(400)
    const from = Date.now()
    const got = await inspectsDuring(a, () => { pressF(a); input(a); tickEnd(a) }, 500)
    const skill = barsSince(a, from).filter(t => t.includes('技能'))
    check('F in the client tick Shift goes up inspects (not the operator skill)', got.some(p => p.ticks > 0) && skill.length === 0, { got, skill })
    await sleep(ticks * 50)
  }
  {
    const from = Date.now()
    const got = await inspectsDuring(a, () => { pressF(a); input(a, { shift: true }); tickEnd(a) }, 800)
    input(a)
    const skill = barsSince(a, from).filter(t => t.includes('技能'))
    check('Shift going down with F in one client tick fires the operator skill (no inspect)', !got.some(p => p.ticks > 0) && skill.length > 0, { got, skill })
    await sleep(1600) // the hint's hold on the action bar
  }
  {
    a.setQuickBarSlot(gunSlot)
    await sleep(600)
    const from = Date.now()
    const got = await inspectsDuring(a, () => {
      pressF(a)
      tickEnd(a)
      a._client.write('held_item_slot', { slotId: 2 }) // the next tick starts with the slot change
      a.quickBarSlot = 2
      a.updateHeldItem()
      tickEnd(a)
    }, 800)
    const mode = barsSince(a, from).filter(t => t.includes('射击模式'))
    check('3 then F in one client tick, from the gun, inspects the knife and leaves the fire mode alone',
      got.some(p => p.ticks > 0) && mode.length === 0, { got, mode })
    await sleep(ticks * 50)
  }
  {
    a.setQuickBarSlot(gunSlot)
    await sleep(600)
    const from = Date.now()
    a.paused = true
    const got = await inspectsDuring(a, async () => {
      pressF(a)
      tickEnd(a)
      await sleep(250) // the next client tick comes late
      a._client.write('held_item_slot', { slotId: 2 })
      a.quickBarSlot = 2
      a.updateHeldItem()
      tickEnd(a)
    }, 800)
    a.paused = false
    const mode = barsSince(a, from).filter(t => t.includes('射击模式'))
    check('3 then F from the gun, the next client tick 250 ms late: inspects the knife, the fire mode stays',
      got.some(p => p.ticks > 0) && mode.length === 0, { got, mode })
    await sleep(ticks * 50)
  }

  // 5. F right after a swing.
  {
    const swungAt = Date.now()
    a.swingArm('right')
    await sleep(50)
    const got = await inspectsDuring(a, () => pressF(a), 600)
    const start = a.inspects.find(p => p.at >= swungAt && p.ticks > 0)
    const after = start ? start.at - swungAt : null
    check('F 50 ms after a swing inspects once the swing is over (>= 200 ms after the swing)', after !== null && after >= 200, { got, after })
    await sleep(ticks * 50)
  }
  if (!await holdKnife(a, 'knife_starter')) return finish(2)
  await sleep(500)
  {
    const swungAt = Date.now()
    a.swingArm('right')
    await sleep(50)
    const got = await inspectsDuring(a, () => pressF(a), 800)
    const start = a.inspects.find(p => p.at >= swungAt && p.ticks > 0)
    const after = start ? start.at - swungAt : null
    check('F 50 ms after a starter knife stab inspects once its 8-tick stab is over (>= 350 ms after it)', after !== null && after >= 350, { got, after })
    await sleep(ticks * 50)
  }
  if (!await holdKnife(a, 'knife_karambit')) return finish(2)
  await sleep(500)

  // 6. F while sprinting inspects (#116), and the sprint goes on; no inspect with sneak+F, or with a
  // gun in hand.
  await settle(a)
  {
    sprintKeys(a, true)
    await sleep(500)
    const before = await sprinting(a.username)
    const from = Date.now()
    pressF(a)
    await sleep(600)
    const during = await sprinting(a.username)
    await sleep(Math.max(0, from + ticks * 50 - 300 - Date.now()))
    const got = a.inspects.filter(p => p.at >= from).map(p => ({ after: p.at - from, ticks: p.ticks }))
    await sleep(600)
    const after = await sprinting(a.username)
    sprintKeys(a, false)
    const bar = barsSince(a, from).filter(t => t.includes('检视'))
    check('F while sprinting inspects, nothing clears it early, no reason on the action bar, and the sprint goes on',
      before && got.length === 1 && got[0].ticks > 0 && bar.length === 0 && during && after, { before, got, bar, during, after })
    await sleep(800)
  }
  {
    a.setControlState('sneak', true)
    await sleep(300)
    const got = await inspectsDuring(a, () => pressF(a), 400)
    a.setControlState('sneak', false)
    check('sneak+F (the operator skill) does not inspect', got.length === 0, got)
    await sleep(1600)
  }
  {
    a.setQuickBarSlot(gunSlot)
    await sleep(500)
    const from = Date.now()
    const got = await inspectsDuring(a, () => pressF(a), 600)
    const mode = barsSince(a, from).filter(t => t.includes('射击模式'))
    check('F with a gun in hand switches the fire mode and does not inspect', got.length === 0 && mode.length === 1, { got, mode })
  }
  {
    await sleep(300)
    const from = Date.now()
    a.paused = true
    pressF(a)
    tickEnd(a)
    await sleep(50)
    pressF(a)
    tickEnd(a)
    await sleep(50)
    tickEnd(a)
    a.paused = false
    await sleep(600)
    const clicks = a.modeClicks.filter(t => t >= from).length
    check('F in two adjacent client ticks with a gun steps the fire mode twice (two clicks)', clicks === 2, { clicks, bars: barsSince(a, from) })
  }

  if (!RAID) return finish(checks.every(c => c.ok) ? 0 : 1)

  // 7. Cuts during an inspect, in the proving ground. A and C are a squad, B is on its own: one pool
  // per map and difficulty, so all three land in the same raid.
  a.chat(`/cv party invite ${c.username}`)
  await sleep(800)
  c.chat('/cv party accept')
  await sleep(800)
  a.chat('/cv queue proving NORMAL TRIO')
  b.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', bots.map(x => x.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and protection
  await killSoldiers(a)
  await clearArea(a)
  await tp(c, 45.5, 65, 98.5, 180, 0) // out of the way of the cuts
  const k = { cd: 8 } // knife_karambit swing interval in ticks (items.yml)
  const place = async () => {
    await killSoldiers(a)
    await rcon(`execute as ${a.username} at @s run tp @s ~ ~ ~ 0 0`)
    await sleep(300)
    await rcon(`execute as ${b.username} at ${a.username} run tp @s ^ ^ ^2 180 0`)
    await sleep(700)
  }
  const target = () => Object.values(a.entities).find(e => e.type === 'player' && e.username === b.username)
  const cut = async () => {
    const e = target()
    if (!e) return null
    await a.lookAt(e.position.offset(0, 1.2, 0), true)
    await sleep(150)
    const before = await health(b.username)
    a.attack(e)
    let after = before
    for (const end = Date.now() + 600; Date.now() < end && after === before; await sleep(30)) after = await health(b.username)
    return before === null || after === null ? null : +(before - after).toFixed(2)
  }
  if (!await holdKnife(a, 'knife_karambit')) return finish(2)
  await place()
  await restore(b)
  {
    const start = await inspectsDuring(a, () => pressF(a), 600)
    let damage = null
    const cleared = await inspectsDuring(a, async () => { damage = await cut() }, 200)
    check('a cut during an inspect lands and ends the inspect', start.length === 1 && start[0].ticks > 0 && damage > 0 && cleared.some(p => p.ticks === 0),
      { start, damage, cleared })
  }
  await sleep(k.cd * 50 + 300)
  await place()
  await restore(b)
  {
    const first = await cut()
    const hitAt = Date.now()
    await sleep(100)
    const from = Date.now()
    pressF(a)
    // The inspect waits for the cut's stab (6 ticks for the karambit) and then starts, before the next cut is due.
    await sleep(Math.max(0, hitAt + k.cd * 50 + 150 - Date.now()))
    const start = a.inspects.filter(p => p.at >= from).map(p => ({ after: p.at - from, ticks: p.ticks }))
    const second = await cut()
    check('a cut, F, and a cut one swing interval later: the inspect starts in between and both cuts land',
      first > 0 && start.length === 1 && start[0].ticks > 0 && second > 0, { first, start, second })
  }
  await sleep(ticks * 50)

  // 8. Reviving C, and downed. C goes down in the cleared area, A stands next to it looking at it.
  await killSoldiers(a)
  await tp(b, 49.5, 65, 86.5, 0, 0)
  await tp(c, 45.5, 65, 94.5, 180, 0)
  await tp(a, 45.5, 65, 92.8, 0, 50)
  if (!await holdKnife(c, 'knife_karambit')) return finish(2) // for F while it is being revived
  await sleep(800)
  await rcon(`damage ${c.username} 500 minecraft:generic`)
  if (!await waitFor(() => c.titles.some(t => t.includes('第一次倒地')), 3000)) {
    log('SETUP: C was not knocked down', c.titles)
    return finish(2)
  }
  await sleep(500)
  const mate = () => a.players[c.username] && a.players[c.username].entity
  if (!mate()) {
    log('SETUP: A does not see C')
    return finish(2)
  }
  {
    const start = await inspectsDuring(a, () => pressF(a), 500)
    const cleared = await inspectsDuring(a, () => holdOn(a, mate(), 900), 0)
    check('holding right click on a downed squadmate (reviving) ends a running inspect',
      start.length === 1 && start[0].ticks > 0 && cleared.some(p => p.ticks === 0), { start, cleared })
  }
  {
    const from = Date.now()
    const got = await inspectsDuring(a, async () => { pressF(a); await holdOn(a, mate(), 1200) }, 0)
    const bars = barsSince(a, from).filter(t => t.includes('无法检视'))
    check('F while reviving does not inspect, and the revive bar says 救援中无法检视',
      !got.some(p => p.ticks > 0) && bars.some(t => t.includes('救援中无法检视') && t.includes('正在救援')), { got, bars })
  }
  await sleep(1500) // let go of C long enough to stop reviving
  {
    // Too far to work on C (downed.revive-range 2.2), close enough to click it: the revive cannot
    // start, and that line holds the action bar instead of the revive bar.
    await tp(a, 45.5, 65, 91.8, 0, 25)
    await sleep(600)
    const hold = holdOn(a, mate(), 1500)
    await sleep(400)
    const from = Date.now()
    const got = await inspectsDuring(a, async () => { pressF(a); await hold }, 0)
    const bars = barsSince(a, from).filter(t => t.includes('无法检视'))
    check('F while holding right click on a downed squadmate out of reach does not inspect, and the 离得太远 line says 救援中无法检视',
      !got.some(p => p.ticks > 0) && bars.some(t => t.includes('救援中无法检视') && t.includes('离得太远')), { got, bars })
    await tp(a, 45.5, 65, 92.8, 0, 50)
    await sleep(1500)
  }
  {
    // C presses F while A works on it: C's action bar is the 正在救援你 bar.
    const hold = holdOn(a, mate(), 1500)
    await sleep(400)
    const from = Date.now()
    const got = await inspectsDuring(c, async () => { pressF(c); await hold }, 0)
    const bars = barsSince(c, from).filter(t => t.includes('无法检视'))
    check('F while downed and being revived does not inspect, and the 正在救援你 bar says 倒地时无法检视',
      !got.some(p => p.ticks > 0) && bars.some(t => t.includes('倒地时无法检视') && t.includes('正在救援你')),
      { got, bars, held: pdc(c.heldItem, 'id') })
    await sleep(1500)
  }
  await rcon(`cv give ${a.username} revive_kit`) // knocked down rather than killed with no mate up
  await sleep(500)
  if (pdc(a.heldItem, 'id') !== 'knife_karambit') {
    log('SETUP: A no longer holds the karambit', pdc(a.heldItem, 'id'))
    return finish(2)
  }
  {
    const start = await inspectsDuring(a, () => pressF(a), 500)
    const cleared = await inspectsDuring(a, () => rcon(`damage ${a.username} 500 minecraft:generic`), 600)
    const down = await waitFor(() => a.titles.some(t => t.includes('第一次倒地')), 2000)
    check('being knocked down ends a running inspect', down && start.length === 1 && start[0].ticks > 0 &&
      cleared.some(p => p.ticks === 0), { down, start, cleared, titles: a.titles.slice(-2) })
  }
  {
    await sleep(500)
    const from = Date.now()
    const got = await inspectsDuring(a, () => pressF(a), 1200)
    const bars = barsSince(a, from).filter(t => t.includes('无法检视'))
    check('F while downed does not inspect, and the bleed-out bar says 倒地时无法检视',
      !got.some(p => p.ticks > 0) && bars.some(t => t.includes('倒地时无法检视') && t.includes('倒地 ')), { got, bars, held: pdc(a.heldItem, 'id') })
  }
  return finish(checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || String(e)); finish(2) })

async function finish (code) {
  if (RAID) {
    for (let i = 0; i < 10; i++) {
      const out = await rcon('cv raids').catch(() => '')
      const mine = bots.some(b => out.includes(b.username)) || /R\d+/.test(out)
      if (!mine) break
      await rcon('cv end').catch(() => {})
      await sleep(1500)
    }
  }
  for (const bot of bots) {
    clearInterval(bot.ticker)
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  const failed = checks.filter(c => !c.ok).length
  console.log(`${checks.length - failed}/${checks.length} checks passed`)
  process.exit(code)
}
