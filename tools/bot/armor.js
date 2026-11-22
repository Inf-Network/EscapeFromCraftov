// Armour damage per shot (issue #26). Two solo bots enter the proving ground; the shooter fires
// single aimed K47 PS rounds into the target's chest and RCON reads the target's health and vest
// durability before and after every shot; before each shot the target is healed and its wounds
// cleared through the test armory (/cv items).
//
//   1. a fresh armor_2 (class 2, PS overmatches it): one hit does ~30.6 (36 x 0.85);
//   2. a fresh armor_3 (same class as PS): one hit does ~21.6 (36 x 0.6);
//   3. a fresh armor_4: ten hits do 14.4, 14.4, 14.4, 14.4 (above half durability: full
//      protection), then 15.6, 21.3, 27.0, 34.1 as it wears out, then 36 (broken: same as none),
//      and every hit wears 11.88 durability (36 x 1.1 x 0.3);
//   4. the vest's lore warns that protection drops below 50% durability.
// The shooter switches its starter K47 (loaded with PS) to semi-auto and aims down sights from five
// blocks. A stomach hit counts too (0.95 of the chest damage); hits elsewhere and misses are fired
// again. The target is not an engineer, whose passive would wear the vest a quarter less.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node armor.js
// The bots are made op to queue for the proving ground (a test map) and to use /cv items; they are
// deopped when done and leave the raid by disconnecting. A match launches after match.wait-seconds
// (30 by default). Exit code 0 when every check passes, 1 when one fails, 2 when the scenario could
// not be set up.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []

// DamageService defaults (config.yml combat.armor) and items.yml values used for the expectations.
const K47 = 36
const PS = { pen: 3, armorDamage: 1.1 }
const WEAR = 0.3
const DAMAGE_TAKEN = [0.85, 0.6, 0.4, 0.25, 0.15]
const VESTS = { armor_2: { cls: 2, max: 60 }, armor_3: { cls: 3, max: 80 }, armor_4: { cls: 4, max: 90 } }
const LOSS = K47 * PS.armorDamage * WEAR

/** Chest damage of one PS round into a vest of this class at this durability (DamageService.armor). */
function expected (cls, max, dura) {
  if (dura <= 0) return K47
  const taken = DAMAGE_TAKEN[Math.min(Math.max(0, 1 - (PS.pen - cls)), DAMAGE_TAKEN.length - 1)]
  const strength = Math.min(1, dura / max / 0.5) * Math.min(1, dura / LOSS)
  return K47 * (1 - (1 - taken) * strength)
}

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.shots = 0
  bot.sounds = []
  bot.bars = []
  const heard = s => {
    s = String(s)
    if (/^craftov:gun\.[a-z0-9_]+\.fp(_s)?$/.test(s)) bot.shots++
    if (/hit\.|gun\.ads|gun\.mode/.test(s)) bot.sounds.push(s)
  }
  bot.on('soundEffectHeard', heard)
  // The shooter's own first-person shot is played on its entity (entity_sound_effect).
  bot._client.on('entity_sound_effect', p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_.\/]+/); if (m) heard(m[0]) })
  bot._client.on('action_bar', p => bot.bars.push(plain(p) || JSON.stringify(p)))
  bot._client.on('system_chat', p => { if (p.isActionBar) bot.bars.push(plain(p.content) || JSON.stringify(p.content)) })
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
async function dataNumber (target, path) {
  const out = await rcon(`data get entity ${target} ${path}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
const health = name => dataNumber(name, 'Health')
const vestDura = name => dataNumber(name, 'equipment.chest.components."minecraft:custom_data".PublicBukkitValues."craftov:dura"')

/** Command slot name for a mineflayer inventory window slot (hotbar 36-44, main 9-35). */
const commandSlot = i => i >= 36 ? `hotbar.${i - 36}` : `inventory.${i - 9}`

/** Gives the target a fresh vest and moves it onto the chest slot. */
async function wear (target, id) {
  await rcon(`cv give ${target.username} ${id}`)
  let slot = -1
  await waitFor(() => (slot = target.inventory.slots.findIndex((s, i) => i >= 9 && i <= 44 && s && pdc(s, 'id') === id)) >= 0, 5000)
  if (slot < 0) return false
  await rcon(`item replace entity ${target.username} armor.chest from entity ${target.username} ${commandSlot(slot)}`)
  await rcon(`item replace entity ${target.username} ${commandSlot(slot)} with minecraft:air`)
  return waitFor(() => pdc(target.inventory.slots[6], 'id') === id, 5000)
}

/**
 * Full health and no wounds: the test armory's "满血并清除伤病" button (slot 46 of /cv items, open
 * to ops). Bleeding left by earlier hits would otherwise tick into a shot's measurement.
 */
async function restore (target) {
  const window = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    target.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    target.chat('/cv items')
  })
  if (!window) return false
  await target.clickWindow(46, 0, 0)
  await sleep(250)
  target.closeWindow(window)
  await sleep(250)
  return (await health(target.username)) === 100
}

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}

/**
 * Places the target five blocks south of the shooter, facing each other, both still. BodyPart.of
 * tells the arms from the torso by the target's body facing, and a player's body only turns once its
 * head is more than 50 degrees away (or when it walks): so the head is swung 100 degrees to one side
 * (the body follows to 50 behind it), then 50 degrees past north to the other (the body is dragged
 * to north), then north, or hits in the middle of the chest would count as arm hits.
 */
async function face (shooter, target) {
  await rcon(`execute as ${shooter.username} at @s run tp @s 45.5 65 88.5 0 0`)
  for (const yaw of [280, 130, 180]) {
    await rcon(`execute as ${target.username} at @s run tp @s 45.5 65 93.5 ${yaw} 0`)
    await sleep(400)
  }
  await sleep(800)
}

/**
 * One aimed shot at the target's chest. Holds right click (aim down sights) for a moment, taps the
 * trigger and returns what RCON saw: health lost and vest durability before and after.
 */
async function shoot (shooter, target) {
  await clearSoldiers()
  if (!await restore(target)) {
    log('  could not restore the target through /cv items; healing with instant health (bleeding may skew this shot)')
    await rcon(`effect give ${target.username} minecraft:instant_health 1 5`)
  }
  await sleep(300)
  const entity = shooter.players[target.username] && shooter.players[target.username].entity
  if (!entity) return null
  const aimPoint = entity.position.offset(0, 1.15, 0)
  for (let i = 0; i < 8; i++) {
    await shooter.lookAt(aimPoint, true)
    shooter.activateItem()
    await sleep(100)
  }
  const duraBefore = await vestDura(target.username)
  const before = await health(target.username)
  const shotsBefore = shooter.shots
  shooter.sounds.length = 0
  shooter.activateItem()
  shooter.swingArm()
  let after = before
  for (const end = Date.now() + 1000; Date.now() < end && after === before; await sleep(20)) after = await health(target.username)
  await sleep(150)
  const duraAfter = await vestDura(target.username)
  await sleep(500)
  return { fired: shooter.shots - shotsBefore, before, after, damage: before - after, duraBefore, duraAfter, heard: shooter.sounds.slice() }
}

/**
 * Fires until a round lands on the vest (its durability drops) or the vest is already broken and a
 * round lands at all; returns that shot or null after too many tries.
 */
async function vestHit (shooter, target, cls, max) {
  for (let tries = 0; tries < 6; tries++) {
    const shot = await shoot(shooter, target)
    if (!shot) return null
    const dura = shot.duraBefore === null ? max : shot.duraBefore
    const want = expected(cls, max, dura)
    const onVest = dura > 0 ? shot.duraAfter !== null && shot.duraAfter < dura - 1e-6
      : Math.abs(shot.damage - want) < 0.2 || Math.abs(shot.damage - want * 0.95) < 0.2
    log(`  rounds=${shot.fired} damage=${shot.damage.toFixed(2)} dura ${shot.duraBefore} -> ${shot.duraAfter} (chest ${want.toFixed(2)}) ${shot.heard.join(' ')}`)
    if (onVest) return { ...shot, dura, want }
    if (shot.damage > 0 && dura > 0) log('  landed off the vest, firing again')
  }
  return null
}

const close = (a, b) => Math.abs(a - b) < 0.15
/** Chest or stomach (0.95) hit with the expected damage. */
const matches = (shot) => close(shot.damage, shot.want) || close(shot.damage, shot.want * 0.95)

;(async () => {
  const shooter = await make(`ar${run}a`)
  const target = await make(`ar${run}b`)
  for (const bot of bots) await rcon(`op ${bot.username}`)
  for (const bot of bots) bot.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', bots.map(b => b.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and spawn protection
  await clearSoldiers()
  await face(shooter, target)

  const gunSlot = shooter.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && pdc(s, 'id') === 'k47')
  if (gunSlot < 0) {
    log('SETUP: the shooter has no K47 in its hotbar')
    return finish(2)
  }
  shooter.setQuickBarSlot(gunSlot - 36)
  await sleep(800)
  const gun = shooter.inventory.slots[gunSlot]
  log('shooter K47', { ammo: pdc(gun, 'ammo'), type: pdc(gun, 'ammo_type') })
  if (pdc(gun, 'ammo_type') !== 'ammo_762_ps') {
    log('SETUP: the K47 is not loaded with PS')
    return finish(2)
  }
  // F (swap hands) cycles the fire mode: AUTO -> SEMI, so one tap is one round.
  for (let i = 0; i < 3 && !shooter.bars.some(b => b.includes('单发')); i++) {
    shooter._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await sleep(600)
  }
  if (!shooter.bars.some(b => b.includes('单发'))) {
    log('SETUP: could not switch the K47 to semi-auto', shooter.bars.slice(-3))
    return finish(2)
  }

  for (const id of ['armor_2', 'armor_3']) {
    if (!await wear(target, id)) {
      log(`SETUP: could not put ${id} on the target`)
      return finish(2)
    }
    await face(shooter, target)
    const { cls, max } = VESTS[id]
    const shot = await vestHit(shooter, target, cls, max)
    check(`${id}: one PS hit through a fresh vest does ~${expected(cls, max, max).toFixed(1)}`, !!shot && matches(shot),
      shot && { damage: +shot.damage.toFixed(2), want: +shot.want.toFixed(2) })
  }

  if (!await wear(target, 'armor_4')) {
    log('SETUP: could not put armor_4 on the target')
    return finish(2)
  }
  check('armor_4: the lore warns that protection drops below 50% durability', lore(target.inventory.slots[6]).includes('耐久低于 50% 时防护下降'),
    lore(target.inventory.slots[6]))
  await face(shooter, target)
  const { cls, max } = VESTS.armor_4
  const seq = []
  for (let n = 0; n < 10; n++) {
    if (n === 5) await face(shooter, target) // recoil and knockback-free hits still drift the bots a little
    const shot = await vestHit(shooter, target, cls, max)
    if (!shot) break
    seq.push(shot)
  }
  const got = seq.map(s => +s.damage.toFixed(2))
  const want = seq.map(s => +s.want.toFixed(2))
  log('armor_4 damage per hit', got)
  log('armor_4 expected (chest)', want)
  check('armor_4: ten hits landed on the vest', seq.length === 10, seq.length)
  check('armor_4: every hit did the expected damage (chest, or stomach x0.95)', seq.length > 0 && seq.every(matches), got)
  const wantChest = [14.4, 14.4, 14.4, 14.4, 15.6, 21.3, 27.0, 34.1, 36, 36]
  check('armor_4: the expectations are the issue\'s 14.4 x4, 15.6, 21.3, 27.0, 34.1, then 36',
    seq.length === 10 && want.every((w, i) => Math.abs(w - wantChest[i]) < 0.06), want)
  const worn = seq.filter(s => s.dura > 0 && s.duraAfter !== null).map(s => +(s.dura - s.duraAfter).toFixed(2))
  check('armor_4: every hit on an intact vest wore min(11.88, what was left)',
    worn.length >= 8 && seq.filter(s => s.dura > 0).every(s => close(s.dura - s.duraAfter, Math.min(LOSS, s.dura))), worn)
  check('armor_4: a broken vest is the same as none (36 a hit)', seq.length === 10 && seq.slice(8).every(s => s.dura <= 0 && (close(s.damage, 36) || close(s.damage, 34.2))),
    seq.slice(8).map(s => ({ dura: s.dura, damage: +s.damage.toFixed(2) })))
  finish(checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  for (const bot of bots) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
