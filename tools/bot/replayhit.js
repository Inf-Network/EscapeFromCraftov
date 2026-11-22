// Kill-replay stand-ins must not stop bullets (issue #36), and whatever should take hits still does.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node replayhit.js [replay] [targets]
//
// replay   Three bots on the proving ground: A alone, B and C in one squad. All three stand still in
//          spawn bay 1 for 8 s, then B is downed and A shoots B until B dies. That starts B's kill
//          replay: invisible mannequins, seen only by B, re-enact the last six seconds, among them
//          stand-ins of A and C exactly where A and C still stand (C's eye is inside its own
//          stand-in). While the replay plays:
//            - every mannequin carries craftov:role, the stand-ins "replay" (corpses "container");
//            - C fires at bare floor: no craftov:hit.*, no flesh or armour impact, the floor is hit;
//            - C fires at A: A loses health and C hears the hit.
//          B must still see the replay: the stand-ins are spawned for B and B's camera follows one.
// targets  Bullets stop only on combatants, so check that the rest still do: a training dummy on
//          the private range, an enemy operator squad (summoned with /cv event operators) and a
//          recon drone (/cv event drone) on the proving ground each give the shooter a hit.
//
// Without arguments both run. Needs a local test server (server/setup.sh --local) with RCON on. The
// bots are made op so they can queue for the proving ground. To keep the run short and quiet, in
// plugins/Craftov/config.yml:
//   match: { wait-seconds: 6 }   cinematic: { enabled: false }   events: { per-raid: 0 }
//   ai: { count-multiplier: 0, operator-chance: [0, 0, 0] }
// (soldiers near the bay are removed anyway). The exit code is the number of failed checks.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const strip = s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, '')
const run = Math.random().toString(36).slice(2, 6)
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const MANNEQUIN = require('minecraft-data')('1.21.11').entitiesByName.mannequin.id

const failures = []
let ours = [] // raid ids this run started, ended at the end
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

function make (tag) {
  const name = `rh${run}${tag}`
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.sounds = []
  bot.titles = []
  bot.chatLog = []
  bot.mannequins = new Map() // entity id -> when it was spawned for this bot
  bot.cameras = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  const heard = p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_./]+/); if (m) bot.sounds.push({ at: Date.now(), id: m[0] }) }
  bot._client.on('sound_effect', heard)
  bot._client.on('entity_sound_effect', heard)
  bot._client.on('spawn_entity', p => { if (p.type === MANNEQUIN) bot.mannequins.set(p.entityId, Date.now()) })
  bot._client.on('camera', p => bot.cameras.push(p.cameraId))
  bot.on('title', (t, type) => bot.titles.push({ at: Date.now(), text: strip(JSON.stringify(t)) }))
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(tag, 'CHAT', m) } })
  bot.on('kicked', r => log(tag, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(tag, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms, step = 200) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(step) }
  return false
}

const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const id = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : null }
const soundsSince = (bot, from) => bot.sounds.slice(from).map(s => s.id)
/** Raid ids from `cv raids`. */
async function raids () {
  return [...(await rcon('cv raids')).matchAll(/\b(R\d+)\b/g)].map(m => m[1])
}
async function count (selector) {
  const out = await rcon(`execute if entity ${selector}`)
  const m = out.match(/count: (\d+)/i)
  return m ? Number(m[1]) : 0
}

async function arm (bot) {
  await rcon(`cv give ${bot.username} m4c`)
  await rcon(`cv give ${bot.username} ammo_556_m855 120`)
  await sleep(800)
  const slot = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && id(s) === 'm4c')
  if (slot >= 0) bot.setQuickBarSlot(slot - 36)
  return slot >= 0
}

/** Looks at a point and pulls the trigger {@code times} times; returns the shots heard by the shooter. */
async function fire (bot, point, times, gap = 250) {
  await bot.lookAt(point, true)
  await sleep(200)
  const from = bot.sounds.length
  for (let i = 0; i < times; i++) { bot.swingArm(); await sleep(gap) }
  await sleep(400)
  return soundsSince(bot, from)
}

async function replay () {
  const a = await make('A')
  const b = await make('B')
  const c = await make('C')
  await sleep(2500)
  for (const bot of [a, b, c]) await rcon(`op ${bot.username}`)
  b.chat(`/cv party invite ${c.username}`)
  await sleep(600)
  c.chat('/cv party accept')
  await sleep(600)
  const older = await raids()
  a.chat('/cv queue proving NORMAL SOLO')
  await sleep(300)
  b.chat('/cv queue proving NORMAL TRIO')
  const landed = await waitFor(() => [a, b, c].every(inRaid), 120000, 500)
  const world = a.worldName
  ours.push(...(await raids()).filter(id => !older.includes(id)))
  check('all three land in one raid', landed && b.worldName === world && c.worldName === world,
    [a, b, c].map(x => `${x.username}:${x.worldName}/${x.game.gameMode}`).join(' '))
  if (!landed) return done([a, b, c])
  await sleep(3500) // spawn protection
  await clear(world)
  check('A and C hold an M4C', (await arm(a)) && (await arm(c)))
  // Spawn bay 1: A faces B from 12 blocks west, C stands 5 blocks south of B.
  await rcon(`execute as ${a.username} at @s run tp @s 10.5 65 90.5 -90 0`)
  await rcon(`execute as ${b.username} at @s run tp @s 22.5 65 90.5 90 0`)
  await rcon(`execute as ${c.username} at @s run tp @s 22.5 65 95.5 90 0`)
  // Stand still for longer than the six-second replay window, so the stand-ins of A and C stand
  // exactly where A and C stand for the whole replay.
  await sleep(8500)

  // B goes down (C is still up), then A finishes B: the kill has a killer, so B gets a replay.
  await rcon(`kill ${b.username}`)
  await sleep(800)
  // A downed player crawls, so aim low. Q reloads halfway in case the magazine runs dry.
  const dead = () => b.game.gameMode === 'spectator' || b.titles.some(t => t.text.includes('击杀回放'))
  for (let i = 0; i < 60 && !dead(); i++) {
    const target = a.players[b.username] && a.players[b.username].entity
    if (target) await a.lookAt(target.position.offset(0, 0.3, 0), true)
    a.swingArm()
    await sleep(150)
    if (i === 30) { a._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(3000) }
  }
  const replayed = await waitFor(() => b.titles.some(t => t.text.includes('击杀回放')), 6000, 100)
  check('B is killed by A and gets the kill replay', replayed, b.titles.slice(-3).map(t => t.text).join(' | '))
  if (!replayed) return done([a, b, c])
  await sleep(400)

  // Every mannequin is tagged; the stand-ins as "replay".
  log('BukkitValues', await rcon('execute as @e[type=minecraft:mannequin] run data get entity @s BukkitValues'))
  const total = await count('@e[type=minecraft:mannequin]')
  const standIns = await count('@e[type=minecraft:mannequin,nbt={BukkitValues:{"craftov:role":"replay"}}]')
  const corpses = await count('@e[type=minecraft:mannequin,nbt={BukkitValues:{"craftov:role":"container"}}]')
  check('the replay spawned stand-ins tagged craftov:role "replay"', standIns >= 3, `${standIns} stand-ins`)
  check('every mannequin carries craftov:role', total > 0 && total === standIns + corpses, `${total} mannequins, ${standIns} replay, ${corpses} container`)
  // The corpse is spawned for everyone at the death; the stand-ins come 1.5 s later, with the title.
  const titled = b.titles.find(t => t.text.includes('击杀回放')).at
  const since = (bot) => [...bot.mannequins].filter(([, at]) => at >= titled - 500).map(([id]) => id)
  check('B is sent the stand-ins and its camera follows one', since(b).length >= 3 && b.cameras.some(id => since(b).includes(id)),
    `${since(b).length} spawned, cameras ${b.cameras.join(',')}`)
  check('nobody else is sent a stand-in', since(a).length === 0 && since(c).length === 0, `A ${since(a).length}, C ${since(c).length}`)

  // C fires at bare floor, away from everyone; C's own stand-in is around C's eye.
  const floor = await fire(c, c.entity.position.offset(4, 0, 2), 3)
  log('C heard (floor)', floor.join(' '))
  check('C fired at the floor', floor.some(s => /gun\.[a-z0-9_]+\.fp/.test(s)))
  check('shooting the floor gives no hit feedback', !floor.some(s => s.startsWith('craftov:hit.')))
  check('shooting the floor makes no body impact', !floor.some(s => /impact\.(flesh|armor|helmet)/.test(s)))
  check('the floor is hit', floor.some(s => s.startsWith('craftov:impact.') && !/impact\.(flesh|armor|helmet|ricochet)/.test(s)))

  // C fires at A.
  const before = a.health
  const atA = await fire(c, a.entity.position.offset(0, 1.2, 0), 2)
  await sleep(300)
  log('C heard (A)', atA.join(' '), 'A health', before, '->', a.health)
  check('shooting A hurts A', a.health < before, `${before} -> ${a.health}`)
  check('shooting A gives hit feedback', atA.some(s => s.startsWith('craftov:hit.')))
  const still = await count('@e[type=minecraft:mannequin]') - await count('@e[type=minecraft:mannequin,nbt={BukkitValues:{"craftov:role":"container"}}]')
  check('the replay was still playing during the shots', still >= 3, `${still} stand-ins left`)
  await done([a, b, c])
}


const AI = ['zombie', 'husk', 'drowned']

/** Fires at an entity until the shooter hears a hit (or gives up); returns what it heard. */
async function hitUntil (bot, find, dy, tries = 24) {
  const from = bot.sounds.length
  for (let i = 0; i < tries && !soundsSince(bot, from).some(s => s.startsWith('craftov:hit.')); i++) {
    const target = find()
    if (!target) { await sleep(250); continue }
    await bot.lookAt(target.position.offset(0, dy, 0), true)
    await sleep(100)
    bot.swingArm()
    await sleep(250)
    if (i === 12) { bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(3000) }
  }
  await sleep(300)
  return soundsSince(bot, from)
}

async function targets () {
  const t = await make('T')
  await sleep(2500)
  await rcon(`op ${t.username}`)
  // The private range: in through the lobby rack, then shoot the nearest training dummy.
  await rcon(`execute as ${t.username} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = t.nearestEntity(e => e.name === 'interaction')
  if (rack) t.activateEntity(rack)
  const ranged = await waitFor(() => t.worldName.includes('range'), 8000)
  check('the bot enters the private range', ranged, t.worldName)
  if (ranged) {
    await sleep(1000)
    check('the bot holds an M4C on the range', await arm(t))
    const dummy = () => t.nearestEntity(e => e.name === 'zombie')
    const heard = await hitUntil(t, dummy, 1.2)
    check('a training dummy takes hits', heard.some(s => s.startsWith('craftov:hit.')), heard.filter(s => /hit\.|impact\./.test(s)).join(' '))
  }
  t.chat('/cv lobby')
  await waitFor(() => t.worldName === 'craftov_lobby', 8000)
  await sleep(2500)

  // The proving ground, alone in spawn bay 1.
  const older = await raids()
  t.chat('/cv queue proving NORMAL SOLO')
  const landed = await waitFor(() => inRaid(t), 120000, 500)
  ours.push(...(await raids()).filter(id => !older.includes(id)))
  check('the bot lands on the proving ground', landed, `${t.worldName}/${t.game.gameMode}`)
  if (!landed) return done([t])
  const world = t.worldName
  await sleep(3500)
  await clear(world)
  check('the bot holds an M4C in the raid', await arm(t))
  await rcon(`execute as ${t.username} at @s run tp @s 15.5 65 88.5 0 0`)
  await sleep(1000)

  // Enemy operators, brought 8 blocks in front of the bot.
  log('event', await rcon('cv event operators'))
  await sleep(500)
  await rcon(`execute as ${t.username} at @s run tp @e[type=minecraft:drowned,distance=..120] ~ ~ ~8`)
  await sleep(800)
  const health = async () => [...(await rcon(`execute at ${t.username} as @e[type=minecraft:drowned,distance=..30] run data get entity @s Health`))
    .matchAll(/data: ([0-9.]+)f/g)].reduce((sum, m) => sum + Number(m[1]), 0)
  const before = await health()
  const soldier = () => t.nearestEntity(e => AI.includes(e.name))
  const heardAi = await hitUntil(t, soldier, 1.2)
  const after = await health()
  check('a soldier takes hits', heardAi.some(s => s.startsWith('craftov:hit.')) && after < before,
    `health ${before} -> ${after}; ${heardAi.filter(s => /hit\.|impact\./.test(s)).join(' ')}`)
  await clear(world)

  // A recon drone, brought 6 blocks in front of the bot and a little up.
  log('event', await rcon('cv event drone'))
  await sleep(500)
  await rcon(`execute as ${t.username} at @s run tp @e[type=minecraft:bee,distance=..300] ~ ~3 ~6`)
  await sleep(300)
  const drone = () => t.nearestEntity(e => e.name === 'bee')
  const heardDrone = await hitUntil(t, drone, 0.3)
  check('a drone takes hits', heardDrone.some(s => s.startsWith('craftov:hit.')), heardDrone.filter(s => /hit\.|impact\./.test(s)).join(' '))
  await done([t])
}

/** Removes soldiers and drones around the proving ground's bays. */
async function clear (world) {
  for (const type of [...AI, 'bee']) {
    await rcon(`execute in minecraft:${world} run kill @e[type=minecraft:${type},x=60,y=65,z=50,distance=..200]`)
  }
}

async function done (bots) {
  for (const id of ours.splice(0)) { try { await rcon(`cv end ${id}`) } catch (e) {} }
  for (const bot of bots) bot.quit()
  await sleep(1500)
}

(async () => {
  const scenarios = { replay, targets }
  const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(scenarios)
  for (const name of wanted) {
    log(`--- ${name}`)
    await scenarios[name]()
  }
})().catch(e => { log('ERROR', e.stack || e.message); failures.push('exception') }).finally(() => {
  log(failures.length ? `FAILED: ${failures.join('; ')}` : 'ALL PASSED')
  process.exit(failures.length)
})
