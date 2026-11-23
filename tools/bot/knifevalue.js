// A knife counts nothing towards gear value (战备值, issue #81), end to end with one headless bot:
//
//   1. lobby, carrying only a karambit (◈95,000): the main menu, the deploy screen and (when the
//      server shows the boss-bar HUD) the lobby bar all say 战备值 0, and queueing for 机密 is
//      refused with "战备值 ◈0 不足 机密 要求的 ◈60,000" (the bot is not in the queue afterwards);
//   2. free kit: the carry limit (◈15,000) no longer stops a player with only a karambit, but the
//      asset check still counts the knife: with the starting money the claim is refused on total
//      assets, and the figure is over ◈200,000 only because of the karambit; with the money taken
//      away the kit is handed out;
//   3. a karambit and a gold bar (◈68,000): the deploy screen says 战备值 ◈68,000, 机密 lets the bot
//      in, and dying there reports 损失 -◈68,000 (the kept knife is not lost); the karambit is back
//      in the melee slot in the lobby;
//   4. extracting from a 常规 raid carrying only the karambit: 净收益 +0 (the knife counts at deploy
//      and at extraction alike) and no flat extraction experience (经验 +0, rank.extract-xp needs
//      gear value);
//   5. a second karambit held on the cursor (copied there with /item) when the bot dies in a 常规
//      raid goes into the body: 损失 -◈95,000, and the equipped karambit is still kept.
//
//   node knifevalue.js     env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local
//
// The bot has a fresh random name and is made op (the proving ground is a test map); it is deopped
// when done. Raids launch after match.wait-seconds (30 by default), so a run takes one to three
// minutes. Exit code 0 when every check passed, 1 when one failed, 2 when the scenario broke.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const EXTRACT = process.env.EXTRACT || '106.5 65 8.5' // the proving ground's permanent extraction
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
const money = s => { const m = String(s).match(/◈ ?([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }

// ------------------------------------------------------------------ bot

function spawnBot (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.titles = []
  bot.bars = {}
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━━━━━━━━━━━━')) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  // A click's result comes on the action bar (util/Notify): logged with the chat, as what the bot was told.
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.chatLog.push(s); log(name, 'BAR', s) } })
  bot.on('title', (text, type) => {
    const s = typeof text === 'string' ? text : (flat(text) || JSON.stringify(text))
    bot.titles.push({ type, text: s })
    log(name, 'TITLE', type, s)
  })
  const bar = b => { bot.bars[b.entityUUID] = b.title ? b.title.toString() : '' }
  bot.on('bossBarCreated', bar)
  bot.on('bossBarUpdated', bar)
  bot.on('bossBarDeleted', b => { delete bot.bars[b.entityUUID] })
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
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'
const melee = bot => cid(bot.inventory.slots[38]) // hotbar key 3

/** Empties the inventory and gives exactly these items (the karambit lands in the melee slot). */
async function carryOnly (bot, ...ids) {
  await rcon(`clear ${bot.username}`)
  await sleep(500)
  for (const id of ids) await rcon(`cv give ${bot.username} ${id}`)
  await waitFor(() => ids.every(id => bot.inventory.slots.some(s => cid(s) === id)), 5000)
  await sleep(500)
}

/** 战备值 as the deploy screen's value button says it. */
async function deployValue (bot) {
  const w = await openWith(bot, () => bot.chat('/cv deploy'))
  if (!w) return { value: NaN, text: '(deploy screen did not open)' }
  const text = title(w.slots[42])
  bot.closeWindow(w)
  await sleep(300)
  return { value: money(text), text }
}

/** 当前战备值 as the main menu's profile button (bottom bar, slot 49) says it. */
async function menuValue (bot) {
  const w = await openWith(bot, () => bot.chat('/cv menu'))
  if (!w) return { value: NaN, text: '(main menu did not open)' }
  const line = lore(w.slots[49]).find(l => l.includes('战备值')) || ''
  bot.closeWindow(w)
  await sleep(300)
  return { value: money(line), text: line }
}

/** Clicks the main menu's free kit button (slot 30) and returns what the bot is told. */
async function claimFreeKit (bot) {
  const w = await openWith(bot, () => bot.chat('/cv menu'))
  if (!w) return '(main menu did not open)'
  const from = bot.chatLog.length
  await bot.clickWindow(30, 0, 0)
  const said = await waitChat(bot, /免费补给|战备值超过|总资产/, 5000, from)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
  return said || '(nothing said)'
}

async function balance (bot) { return money(await rcon(`cv money ${bot.username} 0`)) }

async function deploy (bot, tier) {
  const from = bot.chatLog.length
  bot.chat(`/cv queue proving ${tier} SOLO`)
  const ok = await waitFor(() => inRaid(bot), 150000)
  if (!ok) log('not in a raid; chat since queueing:', bot.chatLog.slice(from))
  await sleep(3500) // a freshly landed player is protected for three seconds
  return ok
}

// ------------------------------------------------------------------ scenario

async function scenario (bot) {
  const name = bot.username

  // 1. Only a karambit: gear value 0 everywhere, and 机密 is refused.
  await carryOnly(bot, 'knife_karambit')
  check('the karambit sits in the melee slot', melee(bot) === 'knife_karambit', melee(bot))
  const menu = await menuValue(bot)
  check('main menu: 当前战备值 0 with only a karambit', menu.value === 0, menu.text)
  const dv = await deployValue(bot)
  check('deploy screen: 战备值 ◈0 with only a karambit', dv.value === 0, dv.text)
  let bar = ''
  // The vanilla info bar spells it "战备值 ◈…"; BetterHud draws its HUD through boss bars of its own,
  // full of private-use glyphs (mineflayer turns them into U+FFFD), which may carry the same label.
  await waitFor(() => (bar = Object.values(bot.bars).find(t => t.includes('战备值 ◈') && !/[\ue000-\uf8ff\ud800-\udfff\ufffd]/.test(t)) || ''), 5000)
  if (bar) check('lobby HUD bar: 战备值 ◈0 with only a karambit', /战备值 ◈0(\s|$)/.test(bar), bar)
  else log('SKIP lobby HUD bar: no boss-bar HUD (BetterHud draws it; hud.vanilla-bars-with-betterhud is off)')
  await rcon(`cv money ${name} 100000`)
  let from = bot.chatLog.length
  bot.chat('/cv queue proving CONFIDENTIAL SOLO')
  const refused = await waitChat(bot, /战备值/, 5000, from)
  check('queueing for 机密 with only a karambit is refused: 战备值 ◈0 不足 ◈60,000',
    !!refused && refused.includes('战备值 ◈0 不足 机密') && refused.includes('◈60,000'), refused)
  from = bot.chatLog.length
  bot.chat('/cv leave')
  const notQueued = await waitChat(bot, /不在匹配队列中|取消/, 5000, from)
  check('and the bot is neither queued nor in a raid', !!notQueued && notQueued.includes('不在匹配队列中') && inLobby(bot),
    { said: notQueued, world: bot.worldName })

  // 2. Free kit: the carry limit ignores the knife, the asset check counts it.
  const assetsIn = said => money((said.match(/总资产[^◈]*(◈[\d,]+)/) || [])[1])
  let said = await claimFreeKit(bot)
  check('free kit: refused on total assets, not on the carry limit (战备值 0)', said.includes('总资产'), said)
  // Total assets to ◈250,000: over the ◈200,000 limit with the karambit, ◈155,000 without it.
  const assets = assetsIn(said)
  if (Number.isFinite(assets)) await rcon(`cv money ${name} ${250000 - assets}`)
  said = await claimFreeKit(bot)
  check('free kit: total assets count the carried karambit (refused at ◈250,000)',
    said.includes('总资产') && assetsIn(said) === 250000, said)
  await rcon(`cv money ${name} -${await balance(bot)}`)
  const granted = await claimFreeKit(bot)
  check('free kit: handed out once the money is gone (the karambit is no gear value)', granted.includes('已领取免费补给'), granted)

  // 3. A karambit and a gold bar: gear value 68,000, 机密 lets the bot in, the death loses 68,000.
  await carryOnly(bot, 'knife_karambit', 'gold_bar')
  await rcon(`cv money ${name} 100000`)
  const dv2 = await deployValue(bot)
  check('deploy screen: 战备值 ◈68,000 with a karambit and a gold bar', dv2.value === 68000, dv2.text)
  if (!await deploy(bot, 'CONFIDENTIAL')) throw new Error('the bot never reached the 机密 raid')
  check('a karambit and a gold bar get into 机密', true)
  from = bot.chatLog.length
  await rcon(`damage ${name} 1000 minecraft:generic`)
  const lost = await waitChat(bot, /损失|击败干员 \d+ · 击败敌兵/, 60000, from)
  check('dying reports 损失 -◈68,000: the gold bar, not the kept karambit', !!lost && lost.includes('损失') && money(lost) === 68000, lost)
  if (!await waitFor(() => inLobby(bot), 60000)) throw new Error('the bot did not get back to the lobby')
  await waitFor(() => melee(bot) === 'knife_karambit', 5000)
  check('the karambit is back in the melee slot after the death', melee(bot) === 'knife_karambit', melee(bot))

  // 4. Extracting with only the karambit: no profit and no flat extraction experience.
  await carryOnly(bot, 'knife_karambit')
  if (!await deploy(bot, 'NORMAL')) throw new Error('the bot never reached the 常规 raid')
  from = bot.chatLog.length
  await rcon(`execute as ${name} at @s run tp @s ${EXTRACT}`)
  // The raid record is one chat line: "撤离成功 · 试验场 · 常规 · 00:12 · 净收益 +◈0 · 击败干员 0 · 经验 +0".
  const xp = await waitChat(bot, /撤离成功.*击败干员 \d+/, 60000, from)
  check('extracting with only the karambit: 净收益 +0', !!xp && /净收益 \+◈?0(\D|$)/.test(xp), xp)
  check('and no flat extraction experience: 经验 +0', !!xp && /经验 \+0(\D|$)/.test(xp), xp)
  // The settlement screen opens after the curtain; shut it so the next steps start from the lobby.
  await sleep(2500)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await waitFor(() => inLobby(bot), 30000)

  // 5. A second knife held on the cursor at death goes into the body and is lost; only the one in
  //    the melee slot is kept. Normal play never puts a knife on the cursor, so /item copies the
  //    equipped karambit there.
  await carryOnly(bot, 'knife_karambit')
  if (!await deploy(bot, 'NORMAL')) throw new Error('the bot never reached the second 常规 raid')
  const copied = await rcon(`item replace entity ${name} player.cursor from entity ${name} hotbar.2`)
  log('cursor copy:', copied)
  from = bot.chatLog.length
  await rcon(`damage ${name} 1000 minecraft:generic`)
  const lost2 = await waitChat(bot, /损失|击败干员 \d+ · 击败敌兵/, 60000, from)
  check('a karambit on the cursor at death is lost with the body: 损失 -◈95,000',
    !!lost2 && lost2.includes('损失') && money(lost2) === 95000, lost2)
  if (!await waitFor(() => inLobby(bot), 60000)) throw new Error('the bot did not get back to the lobby')
  await waitFor(() => melee(bot) === 'knife_karambit', 5000)
  check('the karambit in the melee slot is still kept', melee(bot) === 'knife_karambit', melee(bot))
}

;(async () => {
  const name = 'kv' + Math.random().toString(36).slice(2, 8)
  let bot = null
  let broke = false
  try {
    bot = await spawnBot(name)
    await rcon('op ' + name)
    await scenario(bot)
  } catch (e) {
    broke = true
    log('SETUP', e.stack)
  } finally {
    try { await rcon('deop ' + name) } catch (e) { }
    if (bot) bot.quit()
    await sleep(1000)
    log(failures.length ? `${failures.length} FAILED: ${failures.join('; ')}` : broke ? 'BROKEN' : 'ALL PASSED')
    process.exit(broke ? 2 : failures.length ? 1 : 0)
  }
})()
