// New-player guide (issue #43): steps count in any order, the reward is paid however they came,
// and the action-bar reminder stays out of raids. Headless bots with fresh random names:
//
//   order   (Gd#####) a new player is pointed to the terminal (1/6); opening it ticks it off on
//           the action bar and names the range (#7: a chat line would sit behind the terminal);
//           the bot skips the range and deploys straight away: the landing briefing points out the
//           extract (6/6) in chat; 60 s in the raid bring no action-bar reminder (the lobby before
//           brought one); after extracting the lobby still points out the range (2/6) and opening
//           the terminal again does not close the guide; entering the range ticks it off on the
//           action bar (no second chat line under the range's own) and names the FOV Effects
//           check, as does the HUD's objective line on the range (it used to say to leave), and
//           the lobby then points it out in chat with its button (3/6); /cv fov ok ticks
//           that off and names the gunsmith, and an attachment fitted at the gunsmith finishes it:
//           "新手指引完成" and ◈20,000 more.
//   legacy  profiles written before the bot joins, in the old format. The old build lost the guide
//           of a new player who extracted early; it is open again with the deploy and the extract
//           counted: closed at counter 1 (points out the range, 2/6), left open and silent at
//           counter 2 (the FOV Effects check that came after the range, 3/6), and closed at counter 0
//           with every raid report after the guide's release (deployed without the terminal: 1/6). Veterans never see it and their
//           profile is closed without the reward: counter 0 with a report from before the release
//           among later ones, and counter 0 with the full 20 reports, all recent.
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
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const EXTRACT = process.env.EXTRACT || '106.5 65 8.5' // the proving ground's permanent extraction
const PLAYERS = process.env.PLAYERS || path.join(env.DATA, 'data', 'players')
const RAID_SECONDS = Number(process.env.RAID_SECONDS || 60)
const args = process.argv.slice(2)
const want = s => args.length === 0 || args.includes(s)
const tag = () => Math.floor(10000 + Math.random() * 90000)
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
/** The HUD's objective line for a player, as /cv hud reports it. */
const objectiveOf = async name => ((await rcon(`cv hud ${name}`)).match(/objective=([^|]*)/) || [, ''])[1].trim()
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
    const first = await waitChat(bot, /新手指引 1\/6/, 10000)
    check('order: a new player is pointed to the terminal (1/6)', !!first, first)

    // The lobby reminder runs every 45 s: it must show here, so its absence in the raid means something.
    const lobbyBar = await waitFor(() => bot.bars.some(b => b.text.includes('新手指引') && b.world === 'craftov_lobby'), 50000)
    check('order: the lobby repeats the hint on the action bar', lobbyBar)

    let from = bot.chatLog.length
    let barsFrom = bot.bars.length
    check('order: the terminal opens', await openTerminal(bot))
    // On the action bar (#7): a chat line would sit behind the terminal's window.
    const second = await waitFor(() => bot.bars.slice(barsFrom).some(b => /✔ 打开战术终端.*下一步：进入靶场/.test(b.text)), 5000)
    check('order: opening it ticks it off and names the range next', second, bot.bars.slice(barsFrom).map(b => b.text))

    // Straight into a raid, the range skipped.
    from = bot.chatLog.length
    bot.chat('/cv queue proving NORMAL SOLO')
    if (!await waitFor(() => inRaid(bot), 150000)) throw new Error('the bot never reached the raid')
    const extractHint = await waitChat(bot, /新手指引 6\/6/, 8000, from)
    check('order: deploying with the range skipped counts and points out the extract (6/6)', !!extractHint,
      guideLines(bot, from))
    const rangeInRaid = guideLines(bot, from).find(m => m.includes('2/6'))
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
    const back = await waitChat(bot, /新手指引 2\/6/, 8000, from)
    check('order: back in the lobby the guide still points out the range (2/6)', !!back, guideLines(bot, from))
    await sleep(1500)
    check('order: and says it once (not once more on the extract itself)', guideLines(bot, from).filter(m => m.includes('2/6')).length === 1,
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
    barsFrom = bot.bars.length
    const third = await waitFor(() => bot.bars.slice(barsFrom).some(b => /✔ 进入靶场.*下一步：检查 FOV 特效/.test(b.text)), 8000)
    check('order: entering the range ticks it off and names the FOV Effects check next', third, bot.bars.slice(barsFrom).map(b => b.text))
    // The range has its own line in chat; the guide's goes on the action bar (#7: two lines at once before).
    check('order: and adds no guide line to the range\'s own in chat', guideLines(bot, from).length === 0, guideLines(bot, from))
    // The objective line agrees: the FOV Effects check is done anywhere, so it does not send them out.
    const rangeGoal = await objectiveOf(name)
    check('order: on the range the objective line is the FOV Effects check, not the way out',
      rangeGoal.includes('FOV') && !rangeGoal.includes('离开'), rangeGoal)
    from = bot.chatLog.length
    bot.chat('/cv lobby')
    await waitFor(() => inLobby(bot), 10000)
    const fovLine = await waitChat(bot, /新手指引 3\/6/, 6000, from)
    check('order: back in the lobby it points out the FOV Effects check (3/6)', !!fovLine, guideLines(bot, from))
    check('order: with a button that confirms it', !!fovLine && fovLine.includes('[已是 100%]'), fovLine)

    // The FOV Effects check: confirmed with /cv fov ok (what the chat button runs).
    barsFrom = bot.bars.length
    bot.chat('/cv fov ok')
    const fourth = await waitFor(() => bot.bars.slice(barsFrom).some(b => /✔ 检查 FOV 特效.*下一步：装一个配件/.test(b.text)), 5000)
    check('order: confirming FOV Effects ticks it off and names the gunsmith next', fourth, bot.bars.slice(barsFrom).map(b => b.text))

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

const RELEASED = Date.UTC(2026, 10, 11, 13, 17, 51) // the guide's first build, 75f7382b
const reports = (...times) => times.map(time => ({ time, map: 'proving', tier: 'NORMAL', outcome: '撤离成功' }))
const DAY = 86400000

/** Joins as `name` with an old-format profile, then reads the profile saved after the bot leaves. */
async function legacyJoin (name, fields, during) {
  const file = writeProfile(name, fields)
  const bot = await spawnBot(name)
  let left = Infinity
  try {
    await rcon('op ' + name)
    await waitChat(bot, /新手指引/, 6000)
    await openTerminal(bot)
    await sleep(1500)
    await during(bot)
  } finally {
    await rcon('deop ' + name).catch(() => {})
    left = Date.now()
    bot.quit()
  }
  // The server may already have saved the profile while the bot was on (in the new format, but from
  // before the terminal was opened), so only a file written after the bot left counts.
  const read = () => {
    try {
      if (fs.statSync(file).mtimeMs < left) return null
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'))
      return saved.tutorialStep === 0 && saved.tutorialMask !== undefined ? saved : null
    } catch (e) { return null }
  }
  await waitFor(() => read() !== null, 10000)
  await sleep(1000)
  const saved = read()
  return saved && { done: saved.tutorialDone, mask: saved.tutorialMask, money: saved.money }
}

async function legacy () {
  // The deploy (8) and the extract (16) are counted for every guide brought back.
  const reopened = [
    // Closed by the old build: counter at 1 (terminal), one extract behind it.
    { who: 'GdE', label: 'closed early at 1/5', hint: '2/6', mask: 1 | 8 | 16,
      fields: { tutorialStep: 1, tutorialDone: true, stats: { extracts: 1, raids: 1 } } },
    // Never closed, but silent since the extract: counter at 2 (terminal, range).
    { who: 'GdS', label: 'left open and silent at 2/5', hint: '3/6', mask: 1 | 2 | 8 | 16,
      fields: { tutorialStep: 2, tutorialDone: false, stats: { extracts: 1, raids: 1 }, reports: reports(Date.now() - DAY) } },
    // Deployed from the lobby station without the terminal, extracted, then closed by the terminal.
    { who: 'GdD', label: 'closed with no counter, every raid after the release', hint: '1/6', mask: 8 | 16,
      fields: { tutorialStep: 0, tutorialDone: true, stats: { extracts: 1, raids: 2 }, reports: reports(Date.now() - DAY, RELEASED + 60000) } }
  ]
  for (const c of reopened) {
    let hint = null
    const saved = await legacyJoin(c.who + tag(), c.fields, async bot => { hint = guideLines(bot)[0] || null })
    check(`legacy: a guide ${c.label} is open again and points out ${c.hint}`, !!hint && hint.includes(c.hint), hint)
    // Opening the terminal on joining counts it (1) when it was not yet.
    const mask = c.mask | 1
    check(`legacy: and its saved profile counts the deploy and the extract (mask ${mask}), no reward yet`,
      !!saved && saved.done === false && saved.mask === mask && saved.money === 10000, saved)
  }

  const veterans = [
    { who: 'GdV', label: 'a report from before the release among later ones',
      fields: { tutorialStep: 0, tutorialDone: false, stats: { extracts: 3, raids: 4 },
        reports: reports(Date.now() - DAY, Date.now() - 2 * DAY, RELEASED + DAY, RELEASED - DAY) } },
    { who: 'GdF', label: 'the full 20 reports, all after the release',
      fields: { tutorialStep: 0, tutorialDone: true, stats: { extracts: 20, raids: 30 },
        reports: reports(...Array.from({ length: 20 }, (_, i) => Date.now() - (i + 1) * 3600000)) } }
  ]
  for (const c of veterans) {
    let seen = null
    const saved = await legacyJoin(c.who + tag(), c.fields, async bot => {
      seen = guideLines(bot).concat(bot.bars.filter(b => b.text.includes('新手指引')).map(b => b.text))
    })
    check(`legacy: a veteran (${c.label}) never sees the guide`, seen.length === 0, seen)
    check('legacy: and their saved profile has the guide closed, without the reward',
      !!saved && saved.done === true && saved.mask === 0 && saved.money === 10000, saved)
  }
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
