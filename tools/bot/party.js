// Parties around a teammate who drops out, and raids voided for one player (issue #38), with
// headless bots.
//
//   node party.js                    every scenario
//   node party.js dropout evacuate   only these (dropout kick queue evacuate lobby)
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bots can queue for the proving ground. Ports come from PORT
// (25565), RCON_PORT (25575) and RCON_PASSWORD (craftov-local); profiles and the log are read from
// SERVER_DIR (default ../../server/run). RECONNECT must be the server's raid.reconnect-seconds:
// 10 keeps 'dropout' short (the default 120 works, slowly). match.wait-seconds is WAIT (5).
// Each scenario prints PASS or FAIL with what was seen; the exit code is the number of failures.
//
//   dropout   B leads, A joins; they land in a trio raid and B quits there, away from any fight,
//             and never comes back. When the reconnect window ends A is told B left the party and
//             leads what is left (it used to keep the offline B, so A could neither queue, "only
//             the leader can start matchmaking", nor kick B). A goes to the lobby (/cv lobby, op)
//             and is in no party: it queues solo.
//   kick      A leads, B joins; they land and B quits. A is back in the lobby inside B's reconnect
//             window: /party lists B as offline by name, queueing names B, and /party kick B
//             removes B.
//   queue     C queues solo, then accepts A's invite: "已取消匹配（加入了小队）", and after
//             match.wait-seconds C is still in the lobby (it used to be sent in alone).
//   evacuate  E (insured, auto insurance on) lands solo, is given a gold bar, throws its pistol on
//             the ground and is moved to the lobby world over RCON. Void for E: chat says so, the
//             loadout is the one it went in with but for the pistol (still in the raid), no gold
//             bar, and the premium is back. Once the raid ends (/cv end) no insurance claim is paid
//             for E's pistol.
//   lobby     L (insured) lands solo, is given a gold bar and uses /cv lobby (op): the same void,
//             with every piece it went in with back.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const LOG = path.join(SERVER_DIR, 'logs/latest.log')
const RECONNECT = Number(process.env.RECONNECT || 120)
const WAIT = Number(process.env.WAIT || 5)

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const live = []

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

function plain (n) {
  if (!n || typeof n !== 'object') return ''
  if (n.type === 'string') return n.value
  if (n.type === 'list') return plain(n.value)
  if (n.type === 'compound') {
    if (Array.isArray(n.value)) return n.value.map(v => plain({ type: 'compound', value: v })).join('')
    return plain(n.value.text) + plain(n.value.extra)
  }
  return ''
}

const profileFile = name => path.join(SERVER_DIR, 'plugins/Craftov/data/players', offlineUuid(name) + '.json')

/** Written before the first join: auto insurance on, the starter kit still to come. */
function seed (name) {
  fs.mkdirSync(path.dirname(profileFile(name)), { recursive: true })
  fs.writeFileSync(profileFile(name), JSON.stringify({ uuid: offlineUuid(name), name, autoInsure: true }, null, 2))
}

function profile (name) {
  try { return JSON.parse(fs.readFileSync(profileFile(name), 'utf8')) } catch (e) { return null }
}

/** A fresh player with a random name (a reused one would rejoin an old raid). */
function make (insured) {
  const name = `p${run}${(serial++).toString().padStart(2, '0')}`
  if (insured) seed(name)
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot._client.on('action_bar', p => bot.chatLog.push('[bar] ' + (typeof p.text === 'string' ? p.text : plain(p.text))))
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  live.push(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function leave (bot) {
  const i = live.indexOf(bot)
  if (i >= 0) live.splice(i, 1)
  bot.quit('disconnect.quitting')
  await sleep(300)
}

async function quitAll () {
  for (const bot of live.splice(0)) { try { bot.quit('disconnect.quitting') } catch (e) {} }
  await sleep(500)
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(100) }
  return false
}

const inLobby = bot => bot.worldName === 'craftov_lobby'
const landed = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const said = (bot, re, from = 0) => bot.chatLog.slice(from).some(l => re.test(l))
const heard = (bot, re, from, ms) => waitFor(() => said(bot, re, from), ms)

/** What /party prints now. */
async function partyLine (bot) {
  const from = bot.chatLog.length
  bot.chat('/party')
  await heard(bot, /小队 \(|不在小队中/, from, 3000)
  return bot.chatLog.slice(from).find(l => /小队 \(|不在小队中/.test(l)) || ''
}

/** Leader invites the others, who accept: a party of all of them. */
async function party (leader, ...members) {
  for (const m of members) {
    leader.chat(`/party invite ${m.username}`)
    await sleep(600)
    m.chat('/party accept')
    await sleep(600)
  }
}

/** The server log from here on: call the returned function for what was written since. */
function logFrom () {
  const start = fs.existsSync(LOG) ? fs.statSync(LOG).size : 0
  return () => {
    if (!fs.existsSync(LOG)) return ''
    const fd = fs.openSync(LOG, 'r')
    const size = fs.statSync(LOG).size
    const buf = Buffer.alloc(Math.max(0, size - start))
    fs.readSync(fd, buf, 0, buf.length, start)
    fs.closeSync(fd)
    return buf.toString('utf8')
  }
}
const LOG_PROBLEMS = /Cannot measure distance|Could not void|Exception|at net\.infnetwork\.snowball\.craftov/
const logProblems = text => text.split('\n').filter(l => LOG_PROBLEMS.test(l)).slice(0, 3)

/** Everyone queues (the first as leader of a party, or each solo) and lands, past the landing protection. */
async function land (bots, mode) {
  if (mode === 'TRIO') bots[0].chat('/cv queue proving NORMAL TRIO')
  else for (const b of bots) b.chat('/cv queue proving NORMAL SOLO')
  if (!await waitFor(() => bots.every(landed), 40000)) throw new Error('not landed: ' + bots.map(b => `${b.username} ${b.worldName} ${b.game.gameMode}`).join(', '))
  await sleep(3500)
}

/** A loadout as comparable entries: slot, item, amount, instance id, rounds. */
function entries (loadout) {
  return Object.entries(loadout || {}).map(([slot, r]) => `${slot}:${r.id}x${r.amount || 1}${r.uid ? '#' + r.uid.slice(0, 8) : ''}${r.ammo != null ? '/' + r.ammo : ''}`).sort()
}
const ids = loadout => Object.values(loadout || {}).map(r => r.id)

/** The hotbar slot (0-8) holding a Craftov item, or -1. */
function hotbarItem (bot, id) {
  for (let i = 0; i < 9; i++) {
    const item = bot.inventory.slots[36 + i]
    const data = item && (item.components || []).find(c => c.type === 'custom_data')
    const pdc = data && data.data.value.PublicBukkitValues && data.data.value.PublicBukkitValues.value
    if (pdc && pdc['craftov:id'] && pdc['craftov:id'].value === id) return item
  }
  return null
}

/** Checks a void for {@code bot}, given its profile in the raid: back to {@code want}, premium back, no gold bar. */
function checkVoid (bot, during, after, want, problems) {
  const d = during && during.deployment
  if (!d) { problems.push('no deployment record in the raid'); return }
  if (!(d.premium > 0)) problems.push(`no premium charged (${d.premium})`)
  if (!after) { problems.push('no profile after'); return }
  if (after.deployment || after.activeRaid) problems.push('still in a raid on record')
  if (after.money !== during.money + d.premium + d.entryFee) problems.push(`balance ${after.money}, want ${during.money} + ${d.premium} + ${d.entryFee}`)
  if (ids(after.loadout).includes('gold_bar')) problems.push('the gold bar found in the raid came home')
  const got = entries(after.loadout).join(' ')
  const wanted = entries(want).join(' ')
  if (got !== wanted) problems.push(`loadout ${got}, want ${wanted}`)
  if (!said(bot, /本次行动作废/)) problems.push('no void line in chat')
  if (d.premium > 0 && !said(bot, /保险费 ◈[\d,]+ 已退还/)) problems.push('chat does not say the premium is back')
  if ((after.mail || []).some(m => /补偿/.test(m.title || ''))) problems.push('a compensation letter')
}

const scenarios = {
  async dropout () {
    const log = logFrom()
    const A = await make()
    const B = await make()
    await sleep(2500)
    await rcon(`op ${A.username}`)
    await party(B, A)
    await land([B, A], 'TRIO')
    const mark = A.chatLog.length
    await leave(B)
    const problems = []
    if (!await heard(A, new RegExp(`${B.username} 离开了小队`), mark, (RECONNECT + 8) * 1000)) problems.push('A was never told B left the party')
    if (!said(A, new RegExp(`新的队长：${A.username}`), mark)) problems.push('A did not become the leader')
    A.chat('/cv lobby')
    if (!await waitFor(() => inLobby(A), 10000)) problems.push(`A not in the lobby: ${A.worldName}`)
    await sleep(1000)
    const line = await partyLine(A)
    if (!/不在小队中/.test(line)) problems.push(`/party: ${line}`)
    const from = A.chatLog.length
    A.chat('/cv queue proving NORMAL SOLO')
    if (!await heard(A, /开始匹配/, from, 3000)) problems.push('A cannot queue: ' + A.chatLog.slice(from).join(' / '))
    A.chat('/cv leave')
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || `B (leader) gone after the window, A leads, then alone and queues | ${line}`]
  },

  async kick () {
    const log = logFrom()
    const A = await make()
    const B = await make()
    await sleep(2500)
    await rcon(`op ${A.username}`)
    await party(A, B)
    await land([A, B], 'TRIO')
    await leave(B)
    await sleep(1000)
    A.chat('/cv lobby')
    const problems = []
    if (!await waitFor(() => inLobby(A), 10000)) problems.push(`A not in the lobby: ${A.worldName}`)
    await sleep(800)
    const line = await partyLine(A)
    if (!line.includes(`${B.username}(离线)`)) problems.push(`/party: ${line}`)
    let from = A.chatLog.length
    A.chat('/cv queue proving NORMAL TRIO')
    await heard(A, /小队成员|开始匹配/, from, 3000)
    const why = A.chatLog.slice(from).find(l => /小队成员|开始匹配/.test(l)) || ''
    if (!why.includes(`小队成员 ${B.username} 不在线`) || !why.includes(`/cv party kick ${B.username}`)) problems.push(`queue: ${why}`)
    from = A.chatLog.length
    A.chat(`/party kick ${B.username}`)
    if (!await heard(A, new RegExp(`${B.username} 离开了小队`), from, 3000)) problems.push('kick: ' + A.chatLog.slice(from).join(' / '))
    const after = await partyLine(A)
    if (!/不在小队中/.test(after)) problems.push(`/party after the kick: ${after}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || `${line} | ${why} | kicked`]
  },

  async queue () {
    const A = await make()
    const C = await make()
    await sleep(2500)
    let from = C.chatLog.length
    C.chat('/cv queue proving NORMAL SOLO')
    const problems = []
    if (!await heard(C, /开始匹配/, from, 3000)) problems.push('C did not queue')
    A.chat(`/party invite ${C.username}`)
    await sleep(600)
    from = C.chatLog.length
    C.chat('/party accept')
    if (!await heard(C, /已取消匹配（加入了小队）/, from, 3000)) problems.push('accept: ' + C.chatLog.slice(from).join(' / '))
    await sleep((WAIT + 4) * 1000)
    if (!inLobby(C)) problems.push(`C went to ${C.worldName}`)
    const line = await partyLine(C)
    if (!line.includes('2/3')) problems.push(`/party: ${line}`)
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || `queue cancelled on joining, still in the lobby after ${WAIT + 4} s | ${line}`]
  },

  async evacuate () {
    const log = logFrom()
    const E = await make(true)
    await sleep(2500)
    await land([E], 'SOLO')
    const raidWorld = E.worldName
    await sleep(1500)
    const during = profile(E.username)
    await rcon(`cv give ${E.username} gold_bar 1`)
    await sleep(800)
    const pistol = hotbarItem(E, 'p18')
    const problems = []
    if (!pistol) problems.push('no pistol in the hotbar')
    else await E.tossStack(pistol).catch(e => problems.push('toss: ' + e.message))
    await sleep(1500)
    const mark = E.chatLog.length
    await rcon(`execute in minecraft:craftov_lobby run tp ${E.username} 0.5 65 12.5`)
    if (!await waitFor(() => inLobby(E) && said(E, /本次行动作废/, mark), 10000)) problems.push(`E: ${E.worldName}, ${E.chatLog.slice(mark).join(' / ')}`)
    await sleep(1500)
    const after = profile(E.username)
    const want = Object.fromEntries(Object.entries((during && during.deployment && during.deployment.loadout) || {}).filter(([, r]) => r.id !== 'p18'))
    checkVoid(E, during, after, want, problems)
    // The pistol is still in the raid; the raid closes with no claim for it.
    const lying = await rcon(`execute in minecraft:${raidWorld} if entity @e[type=minecraft:item]`)
    if (!/count: [1-9]/i.test(lying) && !/Test passed/i.test(lying)) problems.push(`no item on the raid's ground: ${lying}`)
    await rcon('cv end')
    await waitFor(() => !fs.existsSync(path.join(SERVER_DIR, raidWorld)), 30000)
    await sleep(1500)
    const claims = log().split('\n').filter(l => l.includes('Insurance claim') && l.includes(offlineUuid(E.username)))
    if (claims.length) problems.push('claim paid: ' + claims[0])
    const later = profile(E.username)
    if (later && ((later.claims || []).length || (later.mailbox || []).some(r => r.id === 'p18'))) problems.push('the pistol came back through the insurance')
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || `void on leaving the raid world: loadout as deployed but the dropped pistol, premium ◈${during.deployment.premium} back, no gold bar, no claim | ${E.chatLog.slice(mark).find(l => /作废/.test(l))}`]
  },

  async lobby () {
    const log = logFrom()
    const L = await make(true)
    await sleep(2500)
    await rcon(`op ${L.username}`)
    await land([L], 'SOLO')
    await sleep(1500)
    const during = profile(L.username)
    await rcon(`cv give ${L.username} gold_bar 1`)
    await sleep(800)
    const mark = L.chatLog.length
    L.chat('/cv lobby')
    const problems = []
    if (!await waitFor(() => inLobby(L) && said(L, /本次行动作废/, mark), 10000)) problems.push(`L: ${L.worldName}, ${L.chatLog.slice(mark).join(' / ')}`)
    await sleep(1500)
    checkVoid(L, during, profile(L.username), during && during.deployment && during.deployment.loadout, problems)
    await rcon('cv end')
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || `/cv lobby voids: loadout as deployed, premium back, no gold bar | ${L.chatLog.slice(mark).find(l => /作废/.test(l))}`]
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
    await quitAll()
    if (result[0] === 'FAIL') failures++
    console.log(`${result[0]} ${name}: ${result[1]}`)
    await sleep(2000)
  }
  process.exit(failures)
})()
