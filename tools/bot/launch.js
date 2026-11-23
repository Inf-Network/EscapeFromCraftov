// Raid launches with headless bots (issue #45): what a launch costs every running raid's tick,
// whether the map's template is copied off the server thread, and players who drop out or try to
// queue again while their map is still being copied.
//
//   node launch.js                         every scenario
//   node launch.js mspt quit requeue       only these (mspt quit squad requeue timeout status)
//
// Needs a local test server (server/setup.sh --local) with RCON on, and in
// plugins/Craftov/config.yml:  match: { wait-seconds: 3 }   test: { enabled: true }
// (the launches go to the proving ground, a test map). Ports come from MC_PORT (25565),
// RCON_PORT (25575) and RCON_PASSWORD (craftov-local); the server directory from SERVER_DIR
// (default the repository's server/run), for its log, its config and the proving ground's template.
//
// mspt: a bot plays a raid of 锈港 while LAUNCHES (4) raids of the proving ground start one after
// another; for each it prints RCON mspt's 5-second maximum just before and just after the launch,
// and the server log's timings for the copy and the world load. Then the same again with the
// template padded (below), where a copy on the server thread shows as a long stall. Passes when
// every copy was logged from a scheduler thread, not the server thread (before the fix it fails
// here, and its numbers are the "before").
// The other scenarios make the copy slow on purpose: for their run the proving ground's template
// gets PAD_FILES (20000) small files in a pad/ folder (removed afterwards), so the bots can act
// while it is still being copied. quit: the bot leaves on "match found" and comes back once the
// copy is done: in the lobby, nothing charged, no raid made for it, and it can queue again.
// squad: of a two-player party one leaves on "match found": the other is told, lands alone, and
// the one who left was charged nothing. requeue: on "match found" the bot queues again: refused,
// then it lands in exactly one raid, and once that raid is ended it can queue again. timeout:
// raid.map-copy-timeout-seconds is set to 1 for the run (put back afterwards): the launch is
// called off with a message, no raid is made when the copy finishes, and the bot can queue again.
// status: with STATUS_PAD (20) times the padding (a copy of several seconds), the bot's action bar says
// "正在部署 试验场 · 地图准备中" and counts up while it waits; /cv raids counts that copy (1), still
// after the bot has left, and 0 once it is done.
// Each scenario prints PASS or FAIL; the exit code is the number of failures.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 3.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const MC_PORT = env.PORT
const RCON = env.RCON
const SERVER_DIR = env.SERVER_DIR
const LOG = path.join(SERVER_DIR, 'logs', 'latest.log')
const CONFIG = path.join(SERVER_DIR, 'plugins', 'Craftov', 'config.yml')
const PAD_DIR = path.join(SERVER_DIR, 'craftov_tpl_proving', 'pad')
const PAD_FILES = Number(process.env.PAD_FILES || 20000)
const LAUNCHES = Number(process.env.LAUNCHES || 4)
const STATUS_PAD = Number(process.env.STATUS_PAD || 20)

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
/** Plain text of a chat component, as JSON or as the NBT mineflayer hands over. */
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
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const run = Math.random().toString(36).slice(2, 5)
let serial = 0

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

const profileFile = name => path.join(SERVER_DIR, 'plugins', 'Craftov', 'data', 'players', offlineUuid(name) + '.json')

/** The profile once its file has stopped changing for a moment (saves are async). */
async function settledProfile (name, ms = 8000) {
  const file = profileFile(name)
  const end = Date.now() + ms
  let last = -1
  while (Date.now() < end) {
    if (fs.existsSync(file)) {
      const m = fs.statSync(file).mtimeMs
      if (m === last) return JSON.parse(fs.readFileSync(file, 'utf8'))
      last = m
    }
    await sleep(600)
  }
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null
}

function make (name = `l${run}${(serial++).toString().padStart(2, '0')}`) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: MC_PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.titles = []
  bot.bars = []
  bot.worldName = ''
  // mineflayer's game.dimension is only the dimension type; the world's name comes with login and respawn.
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot.on('title', (t, type) => bot.titles.push({ at: Date.now(), type, text: strip(JSON.stringify(t)) }))
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push({ at: Date.now(), text: m }) })
  // Paper sends the action bar as its own packet, which mineflayer's 'actionBar' event never reports.
  const bar = text => { if (text.trim()) bot.bars.push({ at: Date.now(), text }) }
  bot._client.on('action_bar', p => bar(flat(p.text)))
  bot._client.on('system_chat', p => { if (p.isActionBar) bar(flat(p.content)) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(50) }
  return false
}

const inLobby = bot => bot.worldName === 'craftov_lobby'
const inRaidWorld = bot => bot.worldName.startsWith('craftov_raid_')
const quit = async bot => { bot.end('disconnect.quitting'); await sleep(300) }
const said = (bot, text, since = 0) => bot.chatLog.some(m => m.at >= since && m.text.includes(text))
const matched = (bot, since = 0) => bot.titles.find(t => t.at >= since && t.text.includes('匹配成功'))
const stat = (p, key) => (p.stats && p.stats[key]) || 0

/** The 5-second maximum of RCON mspt (Paper: "avg/min/max" for 5 s, 10 s and 1 min). */
async function msptMax () {
  const out = await rcon('mspt')
  const m = out.match(/([\d.]+)\/([\d.]+)\/([\d.]+)/)
  return m ? Number(m[3]) : NaN
}

/** Server log lines written since a mark taken with logMark(). */
const logMark = () => { try { return fs.statSync(LOG).size } catch { return 0 } }
function logSince (mark) {
  try {
    const fd = fs.openSync(LOG, 'r')
    const size = fs.fstatSync(fd).size
    const buf = Buffer.alloc(Math.max(0, size - mark))
    fs.readSync(fd, buf, 0, buf.length, mark)
    fs.closeSync(fd)
    return buf.toString('utf8').split('\n').filter(Boolean)
  } catch { return [] }
}
const timings = lines => lines.filter(l => /\[Craftov\] (Copied template|Loaded raid world|Raid world )/.test(l))
const thread = line => (line.match(/^\[[\d:]+\] \[([^\]]+)\//) || [])[1] || '?'

/** The raids /cv raids lists: id, map name and state. */
async function raids () {
  const out = await rcon('cv raids')
  return [...out.matchAll(/(R\d+) (\S+) \S+ (LOADING|RUNNING|ENDING|CLOSED)/g)].map(m => ({ id: m[1], map: m[2], state: m[3] }))
}

async function endAll (map) {
  for (const raid of await raids()) {
    if (!map || raid.map === map) await rcon(`cv end ${raid.id}`)
  }
}

function pad () {
  fs.mkdirSync(PAD_DIR, { recursive: true })
  const data = Buffer.alloc(512, 7)
  for (let i = 0; i < PAD_FILES; i++) fs.writeFileSync(path.join(PAD_DIR, `p${i}.bin`), data)
}
const unpad = () => fs.rmSync(PAD_DIR, { recursive: true, force: true })
/** Raises the padding to `times` times PAD_FILES, for a copy of several seconds. */
function padMore (times) {
  const data = Buffer.alloc(512, 7)
  for (let i = PAD_FILES; i < PAD_FILES * times; i++) fs.writeFileSync(path.join(PAD_DIR, `p${i}.bin`), data)
}

/** The map copies under way, from the last line of /cv raids ("正在复制地图 N 份"). */
async function copies () {
  const m = (await rcon('cv raids')).match(/正在复制地图 (\d+) 份/)
  return m ? Number(m[1]) : NaN
}

async function padded (fn) {
  pad()
  try { return await fn() } finally { unpad() }
}

/** Queues for the proving ground and waits for "match found". Returns when it came, or 0. */
async function queueAndMatch (bot, onMatch) {
  const since = Date.now()
  let fired = false
  const listener = t => {
    if (!fired && strip(JSON.stringify(t)).includes('匹配成功')) { fired = true; onMatch && onMatch() }
  }
  bot.on('title', listener)
  bot.chat('/cv queue proving NORMAL SOLO')
  await waitFor(() => matched(bot, since), 20000)
  bot.off('title', listener)
  const t = matched(bot, since)
  return t ? t.at : 0
}

/** Whether the bot can queue again (it is told "开始匹配"), cancelling that queue straight away. */
async function canQueue (bot) {
  const since = Date.now()
  bot.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => said(bot, '开始匹配', since) || said(bot, '正在部署', since), 3000) && said(bot, '开始匹配', since)
  bot.chat('/cv leave')
  await sleep(500)
  return ok
}

/** One round of launches while a bot plays 锈港; returns a line per launch. */
async function launches (label) {
  const lines = []
  const copies = []
  for (let i = 0; i < LAUNCHES; i++) {
    const bot = await make()
    // Logging in costs the server something too: the "before" window starts once it has settled.
    await sleep(6000)
    const before = await msptMax()
    const mark = logMark()
    const at = await queueAndMatch(bot)
    if (!at) { lines.push(`${label} #${i + 1}: no match-found title`); await quit(bot); continue }
    await sleep(1200)
    const after = await msptMax()
    await waitFor(() => inRaidWorld(bot), 15000)
    await sleep(500)
    const logged = timings(logSince(mark))
    copies.push(...logged.filter(l => /Copied template|copied/.test(l)))
    lines.push(`${label} #${i + 1}: mspt max ${before} -> ${after} | ${logged.map(l => `[${thread(l)}] ${l.replace(/^.*\[Craftov\] /, '')}`).join(' | ')}`)
    await endAll('试验场')
    await sleep(1500)
    await quit(bot)
  }
  return { lines, copies }
}

const scenarios = {
  async mspt () {
    const player = await make()
    await sleep(2500)
    player.chat('/cv queue rustport NORMAL SOLO')
    if (!await waitFor(() => inRaidWorld(player) && player.game.gameMode === 'adventure', 40000)) {
      await quit(player)
      return ['FAIL', `the 锈港 bot never landed (${player.worldName} ${player.game.gameMode})`]
    }
    const plain = await launches('template')
    const heavy = await padded(() => launches(`padded +${PAD_FILES} files`))
    await endAll()
    await quit(player)
    for (const line of [...plain.lines, ...heavy.lines]) console.log('  ' + line)
    const copies = [...plain.copies, ...heavy.copies]
    const onServer = copies.filter(l => thread(l) === 'Server thread')
    if (!copies.length) return ['FAIL', 'no copy timing in the server log']
    return [onServer.length ? 'FAIL' : 'PASS', `${copies.length} copies, ${onServer.length} of them on the server thread`]
  },

  async quit () {
    return padded(async () => {
      let bot = await make()
      const name = bot.username
      await sleep(2500)
      const before = await settledProfile(name)
      const mark = logMark()
      const at = await queueAndMatch(bot, () => bot.end('disconnect.quitting'))
      if (!at) return ['FAIL', 'no match-found title']
      // Back once the copy has finished (its timing is logged) and the launch has been dealt with.
      const copied = await waitFor(() => timings(logSince(mark)).some(l => /Copied template/.test(l)), 30000)
      await sleep(1500)
      const lines = logSince(mark)
      bot = await make(name)
      await sleep(2000)
      const problems = []
      const copy = timings(lines).find(l => /Copied template/.test(l))
      if (!copied) problems.push('no copy timing logged')
      const quitAt = lines.find(l => l.includes(`${name} lost connection`))
      if (lines.some(l => /Loaded raid world/.test(l))) problems.push('a raid world was loaded for nobody')
      if ((await raids()).length) problems.push(`raids left: ${JSON.stringify(await raids())}`)
      if (!inLobby(bot) || bot.game.gameMode !== 'adventure') problems.push(`back in ${bot.worldName} ${bot.game.gameMode}`)
      const after = await settledProfile(name)
      if (after.money !== before.money) problems.push(`money ${before.money} -> ${after.money}`)
      if (stat(after, 'raids') !== stat(before, 'raids')) problems.push(`raids ${stat(before, 'raids')} -> ${stat(after, 'raids')}`)
      if (after.activeRaid || after.deployment) problems.push('a deployment was recorded')
      if (!await canQueue(bot)) problems.push(`cannot queue again: ${bot.chatLog.slice(-2).map(m => m.text).join(' | ')}`)
      await quit(bot)
      return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'left during the copy: no raid, nothing charged, queues again'}`
        + ` | ${copy ? copy.replace(/^.*\[Craftov\] /, '') : ''}${quitAt ? '' : ' (quit not in the log)'}`]
    })
  },

  async squad () {
    return padded(async () => {
      const A = await make()
      const B = await make()
      await sleep(2500)
      const before = await settledProfile(B.username)
      A.chat(`/cv party invite ${B.username}`); await sleep(600)
      B.chat('/cv party accept'); await sleep(600)
      const mark = logMark()
      B.on('title', t => { if (strip(JSON.stringify(t)).includes('匹配成功')) B.end('disconnect.quitting') })
      A.chat('/cv queue proving NORMAL TRIO')
      const landed = await waitFor(() => inRaidWorld(A) && A.game.gameMode === 'adventure', 40000)
      await sleep(1000)
      const lines = logSince(mark)
      const copied = lines.findIndex(l => /Copied template/.test(l))
      const left = lines.findIndex(l => l.includes(`${B.username} lost connection`))
      const problems = []
      if (left < 0 || copied < 0 || left > copied) problems.push('the quit did not come during the copy')
      if (!landed) problems.push(`leader did not land (${A.worldName} ${A.game.gameMode})`)
      if (!said(A, '部署前断开')) problems.push('leader was not told')
      const list = await raids()
      await endAll()
      await sleep(2000)
      await quit(A)
      const after = await settledProfile(B.username)
      if (after.money !== before.money || stat(after, 'raids') !== stat(before, 'raids') || after.deployment) {
        problems.push(`the one who left was charged or deployed (money ${before.money} -> ${after.money})`)
      }
      if (list.length !== 1) problems.push(`${list.length} raids`)
      return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || 'left during the copy: the leader was told and landed alone, nothing charged']
    })
  },

  async requeue () {
    return padded(async () => {
      const bot = await make()
      await sleep(2500)
      const mark = logMark()
      let requeuedAt = 0
      const at = await queueAndMatch(bot, () => { requeuedAt = Date.now(); bot.chat('/cv queue proving NORMAL SOLO') })
      if (!at) return ['FAIL', 'no match-found title']
      const landed = await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'adventure', 40000)
      await sleep(1000)
      const problems = []
      const refused = said(bot, '正在部署', requeuedAt)
      const lines = logSince(mark)
      const copy = timings(lines).find(l => /Copied template/.test(l))
      if (!refused) problems.push(`second queue not refused: ${bot.chatLog.filter(m => m.at >= requeuedAt).map(m => m.text).join(' | ')}`)
      if (said(bot, '开始匹配', requeuedAt)) problems.push('queued a second time')
      if (!landed) problems.push(`did not land (${bot.worldName} ${bot.game.gameMode})`)
      const list = await raids()
      if (list.length !== 1) problems.push(`${list.length} raids: ${JSON.stringify(list)}`)
      await endAll()
      await waitFor(() => inLobby(bot), 15000)
      await sleep(3000)
      // The settlement screen of the ended raid.
      await rcon(`cv close ${bot.username}`)
      await sleep(500)
      if (!await canQueue(bot)) problems.push(`cannot queue after the raid: ${bot.chatLog.slice(-2).map(m => m.text).join(' | ')}`)
      await quit(bot)
      return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'second queue refused, landed in one raid, queues again afterwards'}`
        + ` | ${copy ? copy.replace(/^.*\[Craftov\] /, '') : ''}`]
    })
  },

  async timeout () {
    let original
    try { original = fs.readFileSync(CONFIG, 'utf8') } catch { return ['FAIL', `cannot read ${CONFIG}`] }
    const short = /^\s*map-copy-timeout-seconds:/m.test(original)
      ? original.replace(/^(\s*map-copy-timeout-seconds:\s*)\d+/m, '$11')
      : original.replace(/^raid:\n/m, 'raid:\n  map-copy-timeout-seconds: 1\n')
    fs.writeFileSync(CONFIG, short)
    try {
      if (!(await rcon('cv reload')).includes('已重新加载')) return ['FAIL', 'cv reload failed']
      return await padded(async () => {
        // A copy that outlasts a second for sure: eight times the padding (four took 1.1 to 2.3 s here).
        padMore(8)
        const bot = await make()
        await sleep(2500)
        const mark = logMark()
        const at = await queueAndMatch(bot)
        if (!at) return ['FAIL', 'no match-found title']
        const told = await waitFor(() => said(bot, '超时', at), 6000)
        const copied = await waitFor(() => timings(logSince(mark)).some(l => /Copied template/.test(l)), 60000)
        await sleep(1500)
        const lines = logSince(mark)
        const problems = []
        if (!told) problems.push(`not told: ${bot.chatLog.filter(m => m.at >= at).map(m => m.text).join(' | ')}`)
        if (!copied) problems.push('no copy timing logged')
        if (lines.some(l => /Loaded raid world/.test(l))) problems.push('a raid world was loaded after the timeout')
        if ((await raids()).length) problems.push(`raids left: ${JSON.stringify(await raids())}`)
        if (!inLobby(bot)) problems.push(`in ${bot.worldName}`)
        if (!await canQueue(bot)) problems.push(`cannot queue again: ${bot.chatLog.slice(-2).map(m => m.text).join(' | ')}`)
        await quit(bot)
        const copy = timings(lines).find(l => /Copied template/.test(l))
        return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'called off with a message, no raid afterwards, queues again'}`
          + ` | ${copy ? copy.replace(/^.*\[Craftov\] /, '') : ''}`]
      })
    } finally {
      fs.writeFileSync(CONFIG, original)
      await rcon('cv reload').catch(() => {})
    }
  },

  async status () {
    return padded(async () => {
      // Eight times took 2.2 to 2.8 s here once the disk cache was warm: the bot needs about 4.5 s.
      padMore(STATUS_PAD)
      const bot = await make()
      await sleep(2500)
      const mark = logMark()
      const at = await queueAndMatch(bot)
      if (!at) return ['FAIL', 'no match-found title']
      await sleep(3600)
      const copyDone = () => timings(logSince(mark)).some(l => /Copied template/.test(l))
      const problems = []
      const bars = bot.bars.filter(b => b.at >= at - 500 && b.text.includes('地图准备中'))
      const waits = bars.map(b => (b.text.match(/(\d+):(\d+)\s*$/) || []).slice(1).reduce((m, s) => m * 60 + Number(s), 0))
      const waiting = await copies()
      const early = copyDone()
      if (early) problems.push('the copy was done within 3.6 s: pad more (STATUS_PAD)')
      if (bars.length < 3) problems.push(`${bars.length} status lines in 3.6 s: ${bot.bars.filter(b => b.at >= at - 500).map(b => b.text).join(' | ')}`)
      if (!bars.every(b => b.text.includes('正在部署 试验场'))) problems.push(`status line: ${bars.map(b => b.text).join(' | ')}`)
      if (waits.some((w, i) => i > 0 && w < waits[i - 1]) || waits[waits.length - 1] < 2) problems.push(`waits ${waits.join(', ')}`)
      if (waiting !== 1) problems.push(`/cv raids: ${waiting} copies while the bot waits`)
      await quit(bot)
      await sleep(800)
      const left = await copies()
      const stillCopying = !copyDone()
      if (!stillCopying) problems.push('the copy was done before the bot had left: pad more (STATUS_PAD)')
      else if (left !== 1) problems.push(`/cv raids: ${left} copies once the bot had left, with the copy still running`)
      if (!await waitFor(copyDone, 60000)) problems.push('no copy timing logged')
      await sleep(1500)
      const done = await copies()
      if (done !== 0) problems.push(`/cv raids: ${done} copies once it was done`)
      if ((await raids()).length) problems.push(`raids left: ${JSON.stringify(await raids())}`)
      const copy = timings(logSince(mark)).find(l => /Copied template/.test(l))
      return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || `status line counted ${waits.join(', ')} s; copies 1 waiting, ${left} after leaving, 0 done`}`
        + ` | ${copy ? copy.replace(/^.*\[Craftov\] /, '') : ''}`]
    })
  }
}

;(async () => {
  const wanted = process.argv.slice(2)
  const names = wanted.length ? wanted : Object.keys(scenarios)
  let failures = 0
  for (const name of names) {
    let result
    try {
      result = await scenarios[name]()
    } catch (e) {
      result = ['FAIL', e.stack]
    }
    if (result[0] === 'FAIL') failures++
    console.log(`${result[0]} ${name}: ${result[1]}`)
    await sleep(2000)
  }
  process.exit(failures)
})()
