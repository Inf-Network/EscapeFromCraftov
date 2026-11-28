// Private range check. In the lobby the bot is handed a full kit (helmet, armour, rig, backpack, rifle,
// ammo, meds) and enters the range from the lobby rack: the hangar itself has no dummies or firing
// lane any more (#164), the range plot has both. On the range: the bot wears a loaner rig and
// backpack and holds nothing else but the starter knife; the lobby menus that move items between the
// inventory and the profile (stash, trader, market, secure container, deploy) must refuse to open,
// /cv must open the weapon rack (presets and the free kit live in the lobby menu, which is out of
// reach), every gun and attachment on the rack fits on the bot at once, guns or attachments taken
// first, and the loaner backpack cannot be taken off (#162), a rack button refused five times in a row says why once in its tooltip, and
// not at all two seconds later (#161), the hand-back button empties the rig and backpack but leaves
// them on, Q never fires and a left click does (the capture block in front of the crosshair dug, as
// the vanilla client sends a click; a bare swing fires nothing, #33). Leaving must give back the
// lobby inventory slot for slot, with nothing taken on the range, and so must being teleported off
// the range by an admin, at once, so that a rifle stashed straight after /cv lobby is not handed
// back a moment later.
// Then the range's targets (#165), on a second visit: the plot has its three lanes (silhouettes in
// the corner's, bullseyes and moving targets on rails built beside it); rounds at a bullseye are
// counted, a hit leaves a hole and scores the ring under it, a miss beside the board counts against
// the hit rate and the rack's reset clears it all; a silhouette shot down falls through interpolated
// keyframes (not at once), lets rounds pass while down and stands up again with its health back
// after range.targets.down-seconds; the moving targets ride their rails at range.moving-speeds and
// can be hit; an operator tried on the range (the rack's 试用干员) uses its skill there (the
// engineer's shield, the recon's sonar on the silhouettes, the vanguard's sprint) on the range's own
// cooldown, and leaving drops the trial operator, the sprint and the cooldown (back on the range the
// skill is ready, the lobby shows the bot's own operator).
// Then a solo raid on the test map checks that /cv secure still opens mid-raid, that the skill is
// ready there whatever was used on the range, and that extracting keeps what the bot carried. Last,
// after logging off, the saved profile (loadout, stash, mailbox) holds none of the loaner rig,
// backpack or attachments, and the bot's own operator.
//
//   PORT=25565 RCON_PORT=25575 node range.js [--no-raid] [--no-targets]
// Exits 0 when every check passes, 1 otherwise.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map) (the raid part is on the proving ground), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')
const { pullTrigger, profile: profileOf } = require('./common')
const { Vec3 } = require('vec3')
const conv = require('mineflayer/lib/conversions')

const PORT = env.PORT
const RCON_PORT = env.RCON_PORT
const RCON_PASSWORD = env.RCON_PASSWORD
const RAID = !process.argv.includes('--no-raid')
const TARGETS = !process.argv.includes('--no-targets')
const rcon = cmd => rconRaw(cmd, { port: RCON_PORT, password: RCON_PASSWORD }).then(s => s.replace(/§./g, ''))
const name = 'Rng' + Math.floor(10000 + Math.random() * 90000)
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
// mineflayer applies player_rotation as absolute angles; recoil sends it relative, as vanilla
// expects, which turned the bot to face about south after its first round (as training.js does).
bot.once('login', () => {
  bot._client.removeAllListeners('player_rotation')
  bot._client.on('player_rotation', p => {
    const yaw = (p.relativeYaw ? conv.toNotchianYaw(bot.entity.yaw) : 0) + p.yaw
    const pitch = Math.max(-90, Math.min(90, (p.relativePitch ? conv.toNotchianPitch(bot.entity.pitch) : 0) + p.pitch))
    bot.entity.yaw = conv.fromNotchianYaw(yaw)
    bot.entity.pitch = conv.fromNotchianPitch(pitch)
  })
})
const chat = []
bot.on('messagestr', m => { if (m.trim()) { chat.push(m); log('CHAT', m) } })
// Refusals come on the action bar (util/Notify): logged with the chat.
function flatText (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flatText).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flatText(n.value && n.value.value)
  if (n.type === 'compound') return flatText(n.value)
  return flatText(n.text) + flatText(n.extra)
}
bot._client.on('action_bar', p => { const s = flatText(p.text); if (s.trim()) { chat.push(s); log('BAR', s) } })
bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log('BAR', s) })
bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
bot.on('error', e => log('ERROR', e.message))
let worldChanges = 0
bot.on('respawn', () => worldChanges++)
// The shooter hears the first-person shot on their own entity and the casings at the gun.
let shots = 0
const shotSound = (p) => { if (/gun\.[a-z0-9_]+\.fp|casing\./.test(JSON.stringify(p.sound || p.soundId || ''))) shots++ }
bot._client.on('entity_sound_effect', shotSound)
bot._client.on('sound_effect', shotSound)
// Every craftov sound heard, with its time (hit markers: craftov:hit.body / head / knock / kill), and
// the rounds fired (a first-person report holds one round, or N for gun.<id>.xN.fp).
const sounds = []
let rounds = 0
const onSound = (p) => {
  const key = (JSON.stringify(p.sound || p.soundId || '').match(/craftov:[a-z0-9_./]+/) || [null])[0]
  if (!key) return
  sounds.push({ at: Date.now(), key })
  const m = key.match(/gun\.[a-z0-9_]+?(?:\.x(\d))?\.fp$/)
  if (m) rounds += m[1] ? Number(m[1]) : 1
}
bot._client.on('entity_sound_effect', onSound)
bot._client.on('sound_effect', onSound)
const hitsSince = (at) => sounds.filter(x => x.at >= at && /craftov:hit\./.test(x.key))

const isRange = (item) => !!item && JSON.stringify(item.components || '').includes('craftov:range')
/** Ids of the range's loaner rig and backpack and of the rack's attachments: none may reach the profile. */
const rangeIds = new Set()
const id = (item) => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : (item ? item.name : null) }
const isUi = (s) => s.startsWith('ui/')
const title = (w) => JSON.stringify(w.title)
// Armour 5-8, cells 9-35, hotbar 36-44, offhand 45.
/**
 * A gun's damage component is its hand mark (#34: holstered while another key is held, none in the
 * hand), which follows what the player holds, not what the stack is: left out of the comparison.
 * Guns are the stacks with max_damage 5; any other damage still counts.
 */
const handMarkless = comps => {
  const gun = (comps || []).some(c => c.type === 'max_damage' && c.data === 5)
  return gun ? comps.filter(c => c.type !== 'damage') : comps
}
function layout ({ items = true, components = false } = {}) {
  const out = {}
  for (let i = 5; i <= 45; i++) {
    const it = bot.inventory.slots[i]
    if (!it || (items && isUi(id(it)))) continue
    out[i] = components ? JSON.stringify(handMarkless(it.components)) : id(it) + 'x' + it.count
  }
  return out
}
function diff (a, b) {
  const out = []
  for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) if (a[k] !== b[k]) out.push(`${k}: ${a[k] || '-'} -> ${b[k] || '-'}`)
  return out
}
const show = (l) => Object.entries(l).map(([k, v]) => k + ':' + v).join(' ')
function openWindow (fn, timeout = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, timeout)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}
async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(250) }
  return false
}
const dimension = async () => (await rcon(`data get entity ${name} Dimension`)).replace(/.*: /, '').replace(/"/g, '').trim()

async function refusedOnRange (cmd) {
  const from = chat.length
  const w = await openWindow(() => bot.chat(cmd), 1500)
  if (w) {
    // What the old build allowed: taking something out of the stash saved the range inventory as the loadout.
    if (cmd === '/cv stash') { await bot.clickWindow(0, 0, 0); await sleep(500) }
    bot.closeWindow(w)
    await sleep(300)
  }
  const refused = chat.slice(from).some(m => m.includes('靶场内无法使用'))
  check(`${cmd} is refused on the range`, !w && refused, w ? 'a window opened: ' + title(w) : (refused ? '' : 'no refusal message'))
}

/**
 * The rack button in `slot` is refused for want of room, clicked five times within the two seconds
 * its tooltip shows the reason (#161): the reason must be there once, not once per click, and gone
 * once the two seconds are up.
 */
async function refusedOnce (w, slot) {
  const lines = () => { const it = w.slots[slot]; return it ? (JSON.stringify(it.components || '').match(/放不下/g) || []).length : -1 }
  for (let i = 0; i < 5; i++) { await bot.clickWindow(slot, 0, 0); await sleep(150) }
  await sleep(300)
  check('a rack button refused five times in a row says why once', lines() === 1, `${lines()} lines of the reason`)
  await sleep(2500)
  check('the reason leaves the rack button after two seconds', lines() === 0, `${lines()} lines of the reason`)
}

async function rangePhase () {
  // Kit up in the lobby; /cv lobby rebuilds the inventory from the saved loadout so the baseline is
  // built exactly like the inventory handed back after the range.
  for (const item of ['helmet_3', 'armor_3', 'rig_2', 'pack_2', 'm4c', 'ammo_556_m855 90', 'medkit_s', 'bandage 3', 'splint']) {
    await rcon(`cv give ${name} ${item}`)
  }
  await sleep(2500)
  bot.chat('/cv lobby')
  await sleep(2500)
  const before = layout()
  const beforeFull = layout({ items: false, components: true })
  log('lobby inventory', show(before))
  check('lobby kit is in place', ['helmet_3', 'armor_3', 'rig_2', 'pack_2', 'm4c', 'medkit_s'].every(k => Object.values(before).some(v => v.startsWith(k + 'x'))), show(before))

  // The hangar has no firing lane or dummies any more: shooting is the private range's job (#164).
  // (A selector without a distance looks in every world: a raid's soldier or a range plot's dummy.)
  const lobbyDummies = await rcon('execute in minecraft:craftov_lobby positioned 0 65 0 if entity @e[type=minecraft:zombie,distance=..40]')
  const lobbyHay = await rcon('execute in minecraft:craftov_lobby if block 10 65 -21 minecraft:hay_block')
  const lobbyStripe = await rcon('execute in minecraft:craftov_lobby if block 12 64 -15 minecraft:yellow_concrete')
  check('the hangar has no training dummies and no firing lane (#164)', [lobbyDummies, lobbyHay, lobbyStripe].every(r => /failed/.test(r)),
    `dummies: ${lobbyDummies} | hay: ${lobbyHay} | lane floor: ${lobbyStripe}`)

  await enterRange()
  check('the lobby rack enters the private range', (await dimension()).includes('craftov_range'), await dimension())
  const laneHay = await rcon(`execute as ${name} at @s positioned ~-4 65 -21 if block ~ ~ ~ minecraft:hay_block`)
  // Three silhouettes in the corner's lane and three on the rails beside it, each an invisible zombie.
  const dummies = Object.values(bot.entities).filter(e => e.name === 'zombie').length
  check('the range plot has its firing lane and six silhouettes', /passed/.test(laneHay) && dummies === 6, `hay: ${laneHay} | ${dummies} silhouettes`)
  const inside = layout()
  const rig = bot.inventory.slots[7]
  const pack = bot.inventory.slots[8]
  check('the range puts on a loaner rig and backpack (#162)', isRange(rig) && /^rig_/.test(id(rig)) && isRange(pack) && /^pack_/.test(id(pack)),
    `legs ${id(rig)} ${isRange(rig) ? 'loaner' : 'own'}, feet ${id(pack)} ${isRange(pack) ? 'loaner' : 'own'}`)
  if (rig) rangeIds.add(id(rig))
  if (pack) rangeIds.add(id(pack))
  check('otherwise only the starter knife is held on the range', Object.entries(inside).every(([k, v]) => v.startsWith('knife_starter') || k === '7' || k === '8'), show(inside))

  for (const cmd of ['/cv stash', '/cv trader', '/cv secure', '/cv market', '/cv deploy']) await refusedOnRange(cmd)

  let menu = await openWindow(() => bot.chat('/cv'))
  check('/cv opens the weapon rack on the range', !!menu && title(menu).includes('靶场武器架'), menu ? title(menu) : 'no window')
  if (menu) {
    // Every gun and attachment on the rack, once each: the loaner rig and backpack hold them all.
    const offered = []
    for (let slot = 0; slot < 36; slot++) {
      if (menu.slots[slot]) {
        offered.push(slot)
        if (slot >= 18) rangeIds.add(id(menu.slots[slot]))
      }
    }
    const from = chat.length
    for (const slot of offered) { await bot.clickWindow(slot, 0, 0); await sleep(150) }
    await sleep(800)
    bot.closeWindow(menu)
    await sleep(800)
    const loaners = bot.inventory.items().filter(isRange)
    check('everything on the rack fits on the player at once (#162)', loaners.length === offered.length && !chat.slice(from).some(m => m.includes('放不下')),
      `${loaners.length} of ${offered.length} taken` + (chat.slice(from).some(m => m.includes('放不下')) ? ', refused for want of room' : ''))

    // The loaner backpack stays on: taken off it would pack the loaners, which cannot be packed.
    // (After the last take's line has had its time on the action bar, or the reason would queue
    // behind it and be replaced by the next one.)
    await sleep(1600)
    const said = chat.length
    await bot.clickWindow(8, 0, 0)
    await waitFor(() => chat.slice(said).some(m => m.includes('不能卸下')), 2500)
    const feet = await rcon(`execute if items entity ${name} armor.feet *[minecraft:custom_data~{PublicBukkitValues:{"craftov:range":1b}}]`)
    const cursor = await rcon(`execute if items entity ${name} player.cursor *`)
    check('the loaner backpack cannot be taken off on the range', /passed/.test(feet) && /failed/.test(cursor) && chat.slice(said).some(m => m.includes('不能卸下')),
      `feet: ${feet} | cursor: ${cursor}`)
    check('the loaners in it stay where they were', bot.inventory.items().filter(isRange).length === loaners.length, `${bot.inventory.items().filter(isRange).length} loaners`)

    // No room for one more gun now: a button refused five times says why once (#161).
    menu = await openWindow(() => bot.chat('/cv'))
    if (menu) {
      const before = chat.length
      for (let i = 0; i < 30 && !chat.slice(before).some(m => m.includes('放不下')); i++) { await bot.clickWindow(0, 0, 0); await sleep(150) }
      await sleep(2500)
      await refusedOnce(menu, 0)
      // The hand-back button empties the rig and backpack but leaves them on.
      await bot.clickWindow(42, 0, 0)
      await sleep(500)
      // Attachments first, then the guns: the attachments go to the rig first, so the backpack
      // keeps room for the guns, and all of it fits in this order too.
      const reversed = [...offered.filter(slot => slot >= 18), ...offered.filter(slot => slot < 18)]
      const refusedFrom = chat.length
      for (const slot of reversed) { await bot.clickWindow(slot, 0, 0); await sleep(150) }
      await sleep(800)
      const taken = menu.slots.slice(menu.inventoryStart, menu.inventoryEnd).filter(isRange).length
      check('attachments first, then guns: everything still fits (#162)', taken === offered.length && !chat.slice(refusedFrom).some(m => m.includes('放不下')),
        `${taken} of ${offered.length} taken` + (chat.slice(refusedFrom).some(m => m.includes('放不下')) ? ', refused for want of room' : ''))
      await bot.clickWindow(42, 0, 0)
      await sleep(500)
      await bot.clickWindow(0, 0, 0) // take one loaner again
      await sleep(500)
      bot.closeWindow(menu)
      await sleep(800)
      check('handing everything back keeps the loaner rig and backpack on', isRange(bot.inventory.slots[7]) && isRange(bot.inventory.slots[8])
        && bot.inventory.items().filter(isRange).length === 1, `${bot.inventory.items().filter(isRange).length} loaners left in the cells`)
    }
  }
  check('the rack hands out a loaner', bot.inventory.items().some(isRange))

  // Trigger: Q (reload) never fires, a swing does.
  await rcon(`cv give ${name} m4c`)
  await rcon(`cv give ${name} ammo_556_m855 60`)
  await sleep(800)
  const gun = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && id(s) === 'm4c')
  if (gun >= 0) {
    bot.setQuickBarSlot(gun - 36)
    await sleep(500)
    shots = 0
    for (let i = 0; i < 3; i++) { bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(400) }
    await sleep(3500)
    check('Q reloads without firing', shots === 0, shots + ' shots')
    // Down the range at a dummy: the capture block the click digs sits in the air in front of the
    // crosshair, which a wall or the rack right in front would leave no room for.
    const dummy = bot.nearestEntity(e => e.name === 'zombie')
    if (dummy) await bot.lookAt(dummy.position.offset(0, 1.3, 0), true)
    await sleep(500)
    shots = 0
    const cell = await pullTrigger(bot)
    await sleep(600)
    check('a left click fires', shots >= 1, `${shots} shots${cell ? '' : ', no capture block in front of the crosshair'}`)
  } else check('m4c reached the hotbar on the range', false)

  // Outlast the lobby's 2 s loadout snapshot before leaving.
  await sleep(2500)
  bot.setQuickBarSlot(8)
  await sleep(300)
  bot.activateItem()
  await sleep(2500)
  check('leaving returns to the lobby', (await dimension()).includes('craftov_lobby'), await dimension())
  const after = layout()
  log('inventory after leaving', show(after))
  const d = diff(before, after)
  check('every slot is back as it was before the range', d.length === 0, d.join(', '))
  const dFull = diff(beforeFull, layout({ items: false, components: true }))
  check('every stack is identical, components included (a gun\'s hand mark aside)', dFull.length === 0, `${dFull.length} slots differ: ` + dFull.slice(0, 3).join(' | '))
  check('no range loaner came back', !bot.inventory.items().some(i => JSON.stringify(i.components || '').includes('craftov:range')))

  // Being teleported off the range by other means ends the session the same way, and at once: the
  // gear is back the moment the player lands, not on the range's next upkeep tick.
  await enterRange()
  await rcon(`execute in minecraft:craftov_lobby run tp ${name} 0.5 65 12.5`)
  const rifle = await rcon(`execute if items entity ${name} container.* *[minecraft:item_model="craftov:m4c"]`)
  const exit = await rcon(`execute if items entity ${name} container.* *[minecraft:item_model="craftov:ui/icon_cancel"]`)
  check('the gear is back the moment an admin teleport lands', /passed/.test(rifle) && /failed/.test(exit), `rifle: ${rifle} | exit item: ${exit}`)
  await sleep(3000)
  const moved = diff(before, layout())
  check('an admin teleport off the range also gives the gear back', (await dimension()).includes('craftov_lobby') && moved.length === 0,
    (await dimension()) + ' ' + moved.join(', '))

  // /cv lobby from the range and straight into the stash: the old build still had the session open
  // for up to half a second, then handed the entry loadout back, so a deposited rifle came back too.
  await enterRange()
  const stash = await openWindow(() => { bot.chat('/cv lobby'); bot.chat('/cv stash') })
  const rifleSlot = stash ? stash.slots.findIndex((s, i) => i >= stash.inventoryStart && s && id(s) === 'm4c') : -1
  if (rifleSlot >= 0) { await bot.clickWindow(rifleSlot, 0, 0); await sleep(300) }
  if (stash) bot.closeWindow(stash)
  await sleep(2500)
  const kept = layout()
  check('a rifle stashed right after leaving stays in the stash', rifleSlot >= 0 && !Object.values(kept).some(v => v.startsWith('m4cx')),
    rifleSlot < 0 ? 'no stash window or no rifle in it' : show(kept))
}

// ---------------------------------------------------------------- targets (#165)
// Displays as the client gets them: their text, their plate's rotation (with the interpolation it
// came with), and who rides what. A silhouette is a zombie (its hitbox) carrying its plate, base and
// label; reading them off the packets checks what a vanilla client is told to draw.
const MCDATA = require('minecraft-data')('1.21.11')
const TEXT_DISPLAY = MCDATA.entitiesByName.text_display.id
const ITEM_DISPLAY = MCDATA.entitiesByName.item_display.id
const displays = new Map() // entity id -> { type, at: spawn position, text, angle, ticks, poses: [{ at, angle, ticks, restart }] }
const riders = new Map() // vehicle id -> passenger ids
bot._client.on('spawn_entity', p => {
  if (p.type === TEXT_DISPLAY || p.type === ITEM_DISPLAY) displays.set(p.entityId, { type: p.type, at: new Vec3(p.x, p.y, p.z), text: '', angle: 0, ticks: 0, poses: [] })
})
bot._client.on('entity_destroy', p => { for (const id of p.entityIds) { displays.delete(id); riders.delete(id) } })
bot._client.on('set_passengers', p => riders.set(p.entityId, p.passengers))
bot._client.on('entity_metadata', p => {
  const d = displays.get(p.entityId)
  if (!d) return
  let rotated = null
  let restart = false
  for (const m of p.metadata || []) {
    if (m.key === 8) restart = true // the interpolation starts over: the client eases into what follows
    if (m.key === 9) d.ticks = m.value
    if (d.type === TEXT_DISPLAY && m.key === 23) d.text = flatText(m.value)
    if (d.type === ITEM_DISPLAY && m.key === 13 && m.value) rotated = m.value
  }
  if (rotated) {
    d.angle = Math.round(2 * Math.atan2(rotated.x, rotated.w) * 180 / Math.PI)
    d.poses.push({ at: Date.now(), angle: d.angle, ticks: d.ticks, restart })
  }
})
/** A zombie's passenger of `type`: a silhouette's plate (item display) or label (text display). */
const riderOf = (zombie, type) => (riders.get(zombie.id) || []).map(id => displays.get(id)).find(d => d && d.type === type)
const labelOf = (zombie) => { const d = zombie && riderOf(zombie, TEXT_DISPLAY); return d ? d.text : '' }
const zombieNear = (x, z, r = 0.8) => Object.values(bot.entities).find(e => e.name === 'zombie' && Math.hypot(e.position.x - x, e.position.z - z) < r)
/** `data get entity` of the first entity `selector` finds in the range world (short answers only: long ones are cut). */
const entityData = (selector, path = '') => rcon(`execute in minecraft:craftov_range run data get entity ${selector} ${path}`)
const countIn = async (selector) => {
  const m = (await rcon(`execute in minecraft:craftov_range if entity ${selector}`)).match(/Count: (\d+)/)
  return m ? Number(m[1]) : 0
}
const near = (type, x, y, z, r, extra = '') => `@e[type=minecraft:${type},x=${x},y=${y},z=${z},distance=..${r}${extra}]`
const gametime = async () => Number(((await rcon('time query gametime')).match(/(\d+)/) || [0, 0])[1])
let DX = 0

/** A gun from the armory in hand (a weapon slot), loaded. Nothing else held is touched: the leave item stays. */
async function arm (gun) {
  await rcon(`cv give ${name} ${gun}`)
  await sleep(800)
  const slot = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && id(s) === gun)
  if (slot < 0) return false
  bot.setQuickBarSlot(slot - 36)
  await sleep(1500) // the draw
  await reload()
  return true
}
/** Q: a full magazine (the range's reserve never runs out), so no reload stops a check halfway. */
async function reload () {
  bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  await sleep(4500)
}
async function stand (x, z, at) {
  await rcon(`execute in minecraft:craftov_range run tp ${name} ${x} 65 ${z} 180 0`)
  await sleep(1200)
  if (at) await bot.lookAt(at, true)
  await sleep(400)
}
/** Holds right mouse, the sights up: vanilla repeats the use packet every 4 ticks while it is down (as accuracy.js). */
function aimWhile () {
  bot.activateItem()
  const timer = setInterval(() => bot.activateItem(), 150)
  return () => { clearInterval(timer); bot.deactivateItem() }
}
/** One click of the trigger (a semi-automatic gun fires one round); resolves after the round's time. */
async function click (gap = 450) {
  const cell = await pullTrigger(bot)
  await sleep(gap)
  return cell
}

async function targetsPhase () {
  await enterRange()
  if (!(await dimension()).includes('craftov_range')) { check('back on the range for the targets', false, await dimension()); return }
  DX = Math.round(bot.entity.position.x - 14.5)
  log('plot offset', DX)

  // Layout: the corner's lane and the two lanes built west of it, walled, with their stands and rails.
  const blocks = [['hay at the corner lane\'s end', DX + 10, 65, -21, 'hay_block'], ['hay at the new lanes\' end', DX - 20, 65, -42, 'hay_block'],
    ['the 10 m rail', DX - 22, 65, -19, 'rail'], ['the 20 m rail', DX - 22, 65, -29, 'rail'], ['the 30 m rail', DX - 22, 65, -39, 'rail'],
    ['the 10 m bullseye stand', DX - 5, 65, -19, 'dark_oak_fence'], ['the 25 m bullseye stand', DX + 1, 65, -34, 'dark_oak_fence'],
    ['the back wall', DX - 10, 66, -1, 'deepslate_tiles'], ['the west wall', DX - 34, 66, -20, 'deepslate_tiles'],
    ['the firing rail', DX - 20, 65, -9, 'polished_blackstone_slab']]
  const missing = []
  for (const [what, x, y, z, block] of blocks) {
    if (!/passed/.test(await rcon(`execute in minecraft:craftov_range if block ${x} ${y} ${z} minecraft:${block}`))) missing.push(what)
  }
  check('the plot has three lanes: silhouettes, bullseyes and rails', missing.length === 0, missing.join(', ') || 'all there')
  const plot = `x=${DX - 40},y=55,z=-70,dx=70,dy=30,dz=72`
  const zombies = await countIn(`@e[type=minecraft:zombie,${plot}]`)
  const slimes = await countIn(`@e[type=minecraft:slime,${plot}]`)
  const plates = await countIn(`@e[type=minecraft:item_display,${plot},nbt={item:{components:{"minecraft:item_model":"craftov:fx/target_silhouette"}}}]`)
  const carried = Object.values(bot.entities).filter(e => e.name === 'zombie' && riderOf(e, ITEM_DISPLAY) && riderOf(e, TEXT_DISPLAY)).length
  check('six silhouettes (three on rails) and two bullseyes stand on the plot', zombies === 6 && slimes === 2 && plates === 6 && carried === 6,
    `${zombies} hitboxes, ${plates} plates, ${carried} carrying plate and label, ${slimes} boards`)
  const seat = (await entityData(near('zombie', DX + 15.5, 65, -15.5, 0.5, ',limit=1'), 'Pos[1]')).match(/(-?[\d.]+)d/)
  const plateY = (await entityData(near('item_display', DX + 15.5, 67, -15.5, 0.5, ',limit=1'), 'Pos[1]')).match(/(-?[\d.]+)d/)
  check('a silhouette\'s plate rides its hitbox 2.0125 up, where its model is drawn down from',
    seat && plateY && Math.abs(Number(plateY[1]) - Number(seat[1]) - 2.0125) < 0.01, `hitbox y ${seat && seat[1]}, plate y ${plateY && plateY[1]}`)

  if (!await arm('dm14')) { check('a DM14 in hand for the targets', false); return }
  await bullseye()
  await falling()
  await moving()
}

async function bullseye () {
  // The 10 m board: its slime's box is the paper face.
  const slime = await entityData(near('slime', DX - 4.5, 66, -18.5, 1.5, ',limit=1'), 'Pos')
  const p = slime.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  if (!p) { check('the 10 m bullseye is there', false, slime); return }
  const cx = Number(p[1]); const cy = Number(p[2]) + 0.52; const face = Number(p[3]) + 0.52
  // Its tally hangs over it, the only text display there.
  const board = [...displays.values()].filter(d => d.type === TEXT_DISPLAY && Math.abs(d.at.x - cx) < 0.1 && Math.abs(d.at.z - Number(p[3])) < 0.1)[0]
  const tally = () => board ? board.text : ''
  log('10 m board centre', cx.toFixed(2), cy.toFixed(2), face.toFixed(2), '|', tally())
  await stand(cx, -7.5, new Vec3(cx, cy, face))
  const release = aimWhile()
  await sleep(1200) // the sights up
  const from = rounds
  for (let i = 0; i < 6; i++) await click()
  await sleep(800)
  const fired = rounds - from
  const t1 = tally()
  const m = t1.match(/命中 (\d+)\/(\d+)/)
  const hits = m ? Number(m[1]) : -1
  const counted = m ? Number(m[2]) : -1
  check('every round fired at the board is counted', fired >= 5 && counted === fired, `${fired} rounds fired, tally: ${t1}`)
  check('rounds aimed at the middle hit the board', hits >= 3, t1)
  const holes = await countIn(near('item_display', cx, cy, face, 0.9, ',nbt={item:{components:{"minecraft:item_model":"craftov:fx/hole"}}}'))
  check('each hit leaves a hole on the board', holes === hits, `${holes} holes, ${hits} hits`)
  const outlined = await countIn(near('item_display', cx, cy, face, 0.9, ',nbt={Glowing:1b}'))
  const last = await entityData(near('item_display', cx, cy, face, 0.9, ',nbt={Glowing:1b},limit=1'), 'Pos')
  const lp = last.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  const said = t1.match(/上一发 (\d+) 环|上一发 未上环/)
  if (lp && said) {
    const off = Math.hypot(Number(lp[1]) - cx, Number(lp[2]) - cy)
    const ring = off >= 0.5 ? 0 : Math.max(1, Math.min(10, 10 - Math.floor(off / 0.05)))
    check('the last hit alone is outlined and scores the ring its hole is on', outlined === 1 && (said[1] ? Number(said[1]) : 0) === ring,
      `${outlined} outlined; hole ${off.toFixed(3)} from the centre = ring ${ring}; tally says ${said[0]}`)
  } else check('the last hit alone is outlined and scores the ring its hole is on', false, `${last} | ${t1}`)
  // Beside the board (1 m right of its centre): aimed at it, a miss.
  await bot.lookAt(new Vec3(cx + 1.0, cy, face), true)
  await sleep(400)
  const from2 = rounds
  for (let i = 0; i < 2; i++) await click()
  await sleep(800)
  release()
  const t2 = tally()
  const m2 = t2.match(/命中 (\d+)\/(\d+)/)
  check('a round just beside the board counts as a miss', m2 && Number(m2[1]) === hits && Number(m2[2]) === counted + (rounds - from2) && /脱靶/.test(t2), t2)
  // The rack's reset button.
  const menu = await openWindow(() => bot.chat('/cv'))
  if (menu) { await bot.clickWindow(44, 0, 0); await sleep(600); bot.closeWindow(menu); await sleep(600) }
  const t3 = tally()
  const left = await countIn(near('item_display', cx, cy, face, 0.9, ',nbt={item:{components:{"minecraft:item_model":"craftov:fx/hole"}}}'))
  check('the rack\'s reset clears the tally and the holes', /命中 0\/0/.test(t3) && left === 0, `${t3} | ${left} holes`)
}

async function falling () {
  // The 7 m silhouette, from 6 m in front of it.
  const x = DX + 15.5; const z = -15.5
  const target = zombieNear(x, z)
  const plate = target && riderOf(target, ITEM_DISPLAY)
  if (!plate) { check('the 7 m silhouette and its plate are in sight', false); return }
  await reload()
  await stand(x, -9.5, new Vec3(x, 65 + 1.15, z))
  const release = aimWhile()
  await sleep(1200)
  check('a standing silhouette is upright, its label at full health', plate.angle === 0 && /100/.test(labelOf(target)), `${plate.angle} | ${labelOf(target)}`)
  let knocked = null
  const startedAt = Date.now()
  for (let i = 0; i < 6 && !knocked; i++) {
    const at = Date.now()
    await click(150)
    knocked = sounds.find(s => s.at >= at && s.key === 'craftov:hit.knock') || null
    if (!knocked) await sleep(300)
  }
  check('shot down, the silhouette sounds the knock-down marker', !!knocked, hitsSince(startedAt).map(s => s.key).join(', '))
  if (!knocked) { release(); return }
  await sleep(1500)
  // Its plate tips back through keyframes the client eases into: never a jump to the floor.
  const fall = plate.poses.filter(q => q.at >= knocked.at - 200)
  log('fall as the client got it', JSON.stringify(fall.map(q => ({ ms: q.at - knocked.at, angle: q.angle, ticks: q.ticks, restart: q.restart }))))
  check('it falls back flat (-90 degrees)', fall.length > 0 && fall[fall.length - 1].angle === -90, fall.map(q => q.angle).join(', '))
  check('the fall is several eased steps, each restarting the interpolation', fall.length >= 3 && fall.some(q => q.angle > -90 && q.angle < 0)
    && fall.every(q => q.ticks >= 2 && q.restart), JSON.stringify(fall))
  check('its label says it was knocked down, with the rounds and the time', /击倒 \d+ 发 · [\d.]+ 秒|一发击倒/.test(labelOf(target)), labelOf(target))
  // Down, rounds pass over it.
  const passAt = Date.now()
  await click(500)
  check('a round at a fallen silhouette passes it by', hitsSince(passAt).length === 0, hitsSince(passAt).map(s => s.key).join(', '))
  // Back up after range.targets.down-seconds (3), with its health back.
  await waitFor(() => plate.angle === 0 && plate.poses.some(q => q.at > knocked.at + 1000), 6000)
  const rise = plate.poses.filter(q => q.at > knocked.at + 1000)
  const upAt = rise.length ? rise[rise.length - 1].at : 0
  const downFor = (rise.length ? rise[0].at - knocked.at : 0) / 1000
  log('rise as the client got it', JSON.stringify(rise.map(q => ({ ms: q.at - knocked.at, angle: q.angle, ticks: q.ticks, restart: q.restart }))))
  check('it starts to stand up about 3 s after falling, eased', upAt > 0 && downFor > 2.6 && downFor < 4.5 && rise.every(q => q.ticks >= 3 && q.restart)
    && rise[rise.length - 1].angle === 0, `${downFor.toFixed(2)} s; ${JSON.stringify(rise)}`)
  await sleep(1000)
  const upLabel = labelOf(target)
  const hitAt = Date.now()
  await click(600)
  release()
  check('standing again it can be hit, with its health back', hitsSince(hitAt).length > 0 && /100/.test(upLabel), `${upLabel} | ${hitsSince(hitAt).map(s => s.key).join(', ')}`)
}

async function moving () {
  const railsZ = [-18.5, -28.5, -38.5]
  const speeds = [2.0, 3.5, 5.5]
  const sel = (z) => `@e[type=minecraft:zombie,x=${DX - 33},y=64,z=${z - 0.5},dx=23,dy=3,dz=1,limit=1]`
  const xAt = async (z) => { const m = (await entityData(sel(z), 'Pos[0]')).match(/(-?[\d.]+)d/); return m ? Number(m[1]) : NaN }
  for (let i = 0; i < railsZ.length; i++) {
    const z = railsZ[i]
    const v = speeds[i]
    const found = []
    let fits = false
    let tooFast = false
    for (let w = 0; w < 5; w++) {
      // Each read bracketed by the game time, so the ticks between two reads are known within a tick or two.
      const a0 = await gametime(); const x0 = await xAt(z); const b0 = await gametime()
      await sleep(500)
      const a1 = await gametime(); const x1 = await xAt(z); const b1 = await gametime()
      const lo = Math.max(1, a1 - b0); const hi = Math.max(lo, b1 - a0)
      const d = Math.abs(x1 - x0)
      found.push(`${d.toFixed(2)} in ${lo}-${hi}t`)
      // The speed lies between d/hi and d/lo ticks: a fit when v does (with 5% slack); a pause or a
      // turn at the end of the rail only makes it look slower.
      if (d / (hi / 20) <= v * 1.05 && d / (lo / 20) >= v * 0.95) fits = true
      if (d / (hi / 20) > v * 1.05) tooFast = true
    }
    check(`the ${(i + 1) * 10} m target rides its rail at ${v} m/s`, fits && !tooFast, found.join(', '))
  }
  // Hit the slow one and the fast one, aiming where a vanilla client draws them (a moment behind).
  await reload()
  for (const [z, what] of [[-18.5, '10 m (slow)'], [-38.5, '30 m (fast)']]) {
    await stand(DX - 21.5, -7.5)
    const trail = []
    const target = () => Object.values(bot.entities).find(e => e.name === 'zombie' && Math.abs(e.position.z - z) < 0.6 && e.position.x < DX - 5)
    let aiming = true
    const steer = (async () => {
      while (aiming) {
        const e = target()
        if (e) {
          trail.push({ at: Date.now(), p: e.position.clone() })
          const back = trail.filter(t => t.at <= Date.now() - 150).pop() || trail[0]
          await bot.lookAt(back.p.offset(0, 1.15, 0), true)
        }
        await sleep(25)
      }
    })()
    const release = aimWhile()
    await sleep(1200)
    const before = labelOf(target())
    let landed = false
    for (let i = 0; i < 9 && !landed; i++) {
      await click(400)
      landed = /剩余|-\d+|击倒/.test(labelOf(target()).split('\n').slice(1).join(' ')) && labelOf(target()) !== before
    }
    aiming = false
    release()
    await steer
    check(`the ${what} moving target can be hit`, landed, `before: ${before} | after: ${labelOf(target())}`)
  }
}

// ---------------------------------------------------------------- operators on the range (#165)
/** Sneak + F as the client sends it; the lines it brought. */
async function skill () {
  const from = chat.length
  bot.setControlState('sneak', true)
  await sleep(250)
  bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  await sleep(250)
  bot.setControlState('sneak', false)
  await sleep(900)
  return chat.slice(from)
}
/** Tries an operator through the rack's 试用干员 button: slot 10, 12, 14, 16 = vanguard, medic, recon, engineer. */
async function tryOperator (slot) {
  const rack = await openWindow(() => bot.chat('/cv'))
  if (!rack) return 'no rack'
  const ops = await openWindow(() => bot.clickWindow(36, 0, 0))
  if (!ops) { bot.closeWindow(rack); return 'no operator menu' }
  const t = title(ops)
  const from = chat.length
  await bot.clickWindow(slot, 0, 0)
  await sleep(600)
  bot.closeWindow(ops)
  await sleep(500)
  return t + ' ' + chat.slice(from).join(' | ')
}
const speedOf = async () => Number(((await rcon(`attribute ${name} minecraft:movement_speed get`)).match(/(-?[\d.]+)\s*$/) || [0, NaN])[1])

async function operatorPhase () {
  await stand(DX + 14.5, -5.5)
  const said = await tryOperator(16)
  check('the rack tries out an operator for the range only', /靶场试用/.test(said) && /铁砧/.test(said), said)
  // The engineer's shield, two blocks ahead.
  await bot.look(0, 0, true) // north (mineflayer's yaw 0), level
  await sleep(300)
  let lines = await skill()
  const shield = await rcon(`execute as ${name} at @s positioned ~ ~ ~-2 if block ~ ~ ~ minecraft:tinted_glass`)
  check('the engineer\'s shield goes up on the range', lines.some(l => l.includes('技能 · 部署掩体')) && /passed/.test(shield), `${lines.join(' | ')} | ${shield}`)
  lines = await skill()
  check('right after, the range cooldown holds it', lines.some(l => l.includes('技能冷却中')), lines.join(' | '))
  // The recon's sonar marks the standing silhouettes (after the range's shorter cooldown: 55 s x 0.25).
  await sleep(14500)
  await tryOperator(14)
  lines = await skill()
  const marked = lines.map(l => l.match(/标记了 (\d+) 个目标/)).find(Boolean)
  check('the recon\'s sonar marks the silhouettes on the range', marked && Number(marked[1]) >= 3, lines.join(' | '))
  // The vanguard (the bot's own operator) sprints there; leaving drops the sprint and the trial.
  await sleep(13000)
  await tryOperator(10)
  const base = await speedOf()
  lines = await skill()
  const fast = await speedOf()
  check('the vanguard\'s sprint speeds the player up on the range', lines.some(l => l.includes('技能 · 战术冲刺')) && fast > base * 1.2,
    `${base} -> ${fast} | ${lines.join(' | ')}`)
  await tryOperator(12) // the medic, still being tried when leaving
  bot.setQuickBarSlot(8)
  await sleep(300)
  bot.activateItem()
  await sleep(2500)
  const lobbySpeed = await speedOf()
  check('leaving the range drops the sprint at once', (await dimension()).includes('craftov_lobby') && lobbySpeed <= base * 1.01, `${lobbySpeed} (was ${base} before the sprint)`)
  const main = await openWindow(() => bot.chat('/cv'))
  const opButton = main && main.slots[14]
  const lore = opButton ? JSON.stringify(opButton.components || '') : ''
  if (main) { bot.closeWindow(main); await sleep(400) }
  check('in the lobby the operator is the bot\'s own again, not the one tried', /疾风/.test(lore) && !/白鸮/.test(lore), lore.slice(0, 200))
  // Back on the range the skill is ready: the range's cooldown went with the visit.
  await enterRange()
  await stand(DX + 14.5, -5.5)
  lines = await skill()
  check('back on the range the skill is ready again', lines.some(l => l.includes('技能 · ')), lines.join(' | '))
  bot.setQuickBarSlot(8)
  await sleep(300)
  bot.activateItem()
  await sleep(2500)
}

async function enterRange () {
  await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (rack) bot.activateEntity(rack)
  await sleep(2500)
}

async function raidPhase () {
  const from = worldChanges
  bot.chat('/cv queue proving NORMAL SOLO')
  // Landed, not still in the insertion camera: the client's game mode can lag behind the world
  // change, so ask the server (the secure container stays shut during the fly-in).
  const landed = async () => /passed/i.test(await rcon(`execute if entity @a[name=${name},gamemode=adventure]`))
  const inRaid = await waitFor(async () => worldChanges > from && !/lobby|range/.test(await dimension()) && await landed(), 90000)
  check('a solo raid on the test map starts', inRaid, await dimension())
  if (!inRaid) return
  await sleep(3000)
  const secure = await openWindow(() => bot.chat('/cv secure'))
  check('/cv secure opens mid-raid', !!secure && title(secure).includes('安全箱'), secure ? title(secure) : 'no window')
  if (secure) { bot.closeWindow(secure); await sleep(300) }
  if (TARGETS) {
    // The skills used on the range ran on the range's own cooldown: the raid's is untouched.
    const lines = await skill()
    check('in the raid the skill is ready, whatever was used on the range', lines.some(l => l.includes('技能 · ')) && !lines.some(l => l.includes('冷却')),
      lines.join(' | '))
  }
  const carried = layout()
  log('carried into extract', show(carried))
  const before = worldChanges
  await rcon(`execute as ${name} at @s run tp @s 106.5 65 8.5`)
  const out = await waitFor(() => worldChanges > before, 30000)
  check('the always-open extract works', out)
  await sleep(3000)
  const home = layout()
  const d = diff(carried, home)
  check('extracting keeps what the bot carried', out && d.length === 0, d.join(', '))
}

bot.once('spawn', async () => {
  try {
    await sleep(3000)
    await rcon('op ' + name)
    await rangePhase()
    if (TARGETS) {
      await targetsPhase()
      await operatorPhase()
    }
    if (RAID) await raidPhase()
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  await rcon('deop ' + name).catch(() => {})
  bot.quit()
  // Logging off saves the profile: no range item may be in the loadout, the stash or the mailbox.
  await sleep(2500)
  const saved = JSON.stringify(profileOf(name))
  const leaked = [...rangeIds].filter(item => saved.includes(`"id":"${item}"`))
  check('no loaner rig, backpack or attachment reached the profile', rangeIds.size > 0 && saved.length > 2 && leaked.length === 0,
    leaked.length ? leaked.join(', ') : `${rangeIds.size} ids looked for`)
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})
setTimeout(() => { log('timeout'); process.exit(1) }, 600000)
