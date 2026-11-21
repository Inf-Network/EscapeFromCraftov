// Disconnects between "match found" and landing, with headless bots.
//
//   node deploy.js                  every scenario
//   node deploy.js loading flight   only these
//
// Needs a local test server (server/setup.sh --local) with RCON on. Ports come from
// MC_PORT (25565), RCON_PORT (25575) and RCON_PASSWORD (craftov-local). To keep the
// run short and the scenarios apart, in plugins/Craftov/config.yml:
//   match: { wait-seconds: 2, join-window-seconds: -1 }   (no backfill into older raids)
//   raid: { reconnect-seconds: 30 }                        and RECONNECT=30 here
//   tiers.NORMAL.entry-fee: 1000                           (so a charged fee shows)
// Profiles are read from PLAYERS (default ../../server/run/plugins/Craftov/data/players).
// Each scenario prints PASS, FAIL or SKIP with what was seen; the exit code is the
// number of failures.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const MC_PORT = Number(process.env.MC_PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const RECONNECT = Number(process.env.RECONNECT || 120)  // raid.reconnect-seconds on the server
const MAP = process.env.MAP || 'proving'
const PLAYERS = process.env.PLAYERS || path.join(__dirname, '../../server/run/plugins/Craftov/data/players')
const SECURE_ITEM = { id: 'bandage', amount: 1 }

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
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

const profileFile = name => path.join(PLAYERS, offlineUuid(name) + '.json')
const readProfile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))

/** Waits until the profile file exists and has not changed for a moment (saves are async). */
async function settledProfile (name, ms = 8000) {
  const file = profileFile(name)
  const end = Date.now() + ms
  let last = -1
  while (Date.now() < end) {
    if (fs.existsSync(file)) {
      const m = fs.statSync(file).mtimeMs
      if (m === last) return readProfile(name)
      last = m
    }
    await sleep(600)
  }
  return fs.existsSync(file) ? readProfile(name) : null
}

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: MC_PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.titles = []
  bot.worldName = ''
  // mineflayer's game.dimension is only the dimension type; the world's name comes with login and respawn.
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot.on('title', (t, type) => bot.titles.push({ at: Date.now(), type, text: strip(JSON.stringify(t)) }))
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(100) }
  return false
}

const inLobby = bot => bot.worldName === 'craftov_lobby'
const inRaidWorld = bot => bot.worldName.startsWith('craftov_raid_')
const quit = async bot => { bot.end('disconnect.quitting'); await sleep(300) }
const queue = (bot, mode = 'SOLO') => bot.chat(`/cv queue ${MAP} NORMAL ${mode}`)

/** A fresh player: joins once for the starter kit, then gets a secure-container item and auto insurance. */
async function prime () {
  const name = `d${run}${(serial++).toString().padStart(2, '0')}`
  const bot = await make(name)
  await sleep(2500)
  await quit(bot)
  const profile = await settledProfile(name)
  profile.secure = [SECURE_ITEM]
  profile.autoInsure = true
  fs.writeFileSync(profileFile(name), JSON.stringify(profile, null, 2))
  return { name, before: profile }
}

/** Craftov item ids the bot carries (sorted, with amounts) and how many carry an insurance mark. */
function inventory (bot) {
  const ids = []
  let insured = 0
  for (const item of bot.inventory.slots) {
    const data = item && (item.components || []).find(c => c.type === 'custom_data')
    const pdc = data && data.data.value.PublicBukkitValues && data.data.value.PublicBukkitValues.value
    if (!pdc || !pdc['craftov:id']) continue
    ids.push(pdc['craftov:id'].value + 'x' + item.count)
    if (pdc['craftov:insured']) insured++
  }
  return { ids: ids.sort(), insured }
}

async function position (name) {
  const out = await rcon(`data get entity ${name} Pos`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}

const stat = (p, key) => (p.stats && p.stats[key]) || 0
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const sortedLoadout = p => Object.keys(p.loadout).sort().map(k => [k, p.loadout[k].id, p.loadout[k].amount])

/** Checks that nothing in the profile moved: the raid never touched this player. */
function untouched (before, after) {
  const problems = []
  if (!same(sortedLoadout(before), sortedLoadout(after))) problems.push(`loadout ${JSON.stringify(sortedLoadout(after))}`)
  if (!same(before.secure, after.secure)) problems.push(`secure ${JSON.stringify(after.secure)}`)
  if (stat(before, 'deaths') !== stat(after, 'deaths')) problems.push(`deaths ${stat(after, 'deaths')}`)
  if (stat(before, 'raids') !== stat(after, 'raids')) problems.push(`raids ${stat(after, 'raids')}`)
  if (before.money !== after.money) problems.push(`money ${before.money} -> ${after.money}`)
  if ((after.reports || []).length !== (before.reports || []).length) problems.push(`reports ${JSON.stringify(after.reports.map(r => r.outcome))}`)
  if (after.activeRaid) problems.push(`activeRaid ${after.activeRaid}`)
  return problems
}

/** Queues and quits the instant the match-found title arrives. Returns how long after the title. */
async function quitOnMatch (bot) {
  let quitAt = 0
  bot.on('title', t => {
    if (!quitAt && strip(JSON.stringify(t)).includes('匹配成功')) {
      quitAt = Date.now()
      bot.end('disconnect.quitting')
    }
  })
  queue(bot)
  await waitFor(() => quitAt > 0, 30000)
  return quitAt
}

const scenarios = {
  // Quit right on "match found": the player is still in the lobby and keeps everything.
  async loading () {
    const { name, before } = await prime()
    const bot = await make(name)
    await sleep(2500)
    if (!await quitOnMatch(bot)) return ['FAIL', 'no match-found title']
    await sleep(1500)
    const after = await settledProfile(name)
    if (stat(after, 'raids') > stat(before, 'raids') && (after.reports || []).length === (before.reports || []).length) {
      return ['SKIP', 'the quit reached the server after deployment (map loaded within a tick); try MAP=rustport']
    }
    const problems = untouched(before, after)
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || 'loadout, secure, money, deaths and reports unchanged']
  },

  // Quit on "match found" and come straight back: stays in the lobby, nothing charged.
  async rejoin () {
    const { name, before } = await prime()
    let bot = await make(name)
    await sleep(2500)
    const quitAt = await quitOnMatch(bot)
    if (!quitAt) return ['FAIL', 'no match-found title']
    await sleep(200)
    bot = await make(name)
    const back = (Date.now() - quitAt) / 1000
    // Whether the raid was still loading when the bot came back (a bigger MAP loads longer).
    const loading = /LOADING/.test(await rcon('cv raids'))
    // Long enough for the entry camera to have started if the bot had been deployed.
    await sleep(10000)
    const where = `${bot.worldName} ${bot.game.gameMode}`
    await quit(bot)
    const after = await settledProfile(name)
    const problems = untouched(before, after)
    if (!inLobby(bot) || bot.game.gameMode !== 'adventure') problems.push(`ended up in ${where}`)
    return [problems.length ? 'FAIL' : 'PASS', `back after ${back.toFixed(1)}s ${loading ? 'while the raid was still loading' : 'after the raid had loaded'}, ${where}; ${problems.join('; ') || 'nothing charged'}`]
  },

  // A trio: one member drops out on "match found", the other deploys normally.
  async squad () {
    const a = await prime()
    const b = await prime()
    const A = await make(a.name)
    const B = await make(b.name)
    await sleep(2500)
    A.chat(`/cv party invite ${b.name}`); await sleep(600)
    B.chat('/cv party accept'); await sleep(600)
    let quitAt = 0
    B.on('title', t => {
      if (!quitAt && strip(JSON.stringify(t)).includes('匹配成功')) { quitAt = Date.now(); B.end('disconnect.quitting') }
    })
    A.chat(`/cv queue ${MAP} NORMAL TRIO`)
    const landed = await waitFor(() => inRaidWorld(A) && A.game.gameMode === 'adventure', 40000)
    await sleep(1500)
    const told = A.chatLog.some(m => m.includes(b.name) && m.includes('部署前断开'))
    await quit(A)
    const afterB = await settledProfile(b.name)
    const problems = untouched(b.before, afterB)
    if (stat(afterB, 'raids') > stat(b.before, 'raids')) return ['SKIP', 'the quit reached the server after deployment']
    if (!landed) problems.push(`leader did not land (${A.worldName} ${A.game.gameMode})`)
    if (!told) problems.push('leader was not told')
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || 'leader landed and was told; the one who left kept everything']
  },

  // Quit during the entry camera and come back: lands on the insertion point with the same gear.
  async flight () {
    const { name } = await prime()
    let bot = await make(name)
    await sleep(2500)
    queue(bot)
    if (!await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'spectator', 40000)) return ['FAIL', 'no entry camera']
    await sleep(1500)
    const carried = inventory(bot)
    await quit(bot)
    await sleep(3000)
    bot = await make(name)
    await sleep(2500)
    const now = inventory(bot)
    const at = await position(name)
    const mode = bot.game.gameMode
    // The camera would have finished by now: the rejoined player must not be moved or reset by it.
    await sleep(8000)
    const later = await position(name)
    const problems = []
    if (!inRaidWorld(bot) || mode !== 'adventure') problems.push(`in ${bot.worldName} ${mode}`)
    if (!same(carried.ids, now.ids)) problems.push(`gear ${carried.ids.join(',')} -> ${now.ids.join(',')}`)
    if (carried.insured !== now.insured || now.insured === 0) problems.push(`insured ${carried.insured} -> ${now.insured}`)
    // Proving ground insertion points stand at y 65, z 95..96.
    if (!at || Math.abs(at[1] - 65) > 1.5 || at[2] < 94 || at[2] > 97.5) problems.push(`landed at ${at}`)
    if (!later || !at || Math.hypot(later[0] - at[0], later[2] - at[2]) > 1) problems.push(`moved to ${later}`)
    if (bot.game.gameMode !== 'adventure') problems.push(`later ${bot.game.gameMode}`)
    await quit(bot)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'back on the insertion point with the same gear'} | at ${at} | ${now.ids.length} items, ${now.insured} insured`]
  },

  // Quit during the entry camera and stay away: a body with the gear at the insertion point,
  // reported missing, insured gear back in the mailbox, melee weapon kept.
  async timeout () {
    const { name, before } = await prime()
    const bot = await make(name)
    await sleep(2500)
    queue(bot)
    if (!await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'spectator', 40000)) return ['FAIL', 'no entry camera']
    await sleep(1500)
    const carried = inventory(bot)
    await quit(bot)
    const selector = `@e[type=minecraft:mannequin,name="☠ ${name} 的遗体",limit=1]`
    await sleep(RECONNECT * 1000)
    let body = ''
    for (let i = 0; i < 20 && !body.includes('Test passed'); i++) {
      await sleep(1000)
      body = await rcon(`execute if entity ${selector}`)
    }
    const bodyAt = body.includes('Test passed') ? await rcon(`data get entity ${selector} Pos`) : '(none)'
    // The raid closes 8 s after its last player is gone and settles insurance then; the profile
    // may only reach the disk with the next periodic save (every 10 s).
    let after = null
    for (let i = 0; i < 30 && !(after && (after.mailbox || []).length); i++) {
      await sleep(1000)
      after = readProfile(name)
    }
    after = await settledProfile(name)
    const problems = []
    if (!body.includes('Test passed')) problems.push('no body')
    const z = bodyAt.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
    if (z && (Math.abs(Number(z[2]) - 65) > 1.5 || Number(z[3]) < 94 || Number(z[3]) > 97.5)) problems.push(`body at ${bodyAt}`)
    const outcome = (after.reports && after.reports[0] && after.reports[0].outcome) || ''
    if (outcome !== '失踪（断线超时）') problems.push(`report ${outcome}`)
    if (stat(after, 'deaths') !== stat(before, 'deaths') + 1) problems.push(`deaths ${stat(after, 'deaths')}`)
    if (!same(after.secure, before.secure)) problems.push(`secure ${JSON.stringify(after.secure)}`)
    const kept = Object.keys(after.loadout)
    if (!same(kept, ['2'])) problems.push(`loadout slots ${kept}`)
    const mailed = (after.mailbox || []).length
    if (carried.insured > 0 && mailed === 0) problems.push('no insurance payout')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'body, report, melee and insurance as expected'} | body ${bodyAt.replace(/^.*data: /, '')} | ${carried.insured} insured, ${mailed} mailed`]
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
    // Let the previous raid wind down before the next scenario queues.
    await sleep(2000)
  }
  process.exit(failures)
})()
