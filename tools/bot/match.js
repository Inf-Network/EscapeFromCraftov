// Matchmaking scenarios with headless bots: who lands in which raid and squad.
//
//   node match.js                 every scenario
//   node match.js pair overflow   only these
//
// Needs a local test server (server/setup.sh --local) with RCON on and, to keep
// the run short, in plugins/Craftov/config.yml:
//   match: { wait-seconds: 6, max-squads: 4 }   rank: { min-level: 1 }
// The bots are not op and most scenarios queue for the proving ground, a test
// map, so test mode must be on too:  test: { enabled: true }
// Each scenario prints PASS or FAIL with what the bots saw; the exit code is the
// number of failures.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const rcon = require('./rcon')

const WAIT = Number(process.env.MATCH_WAIT || 6)        // match.wait-seconds on the server
// The server's config, edited by the 'cap' scenario (then restored) to try another max-squads.
const CONFIG = process.env.MATCH_CONFIG || path.join(__dirname, '../../server/run/plugins/Craftov/config.yml')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const bots = []

function make () {
  const name = `m${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
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
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

const world = bot => bot.worldName
const inRaid = bot => world(bot).startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => world(bot) === 'craftov_lobby'
const squad = bot => { const t = bot.teamMap && bot.teamMap[bot.username]; return t ? t.team : null }

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(250) }
  return false
}

/** Raid ids from `cv raids`. */
async function raids () {
  const out = strip(await rcon('cv raids'))
  return [...out.matchAll(/\b(R\d+)\b/g)].map(m => m[1])
}

/** Ends every raid, empties queues and parties, and waits for all bots in the lobby. */
async function reset (crew) {
  for (const bot of crew) { bot.chat('/cv leave'); bot.chat('/cv party leave') }
  for (let i = 0; i < 40 && (await raids()).length; i++) { await rcon('cv end'); await sleep(1500) }
  for (const bot of crew) if (!inLobby(bot)) bot.chat('/cv lobby')
  await waitFor(() => crew.every(inLobby), 20000)
  await sleep(1500)
}

async function party (leader, ...mates) {
  for (const mate of mates) {
    leader.chat(`/cv party invite ${mate.username}`)
    await sleep(500)
    mate.chat('/cv party accept')
    await sleep(500)
  }
}

const queue = (bot, map = 'proving', mode = 'SOLO', ranked = false, fill = true, tier = 'NORMAL') =>
  bot.chat(`/cv queue ${map} ${tier} ${mode} ${ranked} ${fill}`)
const saw = (bot, text) => bot.chatLog.some(m => m.includes(text))
const matchedAt = bot => (bot.titles.find(t => t.text.includes('匹配成功')) || { at: Infinity }).at

async function maxSquads (n) {
  const text = fs.readFileSync(CONFIG, 'utf8')
  const old = Number((text.match(/^  max-squads: (\d+)/m) || [])[1])
  fs.writeFileSync(CONFIG, text.replace(/^  max-squads: \d+/m, `  max-squads: ${n}`))
  await rcon('cv reload')
  return old
}

/** Waits for the bots to be inserted and returns {name: {world, squad}}. */
async function landed (crew, ms = (WAIT + 45) * 1000) {
  const ok = await waitFor(() => crew.every(inRaid), ms)
  await sleep(1200) // squad teams arrive just after the teleport
  const out = {}
  for (const bot of crew) out[bot.username] = { world: inRaid(bot) ? world(bot) : `(${world(bot)} ${bot.game.gameMode})`, squad: squad(bot) }
  return { ok, out }
}

const worlds = (r, crew) => new Set(crew.map(b => r.out[b.username].world))
const squads = (r, crew) => new Set(crew.map(b => r.out[b.username].squad))

const scenarios = {
  // One player alone still gets a raid once the countdown ends.
  async solo (crew) {
    const [a] = crew
    queue(a)
    const r = await landed([a])
    return [r.ok, r]
  },
  // Two strangers who queue a moment apart meet in one raid, as enemies.
  async pair (crew) {
    const [a, b] = crew
    queue(a); await sleep(2000); queue(b)
    const r = await landed([a, b])
    return [r.ok && worlds(r, [a, b]).size === 1 && squads(r, [a, b]).size === 2, r]
  },
  // A party of two with auto-fill takes a third player into its squad.
  async fill (crew) {
    const [a, b, c] = crew
    await party(a, b)
    queue(a, 'proving', 'TRIO'); await sleep(1500); queue(c, 'proving', 'TRIO')
    const r = await landed([a, b, c])
    return [r.ok && worlds(r, [a, b, c]).size === 1 && squads(r, [a, b, c]).size === 1, r]
  },
  // Auto-fill off: same raid, but the party stays on its own.
  async nofill (crew) {
    const [a, b, c] = crew
    await party(a, b)
    queue(a, 'proving', 'TRIO', false, false); await sleep(1500); queue(c, 'proving', 'TRIO')
    const r = await landed([a, b, c])
    return [r.ok && worlds(r, [a, b, c]).size === 1 && squads(r, [a, b]).size === 1 && squads(r, [a, b, c]).size === 2, r]
  },
  // Two full squads share the raid.
  async squads (crew) {
    const [a, b, c, d, e, f] = crew
    await party(a, b, c); await party(d, e, f)
    queue(a, 'proving', 'TRIO'); await sleep(1000); queue(d, 'proving', 'TRIO')
    const r = await landed(crew.slice(0, 6))
    return [r.ok && worlds(r, crew.slice(0, 6)).size === 1 && squads(r, [a, b, c]).size === 1 && squads(r, [d, e, f]).size === 1 &&
      squads(r, crew.slice(0, 6)).size === 2, r]
  },
  // Ranked only decides who earns rank points: ranked and unranked players share the pool.
  async ranked (crew) {
    const [a, b] = crew
    a.titles = []; b.titles = []
    queue(a, 'proving', 'SOLO', true); await sleep(1500); queue(b, 'proving', 'SOLO', false)
    const r = await landed([a, b])
    const tagged = bot => bot.titles.some(t => t.text.includes('排位'))
    r.tagged = { [a.username]: tagged(a), [b.username]: tagged(b) }
    return [r.ok && worlds(r, [a, b]).size === 1 && tagged(a) && !tagged(b), r]
  },
  // A raid's roster is final: whoever queues after it left gets a raid of their own.
  async nolate (crew) {
    const [a, b] = crew
    queue(a)
    const first = await landed([a])
    queue(b)
    const r = await landed([a, b])
    return [first.ok && r.ok && worlds(r, [a, b]).size === 2, r]
  },
  // A pool that reaches max-squads launches at once instead of waiting out the countdown.
  async full (crew) {
    const four = crew.slice(0, 4)
    for (const bot of four) bot.titles = []
    let last = 0
    for (const bot of four) { queue(bot); last = Date.now(); await sleep(250) }
    const r = await landed(four)
    const found = Math.min(...four.map(b => (b.titles.find(t => t.text.includes('匹配成功')) || { at: Infinity }).at))
    r.launchedAfter = (found - last) / 1000
    return [r.ok && worlds(r, four).size === 1 && squads(r, four).size === 4 && r.launchedAfter < WAIT - 2, r]
  },
  // Full party, a party filled by a solo player and a lone wolf share one raid as three squads.
  async mixed (crew) {
    const [a, b, c, d, e, f] = crew
    const g = await make()
    await sleep(2500)
    await party(a, b, c); await party(d, e)
    queue(a, 'proving', 'TRIO', false, false); await sleep(500)
    queue(d, 'proving', 'TRIO'); await sleep(500)
    queue(f, 'proving', 'SOLO'); await sleep(500)
    queue(g, 'proving', 'TRIO')
    const all = [a, b, c, d, e, f, g]
    const r = await landed(all)
    await reset([g])
    g.quit()
    return [r.ok && worlds(r, all).size === 1 && squads(r, [a, b, c]).size === 1 && squads(r, [d, e, g]).size === 1 &&
      squads(r, all).size === 3, r]
  },
  // More squads than the map has spawn groups (proving: 4): the rest get a second raid, nobody is left behind
  // and no two squads share a spawn.
  async overflow (crew) {
    const six = crew.slice(0, 6)
    for (const bot of six) { queue(bot); await sleep(300) }
    const r = await landed(six, (WAIT * 2 + 60) * 1000)
    const count = {}
    for (const bot of six) count[r.out[bot.username].world] = (count[r.out[bot.username].world] || 0) + 1
    const sizes = Object.values(count).sort((x, y) => y - x)
    const spots = new Set(six.map(b => `${world(b)}:${Math.round(b.entity.position.x / 8)}:${Math.round(b.entity.position.z / 8)}`))
    r.sizes = sizes
    r.distinctSpawns = spots.size
    return [r.ok && sizes.length === 2 && sizes[0] === 4 && sizes[1] === 2 && spots.size === 6, r]
  },
  // Different maps never mix.
  async maps (crew) {
    const [a, b] = crew
    queue(a, 'proving'); await sleep(1000); queue(b, 'rustport')
    const r = await landed([a, b])
    return [r.ok && worlds(r, [a, b]).size === 2, r]
  },
  // Cancelling leaves the pool; the others still go.
  async cancel (crew) {
    const [a, b] = crew
    queue(a); await sleep(1000); queue(b); await sleep(1000)
    a.chat('/cv leave')
    const r = await landed([b])
    await sleep(2000)
    return [r.ok && inLobby(a), { ...r, a: world(a) }]
  },
  // A queued player who disconnects is dropped: had the ghost stayed in the pool, the third player would
  // fill it (4 squads) and it would leave at once; without the ghost the three wait out the countdown.
  async quit (crew) {
    const three = crew.slice(0, 3)
    const ghost = await make()
    await sleep(2500)
    for (const bot of three) bot.titles = []
    const t0 = Date.now()
    queue(ghost); await sleep(300)
    queue(three[0]); await sleep(300)
    queue(three[1]); await sleep(300)
    const queued = saw(ghost, '开始匹配') && saw(three[0], '开始匹配') && saw(three[1], '开始匹配')
    ghost.quit()
    await sleep(1500)
    queue(three[2])
    const r = await landed(three)
    r.queued = queued
    r.launchedAfter = (Math.min(...three.map(matchedAt)) - t0) / 1000
    return [r.ok && queued && worlds(r, three).size === 1 && r.launchedAfter >= WAIT - 1, r]
  },
  // Difficulty tiers are separate pools on the same map.
  async tiers (crew) {
    const [a, b, c] = crew
    await rcon(`cv give ${b.username} awr`)
    await sleep(500)
    queue(a, 'proving', 'SOLO', false, true, 'NORMAL')
    queue(b, 'proving', 'SOLO', false, true, 'CONFIDENTIAL')
    queue(c, 'proving', 'SOLO', false, true, 'NORMAL')
    const r = await landed([a, b, c])
    return [r.ok && r.out[a.username].world === r.out[c.username].world && r.out[a.username].world !== r.out[b.username].world, r]
  },
  // Joining a party while queued alone cancels the solo entry; the party then queues as one squad.
  async joinparty (crew) {
    const [a, b] = crew
    queue(b, 'proving', 'TRIO')
    await sleep(1000)
    await party(a, b)
    const cancelled = saw(b, '加入了小队')
    await sleep((WAIT + 2) * 1000)
    const stayed = inLobby(b)
    queue(a, 'proving', 'TRIO')
    const r = await landed([a, b])
    r.cancelled = cancelled; r.stayed = stayed
    return [cancelled && stayed && r.ok && squads(r, [a, b]).size === 1, r]
  },
  // Launch checks the requirements again: gear dropped while waiting keeps a player out of 机密.
  async recheck (crew) {
    const [a, b] = crew
    await rcon(`cv give ${a.username} awr`); await rcon(`cv give ${b.username} awr`)
    await sleep(500)
    queue(a, 'proving', 'SOLO', false, true, 'CONFIDENTIAL'); await sleep(300)
    queue(b, 'proving', 'SOLO', false, true, 'CONFIDENTIAL')
    await sleep(1000)
    await rcon(`clear ${b.username}`)
    const r = await landed([a])
    await sleep(2000)
    r.b = world(b); r.told = saw(b, '未能出发')
    return [r.ok && inLobby(b) && r.told, r]
  },
  // A teammate who disconnects in a raid stays in the party (one who leaves in the lobby is removed at once).
  // Afterwards they can be kicked by name and the party can queue again.
  async kickoffline (crew) {
    const [a] = crew
    const gone = await make()
    await sleep(2500)
    await party(a, gone)
    queue(a, 'proving', 'TRIO')
    const first = await landed([a, gone])
    const name = gone.username
    gone.quit()
    await sleep(1500)
    for (let i = 0; i < 20 && (await raids()).length; i++) { await rcon('cv end'); await sleep(1500) }
    await waitFor(() => inLobby(a), 20000)
    await sleep(1500)
    a.chatLog = []
    queue(a, 'proving', 'TRIO')
    await sleep(1000)
    const refused = first.ok && saw(a, '不在线')
    a.chat(`/cv party kick ${name}`)
    await sleep(1000)
    queue(a, 'proving', 'TRIO')
    const r = await landed([a])
    r.refused = refused
    return [refused && r.ok, r]
  },
  // The raid size is max-squads, but never more squads than the map has spawn groups:
  // proving (4 groups) splits six solos 4 + 2 with the first four queued going first, rustport (6) takes all six.
  async cap (crew) {
    const old = await maxSquads(6)
    try {
      const six = crew.slice(0, 6)
      for (const bot of six) { queue(bot, 'proving'); await sleep(300) }
      const p = await landed(six, (WAIT * 2 + 60) * 1000)
      const firstFour = new Set(six.slice(0, 4).map(b => p.out[b.username].world))
      const provingOk = p.ok && worlds(p, six).size === 2 && firstFour.size === 1 &&
        six.slice(4).every(b => !firstFour.has(p.out[b.username].world))
      await reset(crew)
      for (const bot of six) { queue(bot, 'rustport'); await sleep(300) }
      const q = await landed(six, (WAIT * 2 + 60) * 1000)
      const rustOk = q.ok && worlds(q, six).size === 1 && squads(q, six).size === 6
      return [provingOk && rustOk, { proving: p, rustport: q }]
    } finally {
      await maxSquads(old)
    }
  }
}

;(async () => {
  const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(scenarios)
  const crew = []
  for (let i = 0; i < 6; i++) crew.push(await make())
  await sleep(3000)
  let failed = 0
  for (const name of wanted) {
    if (!scenarios[name]) { console.log(`?? ${name}: no such scenario`); failed++; continue }
    await reset(crew)
    const started = Date.now()
    let ok = false
    let detail
    try { [ok, detail] = await scenarios[name](crew) } catch (err) { detail = String(err && err.stack || err) }
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name} (${((Date.now() - started) / 1000).toFixed(0)}s) ${ok ? '' : JSON.stringify(detail)}`)
    if (!ok) failed++
  }
  await reset(crew)
  for (const bot of bots) { try { bot.quit() } catch (e) {} }
  await sleep(500)
  process.exit(failed)
})()
