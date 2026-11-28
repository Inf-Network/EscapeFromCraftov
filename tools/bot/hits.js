// Last-hit records (issue #45): every victim's last hit is kept for kill credit, soldiers included,
// and records older than their 30 seconds of credit are pruned every 30 seconds. After a fight the
// count /cv raids shows ("击中记录 lastHits N 条") falls back to at most the players online, while
// soldier kills count as before: the "✖ 击败" notice, the kill feed, stats.ai_kills and the daily
// AI / knife kill contracts.
//
//   node hits.js
//
// Needs a local test server (server/setup.sh --local) with RCON on, and in
// plugins/Craftov/config.yml:  match: { wait-seconds: 3 }   test: { enabled: true }
// (the raid is on the proving ground, a test map). Ports come from MC_PORT (25565), RCON_PORT
// (25575) and RCON_PASSWORD (craftov-local); the server directory from SERVER_DIR (default the
// repository's server/run), for the bot's profile and the map's always-open extract.
// One bot lands, knifes KILLS (3) soldiers (frozen, at 1 health, put in front of it) and cuts one
// more without killing it, waits 65 seconds under resistance, then leaves through the extract.
// Prints PASS or FAIL per check; the exit code is the number of failures.
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
const KILLS = Number(process.env.KILLS || 3)

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const rcon = cmd => rconRaw(cmd, RCON).then(strip)
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)
const name = `h${Math.random().toString(36).slice(2, 7)}`
let failures = 0

function check (label, ok, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === '' ? '' : ' | ' + (typeof detail === 'string' ? detail : JSON.stringify(detail))}`)
}

function offlineUuid (player) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + player).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

async function settledProfile (player, ms = 10000) {
  const file = path.join(SERVER_DIR, 'plugins', 'Craftov', 'data', 'players', offlineUuid(player) + '.json')
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

/** (x, y, z) of the proving ground's first always-open extract, from the server's map file. */
function alwaysExtract () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'plugins', 'Craftov', 'maps', 'proving.yml'), 'utf8')
  const section = text.split('\nextracts:')[1]
  if (!section) return null
  for (const block of section.split(/\n(?=[A-Za-z_])/)[0].split('\n- ')) {
    if (/^\s*type: ALWAYS\s*$/m.test(block)) {
      const c = ['x', 'y', 'z'].map(a => (block.match(new RegExp(`^\\s*${a}: (-?[\\d.]+)\\s*$`, 'm')) || [])[1])
      if (c.every(Boolean)) return c.map(Number)
    }
  }
  return null
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(100) }
  return false
}

/** The lastHits count and the players online, from the last line of /cv raids. */
async function tracked () {
  const out = await rcon('cv raids')
  const m = out.match(/lastHits (\d+) 条 · 在线 (\d+) 人/)
  return m ? { hits: Number(m[1]), online: Number(m[2]) } : null
}

const one = tag => `@e[tag=${tag},limit=1]`

/** Tags, freezes and weakens one soldier of a kind a knife can cut from the front; returns the tag or null. */
async function soldier (tag, health) {
  for (const kind of ['patrol', 'guard', 'sniper', 'operator']) {
    for (const type of ['zombie', 'husk', 'drowned']) {
      await rcon(`tag @e[type=minecraft:${type},tag=!h_used,limit=1,nbt={BukkitValues:{"craftov:ai":"${kind}"}}] add ${tag}`)
      if ((await rcon(`execute if entity ${one(tag)}`)).includes('passed')) {
        await rcon(`tag ${one(tag)} add h_used`)
        await rcon(`data merge entity ${one(tag)} {NoAI:1b,Health:${health}f}`)
        return tag
      }
    }
  }
  return null
}

/** Puts the tagged soldier right in front of the bot and cuts it at `height` of its height until `done` (or 6 tries). */
async function cut (bot, tag, done, height = 0.56) {
  for (let n = 0; n < 6; n++) {
    await rcon(`execute as ${name} at @s run tp ${one(tag)} ^ ^ ^1.4 facing entity @s`)
    await sleep(400)
    const target = bot.nearestEntity(e => ['zombie', 'husk', 'drowned'].includes(e.name) && e.position.distanceTo(bot.entity.position) < 3)
    if (!target) continue
    await bot.lookAt(target.position.offset(0, target.height * height, 0), true)
    // Long enough for the new aim to reach the server before the swing.
    await sleep(600)
    bot.attack(target)
    await sleep(700)
    if (await done()) return true
  }
  return false
}

;(async () => {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: MC_PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  let world = ''
  const track = p => { if (p.worldState && p.worldState.name) world = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  const bars = []
  // The raw packet: mineflayer's actionBar event does not come for these.
  bot._client.on('action_bar', p => bars.push(JSON.stringify(p)))
  bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  bot.chat('/cv queue proving NORMAL SOLO')
  if (!await waitFor(() => world.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure', 40000)) {
    check('lands in the proving ground', false, `${world} ${bot.game.gameMode}`)
    bot.quit()
    process.exit(failures)
  }
  // Out of the landing protection (3 s), and safe from the soldiers that are not frozen.
  await sleep(3500)
  await rcon(`effect give ${name} minecraft:resistance 180 4 true`)
  bot.setQuickBarSlot(2)
  await sleep(500)
  // A record from before the fight (an earlier raid on this server, or a stray hit on landing) is
  // pruned once its 30 s of credit are over, maybe half way through the fight, which would hide one
  // of the fight's victims from "at least N more than before": give such records up to 40 s to go.
  let start = await tracked()
  for (let i = 0; i < 40 && start && start.hits > 0; i++) {
    await sleep(1000)
    start = await tracked()
  }
  let killed = 0
  for (let i = 0; i < KILLS; i++) {
    const tag = await soldier(`h_kill${i}`, 1)
    if (!tag) break
    const before = bars.filter(b => b.includes('击败')).length
    if (await cut(bot, tag, async () => bars.filter(b => b.includes('击败')).length > before)) killed++
    else log(`soldier ${i + 1} not killed`)
  }
  check(`knifed ${KILLS} soldiers, each with a "✖ 击败" notice`, killed === KILLS,
    `${killed} killed, ${bars.filter(b => b.includes('击败')).length} notices`)
  const alive = await soldier('h_alive', 200)
  let wounded = false
  if (alive) {
    const hp = async () => Number(((await rcon(`data get entity ${one(alive)} Health`)).match(/(-?[\d.]+)f/) || [])[1])
    const full = await hp()
    // Low on the body: a cut to the head would now finish it (#183).
    wounded = await cut(bot, alive, async () => (await hp()) < full, 0.35)
  }
  check('cut one more soldier without killing it', wounded)
  // #183: a knife to the head finishes a plain soldier at full health.
  const headTag = await soldier('h_head', 200)
  if (headTag) {
    const before = bars.filter(b => b.includes('击败')).length
    let headKill = false
    for (let n = 0; n < 6 && !headKill; n++) {
      await rcon(`execute as ${name} at @s run tp ${one(headTag)} ^ ^ ^1.4 facing entity @s`)
      await sleep(400)
      const target = bot.nearestEntity(e => ['zombie', 'husk', 'drowned'].includes(e.name) && e.position.distanceTo(bot.entity.position) < 3)
      if (!target) continue
      await bot.lookAt(target.position.offset(0, target.height - 0.15, 0), true)
      await sleep(600)
      bot.attack(target)
      await sleep(700)
      headKill = bars.filter(b => b.includes('击败')).length > before
      if (!headKill) break // one cut only: a body cut would not kill from 200
    }
    check('a knife to the head kills a soldier at full health (#183)', headKill)
    if (headKill) killed++
  }
  // #177: a knife breaks raid glass.
  const p = bot.entity.position.floored()
  const glass = p.offset(0, 1, 2)
  await rcon(`execute as ${name} at @s run setblock ~ ~1 ~2 minecraft:glass`)
  await rcon(`execute as ${name} at @s run tp @s ~ ~ ~ 0 0`)
  await sleep(600)
  await bot.lookAt(glass.offset(0.5, 0.5, 0.5), true)
  await sleep(250)
  const block = bot.blockAt(glass)
  if (block) { try { bot.swingArm('right'); await bot._client.write('block_dig', { status: 0, location: glass, face: 2, sequence: 1 }) } catch (e) {} }
  await sleep(800)
  const after = await rcon(`execute as ${name} at @s if block ~ ~1 ~2 minecraft:air`)
  check('a knife swing breaks raid glass (#177)', after.includes('passed'), after)
  const feed = await rcon(`cv feed ${name}`)
  const feedKills = feed.split('\n').filter(l => l.includes(name) && !l.includes('击杀栏是空的')).length
  check(`the kill feed holds the ${KILLS} soldier kills`, feedKills >= KILLS, feed.replace(/\n/g, ' / '))
  const fought = await tracked()
  check(`lastHits holds the victims (at least ${killed + (wounded ? 1 : 0)} more than before)`,
    fought && start && fought.hits - start.hits >= killed + (wounded ? 1 : 0), { start, fought })
  log('waiting 65 s for the records to run out and be pruned')
  await sleep(65000)
  const later = await tracked()
  check('65 s later lastHits is at most the players online', later && later.hits <= later.online, later)
  const spot = alwaysExtract()
  if (spot) await rcon(`execute as ${name} at @s run tp @s ${spot[0]} ${spot[1]} ${spot[2]}`)
  const home = await waitFor(() => world === 'craftov_lobby', 40000)
  check('extracts', home, world)
  await sleep(3000)
  await rcon(`cv close ${name}`)
  bot.quit()
  await sleep(1000)
  const profile = await settledProfile(name)
  const aiKills = (profile && profile.stats && profile.stats.ai_kills) || 0
  check(`stats.ai_kills is ${killed}`, aiKills === killed, aiKills)
  const contracts = ((profile && profile.tasks) || []).filter(t => t.kind === 'AI_KILL' || t.kind === 'MELEE_KILL')
  if (!contracts.length) {
    console.log(`SKIP contracts | no AI or knife kill contract today: ${((profile && profile.tasks) || []).map(t => t.kind).join(', ')}`)
  } else {
    const ok = contracts.every(t => t.progress === Math.min(t.target, killed))
    check('the AI / knife kill contracts moved by the kills', ok, contracts.map(t => `${t.kind} ${t.progress}/${t.target}`).join(', '))
  }
  process.exit(failures)
})()
