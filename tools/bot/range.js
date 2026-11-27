// Private range check. In the lobby the bot is handed a full kit (helmet, armour, rig, backpack, rifle,
// ammo, meds) and enters the range from the lobby rack. On the range: the bot wears a loaner rig and
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
// Then a solo raid on the test map checks that /cv secure still opens mid-raid and that extracting
// keeps what the bot carried. Last, after logging off, the saved profile (loadout, stash, mailbox)
// holds none of the loaner rig, backpack or attachments.
//
//   PORT=25565 RCON_PORT=25575 node range.js [--no-raid]
// Exits 0 when every check passes, 1 otherwise.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map) (the raid part is on the proving ground), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')
const { pullTrigger, profile: profileOf } = require('./common')

const PORT = env.PORT
const RCON_PORT = env.RCON_PORT
const RCON_PASSWORD = env.RCON_PASSWORD
const RAID = !process.argv.includes('--no-raid')
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

  await enterRange()
  check('the lobby rack enters the private range', (await dimension()).includes('craftov_range'), await dimension())
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
setTimeout(() => { log('timeout'); process.exit(1) }, 240000)
