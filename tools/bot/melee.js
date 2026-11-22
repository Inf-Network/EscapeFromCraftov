// Knives through armour and shields (issue #27). Two solo bots enter the proving ground on 机密
// (it has a boss); the attacker cuts soldiers and then the other bot, and RCON reads health and
// armour durability before and after every hit.
//
//   1. lobby: the trader's weapons page has the tactical dagger but not the machete or the karambit,
//      and a karambit's lore gives its damage, penetration, swing interval and reach;
//   2. soldiers have no vanilla armour (attribute minecraft:armor is 0 on a patrol, a shield soldier
//      and the boss);
//   3. an unarmoured patrol (helmet and vest taken off) goes down in exactly 2 machete hits and
//      3 karambit hits, the first hit leaving exactly 100 - damage (nothing taken off by vanilla);
//   4. every knife cut into the chest of a patrol, of a shield soldier from behind and of the boss
//      (each in the armour it spawned with, refreshed before each knife): every hit does what the
//      armour model says for the knife's damage, pen and armor-damage, and the number of hits to take
//      off the soldier's whole health pool matches it (the boss at least 10); the soldier's health
//      pool is raised meanwhile so it is not killed and can be cut by the next knife;
//   5. a shield soldier facing the attacker: a cut to the chest does nothing (health and vest
//      unchanged), a cut to the leg gets under the shield (0.7 of the damage);
//   6. the other bot in a fresh armor_4: one chest cut from each knife does damage x the armour
//      multiplier for its pen (5.1, 9, 13, 14.4);
//   7. the other bot in armor_6 and helmet_6, karambit: chest cuts wear the vest 8.64 a hit and do
//      5.4, a head cut wears the helmet and not the vest, a leg cut does 25.2 and wears neither;
//   8. it is then cut down (chest and legs) and its death report lists more than the chest.
//
// Soldiers are frozen (NoAI) and teleported in front of the attacker; both bots have resistance
// while soldiers are around (their rounds would otherwise kill them), the soldiers are killed before
// the bots cut each other. The knife values come from the server's plugins/Craftov/items.yml
// (SERVER_DIR, default the repository's server/run), or the source one when it has none; a server
// items.yml from before knives went through armour is reported as a setup failure.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node melee.js
//
// The bots are made op (test maps, /cv items) and get a karambit and money in the lobby to meet the
// 机密 gear requirement; they are deopped when done. A match launches after match.wait-seconds
// (30 by default) and the boss appears 60 s into the raid. Exit code 0 when every check passes,
// 1 when one fails, 2 when the scenario could not be set up.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []

// ------------------------------------------------------------------ the armour model (DamageService)

const DAMAGE_TAKEN = [0.85, 0.6, 0.4, 0.25, 0.15]
const WEAR = 0.3
const ZONE = { chest: 1.0, stomach: 0.95, leg: 0.7, head: 1.0 }
const KNIVES = ['knife_starter', 'knife_tactical', 'knife_machete', 'knife_karambit']

function loadItems () {
  const served = path.join(SERVER_DIR, 'plugins/Craftov/items.yml')
  const file = fs.existsSync(served) ? served : path.join(__dirname, '../../src/main/resources/items.yml')
  const text = fs.readFileSync(file, 'utf8')
  const knives = {}
  for (const id of KNIVES) {
    const block = text.match(new RegExp(`\\n  ${id}:\\n((?:    .*\\n)+)`))
    const melee = block && block[1].match(/melee: \{([^}]*)\}/)
    if (!melee) return { file, error: `${id} has no melee section` }
    const num = key => { const m = melee[1].match(new RegExp(`(?:^|[ ,])${key}: ([\\d.]+)`)); return m ? Number(m[1]) : null }
    if (num('damage') === null) return { file, error: `${id} has no melee damage (an items.yml from before issue #27)` }
    knives[id] = { damage: num('damage'), pen: num('pen') || 1, ad: num('armor-damage') ?? 1, head: num('head') ?? 1, cd: num('cooldown') || 12 }
  }
  const armour = {}
  for (const m of text.matchAll(/\n  ((?:armor|helmet)_\d): \{[^}]*class: (\d+), durability: (\d+)/g)) armour[m[1]] = { cls: Number(m[2]), max: Number(m[3]) }
  return { file, knives, armour }
}

/** Share of a hit that gets through armour of class `cls` (max durability `max`) at `dura`. */
function armourMult (pen, cls, max, dura, loss) {
  if (!cls || dura <= 0) return 1
  const taken = DAMAGE_TAKEN[Math.min(Math.max(0, 1 - (pen - cls)), DAMAGE_TAKEN.length - 1)]
  const strength = Math.min(1, dura / max / 0.5) * Math.min(1, dura / loss)
  return 1 - (1 - taken) * strength
}

/** One knife hit on a part: damage and the armour durability left (null armour = none). */
function knifeHit (k, part, armour, dura) {
  const loss = k.damage * k.ad * WEAR
  const zone = part === 'head' ? k.head : ZONE[part]
  if (!armour || dura === null || dura <= 0) return { damage: k.damage * zone, dura }
  return { damage: k.damage * zone * armourMult(k.pen, armour.cls, armour.max, dura, loss), dura: Math.max(0, dura - loss) }
}

/** Chest hits to take `hp` off through `armour` starting at `dura`. */
function hitsToDown (k, hp, armour, dura) {
  let hits = 0
  while (hp > 1e-9 && hits < 200) {
    const hit = knifeHit(k, 'chest', armour, dura)
    hp -= hit.damage
    dura = hit.dura
    hits++
  }
  return hits
}

// ------------------------------------------------------------------ bots and RCON helpers

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.chatLines = []
  bot.on('messagestr', m => bot.chatLines.push(m))
  bot.sounds = []
  bot.on('soundEffectHeard', s => bot.sounds.push(String(s)))
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
const plain = j => [...JSON.stringify(j || '').matchAll(/"text":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('')
const lore = item => plain(((item && item.components) || []).find(c => c.type === 'lore'))

/** A number out of `data get entity` ("... has the following entity data: 87.5f"), or null. */
async function dataNumber (target, nbtPath) {
  const out = await rcon(`data get entity ${target} ${nbtPath}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
async function dataString (target, nbtPath) {
  const out = await rcon(`data get entity ${target} ${nbtPath}`)
  const m = out.match(/entity data: "([^"]*)"\s*$/)
  return m ? m[1] : null
}
const health = sel => dataNumber(sel, 'Health')
const SLOT_NBT = { chest: 'chest', head: 'head' }
const duraOf = (sel, slot) => dataNumber(sel, `equipment.${SLOT_NBT[slot]}.components."minecraft:custom_data".PublicBukkitValues."craftov:dura"`)
const idOf = (sel, slot) => dataString(sel, `equipment.${SLOT_NBT[slot]}.components."minecraft:custom_data".PublicBukkitValues."craftov:id"`)
const setDura = (sel, slot, dura) => rcon(`item modify entity ${sel} armor.${slot} {function:"minecraft:set_custom_data",tag:{PublicBukkitValues:{"craftov:dura":${dura.toFixed(2)}d}}}`)

/** The armour a soldier or player wears on a slot: { id, cls, max, dura } or null. */
async function armourOn (sel, slot, items) {
  const id = await idOf(sel, slot)
  if (!id || !items.armour[id]) return null
  const dura = await duraOf(sel, slot)
  return { id, ...items.armour[id], dura: dura === null ? items.armour[id].max : dura }
}

/** Command slot name for a mineflayer inventory window slot (hotbar 36-44, main 9-35). */
const commandSlot = i => i >= 36 ? `hotbar.${i - 36}` : `inventory.${i - 9}`

/** Gives `bot` a fresh piece of armour and moves it onto `slot` (chest or head). */
async function wear (bot, id, slot) {
  await rcon(`item replace entity ${bot.username} armor.${slot} with minecraft:air`)
  await rcon(`cv give ${bot.username} ${id}`)
  let i = -1
  await waitFor(() => (i = bot.inventory.slots.findIndex((s, n) => n >= 9 && n <= 44 && s && pdc(s, 'id') === id)) >= 0, 5000)
  if (i < 0) return false
  await rcon(`item replace entity ${bot.username} armor.${slot} from entity ${bot.username} ${commandSlot(i)}`)
  await rcon(`item replace entity ${bot.username} ${commandSlot(i)} with minecraft:air`)
  return waitFor(() => pdc(bot.inventory.slots[slot === 'chest' ? 6 : 5], 'id') === id, 5000)
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
  return (await health(bot.username)) === 100
}

// The attacker stands here facing south; whatever it cuts stands 2.5 blocks in front of it.
const ATTACKER = [45.5, 65, 88.5]
const FRONT = [45.5, 65, 91.0]
const FACING_ATTACKER = 180
const FACING_AWAY = 0

async function placeAttacker (attacker) {
  await rcon(`execute as ${attacker.username} at @s run tp @s ${ATTACKER.join(' ')} 0 0`)
}

// Where each soldier waits its turn, well away from the spot in front of the attacker.
const SOLDIERS = ['m_boss', 'm_shield', 'm_pat1', 'm_pat2', 'm_pat3']
const parking = tag => `${36.5 + SOLDIERS.indexOf(tag) * 2} 65 99.5`

/** Sends every soldier still alive back to its parking spot. */
async function parkAll (attacker) {
  for (const tag of SOLDIERS) await rcon(`execute as ${one(tag)} at ${attacker.username} run tp @s ${parking(tag)}`)
}

/**
 * Teleports the entity `sel` (a selector for one entity) in front of the attacker, turned `yaw`,
 * after parking the other soldiers so that it is the only thing there.
 */
async function placeTarget (attacker, sel, yaw) {
  await parkAll(attacker)
  await placeAttacker(attacker)
  await rcon(`execute as ${sel} at ${attacker.username} run tp @s ${FRONT.join(' ')} ${yaw} 0`)
  await sleep(700)
}

/** The mineflayer entity standing in front of the attacker (a soldier or the other bot). */
function frontEntity (attacker, filter = () => true) {
  let best = null
  let bestD = 2.0
  for (const e of Object.values(attacker.entities)) {
    if (e === attacker.entity || !filter(e)) continue
    const d = Math.hypot(e.position.x - FRONT[0], e.position.z - FRONT[2])
    if (d < bestD && Math.abs(e.position.y - FRONT[1]) < 1.5) { best = e; bestD = d }
  }
  return best
}

/**
 * One cut at `part` of the entity in front: looks at the part (a share of `height`), waits for the
 * look to reach the server, attacks, and returns health and armour durability before and after.
 */
async function cut (attacker, sel, part, height, filter) {
  const entity = frontEntity(attacker, filter)
  if (!entity) return null
  const share = { head: 0.92, chest: 0.64, leg: 0.2 }[part]
  await attacker.lookAt(entity.position.offset(0, height * share, 0), true)
  await sleep(250)
  const before = await health(sel)
  const chestBefore = await duraOf(sel, 'chest')
  const headBefore = await duraOf(sel, 'head')
  attacker.attack(entity)
  let after = before
  for (const end = Date.now() + 600; Date.now() < end && after === before; await sleep(30)) after = await health(sel)
  return {
    before, after, damage: before === null || after === null ? null : before - after,
    chest: [chestBefore, await duraOf(sel, 'chest')], head: [headBefore, await duraOf(sel, 'head')]
  }
}

const close = (a, b, eps = 0.06) => a !== null && Math.abs(a - b) < eps
const rd = x => x === null ? null : +x.toFixed(2)

/** Selector for the one entity tagged `tag`. */
const one = tag => `@e[tag=${tag},limit=1]`
const isMob = e => ['zombie', 'husk', 'drowned'].includes(e.name)
const isPlayer = name => e => e.type === 'player' && e.username === name

/**
 * Cuts the soldier `tag` in the chest with `knife` until its original health pool `hp` would be
 * gone, refreshing its vest first and keeping it alive with a huge health pool meanwhile. Checks
 * every hit against the model and the number of hits against hitsToDown.
 */
async function cutsToDown (attacker, items, tag, label, hp, height, knife, yaw) {
  const sel = one(tag)
  const k = items.knives[knife]
  const vest = await armourOn(sel, 'chest', items)
  if (vest) await setDura(sel, 'chest', vest.max)
  const vestNow = vest && { ...vest, dura: vest.max }
  await rcon(`attribute ${sel} minecraft:max_health base set 1000`)
  await rcon(`data merge entity ${sel} {Health:1000f}`)
  await placeTarget(attacker, sel, yaw)
  if (!await holdKnife(attacker, knife)) return log(`  could not hold ${knife}`)
  const want = hitsToDown(k, hp, vestNow, vestNow && vestNow.dura)
  let dura = vestNow ? vestNow.dura : null
  let taken = 0
  let hits = 0
  const got = []
  let allMatch = true
  for (let tries = 0; taken < hp - 1e-6 && tries < want + 12; tries++) {
    if (tries % 6 === 5) await placeTarget(attacker, sel, yaw)
    const c = await cut(attacker, sel, 'chest', height, isMob)
    await sleep(k.cd * 50 + 80)
    if (!c || c.damage === null || c.damage <= 0) continue
    const model = knifeHit(k, 'chest', vestNow, dura)
    const stomach = knifeHit(k, 'stomach', vestNow, dura)
    const ok = close(c.damage, model.damage) || close(c.damage, stomach.damage)
    if (!ok) allMatch = false
    got.push(rd(c.damage))
    if (!ok) log(`  ${label} ${knife}: hit ${hits + 1} did ${rd(c.damage)}, model ${rd(model.damage)} (vest ${rd(c.chest[0])} -> ${rd(c.chest[1])}, model dura ${rd(dura)})`)
    dura = c.chest[1] !== null ? c.chest[1] : model.dura
    taken += c.damage
    hits++
  }
  await rcon(`attribute ${sel} minecraft:max_health base set ${hp}`)
  await rcon(`data merge entity ${sel} {Health:${hp}f}`)
  log(`  ${label} ${knife} (${vest ? vest.id : 'no vest'}): ${hits} hits, model ${want}`, got)
  return { hits, want, allMatch, got, vest: vest && vest.id }
}

/** Tags one AI soldier of `kind` (craftov:ai) and entity `type` with `tag`; true when one was found. */
async function tagSoldier (type, kind, tag, extra = '') {
  await rcon(`tag @e[type=minecraft:${type},tag=!m_keep,limit=1,nbt={BukkitValues:{"craftov:ai":"${kind}"}${extra}}] add ${tag}`)
  const found = (await rcon(`execute if entity ${one(tag)}`)).includes('passed')
  if (found) {
    await rcon(`tag ${one(tag)} add m_keep`)
    await rcon(`data merge entity ${one(tag)} {NoAI:1b}`)
  }
  return found
}

/** Kills the soldiers (and drones) in the attacker's raid world, `extra` narrowing the selector. */
async function killSoldiers (attacker, extra) {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) {
    await rcon(`execute at ${attacker.username} run kill @e[type=minecraft:${type},distance=..512${extra}]`)
  }
}

async function armourAttr (sel) {
  const out = await rcon(`attribute ${sel} minecraft:armor get`)
  const m = out.match(/(-?[\d.]+)\s*$/)
  return m ? Number(m[1]) : null
}

;(async () => {
  const items = loadItems()
  log(`knife values from ${items.file}`)
  if (items.error) {
    log(`SETUP: ${items.error}; copy the plugin's items.yml to the server`)
    return finish(2)
  }
  log(items.knives)
  const attacker = await make(`ml${run}a`)
  const target = await make(`ml${run}b`)
  for (const bot of bots) {
    await rcon(`op ${bot.username}`)
    await rcon(`cv money ${bot.username} 1000000`)
  }
  await sleep(500)

  // 1. Lobby: the trader page and the knife lore.
  const traderWindow = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 5000)
    attacker.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    attacker.chat('/cv trader')
  })
  if (!traderWindow) {
    log('SETUP: the trader did not open')
    return finish(2)
  }
  const sold = traderWindow.slots.slice(0, 45).map(s => pdc(s, 'id')).filter(Boolean)
  attacker.closeWindow(traderWindow)
  check('trader weapons page: the tactical dagger is sold, the machete and the karambit are not',
    sold.includes('knife_tactical') && !sold.includes('knife_machete') && !sold.includes('knife_karambit'), sold.filter(id => id.startsWith('knife')))
  for (const bot of bots) {
    await rcon(`cv give ${bot.username} knife_karambit`) // also meets the 机密 gear requirement
  }
  await waitFor(() => bots.every(b => pdc(b.inventory.slots[38], 'id') === 'knife_karambit'), 5000)
  const karambitLore = lore(attacker.inventory.slots[38])
  const kk = items.knives.knife_karambit
  check('karambit lore gives damage, penetration, swing interval and reach',
    // The text pieces come out of the lore components in no particular order: look for each one.
    ['伤害', `${kk.damage}`, '穿透等级', `${kk.pen}`, '出手间隔', `${(kk.cd / 20).toFixed(1)}s`, '距离', ' 格'].every(t => karambitLore.includes(t)),
    karambitLore)

  for (const bot of bots) bot.chat('/cv queue proving CONFIDENTIAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', bots.map(b => b.worldName), attacker.chatLines.slice(-5))
    return finish(2)
  }
  await sleep(7000)
  for (const bot of bots) await rcon(`effect give ${bot.username} minecraft:resistance infinite 4 true`)
  await rcon(`execute as ${target.username} at @s run tp @s 40.5 65 95.5`)
  log('in the raid; waiting for the boss (60 s in)')
  const bossNbt = ',attributes:[{id:"minecraft:max_health",base:380.0d}]'
  if (!await waitFor(async () => (await rcon(`execute if entity @e[type=minecraft:husk,nbt={BukkitValues:{"craftov:ai":"guard"}${bossNbt}}]`)).includes('passed'), 120000)) {
    log('SETUP: no boss appeared')
    return finish(2)
  }
  await rcon('cv event shields')
  await sleep(1500)
  const tagged = [
    await tagSoldier('husk', 'guard', 'm_boss', bossNbt),
    await tagSoldier('husk', 'shield', 'm_shield'),
    await tagSoldier('zombie', 'patrol', 'm_pat1'),
    await tagSoldier('zombie', 'patrol', 'm_pat2'),
    await tagSoldier('zombie', 'patrol', 'm_pat3')
  ]
  if (!tagged.every(Boolean)) {
    log('SETUP: missing soldiers (boss, shield, patrol x3)', tagged)
    return finish(2)
  }
  await killSoldiers(attacker, ',tag=!m_keep')
  await parkAll(attacker)

  // 2. No vanilla armour on soldiers.
  const attrs = {}
  for (const tag of ['m_pat1', 'm_shield', 'm_boss']) attrs[tag] = await armourAttr(one(tag))
  check('soldiers have no vanilla armour (patrol, shield soldier, boss)', Object.values(attrs).every(v => v === 0), attrs)

  // 3. Unarmoured patrols die in exactly the hits their damage says.
  const ZOMBIE_H = 1.95
  for (const [tag, knife] of [['m_pat1', 'knife_machete'], ['m_pat2', 'knife_karambit']]) {
    const sel = one(tag)
    await rcon(`item replace entity ${sel} armor.chest with minecraft:air`)
    await rcon(`item replace entity ${sel} armor.head with minecraft:air`)
    await placeTarget(attacker, sel, FACING_ATTACKER)
    await holdKnife(attacker, knife)
    const k = items.knives[knife]
    const want = Math.ceil(100 / k.damage - 1e-9)
    const seq = []
    let dead = false
    for (let tries = 0; tries < want + 4 && !dead; tries++) {
      const c = await cut(attacker, sel, 'chest', ZOMBIE_H, isMob)
      await sleep(k.cd * 50 + 120)
      dead = !(await rcon(`execute if entity ${sel}`)).includes('passed')
      if (c && (c.damage > 0 || dead)) seq.push(dead ? 'dead' : rd(c.after))
    }
    check(`unarmoured patrol: ${knife} (${k.damage}) downs it in exactly ${want} hits, the first leaving ${100 - k.damage}`,
      dead && seq.length === want && close(seq[0], 100 - k.damage, 0.01), seq)
  }

  // 4. Hits to down through the armour soldiers spawn with, every knife.
  const results = {}
  for (const [tag, label, hp, height, yaw] of [
    ['m_pat3', 'patrol', 100, ZOMBIE_H, FACING_ATTACKER],
    ['m_shield', 'shield soldier (from behind)', 120, ZOMBIE_H, FACING_AWAY],
    ['m_boss', 'boss', 380, ZOMBIE_H * 1.12, FACING_ATTACKER]]) {
    for (const knife of KNIVES) {
      const r = await cutsToDown(attacker, items, tag, label, hp, height, knife, yaw)
      if (!r) continue
      results[`${label} ${knife}`] = r
      check(`${label}, ${knife} in the chest (${r.vest || 'no vest'}): every hit as the armour model says, ${r.want} hits to down${label === 'boss' ? ' (>= 10)' : ''}`,
        r.allMatch && r.hits === r.want && (label !== 'boss' || r.hits >= 10), { hits: r.hits, want: r.want, damage: r.got })
    }
  }

  // 5. A shield soldier facing the attacker stops a cut to the chest but not one to the legs.
  {
    const sel = one('m_shield')
    await rcon(`attribute ${sel} minecraft:max_health base set 1000`)
    await rcon(`data merge entity ${sel} {Health:1000f}`)
    await placeTarget(attacker, sel, FACING_ATTACKER)
    await holdKnife(attacker, 'knife_karambit')
    attacker.sounds.length = 0
    const front = await cut(attacker, sel, 'chest', ZOMBIE_H, isMob)
    await sleep(kk.cd * 50 + 150)
    const clang = attacker.sounds.includes('craftov:impact.metal')
    check('shield soldier facing the attacker: a chest cut rings off the shield and changes neither health nor the vest',
      !!front && clang && front.damage === 0 && front.chest[0] === front.chest[1], front && { ...front, clang })
    const leg = await cut(attacker, sel, 'leg', ZOMBIE_H, isMob)
    await sleep(kk.cd * 50 + 150)
    check(`shield soldier facing the attacker: a leg cut gets under the shield (${rd(kk.damage * 0.7)})`,
      !!leg && close(leg.damage, kk.damage * 0.7), leg && rd(leg.damage))
  }

  // 6-8. The other bot.
  await killSoldiers(attacker, '')
  await rcon(`effect clear ${target.username} minecraft:resistance`)
  const tsel = target.username
  const PLAYER_H = 1.8
  const isTarget = isPlayer(target.username)
  if (!await wear(target, 'armor_4', 'chest')) {
    log('SETUP: could not put armor_4 on the target')
    return finish(2)
  }
  const a4 = items.armour.armor_4
  for (const knife of KNIVES) {
    const k = items.knives[knife]
    await setDura(tsel, 'chest', a4.max)
    await restore(target)
    await placeTarget(attacker, tsel, FACING_ATTACKER)
    await holdKnife(attacker, knife)
    const want = knifeHit(k, 'chest', a4, a4.max).damage
    let c = null
    for (let tries = 0; tries < 3 && !(c && c.damage > 0); tries++) {
      c = await cut(attacker, tsel, 'chest', PLAYER_H, isTarget)
      await sleep(k.cd * 50 + 150)
    }
    check(`player in a fresh armor_4: one ${knife} chest cut (pen ${k.pen}) does ${rd(want)} and wears the vest`,
      !!c && (close(c.damage, want) || close(c.damage, want * 0.95)) && c.chest[1] < c.chest[0], c && { damage: rd(c.damage), vest: c.chest })
  }

  const ok6 = await wear(target, 'armor_6', 'chest') && await wear(target, 'helmet_6', 'head')
  if (!ok6) {
    log('SETUP: could not put armor_6 and helmet_6 on the target')
    return finish(2)
  }
  await restore(target)
  await placeTarget(attacker, tsel, FACING_ATTACKER)
  await holdKnife(attacker, 'knife_karambit')
  const a6 = items.armour.armor_6
  const h6 = items.armour.helmet_6
  const loss = kk.damage * kk.ad * WEAR
  const chestCuts = []
  for (let n = 0; n < 3; n++) {
    const c = await cut(attacker, tsel, 'chest', PLAYER_H, isTarget)
    await sleep(kk.cd * 50 + 150)
    if (c) chestCuts.push({ damage: rd(c.damage), vest: c.chest.map(rd) })
  }
  check(`armor_6, karambit: chest cuts do ${rd(knifeHit(kk, 'chest', a6, a6.max).damage)} and wear the vest ${rd(loss)} each`,
    chestCuts.length === 3 && chestCuts.every(c => (close(c.damage, knifeHit(kk, 'chest', a6, a6.max).damage) || close(c.damage, knifeHit(kk, 'stomach', a6, a6.max).damage))
      && close((c.vest[0] ?? a6.max) - c.vest[1], loss, 0.02)), chestCuts)
  const head = await cut(attacker, tsel, 'head', PLAYER_H, isTarget)
  await sleep(kk.cd * 50 + 150)
  check(`helmet_6, karambit: a head cut does ${rd(knifeHit(kk, 'head', h6, h6.max).damage)} and wears the helmet, not the vest`,
    !!head && close(head.damage, knifeHit(kk, 'head', h6, h6.max).damage) && close((head.head[0] ?? h6.max) - head.head[1], loss, 0.02) && head.chest[0] === head.chest[1],
    head && { damage: rd(head.damage), helmet: head.head, vest: head.chest })
  const leg = await cut(attacker, tsel, 'leg', PLAYER_H, isTarget)
  await sleep(kk.cd * 50 + 150)
  check(`karambit: a leg cut does ${rd(kk.damage * 0.7)} and wears neither helmet nor vest`,
    !!leg && close(leg.damage, kk.damage * 0.7) && leg.chest[0] === leg.chest[1] && leg.head[0] === leg.head[1],
    leg && { damage: rd(leg.damage), helmet: leg.head, vest: leg.chest })

  // Cut it down, chest and legs, and read its death report.
  target.chatLines.length = 0
  let alive = true
  for (let n = 0; n < 20 && alive; n++) {
    await cut(attacker, tsel, n % 2 === 0 ? 'chest' : 'leg', PLAYER_H, isTarget)
    await sleep(kk.cd * 50 + 200)
    alive = inRaid(target) && target.game.gameMode !== 'spectator'
  }
  check('the target went down', !alive)
  await waitFor(() => target.chatLines.some(l => l.includes('受到的伤害')), 40000)
  await sleep(500)
  const report = target.chatLines.filter(l => l.includes(attacker.username) || l.includes('致命一击') || l.includes('受到的伤害'))
  log('death report', report)
  const line = report.filter(l => l.includes(' · ') && l.includes('→')).join(' | ')
  check('death report: the cuts are listed by part, not all chest (chest and a leg at least)',
    line.includes('胸部') && /左腿|右腿/.test(line), line)
  finish(checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  for (const bot of bots) {
    try { await rcon(`effect clear ${bot.username}`) } catch (e) { }
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
