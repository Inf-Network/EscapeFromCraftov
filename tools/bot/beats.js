// Repeating effects that come round on a beat of their own (issue #40), with two bots in a squad on
// the proving ground: A, the one everything happens to, and its mate M, who stays out of the way
// until the revive. The beats used to be read off the server tick (`getCurrentTick() % N == 0`) in
// timers that never ran on such a tick, so some never came at all.
//
//   markers  standing beside the extract strip, A gets the extract markers as DUST particles,
//            about every 10 ticks per extract: a ring of 24 at the extract's radius (2.5) and, the
//            extract being open to use this raid, a beam of 8 at its centre. Green where it can be
//            used now (always, paid, no-backpack, limited), orange where it cannot yet (timed: two
//            minutes in; switch: until the lever is pulled).
//   switch   A pulls the proving ground's lever: the switch extract's marker turns green.
//   heart    RCON damage takes A to 18-35 HP: it hears craftov:status.heartbeat about once a
//            second (10 in 10 s); under 18 HP twice a second (20 in 10 s); healed to full, none.
//   timed    two minutes into the raid the timed extract's marker has turned green.
//   downed   A takes a lethal hit and is knocked down (M is up to revive it): the downed heartbeat
//            (DownedService) about once a second, and the low-health one not on top of it.
//   revive   M holds right click on A until A is up (downed.revive-seconds, 5): M hears the bandage
//            being wrapped (craftov:med.wrap) about once a second, 4 to 6 times.
//
//   PORT=25565 RCON_PORT=25575 [RCON_PASSWORD=craftov-local] node beats.js [scenario ...]
//
// Server config: test.enabled true (the proving ground is a test map, and the heal button of the test
// armory; the bots are also made op), match.wait-seconds short (5). Soldiers are killed so they keep
// out of it. The bots leave the raid by disconnecting. Exit code: number of failed checks (2+ also
// when the setup failed).
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const wanted = process.argv.slice(2)
const ORDER = ['markers', 'switch', 'heart', 'timed', 'downed', 'revive']
const scenarios = wanted.length ? wanted : ORDER
const failures = []
const check = (name, ok, got) => { if (!ok) failures.push(name); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }

// The proving ground's extract strip (ProvingGroundGenerator.extractStrip): pads at x 106, z 8 + 9i.
const GREEN = 0x50E678
const ORANGE = 0xFFAA3C
const EXTRACTS = [
  ['t_always', GREEN], ['t_timed', ORANGE], ['t_paid', GREEN], ['t_switch', ORANGE], ['t_nopack', GREEN], ['t_limited', GREEN]
].map(([id, color], i) => ({ id, color, x: 106.5, y: 65, z: 8.5 + i * 9, radius: 2.5 }))

let bot // A
let mate // M
let raidAt = 0
function make (name) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  b.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) b.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  b._client.on('login', track)
  b._client.on('respawn', track)
  b._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) b._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  b.heard = []     // { at, name }
  b.dust = []      // { at, x, y, z, color }
  const hear = p => {
    const m = JSON.stringify(p.sound || p).match(/craftov:[a-z0-9_.\/]+/)
    if (m) b.heard.push({ at: Date.now(), name: m[0] })
  }
  b._client.on('sound_effect', hear)
  b._client.on('entity_sound_effect', hear)
  b._client.on('world_particles', p => {
    if (!p.particle || p.particle.type !== 'dust') return
    b.dust.push({ at: Date.now(), x: p.x, y: p.y, z: p.z, color: p.particle.data.color & 0xFFFFFF })
  })
  b.titles = []
  b._client.on('set_title_text', p => b.titles.push(JSON.stringify(p.text)))
  b.on('kicked', r => log(name, 'KICKED', r))
  b.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => b.once('spawn', async () => { await sleep(2500); resolve(b) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(250) }
  return false
}

const inRaid = b => b.worldName.startsWith('craftov_raid_') && b.game.gameMode === 'adventure'
const tp = (b, x, y, z, yaw = 0, pitch = 0) => rcon(`execute as ${b.username} at @s run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)
const clearSoldiers = async () => { for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`) }
async function health () {
  const out = await rcon(`data get entity ${bot.username} Health`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)f?\s*$/)
  return m ? Number(m[1]) : null
}

/** Full health and no wounds: the test armory's "满血并清除伤病" button (slot 46 of /cv items). */
async function restore () {
  const window = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    bot.chat('/cv items')
  })
  if (!window) return false
  await bot.clickWindow(46, 0, 0)
  await sleep(250)
  bot.closeWindow(window)
  await sleep(250)
  return (await health()) === 100
}

/** Heartbeats A hears in the next `ms`. */
async function heartbeats (ms) {
  const from = Date.now()
  await sleep(ms)
  return bot.heard.filter(h => h.at >= from && h.name === 'craftov:status.heartbeat').length
}

/** The particles of `dust` that mark extract `e` (its ring and its beam). */
function around (dust, e) {
  return dust.filter(d => Math.hypot(d.x - e.x, d.z - e.z) < e.radius + 0.5 && d.y > e.y - 1 && d.y < e.y + 12)
}
/** DUST particles A gets in the next `ms`. */
async function dustFor (ms) {
  const from = Date.now()
  await sleep(ms)
  return bot.dust.filter(d => d.at >= from)
}
const colours = dust => [...new Set(dust.map(d => d.color.toString(16)))]
const extract = id => EXTRACTS.find(e => e.id === id)

async function markers () {
  // East of the pads, between them and the signs on the perimeter wall.
  await tp(bot, 112.5, 65, 31, 90)
  await sleep(1500)
  const dust = await dustFor(5000)
  log('dust particles in 5 s', dust.length)
  for (const e of EXTRACTS) {
    const near = around(dust, e)
    const ring = near.filter(d => Math.abs(d.y - (e.y + 0.15)) < 0.01 && Math.hypot(d.x - e.x, d.z - e.z) > 1)
    const beam = near.filter(d => Math.hypot(d.x - e.x, d.z - e.z) < 0.01)
    const radii = ring.map(d => Math.hypot(d.x - e.x, d.z - e.z))
    const colors = colours(near)
    // 5 s is 10 draws of every 10 ticks; a little slack for the window's edges and the server's pace.
    check(`markers: ${e.id} draws its ring about every 10 ticks (24 particles a draw)`,
      ring.length % 24 === 0 && ring.length / 24 >= 8 && ring.length / 24 <= 12, { particles: ring.length, draws: ring.length / 24 })
    check(`markers: ${e.id}'s ring is as wide as the extract (radius ${e.radius})`,
      radii.length > 0 && radii.every(r => Math.abs(r - e.radius) < 0.01), { min: Math.min(...radii), max: Math.max(...radii) })
    check(`markers: ${e.id}, open this raid, has its beam (8 a draw, as often as the ring)`,
      beam.length > 0 && beam.length === ring.length / 3, { beam: beam.length, ring: ring.length })
    check(`markers: ${e.id} is ${e.color === GREEN ? 'green (usable now)' : 'orange (not yet)'}`,
      colors.length === 1 && colors[0] === e.color.toString(16), colors)
  }
}

async function pullSwitch () {
  // The lever stands on a post west of the switch extract's pad (x 97, z 35).
  await tp(bot, 98.5, 65, 35.5, 90)
  await sleep(1000)
  const lever = bot.blockAt(new Vec3(97, 66, 35))
  if (!lever || lever.name !== 'lever') {
    log('SETUP: no lever at 97 66 35', lever && lever.name)
    return 2
  }
  const e = extract('t_switch')
  check('switch: before the lever the switch extract is orange', colours(around(await dustFor(1500), e)).join() === ORANGE.toString(16))
  await bot.activateBlock(lever)
  await sleep(1000)
  const after = colours(around(await dustFor(1500), e))
  check('switch: with the lever pulled the switch extract turns green', after.join() === GREEN.toString(16), after)
  return 0
}

async function timed () {
  const wait = raidAt + 123000 - Date.now() // opens 120 s after the raid started, which was before the bots got in
  if (wait > 0) {
    log(`waiting ${(wait / 1000).toFixed(0)} s for the timed extract to open`)
    await sleep(wait)
  }
  await tp(bot, 112.5, 65, 31, 90)
  await sleep(1000)
  const now = colours(around(await dustFor(1500), extract('t_timed')))
  check('timed: two minutes in the timed extract has turned green', now.join() === GREEN.toString(16), now)
  return 0
}

async function heart () {
  if (!await restore()) {
    log('SETUP: the heal button did not bring A to 100')
    return 2
  }
  check('heart: at full health no heartbeat', await heartbeats(3000) === 0)
  await rcon(`damage ${bot.username} 70 minecraft:generic`)
  await sleep(1000)
  let hp = await health()
  check('heart: RCON damage 70 leaves 18-35 HP', hp >= 18 && hp < 35, hp)
  let beats = await heartbeats(10000)
  check('heart: at 18-35 HP about one heartbeat a second (10 in 10 s)', beats >= 9 && beats <= 11, { hp, beats })
  await rcon(`damage ${bot.username} 15 minecraft:generic`)
  await sleep(1000)
  hp = await health()
  check('heart: another 15 leaves under 18 HP', hp > 0 && hp < 18, hp)
  beats = await heartbeats(10000)
  check('heart: under 18 HP about two heartbeats a second (20 in 10 s)', beats >= 18 && beats <= 22, { hp, beats })
  if (!await restore()) {
    log('SETUP: the heal button did not bring A back to 100')
    return 2
  }
  await sleep(1000)
  beats = await heartbeats(3000)
  check('heart: healed to full the heartbeat stops', beats === 0, beats)
  return 0
}

const isDown = () => bot.titles.some(t => t.includes('倒地'))

async function downed () {
  await restore()
  await clearSoldiers()
  await tp(bot, 112.5, 65, 31, 0)
  await sleep(500)
  bot.titles.length = 0
  await rcon(`damage ${bot.username} 500 minecraft:generic`)
  if (!await waitFor(isDown, 3000)) {
    log('SETUP: A was not knocked down', bot.titles)
    return 2
  }
  await sleep(1000)
  const beats = await heartbeats(10000)
  check('downed: about one heartbeat a second (10 in 10 s), and not the low-health one on top', beats >= 9 && beats <= 11, beats)
  return 0
}

/** One right click on `target` (a raw interact packet, as revive.js sends). */
const click = (b, target) => b._client.write('use_entity', { target: target.id, mouse: 0, hand: 0, sneaking: false })

async function revive () {
  if (!isDown()) {
    await clearSoldiers()
    await tp(bot, 112.5, 65, 31, 0)
    await sleep(500)
    bot.titles.length = 0
    await rcon(`damage ${bot.username} 500 minecraft:generic`)
    if (!await waitFor(isDown, 3000)) {
      log('SETUP: A was not knocked down', bot.titles)
      return 2
    }
  }
  // M kneels 1.6 blocks south of A, facing it.
  await tp(mate, 112.5, 65, 32.6, 180, 50)
  await sleep(1000)
  const target = mate.players[bot.username] && mate.players[bot.username].entity
  if (!target) {
    log('SETUP: M cannot see A')
    return 2
  }
  bot.titles.length = 0
  const from = Date.now()
  let upAt = null
  while (Date.now() - from < 9000 && upAt === null) {
    click(mate, target)
    await sleep(150)
    if (bot.titles.some(t => t.includes('救起'))) upAt = Date.now()
  }
  const wraps = mate.heard.filter(h => h.at >= from && h.at <= (upAt || Date.now()) && h.name === 'craftov:med.wrap')
  const gaps = wraps.slice(1).map((w, i) => (w.at - wraps[i].at) / 1000)
  check('revive: M brings A up', upAt !== null, upAt === null ? bot.titles : ((upAt - from) / 1000).toFixed(1) + ' s')
  check('revive: M hears the bandage wrapped about once a second (4-6 times in the 5 s)',
    wraps.length >= 4 && wraps.length <= 6 && gaps.every(g => g > 0.7 && g < 1.3), { wraps: wraps.length, gaps })
  return 0
}

;(async () => {
  const tag = Math.random().toString(36).slice(2, 7)
  bot = await make('BeatA' + tag)
  mate = await make('BeatM' + tag)
  for (const b of [bot, mate]) await rcon(`op ${b.username}`)
  bot.chat(`/cv party invite ${mate.username}`)
  await sleep(800)
  mate.chat('/cv party accept')
  await sleep(800)
  bot.chat('/cv queue proving NORMAL TRIO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => inRaid(bot) && inRaid(mate), 150000)) {
    log('SETUP: the bots never reached a raid', bot.worldName, mate.worldName)
    return finish(2)
  }
  raidAt = Date.now()
  await sleep(7000) // insertion camera and spawn protection
  await clearSoldiers()
  const RUN = { markers, switch: pullSwitch, heart, timed, downed, revive }
  for (const name of ORDER.filter(s => scenarios.includes(s))) {
    log('---', name)
    const code = await RUN[name]()
    if (code) return finish(code)
  }
  finish(failures.length)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  log(failures.length ? `FAILED: ${failures.join('; ')}` : 'all checks passed')
  for (const b of [bot, mate]) {
    if (!b) continue
    try { await rcon(`deop ${b.username}`) } catch (e) { }
    try { b.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
