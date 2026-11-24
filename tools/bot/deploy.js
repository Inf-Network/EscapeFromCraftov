// Disconnects between "match found" and landing, with headless bots.
//
//   node deploy.js                  every scenario
//   node deploy.js loading flight   only these (loading rejoin squad escort flight late timeout)
//
// Needs a local test server (server/setup.sh --local) with RCON on. Ports come from
// MC_PORT (25565), RCON_PORT (25575) and RCON_PASSWORD (craftov-local). To keep the
// run short and the scenarios apart, in plugins/Craftov/config.yml:
//   match: { wait-seconds: 2 }
//   raid: { reconnect-seconds: 30 }          and RECONNECT=30 here
//   tiers.NORMAL.entry-fee: 1000             (so a charged fee shows)
// The 'timeout' scenario also sets insurance.loss-chance and insurance.return-minutes to 0 for its
// run (insurance.js, put back afterwards), since it checks that every insured piece comes back.
// Profiles are read from PLAYERS (default ../../server/run/plugins/Craftov/data/players).
// Each scenario prints PASS, FAIL or SKIP with what was seen; the exit code is the
// number of failures.
//
// Server config (tools/bot/suite.js sets it for its run): test.enabled true (the bots queue for
// the proving ground, a test map), match.wait-seconds 2, raid.reconnect-seconds 30 (with
// RECONNECT=30), tiers.NORMAL.entry-fee 1000.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const pinInsurance = require('./insurance')
const env = require('./env')

const MC_PORT = env.PORT
const RCON = env.RCON
const RECONNECT = Number(process.env.RECONNECT || 120)  // raid.reconnect-seconds on the server
const MAP = process.env.MAP || 'proving'
const PLAYERS = process.env.PLAYERS || path.join(env.DATA, 'data', 'players')
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

/** The profile with every carry cell filled: big rig and backpack, each free cell a bandage. */
function fullLoad (profile) {
  const loadout = { ...profile.loadout }
  for (let slot = 0; slot < 36; slot++) {
    if (slot === 2 || slot === 8) continue
    if (!loadout[slot]) loadout[slot] = { id: 'bandage', amount: 1 }
  }
  loadout['36'] = { id: 'pack_3', amount: 1 }
  loadout['37'] = { id: 'rig_3', amount: 1 }
  return { ...profile, loadout }
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

  // Quit early in the entry camera and come straight back: back on the camera, then lands on the
  // insertion point when the flight ends, with the landing title and the same (insured) gear.
  async flight () {
    const { name } = await prime()
    let bot = await make(name)
    await sleep(2500)
    queue(bot)
    if (!await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'spectator', 40000)) return ['FAIL', 'no entry camera']
    const cameraAt = Date.now()
    await sleep(800)
    const carried = inventory(bot)
    await quit(bot)
    bot = await make(name)
    const back = (Date.now() - cameraAt) / 1000
    const onCamera = await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'spectator', 3000)
    const landed = await waitFor(() => bot.game.gameMode === 'adventure', 15000)
    const landedAt = (Date.now() - cameraAt) / 1000
    await sleep(1500)
    const now = inventory(bot)
    const at = await position(name)
    const titled = bot.titles.some(t => t.text.includes('行动开始'))
    const problems = []
    if (!onCamera) problems.push(`back in ${bot.worldName} ${bot.game.gameMode}, not on the camera`)
    if (!landed) problems.push('never landed')
    // The camera runs about 6.8 s; landing earlier means a head start on the squad.
    if (landed && landedAt < 6.0) problems.push(`landed ${landedAt.toFixed(1)}s after the camera started`)
    if (!titled) problems.push('no landing title')
    if (!same(carried.ids, now.ids)) problems.push(`gear ${carried.ids.join(',')} -> ${now.ids.join(',')}`)
    if (carried.insured !== now.insured || now.insured === 0) problems.push(`insured ${carried.insured} -> ${now.insured}`)
    // Proving ground insertion points stand at y 65, z 95..96.
    if (!at || Math.abs(at[1] - 65) > 1.5 || at[2] < 94 || at[2] > 97.5) problems.push(`landed at ${at}`)
    await quit(bot)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'back on the camera, landed with the squad'} | back after ${back.toFixed(1)}s, landed after ${landedAt.toFixed(1)}s at ${at} | ${now.ids.length} items, ${now.insured} insured`]
  },

  // Quit during the entry camera and come back after it ended: lands on the insertion point at
  // once with the same gear, and nothing moves the player afterwards.
  async late () {
    const { name } = await prime()
    let bot = await make(name)
    await sleep(2500)
    queue(bot)
    if (!await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'spectator', 40000)) return ['FAIL', 'no entry camera']
    await sleep(800)
    const carried = inventory(bot)
    await quit(bot)
    await sleep(8000)
    bot = await make(name)
    await sleep(2500)
    const now = inventory(bot)
    const at = await position(name)
    const mode = bot.game.gameMode
    await sleep(5000)
    const later = await position(name)
    const problems = []
    if (!inRaidWorld(bot) || mode !== 'adventure') problems.push(`in ${bot.worldName} ${mode}`)
    if (!same(carried.ids, now.ids)) problems.push(`gear ${carried.ids.join(',')} -> ${now.ids.join(',')}`)
    if (carried.insured !== now.insured || now.insured === 0) problems.push(`insured ${carried.insured} -> ${now.insured}`)
    if (!at || Math.abs(at[1] - 65) > 1.5 || at[2] < 94 || at[2] > 97.5) problems.push(`landed at ${at}`)
    if (!later || !at || Math.hypot(later[0] - at[0], later[2] - at[2]) > 1) problems.push(`moved to ${later}`)
    if (bot.game.gameMode !== 'adventure') problems.push(`later ${bot.game.gameMode}`)
    await quit(bot)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'back on the insertion point with the same gear'} | at ${at} | ${now.ids.length} items, ${now.insured} insured`]
  },

  // An escort client drops out on "match found" while their escort deploys: the contract is
  // cancelled, the reward comes back, the escort is told, and the client can post again.
  async escort () {
    const client = await prime()
    const guard = await prime()
    const C = await make(client.name)
    const G = await make(guard.name)
    await sleep(2500)
    const openWith = (bot, fn) => new Promise(resolve => {
      const t = setTimeout(() => resolve(null), 4000)
      bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
      fn()
    })
    let menu = await openWith(C, () => C.chat('/cv'))
    if (!menu) return ['FAIL', 'client: no main menu']
    menu = await openWith(C, () => C.clickWindow(16, 0, 0))
    if (!menu) return ['FAIL', 'client: no escort hall']
    await C.clickWindow(38, 0, 0); await sleep(600)
    C.closeWindow(menu)
    menu = await openWith(G, () => G.chat('/cv'))
    menu = menu && await openWith(G, () => G.clickWindow(16, 0, 0))
    if (!menu) return ['FAIL', 'escort: no escort hall']
    await G.clickWindow(0, 0, 0); await sleep(800)
    G.closeWindow(menu)
    if (!C.chatLog.some(m => m.includes('接取了你的护航委托'))) return ['FAIL', `contract not accepted: ${C.chatLog.slice(-4).join(' | ')}`]
    let quitAt = 0
    C.on('title', t => {
      if (!quitAt && strip(JSON.stringify(t)).includes('匹配成功')) { quitAt = Date.now(); C.end('disconnect.quitting') }
    })
    C.chat(`/cv queue ${MAP} NORMAL TRIO`)
    const landed = await waitFor(() => inRaidWorld(G) && G.game.gameMode === 'adventure', 40000)
    await sleep(1500)
    const told = G.chatLog.some(m => m.includes(client.name) && m.includes('护航委托已取消'))
    await quit(G)
    const afterC = await settledProfile(client.name)
    if (stat(afterC, 'raids') > stat(client.before, 'raids')) return ['SKIP', 'the quit reached the server after deployment']
    const problems = untouched(client.before, afterC)
    if (!landed) problems.push(`escort did not land (${G.worldName} ${G.game.gameMode})`)
    if (!told) problems.push('escort was not told')
    // The refund releases the escrow too: a reward still recorded as held would be paid back a
    // second time on the next join, as if a crash had dropped the contract.
    if (afterC.escortEscrow) problems.push(`escrow still held: ${afterC.escortEscrow}`)
    // Back in the lobby the client can post a new contract: the old one is gone, not stuck.
    const C2 = await make(client.name)
    await sleep(2500)
    if (C2.chatLog.some(m => m.includes('护航委托因服务器中断已撤销'))) problems.push('refunded again on rejoin')
    menu = await openWith(C2, () => C2.chat('/cv'))
    menu = menu && await openWith(C2, () => C2.clickWindow(16, 0, 0))
    if (menu) { await C2.clickWindow(38, 0, 0); await sleep(600) }
    const reposted = C2.chatLog.some(m => m.includes('护航委托已发布'))
    if (!reposted) problems.push(`client could not post again: ${C2.chatLog.slice(-3).join(' | ')}`)
    // Take the new contract down again so later runs start clean.
    if (menu && reposted) { await C2.clickWindow(0, 0, 0); await sleep(600) }
    await quit(C2)
    return [problems.length ? 'FAIL' : 'PASS', problems.join('; ') || 'reward refunded, escort told, client could post again']
  },

  // A fully loaded player (38 stacks: every pocket, rig and backpack cell, and all four worn
  // pieces) quits during the entry camera and stays away: a body with the gear at the insertion
  // point, reported missing, insured gear (helmet and armour included) back in the mailbox, melee
  // weapon kept. Insurance is pinned to a full, immediate refund for this one scenario.
  async timeout () {
    const restore = await pinInsurance(path.join(PLAYERS, '..', '..'), rcon)
    try {
      return await timeoutRun()
    } finally {
      await restore()
    }
  }
}

async function timeoutRun () {
  const { name, before } = await prime()
  const full = fullLoad(before)
  fs.writeFileSync(profileFile(name), JSON.stringify(full, null, 2))
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
  const mailed = (after.mailbox || []).map(r => r.id).sort()
  if (carried.insured > 0 && mailed.length === 0) problems.push('no insurance payout')
  // Worn armour comes last in slot order: it is what a too-small body used to drop.
  for (const slot of ['36', '37', '38', '39']) {
    if (!mailed.includes(full.loadout[slot].id)) problems.push(`${full.loadout[slot].id} not mailed back`)
  }
  if (carried.ids.length < 38) problems.push(`only ${carried.ids.length} stacks carried`)
  return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'body, report, melee and insurance as expected'} | body ${bodyAt.replace(/^.*data: /, '')} | ${carried.ids.length} stacks, ${carried.insured} insured, mailed ${mailed.join(',')}`]
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
