// Checks for the silent item losses of issue #18: a broken or changed items.yml, items held on the
// cursor, loading a preset without its backpack, insurance marks, full bodies and season rewards.
//
//   node itemloss.js reload | unknown | raid | cursorpack | insure [end|stop|offline|two] | missing | preset | season
//
// cursorpack: a backpack taken off onto the cursor with a collectible inside. At the no-backpack exit
// it must block the countdown like a worn one; then, with another backpack worn, the extraction mails
// it (it cannot be worn or put in a cell) and must count it: collection entry, extracted value.
//
// insure: end = the raid ends normally; stop = the server stops after the owner died; offline = it
// stops while the owner is disconnected (their raid is void: the loadout as it went in, no claim);
// two = two raids are running when it stops. The three stop variants leave the server stopped.
// insure sets insurance.loss-chance and return-minutes to 0 in config.yml while it runs, so every
// claim comes back at once, and puts the file back afterwards.
//
// Env: PORT / RCON_PORT / RCON_PASS of the local test server (default 25565 / 25575 / craftov-local),
// CRAFTOV_DATA = its plugins/Craftov folder (default ../../server/run/plugins/Craftov). Profiles are
// prepared by editing the player's JSON while the bot is offline, then checked the same way.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')
const pinInsurance = require('./insurance')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const DATA = process.env.CRAFTOV_DATA || path.join(__dirname, '../../server/run/plugins/Craftov')
const scenario = process.argv[2] || 'reload'
const variant = process.argv[3] || 'end'
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
let failures = 0
const check = (label, ok, extra) => { if (!ok) failures++; log(ok ? 'PASS' : 'FAIL', label, extra === undefined ? '' : extra) }

// ------------------------------------------------------------------ profiles

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(DATA, 'data/players', offlineUuid(name) + '.json')
const readProfile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const writeProfile = (name, p) => fs.writeFileSync(profileFile(name), JSON.stringify(p, null, 2))
const rec = (id, extra = {}) => Object.assign({ id, amount: 1 }, extra)
const gun = id => rec(id, { uid: crypto.randomUUID(), ammo: 0 })
const countIds = (records, id) => records.filter(r => r && r.id === id).reduce((n, r) => n + r.amount, 0)

// ------------------------------------------------------------------ bots

function cid (item) {
  if (!item) return null
  const model = (item.components || []).find(c => c.type === 'item_model')
  const m = JSON.stringify(model ? model.data : item.components || '').match(/craftov:([a-z0-9_/]+)/)
  return m ? m[1] : item.name
}
const text = item => JSON.stringify(item && item.components || '')

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldChanges = 0
  bot.chats = []
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chats.push(m); log(name, 'CHAT', m) } })
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    bot.once('spawn', async () => { await sleep(3000); resolve(bot) })
    bot.once('end', r => reject(new Error(name + ' disconnected: ' + r)))
  })
}

async function leave (bot) {
  const file = profileFile(bot.username)
  const before = fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0
  bot.quit()
  await waitFor(() => fs.existsSync(file) && fs.statSync(file).mtimeMs > before, 8000)
  await sleep(500)
}

/** Joins once so the profile exists, then lets {@code mutate} edit it offline. */
async function prepare (name, mutate) {
  const bot = await make(name)
  await rcon('op ' + name)
  await leave(bot)
  const p = readProfile(name)
  mutate(p)
  writeProfile(name, p)
}

async function waitFor (pred, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(step) }
  return false
}

function openWindow (bot, fn, timeout = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeout)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    fn()
  })
}

/**
 * The stash's mailbox button (slot 50) opens the mailbox screen, whose item-mailbox button (slot 49)
 * takes everything into the stash; then back to the stash, whose window is returned.
 */
async function claimMailbox (bot) {
  const mail = await openWindow(bot, () => bot.clickWindow(50, 0, 0))
  if (mail) { await bot.clickWindow(49, 0, 0); await sleep(600) }
  return openWindow(bot, () => bot.chat('/cv stash'))
}

const carried = bot => bot.inventory.slots.slice(5, 45).filter(s => s && !String(cid(s)).startsWith('ui/'))
/** A click outside the inventory with a stale state id: the server sends the whole inventory again. */
async function resync (bot) {
  bot._client.write('window_click', { windowId: 0, stateId: -1, slot: -999, mouseButton: 0, mode: 0, changedSlots: [], cursorItem: undefined })
  await sleep(800)
}
/** The box's own cells of a search screen (its 6x6 block, #10), the page button left out. */
const boxCells = w => w.slots.slice(0, w.inventoryStart).filter((s, i) => i % 9 < 6 && s && !String(cid(s)).startsWith('ui/arrow'))
const carriedIds = bot => carried(bot).map(s => cid(s) + 'x' + s.count).join(' ')
const top = w => w.slots.slice(0, w.inventoryStart)

async function deploy (bots) {
  const running = await rcon('cv raids')
  if (running.includes('RUNNING')) {
    log('WARNING: a raid is still running and the bots may be put into it; wait for it to close first:', running)
  }
  const before = bots.map(b => b.worldChanges)
  for (const b of bots) b.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => bots.every((b, i) => b.game.gameMode === 'adventure' && b.worldChanges > before[i]), 90000)
  check('everyone deployed into one raid', ok, await rcon('cv raids'))
  await sleep(4000) // past the spawn protection
}

/** Teleports the looter next to a body and opens it. */
async function openBody (looter, at) {
  await rcon(`execute as ${looter.username} at @s run tp @s ${at.x.toFixed(2)} ${at.y.toFixed(2)} ${(at.z + 1.3).toFixed(2)}`)
  await sleep(1500)
  const body = looter.nearestEntity(e => e.name === 'interaction' && e.position.distanceTo(at) < 2.5)
  if (!body) return null
  await looter.lookAt(body.position.offset(0, 0.3, 0), true)
  return openWindow(looter, () => looter.activateEntity(body))
}

// ------------------------------------------------------------------ scenarios

const itemsYml = path.join(DATA, 'items.yml')

async function reload () {
  const name = 'Rl' + tag
  await prepare(name, p => { p.loadout = { 0: gun('m4c'), 1: rec('screws', { amount: 3 }), 18: rec('medkit_s', { dura: 120 }), 36: rec('pack_3') } })
  let bot = await make(name)
  const before = carried(bot).length
  log('carried before', carriedIds(bot))
  const original = fs.readFileSync(itemsYml, 'utf8')
  try {
    fs.writeFileSync(itemsYml, original + '\n\tbroken: [\n')
    const reply = await rcon('cv reload')
    check('reload reports the broken items.yml', reply.includes('items.yml 有错误'), reply.slice(0, 160))
    await sleep(5000)
    await leave(bot)
    const loadout = Object.values(readProfile(name).loadout)
    check('saved loadout kept every item', loadout.length === before, loadout.map(r => r.id))
    bot = await make(name)
    check('inventory complete after rejoining', carried(bot).length === before, carriedIds(bot))
    await leave(bot)
    // A file saved as GBK (a Chinese comment from Notepad) still loads, with a warning in the log.
    fs.writeFileSync(itemsYml, Buffer.concat([Buffer.from(original, 'utf8'), Buffer.from([0x0a, 0x23, 0x20, 0xc4, 0xe3, 0xba, 0xc3, 0x0a])]))
    const gbk = await rcon('cv reload')
    check('an items.yml that is not UTF-8 still loads', gbk.includes('已重新加载'), gbk.slice(0, 160))
  } finally {
    fs.writeFileSync(itemsYml, original)
    log('restored items.yml:', await rcon('cv reload'))
  }
}

async function unknown () {
  const name = 'Uk' + tag
  // Full pockets, a backpack holding a tape and a first-aid kit.
  const loadout = { 36: rec('pack_3'), 18: rec('tape'), 19: rec('medkit_s', { dura: 120 }) }
  for (const s of [0, 1, 3, 4, 5, 6, 7]) loadout[s] = rec('screws')
  await prepare(name, p => { p.loadout = loadout; p.mailbox = [] })
  let bot = await make(name)
  const original = fs.readFileSync(itemsYml, 'utf8')
  try {
    fs.writeFileSync(itemsYml, original.replace(/^ {2}medkit_s:/m, '  medkit_s_renamed:').replace(/^ {2}pack_3:/m, '  pack_3_renamed:'))
    log('reload without medkit_s and pack_3:', await rcon('cv reload'))
    await sleep(3000) // a few periodic loadout saves
    // Rebuilds the inventory from the loadout, twice before the next periodic save: the unknown
    // backpack and kit are mailed, the tape no longer has a cell and the pockets are full, so it is
    // mailed too, and the second pass must not mail any of them again.
    bot.chat('/cv lobby')
    await sleep(300)
    bot.chat('/cv lobby')
    await sleep(1500)
    check('player told the unknown item went to the mailbox', bot.chats.some(c => c.includes('medkit_s') && c.includes('邮箱')))
    await leave(bot)
    const p = readProfile(name)
    check('each mailed item is in the mailbox once', countIds(p.mailbox, 'medkit_s') === 1 && countIds(p.mailbox, 'pack_3') === 1
      && countIds(p.mailbox, 'tape') === 1, p.mailbox.map(r => r.id))
    check('other items stay in the loadout', countIds(Object.values(p.loadout), 'screws') === 7, Object.values(p.loadout).map(r => r.id))
    p.loadout = {} // room to take the kit out of the stash below
    writeProfile(name, p)
  } finally {
    fs.writeFileSync(itemsYml, original)
    log('restored items.yml:', await rcon('cv reload'))
  }
  bot = await make(name)
  await openWindow(bot, () => bot.chat('/cv stash'))
  const stash = await claimMailbox(bot)
  const at = stash ? top(stash).findIndex(s => cid(s) === 'medkit_s') : -1
  check('restored item shows in the stash', at >= 0)
  if (at >= 0) { await bot.clickWindow(at, 0, 0); await sleep(600) }
  if (stash) bot.closeWindow(stash)
  check('restored item taken out of the stash', carried(bot).some(s => cid(s) === 'medkit_s'), carriedIds(bot))
  await leave(bot)
}

async function raid () {
  const full = { 36: rec('pack_3'), 37: rec('rig_3'), 38: rec('armor_3', { dura: 80 }), 39: rec('helmet_3', { dura: 40 }) }
  for (const s of [0, 1, 3, 4, 5, 6, 7]) full[s] = rec('screws')
  for (let s = 9; s < 36; s++) full[s] = rec('screws')
  const names = ['Bf' + tag, 'Bc' + tag, 'Bl' + tag]
  await prepare(names[0], p => { p.loadout = full })
  await prepare(names[1], p => { p.loadout = { 36: rec('pack_3'), 18: rec('screws'), 19: rec('tape'), 20: rec('battery') } })
  await prepare(names[2], p => { p.loadout = { 0: rec('gold_bar') }; p.mailbox = [] })
  const [full38, cursor, looter] = [await make(names[0]), await make(names[1]), await make(names[2])]
  check('full loadout carries 38 items besides the knife', carried(full38).length === 39, carried(full38).length)
  await deploy([full38, cursor, looter])

  // A full rig_3 + pack_3 body keeps all 38 items.
  let at = full38.entity.position.clone()
  await rcon('kill ' + full38.username)
  await sleep(2000)
  let w = await openBody(looter, at)
  check('body of the full loadout opened', !!w)
  if (w) {
    // The search screen shows 35 of a big body's cells a page, the last cell turning the page (#10).
    let items = boxCells(w).length
    const first = items
    if (w.slots[50]) {
      await looter.clickWindow(50, 0, 0)
      await sleep(800)
      items += boxCells(looter.currentWindow || w).length
    }
    check('body holds all 38 items over its two pages', items === 38, { first, items })
    looter.closeWindow(looter.currentWindow || w)
  }

  // A backpack picked up onto the cursor goes into the body with its contents.
  await cursor.clickWindow(8, 0, 0) // the worn backpack slot
  await sleep(800)
  log('cursor holds', cid(cursor.inventory.selectedItem))
  at = cursor.entity.position.clone()
  await rcon('kill ' + cursor.username)
  await sleep(2000)
  w = await openBody(looter, at)
  check('body of the cursor player opened', !!w)
  if (w) {
    await waitFor(() => boxCells(w).some(s => cid(s) === 'pack_3'), 10000)
    const pack = boxCells(w).find(s => cid(s) === 'pack_3')
    check('body holds the backpack from the cursor with its 3 items', !!pack && text(pack).includes('内含 3'), boxCells(w).map(cid))
    looter.closeWindow(w)
  }

  // A gold bar held on the cursor when the extraction countdown ends comes home.
  const before = looter.worldChanges
  await rcon(`execute as ${looter.username} at @s run tp @s 106.5 65 8.5`)
  await sleep(500)
  await looter.clickWindow(36, 0, 0) // hotbar 0: the gold bar onto the cursor
  log('looter cursor', cid(looter.inventory.selectedItem))
  check('extracted', await waitFor(() => looter.worldChanges > before, 20000))
  await sleep(2000)
  // A gold bar fits neither a gun key nor a quick key (#10), and the looter wears no rig or backpack:
  // it comes home in the stash mailbox.
  await resync(looter)
  const goldHome = () => carried(looter).some(s => cid(s) === 'gold_bar') || countIds(readProfile(names[2]).mailbox || [], 'gold_bar') === 1
  check('gold bar comes home (the inventory, or the mailbox when it has no room)', await waitFor(goldHome, 8000), carriedIds(looter))
  const report = looter.chats.find(c => c.includes('带出') || c.includes('撤离成功'))
  log('report line', report || '(none)')
  for (const b of [full38, cursor, looter]) await leave(b)
  const p = readProfile(names[2])
  check('gold bar saved in the loadout or the mailbox', countIds(Object.values(p.loadout), 'gold_bar') + countIds(p.mailbox || [], 'gold_bar') === 1,
    { loadout: Object.values(p.loadout).map(r => r.id), mailbox: (p.mailbox || []).map(r => r.id) })
}

async function cursorpack () {
  const name = 'Bp' + tag
  // pack_3 worn with empty cells, an empty pack_2 in pocket 0, the other pockets full and no rig, so
  // a collectible given in the raid lands in the backpack.
  const loadout = { 36: rec('pack_3'), 0: rec('pack_2') }
  for (const s of [1, 3, 4, 5, 6, 7]) loadout[s] = rec('screws')
  await prepare(name, p => { p.loadout = loadout; p.mailbox = []; p.collected = [] })
  const bot = await make(name)
  // The countdown beeps every second (craftov:extract.beep); a blocked exit stays silent.
  const beeps = []
  // The last ten seconds tick with craftov:extract.final instead, played on the player as an entity
  // sound, which mineflayer's soundEffectHeard never reports: both packets are read directly.
  const tick = p => { if (/extract\.(beep|final)/.test(JSON.stringify(p))) beeps.push(Date.now()) }
  for (const packet of ['sound_effect', 'entity_sound_effect']) bot._client.on(packet, tick)
  await deploy([bot])
  await rcon(`cv give ${name} pocket_watch`)
  await sleep(800)
  const cells = Array.from({ length: 18 }, (_, i) => 18 + i)
  check('collectible lands in the worn backpack', cells.some(i => cid(bot.inventory.slots[i]) === 'pocket_watch'), carriedIds(bot))

  // The worn backpack onto the cursor, then into the no-backpack exit: blocked like a worn one.
  await bot.clickWindow(8, 0, 0)
  await sleep(600)
  const before = bot.worldChanges
  await rcon(`execute as ${name} at @s run tp @s 106.5 65 44.5`)
  await sleep(9000)
  check('no-backpack exit blocks a packed backpack held on the cursor', bot.worldChanges === before && beeps.length === 0,
    { worldChanges: bot.worldChanges - before, beeps: beeps.length })
  await rcon(`execute as ${name} at @s run tp @s 100.5 65 30.5`)
  await sleep(1000)

  // Back on, then swapped for the empty pack_2: pack_3 with the watch is left on the cursor.
  await bot.clickWindow(8, 0, 0) // cursor pack_3 back on
  await sleep(500)
  await bot.clickWindow(36, 0, 0) // pocket 0: pack_2 onto the cursor
  await sleep(500)
  await bot.clickWindow(8, 0, 0) // pack_2 on, pack_3 (packed) onto the cursor
  await sleep(800)
  check('pack_2 worn, pack_3 on the cursor', cid(bot.inventory.slots[8]) === 'pack_2', carriedIds(bot))
  await rcon(`execute as ${name} at @s run tp @s 106.5 65 8.5`)
  check('extracted (the countdown beeped this time)', await waitFor(() => bot.worldChanges > before, 20000) && beeps.length > 0, beeps.length)
  await sleep(2500)
  const broadcast = bot.chats.some(c => c.includes('成功带出收藏品'))
  await leave(bot)
  const p = readProfile(name)
  const mailed = p.mailbox.find(r => r.id === 'pack_3')
  check('the packed backpack went to the mailbox with the watch', !!mailed && (mailed.contents || []).some(r => r.id === 'pocket_watch'),
    p.mailbox.map(r => r.id + (r.contents ? '[' + r.contents.map(c => c.id).join(',') + ']' : '')))
  check('the watch is in the collection', (p.collected || []).includes('pocket_watch') && broadcast, { collected: p.collected, broadcast })
  const report = p.reports && p.reports[0]
  check('the extracted value counts the mailed watch', !!report && report.extractedValue >= 70000, report && report.extractedValue)
}

// The guns are loaded, so a claim that lost the deploy snapshot (which would empty them) shows.
const loaded = (id, ammoType) => Object.assign(gun(id), { ammo: 20, ammoType })
const insuredLoadout = () => ({ 0: loaded('m4c', 'ammo_556_m855'), 1: rec('optic_reddot'), 18: loaded('k47', 'ammo_762_ps'),
  36: rec('pack_3'), 38: rec('armor_3', { dura: 80 }) })

async function stopServer () {
  log('stopping the server with the raid still running')
  await rcon('stop').catch(() => {})
  const end = Date.now() + 60000
  // A server in the middle of stopping may drop the connection without an answer: give up after 3 s.
  const up = () => Promise.race([rcon('list').then(() => true, () => false), sleep(3000).then(() => false)])
  while (Date.now() < end && await up()) await sleep(1000)
  await sleep(3000)
}

const noMarks = records => records.every(r => !r.insured && !(r.contents || []).some(c => c.insured))

/** Every insured item comes back at once for these checks (see insurance.js). */
async function insure () {
  const restore = await pinInsurance(DATA, rcon, log)
  try {
    await insureOne()
  } finally {
    await restore()
  }
}

async function insureOne () {
  if (variant === 'two') return insureTwoRaids()
  const name = 'In' + tag
  const mate = 'Im' + tag
  await prepare(name, p => {
    p.autoInsure = true
    p.money = 1000000
    p.mailbox = []
    p.loadout = insuredLoadout()
  })
  await prepare(mate, p => {})
  let bot = await make(name)
  const buddy = await make(mate)
  await deploy([bot, buddy])
  check('gear insured on deploy', bot.chats.some(c => c.includes('已为 4 件装备投保')))
  await rcon(`execute as ${mate} at @s run tp @s 70.5 65 92.5`)

  // Reconnect.
  bot.quit()
  await sleep(3000)
  bot = await make(name)
  check('rejoined the raid', bot.game.gameMode === 'adventure' && bot.chats.some(c => c.includes('重新连接')))
  // Fit the red dot on the held m4c at the gunsmith.
  const bench = await openWindow(bot, () => bot.chat('/cv mod'))
  if (bench) { await bot.clickWindow(45, 0, 0); await sleep(600); bot.closeWindow(bench) }
  const m4c = carried(bot).find(s => cid(s) === 'm4c')
  check('m4c still insured after the gunsmith and the reconnect', !!m4c && text(m4c).includes('本局已投保'))
  // Q on the worn backpack drops it with the k47 packed inside.
  await bot.clickWindow(8, 0, 4)
  await sleep(150)
  // Step away before the dropped backpack can be picked up (and worn) again.
  await rcon(`execute as ${name} at @s run tp @s ~6 ~ ~`)
  await sleep(1000)
  check('backpack dropped', !carried(bot).some(s => cid(s) === 'pack_3'), carriedIds(bot))
  await sleep(2500)
  if (variant === 'offline') {
    // The owner disconnects with the m4c and the armour; the backpack and the k47 lie on the ground.
    // The stop finds them inside the reconnect window, so their raid is void (issue #67): the
    // offline profile gets the loadout back as it went in, the dropped backpack and k47 included,
    // and no insurance claim on top of it.
    bot.quit()
    await sleep(3000)
    await stopServer()
    const p = readProfile(name)
    check('no insurance claim: the mailbox is empty', p.mailbox.length === 0 && (p.claims || []).length === 0,
      { mailbox: p.mailbox.map(r => r.id), claims: (p.claims || []).map(c => c.item && c.item.id) })
    // The lobby's starter knife in the melee slot went in too.
    const kept = Object.values(p.loadout)
    const want = ['armor_3', 'k47', 'knife_starter', 'm4c', 'optic_reddot', 'pack_3']
    check('loadout as it went in, the dropped backpack and k47 included',
      JSON.stringify(kept.map(r => r.id).sort()) === JSON.stringify(want), kept.map(r => r.id))
    check('the m4c came back without the red dot fitted in the raid', kept.some(r => r.id === 'm4c' && !r.optic),
      kept.filter(r => r.id === 'm4c').map(r => r.optic || 'no optic'))
    check('a void letter waits in the mailbox', (p.mail || []).filter(m => m.title === '服务器维护补偿' && !m.claimed).length === 1,
      (p.mail || []).map(m => m.title))
    check('no insurance mark left anywhere', noMarks(kept))
    return
  }
  await rcon('kill ' + name)
  await sleep(3000)
  if (variant === 'stop') {
    await stopServer()
  } else {
    // The last player dying ends the raid (cv end does nothing on a server up for less than a raid length).
    await rcon('kill ' + mate)
    await waitFor(() => bot.chats.some(c => c.includes('保险理赔')), 30000)
    check('payout announced', bot.chats.some(c => c.includes('保险理赔：4 件')), bot.chats.filter(c => c.includes('保险')))
    await leave(bot)
    buddy.quit()
  }
  const p = readProfile(name)
  const ids = p.mailbox.map(r => r.id).sort()
  check('mailbox has all 4 insured items', JSON.stringify(ids) === JSON.stringify(['armor_3', 'k47', 'm4c', 'pack_3']), ids)
  // Insurance returns an item as it was at deploy (issue #21): the red dot fitted in the raid stays there.
  check('the m4c came back as it was insured, without the red dot fitted in the raid',
    p.mailbox.some(r => r.id === 'm4c' && !r.optic), p.mailbox.filter(r => r.id === 'm4c').map(r => r.optic || 'no optic'))
  // The snapshot travelled with the m4c through the gunsmith and the reconnect, and with the k47 packed in the backpack.
  check('both guns came back with the 20 rounds they were insured with',
    ['m4c', 'k47'].every(id => p.mailbox.some(r => r.id === id && r.ammo === 20)), p.mailbox.filter(r => r.ammo !== undefined).map(r => `${r.id} ${r.ammo}`))
  check('no insurance mark left on mailed items', noMarks(p.mailbox))
}

/** Two raids running (two tiers), an insured player dead in each, then the server stops. */
async function insureTwoRaids () {
  const squads = [['Ia' + tag, 'Ib' + tag, 'NORMAL'], ['Ic' + tag, 'Id' + tag, 'CONFIDENTIAL']]
  for (const [owner, mate] of squads) {
    await prepare(owner, p => { p.autoInsure = true; p.money = 1000000; p.mailbox = []; p.loadout = insuredLoadout() })
    await prepare(mate, p => { p.loadout = insuredLoadout() }) // enough gear for the CONFIDENTIAL tier
  }
  const bots = []
  for (const [owner, mate, tier] of squads) {
    const pair = [await make(owner), await make(mate)]
    const before = pair.map(b => b.worldChanges)
    for (const b of pair) b.chat(`/cv queue proving ${tier} SOLO`)
    const ok = await waitFor(() => pair.every((b, i) => b.game.gameMode === 'adventure' && b.worldChanges > before[i]), 90000)
    check(tier + ' pair deployed', ok)
    bots.push(...pair)
  }
  const raids = await rcon('cv raids')
  check('two raids running', (raids.match(/RUNNING/g) || []).length === 2, raids)
  await sleep(4000)
  for (const [owner] of squads) {
    const bot = bots.find(b => b.username === owner)
    check(owner + ' insured', bot.chats.some(c => c.includes('已为 4 件装备投保')))
    await rcon('kill ' + owner)
  }
  await sleep(3000)
  await stopServer()
  for (const [owner] of squads) {
    const p = readProfile(owner)
    const ids = p.mailbox.map(r => r.id).sort()
    check(owner + ' got all 4 insured items back', JSON.stringify(ids) === JSON.stringify(['armor_3', 'k47', 'm4c', 'pack_3']), ids)
    check(owner + ' mailbox has no insurance mark', noMarks(p.mailbox))
  }
}

/** A disconnected player times out while an item they carry has an id items.yml no longer knows. */
async function missing () {
  const name = 'Ms' + tag
  await prepare(name, p => { p.mailbox = []; p.loadout = { 0: rec('medkit_s', { dura: 120 }), 1: rec('screws') } })
  const bot = await make(name)
  await deploy([bot])
  const configYml = path.join(DATA, 'config.yml')
  const config = fs.readFileSync(configYml, 'utf8')
  const original = fs.readFileSync(itemsYml, 'utf8')
  try {
    fs.writeFileSync(configYml, config.replace(/reconnect-seconds: \d+/, 'reconnect-seconds: 3'))
    fs.writeFileSync(itemsYml, original.replace(/^ {2}medkit_s:/m, '  medkit_s_renamed:'))
    log('reload without medkit_s, 3 s to reconnect:', await rcon('cv reload'))
    await leave(bot)
    await sleep(8000)
    const p = readProfile(name)
    check('counted as missing', p.reports.length > 0 && JSON.stringify(p.reports[0]).includes('断线超时'), p.reports[0])
    check('the unknown kit went to the mailbox', countIds(p.mailbox, 'medkit_s') === 1, p.mailbox.map(r => r.id))
  } finally {
    fs.writeFileSync(configYml, config)
    fs.writeFileSync(itemsYml, original)
    log('restored config.yml and items.yml:', await rcon('cv reload'))
  }
}

async function preset () {
  const name = 'Pr' + tag
  const loadout = { 0: rec('bandage'), 1: rec('screws'), 36: rec('pack_3') }
  for (let s = 18; s < 36; s++) loadout[s] = rec('medkit_s', { dura: 120 })
  await prepare(name, p => { p.loadout = loadout; p.presets = []; p.stash = [] })
  let bot = await make(name)
  let menu = await openWindow(bot, () => bot.chat('/cv'))
  const presets = await openWindow(bot, () => bot.clickWindow(21, 0, 0))
  await bot.clickWindow(10, 0, 0) // save the current loadout
  await sleep(600)
  bot.closeWindow(presets || menu)
  await leave(bot)
  // The backpack and its contents are lost; ten medkits are left in the stash.
  const p0 = readProfile(name)
  check('preset saved with the backpack', p0.presets.length === 1 && Object.values(p0.presets[0].slots).includes('pack_3'))
  p0.loadout = { 1: rec('screws') }
  p0.stash = Array.from({ length: 10 }, () => rec('medkit_s', { dura: 120 }))
  p0.money = 5000 // a bandage (920) yes, the backpack (43,700) no
  p0.presets[0].slots['5'] = 'gone_item' // an id items.yml does not have
  writeProfile(name, p0)
  bot = await make(name)
  menu = await openWindow(bot, () => bot.chat('/cv'))
  const again = await openWindow(bot, () => bot.clickWindow(21, 0, 0))
  await bot.clickWindow(10, 0, 0) // load it
  await sleep(800)
  bot.closeWindow(again || menu)
  check('chat lists what was not placed', bot.chats.some(c => c.includes('没有放上') && c.includes('急救包')))
  check('the unknown id is listed too', bot.chats.some(c => c.includes('没有放上') && c.includes('gone_item')))
  await leave(bot)
  const p = readProfile(name)
  const all = Object.values(p.loadout).concat(p.stash)
  check('ten medkits still there', countIds(all, 'medkit_s') === 10, { stash: p.stash.map(r => r.id), loadout: Object.values(p.loadout).map(r => r.id) })
  // The preset has them on keys 1 and 2, which take guns only (#10): the bandage goes to a quick key,
  // and the screws, with no rig or backpack to carry them, back to the stash.
  const slotsOf = id => Object.entries(p.loadout).filter(([, r]) => r.id === id).map(([slot]) => Number(slot))
  check('the bandage carried on a quick key', slotsOf('bandage').length === 1 && slotsOf('bandage')[0] >= 3 && slotsOf('bandage')[0] <= 7, p.loadout)
  check('the screws kept in the stash, not on a gun key', slotsOf('screws').length === 0 && countIds(p.stash, 'screws') === 1, slotsOf('screws'))
  check('only the bandage was paid for', p.money === 5000 - 920, p.money)
}

async function season () {
  const name = 'Se' + tag
  await prepare(name, p => {
    p.seasonId = 'S1'
    p.seasonXp = 12500
    p.seasonClaimed = []
    p.loadout = {}
    p.mailbox = []
    p.stash = [rec('medkit_l', { amount: 2 })] // an oversized record saved by an older build
  })
  const bot = await make(name)
  await openWindow(bot, () => bot.chat('/cv'))
  const pass = await openWindow(bot, () => bot.clickWindow(24, 0, 0))
  await bot.clickWindow(20, 0, 0) // tier 12: two military med cases
  await sleep(600)
  bot.closeWindow(pass)
  await openWindow(bot, () => bot.chat('/cv stash'))
  const stash = await claimMailbox(bot)
  for (let i = 0; i < 5; i++) { await bot.clickWindow(9, 0, 0); await sleep(500) } // the first stash cell (0-8 are the tabs)
  if (bot.currentWindow || stash) bot.closeWindow(bot.currentWindow || stash)
  await resync(bot) // the stash retitles itself on every take, and mineflayer then stops mirroring the inventory
  const cases = carried(bot).filter(s => cid(s) === 'medkit_l').length
  check('four military med cases taken (2 from the pass, 2 from the old record)', cases === 4, carriedIds(bot))
  await leave(bot)
  const p = readProfile(name)
  check('none left behind in the stash', countIds(p.stash, 'medkit_l') === 0, p.stash)
  check('all four in the loadout', countIds(Object.values(p.loadout), 'medkit_l') === 4)
}

const scenarios = { reload, unknown, raid, cursorpack, insure, missing, preset, season }

;(async () => {
  try {
    await scenarios[scenario]()
  } catch (e) {
    failures++
    log('ERROR', e.stack)
  }
  log(failures === 0 ? 'ALL PASSED' : failures + ' FAILED')
  process.exit(failures === 0 ? 0 : 1)
})()
