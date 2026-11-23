// Market writes under load at shutdown (issue #62): bots list, buy and take down listings as fast as
// the menus let them, offline sellers' listings expire every market tick, and the server is stopped
// in the middle of it. Afterwards the files on disk must hold exactly the state the server stopped in.
//
// Each round (ROUNDS of them):
//   1. With the server down, BOTS bot accounts are written before they first join (level 3,
//      ◈50,000,000, STASH tracked guns each, room for everything) and OFFLINE seller accounts that
//      never join. market.json is replaced by LISTINGS listings of the offline sellers, each a
//      tracked gun; a third of them expire one after another while the bots trade, so every market
//      tick hands some back to offline mailboxes right up to the stop.
//   2. config.yml: no listing fee, no sale tax, no system buyers, min-buy-level 1, max-listings 40,
//      so money and guns only move between the round's accounts and the market.
//   3. The server starts, the bots join and loop: list two guns from the stash, buy up to three
//      listings of somebody else, take one of their own listings down (back to the mailbox).
//   4. After STOP_MIN..STOP_MAX seconds of trading, "stop" over RCON while every bot is mid-click.
//      While they trade, market.json is read every 100 ms: it must parse every time.
//   5. Once the server is gone:
//      - market.json parses, has no listing twice, and holds as many listings as the final save
//        logged ("Saved the market: N listings.");
//      - no temp file is left in data/ or data/players/;
//      - every tracked gun is in exactly one place: a listing, or a stash, mailbox, loadout, secure
//        container or letter of one of the round's accounts (none lost, none twice);
//      - the round's accounts still hold all their money between them (the fee and tax are 0);
//      - the shutdown logged no failed step and no failed save;
//      - enough happened: at least MIN_TRADES confirmed purchases and as many listings in the
//        round, and the bots were still getting trades confirmed in the last 2 s before the stop.
//
// The last SLOW_ROUNDS rounds run on a slow disk: the server starts with
// -Dcraftov.test.write-delay-ms=SLOW_MS (through JAVA_TOOL_OPTIONS), so every data file write
// waits that long and the profile writer falls behind the trading. The shutdown then gives up
// waiting for it after 10 seconds ("did not finish in 10 seconds" must be logged) and writes the
// final saves while older copies are still queued: the race of #62. Those rounds must pass every
// check above all the same; a queued copy landing on a final save shows up as a market.json that
// is not the one the final save logged, or as guns and money lost or counted twice.
//
// The script STOPS and STARTS the server in SERVER_DIR and rewrites its market.json and config.yml
// while it is down (both are put back at the end, and the round's accounts are deleted unless
// KEEP=1), so only point it at a throwaway test server. It may be running or not at the start; it
// is left the way it was found.
//
// node marketio.js   env: SERVER_DIR=(required, the run folder) PORT=25565 RCON_PORT=25575
//                         RCON_PASS=craftov-local ROUNDS=3 BOTS=6 OFFLINE=20 LISTINGS=600 STASH=40
//                         STOP_MIN=20 STOP_MAX=40 MIN_TRADES=40 SLOW_ROUNDS=1 SLOW_MS=150
//                         MEMORY=3G KEEP=0 LOG=/tmp/marketio.log
// Exit code 0 when every check passed. Linux only (finds the server process through /proc).
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { spawn } = require('child_process')
const rconRaw = require('./rcon')

const SERVER_DIR = process.env.SERVER_DIR ? path.resolve(process.env.SERVER_DIR) : ''
if (!SERVER_DIR || !fs.existsSync(path.join(SERVER_DIR, 'start.sh'))) {
  console.error('SERVER_DIR must point at the server run folder (with start.sh)')
  process.exit(2)
}
const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const ROUNDS = Number(process.env.ROUNDS || 3)
const BOTS = Number(process.env.BOTS || 6)
const OFFLINE = Number(process.env.OFFLINE || 20)
const LISTINGS = Number(process.env.LISTINGS || 600)
const STASH = Number(process.env.STASH || 40)
const STOP_MIN = Number(process.env.STOP_MIN || 20)
const STOP_MAX = Math.max(STOP_MIN, Number(process.env.STOP_MAX || 40))
const MIN_TRADES = Number(process.env.MIN_TRADES || 40)
const SLOW_ROUNDS = Math.min(ROUNDS, Number(process.env.SLOW_ROUNDS ?? 1))
const SLOW_MS = Number(process.env.SLOW_MS || 150)
const KEEP = process.env.KEEP === '1'
const BOT_MONEY = 50_000_000
const GUNS = ['p18', 'sv9', 'v57', 'k47', 'm4c', 'scr', 'kl7', 'r12', 'dm14', 'd50']
const PLUGIN = path.join(SERVER_DIR, 'plugins/Craftov')
const DATA = path.join(PLUGIN, 'data')
const PLAYERS = path.join(DATA, 'players')
const MARKET = path.join(DATA, 'market.json')
const CONFIG = path.join(PLUGIN, 'config.yml')
const CONSOLE = path.join(SERVER_DIR, 'marketio-console.log')
const out = process.env.LOG || '/tmp/marketio.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => {
  const line = `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')
  fs.appendFileSync(out, line + '\n')
  console.log(line)
}
const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}
/** RCON that gives up after `ms` (a "stop" may never be answered). */
function rcon (cmd, ms = 10000) {
  return Promise.race([rconRaw(cmd, RCON), sleep(ms).then(() => { throw new Error('rcon timeout: ' + cmd) })])
}

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
    await sleep(200)
  }
  return false
}

async function stop () {
  if (!serverPid()) return
  try { await rcon('stop') } catch (e) { log('stop: rcon', e.message) }
  if (!await waitGone(120000)) throw new Error('server did not stop')
  log('server stopped')
}

/**
 * Starts the server and waits for "Done (" in its console output; with `writeDelay`, every data
 * file write on it waits that many milliseconds (a slow disk, see AtomicFiles).
 */
async function start (writeDelay = 0) {
  if (serverPid()) throw new Error('server already running')
  const fd = fs.openSync(CONSOLE, 'w')
  const env = { ...process.env, MEMORY: process.env.MEMORY || '3G' }
  if (writeDelay > 0) env.JAVA_TOOL_OPTIONS = `${env.JAVA_TOOL_OPTIONS || ''} -Dcraftov.test.write-delay-ms=${writeDelay}`.trim()
  spawn('bash', ['start.sh'], { cwd: SERVER_DIR, detached: true, stdio: ['ignore', fd, fd], env }).unref()
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
      return
    }
    if (!serverPid()) throw new Error('server exited while starting:\n' + text.slice(-2000))
  }
  throw new Error('server did not start')
}

// ------------------------------------------------------------------ files

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(PLAYERS, offlineUuid(name) + '.json')

/** Sets (or adds) "key: value" inside the top-level "section:" of the server's config.yml. */
function setConfig (section, key, value) {
  let text = fs.readFileSync(CONFIG, 'utf8')
  const head = new RegExp(`^${section}:\\s*$`, 'm')
  if (!head.test(text)) {
    text += `\n${section}:\n  ${key}: ${value}\n`
  } else {
    const lines = text.split('\n')
    const at = lines.findIndex(l => head.test(l))
    let done = false
    for (let i = at + 1; i < lines.length && (lines[i].startsWith(' ') || lines[i].trim() === '' || lines[i].trim().startsWith('#')); i++) {
      if (new RegExp(`^  ${key}:`).test(lines[i])) { lines[i] = `  ${key}: ${value}`; done = true; break }
    }
    if (!done) lines.splice(at + 1, 0, `  ${key}: ${value}`)
    text = lines.join('\n')
  }
  fs.writeFileSync(CONFIG, text)
}

/** Every tracked gun uid in a list of item records, nested contents included. */
function uidsIn (records, into, where) {
  for (const r of records || []) {
    if (!r) continue
    if (r.uid && r.uid.startsWith('mio')) (into[r.uid] = into[r.uid] || []).push(where)
    uidsIn(r.contents, into, where)
  }
}

/**
 * Writes the round's accounts and its market.json; returns what they hold between them (the uids of
 * every tracked gun and the money).
 */
function seed (round, bots, sellers) {
  let n = 0
  const gun = () => ({ id: GUNS[n % GUNS.length], amount: 1, uid: `mio${round.tag}-${n++}`, dura: 1.0 })
  const base = { level: 3, xp: 0, receivedStarter: true, tutorialDone: true, stashCapacity: 100000, autoInsure: false }
  for (const name of bots) {
    const stash = Array.from({ length: STASH }, gun)
    fs.writeFileSync(profileFile(name), JSON.stringify({ ...base, uuid: offlineUuid(name), name, money: BOT_MONEY, stash }, null, 2))
  }
  for (const name of sellers) {
    fs.writeFileSync(profileFile(name), JSON.stringify({ ...base, uuid: offlineUuid(name), name, money: 0 }, null, 2))
  }
  const now = Date.now()
  const listings = []
  for (let i = 0; i < LISTINGS; i++) {
    const seller = sellers[i % sellers.length]
    // Every third one expires, one after another, from 20 s after seeding to well past the stop
    // (the server takes 20-40 s to start): the first market tick hands back what expired during
    // the start, and every tick after it some more, right up to the stop.
    const expiring = i % 3 === 0
    const expires = expiring ? now + 20000 + Math.floor((i / LISTINGS) * (STOP_MAX + 60) * 1000) : now + 48 * 3600000
    listings.push({ id: `mio${round.tag}l${i}`, seller: offlineUuid(seller), sellerName: seller, item: gun(), price: 9000 + (i % 7) * 500, created: now - 3600000, expires })
  }
  fs.writeFileSync(MARKET, JSON.stringify({ version: 1, listings, reference: {}, history: {}, virtualSales: {}, virtualDay: -1 }, null, 2))
  return { guns: n, money: bots.length * BOT_MONEY }
}

// ------------------------------------------------------------------ bots

function flatText (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flatText).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flatText(n.value && n.value.value)
  if (n.type === 'compound') return flatText(n.value)
  return flatText(n.text) + flatText(n.extra)
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
function cid (item) {
  const data = comp(item, 'custom_data')
  const pbv = data && data.value && data.value.PublicBukkitValues
  const id = pbv && pbv.value['craftov:id']
  return id ? id.value : null
}
const title = item => txt(comp(item, 'custom_name') || comp(item, 'item_name'))
const lore = item => (comp(item, 'lore') || []).map(txt)

function join (name, stats) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
    bot.gone = false
    bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
    // What the server confirmed, with the time it arrived (chat and action bar, see util/Notify).
    const seen = s => {
      if (/购入 /.test(s)) { stats.bought++; stats.last = Date.now() } else if (/^已上架 /.test(s)) { stats.listed++; stats.last = Date.now() } else if (/已下架/.test(s)) { stats.cancelled++; stats.last = Date.now() }
    }
    bot.on('messagestr', m => { if (m.trim()) seen(m.trim()) })
    bot._client.on('action_bar', p => { const s = flatText(p.text).trim(); if (s) seen(s) })
    bot.on('end', () => { bot.gone = true })
    bot.on('error', e => log(name, 'ERROR', e.message))
    const timer = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('kicked', r => { clearTimeout(timer); bot.gone = true; reject(new Error(name + ' kicked: ' + JSON.stringify(r))) })
    bot.once('spawn', async () => { clearTimeout(timer); await sleep(3000); resolve(bot) })
  })
}
function openWith (bot, fn, ms = 3000) {
  return new Promise(resolve => {
    const t = setTimeout(() => { bot.removeListener('windowOpen', on); resolve(null) }, ms)
    const on = w => { clearTimeout(t); resolve(w) }
    bot.once('windowOpen', on)
    fn()
  })
}
const click = async (bot, slot, wait = 250) => { await bot.clickWindow(slot, 0, 0); await sleep(wait) }
const shut = bot => { try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { /* gone */ } }

/** Lists up to `count` tracked guns from the stash at the price the pricing screen starts at. */
async function listSome (bot, count) {
  let w = await openWith(bot, () => bot.chat('/cv stash'))
  if (!w) return
  for (let i = 0; i < 3 && !title(w.slots[48]).includes('上架'); i++) await click(bot, 48)
  for (let k = 0; k < count && !bot.gone; k++) {
    const slots = []
    for (let s = 0; s < 45; s++) if (w.slots[s] && cid(w.slots[s]) && GUNS.includes(cid(w.slots[s]))) slots.push(s)
    if (!slots.length) break
    const pm = await openWith(bot, () => bot.clickWindow(slots[Math.floor(Math.random() * slots.length)], 0, 0))
    if (!pm) break
    // Confirm: the stash opens again on success, the menu just closes on a refusal.
    const back = await openWith(bot, () => bot.clickWindow(26, 0, 0), 1500)
    if (!back) return
    w = back
  }
  shut(bot)
}

/** In the market, opens a random item and buys up to `count` listings of other sellers. */
async function buySome (bot, count) {
  const w = await openWith(bot, () => bot.chat('/cv market'))
  if (!w) return
  const offers = []
  for (let s = 0; s < 45; s++) if (w.slots[s] && lore(w.slots[s]).some(l => l.includes('点击查看在售列表'))) offers.push(s)
  if (offers.length) {
    await click(bot, offers[Math.floor(Math.random() * offers.length)], 300)
    for (let k = 0; k < count && !bot.gone; k++) {
      const mine = '卖家 ' + bot.username
      const buyable = []
      for (let s = 0; s < 45; s++) {
        const l = w.slots[s] ? lore(w.slots[s]) : []
        if (l.some(x => x.includes('点击购买')) && !l.some(x => x === mine)) buyable.push(s)
      }
      if (!buyable.length) break
      await click(bot, buyable[Math.floor(Math.random() * buyable.length)], 300)
    }
  }
  // My listings: take the first one down.
  await click(bot, 53, 300)
  if (w.slots[0] && lore(w.slots[0]).some(l => l.includes('点击下架'))) await click(bot, 0, 300)
  shut(bot)
}

async function trade (bot, state) {
  while (!state.stopping && !bot.gone) {
    try {
      await listSome(bot, 2)
      if (state.stopping || bot.gone) break
      await buySome(bot, 3)
    } catch (e) {
      if (!state.stopping && !bot.gone) log(bot.username, 'step failed:', e.message)
      await sleep(300)
    }
  }
}

// ------------------------------------------------------------------ one round

async function round (i) {
  const r = { tag: Date.now().toString(36) + i }
  const bots = Array.from({ length: BOTS }, (_, k) => `Mio${r.tag}b${k}`.slice(0, 16))
  const sellers = Array.from({ length: OFFLINE }, (_, k) => `Mio${r.tag}s${k}`.slice(0, 16))
  const accounts = [...bots, ...sellers]
  const slow = i >= ROUNDS - SLOW_ROUNDS
  log(`round ${i + 1}/${ROUNDS}: ${BOTS} bots, ${OFFLINE} offline sellers, ${LISTINGS} listings` +
    (slow ? `, every write ${SLOW_MS} ms late` : ''))
  const start0 = seed(r, bots, sellers)
  await start(slow ? SLOW_MS : 0)

  const stats = { bought: 0, listed: 0, cancelled: 0, last: 0 }
  const state = { stopping: false }
  const joined = await Promise.all(bots.map(name => join(name, stats)))
  // market.json is read all along: every read must find a whole file.
  let reads = 0
  let broken = 0
  let sampling = true
  const sampler = (async () => {
    while (sampling) {
      try { JSON.parse(fs.readFileSync(MARKET, 'utf8')); reads++ } catch (e) { broken++; log('market.json unreadable during the run:', e.message) }
      await sleep(100)
    }
  })()
  const loops = joined.map(bot => trade(bot, state))
  const seconds = STOP_MIN + Math.random() * (STOP_MAX - STOP_MIN)
  await sleep(seconds * 1000)
  const stopAt = Date.now()
  log(`stopping after ${seconds.toFixed(1)} s of trading: ${stats.listed} listed, ${stats.bought} bought, ${stats.cancelled} taken down so far`)
  rcon('stop', 5000).catch(() => {})
  if (!await waitGone(120000)) throw new Error('server did not stop')
  state.stopping = true
  sampling = false
  await sampler
  await Promise.race([Promise.all(loops), sleep(5000)])
  for (const bot of joined) { try { bot.quit() } catch { /* gone */ } }
  log(`server stopped ${((Date.now() - stopAt) / 1000).toFixed(1)} s after the stop command`)

  // ---- the files
  const text = fs.readFileSync(CONSOLE, 'utf8')
  let market = null
  try { market = JSON.parse(fs.readFileSync(MARKET, 'utf8')) } catch (e) { log('market.json:', e.message) }
  check(`round ${i + 1}: market.json parses after the stop`, !!market && Array.isArray(market.listings))
  check(`round ${i + 1}: market.json parsed on every read during the run`, broken === 0, `${reads} reads, ${broken} unreadable`)
  const listings = market ? market.listings : []
  const ids = new Set(listings.map(l => l.id))
  check(`round ${i + 1}: no listing twice in market.json`, ids.size === listings.length, `${listings.length} listings, ${ids.size} ids`)
  const saved = [...text.matchAll(/Saved the market: (\d+) listings\./g)].map(m => Number(m[1]))
  check(`round ${i + 1}: market.json holds the market the final save wrote`, saved.length === 1 && saved[0] === listings.length,
    `logged ${saved.join(',') || 'nothing'}, file has ${listings.length}`)
  const temps = [DATA, PLAYERS].flatMap(dir => fs.readdirSync(dir).filter(f => f.endsWith('.tmp')).map(f => path.join(path.basename(dir), f)))
  check(`round ${i + 1}: no temp file left`, temps.length === 0, temps.join(', '))
  const shutdownLines = text.split('\n').filter(l => /Shutdown step|Could not save|Error occurred while disabling Craftov/.test(l))
  check(`round ${i + 1}: the shutdown logged no failed step or save`, shutdownLines.length === 0, shutdownLines.slice(0, 3).join(' | '))
  const late = text.split('\n').filter(l => l.includes('did not finish in 10 seconds'))
  if (slow) {
    check(`round ${i + 1}: the server ran with the write delay`, text.includes('Test hook on: every data file write waits'))
    check(`round ${i + 1}: the shutdown gave up waiting for queued writes (the #62 race)`, late.length > 0,
      late.length ? '' : 'the writer caught up within 10 s: raise SLOW_MS or STOP_MIN')
  } else if (late.length) {
    log('note: the shutdown gave up waiting for queued writes:', late[0])
  }

  const where = {}
  for (const l of listings) uidsIn([l.item], where, 'listing ' + l.id)
  let money = 0
  let expired = 0
  for (const name of accounts) {
    let p
    try { p = JSON.parse(fs.readFileSync(profileFile(name), 'utf8')) } catch (e) { check(`round ${i + 1}: profile of ${name} parses`, false, e.message); continue }
    money += p.money || 0
    uidsIn(p.stash, where, name + ' stash')
    uidsIn(p.mailbox, where, name + ' mailbox')
    if (sellers.includes(name)) expired += (p.mailbox || []).length
    uidsIn(Object.values(p.loadout || {}), where, name + ' loadout')
    uidsIn(p.secure, where, name + ' secure')
    for (const m of p.mail || []) uidsIn(m.items, where, name + ' letter')
  }
  const twice = Object.entries(where).filter(([, at]) => at.length > 1)
  const missing = []
  for (let n = 0; n < start0.guns; n++) if (!where[`mio${r.tag}-${n}`]) missing.push(`mio${r.tag}-${n}`)
  check(`round ${i + 1}: every tracked gun is in exactly one place`, twice.length === 0 && missing.length === 0 && Object.keys(where).length === start0.guns,
    `${start0.guns} guns; ${twice.length} twice${twice.length ? ' (' + twice.slice(0, 3).map(([u, at]) => u + ': ' + at.join(' + ')).join('; ') + ')' : ''}; ` +
    `${missing.length} missing${missing.length ? ' (' + missing.slice(0, 5).join(', ') + ')' : ''}`)
  check(`round ${i + 1}: the accounts hold all their money`, money === start0.money, `${money} of ${start0.money}`)
  const returned = listings.length - LISTINGS
  log(`round ${i + 1}: ${listings.length} listings left (${returned >= 0 ? '+' : ''}${returned} against the seeded ${LISTINGS}); ` +
    `${stats.listed} listed, ${stats.bought} bought, ${stats.cancelled} taken down; ${expired} expired back to offline mailboxes`)
  check(`round ${i + 1}: expired listings went back to offline sellers`, expired > 0, expired)
  check(`round ${i + 1}: enough trading (>= ${MIN_TRADES} purchases and listings)`, stats.bought >= MIN_TRADES && stats.listed >= MIN_TRADES,
    `${stats.bought} bought, ${stats.listed} listed`)
  const lastGap = (stopAt - stats.last) / 1000
  check(`round ${i + 1}: trades were still being confirmed when the stop came`, stats.last > 0 && lastGap < 2,
    `last one ${Math.abs(lastGap).toFixed(2)} s ${lastGap >= 0 ? 'before' : 'after'} the stop command`)
  if (!KEEP) for (const name of accounts) fs.rmSync(profileFile(name), { force: true })
}

// ------------------------------------------------------------------ main

async function main () {
  const wasRunning = !!serverPid()
  await stop()
  const configBefore = fs.readFileSync(CONFIG, 'utf8')
  const marketBefore = fs.existsSync(MARKET) ? fs.readFileSync(MARKET) : null
  try {
    fs.mkdirSync(PLAYERS, { recursive: true })
    for (const [k, v] of Object.entries({ 'listing-fee': 0, 'sale-tax': 0, 'virtual-buyers': false, 'min-buy-level': 1, 'max-listings': 40, 'block-same-ip': false, 'daily-player-income-cap': 0 })) {
      setConfig('market', k, v)
    }
    for (let i = 0; i < ROUNDS; i++) {
      await round(i)
    }
  } finally {
    await stop().catch(e => log('stop:', e.message))
    fs.writeFileSync(CONFIG, configBefore)
    if (marketBefore) fs.writeFileSync(MARKET, marketBefore)
    else fs.rmSync(MARKET, { force: true })
    log('config.yml and market.json put back')
    if (wasRunning) await start().catch(e => log('start:', e.message))
  }
}

main()
  .catch(e => { log('ERROR', e.stack); failures.push('error: ' + e.message) })
  .finally(async () => {
    log(failures.length ? `FAILED: ${failures.join('; ')}` : 'ALL PASSED')
    await sleep(500)
    process.exit(failures.length ? 1 : 0)
  })
