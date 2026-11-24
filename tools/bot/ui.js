// Screens, sounds and message channels (issue #6), checked from the protocol side:
//
//   menu       /cv opens the terminal: the open_screen title is in the craftov:ui font and carries
//              the "main" screen's background glyph (U+E100..); clicking a button plays
//              craftov:ui.click; the free-kit button on a rich account flashes its refusal into the
//              button's tooltip and plays craftov:ui.error.
//   tooltips   one item of each rarity carries tooltip_style craftov:rarity_<rarity>; a menu button
//              carries craftov:ui.
//   channels   three solo bots in one proving-ground raid: B dies. B's squad gets "☠ B" on its HUD
//              feed (`cv hud`), A's and C's do not (credit.js checks the killer's side); C extracts,
//              and A hears nothing of it. No
//              chat line anyone gets starts with "[Craftov]", and no two action bars (countdowns
//              aside) come less than 10 ticks apart.
//   settle     the extracting bot gets the black-curtain title, then the world change, then the
//              settlement screen ("行动结算") within 3 s of arriving. Entering the raid, the curtain
//              comes before the world change too, and no other title replaces it within its 28 ticks
//              (the map's name waits until it has lifted).
//   squad      two bots in a party deploy as a trio: each one's squad bar (`cv hud`) lists the other
//              with their health; when one goes down the other's alert banner says so, and the
//              squad bar shows "倒地".
//
//   PORT=25565 RCON_PORT=25575 node ui.js [menu tooltips channels squad]   (settle runs with channels)
//
// The server needs test.enabled (bots queue for the proving ground). Exit code: failed checks.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = c => rconRaw(c, RCON).then(r => r.replace(/§./g, ''))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['menu', 'tooltips', 'channels', 'squad']
const failures = []
function check (name, ok, detail) {
  if (!ok) failures.push(name)
  log(ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : detail)
}

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

/** A bot that records chat, action bars (with time), titles, sounds, screens and world changes. */
function join (prefix) {
  const name = prefix + tag
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  Object.assign(bot, { chatLines: [], bars: [], events: [], sounds: [], screens: [], worldChanges: 0 })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLines.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push({ at: Date.now(), text: s }); log(name, 'BAR', s) } })
  bot._client.on('set_title_text', p => bot.events.push({ at: Date.now(), kind: 'title', text: JSON.stringify(p.text) }))
  bot._client.on('respawn', () => { bot.worldChanges++; bot.events.push({ at: Date.now(), kind: 'world' }) })
  bot._client.on('open_window', p => { const t = JSON.stringify(p.windowTitle || p.title); bot.screens.push(t); bot.events.push({ at: Date.now(), kind: 'screen', text: t }) })
  for (const packet of ['sound_effect', 'entity_sound_effect']) {
    bot._client.on(packet, p => bot.sounds.push({ at: Date.now(), text: JSON.stringify(p) }))
  }
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
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

async function menu () {
  const a = await join('UiM')
  const w = await nextWindow(a, () => a.chat('/cv'))
  const title = a.screens[a.screens.length - 1] || ''
  check('the terminal title uses the craftov:ui font', title.includes('craftov:ui'), title.slice(0, 160))
  check('the terminal title carries a screen background glyph (U+E1xx)', /[-]|\\ue1[0-9a-f]{2}/i.test(title), title.slice(0, 160))
  if (!w) { check('terminal opened', false); a.quit(); return }
  const before = a.sounds.length
  const open = await nextWindow(a, () => a.clickWindow(11, 0, 0)) // stash
  await sleep(400)
  const heard = a.sounds.slice(before).map(s => s.text)
  check('a button click plays craftov:ui.click', heard.some(s => s.includes('ui.click')), heard.slice(0, 3))
  check('the stash that opens plays craftov:ui.open', heard.some(s => s.includes('ui.open')), heard.slice(0, 3))
  if (open) a.closeWindow(open)
  await sleep(500)
  // A rich account is refused the free kit: the refusal comes into the button, with the error sound.
  await rcon(`cv money ${a.username} 5000000`)
  const w2 = await nextWindow(a, () => a.chat('/cv'))
  if (w2) {
    const mark = a.sounds.length
    await a.clickWindow(30, 0, 0)
    await sleep(700)
    const lore = loreOf(a.currentWindow && a.currentWindow.slots[30])
    check('a refused click flashes the reason into the button tooltip', lore.includes('✖'), lore.slice(0, 200))
    check('a refused click plays craftov:ui.error', a.sounds.slice(mark).some(s => s.text.includes('ui.error')))
    a.closeWindow(w2)
  }
  a.quit()
}

async function tooltips () {
  const a = await join('UiT')
  const items = { white: 'bandage', green: 'k47', blue: 'm4c', purple: 'armor_4', gold: 'gold_bar', red: 'star_crystal' }
  for (const id of Object.values(items)) await rcon(`cv give ${a.username} ${id}`)
  await sleep(1500)
  for (const [rarity, id] of Object.entries(items)) {
    const item = a.inventory.items().find(i => loreOf(i).includes('"' + id + '"'))
    const comps = loreOf(item)
    check(`${id} (${rarity}) has tooltip_style craftov:rarity_${rarity}`, comps.includes('rarity_' + rarity), comps.slice(0, 80))
  }
  const w = await nextWindow(a, () => a.chat('/cv'))
  const button = w && w.slots[11]
  check('a menu button has tooltip_style craftov:ui', loreOf(button).includes('craftov:ui"') || loreOf(button).includes('"craftov:ui'), loreOf(button).slice(0, 120))
  if (w) a.closeWindow(w)
  a.quit()
}

async function channels () {
  const [A, B, C] = [await join('UiA'), await join('UiB'), await join('UiC')]
  const all = [A, B, C]
  const before = all.map(b => b.worldChanges)
  const entryMark = A.events.length
  for (const b of all) b.chat('/cv queue proving NORMAL SOLO')
  const inRaid = await waitFor(() => all.every((b, i) => b.worldChanges > before[i] && b.game.gameMode === 'adventure'), 120000)
  check('three solo bots land in one proving-ground raid', inRaid)
  if (!inRaid) { all.forEach(b => b.quit()); return }
  // Entering: the curtain comes before the jump into the raid world and stays its full time (in 4,
  // hold 14, out 10 ticks) before any other title takes its place.
  const entry = A.events.slice(entryMark)
  const cur = entry.findIndex(e => e.kind === 'title' && /|\\ue0ff/i.test(e.text))
  const jump = entry.findIndex((e, i) => i > cur && e.kind === 'world')
  const after = entry.findIndex((e, i) => i > cur && e.kind === 'title')
  check('entering: curtain, then the jump into the raid world', cur >= 0 && jump > cur, entry.map(e => e.kind))
  check('entering: no other title cuts the curtain short', cur >= 0 && after > cur && entry[after].at - entry[cur].at >= 1350,
    cur >= 0 && after > cur ? `${entry[after].at - entry[cur].at} ms` : 'no title after the curtain')
  await sleep(4000) // spawn protection
  const barsFrom = A.bars.length
  // B dies (no killer: Craftov cancels a bare player-on-player hit, and credit.js covers the killer's feed).
  log('kill', await rcon(`damage ${B.username} 1000 minecraft:generic`))
  const died = await waitFor(() => B.game.gameMode === 'spectator', 15000)
  log('B died', died, B.chatLines.slice(-3))
  await sleep(1500)
  const feed = async b => ((await rcon(`cv hud ${b.username}`)).match(/feedlog=([^|]*)/) || [, ''])[1]
  const [fa, fb, fc] = [await feed(A), await feed(B), await feed(C)]
  check('the dead player\'s squad gets "☠ B" on its feed', fb.includes('☠') && fb.includes(B.username), fb)
  check('the other squads do not', !fa.includes(B.username) && !fc.includes(B.username), { A: fa, C: fc })
  check('nobody gets it in chat', !all.some(b => b.chatLines.some(l => l.includes('→') && l.includes(B.username))))
  // C extracts: black curtain, world change, settlement screen within 3 s; A hears nothing of it.
  const mark = C.events.length
  const chatA = A.chatLines.length
  await rcon(`execute as ${C.username} at @s run tp @s 106.5 65 8.5`)
  const home = await waitFor(() => C.events.slice(mark).some(e => e.kind === 'screen' && e.text.includes('行动结算')), 30000)
  const ev = C.events.slice(mark)
  const curtain = ev.findIndex(e => e.kind === 'title' && /|\\ue0ff/i.test(e.text))
  const world = ev.findIndex(e => e.kind === 'world')
  const screen = ev.findIndex(e => e.kind === 'screen' && e.text.includes('行动结算'))
  check('extracting: curtain, then the trip home, then the settlement screen', home && curtain >= 0 && curtain < world && world < screen,
    ev.map(e => e.kind))
  check('the settlement screen opens within 3 s of arriving', home && ev[screen].at - ev[world].at <= 3000,
    home ? `${ev[screen].at - ev[world].at} ms` : 'no screen')
  check('the other squads hear nothing of the extraction', !A.chatLines.slice(chatA).some(l => l.includes(C.username)))
  check('no chat line starts with "[Craftov]"', !all.some(b => b.chatLines.some(l => l.trim().startsWith('[Craftov]'))),
    all.flatMap(b => b.chatLines.filter(l => l.includes('[Craftov]'))))
  // Action bars: countdowns update under one key and may come every few ticks; anything else keeps
  // at least 10 ticks (500 ms) apart.
  const bars = A.bars.slice(barsFrom).filter(b => !/倒计时|救援|倒地|匹配中|撤离中/.test(b.text))
  const tooClose = bars.filter((b, i) => i > 0 && b.at - bars[i - 1].at < 450 && b.text !== bars[i - 1].text)
  check('no two action bars within 10 ticks of each other', tooClose.length === 0, tooClose.map(b => b.text))
  await rcon(`execute as ${A.username} at @s run tp @s 106.5 65 8.5`)
  await sleep(12000)
  all.forEach(b => b.quit())
}

async function squad () {
  const [A, B] = [await join('UiP'), await join('UiQ')]
  A.chat(`/cv party invite ${B.username}`); await sleep(700)
  B.chat('/cv party accept'); await sleep(700)
  const before = [A.worldChanges, B.worldChanges]
  A.chat('/cv queue proving NORMAL TRIO')
  const landed = await waitFor(() => [A, B].every((b, i) => b.worldChanges > before[i] && b.game.gameMode === 'adventure'), 120000)
  check('a party of two lands as one squad', landed)
  if (!landed) { A.quit(); B.quit(); return }
  await sleep(4000)
  const hud = async b => rcon(`cv hud ${b.username}`)
  const mates = (await hud(A)).match(/mates=([^|]*)/)
  check('the squad bar lists the squadmate with their health', !!mates && mates[1].includes(B.username) && mates[1].includes('❤'),
    mates && mates[1])
  // B carries no injector in a trio, so a lethal hit with a squadmate standing knocks it down.
  await rcon(`damage ${B.username} 1000 minecraft:generic`)
  await sleep(1500)
  const after = await hud(A)
  check('A\'s alert banner says B is down', /alert=[^|]*倒地/.test(after) && after.includes(B.username), after.split('|')[0])
  check('A\'s squad bar shows B down', /mates=[^|]*倒地/.test(after), (after.match(/mates=([^|]*)/) || [])[1])
  for (const b of [A, B]) await rcon(`execute as ${b.username} at @s run tp @s 106.5 65 8.5`)
  await sleep(12000)
  A.quit(); B.quit()
}

;(async () => {
  const runs = { menu, tooltips, channels, squad }
  for (const s of scenarios) {
    log('scenario', s)
    try { await runs[s]() } catch (e) { check(`${s} ran`, false, e.stack) }
    await sleep(1500)
  }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'all passed')
  process.exit(failures.length)
})()
