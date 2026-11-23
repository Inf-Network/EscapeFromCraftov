// Rig / backpack slot races (issue #17). Two clicks on the rig or backpack slot that the server
// handles in the same tick must never duplicate or delete the container or what is packed in it,
// Q on a worn rig or backpack only throws it while the player is alive in a raid, and an item with
// no room on the player goes to the stash mailbox in the lobby but still drops in a raid.
//
// The clicks are written as raw packets back to back, so the server reads them in one tick. The
// raid part brings a second bot as a squadmate, because a solo player is never downed. Closing the
// inventory, clicking outside it or leaving the game with a packed container on the cursor and no
// free cell must not delete it in the lobby or while downed, and a shift-click on the slot still
// puts it on. Both bots get random names (Cr#####, Cm#####): turn the whitelist off or add them
// first.
//
//   PORT=25565 RCON_PORT=25575 node carry.js        # all checks
//   CHECKS=lobby-q,raid-dupe node carry.js          # a subset
//
// Prints one PASS / FAIL line per check and exits with the number of failures.
const mineflayer = require('mineflayer')
const rcon = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const MAP = process.env.MAP || 'proving'
const ONLY = process.env.CHECKS ? process.env.CHECKS.split(',') : null

const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const cmd = c => rcon(c, RCON).then(r => r.replace(/§./g, ''))

// Player inventory window: 7 legs = rig, 8 feet = backpack, 9..35 main rows, 36..44 hotbar (= inventory 0..8).
const RIG = 7
const PACK = 8
const MELEE = 38
const POCKETS = [36, 37, 39, 40, 41, 42, 43] // hotbar minus the melee slot and the terminal (44)
// Keys 4-8 take medkits; keys 1-2 take guns only and a rig or backpack goes into a backpack cell (#10).
const QUICK = [39, 40, 41, 42, 43]
const RIG_CELLS = range(9, 9)
const PACK_CELLS = range(18, 18)
const OUTSIDE = -999

function range (start, n) { return Array.from({ length: n }, (_, i) => start + i) }

const tag = Math.floor(Math.random() * 90000 + 10000)
const name = 'Cr' + tag
const chat = []
let inserted = false

function join (username, verbose) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username, version: '1.21.11', auth: 'offline' })
  b._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) b._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  b.on('kicked', r => log(username, 'KICKED', r))
  b.on('error', e => log(username, 'ERROR', e.message))
  if (verbose) {
    b.on('messagestr', m => { if (m.trim() && !m.includes('新手指引')) { chat.push(m); log('CHAT', m) } })
    b.on('title', t => { if (JSON.stringify(t).includes('行动开始')) inserted = true })
  }
  return new Promise(resolve => b.once('spawn', () => resolve(b)))
}

let bot
let mate = null // squadmate in the raid, so the player can be downed

// ------------------------------------------------------------------ reading items

function pdc (item) {
  const data = (item?.components || []).find(c => c.type === 'custom_data')?.data
  return data?.value?.PublicBukkitValues?.value || {}
}
const idOf = item => (item && pdc(item)['craftov:id']?.value) || null
function packed (records) {
  return (records || []).reduce((n, r) => n + (r.amount || 1) + packed(r.contents), 0)
}
const contentsOf = item => { const raw = item && pdc(item)['craftov:contents']?.value; return raw ? packed(JSON.parse(raw)) : 0 }
/** Items in a stack, counting everything packed inside a rig or backpack. UI placeholders count 0. */
const total = item => idOf(item) ? item.count + contentsOf(item) : 0
const at = slot => idOf(bot.inventory.slots[slot])
const cursor = () => idOf(bot.inventory.selectedItem)

function carried () {
  let n = total(bot.inventory.selectedItem)
  for (let i = 5; i <= 45; i++) n += total(bot.inventory.slots[i])
  return n
}
function droppedNearby () {
  return Object.values(bot.entities)
    .filter(e => e.name === 'item' && e.position.distanceTo(bot.entity.position) <= 10)
    .map(e => e.getDroppedItem()).filter(Boolean)
}
function ground () {
  const items = droppedNearby()
  return { n: items.reduce((n, it) => n + total(it), 0), ids: items.map(it => idOf(it) + (contentsOf(it) ? `[${contentsOf(it)}]` : '')) }
}
/** Everything the player owns right here: carried, on the cursor and lying on the ground nearby. */
const world = () => carried() + ground().n
function count (id) {
  let n = cursor() === id ? 1 : 0
  for (let i = 5; i <= 45; i++) if (at(i) === id) n++
  return n + droppedNearby().filter(it => idOf(it) === id).length
}
function describe () {
  return `rig=${at(RIG)}(${contentsOf(bot.inventory.slots[RIG])}) pack=${at(PACK)} cursor=${cursor()}(${contentsOf(bot.inventory.selectedItem)})` +
    ` rigCells=${RIG_CELLS.filter(at).length} packCells=${PACK_CELLS.filter(at).length}` +
    ` carried=${carried()} ground=${JSON.stringify(ground().ids)}`
}

// ------------------------------------------------------------------ clicking

/** One raw click on the player's own inventory. A stale stateId makes the server resend the whole window. */
function click (slot, button = 0, mode = 0) {
  bot._client.write('window_click', { windowId: 0, stateId: -1, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined })
}
/** Closes the player's own inventory screen the way the vanilla client does; its cursor is gone after that. */
function closeInv () {
  bot._client.write('close_window', { windowId: 0 })
  bot.inventory.selectedItem = null
}
/** Lets the server finish anything it does a tick later, then pulls the real window state. */
async function settle (ms = 400) {
  await sleep(ms)
  if (bot._syncWindow) await Promise.race([bot._syncWindow(bot.inventory), sleep(2000)])
  await sleep(100)
}

/** Number of items waiting in the stash mailbox, read off the stash menu's mailbox button. */
async function mailbox () {
  const w = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    bot.once('windowOpen', win => { clearTimeout(timer); resolve(win) })
    bot.chat('/cv stash')
  })
  if (!w) return null
  const name = (w.slots[50]?.components || []).find(c => c.type === 'item_name' || c.type === 'custom_name')
  // The name is "邮箱 " with the count as a child component; the child may come first in the JSON.
  const texts = (JSON.stringify(name || '').match(/"text":\{"type":"string","value":"[^"]*"/g) || []).map(t => t.split('"value":"')[1].slice(0, -1))
  bot.closeWindow(w)
  await sleep(300)
  const m = texts.join('').includes('邮箱') && texts.map(t => t.match(/(\d+)\s*$/)).find(Boolean)
  return m ? Number(m[1]) : null
}

const results = []
function check (label, ok, detail) {
  results.push({ label, ok })
  log(ok ? 'PASS' : 'FAIL', label, '|', detail)
}

// ------------------------------------------------------------------ kit

/** A free cell for what is on the cursor: a quick key for a medkit, a backpack cell for a rig or backpack. */
const emptyPocket = () => [...QUICK, ...PACK_CELLS].find(s => !bot.inventory.slots[s])

/** Frees a pocket by deleting a filler medkit, so a container given next is not dropped on the floor. */
async function freePocket () {
  if (emptyPocket() !== undefined) return
  const filler = PACK_CELLS.find(s => at(s) === 'medkit_s')
  if (filler !== undefined) {
    await cmd(`item replace entity ${name} container.${filler} with minecraft:air`)
    await settle()
  }
}

/** Wears `id` in `slot`, giving a new one when it went missing, and puts anything on the cursor away. */
async function wear (slot, id) {
  if (cursor()) {
    if (cursor() !== id || at(slot)) await freePocket()
    const target = cursor() === id && !at(slot) ? slot : emptyPocket()
    if (target !== undefined) { click(target); await settle() }
  }
  if (at(slot) === id) return
  let from = range(9, 36).find(s => at(s) === id)
  if (from === undefined) {
    await freePocket()
    await cmd(`cv give ${name} ${id}`)
    await settle()
    from = range(9, 36).find(s => at(s) === id)
  }
  if (from === undefined) return
  click(from); await settle() // onto the cursor
  click(slot); await settle() // on; whatever was worn comes back on the cursor
  if (cursor()) { await freePocket(); const p = emptyPocket(); if (p !== undefined) { click(p); await settle() } }
}

/** A worn rig_3 and pack_3 with every cell full, so a duplicate or a loss shows up in the totals. */
async function kit () {
  await wear(RIG, 'rig_3')
  await wear(PACK, 'pack_3')
  const empty = [...QUICK, ...RIG_CELLS, ...PACK_CELLS].filter(s => !bot.inventory.slots[s]).length
  for (let i = 0; i < empty; i++) await cmd(`cv give ${name} medkit_s`)
  await settle()
  log('kit', describe())
}

// ------------------------------------------------------------------ checks

const checks = [
  ['lobby-double', 'lobby: two clicks on the worn backpack in one tick keep it and its contents', async () => {
    const before = world()
    const packs = count('pack_3')
    click(PACK); click(PACK)
    await settle()
    check('lobby-double', world() === before && count('pack_3') === packs && (at(PACK) === 'pack_3' || cursor() === 'pack_3'),
      `before=${before} after=${world()} ${describe()}`)
  }],
  ['lobby-doubleclick', 'lobby: a vanilla double click (pick up + collect) on the worn backpack in one tick keeps it', async () => {
    const before = world()
    const packs = count('pack_3')
    click(PACK, 0, 0); click(PACK, 0, 6)
    await settle()
    check('lobby-doubleclick', world() === before && count('pack_3') === packs && (at(PACK) === 'pack_3' || cursor() === 'pack_3'),
      `before=${before} after=${world()} ${describe()}`)
  }],
  ['lobby-rig-twice', 'lobby: a full rig on the cursor clicked twice onto the empty rig slot in one tick is not unpacked twice', async () => {
    click(RIG); await settle()
    const onCursor = cursor()
    const before = world()
    const rigs = count('rig_3')
    click(RIG); click(RIG)
    await settle()
    check('lobby-rig-twice', onCursor === 'rig_3' && world() === before && count('rig_3') === rigs,
      `cursorBefore=${onCursor} before=${before} after=${world()} ${describe()}`)
  }],
  ['lobby-q', 'lobby: Q on the worn backpack keeps it on and drops nothing', async () => {
    const ready = at(PACK) === 'pack_3' && cursor() === null
    const before = world()
    const groundBefore = ground().n
    chat.length = 0
    click(PACK, 0, 4)
    await settle()
    check('lobby-q', ready && at(PACK) === 'pack_3' && ground().n === groundBefore && world() === before,
      `ready=${ready} before=${before} after=${world()} ${describe()} chat=${JSON.stringify(chat.slice(-2))}`)
  }],
  ['lobby-overflow', 'lobby: an item with no room left on the player goes to the stash mailbox, not the floor', async () => {
    const mailBefore = await mailbox()
    const groundBefore = ground().n
    const carriedBefore = carried()
    chat.length = 0
    await cmd(`cv give ${name} medkit_s`)
    await settle()
    const mailAfter = await mailbox()
    check('lobby-overflow', mailBefore !== null && mailAfter === mailBefore + 1 && ground().n === groundBefore && carried() === carriedBefore,
      `mailbox ${mailBefore} -> ${mailAfter}, ground ${groundBefore} -> ${ground().n}, carried ${carriedBefore} -> ${carried()} chat=${JSON.stringify(chat.slice(-2))}`)
  }],
  ['lobby-close', 'lobby: closing the inventory with the full backpack on the cursor and no free cell puts it back on', async () => {
    const mailBefore = await mailbox()
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    closeInv()
    await settle()
    const mailAfter = await mailbox()
    check('lobby-close', onCursor === 'pack_3' && at(PACK) === 'pack_3' && world() === before && mailAfter === mailBefore,
      `cursorBefore=${onCursor} before=${before} after=${world()} mailbox ${mailBefore} -> ${mailAfter} ${describe()}`)
  }],
  ['lobby-close-item', 'lobby: closing the inventory with an item on the cursor and no free cell mails it', async () => {
    const mailBefore = await mailbox()
    const pocket = POCKETS.find(s => at(s) === 'medkit_s')
    click(pocket); await settle()
    const onCursor = cursor()
    await cmd(`cv give ${name} medkit_s`) // fills the pocket the medkit came from
    await settle()
    const before = world()
    const groundBefore = ground().n
    closeInv()
    await settle()
    const mailAfter = await mailbox()
    check('lobby-close-item', onCursor === 'medkit_s' && mailAfter === mailBefore + 1 && world() === before - 1 && ground().n === groundBefore,
      `cursorBefore=${onCursor} before=${before} after=${world()} mailbox ${mailBefore} -> ${mailAfter} ${describe()}`)
  }],
  ['lobby-outside', 'lobby: a click outside the window with the full backpack on the cursor keeps it on the cursor', async () => {
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    click(OUTSIDE)
    await settle()
    const held = cursor()
    const after = world()
    click(PACK); await settle() // back on
    check('lobby-outside', onCursor === 'pack_3' && held === 'pack_3' && after === before && at(PACK) === 'pack_3' && world() === before,
      `cursorBefore=${onCursor} cursorAfter=${held} before=${before} after=${after} ${describe()}`)
  }],
  ['lobby-shift-wear', 'lobby: shift-click on the backpack slot with the backpack on the cursor puts it back on', async () => {
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    click(PACK, 0, 1)
    await settle()
    check('lobby-shift-wear', onCursor === 'pack_3' && at(PACK) === 'pack_3' && cursor() === null && world() === before,
      `cursorBefore=${onCursor} before=${before} after=${world()} ${describe()}`)
  }],
  ['lobby-shift-blocked', 'lobby: with the full rig on the cursor, shift-clicking another rig on is refused and nothing is mailed', async () => {
    // A spare rig fits in a backpack cell only (#10 zone rules), so it is the rig that comes off.
    await freePocket()
    await cmd(`cv give ${name} rig_2`)
    await settle()
    const spare = PACK_CELLS.find(s => at(s) === 'rig_2')
    const mailBefore = await mailbox()
    click(RIG); await settle()
    const before = world()
    chat.length = 0
    click(spare, 0, 1)
    await settle()
    const refused = at(spare) === 'rig_2' && at(RIG) === null && cursor() === 'rig_3'
    closeInv()
    await settle()
    const mailAfter = await mailbox()
    check('lobby-shift-blocked', spare !== undefined && refused && at(RIG) === 'rig_3' && at(spare) === 'rig_2' && world() === before && mailAfter === mailBefore,
      `spare=${spare} refused=${refused} before=${before} after=${world()} mailbox ${mailBefore} -> ${mailAfter} ${describe()} chat=${JSON.stringify(chat.slice(-2))}`)
  }],
  ['lobby-quit', 'lobby: leaving with the full backpack on the cursor keeps it for the next login', async () => {
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    bot.quit()
    await sleep(2000)
    bot = await join(name, true)
    await sleep(3000)
    await settle()
    check('lobby-quit', onCursor === 'pack_3' && at(PACK) === 'pack_3' && world() === before,
      `cursorBefore=${onCursor} before=${before} after=${world()} ${describe()}`)
  }],
  ['raid-dupe','raid: full rig on the cursor, rig slot click + click outside the window in one tick leaves one rig', async () => {
    click(RIG); await settle()
    const onCursor = cursor()
    const before = world()
    const rigs = count('rig_3')
    click(RIG); click(OUTSIDE)
    await settle(1500)
    check('raid-dupe', onCursor === 'rig_3' && world() === before && count('rig_3') === rigs,
      `cursorBefore=${onCursor} before=${before} after=${world()} ${describe()}`)
  }],
  ['raid-overflow', 'raid: an item with no room left on the player still drops at their feet', async () => {
    // The squadmate stands close by and may pick the drop up, so its medkits count as dropped too.
    const mateMedkits = () => mate ? mate.inventory.slots.filter(it => idOf(it) === 'medkit_s').reduce((n, it) => n + it.count, 0) : 0
    const droppedBefore = droppedNearby().filter(it => idOf(it) === 'medkit_s').length + mateMedkits()
    const carriedBefore = carried()
    await cmd(`cv give ${name} medkit_s`)
    await settle()
    const droppedAfter = droppedNearby().filter(it => idOf(it) === 'medkit_s').length + mateMedkits()
    check('raid-overflow', droppedAfter === droppedBefore + 1 && carried() === carriedBefore,
      `dropped (ground + picked up by the mate) ${droppedBefore} -> ${droppedAfter}, carried ${carriedBefore} -> ${carried()}`)
    await cmd(`execute as ${name} at @s run kill @e[type=item,distance=..3]`)
  }],
  ['raid-close-gun', 'raid: alive, closing the inventory with a gun on the cursor and no free cell keeps the gun', async () => {
    // The weapon code takes a gun thrown without a click for a reload and refuses the throw. The mate
    // may pick a dropped gun up, and it carries a starter gun of its own, so its guns count too.
    const mateGuns = () => mate ? mate.inventory.slots.filter(it => idOf(it) === 'p18').length : 0
    await freePocket()
    await cmd(`cv give ${name} p18`)
    await settle()
    const pocket = POCKETS.find(s => at(s) === 'p18')
    click(pocket); await settle()
    const onCursor = cursor()
    await cmd(`cv give ${name} medkit_s`) // fills the pocket the gun came from
    await settle()
    const before = count('p18') + mateGuns()
    closeInv()
    await settle()
    const after = count('p18') + mateGuns()
    check('raid-close-gun', onCursor === 'p18' && after === before,
      `cursorBefore=${onCursor} guns ${before} -> ${after} ${describe()}`)
    await cmd(`execute as ${name} at @s run kill @e[type=item,distance=..3]`)
  }],
  ['raid-q','raid: alive, Q on the worn backpack throws it with its contents', async () => {
    const before = world()
    click(PACK, 0, 4)
    await settle(300)
    check('raid-q', at(PACK) === null && ground().ids.includes('pack_3[18]') && world() === before,
      `before=${before} after=${world()} ${describe()}`)
    // Put it back on for the next check: a pickup needs one free vanilla slot, so clear a pocket first.
    await freePocket()
    await cmd(`execute as ${name} at @s run tp @s @e[type=item,sort=nearest,limit=1,nbt={Item:{components:{"minecraft:item_model":"craftov:pack_3"}}}]`)
    await settle(1500)
  }],
  ['raid-downed-q', 'raid: downed, Q on the worn backpack keeps it on and drops nothing', async () => {
    await cmd(`damage ${name} 500 minecraft:generic`)
    await settle(1500)
    const ready = at(PACK) === 'pack_3' && cursor() === null && bot.health > 0
    const before = world()
    const groundBefore = ground().n
    chat.length = 0
    click(PACK, 0, 4)
    await settle()
    check('raid-downed-q', ready && at(PACK) === 'pack_3' && ground().n === groundBefore && world() === before,
      `ready=${ready} hp=${bot.health} before=${before} after=${world()} ${describe()} chat=${JSON.stringify(chat.slice(-2))}`)
  }],
  ['raid-downed-close', 'raid: downed, closing the inventory with the full backpack on the cursor and no free cell puts it back on', async () => {
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    const groundBefore = ground().n
    closeInv()
    await settle()
    check('raid-downed-close', onCursor === 'pack_3' && at(PACK) === 'pack_3' && ground().n === groundBefore && world() === before,
      `cursorBefore=${onCursor} hp=${bot.health} before=${before} after=${world()} ${describe()}`)
  }],
  ['raid-downed-outside', 'raid: downed, a click outside the window with the full backpack on the cursor throws nothing', async () => {
    click(PACK); await settle()
    const onCursor = cursor()
    const before = world()
    const groundBefore = ground().n
    click(OUTSIDE)
    await settle()
    const held = cursor()
    const groundAfter = ground().n
    click(PACK); await settle() // back on
    check('raid-downed-outside', onCursor === 'pack_3' && held === 'pack_3' && groundAfter === groundBefore && at(PACK) === 'pack_3' && world() === before,
      `cursorBefore=${onCursor} cursorAfter=${held} hp=${bot.health} before=${before} after=${world()} ${describe()}`)
  }]
]

const want = key => !ONLY || ONLY.includes(key)

;(async () => {
  try {
    bot = await join(name, true)
    await sleep(3000)
    log('joined as', name)
    // Start from nothing: the starter rig holds ammo and could not be swapped out in the lobby.
    await cmd(`clear ${name}`)
    for (let i = 0; i < 12 && at(MELEE) !== 'knife_starter'; i++) await settle(500) // the lobby hands the knife back
    // The terminal only comes back when the lobby is entered again. A stand-in keeps hotbar slot 9
    // taken as it is in play, or vanilla would quietly put a returned cursor there.
    await cmd(`item replace entity ${name} hotbar.8 with minecraft:barrier`)
    for (const [key, label, run] of checks.filter(c => c[0].startsWith('lobby-') && want(c[0]))) {
      log('--', label)
      await kit()
      await run()
    }
    const raidChecks = checks.filter(c => c[0].startsWith('raid-') && want(c[0]))
    if (raidChecks.length) {
      await kit()
      mate = await join('Cm' + tag, false)
      await sleep(2500)
      bot.chat('/cv party invite Cm' + tag); await sleep(800)
      mate.chat('/cv party accept'); await sleep(800)
      bot.chat(`/cv queue ${MAP} NORMAL TRIO`)
      const deadline = Date.now() + 120000
      while (!inserted && Date.now() < deadline) await sleep(500)
      log('inserted', inserted)
      await sleep(2000)
      for (const [, label, run] of raidChecks) {
        log('--', label)
        await kit()
        await run()
      }
      await cmd('cv end')
    }
  } catch (e) {
    log('ERROR', e.stack)
    results.push({ label: 'script', ok: false })
  }
  const failed = results.filter(r => !r.ok).length
  log(`${results.length - failed}/${results.length} passed`)
  if (bot) bot.quit()
  if (mate) mate.quit()
  setTimeout(() => process.exit(failed), 500)
})()
