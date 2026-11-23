// Knife skins and points (issue #106), end to end with one headless bot through the menus:
//
//   1. points: /cv points shows 0 for a new player and sets 500; the profile card of the lobby
//      terminal and the 外观 menu both say 点券 500;
//   2. the 外观 menu opens on the tab of the knife in the melee slot (新手战术刀), its own look worn;
//      a left click on the 锈港 card puts craftov:knife_skin/starter_rustport on the melee knife and
//      "· 锈港" after its name, and the menu's clicks never leave anything on the cursor or in the
//      inventory: no stack but the melee knife ever shows a skin;
//   3. a skin is not an item: the skinned knife put into the stash is stored as the plain knife (its
//      stash entry shows the knife's own model) and comes back out wearing the skin; the same through
//      the secure container; a tactical dagger wearing 黑曜, put into the stash and listed on the
//      market, is listed as the plain dagger; meanwhile the starter knife given in its place wears
//      锈港 again (each kind of knife keeps its own skin);
//   4. the dagger wears 黑曜 in a raid on the proving ground; a click on it in the gallery crate's
//      search screen puts nothing into the crate (the melee knife never moves there, #10) and it
//      still wears 黑曜 afterwards; after dying in the raid it still wears 黑曜 in the lobby;
//   5. after logging out and back in it still wears 黑曜, and the profile file (with SERVER_DIR)
//      holds the choice and no record anywhere (stash, loadout, secure container, mailbox, market)
//      carries a skin;
//   6. preview: a right click on the 星图 card (a karambit skin) with the dagger in the melee slot and
//      another slot held closes the menu, selects the melee slot, shows craftov:knife_skin/
//      karambit_starchart on the dagger and starts the inspect (craftov:inspect cooldown); once the
//      inspect is over the dagger wears 黑曜 again and the menu opens again on the karambit's tab;
//      F tapped every half second during a preview (赤潮) starts the inspect over each time, yet the
//      preview still ends after one inspect (黑曜 back within about 3 s) and the menu stays shut; a
//      preview cut short by a slot switch ends at once and the menu stays shut;
//   7. with SERVER_DIR, skins.own-all and skins.shop-open are switched in config.yml (put back at the
//      end, then /cv reload): with own-all off the dagger falls back to its own look and the cards
//      say 未拥有; a click on 黑曜 says 点券商店暂未开放 and changes nothing; with the shop open and
//      500 points buying 黑曜 (880) is refused (点券不足), with 1,000 it is bought and worn and 120
//      points are left.
//
//   node skins.js     env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local SERVER_DIR=(optional, the run folder)
//
// Needs test mode on (the proving ground is a test map) and, to keep it short, match.wait-seconds of a
// few seconds. The bot has a fresh random name, is made op for the proving ground and deopped when done.
// Exit code 0 when every check passed, 1 when one failed, 2 when the scenario broke.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const SERVER_DIR = process.env.SERVER_DIR ? path.resolve(process.env.SERVER_DIR) : ''
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

// ------------------------------------------------------------------ text and items

function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n['']) + flat(n.extra)
}
function comp (item, type) { const c = item && item.components && item.components.find(x => x.type === type); return c ? c.data : null }
function cid (item) {
  const m = JSON.stringify(item && item.components || '').match(/"craftov:id":\{"type":"\w+","value":"([a-z0-9_]+)"/)
  return m ? m[1] : null
}
const model = item => { const c = comp(item, 'item_model'); return c == null ? null : typeof c === 'string' ? c : flat(c) || JSON.stringify(c) }
const title = item => flat(comp(item, 'custom_name') || comp(item, 'item_name'))
const lore = item => (comp(item, 'lore') || []).map(flat)
const skinned = item => /craftov:knife_skin\//.test(JSON.stringify(item && item.components || ''))
const SKIN = id => 'craftov:knife_skin/' + id

// The 外观 menu (gui/SkinMenu.java): knife tabs down the left, a card per look (cells in rows 1-3,
// state in row 4), the points balance top right.
const TAB = { knife_starter: 9, knife_tactical: 18, knife_machete: 27, knife_karambit: 36 }
const CARD = [10, 12, 14, 16] // own look, then the knife's three skins in skins.yml order
const STATE = [37, 39, 41, 43]
const POINTS_SLOT = 8

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const PLUGIN = SERVER_DIR && path.join(SERVER_DIR, 'plugins', 'Craftov')
const profileOf = name => JSON.parse(fs.readFileSync(path.join(PLUGIN, 'data', 'players', offlineUuid(name) + '.json'), 'utf8'))

// ------------------------------------------------------------------ bot

function spawnBot (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.inspects = []
  bot.opened = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot._client.on('set_cooldown', p => { if (p.cooldownGroup === 'craftov:inspect') bot.inspects.push({ at: Date.now(), ticks: p.cooldownTicks }) })
  bot._client.on('open_window', p => bot.opened.push({ at: Date.now(), title: flat(p.windowTitle) }))
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.chatLog.push(s); log(name, 'BAR', s) } })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', async () => { clearTimeout(t); await sleep(3000); resolve(bot) })
  })
}
async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(200)
  }
  return false
}
async function waitChat (bot, re, ms, from = 0) {
  let hit = null
  await waitFor(() => { hit = bot.chatLog.slice(from).find(m => re.test(m)) || null; return hit }, ms)
  return hit
}
function openWith (bot, fn) {
  return new Promise(resolve => {
    const t = setTimeout(() => resolve(null), 5000)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
    fn()
  })
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'
/** The knife in the melee slot (hotbar key 3): with no window open the player's slot 38, else the window's. */
function melee (bot) {
  const w = bot.currentWindow
  return w ? w.slots[w.inventoryStart + 29] : bot.inventory.slots[38]
}
const meleeSlotIn = w => w.inventoryStart + 29
const click = async (bot, slot, button = 0, wait = 500) => { await bot.clickWindow(slot, button, 0); await sleep(wait) }
/** F: the swap-hands key, which inspects the knife in hand. */
const pressF = bot => bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
const closeAll = async bot => { if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await sleep(400) }

/**
 * The player's own stacks showing a skin anywhere but the melee slot, plus the cursor. (A menu's
 * buttons may show one: the 外观 tabs show each knife as the player wears it.)
 */
function strays (bot) {
  const w = bot.currentWindow || bot.inventory
  const own = w === bot.inventory ? 9 : w.inventoryStart
  const out = []
  w.slots.forEach((s, i) => { if (i >= own && s && skinned(s) && !(w === bot.inventory ? i === 38 : i === meleeSlotIn(w))) out.push(i) })
  if (w.selectedItem) out.push('cursor:' + (cid(w.selectedItem) || model(w.selectedItem)))
  return out
}

async function openSkins (bot) {
  const w = await openWith(bot, () => bot.chat('/cv skins'))
  if (!w) throw new Error('the 外观 menu did not open')
  await sleep(500)
  return w
}
async function wear (bot, knife, card) {
  const w = await openSkins(bot)
  await click(bot, TAB[knife])
  await click(bot, CARD[card])
  const left = strays(bot)
  await closeAll(bot)
  return left
}
const pointsOf = async name => { const m = (await rcon(`cv points ${name}`)).match(/点券 ([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }

async function give (bot, id) {
  await rcon(`cv give ${bot.username} ${id}`)
  await waitFor(() => cid(melee(bot)) === id, 5000)
  await sleep(400)
}

/** Opens the first crate of the proving ground's container gallery (6.5 65 64.5): random loot, room to spare. */
async function openCrate (bot) {
  await rcon(`execute as ${bot.username} at @s run tp @s 6.5 65 65.9 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return openWith(bot, () => bot.activateEntity(target))
}

async function deploy (bot) {
  const from = bot.chatLog.length
  bot.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => inRaid(bot), 150000)
  if (!ok) log('not in a raid; chat since queueing:', bot.chatLog.slice(from))
  await sleep(3500)
  return ok
}

/** Sets keys of the server's config.yml ("skins.own-all" style paths, values as YAML) and reloads. */
async function setConfig (values) {
  const file = path.join(PLUGIN, 'config.yml')
  let text = fs.readFileSync(file, 'utf8')
  for (const [key, value] of Object.entries(values)) {
    const [section, leaf] = key.split('.')
    const re = new RegExp(`(^${section}:\\n(?:(?:  .*|\\s*)\\n)*?  ${leaf}: )[^\\n]*`, 'm')
    if (!re.test(text)) throw new Error('config.yml has no ' + key)
    text = text.replace(re, `$1${value}`)
  }
  fs.writeFileSync(file, text)
  log('config', values, (await rcon('cv reload')).split('\n')[0])
  await sleep(1000)
}

// ------------------------------------------------------------------ scenario

async function scenario (name, botRef) {
  let bot = botRef.bot

  // 1. Points.
  check('/cv points: a new player has 点券 0', await pointsOf(name) === 0, await rcon(`cv points ${name}`))
  await rcon(`cv points ${name} 500`)
  check('/cv points <player> 500 sets it', await pointsOf(name) === 500, await rcon(`cv points ${name}`))
  let w = await openWith(bot, () => bot.chat('/cv menu'))
  const card = w ? lore(w.slots[49]).find(l => l.includes('点券')) || '' : '(main menu did not open)'
  check('the lobby terminal profile card says 点券 500', /点券 500/.test(card), card)
  const appearance = w ? title(w.slots[26]) : ''
  check('the lobby terminal has the 外观 button (slot 26)', appearance.includes('外观'), appearance)
  await closeAll(bot)

  // 2. The menu: its own look first, then 锈港 worn by a left click.
  check('the starter knife wears its own look at first', model(melee(bot)) === 'craftov:knife_starter', model(melee(bot)))
  w = await openSkins(bot)
  const opened = (bot.opened[bot.opened.length - 1] || {}).title || ''
  check('the 外观 menu opens on the tab of the knife carried (新手战术刀)', opened.includes('外观') && opened.includes('新手战术刀'), opened)
  check('the menu shows 点券 500', title(w.slots[POINTS_SLOT]).includes('点券 500'), title(w.slots[POINTS_SLOT]))
  check('the own-look card is worn (使用中)', title(w.slots[STATE[0]]).includes('使用中'), title(w.slots[STATE[0]]))
  check('the 锈港 card is there and owned (everyone owns every skin by default)',
    title(w.slots[CARD[1]]).includes('锈港') && lore(w.slots[CARD[1]]).some(l => l.includes('已拥有')), [title(w.slots[CARD[1]]), lore(w.slots[CARD[1]])])
  await click(bot, CARD[1])
  await waitFor(() => model(melee(bot)) === SKIN('starter_rustport'), 3000)
  check('a left click on 锈港 puts the skin on the melee knife', model(melee(bot)) === SKIN('starter_rustport'), model(melee(bot)))
  check('and its name says · 锈港', title(melee(bot)).includes('锈港'), title(melee(bot)))
  check('the 锈港 card now says 使用中', title(w.slots[STATE[1]]).includes('使用中'), title(w.slots[STATE[1]]))
  // Clicks on every other kind of button: still nothing on the cursor or in the inventory.
  for (const slot of [CARD[2], STATE[2], CARD[0], STATE[0], CARD[1], POINTS_SLOT, 7, 49]) await click(bot, slot, 0, 300)
  await click(bot, TAB.knife_machete, 0, 300)
  check('no click in the menu puts a skin anywhere but on the melee knife (inventory, cursor)', strays(bot).length === 0, strays(bot))
  check('the melee knife still wears 锈港 (the own-look click was undone by 锈港 again)', model(melee(bot)) === SKIN('starter_rustport'), model(melee(bot)))
  await closeAll(bot)

  // 3a. The stash: stored as the plain knife, back out wearing the skin.
  w = await openWith(bot, () => bot.chat('/cv stash'))
  if (!w) throw new Error('stash did not open')
  const before = w.slots.slice(0, 45).filter(s => cid(s) === 'knife_starter').length
  await click(bot, meleeSlotIn(w), 0, 800)
  // The stash retitles itself after a deposit (room and value in the title), which mineflayer takes
  // for a new window: read the current one.
  w = bot.currentWindow || w
  const stored = w.slots.slice(0, 45).map((s, i) => [s, i]).filter(([s]) => cid(s) === 'knife_starter')
  check('the skinned knife goes into the stash', stored.length === before + 1, stored.length)
  const entry = stored.length ? stored[stored.length - 1] : [null, -1]
  check('its stash entry is the plain knife (craftov:knife_starter, no skin name)',
    model(entry[0]) === 'craftov:knife_starter' && !title(entry[0]).includes('锈港'), [model(entry[0]), title(entry[0])])
  await closeAll(bot)
  // The lobby hands out a new starter knife for the empty slot; it wears the skin as well.
  await waitFor(() => model(melee(bot)) === SKIN('starter_rustport'), 8000)
  check('the replacement starter knife wears 锈港 too', model(melee(bot)) === SKIN('starter_rustport'), model(melee(bot)))
  w = await openWith(bot, () => bot.chat('/cv stash'))
  // A left click on an item takes it out (the stash has no modes since #10).
  const stashed = w ? w.slots.slice(0, 45).findIndex(s => cid(s) === 'knife_starter') : -1
  if (stashed >= 0) await click(bot, stashed, 0, 800)
  await closeAll(bot)
  await waitFor(() => model(melee(bot)) === SKIN('starter_rustport'), 3000)
  check('taken back out of the stash, the knife wears 锈港 in the melee slot', model(melee(bot)) === SKIN('starter_rustport'), model(melee(bot)))

  // 3b. The secure container.
  w = await openWith(bot, () => bot.chat('/cv secure'))
  if (!w) throw new Error('secure container did not open')
  await click(bot, meleeSlotIn(w), 0, 800)
  const secured = w.slots.slice(0, w.inventoryStart).find(s => cid(s) === 'knife_starter')
  check('in the secure container the knife is the plain knife', !!secured && model(secured) === 'craftov:knife_starter' && !skinned(secured),
    secured ? [model(secured), title(secured)] : '(not there)')
  const at = w.slots.slice(0, w.inventoryStart).findIndex(s => cid(s) === 'knife_starter')
  if (at >= 0) await click(bot, at, 0, 800)
  await closeAll(bot)
  await waitFor(() => model(melee(bot)) === SKIN('starter_rustport'), 5000)
  check('out of the secure container it wears 锈港 again', model(melee(bot)) === SKIN('starter_rustport'), model(melee(bot)))

  // 3c. The tactical dagger: its own skin, stored and listed plain; the starter keeps 锈港.
  await give(bot, 'knife_tactical')
  check('a tactical dagger replaces the starter knife and wears its own look (no skin chosen for it)',
    cid(melee(bot)) === 'knife_tactical' && model(melee(bot)) === 'craftov:knife_tactical', [cid(melee(bot)), model(melee(bot))])
  await wear(bot, 'knife_tactical', 1)
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 3000)
  check('黑曜 goes on the dagger from its tab', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  w = await openWith(bot, () => bot.chat('/cv stash'))
  await click(bot, meleeSlotIn(w), 0, 800)
  w = bot.currentWindow || w
  const dagger = w.slots.slice(0, 45).findIndex(s => cid(s) === 'knife_tactical')
  check('the dagger is in the stash as the plain dagger', dagger >= 0 && model(w.slots[dagger]) === 'craftov:knife_tactical',
    dagger >= 0 ? model(w.slots[dagger]) : '(not there)')
  let listed = null
  if (dagger >= 0) {
    // A right click on an item opens its market listing.
    const pm = await openWith(bot, () => bot.clickWindow(dagger, 1, 0))
    if (pm) {
      const from = bot.chatLog.length
      const back = await openWith(bot, () => bot.clickWindow(26, 0, 0))
      if (back) bot.closeWindow(back)
      listed = await waitChat(bot, /已上架|无法上架|等级|手续费不足/, 4000, from)
    }
  }
  await closeAll(bot)
  check('the dagger is listed on the market', !!listed && listed.includes('已上架'), listed)
  if (PLUGIN) {
    const market = JSON.parse(fs.readFileSync(path.join(PLUGIN, 'data', 'market.json'), 'utf8'))
    const mine = (market.listings || []).filter(l => l.sellerName === name)
    check('market.json: the listing is the plain dagger, nothing about a skin', mine.length === 1 && mine[0].item.id === 'knife_tactical'
      && !/skin|obsidian/i.test(JSON.stringify(mine[0])), mine.map(l => l.item))
  }
  await waitFor(() => cid(melee(bot)) === 'knife_starter', 8000)
  await waitFor(() => model(melee(bot)) === SKIN('starter_rustport'), 3000)
  check('the starter knife handed out in its place wears 锈港 (each kind keeps its own skin)',
    cid(melee(bot)) === 'knife_starter' && model(melee(bot)) === SKIN('starter_rustport'), [cid(melee(bot)), model(melee(bot))])

  // 4. A raid and a death with the dagger.
  await give(bot, 'knife_tactical')
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 3000)
  check('a new tactical dagger wears 黑曜 at once (the choice is the kind of knife, not the stack)',
    model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  if (!await deploy(bot)) throw new Error('the bot never reached the proving ground')
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 3000)
  check('in the raid the dagger wears 黑曜', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  // The search screen (#10) never moves the melee knife: a click on it puts nothing into the crate,
  // so no skin can get there, and the dagger stays in the melee slot wearing its skin. (A body still
  // copies a knife as it is; SkinService#undress covers that.)
  w = await openCrate(bot)
  if (!w) throw new Error('the gallery crate on the proving ground did not open')
  await sleep(1000)
  w = bot.currentWindow || w
  const topDaggers = win => win.slots.slice(0, win.inventoryStart).map((s, i) => cid(s) === 'knife_tactical' ? i : -1).filter(i => i >= 0)
  const daggersBefore = topDaggers(w)
  await click(bot, meleeSlotIn(w), 0, 800)
  w = bot.currentWindow || w
  // The click was refused: the client guessed it picked the dagger up, so ask for the window as the
  // server has it.
  if (bot._syncWindow) await Promise.race([bot._syncWindow(w), sleep(1500)])
  w = bot.currentWindow || w
  const putAt = topDaggers(w).find(i => !daggersBefore.includes(i))
  check('a click on the dagger in the melee slot puts nothing into the crate',
    putAt === undefined && cid(w.slots[meleeSlotIn(w)]) === 'knife_tactical',
    putAt === undefined ? cid(w.slots[meleeSlotIn(w)]) : [model(w.slots[putAt]), title(w.slots[putAt])])
  check('no stack but the melee knife shows a skin while the crate is open', strays(bot).length === 0, strays(bot))
  await closeAll(bot)
  await waitFor(() => cid(melee(bot)) === 'knife_tactical' && model(melee(bot)) === SKIN('tactical_obsidian'), 3000)
  check('after the crate, the dagger still wears 黑曜', cid(melee(bot)) === 'knife_tactical'
    && model(melee(bot)) === SKIN('tactical_obsidian'), [cid(melee(bot)), model(melee(bot))])
  const from = bot.chatLog.length
  await rcon(`damage ${name} 1000 minecraft:generic`)
  await waitChat(bot, /损失|击败干员 \d+ · 击败敌兵/, 60000, from)
  if (!await waitFor(() => inLobby(bot), 60000)) throw new Error('the bot did not get back to the lobby')
  await sleep(1500)
  await closeAll(bot)
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 5000)
  check('after dying, the dagger in the melee slot still wears 黑曜', cid(melee(bot)) === 'knife_tactical' && model(melee(bot)) === SKIN('tactical_obsidian'),
    [cid(melee(bot)), model(melee(bot))])

  // 5. Log out and back in.
  bot.quit()
  await sleep(3000)
  bot = botRef.bot = await spawnBot(name)
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 5000)
  check('after logging back in, the dagger wears 黑曜', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  if (PLUGIN) {
    // Written when the bot left.
    const p = profileOf(name)
    check('the profile keeps the choices: knifeSkins', p.knifeSkins && p.knifeSkins.knife_starter === 'starter_rustport'
      && p.knifeSkins.knife_tactical === 'tactical_obsidian', p.knifeSkins)
    check('the profile keeps the points', p.points === 500, p.points)
    const records = JSON.stringify([p.stash, p.loadout, p.secure, p.mailbox, p.mail])
    check('no record in the profile carries a skin (stash, loadout, secure container, mailbox)', !/knife_skin|starter_rustport|tactical_obsidian/.test(records))
  }

  // 6. Preview a karambit skin on the dagger, from another slot.
  bot.setQuickBarSlot(0)
  await sleep(500)
  w = await openSkins(bot)
  await click(bot, TAB.knife_karambit)
  const inspectsBefore = bot.inspects.length
  const openedBefore = bot.opened.length
  await bot.clickWindow(CARD[2], 1, 0) // right click: preview 星图
  await waitFor(() => !bot.currentWindow, 2000)
  await waitFor(() => model(melee(bot)) === SKIN('karambit_starchart'), 2000)
  check('preview: the menu closes and the dagger shows 星图', !bot.currentWindow && model(melee(bot)) === SKIN('karambit_starchart'), model(melee(bot)))
  check('preview: the melee slot is selected', bot.quickBarSlot === 2, bot.quickBarSlot)
  await waitFor(() => bot.inspects.slice(inspectsBefore).some(i => i.ticks > 0), 2000)
  check('preview: the inspect starts (craftov:inspect cooldown)', bot.inspects.slice(inspectsBefore).some(i => i.ticks > 0), bot.inspects.slice(inspectsBefore))
  check('preview: the name still says 黑曜 (what the dagger wears)', title(melee(bot)).includes('黑曜'), title(melee(bot)))
  await waitFor(() => bot.opened.length > openedBefore, 6000)
  const back = bot.opened[bot.opened.length - 1] || {}
  check('after the inspect the menu opens again on the karambit tab', bot.opened.length > openedBefore && back.title.includes('爪刀'), back.title)
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 2000)
  check('and the dagger wears 黑曜 again', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  await closeAll(bot)
  // F tapped during a preview starts the inspect over, but not the preview: one inspect (2.5 s) and
  // the skin is gone, so tapping cannot keep a look (perhaps one not owned) on the knife.
  w = await openSkins(bot)
  await click(bot, TAB.knife_karambit)
  const openedTap = bot.opened.length
  await bot.clickWindow(CARD[3], 1, 0) // right click: preview 赤潮
  await waitFor(() => model(melee(bot)) === SKIN('karambit_redtide'), 2000)
  const tapStart = Date.now()
  const tapInspects = bot.inspects.length
  let shownFor = -1
  while (Date.now() - tapStart < 5000) {
    if (Date.now() - tapStart > 300) pressF(bot)
    for (let i = 0; i < 5; i++) {
      await sleep(100)
      if (model(melee(bot)) === SKIN('karambit_redtide')) shownFor = Date.now() - tapStart
    }
  }
  const restarts = bot.inspects.slice(tapInspects).filter(i => i.ticks > 0).length
  check('F every half second during a preview starts the inspect over each time', restarts >= 6, restarts)
  check('yet the preview ends after one inspect: 赤潮 gone within about 3 s, 黑曜 back',
    shownFor >= 0 && shownFor < 3200 && model(melee(bot)) === SKIN('tactical_obsidian'), [shownFor, model(melee(bot))])
  await sleep(1500)
  check('and the menu does not come back after tapping F', bot.opened.length === openedTap, bot.opened.slice(openedTap))
  await closeAll(bot)
  // A preview cut short by switching slot: no menu afterwards, the look comes back at once.
  w = await openSkins(bot)
  await click(bot, TAB.knife_karambit)
  const openedCut = bot.opened.length
  await bot.clickWindow(CARD[1], 1, 0)
  await waitFor(() => model(melee(bot)) === SKIN('karambit_gilded'), 2000)
  await sleep(600)
  bot.setQuickBarSlot(0)
  await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 2000)
  check('a preview cut short by a slot switch ends there: 黑曜 back', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
  await sleep(3000)
  check('and the menu does not come back', bot.opened.length === openedCut, bot.opened.slice(openedCut))
  await closeAll(bot)

  // 7. Ownership and the shop (needs the config file).
  if (!PLUGIN) {
    log('SKIP ownership and shop: SERVER_DIR is not set')
    return
  }
  try {
    await setConfig({ 'skins.own-all': 'false' })
    await waitFor(() => model(melee(bot)) === 'craftov:knife_tactical', 3000)
    check('own-all off: the dagger falls back to its own look (黑曜 not owned)', model(melee(bot)) === 'craftov:knife_tactical', model(melee(bot)))
    w = await openSkins(bot)
    check('the 黑曜 card says 未拥有 and its state is locked with the price', lore(w.slots[CARD[1]]).some(l => l.includes('未拥有'))
      && title(w.slots[STATE[1]]).includes('点券 880'), [lore(w.slots[CARD[1]]), title(w.slots[STATE[1]])])
    await click(bot, CARD[1])
    check('a click on it says 点券商店暂未开放', lore(w.slots[CARD[1]]).some(l => l.includes('点券商店暂未开放')), lore(w.slots[CARD[1]]))
    check('and changes nothing: points 500, the dagger plain', await pointsOf(name) === 500 && model(melee(bot)) === 'craftov:knife_tactical',
      [await pointsOf(name), model(melee(bot))])
    await closeAll(bot)
    await setConfig({ 'skins.shop-open': 'true' })
    w = await openSkins(bot)
    await click(bot, CARD[1])
    check('shop open, 500 points: buying 黑曜 (880) is refused for want of points', lore(w.slots[CARD[1]]).some(l => l.includes('点券不足')),
      lore(w.slots[CARD[1]]))
    await closeAll(bot)
    await rcon(`cv points ${name} 1000`)
    w = await openSkins(bot)
    await click(bot, CARD[1])
    await waitFor(() => model(melee(bot)) === SKIN('tactical_obsidian'), 3000)
    check('with 1,000 points 黑曜 is bought and worn', model(melee(bot)) === SKIN('tactical_obsidian'), model(melee(bot)))
    check('and 120 points are left', await pointsOf(name) === 120, await pointsOf(name))
    check('its card says 使用中', title(w.slots[STATE[1]]).includes('使用中'), title(w.slots[STATE[1]]))
    await closeAll(bot)
    // Profiles are written every 10 s.
    await waitFor(() => (profileOf(name).skins || []).includes('tactical_obsidian'), 15000)
    check('the profile lists it as bought', (profileOf(name).skins || []).includes('tactical_obsidian'), profileOf(name).skins)
  } finally {
    await setConfig({ 'skins.own-all': 'true', 'skins.shop-open': 'false' })
  }
}

;(async () => {
  const name = 'sk' + Math.random().toString(36).slice(2, 8)
  const ref = { bot: null }
  let broke = false
  try {
    ref.bot = await spawnBot(name)
    await rcon('op ' + name)
    await scenario(name, ref)
  } catch (e) {
    broke = true
    log('SETUP', e.stack)
  } finally {
    try { await rcon('deop ' + name) } catch (e) { }
    if (ref.bot) ref.bot.quit()
    await sleep(1000)
    log(failures.length ? `${failures.length} FAILED: ${failures.join('; ')}` : broke ? 'BROKEN' : 'ALL PASSED')
    process.exit(broke ? 2 : failures.length ? 1 : 0)
  }
})()
