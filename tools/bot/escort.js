// The escort contract's happy path, with two headless bots on the proving ground:
//
//   1. the client (Boss####) posts a ◈20,000 contract from the escort hall: its balance drops by the
//      reward and the profile holds it in escrow;
//   2. the escort (Guard####) accepts it from the list: told so (已接取), and the two are one party;
//   3. the client queues a trio raid: both land in the same raid world;
//   4. the client extracts: the escort is told it was paid (护航成功) and its profile's
//      stats.escort_income grows by the whole reward; the client's escrow is closed and it gets
//      nothing back (its balance change, less the rewards it was told about on the way out, such as
//      an extract contract or a level-up, is 0);
//   5. the escort extracts too, so nothing is left in the raid.
//
//   node escort.js     env: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR (env.js)
//
// Server config: test.enabled true (the bots queue for the proving ground); match.wait-seconds of a
// few seconds keeps it short. Profiles are read from SERVER_DIR, so it runs on the server's machine.
// Prints PASS / FAIL per check; the exit code is the number of failures.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const REWARD = 20000 // the hall's middle button (slot 38)
const EXTRACT = '106.5 65 8.5' // the proving ground's always-open extract
const rcon = cmd => rconRaw(cmd).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const tag = String(Math.floor(1000 + Math.random() * 9000))
const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === '' ? '' : ' | ' + detail}`)
}

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profile = name => { try { return JSON.parse(fs.readFileSync(path.join(env.DATA, 'data', 'players', offlineUuid(name) + '.json'), 'utf8')) } catch { return {} } }

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes). */
function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n.extra)
}

const bots = []
function make (name) {
  const bot = mineflayer.createBot({ host: env.HOST, port: env.PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.msgs = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.msgs.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.msgs.push(s); log(name, 'BAR', s) } })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(250) }
  return false
}
const heard = (bot, re, mark, ms = 5000) => waitFor(() => bot.msgs.slice(mark).some(m => re.test(m)), ms)
function openWith (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), ms)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    fn()
  })
}
async function money (name) {
  const m = (await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}
/** Money the bot was told it was paid since mark: contracts ("+◈…"), level-ups and bonuses ("奖励 ◈…"). */
function announcedSince (bot, mark) {
  let sum = 0
  for (const m of bot.msgs.slice(mark)) {
    const r = m.match(/\[每日任务完成\].*\+◈([\d,]+)/) || m.match(/奖励 ◈([\d,]+)/)
    if (r) sum += Number(r[1].replace(/,/g, ''))
  }
  return sum
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
async function extract (bot) {
  await rcon(`execute as ${bot.username} at @s run tp @s ${EXTRACT}`)
  return waitFor(() => bot.worldName === 'craftov_lobby', 30000)
}

;(async () => {
  const c = await make('Boss' + tag)
  const e = await make('Guard' + tag)

  // 1. The client posts.
  const before = await money(c.username)
  await openWith(c, () => c.chat('/cv'))
  const hall = await openWith(c, () => c.clickWindow(16, 0, 0))
  if (hall) {
    await c.clickWindow(38, 0, 0)
    await sleep(800)
    c.closeWindow(hall)
  }
  const posted = await money(c.username)
  check('the client posts a contract: the reward leaves its balance', before - posted === REWARD, `${before} -> ${posted}`)
  check('the reward is held in escrow', await waitFor(() => profile(c.username).escortEscrow === REWARD, 12000),
    String(profile(c.username).escortEscrow))

  // 2. The escort accepts.
  await openWith(e, () => e.chat('/cv'))
  const list = await openWith(e, () => e.clickWindow(16, 0, 0))
  let mark = e.msgs.length
  const slot = list ? list.slots.findIndex((s, i) => i < 27 && s && JSON.stringify(s.components || '').includes(c.username)) : -1
  if (slot >= 0) await e.clickWindow(slot, 0, 0)
  check('the escort accepts the contract', await heard(e, /已接取/, mark), slot < 0 ? 'contract not listed' : '')
  if (list) e.closeWindow(list)
  await sleep(500)
  mark = c.msgs.length
  c.chat('/cv party')
  check('the escort joins the client\'s party', await heard(c, new RegExp(`小队 \\(2/3\\).*${e.username}`), mark, 3000))

  // 3. A trio raid together.
  const cMark = c.msgs.length
  const eIncome = (profile(e.username).stats || {}).escort_income || 0
  c.chat('/cv queue proving NORMAL TRIO')
  const landed = await waitFor(() => inRaid(c) && inRaid(e), 120000)
  check('both land in the same raid', landed && c.worldName === e.worldName, `${c.worldName} / ${e.worldName}`)
  if (!landed) {
    for (const bot of bots) bot.quit()
    await sleep(500)
    process.exit(failures.length + 1)
  }
  await sleep(3500) // insertion protection

  // 4. The client extracts: the escort is paid, the client gets nothing back.
  mark = e.msgs.length
  check('the client extracts', await extract(c))
  check('the escort is told it was paid', await heard(e, /护航成功/, mark, 8000))
  await sleep(1500)
  const income = ((profile(e.username).stats || {}).escort_income || 0) - eIncome
  check('the escort\'s escort income grows by the whole reward', income === REWARD, String(income))
  const rewards = announcedSince(c, cMark)
  const after = await money(c.username)
  check('the client gets nothing back', after - posted - rewards === 0, `${posted} -> ${after}, of which rewards ${rewards}`)
  check('the client\'s escrow is closed', !profile(c.username).escortEscrow, String(profile(c.username).escortEscrow))

  // 5. Out with the escort as well.
  check('the escort extracts', await extract(e))
  for (const bot of bots) bot.quit()
  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  await sleep(500)
  process.exit(failures.length)
})().catch(err => {
  log('ERROR', err.stack || err.message)
  for (const bot of bots) bot.quit()
  setTimeout(() => process.exit(failures.length + 2), 500)
})
