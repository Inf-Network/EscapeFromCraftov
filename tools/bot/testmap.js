// Proving-ground access and armory items with headless bots.
//
//   node testmap.js                  every scenario
//   node testmap.js gate squad       only these
//
// Env: PORT (25565), RCON_PORT (25575), RCON_PASSWORD (craftov-local), and CRAFTOV_RUN, the
// server's run folder (../../server/run), whose plugins/Craftov/config.yml is edited to switch
// test.enabled per scenario (and insurance.loss-chance / return-minutes to 0 for insured; restored
// at the end) and whose profiles are read for the armory check.
// The bots are never op unless a scenario says so. Run `gate` once more after a server restart:
// the deploy screen must still default to a map that is not a test map.
//
//   gate    test mode off: no proving ground in the deploy screen, Rust Port selected, /cv queue
//           proving refused; an admin can still queue for it.
//   last    test mode on: the proving ground is listed but not preselected; picked and queued once,
//           it is remembered, and dropped again (Rust Port) once test mode is off.
//   squad   test mode off: an admin leader cannot take an ordinary teammate into the proving ground.
//   recheck test mode switched off while queued: the entry is cancelled at launch.
//   ammo    test mode on: cycling the ammo type (sneak+Q) on the bot's own gun never loads armory
//           rounds; on an armory gun it loads armory rounds and gives its old rounds back as armory
//           rounds, never as the bot's own.
//   armory  test mode on: armory items carry the test mark, and after extracting with collectibles,
//           valuables, a gun and an armory backpack: money, stash and collected unchanged, profit ~0,
//           no test item left, the bot's own item packed in the armory backpack handed back.
//   parts   test mode on: the armory weapons case holds every attachment, marked; an armory sight
//           is refused on the bot's own gun, fits the armory gun, and comes off as an armory part.
//   keys    test mode on: with its own key and an armory copy for the same door, opening the door
//           wears the armory copy and leaves the bot's own key alone.
//   contract a saved daily contract for the proving ground (test mode off) is swapped for another
//           one when the contracts are read, for free; the other two contracts stay.
//   cursor  test mode on: the bot packs its own bandage into an armory backpack, takes its own pack_1
//           onto the cursor and clicks the backpack slot, so pack_1 is worn and the armory backpack,
//           packed, is left on the cursor. It extracts like that: the bandage must come home (in the
//           inventory or the mailbox), the armory backpack must be taken with the usual notice and
//           no test item may be left.
//   insured test mode on, insurance on: the bot packs its own insured K47 into an armory backpack,
//           throws the backpack with Q and dies. When the raid closes the K47 must reach the mailbox
//           as it was insured (with its rounds), although the backpack around it is an armory item.
//   offline test mode on, two bots in a raid, each wearing an armory backpack whose cells hold own
//           items given in the raid. A disconnects and rejoins (the armory backpack comes back as it
//           was), disconnects again, and the server is stopped with B still online. The raid is void
//           for both (#67): after the restart each loadout is slot for slot the one it went in with
//           (no test item, none of the items packed in the raid), nothing reached the mailbox, and
//           nothing is dropped when A logs in. The
//           server is brought back by server.js: a restart loop around start.sh gets 15 s
//           (LOOP_WAIT) to start it, otherwise SERVER_DIR/start.sh is run detached (NO_START=1 only
//           waits, up to 4 min).
//
// Each scenario prints PASS or FAIL with what the bots saw; the exit code is the number of failures.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const pinInsurance = require('./insurance')
const env = require('./env')
const server = require('./server')

const PORT = env.PORT
const RCON = env.RCON
const RUN = env.SERVER_DIR
const CONFIG = path.join(RUN, 'plugins/Craftov/config.yml')
const PLAYERS = path.join(RUN, 'plugins/Craftov/data/players')
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const bots = []

function make (reuse) {
  const name = reuse || `t${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'
const saw = (bot, text) => bot.chatLog.some(m => m.includes(text))

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(250) }
  return false
}

/** Plain text of every text value in a component tree (item names, lore). */
const plain = j => [...JSON.stringify(j || '').matchAll(/"text":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('')
const component = (item, type) => ((item && item.components) || []).find(c => c.type === type)
const itemName = item => plain(component(item, 'item_name') || component(item, 'custom_name'))
const lore = item => plain(component(item, 'lore'))
const isTest = item => !!item && JSON.stringify(item.components || '').includes('craftov:test')
const pdc = (item, key) => { const m = JSON.stringify(item && item.components || '').match(new RegExp(`"craftov:${key}":\\{"type":"\\w+","value":"?([^",}]+)`)); return m ? m[1] : null }
const itemId = item => pdc(item, 'id')
/** Rounds of one ammo type the bot carries, armory stacks or its own. */
const rounds = (bot, id, test) => bot.inventory.slots.filter(s => s && itemId(s) === id && isTest(s) === test).reduce((n, s) => n + s.count, 0)
const dropKey = bot => bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })

function openWindow (bot, fn, ms = 5000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), ms)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    fn()
  })
}

async function testMode (on) {
  const text = fs.readFileSync(CONFIG, 'utf8')
  fs.writeFileSync(CONFIG, text.replace(/^(test:\n(?:[ \t]*#.*\n)*[ \t]*enabled:) *\w+/m, `$1 ${on}`))
  await rcon('cv reload')
}

function offlineUuid (name) {
  const md5 = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  md5[6] = (md5[6] & 0x0f) | 0x30
  md5[8] = (md5[8] & 0x3f) | 0x80
  const h = md5.toString('hex')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

const profileFile = bot => path.join(PLAYERS, offlineUuid(bot.username) + '.json')
const profile = bot => JSON.parse(fs.readFileSync(profileFile(bot), 'utf8'))

/** Opens the armory container at x,y,z (the bot stands just south of it, looking north). */
async function openContainerAt (bot, x, y, z) {
  await rcon(`execute as ${bot.username} at @s run tp @s ${x} ${y} ${z + 1.4} 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return openWindow(bot, () => bot.activateEntity(target))
}

/** The container's own cells of a search screen: its 6x6 block (#10); the right-hand columns are the bot's gear. */
const top = w => w.slots.slice(0, w.inventoryStart).map((s, i) => [i, s]).filter(([i, s]) => s && i % 9 < 6)
/** Map buttons of the deploy screen (slots 10-16): [slot, name]; the selected one is marked ▶. */
const mapButtons = menu => [10, 11, 12, 13, 14, 15, 16].map(i => [i, itemName(menu.slots[i])]).filter(([, n]) => n)
const selectedMap = menu => (mapButtons(menu).find(([, n]) => n.includes('▶')) || [null, ''])[1]
/** Revealed items of a loot window: [slot, item], search placeholders left out. */
const revealed = w => top(w).filter(([, s]) => !JSON.stringify(s.components || '').includes('search_'))

/** Waits until the container has revealed at least `count` items (search time grows with rarity). */
async function searched (w, count, ms = 60000) {
  return waitFor(() => revealed(w).length >= count, ms)
}

/** Today's contract day as the server counts it (04:00 local rollover), in epoch days. */
function contractDay () {
  const d = new Date(Date.now() - 4 * 3600e3)
  return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000)
}

const POCKETS = [36, 37, 39, 40, 41, 42, 43] // hotbar 0, 1, 3..7 (2 is the melee slot)
const PACK_CELLS = Array.from({ length: 18 }, (_, i) => 18 + i)
const GIFTS = ['screws', 'tape', 'battery', 'lighter', 'canned_food', 'copper_coil', 'water_tabs', 'circuit', 'toolset',
  'cigarettes', 'coffee', 'energy_drink', 'multimeter', 'ration', 'ssd', 'radio']
const ownIn = (bot, slots) => slots.map(i => bot.inventory.slots[i]).filter(s => s && !isTest(s)).map(itemId).filter(Boolean)
/** How many of an item the bot carries of its own (armory stacks left out). */
const ownCount = (bot, id) => bot.inventory.slots.filter(s => s && itemId(s) === id && !isTest(s)).reduce((n, s) => n + s.count, 0)
/** One raw click on the bot's own inventory (mode 4 = Q), as the vanilla client sends it. */
const rawClick = (bot, slot, button, mode) =>
  bot._client.write('window_click', { windowId: 0, stateId: -1, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined })

/** Opens the armory carry box and right-clicks the biggest armory backpack on; true if it is worn. */
async function wearArmoryPack (bot) {
  const w = await openContainerAt(bot, 47.5, 65, 77.5)
  if (!w) return false
  await waitFor(() => revealed(w).length === top(w).length, 60000) // backpacks come last
  const packs = revealed(w).filter(([, s]) => /pack_/.test(itemId(s) || '') && isTest(s))
  const [bigSlot] = packs[packs.length - 1] || []
  if (bigSlot !== undefined) {
    await bot.clickWindow(bigSlot, 1, 0) // right-click: equip (the bot's own pack goes into the box)
    await sleep(800)
  }
  bot.__box = w
  return isTest(bot.inventory.slots[8]) && /pack_/.test(itemId(bot.inventory.slots[8]) || '')
}

/** Queues a squad of solo bots for the proving ground and waits until all are in the raid. */
async function deploy (list) {
  for (const bot of list) bot.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => list.every(inRaid), 120000)
  await sleep(6000) // insertion camera and spawn protection
  return ok
}

/**
 * Equips the biggest armory backpack, then has own items (valuables, which go to the backpack first,
 * #10) given until at least `cells` of them sit in its cells. Returns the own item ids in the cells.
 */
async function packOwnItems (bot, cells) {
  const w = await openContainerAt(bot, 47.5, 65, 77.5)
  if (!w) return null
  await waitFor(() => revealed(w).length === top(w).length, 60000)
  // The biggest armory pack still in the box (bots share it; an own pack swapped in is not one).
  const packs = revealed(w).filter(([, s]) => /pack_/.test(itemId(s) || '') && isTest(s))
  const [bigSlot] = packs[packs.length - 1] || []
  if (bigSlot === undefined) return null
  await bot.clickWindow(bigSlot, 1, 0) // right-click: equip (the bot's own pack stays in the box)
  await sleep(800)
  bot.closeWindow(w)
  await sleep(500)
  if (!isTest(bot.inventory.slots[8])) return null
  for (const id of GIFTS) {
    if (ownIn(bot, PACK_CELLS).length >= cells) break
    await rcon(`cv give ${bot.username} ${id}`)
    await sleep(300)
  }
  return ownIn(bot, PACK_CELLS)
}

/**
 * Stops the server with A offline and B online, and waits for it to come back: a restart loop gets a
 * few seconds to start it, otherwise SERVER_DIR/start.sh is run here (server.js).
 */
function restart () {
  return server.restart({ log: (...a) => console.log('offline:', ...a) })
}

const scenarios = {
  async gate () {
    await testMode(false)
    const a = await make()
    const menu = await openWindow(a, () => a.chat('/cv deploy'))
    if (!menu) return [false, { error: 'deploy screen did not open' }]
    const maps = mapButtons(menu).map(([, n]) => n)
    const selected = selectedMap(menu)
    a.closeWindow(menu)
    a.chat('/cv queue proving NORMAL SOLO')
    await sleep(1500)
    const refused = saw(a, '只在测试模式开放') && !saw(a, '开始匹配')
    a.chat('/cv leave')
    const admin = await make()
    await rcon(`op ${admin.username}`)
    await sleep(500)
    admin.chat('/cv queue proving NORMAL SOLO')
    await sleep(1500)
    const adminQueued = saw(admin, '开始匹配')
    admin.chat('/cv leave')
    await rcon(`deop ${admin.username}`)
    const ok = maps.length > 0 && !maps.some(n => n.includes('试验场')) && selected.includes('锈港') && refused && adminQueued
    return [ok, { maps, selected, refused, adminQueued, chat: a.chatLog.filter(m => m.includes('测试模式') || m.includes('开始匹配')) }]
  },

  async last () {
    await testMode(true)
    const a = await make()
    let menu = await openWindow(a, () => a.chat('/cv deploy'))
    if (!menu) return [false, { error: 'deploy screen did not open' }]
    const proving = mapButtons(menu).find(([, n]) => n.includes('试验场'))
    const firstPick = selectedMap(menu)
    if (!proving) return [false, { error: 'proving ground not listed in test mode', maps: mapButtons(menu) }]
    const testLore = lore(menu.slots[proving[0]]).includes('测试图')
    await a.clickWindow(proving[0], 0, 0)
    await sleep(600)
    const picked = selectedMap(menu)
    await a.clickWindow(40, 0, 0) // start matchmaking: the choice is remembered
    await sleep(1200)
    const queued = saw(a, '开始匹配')
    a.chat('/cv leave')
    await sleep(800)
    menu = await openWindow(a, () => a.chat('/cv deploy'))
    const remembered = menu ? selectedMap(menu) : ''
    if (menu) a.closeWindow(menu)
    await testMode(false)
    await sleep(500)
    menu = await openWindow(a, () => a.chat('/cv deploy'))
    const after = menu ? mapButtons(menu).map(([, n]) => n) : []
    const afterPick = menu ? selectedMap(menu) : ''
    if (menu) a.closeWindow(menu)
    const ok = firstPick.includes('锈港') && testLore && picked.includes('试验场') && queued && remembered.includes('试验场') &&
      after.length > 0 && !after.some(n => n.includes('试验场')) && afterPick.includes('锈港')
    return [ok, { firstPick, testLore, picked, queued, remembered, after, afterPick }]
  },

  async squad () {
    await testMode(false)
    const leader = await make()
    const mate = await make()
    await rcon(`op ${leader.username}`)
    await sleep(500)
    leader.chat(`/cv party invite ${mate.username}`)
    await sleep(700)
    mate.chat('/cv party accept')
    await sleep(700)
    leader.chat('/cv queue proving NORMAL TRIO')
    await sleep(1500)
    const refused = saw(leader, `小队成员 ${mate.username} 不能进入`) && !saw(leader, '开始匹配')
    leader.chat('/cv leave')
    mate.chat('/cv party leave')
    await rcon(`deop ${leader.username}`)
    return [refused, { refused, chat: leader.chatLog.filter(m => m.includes('测试模式') || m.includes('开始匹配')) }]
  },

  async recheck () {
    await testMode(true)
    const a = await make()
    a.chat('/cv queue proving NORMAL SOLO')
    await sleep(1500)
    const queued = saw(a, '开始匹配')
    await testMode(false)
    const cancelled = await waitFor(() => saw(a, '未能出发'), 45000)
    await sleep(3000)
    const ok = queued && cancelled && inLobby(a) && saw(a, '只在测试模式开放')
    return [ok, { queued, cancelled, world: a.worldName, chat: a.chatLog.slice(-2) }]
  },

  async ammo () {
    await testMode(true)
    const a = await make()
    a.chat('/cv queue proving NORMAL SOLO')
    if (!await waitFor(() => inRaid(a), 120000)) return [false, { error: 'never reached the raid', world: a.worldName }]
    await sleep(6000)
    // Keys 1-2 take guns only (#10): the pistol goes into the backpack, so the armory K47 lands on key 2.
    const freeCell = [18, 19, 20, 21, 22, 23].find(i => !a.inventory.slots[i])
    if (freeCell !== undefined && itemId(a.inventory.slots[37]) === 'p18') {
      await a.clickWindow(37, 0, 0); await sleep(300)
      await a.clickWindow(freeCell, 0, 0); await sleep(500)
    }
    // An armory K47 first (it lands on the free gun key), then armory 7.62x39 rounds.
    let w = await openContainerAt(a, 8.5, 65, 77.5)
    if (!w) return [false, { error: 'armory weapons box did not open' }]
    await waitFor(() => revealed(w).some(([, s]) => itemId(s) === 'k47'), 60000)
    const k47 = revealed(w).find(([, s]) => itemId(s) === 'k47')
    if (!k47) return [false, { error: 'no armory K47 revealed' }]
    await a.clickWindow(k47[0], 0, 0)
    await sleep(500)
    a.closeWindow(w)
    await sleep(500)
    w = await openContainerAt(a, 21.5, 65, 77.5)
    if (!w) return [false, { error: 'armory ammo box did not open' }]
    await waitFor(() => revealed(w).some(([, s]) => itemId(s) === 'ammo_762_ap'), 60000)
    for (const [slot, s] of revealed(w)) {
      if (/^ammo_762_/.test(itemId(s) || '')) { await a.clickWindow(slot, 0, 0); await sleep(300) }
    }
    a.closeWindow(w)
    await sleep(500)
    const hotbar = test => [36, 37, 38, 39, 40, 41, 42, 43].find(i => itemId(a.inventory.slots[i]) === 'k47' && isTest(a.inventory.slots[i]) === test)
    const ownSlot = hotbar(false)
    const testSlot = hotbar(true)
    if (ownSlot === undefined || testSlot === undefined) {
      const layout = a.inventory.slots.map((s, i) => s && `${i}:${itemId(s)}${isTest(s) ? '*' : ''}`).filter(Boolean)
      return [false, { error: 'K47s not both in the hotbar', ownSlot, testSlot, layout }]
    }
    const cycle = async slot => {
      a.setQuickBarSlot(slot - 36)
      await sleep(800)
      a.setControlState('sneak', true); await sleep(200); dropKey(a); await sleep(150); a.setControlState('sneak', false)
      await sleep(6000) // a full reload
    }
    const start = { own: rounds(a, 'ammo_762_ps', false), testPs: rounds(a, 'ammo_762_ps', true), testBp: rounds(a, 'ammo_762_bp', true) }
    await cycle(ownSlot)
    const ownGun = a.inventory.slots[ownSlot]
    const afterOwn = { gunType: pdc(ownGun, 'ammo_type'), own: rounds(a, 'ammo_762_ps', false), testBp: rounds(a, 'ammo_762_bp', true), testAp: rounds(a, 'ammo_762_ap', true) }
    await cycle(testSlot)
    // The magazine reaches the stack only when the gun could move: switching hands writes it.
    a.setQuickBarSlot(ownSlot - 36)
    await sleep(800)
    const testGun = a.inventory.slots[testSlot]
    const afterTest = { gunType: pdc(testGun, 'ammo_type'), gunTest: isTest(testGun), own: rounds(a, 'ammo_762_ps', false), testPs: rounds(a, 'ammo_762_ps', true) }
    await rcon(`execute as ${a.username} at @s run tp @s 106.5 65 8.5`)
    const home = await waitFor(() => inLobby(a), 30000)
    await sleep(4000)
    const report = home && profile(a).reports[0]
    const result = { start, afterOwn, afterTest, extracted: home, extractedValue: report && report.extractedValue, ownPsHome: rounds(a, 'ammo_762_ps', false) }
    const ok = afterOwn.gunType === 'ammo_762_ps' && afterOwn.own === start.own && afterOwn.testBp === start.testBp &&
      afterTest.gunTest && afterTest.gunType !== 'ammo_762_ps' && afterTest.own === start.own && afterTest.testPs > start.testPs &&
      home && report.extractedValue < 1000 && result.ownPsHome === start.own
    return [ok, result]
  },

  async armory () {
    await testMode(true)
    const a = await make()
    await waitFor(() => fs.existsSync(profileFile(a)), 15000)
    a.chat('/cv queue proving NORMAL SOLO')
    if (!await waitFor(() => inRaid(a), 120000)) return [false, { error: 'never reached the raid', world: a.worldName, chat: a.chatLog.slice(-3) }]
    await sleep(6000) // insertion camera and spawn protection
    const before = profile(a)
    const notes = {}

    // Armory backpack: right-click equips it; the bot's own pack (empty) comes back out of the box.
    let w = await openContainerAt(a, 47.5, 65, 77.5)
    if (!w) return [false, { error: 'armory carry box did not open' }]
    await waitFor(() => revealed(w).length === top(w).length, 60000) // backpacks come last
    const packs = revealed(w).filter(([, s]) => /pack_/.test(itemId(s) || ''))
    const [bigSlot, big] = packs[packs.length - 1] || []
    if (bigSlot === undefined) return [false, { error: 'no backpack in the armory', items: top(w).map(([, s]) => itemId(s)) }]
    notes.armoryPackMarked = isTest(big) && lore(big).includes('试验场物资')
    await a.clickWindow(bigSlot, 1, 0) // right-click: equip
    await sleep(800)
    notes.armoryPackWorn = isTest(a.inventory.slots[8]) && /pack_/.test(itemId(a.inventory.slots[8]) || '')
    const own = top(w).find(([, s]) => itemId(s) === 'pack_1' && !isTest(s))
    if (own) { await a.clickWindow(own[0], 0, 0); await sleep(600) }
    a.closeWindow(w)
    await sleep(500)
    // The bot's own bandage (hotbar) into a free cell of the armory backpack (its own pack_1 took the first).
    const bandageSlot = [36, 37, 38, 39, 40, 41, 42, 43].find(i => itemId(a.inventory.slots[i]) === 'bandage')
    const cell = PACK_CELLS.find(i => !a.inventory.slots[i])
    if (bandageSlot !== undefined && cell !== undefined) {
      await a.clickWindow(bandageSlot, 0, 0); await sleep(300)
      await a.clickWindow(cell, 0, 0); await sleep(500)
    }
    notes.bandageInArmoryPack = cell !== undefined && itemId(a.inventory.slots[cell]) === 'bandage' && !isTest(a.inventory.slots[cell])

    // Collectibles, valuables and one gun from the armory.
    for (const [x, rows, want] of [[112.5, 'collectibles', 3], [99.5, 'valuables', 4], [8.5, 'weapons', 1]]) {
      w = await openContainerAt(a, x, 65, 77.5)
      if (!w) return [false, { error: `armory ${rows} box did not open`, notes }]
      if (!await searched(w, want)) return [false, { error: `armory ${rows} box still searching`, notes, title: plain(w.title) }]
      const items = revealed(w).slice(0, want)
      notes[rows] = items.length
      notes[rows + 'Marked'] = items.every(([, s]) => isTest(s))
      for (const [slot] of items) {
        await a.clickWindow(slot, 0, 0)
        await sleep(250)
      }
      a.closeWindow(w)
      await sleep(500)
    }
    notes.carriedTest = a.inventory.slots.filter(isTest).length

    // Extract at the always-open point.
    await rcon(`execute as ${a.username} at @s run tp @s 106.5 65 8.5`)
    if (!await waitFor(() => inLobby(a), 30000)) return [false, { error: 'did not extract', notes, chat: a.chatLog.slice(-3) }]
    await sleep(4000)
    const after = profile(a)
    const report = after.reports && after.reports[0]
    const left = a.inventory.slots.filter(isTest).length
    const stashSame = JSON.stringify(after.stash) === JSON.stringify(before.stash)
    const handedBack = a.inventory.slots.some(s => s && itemId(s) === 'bandage' && !isTest(s))
    const result = {
      ...notes,
      money: [before.money, after.money],
      stashSame,
      collected: [before.collected.length, after.collected.length],
      extractedValue: report && report.extractedValue,
      testItemsLeft: left,
      notice: saw(a, '试验场物资已收回'),
      handedBack
    }
    const ok = notes.armoryPackMarked && notes.armoryPackWorn && notes.collectiblesMarked && notes.valuablesMarked && notes.weaponsMarked &&
      notes.bandageInArmoryPack && notes.carriedTest > 0 && after.money === before.money && stashSame &&
      after.collected.length === before.collected.length && report && report.extractedValue < 1000 &&
      left === 0 && result.notice && handedBack
    return [ok, result]
  },

  async parts () {
    await testMode(true)
    const a = await make()
    if (!await deploy([a])) return [false, { error: 'never reached the raid', world: a.worldName }]
    let w = await openContainerAt(a, 8.5, 65, 77.5)
    if (!w) return [false, { error: 'armory weapons box did not open' }]
    await waitFor(() => top(w).length > 0 && revealed(w).length === top(w).length, 90000)
    const parts = revealed(w).filter(([, s]) => /^(optic|att)_/.test(itemId(s) || ''))
    const notes = { title: plain(w.title), items: top(w).length, parts: parts.length, partsMarked: parts.every(([, s]) => isTest(s)) }
    for (const id of ['k47', 'optic_reddot']) {
      const found = revealed(w).find(([, s]) => itemId(s) === id)
      if (!found) return [false, { error: `no armory ${id}`, notes }]
      await a.clickWindow(found[0], 0, 0)
      await sleep(400)
    }
    a.closeWindow(w)
    await sleep(500)
    a.setQuickBarSlot(0) // the bot's own K47 is held, so the gunsmith opens on it
    await sleep(300)
    w = await openWindow(a, () => a.chat('/cv mod'))
    if (!w) return [false, { error: 'gunsmith did not open', notes }]
    const partSlot = () => [45, 46, 47, 48, 49, 50, 51, 52, 53].find(i => itemId(w.slots[i]) === 'optic_reddot' && isTest(w.slots[i]))
    let ps = partSlot()
    notes.onOwnGun = ps === undefined ? null : lore(w.slots[ps])
    const testGun = [0, 1, 2, 3, 4, 5, 6, 7, 8].find(i => itemId(w.slots[i]) === 'k47' && isTest(w.slots[i]))
    if (testGun === undefined || ps === undefined) return [false, { error: 'armory K47 or sight missing in the gunsmith', notes }]
    await a.clickWindow(testGun, 0, 0)
    await sleep(600)
    ps = partSlot()
    notes.onArmoryGun = ps === undefined ? null : lore(w.slots[ps])
    if (ps !== undefined) { await a.clickWindow(ps, 0, 0); await sleep(800) }
    const gun = a.inventory.slots.find(s => s && itemId(s) === 'k47' && isTest(s))
    notes.fitted = pdc(gun, 'optic')
    notes.fittedGunMarked = isTest(gun)
    notes.sightRowShows = itemId(w.slots[31])
    notes.sightLeftCarried = a.inventory.slots.filter(s => s && itemId(s) === 'optic_reddot').length
    await a.clickWindow(31, 0, 0) // take it off again
    await sleep(800)
    const back = a.inventory.slots.filter(s => s && itemId(s) === 'optic_reddot')
    notes.removedBackMarked = back.length === 1 && isTest(back[0])
    notes.ownGunFitted = pdc(a.inventory.slots.find(s => s && itemId(s) === 'k47' && !isTest(s)), 'optic')
    a.closeWindow(w)
    const ok = notes.parts === 10 && notes.partsMarked && (notes.onOwnGun || '').includes('试验场配件只能装在试验场武器上') &&
      (notes.onArmoryGun || '').includes('点击安装') && notes.fitted === 'optic_reddot' && notes.fittedGunMarked &&
      notes.sightRowShows === 'optic_reddot' && notes.sightLeftCarried === 0 && notes.removedBackMarked && !notes.ownGunFitted
    return [ok, notes]
  },

  async keys () {
    await testMode(true)
    const a = await make()
    if (!await deploy([a])) return [false, { error: 'never reached the raid', world: a.worldName }]
    await rcon(`cv give ${a.username} key_dorm`)
    await sleep(500)
    const w = await openContainerAt(a, 86.5, 65, 77.5)
    if (!w) return [false, { error: 'armory keys box did not open' }]
    await waitFor(() => revealed(w).some(([, s]) => itemId(s) === 'key_dorm'), 60000)
    const armoryKey = revealed(w).find(([, s]) => itemId(s) === 'key_dorm')
    if (!armoryKey) return [false, { error: 'no armory key_dorm' }]
    await a.clickWindow(armoryKey[0], 0, 0)
    await sleep(400)
    a.closeWindow(w)
    await sleep(500)
    const key = test => a.inventory.slots.find(s => s && itemId(s) === 'key_dorm' && isTest(s) === test)
    const before = { own: pdc(key(false), 'dura'), armory: pdc(key(true), 'dura'), both: !!key(false) && !!key(true) }
    const empty = [43, 42, 41].find(i => !a.inventory.slots[i])
    if (empty !== undefined) a.setQuickBarSlot(empty - 36) // an empty hand, so nothing else takes the click
    await rcon(`execute as ${a.username} at @s run tp @s 8.5 65 49.6 180 10`)
    await sleep(1500)
    const Vec3 = require('vec3')
    const door = a.blockAt(new Vec3(8, 65, 48))
    if (!door) return [false, { error: 'door block not loaded', before }]
    await a.activateBlock(door)
    await sleep(1200)
    const doorNow = a.blockAt(new Vec3(8, 65, 48))
    const after = { own: pdc(key(false), 'dura'), armory: key(true) ? pdc(key(true), 'dura') : 'used up', door: doorNow && doorNow.getProperties().open }
    const lower = v => v === null ? 3 : Number(v) // a fresh key has no wear recorded yet: full durability (3)
    const ok = before.both && after.own === before.own && after.armory !== before.armory &&
      (after.armory === 'used up' || lower(after.armory) === lower(before.armory) - 1) && after.door === true
    return [ok, { before, after }]
  },

  async contract () {
    await testMode(false)
    let a = await make()
    const name = a.username
    await waitFor(() => fs.existsSync(profileFile(a)), 15000)
    a.quit()
    bots.splice(bots.indexOf(a), 1)
    await sleep(4000)
    const p = profile(a)
    const money = p.money
    p.taskDay = contractDay()
    p.tasks = [
      { kind: 'EXTRACT_MAP', map: 'proving', mapName: '试验场', target: 1, progress: 0, reward: 12000, xp: 600, done: false },
      { kind: 'AI_KILL', target: 5, progress: 2, reward: 7500, xp: 487, done: false },
      { kind: 'SEARCH', target: 8, progress: 1, reward: 7200, xp: 480, done: false }
    ]
    fs.writeFileSync(profileFile(a), JSON.stringify(p, null, 2))
    a = await make(name)
    const menu = await openWindow(a, () => a.chat('/cv menu'))
    if (menu) a.closeWindow(menu)
    await sleep(500)
    a.quit()
    bots.splice(bots.indexOf(a), 1)
    await sleep(4000)
    const after = profile(a)
    const tasks = after.tasks.map(t => ({ kind: t.kind, map: t.map || null, progress: t.progress }))
    const ok = !!menu && after.taskDay === p.taskDay && tasks.length === 3 && !tasks.some(t => t.map === 'proving') &&
      tasks[1].kind === 'AI_KILL' && tasks[1].progress === 2 && tasks[2].kind === 'SEARCH' && tasks[2].progress === 1 &&
      after.money === money
    return [ok, { menu: !!menu, tasks, money: [money, after.money] }]
  },

  async cursor () {
    await testMode(true)
    const a = await make()
    await waitFor(() => fs.existsSync(profileFile(a)), 15000)
    const mailBefore = profile(a).mailbox.length
    if (!await deploy([a])) return [false, { error: 'never reached the raid', world: a.worldName }]
    const notes = {}
    notes.armoryPackWorn = await wearArmoryPack(a)
    const w = a.__box
    // The bot's own pack_1 comes back out of the box into a pocket.
    const own = w && top(w).find(([, s]) => itemId(s) === 'pack_1' && !isTest(s))
    if (own) { await a.clickWindow(own[0], 0, 0); await sleep(600) }
    if (w) a.closeWindow(w)
    await sleep(500)
    const bandages = ownCount(a, 'bandage')
    const bandageSlot = POCKETS.concat([38]).find(i => itemId(a.inventory.slots[i]) === 'bandage' && !isTest(a.inventory.slots[i]))
    // The own pack_1 took the first backpack cell (#10: a backpack fits nowhere else): the next free one.
    const cell = PACK_CELLS.find(i => !a.inventory.slots[i])
    if (bandageSlot !== undefined && cell !== undefined) {
      await a.clickWindow(bandageSlot, 0, 0); await sleep(300)
      await a.clickWindow(cell, 0, 0); await sleep(500)
    }
    notes.bandages = bandages
    notes.bandageInArmoryPack = cell !== undefined && itemId(a.inventory.slots[cell]) === 'bandage' && !isTest(a.inventory.slots[cell])
    const ownPack = a.inventory.slots.findIndex((s, i) => i >= 9 && s && itemId(s) === 'pack_1' && !isTest(s))
    if (ownPack < 0) return [false, { error: 'own pack_1 not carried', notes }]
    // Own pack_1 onto the cursor, then onto the backpack slot: the armory backpack, packed, takes its place on the cursor.
    await a.clickWindow(ownPack, 0, 0); await sleep(400)
    await a.clickWindow(8, 0, 0); await sleep(800)
    notes.ownPackWorn = itemId(a.inventory.slots[8]) === 'pack_1' && !isTest(a.inventory.slots[8])
    notes.cursor = itemId(a.inventory.selectedItem)
    await rcon(`execute as ${a.username} at @s run tp @s 106.5 65 8.5`)
    if (!await waitFor(() => inLobby(a), 30000)) return [false, { error: 'did not extract', notes, chat: a.chatLog.slice(-3) }]
    await sleep(4000)
    const after = profile(a)
    const mailed = after.mailbox.slice(mailBefore).filter(r => r.id === 'bandage').reduce((n, r) => n + r.amount, 0)
    const result = {
      ...notes,
      home: { carried: ownCount(a, 'bandage'), mailed },
      testItemsLeft: a.inventory.slots.filter(isTest).length,
      notice: saw(a, '试验场物资已收回'),
      packHome: itemId(a.inventory.slots[8])
    }
    const ok = notes.armoryPackWorn && notes.bandageInArmoryPack && notes.ownPackWorn && bandages > 0 &&
      result.home.carried + result.home.mailed === bandages && result.testItemsLeft === 0 && result.notice && result.packHome === 'pack_1'
    return [ok, result]
  },

  async insured () {
    await testMode(true)
    const a = await make()
    await waitFor(() => fs.existsSync(profileFile(a)), 15000)
    const menu = await openWindow(a, () => a.chat('/cv deploy'))
    if (!menu) return [false, { error: 'deploy screen did not open' }]
    await a.clickWindow(34, 0, 0) // auto insurance on
    await sleep(400)
    a.closeWindow(menu)
    const mailBefore = profile(a).mailbox.length
    if (!await deploy([a])) return [false, { error: 'never reached the raid', world: a.worldName }]
    const notes = { insured: saw(a, '件装备投保') }
    notes.armoryPackWorn = await wearArmoryPack(a)
    if (a.__box) a.closeWindow(a.__box)
    await sleep(500)
    const gunSlot = POCKETS.concat([38]).find(i => itemId(a.inventory.slots[i]) === 'k47' && !isTest(a.inventory.slots[i]))
    if (gunSlot === undefined) return [false, { error: 'own K47 not carried', notes }]
    notes.ammo = pdc(a.inventory.slots[gunSlot], 'ammo')
    await a.clickWindow(gunSlot, 0, 0); await sleep(300)
    await a.clickWindow(18, 0, 0); await sleep(500)
    notes.gunInArmoryPack = itemId(a.inventory.slots[18]) === 'k47' && !isTest(a.inventory.slots[18])
    rawClick(a, 8, 0, 4) // Q on the worn armory backpack: thrown with the K47 inside
    await sleep(1500)
    notes.thrown = Object.values(a.entities).some(e => e.name === 'item' && e.position.distanceTo(a.entity.position) < 6)
    await rcon(`damage ${a.username} 1000 minecraft:generic`)
    // The solo raid closes a few seconds after the death; the claim then mails what was insured.
    const back = await waitFor(() => profile(a).mailbox.slice(mailBefore).some(r => r.id === 'k47'), 60000)
    const gun = profile(a).mailbox.slice(mailBefore).find(r => r.id === 'k47')
    const result = { ...notes, back, gun: gun && { ammo: gun.ammo, ammoType: gun.ammoType, test: !!gun.test, insured: gun.insured || null } }
    const ok = notes.insured && notes.armoryPackWorn && notes.gunInArmoryPack && notes.thrown && back &&
      !gun.test && !gun.insured && String(gun.ammo) === String(notes.ammo)
    return [ok, result]
  },

  async offline () {
    await testMode(true)
    const a = await make()
    const b = await make()
    await waitFor(() => fs.existsSync(profileFile(a)) && fs.existsSync(profileFile(b)), 15000)
    const mailBefore = { a: profile(a).mailbox.length, b: profile(b).mailbox.length }
    if (!await deploy([a, b])) return [false, { error: 'never reached the raid', worlds: [a.worldName, b.worldName] }]
    // What each went in with, as the deploy recorded it: a raid the server stops in is void (#67).
    await waitFor(() => profile(a).deployment && profile(b).deployment, 15000)
    const went = { a: (profile(a).deployment || {}).loadout, b: (profile(b).deployment || {}).loadout }
    const cellsB = await packOwnItems(b, 3)
    const cellsA = await packOwnItems(a, 3)
    if (!cellsA || !cellsB || cellsA.length < 3 || cellsB.length < 3) return [false, { error: 'could not fill the armory backpacks', cellsA, cellsB }]
    const nameA = a.username
    const feet = bot => ({ id: itemId(bot.inventory.slots[8]), test: isTest(bot.inventory.slots[8]) })
    const packBefore = feet(a)
    // Disconnect and rejoin within the reconnect window: the armory backpack comes back as it was.
    a.quit()
    bots.splice(bots.indexOf(a), 1)
    await sleep(3000)
    let a2 = await make(nameA)
    await waitFor(() => inRaid(a2) && a2.inventory.slots[8], 15000)
    await sleep(1000)
    const rejoin = { inRaid: inRaid(a2), packBefore, packAfter: feet(a2), cells: ownIn(a2, PACK_CELLS).length === cellsA.length }
    a2.quit()
    bots.splice(bots.indexOf(a2), 1)
    await sleep(3000)
    // Server stop with A offline (inside its reconnect window) and B online in the raid.
    const back = await restart()
    bots.splice(0).forEach(bot => { try { bot.quit() } catch (e) {} })
    if (!back) return [false, { error: 'server did not come back after stop', rejoin }]
    await sleep(3000)
    const pa = profile(a)
    const pb = profile(b)
    const hasTest = r => !!r && (r.test || (r.contents || []).some(hasTest))
    // A record as the void hands it back: instance ids are given out at deploy and insurance marks never come home.
    const clean = r => { const c = JSON.parse(JSON.stringify(r)); delete c.uid; delete c.insured; delete c.insuredAs; if (c.contents) c.contents = c.contents.map(clean); return c }
    const layout = loadout => JSON.stringify(Object.keys(loadout || {}).sort((x, y) => x - y).map(k => [k, clean(loadout[k])]))
    const side = (p, mailed, before) => ({
      activeRaid: p.activeRaid || null,
      test: Object.values(p.loadout).some(hasTest),
      asWent: !!before && layout(p.loadout) === layout(before),
      mailed: p.mailbox.slice(mailed).map(r => r.id)
    })
    const result = { cellsA, cellsB, rejoin, a: side(pa, mailBefore.a, went.a), b: side(pb, mailBefore.b, went.b) }
    // A logs in: its loadout fits, nothing is dropped on the lobby floor.
    a2 = await make(nameA)
    await sleep(3000)
    result.a.lobby = inLobby(a2)
    result.a.dropped = Object.values(a2.entities).filter(e => e.name === 'item' && e.position.distanceTo(a2.entity.position) < 6).length
    // Void for both: the loadout slot for slot as it went in, so neither the armory backpack nor the
    // own items packed in it in the raid, and nothing found in the raid reaches the mailbox.
    const ok = rejoin.inRaid && rejoin.packAfter.test && /pack_/.test(rejoin.packAfter.id || '') && rejoin.cells &&
      ['a', 'b'].every(k => !result[k].activeRaid && !result[k].test && result[k].asWent && result[k].mailed.length === 0) &&
      result.a.lobby && result.a.dropped === 0
    return [ok, result]
  }
}

;(async () => {
  const wanted = process.argv.slice(2)
  const names = wanted.length ? wanted : Object.keys(scenarios)
  const original = fs.readFileSync(CONFIG, 'utf8')
  let failures = 0
  try {
    for (const name of names) {
      let ok, detail
      // insured checks what the claim sends back, so every insured item must come back at once.
      const restore = name === 'insured' ? await pinInsurance(path.join(RUN, 'plugins/Craftov'), rcon) : null
      try {
        [ok, detail] = await scenarios[name]()
      } catch (e) {
        ok = false
        detail = { error: e.stack }
      } finally {
        if (restore) await restore()
      }
      if (!ok) failures++
      console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`)
      for (const bot of bots.splice(0)) bot.quit()
      await sleep(1500)
    }
  } finally {
    fs.writeFileSync(CONFIG, original)
    await rcon('cv reload').catch(() => {})
  }
  process.exit(failures)
})()
