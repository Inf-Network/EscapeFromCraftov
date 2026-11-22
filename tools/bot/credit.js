// Kill credit and hit feedback (issue #29), with bots on the proving ground.
//
// squad: an enemy S (solo) against V and M in a party (TRIO), all in one raid, friendly fire off:
//   1. control: M, standing, falls 10 blocks and breaks a leg (the fall check below can see one);
//   2. S shoots V once, V is given S's heavy chest bleed (`cv injure`) and bleeds until it goes down,
//      over 30 s after the shot: S is told it knocked V down;
//   3. S shoots the crawling V: a plain body or head hit sound, neither the kill nor the knock-down;
//   4. V, down, is lifted 10 blocks and falls: no fracture; M revives V, still no fracture;
//   5. S shoots V (full health) down: the shot that downs it plays craftov:hit.knock and no shot
//      plays craftov:hit.kill;
//   6. V, down again, falls 10 blocks and M's frag goes off at its feet: V loses no bleed-out time;
//      V bleeds out and the kill is S's (kill feed, "✖ 击杀" for S, none for M, V's death title),
//      with no kill replay (S last hit V 30 s before);
//   7. S shoots M dead (nobody left standing to revive it): the killing shot plays craftov:hit.kill,
//      M watches the kill replay, and M's report shows no player kills;
//   8. S, at 20 health, blows itself up: "阵亡（破片手雷）", and its report shows the two kills only.
// solo: four solo bots A (the shooter), B, C and D; D first puts a bounty on C from the lobby:
//   9. A shoots C, who carries an injector, down; C quits 32 s later: A gets "✖ 击杀" and the bounty;
//  10. A shoots D, D's wounds are cleared and it gets a heavy bleed nobody made: it bleeds out over
//      30 s after the shot as nobody's kill ("阵亡（失血）", "☠ D");
//  11. A shoots B, B's wounds are cleared and it gets a light bleed of A's; A is killed, then B bleeds
//      out: B's death still names A, but A, already dead, gets no kill; A's report shows C only.
// ff: the squad setup with combat.friendly-fire turned on in the server's config.yml (SERVER_DIR,
// default the repository's server/run; put back afterwards):
//  12. S shoots V down; M hits the crawling V in the last 20 s of its bleed-out (the hits are
//      recorded, though the squad's scoreboard team blocks their damage): when V bleeds out, the
//      kill is S's, not M's.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node credit.js [ff|squad|solo]
//
// All three run when none is named. The bots are made op (test map) and deopped when done;
// soldiers are killed every few seconds so they do not join in. A match launches after
// match.wait-seconds (30 by default). Exit code 0 when every check passes, 1 when one fails, 2 when
// the scenario could not be set up.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
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
// The kill feed is on the BetterHud HUD (top right) and only for the squads of the killer and the
// victim; bots cannot see BetterHud, so `cv hud` prints the last lines each player was sent.
const feedOf = async bot => {
  const out = await rcon(`cv hud ${bot.username}`)
  const m = String(out).match(/feedlog=([^|]*)/)
  return m ? m[1].split(' / ').map(l => l.trim()).filter(Boolean) : []
}

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

/** Runs `action` and returns the window it opens, or null after 4 s. */
function nextWindow (bot, action) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 4000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    Promise.resolve().then(action).catch(() => {})
  })
}

/** `payer`, in the lobby, puts a bounty on `target`: main menu, 悬赏榜 (slot 25), the target's head. */
async function placeBounty (payer, target) {
  await rcon(`cv money ${payer.username} 20000`)
  const main = await nextWindow(payer, () => payer.chat('/cv menu'))
  if (!main) return false
  const board = await nextWindow(payer, () => payer.clickWindow(25, 0, 0))
  if (!board) return false
  await sleep(300)
  const slot = board.slots.findIndex((s, i) => i >= 9 && i < 36 && s && JSON.stringify(s.components || '').includes(target.username))
  const since = Date.now()
  if (slot >= 0) await payer.clickWindow(slot, 0, 0)
  await waitFor(() => barsSince(payer, since).some(b => b.includes('已追加悬赏')), 3000)
  payer.closeWindow(board)
  await sleep(300)
  return barsSince(payer, since).some(b => b.includes('已追加悬赏'))
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
  await rcon(`cv injure ${V.username} chest heavy ${S.username}`)
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
  const feed = (await feedOf(S)).filter(l => l.includes('→') && l.includes(V.username))
  check('V bleeds out', V.game.gameMode === 'spectator', V.game.gameMode)
  check('the kill feed gives V to S, not to M', feed.some(l => l.startsWith(S.username)) && !feed.some(l => l.includes(M.username)), feed)
  check('S gets "✖ 击杀" for V, M does not', barsSince(S, since).some(b => b.includes('✖ 击杀') && b.includes(V.username))
    && !barsSince(M, since).some(b => b.includes('✖ 击杀')), { S: barsSince(S, since).filter(b => b.includes('击杀')), M: barsSince(M, since).filter(b => b.includes('击杀')) })
  check('V\'s death title names S', titled(V, `被 ${S.username} 击杀`), V.titles.slice(-2))
  check('no bounty is claimed for V', !chatHas(S, '领取了', V.username))
  await sleep(2000)
  check('V gets no kill replay: S, still in the raid, last hit it 30 s before', !titled(V, '击杀回放'), V.titles.slice(-3))

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
  check('M, shot dead, watches the kill replay first', titled(M, '击杀回放'), M.titles)
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
  const C = await make(`kc${run}c`)
  const D = await make(`kc${run}d`)
  const all = [A, B, C, D]
  for (const bot of all) await rcon(`op ${bot.username}`)
  if (!await placeBounty(D, C)) {
    log('SETUP: D could not put a bounty on C from the lobby')
    return 2
  }
  for (const bot of all) bot.chat('/cv queue proving NORMAL SOLO')
  log('solo queued; waiting for the raid')
  if (!await enterRaid(all)) return 2
  if (new Set(all.map(b => b.worldName)).size !== 1) {
    log('SETUP: the four solo bots are not in one raid', all.map(b => b.worldName))
    return 2
  }
  await waitFor(() => chatHas(A, '本局悬赏目标', C.username), 15000) // 14 s after the raid begins
  check('the raid announces the bounty on C', chatHas(A, '本局悬赏目标', C.username), A.chatLines.filter(l => l.includes('悬赏')))
  sweep(true)
  await clearArea(A)
  for (const bot of [B, C, D]) for (const slot of ['head', 'chest']) await rcon(`item replace entity ${bot.username} armor.${slot} with minecraft:air`)
  if (!await holdGun(A) || !await semi(A)) {
    log('SETUP: the shooter has no K47 or could not switch it to semi-auto')
    return 2
  }
  if (await giveToHotbar(C, 'revive_kit', 7) < 0) {
    log('SETUP: could not give C an injector')
    return 2
  }
  for (const bot of [B, C, D]) bot.setQuickBarSlot(2)
  await tp(A, 45.5, 65, 88.5, 0, 0)
  await tp(B, 40.5, 65, 94.5, 180, 0)
  await tp(C, 45.5, 65, 94.5, 180, 0)
  await tp(D, 50.5, 65, 94.5, 180, 0)
  await sleep(1000)
  const since = Date.now()

  // 9. A shoots C down; with the injector on it, C crawls instead of dying.
  for (let i = 0; i < 6 && !titled(C, '第一次倒地'); i++) {
    await shot(A, C, 1.2)
    await sleep(300)
  }
  const downAt = Date.now()
  check('A shoots C, who carries an injector, down', titled(C, '第一次倒地'), C.titles.slice(-2))

  // 10. D: one shot from A, then only a heavy bleed nobody made (A's shot may have made one too).
  const dShot = await shot(A, D, 1.2)
  const dShotAt = Date.now()
  await restore(D)
  await rcon(`damage ${D.username} 40 minecraft:generic`)
  await rcon(`cv injure ${D.username} chest heavy`)
  log(`A's shot at D: ${JSON.stringify(dShot)}; D at ${await health(D.username)}, bleeding from a wound nobody made`)

  // 11. B: one shot from A, then only a light bleed of A's, which keeps A's credit going.
  const bShot = await shot(A, B, 1.2)
  await restore(B)
  await rcon(`damage ${B.username} 40 minecraft:generic`)
  await rcon(`cv injure ${B.username} chest light ${A.username}`)
  log(`A's shot at B: ${JSON.stringify(bShot)}; B at ${await health(B.username)}, bleeding lightly from A's wound`)

  // 9. C quits 32 s after going down: the kill and the bounty go to A.
  await waitFor(() => Date.now() - downAt > 32000, 40000)
  const quitAt = Date.now()
  C.quit()
  await waitFor(() => barsSince(A, quitAt).some(b => b.includes('✖ 击杀') && b.includes(C.username)), 6000)
  await sleep(500)
  check('C quits 32 s after A downed it: A gets "✖ 击杀"', barsSince(A, quitAt).some(b => b.includes('✖ 击杀') && b.includes(C.username)),
    barsSince(A, quitAt).filter(b => b.includes('击')))
  check('A claims the bounty on C', chatHas(A, '领取了', C.username), A.chatLines.filter(l => l.includes('悬赏')).slice(-2))
  const aFeed = await feedOf(A)
  check('the kill feed gives C to A', aFeed.some(l => l.startsWith(A.username) && l.includes('→') && l.includes(C.username)), aFeed)

  // 10. D bleeds out from the wound nobody made, over 30 s after A's shot: nobody's kill.
  await waitFor(() => D.game.gameMode === 'spectator', 60000)
  const dAfter = (Date.now() - dShotAt) / 1000
  await sleep(1500)
  check(`D bleeds out ${dAfter.toFixed(0)} s after A's shot (over 30 s): "阵亡（失血）"`,
    D.game.gameMode === 'spectator' && dAfter > 30 && titled(D, '阵亡（失血）'), D.titles.slice(-2))
  check('A gets no kill for D', !barsSince(A, since).some(b => b.includes('✖ 击杀') && b.includes(D.username)))
  // D was solo: its death goes to its own squad's feed only, never to A's.
  const dFeed = await feedOf(D)
  const aFeedAfter = await feedOf(A)
  check('the kill feed shows "☠ D" to D\'s squad only', dFeed.some(l => l.includes('☠') && l.includes(D.username) && !l.includes('→'))
    && !aFeedAfter.some(l => l.includes(D.username)), { D: dFeed, A: aFeedAfter })

  // 11. A dies; B, still bleeding from A's wound, bleeds out afterwards.
  const aDeath = Date.now()
  await rcon(`damage ${A.username} 1000 minecraft:generic`)
  await waitFor(() => A.game.gameMode === 'spectator', 5000)
  check('A dies', A.game.gameMode === 'spectator', A.game.gameMode)
  await rcon(`cv injure ${B.username} chest heavy ${A.username}`)
  await waitFor(() => A.chatLines.some(l => l.includes('击败干员')), 30000)
  const aReport = A.chatLines.find(l => l.includes('击败干员'))
  check('A\'s report: one player kill (C; D bled from a wound nobody made)', /击败干员 1\b/.test(aReport || ''), aReport)
  await waitFor(() => B.game.gameMode === 'spectator', 60000)
  await sleep(1500)
  check('B bleeds out after A died; its death still names A', B.game.gameMode === 'spectator' && titled(B, `被 ${A.username} 击杀（失血）`),
    B.titles.slice(-2))
  check('A, already dead, gets no "✖ 击杀" for B', !barsSince(A, aDeath).some(b => b.includes('✖ 击杀')), barsSince(A, aDeath).filter(b => b.includes('击')))
  return 0
}

// ------------------------------------------------------------------ friendly fire

async function ff () {
  const file = path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
  let original
  try { original = fs.readFileSync(file, 'utf8') } catch (e) {
    log('SETUP: cannot read', file)
    return 2
  }
  const on = original.replace(/^(\s*friendly-fire:\s*)false\s*$/m, '$1true')
  if (on === original) {
    log('SETUP: no "friendly-fire: false" in', file)
    return 2
  }
  fs.writeFileSync(file, on)
  try {
    if (!(await rcon('cv reload')).includes('已重新加载')) {
      log('SETUP: cv reload failed')
      return 2
    }
    return await friendlyFire()
  } finally {
    fs.writeFileSync(file, original)
    await rcon('cv reload').catch(() => {})
  }
}

async function friendlyFire () {
  const S = await make(`kf${run}s`)
  const V = await make(`kf${run}v`)
  const M = await make(`kf${run}m`)
  for (const bot of [S, V, M]) await rcon(`op ${bot.username}`)
  M.chat(`/cv party invite ${V.username}`)
  await sleep(800)
  V.chat('/cv party accept')
  await sleep(800)
  M.chat('/cv queue proving NORMAL TRIO')
  S.chat('/cv queue proving NORMAL SOLO')
  log('friendly fire on; squad and enemy queued')
  if (!await enterRaid([S, V, M])) return 2
  if (S.worldName !== V.worldName || S.worldName !== M.worldName) {
    log('SETUP: the enemy is not in the squad\'s raid', [S.worldName, V.worldName, M.worldName])
    return 2
  }
  sweep(true)
  await clearArea(S)
  for (const bot of [S, V, M]) for (const slot of ['head', 'chest']) await rcon(`item replace entity ${bot.username} armor.${slot} with minecraft:air`)
  if (!await holdGun(M) && await giveToHotbar(M, 'k47', 0) < 0) {
    log('SETUP: could not give M a K47')
    return 2
  }
  if (!await holdGun(S) || !await semi(S) || !await holdGun(M) || !await semi(M)) {
    log('SETUP: S or M has no K47 or could not switch it to semi-auto')
    return 2
  }
  V.setQuickBarSlot(2)
  await tp(S, 45.5, 65, 88.5, 0, 0)
  await tp(V, 45.5, 65, 94.5, 180, 0)
  await tp(M, 50.5, 65, 94.5, 90, 0) // side-on: a miss at V flies on past nobody
  await sleep(1000)
  const since = Date.now()
  for (let i = 0; i < 6 && !titled(V, '第一次倒地'); i++) {
    await shot(S, V, 1.2)
    await sleep(300)
  }
  check('S shoots V down', titled(V, '第一次倒地'), V.titles.slice(-2))
  // Late in the bleed-out, so M's hits are still V's last hit (under 30 s old) when it dies. They
  // are recorded and play hit markers; the squad's scoreboard team keeps them from doing damage.
  await waitFor(() => bleedOut(V) !== null && bleedOut(V) <= 20, 60000)
  const mSounds = []
  for (let i = 0; i < 2 && V.game.gameMode !== 'spectator'; i++) {
    mSounds.push(await shot(M, V, 0.3))
    await sleep(300)
  }
  log('M\'s shots at the crawling V', mSounds, 'V bleed-out left', bleedOut(V))
  check('M (friendly fire on) hits the crawling V in its last 20 s', mSounds.some(s => s && s.length > 0), mSounds)
  await waitFor(() => V.game.gameMode === 'spectator', 30000)
  await sleep(1500)
  const feed = (await feedOf(S)).filter(l => l.includes('→') && l.includes(V.username))
  check('V bleeds out', V.game.gameMode === 'spectator', V.game.gameMode)
  check('V\'s death names S, who knocked it down, not M', titled(V, `被 ${S.username} 击杀`) && !titled(V, `被 ${M.username}`), V.titles.slice(-2))
  check('S gets "✖ 击杀" for V, M does not', barsSince(S, since).some(b => b.includes('✖ 击杀') && b.includes(V.username))
    && !barsSince(M, since).some(b => b.includes('✖ 击杀')), { S: barsSince(S, since).filter(b => b.includes('击杀')), M: barsSince(M, since).filter(b => b.includes('击杀')) })
  check('the kill feed gives V to S', feed.some(l => l.startsWith(S.username)) && !feed.some(l => l.startsWith(M.username)), feed)
  return 0
}

;(async () => {
  let code = 0
  for (const [name, scenario] of [['ff', ff], ['squad', squad], ['solo', solo]]) {
    if (code < 2 && (WHICH === 'all' || WHICH === name)) code = Math.max(code, await scenario())
    sweep(false)
    for (const bot of bots.splice(0)) {
      try { await rcon(`deop ${bot.username}`) } catch (e) { }
      try { bot.quit() } catch (e) { }
    }
  }
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
