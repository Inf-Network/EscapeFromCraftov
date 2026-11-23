// Gun reports as each listener receives them (issue #2). Two bots enter the proving ground and stand
// on barrier pads high above it, where nothing is between them; a third, fresh shooter is used for
// the variant deal. Everything is read from the sound packets the bots receive (key, volume, seed).
//
//   volume   the shooter fires every gun once: its own report (gun.<id>.fp) comes at the volume
//            weapons.audio.shooter-volume gives the gun's items.yml volume (0.55..1.0, within the
//            +-1 dB shot-to-shot variation), and adding each fp file's loudness (audio_sources.json)
//            the order is p18 < sv9 < m4c < dm14 < awr.
//   bands    the listener stands 10..135 blocks from a P18: near, mid and far arrive with weights
//            (volume squared, normalised) that match weapons.audio.near-mid / mid-far (20-32,
//            70-110) within 0.06, their energy adds up to what one report would have (the weights sum
//            to 1) within the 1 dB variation, and they change gradually from one distance to the next.
//            A suppressed P18 (suppressor written onto the gun) crossfades near_s into mid_s instead
//            and is out of earshot (or mid_s only) at 80 blocks.
//   wall     a stone wall between them at 15 blocks: gun.p18.muff and gun.p18.near arrive together,
//            the open report at 0.2 / 0.75 of the muffled one.
//   room     a stone room around both: every shot brings gun.tail.light.in or .in2 (alternating) in
//            the same tick as the report, each after a stop of its own key, for shooter and listener.
//   cover    a closed stone room around a DM14, the listener outside at 40..130 blocks: the room
//            arrives muffled (gun.tail.heavy.in_muff / in_muff2, never the bright .in / .in2), the
//            muffled report and room against the open report follow the fade over mid-far (70-110),
//            the muffled share changes gradually and from 110 blocks on nothing is muffled.
//   deal     a fresh shooter fires the P18 twelve times and the SV9 nine automatic samples: the
//            variant the client picks from each seed (java.util.Random(seed).nextInt(n)) never starts
//            on the recorded shot the one before ended on (src/main/resources/gun_variants.json), for
//            the shooter's report and the listener's, and every variant comes equally often.
//
//   PORT=25565 RCON_PORT=25575 node gunaudio.js [volume bands wall room cover deal]
//
// Needs test.enabled (the proving ground) and a short match.wait-seconds; the bots are made op and
// deopped at the end. Exit code: number of failed checks (2+ also when the setup failed).
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const ROOT = path.join(__dirname, '../..')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['volume', 'bands', 'wall', 'room', 'cover', 'deal']
const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''}`)
}
const bots = []

// ---------------------------------------------------------------- what the server should do
const config = (() => {
  const yaml = fs.readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
  const pair = key => { const m = yaml.match(new RegExp(`\\n\\s+${key}: \\[([\\d.]+), ([\\d.]+)\\]`)); return m ? [Number(m[1]), Number(m[2])] : null }
  return { shooter: pair('shooter-volume'), nearMid: pair('near-mid'), midFar: pair('mid-far') }
})()
const guns = (() => {
  const out = {}
  let id = null
  for (const line of fs.readFileSync(path.join(ROOT, 'src/main/resources/items.yml'), 'utf8').split('\n')) {
    const head = line.match(/^ {2}([a-z0-9_]+):/)
    if (head) id = head[1]
    const vol = line.match(/ volume: ([\d.]+)/)
    if (vol && id && /action: /.test(line)) out[id] = Number(vol[1])
  }
  return out
})()
const sources = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools/art/audio_sources.json'), 'utf8'))
/** Per sound event, the recorded shot of each round of each variant (what GunAudio deals by). */
const variantShots = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/gun_variants.json'), 'utf8'))
/** Whether variant b of an event may follow variant a: it does not start on the shot a ended on. */
const follows = (rows, a, b) => rows[b][0] !== rows[a][rows[a].length - 1]
const clamp = (x, a, b) => Math.max(a, Math.min(b, x))
const shooterVolume = gid => clamp(config.shooter[0] + clamp((guns[gid] - 3.5) / 3.5, 0, 1) * (config.shooter[1] - config.shooter[0]), 0, 1)
const ramp = (d, a, b) => b <= a ? (d < a ? 0 : 1) : clamp((d - a) / (b - a), 0, 1)
function bandWeights (d) {
  const toMid = ramp(d, config.nearMid[0], config.nearMid[1])
  const toFar = ramp(d, Math.max(config.midFar[0], config.nearMid[1]), Math.max(config.midFar[1], config.nearMid[1]))
  return [1 - toMid, toMid * (1 - toFar), toMid * toFar]
}
/** GunAudio.gain for an open report: items.yml volume / 5 capped at 1.2, halved at 30 x that blocks. */
const openGain = (gid, d) => Math.min(1.2, guns[gid] / 5) / (1 + Math.pow(d / Math.max(4, 30 * guns[gid] / 5), 1.5))
const AUDIBLE = 0.012
const PROXY_ATTENUATION = 1 - 4 / 16

/** The variant a vanilla client picks for this packet seed out of n: java.util.Random(seed).nextInt(n). */
function variantOf (seed, n) {
  const MASK = (1n << 48n) - 1n
  let s = (BigInt.asUintN(64, seed) ^ 0x5DEECE66Dn) & MASK
  const next = bits => { s = (s * 0x5DEECE66Dn + 0xBn) & MASK; return Number(BigInt.asIntN(32, s >> BigInt(48 - bits))) }
  if ((n & -n) === n) return Number((BigInt(n) * BigInt(next(31))) >> 31n)
  let bits, val
  do { bits = next(31); val = bits % n } while (((bits - val + (n - 1)) | 0) < 0)
  return val
}
const toBig = v => typeof v === 'bigint' ? v : Array.isArray(v) ? (BigInt(v[0]) << 32n) | BigInt(v[1] >>> 0) : BigInt(v || 0)

// ---------------------------------------------------------------- bots
function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.heard = []
  const keyOf = p => (JSON.stringify(p.sound || p.soundId || p) .match(/craftov:[a-z0-9_.\/]+/) || [null])[0]
  const onSound = p => {
    const key = keyOf(p)
    if (key) bot.heard.push({ at: Date.now(), key, volume: p.volume, seed: toBig(p.seed), stop: false })
  }
  bot._client.on('sound_effect', onSound)
  bot._client.on('entity_sound_effect', onSound)
  bot._client.on('stop_sound', p => { const key = keyOf(p); if (key) bot.heard.push({ at: Date.now(), key, stop: true }) })
  bot.bars = []
  bot._client.on('system_chat', p => { if (p.isActionBar) bot.bars.push(JSON.stringify(p.content)) })
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(200) }
  return false
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/"craftov:id":\{"type":"\w+","value":"([a-z0-9_]+)"/); return m ? m[1] : null }
const hotbar = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}
const world = () => bots[0].worldName

/** Stands the bot on a barrier pad at x y z, facing yaw. */
async function stand (bot, x, y, z, yaw = 0) {
  await rcon(`execute in minecraft:${world()} run setblock ${Math.floor(x)} ${y - 1} ${Math.floor(z)} minecraft:barrier`)
  await rcon(`execute in minecraft:${world()} run tp ${bot.username} ${x} ${y} ${z} ${yaw} 0`)
}

/** Empties the inventory, gives the gun (loaded) and selects it; `attach` is written onto it first. */
async function arm (bot, id, attach) {
  // Hold another slot while the gun arrives, so the server reads it (and what is written onto it)
  // only once it is selected.
  bot.setQuickBarSlot(8)
  await sleep(300)
  await rcon(`clear ${bot.username}`)
  await waitFor(() => hotbar(bot, id) < 0, 2000)
  await rcon(`cv give ${bot.username} ${id}`)
  if (!await waitFor(() => hotbar(bot, id) >= 0, 4000)) throw new Error(`${id} did not reach the hotbar`)
  const slot = hotbar(bot, id)
  if (attach) {
    await rcon(`item modify entity ${bot.username} hotbar.${slot - 36} {function:"minecraft:set_custom_data",tag:{PublicBukkitValues:{"craftov:attach":"${attach}"}}}`)
    await sleep(300)
  }
  bot.setQuickBarSlot(slot - 36)
  await sleep(1200) // the draw time
}

let sequence = 1
const dig = (bot, status, cell) => bot._client.write('block_dig', { status, location: cell, face: 1, sequence: sequence++ })
/** The capture block the server put in front of the crosshair (nothing else is near up here). */
async function captureCell (bot) {
  const id = bot.registry.blocksByName.sculk_vein.id
  for (let i = 0; i < 30; i++) {
    const eye = bot.entity.position.offset(0, 1.62, 0)
    const yaw = conv.toNotchianYaw(bot.entity.yaw) * Math.PI / 180
    const pitch = conv.toNotchianPitch(bot.entity.pitch) * Math.PI / 180
    const dir = { x: -Math.sin(yaw) * Math.cos(pitch), y: -Math.sin(pitch), z: Math.cos(yaw) * Math.cos(pitch) }
    let best = null
    let bestMiss = 0.9
    for (const c of bot.findBlocks({ matching: id, maxDistance: 6, count: 16 })) {
      const to = { x: c.x + 0.5 - eye.x, y: c.y + 0.5 - eye.y, z: c.z + 0.5 - eye.z }
      const t = to.x * dir.x + to.y * dir.y + to.z * dir.z
      const miss = Math.hypot(to.x - t * dir.x, to.y - t * dir.y, to.z - t * dir.z)
      if (t > 0 && miss < bestMiss) { best = c; bestMiss = miss }
    }
    if (best) return best
    await sleep(100)
  }
  throw new Error(bot.username + ': no capture block in front of the crosshair')
}
/** One pull of the trigger as the vanilla client sends it: start digging the capture block, swing, abort. */
async function pull (bot) {
  const cell = await captureCell(bot)
  dig(bot, 0, cell)
  bot._client.write('arm_animation', { hand: 0 })
  await sleep(50)
  dig(bot, 1, cell)
}

/** Sounds the bot received from `from` on whose key matches. */
const since = (bot, from, re) => bot.heard.slice(from).filter(h => re.test(h.key))
const db = x => 20 * Math.log10(x)
/**
 * Ear-to-muzzle distance for a listener `d` blocks east of a shooter facing south: the muzzle is 0.9
 * ahead of the eye, 0.22 to its right (west) and 0.18 below (Ballistics.muzzlePoint).
 */
const muzzleDistance = d => Math.hypot(d + 0.22, 0.18, 0.9)

// ---------------------------------------------------------------- scenarios
const SPOT = { x: 40.5, y: 140, z: 40.5 }

async function volume (shooter) {
  const order = []
  for (const gid of Object.keys(guns)) {
    await stand(shooter, SPOT.x, SPOT.y, SPOT.z, 0)
    await arm(shooter, gid)
    const vols = []
    for (let i = 0; i < 3; i++) {
      const from = shooter.heard.length
      await pull(shooter)
      await waitFor(() => since(shooter, from, new RegExp(`^craftov:gun\\.${gid}(\\.x\\d)?\\.fp$`)).length > 0, 2500)
      const fp = since(shooter, from, new RegExp(`^craftov:gun\\.${gid}(\\.x\\d)?\\.fp$`))
      if (fp.length) vols.push(fp[0].volume)
      await sleep(gid === 'awr' ? 1800 : gid === 'r12' ? 1200 : 500)
    }
    const want = shooterVolume(gid)
    const ok = vols.length === 3 && vols.every(v => Math.abs(db(v) - db(want)) <= 1.05 || (want * 1.122 > 1 && v <= 1 && db(v) >= db(want) - 1.05))
    check(`volume: ${gid} (items.yml ${guns[gid]}) is heard by its shooter at ${want.toFixed(3)} +-1 dB`, ok, vols.map(v => v.toFixed(3)).join(' '))
    const files = Object.entries(sources.files).filter(([p]) => new RegExp(`^gun/${gid}/fp_\\d+$`).test(p)).map(([, f]) => f.loudness)
    const loud = files.reduce((a, b) => a + b, 0) / files.length
    order.push({ gid, level: db(want) + loud })
  }
  const level = Object.fromEntries(order.map(o => [o.gid, o.level]))
  const chain = ['p18', 'sv9', 'm4c', 'dm14', 'awr']
  check('volume: the shooter hears p18 < sv9 < m4c < dm14 < awr (playback volume + fp loudness)',
    chain.every((g, i) => i === 0 || level[chain[i - 1]] < level[g]), chain.map(g => `${g} ${level[g].toFixed(1)} dB`).join(', '))
}

/** One P18 shot heard by the listener `d` blocks east of the shooter (who faces south). */
async function heardAt (shooter, listener, d, re) {
  await stand(listener, SPOT.x + d, SPOT.y, SPOT.z, 90)
  await sleep(700)
  const from = listener.heard.length
  await pull(shooter)
  await sleep(600 + d / 17.15 * 50)
  return since(listener, from, re).filter(h => !h.stop)
}

async function bands (shooter, listener) {
  await stand(shooter, SPOT.x, SPOT.y, SPOT.z, 0)
  await arm(shooter, 'p18')
  const rows = []
  for (const d of [10, 18, 21, 24, 26, 28, 31, 34, 50, 68, 72, 80, 90, 100, 108, 115, 135]) {
    const got = await heardAt(shooter, listener, d, /^craftov:gun\.p18\.(near|mid|far)$/)
    const v = ['near', 'mid', 'far'].map(b => got.filter(h => h.key.endsWith('.' + b)).reduce((a, h) => a + h.volume ** 2, 0))
    const total = v[0] + v[1] + v[2]
    const w = v.map(x => total ? x / total : 0)
    const want = bandWeights(muzzleDistance(d))
    const energy = db(Math.sqrt(total)) - db(openGain('p18', muzzleDistance(d)) / PROXY_ATTENUATION)
    rows.push({ d, w, want, energy, n: got.length })
    log(`  ${d} blocks: ${got.map(h => h.key.split('.').pop() + ' ' + h.volume.toFixed(3)).join(', ')}  weights ${w.map(x => x.toFixed(2)).join('/')} (want ${want.map(x => x.toFixed(2)).join('/')}) energy ${energy.toFixed(2)} dB`)
  }
  check('bands: near/mid/far weights match the crossfade at every distance', rows.every(r => r.n > 0 && r.w.every((x, i) => Math.abs(x - r.want[i]) <= 0.06)),
    rows.filter(r => !(r.n > 0 && r.w.every((x, i) => Math.abs(x - r.want[i]) <= 0.06))).map(r => r.d))
  check('bands: the reports of a shot add up to one report (weights sum to 1, within the 1 dB variation)', rows.every(r => Math.abs(r.energy) <= 1.1),
    rows.map(r => r.energy.toFixed(2)).join(' '))
  check('bands: both reports play inside 20-32 and 70-110 blocks, one outside', rows.every(r => {
    const playing = r.w.filter(x => x > 0.001).length
    const inside = (r.d > 20 && r.d < 32) || (r.d > 70 && r.d < 110)
    return inside ? playing === 2 : playing === 1
  }), rows.map(r => `${r.d}:${r.w.filter(x => x > 0.001).length}`).join(' '))
  check('bands: the weights change gradually (no step over 0.35 between neighbouring distances)',
    rows.every((r, i) => i === 0 || r.w.every((x, k) => Math.abs(x - rows[i - 1].w[k]) <= 0.35)))

  // Suppressed: the same P18 with a suppressor written onto it.
  await arm(shooter, 'p18', 'muzzle=att_suppressor')
  const srows = []
  for (const d of [10, 24, 26, 28, 40, 80]) {
    const got = await heardAt(shooter, listener, d, /^craftov:gun\.p18\.(near_s|mid_s|near|mid|far|muff)$/)
    const v = ['near_s', 'mid_s'].map(b => got.filter(h => h.key.endsWith('.' + b)).reduce((a, h) => a + h.volume ** 2, 0))
    const total = v[0] + v[1]
    const want = bandWeights(muzzleDistance(d))
    srows.push({ d, w0: total ? v[0] / total : -1, want0: want[0], open: got.filter(h => !/_s$/.test(h.key)).length })
    log(`  suppressed ${d} blocks: ${got.map(h => h.key.split('.').pop() + ' ' + h.volume.toFixed(3)).join(', ')}`)
  }
  // Far away a suppressed pistol may be below the threshold altogether (w0 -1: nothing arrived).
  check('bands: a suppressed P18 crossfades near_s into mid_s over 20-32 blocks and plays no open report',
    srows.every(r => r.open === 0 && (Math.abs(r.w0 - r.want0) <= 0.06 || (r.w0 < 0 && r.d >= 60))),
    srows.map(r => `${r.d}:${r.w0.toFixed(2)}/${r.want0.toFixed(2)}`).join(' '))
  await arm(shooter, 'p18')
}

async function wall (shooter, listener) {
  await stand(shooter, SPOT.x, SPOT.y, SPOT.z, 0)
  await arm(shooter, 'p18')
  const x = Math.floor(SPOT.x) + 7
  await rcon(`execute in minecraft:${world()} run fill ${x} ${SPOT.y - 3} ${Math.floor(SPOT.z) - 3} ${x} ${SPOT.y + 4} ${Math.floor(SPOT.z) + 3} minecraft:stone`)
  await sleep(500)
  const got = await heardAt(shooter, listener, 15, /^craftov:gun\.p18\.(near|mid|far|muff)$/)
  await rcon(`execute in minecraft:${world()} run fill ${x} ${SPOT.y - 3} ${Math.floor(SPOT.z) - 3} ${x} ${SPOT.y + 4} ${Math.floor(SPOT.z) + 3} minecraft:air`)
  const muff = got.find(h => h.key.endsWith('.muff'))
  const open = got.find(h => h.key.endsWith('.near'))
  check('wall: behind a wall the muffled report and the open one arrive together, the open one at 0.2/0.75 of it',
    !!muff && !!open && Math.abs(open.volume / muff.volume - 0.2 / 0.75) < 0.03 && Math.abs(open.at - muff.at) < 30,
    got.map(h => h.key.split('.').pop() + ' ' + h.volume.toFixed(3)).join(', '))
}

async function room (shooter, listener) {
  const x0 = Math.floor(SPOT.x) - 3
  const z0 = Math.floor(SPOT.z) - 3
  const y0 = SPOT.y - 1
  const box = (block, hollow) => rcon(`execute in minecraft:${world()} run fill ${x0} ${y0} ${z0} ${x0 + 11} ${y0 + 5} ${z0 + 11} minecraft:${block}${hollow ? ' hollow' : ''}`)
  await box('stone', true)
  await stand(shooter, SPOT.x, SPOT.y, SPOT.z, 0)
  await stand(listener, SPOT.x + 5, SPOT.y, SPOT.z, 90)
  await arm(shooter, 'p18')
  await sleep(800)
  const fromS = shooter.heard.length
  const fromL = listener.heard.length
  const shots = []
  for (let i = 0; i < 4; i++) {
    const f = listener.heard.length
    await pull(shooter)
    await sleep(700)
    shots.push(listener.heard.slice(f))
  }
  await box('air', false)
  const okEach = shots.map(hs => {
    const report = hs.find(h => /gun\.p18\.near$/.test(h.key) && !h.stop)
    const tail = hs.find(h => /gun\.tail\.light\.in2?$/.test(h.key) && !h.stop)
    const stop = hs.find(h => h.stop && tail && h.key === tail.key)
    return { ok: !!report && !!tail && !!stop && Math.abs(tail.at - report.at) < 30 && stop.at <= tail.at, tail: tail && tail.key.split('.').pop(), gap: report && tail ? tail.at - report.at : null }
  })
  check('room: under a roof every shot brings the room tail with the report (same tick), after a stop of its key', okEach.every(r => r.ok), okEach)
  check('room: the tail alternates between .in and .in2', okEach.every((r, i) => i === 0 || r.tail !== okEach[i - 1].tail), okEach.map(r => r.tail).join(' '))
  const shooterTails = since(shooter, fromS, /gun\.tail\.light\.in2?$/).filter(h => !h.stop).length
  check('room: the shooter hears the room too', shooterTails === 4, `${shooterTails} tails`)
  log('  listener packets', since(listener, fromL, /./).map(h => (h.stop ? 'stop ' : '') + h.key.replace('craftov:', '')).join(', '))
}

/** A closed room around the shooter: the listener outside hears it through the wall at every distance. */
async function cover (shooter, listener) {
  // Longer to the south, where the shooter faces: the trigger's capture block goes 3 blocks ahead.
  const x0 = Math.floor(SPOT.x) - 4
  const z0 = Math.floor(SPOT.z) - 4
  const y0 = SPOT.y - 1
  const box = (block, hollow) => rcon(`execute in minecraft:${world()} run fill ${x0} ${y0} ${z0} ${x0 + 8} ${y0 + 5} ${z0 + 11} minecraft:${block}${hollow ? ' hollow' : ''}`)
  await box('stone', true)
  await stand(shooter, SPOT.x, SPOT.y, SPOT.z, 0)
  await arm(shooter, 'dm14')
  const dry = 0.2
  const rows = []
  const distances = [40, 60, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 130]
  for (const [i, d] of distances.entries()) {
    // A fresh, loaded DM14 before its ten rounds run out.
    if (i === 8) await arm(shooter, 'dm14')
    const got = await heardAt(shooter, listener, d, /^craftov:gun\.(dm14\.(near|mid|far|muff)|tail\.heavy\.in(_muff)?2?)$/)
    const vol = re => got.filter(h => re.test(h.key)).reduce((a, h) => a + h.volume ** 2, 0) ** 0.5
    const open = vol(/dm14\.(near|mid|far)$/)
    const muff = vol(/dm14\.muff$/)
    const roomMuff = vol(/in_muff2?$/)
    const bright = got.filter(h => /tail\.heavy\.in2?$/.test(h.key)).length
    const md = muzzleDistance(d)
    const close = 1 - bandWeights(md)[2]
    const openShare = dry + (1 - dry) * (1 - close)
    const gain = openGain('dm14', md)
    rows.push({ d, open, muff, roomMuff, bright, close, openShare, gain,
      share: muff ** 2 / (muff ** 2 + open ** 2 || 1), want: (0.75 * close) ** 2 / ((0.75 * close) ** 2 + openShare ** 2) })
    log(`  ${d} blocks: ${got.map(h => h.key.replace('craftov:gun.', '') + ' ' + h.volume.toFixed(3)).join(', ')}`)
  }
  await box('air', false)
  check('cover: every shot is heard (the open report arrives at every distance)', rows.every(r => r.open > 0), rows.filter(r => !(r.open > 0)).map(r => r.d))
  check('cover: the room reaches the listener behind its wall muffled, never bright', rows.every(r => r.bright === 0) && rows[0].roomMuff > 0,
    rows.map(r => `${r.d}:${r.bright ? 'BRIGHT' : r.roomMuff ? 'muff' : '-'}`).join(' '))
  // Both share the shot's level variation, so the ratios are exact; near the threshold a sound may drop out.
  const ratioOk = (got, want, level) => level < 1.5 * AUDIBLE / 2 ? true : got > 0 && Math.abs(got / want - 1) < 0.05
  check('cover: the muffled report against the open one follows the fade (0.75 x close : 0.2 + 0.8 x (1 - close))',
    rows.every(r => r.close <= 0 || ratioOk(r.muff / r.open, 0.75 * r.close / r.openShare, r.gain * 0.75 * r.close)),
    rows.map(r => `${r.d}:${(r.muff / r.open).toFixed(3)}/${(0.75 * r.close / r.openShare).toFixed(3)}`).join(' '))
  check('cover: the muffled room against the open report follows the same fade',
    rows.every(r => r.close <= 0 || (r.gain * 0.6 * r.close < 1.5 * AUDIBLE ? true : r.roomMuff > 0 && Math.abs(r.roomMuff / r.open / (0.6 * r.close / r.openShare) - 1) < 0.05)),
    rows.map(r => `${r.d}:${(r.roomMuff / r.open).toFixed(3)}/${(0.6 * r.close / r.openShare).toFixed(3)}`).join(' '))
  check('cover: from the far end of mid-far on, cover changes nothing (no muffled report or room)',
    rows.filter(r => r.close <= 0).every(r => r.muff === 0 && r.roomMuff === 0) && rows.some(r => r.close <= 0),
    rows.filter(r => r.close <= 0).map(r => r.d))
  check('cover: the muffled share changes gradually (no step over 0.3 between neighbouring distances)',
    rows.every((r, i) => i === 0 || Math.abs(r.share - rows[i - 1].share) <= 0.3),
    rows.map(r => `${r.d}:${r.share.toFixed(2)}`).join(' '))
}

/** The dealer fires `shots` reports of a gun; the deal is checked by recorded shot, for its report and the listener's. */
async function dealGun (dealer, listener, gid, shots) {
  await arm(dealer, gid)
  const x = guns[gid] && /^(m4c|sv9|v57)$/.test(gid) ? `.x${gid === 'm4c' ? 2 : 3}` : ''
  const keys = { fp: `gun.${gid}${x}.fp`, near: `gun.${gid}${x}.near` }
  const picks = { fp: [], near: [] }
  for (let i = 0; i < shots; i++) {
    const from = { fp: dealer.heard.length, near: listener.heard.length }
    await pull(dealer)
    await sleep(450)
    for (const [band, bot] of [['fp', dealer], ['near', listener]]) {
      const h = bot.heard.slice(from[band]).find(e => !e.stop && e.key === 'craftov:' + keys[band])
      picks[band].push(h ? variantOf(h.seed, variantShots[keys[band]].length) : null)
    }
  }
  for (const band of ['fp', 'near']) {
    const rows = variantShots[keys[band]]
    const seq = picks[band]
    const counts = rows.map((_, i) => seq.filter(v => v === i).length)
    const shotsHeard = seq.map(v => v === null ? '?' : rows[v].join('+')).join(' ')
    check(`deal: ${keys[band]} never starts on the recorded shot the one before ended on`,
      seq.every((v, i) => v !== null && (i === 0 || follows(rows, seq[i - 1], v))), `variants ${seq.join('')} shots ${shotsHeard}`)
    check(`deal: each of the ${rows.length} variants of ${keys[band]} comes ${shots / rows.length} times in ${shots}`,
      counts.every(c => c === shots / rows.length), counts.join(','))
  }
}

async function deal (dealer, listener) {
  await stand(dealer, SPOT.x, SPOT.y, SPOT.z + 3, 0)
  await stand(listener, SPOT.x + 8, SPOT.y, SPOT.z + 3, 90)
  await dealGun(dealer, listener, 'p18', 12)
  // Automatic: every pull fires a whole three-round sample.
  await dealGun(dealer, listener, 'sv9', 9)
}

// ---------------------------------------------------------------- main
async function finish (code) {
  for (const bot of bots) { await rcon(`deop ${bot.username}`).catch(() => {}); bot.quit() }
  await sleep(500)
  log(failures.length ? `FAIL ${failures.length}: ${failures.join('; ')}` : 'PASS all checks')
  process.exit(code === undefined ? failures.length : Math.max(code, failures.length))
}

;(async () => {
  const shooter = await make(`ga${run}s`)
  const listener = await make(`ga${run}l`)
  // A shooter that fires nothing before the deal, so its first shot starts a fresh round.
  const dealer = await make(`ga${run}d`)
  for (const bot of bots) await rcon(`op ${bot.username}`)
  for (const bot of bots) bot.chat('/cv queue proving NORMAL SOLO')
  log('queued; waiting for the raid')
  if (!await waitFor(() => bots.every(inRaid), 150000) || bots.some(b => b.worldName !== bots[0].worldName)) {
    log('SETUP: the bots never reached the same raid', bots.map(b => b.worldName))
    return finish(2)
  }
  await sleep(7000) // insertion camera and spawn protection
  for (const bot of bots) await rcon(`effect give ${bot.username} minecraft:resistance 900 4 true`)
  await clearSoldiers()
  try {
    for (const scenario of scenarios) {
      await clearSoldiers()
      if (scenario === 'volume') await volume(shooter)
      else if (scenario === 'bands') await bands(shooter, listener)
      else if (scenario === 'wall') await wall(shooter, listener)
      else if (scenario === 'room') await room(shooter, listener)
      else if (scenario === 'cover') await cover(shooter, listener)
      else if (scenario === 'deal') await deal(dealer, listener)
    }
  } catch (e) {
    log('ERROR', e.stack || e.message)
    return finish(2)
  }
  return finish()
})()
