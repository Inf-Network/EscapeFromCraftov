// Data safety checks (issue #20). This script STOPS, STARTS and once KILLS -9 the server in
// SERVER_DIR, and edits its data files while it is down (everything it changes is put back), so
// only point it at a throwaway test server that nothing else is starting or stopping. The server
// must be running when the script starts; it is left running at the end.
//
//  corrupt        a truncated profile: the bot is refused at login, the file is left as it was and
//                 a byte-equal <uuid>.json.corrupt-* copy exists; once the file is put back the bot
//                 gets in again with its old balance
//  escort-mia     an escort client (guard accepted) who disconnects in a raid and times out comes
//                 back with an empty loadout, a missing-in-action report, no "server interrupted"
//                 restore and the right refund (raid.reconnect-seconds is set to 10 for this)
//  stop-mid-raid  the server stops while a bot is in a raid: no shutdown step fails, and the
//                 profile has no raid left; the raid is void (#67), so the loadout is the one it
//                 went in with and what it found in the raid is not in it
//  hot-reload     /bukkit:reload confirm with a bot online holding a gun: a middle click keeps the
//                 connection and is answered by the reloaded plugin, the gun fires in the range
//                 without reconnecting, and the log has no plugin errors
//  market-expire  three listings of one offline seller expire in the same tick after a restart:
//                 all three land in his mailbox. An expired listing of a seller whose profile is
//                 broken stays listed (no journal, the file untouched, one warning), and goes to
//                 his mailbox once the file is repaired while the server runs
//  lost-edit      an escort client's profile breaks while he is disconnected in a raid: the
//                 timeout and the escrow release go into <uuid>.json.unsaved, the guard is paid,
//                 the repaired profile stays locked and keeps its escrow over a restart until the
//                 journal is applied by hand, then logs in with the refund once and no restore
//  market-corrupt a garbled market.json survives a start and a stop byte for byte
//  map            rustport.yml with "type: SWTICH" survives a restart unchanged, a .broken copy
//                 exists, the log reports it and the map is not regenerated
//  escort-crash   a posted escort contract followed by kill -9: the start log reports it, and the
//                 reward is back (with a chat notice) when the client logs in again
//  backup         RESTARTS restarts with backup.first-delay-minutes 0 keep the first daily copy, and
//                 a shutdown backup that cannot read a file leaves no zip (or temp file) behind
//  backup-keep    a config with only the old "keep: 30" keeps ten older shutdown copies over a
//                 shutdown backup, and the start log says the key was split
//
// node persistence.js   env: SERVER_DIR (required, the run folder; CRAFTOV_RUN is read too), PORT,
//                            RCON_PORT, RCON_PASSWORD (env.js), STEPS=(comma list, default all)
//                            RESTARTS=10 MEMORY=3G LOG=/tmp/persistence.log
// Exit code 0 when every check passed. Linux only (finds the server process through /proc).
//
// Server config (tools/bot/suite.js sets it for its run): the script edits what it needs itself
// while the server is down and puts it back (see above).
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR (which must be set).
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { spawn } = require('child_process')
const rconRaw = require('./rcon')
const env = require('./env')
const { pullTrigger } = require('./common')

// Named on purpose (env.js's SERVER_DIR or CRAFTOV_RUN), never the default: the script stops,
// kills and edits the server it is pointed at.
const SERVER_DIR = env.SERVER_DIR_SET ? env.SERVER_DIR : ''
if (!SERVER_DIR || !fs.existsSync(path.join(SERVER_DIR, 'start.sh'))) {
  console.error('SERVER_DIR must point at the server run folder (with start.sh)')
  process.exit(2)
}
const PORT = env.PORT
const RCON = env.RCON
const RESTARTS = Number(process.env.RESTARTS || 10)
const ALL = ['corrupt', 'escort-mia', 'lost-edit', 'stop-mid-raid', 'hot-reload', 'market-expire', 'market-corrupt', 'map', 'escort-crash',
  'backup', 'backup-keep']
const STEPS = process.env.STEPS ? process.env.STEPS.split(',').map(s => s.trim()) : ALL
const out = process.env.LOG || '/tmp/persistence.log'
const PLUGIN = path.join(SERVER_DIR, 'plugins/Craftov')
const CONSOLE = path.join(SERVER_DIR, 'persistence-console.log')
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
const tag = () => String(Date.now() % 100000)

// ------------------------------------------------------------------ server control

function serverPid () {
  const want = fs.realpathSync(SERVER_DIR)
  for (const pid of fs.readdirSync('/proc').filter(d => /^\d+$/.test(d))) {
    try {
      if (!fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('paper.jar')) continue
      if (fs.realpathSync(`/proc/${pid}/cwd`) === want) return Number(pid)
    } catch { /* gone or not ours */ }
  }
  return null
}

async function waitGone (ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (!serverPid()) return true
    await sleep(500)
  }
  return false
}

async function stop () {
  try { await rcon('stop') } catch (e) { log('stop: rcon', e.message) }
  if (!await waitGone(90000)) throw new Error('server did not stop')
  log('server stopped')
}

async function kill9 () {
  const pid = serverPid()
  if (!pid) throw new Error('no server to kill')
  process.kill(pid, 'SIGKILL')
  if (!await waitGone(30000)) throw new Error('server did not die')
  log('server killed (-9)')
}

/** Starts the server and waits for "Done (" in its console output; returns that output. */
async function start () {
  if (serverPid()) throw new Error('server already running')
  const fd = fs.openSync(CONSOLE, 'w')
  spawn('bash', ['start.sh'], { cwd: SERVER_DIR, detached: true, stdio: ['ignore', fd, fd], env: { ...process.env, MEMORY: process.env.MEMORY || '3G' } }).unref()
  fs.closeSync(fd)
  const end = Date.now() + 240000
  while (Date.now() < end) {
    await sleep(1000)
    const text = fs.readFileSync(CONSOLE, 'utf8')
    if (text.includes('Done (')) {
      for (let i = 0; i < 20; i++) {
        try { await rcon('list'); break } catch { await sleep(500) }
      }
      // Stopping within seconds of a start makes CraftEngine wait a minute for its workers.
      await sleep(8000)
      log('server started')
      return text
    }
    if (!serverPid()) throw new Error('server exited while starting:\n' + text.slice(-2000))
  }
  throw new Error('server did not start')
}
const consoleText = () => fs.readFileSync(CONSOLE, 'utf8')

// ------------------------------------------------------------------ files

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const playersDir = path.join(PLUGIN, 'data/players')
const profileFile = name => path.join(playersDir, offlineUuid(name) + '.json')
const profile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
async function waitFile (file, pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try { if (pred(fs.readFileSync(file, 'utf8'))) return true } catch { /* not there yet */ }
    await sleep(500)
  }
  return false
}

/** Sets (or adds) "key: value" inside the top-level "section:" of the server's config.yml. */
function setConfig (section, key, value) {
  const file = path.join(PLUGIN, 'config.yml')
  let text = fs.readFileSync(file, 'utf8')
  const head = new RegExp(`^${section}:\\s*$`, 'm')
  if (!head.test(text)) {
    text += `\n${section}:\n  ${key}: ${value}\n`
  } else {
    const lines = text.split('\n')
    const at = lines.findIndex(l => head.test(l))
    let i = at + 1
    let done = false
    for (; i < lines.length && (lines[i].startsWith(' ') || lines[i].trim() === '' || lines[i].trim().startsWith('#')); i++) {
      if (new RegExp(`^  ${key}:`).test(lines[i])) { lines[i] = `  ${key}: ${value}`; done = true; break }
    }
    if (!done) lines.splice(at + 1, 0, `  ${key}: ${value}`)
    text = lines.join('\n')
  }
  fs.writeFileSync(file, text)
}

// ------------------------------------------------------------------ bots

function join (name) {
  return new Promise(resolve => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
    bot.chatLog = []
    bot.titles = []
    bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
    bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
    bot.on('title', t => bot.titles.push(typeof t === 'string' ? t : JSON.stringify(t)))
    bot.bars = []
    bot._client.on('action_bar', p => bot.bars.push(JSON.stringify(p))) // raw: the event misses NBT text
    bot.on('error', e => log(name, 'ERROR', e.message))
    const timer = setTimeout(() => resolve({ error: 'timeout' }), 60000)
    // A refused login is answered within a few milliseconds, sometimes before mineflayer has loaded
    // its plugins (it does on a timer after createBot), the one that turns this packet into 'kicked'
    // among them: the bot then sees only the connection close (#138). The client parses the login's
    // disconnect packet either way, so the reason is taken from there.
    bot._client.once('disconnect', p => {
      const reason = typeof p.reason === 'string' ? p.reason : JSON.stringify(p.reason)
      clearTimeout(timer)
      log(name, 'REFUSED', reason)
      resolve({ kicked: reason })
    })
    bot.once('kicked', r => { clearTimeout(timer); log(name, 'KICKED', r); resolve({ kicked: typeof r === 'string' ? r : JSON.stringify(r) }) })
    let spawned = false
    bot.once('spawn', async () => { spawned = true; clearTimeout(timer); await sleep(3000); resolve({ bot }) })
    // Closed before spawning with no word (a kick resolves first): say so now, not after the minute.
    bot.once('end', reason => {
      if (spawned) return
      clearTimeout(timer)
      log(name, 'END', reason)
      setTimeout(() => resolve({ error: 'connection closed: ' + reason }), 200)
    })
  })
}
async function online (name) {
  const r = await join(name)
  if (!r.bot) throw new Error(name + ' could not join: ' + (r.kicked || r.error))
  return r.bot
}
async function leave (bot) {
  bot.quit()
  await sleep(1500)
}
function openWith (bot, fn, ms = 5000) {
  return new Promise(resolve => {
    const t = setTimeout(() => resolve(null), ms)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
    fn()
  })
}
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
const lore = item => (comp(item, 'lore') || []).map(txt)
const money = s => { const m = String(s).match(/◈([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }
const balance = async name => money(await rcon(`cv money ${name} 0`))
async function escortMenu (bot) {
  const main = await openWith(bot, () => bot.chat('/cv'))
  if (!main) throw new Error('main menu did not open')
  const menu = await openWith(bot, () => bot.clickWindow(16, 0, 0))
  if (!menu) throw new Error('escort menu did not open')
  return menu
}
async function postContract (bot) {
  const menu = await escortMenu(bot)
  await bot.clickWindow(38, 0, 0) // ◈20,000
  await sleep(800)
  bot.closeWindow(menu)
  await sleep(300)
}

// ------------------------------------------------------------------ steps

async function corrupt () {
  const name = 'Brk' + tag()
  const bot = await online(name)
  const before = await balance(name)
  await leave(bot)
  const file = profileFile(name)
  // The logout write lands on the io thread, and the copy stays in memory until a flush (10 s)
  // sees it on disk; wait past that so the next login has to read the file.
  await waitFile(file, () => true, 10000)
  await sleep(12000)
  const good = fs.readFileSync(file)
  const broken = good.subarray(0, Math.floor(good.length / 2))
  fs.writeFileSync(file, broken)
  const r = await join(name)
  if (r.bot) await leave(r.bot)
  check('corrupt: login refused', !!r.kicked && r.kicked.includes('档案读取失败'), r.kicked || r.error || 'joined')
  await sleep(1500)
  check('corrupt: file left as it was', Buffer.compare(fs.readFileSync(file), broken) === 0)
  const copies = fs.readdirSync(playersDir).filter(f => f.startsWith(path.basename(file) + '.corrupt-'))
  check('corrupt: one byte-equal .corrupt copy', copies.length === 1
    && Buffer.compare(fs.readFileSync(path.join(playersDir, copies[0])), broken) === 0, copies)
  const again = await join(name)
  if (again.bot) await leave(again.bot)
  check('corrupt: refused again, no second copy', !!again.kicked
    && fs.readdirSync(playersDir).filter(f => f.startsWith(path.basename(file) + '.corrupt-')).length === 1)
  fs.writeFileSync(file, good)
  const fixed = await online(name)
  const after = await balance(name)
  check('corrupt: repaired file logs in with the old balance', after === before, { before, after })
  await leave(fixed)
  for (const c of copies) fs.rmSync(path.join(playersDir, c), { force: true })
}

async function escortMia () {
  setConfig('raid', 'reconnect-seconds', 10)
  await rcon('cv reload')
  const id = tag()
  const bossName = 'Mia' + id
  const guardName = 'Grd' + id
  const boss = await online(bossName)
  const guard = await online(guardName)
  try {
    await postContract(boss)
    const menu = await escortMenu(guard)
    let slot = -1
    for (let i = 0; i < 27; i++) if (lore(menu.slots[i]).some(l => l.includes(bossName))) slot = i
    if (slot < 0) throw new Error('contract not listed')
    await guard.clickWindow(slot, 0, 0)
    await sleep(800)
    guard.closeWindow(menu)
    const bossBefore = await balance(bossName)
    const guardBefore = await balance(guardName)
    log('balances after posting', { bossBefore, guardBefore })
    boss.chat('/cv queue proving NORMAL TRIO')
    const end = Date.now() + 120000
    while (Date.now() < end && !boss.titles.some(t => t.includes('行动开始'))) await sleep(500)
    check('escort-mia: client deployed', boss.titles.some(t => t.includes('行动开始')))
    await sleep(2000)
    await leave(boss)
    // 10 s reconnect window, checked once a second, then the missing-in-action settlement.
    const file = profileFile(bossName)
    const settled = await waitFile(file, s => {
      const p = JSON.parse(s)
      return p.activeRaid == null && (p.reports || []).length > 0
    }, 40000)
    await sleep(2000)
    const p = profile(bossName)
    log('client profile', { money: p.money, escrow: p.escortEscrow, loadout: Object.keys(p.loadout || {}), activeRaid: p.activeRaid, report: p.reports && p.reports[0] })
    check('escort-mia: settled as missing', settled && String(p.reports[0].outcome).includes('失踪'), p.reports && p.reports[0] && p.reports[0].outcome)
    // Going missing keeps the melee weapon (slot 2), nothing else.
    check('escort-mia: loadout emptied but for the melee weapon', Object.keys(p.loadout || {}).every(slot => slot === '2'), p.loadout)
    check('escort-mia: refund of 70% kept with it', p.money === bossBefore + 14000 && !p.escortEscrow, { money: p.money, expected: bossBefore + 14000, escrow: p.escortEscrow })
    check('escort-mia: guard got 30%', await balance(guardName) === guardBefore + 6000)
    const back = await online(bossName)
    check('escort-mia: no "server interrupted" restore on login', !back.chatLog.some(m => m.includes('服务器中断')))
    const carried = back.inventory.items().map(i => i.name)
    log('client carries after login', carried)
    await leave(back)
  } finally {
    await rcon('cv end').catch(() => {})
    await sleep(1500)
    guard.quit()
    setConfig('raid', 'reconnect-seconds', 120)
    await rcon('cv reload').catch(() => {})
  }
}

async function lostEdit () {
  // Long enough for the logout copy to leave memory (flushed every 10 s) before the profile
  // breaks, so the timeout has to read the broken file.
  setConfig('raid', 'reconnect-seconds', 25)
  await rcon('cv reload')
  const id = tag()
  const bossName = 'Jrn' + id
  const guardName = 'Jgd' + id
  const boss = await online(bossName)
  const guard = await online(guardName)
  const file = profileFile(bossName)
  const journal = file + '.unsaved'
  try {
    await postContract(boss)
    const menu = await escortMenu(guard)
    let slot = -1
    for (let i = 0; i < 27; i++) if (lore(menu.slots[i]).some(l => l.includes(bossName))) slot = i
    if (slot < 0) throw new Error('contract not listed')
    await guard.clickWindow(slot, 0, 0)
    await sleep(800)
    guard.closeWindow(menu)
    const guardBefore = await balance(guardName)
    boss.chat('/cv queue proving NORMAL TRIO')
    const end = Date.now() + 120000
    while (Date.now() < end && !boss.titles.some(t => t.includes('行动开始'))) await sleep(500)
    check('lost-edit: client deployed', boss.titles.some(t => t.includes('行动开始')))
    await sleep(2000)
    await leave(boss)
    await sleep(13000)
    const good = fs.readFileSync(file)
    const broken = good.subarray(0, Math.floor(good.length / 2))
    fs.writeFileSync(file, broken)
    const journaled = await waitFile(journal, s => s.includes('escort contract'), 40000)
    const text = journaled ? fs.readFileSync(journal, 'utf8') : ''
    log('journal', text)
    const left = text.match(/missing in raid \S+: loadout = (\{.*?\}) \(the rest of that gear stayed in the raid\)/)
    check('lost-edit: the timeout is journaled with its resets', !!left && Object.keys(JSON.parse(left[1])).every(slot => slot === '2')
      && /missing in raid .*activeRaid = null, (deployment = null, )?stats\.deaths \+1/.test(text), left && left[1])
    check('lost-edit: the escrow release is journaled after it', text.indexOf('escortEscrow -20000') > text.indexOf('missing in raid')
      && text.includes('money +14000'))
    check('lost-edit: the broken file is left as it was', Buffer.compare(fs.readFileSync(file), broken) === 0)
    check('lost-edit: the guard got 30%', await balance(guardName) === guardBefore + 6000)
    fs.writeFileSync(file, good) // repaired, the journal not applied yet
    // join() hears the refusal from the login's disconnect packet (#138), so a close with no reason
    // is a failure here, at once.
    const r = await join(bossName)
    if (r.bot) await leave(r.bot)
    check('lost-edit: login refused while the journal is there', !!r.kicked && r.kicked.includes('档案读取失败'), r.kicked || r.error || 'joined')
    guard.quit()
    await stop()
    const started = await start()
    check('lost-edit: the locked escrow is reported on start', started.split('\n').some(l => l.includes('is NOT returned') && l.includes(offlineUuid(bossName))))
    check('lost-edit: the repaired file is untouched', Buffer.compare(fs.readFileSync(file), good) === 0)
    // Apply the journal by hand, as the README says, then delete it.
    const p = JSON.parse(good)
    const secure = text.match(/secure = (\[.*?\]), activeRaid/)
    p.loadout = left ? JSON.parse(left[1]) : {}
    p.secure = secure ? JSON.parse(secure[1]) : p.secure
    p.activeRaid = null
    // The deployment record goes too (the journal says so): one left behind is a raid the server
    // stopped in, which the next login would void.
    if (/deployment = null/.test(text)) p.deployment = null
    p.stats = p.stats || {}
    p.stats.deaths = (p.stats.deaths || 0) + 1
    p.escortEscrow = Math.max(0, (p.escortEscrow || 0) - 20000)
    p.money += 14000
    fs.writeFileSync(file, JSON.stringify(p, null, 2))
    fs.rmSync(journal)
    const back = await online(bossName)
    const after = await balance(bossName)
    check('lost-edit: logs in with the refund once', after === p.money, { after, expected: p.money })
    check('lost-edit: no "server interrupted" restore', !back.chatLog.some(m => m.includes('服务器中断')))
    await leave(back)
    await sleep(12000)
    await stop()
    await start()
    const again = await online(bossName)
    check('lost-edit: nothing returned again on the next start', await balance(bossName) === p.money)
    await leave(again)
  } finally {
    await rcon('cv end').catch(() => {})
    try { guard.quit() } catch { /* already gone */ }
    setConfig('raid', 'reconnect-seconds', 120)
    await rcon('cv reload').catch(() => {})
    fs.rmSync(journal, { force: true })
    for (const f of fs.readdirSync(playersDir).filter(f => f.startsWith(path.basename(file) + '.corrupt-'))) fs.rmSync(path.join(playersDir, f))
  }
}

async function marketExpire () {
  const name = 'Sel' + tag()
  const bot = await online(name)
  await leave(bot)
  // A second seller whose profile is broken: his listing must wait for the repair.
  const brokenName = 'Lost' + tag()
  await leave(await online(brokenName))
  await sleep(12000)
  await stop()
  const file = profileFile(name)
  const p = profile(name)
  if ((p.stash || []).length < 3) throw new Error('starter stash has fewer than 3 items')
  const marketFile = path.join(PLUGIN, 'data/market.json')
  const market = fs.existsSync(marketFile) ? JSON.parse(fs.readFileSync(marketFile, 'utf8')) : { version: 1, listings: [], reference: {}, history: {} }
  const now = Date.now()
  const moved = p.stash.splice(0, 3)
  moved.forEach((item, i) => market.listings.push({
    id: 'persist' + now + i, seller: p.uuid, sellerName: name, item, price: 999999, created: now - 3600000, expires: now - 1000
  }))
  const brokenFile = profileFile(brokenName)
  const brokenGood = fs.readFileSync(brokenFile)
  const lostItem = JSON.parse(brokenGood).stash[0]
  market.listings.push({
    id: 'persistlost' + now, seller: offlineUuid(brokenName), sellerName: brokenName, item: lostItem, price: 999999, created: now - 3600000, expires: now - 1000
  })
  const broken = brokenGood.subarray(0, 40)
  fs.writeFileSync(brokenFile, broken)
  const mailboxBefore = (p.mailbox || []).length
  fs.writeFileSync(file, JSON.stringify(p, null, 2))
  fs.writeFileSync(marketFile, JSON.stringify(market, null, 2))
  await start()
  await sleep(15000)
  const after = profile(name)
  check('market-expire: all three expired listings in the mailbox', (after.mailbox || []).length === mailboxBefore + 3,
    { before: mailboxBefore, after: (after.mailbox || []).length })
  const listed = () => JSON.parse(fs.readFileSync(marketFile, 'utf8')).listings.some(l => l.id === 'persistlost' + now)
  check('market-expire: the broken seller\'s listing stays listed', listed())
  check('market-expire: no journal for it', !fs.existsSync(brokenFile + '.unsaved'))
  check('market-expire: the broken profile is left as it was', Buffer.compare(fs.readFileSync(brokenFile), broken) === 0)
  const banners = consoleText().split('\n').filter(l => l.includes('==== Profile ' + path.basename(brokenFile) + ' is unreadable')).length
  check('market-expire: the broken profile is reported once, not every tick', banners === 1, banners)
  // Repaired while the server runs: the next market tick returns the listing.
  const mails = (JSON.parse(brokenGood).mailbox || []).length
  fs.writeFileSync(brokenFile, brokenGood)
  const back = await waitFile(brokenFile, s => { try { return (JSON.parse(s).mailbox || []).length === mails + 1 } catch { return false } }, 30000)
  check('market-expire: once repaired, the listing goes to his mailbox', back && !listed())
  for (const f of fs.readdirSync(playersDir).filter(f => f.startsWith(path.basename(brokenFile) + '.corrupt-'))) fs.rmSync(path.join(playersDir, f))
}

async function marketCorrupt () {
  const marketFile = path.join(PLUGIN, 'data/market.json')
  await stop()
  const good = fs.existsSync(marketFile) ? fs.readFileSync(marketFile) : null
  const garbled = Buffer.from('{"version":1,"listings":[{"id":"x", oops')
  fs.writeFileSync(marketFile, garbled)
  const text = await start()
  check('market-corrupt: reported on start', text.includes('could not be read') && text.includes('market is closed'))
  await stop()
  check('market-corrupt: file unchanged after start and stop', Buffer.compare(fs.readFileSync(marketFile), garbled) === 0)
  if (good) fs.writeFileSync(marketFile, good)
  else fs.rmSync(marketFile)
  await start()
}

async function map () {
  const file = path.join(PLUGIN, 'maps/rustport.yml')
  await stop()
  const good = fs.readFileSync(file, 'utf8')
  if (!good.includes('type: SWITCH')) throw new Error('rustport.yml has no SWITCH extract')
  const bad = good.replace('type: SWITCH', 'type: SWTICH')
  fs.writeFileSync(file, bad)
  const text = await start()
  check('map: file unchanged', fs.readFileSync(file, 'utf8') === bad)
  check('map: .broken copy', fs.existsSync(file + '.broken') && fs.readFileSync(file + '.broken', 'utf8') === bad)
  check('map: reported', text.includes('maps/rustport.yml is invalid'))
  check('map: not regenerated', !text.includes("Generating built-in raid map '锈港'"))
  await stop()
  fs.writeFileSync(file, good)
  fs.rmSync(file + '.broken', { force: true })
  await start()
}

async function escortCrash () {
  const name = 'Crs' + tag()
  const bot = await online(name)
  const before = await balance(name)
  await postContract(bot)
  const posted = await balance(name)
  check('escort-crash: reward taken', posted === before - 20000, { before, posted })
  await sleep(2000)
  await kill9()
  bot.end()
  const text = await start()
  check('escort-crash: lost contract reported on start', text.includes('escort contracts lost when the server stopped'))
  const back = await online(name)
  await sleep(1500)
  const after = await balance(name)
  check('escort-crash: reward back on login after kill -9', after === before, { before, after })
  check('escort-crash: told on login', back.chatLog.some(m => m.includes('护航委托因服务器中断已撤销')))
  await leave(back)
}

async function backup () {
  const dir = path.join(PLUGIN, 'backups')
  const zips = kind => (fs.existsSync(dir) ? fs.readdirSync(dir) : []).filter(f => f.endsWith(`-${kind}.zip`)).sort()
  setConfig('backup', 'first-delay-minutes', 0)
  let first = null
  for (let i = 0; i < RESTARTS; i++) {
    await stop()
    await start()
    await sleep(2000) // the first daily check runs a tick after start
    if (first == null) first = zips('auto')[0] || null
    log(`restart ${i + 1}/${RESTARTS}: daily`, zips('auto').length, 'shutdown', zips('shutdown').length)
  }
  check('backup: a daily copy was made', first != null)
  check('backup: the first daily copy survives the restarts', first != null && zips('auto').includes(first), zips('auto'))
  check('backup: shutdown copies capped at 5', zips('shutdown').length <= 5, zips('shutdown').length)
  // A file the backup cannot read makes the shutdown copy fail: nothing may be left behind.
  const blocker = path.join(PLUGIN, 'data/unreadable.bin')
  fs.writeFileSync(blocker, 'x')
  fs.chmodSync(blocker, 0)
  const before = fs.readdirSync(dir).sort()
  await stop()
  const after = fs.readdirSync(dir).sort()
  fs.chmodSync(blocker, 0o644)
  fs.rmSync(blocker)
  check('backup: failed copy leaves no zip or temp file', JSON.stringify(before) === JSON.stringify(after), { before: before.length, after: after.length })
  setConfig('backup', 'first-delay-minutes', 10)
  await start()
}

async function backupKeep () {
  const dir = path.join(PLUGIN, 'backups')
  const cfgFile = path.join(PLUGIN, 'config.yml')
  await stop()
  const cfg = fs.readFileSync(cfgFile, 'utf8')
  // A config from before the split: only "keep", meant for all copies together.
  fs.writeFileSync(cfgFile, cfg.replace(/^ {2}keep-daily: .*\n/m, '').replace(/^ {2}keep-shutdown: .*\n/m, '')
    .replace(/^backup:\n/m, 'backup:\n  keep: 30\n'))
  fs.mkdirSync(dir, { recursive: true })
  const fakes = []
  for (let i = 0; i < 10; i++) {
    const f = path.join(dir, `craftov-20200101-00000${i}-shutdown.zip`)
    fs.writeFileSync(f, '')
    fakes.push(f)
  }
  try {
    const text = await start()
    check('backup-keep: the split is noted on start', text.includes('backup.keep is now'))
    await stop()
    const kept = fakes.filter(f => fs.existsSync(f)).length
    check('backup-keep: older shutdown copies kept under the old keep: 30', kept === 10, kept)
  } finally {
    fs.writeFileSync(cfgFile, cfg)
    for (const f of fakes) fs.rmSync(f, { force: true })
  }
  await start()
}

async function stopMidRaid () {
  const name = 'Hom' + tag()
  const bot = await online(name)
  bot.chat('/cv queue proving NORMAL SOLO')
  const end = Date.now() + 120000
  while (Date.now() < end && !bot.titles.some(t => t.includes('行动开始'))) await sleep(500)
  check('stop-mid-raid: deployed', bot.titles.some(t => t.includes('行动开始')))
  await sleep(2000)
  await rcon(`cv give ${name} surgery 1`) // something found in the raid
  await sleep(1000)
  await stop()
  bot.end()
  const text = consoleText()
  const p = profile(name)
  const carried = Object.values(p.loadout || {}).map(r => r.id)
  check('stop-mid-raid: no failed shutdown step', !text.includes('Shutdown step') && !text.includes('Could not send'), text.match(/.*(Shutdown step|Could not send).*/g))
  check('stop-mid-raid: raid cleared from the profile', p.activeRaid == null, p.activeRaid)
  // A raid the server stops in is void (#67): the kit it went in with, not the surgery kit it found.
  check('stop-mid-raid: void, the loadout it went in with and nothing found in the raid', !carried.includes('surgery') && carried.includes('k47'), carried)
  await start()
}

async function hotReload () {
  const name = 'Rld' + tag()
  const bot = await online(name)
  let shots = 0
  // The shooter hears the first-person shot as a sound attached to their own entity.
  const heard = p => { if (/gun\./.test(JSON.stringify(p.sound || p.soundId || p.soundEvent || ''))) shots++ }
  bot._client.on('sound_effect', heard)
  bot._client.on('entity_sound_effect', heard)
  let ended = false
  bot.on('end', () => { ended = true })
  // Keys 1-2 are the only cells a gun goes to (#10) and the kit's rifle and pistol hold them.
  await rcon(`item replace entity ${name} hotbar.0 with minecraft:air`)
  await rcon(`cv give ${name} m4c`)
  await rcon(`cv give ${name} ammo_556_m855 60`)
  await sleep(800)
  const gunSlot = () => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes('m4c'))
  bot.setQuickBarSlot(gunSlot() - 36)
  await sleep(1500) // held long enough to count as armed
  const logFile = path.join(SERVER_DIR, 'logs/latest.log')
  const mark = fs.readFileSync(logFile, 'utf8').length
  log('reload:', (await rcon('bukkit:reload confirm')).replace(/§./g, '').slice(0, 120))
  await sleep(6000)
  // Middle click with the gun: before the fix the old handler scheduled for the disabled plugin
  // on a Netty thread, which threw and dropped the connection.
  const at = bot.entity.position.floored().offset(0, -1, 0)
  bot.bars.length = 0
  bot._client.write('pick_item_from_block', { position: at, includeData: false })
  await sleep(2000)
  check('hot-reload: middle click with a gun keeps the connection', !ended)
  // The reloaded plugin answers it (no light on this gun); a handler left over from the old
  // load would swallow it.
  check('hot-reload: middle click reaches the reloaded plugin', bot.bars.some(b => b.includes('战术手电')), bot.bars)
  if (!ended) {
    // Firing works without reconnecting: into the shooting range (the lobby rack), tap once.
    await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
    await sleep(1000)
    const rack = Object.values(bot.entities).filter(e => e.name === 'interaction')
      .sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
    if (rack) bot.activateEntity(rack)
    await sleep(2500)
    await rcon(`cv give ${name} m4c`)
    await rcon(`cv give ${name} ammo_556_m855 60`)
    await sleep(800)
    if (gunSlot() >= 0) bot.setQuickBarSlot(gunSlot() - 36)
    await sleep(800)
    // Q loads the magazine first, then one tap of the trigger as the vanilla client sends it (the
    // capture block in front of the crosshair dug, a swing, the abort).
    bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await sleep(4500)
    shots = 0
    await pullTrigger(bot)
    await sleep(800)
    check('hot-reload: the gun fires after /reload without reconnecting', shots > 0, shots)
    await leave(bot)
  }
  const since = fs.readFileSync(logFile, 'utf8').slice(mark)
  const bad = since.split('\n').filter(l => /IllegalPluginAccessException|Shutdown step|Exception.*craftov/i.test(l))
  check('hot-reload: no plugin errors around the reload', bad.length === 0, bad.slice(0, 3))
}

const STEP_FNS = {
  'hot-reload': hotReload,
  'stop-mid-raid': stopMidRaid,
  corrupt,
  'escort-mia': escortMia,
  'lost-edit': lostEdit,
  'market-expire': marketExpire,
  'market-corrupt': marketCorrupt,
  map,
  'escort-crash': escortCrash,
  backup,
  'backup-keep': backupKeep
}

;(async () => {
  try {
    if (!serverPid()) throw new Error('start the server in SERVER_DIR first')
    for (const step of STEPS) {
      if (!STEP_FNS[step]) throw new Error('unknown step ' + step)
      log('==== ' + step)
      try {
        await STEP_FNS[step]()
      } catch (e) {
        check(step + ': ran to the end', false, e.stack)
      }
      if (!serverPid()) await start()
    }
  } catch (e) {
    check('script', false, e.stack)
  }
  log(failures.length ? 'FAILED: ' + failures.join(', ') : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
