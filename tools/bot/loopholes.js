// Money loopholes (issue #21): free kit, kit gear value, stash overfill, escort contracts,
// insured backpacks and guns, gear and stacks handed between players, rank points and experience
// for extracting, self-kills and the house bounty (see bounty() and rank() for their setup).
//
//   PORT=25565 RCON_PORT=25575 node loopholes.js [kit|stash|overfull|escort|escortraid|insurance|
//                                                 insgun|profit|stacks|selfkill|rank|bounty|all]
//
// CRAFTOV_RUN points at the server directory (default ../../server/run) so the script can read
// player profiles from plugins/Craftov/data/players; insurance and insgun set insurance.loss-chance
// and return-minutes to 0 in its config.yml while they run (insurance.js). Bots use random names, so
// they start as new accounts (starter loadout, ◈150,000). Prints PASS/FAIL lines and exits 1 on any
// failure.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const rcon = require('./rcon')
const pinInsurance = require('./insurance')
const env = require('./env')

const PORT = env.PORT
const RCON_PORT = env.RCON_PORT
const RCON_PASSWORD = env.RCON_PASSWORD
const RUN = env.SERVER_DIR
const PLAYERS = path.join(RUN, 'plugins/Craftov/data/players')
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const cmd = c => rcon(c, { port: RCON_PORT, password: RCON_PASSWORD }).then(r => r.replace(/§./g, ''))
const results = []
function check (name, ok, detail) {
  results.push({ name, ok })
  log(ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : detail)
}

// ------------------------------------------------------------------ bots

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes). */
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
  return joinAs(prefix + tag)
}

function joinAs (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.msgs = []
  bot.worldChanges = 0
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.msgs.push(m); log(name, 'CHAT', m) } })
  // Paper sends action bars as their own packet, which mineflayer does not turn into an event here.
  bot._client.on('action_bar', p => {
    const s = flat(p.text)
    if (s.trim()) { bot.msgs.push(s); log(name, 'BAR', s) }
  })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(250)
  }
  return false
}

/** Waits for a chat or action-bar line matching re that arrived after mark (bot.msgs.length). */
async function heard (bot, re, mark, ms = 5000) {
  let line = null
  await waitFor(() => { line = bot.msgs.slice(mark).find(m => re.test(m)) || null; return line !== null }, ms)
  return line
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
const isKit = item => json(item).includes('"craftov:kit"')
const isLocked = item => json(item).includes('craftov:ui/locked')
/** Window slot of player inventory index i (0-8 hotbar, 9-35 main) in an open container window. */
const bottom = (w, i) => i < 9 ? w.inventoryStart + 27 + i : w.inventoryStart + i - 9
/** Player inventory index of the first carried item matching pred, or -1. */
function findCarried (w, pred) {
  for (let i = 0; i < 36; i++) {
    if (i === 8) continue
    const it = w.slots[bottom(w, i)]
    if (it && !isLocked(it) && pred(it)) return i
  }
  return -1
}
function freeCells (w) {
  let n = 0
  for (let i = 0; i < 36; i++) {
    if (i === 2 || i === 8) continue
    if (!w.slots[bottom(w, i)]) n++
  }
  return n
}
function stashUsed (w) {
  const m = json(w.slots[49]).match(/仓库 (\d+)\/(\d+)/)
  return m ? { used: Number(m[1]), cap: Number(m[2]) } : null
}

/**
 * Money the server announced paying bot in the lines since mark: daily contracts ("[每日任务完成] …
 * +◈7,000"), the all-done bonus, level-ups, collection milestones and the guide ("奖励 ◈…"). A
 * refund comes without a line, so a balance change minus these is what was refunded.
 */
function announcedSince (bot, mark) {
  let sum = 0
  for (const m of bot.msgs.slice(mark)) {
    const r = m.match(/\[每日任务完成\].*\+◈([\d,]+)/) || m.match(/奖励 ◈([\d,]+)/)
    if (r) sum += Number(r[1].replace(/,/g, ''))
  }
  return sum
}

async function money (name) {
  const m = (await cmd(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}

function profile (name) {
  for (const file of fs.readdirSync(PLAYERS)) {
    if (!file.endsWith('.json')) continue
    try {
      const p = JSON.parse(fs.readFileSync(path.join(PLAYERS, file), 'utf8'))
      if (p.name === name) return p
    } catch (e) { /* being written */ }
  }
  return null
}

async function enterRaid (bots, queue) {
  const before = bots.map(b => b.worldChanges)
  queue()
  const ok = await waitFor(() => bots.every((b, i) => b.worldChanges > before[i] && b.game.gameMode === 'adventure'), 90000)
  await sleep(3500)
  return ok
}

async function extract (bot) {
  const before = bot.worldChanges
  await cmd(`execute as ${bot.username} at @s run tp @s 106.5 65 8.5`)
  return waitFor(() => bot.worldChanges > before, 25000)
}

/** Opens the proving ground's armory container (8.5 65 77.5). */
async function openArmory (bot) {
  await cmd(`execute as ${bot.username} at @s run tp @s 8.5 65 78.9 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return openWith(bot, () => bot.activateEntity(target))
}

/** Puts everything the player carries in their cells into the stash (equipment stays on). */
// The stash retitles itself when its page count changes (the window is sent again under the same
// id), and mineflayer then hands out a new window object: read bot.currentWindow after every click.
async function depositAll (bot, w) {
  for (let round = 0; round < 40; round++) {
    const i = findCarried(w, it => idOf(it) && idOf(it) !== 'knife_starter')
    if (i < 0) return w
    await bot.clickWindow(bottom(w, i), 0, 1) // shift-click: every stack of that item
    await sleep(350)
    w = bot.currentWindow || w
  }
  return w
}

/** Slot of the menu item whose components mention who (escort contract lists), or -1. */
function slotOf (w, who) {
  for (let s = 0; s < 27; s++) if (json(w.slots[s]).includes(who)) return s
  return -1
}

/** Waits for the newest raid report of name, newer than count reports, and returns it. */
async function nextReport (name, count) {
  await waitFor(() => { const pr = profile(name); return pr && pr.reports && pr.reports.length > count }, 15000)
  const pr = profile(name)
  return pr && pr.reports ? pr.reports[0] : null
}

const reportCount = name => { const pr = profile(name); return pr && pr.reports ? pr.reports.length : 0 }

// ------------------------------------------------------------------ scenarios

/** Free kit: a new account that lost its loadout gets one, kit gear sells for ◈0 and cannot be listed, a rich one is refused. */
async function kit () {
  const k = await make('Kit')
  await cmd(`clear ${k.username}`)
  await sleep(1500)
  let w = await openWith(k, () => k.chat('/cv'))
  let mark = k.msgs.length
  await k.clickWindow(30, 0, 0)
  check('kit: new account without gear (about ◈180,000 left) can claim the free kit', !!await heard(k, /已领取免费补给/, mark))
  k.closeWindow(w)
  await sleep(500)
  await cmd(`cv give ${k.username} medkit_s`)
  await sleep(800)
  w = await openWith(k, () => k.chat('/cv trader'))
  const kitMed = findCarried(w, it => idOf(it) === 'medkit_s' && isKit(it))
  const ownMed = findCarried(w, it => idOf(it) === 'medkit_s' && !isKit(it))
  check('kit: kit items carry the free-kit mark', kitMed >= 0, `kit medkit at ${kitMed}, plain medkit at ${ownMed}`)
  mark = k.msgs.length
  if (kitMed >= 0) await k.clickWindow(bottom(w, kitMed), 0, 0)
  const kitPrice = await heard(k, /商人收购价/, mark)
  mark = k.msgs.length
  if (ownMed >= 0) await k.clickWindow(bottom(w, ownMed), 0, 0)
  const ownPrice = await heard(k, /商人收购价/, mark)
  check('kit: trader pays ◈0 for a kit medkit', /◈0(\D|$)/.test(kitPrice || ''), kitPrice)
  check('kit: trader still pays for a bought medkit', /◈1,925/.test(ownPrice || ''), ownPrice)
  k.closeWindow(w)
  // Deposit the kit medkit and try to list it.
  w = await openWith(k, () => k.chat('/cv stash'))
  const again = findCarried(w, it => idOf(it) === 'medkit_s' && isKit(it))
  if (again >= 0) { await k.clickWindow(bottom(w, again), 0, 0); await sleep(500) }
  w = k.currentWindow || w // the stash retitles itself on a deposit
  let listSlot = -1
  for (let s = 9; s < 45; s++) if (idOf(w.slots[s]) === 'medkit_s' && isKit(w.slots[s])) listSlot = s
  mark = k.msgs.length
  let priceMenu = null
  // A right-click lists an item on the market (#10: the stash has no modes).
  if (listSlot >= 0) priceMenu = await openWith(k, () => k.clickWindow(listSlot, 1, 0), 2500)
  check('kit: a kit item cannot be listed on the market', listSlot >= 0 && !priceMenu && !!await heard(k, /不能上架交易行/, mark),
    `stash slot ${listSlot}, price menu opened: ${!!priceMenu}`)
  k.closeWindow(priceMenu || w)
  k.quit()

  // An account with ◈1,120,000 of armour in the stash.
  const r = await make('Rich')
  for (let n = 0; n < 4; n++) await cmd(`cv give ${r.username} armor_6`)
  await sleep(800)
  w = await openWith(r, () => r.chat('/cv stash'))
  const armor = findCarried(w, it => idOf(it) === 'armor_6')
  if (armor >= 0) { await r.clickWindow(bottom(w, armor), 0, 1); await sleep(600) }
  log('Rich', 'stash', stashUsed(w))
  r.closeWindow(w)
  await cmd(`clear ${r.username}`)
  await sleep(1500)
  w = await openWith(r, () => r.chat('/cv'))
  mark = r.msgs.length
  await r.clickWindow(30, 0, 0)
  const refused = await heard(r, /总资产|已领取免费补给/, mark)
  check('kit: an account with a stash worth over ◈1,000,000 is refused, with the reason', /总资产/.test(refused || ''), refused)
  r.closeWindow(w)
  r.quit()
}

/** Stash 120/120 and one free cell: the ammo refill (two stacks for a rifle) must not push the stash over. */
async function stash () {
  const s = await make('Stash')
  let w = await openWith(s, () => s.chat('/cv stash'))
  w = await depositAll(s, w)
  let st = stashUsed(w)
  log('Stash', 'after deposit', st, 'free cells', freeCells(w))
  for (let round = 0; round < 20 && st && st.used < st.cap; round++) {
    const n = Math.min(st.cap - st.used, freeCells(w))
    for (let i = 0; i < n; i++) await cmd(`cv give ${s.username} armor_1`)
    await sleep(800)
    const i = findCarried(w, it => idOf(it) === 'armor_1')
    if (i >= 0) await s.clickWindow(bottom(w, i), 0, 1)
    await sleep(800)
    w = s.currentWindow || w
    st = stashUsed(w)
  }
  log('Stash', 'filled', st)
  s.closeWindow(w)
  await cmd(`cv give ${s.username} k47`)
  await sleep(600)
  w = await openWith(s, () => s.chat('/cv trader'))
  const fill = freeCells(w) - 1
  w && s.closeWindow(w)
  // Medkits fill the quick keys, the rig and the backpack alike (#10: armour only fits the backpack).
  for (let i = 0; i < fill; i++) await cmd(`cv give ${s.username} medkit_s`)
  await sleep(800)
  w = await openWith(s, () => s.chat('/cv trader'))
  log('Stash', 'free cells before refill', freeCells(w))
  await s.clickWindow(53, 0, 0); await sleep(400) // services tab
  const mark = s.msgs.length
  await s.clickWindow(16, 0, 0) // refill ammo
  const refused = await heard(s, /放不下/, mark)
  s.closeWindow(w)
  w = await openWith(s, () => s.chat('/cv stash'))
  const after = stashUsed(w)
  check('stash: refilling two stacks with one free cell and a full stash is refused', !!refused && after && after.used <= after.cap,
    `${refused} · stash ${after && after.used}/${after && after.cap}`)
  s.closeWindow(w)
  s.quit()
}

/** Escort contracts: one at a time, a kicked escort frees the slot and is not paid, a real escort is. */
async function escort () {
  const c = await make('Boss')
  const c2 = await make('Boss2')
  const e = await make('Guard')
  for (const client of [c, c2]) {
    await openWith(client, () => client.chat('/cv'))
    const menu = await openWith(client, () => client.clickWindow(16, 0, 0))
    await client.clickWindow(38, 0, 0) // post a ◈20,000 contract
    await sleep(500)
    client.closeWindow(menu)
  }
  await openWith(e, () => e.chat('/cv'))
  let list = await openWith(e, () => e.clickWindow(16, 0, 0))
  let mark = e.msgs.length
  await e.clickWindow(slotOf(list, c.username), 0, 0)
  check('escort: accepting a contract works', !!await heard(e, /已接取/, mark))
  mark = e.msgs.length
  await e.clickWindow(slotOf(list, c2.username), 0, 0)
  // The refusal names the client the escort is guarding (#63), not the contract they clicked.
  const second = await heard(e, /你正在护航 .*，离开其小队后才能接取别的委托/, mark)
  check('escort: a second contract is refused', !!second && second.includes(c.username) && !second.includes(c2.username), second)
  e.closeWindow(list)
  mark = e.msgs.length
  c.chat(`/cv party kick ${e.username}`)
  check('escort: a kicked escort is told the contract is gone', !!await heard(e, /护航委托已放弃/, mark))
  await openWith(e, () => e.chat('/cv'))
  list = await openWith(e, () => e.clickWindow(16, 0, 0))
  const lore = json(list.slots[slotOf(list, c.username)])
  check('escort: the slot opens again in the lobby', lore.includes('护航 0/2') && lore.includes('等待护航'))
  e.closeWindow(list)
  // Kicked escort in the same raid: the client extracts, the escort gets nothing, the client gets it all back.
  const cBefore = await money(c.username)
  const eBefore = await money(e.username)
  const inRaid = await enterRaid([c, e], () => { c.chat('/cv queue proving NORMAL SOLO'); e.chat('/cv queue proving NORMAL SOLO') })
  log('both', 'in raid', inRaid)
  check('escort: client extracted', await extract(c))
  await sleep(1500)
  const cAfter = await money(c.username)
  const eAfter = await money(e.username)
  check('escort: a kicked escort in the same raid is not paid', eAfter === eBefore, `escort ${eBefore} -> ${eAfter}`)
  await extract(e)
  await sleep(1500)
  // With nobody on it the contract never went into the raid: it is still open, reward in escrow.
  await openWith(e, () => e.chat('/cv'))
  list = await openWith(e, () => e.clickWindow(16, 0, 0))
  const still = json(list.slots[slotOf(list, c.username)])
  check('escort: the contract stays open for the client, reward still in escrow', still.includes('等待护航') && cAfter === cBefore,
    `client ${cBefore} -> ${cAfter}`)
  // A real escort is still paid.
  await e.clickWindow(slotOf(list, c.username), 0, 0); await sleep(500)
  e.closeWindow(list)
  const ePaid = await money(e.username)
  await enterRaid([c, e], () => c.chat('/cv queue proving NORMAL TRIO'))
  await extract(c)
  await sleep(1500)
  check('escort: an escort in the client\'s squad is paid', (await money(e.username)) - ePaid === 20000)
  await extract(e)
  // Clean up the second contract.
  await openWith(c2, () => c2.chat('/cv'))
  const menu = await openWith(c2, () => c2.clickWindow(16, 0, 0))
  const own = slotOf(menu, c2.username)
  if (own >= 0) await c2.clickWindow(own, 0, 0)
  await sleep(500)
  for (const b of [c, c2, e]) b.quit()
}

/** Stash already over its capacity (older builds): the free kit still comes when it all fits on the player. */
async function overfull () {
  const name = 'Over' + tag
  let o = await joinAs(name)
  o.quit()
  await sleep(4000)
  const file = fs.readdirSync(PLAYERS).map(f => path.join(PLAYERS, f)).find(f => {
    try { return JSON.parse(fs.readFileSync(f, 'utf8')).name === name } catch (e) { return false }
  })
  const data = JSON.parse(fs.readFileSync(file, 'utf8'))
  data.stashCapacity = 3
  fs.writeFileSync(file, JSON.stringify(data, null, 2))
  log(name, 'stash', data.stash.length, 'cells, capacity set to', data.stashCapacity)
  o = await joinAs(name)
  await cmd(`clear ${name}`)
  await sleep(1500)
  const w = await openWith(o, () => o.chat('/cv'))
  const mark = o.msgs.length
  await o.clickWindow(30, 0, 0)
  const line = await heard(o, /已领取免费补给|仓库空间不足|不能领取|总资产/, mark)
  check('overfull: with the stash over capacity and nothing to store, the free kit is handed out', /已领取免费补给/.test(line || ''), line)
  o.closeWindow(w)
  o.quit()
}

/** Insured backpack packed with loot and left in a box comes home empty. */
async function insurance () {
  const p = await make('Ins')
  let w = await openWith(p, () => p.chat('/cv deploy'))
  await p.clickWindow(34, 0, 0) // auto insurance on
  await sleep(400)
  p.closeWindow(w)
  await cmd(`cv give ${p.username} pack_2`)
  await sleep(500)
  const mark = p.msgs.length
  check('insurance: deployed', await enterRaid([p], () => p.chat('/cv queue proving NORMAL SOLO')))
  check('insurance: gear insured on deploy', !!await heard(p, /件装备投保/, mark, 2000))
  for (let i = 0; i < 12; i++) await cmd(`cv give ${p.username} armor_6`)
  await sleep(800)
  w = await openArmory(p)
  if (!w) { check('insurance: armory opened', false); p.quit(); return }
  const pack2 = findCarried(w, it => idOf(it) === 'pack_2')
  if (pack2 >= 0) { await p.clickWindow(bottom(w, pack2), 0, 1); await sleep(500) }
  let boxSlot = -1
  for (let s = 0; s < w.inventoryStart; s++) if (idOf(w.slots[s]) === 'pack_2') boxSlot = s
  if (boxSlot >= 0) { await p.clickWindow(boxSlot, 1, 0); await sleep(600) } // right-click: swap backpacks
  const packed = w.slots[boxSlot]
  const inside = (json(packed).match(/内含 (\d+) 件物品/) || [])[1]
  check('insurance: the worn backpack went into the box packed with loot', idOf(packed) === 'pack_1' && Number(inside) > 0, `slot ${boxSlot}: ${idOf(packed)} with ${inside}`)
  p.closeWindow(w)
  // Die (cv end does nothing while the server has been up for less than a raid's length).
  await cmd(`damage ${p.username} 1000 minecraft:generic`)
  await waitFor(() => { const pr = profile(p.username); return pr && pr.mailbox && pr.mailbox.some(r => r.id === 'pack_1') }, 40000)
  const mail = (profile(p.username) || { mailbox: [] }).mailbox
  const back = mail.find(r => r.id === 'pack_1')
  log('Ins', 'mailbox', mail.map(r => r.id + (r.contents ? `[${r.contents.length}]` : '')))
  check('insurance: the insured backpack comes home empty', !!back && !(back.contents && back.contents.length), back && JSON.stringify(back.contents || []).slice(0, 80))
  p.quit()
}

/** An insured gun reloaded with looted rounds in the raid comes home with only what it was insured with. */
async function insgun () {
  const p = await make('InsG')
  let w = await openWith(p, () => p.chat('/cv deploy'))
  await p.clickWindow(34, 0, 0) // auto insurance on
  await sleep(400)
  p.closeWindow(w)
  p.setQuickBarSlot(0) // starter k47, full of 7.62 PS
  const mark = p.msgs.length
  check('insgun: deployed', await enterRaid([p], () => p.chat('/cv queue proving NORMAL SOLO')))
  check('insgun: gear insured on deploy', !!await heard(p, /件装备投保/, mark, 2000))
  await sleep(1500)
  await cmd(`cv give ${p.username} ammo_762_ap 60`)
  await sleep(800)
  p.setQuickBarSlot(0)
  await sleep(300)
  // Sneak + Q: reload with the next ammo type (the looted AP rounds).
  p.setControlState('sneak', true)
  await sleep(400)
  p._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  await sleep(400)
  p.setControlState('sneak', false)
  await sleep(4500)
  p.setQuickBarSlot(1) // switching away writes the magazine onto the stack
  await sleep(800)
  const k47 = p.inventory.slots[36]
  const loaded = (json(k47).match(/"craftov:ammo_type":\{"type":"string","value":"([^"]+)"/) || [])[1]
  check('insgun: the insured k47 now holds the looted AP rounds', loaded === 'ammo_762_ap', loaded)
  await cmd(`damage ${p.username} 1000 minecraft:generic`)
  await waitFor(() => { const pr = profile(p.username); return pr && pr.mailbox && pr.mailbox.some(r => r.id === 'k47') }, 40000)
  const mail = (profile(p.username) || { mailbox: [] }).mailbox
  log('InsG', 'mailbox', mail.map(r => `${r.id} ${r.ammo || 0}x${r.ammoType || '-'}`))
  const gun = mail.find(r => r.id === 'k47')
  check('insgun: the k47 comes home without the looted rounds', gun && (gun.ammo || 0) === 0 && gun.ammoType === 'ammo_762_ps', gun && `${gun.ammo} x ${gun.ammoType}`)
  const pistol = mail.find(r => r.id === 'p18')
  check('insgun: an untouched insured pistol comes home with its own rounds', pistol && pistol.ammo > 0 && pistol.ammoType === 'ammo_9_fmj', pistol && `${pistol.ammo} x ${pistol.ammoType}`)
  p.quit()
}

/** A hands his guns to B in a box; B extracts with them and they do not count as B's profit. */
async function profit () {
  const a = await make('Giver')
  const b = await make('Taker')
  const ok = await enterRaid([a, b], () => { a.chat('/cv queue proving NORMAL SOLO'); b.chat('/cv queue proving NORMAL SOLO') })
  log('both', 'in raid', ok)
  let w = await openArmory(a)
  if (!w) { check('profit: armory opened', false); a.quit(); b.quit(); return }
  const empty = new Set()
  for (let s = 0; s < w.inventoryStart; s++) if (!w.slots[s]) empty.add(s)
  for (const id of ['k47', 'p18']) {
    const i = findCarried(w, it => idOf(it) === id)
    if (i >= 0) { await a.clickWindow(bottom(w, i), 0, 1); await sleep(400) }
  }
  const handed = [...empty].filter(s => ['k47', 'p18'].includes(idOf(w.slots[s])))
  a.closeWindow(w)
  log('Giver', 'left in the box at', handed)
  w = await openArmory(b)
  for (const s of handed) { await b.clickWindow(s, 0, 0); await sleep(400) }
  b.closeWindow(w)
  const mark = b.msgs.length
  check('profit: taker extracted', await extract(b))
  const note = await heard(b, /其他玩家带进本局的装备/, mark, 5000)
  const value = Number(((note || '').match(/◈([\d,]+)/) || ['', '0'])[1].replace(/,/g, ''))
  check('profit: the guns handed over are left out of the taker\'s profit', value >= 26000, note)
  await waitFor(() => { const pr = profile(b.username); return pr && pr.reports && pr.reports.length > 0 }, 15000)
  const report = (profile(b.username) || { reports: [{}] }).reports[0]
  check('profit: the taker\'s report shows no profit from them', report && report.extractedValue < 1000, report && report.extractedValue)
  await extract(a)
  a.quit(); b.quit()
}

/** A hands three stacks of .338 rounds to B in a box; only B's own finds count as B's profit. */
async function stacks () {
  const a = await make('Pile')
  const b = await make('Heap')
  for (let i = 0; i < 3; i++) await cmd(`cv give ${a.username} ammo_338_fmj 30`)
  await sleep(800)
  const ok = await enterRaid([a, b], () => { a.chat('/cv queue proving NORMAL SOLO'); b.chat('/cv queue proving NORMAL SOLO') })
  log('both', 'in raid', ok)
  let w = await openArmory(a)
  if (!w) { check('stacks: armory opened', false); a.quit(); b.quit(); return }
  const empty = new Set()
  for (let s = 0; s < w.inventoryStart; s++) if (!w.slots[s]) empty.add(s)
  for (let n = 0; n < 3; n++) {
    const i = findCarried(w, it => idOf(it) === 'ammo_338_fmj')
    if (i >= 0) { await a.clickWindow(bottom(w, i), 0, 1); await sleep(400) }
  }
  const handed = [...empty].filter(s => idOf(w.slots[s]) === 'ammo_338_fmj')
  a.closeWindow(w)
  log('Pile', 'left .338 in the box at', handed)
  w = await openArmory(b)
  for (const s of handed) { await b.clickWindow(s, 0, 0); await sleep(400) }
  b.closeWindow(w)
  // B's own finds: 5.56 nobody brought, and 7.62 PS while A still holds all of his.
  await cmd(`cv give ${b.username} ammo_556_m855 60`)
  await cmd(`cv give ${b.username} ammo_762_ps 60`)
  await sleep(800)
  const before = reportCount(b.username)
  const mark = b.msgs.length
  check('stacks: taker extracted', await extract(b))
  const note = await heard(b, /其他玩家带进本局的装备/, mark, 5000)
  const value = Number(((note || '').match(/◈([\d,]+)/) || ['', '0'])[1].replace(/,/g, ''))
  check('stacks: the 90 .338 rounds handed over (◈28,800) are left out of the taker\'s profit', value === 28800, note)
  const report = await nextReport(b.username, before)
  check('stacks: the taker\'s own finds still count (60 × ◈32 + 60 × ◈30 = ◈3,720)', report && report.extractedValue === 3720, report && report.extractedValue)
  await extract(a)
  a.quit(); b.quit()
}

/** Escort roster is fixed in the raid: a kicked escort and one who extracted and logged off are both paid. */
async function escortraid () {
  const c = await make('Lead')
  const e1 = await make('EscA')
  const e2 = await make('EscB')
  await openWith(c, () => c.chat('/cv'))
  const posted = await openWith(c, () => c.clickWindow(16, 0, 0))
  await c.clickWindow(38, 0, 0) // post a ◈20,000 contract
  await sleep(500)
  c.closeWindow(posted)
  for (const e of [e1, e2]) {
    await openWith(e, () => e.chat('/cv'))
    const list = await openWith(e, () => e.clickWindow(16, 0, 0))
    const mark = e.msgs.length
    await e.clickWindow(slotOf(list, c.username), 0, 0)
    check(`escortraid: ${e.username} accepts`, !!await heard(e, /已接取/, mark))
    e.closeWindow(list)
  }
  const cBefore = await money(c.username)
  const cMark = c.msgs.length
  const e1Before = await money(e1.username)
  check('escortraid: squad deployed', await enterRaid([c, e1, e2], () => c.chat('/cv queue proving NORMAL TRIO')))
  c.chat(`/cv party kick ${e1.username}`)
  await sleep(1500)
  check('escortraid: escort B extracted first', await extract(e2))
  await sleep(1500)
  e2.quit()
  await sleep(3000)
  check('escortraid: client extracted', await extract(c))
  await sleep(2500)
  const e1Paid = (await money(e1.username)) - e1Before
  // B's balance also moved with his own extraction (level-up and task rewards): read the escort income.
  const e2Paid = (((profile(e2.username) || {}).stats || {}).escort_income || 0)
  // The client's balance also moves with its own extraction: a daily contract to extract once
  // pays ◈7,000 right then, and its experience can bring a level-up. Those are announced; the
  // escort refund is not, so it is what is left of the change.
  const cAfter = await money(c.username)
  const cRewards = announcedSince(c, cMark)
  const cRefund = cAfter - cBefore - cRewards
  check('escortraid: the escort kicked in the raid is still paid', e1Paid === 10000, e1Paid)
  check('escortraid: the escort who extracted and logged off is still paid', e2Paid === 10000, e2Paid)
  check('escortraid: the client gets nothing back', cRefund === 0,
    `${cBefore} -> ${cAfter}, of which rewards ${cRewards}: refund ${cRefund}`)
  const escrow = (profile(c.username) || {}).escortEscrow
  check('escortraid: the client\'s escrow is closed', !escrow, escrow)
  await extract(e1)
  c.quit(); e1.quit()
}

/** Your own grenade is no kill: no kill action bar (and so no task or bounty credit) for the thrower. */
async function selfkill () {
  const s = await make('Nade')
  check('selfkill: deployed', await enterRaid([s], () => s.chat('/cv queue proving NORMAL SOLO')))
  await sleep(2000)
  await cmd(`cv give ${s.username} frag 2`)
  await sleep(800)
  let hot = -1
  for (let i = 36; i <= 44; i++) if (idOf(s.inventory.slots[i]) === 'frag') hot = i - 36
  check('selfkill: frag in the hotbar', hot >= 0, hot)
  s.setQuickBarSlot(Math.max(0, hot))
  await s.look(0, -Math.PI / 2, true)
  const mark = s.msgs.length
  const dead = s.worldChanges
  // The first right click may still fall in the landing protection, and one frag at your feet
  // through the starter helmet and vest takes about 65: it takes both to die.
  for (let n = 0; n < 3 && !s.msgs.slice(mark).some(m => /行动失败|结果/.test(m)); n++) {
    s.activateItem()
    await sleep(250)
    s.deactivateItem()
    await sleep(6000)
  }
  const died = s.msgs.slice(mark).some(m => /行动失败|结果|破片手雷/.test(m)) || s.worldChanges > dead
  check('selfkill: the thrower died to his own grenade', died, s.msgs.slice(mark).filter(m => /结果|破片/.test(m)))
  check('selfkill: no kill credited for it', !s.msgs.slice(mark).some(m => /✖ 击杀/.test(m)))
  await sleep(12000)
  s.quit()
}

/**
 * Rank points and experience for extracting: none for a naked run, a loss capped at 20 (halved
 * below 1,100 points). Ranked raids need level 3, so set rank.min-level to 0 in the server's
 * plugins/Craftov/config.yml first; not part of "all".
 */
async function rank () {
  const r = await make('Rank')
  await cmd(`clear ${r.username}`)
  await sleep(1000)
  let before = reportCount(r.username)
  check('rank: naked ranked raid', await enterRaid([r], () => r.chat('/cv queue proving NORMAL SOLO true')))
  await extract(r)
  let report = await nextReport(r.username, before)
  check('rank: a naked extraction scores no rank points', report && report.rankDelta === 0, report && report.rankDelta)
  check('rank: and no flat extraction experience', report && report.xp === 0, report && report.xp)
  await sleep(2000)
  await cmd(`cv give ${r.username} armor_6`)
  await sleep(800)
  before = reportCount(r.username)
  check('rank: ranked raid with ◈280,000 of armour', await enterRaid([r], () => r.chat('/cv queue proving NORMAL SOLO true')))
  await cmd(`clear ${r.username}`)
  await sleep(1000)
  await extract(r)
  report = await nextReport(r.username, before)
  check('rank: walking out with ◈280,000 less costs 20 points, halved below 1,100 (old: 30, halved)', report && report.rankDelta === -10, report && report.rankDelta)
  check('rank: with gear brought in the flat 150 experience is paid', report && report.xp === 150, report && report.xp)
  r.quit()
}

/**
 * House bounty on the top-ranked player: not in casual raids, once a day in ranked ones. Needs a
 * prepared champion, so it is not part of "all": let both accounts join once, stop the server, set
 * "rankPoints" well above everyone else's and "level" to 3 or more in the champion's profile
 * (data/players/<uuid>.json) and "level" in the hunter's, start the server again, then run
 *   BOUNTY_CHAMPION=<name> BOUNTY_HUNTER=<name> node loopholes.js bounty
 */
async function bounty () {
  const champName = process.env.BOUNTY_CHAMPION
  const hunterName = process.env.BOUNTY_HUNTER
  if (!champName || !hunterName) {
    check('bounty: BOUNTY_CHAMPION and BOUNTY_HUNTER are set (see the comment above bounty())', false)
    return
  }
  const join = name => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
    bot.msgs = []
    bot.worldChanges = 0
    bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
    bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.msgs.push(m); log(name, 'CHAT', m) } })
    bot.on('respawn', () => bot.worldChanges++)
    return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
  }
  const champ = await join(champName)
  const hunter = await join(hunterName)
  /** One raid: the hunter knifes the champion to death; returns what the kill paid the hunter. */
  const round = async (ranked, gear) => {
    for (const id of gear) await cmd(`cv give ${champName} ${id}`)
    await sleep(500)
    const flag = ranked ? ' true' : ''
    const mark = hunter.msgs.length
    const champMark = champ.msgs.length
    const inRaid = await enterRaid([champ, hunter], () => {
      champ.chat(`/cv queue proving NORMAL SOLO${flag}`)
      hunter.chat(`/cv queue proving NORMAL SOLO${flag}`)
    })
    if (!inRaid) return null
    // The bounty briefing comes 14 s into the raid; let it arrive (or not) before the kill.
    const announced = !!await heard(hunter, /本局悬赏目标/, mark, 12000)
    const before = await money(hunterName)
    hunter.setQuickBarSlot(2) // knife
    const dead = champ.worldChanges
    for (let i = 0; i < 40 && champ.worldChanges === dead && !champ.msgs.slice(champMark).some(m => /行动失败 · 被/.test(m)); i++) {
      await cmd(`execute at ${champName} as ${hunterName} run tp @s ~1 ~ ~ facing entity ${champName} eyes`)
      await sleep(150)
      const target = hunter.nearestEntity(e => e.type === 'player' && e.username === champName)
      if (target) hunter.attack(target)
      await sleep(700)
    }
    await sleep(1500)
    const paid = (await money(hunterName)) - before
    await extract(hunter)
    // The champion watches the kill replay, then is back in the lobby.
    await waitFor(() => champ.msgs.slice(champMark).some(m => /行动失败 · 被/.test(m)), 5000)
    await sleep(12000)
    return { paid, announced }
  }
  const casual = await round(false, [])
  check('bounty: killing the top player in a casual raid pays no house bounty', casual && casual.paid === 0 && !casual.announced, casual)
  const first = await round(true, ['armor_4', 'helmet_3'])
  check('bounty: in a ranked raid with ◈60,000+ of gear it pays once', first && first.paid === 20000, first)
  const second = await round(true, ['armor_6'])
  check('bounty: the same day it does not pay again', second && second.paid === 0, second)
  champ.quit(); hunter.quit()
}

const scenarios = { kit, stash, overfull, escort, escortraid, insurance, insgun, profit, stacks, selfkill, rank, bounty }

;(async () => {
  const which = process.argv[2] || 'all'
  const run = which === 'all' ? Object.keys(scenarios).filter(s => s !== 'bounty' && s !== 'rank') : which.split(',')
  for (const name of run) {
    log('====', name)
    // These check what an insurance claim sends back, so every insured item must come back at once.
    const restore = ['insurance', 'insgun'].includes(name)
      ? await pinInsurance(path.join(RUN, 'plugins/Craftov'), cmd, (...a) => log(name, ...a)) : null
    try {
      await scenarios[name]()
    } catch (err) {
      check(`${name}: ran without errors`, false, err.stack)
    } finally {
      if (restore) await restore()
    }
    // No `cv end` here: on a server up for less than a raid's length it leaves the raid running
    // forever and open to backfill. Every scenario gets its bots out of the raid itself.
    await sleep(3000)
  }
  const failed = results.filter(r => !r.ok)
  log('====', `${results.length - failed.length}/${results.length} passed`)
  setTimeout(() => process.exit(failed.length ? 1 : 0), 500)
})()
