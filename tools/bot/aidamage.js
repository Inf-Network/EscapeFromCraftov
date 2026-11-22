// Bullet damage a player takes, against the total in their death report, with headless bots.
//
//   node aidamage.js                 every scenario
//   node aidamage.js soldiers        only these (soldiers players)
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bots, who are not op, can queue for the proving ground.
// match.wait-seconds: 2 keeps the run short. Ports come from PORT (25565), RCON_PORT (25575)
// and RCON_PASSWORD (craftov-local). Each scenario prints PASS or FAIL with what was seen; the
// exit code is the number of failures. DEBUG=1 prints every hit and the report lines.
// DIFFICULTY=hard switches the raid world to that difficulty before the soldiers shoot: their
// damage must not change, since it never goes through vanilla's difficulty scaling.
//
// The bot reads its own health updates (shown scaled to 20, so they are scaled back by the
// server's own reading) and the damage events that come with them, so it knows what each
// bullet took off (bleeding ticks have their own damage type and are left out; a tick
// with both makes the attempt inconclusive and it is run again). RCON kills the bot before a
// shot could, which adds nothing to the report, so the health lost to bullets should equal the
// total under 受到的伤害.
//
//   soldiers  A, out of helmet and vest, stands at the proving ground's soldier post once the
//             insertion grace is over and is shot until under 60 HP: the raid world is on normal
//             difficulty, and the health lost is about 1.0 times the report's total (it was 1.5
//             while raid worlds ran on hard difficulty, which scales damage from mobs to
//             players when the damage names the mob as its attacker). The hits carry where the
//             shot came from but no attacking entity, and the soldiers there are still at full
//             health afterwards (they do not shoot each other).
//   players   B, from another squad, puts three pistol rounds into A (no helmet or vest): about
//             1.0 as well, as it always was, and each hit is the pistol's 22 times the body
//             part's multiplier.
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const MAP = process.env.MAP || 'proving'
// The proving ground's soldier post (a guard group of two) and a spot in front of it.
const POST = process.env.POST || '47.5 65 30.5'
const ATTEMPTS = 3
const DIFFICULTY = process.env.DIFFICULTY || ''
const BULLET = new Set(['mob_attack', 'player_attack'])
// The starter pistol (P18, 9x19 FMJ) per body part, as DamageService weighs them.
const PISTOL = { 头部: null, 胸部: 22, 腹部: 22 * 0.95, 左臂: 22 * 0.65, 右臂: 22 * 0.65, 左腿: 22 * 0.7, 右腿: 22 * 0.7 }

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const run = Math.random().toString(36).slice(2, 5)
let serial = 0
const live = []

/** A fresh player with a random name (a reused one would rejoin an old raid). */
function make () {
  const name = `h${run}${(serial++).toString().padStart(2, '0')}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  bot.damageTypes = []
  bot.hits = [] // every health drop, with the damage events since the previous health update
  const track = p => {
    if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '')
  }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  // Damage events name their type by its index in the damage_type registry sent while configuring.
  bot._client.on('registry_data', p => {
    if (p.id.replace(/^minecraft:/, '') === 'damage_type') bot.damageTypes = p.entries.map(e => e.key.replace(/^minecraft:/, ''))
  })
  let pending = []
  let last = null
  bot._client.on('damage_event', p => {
    if (bot.entity && p.entityId === bot.entity.id) {
      pending.push({ type: bot.damageTypes[p.sourceTypeId] || String(p.sourceTypeId), cause: p.sourceCauseId, direct: p.sourceDirectId, pos: p.sourcePosition || null })
    }
  })
  bot._client.on('update_health', p => {
    if (last !== null && p.health < last - 1e-4) bot.hits.push({ drop: last - p.health, events: pending })
    pending = []
    last = p.health
  })
  bot.on('messagestr', m => { if (m.trim()) bot.chatLog.push(m) })
  bot.on('kicked', r => console.log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => console.log(name, 'ERROR', e.message))
  live.push(bot)
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function quitAll () {
  for (const bot of live.splice(0)) { try { bot.quit('disconnect.quitting') } catch (e) {} }
  await sleep(500)
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(50) }
  return false
}

const landed = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const queue = bot => bot.chat(`/cv queue ${MAP} NORMAL SOLO`)

/** Fresh players, each in a squad of their own, landed in the same raid past the 3 s landing protection. */
async function land (n) {
  const bots = []
  for (let i = 0; i < n; i++) bots.push(await make())
  await sleep(2500)
  bots.forEach(queue)
  if (!await waitFor(() => bots.every(landed), 40000)) throw new Error('not landed: ' + bots.map(b => `${b.worldName} ${b.game.gameMode}`).join(', '))
  if (new Set(bots.map(b => b.worldName)).size > 1) throw new Error('different raids: ' + bots.map(b => b.worldName).join(' / '))
  await sleep(3500)
  return bots
}

async function unarmour (bot) {
  await rcon(`item replace entity ${bot.username} armor.head with minecraft:air`)
  await rcon(`item replace entity ${bot.username} armor.chest with minecraft:air`)
  await sleep(200)
}

async function vec (selector) {
  const m = (await rcon(`data get entity ${selector} Pos`)).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}

async function health (selector) {
  const m = (await rcon(`data get entity ${selector} Health`)).match(/: (-?[\d.]+)f/)
  return m ? Number(m[1]) : null
}

/** Health of every soldier near the post, sorted. */
async function soldiers (world) {
  let all = []
  for (const type of ['zombie', 'husk']) {
    const out = await rcon(`execute in minecraft:${world} positioned ${POST} as @e[type=minecraft:${type},distance=..40] run data get entity @s Health`)
    all = all.concat([...out.matchAll(/entity data: (-?[\d.]+)f/g)].map(m => Number(m[1])))
  }
  return all.sort((a, b) => a - b)
}

/**
 * The client shows health scaled to 20 hearts' worth (HealthService): the factor back to real
 * health points, from the server's reading against the bot's.
 */
async function healthScale (bot) {
  const real = await health(bot.username)
  return real && bot.health ? real / bot.health : 1
}

/** The health the bot lost to bullets from hit {@code since} on, and the bullet events. */
function bullets (bot, since, scale) {
  const out = { lost: 0, events: [], mixed: 0, bleed: 0 }
  for (const h of bot.hits.slice(since)) {
    const shots = h.events.filter(e => BULLET.has(e.type))
    const drop = h.drop * scale
    if (shots.length && shots.length < h.events.length) out.mixed++
    else if (shots.length) { out.lost += drop; out.events.push(...shots) } else out.bleed += drop
    if (process.env.DEBUG) console.log(bot.username, 'lost', drop.toFixed(2), JSON.stringify(h.events))
  }
  return out
}

/** The rows under 受到的伤害 in the bot's chat: who, body part, hits and damage. */
function report (bot) {
  const i = bot.chatLog.findIndex(l => l.includes('受到的伤害'))
  if (i < 0) return null
  const rows = []
  for (const line of bot.chatLog.slice(i + 1)) {
    if (!line.includes('→')) break
    if (process.env.DEBUG) console.log(bot.username, 'report', line)
    const [who, parts] = line.split('→')
    for (const m of parts.matchAll(/(\S+) ×(\d+) (\d+)/g)) rows.push({ who: who.trim(), part: m[1], n: Number(m[2]), dmg: Number(m[3]) })
  }
  return rows
}

/** RCON kills the bot (adding nothing to its report) and waits for the report in its chat. */
async function killAndRead (bot) {
  await rcon(`kill ${bot.username}`)
  // The report comes after the kill replay (about 7 s) when there is a killer to follow.
  if (!await waitFor(() => report(bot) && bot.chatLog.slice(bot.chatLog.findIndex(l => l.includes('受到的伤害')) + 1).some(l => l.includes('→')), 20000)) return null
  await sleep(300)
  return report(bot)
}

function compare (hit, rows) {
  const total = rows.reduce((s, r) => s + r.dmg, 0)
  const count = rows.reduce((s, r) => s + r.n, 0)
  return { total, count, ratio: total > 0 ? hit.lost / total : NaN }
}

/** Rounding to whole points per report row leaves up to half a point per row either way. */
const close = (hit, rows, c) => Math.abs(hit.lost - c.total) <= 0.5 * rows.length + 0.1

const scenarios = {
  async soldiers () {
    const [A] = await land(1)
    await unarmour(A)
    // Out of the soldiers' insertion grace (3 s protection + 12 s) away from them.
    await sleep(12500)
    const world = A.worldName
    const difficulty = await rcon(`execute in minecraft:${world} run difficulty`)
    if (DIFFICULTY) await rcon(`execute in minecraft:${world} run difficulty ${DIFFICULTY}`)
    const before = await soldiers(world)
    const scale = await healthScale(A)
    const since = A.hits.length
    await rcon(`execute in minecraft:${world} run tp ${A.username} ${POST} 180 0`)
    const shotAt = await waitFor(async () => (await health(A.username)) < 60, 15000)
    const rows = shotAt ? await killAndRead(A) : null
    const hit = bullets(A, since, scale)
    if (!shotAt) return ['FAIL', `the soldiers did not bring A under 60 HP in 15 s (${await health(A.username)} HP)`]
    if (!rows) return ['FAIL', 'no 受到的伤害 report after the kill']
    if (hit.mixed) return ['RETRY', `a bleed tick and a bullet arrived together (${hit.mixed}x)`]
    const c = compare(hit, rows)
    if (c.count !== hit.events.length) return ['RETRY', `the report counts ${c.count} hits, A saw ${hit.events.length} (a shot was stopped)`]
    const after = await soldiers(world)
    const problems = []
    if (!/normal/i.test(difficulty)) problems.push(`raid world: ${difficulty.trim()}`)
    if (!close(hit, rows, c)) problems.push(`lost ${hit.lost.toFixed(1)} HP to ${hit.events.length} hits, the report says ${c.total}`)
    const named = hit.events.filter(e => e.cause || e.direct)
    if (named.length) problems.push(`${named.length} hits name an attacking entity`)
    if (hit.events.some(e => !e.pos)) problems.push('a hit without the position it came from')
    if (!before.length) problems.push('no soldiers near the post')
    else if (JSON.stringify(before) !== JSON.stringify(after)) problems.push(`soldier health ${before.join(',')} -> ${after.join(',')}`)
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'soldier damage matches the report, soldiers unhurt'} | lost ${hit.lost.toFixed(1)} HP to ${hit.events.length} hits (${hit.events.map(e => e.type).filter((t, i, a) => a.indexOf(t) === i).join(',')}), report ${c.total} (${rows.map(r => `${r.part}×${r.n} ${r.dmg}`).join(', ')}), ratio ${c.ratio.toFixed(2)}${hit.bleed ? `, ${hit.bleed.toFixed(1)} to bleeding left out` : ''}${DIFFICULTY ? ` on ${DIFFICULTY}` : ''} | soldiers ${before.join(',')}`]
  },

  async players () {
    const [A, B] = await land(2)
    await unarmour(A)
    const scale = await healthScale(A)
    const since = A.hits.length
    B.setQuickBarSlot(1) // the starter pistol
    await sleep(800)
    let fired = 0
    for (let i = 0; i < 3; i++) {
      const at = await vec(A.username)
      if (!at) break
      await rcon(`execute as ${B.username} at ${A.username} run tp @s ^ ^ ^1.6`)
      await sleep(300)
      // The bot's own look packets win over a teleport's rotation, so it aims itself.
      await B.look(B.entity.yaw + 0.5, 0, true)
      await sleep(100)
      await B.lookAt(new Vec3(at[0], at[1] + 1.1, at[2]), true)
      await sleep(200)
      const target = B.players[A.username] && B.players[A.username].entity
      if (!target) break
      B.attack(target) // a left click on an entity with a gun in hand pulls the trigger
      fired++
      await sleep(600)
    }
    if (!fired) return ['FAIL', 'B cannot see A']
    await sleep(300)
    const rows = await killAndRead(A)
    const hit = bullets(A, since, scale)
    if (!rows) return ['FAIL', `no 受到的伤害 report after the kill (${fired} shots)`]
    if (hit.mixed) return ['RETRY', `a bleed tick and a bullet arrived together (${hit.mixed}x)`]
    const c = compare(hit, rows)
    if (c.count !== hit.events.length) return ['RETRY', `the report counts ${c.count} hits, A saw ${hit.events.length}`]
    const problems = []
    if (!hit.events.length) problems.push(`none of ${fired} shots hit`)
    if (!close(hit, rows, c)) problems.push(`lost ${hit.lost.toFixed(1)} HP to ${hit.events.length} hits, the report says ${c.total}`)
    for (const r of rows) {
      const per = PISTOL[r.part]
      if (per && Math.abs(r.dmg - Math.round(per * r.n)) > 0.6) problems.push(`${r.part} ×${r.n} ${r.dmg}, expected ${(per * r.n).toFixed(1)}`)
    }
    if (hit.events.some(e => e.cause === 0)) problems.push('a hit without B as the attacker')
    return [problems.length ? 'FAIL' : 'PASS', `${problems.join('; ') || 'player damage matches the report and the pistol'} | lost ${hit.lost.toFixed(1)} HP to ${hit.events.length} hits, report ${c.total} (${rows.map(r => `${r.part}×${r.n} ${r.dmg}`).join(', ')}), ratio ${c.ratio.toFixed(2)}`]
  }
}

;(async () => {
  const wanted = process.argv.slice(2)
  const names = wanted.length ? wanted : Object.keys(scenarios)
  let failures = 0
  for (const name of names) {
    let result
    for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        result = await scenarios[name]()
      } catch (e) {
        result = ['FAIL', e.stack]
      }
      await quitAll()
      // Let the previous raid wind down before the next attempt queues.
      await sleep(2000)
      if (result[0] !== 'RETRY') break
      console.log(`retry ${name}: ${result[1]}`)
    }
    if (result[0] !== 'PASS') failures++
    console.log(`${result[0] === 'RETRY' ? 'FAIL' : result[0]} ${name}: ${result[1]}`)
  }
  process.exit(failures)
})()
