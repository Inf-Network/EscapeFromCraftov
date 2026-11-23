// Grenades (issue #42). Four solo bots enter the proving ground; RCON builds a flat stone platform
// high above the map and stands them on it around the spot where the thrower T drops a frag at its
// own feet (crouching and looking straight down, so the grenade lands right under its eyes). A throw
// is the button going down (the pin comes out) and coming up again (the grenade leaves the hand).
// RCON reads health and armour durability just before and right after each blast; T is moved well
// out of the radius as soon as the grenade leaves its hand.
//
//   1. cooldown: a throw sends T a set_cooldown for craftov:throw and none for minecraft:paper, and a
//      painkiller used straight after the throw is taken (with the old shared group it was refused);
//   2. armour: two blocks from the blast, B in a class 6 helmet and vest loses well under 0.6 of what
//      the bare A loses (about 25 against 65), and its helmet and vest both lose durability;
//   3. sphere: C at the diagonal (6, 0, 6), 8.5 blocks away, takes nothing;
//   4. cover: A behind a one-block crate 3.8 blocks from the blast, its head over the crate, takes
//      something, and less than half of what C takes in the open at the same distance;
//   5. death report: A at 30 health, killed by a blast two blocks away, finds a 破片手雷 line in the
//      damage log it gets after the kill replay;
//   6. bounce: thrown flat at a wall three blocks off, the grenade comes back off it and rests more
//      than a block in front of the wall, not at its foot;
//   7. cook: held for two seconds, the frag goes off about 1.5 s after it leaves the hand (3.5 s after
//      the button went down), not 3.5 s after the throw;
//   8. held too long: C keeps holding a frag past its fuse; it goes off in its hand, the frag is gone
//      and C is hurt;
//   9. pin back: T pulls the pin and takes out another item; nothing goes off, T keeps the frag and
//      no throw cooldown is set, and the item use the server started for the pull (T's own living
//      entity flags, which the client follows) ends with the swap, so it does not carry over to the
//      painkiller (a client still "using" something can neither fire nor aim);
//  10. flash: a flashbang (radius 10) at T's feet rings the ears of A four blocks away facing it, and
//      not C at the diagonal (8, 0, 8), 11.4 blocks from it;
//  11. wounds: the bare C two blocks from a blast bleeds or has a fracture on its HUD body (/cv hud)
//      within eight blasts (each one does it about half the time);
//  12. menu: a menu opening while the pin is out (the item catalog) puts the pin back: nothing goes
//      off once T closes it and lets go;
//  13. box: a right click on an armory box with a frag in hand (the click on the box, then the item
//      use the vanilla client sends after it) opens the box and pulls no pin;
//  14. fence: a right click on a fence post (which the client does not use, so the item use follows
//      the click) pulls the pin and throws the frag;
//  15. door: a right click on a wooden door (which the client opens itself, sending nothing more)
//      opens the door and pulls no pin.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node grenade.js [only...]
//
// Name scenarios (cooldown armour sphere cover death bounce cook overcook pinback flash wounds menu
// box fence door) to run only those; the setup is the same. Needs test.enabled true (the proving ground) and a short match.wait-seconds; the
// bots are made op for /cv queue and /cv items and deopped when done. Exit code 0 when every check
// passes, 1 when one fails, 2 when the scenario could not be set up.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 3; the bots are made op for /cv items.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []
const ONLY = process.argv.slice(2)
const want = name => ONLY.length === 0 || ONLY.includes(name)

// The platform: stone at Y - 1, a 21 x 21 square around (X, Z), clear above. Above the proving
// ground's buildings (ground level 64), inside its 120 x 104 footprint.
const X = 60
const Y = 150
const Z = 50
const FUSE_MS = 3500 // items.yml frag fuse: 70 ticks

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.cooldowns = []
  bot._client.on('set_cooldown', p => bot.cooldowns.push({ group: p.cooldownGroup, ticks: p.cooldownTicks, at: Date.now() }))
  bot.sounds = []
  const heard = s => { s = String(s); if (/craftov:(throw|med|status)\./.test(s)) bot.sounds.push({ name: s, at: Date.now() }) }
  bot.on('soundEffectHeard', heard)
  bot._client.on('entity_sound_effect', p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_.\/]+/); if (m) heard(m[0]) })
  // Whether the server has the bot using an item: bit 1 of its own living entity flags, which the
  // vanilla client follows (it stops its own use when the server clears it).
  bot.using = []
  bot._client.on('entity_metadata', p => {
    if (!bot.entity || p.entityId !== bot.entity.id) return
    const key = bot.registry.entitiesByName.player.metadataKeys.indexOf('living_entity_flags')
    for (const m of p.metadata) if (m.key === key) bot.using.push({ on: (m.value & 1) !== 0, at: Date.now() })
  })
  bot.msgs = []
  bot.on('messagestr', m => bot.msgs.push(m))
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(100) }
  return false
}

const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const pdc = (item, key) => { const m = JSON.stringify(item && item.components || '').match(new RegExp(`"craftov:${key}":\\{"type":"\\w+","value":"?([^",}]+)`)); return m ? m[1] : null }
const commandSlot = i => i >= 36 ? `hotbar.${i - 36}` : `inventory.${i - 9}`
const round = n => n === null ? null : +n.toFixed(2)

/** A number out of `data get entity` ("... has the following entity data: 87.5f"), or null. */
async function dataNumber (target, path) {
  const out = await rcon(`data get entity ${target} ${path}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
const health = name => dataNumber(name, 'Health')
const dura = (name, slot) => dataNumber(name, `equipment.${slot}.components."minecraft:custom_data".PublicBukkitValues."craftov:dura"`)

/** Gives the bot one `id` (or `amount`) into hotbar slot `key`; the hotbar key, or -1. */
async function giveToHotbar (bot, id, key, amount = 1) {
  await rcon(`cv give ${bot.username} ${id} ${amount}`)
  let i = -1
  await waitFor(() => (i = bot.inventory.slots.findIndex((s, n) => n >= 9 && n <= 44 && n !== 36 + key && s && pdc(s, 'id') === id)) >= 0
    || pdc(bot.inventory.slots[36 + key], 'id') === id, 5000)
  if (i >= 0) {
    await rcon(`item replace entity ${bot.username} hotbar.${key} from entity ${bot.username} ${commandSlot(i)}`)
    await rcon(`item replace entity ${bot.username} ${commandSlot(i)} with minecraft:air`)
  }
  return await waitFor(() => pdc(bot.inventory.slots[36 + key], 'id') === id, 5000) ? key : -1
}
const countIn = (bot, key) => { const s = bot.inventory.slots[36 + key]; return s ? s.count : 0 }

/** Puts a fresh helmet or vest on the bot. */
async function wear (bot, id, slot) {
  if (await giveToHotbar(bot, id, 4) < 0) return false
  await rcon(`item replace entity ${bot.username} armor.${slot} from entity ${bot.username} hotbar.4`)
  await rcon(`item replace entity ${bot.username} hotbar.4 with minecraft:air`)
  return waitFor(() => pdc(bot.inventory.slots[slot === 'head' ? 5 : 6], 'id') === id, 5000)
}

/** Full health and no wounds: the "满血并清除伤病" button (slot 46) of /cv items. */
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
  return (await health(bot.username)) === 100
}

const place = (bot, x, z, yaw = 0, pitch = 0) => rcon(`execute as ${bot.username} at @s run tp @s ${x} ${Y} ${z} ${yaw} ${pitch}`)
const blasts = bot => bot.sounds.filter(s => s.name === 'craftov:throw.explode')
const W = bot => `minecraft:${bot.worldName}`

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}

/** Builds the platform (and clears whatever an earlier scenario put on it). */
async function platform (T) {
  await rcon(`execute in ${W(T)} run fill ${X - 10} ${Y} ${Z - 10} ${X + 10} ${Y + 4} ${Z + 10} minecraft:air`)
  return rcon(`execute in ${W(T)} run fill ${X - 10} ${Y - 1} ${Z - 10} ${X + 10} ${Y - 1} ${Z + 10} minecraft:stone`)
}

/**
 * T drops a frag from hotbar slot `key` at its own feet at (X + 0.5, Z + 0.5): crouching, looking
 * straight down, the button held for `holdMs`. Once the release has reached the server (the
 * grenade leaves the hand where T stands) T is moved 12 blocks off. Returns the time the button
 * went down and the time it came up.
 */
async function dropFrag (T, key, holdMs = 150, after = null) {
  await place(T, X + 0.5, Z + 0.5, 0, 90)
  T.setQuickBarSlot(key)
  T.setControlState('sneak', true)
  await sleep(400)
  await T.look(0, -Math.PI / 2, true)
  await sleep(200)
  const down = Date.now()
  T.activateItem()
  await sleep(holdMs)
  T.deactivateItem()
  const up = Date.now()
  if (after) await after()
  await sleep(150)
  T.setControlState('sneak', false)
  await place(T, X + 8.5, Z - 8.5)
  return { down, up }
}

/** Waits for the next blast T hears after `since`; its time, or null. */
async function nextBlast (T, since, ms = 6000) {
  let at = null
  await waitFor(() => { const b = blasts(T).find(s => s.at > since); if (b) at = b.at; return !!b }, ms)
  return at
}

/** Health of each bot read right after the blast (polled until the first one changes). */
async function healthAfter (victims, before, since, T) {
  const at = await nextBlast(T, since)
  if (at === null) return null
  const end = Date.now() + 1500
  let now
  do {
    now = []
    for (const v of victims) now.push(await health(v.username))
  } while (Date.now() < end && now.every((h, i) => h === before[i]))
  return now
}

;(async () => {
  const T = await make(`gr${run}t`)
  const A = await make(`gr${run}a`)
  const B = await make(`gr${run}b`)
  const C = await make(`gr${run}c`)
  for (const bot of bots) await rcon(`op ${bot.username}`)
  for (const bot of bots) bot.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached one raid', bots.map(b => b.worldName))
    return finish(2)
  }
  if (new Set(bots.map(b => b.worldName)).size !== 1) {
    log('SETUP: the bots ended up in different raids', bots.map(b => b.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and spawn protection
  await clearSoldiers()
  // The platform first: a bot put up there before it stands falls to its death.
  await rcon(`execute in ${W(T)} run forceload add ${X - 10} ${Z - 10} ${X + 10} ${Z + 10}`)
  const built = await platform(T)
  if (!/filled|Successfully/i.test(built)) {
    log('SETUP: could not build the platform', built)
    return finish(2)
  }
  await place(T, X + 0.5, Z + 0.5)
  await sleep(500)
  const FRAG = 5
  const PILL = 6
  if (await giveToHotbar(T, 'frag', FRAG, 2) < 0 || await giveToHotbar(T, 'painkiller', PILL) < 0) {
    log('SETUP: could not give T its frags and painkiller')
    return finish(2)
  }
  // The starter kit dresses everyone in class 2: A and C go bare.
  for (const v of [A, C]) {
    for (const slot of ['head', 'chest']) await rcon(`item replace entity ${v.username} armor.${slot} with minecraft:air`)
  }
  const topUp = async () => countIn(T, FRAG) > 0 || await giveToHotbar(T, 'frag', FRAG, 2) >= 0

  // 1-3. One blast: A bare and B in class 6 two blocks east and west, C at the diagonal (6, 0, 6).
  if (want('cooldown') || want('armour') || want('sphere')) {
    if (!await wear(B, 'helmet_6', 'head') || !await wear(B, 'armor_6', 'chest')) {
      log('SETUP: could not put the class 6 helmet and vest on B')
      return finish(2)
    }
    for (const v of [A, B, C]) await restore(v)
    await place(A, X + 2.5, Z + 0.5, 90)
    await place(B, X - 1.5, Z + 0.5, -90)
    await place(C, X + 6.5, Z + 6.5, 135)
    await clearSoldiers()
    await sleep(600)
    const victims = [A, B, C]
    const before = []
    for (const v of victims) before.push(await health(v.username))
    const helmet0 = await dura(B.username, 'head')
    const vest0 = await dura(B.username, 'chest')
    const pills0 = countIn(T, PILL)
    const mark = Date.now()
    T.cooldowns.length = 0
    T.sounds.length = 0
    // The painkiller goes straight after the throw, while the throw's one-second cooldown runs.
    let pillAt = null
    const { up } = await dropFrag(T, FRAG, 150, async () => {
      T.setQuickBarSlot(PILL)
      await sleep(50)
      pillAt = Date.now()
      T.activateItem()
    })
    const after = await healthAfter(victims, before, mark, T)
    await sleep(800)
    T.deactivateItem()
    const helmet1 = await dura(B.username, 'head')
    const vest1 = await dura(B.username, 'chest')
    if (!after) {
      log('SETUP: no blast heard', { sounds: T.sounds, cooldowns: T.cooldowns, frags: countIn(T, FRAG), held: T.quickBarSlot, slot: pdc(T.inventory.slots[36 + FRAG], 'id') })
      return finish(2)
    }
    const lost = after.map((h, i) => round(before[i] - h))
    log('blast 1 losses (A bare 2 m, B class 6 2 m, C diagonal)', lost, { helmet: [helmet0, helmet1], vest: [vest0, vest1] })
    if (want('cooldown')) {
      const groups = T.cooldowns.map(c => `${c.group}:${c.ticks}`)
      check('cooldown: the throw set craftov:throw for 20 ticks', T.cooldowns.some(c => c.group === 'craftov:throw' && c.ticks === 20), groups)
      check('cooldown: nothing set a cooldown on minecraft:paper', !T.cooldowns.some(c => /paper/.test(c.group)), groups)
      check(`cooldown: the painkiller used ${pillAt - up} ms after the throw was taken`,
        countIn(T, PILL) === pills0 - 1 && T.sounds.some(s => s.name === 'craftov:med.finish'), { pills: [pills0, countIn(T, PILL)] })
    }
    if (want('armour')) {
      check('armour: the bare A lost a lot (50-80) two blocks from the blast', lost[0] > 50 && lost[0] < 80, lost[0])
      check('armour: B in class 6 lost well under 0.6 of that', lost[1] > 0 && lost[1] < 0.6 * lost[0], lost[1])
      check('armour: B\'s helmet and vest both lost durability', helmet1 < helmet0 && vest1 < vest0, { helmet: [helmet0, helmet1], vest: [vest0, vest1] })
    }
    if (want('sphere')) check('sphere: C at the diagonal (6, 0, 6) took nothing', lost[2] === 0, lost[2])
    await sleep(1500)
  }

  // 4. Cover: A 3.8 blocks south behind a one-block crate, C 3.8 blocks north in the open.
  if (want('cover')) {
    if (!await topUp()) return finish(2)
    await rcon(`execute in ${W(T)} run setblock ${X} ${Y} ${Z + 3} minecraft:oak_planks`)
    for (const v of [A, C]) await restore(v)
    await place(A, X + 0.5, Z + 4.3, 180)
    await place(C, X + 0.5, Z - 3.3, 0)
    await place(B, X - 8.5, Z + 8.5)
    await clearSoldiers()
    await sleep(600)
    const victims = [A, C]
    const before = []
    for (const v of victims) before.push(await health(v.username))
    const mark = Date.now()
    await dropFrag(T, FRAG)
    const after = await healthAfter(victims, before, mark, T)
    await rcon(`execute in ${W(T)} run setblock ${X} ${Y} ${Z + 3} minecraft:air`)
    if (!after) {
      log('SETUP: no blast heard')
      return finish(2)
    }
    const lost = after.map((h, i) => round(before[i] - h))
    log('blast 2 losses (A behind the crate, C in the open, both 3.8 m)', lost)
    check('cover: A, head over the crate, took some damage', lost[0] > 0, lost[0])
    check('cover: and less than half of what C took in the open', lost[0] < 0.5 * lost[1], lost)
    await sleep(1500)
  }

  // 10. Flash: A four blocks east facing the blast, C at the diagonal (8, 0, 8) facing it too.
  if (want('flash')) {
    const FLASH = 7
    if (await giveToHotbar(T, 'flash', FLASH) < 0) {
      log('SETUP: could not give T a flashbang')
      return finish(2)
    }
    await place(A, X + 4.5, Z + 0.5, 90)
    await place(C, X + 8.5, Z + 8.5, 135)
    await place(B, X - 8.5, Z + 8.5)
    await clearSoldiers()
    await sleep(600)
    const mark = Date.now()
    await dropFrag(T, FLASH)
    let at = null
    await waitFor(() => { const b = T.sounds.find(s => s.name === 'craftov:throw.flash' && s.at > mark); if (b) at = b.at; return !!b }, 5000)
    await sleep(600)
    const rang = bot => bot.sounds.some(s => s.name === 'craftov:status.tinnitus' && s.at > mark)
    log('flash: went off after', at && at - mark, 'ms; ears ringing', { A: rang(A), C: rang(C) })
    if (at === null) {
      log('SETUP: no flashbang heard')
      return finish(2)
    }
    check('flash: A four blocks away facing it is flashed', rang(A))
    check('flash: C at the diagonal (8, 0, 8), 11.4 blocks off, is not', !rang(C))
    await sleep(3500) // the white screen and its blindness wear off
  }

  // 9. Pin back: the pin comes out, then T takes out the painkiller instead.
  if (want('pinback')) {
    if (!await topUp()) return finish(2)
    await place(T, X + 0.5, Z + 0.5, 0, 0)
    await place(A, X + 2.5, Z + 0.5, 90)
    T.setQuickBarSlot(FRAG)
    await sleep(1200) // the last throw's cooldown is long over
    const frags = countIn(T, FRAG)
    T.cooldowns.length = 0
    T.using.length = 0
    const mark = Date.now()
    T.activateItem()
    await sleep(600)
    const pulled = T.using.some(u => u.on)
    const swap = Date.now()
    T.setQuickBarSlot(PILL)
    await sleep(600)
    const ended = T.using.find(u => !u.on && u.at > swap)
    const after = T.using.filter(u => u.at > swap)
    T.deactivateItem()
    const blast = await nextBlast(T, mark, FUSE_MS + 1500)
    log('pin back: blast', blast && blast - mark, 'frags', frags, '->', countIn(T, FRAG), 'cooldowns', T.cooldowns,
      'use flag', T.using.map(u => `${u.on ? 'on' : 'off'}@${u.at - mark}`))
    check('pin back: nothing went off', blast === null, blast && blast - mark)
    check('pin back: T still has the frag', countIn(T, FRAG) === frags, [frags, countIn(T, FRAG)])
    check('pin back: no throw cooldown was set', !T.cooldowns.some(c => c.group === 'craftov:throw' && c.ticks > 0), T.cooldowns)
    check('pin back: the pull started an item use (T\'s own use flag came on)', pulled, T.using)
    check('pin back: and the swap ended it (flag off within 500 ms, and not back on): no use carries over to the painkiller',
      !!ended && ended.at - swap < 500 && !after.some(u => u.on && u.at > ended.at), after.map(u => `${u.on ? 'on' : 'off'}@${u.at - swap}`))
  }

  // 12. Menu: the pin comes out, then the item catalog opens over the screen.
  if (want('menu')) {
    if (!await topUp()) return finish(2)
    await place(T, X + 0.5, Z + 0.5, 0, 0)
    T.setQuickBarSlot(FRAG)
    await sleep(1200)
    const frags = countIn(T, FRAG)
    const mark = Date.now()
    T.activateItem()
    await sleep(600)
    const window = await new Promise(resolve => {
      const timer = setTimeout(() => resolve(null), 4000)
      T.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
      T.chat('/cv items')
    })
    await sleep(800)
    if (window) T.closeWindow(window)
    await sleep(300)
    T.deactivateItem()
    const blast = await nextBlast(T, mark, FUSE_MS + 1500)
    log('menu: window', !!window, 'blast', blast && blast - mark, 'frags', frags, '->', countIn(T, FRAG))
    if (!window) {
      log('SETUP: the item catalog did not open')
      return finish(2)
    }
    check('menu: a menu opening over a cooked grenade puts the pin back: nothing went off', blast === null, blast && blast - mark)
    check('menu: and T still has the frag', countIn(T, FRAG) === frags, [frags, countIn(T, FRAG)])
  }

  // 13. Box: a right click on a box in the armory with a frag in hand opens it and pulls no pin.
  if (want('box')) {
    if (!await topUp()) return finish(2)
    // The armory's medical box (ProvingGroundGenerator: x 8 + 4 * 13, z 77, on the ground at 64).
    await rcon(`execute as ${T.username} at @s run tp @s 60.5 65 79.5 180 35`)
    T.setQuickBarSlot(FRAG)
    await sleep(1500)
    const box = T.nearestEntity(e => e.name === 'interaction' && e.position.distanceTo(T.entity.position) < 4)
    if (!box) {
      log('SETUP: no box in front of T')
      return finish(2)
    }
    const frags = countIn(T, FRAG)
    const mark = Date.now()
    T.cooldowns.length = 0
    const opened = new Promise(resolve => {
      const timer = setTimeout(() => resolve(null), 3000)
      T.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    })
    // What the vanilla client sends for one right click on an entity that does not take it on its
    // side: the click on the entity, then the use of the item in hand.
    T.activateEntity(box)
    T.activateItem()
    const window = await opened
    await sleep(800)
    if (window) T.closeWindow(window)
    await sleep(300)
    T.deactivateItem()
    const blast = await nextBlast(T, mark, FUSE_MS + 1500)
    const pin = T.sounds.some(s => s.name === 'craftov:throw.pin' && s.at > mark)
    log('box: window', !!window, 'pin', pin, 'blast', blast && blast - mark, 'frags', frags, '->', countIn(T, FRAG))
    await place(T, X + 0.5, Z + 0.5)
    if (!window) {
      log('SETUP: the box did not open')
      return finish(2)
    }
    check('box: the box opened and no pin came out', !pin, T.sounds.filter(s => s.at > mark))
    check('box: nothing went off and T keeps its frags', blast === null && countIn(T, FRAG) === frags, { blast, frags: [frags, countIn(T, FRAG)] })
  }

  // 14. Fence: T two blocks from a spruce fence post, looking at it, presses and lets go. What the
  // vanilla client sends: the click on the post (which it does not use itself), then the item use.
  if (want('fence')) {
    if (!await topUp()) return finish(2)
    await rcon(`execute in ${W(T)} run setblock ${X + 2} ${Y} ${Z} minecraft:spruce_fence`)
    for (const v of [A, B, C]) await place(v, X - 8.5, Z + 8.5)
    await place(T, X + 0.5, Z + 0.5, -90, 0)
    T.setQuickBarSlot(FRAG)
    await sleep(1200)
    const fence = T.blockAt(new Vec3(X + 2, Y, Z))
    if (!fence || fence.name !== 'spruce_fence') {
      log('SETUP: no fence post in front of T', fence && fence.name)
      return finish(2)
    }
    const frags = countIn(T, FRAG)
    const mark = Date.now()
    await T.activateBlock(fence)
    T.activateItem()
    await sleep(300)
    T.deactivateItem()
    await sleep(150)
    await place(T, X + 8.5, Z - 8.5)
    const blast = await nextBlast(T, mark, FUSE_MS + 1500)
    const pin = T.sounds.some(s => s.name === 'craftov:throw.pin' && s.at > mark)
    await rcon(`execute in ${W(T)} run setblock ${X + 2} ${Y} ${Z} minecraft:air`)
    log('fence: pin', pin, 'blast', blast && blast - mark, 'frags', frags, '->', countIn(T, FRAG))
    check('fence: a right click on a fence post pulled the pin', pin, T.sounds.filter(s => s.at > mark))
    check('fence: and the frag left the hand and went off', blast !== null && countIn(T, FRAG) === frags - 1, { blast: blast && blast - mark, frags: [frags, countIn(T, FRAG)] })
    await sleep(1000)
  }

  // 15. Door: T two blocks from an oak door, looking at it, clicks it once. The vanilla client opens
  // a wooden door itself and sends no item use after the click.
  if (want('door')) {
    if (!await topUp()) return finish(2)
    await rcon(`execute in ${W(T)} run setblock ${X + 2} ${Y} ${Z} minecraft:oak_door[half=lower,facing=west]`)
    await rcon(`execute in ${W(T)} run setblock ${X + 2} ${Y + 1} ${Z} minecraft:oak_door[half=upper,facing=west]`)
    const doorOpen = async () => /passed/i.test(await rcon(`execute in ${W(T)} if block ${X + 2} ${Y} ${Z} minecraft:oak_door[open=true]`))
    for (const v of [A, B, C]) await place(v, X - 8.5, Z + 8.5)
    await place(T, X + 0.5, Z + 0.5, -90, 0)
    T.setQuickBarSlot(FRAG)
    await sleep(1200)
    const door = T.blockAt(new Vec3(X + 2, Y, Z))
    if (!door || door.name !== 'oak_door' || await doorOpen()) {
      log('SETUP: no closed oak door in front of T', door && door.name)
      return finish(2)
    }
    const frags = countIn(T, FRAG)
    const mark = Date.now()
    await T.activateBlock(door)
    await sleep(600)
    const open = await doorOpen()
    const blast = await nextBlast(T, mark, FUSE_MS + 1000)
    const pin = T.sounds.some(s => s.name === 'craftov:throw.pin' && s.at > mark)
    await rcon(`execute in ${W(T)} run fill ${X + 2} ${Y} ${Z} ${X + 2} ${Y + 1} ${Z} minecraft:air`)
    log('door: open', open, 'pin', pin, 'blast', blast && blast - mark, 'frags', frags, '->', countIn(T, FRAG))
    check('door: a right click on a wooden door with a frag in hand opened it', open)
    check('door: and pulled no pin, nothing went off, T keeps its frags', !pin && blast === null && countIn(T, FRAG) === frags,
      { pin, blast, frags: [frags, countIn(T, FRAG)] })
  }

  // 11. Wounds: the bare C two blocks from the blast, again and again until a line of shrapnel that
  // got through makes it bleed or breaks a bone (each blast does about half the time).
  if (want('wounds')) {
    let flags = 0
    let blasts = 0
    while (blasts < 8 && (flags & 7) === 0) {
      if (!await topUp()) return finish(2)
      await restore(C)
      await place(C, X + 2.5, Z + 0.5, 90)
      await place(A, X - 8.5, Z + 8.5)
      await clearSoldiers()
      await sleep(400)
      const mark = Date.now()
      await dropFrag(T, FRAG)
      if (await nextBlast(T, mark) === null) {
        log('SETUP: no blast heard')
        return finish(2)
      }
      await sleep(400)
      blasts++
      const m = (await rcon(`cv hud ${C.username}`)).match(/wounds=(\d+)/)
      flags = m ? Number(m[1]) : 0
    }
    log('wounds: after', blasts, 'blasts, HUD wound flags', flags)
    check('wounds: shrapnel made C bleed or broke a bone (HUD body)', (flags & 7) !== 0, { blasts, flags })
    await restore(C)
  }

  // 5. Death report: A, bare and at 30 health, two blocks from the blast.
  if (want('death')) {
    if (!await topUp()) return finish(2)
    await restore(A)
    await rcon(`damage ${A.username} 70 minecraft:generic`)
    await place(A, X + 2.5, Z + 0.5, 90)
    await place(C, X - 8.5, Z + 8.5)
    await clearSoldiers()
    await sleep(600)
    const mark = A.msgs.length
    await dropFrag(T, FRAG)
    const logged = await waitFor(() => A.msgs.slice(mark).some(m => m.includes('受到的伤害')), 20000)
    await sleep(500)
    const lines = A.msgs.slice(mark).filter(m => /致命一击|受到的伤害|破片手雷/.test(m))
    log('A\'s death report', lines)
    check('death: A got a damage log', logged, A.msgs.slice(mark).slice(-6))
    check('death: the damage log has a 破片手雷 line', A.msgs.slice(mark).some(m => m.includes(T.username) && m.includes('破片手雷') && m.includes('→')), lines)
  }

  // 6. Bounce: thrown flat east at a wall whose face is 3.5 blocks off.
  if (want('bounce')) {
    if (!await topUp()) return finish(2)
    await rcon(`execute in ${W(T)} run fill ${X + 4} ${Y} ${Z - 3} ${X + 4} ${Y + 3} ${Z + 3} minecraft:stone`)
    await place(T, X + 0.5, Z + 0.5, -90, 0)
    T.setQuickBarSlot(FRAG)
    await sleep(400)
    await T.look(-Math.PI / 2, 0, true)
    await sleep(200)
    const mark = Date.now()
    T.activateItem()
    await sleep(100)
    T.deactivateItem()
    await sleep(2200) // flown, bounced and come to rest
    const pos = await grenadePos(T)
    await place(T, X - 8.5, Z - 8.5)
    await nextBlast(T, mark)
    await rcon(`execute in ${W(T)} run fill ${X + 4} ${Y} ${Z - 3} ${X + 4} ${Y + 3} ${Z + 3} minecraft:air`)
    log('bounce: grenade at rest', pos)
    check('bounce: the grenade came back off the wall and rests over a block in front of it', !!pos && pos.x < X + 4 - 1.0, pos)
    check('bounce: and on the platform', !!pos && Math.abs(pos.y - Y) < 0.5, pos)
    await sleep(1000)
  }

  // 7. Cooking: two seconds in the hand.
  if (want('cook')) {
    if (!await topUp()) return finish(2)
    const { down, up } = await dropFrag(T, FRAG, 2000)
    const at = await nextBlast(T, down)
    log('cook: down -> up', up - down, 'ms; up -> blast', at && at - up, 'ms')
    check('cook: the frag went off about 1.5 s after the throw (fuse from the button going down)',
      at !== null && at - up > 1100 && at - up < 1900, at && { downToBlast: at - down, upToBlast: at - up })
    await sleep(1000)
  }

  // 8. Held past the fuse: C keeps the button down for 4.5 s.
  if (want('overcook')) {
    if (C.game.gameMode !== 'adventure' || await giveToHotbar(C, 'frag', FRAG) < 0) {
      log('SETUP: C cannot hold a frag', C.game.gameMode)
      return finish(2)
    }
    await restore(C)
    await place(C, X + 0.5, Z + 0.5, 0, 0)
    await place(T, X + 8.5, Z - 8.5)
    C.setQuickBarSlot(FRAG)
    await sleep(500)
    const hp0 = await health(C.username)
    const down = Date.now()
    C.activateItem()
    const at = await nextBlast(T, down, 5000)
    const held = at !== null
    await sleep(300)
    const hp1 = C.game.gameMode === 'adventure' ? await health(C.username) : 0
    C.deactivateItem()
    log('overcook: blast after', at && at - down, 'ms; health', hp0, '->', hp1)
    check('overcook: the frag went off in C\'s hand about 3.5 s after the button went down',
      held && at - down > FUSE_MS - 400 && at - down < FUSE_MS + 400, at && at - down)
    check('overcook: the frag is gone from C\'s hand', countIn(C, FRAG) === 0, countIn(C, FRAG))
    check('overcook: C was hurt by it', hp1 < hp0, [hp0, hp1])
  }
  finish(checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

/** Where the grenade nearest T (a display or an item) is now, or null. */
async function grenadePos (T) {
  for (const type of ['item_display', 'item']) {
    const out = await rcon(`execute in ${W(T)} positioned ${X} ${Y} ${Z} run data get entity @e[type=minecraft:${type},distance=..12,sort=nearest,limit=1] Pos`)
    const m = out.match(/\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]/)
    if (m) return { x: +(+m[1]).toFixed(2), y: +(+m[2]).toFixed(2), z: +(+m[3]).toFixed(2), type }
  }
  return null
}

async function finish (code) {
  for (const bot of bots) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
