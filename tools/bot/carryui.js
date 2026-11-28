// The carry interface (issue #10), checked from the protocol side:
//
//   gear     no rig or backpack (#184, #176): Q on the worn vest in the lobby leaves it on, a gold bar
//            given lands in a pocket and a second vest does not, and a shift-click on the worn vest
//            puts it into the stash.
//   zones    a new account (starter rig_1 + pack_1): each carry zone has locked cells, and exactly
//            one of them per zone shows a tooltip. Ammo, a gold bar, a rifle and a bandage given
//            land where their category goes: ammo and the bandage on a free quick key (4-8), the
//            gold bar in the backpack, the rifle in the backpack once keys 1-2 hold guns.
//   rules    zone rules on the inventory screen: a gold bar placed on key 1, a gun placed in the
//            rig and a number-key swap that would put a gun on a pocket (keys 4-8) are all refused
//            (nothing moves); a gold bar or ammo placed in a pocket is fine (#176); shift-click on
//            the gold bar in the backpack moves it to a pocket.
//   matrix   every kind of item (gun, ammo, med, key, valuable, armour) x every zone (gun key, quick
//            key, rig, backpack): picked up and placed on an empty cell of the zone, and for the two
//            hotbar zones moved there with a number key; it moves exactly when the zone takes it
//            (CarryService.refusal), and a refused one stays where it was. A drag of a vest over
//            two pockets is refused; one of ammo over two rig cells goes through.
//   info     the inventory screen's crafting grid shows the five info cells (raw 0..4) after a
//            full resync and after a click on them; a click on raw 3 opens the secure container;
//            the player never holds an info cell afterwards (server-side count).
//   cursor   gear left on the cursor when the inventory screen closes goes back on: the helmet taken
//            off with the backpack full, and the empty starter backpack taken off (it fits no cell);
//            nothing reaches the stash mailbox.
//   stash    three items picked with shift-click and sold with two clicks of the sell button: money
//            goes up by exactly the three sell prices shown in their lore; a single click on the
//            sell button, or plain clicks on items, never sell anything; every tab and every sort
//            shows the same items in total. A deposit that tops up a picked stack while the sale
//            is armed disarms it: the next click sells nothing, the one after sells the new amount.
//   trader   an account with no helmet, no backpack and a full stash buys a helmet and a backpack
//            from the trader: both go on (the room check knows they are worn), none is refused.
//   preset   a preset saved before the zone rules (a bandage on key 1, a rifle in a rig cell) is
//            applied: the bandage lands on a quick key and the rifle on a gun key.
//   secure   a secure record above the stack size (80 rounds, stack 60) shift-clicked into the box
//            on the search screen: the box gets one stack and the secure container keeps 20. Then
//            the secure cells count cells as the secure menu does: a lion statue (6 cells) on the
//            cursor is refused by the 3-cell container, a master chip (2 cells) goes in beside the
//            rounds with an occupied marker in its second cell, and the summary reads 3/3 格.
//   loot     two bots in one proving-ground raid share the armory weapons box and click at random
//            500 times between them (every click mode, number keys, double clicks, clicks outside):
//            the box's own cells only ever sit in its 6x6 block, no interface item reaches a carry
//            cell, and every 50 clicks, with both screens closed, the items in the box, on both
//            bots (packed contents counted), in both secure containers and on the ground nearby
//            add up to what there was before.
//   ghost    clicks sent the way the vanilla client sends them (the window's current state id, the
//            slots the client already changed itself), and after each one the window as the client
//            shows it is compared with the server's own (#160: a taken item stayed in its cell until
//            clicked again). The test armory (a left click), the stash (an item put in from below and
//            taken back), then the proving-ground armory box: a left click, a double click, a click
//            and a drag, the bot's own items shift-clicked into the box, one shift-clicked on into the
//            secure container, one put on an empty quick key with its number key, the worn helmet
//            shift-clicked into the box and worn again with a right click, and take all. Each case
//            also checks that the click did what it should (the cell emptied, the item arrived).
//
//   PORT=25565 RCON_PORT=25575 node carryui.js [gear zones rules matrix info cursor stash trader preset secure loot ghost]
//
// The server needs test.enabled (secure, loot and ghost queue for the proving ground). Bots use random
// names (new accounts with the starter kit); trader, preset and secure prepare the profile by
// editing its JSON while the bot is offline (CRAFTOV_DATA = the server's plugins/Craftov folder,
// default ../../server/run/plugins/Craftov). Prints PASS/FAIL lines and exits with the number of
// failures.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rcon = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const cmd = c => rcon(c, RCON).then(r => r.replace(/§./g, ''))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['zones', 'rules', 'matrix', 'info', 'cursor', 'stash', 'trader', 'preset', 'secure', 'loot', 'ghost']
const DATA = env.DATA
const failures = []
function check (name, ok, detail) {
  if (!ok) failures.push(name)
  log(ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : detail)
}

// ------------------------------------------------------------------ bots and items

function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n.extra)
}

function make (prefix) {
  const name = prefix + tag
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.msgs = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('新手指引')) bot.msgs.push(m) })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) bot.msgs.push(s) })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(3000); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(250)
  }
  return false
}

function openWith (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), ms)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    fn()
  })
}

const json = item => JSON.stringify(item ? item.components || '' : '')
const idOf = item => { const m = json(item).match(/"craftov:id":\{"type":"string","value":"([^"]+)"/); return m ? m[1] : null }
const uiTag = item => { const m = json(item).match(/"craftov:ui":\{"type":"string","value":"([^"]+)"/); return m ? m[1] : null }
const isTest = item => json(item).includes('"craftov:test"')
const hidesTooltip = item => /"hideTooltip":(\{"type":"byte","value":)?(1|true)/.test(json(item)) || json(item).includes('"hide_tooltip":true')
const nameOf = item => {
  const c = (item?.components || []).find(x => x.type === 'item_name' || x.type === 'custom_name')
  return c ? flat(c.data) : ''
}
const loreOf = item => {
  const c = (item?.components || []).find(x => x.type === 'lore')
  return c ? (c.data || []).map(flat) : []
}
function contentsCount (item) {
  const m = json(item).match(/"craftov:contents":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/)
  if (!m) return 0
  const count = rs => (rs || []).reduce((n, r) => n + (r.amount || 1) + count(r.contents), 0)
  try { return count(JSON.parse(JSON.parse('"' + m[1] + '"'))) } catch (e) { return 0 }
}
/** Items in a stack, packed contents included; interface stacks count nothing. */
const units = item => idOf(item) && !uiTag(item) ? item.count + contentsCount(item) : 0

/** One raw click on window `windowId`; a stale state id makes the server send the whole window again. */
function rawClick (bot, windowId, slot, button, mode) {
  bot._client.write('window_click', { windowId, stateId: -1, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined })
}
/** Lets the server answer, then pulls the real window state. */
async function settle (bot, ms = 350) {
  await sleep(ms)
  if (bot._syncWindow) await Promise.race([bot._syncWindow(bot.currentWindow || bot.inventory), sleep(1500)])
  await sleep(80)
}

/** How many of an item (or interface stacks with a tag) the server says the player holds, cursor aside. */
async function serverCount (bot, predicate, where = 'container.*') {
  const out = await cmd(`execute if items entity ${bot.username} ${where} ${predicate}`)
  const m = out.match(/(\d+)/)
  return m ? Number(m[1]) : out.includes('passed') ? 1 : 0
}

// Player inventory window: 5 head, 6 chest, 7 legs = rig, 8 feet = backpack, 9..35 main, 36..44 hotbar.
const RIG_CELLS = Array.from({ length: 9 }, (_, i) => 9 + i)
const PACK_CELLS = Array.from({ length: 18 }, (_, i) => 18 + i)
const QUICK = [39, 40, 41, 42, 43] // keys 4..8
const GUNS = [36, 37]
const where = (bot, id) => bot.inventory.slots.map((s, i) => [i, s]).filter(([i, s]) => i >= 5 && idOf(s) === id).map(([i]) => i)
const zoneOf = slot => QUICK.includes(slot) ? 'quick' : GUNS.includes(slot) ? 'gun' : RIG_CELLS.includes(slot) ? 'rig'
  : PACK_CELLS.includes(slot) ? 'pack' : 'other'

// ------------------------------------------------------------------ scenarios

async function zones () {
  const bot = await make('Cz')
  await settle(bot)
  const lockedRig = RIG_CELLS.filter(i => uiTag(bot.inventory.slots[i]) === 'locked')
  const lockedPack = PACK_CELLS.filter(i => uiTag(bot.inventory.slots[i]) === 'locked')
  const tipsRig = lockedRig.filter(i => !hidesTooltip(bot.inventory.slots[i]))
  const tipsPack = lockedPack.filter(i => !hidesTooltip(bot.inventory.slots[i]))
  check('zones: the rig has locked cells, exactly one with a tooltip', lockedRig.length > 0 && tipsRig.length === 1,
    `${lockedRig.length} locked, tooltip on ${tipsRig} "${nameOf(bot.inventory.slots[tipsRig[0]])}"`)
  check('zones: the backpack has locked cells, exactly one with a tooltip', lockedPack.length > 0 && tipsPack.length === 1,
    `${lockedPack.length} locked, tooltip on ${tipsPack} "${nameOf(bot.inventory.slots[tipsPack[0]])}"`)
  check('zones: the rig tooltip gives its capacity', /\d+\/9/.test(nameOf(bot.inventory.slots[tipsRig[0]])), nameOf(bot.inventory.slots[tipsRig[0]]))
  check('zones: the first locked cell comes right after the open ones', tipsRig[0] === lockedRig[0] && tipsPack[0] === lockedPack[0])
  const quickFree = QUICK.filter(i => !bot.inventory.slots[i])
  for (const [id, n] of [['ammo_556_m855', 30], ['gold_bar', 1], ['m4c', 1], ['bandage', 1]]) {
    await cmd(`cv give ${bot.username} ${id} ${n}`)
    await sleep(300)
  }
  await settle(bot, 600)
  const land = id => where(bot, id).map(zoneOf)
  check('zones: ammo lands on a free quick key', quickFree.length === 0 || land('ammo_556_m855').includes('quick'), land('ammo_556_m855'))
  check('zones: the gold bar lands in the backpack', land('gold_bar').length === 1 && land('gold_bar')[0] === 'pack', land('gold_bar'))
  check('zones: the rifle lands in the backpack (keys 1-2 hold the starter guns)', land('m4c').length === 1 && land('m4c')[0] === 'pack', land('m4c'))
  check('zones: the bandage stacks onto the bandage on a quick key', land('bandage').every(z => z === 'quick'), land('bandage'))
  bot.quit()
}

async function rules () {
  const bot = await make('Cr')
  await settle(bot)
  await cmd(`cv give ${bot.username} gold_bar 1`)
  await cmd(`cv give ${bot.username} m4c 1`)
  await cmd(`cv give ${bot.username} ammo_556_m855 20`)
  await settle(bot, 700)
  const gold = where(bot, 'gold_bar')[0]
  const gun = where(bot, 'm4c')[0]
  const snapshot = () => bot.inventory.slots.slice(5, 45).map(s => s ? idOf(s) + ':' + s.count : '-').join(',')
  const tryPlace = async (from, to) => {
    rawClick(bot, 0, from, 0, 0); await settle(bot)
    rawClick(bot, 0, to, 0, 0); await settle(bot)
    if (bot.inventory.selectedItem) { rawClick(bot, 0, from, 0, 0); await settle(bot) }
  }
  let before = snapshot()
  await tryPlace(gold, 36) // key 1: guns only
  check('rules: a gold bar is refused on key 1', where(bot, 'gold_bar')[0] === gold && snapshot() === before, where(bot, 'gold_bar'))
  const quickEmpty = QUICK.find(i => !bot.inventory.slots[i])
  if (quickEmpty !== undefined) {
    await tryPlace(gold, quickEmpty)
    check('rules: a gold bar may go in a pocket (#176)', where(bot, 'gold_bar')[0] === quickEmpty, where(bot, 'gold_bar'))
    await tryPlace(quickEmpty, gold) // back into the backpack
  }
  const rigFree = RIG_CELLS.find(i => !bot.inventory.slots[i])
  if (rigFree !== undefined) {
    before = snapshot()
    await tryPlace(gun, rigFree)
    check('rules: a rifle is refused in the rig', where(bot, 'm4c')[0] === gun && snapshot() === before, where(bot, 'm4c'))
  }
  if (quickEmpty !== undefined) {
    before = snapshot()
    rawClick(bot, 0, gun, quickEmpty - 36, 2); await settle(bot) // number key: the rifle onto a quick key
    check('rules: a number key cannot put a rifle on a quick key', where(bot, 'm4c')[0] === gun && snapshot() === before, where(bot, 'm4c'))
    const ammo = where(bot, 'ammo_556_m855')[0]
    if (zoneOf(ammo) !== 'quick') {
      await tryPlace(ammo, quickEmpty)
      check('rules: ammo may go on a quick key', zoneOf(where(bot, 'ammo_556_m855')[0]) === 'quick', where(bot, 'ammo_556_m855'))
    }
  }
  rawClick(bot, 0, gold, 0, 1); await settle(bot) // shift-click
  check('rules: shift-click moves a gold bar from the backpack to a pocket', where(bot, 'gold_bar').every(s => QUICK.includes(s)), where(bot, 'gold_bar'))
  bot.quit()
}

/** What each zone takes, as CarryService.refusal says: kind -> [gun key, quick key, rig, backpack]. */
const TAKES = {
  m4c: [true, false, false, true],
  ammo_556_m855: [false, true, true, true],
  bandage: [false, true, true, true],
  key_dorm: [false, true, true, true],
  gold_bar: [false, true, true, true],
  armor_1: [false, false, false, true]
}

async function matrix () {
  const bot = await make('Cm')
  await settle(bot)
  const snapshot = () => bot.inventory.slots.slice(5, 45).map(s => s ? idOf(s) + ':' + s.count : '-').join(',')
  // One of every kind given (the rifle lands in the backpack: keys 1-2 hold the starter guns), then
  // key 1 emptied (the starter K47 goes into the backpack too).
  const firstFreePack = () => PACK_CELLS.find(i => !bot.inventory.slots[i])
  for (const id of ['m4c', 'gold_bar', 'key_dorm', 'armor_1']) await cmd(`cv give ${bot.username} ${id} 1`)
  await cmd(`cv give ${bot.username} ammo_556_m855 10`)
  await settle(bot, 700)
  rawClick(bot, 0, 36, 0, 0); await settle(bot)
  rawClick(bot, 0, firstFreePack(), 0, 0); await settle(bot)
  const targets = () => [36, QUICK.find(i => !bot.inventory.slots[i]), RIG_CELLS.find(i => !bot.inventory.slots[i]), firstFreePack()]
  let wrong = 0
  let cases = 0
  for (const [id, takes] of Object.entries(TAKES)) {
    for (let zone = 0; zone < 4; zone++) {
      const to = targets()[zone]
      const from = where(bot, id).find(i => i >= 9 && i < 45 && i !== to)
      if (from === undefined || to === undefined) { log('matrix', 'no cell for', id, zone, from, to); wrong++; continue }
      for (const how of zone < 2 ? ['place', 'key'] : ['place']) {
        const before = snapshot()
        if (how === 'place') {
          rawClick(bot, 0, from, 0, 0); await settle(bot, 200)
          rawClick(bot, 0, to, 0, 0); await settle(bot, 200)
        } else {
          rawClick(bot, 0, from, to - 36, 2); await settle(bot, 200)
        }
        const moved = idOf(bot.inventory.slots[to]) === id && !bot.inventory.slots[from]
        const stayed = snapshot() === before || (bot.inventory.selectedItem && idOf(bot.inventory.selectedItem) === id)
        cases++
        if (takes[zone] ? !moved : !stayed || moved) {
          wrong++
          log('matrix', 'WRONG', id, ['gun key', 'quick key', 'rig', 'backpack'][zone], how, { from, to, moved })
        }
        // Back where it came from, for the next case.
        if (bot.inventory.selectedItem) { rawClick(bot, 0, from, 0, 0); await settle(bot, 200) }
        if (moved) {
          rawClick(bot, 0, to, 0, 0); await settle(bot, 200)
          rawClick(bot, 0, from, 0, 0); await settle(bot, 200)
        }
      }
    }
  }
  check('matrix: every kind of item goes into exactly the zones that take it', wrong === 0, `${cases} cases, ${wrong} wrong`)
  // Drags (vanilla: start, a slot each, end) over two cells.
  const drag = async (from, slots) => {
    rawClick(bot, 0, from, 0, 0); await settle(bot, 200)
    rawClick(bot, 0, -999, 0, 5)
    for (const s of slots) rawClick(bot, 0, s, 1, 5)
    rawClick(bot, 0, -999, 2, 5)
    await settle(bot, 300)
    if (bot.inventory.selectedItem) { rawClick(bot, 0, from, 0, 0); await settle(bot, 200) }
  }
  const quick = QUICK.filter(i => !bot.inventory.slots[i]).slice(0, 2)
  if (quick.length === 2) {
    await drag(where(bot, 'armor_1').find(i => i >= 9 && i < 36), quick)
    check('matrix: a vest dragged over two pockets stays off them', quick.every(i => idOf(bot.inventory.slots[i]) !== 'armor_1'), where(bot, 'armor_1'))
  }
  const rig = RIG_CELLS.filter(i => !bot.inventory.slots[i]).slice(0, 2)
  if (rig.length === 2) {
    await drag(where(bot, 'ammo_556_m855')[0], rig)
    check('matrix: ammo dragged over two rig cells is shared between them', rig.every(i => idOf(bot.inventory.slots[i]) === 'ammo_556_m855'), where(bot, 'ammo_556_m855'))
  }
  bot.quit()
}

async function info () {
  const bot = await make('Ci')
  await sleep(1500) // the cells are rebuilt every half second
  await settle(bot)
  const cells = () => [0, 1, 2, 3, 4].map(i => uiTag(bot.inventory.slots[i]))
  check('info: raw 0..4 show the info cells', cells().every(t => t === 'info'), cells())
  check('info: the load cell names the weight', /负重 .*kg/.test(nameOf(bot.inventory.slots[1])), nameOf(bot.inventory.slots[1]))
  rawClick(bot, 0, 1, 0, 0) // a click on the load cell, which the client acts out
  await settle(bot, 600)
  check('info: the cells are back after a click on one', cells().every(t => t === 'info') && !uiTag(bot.inventory.selectedItem), cells())
  const secure = await openWith(bot, () => rawClick(bot, 0, 3, 0, 0))
  check('info: a click on raw 3 opens the secure container', !!secure && flat(secure.title).includes('安全箱'), secure && flat(secure.title))
  if (secure) bot.closeWindow(secure)
  await sleep(800)
  const held = await serverCount(bot, '*[minecraft:custom_data~{PublicBukkitValues:{"craftov:ui":"info"}}]')
  check('info: the player never holds an info cell', held === 0, `server count ${held}`)
  bot.quit()
}

/** The id of what the server says the bot wears in `part` (head, chest, legs, feet), or null. */
async function wornId (bot, part) {
  const out = await dataOf(bot, `equipment.${part}.components."minecraft:custom_data".PublicBukkitValues."craftov:id"`)
  return out ? out.replace(/"/g, '') : null
}

async function cursor () {
  const bot = await make('Cc')
  await settle(bot)
  const helmet = await wornId(bot, 'head')
  // The starter backpack (pack_1) is empty: fill its cells with vests (armour goes only there, the
  // vest slot is taken), so the helmet has no cell to go to once it is off.
  const freePack = () => PACK_CELLS.filter(i => !bot.inventory.slots[i])
  for (let n = freePack().length; n > 0; n--) await cmd(`cv give ${bot.username} armor_1 1`)
  await settle(bot, 700)
  check('cursor: the backpack is full of vests', freePack().length === 0 && where(bot, 'armor_1').length > 0, where(bot, 'armor_1'))
  bot.msgs.length = 0
  rawClick(bot, 0, 5, 0, 0) // the helmet onto the cursor
  await settle(bot)
  const onCursor = idOf(bot.inventory.selectedItem)
  bot._client.write('close_window', { windowId: 0 })
  bot.inventory.selectedItem = null
  await sleep(1000)
  const back = await wornId(bot, 'head')
  check('cursor: a helmet left in hand with the backpack full goes back on', !!helmet && onCursor === helmet && back === helmet,
    { worn: helmet, cursor: onCursor, after: back })
  // Make room again, then take the (empty) backpack off onto the cursor.
  await cmd(`clear ${bot.username} *[minecraft:custom_data~{PublicBukkitValues:{"craftov:id":"armor_1"}}]`)
  await settle(bot, 600)
  const pack = await wornId(bot, 'feet')
  rawClick(bot, 0, 8, 0, 0)
  await settle(bot)
  const packOnCursor = idOf(bot.inventory.selectedItem)
  bot._client.write('close_window', { windowId: 0 })
  bot.inventory.selectedItem = null
  await sleep(1000)
  const packBack = await wornId(bot, 'feet')
  check('cursor: an empty backpack left in hand goes back on', !!pack && packOnCursor === pack && packBack === pack,
    { worn: pack, cursor: packOnCursor, after: packBack })
  const mailed = bot.msgs.filter(m => m.includes('仓库邮箱'))
  check('cursor: nothing went to the stash mailbox', mailed.length === 0, mailed)
  bot.quit()
}

/**
 * Gear with no rig or backpack (#184): Q on the worn vest in the lobby leaves it on (it used to go
 * to gun key 1), and a shift-click on it puts it into the stash since it fits nowhere on the player.
 * Pockets (#176): a gold bar given then lands in a pocket, a second vest never does (it is mailed).
 */
async function gear () {
  const bot = await make('Cg')
  await settle(bot)
  await cmd(`item replace entity ${bot.username} armor.legs with air`)
  await cmd(`item replace entity ${bot.username} armor.feet with air`)
  bot._client.write('close_window', { windowId: 0 })
  await sleep(600)
  if (!await wornId(bot, 'chest')) await cmd(`cv give ${bot.username} armor_1 1`)
  await settle(bot, 600)
  const vest = await wornId(bot, 'chest')
  const key1 = idOf(bot.inventory.slots[36])
  rawClick(bot, 0, 6, 0, 4) // Q on the worn vest
  await settle(bot)
  check('gear: Q on the worn vest in the lobby leaves it on', !!vest && await wornId(bot, 'chest') === vest
    && idOf(bot.inventory.slots[36]) === key1, { vest, key1, after: idOf(bot.inventory.slots[36]) })
  await cmd(`cv give ${bot.username} gold_bar 1`)
  await cmd(`cv give ${bot.username} armor_1 1`)
  await settle(bot, 600)
  check('gear: with no rig or backpack a gold bar lands in a pocket', where(bot, 'gold_bar').length === 1
    && QUICK.includes(where(bot, 'gold_bar')[0]), where(bot, 'gold_bar'))
  check('gear: a second vest never goes into a pocket', bot.inventory.slots.filter((s, i) => i !== 6 && idOf(s) === 'armor_1').length === 0,
    where(bot, 'armor_1'))
  bot.msgs.length = 0
  rawClick(bot, 0, 6, 0, 1) // shift-click: no backpack, so into the stash
  await settle(bot, 600)
  const onBot = bot.inventory.slots.filter(s => idOf(s) === vest).length
  const worn = await wornId(bot, 'chest')
  bot._client.write('close_window', { windowId: 0 })
  await leave(bot)
  const stashed = (readProfile(bot.username).stash || []).filter(r => r.id === vest).length
  check('gear: shift-click on the worn vest with no backpack puts it in the stash', !worn && onBot === 0 && stashed === 1, { worn, onBot, stashed, msgs: bot.msgs.slice(-3) })
}

async function stash () {
  const bot = await make('Cs')
  await settle(bot)
  for (const id of ['laptop', 'gold_bar', 'circuit', 'screws']) await cmd(`cv give ${bot.username} ${id} 1`)
  await sleep(800)
  const w = await openWith(bot, () => bot.chat('/cv stash'))
  if (!w) return check('stash: opens', false)
  // The stash retitles itself in place, which mineflayer takes for a new window: always read the current one.
  const win = () => bot.currentWindow || w
  // Deposit what was just given (the backpack row).
  for (let i = w.inventoryStart; i < w.slots.length; i++) {
    if (['laptop', 'gold_bar', 'circuit', 'screws'].includes(idOf(win().slots[i]))) { await bot.clickWindow(i, 0, 0); await sleep(300) }
  }
  await settle(bot)
  const items = () => win().slots.slice(9, 45).map((s, i) => [i + 9, s]).filter(([, s]) => idOf(s))
  const total = () => items().length
  const balance = () => Number((nameOf(win().slots[49]) + loreOf(win().slots[49]).join(' ')).match(/块币 ◈([\d,]+)/)?.[1].replace(/,/g, '') || -1)
  const startMoney = balance()
  const all = total()
  // A plain left click on the sell button with nothing picked, then plain clicks on items: never a sale.
  await bot.clickWindow(48, 0, 0); await settle(bot)
  await bot.clickWindow(48, 0, 0); await settle(bot)
  check('stash: the sell button sells nothing with nothing picked', balance() === startMoney, `${startMoney} -> ${balance()}`)
  // Pick three with shift-click.
  const pick = items().slice(0, 3)
  let expected = 0
  for (const [slot, s] of pick) {
    expected += Number((loreOf(s).find(l => l.includes('商人收购价')) || '').replace(/[^\d]/g, '') || 0)
    await bot.clickWindow(slot, 0, 1); await settle(bot)
  }
  const picked = items().filter(([, s]) => loreOf(s).some(l => l.includes('已选中'))).length
  check('stash: shift-click picks items', picked === 3, `${picked} picked`)
  await bot.clickWindow(48, 0, 0); await settle(bot)
  check('stash: one click on sell only arms it', balance() === startMoney && total() === all, `${balance()} items ${total()}`)
  await bot.clickWindow(48, 0, 0); await settle(bot, 600)
  const gained = balance() - startMoney
  check('stash: the second click sells exactly the three picked', gained === expected && total() === all - 3, `gained ${gained}, expected ${expected}, items ${all} -> ${total()}`)
  // Every tab and sort shows the same items in total.
  const left = total()
  let sum = 0
  for (let tab = 1; tab <= 8; tab++) { await bot.clickWindow(tab, 0, 0); await settle(bot, 250); sum += total() }
  await bot.clickWindow(0, 0, 0); await settle(bot)
  const counts = []
  for (let s = 0; s < 4; s++) { await bot.clickWindow(46, 0, 0); await settle(bot, 250); counts.push(total()) }
  check('stash: the tabs together show every item once', sum === left, `tabs ${sum}, all ${left}`)
  check('stash: every sort shows the same items', counts.every(c => c === left), counts)
  // An armed sale, then a deposit that tops up the picked stack: the next click only arms it again.
  const bottomOf = id => { for (let i = win().inventoryStart; i < win().slots.length; i++) if (idOf(win().slots[i]) === id) return i; return -1 }
  const stashOf = id => items().filter(([, s]) => idOf(s) === id)
  const priceOf = s => Number((loreOf(s).find(l => l.includes('商人收购价')) || '').replace(/[^\d]/g, '') || 0)
  await cmd(`cv give ${bot.username} screws 1`); await settle(bot, 600)
  await bot.clickWindow(bottomOf('screws'), 0, 0); await settle(bot)
  const [[screwSlot]] = stashOf('screws').length ? stashOf('screws') : [[-1]]
  if (screwSlot < 0) return check('stash: a screw box is deposited', false)
  await bot.clickWindow(screwSlot, 0, 1); await settle(bot)
  const money0 = balance()
  await bot.clickWindow(48, 0, 0); await settle(bot) // armed: one screw box
  await cmd(`cv give ${bot.username} screws 2`); await settle(bot, 600)
  await bot.clickWindow(bottomOf('screws'), 0, 0); await settle(bot) // tops the picked record up to 3
  const merged = stashOf('screws')
  check('stash: the deposit tops up the picked stack', merged.length === 1 && merged[0][1].count === 3, merged.map(([i, s]) => `${i}:${s.count}`))
  await bot.clickWindow(48, 0, 0); await settle(bot)
  check('stash: a click after the deposit only arms the sale again', balance() === money0 && stashOf('screws').length === 1,
    `${money0} -> ${balance()}, screws ${stashOf('screws').length}`)
  const price3 = priceOf(stashOf('screws')[0]?.[1])
  await bot.clickWindow(48, 0, 0); await settle(bot, 600)
  check('stash: the next click sells the stack as it now is', balance() - money0 === price3 && price3 > 0 && stashOf('screws').length === 0,
    `gained ${balance() - money0}, shown ${price3}`)
  bot.closeWindow(win())
  bot.quit()
}

// ------------------------------------------------------------------ prepared profiles

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(DATA, 'data/players', offlineUuid(name) + '.json')
const readProfile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const rec = (id, extra = {}) => Object.assign({ id, amount: 1 }, extra)

/** Logs off and waits until the profile has been written. */
async function leave (bot) {
  const file = profileFile(bot.username)
  const before = fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0
  bot.quit()
  await waitFor(() => fs.existsSync(file) && fs.statSync(file).mtimeMs > before, 8000)
  await sleep(500)
}

/** A new account that joins once, is edited offline by `mutate` and joins again. */
async function prepared (prefix, mutate) {
  const first = await make(prefix)
  await leave(first)
  const p = readProfile(first.username)
  mutate(p)
  fs.writeFileSync(profileFile(first.username), JSON.stringify(p, null, 2))
  return make(prefix)
}

async function trader () {
  const bot = await prepared('Ct', p => {
    p.loadout = { 37: rec('rig_1') }
    p.stash = []
    p.stashCapacity = 0
    p.money = 100000
  })
  await settle(bot)
  let w = await openWith(bot, () => bot.chat('/cv trader'))
  if (!w) return check('trader: opens', false)
  const buy = async (tab, id) => {
    await bot.clickWindow(tab, 0, 0); await settle(bot, 400)
    w = bot.currentWindow || w
    const slot = w.slots.slice(0, 45).findIndex(s => idOf(s) === id)
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await settle(bot, 500) }
    return slot
  }
  bot.msgs.length = 0
  const helmetSlot = await buy(48, 'helmet_1')
  const packSlot = await buy(49, 'pack_1')
  bot.closeWindow(bot.currentWindow || w)
  await sleep(600)
  const head = await wornId(bot, 'head')
  const feet = await wornId(bot, 'feet')
  const refused = bot.msgs.filter(m => m.includes('放不下'))
  check('trader: a helmet bought with a full stash and no backpack goes on', helmetSlot >= 0 && head === 'helmet_1', { helmetSlot, head })
  check('trader: so does a backpack', packSlot >= 0 && feet === 'pack_1', { packSlot, feet })
  check('trader: neither purchase is refused', refused.length === 0, refused)
  bot.quit()
}

async function preset () {
  const bot = await prepared('Cp', p => {
    p.loadout = { 36: rec('pack_1'), 37: rec('rig_1') }
    p.stash = [rec('bandage'), rec('m4c', { uid: crypto.randomUUID(), ammo: 0 })]
    p.presets = [{ name: 'old', slots: { 0: 'bandage', 9: 'm4c', 36: 'pack_1', 37: 'rig_1' }, amounts: { 0: 1, 9: 1, 36: 1, 37: 1 } }]
    p.money = 0 // everything comes from the stash
  })
  await settle(bot)
  const menu = await openWith(bot, () => bot.chat('/cv'))
  const presets = await openWith(bot, () => bot.clickWindow(21, 0, 0))
  if (!presets) return check('preset: the preset screen opens', false)
  await bot.clickWindow(10, 0, 0) // apply it
  await sleep(800)
  bot.closeWindow(bot.currentWindow || presets || menu)
  await sleep(400)
  // A click outside the inventory with a stale state id: the server sends all of it again (the
  // menus changed it while open, and mineflayer does not mirror that into bot.inventory).
  rawClick(bot, 0, -999, 0, 0)
  await settle(bot, 800)
  const bandage = where(bot, 'bandage').map(zoneOf)
  const rifle = where(bot, 'm4c').map(zoneOf)
  check('preset: the bandage saved on key 1 lands on a quick key', bandage.length === 1 && bandage[0] === 'quick', bandage)
  check('preset: the rifle saved in the rig lands on a gun key', rifle.length === 1 && rifle[0] === 'gun', rifle)
  bot.quit()
}

async function secure () {
  const bot = await prepared('Cq', p => { p.secure = [rec('ammo_556_m855', { amount: 80 })] })
  bot.chat('/cv queue proving NORMAL SOLO')
  const inRaid = () => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
  if (!await waitFor(inRaid, 150000)) return check('secure: in a raid', false, bot.worldName)
  await sleep(6000)
  const w = await openBox(bot)
  if (!w) return check('secure: the armory box opens', false)
  await waitFor(() => BOX_CELLS.every(raw => !json(w.slots[raw]).includes('search_')), 90000)
  await settle(bot)
  const ammo = cells => cells.reduce((n, raw) => n + (idOf(w.slots[raw]) === 'ammo_556_m855' ? w.slots[raw].count : 0), 0)
  const before = { box: ammo(BOX_CELLS), secure: ammo(SECURE) }
  const cell = SECURE.find(raw => idOf(w.slots[raw]) === 'ammo_556_m855')
  if (cell === undefined) return check('secure: the record shows in a secure cell', false)
  rawClick(bot, w.id, cell, 0, 1) // shift-click: into the box
  await settle(bot, 600)
  const after = { box: ammo(BOX_CELLS), secure: ammo(SECURE) }
  check('secure: shift-click moves one stack into the box and keeps the rest', after.box - before.box === 60 && after.secure === 20,
    { before, after })
  // The secure cells here count cells as the secure menu does (#37): the 20 rounds left take one of
  // the three, a lion statue (6 cells) is refused, a master chip (2) goes in with an occupied marker
  // in its second cell.
  await cmd(`cv give ${bot.username} lion_statue`)
  await cmd(`cv give ${bot.username} master_chip`)
  await settle(bot, 600)
  const win = () => bot.currentWindow || w
  const own = id => win().slots.findIndex((s, i) => i >= 54 && idOf(s) === id)
  const cells = () => SECURE.map(raw => {
    const s = win().slots[raw]
    return !s ? '-' : idOf(s) ? `${idOf(s)}:${s.count}` : json(s).includes('secure_used') ? '=' : json(s).includes('locked_secure') ? '#' : '?'
  })
  const statueAt = own('lion_statue')
  const chipAt = own('master_chip')
  if (statueAt < 0 || chipAt < 0) {
    check('secure: the bot was given a lion statue and a master chip', false, { statueAt, chipAt })
  } else {
    rawClick(bot, win().id, statueAt, 0, 0) // onto the cursor
    await settle(bot)
    const from = bot.msgs.length
    rawClick(bot, win().id, SECURE[1], 0, 0) // onto a free secure cell
    await settle(bot, 600)
    check('secure: a lion statue (6 cells) does not go into the 3-cell container, and the bar says why',
      !cells().some(c => c.startsWith('lion_statue')) && bot.msgs.slice(from).some(m => m.includes('占 6 格')),
      { cells: cells(), msgs: bot.msgs.slice(from) })
    rawClick(bot, win().id, statueAt, 0, 0) // back where it was
    await settle(bot)
    rawClick(bot, win().id, chipAt, 0, 0)
    await settle(bot)
    rawClick(bot, win().id, SECURE[1], 0, 0)
    await settle(bot, 600)
    check('secure: a master chip goes in and takes two cells, the second an occupied marker',
      cells().join(' ') === 'ammo_556_m855:20 master_chip:1 = # # # # # #', cells())
    check('secure: the summary counts cells', nameOf(win().slots[44]).includes('3/3 格'), nameOf(win().slots[44]))
  }
  bot.closeWindow(bot.currentWindow || w)
  await cmd(`execute as ${bot.username} at @s run tp @s 106.5 65 8.5`)
  await waitFor(() => !inRaid(), 40000)
  bot.quit()
}

// ------------------------------------------------------------------ loot fuzz

const GEAR = [6, 15, 24, 33]
const BOX_CELLS = Array.from({ length: 36 }, (_, k) => Math.floor(k / 6) * 9 + k % 6)
const SECURE = [7, 8, 16, 17, 25, 26, 34, 35, 43]

/** Units in the box (its cells), in the bot's secure container and the bot's carried items (not the mirrors). */
function tally (bot, w) {
  const box = BOX_CELLS.reduce((n, raw) => n + units(w.slots[raw]), 0)
  const secure = SECURE.reduce((n, raw) => n + units(w.slots[raw]), 0)
  return { box, secure }
}
/** `data get entity` of one value of the bot's, or null when there is none. */
async function dataOf (bot, path) {
  const out = await cmd(`data get entity ${bot.username} ${path}`)
  const marker = 'has the following entity data: '
  return out.includes(marker) ? out.split(marker)[1].trim() : null
}

/**
 * What the server says the bot carries, in units: every Craftov stack (interface stacks have no item
 * id) in the carry cells and the worn slots, slot by slot (a whole inventory comes back abridged).
 * Read with the screens closed, when nothing is on the cursor; packed contents only ever travel on
 * the cursor or in the box.
 */
async function carried (bot) {
  const id = '.components."minecraft:custom_data".PublicBukkitValues."craftov:id"'
  const paths = []
  for (let slot = 0; slot < 36; slot++) paths.push(`Inventory[{Slot:${slot}b}]`)
  for (const part of ['head', 'chest', 'legs', 'feet']) paths.push(`equipment.${part}`)
  let n = 0
  for (const path of paths) {
    if (await dataOf(bot, path + id) === null) continue
    n += Number((await dataOf(bot, path + '.count')) || 1)
  }
  return n
}

function ground (bot) {
  return Object.values(bot.entities).filter(e => e.name === 'item' && e.position.distanceTo(bot.entity.position) <= 12)
    .map(e => e.getDroppedItem()).filter(Boolean).reduce((n, it) => n + units(it), 0)
}

async function openBox (bot) {
  await cmd(`execute as ${bot.username} at @s run tp @s 8.5 65 78.9 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  return openWith(bot, () => bot.activateEntity(target))
}

async function loot () {
  const a = await make('La')
  const b = await make('Lb')
  for (const bot of [a, b]) {
    for (const id of ['bandage', 'screws', 'circuit', 'gold_bar']) await cmd(`cv give ${bot.username} ${id} 1`)
  }
  a.chat('/cv queue proving NORMAL SOLO')
  b.chat('/cv queue proving NORMAL SOLO')
  const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
  if (!await waitFor(() => inRaid(a) && inRaid(b), 150000)) return check('loot: both bots in a raid', false, [a.worldName, b.worldName])
  check('loot: both bots in the same raid', a.worldName === b.worldName, [a.worldName, b.worldName])
  await sleep(6000)
  let wa = await openBox(a)
  let wb = await openBox(b)
  if (!wa || !wb) return check('loot: the armory box opens for both', false)
  check('loot: the search screen is six rows', wa.slots.length - 36 === 54, wa.slots.length)
  await waitFor(() => BOX_CELLS.every(raw => !json(wa.slots[raw]).includes('search_')), 90000)
  await settle(a); await settle(b)
  const measure = async () => {
    // Both screens closed: the cursors are settled; then the box and the secure containers are read again.
    a.closeWindow(wa); b.closeWindow(wb)
    // Closing drops the client's cursor (the server puts it back on the player, CarryListener).
    a.inventory.selectedItem = null
    b.inventory.selectedItem = null
    await sleep(1200)
    wa = await openBox(a); wb = await openBox(b)
    if (!wa || !wb) return null
    await settle(a); await settle(b)
    const ta = tally(a, wa)
    const tb = tally(b, wb)
    return { box: ta.box, boxB: tb.box, a: await carried(a) + ta.secure, b: await carried(b) + tb.secure, ground: Math.max(ground(a), ground(b)) }
  }
  const start = await measure()
  if (!start) return check('loot: the box reopens', false)
  const sum = m => m.box + m.a + m.b + m.ground
  log('loot', 'start', start)
  let bad = 0
  let leaks = 0
  const misplaced = []
  for (let i = 0; i < 500; i++) {
    const bot = i % 2 ? b : a
    const w = i % 2 ? wb : wa
    const mode = [0, 0, 0, 1, 1, 2, 3, 4, 6][Math.floor(Math.random() * 9)]
    const slot = Math.random() < 0.03 ? -999 : Math.floor(Math.random() * (w.slots.length))
    const button = mode === 2 ? Math.floor(Math.random() * 9) : mode === 4 ? Math.floor(Math.random() * 2) : Math.floor(Math.random() * 2)
    rawClick(bot, w.id, slot, mode === 6 ? 0 : button, mode)
    await settle(bot, 120)
    // The box's own cells never leave its 6x6 block: columns 6-8 hold only gear, secure cells and buttons.
    for (let raw = 0; raw < 54; raw++) {
      const s = w.slots[raw]
      if (raw % 9 >= 6 && s && idOf(s) && !GEAR.includes(raw) && !SECURE.includes(raw)) misplaced.push(raw)
    }
    for (let k = w.inventoryStart; k < w.slots.length; k++) {
      const tagged = uiTag(w.slots[k])
      if (tagged && tagged !== 'locked' && !(k === w.inventoryStart + 35 && tagged === 'lobby_menu') && tagged !== 'raid_terminal') leaks++
    }
    if ((i + 1) % 50 === 0) {
      const m = await measure()
      if (!m) { bad++; break }
      const ok = sum(m) === sum(start) && m.box === m.boxB
      if (!ok) bad++
      log('loot', `after ${i + 1} clicks`, m, ok ? 'ok' : `MISMATCH ${sum(m)} vs ${sum(start)}`)
    }
  }
  check('loot: 500 random clicks never create or destroy an item', bad === 0, `${bad} bad checkpoints`)
  check('loot: the box only ever shows in its 6x6 block', misplaced.length === 0, misplaced.slice(0, 10))
  check('loot: no interface item reaches a carry cell', leaks === 0, `${leaks} sightings`)
  for (const bot of [a, b]) {
    bot.closeWindow(bot.currentWindow || wa)
    await cmd(`execute as ${bot.username} at @s run tp @s 106.5 65 8.5`)
  }
  await waitFor(() => !inRaid(a) && !inRaid(b), 40000)
  a.quit(); b.quit()
}

// ------------------------------------------------------------------ ghosts (#160)

/** Keeps the state id of every window as the client does: the last one the server sent for it. */
function trackStates (bot) {
  bot.stateIds = {}
  const note = p => { bot.stateIds[p.windowId] = p.stateId }
  bot._client.on('window_items', note)
  bot._client.on('set_slot', note)
}

/**
 * A click on the open window sent the way the vanilla client sends it: the slots in `emptied` are
 * emptied on the client first (its own prediction of the click) and reported so, with the window's
 * current state id. The server then corrects only the slots it holds differently from what the
 * client reported, so one it fails to correct stays on screen as it is. (rawClick's stale state id
 * makes the server send the whole window back, which would hide exactly that.) Only empty slots are
 * reported: an item would need its component hashes.
 */
function clientClick (bot, slot, button, mode, emptied = []) {
  const w = bot.currentWindow
  for (const s of emptied) w.updateSlot(s, null)
  bot._client.write('window_click', {
    windowId: w.id,
    stateId: bot.stateIds[w.id] ?? 0,
    slot,
    mouseButton: button,
    mode,
    changedSlots: emptied.map(location => ({ location, item: undefined })),
    cursorItem: undefined
  })
}

const shownAs = s => s ? `${idOf(s) || uiTag(s) || s.name}:${s.count}` : '-'

/**
 * Lets the server answer a click, reads the top of the window as the client now shows it, then
 * pulls the server's own state (a full resync) and names every slot where the two differ. Returns
 * what the server holds, slot by slot. The window is read anew each time: a new title reopens it.
 * The search screen's load and value cells (52, 53) are left out: they follow the carried weight,
 * which the server itself only works out again every second, so they may change in between.
 */
async function sameAsServer (bot, label) {
  await sleep(500)
  const shown = bot.currentWindow.slots.slice(0, 54).map(shownAs)
  await settle(bot)
  const real = bot.currentWindow.slots.slice(0, 54).map(shownAs)
  const live = bot.currentWindow.slots.length === 90 && json(bot.currentWindow.slots[52]).includes('info_weight') ? [52, 53] : []
  const ghosts = []
  shown.forEach((s, raw) => { if (s !== real[raw] && !live.includes(raw)) ghosts.push(`${raw} shows ${s}, holds ${real[raw]}`) })
  check(`ghost: ${label}: the screen shows what the server holds`, ghosts.length === 0, ghosts.join('; '))
  return real
}

async function ghost () {
  const bot = await make('Cg')
  trackStates(bot)
  await cmd(`cv give ${bot.username} circuit 1`)
  await cmd(`cv give ${bot.username} key_dorm 1`)
  await settle(bot, 600)
  const own = id => bot.currentWindow.slots.findIndex((s, i) => i >= bot.currentWindow.inventoryStart && idOf(s) === id)

  // The test armory: a left click hands out a copy and the item stays on show.
  if (!await openWith(bot, () => bot.chat('/cv items'))) return check('ghost: the test armory opens', false)
  await settle(bot)
  clientClick(bot, 4, 0, 0, [4]) // the medical tab
  await sameAsServer(bot, 'the test armory, a tab')
  const sample = shownAs(bot.currentWindow.slots[9])
  clientClick(bot, 9, 0, 0, [9])
  let real = await sameAsServer(bot, 'the test armory, a left click on an item')
  check('ghost: the test armory keeps showing the item handed out', real[9] === sample, { sample, now: real[9] })
  bot.closeWindow(bot.currentWindow)
  await sleep(600)

  // The stash: an item put in from below (a new title reopens the screen), then taken back out.
  if (!await openWith(bot, () => bot.chat('/cv stash'))) return check('ghost: the stash opens', false)
  await settle(bot)
  const ammo = own('ammo_9_fmj')
  if (ammo < 0) {
    check('ghost: the starter kit has 9 mm rounds to put into the stash', false)
  } else {
    clientClick(bot, ammo, 0, 0, [ammo])
    real = await sameAsServer(bot, 'the stash, an item put in')
    const cell = real.findIndex((s, raw) => raw >= 9 && raw < 45 && s.startsWith('ammo_9_fmj:'))
    check('ghost: the rounds went into the stash', cell >= 0 && own('ammo_9_fmj') < 0, cell)
    if (cell >= 0) {
      clientClick(bot, cell, 0, 0, [cell])
      await sameAsServer(bot, 'the stash, an item taken out')
      check('ghost: the rounds came back out of the stash', own('ammo_9_fmj') >= 0)
    }
  }
  bot.closeWindow(bot.currentWindow)
  await sleep(600)

  // The search screen: the proving-ground armory box (guns only), the bot's own items put in.
  bot.chat('/cv queue proving NORMAL SOLO')
  const inRaid = () => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
  if (!await waitFor(inRaid, 150000)) return check('ghost: in a raid', false, bot.worldName)
  await sleep(6000)
  const w = await openBox(bot)
  if (!w) return check('ghost: the armory box opens', false)
  // An item revealed between a click's "shown" snapshot and the resync would read as a ghost.
  if (!await waitFor(() => BOX_CELLS.every(raw => !json(w.slots[raw]).includes('search_')), 90000)) {
    return check('ghost: the box finished searching', false)
  }
  await settle(bot)
  const boxCell = pred => BOX_CELLS.find(raw => pred(bot.currentWindow.slots[raw]))
  const inBox = (real, id) => BOX_CELLS.some(raw => real[raw].startsWith(id + ':'))
  const took = (label, raw, real, before) => check(`ghost: ${label}: the cell is empty`, real[raw] === '-', { raw, before, now: real[raw] })

  let raw = boxCell(s => idOf(s))
  let before = shownAs(bot.currentWindow.slots[raw])
  clientClick(bot, raw, 0, 0, [raw])
  took('a left click', raw, await sameAsServer(bot, 'a left click takes an item'), before)

  raw = boxCell(s => idOf(s))
  before = shownAs(bot.currentWindow.slots[raw])
  clientClick(bot, raw, 0, 0, [raw])
  await sleep(150) // the second half of a double click comes once the first has been answered
  clientClick(bot, raw, 0, 6, [])
  took('a double click', raw, await sameAsServer(bot, 'a double click takes an item'), before)


  for (const id of ['circuit', 'key_dorm']) {
    const at = own(id)
    if (at < 0) { check(`ghost: the bot carries its ${id}`, false); continue }
    clientClick(bot, at, 0, 1, [at])
    real = await sameAsServer(bot, `the ${id} shift-clicked into the box`)
    check(`ghost: the ${id} is in the box`, inBox(real, id) && own(id) < 0)
  }

  raw = boxCell(s => idOf(s) === 'circuit')
  if (raw !== undefined) {
    clientClick(bot, raw, 0, 1, [raw])
    real = await sameAsServer(bot, 'a shift-click puts an item into the secure container')
    took('a shift-click into the secure container', raw, real, 'circuit')
    check('ghost: the circuit is in the secure container', SECURE.some(c => real[c].startsWith('circuit:')), SECURE.map(c => real[c]))
  }

  raw = boxCell(s => idOf(s) === 'key_dorm')
  const key = [3, 4, 5, 6, 7].find(n => !bot.currentWindow.slots[bot.currentWindow.inventoryStart + 27 + n])
  if (raw !== undefined && key !== undefined) {
    clientClick(bot, raw, key, 2, [raw])
    real = await sameAsServer(bot, `number key ${key + 1} puts an item on its empty quick key`)
    took('a number key', raw, real, 'key_dorm')
    check('ghost: the key is on that quick key', idOf(bot.currentWindow.slots[bot.currentWindow.inventoryStart + 27 + key]) === 'key_dorm')
  } else {
    check('ghost: a free quick key for the number-key case', false, { raw, key })
  }

  const helmet = idOf(bot.currentWindow.slots[GEAR[0]])
  if (!helmet) {
    check('ghost: the starter kit wears a helmet', false)
  } else {
    clientClick(bot, GEAR[0], 0, 1, [GEAR[0]])
    real = await sameAsServer(bot, 'the worn helmet shift-clicked into the box')
    took('the worn helmet shift-clicked', GEAR[0], real, helmet)
    check('ghost: the helmet is in the box', inBox(real, helmet))
    raw = boxCell(s => idOf(s) === helmet)
    if (raw !== undefined) {
      clientClick(bot, raw, 1, 0, [raw])
      real = await sameAsServer(bot, 'a right click wears a helmet')
      took('a right click on a helmet', raw, real, helmet)
      check('ghost: the helmet is worn again', real[GEAR[0]].startsWith(helmet + ':'), real[GEAR[0]])
    }
  }

  const inside = () => BOX_CELLS.filter(c => idOf(bot.currentWindow.slots[c])).length
  const left = inside()
  clientClick(bot, 42, 0, 0, [42])
  await sameAsServer(bot, 'take all')
  check('ghost: take all took something', inside() < left, { before: left, after: inside() })

  bot.closeWindow(bot.currentWindow || w)
  await cmd(`execute as ${bot.username} at @s run tp @s 106.5 65 8.5`)
  await waitFor(() => !inRaid(), 40000)
  bot.quit()
}

;(async () => {
  const all = { gear, zones, rules, matrix, info, cursor, stash, trader, preset, secure, loot, ghost }
  for (const name of scenarios) {
    try {
      await all[name]()
    } catch (e) {
      check(name + ': ran', false, e.stack)
    }
  }
  log('done', failures.length ? 'FAILED ' + failures.join(', ') : 'all passed')
  process.exit(failures.length)
})()
