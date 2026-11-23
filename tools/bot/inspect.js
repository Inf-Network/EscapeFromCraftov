// Knife inspect (issues #82 and #104). F with a knife in hand puts the craftov:inspect cooldown group on
// cooldown for combat.melee.inspect-ticks (the client plays the inspect frames of the item model from
// it); a swing, a slot switch or sprinting clears it at once. The bot watches the cooldown packets and
// the action bar.
//
// A real client sends F from the middle of its tick: Shift and W (so the end of a sprint) follow at the
// end of the same tick, a hotbar key pressed in the same tick only at the start of the next, and every
// tick ends with a tick_end packet. The bot sends tick_end every 50 ms like a client, and the "one
// client tick" checks write a tick's packets in exactly that order (tools/shots/scenes/inspectkeys.py
// presses the same keys with the real client).
//
//   1. lobby, each of the four knives: the knife carries the inspect cooldown group, F starts the
//      inspect (cooldown > 0) and nothing clears it before it has run out;
//   2. F again 0.5 s into an inspect starts it over (a second start), and so does F as it ends;
//   3. a swing, a slot switch and starting to sprint each clear a running inspect (cooldown 0)
//      within 300 ms;
//   4. the same client tick: F as W is let go after a sprint inspects (it was refused as sprinting);
//      F as Shift goes up inspects (it fired the operator skill); Shift going down with F fires the
//      skill (it inspected); 3 then F from a gun inspects the knife and leaves the gun's fire mode
//      alone (it switched the fire mode);
//   5. F 50 ms after a swing inspects once the swing is over (at least 200 ms after the swing, not
//      over it);
//   6. no inspect while sprinting, and the action bar says 冲刺中无法检视; none with sneak+F (the
//      operator skill); with a gun in hand F switches the fire mode and does not inspect;
//   7. in the proving ground, with the karambit against a second bot: a cut during an inspect lands
//      and ends the inspect; a cut, F right after it, and a second cut one swing interval after the
//      first: the inspect starts in between and the second cut lands too (the inspect does not hold
//      up the knife).
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node inspect.js [--no-raid]
//
// Needs test mode on (test.enabled, the proving ground is a test map) and, to keep it short,
// match.wait-seconds of a few seconds. The bots are made op for the test armory (full health between
// cuts) and deopped when done. Exit code 0 when every check passes, 1 when one fails, 2 when the
// scenario could not be set up.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
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
  // A client ends every tick with tick_end; the server reads F by them.
  bot.ticker = setInterval(() => { if (bot._client.state === 'play') tickEnd(bot) }, 50)
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

async function killSoldiers (bot) {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) {
    await rcon(`execute at ${bot.username} run kill @e[type=minecraft:${type},distance=..512]`)
  }
}

;(async () => {
  const a = await make(`in${run}a`)
  const b = RAID ? await make(`in${run}b`) : null
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
    ['a slot switch', async () => { a.setQuickBarSlot(3) }],
    ['starting to sprint', async () => sprint(a, true)]
  ]) {
    await settle(a)
    if (!await holdKnife(a, 'knife_karambit')) return finish(2)
    await sleep(300)
    const start = await inspectsDuring(a, () => pressF(a), 600)
    const cleared = await inspectsDuring(a, act, 300)
    sprint(a, false)
    check(`${what} ends a running inspect at once`, start.length === 1 && start[0].ticks > 0 && cleared.some(p => p.ticks === 0), { start, cleared })
    await sleep(800)
  }

  // A gun next to the knife, for the fire mode.
  await settle(a)
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
    sprint(a, true)
    input(a, { forward: true, sprint: true })
    await sleep(500)
    const got = await inspectsDuring(a, () => { pressF(a); input(a); sprint(a, false); tickEnd(a) }, 400)
    check('F in the client tick W is let go after a sprint inspects', got.some(p => p.ticks > 0), got)
    await sleep(ticks * 50)
  }
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

  // 6. No inspect while sprinting, with sneak+F, or with a gun in hand.
  await settle(a)
  {
    sprint(a, true)
    input(a, { forward: true, sprint: true })
    await sleep(500)
    const from = Date.now()
    const got = await inspectsDuring(a, () => pressF(a), 400)
    await waitFor(() => barsSince(a, from).some(t => t.includes('冲刺中无法检视')), 1600)
    sprint(a, false)
    input(a)
    const bar = barsSince(a, from)
    check('no inspect while sprinting, and the action bar says 冲刺中无法检视', got.length === 0 && bar.some(t => t.includes('冲刺中无法检视')), { got, bar })
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

  if (!RAID) return finish(checks.every(c => c.ok) ? 0 : 1)

  // 7. Cuts during an inspect, in the proving ground.
  for (const bot of bots) bot.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', bots.map(x => x.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and protection
  await killSoldiers(a)
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
    // The inspect waits for the cut's swing (6 ticks) and then starts, before the next cut is due.
    await sleep(Math.max(0, hitAt + k.cd * 50 + 150 - Date.now()))
    const start = a.inspects.filter(p => p.at >= from).map(p => ({ after: p.at - from, ticks: p.ticks }))
    const second = await cut()
    check('a cut, F, and a cut one swing interval later: the inspect starts in between and both cuts land',
      first > 0 && start.length === 1 && start[0].ticks > 0 && second > 0, { first, start, second })
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
