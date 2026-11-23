// Economy balance checks (issue #22), end to end with headless bots:
//
//   market     a laptop and a ceramic vest listed at about 1.0x their value, and a gold bar and a
//              star crystal listed at a tenth of it by a level-3 seller, are never bought by system
//              buyers within MARKET_MIN minutes; a level-1 seller cannot list the gold bar at all; a
//              police vest at 81% of the system buyers' price sells to them with 5% tax taken, after
//              which (market.virtual-daily-cap set to 1) a second one is held back by the daily quota;
//              the pricing screen shows the system buyers' price and says when they will not buy.
//   insurance  DEATHS deaths in solo proving-ground raids with insurance on (the starter kit, then
//              five P18s each time): what comes back matches insurance.loss-chance within three
//              standard deviations, every claim waits insurance.return-minutes in the profile, and
//              once due it reaches the mailbox when the owner is online.
//   meds       at full health with both legs broken a surgery kit treats one leg per use and loses
//              40 of its 160 points each time; a med case treats one of two heavy bleeds and loses 40.
//   load       five gold lions (50 kg) stop sprinting (food level 6) and say so; emptying the
//              inventory brings sprinting back.
//
//   node economy.js [all|market|insurance|meds]    (meds includes load)
//   env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local SERVER_DIR=../../server/run
//        DEATHS=20 MARKET_MIN=10
//
// SERVER_DIR is read for player profiles and its plugins/Craftov/config.yml is edited for the run
// (restored at the end, then /cv reload): match.wait-seconds 2 and cinematic.enabled false to make
// raids quick, market.virtual-daily-cap 1. In `all` the market listings wait while the other
// scenarios run. Bots use fresh random names and are made op (the proving ground is test-only).
// Exits 0 when every check passed.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const SERVER_DIR = path.resolve(process.env.SERVER_DIR || path.join(__dirname, '../../server/run'))
const DEATHS = Number(process.env.DEATHS || 20)
const MARKET_MIN = Number(process.env.MARKET_MIN || 10)
const CONFIG = path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
const tag = String(Math.floor(Math.random() * 9000) + 1000)
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

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes). */
function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n['']) + flat(n.extra) // '' holds plain text in the compact NBT form
}
function comp (item, type) { const c = item && item.components && item.components.find(x => x.type === type); return c ? c.data : null }
function cid (item) {
  const data = comp(item, 'custom_data')
  const pbv = data && data.value && data.value.PublicBukkitValues
  const id = pbv && pbv.value['craftov:id']
  return id ? id.value : null
}
const title = item => flat(comp(item, 'custom_name') || comp(item, 'item_name'))
const lore = item => (comp(item, 'lore') || []).map(flat)
const money = s => { const m = String(s).match(/◈([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }
function find (w, id, from, to, last = false) {
  let hit = -1
  for (let i = from; i < to; i++) {
    if (w.slots[i] && cid(w.slots[i]) === id) {
      hit = i
      if (!last) break
    }
  }
  return hit
}

// ------------------------------------------------------------------ profiles and config

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(SERVER_DIR, 'plugins/Craftov/data/players', offlineUuid(name) + '.json')
const readProfile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const writeProfile = (name, p) => fs.writeFileSync(profileFile(name), JSON.stringify(p, null, 2))

const configBefore = fs.readFileSync(CONFIG, 'utf8')
/** Sets `section.key: value` lines in config.yml (adding the key under the section when missing). */
async function setConfig (section, values) {
  let s = fs.readFileSync(CONFIG, 'utf8')
  for (const [k, v] of Object.entries(values)) {
    const block = new RegExp(`^(${section}:\\n(?:(?:[ \\t]+.*|[ \\t]*#.*|)\\n)*?)[ \\t]+${k}:.*$`, 'm')
    s = block.test(s) ? s.replace(block, `$1  ${k}: ${v}`)
      : new RegExp(`^${section}:\\n`, 'm').test(s) ? s.replace(new RegExp(`^${section}:\\n`, 'm'), `${section}:\n  ${k}: ${v}\n`)
        : s + `\n${section}:\n  ${k}: ${v}\n`
  }
  fs.writeFileSync(CONFIG, s)
  log('config', section, values, await rcon('cv reload'))
}
const configValue = (key, fallback) => { const m = fs.readFileSync(CONFIG, 'utf8').match(new RegExp(`^ +${key}: *([\\d.]+)`, 'm')); return m ? Number(m[1]) : fallback }

// ------------------------------------------------------------------ bots

function spawnBot (prefix, name = prefix + tag) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.bars = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  // Paper sends action bars as their own packet, which mineflayer does not turn into an event here.
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push(s); log(name, 'BAR', s) } })
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
    await sleep(250)
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
const click = async (bot, slot, wait = 350) => { await bot.clickWindow(slot, 0, 0); await sleep(wait) }
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'
async function quit (bot) {
  bot.quit()
  await waitFor(async () => !(await rcon('list')).includes(bot.username), 15000)
  await sleep(1500) // the profile is written on the way out
}
async function deploy (bot) {
  bot.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => inRaid(bot), 120000)
  await sleep(1500)
  return ok
}

// ------------------------------------------------------------------ market

/** Opens the pricing screen for the stash record of `id`, depositing a carried one first if asked. */
async function priceMenu (bot, id, deposit) {
  const w = await openWith(bot, () => bot.chat('/cv stash'))
  if (!w) throw new Error('stash did not open')
  if (deposit) {
    const s = find(w, id, w.inventoryStart, w.slots.length)
    if (s < 0) throw new Error('no carried ' + id)
    await click(bot, s, 500)
  }
  // The stash retitles itself on every deposit (mineflayer then has a new window), shows the newest
  // record first, and lists an item on a right-click (#10).
  const cur = bot.currentWindow || w
  const t = find(cur, id, 9, 45, false)
  if (t < 0) throw new Error('no stash ' + id)
  const pm = await openWith(bot, () => bot.clickWindow(t, 1, 0))
  if (!pm) throw new Error('price menu did not open')
  return pm
}
const priceOf = pm => money(title(pm.slots[13]))
const hint = pm => lore(pm.slots[13]).find(l => l.startsWith('预计')) || ''
async function confirm (bot) {
  const back = await openWith(bot, () => bot.clickWindow(26, 0, 0))
  if (back) bot.closeWindow(back)
  await sleep(400)
}
async function cancelAll (bot) {
  const w = await openWith(bot, () => bot.chat('/cv market'))
  if (!w) return
  await click(bot, 53)
  for (let i = 0; i < 20 && w.slots[0] && cid(w.slots[0]); i++) await click(bot, 0, 400)
  bot.closeWindow(w)
  await sleep(300)
}

const NAMES = { laptop: '加密笔记本电脑', armor_4: '陶瓷插板背心', gold_bar: '金条', star_crystal: '星核水晶', armor_2: '警用防弹背心' }
const soldRe = id => new RegExp(NAMES[id] + ' ×1 已被 系统买家 买下，入账 ◈([\\d,]+)')

/** Lists everything and returns the state the final check needs. */
async function marketList () {
  await setConfig('market', { 'virtual-daily-cap': 1 })
  let m = await spawnBot('EcM')
  await rcon('op ' + m.username)
  for (const id of ['laptop', 'armor_4', 'gold_bar', 'star_crystal']) await rcon(`cv give ${m.username} ${id}`)
  await sleep(800)

  // About 1.0x value (the reference price x 1.2 = 1.02x value): far above the system buyers' 0.7x.
  for (const [id, value] of [['laptop', 26000], ['armor_4', 60000]]) {
    const pm = await priceMenu(m, id, true)
    const button = lore(pm.slots[20]).join(' ')
    check(`${id}: the system buyers' price is 0.7x value`, button.includes('最多出 ◈' + Math.round(value * 0.7).toLocaleString('en-US')), button)
    await click(m, 23)
    const price = priceOf(pm)
    check(`${id}: listed at ${price} (>= 1.0x value) is above what system buyers pay`, price >= value && hint(pm).includes('系统买家不会收购'), hint(pm))
    await confirm(m)
  }
  const listedAt = Date.now()

  // Gold: a level-1 seller cannot list what only system buyers would take, and they never take gold.
  let pm = await priceMenu(m, 'gold_bar', true)
  check('gold bar: the pricing screen says system buyers will not buy it', hint(pm).includes('系统买家不会收购'), hint(pm))
  let from = m.chatLog.length
  await click(m, 26, 800)
  check('gold bar: a level-1 seller cannot list it', !!await waitChat(m, /只有系统买家会收购你的商品/, 3000, from), m.chatLog.slice(from).join(' | '))
  try { m.closeWindow(m.currentWindow) } catch {}
  await sleep(300)
  pm = await priceMenu(m, 'star_crystal', true)
  m.closeWindow(pm)
  await sleep(300)

  // Level 3 (profile edited offline), then both are listed at about a tenth of their value.
  const name = m.username
  await quit(m)
  const p = readProfile(name)
  p.level = 3
  writeProfile(name, p)
  m = await spawnBot('EcM', name)
  for (const id of ['gold_bar', 'star_crystal']) {
    pm = await priceMenu(m, id, false)
    for (let i = 0; i < 20; i++) await click(m, 10, 150)
    const price = priceOf(pm)
    from = m.chatLog.length
    await confirm(m)
    check(`${id}: listed by a level-3 seller at ${price}`, !!await waitChat(m, new RegExp('已上架 ' + NAMES[id]), 3000, from), m.chatLog.slice(from).join(' | '))
  }

  // A police vest at 81% of the system buyers' price sells to them.
  await rcon(`cv give ${name} armor_2`)
  await sleep(500)
  pm = await priceMenu(m, 'armor_2', true)
  await click(m, 20)
  const top = priceOf(pm)
  await click(m, 10)
  await click(m, 10)
  const cheap = priceOf(pm)
  check('police vest: system buyers pay at most 5,600 (0.7 x 8,000)', top === 5600, top)
  check('police vest: at 81% of that they buy quickly', hint(pm).includes('很快收购'), `${cheap}: ${hint(pm)}`)
  from = m.chatLog.length
  await confirm(m)
  const sold = await waitChat(m, soldRe('armor_2'), 5 * 60000, from)
  const payout = sold ? money(sold) : NaN
  check('police vest: sold to a system buyer, less 5% tax', payout === cheap - Math.round(cheap * 0.05), sold || 'not sold in 5 minutes')

  // The daily quota (set to 1 for this run) holds back a second one.
  await rcon(`cv give ${name} armor_2`)
  await sleep(500)
  pm = await priceMenu(m, 'armor_2', true)
  check('second police vest: the pricing screen says today\'s quota is used up', hint(pm).includes('今天系统买家已收满'), hint(pm))
  await click(m, 20)
  await click(m, 10)
  await click(m, 10)
  await confirm(m)
  return { m, listedAt, from: m.chatLog.length }
}

async function marketCheck ({ m, listedAt, from }) {
  const wait = listedAt + MARKET_MIN * 60000 - Date.now()
  if (wait > 0) {
    log(`waiting ${(wait / 60000).toFixed(1)} more minutes for the market listings`)
    await sleep(wait)
  }
  const minutes = ((Date.now() - listedAt) / 60000).toFixed(1)
  for (const id of ['laptop', 'armor_4', 'gold_bar', 'star_crystal']) {
    const hit = m.chatLog.find(x => soldRe(id).test(x))
    check(`${id}: not bought by system buyers in ${minutes} minutes`, !hit, hit || '')
  }
  const again = m.chatLog.slice(from).find(x => soldRe('armor_2').test(x))
  check('second police vest: not bought past the daily quota', !again, again || '')
  await cancelAll(m)
  m.quit()
}

// ------------------------------------------------------------------ insurance

async function insurance () {
  const bot = await spawnBot('EcI')
  const name = bot.username
  await rcon('op ' + name)
  const menu = await openWith(bot, () => bot.chat('/cv deploy'))
  if (!menu) throw new Error('deploy screen did not open')
  await click(bot, 34, 600)
  const insureLore = lore(menu.slots[34]).join(' ')
  bot.closeWindow(menu)
  const loss = configValue('loss-chance', 0.3)
  const minutes = configValue('return-minutes', 30)
  check('deploy screen gives the premium by rarity and the odds of a claim', insureLore.includes('金色') && insureLore.includes('红色')
    && insureLore.includes(Math.round((1 - loss) * 100) + '% 的机会找回'), insureLore)
  let insured = 0
  let back = 0
  let lost = 0
  let deaths = 0
  for (let i = 0; i < DEATHS; i++) {
    for (let k = 0; k < 5; k++) await rcon(`cv give ${name} p18`)
    await rcon(`cv money ${name} 5000`)
    await sleep(500)
    const from = bot.chatLog.length
    if (!await deploy(bot)) { check(`death ${i + 1}: deployed`, false); break }
    const note = await waitChat(bot, /已为 (\d+) 件装备投保/, 10000, from)
    const n = note ? Number(note.match(/已为 (\d+) 件/)[1]) : 0
    await sleep(3500) // a freshly landed player is protected for three seconds
    await rcon(`damage ${name} 1000 minecraft:generic`)
    const claim = await waitChat(bot, /保险理赔：/, 90000, from)
    if (!claim) { check(`death ${i + 1}: claim announced`, false, bot.chatLog.slice(from).join(' | ')); break }
    const r = claim.includes('全部没能找回') ? 0 : Number((claim.match(/保险理赔：(\d+) 件装备/) || [0, 0])[1])
    const l = claim.includes('全部没能找回') ? n : Number((claim.match(/(\d+) 件没能找回/) || [0, 0])[1])
    if (r + l !== n) check(`death ${i + 1}: ${r} back + ${l} lost = ${n} insured`, false, claim)
    insured += n
    back += r
    lost += l
    deaths++
    log(`death ${deaths}: ${n} insured, ${r} back, ${l} lost (so far ${back}/${insured})`)
    if (!await waitFor(() => inLobby(bot), 60000)) { check(`death ${i + 1}: back in the lobby`, false); break }
    await sleep(1000)
  }
  const expected = insured * (1 - loss)
  const sd = Math.sqrt(insured * loss * (1 - loss))
  check(`insurance: ${back} of ${insured} insured items came back over ${deaths} deaths (expected ${expected.toFixed(1)} ± ${(3 * sd).toFixed(1)})`,
    deaths === DEATHS && Math.abs(back - expected) <= 3 * sd, `lost ${lost}`)

  await quit(bot)
  const p = readProfile(name)
  const claims = p.claims || []
  const late = claims.map(c => (c.readyAt - Date.now()) / 60000)
  check(`every returned item waits in the profile, about ${minutes} minutes`, back > 0 && claims.length === back
    && late.every(x => x > minutes - DEATHS * 2.5 && x <= minutes + 0.1), `${claims.length} claims, ${Math.min(...late).toFixed(1)}-${Math.max(...late).toFixed(1)} min to go`)
  check('nothing returned reaches the mailbox early', !(p.mailbox || []).some(r => r.id === 'p18'), (p.mailbox || []).map(r => r.id))
  for (const c of claims) c.readyAt = Date.now() - 1000
  writeProfile(name, p)
  const again = await spawnBot('EcI', name)
  const arrived = await waitChat(again, /保险理赔到账：(\d+) 件装备/, 20000)
  check('due claims reach the mailbox when the owner is online', !!arrived && Number(arrived.match(/(\d+) 件装备/)[1]) === back, arrived || 'no message')
  await quit(again)
  const after = readProfile(name)
  check('the profile has them in the mailbox and no claim left', back > 0 && (after.claims || []).length === 0
    && (after.mailbox || []).filter(r => r.id === 'p18' || ['k47', 'pack_1', 'rig_1', 'armor_2', 'helmet_2'].includes(r.id)).length === back,
  `${(after.mailbox || []).length} mailed, ${(after.claims || []).length} claims`)
}

// ------------------------------------------------------------------ meds and load

const hotbar = (bot, id) => { for (let s = 36; s <= 44; s++) if (cid(bot.inventory.slots[s]) === id) return s; return -1 }
async function use (bot, id, seconds) {
  const slot = hotbar(bot, id)
  if (slot < 0) throw new Error(id + ' not in the hotbar: ' + bot.inventory.slots.map(cid).join(','))
  bot.setQuickBarSlot(slot - 36)
  await sleep(400)
  const from = bot.bars.length
  bot.activateItem()
  await sleep(seconds * 1000 + 700)
  bot.deactivateItem()
  await sleep(800)
  return { bars: bot.bars.slice(from), item: bot.inventory.slots[slot] }
}
const pool = item => (lore(item).find(l => l.startsWith('剩余治疗量')) || '').replace('剩余治疗量 ', '')

async function meds () {
  const bot = await spawnBot('EcH')
  const name = bot.username
  await rcon('op ' + name)
  check('meds: deployed', await deploy(bot))
  await rcon(`cv give ${name} surgery`)
  await rcon(`cv injure ${name} left_leg fracture`)
  await rcon(`cv injure ${name} right_leg fracture`)
  await sleep(800)
  let r = await use(bot, 'surgery', 12)
  let treated = r.bars.find(b => b.includes('已处理')) || ''
  check('surgery at full health treats one broken leg', (treated.match(/骨折/g) || []).length === 1, treated)
  check('and loses 40 of its 160 points', cid(r.item) === 'surgery' && pool(r.item) === '120/160', pool(r.item))
  r = await use(bot, 'surgery', 12)
  treated = r.bars.find(b => b.includes('已处理')) || ''
  check('a second use treats the other leg', (treated.match(/骨折/g) || []).length === 1 && pool(r.item) === '80/160', `${treated} / ${pool(r.item)}`)

  await rcon(`cv give ${name} medkit_l`)
  await rcon(`cv injure ${name} chest heavy`)
  await rcon(`cv injure ${name} stomach heavy`)
  await sleep(800)
  r = await use(bot, 'medkit_l', 4.5)
  treated = r.bars.find(b => b.includes('已处理')) || ''
  // The bleeds cost health while the case is applied, so what it heals comes out of the pool too.
  const healed = Number((treated.match(/\+(\d+) HP/) || [0, NaN])[1])
  const left = parseFloat(pool(r.item))
  check('a med case treats one of two heavy bleeds for 40 points, then heals', (treated.match(/出血/g) || []).length === 1
    && Math.abs(400 - 40 - healed - left) <= 1, `${treated} / ${pool(r.item)}`)

  // Load: five gold lions are 50 kg, past the 40 kg overload limit.
  await rcon(`clear ${name}`)
  await sleep(500)
  const from = bot.bars.length
  for (let i = 0; i < 5; i++) await rcon(`cv give ${name} lion_statue`)
  await waitFor(() => bot.food <= 6, 5000)
  check('five gold lions (50 kg) stop sprinting', bot.food <= 6 && bot.bars.slice(from).some(b => b.includes('无法冲刺')),
    `food ${bot.food}; ${bot.bars.slice(from).join(' | ')}`)
  await rcon(`clear ${name}`)
  await waitFor(() => bot.food >= 20, 5000)
  check('emptying the pack brings sprinting back', bot.food >= 20 && bot.bars.slice(from).some(b => b.includes('可以冲刺了')), `food ${bot.food}`)
  await rcon(`damage ${name} 1000 minecraft:generic`)
  await waitFor(() => inLobby(bot), 60000)
  await quit(bot)
}

// ------------------------------------------------------------------ run

;(async () => {
  const which = process.argv[2] || 'all'
  try {
    await setConfig('match', { 'wait-seconds': 2 })
    await setConfig('cinematic', { enabled: false })
    let listing = null
    if (which === 'all' || which === 'market') listing = await marketList()
    if (which === 'all' || which === 'meds') await meds()
    if (which === 'all' || which === 'insurance') await insurance()
    if (listing) await marketCheck(listing)
  } catch (e) {
    check('ran without errors', false, e.stack)
  } finally {
    fs.writeFileSync(CONFIG, configBefore)
    log('config.yml put back', await rcon('cv reload').catch(e => e.message))
  }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
