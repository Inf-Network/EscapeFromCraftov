// The training raid (issue #7): a solo course on the training ground with nothing at stake.
// Headless bots with fresh random names:
//
//   course  (Tr####) a new player opens the deploy screen: its first card is 训练行动, and the
//           click lands the bot on the course with the kit (K47 with 8 rounds, no gear of its own).
//           The east gate stays shut until the crate is emptied; hip fire at a target does not
//           count, aimed hits do (1/3 .. 3/3); a reload; the tripwire breaks a leg and opens a
//           bleed, and bandage and splint open the next gate; a lever countdown that runs out
//           with the bot outside the extract sends it back to the lever ("再拉一次电闸"), and the
//           lever again and 10 s in the extract end it; all three targets still stand by then
//           (#151). Back in the lobby: the black curtain, then the training settlement screen;
//           the inventory is exactly what it was, chat has the one summary line, and the saved
//           profile has the same loadout, no raid in its stats, the guide's deploy and extract
//           steps, no reward yet (the range and the gunsmith are still open) and no letter.
//   quit    (Tq####) a bot leaves halfway through the course and joins again: its gear is back,
//           its profile names no raid and holds no letter, and /cv training right away is
//           refused until the cooldown (training.cooldown-seconds) is over.
//   solo    (Ts####) a bot waits in the yard, more than 50 blocks from the course's spawn, until
//           a rival operator squad would have landed there (ai.operator-delay-seconds, 60 by
//           default, plus 15 s): /cv ai lists no soldier in its raid, and the three targets, the
//           30 m one never within 32 blocks of the bot, still stand (#151: the server used to
//           despawn them).
//
//   PORT=25565 RCON_PORT=25575 node training.js [course] [quit] [solo]     env: RCON_PASSWORD, SERVER_DIR
//
// Needs a local test server with RCON on and the whitelist off (or the bots added), the guide on
// (tutorial.enabled, the default) and training.enabled (the default). 'solo' proves something only
// with ai.operator-chance at [1.0, 1.0, 1.0] in plugins/Craftov/config.yml, where every other raid
// gets an operator squad. 'course' and 'quit' run by default; 'solo' takes 80 s. Reads profiles from
// SERVER_DIR/plugins/Craftov/data/players (default ../../server/run), so it runs on the server's
// machine. Prints PASS / FAIL per check; the exit code is the number of failed checks.
//
// Server config (tools/bot/suite.js sets it for its run): training.enabled and tutorial.enabled
// (the defaults), no whitelist; solo proves something only with ai.operator-chance [1.0, 1.0,
// 1.0].
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const env = require('./env')
const { offlineUuid } = require('./common')

const PORT = env.PORT
const RCON = env.RCON
const RUN = env.SERVER_DIR
const PLAYERS = path.join(RUN, 'plugins/Craftov/data/players')
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = c => rconRaw(c, RCON).then(r => r.replace(/§./g, ''))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['course', 'quit']
const OPERATOR_DELAY = Number(process.env.OPERATOR_DELAY || 60)
const failures = []
function check (name, ok, detail) {
  if (!ok) failures.push(name)
  log(ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : detail)
}

// Course positions (TrainingGroundGenerator, ground at y 64).
const G = 64
const GATES = { loot: 15, range: 45, heal: 60 }
const TARGETS = [[22, -7], [30, -17], [38, -27]]
const CRATE = new Vec3(11.5, G + 1, 10.5)
const LEVER = new Vec3(65, G + 2, 5)
const EXTRACT = '74.5 65 10.5'

function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') {
    if (n.startsWith('{') || n.startsWith('[')) { try { return flat(JSON.parse(n)) } catch (e) { return n } }
    return n
  }
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  // A mixed list wraps its plain strings as {"": text}; children come after a node's own text.
  return flat(n['']) + flat(n.text) + flat(n.keybind) + flat(n.extra)
}

async function waitFor (pred, ms, every = 200) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}

const profileFile = name => path.join(PLAYERS, offlineUuid(name) + '.json')

/**
 * Which copy of the bot's profile is on disk now, or null before the first save. Every save
 * replaces the file (AtomicFiles), so a newer copy has another inode or a later mtime.
 */
function stamp (name) {
  try { const s = fs.statSync(profileFile(name)); return { ino: s.ino, mtimeMs: s.mtimeMs } } catch (e) { return null }
}

/**
 * The profile as saved after `before` (the stamp taken just before the bot left): the save that
 * follows the bot leaving. Told by the file, not by the clock: files are stamped with the kernel's
 * coarse clock, up to a tick (4 ms at HZ 250) behind Date.now(), and the server saves at its next
 * tick, 0 to 50 ms after the bot quits, so about one save in 60 looked older than the moment the
 * bot left and was never found (#151).
 */
async function savedAfter (name, before) {
  let out = null
  await waitFor(() => {
    const now = stamp(name)
    if (!now || (before && now.ino === before.ino && now.mtimeMs <= before.mtimeMs)) return false
    try { out = JSON.parse(fs.readFileSync(profileFile(name), 'utf8')); return true } catch (e) { return false } // being replaced
  }, 15000)
  return out
}

/**
 * How many targets stand on the bot's course: the zombies in its raid world (the course has no
 * soldiers). The distance keeps @e to that world: without one it counts every world's zombies.
 */
const targetsUp = async bot => Number(((await rcon(`execute in ${bot.worldName} positioned 30 ${G} 0 if entity @e[type=minecraft:zombie,distance=..200]`))
  .match(/Count: (\d+)/) || [, 0])[1])
const hud = async name => rcon(`cv hud ${name}`)
const objectiveOf = async name => ((await hud(name)).match(/objective=([^|]*)/) || [, ''])[1].trim()
const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : (item ? item.name : null) }
/** What the bot carries, slot by slot (UI buttons left out). */
function carried (bot) {
  const out = {}
  bot.inventory.slots.forEach((s, i) => { const id = itemId(s); if (s && id && !id.startsWith('ui/')) out[i] = id + 'x' + s.count })
  return out
}

function join (prefix, name) {
  name = name || prefix + tag
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  Object.assign(bot, { chatLines: [], bars: [], titles: [], screens: [], worldChanges: 0, seq: 1, worldName: '' })
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  // mineflayer applies player_rotation as absolute angles; recoil sends it relative, as vanilla
  // expects, which turned the bot to face about south after the first round of a burst. Its plugins
  // load after createBot returns, so its handler is replaced once logged in (as accuracy.js does).
  bot.once('login', () => {
    bot._client.removeAllListeners('player_rotation')
    bot._client.on('player_rotation', p => {
      const yaw = (p.relativeYaw ? conv.toNotchianYaw(bot.entity.yaw) : 0) + p.yaw
      const pitch = Math.max(-90, Math.min(90, (p.relativePitch ? conv.toNotchianPitch(bot.entity.pitch) : 0) + p.pitch))
      bot.entity.yaw = conv.fromNotchianYaw(yaw)
      bot.entity.pitch = conv.fromNotchianPitch(pitch)
    })
  })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLines.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push({ at: Date.now(), text: s }); log(name, 'BAR', s) } })
  bot._client.on('set_title_text', p => { const s = flat(p.text); bot.titles.push({ at: Date.now(), text: s }) })
  bot._client.on('respawn', () => { bot.worldChanges++; bot.titles.push({ at: Date.now(), text: '<respawn>' }) })
  bot._client.on('open_window', p => { const s = flat(p.windowTitle || p.title); bot.screens.push({ at: Date.now(), title: s }); log(name, 'WINDOW', s) })
  bot._client.on('show_dialog', () => { /* the briefing: left unanswered */ })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(3000); resolve(bot) }))
}

function nextWindow (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, ms)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}

const block = (bot, x, y, z) => { const b = bot.blockAt(new Vec3(x, y, z)); return b ? b.name : 'unloaded' }
const gateShut = (bot, x) => block(bot, x, G + 2, 10) === 'iron_bars'
const tp = (bot, where, yaw = '', pitch = '') => rcon(`execute as ${bot.username} at @s run tp @s ${where} ${yaw} ${pitch}`.trim())
const hotbar = (bot, id) => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
const dig = (bot, status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: bot.seq++ })
const swing = bot => bot._client.write('arm_animation', { hand: 0 })
// The use packet carries the look: the server turns the player to it, so it must be the bot's own.
const useItem = bot => bot._client.write('use_item', { hand: 0, sequence: bot.seq++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })

/** The capture cell the server keeps in front of the crosshair (TriggerService), as the client digs it. */
function cell (bot) {
  const id = bot.registry.blocksByName.sculk_vein.id
  const cells = bot.findBlocks({ matching: id, maxDistance: 6, count: 16 })
  if (!cells.length) return null
  const eye = bot.entity.position.offset(0, 1.62, 0)
  return cells.reduce((a, b) => a.distanceTo(eye) < b.distanceTo(eye) ? a : b)
}

/** The trigger held for `ms`, right mouse held too when `aiming`. */
async function shoot (bot, ms, aiming) {
  let stopAim = null
  if (aiming) { useItem(bot); const t = setInterval(() => useItem(bot), 200); stopAim = () => clearInterval(t); await sleep(700) }
  // The cell follows the crosshair a moment after the bot moved or turned.
  await waitFor(() => { const c = cell(bot); return c && c.distanceTo(bot.entity.position) < 5 }, 2000, 100)
  const at = cell(bot)
  if (!at) { log(bot.username, 'no capture cell'); if (stopAim) stopAim(); return }
  dig(bot, 0, at)
  const until = Date.now() + ms
  while (Date.now() < until) { swing(bot); await sleep(50) }
  dig(bot, 1, at)
  await sleep(300)
  if (stopAim) stopAim()
}

/** Looks at a target from where the bot stands (yaw and pitch in degrees, as /tp takes them). */
function lookAngles (from, to) {
  const dx = to.x - from.x
  const dy = to.y - (from.y + 1.62)
  const dz = to.z - from.z
  const yaw = -Math.atan2(dx, dz) * 180 / Math.PI
  const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI
  return { yaw, pitch }
}

/**
 * The hotbar index holding an item, asked of the server: what the crate gave went into the
 * inventory while the crate's window was open, and bot.inventory never heard of it.
 */
async function serverHotbar (name, id) {
  for (let k = 0; k < 8; k++) {
    if ((await rcon(`execute if items entity ${name} hotbar.${k} *[minecraft:item_model="craftov:${id}"]`)).includes('passed')) return k
  }
  return -1
}

async function useMed (bot, id, ms, known) {
  // `known` is the hotbar index the server reported (serverHotbar).
  const slot = hotbar(bot, id) >= 0 ? hotbar(bot, id) - 36 : known
  if (slot === undefined || slot < 0) { log(bot.username, 'no ' + id + ' on the hotbar', carried(bot)); return false }
  bot.setQuickBarSlot(slot)
  await sleep(1200)
  bot.activateItem()
  await sleep(ms)
  bot.deactivateItem()
  await sleep(800)
  return true
}

async function deployTraining (bot) {
  const w = await nextWindow(bot, () => bot.chat('/cv deploy'))
  check('the deploy screen opens', !!w)
  if (!w) return false
  const first = JSON.stringify(w.slots[10] && (w.slots[10].components || w.slots[10].nbt) || {})
  check('its first card is the training raid', first.includes('训练行动'), first.slice(0, 160))
  const worlds = bot.worldChanges
  await bot.clickWindow(10, 0, 0)
  const landed = await waitFor(() => bot.worldChanges > worlds && bot.game.gameMode === 'adventure', 30000)
  check('clicking it lands the bot on the course', landed)
  return landed
}

async function course () {
  const bot = await join('Tr')
  const name = bot.username
  const before = carried(bot)
  const money = (await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  log(name, 'gear before', before, money && money[0])
  if (!await deployTraining(bot)) { bot.quit(); return }
  await sleep(3500) // spawn protection
  const kit = carried(bot)
  check('the kit is the K47 and no own gear', Object.values(kit).some(v => v.startsWith('k47')) && !Object.values(kit).some(v => /p18|armor_2|helmet_2|pack_1/.test(v)), kit)
  let o = await objectiveOf(name)
  check('the first lesson is the crate', o.includes('搜箱') && o.includes('(compass)'), o)
  check('the east gate is shut', gateShut(bot, GATES.loot), block(bot, GATES.loot, G + 2, 10))

  // Lesson 1: the crate.
  await tp(bot, '9.5 65 10.5', -90, 20)
  await sleep(800)
  const crate = bot.nearestEntity(e => e.name === 'interaction' && e.position.distanceTo(CRATE) < 2)
  check('the crate is there', !!crate)
  if (!crate) { bot.quit(); return }
  const loot = await nextWindow(bot, () => bot.activateEntity(crate))
  check('the crate opens', !!loot)
  const meds = {}
  if (loot) {
    await sleep(6000) // the search
    for (let i = 0; i < loot.inventoryStart; i++) {
      const s = loot.slots[i]
      // Only the crate's own cells: the right-hand columns of the search screen are the bot's gear (#10).
      if (i % 9 < 6 && s && !itemId(s).startsWith('ui/')) { await bot.clickWindow(i, 0, 0); await sleep(250) }
    }
    bot.closeWindow(loot)
    await sleep(500)
    for (const id of ['bandage', 'splint']) meds[id] = await serverHotbar(name, id)
    log(name, 'meds on the hotbar', meds)
  }
  const opened = await waitFor(() => !gateShut(bot, GATES.loot), 4000)
  check('emptying the crate opens the gate', opened, carried(bot))
  o = await objectiveOf(name)
  check('then the targets, 0/3', o.includes('打靶') && o.includes('0/3'), o)

  // Lesson 2: aimed hits on all three targets, then a reload.
  const k47 = hotbar(bot, 'k47')
  bot.setQuickBarSlot(k47 - 36)
  await sleep(1500) // the draw
  const spot = new Vec3(30.5, G + 1, 5.5)
  const aimAt = (i, from = spot) => { const t = new Vec3(TARGETS[i][0] + 0.5, G + 1.4, TARGETS[i][1] + 0.5); return lookAngles(from, t) }
  // Hip fire first, from right in front of the nearest target so that some rounds hit it.
  const close = new Vec3(22.5, G + 1, 4.5)
  let a = aimAt(0, close)
  await tp(bot, `${close.x} ${close.y} ${close.z}`, a.yaw.toFixed(2), a.pitch.toFixed(2))
  await sleep(1200)
  const barsFrom = bot.bars.length
  const told = () => bot.bars.slice(barsFrom).some(b => b.text.includes('没开镜'))
  // Hip fire spreads: short bursts until one lands (the magazine holds 8; a reload halfway).
  // The look is set again before every burst, which the recoil has climbed.
  for (let burst = 0; burst < 6 && !told(); burst++) {
    if (burst === 3) { dig(bot, 4); await sleep(4000) }
    await tp(bot, `${close.x} ${close.y} ${close.z}`, a.yaw.toFixed(2), a.pitch.toFixed(2))
    await sleep(500)
    await shoot(bot, 200, false)
    await sleep(600)
  }
  o = await objectiveOf(name)
  check('a hip-fire hit does not count', o.includes('0/3'), o)
  check('and says to aim first', told(), bot.bars.slice(barsFrom).map(b => b.text))
  // The magazine is about empty now: the reload lesson is done early, and says to go on aiming.
  dig(bot, 4)
  check('a reload before the targets are done says to go on aiming',
    await waitFor(() => bot.bars.slice(barsFrom).some(b => b.text.includes('接着开镜打靶')), 6000))
  const hits = async () => { const o = await objectiveOf(name); const m = o.match(/(\d)\/3/); return o.includes('打靶') ? Number(m ? m[1] : 0) : 3 }
  for (let i = 0; i < 3; i++) {
    for (let tries = 0; tries < 3 && (await hits()) < i + 1; tries++) {
      a = aimAt(i)
      await tp(bot, `${spot.x} ${spot.y} ${spot.z}`, a.yaw.toFixed(2), a.pitch.toFixed(2))
      await sleep(900)
      await shoot(bot, 100, true)
      await sleep(500)
      if (tries > 0) { dig(bot, 4); await sleep(4000) } // perhaps the magazine ran dry: reload and try again
    }
    o = await objectiveOf(name)
    log(name, 'after target', i, o)
  }
  o = await objectiveOf(name)
  check('three aimed hits finish the targets', !o.includes('打靶'), o)
  if (o.includes('换')) {
    dig(bot, 4) // Q
    await waitFor(async () => !(await objectiveOf(name)).includes('换'), 6000)
  }
  const rangeOpen = await waitFor(() => !gateShut(bot, GATES.range), 4000)
  check('after the reload the range gate opens', rangeOpen, await objectiveOf(name))

  // Lesson 3: the tripwire, then bandage and splint.
  await tp(bot, '48.5 65 10.5', -90, 0)
  await sleep(600)
  await tp(bot, '53.5 65 10.5', -90, 0)
  const wounds = async () => Number(((await hud(name)).match(/wounds=(\d+)/) || [, 0])[1])
  const tripped = await waitFor(async () => ((await wounds()) & 5) === 5, 4000) // bleeding and a fracture
  const status = await hud(name)
  check('the tripwire breaks a leg and opens a bleed', tripped, status.slice(0, 300))
  check('the heal gate is shut', gateShut(bot, GATES.heal))
  await useMed(bot, 'bandage', 3300, meds.bandage)
  await useMed(bot, 'splint', 4800, meds.splint)
  const healOpen = await waitFor(() => !gateShut(bot, GATES.heal), 4000)
  check('bandage and splint open the yard', healOpen, (await hud(name)).slice(0, 300))

  // Lesson 4: the lever and the extract.
  await tp(bot, '65.5 65 7.5', 180, 40)
  await sleep(800)
  const lever = bot.blockAt(LEVER)
  check('the lever is there', lever && lever.name === 'lever', lever && lever.name)
  if (lever) await bot.activateBlock(lever)
  await sleep(800)
  o = await objectiveOf(name)
  check('after the lever the objective is the extract', o.includes('撤离'), o)
  // The bot stays by the lever: the countdown runs out, the extract is shut again, and the lesson
  // goes back to the lever instead of pointing at a closed extract.
  const barsAt = bot.bars.length
  const again = await waitFor(async () => (await objectiveOf(name)).includes('再拉一次电闸'), 15000, 500)
  check('a countdown missed outside the extract points back to the lever', again, await objectiveOf(name))
  check('and the action bar says so at once', await waitFor(() => bot.bars.slice(barsAt).some(b => b.text.includes('再拉一次电闸')), 1500),
    bot.bars.slice(barsAt).map(b => b.text))
  await sleep(1500) // the lever springs back a second after its countdown
  if (lever) await bot.activateBlock(bot.blockAt(LEVER))
  await sleep(800)
  o = await objectiveOf(name)
  check('pulled again, the objective is the extract again', o.includes('撤离'), o)
  // The 30 m target has never been within 32 blocks of the bot (33 from the firing spot): before
  // #151 the server had despawned it in more than half the runs by now.
  const standing = await targetsUp(bot)
  check('all three targets still stand at the end of the course', standing === TARGETS.length, `${standing} in ${bot.worldName}`)
  const worlds = bot.worldChanges
  const titlesFrom = bot.titles.length
  const screensFrom = bot.screens.length
  const chatFrom = bot.chatLines.length
  await tp(bot, EXTRACT)
  const home = await waitFor(() => bot.worldChanges > worlds, 20000)
  check('standing in the extract through the countdown ends the course', home)
  const settled = await waitFor(() => bot.screens.slice(screensFrom).some(s => s.title.includes('训练结算')), 5000)
  check('the training settlement screen opens', settled, bot.screens.slice(screensFrom))
  const seq = bot.titles.slice(titlesFrom).map(t => t.text)
  const curtain = seq.findIndex(t => t !== '<respawn>' && t.length > 0)
  const respawn = seq.indexOf('<respawn>')
  check('the curtain comes before the trip home', curtain >= 0 && respawn > curtain, seq)
  const summary = bot.chatLines.slice(chatFrom).filter(l => l.includes('训练完成'))
  check('chat keeps one summary line', summary.length === 1, bot.chatLines.slice(chatFrom))
  await sleep(1500)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(1000)
  const after = carried(bot)
  check('the gear is back exactly as it was', JSON.stringify(after) === JSON.stringify(before), { before, after })
  const left = stamp(name)
  bot.quit()
  const p = await savedAfter(name, left)
  check('the profile was saved after leaving', !!p)
  if (!p) return
  const ids = Object.values(p.loadout || {}).map(r => r.id).sort()
  check('its loadout is the gear it went in with', ids.length > 0 && ids.includes('k47') && ids.includes('p18'), ids)
  check('no raid in its stats', !(p.stats && (p.stats.raids || p.stats.extracts || p.stats.deaths)), p.stats)
  check('no raid on record', !p.activeRaid && !p.deployment, { activeRaid: p.activeRaid, deployment: !!p.deployment })
  check('the guide counts the deploy and the extract', ((p.tutorialMask || 0) & 24) === 24, p.tutorialMask)
  check('no reward yet: the range and the gunsmith are open', !p.tutorialDone, p.tutorialDone)
  check('no letter', !(p.mail || []).some(m => JSON.stringify(m).includes('补偿')), (p.mail || []).length)
}

async function quit () {
  let bot = await join('Tq')
  const name = bot.username
  const before = carried(bot)
  if (!await deployTraining(bot)) { bot.quit(); return }
  await sleep(2000)
  bot.quit()
  await sleep(3000)
  bot = await join('Tq', name)
  await sleep(1500)
  const after = carried(bot)
  check('after leaving mid-course the gear is back', JSON.stringify(after) === JSON.stringify(before), { before, after })
  const raids = await rcon('cv raids')
  check('back in the lobby, with no training raid holding the bot', !/训练场.*玩家 [1-9]/.test(raids), raids)
  // Seconds after the last start: another one waits (training.cooldown-seconds, 30 by default).
  const chatAt = bot.chatLines.length
  const worlds = bot.worldChanges
  bot.chat('/cv training')
  const refused = await waitFor(() => bot.chatLines.slice(chatAt).some(l => l.includes('秒后再开始')), 4000)
  check('a second course right away waits for the cooldown', refused && bot.worldChanges === worlds, bot.chatLines.slice(chatAt))
  const left = stamp(name)
  bot.quit()
  const p = await savedAfter(name, left)
  check('the profile names no raid', p && !p.activeRaid && !p.deployment, p && { activeRaid: p.activeRaid })
  check('and holds no letter', p && !(p.mail || []).some(m => JSON.stringify(m).includes('补偿')), p && (p.mail || []).length)
  check('no raid in its stats', p && !(p.stats && p.stats.raids), p && p.stats)
}

async function solo () {
  const bot = await join('Ts')
  const name = bot.username
  if (!await deployTraining(bot)) { bot.quit(); return }
  const landed = Date.now()
  // The yard: the operators' only spawn point, at x 3, is clear of the player from here.
  await tp(bot, '68.5 65 14.5')
  log(name, 'waiting for the operator squad that must not come')
  await sleep(Math.max(0, landed + (OPERATOR_DELAY + 15) * 1000 - Date.now()))
  // Named by its world: with no name /cv ai only answers when exactly one raid is running.
  const listing = await rcon(`cv ai ${bot.worldName}`)
  const count = (listing.match(/敌兵 (\d+)/) || [, '?'])[1]
  check('no soldier or rival operator ever comes to the course', count === '0', listing)
  // The bot has stood in the yard since it was moved there, far from all three targets (#151).
  const standing = await targetsUp(bot)
  check('the targets still stand after the wait', standing === TARGETS.length, `${standing} in ${bot.worldName}`)
  const raids = await rcon('cv raids')
  check('the bot is still on the course', /训练场 训练 RUNNING.*玩家 1/.test(raids), raids)
  bot.quit()
}

;(async () => {
  const runs = { course, quit, solo }
  for (const s of scenarios) {
    log('scenario', s)
    try {
      await runs[s]()
    } catch (e) {
      check(s + ' ran without an exception', false, e.stack)
    }
    await sleep(1500)
  }
  log('done', failures.length ? 'FAILED: ' + failures.join('; ') : 'all passed')
  process.exit(failures.length)
})()
