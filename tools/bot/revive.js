// Knock-downs, revives and the self-revive injector (issue #28), with bots on the proving ground.
//
// squad: two bots in a party (TRIO), P is knocked down with RCON and M tries to revive it (downed, P
// has vanilla's sneaking speed 0.3, which the client scales the crawl by, while M standing has the
// silent walk's 0.45; revived, P has 0.45 again):
//   1. M on the far side of a one-block wall, 2 blocks away, crouches and right-clicks P for 8 s
//      (raw interact packets, as a modified client could send through the wall): P stays down;
//   2. M on a ledge 1.5 blocks up right next to P right-clicks it for 8 s: P stays down;
//   3. M next to P with its K47 (P holds a P18): a trigger pull fires (control), then while M holds
//      right click on P its trigger pulls (left clicks on P) fire nothing; RCON `damage M 5` and
//      then moving M 0.7 blocks each drop the revive bar back to (nearly) empty and say it was cut
//      short; M then finishes and P is up, and P's pistol comes up again: one new draw of the
//      P18's 6 ticks (#34);
//   4. P gets an injector and is knocked down again; M, carrying an injector too, revives P and is
//      knocked down mid-revive: seen from P it crawls and never stands up in between (ending the
//      revive used to clear its crawl), and its title says to use the injector (nobody standing);
//      with its injector taken away M dies at once; P is not finished for having nobody left, is
//      told to use the injector, and stands up with it.
// solo: two solo bots (different squads), S shoots T with single K47 PS rounds:
//   5. T in a one-block gap (crawling, server pose SWIMMING), no armour, healed before each shot:
//      side-on to S most hits are chest, stomach or arm (not head), feet towards S most are legs;
//      head towards S, 15 shots aimed over a 5 x 3 grid on the side of the box facing S: the outer
//      columns are arms, the bottom row chest and the rest head, well under all of them head;
//   6. T gets an injector and takes a lethal hit: it is downed although solo, a body shot takes about
//      21.6 s off its bleed-out (36 x 0.6; a headshot took 43), and it stands up with the injector;
//   7. T, without an injector now, is shot dead: it dies at once (no knock-down), and its damage log
//      does not list mostly head hits.
// secure: two fresh bots in a party (TRIO), B with the starting 3-cell secure container (#37):
//   8. standing, B opens /cv secure: a lion statue (6 cells) does not go in and B is told why, a
//      master chip (2 cells) goes in with an "occupied" marker in its second cell, and a box of 60
//      rounds puts 30 in the last cell and leaves 30 in the inventory;
//   9. B is knocked down with the container open: the server closes the window;
//  10. downed, /cv secure opens nothing and the bleed-out bar says why; /cv opens the terminal, whose
//      secure container, gunsmith, skill and test armory buttons each show their own locked picture
//      (<icon>_locked) and the container and armory open nothing (B is an op, so it has the armory);
//      /cv items opens no armory either; the terminal in hotbar slot 9 opens the terminal as well,
//      never the container;
//  11. B dies of a second hit: its saved profile holds the same secure container as before the
//      knock-down, and the gold bar it tried to put in while downed is not in it;
//  12. (run first, in the lobby) a third bot whose saved container holds three lion statues from
//      when every item took one slot sees each in one slot and 18/3 格, cannot put a master chip
//      in, and takes a statue out.
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node revive.js [squad|solo|secure]
//
// The draw check in 3 reads the server log (SERVER_LOG, default the repository's
// server/run/logs/latest.log) and needs `debug: true` in the server's config.yml. The profile
// check in 11 reads CRAFTOV_DATA/data/players (default the repository's server/run/plugins/Craftov).
// All three scenarios run when none is named. The bots are made op (test map) and deopped when done;
// soldiers are killed now and then so they do not join in. A match launches after
// match.wait-seconds (30 by default). Exit code 0 when every check passes, 1 when one fails, 2 when
// the scenario could not be set up.
//
// Server config (tools/bot/suite.js sets it for its run): debug true (the draw check reads the
// log), match.wait-seconds 3; the bots are made op.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON)
const LOG = env.LOG
const DATA = env.DATA
const logSize = () => fs.statSync(LOG).size
/** Server log lines written since byte offset `from`. */
function logSince (from) {
  const fd = fs.openSync(LOG, 'r')
  try {
    const buf = Buffer.alloc(Math.max(0, fs.fstatSync(fd).size - from))
    fs.readSync(fd, buf, 0, buf.length, from)
    return buf.toString('utf8').split('\n')
  } finally { fs.closeSync(fd) }
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const run = Math.random().toString(36).slice(2, 6)
const checks = []
const check = (name, ok, got) => { checks.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${got === undefined ? '' : ': ' + JSON.stringify(got)}`) }
const bots = []
const WHICH = process.argv[2] || 'all'

// K47 with PS on a bare body (DamageService, config.yml combat.zone-multipliers).
const K47 = 36
const ZONES = { head: K47 * 2, chest: K47, stomach: K47 * 0.95, arm: K47 * 0.65, leg: K47 * 0.7 }

/** Plain text of an NBT text component. */
const plain = j => [...JSON.stringify(j || '').matchAll(/"text":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('')

/**
 * The action bar as a string in which every bar segment `|` is G (the palette's ok green #7FD1AE),
 * g (its dim grey #69707A, the empty part) or x (any other colour), and other characters are kept.
 */
function coded (text) {
  let out = ''
  const walk = (node, color) => {
    if (node === null || node === undefined) return
    if (typeof node === 'string' || typeof node === 'number') {
      for (const ch of String(node)) out += ch !== '|' ? ch : /^#7fd1ae$/i.test(color || '') ? 'G' : /^#69707a$/i.test(color || '') ? 'g' : 'x'
      return
    }
    if (Array.isArray(node)) { node.forEach(n => walk(n, color)); return }
    const c = node.color || color
    if (node.text !== undefined) walk(String(node.text), c)
    if (node[''] !== undefined) walk(String(node['']), c)
    if (node.extra) walk(node.extra, c)
  }
  try { walk(nbt.simplify(text), null) } catch (e) { out = plain(text) }
  return out
}
/** Filled cells of the 20-cell revive bar in a coded action bar, or null when there is none. */
const reviveFill = bar => { const m = bar.match(/[Gg]{20}/); return m ? (m[0].match(/G/g) || []).length : null }

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.shots = 0
  const heard = s => { if (/^craftov:gun\.[a-z0-9_]+\.fp(_s)?$/.test(String(s))) bot.shots++ }
  bot.on('soundEffectHeard', heard)
  // The shooter's own first-person shot is played on its entity (entity_sound_effect).
  bot._client.on('entity_sound_effect', p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_.\/]+/); if (m) heard(m[0]) })
  bot.bars = []
  const bar = text => bot.bars.push({ at: Date.now(), text: coded(text) })
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
const titled = (bot, text) => bot.titles.some(t => t.includes(text))

async function dataNumber (target, path) {
  const out = await rcon(`data get entity ${target} ${path}`)
  const m = out.match(/entity data: (-?[\d.]+(?:E-?\d+)?)[dfbsL]?\s*$/)
  return m ? Number(m[1]) : null
}
const health = name => dataNumber(name, 'Health')

async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}

/** Clears the test area around (45, 65, 93) of the proving ground: stone floor, air above. */
async function clearArea (bot) {
  await rcon(`execute at ${bot.username} run fill 39 64 85 52 64 100 minecraft:stone`)
  await rcon(`execute at ${bot.username} run fill 39 65 85 52 70 100 minecraft:air`)
}

const tp = (bot, x, y, z, yaw = 0, pitch = 0) => rcon(`execute as ${bot.username} at @s run tp @s ${x} ${y} ${z} ${yaw} ${pitch}`)

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

/** One right click on `target` (a raw interact packet: no line-of-sight check on the client side). */
const click = (bot, target) => bot._client.write('use_entity', { target: target.id, mouse: 0, hand: 0, sneaking: bot.getControlState('sneak') })

/** Holds right click on `target` for `ms`, the way a held button repeats (every 150 ms here). */
async function holdOn (bot, target, ms, during) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    click(bot, target)
    if (during) await during()
    await sleep(150)
  }
}

/** Uses the item in the bot's hand for `ms` (right click held). */
async function use (bot, ms) {
  bot.activateItem()
  await sleep(ms)
  bot.deactivateItem()
}

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

// ------------------------------------------------------------------ squad

/** The player's sneaking-speed attribute as the server has it, or null. */
async function sneakSpeed (bot) {
  const m = (await rcon(`attribute ${bot.username} minecraft:sneaking_speed get`)).match(/(-?[\d.]+)\s*$/)
  return m ? Number(m[1]) : null
}
const close = (a, b) => a !== null && Math.abs(a - b) < 1e-6

async function squad () {
  const P = await make(`rv${run}p`)
  const M = await make(`rv${run}m`)
  for (const bot of [P, M]) await rcon(`op ${bot.username}`)
  M.chat(`/cv party invite ${P.username}`)
  await sleep(800)
  P.chat('/cv party accept')
  await sleep(800)
  M.chat('/cv queue proving NORMAL TRIO')
  log('squad queued; waiting for the raid')
  if (!await enterRaid([P, M])) return 2
  await clearArea(M)
  for (const bot of [P, M]) await rcon(`item replace entity ${bot.username} armor.chest with minecraft:air`)
  // P holds a P18 through the knock-down, so its draw on getting up can be read from the log.
  const pistol = await giveToHotbar(P, 'p18', 6)
  if (pistol >= 0) P.setQuickBarSlot(pistol)
  await sleep(1000)

  // P goes down in the middle of the area, facing north.
  await tp(P, 45.5, 65, 94.5, 180, 0)
  await sleep(500)
  await rcon(`damage ${P.username} 500 minecraft:generic`)
  if (!await waitFor(() => titled(P, '第一次倒地'), 3000)) {
    log('SETUP: P was not knocked down', P.titles)
    return 2
  }
  const pEntity = () => M.players[P.username] && M.players[P.username].entity
  await sleep(300)
  const downedSneak = await sneakSpeed(P)
  const standingSneak = await sneakSpeed(M)
  check('downed, P keeps vanilla\'s sneaking speed for the crawl (0.3); M, standing, has the silent walk\'s 0.45',
    close(downedSneak, 0.3) && close(standingSneak, 0.45), { downed: downedSneak, standing: standingSneak })

  // 1. Through a one-block wall, crouching and clicking.
  await rcon(`execute at ${M.username} run fill 43 65 93 48 67 93 minecraft:stone`)
  await tp(M, 45.5, 65, 92.5, 0, 30)
  await sleep(800)
  let since = Date.now()
  M.setControlState('sneak', true)
  await holdOn(M, pEntity(), 8000)
  M.setControlState('sneak', false)
  check('behind a one-block wall, 2 blocks away, crouching and clicking for 8 s: P is still down',
    !titled(P, '已被救起') && inRaid(P), { titles: P.titles.slice(-3) })
  check('behind the wall: M is told something is in the way', barsSince(M, since).some(b => b.includes('中间有遮挡')), barsSince(M, since).slice(-2))
  await rcon(`execute at ${M.username} run fill 43 65 93 48 67 93 minecraft:air`)

  // 2. From a ledge 1.5 blocks up, right next to P.
  await rcon(`execute at ${M.username} run setblock 45 65 93 minecraft:stone`)
  await rcon(`execute at ${M.username} run setblock 45 66 93 minecraft:smooth_stone_slab[type=bottom]`)
  await tp(M, 45.5, 66.5, 93.5, 0, 60)
  await sleep(800)
  since = Date.now()
  await holdOn(M, pEntity(), 8000)
  check('from a ledge 1.5 blocks up, 1.8 blocks away, clicking for 8 s: P is still down',
    !titled(P, '已被救起') && inRaid(P), { titles: P.titles.slice(-3) })
  check('on the ledge: M is told it is not on the same level', barsSince(M, since).some(b => b.includes('不在同一高度')), barsSince(M, since).slice(-2))
  await rcon(`execute at ${M.username} run fill 45 65 93 45 66 93 minecraft:air`)

  // 3. Next to P with a gun: no shots while reviving, damage and moving start it over, then it works.
  if (!await holdGun(M)) {
    log('SETUP: M has no K47')
    return 2
  }
  await tp(M, 45.5, 65, 92.8, 0, -80)
  await sleep(800)
  let shots = M.shots
  for (let i = 0; i < 6 && M.shots === shots; i++) { M.swingArm(); await sleep(300) }
  const control = M.shots - shots
  check('control: M pulling the trigger (looking up, not reviving) fires', control > 0, control)
  await sleep(1500)
  await tp(M, 45.5, 65, 92.8, 0, 50)
  await sleep(500)
  await clearSoldiers()
  since = Date.now()
  shots = M.shots
  // Hold on for 1.5 s, then keep holding on while pulling the trigger for 1.5 s. With the crosshair
  // on P a vanilla client's left click is an attack on P (and a swing): a bare swing right after the
  // right click is the one vanilla sends after any use, which the trigger ignores (TriggerService.mute).
  await holdOn(M, pEntity(), 1500)
  await holdOn(M, pEntity(), 1500, () => M.attack(pEntity()))
  check('while M holds right click on P its trigger pulls fire nothing', M.shots === shots, M.shots - shots)
  check('the trigger pulls mid-revive are refused because M is reviving', barsSince(M, since).some(b => b.includes('救援时不能开火')),
    barsSince(M, since).filter(b => !b.includes('正在救援')).slice(-2))
  const fills = () => barsSince(M, since).map(reviveFill).filter(f => f !== null)
  check('M sees the revive bar fill', fills().length > 0 && Math.max(...fills()) >= 8, fills())
  // Damage: the bar drops back.
  let before = Math.max(...fills(), 0)
  let after = Date.now()
  await rcon(`damage ${M.username} 5 minecraft:generic`)
  await holdOn(M, pEntity(), 700)
  let next = barsSince(M, after).map(reviveFill).filter(f => f !== null)
  // The action bar of the revive pass just before the hit can still arrive after it: one stale update is allowed.
  check(`RCON damage M 5 mid-revive: the bar drops from ${before} cells back to at most 2`, next.slice(0, 2).some(f => f <= 2), next)
  check('mid-revive damage: the bar says the revive was cut short', barsSince(M, after).some(b => b.includes('救援被打断')), barsSince(M, after).slice(0, 2))
  // Moving 0.7 blocks: the bar drops back too.
  await holdOn(M, pEntity(), 1800)
  before = Math.max(...barsSince(M, after).map(reviveFill).filter(f => f !== null), 0)
  after = Date.now()
  await rcon(`execute as ${M.username} at @s run tp @s ~0.7 ~ ~`)
  await holdOn(M, pEntity(), 700)
  next = barsSince(M, after).map(reviveFill).filter(f => f !== null)
  check(`M moved 0.7 blocks mid-revive: the bar drops from ${before} cells back to at most 2`, before >= 6 && next.slice(0, 2).some(f => f <= 2), { before, next })
  // Finish it.
  const beforeRevive = logSize()
  await waitFor(async () => { await holdOn(M, pEntity(), 300); return titled(P, '已被救起') }, 9000)
  check('M holding on next to P revives it', titled(P, '已被救起') && inRaid(P), P.titles.slice(-2))
  // FootstepService puts the silent walk back on its next tick, which can come after the title.
  let upSneak = null
  await waitFor(async () => close(upSneak = await sneakSpeed(P), 0.45), 1000)
  check('revived, P has the silent walk\'s sneaking speed again (0.45)', close(upSneak, 0.45), upSneak)
  // Revived, the pistol comes up again: one new draw of 6 ticks, and it is up on its ready tick.
  await sleep(800)
  const lines = logSince(beforeRevive)
  const draws = lines.map(l => l.match(new RegExp(`\\[debug\\] ${P.username} draws p18 tick=(\\d+) ready=(\\d+)`))).filter(Boolean)
  const ups = lines.map(l => l.match(new RegExp(`\\[debug\\] ${P.username} up p18 tick=(\\d+)`))).filter(Boolean)
  check('revived, P draws its pistol again (one draw of 6 ticks, up on its ready tick)', pistol >= 0 && draws.length === 1 &&
    Number(draws[0][2]) - Number(draws[0][1]) === 6 && ups.length === 1 && ups[0][1] === draws[0][2],
    pistol < 0 ? 'no P18 on the hotbar' : `${draws.length} draws (${draws.map(m => m[1] + '->' + m[2]).join(', ')}), up at ${ups.map(m => m[1]).join(',')}`)
  await sleep(1500)
  shots = M.shots
  await tp(M, 45.5, 65, 92.8, 0, -80)
  await sleep(500)
  for (let i = 0; i < 6 && M.shots === shots; i++) { M.swingArm(); await sleep(300) }
  check('after the revive M can shoot again', M.shots > shots, M.shots - shots)

  // 4. P with an injector goes down again; M, reviving it with an injector of its own, is knocked
  // down mid-revive and stays down; M without its injector dies; P stands up with its own.
  await clearSoldiers()
  const key = await giveToHotbar(P, 'revive_kit', 7)
  const mKey = await giveToHotbar(M, 'revive_kit', 8)
  if (key < 0 || mKey < 0) {
    log('SETUP: could not give P and M an injector each')
    return 2
  }
  P.titles.length = 0
  await rcon(`damage ${P.username} 500 minecraft:generic`)
  if (!await waitFor(() => titled(P, '第二次倒地'), 3000)) {
    log('SETUP: P was not knocked down a second time', P.titles)
    return 2
  }
  await tp(M, 45.5, 65, 92.8, 0, 50)
  await sleep(500)
  await clearSoldiers()
  // M's pose as P's client sees it (entity metadata; 0 standing, 3 crawling, 5 crouching).
  const POSE = Math.max(0, P.registry.entitiesByName.player.metadataKeys.indexOf('pose'))
  const poses = []
  const onMeta = packet => {
    const m = P.players[M.username] && P.players[M.username].entity
    if (m && packet.entityId === m.id) for (const meta of packet.metadata) if (meta.key === POSE) poses.push({ at: Date.now(), pose: meta.value })
  }
  P._client.on('entity_metadata', onMeta)
  since = Date.now()
  // M holds on through the knock-down, so P's revive check still has it as the reviver.
  const holding = holdOn(M, pEntity(), 3000)
  await sleep(1500)
  const reviving = barsSince(M, since).some(b => b.includes('正在救援'))
  M.titles.length = 0
  const downAt = Date.now()
  await rcon(`damage ${M.username} 500 minecraft:generic`)
  await holding
  await sleep(1500)
  P._client.removeListener('entity_metadata', onMeta)
  // From the knock-down on, M only ever crawls: no standing pose before or after the crawl starts.
  const afterDown = poses.filter(p => p.at >= downAt).map(p => p.pose)
  check('M, knocked down (carrying an injector) while reviving P, crawls and never stands up in between',
    reviving && titled(M, '第一次倒地') && afterDown.includes(3) && !afterDown.includes(0),
    { reviving, poses: poses.map(p => (p.at >= downAt ? '' : 'before:') + p.pose), titles: M.titles })
  check('M, knocked down with nobody else standing, is told to use its injector', titled(M, '使用自救注射器'), M.titles)
  since = Date.now()
  await rcon(`clear ${M.username}`)
  await waitFor(() => M.game.gameMode === 'spectator', 4000)
  check('M, down with P down and its injector gone, dies at once instead of bleeding out', M.game.gameMode === 'spectator',
    { mode: M.game.gameMode, titles: M.titles.slice(-2) })
  await sleep(2500)
  check('P, down with an injector and nobody standing, is not finished', inRaid(P) && !titled(P, '行动失败'), { mode: P.game.gameMode, titles: P.titles })
  check('P is told to use the injector', barsSince(P, since).some(b => b.includes('使用自救注射器')), barsSince(P, since).slice(-1))
  check('M was still up when P went down: the title says to wait for a teammate', titled(P, '等待队友救援'), P.titles)
  P.setQuickBarSlot(key)
  await sleep(400)
  await use(P, 4600)
  await waitFor(() => titled(P, '已被救起'), 2000)
  check('P stands up with the injector', titled(P, '已被救起') && titled(P, '自我救援成功') && inRaid(P), P.titles.slice(-2))
  return 0
}

// ------------------------------------------------------------------ solo

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

/** Turns the shooter's K47 to semi-auto (F cycles AUTO -> SEMI). */
async function semi (bot) {
  for (let i = 0; i < 3 && !bot.bars.some(b => b.text.includes('单发')); i++) {
    bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await sleep(600)
  }
  return bot.bars.some(b => b.text.includes('单发'))
}

/**
 * One aimed shot at the target, by default at the middle of its crawling box (`aimAt` maps its feet
 * position to the point aimed at). Returns the health the target lost, or null when it could not be
 * read or no round left the gun twice (an empty magazine reloads on the first pull).
 */
async function shot (S, T, heal = true, aimAt = pos => pos.offset(0, 0.3, 0)) {
  for (let attempt = 0; attempt < 2; attempt++) {
    await clearSoldiers()
    if (heal) await restore(T)
    await sleep(200)
    const entity = S.players[T.username] && S.players[T.username].entity
    if (!entity) return null
    const aim = aimAt(entity.position)
    for (let i = 0; i < 8; i++) {
      await S.lookAt(aim, true)
      S.activateItem()
      await sleep(100)
    }
    const before = await health(T.username)
    const shots = S.shots
    S.activateItem()
    S.swingArm()
    let after = before
    for (const end = Date.now() + 1000; Date.now() < end && after === before; await sleep(20)) after = await health(T.username)
    await sleep(300)
    if (S.shots > shots) return before === null || after === null ? null : before - after
    log('  no round left the gun (reloading?); waiting and firing again')
    await sleep(3500)
  }
  return null
}

const zoneOf = damage => {
  for (const [zone, value] of Object.entries(ZONES)) if (Math.abs(damage - value) < 0.3) return zone
  return damage > 0 ? 'other' : 'miss'
}

async function solo () {
  const S = await make(`rv${run}s`)
  const T = await make(`rv${run}t`)
  for (const bot of [S, T]) await rcon(`op ${bot.username}`)
  await rcon(`cv give ${S.username} ammo_762_ps 120`)
  for (const bot of [S, T]) bot.chat('/cv queue proving NORMAL SOLO')
  log('solo queued; waiting for the raid')
  if (!await enterRaid([S, T])) return 2
  await clearArea(S)
  for (const slot of ['head', 'chest']) await rcon(`item replace entity ${T.username} armor.${slot} with minecraft:air`)
  if (!await holdGun(S) || !await semi(S)) {
    log('SETUP: the shooter has no K47 or could not switch it to semi-auto')
    return 2
  }
  T.setQuickBarSlot(2) // the knife: swinging it to turn the body fires nothing
  T.physicsEnabled = false
  await tp(S, 45.5, 65, 88.5, 0, 0)

  // 5. Prone hit zones, by which way the crawling target faces. Side-on and feet first, S aims at the
  // middle of the box; head first, over a grid on the side of the box facing S (z - 0.3): columns
  // across it, rows up from the ground. The arm band is the outer 19% each side (|x| > 0.19 of 0.3),
  // the chest the lowest quarter (y < 0.15 of 0.6).
  const GRID = []
  for (const y of [0.07, 0.3, 0.5]) {
    for (const x of [-0.24, -0.12, 0, 0.12, 0.24]) GRID.push({ x, y, want: Math.abs(x) > 0.2 ? 'arm' : y < 0.15 ? 'chest' : 'head' })
  }
  const runs = {}
  for (const [label, yaw] of [['side-on', 90], ['feet towards the shooter', 0], ['head towards the shooter', 180]]) {
    await rcon(`execute at ${S.username} run fill 43 66 92 48 66 95 minecraft:air`)
    await tp(T, 45.5, 65, 93.5, yaw, 0)
    await sleep(300)
    for (let i = 0; i < 6; i++) { T.swingArm(); await sleep(100) } // the body yaw follows the head while swinging
    await rcon(`execute at ${S.username} run fill 43 66 92 48 66 95 minecraft:stone`)
    await sleep(600)
    const zones = []
    if (yaw === 180) {
      for (const cell of GRID) {
        let d = null
        for (let tries = 0; tries < 2 && !(d > 0); tries++) d = await shot(S, T, true, pos => pos.offset(cell.x, cell.y, -0.3))
        zones.push({ ...cell, got: d === null ? 'none' : zoneOf(d) })
      }
    } else {
      for (let tries = 0; zones.length < 10 && tries < 20; tries++) {
        const d = await shot(S, T)
        if (d === null) break
        if (d > 0) zones.push(zoneOf(d))
      }
    }
    runs[label] = zones
    log(label, zones)
  }
  const count = (zones, ...names) => zones.filter(z => names.includes(z)).length
  const side = runs['side-on']
  check('crawling side-on to the shooter: most hits are chest, stomach or arm, at most 2 of 10 head',
    side.length >= 8 && count(side, 'chest', 'stomach', 'arm') > side.length / 2 && count(side, 'head') <= 2, side)
  const back = runs['feet towards the shooter']
  check('crawling feet towards the shooter: most hits are legs, none head', back.length >= 8 && count(back, 'leg') > back.length / 2 && count(back, 'head') === 0, back)
  const front = runs['head towards the shooter']
  const frontGot = front.map(c => c.got)
  const matched = front.filter(c => c.got === c.want).length
  check('crawling head towards the shooter: the outer columns are arms, the bottom row chest, the upper middle head (13+ of 15 as expected)',
    matched >= GRID.length - 2, front.map(c => `${c.x},${c.y}:${c.got}${c.got === c.want ? '' : '!=' + c.want}`))
  check('crawling head towards the shooter: well under all hits are head (under 60%)',
    frontGot.length === GRID.length && count(frontGot, 'head') < GRID.length * 0.6 && count(frontGot, 'arm') >= 4 && count(frontGot, 'chest') >= 2,
    { head: count(frontGot, 'head'), arm: count(frontGot, 'arm'), chest: count(frontGot, 'chest'), of: frontGot.length })

  // 6. Solo with an injector: downed, a body shot takes ~21.6 s, the injector stands it up.
  await rcon(`execute at ${S.username} run fill 43 66 92 48 66 95 minecraft:air`)
  await tp(T, 45.5, 65, 93.5, 90, 0)
  await sleep(300)
  for (let i = 0; i < 6; i++) { T.swingArm(); await sleep(100) }
  const key = await giveToHotbar(T, 'revive_kit', 7)
  if (key < 0) {
    log('SETUP: could not give T an injector')
    return 2
  }
  T.titles.length = 0
  await rcon(`damage ${T.username} 500 minecraft:generic`)
  await sleep(1500)
  check('solo T with an injector is knocked down by a lethal hit, not killed', titled(T, '第一次倒地') && inRaid(T), T.titles)
  check('solo T\'s knock-down title says to use the injector', titled(T, '使用自救注射器'), T.titles)
  const seconds = () => { const b = T.bars.slice().reverse().find(x => /倒地 .*?(\d+)s/.test(x.text)); return b ? Number(b.text.match(/倒地 .*?(\d+)s/)[1]) : null }
  check('solo T, down, is told to use the injector', T.bars.slice(-4).some(b => b.text.includes('使用自救注射器')), T.bars.slice(-1).map(b => b.text))
  // The bleed-out keeps running at 1 s a second; what a shot took is the drop minus the time passed.
  let lost = null
  for (let tries = 0; tries < 4 && lost === null; tries++) {
    const pool = seconds()
    const from = Date.now()
    const d = await shot(S, T, false)
    await sleep(700)
    const now = seconds()
    const took = pool === null || now === null ? null : pool - now - (Date.now() - from) / 1000
    log(`  shot the downed T: bleed-out ${pool}s -> ${now}s, ${took === null ? '?' : took.toFixed(1)}s more than the time passed`)
    if (d !== null && took !== null && took > 5) lost = took
  }
  check('a body shot (chest 21.6, stomach 20.5) at the downed T takes ~21 s off its bleed-out, not 43 like a headshot',
    lost !== null && lost >= 18.5 && lost <= 24, lost === null ? null : +lost.toFixed(1))
  T.setQuickBarSlot(key)
  await sleep(400)
  await use(T, 4600)
  await waitFor(() => titled(T, '已被救起'), 2000)
  check('solo T stands up with the injector', titled(T, '自我救援成功') && inRaid(T), T.titles.slice(-2))

  // 7. Without an injector: shot dead at once; the damage log is not mostly head.
  T.setQuickBarSlot(2)
  T.titles.length = 0
  T.chatLines.length = 0
  await rcon(`execute at ${S.username} run fill 43 66 92 48 66 95 minecraft:stone`)
  await sleep(600)
  for (let tries = 0; tries < 10 && T.game.gameMode !== 'spectator'; tries++) {
    await shot(S, T, false) // 35 health after the injector: a round or two
    await sleep(500)
  }
  check('T without an injector dies at once (no knock-down)', T.game.gameMode === 'spectator' && !titled(T, '倒地'), { mode: T.game.gameMode, titles: T.titles })
  await waitFor(() => T.chatLines.some(l => l.includes('受到的伤害')), 40000)
  await sleep(500)
  const lines = T.chatLines.filter(l => l.includes(S.username) && l.includes('→'))
  log('damage log', T.chatLines.filter(l => l.includes('受到的伤害') || l.includes('致命一击') || l.includes('→')))
  const parts = {}
  for (const l of lines) for (const m of l.matchAll(/(头部|胸部|腹部|左臂|右臂|左腿|右腿) ×(\d+)/g)) parts[m[1]] = (parts[m[1]] || 0) + Number(m[2])
  const total = Object.values(parts).reduce((a, b) => a + b, 0)
  check('T\'s damage log lists the shots by part, under half of them head', total >= 20 && (parts['头部'] || 0) < total / 2, parts)
  return 0
}

// ------------------------------------------------------------------ secure

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const savedProfile = name => {
  try { return JSON.parse(fs.readFileSync(path.join(DATA, 'data/players', offlineUuid(name) + '.json'), 'utf8')) } catch (e) { return null }
}

/** Runs `action` and resolves the window it opens, or null when none opens within `ms`. */
function opened (bot, action, ms = 3000) {
  return new Promise(resolve => {
    const on = w => { clearTimeout(timer); resolve(w) }
    const timer = setTimeout(() => { bot.removeListener('windowOpen', on); resolve(null) }, ms)
    bot.once('windowOpen', on)
    action()
  })
}
const titleOf = w => w ? JSON.stringify(w.title || '') : ''
const uiModel = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:ui\/[a-z_]+/); return m ? m[0] : null }
/** Plain text of an NBT text component as item components carry it (a string, a compound or a list). */
function txt (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (n.type === 'string') return n.value
  if (n.type === 'compound') return (n.value.text ? txt(n.value.text) : '') + (n.value.extra ? txt(n.value.extra) : '')
  if (n.type === 'list') return (n.value.value || []).map(v => typeof v === 'string' ? v : txt({ type: 'compound', value: v })).join('')
  return ''
}
const nameOf = item => {
  const c = item && item.components && item.components.find(x => x.type === 'custom_name' || x.type === 'item_name')
  return c ? txt(c.data) : ''
}
/**
 * The secure container's nine cells as `id:count` (`=` for an occupied marker, `#` locked, `-` empty).
 * A locked cell is the striped secure tile (#10); craftov:ui/locked is what it was before.
 */
const LOCKED_MODELS = ['craftov:ui/locked_secure', 'craftov:ui/locked']
const cellsOf = w => w.slots.slice(0, 9).map(s => !s ? '-' : pdc(s, 'id') ? `${pdc(s, 'id')}:${s.count}`
  : uiModel(s) === 'craftov:ui/secure_used' ? '=' : LOCKED_MODELS.includes(uiModel(s)) ? '#' : '?')
/** The window slot (below the menu's `top` slots) holding item `id`, or -1. */
const bottomSlot = (w, id, top) => w.slots.findIndex((s, i) => i >= top && s && pdc(s, 'id') === id)
const countIn = (w, id, top) => w.slots.reduce((n, s, i) => n + (i >= top && s && pdc(s, 'id') === id ? s.count : 0), 0)

async function secure () {
  // 12 (first, in the lobby). A container filled when every item took one slot, three lion statues
  // in the 3 starting slots: it keeps them, one slot each, and takes nothing more until back within
  // its 3 cells.
  const old = `rv${run}c`
  fs.mkdirSync(path.join(DATA, 'data/players'), { recursive: true })
  fs.writeFileSync(path.join(DATA, 'data/players', offlineUuid(old) + '.json'), JSON.stringify({
    uuid: offlineUuid(old), name: old, secureTier: 1, secure: [1, 2, 3].map(() => ({ id: 'lion_statue', amount: 1 }))
  }))
  const C = await make(old)
  await rcon(`cv give ${old} master_chip`)
  await sleep(1000)
  const live = () => C.currentWindow || { slots: [], title: '' }
  if (!await opened(C, () => C.chat('/cv secure'))) {
    log('SETUP: /cv secure opened nothing in the lobby')
    return 2
  }
  check('an old container over its cells shows each statue in one slot', cellsOf(live()).join(' ') ===
    'lion_statue:1 lion_statue:1 lion_statue:1 # # # # # #', cellsOf(live()))
  check('and its info button reads 18/3 格', nameOf(live().slots[13]).includes('18/3 格'), nameOf(live().slots[13]))
  let since = Date.now()
  const chip = bottomSlot(live(), 'master_chip', 18)
  if (chip >= 0) await C.clickWindow(chip, 0, 0)
  await sleep(800)
  check('it takes no master chip while over its cells, and says so', chip >= 0 && !cellsOf(live()).some(c => c.startsWith('master_chip')) &&
    barsSince(C, since).some(b => b.includes('超出容量')), { chip, cells: cellsOf(live()), bars: barsSince(C, since).slice(-1) })
  await C.clickWindow(0, 0, 0)
  await sleep(800)
  check('a statue taken out comes back to the inventory', bottomSlot(live(), 'lion_statue', 18) >= 0 &&
    nameOf(live().slots[13]).includes('12/3 格'), { cells: cellsOf(live()), info: nameOf(live().slots[13]) })
  C.quit()
  bots.splice(bots.indexOf(C), 1)

  const A = await make(`rv${run}a`)
  const B = await make(`rv${run}b`)
  for (const bot of [A, B]) await rcon(`op ${bot.username}`)
  A.chat(`/cv party invite ${B.username}`)
  await sleep(800)
  B.chat('/cv party accept')
  await sleep(800)
  A.chat('/cv queue proving NORMAL TRIO')
  log('secure: squad queued; waiting for the raid')
  if (!await enterRaid([A, B])) return 2
  await clearArea(A)
  await tp(B, 45.5, 65, 94.5, 180, 0)
  await tp(A, 45.5, 65, 91.5, 0, 0)
  for (const [id, n] of [['lion_statue', 1], ['master_chip', 1], ['ammo_556_m855', 60], ['gold_bar', 1]]) await rcon(`cv give ${B.username} ${id} ${n}`)
  await sleep(1500)

  // 8. Standing: cells by size. A menu that retitles itself (a refusal shown after the title) opens
  // its screen again, so the window is always read from B.currentWindow, never kept.
  const cur = () => B.currentWindow || { slots: [], title: '' }
  let w = await opened(B, () => B.chat('/cv secure'))
  if (!w || !titleOf(w).includes('安全箱')) {
    log('SETUP: /cv secure opened no secure container while standing', titleOf(w))
    return 2
  }
  const TOP = 18
  const missing = ['lion_statue', 'master_chip', 'ammo_556_m855', 'gold_bar'].filter(id => bottomSlot(cur(), id, TOP) < 0)
  if (missing.length) {
    log('SETUP: B does not carry', missing)
    return 2
  }
  check('a fresh container shows 3 open cells and 6 locked ones', cellsOf(cur()).join(' ') === '- - - # # # # # #', cellsOf(cur()))
  const put = async id => { await B.clickWindow(bottomSlot(cur(), id, TOP), 0, 0); await sleep(800) }
  since = Date.now()
  await put('lion_statue')
  check('the lion statue (6 cells) does not go into a 3-cell container', !cellsOf(cur()).some(c => c.startsWith('lion_statue')) &&
    bottomSlot(cur(), 'lion_statue', TOP) >= 0, cellsOf(cur()))
  check('B is told the statue takes 6 cells of 3', barsSince(B, since).some(b => b.includes('占 6 格') && b.includes('只有 3 格')), barsSince(B, since).slice(-2))
  await put('master_chip')
  check('the master chip goes in and takes 2 cells (the second an occupied marker)', cellsOf(cur()).slice(0, 2).join(' ') === 'master_chip:1 =' &&
    bottomSlot(cur(), 'master_chip', TOP) < 0, cellsOf(cur()))
  check('the occupied marker names the chip', nameOf(cur().slots[1]).includes('机密主控芯片'), nameOf(cur().slots[1]))
  since = Date.now()
  await put('ammo_556_m855')
  check('60 rounds into the one cell left: 30 go in, 30 stay with B', cellsOf(cur())[2] === 'ammo_556_m855:30' && countIn(cur(), 'ammo_556_m855', TOP) === 30,
    { cells: cellsOf(cur()), left: countIn(cur(), 'ammo_556_m855', TOP) })
  check('B is told only 30 rounds went in', barsSince(B, since).some(b => b.includes('放入 30 发')), barsSince(B, since).slice(-1))
  check('the info button reads 3/3 格', nameOf(cur().slots[13]).includes('3/3 格'), nameOf(cur().slots[13]))
  since = Date.now()
  await put('gold_bar')
  check('a full container takes no gold bar and says so', !cellsOf(cur()).some(c => c.startsWith('gold_bar')) &&
    barsSince(B, since).some(b => b.includes('空间不足')), { cells: cellsOf(cur()), bars: barsSince(B, since).slice(-1) })
  // The rounds come back out, so the downed B has a free cell for the gold bar it may not put in.
  await B.clickWindow(2, 0, 0)
  await sleep(800)
  const before = cellsOf(cur()).filter(c => c.includes(':')).sort()
  check('taking the rounds back out frees their cell', before.join(' ') === 'master_chip:1' && cellsOf(cur())[2] === '-' &&
    countIn(cur(), 'ammo_556_m855', TOP) === 60, { cells: cellsOf(cur()), rounds: countIn(cur(), 'ammo_556_m855', TOP) })

  // 9. Knocked down with the container open: the server closes it.
  let closedAt = 0
  B._client.on('close_window', () => { closedAt = Date.now() })
  const downAt = Date.now()
  await rcon(`damage ${B.username} 500 minecraft:generic`)
  if (!await waitFor(() => titled(B, '第一次倒地'), 3000)) {
    log('SETUP: B was not knocked down', B.titles)
    return 2
  }
  await sleep(500)
  check('knocked down with the secure container open, B gets its window closed', closedAt >= downAt && !B.currentWindow,
    closedAt ? closedAt - downAt : 'never')
  // Whenever a secure container is open to the downed B anyway, it tries to save the gold bar in it
  // (the profile check in 11 catches it).
  const tryGold = async () => {
    if (titleOf(B.currentWindow).includes('安全箱') && bottomSlot(cur(), 'gold_bar', TOP) >= 0) {
      await B.clickWindow(bottomSlot(cur(), 'gold_bar', TOP), 0, 0).catch(() => {})
      await sleep(800)
    }
  }
  await tryGold()

  // 10. Downed: /cv secure, /cv and the terminal item.
  since = Date.now()
  w = await opened(B, () => B.chat('/cv secure'))
  await sleep(800)
  check('downed, /cv secure opens no secure container', !w || !titleOf(w).includes('安全箱'), titleOf(w).slice(0, 80))
  const said = () => barsSince(B, since).find(b => b.includes('倒地时无法使用安全箱')) || barsSince(B, since).slice(-1)
  check('downed, the bleed-out bar says the secure container cannot be used', barsSince(B, since).some(b => b.includes('倒地时无法使用安全箱')), said())
  await tryGold()
  if (B.currentWindow) B.closeWindow(B.currentWindow)
  w = await opened(B, () => B.chat('/cv'))
  check('downed, /cv opens the tactical terminal, not the container', !!w && titleOf(w).includes('战术终端') && !titleOf(w).includes('安全箱'), titleOf(w).slice(0, 80))
  if (w) {
    check('the terminal\'s secure container button shows its own locked picture', uiModel(w.slots[10]) === 'craftov:ui/icon_secure_locked', uiModel(w.slots[10]))
    check('the gunsmith, the skill (vanguard) and the test armory are locked too, each with its own picture',
      uiModel(w.slots[20]) === 'craftov:ui/icon_gunsmith_locked' && uiModel(w.slots[16]) === 'craftov:ui/op_vanguard_locked' &&
      uiModel(w.slots[26]) === 'craftov:ui/icon_catalog_locked', [uiModel(w.slots[20]), uiModel(w.slots[16]), uiModel(w.slots[26])])
    since = Date.now()
    const inner = await opened(B, () => B.clickWindow(10, 0, 0))
    check('clicking the locked button opens no secure container', !inner || !titleOf(inner).includes('安全箱'), titleOf(inner).slice(0, 80))
    check('and says why', barsSince(B, since).some(b => b.includes('倒地时无法使用安全箱')), said())
    await tryGold()
    if (B.currentWindow) B.closeWindow(B.currentWindow)
    await sleep(500)
    w = await opened(B, () => B.chat('/cv'))
    since = Date.now()
    const armory = w ? await opened(B, () => B.clickWindow(26, 0, 0)) : null
    await sleep(500)
    check('clicking the locked test armory opens no armory (it hands out injectors)', !!w && !titleOf(B.currentWindow).includes('测试军械库'),
      titleOf(armory).slice(0, 80))
    check('and the bleed-out bar says why', barsSince(B, since).some(b => b.includes('倒地时无法使用测试军械库')), barsSince(B, since).slice(-1))
    if (B.currentWindow) B.closeWindow(B.currentWindow)
    await sleep(500)
  }
  w = await opened(B, () => B.chat('/cv items'))
  check('downed, /cv items opens no test armory', !w || !titleOf(w).includes('测试军械库'), titleOf(w).slice(0, 80))
  if (B.currentWindow) B.closeWindow(B.currentWindow)
  await sleep(500)
  B.setQuickBarSlot(8)
  await sleep(400)
  w = await opened(B, () => B.activateItem())
  check('downed, the terminal item (hotbar slot 9) opens the terminal, not the container', !!w && titleOf(w).includes('战术终端') && !titleOf(w).includes('安全箱'),
    titleOf(w).slice(0, 80))
  if (w) B.closeWindow(w)
  await sleep(500)

  // 11. Dead: the saved secure container is the one from before the knock-down. Only hostile damage
  // finishes a downed player (RaidManager.onDamage): an explosion counts, a generic hit does not.
  await rcon(`damage ${B.username} 500 minecraft:explosion`)
  await waitFor(() => B.game.gameMode === 'spectator', 5000)
  check('a second hit kills the downed B', B.game.gameMode === 'spectator', B.game.gameMode)
  let saved = null
  await waitFor(() => {
    const p = savedProfile(B.username)
    saved = p && (p.secure || []).map(r => `${r.id}:${r.amount}`).sort()
    return p && !p.activeRaid && saved.join(' ') === before.join(' ')
  }, 20000)
  check('B\'s saved secure container is what it held before the knock-down (no gold bar)', before.length > 0 && !!saved &&
    saved.join(' ') === before.join(' '), { saved, before })
  return 0
}

;(async () => {
  let code = 0
  if (WHICH === 'all' || WHICH === 'squad') code = Math.max(code, await squad())
  for (const bot of bots.splice(0)) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  if (code < 2 && (WHICH === 'all' || WHICH === 'solo')) code = Math.max(code, await solo())
  for (const bot of bots.splice(0)) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  if (code < 2 && (WHICH === 'all' || WHICH === 'secure')) code = Math.max(code, await secure())
  finish(code === 2 ? 2 : checks.every(c => c.ok) ? 0 : 1)
})().catch(e => { log('ERROR', e.stack || e.message); finish(2) })

async function finish (code) {
  for (const bot of bots) {
    try { await rcon(`deop ${bot.username}`) } catch (e) { }
    try { bot.quit() } catch (e) { }
  }
  await sleep(500)
  process.exit(code)
}
