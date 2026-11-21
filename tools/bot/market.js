// Market price rules (issue #13), end to end through the GUIs:
//  1. pricing screen: reference price = value x 0.85, the + buttons stop at 3x (vest, 60 rounds of 9mm);
//  2. system buyers: a vest listed at 1.2x sells SALES times, relisted after every sale; the trade
//     price never moves and the trader -> market round trip loses money;
//  3. a scoped and suppressed M4C sold to a system buyer leaves the bare M4C's trade price alone and
//     enters the price history per plain gun;
//  4. player to player with two bots on one address: a level-1 buyer is refused. With SERVER_DIR set,
//     the buyer is raised to level 3 in its profile file (it leaves and rejoins) to check that a
//     level-1 seller is refused too, and the script switches market.min-buy-level / block-same-ip /
//     daily-player-income-cap / virtual-hourly-cap in SERVER_DIR/plugins/Craftov/config.yml
//     (restored at the end, then /cv reload) to check the same-address refusal, the daily cap on both
//     sides (seller income, buyer bargain), that the hourly system-buyer quota is per seller, that a
//     kitted M4C bought by a player keeps the bare M4C's trade price where it was, and that a sale at
//     3x the reference price is ignored.
//
// node market.js     env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local SERVER_DIR=(unset)
//                         SALES=20 WAIT_MIN=25 LOG=/tmp/market.log
// Exit code 0 when every check passed. Bots use fresh random names, so they start with the starter kit.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || ''
const SALES = Number(process.env.SALES || 20)
const WAIT_MIN = Number(process.env.WAIT_MIN || 25)
const out = process.env.LOG || '/tmp/market.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => {
  const line = `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')
  fs.appendFileSync(out, line + '\n')
  console.log(line)
}
const rcon = cmd => rconRaw(cmd, RCON)
const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

// ------------------------------------------------------------------ item helpers

function txt (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (n.type === 'string') return n.value
  if (n.type === 'compound') return txtObj(n.value)
  if (n.type === 'list') return (n.value.value || []).map(v => typeof v === 'string' ? v : txtObj(v)).join('')
  return ''
}
function txtObj (o) { return (o.text ? txt(o.text) : '') + (o.extra ? txt(o.extra) : '') }
function comp (item, type) { const c = item && item.components && item.components.find(x => x.type === type); return c ? c.data : null }
function cid (item) {
  const data = comp(item, 'custom_data')
  const pbv = data && data.value && data.value.PublicBukkitValues
  const id = pbv && pbv.value['craftov:id']
  return id ? id.value : null
}
function title (item) { return txt(comp(item, 'custom_name') || comp(item, 'item_name')) }
function lore (item) { return (comp(item, 'lore') || []).map(txt) }
const money = s => { const m = String(s).match(/◈([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }
const short = s => { const m = String(s).match(/^◈([\d.]+)(万|亿)?$/); return m ? Number(m[1]) * (m[2] === '万' ? 1e4 : m[2] === '亿' ? 1e8 : 1) : NaN }
function find (w, id, from, to, pred, last = false) {
  let hit = -1
  for (let i = from; i < to; i++) {
    if (w.slots[i] && cid(w.slots[i]) === id && (!pred || pred(w.slots[i]))) {
      hit = i
      if (!last) break
    }
  }
  return hit
}

// ------------------------------------------------------------------ bots

function spawnBot (prefix, name = prefix + (Date.now() % 100000)) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', async () => { clearTimeout(t); await sleep(3000); resolve(bot) })
  })
}
function openWith (bot, fn) {
  return new Promise(resolve => {
    const t = setTimeout(() => resolve(null), 5000)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
    fn()
  })
}
async function waitChat (bot, re, ms, from = 0) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const hit = bot.chatLog.slice(from).find(m => re.test(m))
    if (hit) return hit
    await sleep(250)
  }
  return null
}
async function balance (bot) { return money(await rcon(`cv money ${bot.username} 0`)) }
const click = async (bot, slot, wait = 350) => { await bot.clickWindow(slot, 0, 0); await sleep(wait) }

/**
 * Opens the pricing screen for a stash record of `id`. With deposit, the first carried one is put in
 * the stash first and that new record (the last one) is priced.
 */
async function priceMenu (bot, id, { deposit = false, pred } = {}) {
  const w = await openWith(bot, () => bot.chat('/cv stash'))
  if (!w) throw new Error('stash did not open')
  if (deposit) {
    const s = find(w, id, w.inventoryStart, w.slots.length, pred)
    if (s < 0) throw new Error('no carried ' + id)
    await click(bot, s, 500)
  }
  for (let i = 0; i < 3 && !title(w.slots[48]).includes('上架'); i++) await click(bot, 48)
  const t = find(w, id, 0, 45, pred, deposit)
  if (t < 0) throw new Error('no stash ' + id)
  const pm = await openWith(bot, () => bot.clickWindow(t, 0, 0))
  if (!pm) throw new Error('price menu did not open')
  return pm
}
const priceOf = pm => money(title(pm.slots[13]))
const info = pm => lore(pm.slots[13])
const line = (pm, prefix) => info(pm).find(l => l.startsWith(prefix)) || ''

/** Confirms the listing on an open pricing screen and closes the stash it returns to. */
async function confirm (bot) {
  const back = await openWith(bot, () => bot.clickWindow(26, 0, 0))
  if (back) bot.closeWindow(back)
  await sleep(300)
}

/** Fits the given parts to the carried M4C at the gunsmith. */
async function kitM4c (bot, parts) {
  const w = await openWith(bot, () => bot.chat('/cv mod'))
  if (!w) throw new Error('gunsmith did not open')
  const g = find(w, 'm4c', 0, 9)
  if (g >= 0) await click(bot, g)
  for (const part of parts) {
    const p = find(w, part, 45, 54)
    if (p < 0) throw new Error('part not offered: ' + part)
    await click(bot, p, 500)
  }
  bot.closeWindow(w)
  await sleep(300)
}

/** Cancels every listing of the bot (items go to its mailbox). */
async function cancelAll (bot) {
  const w = await openWith(bot, () => bot.chat('/cv market'))
  if (!w) return
  await click(bot, 53)
  for (let i = 0; i < 20 && w.slots[0] && cid(w.slots[0]); i++) await click(bot, 0, 400)
  bot.closeWindow(w)
  await sleep(300)
}

/** In the market browser, opens `id`'s listings and clicks the first one; returns the window. */
async function tryBuy (bot, id) {
  const w = await openWith(bot, () => bot.chat('/cv market'))
  if (!w) throw new Error('market did not open')
  const b = find(w, id, 0, 45)
  if (b < 0) { bot.closeWindow(w); throw new Error('no offer for ' + id) }
  await click(bot, b, 500)
  const l = find(w, id, 0, 45)
  const lines = l >= 0 ? lore(w.slots[l]) : []
  const from = bot.chatLog.length
  if (l >= 0) await click(bot, l, 800)
  bot.closeWindow(w)
  await sleep(300)
  return { lines, chat: bot.chatLog.slice(from) }
}

// ------------------------------------------------------------------ config switches (SERVER_DIR only)

const cfgPath = SERVER_DIR && path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
let cfgOriginal = null
async function setMarket (values) {
  let s = fs.readFileSync(cfgPath, 'utf8')
  if (cfgOriginal === null) cfgOriginal = s
  for (const [k, v] of Object.entries(values)) {
    const re = new RegExp('^  ' + k + ':.*$', 'm')
    s = re.test(s) ? s.replace(re, `  ${k}: ${v}`) : s.replace(/^market:\s*$/m, `market:\n  ${k}: ${v}`)
  }
  fs.writeFileSync(cfgPath, s)
  await rcon('cv reload')
  await sleep(500)
}
async function restoreConfig () {
  if (cfgOriginal !== null) {
    fs.writeFileSync(cfgPath, cfgOriginal)
    await rcon('cv reload')
    log('config restored')
  }
}

// ------------------------------------------------------------------ scenario

async function main () {
  const S = await spawnBot('MkS')
  const name = S.username
  await rcon('op ' + name)

  // 1. Pricing screen: reference price and the 3x cap.
  await rcon(`cv give ${name} armor_4`)
  await sleep(400)
  let pm = await priceMenu(S, 'armor_4', { deposit: true })
  check('vest reference price is value x 0.85', line(pm, '参考价').startsWith('参考价 ◈51,000'), line(pm, '参考价'))
  check('vest highest price shown', info(pm).includes('最高售价 ◈153,000'), info(pm).join(' | '))
  check('pricing screen tells a level-1 seller only system buyers buy', info(pm).includes('账号等级 3 前只有系统买家会收购'), info(pm).join(' | '))
  await click(S, 23)
  check('x1.2 asks 61,200 and is hard to sell', priceOf(pm) === 61200 && line(pm, '预计').includes('较难售出'), line(pm, '预计'))
  for (let i = 0; i < 15; i++) await click(S, 16, 200)
  check('+10% stops at 3x the reference', priceOf(pm) === 153000, title(pm.slots[13]))
  check('price lore says the cap is reached', info(pm).some(l => l.includes('已达最高售价')), info(pm).join(' | '))
  check('+10% button explains the cap', lore(pm.slots[16]).some(l => l.includes('已达最高售价')), lore(pm.slots[16]).join(' | '))
  check('above 1.25x system buyers never buy', line(pm, '预计').includes('系统买家不会收购'), line(pm, '预计'))
  S.closeWindow(pm)
  await sleep(300)

  pm = await priceMenu(S, 'ammo_9_fmj', { pred: it => it.count === 60 })
  check('60 rounds of 9mm: reference 306', line(pm, '参考价').startsWith('参考价 ◈306'), line(pm, '参考价'))
  for (let i = 0; i < 20; i++) await click(S, 16, 150)
  check('60 rounds of 9mm cannot be listed above 918', priceOf(pm) === 918, title(pm.slots[13]))
  S.closeWindow(pm)
  await sleep(300)

  // 2. System buyers at 1.2x: ten vests stay listed, relisted after every sale, until SALES sales.
  const traderPrice = 69000 // 60,000 x trader.buy-multiplier 1.15
  const before = await balance(S)
  const soldRe = /陶瓷插板背心 ×1 已被 系统买家 买下，入账 ◈([\d,]+)/
  const chatFrom = S.chatLog.length
  let listed = 0
  const started = Date.now()
  const deadline = started + WAIT_MIN * 60000
  let sold = 0
  let lastLog = 0
  while (sold < SALES && Date.now() < deadline) {
    sold = S.chatLog.slice(chatFrom).filter(m => soldRe.test(m)).length
    while (listed - sold < 10) {
      // The vest already in the stash is listed first; later ones come from /cv give.
      if (listed > 0) { await rcon(`cv give ${name} armor_4`); await sleep(300) }
      pm = await priceMenu(S, 'armor_4', { deposit: listed > 0 })
      await click(S, 23)
      if (priceOf(pm) !== 61200 || !line(pm, '参考价').startsWith('参考价 ◈51,000')) {
        check(`listing ${listed + 1}: reference still 51,000 and x1.2 still 61,200`, false, line(pm, '参考价') + ' / ' + title(pm.slots[13]))
      }
      await confirm(S)
      listed++
    }
    if (Date.now() - lastLog > 60000) { log(`system buyers: ${sold}/${SALES} sold, ${listed} listed`); lastLog = Date.now() }
    await sleep(3000)
  }
  sold = S.chatLog.slice(chatFrom).filter(m => soldRe.test(m)).length
  const payouts = S.chatLog.slice(chatFrom).map(m => m.match(soldRe)).filter(Boolean).map(m => Number(m[1].replace(/,/g, '')))
  const fees = S.chatLog.slice(chatFrom).map(m => m.match(/已上架 陶瓷插板背心 ×1 售价 ◈61,200 · 手续费 ◈([\d,]+)/))
    .filter(Boolean).map(m => Number(m[1].replace(/,/g, '')))
  const after = await balance(S)
  // Per vest sold: payout - listing fee - what the trader charges for it (58,140 - 1,224 - 69,000).
  const net = payouts.reduce((a, b) => a + b, 0) - sold * ((fees[0] || 0) + traderPrice)
  check(`system buyers bought ${SALES} vests at 1.2x`, sold >= SALES, `${sold} sold of ${listed} listed in ${((Date.now() - started) / 60000).toFixed(1)} min`)
  check('every payout is 58,140 (61,200 - 5% tax)', payouts.length > 0 && payouts.every(p => p === 58140), payouts.join(','))
  check('buying from the trader to sell to system buyers loses money', net < 0 && fees.length === listed,
    `${sold} round trips net ${net} (${Math.round(net / Math.max(1, sold))} each); balance ${before} -> ${after} with ${listed - sold} still listed`)
  await cancelAll(S)
  await rcon(`cv give ${name} armor_4`)
  await sleep(300)
  pm = await priceMenu(S, 'armor_4', { deposit: true })
  check('after the sales the vest reference price is still 51,000', line(pm, '参考价').startsWith('参考价 ◈51,000'), line(pm, '参考价'))
  check('system-buyer sales leave no player trade price', !info(pm).some(l => l.startsWith('玩家成交均价')), info(pm).join(' | '))
  S.closeWindow(pm)
  await sleep(300)

  // 3. A scoped and suppressed M4C sold to a system buyer.
  await rcon(`cv give ${name} m4c`)
  await rcon(`cv give ${name} optic_4x`)
  await rcon(`cv give ${name} att_suppressor`)
  await sleep(400)
  await kitM4c(S, ['optic_4x', 'att_suppressor'])
  pm = await priceMenu(S, 'm4c', { deposit: true })
  const kitRef = money(line(pm, '参考价'))
  log('kitted M4C reference', kitRef)
  check('attachments and loaded rounds count in the kitted reference', kitRef === Math.round((34000 + 30 * 32 + 26000 + 22000) * 0.85), kitRef)
  await click(S, 21)
  const m4From = S.chatLog.length
  await confirm(S)
  const m4Sold = await waitChat(S, /M4C 卡宾枪 ×1 已被 系统买家 买下/, 5 * 60000, m4From)
  check('system buyer bought the kitted M4C at 0.9x', !!m4Sold, m4Sold || 'timed out')
  await rcon(`cv give ${name} m4c`)
  await sleep(300)
  pm = await priceMenu(S, 'm4c', { deposit: true })
  const hist = line(pm, '近期成交').replace('近期成交 ', '').replace(' (单件)', '').trim().split(' ')
  const lastSale = short(hist[hist.length - 1])
  check('bare M4C has no player trade price after a system sale', !info(pm).some(l => l.startsWith('玩家成交均价')), info(pm).join(' | '))
  check('kitted sale enters history per plain M4C (0.9 x 28,900)', Math.abs(lastSale - 26010) <= 100, line(pm, '近期成交'))
  S.closeWindow(pm)
  await sleep(300)

  // 4. Player to player, same address. The splint is listed at 1.2x so system buyers leave it alone.
  let B = await spawnBot('MkB')
  pm = await priceMenu(S, 'splint')
  await click(S, 23)
  let from = S.chatLog.length
  await confirm(S)
  const note = await waitChat(S, /只有系统买家会收购/, 3000, from)
  check('a level-1 seller is told only system buyers will buy', !!note, note || S.chatLog.slice(from).join(' | '))
  let r = await tryBuy(B, 'splint')
  check('market list tells a level-1 buyer about the level', r.lines.some(l => l.includes('需要账号等级 3')), r.lines.slice(-2).join(' | '))
  check('level-1 buyer is refused', r.chat.some(m => m.includes('需要账号等级 3')), r.chat.join(' | '))
  if (!cfgPath) {
    log('SERVER_DIR not set: skipping the seller-level, same-address, daily-cap, quota and player-trade-price checks')
  } else {
    try {
      // The buyer reaches level 3 (profile edited while it is offline); the seller is still level 1.
      const bName = B.username
      B.quit()
      await sleep(2000)
      const dir = path.join(SERVER_DIR, 'plugins/Craftov/data/players')
      const file = fs.readdirSync(dir).map(f => path.join(dir, f)).filter(f => f.endsWith('.json'))
        .find(f => JSON.parse(fs.readFileSync(f, 'utf8')).name === bName)
      const profile = JSON.parse(fs.readFileSync(file, 'utf8'))
      profile.level = 3
      fs.writeFileSync(file, JSON.stringify(profile, null, 2))
      B = await spawnBot('MkB', bName)
      r = await tryBuy(B, 'splint')
      check('market list tells a level-3 buyer the level-1 seller cannot sell to players', r.lines.some(l => l.includes('卖家账号等级不足 3')),
        r.lines.slice(-2).join(' | '))
      check('level-3 buyer cannot buy from a level-1 seller', r.chat.some(m => m.includes('卖家账号等级不足 3')), r.chat.join(' | '))

      await setMarket({ 'min-buy-level': 1, 'block-same-ip': true, 'daily-player-income-cap': 0 })
      r = await tryBuy(B, 'splint')
      check('same-address buyer is refused', r.chat.some(m => m.includes('同一网络地址')), r.chat.join(' | '))

      await setMarket({ 'block-same-ip': false, 'daily-player-income-cap': 5000 })
      r = await tryBuy(B, 'splint')
      check('cheap purchase under the daily cap goes through', r.chat.some(m => m.includes('购入')), r.chat.join(' | '))
      pm = await priceMenu(S, 'armor_4')
      await confirm(S)
      r = await tryBuy(B, 'armor_4')
      check('purchase past the seller\'s daily income cap is refused', r.chat.some(m => m.includes('收入已达上限')), r.chat.join(' | '))
      // A vest at 0.9x hands the buyer 5,100 below the reference price: past a 5,000 cap.
      await rcon(`cv give ${name} armor_4`)
      await sleep(300)
      pm = await priceMenu(S, 'armor_4', { deposit: true })
      await click(S, 21)
      await confirm(S)
      r = await tryBuy(B, 'armor_4')
      check('purchase handing the buyer more than its daily cap below the reference is refused',
        r.chat.some(m => m.includes('比参考价低 ◈5,100') && m.includes('收益已达上限')), r.chat.join(' | '))
      await setMarket({ 'daily-player-income-cap': 0 })
      await cancelAll(S)

      // The hourly system-buyer quota is per seller: the seller sold SALES vests this hour, the buyer none.
      await setMarket({ 'virtual-hourly-cap': SALES })
      await rcon(`cv give ${name} armor_4`)
      await sleep(300)
      pm = await priceMenu(S, 'armor_4', { deposit: true })
      check('seller who sold SALES vests this hour sees the quota reached', line(pm, '预计').includes('本小时系统买家已收满'), line(pm, '预计'))
      S.closeWindow(pm)
      await sleep(300)
      await rcon(`cv give ${bName} armor_4`)
      await sleep(300)
      pm = await priceMenu(B, 'armor_4', { deposit: true })
      check('another seller of the same item is not held back by that quota', !line(pm, '预计').includes('本小时系统买家已收满'), line(pm, '预计'))
      B.closeWindow(pm)
      await sleep(300)
      await setMarket({ 'virtual-hourly-cap': 40 })

      // A kitted M4C bought by a player at its reference price: the bare M4C's trade price stays.
      await rcon(`cv give ${name} m4c`)
      await rcon(`cv give ${name} optic_4x`)
      await rcon(`cv give ${name} att_suppressor`)
      await sleep(400)
      await kitM4c(S, ['optic_4x', 'att_suppressor'])
      pm = await priceMenu(S, 'm4c', { deposit: true })
      await click(S, 22)
      const kitPrice = priceOf(pm)
      from = S.chatLog.length
      await confirm(S)
      r = await tryBuy(B, 'm4c')
      const bought = await waitChat(S, /M4C 卡宾枪 ×1 已被 MkB/, 5000, from)
      check('player bought the kitted M4C', !!bought, (bought || r.chat.join(' | ')) + ' at ' + kitPrice)
      pm = await priceMenu(S, 'm4c')
      const ref = money(line(pm, '参考价'))
      const traded = money(line(pm, '玩家成交均价'))
      check('bare M4C player trade price equals its reference after a kitted sale', Math.abs(traded - ref) <= ref * 0.01,
        `参考价 ${ref}, 玩家成交均价 ${traded} (the old rule gave ~${Math.round(28900 * 0.85 + kitPrice * 0.15)} per gun)`)
      S.closeWindow(pm)
      await sleep(300)

      // A colluding sale at 3x is ignored by the trade price.
      pm = await priceMenu(S, 'ammo_9_fmj', { pred: it => it.count === 60 })
      for (let i = 0; i < 20; i++) await click(S, 16, 150)
      await confirm(S)
      from = S.chatLog.length
      r = await tryBuy(B, 'ammo_9_fmj')
      const ammo = await waitChat(S, /9x19 FMJ ×60 已被 MkB/, 5000, from)
      check('player bought 60 rounds of 9mm at 3x', !!ammo, ammo || r.chat.join(' | '))
      pm = await priceMenu(S, 'ammo_9_fmj', { pred: it => it.count === 60 })
      check('a sale at 3x does not create a 9mm trade price', !info(pm).some(l => l.startsWith('玩家成交均价')), info(pm).join(' | '))
      S.closeWindow(pm)
      await sleep(300)
    } finally {
      await restoreConfig()
    }
  }
  await cancelAll(S)
  B.quit()
  S.quit()
}

main()
  .catch(async e => { log('ERROR', e.stack); failures.push('error: ' + e.message); await restoreConfig().catch(() => {}) })
  .finally(async () => {
    log(failures.length ? `FAILED: ${failures.join('; ')}` : 'ALL PASSED')
    await sleep(1000)
    process.exit(failures.length ? 1 : 0)
  })
