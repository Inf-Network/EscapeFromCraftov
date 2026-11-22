// Checks for the silent item losses of issue #18: a broken or changed items.yml, items held on the
// cursor, loading a preset without its backpack, insurance marks, full bodies and season rewards.
//
//   node itemloss.js reload | unknown | raid | insure [end|stop|offline|two] | missing | preset | season
//
// insure: end = the raid ends normally; stop = the server stops after the owner died; offline = it
// stops while the owner is disconnected; two = two raids are running when it stops. The three stop
// variants leave the server stopped.
//
// Env: PORT / RCON_PORT / RCON_PASS of the local test server (default 25565 / 25575 / craftov-local),
// CRAFTOV_DATA = its plugins/Craftov folder (default ../../server/run/plugins/Craftov). Profiles are
// prepared by editing the player's JSON while the bot is offline, then checked the same way.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')

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

const carried = bot => bot.inventory.slots.slice(5, 45).filter(s => s && !String(cid(s)).startsWith('ui/'))
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
  const stash = await openWindow(bot, () => bot.chat('/cv stash'))
  await bot.clickWindow(50, 0, 0) // claim the mailbox
  await sleep(600)
  const at = top(stash).findIndex(s => cid(s) === 'medkit_s')
  check('restored item shows in the stash', at >= 0)
  if (at >= 0) { await bot.clickWindow(at, 0, 0); await sleep(600) }
  bot.closeWindow(stash)
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
    const items = top(w).filter(Boolean).length
    check('body has 5 rows and all 38 items', w.inventoryStart === 45 && items === 38, { rows: w.inventoryStart / 9, items })
    looter.closeWindow(w)
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
    await waitFor(() => top(w).some(s => cid(s) === 'pack_3'), 10000)
    const pack = top(w).find(s => cid(s) === 'pack_3')
    check('body holds the backpack from the cursor with its 3 items', !!pack && text(pack).includes('内含 3'), top(w).filter(Boolean).map(cid))
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
  check('gold bar back in the inventory after extracting', carried(looter).some(s => cid(s) === 'gold_bar'), carriedIds(looter))
  const report = looter.chats.find(c => c.includes('带出') || c.includes('撤离成功'))
  log('report line', report || '(none)')
  for (const b of [full38, cursor, looter]) await leave(b)
  const p = readProfile(names[2])
  check('gold bar saved in the loadout', countIds(Object.values(p.loadout), 'gold_bar') === 1, Object.values(p.loadout).map(r => r.id))
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

async function insure () {
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
    // Stopping the server pays those out and restores the loadout: both to the offline profile.
    bot.quit()
    await sleep(3000)
    await stopServer()
    const p = readProfile(name)
    const ids = p.mailbox.map(r => r.id).sort()
    check('mailbox has the dropped backpack and the gun inside it', JSON.stringify(ids) === JSON.stringify(['k47', 'pack_3']), ids)
    const kept = Object.values(p.loadout)
    check('loadout restored with the m4c and the armour', countIds(kept, 'm4c') === 1 && countIds(kept, 'armor_3') === 1, kept.map(r => r.id))
    check('no insurance mark left anywhere', noMarks(p.mailbox) && noMarks(kept))
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
  const presets = await openWindow(bot, () => bot.clickWindow(19, 0, 0))
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
  const again = await openWindow(bot, () => bot.clickWindow(19, 0, 0))
  await bot.clickWindow(10, 0, 0) // load it
  await sleep(800)
  bot.closeWindow(again || menu)
  check('chat lists what was not placed', bot.chats.some(c => c.includes('没有放上') && c.includes('急救包')))
  check('the unknown id is listed too', bot.chats.some(c => c.includes('没有放上') && c.includes('gone_item')))
  await leave(bot)
  const p = readProfile(name)
  const all = Object.values(p.loadout).concat(p.stash)
  check('ten medkits still there', countIds(all, 'medkit_s') === 10, { stash: p.stash.map(r => r.id), loadout: Object.values(p.loadout).map(r => r.id) })
  check('screws and bandage carried', countIds(Object.values(p.loadout), 'screws') === 1 && countIds(Object.values(p.loadout), 'bandage') === 1)
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
  const pass = await openWindow(bot, () => bot.clickWindow(33, 0, 0))
  await bot.clickWindow(20, 0, 0) // tier 12: two military med cases
  await sleep(600)
  bot.closeWindow(pass)
  const stash = await openWindow(bot, () => bot.chat('/cv stash'))
  await bot.clickWindow(50, 0, 0) // claim the mailbox
  await sleep(600)
  for (let i = 0; i < 5; i++) { await bot.clickWindow(0, 0, 0); await sleep(500) }
  bot.closeWindow(stash)
  const cases = carried(bot).filter(s => cid(s) === 'medkit_l').length
  check('four military med cases taken (2 from the pass, 2 from the old record)', cases === 4, carriedIds(bot))
  await leave(bot)
  const p = readProfile(name)
  check('none left behind in the stash', countIds(p.stash, 'medkit_l') === 0, p.stash)
  check('all four in the loadout', countIds(Object.values(p.loadout), 'medkit_l') === 4)
}

const scenarios = { reload, unknown, raid, insure, missing, preset, season }

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
