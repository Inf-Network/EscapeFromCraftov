// Escort refusals (issue #63): a player already on a contract who tries to take or post another is
// told which side of it they are on, and what frees them up.
//
//   lobby   client A and client B each post a contract; escort E takes B's.
//           A clicking B's contract: "你已经发布了一份护航委托，取消后才能接取别的委托。" (A's own name
//           is nowhere in it; it used to say A was in A's own contract). A posting again: the same,
//           "…才能发布新的委托。" E clicking B's again: "你已经接取了这份委托。" E clicking A's:
//           "你正在护航 B，离开其小队后才能接取别的委托。" E posting: "…才能发布新的委托。"
//   raid    B and E deploy together to the proving ground; E extracts first. Back in the lobby with
//           B still out, E clicking A's contract hears "你正在护航 B，老板这局结束后才能…"; leaving
//           the party does not change that (the contract is fixed once deployed). Once B extracts, E
//           takes A's contract.
//
// Each refusal must reach the action bar and flash into the clicked button's tooltip, as every
// other click result does (it used to be a chat line).
//
//   PORT=25565 RCON_PORT=25575 node escortbusy.js [lobby raid]   (raid runs after lobby, on its state)
//
// The server needs test.enabled (B queues for the proving ground), no whitelist and a short
// match.wait-seconds. Exit code: failed checks.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = c => rconRaw(c, RCON).then(r => r.replace(/§./g, ''))
const wanted = process.argv.slice(2)
const runRaid = !wanted.length || wanted.includes('raid')
let failures = 0
const check = (label, ok, extra) => { if (!ok) failures++; log(ok ? 'PASS' : 'FAIL', label, extra === undefined ? '' : extra) }

function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n.extra)
}

async function waitFor (pred, ms, every = 200) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}

function join (prefix) {
  const name = prefix + tag
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  Object.assign(bot, { chatLines: [], bars: [], worldChanges: 0 })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLines.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push(s); log(name, 'BAR', s) } })
  bot._client.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r).slice(0, 200)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    bot.once('spawn', async () => { await sleep(3000); resolve(bot) })
    bot.once('end', r => reject(new Error(name + ' disconnected: ' + r)))
  })
}

function nextWindow (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, ms)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}

const loreOf = item => JSON.stringify((item && item.components) || [])
/** The tooltip lines' text run together (a long flashed reason is wrapped over several), without spaces. */
function loreText (item) {
  const lore = item && item.components && item.components.find(c => c.type === 'lore')
  return (lore ? flat(lore.data) : loreOf(item)).replace(/\s+/g, '')
}

/** Opens the escort hall from the terminal (slot 16), closing whatever screen was open. */
async function hall (bot) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await sleep(400) }
  const main = await nextWindow(bot, () => bot.chat('/cv'))
  if (!main) return null
  const w = await nextWindow(bot, () => bot.clickWindow(16, 0, 0))
  await sleep(300)
  return w
}

/** The hall slot showing `client`'s contract, or -1. */
function contractSlot (bot, client) {
  const w = bot.currentWindow
  if (!w) return -1
  for (let i = 0; i < 27; i++) {
    if (w.slots[i] && loreOf(w.slots[i]).includes('委托人') && loreOf(w.slots[i]).includes(client)) return i
  }
  return -1
}

/** Clicks `slot` in the open hall: what the action bar and the clicked button say about it, and new chat. */
async function click (bot, slot) {
  const bars = bot.bars.length
  const chats = bot.chatLines.length
  await bot.clickWindow(slot, 0, 0)
  await sleep(700)
  return {
    bar: bot.bars.slice(bars).join(' / '),
    tip: loreText(bot.currentWindow && bot.currentWindow.slots[slot]),
    chat: bot.chatLines.slice(chats).join(' / ')
  }
}

/** A refusal: on the action bar, flashed (✖) into the clicked button, and not in chat. */
function refused (who, label, r, want, notName) {
  check(`${who}: ${label}: the action bar says "${want}"`, r.bar.includes(want), r.bar)
  check(`${who}: ${label}: the clicked button flashes it`, r.tip.includes('✖') && r.tip.includes(want.replace(/\s+/g, '')), r.tip.slice(0, 300))
  check(`${who}: ${label}: nothing in chat`, !r.chat.includes(want), r.chat)
  if (notName) {
    const said = [r.bar, r.tip.split('✖')[1] || '', r.chat].join(' / ')
    check(`${who}: ${label}: the answer does not name ${notName}`, !said.includes(notName), said)
  }
}

async function post (bot) {
  const w = await hall(bot)
  if (!w) { check(`${bot.username}: escort hall opened`, false); return }
  const r = await click(bot, 38)
  check(`${bot.username}: posted a contract`, r.chat.includes('护航委托已发布'), r.chat || r.bar)
}

;(async () => {
  let bots = []
  try {
    const A = await join('EbA')
    const B = await join('EbB')
    const E = await join('EbE')
    bots = [A, B, E]
    for (const b of [A, B]) await rcon(`cv money ${b.username} 100000`)
    await sleep(500)
    await post(A)
    await post(B)

    // A, a client, tries to take B's contract and to post another.
    await hall(A)
    let slot = contractSlot(A, B.username)
    check('A sees B\'s contract', slot >= 0)
    refused('A', 'taking B\'s contract', await click(A, slot), '你已经发布了一份护航委托，取消后才能接取别的委托。', A.username)
    await hall(A)
    refused('A', 'posting another', await click(A, 39), '你已经发布了一份护航委托，取消后才能发布新的委托。', A.username)

    // E takes B's contract, then tries B's again, A's, and posting.
    await hall(E)
    slot = contractSlot(E, B.username)
    let r = await click(E, slot)
    check('E took B\'s contract', r.chat.includes('已接取 ' + B.username), r.chat || r.bar)
    await hall(E)
    refused('E', 'taking B\'s contract again', await click(E, contractSlot(E, B.username)), '你已经接取了这份委托。')
    await hall(E)
    refused('E', 'taking A\'s contract', await click(E, contractSlot(E, A.username)),
      `你正在护航 ${B.username}，离开其小队后才能接取别的委托。`)
    await hall(E)
    refused('E', 'posting', await click(E, 38), `你正在护航 ${B.username}，离开其小队后才能发布新的委托。`)
    if (E.currentWindow) E.closeWindow(E.currentWindow)
    for (const b of [A, B]) if (b.currentWindow) b.closeWindow(b.currentWindow)

    if (runRaid) {
      // B and E deploy; E gets out first and is back in the lobby while B is still in the raid.
      await sleep(800)
      const before = [B.worldChanges, E.worldChanges]
      B.chat('/cv queue proving NORMAL TRIO')
      const landed = await waitFor(() => [B, E].every((b, i) => b.worldChanges > before[i] && b.game.gameMode === 'adventure'), 120000)
      check('B and E deployed together', landed, await rcon('cv raids'))
      await sleep(4000)
      const out = E.worldChanges
      await rcon(`execute as ${E.username} at @s run tp @s 106.5 65 8.5`)
      check('E extracted', await waitFor(() => E.worldChanges > out, 40000))
      await sleep(3000)
      const outside = 'E (B still in the raid)'
      await hall(E)
      refused(outside, 'taking A\'s contract', await click(E, contractSlot(E, A.username)),
        `你正在护航 ${B.username}，老板这局结束后才能接取别的委托。`)
      await hall(E)
      refused(outside, 'posting', await click(E, 38), `你正在护航 ${B.username}，老板这局结束后才能发布新的委托。`)
      if (E.currentWindow) E.closeWindow(E.currentWindow)
      await sleep(400)
      E.chat('/cv party leave')
      await sleep(1000)
      await hall(E)
      refused(outside + ' after leaving the party', 'taking A\'s contract', await click(E, contractSlot(E, A.username)),
        `你正在护航 ${B.username}，老板这局结束后才能接取别的委托。`)
      if (E.currentWindow) E.closeWindow(E.currentWindow)

      // B extracts: the contract settles, E is paid and free to take A's.
      const home = B.worldChanges
      await rcon(`execute as ${B.username} at @s run tp @s 106.5 65 8.5`)
      check('B extracted', await waitFor(() => B.worldChanges > home, 40000))
      check('E was paid for guarding B', await waitFor(() => E.chatLines.some(l => l.includes('护航成功')), 8000))
      await sleep(2000)
      await hall(E)
      r = await click(E, contractSlot(E, A.username))
      check('E, free again, takes A\'s contract', r.chat.includes('已接取 ' + A.username), r.chat || r.bar)
      if (E.currentWindow) E.closeWindow(E.currentWindow)
    }

    // Clean up: A calls its contract off (B's settled in the raid, or is called off here).
    for (const b of runRaid ? [A] : [A, B]) {
      await hall(b)
      const own = contractSlot(b, b.username)
      if (own >= 0) await click(b, own)
      if (b.currentWindow) b.closeWindow(b.currentWindow)
    }
  } catch (e) { check('ran', false, e.stack) }
  bots.forEach(b => b.quit())
  log(failures ? `FAILED ${failures}` : 'all passed')
  setTimeout(() => process.exit(failures), 800)
})()
