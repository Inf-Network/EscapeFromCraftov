// Knife inspect (issue #82). F with a knife in hand puts the craftov:inspect cooldown group on cooldown
// for combat.melee.inspect-ticks (the client plays the inspect frames of the item model from it); a
// swing, a slot switch or sprinting clears it at once. The bot watches the cooldown packets.
//
//   1. lobby, each of the four knives: the knife carries the inspect cooldown group, F starts the
//      inspect (cooldown > 0) and nothing clears it before it has run out;
//   2. F again during an inspect does not restart it;
//   3. a swing, a slot switch and starting to sprint each clear a running inspect (cooldown 0)
//      within 300 ms;
//   4. no inspect while sprinting, with sneak+F (the operator skill) or with a gun in hand (F
//      switches the fire mode);
//   5. in the proving ground, with the karambit against a second bot: a cut during an inspect lands
//      and ends the inspect; a cut, F right after it, and a second cut one swing interval after the
//      first: the second cut lands too (the inspect does not hold up the knife).
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

  // 2. F again during an inspect.
  await settle(a)
  {
    const got = await inspectsDuring(a, async () => { pressF(a); await sleep(500); pressF(a) }, 400)
    check('F again during an inspect does not restart it (one start packet)', got.length === 1 && got[0].ticks > 0, got)
  }

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

  // 4. No inspect while sprinting, with sneak+F, or with a gun in hand.
  await settle(a)
  if (!await holdKnife(a, 'knife_karambit')) return finish(2)
  {
    sprint(a, true)
    await sleep(500)
    const got = await inspectsDuring(a, () => pressF(a), 400)
    sprint(a, false)
    check('no inspect while sprinting', got.length === 0, got)
    await sleep(800)
  }
  {
    a.setControlState('sneak', true)
    await sleep(300)
    const got = await inspectsDuring(a, () => pressF(a), 400)
    a.setControlState('sneak', false)
    check('sneak+F (the operator skill) does not inspect', got.length === 0, got)
    await sleep(500)
  }
  {
    await rcon(`cv give ${a.username} m4c`)
    let gunSlot = -1
    await waitFor(() => (gunSlot = a.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && pdc(s, 'id') === 'm4c')) >= 0, 5000)
    if (gunSlot < 0) {
      log('SETUP: no m4c in the hotbar')
      return finish(2)
    }
    a.setQuickBarSlot(gunSlot - 36)
    await sleep(500)
    const got = await inspectsDuring(a, () => pressF(a), 400)
    check('F with a gun in hand does not inspect', got.length === 0, got)
  }

  if (!RAID) return finish(checks.every(c => c.ok) ? 0 : 1)

  // 5. Cuts during an inspect, in the proving ground.
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
    const start = await inspectsDuring(a, () => pressF(a), 100)
    await sleep(Math.max(0, hitAt + k.cd * 50 + 150 - Date.now()))
    const second = await cut()
    check('a cut, F, and a cut one swing interval later: both land (the inspect does not hold up the knife)',
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
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  const failed = checks.filter(c => !c.ok).length
  console.log(`${checks.length - failed}/${checks.length} checks passed`)
  process.exit(code)
}
