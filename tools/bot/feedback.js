// What a hit, a wound and a medical item sound and look like to the people around (issue #41).
// Three solo bots on the proving ground (test mode): A shoots, B is shot, C stands by and listens.
//
//   armor    B wears helmet_6 and armor_6 (durability topped up before each burst, healed between),
//            A fires short bursts of full-auto K47 PS, aimed, at B's chest (some go into the helmet):
//            at least 30 rounds, and while A fires neither A nor C hears a single armour equip sound
//            (vanilla's leather one, or the new craftov:gear.equip), while B's armour wears down as
//            before. (Between bursts the harness tops the armour up by RCON, which does make one.)
//            A player's armour never made the sound when worn down (Paper puts a player's armour on
//            without onEquipItem); a soldier's and a stand-in's did. So then a patrol posted where B
//            stood (frozen, its health pool raised, in copies of B's helmet_6 and armor_6) takes at
//            least 20 rounds the same way, and A and C hear no equip sound from it either while its
//            armour wears down.
//   bleed    B healed and given a heavy bleed on the left leg (/cv injure): for 15 seconds its health
//            on the server (read by RCON) falls by 2.6 every 1.5 s (30 ticks), and neither C nor B
//            itself gets a damage event or a hurt animation of B, or hears a player hurt sound; B alone
//            hears craftov:status.bleed. B's own client is never shown its health drop (the vanilla
//            client tilts the view at any drop, as for a hit): every health update it gets says 100.
//            Then a hit takes B to 8 (/damage): its client is shown the true health again. B bleeds
//            on to 0: it dies as before (solo, no injector: "行动失败", spectator), and C hears it
//            cry out (entity.player.death).
//   meds     every medical item's item definition in the pack (assets/craftov/items/<id>.json) turns
//            on minecraft:using_item with a minecraft:use_duration dispatch; a medkit given to C uses
//            craftov:misc.silence as its consumable sound, not craftov:med.loop, and no vanilla use
//            pose (animation none; an items.yml from before cues said bundle). C, given a light
//            bleed, uses a bandage: A hears its cues in order, med.tear at the start and med.wrap at
//            about 0.75 and 1.55 s (0.3 and 0.62 of 2.5 s), and no med.loop.
//   scope    C wears helmet_6 and aims an AWR (8x, a use packet every 4 ticks), A shoots its
//            head: the helmet wears, and every head-slot update C got since, the last one included,
//            still carries the scope's camera overlay.
//
//   PORT=25565 RCON_PORT=25575 [RCON_PASSWORD=craftov-local] [SERVER_DIR=../../server/run] node feedback.js [scenario ...]
//
// Server config: test.enabled true (the proving ground is a test map; the bots are also made op),
// match.wait-seconds short (5) so the three solo queues land in one match. The bots leave the raid
// by disconnecting. Exit code: number of failed checks (2+ also when a scenario could not be set up).
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const fs = require('fs')
const path = require('path')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const PACK = path.join(__dirname, '../../src/main/resources/pack/craftov/resourcepack/assets/craftov')
const MEDS = ['bandage', 'hemostat', 'splint', 'medkit_s', 'medkit_l', 'surgery', 'painkiller', 'adrenaline', 'revive_kit']
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['armor', 'bleed', 'meds', 'scope']
const failures = []
const check = (name, ok, got) => { if (!ok) failures.push(name); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []

const plain = j => [...JSON.stringify(j || '').matchAll(/"text":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('')
const pdc = (item, key) => { const m = JSON.stringify(item && item.components || '').match(new RegExp(`"craftov:${key}":\\{"type":"\\w+","value":"?([^",}]+)`)); return m ? m[1] : null }
const overlayOf = item => JSON.stringify(item || '').includes('camera_overlay') || JSON.stringify(item || '').includes('misc/scope')

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.heard = []      // { at, name } every sound, positioned or on an entity
  bot.damage = []     // { at, entityId, kind } damage events and hurt animations
  bot.healths = []    // { at, hp } own health, unscaled (the server scales 100 to 20)
  bot.titles = []
  bot.shots = 0
  const hear = (name, extra) => {
    name = String(name)
    bot.heard.push({ at: Date.now(), name, ...extra })
    if (/^craftov:gun\.[a-z0-9_]+\.fp(_s)?$/.test(name)) bot.shots++
  }
  bot.on('soundEffectHeard', s => hear(s))
  bot.on('hardcodedSoundEffectHeard', id => hear('#' + id))
  bot._client.on('entity_sound_effect', p => {
    const name = p.sound && p.sound.data ? p.sound.data.soundName : bot.registry.sounds && p.sound && bot.registry.sounds[p.sound.soundId]
      ? bot.registry.sounds[p.sound.soundId].name : JSON.stringify(p.sound)
    hear(name, { entityId: p.entityId })
  })
  bot._client.on('damage_event', p => bot.damage.push({ at: Date.now(), entityId: p.entityId, kind: 'damage_event' }))
  bot._client.on('hurt_animation', p => bot.damage.push({ at: Date.now(), entityId: p.entityId, kind: 'hurt_animation' }))
  bot._client.on('update_health', p => bot.healths.push({ at: Date.now(), hp: p.health * 5 }))
  bot._client.on('set_title_text', p => bot.titles.push(plain(p.text)))
  bot.heads = []
  const head = (via, item) => ({ at: Date.now(), via, overlay: overlayOf(item), json: JSON.stringify(item || '') })
  bot._client.on('set_slot', p => { if (p.windowId === 0 && p.slot === 5) bot.heads.push(head('slot', p.item)) })
  bot._client.on('set_player_inventory', p => { if (p.slotId === 39) bot.heads.push(head('inventory', p.contents)) })
  bot._client.on('window_items', p => { if (p.windowId === 0 && p.items.length > 5) bot.heads.push(head('content', p.items[5])) })
  bot._client.on('entity_equipment', p => {
    if (!bot.entity || p.entityId !== bot.entity.id) return
    for (const e of p.equipments) if (e.slot === 5) bot.heads.push(head('equipment', e.item))
  })
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
async function dataNumber (target, path) {
  const out = await rcon(`data get entity ${target} ${path}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
const health = name => dataNumber(name, 'Health')
// `name` may be a selector (a soldier) as well.
const dura = (name, slot) => dataNumber(name, `equipment.${slot}.components."minecraft:custom_data".PublicBukkitValues."craftov:dura"`)
const setDura = (name, slot, value) => rcon(`item modify entity ${name} armor.${slot} {function:"minecraft:set_custom_data",tag:{PublicBukkitValues:{"craftov:dura":${value.toFixed(2)}d}}}`)
const commandSlot = i => i >= 36 ? `hotbar.${i - 36}` : `inventory.${i - 9}`
const hotbar = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && pdc(s, 'id') === id)
const anySlot = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 9 && i <= 44 && s && pdc(s, 'id') === id)

/** Gives `id` and puts it on (armor.head / armor.chest). */
async function wear (bot, id, slot) {
  await rcon(`cv give ${bot.username} ${id}`)
  let at = -1
  await waitFor(() => (at = anySlot(bot, id)) >= 0, 5000)
  if (at < 0) return false
  await rcon(`item replace entity ${bot.username} armor.${slot} from entity ${bot.username} ${commandSlot(at)}`)
  await rcon(`item replace entity ${bot.username} ${commandSlot(at)} with minecraft:air`)
  return waitFor(() => pdc(bot.inventory.slots[slot === 'head' ? 5 : 6], 'id') === id, 5000)
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
  return (await health(bot.username)) === 100
}

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}

async function place (bot, x, z, yaw) {
  await rcon(`execute as ${bot.username} at @s run tp @s ${x} 65 ${z} ${yaw} 0`)
}

/** A and B five blocks apart, facing each other; C three blocks to the side. */
async function positions (A, B, C) {
  await place(A, 45.5, 88.5, 0)
  await place(B, 45.5, 93.5, 180)
  await place(C, 49.5, 91.0, 90)
  await sleep(1000)
}

/**
 * Aims down the sights (a use packet every 4 ticks, as right mouse held) and holds left mouse for
 * `ms` (a swing every tick: the trigger held), at `y` above the feet of `target` (a bot, or an entity
 * as A sees it). Aimed, the rounds stay on the armour: from the hip, full auto sprays the arms and
 * legs, which no armour covers.
 */
async function burst (A, target, ms, y) {
  const entity = target.username ? A.players[target.username] && A.players[target.username].entity : target
  if (!entity) return
  let seq = 1
  const use = () => A._client.write('use_item', { hand: 0, sequence: seq++, rotation: { x: conv.toNotchianYaw(A.entity.yaw), y: conv.toNotchianPitch(A.entity.pitch) } })
  const aimed = Date.now() + 900
  while (Date.now() < aimed) {
    await A.lookAt(entity.position.offset(0, y, 0), true)
    use()
    await sleep(200)
  }
  const end = Date.now() + ms
  firing.push({ from: Date.now(), to: end + 600 })
  let tick = 0
  while (Date.now() < end) {
    await A.lookAt(entity.position.offset(0, y, 0), true)
    if (tick++ % 4 === 0) use()
    A.swingArm()
    await sleep(50)
  }
  A._client.write('block_dig', { status: 1, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
}

const armourSound = h => /equip_leather|armor\.equip|gear\.equip/.test(h.name)
/** When A had the trigger down (and a moment after): the armour B wears is only touched by the test
 * harness (RCON topping its durability up, which vanilla answers with an equip sound) outside them. */
const firing = []
const whileFiring = h => firing.some(w => h.at >= w.from && h.at <= w.to)

async function armor (A, B, C) {
  await restore(B)
  if (!await wear(B, 'helmet_6', 'head') || !await wear(B, 'armor_6', 'chest')) {
    log('SETUP: could not dress B')
    return 2
  }
  await positions(A, B, C)
  const gun = hotbar(A, 'k47')
  if (gun < 0) {
    log('SETUP: A has no K47 in its hotbar')
    return 2
  }
  A.setQuickBarSlot(gun - 36)
  await sleep(1500)
  const since = Date.now()
  const shots0 = A.shots
  const before = { head: 60, chest: 120 }
  const worn = { head: 0, chest: 0 }
  for (let k = 0; k < 16 && A.shots - shots0 < 30; k++) {
    await clearSoldiers()
    await restore(B)
    await setDura(B.username, 'head', before.head)
    await setDura(B.username, 'chest', before.chest)
    await positions(A, B, C)
    await burst(A, B, 300, 1.0) // three or four rounds at the stomach: a burst that climbs into the head cannot kill
    await sleep(600)
    const head = await dura(B.username, 'head')
    const chest = await dura(B.username, 'chest')
    if (head !== null) worn.head += before.head - head
    if (chest !== null) worn.chest += before.chest - chest
    log(`burst ${k + 1}: ${A.shots - shots0} rounds so far, helmet ${head}, vest ${chest}, B at ${await health(B.username)}`)
    if (A.shots - shots0 < 30 && (A.shots - shots0) % 30 > 22) {
      // Reload before the magazine runs dry (Q drops nothing with a gun in hand: Craftov reloads).
      A._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
      await sleep(3500)
    }
  }
  const fired = A.shots - shots0
  const heardA = A.heard.filter(h => whileFiring(h) && armourSound(h))
  const heardC = C.heard.filter(h => whileFiring(h) && armourSound(h))
  check('armor: A fired at least 30 rounds of full-auto K47 at B', fired >= 30, fired)
  check('armor: the hits wore B\'s helmet and vest', worn.head > 0 && worn.chest > 0, worn)
  check('armor: A heard no armour equip sound while it fired', heardA.length === 0, heardA.map(h => h.name))
  check('armor: C heard no armour equip sound while A fired', heardC.length === 0, heardC.map(h => h.name))
  const setup = C.heard.filter(h => h.at >= since && !whileFiring(h) && armourSound(h)).length
  log(`(C heard ${setup} equip sounds from the harness topping the armour up between bursts)`)
  const impacts = C.heard.filter(h => h.at >= since && /impact\.(armor|helmet)/.test(h.name)).length
  check('armor: C heard the impacts on the armour', impacts > 0, impacts)
  return soldierArmor(A, B, C)
}

const SOLDIER = '@e[tag=fb_soldier,limit=1]'
const SPOT = new Vec3(45.5, 65, 93.5)
/** The posted soldier as A sees it. */
const soldierFor = A => Object.values(A.entities).find(e => ['zombie', 'husk', 'drowned'].includes(e.name) && e.position.distanceTo(SPOT) < 2.5)

/**
 * A patrol where B stood, in copies of B's helmet_6 and armor_6. Every hit puts its armour back
 * worn down with the same write-back as a player's (DamageService), and on a soldier (or a
 * stand-in) a write-back that is not silent plays the equip sound to everyone around.
 */
async function soldierArmor (A, B, C) {
  await clearSoldiers()
  await restore(B)
  await positions(A, B, C)
  await place(B, 41.5, 91.0, 270) // out of the line of fire
  // A frozen soldier still fights with what it holds: nobody is to be hurt while it stands there
  // (resistance stops the damage, not the wounds a hit makes: everyone is healed afterwards).
  for (const bot of [A, B, C]) await rcon(`effect give ${bot.username} minecraft:resistance 120 4 true`)
  const reply = await rcon(`cv ai spawn patrol ${SPOT.x} ${SPOT.y} ${SPOT.z} ${A.worldName}`)
  await sleep(500)
  await rcon(`tag @e[limit=1,nbt={BukkitValues:{"craftov:ai":"patrol"}}] add fb_soldier`)
  if (!(await rcon(`execute if entity ${SOLDIER}`)).includes('passed')) {
    log('SETUP: no patrol posted', reply)
    return 2
  }
  await rcon(`data merge entity ${SOLDIER} {NoAI:1b}`)
  await rcon(`attribute ${SOLDIER} minecraft:max_health base set 1000`)
  await rcon(`item replace entity ${SOLDIER} armor.head from entity ${B.username} armor.head`)
  await rcon(`item replace entity ${SOLDIER} armor.chest from entity ${B.username} armor.chest`)
  const before = { head: 60, chest: 120 }
  const worn = { head: 0, chest: 0 }
  const since = Date.now()
  const from = firing.length
  const shots0 = A.shots
  for (let k = 0; k < 16 && A.shots - shots0 < 20; k++) {
    await rcon(`data merge entity ${SOLDIER} {Health:1000f}`)
    await rcon(`execute as ${SOLDIER} at @s run tp @s ${SPOT.x} ${SPOT.y} ${SPOT.z} 180 0`)
    await setDura(SOLDIER, 'head', before.head)
    await setDura(SOLDIER, 'chest', before.chest)
    await place(A, 45.5, 88.5, 0)
    await sleep(800)
    const target = soldierFor(A)
    if (!target) {
      log('SETUP: A cannot see the soldier')
      return 2
    }
    await burst(A, target, 300, 1.0)
    await sleep(600)
    const head = await dura(SOLDIER, 'head')
    const chest = await dura(SOLDIER, 'chest')
    if (head !== null) worn.head += before.head - head
    if (chest !== null) worn.chest += before.chest - chest
    log(`soldier burst ${k + 1}: ${A.shots - shots0} rounds so far, helmet ${head}, vest ${chest}, health ${await dataNumber(SOLDIER, 'Health')}`)
    if (A.shots - shots0 < 20 && (A.shots - shots0) % 30 > 22) {
      A._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
      await sleep(3500)
    }
  }
  const windows = firing.slice(from)
  const during = h => windows.some(w => h.at >= w.from && h.at <= w.to)
  const fired = A.shots - shots0
  const heardA = A.heard.filter(h => during(h) && armourSound(h))
  const heardC = C.heard.filter(h => during(h) && armourSound(h))
  check('armor: A fired at least 20 rounds at the soldier', fired >= 20, fired)
  check('armor: the hits wore the soldier\'s armour', worn.head + worn.chest > 0, worn)
  check('armor: A heard no armour equip sound from the soldier while it fired', heardA.length === 0, heardA.map(h => h.name))
  check('armor: C heard no armour equip sound from the soldier while A fired', heardC.length === 0, heardC.map(h => h.name))
  const setup = C.heard.filter(h => h.at >= since && !during(h) && armourSound(h)).length
  log(`(C heard ${setup} equip sounds from the harness dressing the soldier and topping its armour up)`)
  await rcon(`kill ${SOLDIER}`)
  for (const bot of [A, B, C]) {
    await rcon(`effect clear ${bot.username} minecraft:resistance`)
    await restore(bot)
  }
  return 0
}

const hurtSound = h => /entity\.player\.hurt|entity\.generic\.hurt|player_hurt/.test(h.name)

async function bleed (A, B, C) {
  if (!inRaid(B)) {
    log('SETUP: B is no longer in the raid (killed earlier?)', B.game.gameMode)
    return 2
  }
  await clearSoldiers()
  if (!await restore(B)) {
    log('SETUP: could not heal B')
    return 2
  }
  await positions(A, B, C)
  await sleep(1000)
  const id = B.entity.id
  const idForC = C.players[B.username] && C.players[B.username].entity ? C.players[B.username].entity.id : -1
  const since = Date.now()
  const shownBefore = B.healths.length ? +B.healths[B.healths.length - 1].hp.toFixed(2) : null
  await rcon(`cv injure ${B.username} left_leg heavy`)
  // Its health on the server: the client is no longer told.
  const server = []
  while (Date.now() < since + 15000) {
    const hp = await health(B.username)
    if (hp !== null) server.push({ at: Date.now(), hp })
    await sleep(150)
  }
  const drops = []
  for (let i = 1; i < server.length; i++) {
    if (server[i].hp < server[i - 1].hp - 0.01) drops.push({ at: server[i].at, by: +(server[i - 1].hp - server[i].hp).toFixed(2) })
  }
  const gaps = drops.slice(1).map((d, i) => d.at - drops[i].at)
  check('bleed: over 15 s B loses 2.6 at a time on the server, 9 or 10 times', drops.length >= 9 && drops.length <= 11 && drops.every(d => Math.abs(d.by - 2.6) < 0.05),
    drops.map(d => d.by))
  check('bleed: about every 1.5 s', gaps.length > 0 && gaps.every(g => g > 1100 && g < 1900), gaps)
  const shown = B.healths.filter(h => h.at >= since).map(h => +h.hp.toFixed(2))
  check('bleed: B\'s own client is never shown a drop of its health (every update still says 100)',
    shownBefore === 100 && shown.length >= drops.length - 1 && shown.every(hp => hp === 100), { before: shownBefore, shown })
  const cEvents = C.damage.filter(d => d.at >= since && d.entityId === idForC)
  const bEvents = B.damage.filter(d => d.at >= since && d.entityId === id)
  check('bleed: C got no damage event or hurt animation of B', idForC >= 0 && cEvents.length === 0, { idForC, events: cEvents.map(e => e.kind) })
  check('bleed: B got no damage event or hurt animation of itself', bEvents.length === 0, bEvents.map(e => e.kind))
  const hurtC = C.heard.filter(h => h.at >= since && hurtSound(h))
  const hurtB = B.heard.filter(h => h.at >= since && hurtSound(h))
  check('bleed: nobody heard a hurt sound', hurtC.length === 0 && hurtB.length === 0, { C: hurtC.length, B: hurtB.length })
  const drip = B.heard.filter(h => h.at >= since && /status\.bleed/.test(h.name)).length
  const dripC = C.heard.filter(h => h.at >= since && /status\.bleed/.test(h.name)).length
  check('bleed: B heard the drip with every drop, C never', drip >= drops.length - 1 && dripC === 0, { B: drip, C: dripC })

  // On to the end: B at 8 health bleeds out.
  const now = await health(B.username)
  if (now === null || now <= 8) {
    log('SETUP: B is at', now)
    return 2
  }
  const hitAt = Date.now()
  await rcon(`damage ${B.username} ${(now - 8).toFixed(2)} minecraft:generic`)
  const end = Date.now()
  B.titles.length = 0
  // An update of a quiet loss may still be on its way: the hit's own comes within a tick or two.
  await sleep(1000)
  const afterHit = B.healths.filter(h => h.at >= hitAt).map(h => +h.hp.toFixed(2))
  check('bleed: a hit shows B\'s client its true health again (8, or less if it bled meanwhile)',
    afterHit.some(hp => hp > 0 && hp <= 8.05), afterHit)
  const died = await waitFor(() => B.game.gameMode === 'spectator', 11000)
  check('bleed: bled to 0, B dies as before (solo, no injector)', died && B.titles.some(t => t.includes('行动失败')), { mode: B.game.gameMode, titles: B.titles })
  const cry = C.heard.filter(h => h.at >= end && /entity\.player\.death/.test(h.name)).length
  check('bleed: C hears B cry out as it dies', cry >= 1, cry)
  return 0
}

async function meds (A, B, C) {
  const missing = []
  for (const id of MEDS) {
    const file = path.join(PACK, 'items', id + '.json')
    const text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
    if (!text.includes('minecraft:using_item') || !text.includes('minecraft:use_duration')) missing.push(id)
  }
  check('meds: every medical item model turns on using_item with a use_duration dispatch', missing.length === 0, missing)
  await rcon(`cv give ${C.username} medkit_s`)
  let at = -1
  await waitFor(() => (at = anySlot(C, 'medkit_s')) >= 0, 5000)
  const json = at >= 0 ? JSON.stringify(C.inventory.slots[at].components || '') : ''
  check('meds: a medkit\'s consumable sound is craftov:misc.silence, not med.loop', at >= 0 && json.includes('misc.silence') && !json.includes('med.loop'),
    (json.match(/"sound[^}]*}/) || [''])[0])
  const pose = (json.match(/"animation":"?(\w+)/) || [])[1]
  check('meds: a medkit has no vanilla use pose (animation none)', pose === 'none', pose || json.slice(0, 300))
  // A bandage on a light bleed: the cues, in order, heard by A.
  await clearSoldiers()
  await restore(C)
  await positions(A, B, C)
  await rcon(`cv give ${C.username} bandage`)
  let slot = -1
  await waitFor(() => (slot = hotbar(C, 'bandage')) >= 0, 5000)
  if (slot < 0) {
    log('SETUP: no bandage on C\'s hotbar')
    return 2
  }
  C.setQuickBarSlot(slot - 36)
  await rcon(`cv injure ${C.username} left_leg light`)
  await sleep(800)
  const since = Date.now()
  C.activateItem()
  await sleep(3200)
  C.deactivateItem()
  const cues = A.heard.filter(h => h.at >= since && /craftov:med\./.test(h.name)).map(h => ({ name: h.name.replace('craftov:', ''), ms: h.at - since }))
  const names = cues.map(c => c.name)
  const want = ['med.tear', 'med.wrap', 'med.wrap', 'med.finish']
  check('meds: A hears the bandage\'s cues in order (tear, wrap, wrap, then done)', JSON.stringify(names) === JSON.stringify(want), cues)
  check('meds: the wraps come at about 0.75 and 1.55 s', cues.length >= 3 && Math.abs(cues[1].ms - 750) < 350 && Math.abs(cues[2].ms - 1550) < 350,
    cues.map(c => c.ms))
  const loops = A.heard.filter(h => h.at >= since && /med\.loop/.test(h.name)).length
  check('meds: no med.loop', loops === 0, loops)
  return 0
}

async function scope (A, B, C) {
  await clearSoldiers()
  await restore(C)
  if (!await wear(C, 'helmet_6', 'head')) {
    log('SETUP: could not put a helmet on C')
    return 2
  }
  // C needs the AWR in a weapon slot: hotbar keys 1-2 take guns only.
  await rcon(`item replace entity ${C.username} hotbar.0 with minecraft:air`)
  await rcon(`cv give ${C.username} awr`)
  let gun = -1
  await waitFor(() => (gun = hotbar(C, 'awr')) >= 0, 5000)
  if (gun < 0) {
    log('SETUP: C has no AWR')
    return 2
  }
  // C stands where B stood, facing A; A aims at its head.
  await place(A, 45.5, 88.5, 0)
  await place(C, 45.5, 93.5, 180)
  await place(B, 41.5, 91.0, 270)
  await sleep(1000)
  C.setQuickBarSlot(gun - 36)
  await sleep(1500)
  let seq = 1
  let stop = false
  const pump = (async () => {
    while (!stop) {
      C._client.write('use_item', { hand: 0, sequence: seq++, rotation: { x: conv.toNotchianYaw(C.entity.yaw), y: conv.toNotchianPitch(C.entity.pitch) } })
      await sleep(200)
    }
  })()
  try {
    const up = await waitFor(() => C.heads.length > 0 && C.heads[C.heads.length - 1].overlay, 4000)
    check('scope: aiming the AWR puts the scope overlay on C\'s head slot', up)
    const since = Date.now()
    const shots0 = A.shots
    // Single rounds at the head: one tap at a time until C's head slot has been rewritten by a hit.
    const helmetHits = () => C.heads.filter(h => h.at >= since).length
    const entity = A.players[C.username] && A.players[C.username].entity
    for (let i = 0; i < 6 && entity; i++) {
      await A.lookAt(entity.position.offset(0, 1.6, 0), true)
      A.swingArm()
      await sleep(80)
      A._client.write('block_dig', { status: 1, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
      await sleep(700)
      if (helmetHits() > 0 && A.shots > shots0) break
    }
    await sleep(1500)
    const after = C.heads.filter(h => h.at >= since)
    check('scope: A\'s rounds reached C\'s head (its head slot was rewritten)', A.shots > shots0 && after.length > 0,
      { rounds: A.shots - shots0, updates: after.length })
    check('scope: every head-slot update since still carries the overlay', after.length > 0 && after.every(h => h.overlay),
      after.map(h => h.via + (h.overlay ? ':scope' : ':plain')))
    const last = C.heads[C.heads.length - 1]
    check('scope: the last head-slot update C got carries the overlay', !!last && last.overlay, last && last.via)
  } finally {
    stop = true
    await pump
  }
  return 0
}

;(async () => {
  const A = await make(`fb${run}a`)
  const B = await make(`fb${run}b`)
  const C = await make(`fb${run}c`)
  for (const bot of bots) await rcon(`op ${bot.username}`)
  for (const bot of bots) bot.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', bots.map(b => b.worldName))
    return finish(2)
  }
  if (new Set(bots.map(b => b.worldName)).size !== 1) {
    log('SETUP: the bots are in different raids', bots.map(b => b.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and spawn protection
  const order = ['meds', 'scope', 'armor', 'bleed'].filter(s => scenarios.includes(s)) // bleed kills B: last
  const RUN = { armor, bleed, meds, scope }
  for (const name of order) {
    log('---', name)
    const code = await RUN[name](A, B, C)
    if (code) return finish(code)
  }
  finish(failures.length)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  log(failures.length ? `FAILED: ${failures.join('; ')}` : 'all checks passed')
  for (const bot of bots) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
