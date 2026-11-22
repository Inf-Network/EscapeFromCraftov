// Kill credit and hit feedback (issue #29), with bots on the proving ground.
//
// squad: an enemy S (solo) against V and M in a party (TRIO), all in one raid, friendly fire off:
//   1. control: M, standing, falls 10 blocks and breaks a leg (the fall check below can see one);
//   2. S shoots V once, V is given a heavy chest bleed (`cv injure`) and bleeds until it goes down,
//      over 30 s after the shot: S is told it knocked V down;
//   3. S shoots the crawling V: a plain body or head hit sound, neither the kill nor the knock-down;
//   4. V, down, is lifted 10 blocks and falls: no fracture; M revives V, still no fracture;
//   5. S shoots V (full health) down: the shot that downs it plays craftov:hit.knock and no shot
//      plays craftov:hit.kill;
//   6. V, down again, falls 10 blocks and M's frag goes off at its feet: V loses no bleed-out time;
//      V bleeds out and the kill is S's (kill feed, "✖ 击杀" for S, none for M, V's death title);
//   7. S shoots M dead (nobody left standing to revive it): the killing shot plays craftov:hit.kill,
//      M's report shows no player kills;
//   8. S, at 20 health, blows itself up: "阵亡（破片手雷）", and its report shows the two kills only.
// solo: two solo bots A and B:
//   9. A shoots B once, B is given a heavy chest bleed and bleeds out, over 30 s after the shot:
//      B's death reads "被 A 击杀（失血）", A gets "✖ 击杀", the kill feed names A.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node credit.js [squad|solo]
//
// Both scenarios run when none is named. The bots are made op (test map) and deopped when done;
// soldiers are killed every few seconds so they do not join in. A match launches after
// match.wait-seconds (30 by default). Exit code 0 when every check passes, 1 when one fails, 2 when
// the scenario could not be set up.
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const rcon = cmd => rconRaw(cmd, RCON)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []
const WHICH = process.argv[2] || 'all'

/** Plain text of an NBT text component. */
function plain (text) {
  let out = ''
  const walk = node => {
    if (node === null || node === undefined) return
    if (typeof node === 'string' || typeof node === 'number') { out += node; return }
    if (Array.isArray(node)) { node.forEach(walk); return }
    if (node.text !== undefined) walk(String(node.text))
    if (node[''] !== undefined) walk(String(node['']))
    if (node.extra) walk(node.extra)
  }
  try { walk(nbt.simplify(text)) } catch (e) { out = JSON.stringify(text) }
  return out
}

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.shots = 0
  bot.sounds = []
  const heard = s => {
    if (/^craftov:gun\.[a-z0-9_]+\.fp(_s)?$/.test(s)) bot.shots++
    bot.sounds.push({ at: Date.now(), name: s })
  }
  for (const packet of ['sound_effect', 'entity_sound_effect']) {
    bot._client.on(packet, p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_.\/]+/); if (m) heard(m[0]) })
  }
  bot.bars = []
  const bar = text => bot.bars.push({ at: Date.now(), text: plain(text) })
  bot._client.on('action_bar', p => bar(p.text))
  bot._client.on('system_chat', p => { if (p.isActionBar) bar(p.content) })
  bot.titles = []
  bot._client.on('set_title_text', p => bot.titles.push(plain(p.text)))
  bot._client.on('set_title_subtitle', p => bot.titles.push(plain(p.text)))
  bot.chatLines = []
  bot.on('messagestr', m => bot.chatLines.push(m))
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(100) }
  return false
}

const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const pdc = (item, key) => { const m = JSON.stringify(item && item.components || '').match(new RegExp(`"craftov:${key}":\\{"type":"\\w+","value":"?([^",}]+)`)); return m ? m[1] : null }
const barsSince = (bot, since) => bot.bars.filter(b => b.at >= since).map(b => b.text)
const hitsSince = (bot, since) => bot.sounds.filter(s => s.at >= since && s.name.startsWith('craftov:hit.')).map(s => s.name.slice('craftov:hit.'.length))
const titled = (bot, text) => bot.titles.some(t => t.includes(text))
const chatHas = (bot, ...parts) => bot.chatLines.some(l => parts.every(p => l.includes(p)))

async function dataNumber (target, path) {
  const out = await rcon(`data get entity ${target} ${path}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
const health = name => dataNumber(name, 'Health')

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}
let sweeper = null
const sweep = on => {
  if (sweeper) clearInterval(sweeper)
  sweeper = on ? setInterval(() => clearSoldiers().catch(() => {}), 4000) : null
}

/** Clears the test area around (45, 65, 93) of the proving ground: stone floor, air above, high enough to fall 10 blocks. */
async function clearArea (bot) {
  await rcon(`execute at ${bot.username} run fill 39 64 85 52 64 100 minecraft:stone`)
  await rcon(`execute at ${bot.username} run fill 39 65 85 52 79 100 minecraft:air`)
}

const tp = (bot, x, y, z, yaw = 0, pitch = 0) => rcon(`execute as ${bot.username} at @s run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)
const lift = bot => rcon(`execute as ${bot.username} at @s run tp @s ~ ~10 ~`)

/** Command slot name for a mineflayer inventory window slot (hotbar 36-44, main 9-35). */
const commandSlot = i => i >= 36 ? `hotbar.${i - 36}` : `inventory.${i - 9}`

/** Gives the bot an item and makes sure it sits in hotbar slot `key` (0-8); returns that key. */
async function giveToHotbar (bot, id, key) {
  await rcon(`cv give ${bot.username} ${id}`)
  let i = -1
  await waitFor(() => (i = bot.inventory.slots.findIndex((s, n) => n >= 9 && n <= 44 && s && pdc(s, 'id') === id)) >= 0, 5000)
  if (i < 0) return -1
  if (i !== 36 + key) {
    await rcon(`item replace entity ${bot.username} hotbar.${key} from entity ${bot.username} ${commandSlot(i)}`)
    await rcon(`item replace entity ${bot.username} ${commandSlot(i)} with minecraft:air`)
  }
  return await waitFor(() => pdc(bot.inventory.slots[36 + key], 'id') === id, 5000) ? key : -1
}

/** Selects the K47 in the bot's hotbar; false when it has none. */
async function holdGun (bot) {
  const slot = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && pdc(s, 'id') === 'k47')
  if (slot < 0) return false
  bot.setQuickBarSlot(slot - 36)
  await sleep(500)
  return true
}

/** Turns the shooter's K47 to semi-auto (F cycles AUTO -> SEMI). */
async function semi (bot) {
  for (let i = 0; i < 3 && !bot.bars.some(b => b.text.includes('单发')); i++) {
    bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await sleep(600)
  }
  return bot.bars.some(b => b.text.includes('单发'))
}

/** One right click on `target` (a raw interact packet). */
const click = (bot, target) => bot._client.write('use_entity', { target: target.id, mouse: 0, hand: 0, sneaking: bot.getControlState('sneak') })

/** Holds right click on `target` for `ms`, the way a held button repeats (every 150 ms here). */
async function holdOn (bot, target, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    click(bot, target)
    await sleep(150)
  }
}

/**
 * Full health and no wounds: the test armory's "满血并清除伤病" button (slot 46 of /cv items, open
 * to ops). Falls back to instant health.
 */
async function restore (bot) {
  const window = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    bot.chat('/cv items')
  })
  if (window) {
    await bot.clickWindow(46, 0, 0)
    await sleep(250)
    bot.closeWindow(window)
    await sleep(250)
  }
  if ((await health(bot.username)) !== 100) await rcon(`effect give ${bot.username} minecraft:instant_health 1 5`)
}

/**
 * One aimed shot from S at T (`dy` above its feet). Returns the hit sounds S heard for it (body,
 * head, knock, kill), or null when no round left the gun twice (an empty magazine reloads on the
 * first pull).
 */
async function shot (S, T, dy) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const entity = S.players[T.username] && S.players[T.username].entity
    if (!entity) return null
    const aim = entity.position.offset(0, dy, 0)
    for (let i = 0; i < 8; i++) {
      await S.lookAt(aim, true)
      S.activateItem()
      await sleep(100)
    }
    const since = Date.now()
    const shots = S.shots
    S.activateItem()
    S.swingArm()
    await sleep(700)
    if (S.shots > shots) return hitsSince(S, since)
    log('  no round left the gun (reloading?); waiting and firing again')
    await sleep(3500)
  }
  return null
}

/** Throws the frag in hotbar slot `key` at the bot's own feet, crouching (a short lob). */
async function dropFrag (bot, key, at) {
  bot.setQuickBarSlot(key)
  await sleep(300)
  bot.setControlState('sneak', true)
  await bot.lookAt(at, true)
  await sleep(200)
  bot.activateItem()
  await sleep(150)
  bot.deactivateItem()
  bot.setControlState('sneak', false)
}

/** Seconds left on a downed bot's bleed-out, from its latest action bar. */
const bleedOut = bot => { const b = bot.bars.slice().reverse().find(x => /倒地 .*?(\d+)s/.test(x.text)); return b ? Number(b.text.match(/倒地 .*?(\d+)s/)[1]) : null }

/** Waits for the queued bots all to be in the raid, past the insertion camera. */
async function enterRaid (list) {
  if (!await waitFor(() => list.every(inRaid), 150000)) {
    log('SETUP: the bots never reached a raid', list.map(b => b.worldName))
    return false
  }
  await sleep(7000) // insertion camera and spawn protection
  await clearSoldiers()
  return true
}

const fracture = (bot, since) => barsSince(bot, since).some(b => b.includes('骨折'))

// ------------------------------------------------------------------ squad

async function squad () {
  const S = await make(`kc${run}s`)
  const V = await make(`kc${run}v`)
  const M = await make(`kc${run}m`)
  for (const bot of [S, V, M]) await rcon(`op ${bot.username}`)
  M.chat(`/cv party invite ${V.username}`)
  await sleep(800)
  V.chat('/cv party accept')
  await sleep(800)
  M.chat('/cv queue proving NORMAL TRIO')
  S.chat('/cv queue proving NORMAL SOLO')
  log('squad and enemy queued; waiting for the raid')
  if (!await enterRaid([S, V, M])) return 2
  if (S.worldName !== V.worldName || S.worldName !== M.worldName) {
    log('SETUP: the enemy is not in the squad\'s raid', [S.worldName, V.worldName, M.worldName])
    return 2
  }
  sweep(true)
  await clearArea(S)
  for (const bot of [S, V, M]) for (const slot of ['head', 'chest']) await rcon(`item replace entity ${bot.username} armor.${slot} with minecraft:air`)
  if (!await holdGun(S) || !await semi(S)) {
    log('SETUP: the shooter has no K47 or could not switch it to semi-auto')
    return 2
  }
  const mFrag = await giveToHotbar(M, 'frag', 6)
  const sFrag = await giveToHotbar(S, 'frag', 6)
  if (mFrag < 0 || sFrag < 0) {
    log('SETUP: could not give M and S a frag each')
    return 2
  }
  for (const bot of [V, M]) bot.setQuickBarSlot(2) // the knife: swinging it fires nothing
  const vEntity = () => M.players[V.username] && M.players[V.username].entity

  // 1. Control: a standing player falling 10 blocks breaks a leg, and the bot sees it.
  await tp(M, 49.5, 65, 98.5, 0, 0)
  await sleep(800)
  let since = Date.now()
  await lift(M)
  await waitFor(() => fracture(M, since), 4000)
  check('control: M, standing, falls 10 blocks and is told a leg is broken', fracture(M, since), barsSince(M, since).slice(0, 3))

  // 2. One shot, then a heavy chest bleed: V goes down from bleeding, as S's knock-down.
  await tp(S, 45.5, 65, 88.5, 0, 0)
  await tp(V, 45.5, 65, 94.5, 180, 0)
  await tp(M, 50.5, 65, 98.5, 0, 0)
  await sleep(1000)
  since = Date.now()
  const first = await shot(S, V, 1.2)
  const shotAt = Date.now()
  const vHealth = await health(V.username)
  check('S shooting V once: a plain hit sound', first !== null && first.length > 0 && first.every(s => s === 'body' || s === 'head'), { sounds: first, health: vHealth })
  await rcon(`cv injure ${V.username} chest heavy`)
  log(`V at ${vHealth} health, bleeding heavily; waiting for it to go down`)
  await waitFor(() => titled(V, '第一次倒地'), 70000)
  const downAfter = (Date.now() - shotAt) / 1000
  check(`V goes down from the bleeding ${downAfter.toFixed(0)} s after S's shot (over 30 s)`, titled(V, '第一次倒地') && downAfter > 30, V.titles.slice(-2))
  check('S is told it knocked V down', barsSince(S, shotAt).some(b => b.includes('击倒') && b.includes(V.username)),
    barsSince(S, shotAt).filter(b => b.includes('击')).slice(-2))
  await sleep(1000)

  // 3. A shot at the crawling V is a plain hit.
  const onDowned = await shot(S, V, 0.3)
  check('S shooting the crawling V: a plain body or head hit sound, not the kill or knock-down',
    onDowned !== null && onDowned.length > 0 && onDowned.every(s => s === 'body' || s === 'head'), onDowned)

  // 4. V falls 10 blocks while down: no fracture, then or after M revives it.
  since = Date.now()
  await lift(V)
  await sleep(3500)
  check('V, down, falls 10 blocks: no broken leg', !fracture(V, since) && inRaid(V), barsSince(V, since).filter(b => !b.includes('倒地')).slice(0, 3))
  const vPos = vEntity() && vEntity().position
  if (!vPos) {
    log('SETUP: M cannot see V')
    return 2
  }
  await tp(M, vPos.x, 65, vPos.z + 1.6, 180, 50)
  await sleep(800)
  await waitFor(async () => { await holdOn(M, vEntity(), 300); return titled(V, '已被救起') }, 12000)
  await sleep(2500)
  check('M revives V; V has no broken leg (food not held at 6)', titled(V, '已被救起') && !fracture(V, since) && V.food > 6,
    { titles: V.titles.slice(-2), food: V.food })
  await restore(V)
  await tp(V, 45.5, 65, 94.5, 180, 0)
  await tp(M, 50.5, 65, 98.5, 0, 0)
  await sleep(1500)

  // 5. S shoots V down: the shot that downs it plays the knock-down sound, none the kill sound.
  V.titles.length = 0
  since = Date.now()
  const sounds = []
  for (let i = 0; i < 6 && !titled(V, '第二次倒地'); i++) {
    const s = await shot(S, V, 1.2)
    if (s) sounds.push(s)
    await sleep(300)
  }
  log('shots at V until down', sounds)
  const last = sounds[sounds.length - 1] || []
  check('the shot that knocks V down plays craftov:hit.knock', titled(V, '第二次倒地') && last.includes('knock'), sounds)
  check('no shot at V plays craftov:hit.kill, the ones before the knock-down are plain hits',
    !sounds.flat().includes('kill') && sounds.slice(0, -1).flat().every(s => s === 'body' || s === 'head'), sounds)
  check('S is told it knocked V down again', barsSince(S, since).some(b => b.includes('击倒') && b.includes(V.username)))

  // 6. V falls again, M's frag goes off at its feet; V bleeds out as S's kill.
  await sleep(500)
  since = Date.now()
  await lift(V)
  await sleep(3000)
  await tp(S, 39.5, 65, 85.5, 0, 0) // out of the blast
  const at = vEntity() && vEntity().position
  if (!at) {
    log('SETUP: M cannot see V')
    return 2
  }
  await tp(M, at.x, 65, at.z + 2, 180, 60)
  await sleep(800)
  const poolBefore = bleedOut(V)
  const throwAt = Date.now()
  await dropFrag(M, mFrag, at)
  await sleep(250)
  await tp(M, 51.5, 65, 99.5, 0, 0) // out of the blast, still standing for V
  await waitFor(() => V.sounds.some(s => s.at >= throwAt && s.name === 'craftov:throw.explode'), 6000)
  const exploded = V.sounds.some(s => s.at >= throwAt && s.name === 'craftov:throw.explode')
  await sleep(1200)
  const poolAfter = bleedOut(V)
  const took = poolBefore === null || poolAfter === null ? null : poolBefore - poolAfter - (Date.now() - throwAt) / 1000
  check('M\'s frag goes off by the downed V and takes nothing off its bleed-out (friendly fire off)',
    exploded && took !== null && took < 3, { exploded, poolBefore, poolAfter, took: took === null ? null : +took.toFixed(1) })
  check('V, down a second time, falls 10 blocks: no broken leg', !fracture(V, since))
  log('waiting for V to bleed out')
  await waitFor(() => V.game.gameMode === 'spectator', 40000)
  await sleep(1500)
  const feed = S.chatLines.filter(l => l.includes('→') && l.includes(V.username))
  check('V bleeds out', V.game.gameMode === 'spectator', V.game.gameMode)
  check('the kill feed gives V to S, not to M', feed.some(l => l.startsWith(S.username)) && !feed.some(l => l.includes(M.username)), feed)
  check('S gets "✖ 击杀" for V, M does not', barsSince(S, since).some(b => b.includes('✖ 击杀') && b.includes(V.username))
    && !barsSince(M, since).some(b => b.includes('✖ 击杀')), { S: barsSince(S, since).filter(b => b.includes('击杀')), M: barsSince(M, since).filter(b => b.includes('击杀')) })
  check('V\'s death title names S', titled(V, `被 ${S.username} 击杀`), V.titles.slice(-2))
  check('no bounty is claimed for V', !chatHas(S, '领取了', V.username))

  // 7. S shoots M dead: the killing shot plays the kill sound.
  await tp(S, 45.5, 65, 88.5, 0, 0)
  await tp(M, 45.5, 65, 94.5, 180, 0)
  M.titles.length = 0
  M.chatLines.length = 0
  await sleep(1500)
  const mSounds = []
  for (let i = 0; i < 6 && M.game.gameMode !== 'spectator'; i++) {
    const s = await shot(S, M, 1.2)
    if (s) mSounds.push(s)
    await sleep(500)
  }
  log('shots at M until dead', mSounds)
  check('M, with nobody left standing, is shot dead and not knocked down', M.game.gameMode === 'spectator' && !titled(M, '倒地'), M.titles)
  check('the shot that kills M plays craftov:hit.kill, none plays the knock-down',
    (mSounds[mSounds.length - 1] || []).includes('kill') && !mSounds.flat().includes('knock'), mSounds)
  await waitFor(() => M.chatLines.some(l => l.includes('击败干员')), 45000)
  const mReport = M.chatLines.find(l => l.includes('击败干员'))
  check('M\'s report: no player kills (its frag by the downed V counted for nothing)', /击败干员 0\b/.test(mReport || ''), mReport)

  // 8. S at 20 health blows itself up.
  S.titles.length = 0
  S.chatLines.length = 0
  await rcon(`damage ${S.username} 80 minecraft:generic`)
  await sleep(500)
  const sAt = S.entity.position
  await dropFrag(S, sFrag, sAt)
  await waitFor(() => S.game.gameMode === 'spectator', 7000)
  check('S killed by its own frag: "阵亡（破片手雷）"', S.game.gameMode === 'spectator' && titled(S, '阵亡（破片手雷）'), S.titles.slice(-2))
  await waitFor(() => S.chatLines.some(l => l.includes('击败干员')), 30000)
  const sReport = S.chatLines.find(l => l.includes('击败干员'))
  check('S\'s report: the two kills, V and M, and not itself', /击败干员 2\b/.test(sReport || ''), sReport)
  check('S\'s own death claims no bounty', !chatHas(S, '领取了', S.username))
  return 0
}

// ------------------------------------------------------------------ solo

async function solo () {
  const A = await make(`kc${run}a`)
  const B = await make(`kc${run}b`)
  for (const bot of [A, B]) await rcon(`op ${bot.username}`)
  for (const bot of [A, B]) bot.chat('/cv queue proving NORMAL SOLO')
  log('solo queued; waiting for the raid')
  if (!await enterRaid([A, B])) return 2
  sweep(true)
  await clearArea(A)
  for (const slot of ['head', 'chest']) await rcon(`item replace entity ${B.username} armor.${slot} with minecraft:air`)
  if (!await holdGun(A) || !await semi(A)) {
    log('SETUP: the shooter has no K47 or could not switch it to semi-auto')
    return 2
  }
  B.setQuickBarSlot(2)
  await tp(A, 45.5, 65, 88.5, 0, 0)
  await tp(B, 45.5, 65, 94.5, 180, 0)
  await sleep(1000)

  // 9. One shot and a heavy bleed: B bleeds out as A's kill.
  const since = Date.now()
  const first = await shot(A, B, 1.2)
  const shotAt = Date.now()
  log(`A's shot: ${JSON.stringify(first)}, B at ${await health(B.username)}`)
  await rcon(`cv injure ${B.username} chest heavy`)
  await waitFor(() => B.game.gameMode === 'spectator', 70000)
  const after = (Date.now() - shotAt) / 1000
  await sleep(1500)
  check(`B bleeds out ${after.toFixed(0)} s after A's shot (over 30 s)`, B.game.gameMode === 'spectator' && after > 30)
  check('B\'s death reads "被 A 击杀（失血）"', titled(B, `被 ${A.username} 击杀（失血）`), B.titles.slice(-2))
  check('A gets "✖ 击杀" for B', barsSince(A, since).some(b => b.includes('✖ 击杀') && b.includes(B.username)), barsSince(A, since).filter(b => b.includes('击')))
  const feed = A.chatLines.filter(l => l.includes('→') && l.includes(B.username))
  check('the kill feed reads "A [失血] → B"', feed.some(l => l.startsWith(`${A.username} [失血]`)), feed)
  return 0
}

;(async () => {
  let code = 0
  if (WHICH === 'all' || WHICH === 'squad') code = Math.max(code, await squad())
  sweep(false)
  for (const bot of bots.splice(0)) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  if (code < 2 && (WHICH === 'all' || WHICH === 'solo')) code = Math.max(code, await solo())
  finish(code === 2 ? 2 : checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  sweep(false)
  for (const bot of bots) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
