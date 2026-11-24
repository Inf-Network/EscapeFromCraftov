// Plumbing the smaller bot scripts share (test, squad, econ, meds, doors, reload, proving): bots that
// accept the resource pack and remember what they were told, PASS / FAIL lines with an exit code,
// and the vanilla client's trigger pull (left mouse digs the capture block in front of the
// crosshair, issue #33).
//
//   const c = require('./common')
//   const bot = await c.make('Tst' + c.tag)
//   c.check('the terminal opens', !!await c.openWith(bot, () => bot.chat('/cv')))
//   await c.finish()                     // quits the bots, exits with the number of failures
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const env = require('./env')
const rconRaw = require('./rcon')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd).then(s => s.replace(/§x(§[0-9a-f]){6}/gi, '').replace(/§./g, ''))
/** Four random digits for this run's bot names, so every run starts with fresh accounts. */
const tag = String(Math.floor(1000 + Math.random() * 9000))

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === '' || detail == null ? '' : ' | ' + (typeof detail === 'string' ? detail : JSON.stringify(detail))}`)
  return !!ok
}

const bots = []
/** Quits every bot and exits with the number of failed checks (plus `extra`, for a broken setup). */
async function finish (extra = 0) {
  for (const bot of bots.splice(0)) { try { bot.quit() } catch { } }
  log(failures.length ? `FAIL ${failures.length}: ${failures.join('; ')}` : 'PASS all checks')
  await sleep(500)
  process.exit(failures.length + extra)
}
/** For `main().catch(crashed)`: a scenario that threw counts as a broken setup (exit 2+). */
function crashed (e) {
  log('ERROR', e && (e.stack || e.message))
  finish(2)
}

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes). */
function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n.extra)
}

/**
 * A bot that accepts the resource pack and keeps: msgs (chat and action-bar lines), worldName and
 * worldChanges, sounds (craftov sound keys heard, with the time) and reports (its first-person gun
 * reports, with the rounds each holds). Resolves 2.5 s after it spawned.
 */
function make (name, { quiet = false } = {}) {
  const bot = mineflayer.createBot({ host: env.HOST, port: env.PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.msgs = []
  bot.worldName = ''
  bot.worldChanges = 0
  bot.sounds = []
  bot.reports = []
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', p => { track(p); bot.worldChanges++ })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.msgs.push(m); if (!quiet) log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.msgs.push(s); if (!quiet) log(name, 'BAR', s) } })
  const onSound = p => {
    const key = (JSON.stringify(p.sound || p.soundId || '').match(/craftov:[a-z0-9_.\/]+/) || [null])[0]
    if (!key) return
    bot.sounds.push({ at: Date.now(), key })
    const m = key.match(/gun\.([a-z0-9_]+?)(?:\.x(\d))?\.fp$/)
    if (m) bot.reports.push({ at: Date.now(), gun: m[1], rounds: m[2] ? Number(m[2]) : 1 })
  }
  bot._client.on('sound_effect', onSound)
  bot._client.on('entity_sound_effect', onSound)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', async () => { clearTimeout(timer); await sleep(2500); resolve(bot) })
  })
}

async function waitFor (pred, ms, every = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}
/** The first line since mark matching re, waiting up to ms for it; null if none came. */
async function heard (bot, re, mark, ms = 5000) {
  let line = null
  await waitFor(() => { line = bot.msgs.slice(mark).find(m => re.test(m)) || null; return line !== null }, ms)
  return line
}
function openWith (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, ms)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}

const components = item => JSON.stringify(item && item.components || '')
/** The Craftov item id written on a stack (its custom data), or null. */
const itemId = item => { const m = components(item).match(/"craftov:id":\{"type":"\w+","value":"([a-z0-9_]+)"/); return m ? m[1] : null }
/** Hotbar index (0-8) of the first stack with this id, or -1. */
const hotbar = (bot, id) => { for (let i = 0; i < 9; i++) if (itemId(bot.inventory.slots[36 + i]) === id) return i; return -1 }
/** How many of an item the bot carries. */
const carried = (bot, id) => bot.inventory.items().filter(i => itemId(i) === id).reduce((n, i) => n + i.count, 0)
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
/** The saved profile of a player (SERVER_DIR's data/players), or {} while there is none. */
function profile (name) {
  try { return JSON.parse(fs.readFileSync(path.join(env.DATA, 'data', 'players', offlineUuid(name) + '.json'), 'utf8')) } catch { return {} }
}
async function money (name) {
  const m = (await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}
/** A daily contract finished ("[每日任务完成] 成功撤离 1 次 +◈7,000 +475 经验") and the bonus for all three. */
const TASK_PAID = [/\[每日任务完成\].*\+◈([\d,]+)/, /今日任务全部完成！.*额外奖励 ◈([\d,]+)/]
/** Any other reward paid in chat: a level-up, a collection milestone, the guide's reward. */
const REWARD_LINE = /奖励 ◈[\d,]+/
/**
 * What `bot` was told it was paid in its lines since `mark` (bot.msgs, or bot.chatLog for a bot that
 * keeps that). `tasks` is the money of the daily contracts it finished and of their all-done bonus:
 * extracting can finish one (成功撤离), so a balance check across an extraction takes that off.
 * `others` lists every other reward line (a level-up, a collection milestone, the guide's reward)
 * that `allow` does not match: no balance check expects one, so the caller fails on them.
 */
function announcedSince (bot, mark, { allow = [] } = {}) {
  let tasks = 0
  const others = []
  for (const m of (bot.msgs || bot.chatLog).slice(mark)) {
    const paid = TASK_PAID.map(re => m.match(re)).find(Boolean)
    if (paid) tasks += Number(paid[1].replace(/,/g, ''))
    else if (REWARD_LINE.test(m) && !allow.some(re => re.test(m))) others.push(m)
  }
  return { tasks, others }
}

/** Teleports the bot (as itself, so into its own world) and waits for the move to arrive. */
async function tp (bot, where) {
  await rcon(`execute as ${bot.username} at @s run tp @s ${where}`)
  await sleep(900)
}
/** Out through an extract at `where` (the proving ground's always-open one by default): true once in the lobby. */
async function extract (bot, where = '106.5 65 8.5') {
  const before = bot.worldChanges
  await rcon(`execute as ${bot.username} at @s run tp @s ${where}`)
  return waitFor(() => bot.worldChanges > before && inLobby(bot), 30000)
}
/** Kills every soldier, drone and operator on the server, so they leave the bots alone. */
async function clearSoldiers () {
  for (const type of ['zombie', 'husk', 'drowned', 'bee']) await rcon(`kill @e[type=minecraft:${type}]`)
}
/**
 * Clears the open ground of the proving ground around (45, 65, 93) in the bot's raid world: a stone
 * floor with air above, somewhere to stand, fall and crawl. Then stands the bot at 45.5 65 94.5.
 */
async function clearArea (bot) {
  await rcon(`execute at ${bot.username} run fill 39 64 85 52 64 100 minecraft:stone`)
  await rcon(`execute at ${bot.username} run fill 39 65 85 52 70 100 minecraft:air`)
  await tp(bot, '45.5 65 94.5 0 0')
}

/** Onto the private range through the lobby's weapon rack: true once there. */
async function enterRange (bot) {
  await rcon(`execute as ${bot.username} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (rack) bot.activateEntity(rack)
  return waitFor(async () => (await rcon(`data get entity ${bot.username} Dimension`)).includes('craftov_range'), 6000, 500)
}
/** Off the private range the player's way: the leave item on hotbar key 9, used. True once in the lobby. */
async function leaveRange (bot) {
  bot.setQuickBarSlot(8)
  await sleep(300)
  bot.activateItem()
  await sleep(300)
  bot.deactivateItem()
  return waitFor(() => inLobby(bot), 6000)
}

let sequence = 1
const dig = (bot, status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
/** Q as the client sends it: a reload with a gun in hand. */
const dropKey = bot => dig(bot, 4)
/** The capture block the server put in front of the crosshair, or null when there is none. */
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
  return null
}
/**
 * Left mouse as the vanilla client sends it with a gun out: dig start on the capture block, a swing
 * every tick for `ms` (one swing for a click), abort. False when no capture block was in front.
 */
async function pullTrigger (bot, ms = 0) {
  const cell = await captureCell(bot)
  if (!cell) return false
  dig(bot, 0, cell)
  const end = Date.now() + ms
  do { bot._client.write('arm_animation', { hand: 0 }); await sleep(50) } while (Date.now() < end)
  dig(bot, 1, cell)
  return true
}
/** Rounds the bot heard itself fire since `from` (an index into bot.reports). */
const roundsSince = (bot, from) => bot.reports.slice(from).reduce((n, r) => n + r.rounds, 0)

/** Right mouse held for `ms` (a use packet every 4 ticks, as the client sends them): uses the item in hand. */
async function useItem (bot, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
    await sleep(200)
  }
}

module.exports = {
  env, rcon, sleep, log, tag, check, failures, finish, crashed, flat, make, bots, waitFor, heard, openWith,
  itemId, hotbar, carried, inRaid, inLobby, offlineUuid, profile, money, announcedSince, tp, extract, clearSoldiers, clearArea, enterRange, leaveRange,
  dig, dropKey, captureCell, pullTrigger, roundsSince, useItem
}
