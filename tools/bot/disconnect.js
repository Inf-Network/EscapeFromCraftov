// Leaving a raid by disconnecting, with headless bots.
//
//   node disconnect.js                         every scenario
//   node disconnect.js combat standin          only these (combat standin rejoin timeout bleed soldiers)
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bots, who are not op, can queue for the proving ground.
// match.wait-seconds: 2 keeps the run short. Ports come from PORT (25565), RCON_PORT (25575)
// and RCON_PASSWORD (craftov-local); profiles and the log are read from SERVER_DIR (default
// ../../server/run). raid.combat-logout-seconds must be the default 15; RECONNECT (120) must be
// the server's raid.reconnect-seconds, and a shorter one (30) makes 'timeout' quicker. Each
// scenario prints PASS or FAIL with what was seen; the exit code is the number of failures.
// DEBUG=1 prints the RCON entity checks and the killer's chat.
//
//   combat    B stabs A, and A quits a second later: A dies on the spot (report 交战中断线),
//             a body lies where A stood, B gets the kill, and A comes back in the lobby
//   standin   A quits outside a fight: a stand-in stands where A was; B stabs it to death, a
//             body lies there, B gets the kill, and A comes back in the lobby
//   rejoin    A quits, the stand-in takes a hit, and A comes back 10 s later: in the stand-in's
//             place, at its health, with the same gear, and the stand-in is gone
//   timeout   A quits and does not come back: when the reconnect window ends the stand-in is
//             gone and a body in A's skin and helmet lies there (report 失踪（断线超时）)
//   bleed     A quits with a heavy bleed and comes back 30 s later (less with a short reconnect
//             window): about 52 HP down (20 ticks of 2.6), and the stand-in bled as well
//   soldiers  A, out of the insertion grace, quits next to a soldier post and comes back 5 s
//             later: a grenade cannot be thrown for 2 s but can be after, and a soldier hits A
//             well before the 14 s the old rejoin grace gave
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const LOG = path.join(SERVER_DIR, 'logs/latest.log')
const MAP = process.env.MAP || 'proving'
// The proving ground's soldier post (a guard group of two) and a spot in front of it.
const POST = process.env.POST || '47.5 65 30.5'
// raid.reconnect-seconds of the server (the timeout scenario waits that long).
const RECONNECT = Number(process.env.RECONNECT || 120)

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

/** The text of a chat component sent as NBT (an action bar), without its styling. */
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

/** A fresh player with a random name (a reused one would rejoin an old raid), or one coming back. */
function make (again) {
  const name = again || `d${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  const track = p => {
    if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '')
  }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  // Paper sends action bars as their own packet, which mineflayer does not turn into an event.
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

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(50) }
  return false
}

const inLobby = bot => bot.worldName === 'craftov_lobby'
const inRaidWorld = bot => bot.worldName.startsWith('craftov_raid_')
const landed = bot => inRaidWorld(bot) && bot.game.gameMode === 'adventure'
const queue = bot => bot.chat(`/cv queue ${MAP} NORMAL SOLO`)

async function quitAll () {
  for (const bot of live.splice(0)) { try { bot.quit('disconnect.quitting') } catch (e) {} }
  await sleep(500)
}

/** Craftov item ids the bot carries, sorted, with amounts. */
function inventory (bot) {
  const ids = []
  for (const item of bot.inventory.slots) {
    const data = item && (item.components || []).find(c => c.type === 'custom_data')
    const pdc = data && data.data.value.PublicBukkitValues && data.data.value.PublicBukkitValues.value
    if (pdc && pdc['craftov:id']) ids.push(pdc['craftov:id'].value + 'x' + item.count)
  }
  return ids.sort()
}

function count (bot, id) {
  return inventory(bot).filter(s => s.startsWith(id + 'x')).reduce((n, s) => n + Number(s.split('x').pop()), 0)
}

/** The hotbar slot (0-8) holding a Craftov item, or -1. */
function hotbarSlot (bot, id) {
  for (let i = 0; i < 9; i++) {
    const ids = inventory({ inventory: { slots: [bot.inventory.slots[36 + i]] } })
    if (ids.length && ids[0].startsWith(id + 'x')) return i
  }
  return -1
}

const standInSel = name => `@e[type=minecraft:mannequin,nbt={BukkitValues:{"craftov:stand_in":"${offlineUuid(name)}"}},limit=1]`
const bodySel = '@e[type=minecraft:mannequin,distance=..2,nbt={BukkitValues:{"craftov:role":"container"}}]'

async function vec (selector, field = 'Pos') {
  const out = await rcon(`data get entity ${selector} ${field}`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}

async function health (selector) {
  const m = (await rcon(`data get entity ${selector} Health`)).match(/: (-?[\d.]+)f/)
  return m ? Number(m[1]) : null
}

/** How many entities match {@code selector} around {@code pos} in {@code world}. */
async function near (world, pos, selector) {
  const out = await rcon(`execute in minecraft:${world} positioned ${pos.join(' ')} if entity ${selector}`)
  if (process.env.DEBUG) console.log('near', world, pos, selector, '->', out)
  const m = out.match(/count: (\d+)/i)
  return m ? Number(m[1]) : 0
}

const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2])

/** The newest raid report in a player's saved profile. */
function lastReport (name) {
  const file = path.join(SERVER_DIR, 'plugins/Craftov/data/players', offlineUuid(name) + '.json')
  if (!fs.existsSync(file)) return null
  try {
    const profile = JSON.parse(fs.readFileSync(file, 'utf8'))
    return profile.reports && profile.reports[0]
  } catch (e) {
    return null
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

const LOG_PROBLEMS = /Cannot measure distance|Could not leave a stand-in|at net\.infnetwork\.snowball\.craftov/

function logProblems (text) {
  return text.split('\n').filter(l => LOG_PROBLEMS.test(l)).slice(0, 3)
}

/** Two fresh players in the same raid, landed and past the 3 s landing protection. */
async function pair () {
  const A = await make()
  const B = await make()
  await sleep(2500)
  queue(A)
  queue(B)
  if (!await waitFor(() => landed(A) && landed(B), 40000)) throw new Error(`not landed: A ${A.worldName} ${A.game.gameMode}, B ${B.worldName} ${B.game.gameMode}`)
  if (A.worldName !== B.worldName) throw new Error(`different raids: ${A.worldName} / ${B.worldName}`)
  await sleep(3500)
  return [A, B]
}

/** B steps up to the target (a player name or a selector) and stabs it with the starter knife. */
async function stab (B, target, entityOf) {
  await rcon(`execute as ${B.username} at ${target} run tp @s ^ ^ ^1.2 facing entity ${target}`)
  await sleep(600)
  B.setQuickBarSlot(2)
  await sleep(300)
  const entity = entityOf()
  if (!entity) return false
  B.attack(entity)
  return true
}

const scenarios = {
  async combat () {
    const log = logFrom()
    const [A, B] = await pair()
    const world = A.worldName
    if (!await stab(B, A.username, () => B.players[A.username] && B.players[A.username].entity)) return ['FAIL', 'B cannot see A']
    await sleep(500)
    const hp = await health(A.username)
    const at = await vec(A.username)
    await leave(A)
    await sleep(1500)
    const problems = []
    if (hp === null || hp >= 100) problems.push(`the stab did not land (A at ${hp} HP)`)
    const bodies = await near(world, at, bodySel)
    if (!bodies) problems.push('no body where A stood')
    if (await near(world, at, standInSel(A.username).replace(',limit=1', ''))) problems.push('a stand-in where A stood')
    const credited = B.chatLog.some(l => l.includes('击杀') && l.includes(A.username))
    if (process.env.DEBUG) console.log(B.chatLog.slice(-15))
    if (!credited) problems.push('B was not credited')
    const report = lastReport(A.username)
    if (!report || report.outcome !== '交战中断线') problems.push(`report: ${report && report.outcome}`)
    const A2 = await make(A.username)
    if (!await waitFor(() => inLobby(A2), 8000)) problems.push(`A came back in ${A2.worldName}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'death on quit, body, kill credited, back in the lobby'} | A at ${hp} HP after the stab, report "${report && report.outcome}"`]
  },

  async standin () {
    const log = logFrom()
    const [A, B] = await pair()
    const world = A.worldName
    const at = await vec(A.username)
    await leave(A)
    await sleep(1000)
    const problems = []
    const sel = standInSel(A.username)
    const standAt = await vec(sel)
    if (!standAt) return ['FAIL', 'no stand-in']
    if (dist(standAt, at) > 1) problems.push(`stand-in ${dist(standAt, at).toFixed(2)} m from where A left`)
    const startHp = await health(sel)
    // The starter knife deals 30 a stab every 12 ticks: four stabs for 100 HP.
    let stabs = 0
    const standIn = () => Object.values(B.entities).find(e => e.name === 'mannequin' && e.position.distanceTo(B.entity.position) < 4
      && Math.hypot(e.position.x - standAt[0], e.position.z - standAt[2]) < 0.3)
    for (; stabs < 8 && await vec(sel); stabs++) {
      if (!await stab(B, sel, standIn)) { problems.push('B cannot see the stand-in'); break }
      await sleep(700)
    }
    if (await vec(sel)) problems.push(`stand-in still up after ${stabs} stabs (${await health(sel)} HP)`)
    await sleep(500)
    if (!await near(world, standAt, bodySel)) problems.push('no body where the stand-in stood')
    const credited = B.chatLog.some(l => l.includes('击杀') && l.includes(A.username))
    if (process.env.DEBUG) console.log(B.chatLog.slice(-15))
    if (!credited) problems.push('B was not credited')
    const report = lastReport(A.username)
    if (!report || !report.outcome.startsWith('断线后被 ' + B.username + ' 击杀')) problems.push(`report: ${report && report.outcome}`)
    const A2 = await make(A.username)
    if (!await waitFor(() => inLobby(A2), 8000)) problems.push(`A came back in ${A2.worldName}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'stand-in, killed, body, kill credited, back in the lobby'} | stand-in at ${startHp} HP, ${stabs} stabs, report "${report && report.outcome}"`]
  },

  async rejoin () {
    const log = logFrom()
    const [A] = await pair()
    const gear = inventory(A)
    const hpBefore = await health(A.username)
    await leave(A)
    await sleep(2000)
    const sel = standInSel(A.username)
    await rcon(`damage ${sel} 20 minecraft:explosion`)
    await sleep(8000)
    const standAt = await vec(sel)
    const standHp = await health(sel)
    const problems = []
    if (!standAt) return ['FAIL', 'no stand-in']
    if (standHp === null || standHp >= hpBefore) problems.push(`the stand-in took no damage (${hpBefore} -> ${standHp})`)
    const A2 = await make(A.username)
    if (!await waitFor(() => landed(A2), 8000)) return ['FAIL', `A came back in ${A2.worldName} ${A2.game.gameMode}`]
    await sleep(1000)
    const pos = await vec(A2.username)
    const hp = await health(A2.username)
    if (!pos || dist(pos, standAt) > 0.5) problems.push(`back ${pos ? dist(pos, standAt).toFixed(2) + ' m' : '?'} from the stand-in`)
    if (hp === null || Math.abs(hp - standHp) > 0.5) problems.push(`health ${hp}, stand-in had ${standHp}`)
    if (await vec(sel)) problems.push('stand-in still there')
    const now = inventory(A2)
    if (JSON.stringify(now) !== JSON.stringify(gear)) problems.push(`gear changed: ${gear.join(',')} -> ${now.join(',')}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'back in its place, at its health, same gear, stand-in gone'} | ${hpBefore} HP on leaving, stand-in ${standHp}, back at ${hp}`]
  },

  async timeout () {
    const log = logFrom()
    const [A] = await pair()
    const world = A.worldName
    await leave(A)
    await sleep(1000)
    const sel = standInSel(A.username)
    const standAt = await vec(sel)
    if (!standAt) return ['FAIL', 'no stand-in']
    await sleep(RECONNECT * 1000 + 2500)
    const problems = []
    if (await vec(sel)) problems.push('stand-in still there after the reconnect window')
    if (!await near(world, standAt, bodySel)) problems.push('no body where the stand-in stood')
    const body = cmd => rcon(`execute in minecraft:${world} positioned ${standAt.join(' ')} run data get entity ${bodySel.replace(']', ',limit=1]')} ${cmd}`)
    const head = await body('equipment.head')
    const look = await body('profile')
    if (!head.includes('helmet')) problems.push(`the body wears no helmet: ${head.slice(0, 120)}`)
    if (!look.includes(A.username)) problems.push(`the body is not in A's skin: ${look.slice(0, 120)}`)
    const report = lastReport(A.username)
    if (!report || report.outcome !== '失踪（断线超时）') problems.push(`report: ${report && report.outcome}`)
    const A2 = await make(A.username)
    if (!await waitFor(() => inLobby(A2), 8000)) problems.push(`A came back in ${A2.worldName}`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'stand-in became a body in A\'s skin and helmet, back in the lobby'} | report "${report && report.outcome}"`]
  },

  async bleed () {
    const log = logFrom()
    const [A] = await pair()
    await rcon(`cv injure ${A.username} chest heavy`)
    await sleep(200)
    const hpBefore = await health(A.username)
    const leftAt = Date.now()
    await leave(A)
    const sel = standInSel(A.username)
    await sleep(1000)
    const early = await health(sel)
    // 30 s away, or less if the reconnect window is shorter than that.
    const away = Math.min(30, RECONNECT - 4) * 1000
    await sleep(away - (Date.now() - leftAt))
    const late = await health(sel)
    const A2 = await make(A.username)
    const offline = (Date.now() - leftAt) / 1000
    if (!await waitFor(() => landed(A2), 8000)) return ['FAIL', `A came back in ${A2.worldName} ${A2.game.gameMode}`]
    await sleep(300)
    const hp = await health(A2.username)
    const problems = []
    const lost = hpBefore - hp
    // One tick of 2.6 every 1.5 s (52 for 30 s), give or take a tick either side of the quit and the join.
    const expected = offline / 1.5 * 2.6
    if (!(Math.abs(lost - expected) <= 2 * 2.6 + 0.1)) problems.push(`lost ${lost.toFixed(1)} HP in ${offline.toFixed(1)} s offline, expected about ${expected.toFixed(0)}`)
    if (!(early > late)) problems.push(`the stand-in did not bleed (${early} -> ${late})`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'bled while offline'} | ${hpBefore} HP on leaving, stand-in ${early} -> ${late}, back at ${hp} after ${offline.toFixed(1)} s (${lost.toFixed(1)} lost)`]
  },

  async soldiers () {
    const log = logFrom()
    const [A] = await pair()
    // Out of the soldiers' insertion grace (3 s protection + 12 s), far from them.
    await sleep(13000)
    await rcon(`cv give ${A.username} smoke 2`)
    await sleep(500)
    // Up to the soldier post, and out before anyone there can react.
    await rcon(`execute in minecraft:${A.worldName} run tp ${A.username} ${POST} 180 0`)
    await sleep(150)
    await leave(A)
    const problems = []
    await sleep(5000)
    const A2 = await make(A.username)
    const back = Date.now()
    if (!await waitFor(() => landed(A2), 8000)) return ['FAIL', `A came back in ${A2.worldName} ${A2.game.gameMode}`]
    const hp0 = await health(A2.username)
    // A smoke grenade in hand: right-click throws it only once the 2 s protection is over.
    const slot = hotbarSlot(A2, 'smoke')
    if (slot < 0) problems.push(`no smoke grenade on the hotbar (${inventory(A2).join(',')})`)
    else A2.setQuickBarSlot(slot)
    await sleep(300)
    const smoke0 = count(A2, 'smoke')
    const earlyAt = (Date.now() - back) / 1000
    A2.activateItem()
    await sleep(400)
    A2.deactivateItem()
    if (count(A2, 'smoke') < smoke0) problems.push(`threw a grenade ${earlyAt.toFixed(1)} s after rejoining`)
    // The same right-click after the protection does throw it (so the check above means something).
    let lateAt = null
    let thrownLate = false
    let hitAt = null
    await waitFor(async () => {
      const t = (Date.now() - back) / 1000
      if (lateAt === null && t >= 2.4) {
        lateAt = t
        const before = count(A2, 'smoke')
        A2.activateItem()
        await sleep(400)
        A2.deactivateItem()
        thrownLate = count(A2, 'smoke') < before
      }
      const hp = await health(A2.username)
      if (hp !== null && hp < hp0 && hitAt === null) hitAt = Date.now()
      return hitAt !== null && lateAt !== null
    }, 12000)
    if (!thrownLate) problems.push(`no throw ${lateAt === null ? '-' : lateAt.toFixed(1)} s after rejoining either`)
    const hitAfter = hitAt ? (hitAt - back) / 1000 : null
    if (hitAfter === null) problems.push('no soldier hit A within 12 s of rejoining')
    else if (hitAfter < 1.9) problems.push(`hit ${hitAfter.toFixed(1)} s after rejoining, inside the protection`)
    else if (hitAfter > 6) problems.push(`hit only ${hitAfter.toFixed(1)} s after rejoining`)
    const errors = logProblems(log())
    if (errors.length) problems.push(errors.join(' / '))
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'no throw inside the protection, a throw after it, soldiers fired right after it'} | throws tried ${earlyAt.toFixed(1)} s and ${lateAt === null ? '-' : lateAt.toFixed(1)} s, first hit ${hitAfter === null ? '-' : hitAfter.toFixed(1) + ' s'} after rejoining`]
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
    // Let the previous raid wind down before the next scenario queues.
    await sleep(2000)
  }
  process.exit(failures)
})()
