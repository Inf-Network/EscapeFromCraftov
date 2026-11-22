// New-player guide (issue #43): steps count in any order, the reward is paid however they came,
// and the action-bar reminder stays out of raids. Headless bots with fresh random names:
//
//   order   (Gd#####) a new player opens the terminal (1/5), skips the range and deploys straight
//           away: the deploy points out the extract (5/5) in chat; 60 s in the raid bring no
//           action-bar reminder (the lobby before brought one); after extracting the lobby still
//           points out the range (2/5) and opening the terminal again does not close the guide; the
//           range (then 3/5) and an attachment fitted at the gunsmith finish it: "新手指引完成" and
//           ◈20,000 more.
//   legacy  profiles written before the bot joins, in the old format: a guide the old build closed
//           early (step counter 1, done, one extract) is open again and points out the range (2/5);
//           a veteran (counter 0, three extracts) never sees it, and their profile is closed.
//
//   PORT=25565 RCON_PORT=25575 node guide.js [order] [legacy]     env: RCON_PASSWORD, EXTRACT, PLAYERS
//
// Needs a local test server with RCON on and the whitelist off (or the bots added), with the guide
// on (tutorial.enabled, the default). For a short run, in plugins/Craftov/config.yml:
// match.wait-seconds 3, cinematic.enabled false, and no soldiers to shoot the bot during its minute
// in the raid (ai.count-multiplier 0, ai.operator-chance [0, 0, 0], events.per-raid 0). 'legacy'
// writes profile files into PLAYERS (default ../../server/run/plugins/Craftov/data/players), so it
// must run on the same machine as the server. Bots are made op for the test map and deopped when
// done. Prints PASS / FAIL per check; exit code 0 when every check passed, 1 otherwise.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const EXTRACT = process.env.EXTRACT || '106.5 65 8.5' // the proving ground's permanent extraction
const PLAYERS = process.env.PLAYERS || path.join(__dirname, '../../server/run/plugins/Craftov/data/players')
const RAID_SECONDS = Number(process.env.RAID_SECONDS || 60)
const args = process.argv.slice(2)
const want = s => args.length === 0 || args.includes(s)
const tag = () => Math.floor(10000 + Math.random() * 90000)
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
const money = s => { const m = String(s).match(/◈ ?([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }

const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes or JSON). */
function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') {
    if (n.startsWith('{') || n.startsWith('[')) { try { return flat(JSON.parse(n)) } catch (e) { return n } }
    return n
  }
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n['']) + flat(n.extra)
}

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

// ------------------------------------------------------------------ bot

/** A bot that logs chat and action-bar lines with when they came and where the bot was. */
function spawnBot (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.bars = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  // Paper sends the action bar as its own packet, which mineflayer's 'actionBar' event never reports.
  const bar = s => {
    if (!s.trim()) return
    bot.bars.push({ at: Date.now(), world: bot.worldName, text: s })
    if (s.includes('新手指引')) log(name, 'BAR', bot.worldName, s)
  }
  bot._client.on('action_bar', p => bar(flat(p.text)))
  bot._client.on('system_chat', p => { if (p.isActionBar) bar(flat(p.content)) })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(t); resolve(bot) })
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
const guideLines = (bot, from = 0) => bot.chatLog.slice(from).filter(m => m.includes('新手指引'))
async function balance (bot) { return money(await rcon(`cv money ${bot.username} 0`)) }

async function openTerminal (bot) {
  const w = await openWith(bot, () => bot.chat('/cv menu'))
  if (w) { bot.closeWindow(w); await sleep(300) }
  return !!w
}

// ------------------------------------------------------------------ scenarios

async function order () {
  const bot = await spawnBot('Gd' + tag())
  const name = bot.username
  try {
    await rcon('op ' + name)
    const first = await waitChat(bot, /新手指引 1\/5/, 10000)
    check('order: a new player is pointed to the terminal (1/5)', !!first, first)

    // The lobby reminder runs every 45 s: it must show here, so its absence in the raid means something.
    const lobbyBar = await waitFor(() => bot.bars.some(b => b.text.includes('新手指引') && b.world === 'craftov_lobby'), 50000)
    check('order: the lobby repeats the hint on the action bar', lobbyBar)

    let from = bot.chatLog.length
    check('order: the terminal opens', await openTerminal(bot))
    const second = await waitChat(bot, /新手指引 2\/5/, 5000, from)
    check('order: opening it points out the range next (2/5)', !!second, second)

    // Straight into a raid, the range skipped.
    from = bot.chatLog.length
    bot.chat('/cv queue proving NORMAL SOLO')
    if (!await waitFor(() => inRaid(bot), 150000)) throw new Error('the bot never reached the raid')
    const extractHint = await waitChat(bot, /新手指引 5\/5/, 8000, from)
    check('order: deploying with the range skipped counts and points out the extract (5/5)', !!extractHint,
      guideLines(bot, from))
    const rangeInRaid = guideLines(bot, from).find(m => m.includes('2/5'))
    check('order: and the raid does not point out the range', !rangeInRaid, rangeInRaid)
    const raidFrom = Date.now()
    log(`staying ${RAID_SECONDS} s in the raid`)
    await sleep(RAID_SECONDS * 1000)
    const raidBars = bot.bars.filter(b => b.at >= raidFrom && b.text.includes('新手指引'))
    check(`order: no guide hint on the action bar in ${RAID_SECONDS} s of raid`, raidBars.length === 0 && inRaid(bot),
      { bars: raidBars.map(b => b.text), inRaid: inRaid(bot), otherBars: bot.bars.filter(b => b.at >= raidFrom).length })

    from = bot.chatLog.length
    await rcon(`execute as ${name} at @s run tp @s ${EXTRACT}`)
    if (!await waitFor(() => inLobby(bot), 30000)) throw new Error('the bot did not extract')
    const back = await waitChat(bot, /新手指引 2\/5/, 8000, from)
    check('order: back in the lobby the guide still points out the range (2/5)', !!back, guideLines(bot, from))
    await sleep(1500)
    check('order: and says it once (not once more on the extract itself)', guideLines(bot, from).filter(m => m.includes('2/5')).length === 1,
      guideLines(bot, from))

    // The old build closed the guide here: the next step taken took the player for a veteran.
    from = bot.chatLog.length
    await openTerminal(bot)
    await sleep(1500)
    check('order: opening the terminal again leaves the guide open', !bot.chatLog.slice(from).some(m => m.includes('完成')), bot.chatLog.slice(from))

    // The range: entered from the lobby rack.
    from = bot.chatLog.length
    await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
    await sleep(1500)
    const rack = bot.nearestEntity(e => e.name === 'interaction')
    if (rack) bot.activateEntity(rack)
    const third = await waitChat(bot, /新手指引 3\/5/, 8000, from)
    check('order: entering the range counts and points out the gunsmith (3/5)', !!third, guideLines(bot, from))
    bot.chat('/cv lobby')
    await waitFor(() => inLobby(bot), 10000)
    await sleep(3000)

    // The gunsmith: a red dot on an M4C.
    await rcon(`clear ${name}`)
    await sleep(800)
    await rcon(`cv give ${name} m4c`)
    await rcon(`cv give ${name} optic_reddot`)
    await sleep(800)
    const before = await balance(bot)
    from = bot.chatLog.length
    const bench = await openWith(bot, () => bot.chat('/cv mod'))
    if (!bench) throw new Error('the gunsmith did not open')
    await bot.clickWindow(45, 0, 0)
    const finished = await waitChat(bot, /新手指引完成/, 5000, from)
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await sleep(500)
    const after = await balance(bot)
    check('order: fitting the attachment finishes the guide', !!finished, finished)
    check('order: and pays ◈20,000', after - before === 20000, { before, after })
  } finally {
    await rcon('deop ' + name).catch(() => {})
    bot.quit()
    await sleep(1000)
  }
}

function writeProfile (name, fields) {
  const uuid = offlineUuid(name)
  const file = path.join(PLAYERS, uuid + '.json')
  fs.mkdirSync(PLAYERS, { recursive: true })
  fs.writeFileSync(file, JSON.stringify({ uuid, name, receivedStarter: true, money: 10000, ...fields }, null, 2))
  return file
}

async function legacy () {
  // A guide the old build closed early: counter at 1 (terminal), closed, one extract behind it.
  const early = 'GdE' + tag()
  writeProfile(early, { tutorialStep: 1, tutorialDone: true, stats: { extracts: 1, raids: 1 } })
  let bot = await spawnBot(early)
  try {
    const hint = await waitChat(bot, /新手指引/, 10000)
    check('legacy: a guide the old build closed early is open again and points out the range (2/5)', !!hint && hint.includes('2/5'), hint)
  } finally {
    bot.quit()
    await sleep(1000)
  }

  // A veteran from before the guide: counter 0, never closed, three extracts.
  const veteran = 'GdV' + tag()
  const file = writeProfile(veteran, { tutorialStep: 0, tutorialDone: false, stats: { extracts: 3, raids: 4 } })
  bot = await spawnBot(veteran)
  try {
    await rcon('op ' + veteran)
    await sleep(5000)
    await openTerminal(bot)
    await sleep(1500)
    check('legacy: a veteran never sees the guide', guideLines(bot).length === 0 && !bot.bars.some(b => b.text.includes('新手指引')),
      guideLines(bot))
  } finally {
    await rcon('deop ' + veteran).catch(() => {})
    bot.quit()
  }
  let saved = null
  await waitFor(() => {
    try { saved = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (e) { saved = null }
    return saved && saved.tutorialDone === true
  }, 10000)
  check('legacy: and their saved profile has the guide closed, without the reward', !!saved && saved.tutorialDone === true &&
    saved.tutorialMask === 0 && saved.money === 10000, saved && { done: saved.tutorialDone, mask: saved.tutorialMask, money: saved.money })
}

;(async () => {
  try {
    if (want('order')) await order()
    if (want('legacy')) await legacy()
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
setTimeout(() => { log('timeout'); process.exit(1) }, 480000)
