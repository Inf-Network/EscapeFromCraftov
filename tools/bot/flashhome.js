// A flashbang's white-out ends with the raid (issue #61): flashed and then sent home, or killed,
// the player's screen must clear at once instead of fading out over the next four seconds.
//
//   node flashhome.js                 all three scenarios
//   node flashhome.js lobby death     only these
//
//   lobby  flashed in the face on the proving ground, the bot leaves with /cv lobby while the
//          screen is still fully white: within half a second its head slot is a plain item,
//          and no head-slot update from then on carries a misc/flash_* camera overlay
//   death  flashed the same way and then killed: by 300 ms after turning spectator the head slot
//          is plain and the white-out's blindness is lifted, and neither comes back on the death
//          camera
//   blast  at a tenth of its health, a flashbang ahead and then a frag held in the hand until it
//          goes off while the screen is still white: a death through the weapon damage path,
//          which shows the red hit rim after the hit. Once the bot is a spectator no head-slot
//          update carries any camera overlay (white-out, hit rim, blood edge), and the last one
//          is plain
//
// Before the fix (#61) the white-out stepped down over the lobby and the death camera for about two
// seconds after either, and the blindness stayed on the dead for a second; a killing hit put the red
// rim back on the death camera for 0.3 s after dying had cleared it.
//
// Camera overlays reach the client as a client-only helmet on the bot's own head slot: an equipment
// packet about itself, or the real head slot (inventory slot 5) dressed with the overlay.
//
// Needs a local test server (server/setup.sh --local) with RCON on and test mode on
// (test: { enabled: true }) so the bot can queue for the proving ground; match.wait-seconds: 2
// keeps the run short. The bot is opped for /cv lobby and deopped at the end. Ports come from PORT
// (25565), RCON_PORT (25575) and RCON_PASSWORD (craftov-local). Each scenario prints PASS or FAIL
// with what was seen; the exit code is the number of failures (2 more when a scenario could not be
// set up).
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const BLINDNESS = 14
let failures = 0

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(50) }
  return false
}

function check (name, ok, seen) {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${seen === undefined ? '' : ' -- ' + JSON.stringify(seen)}`)
}

/** The camera overlay a head item carries (flash_4, scope ...), or null for a plain item. */
const overlayOf = item => (JSON.stringify(item || {}).match(/misc\/([a-z0-9_]+)/) || [])[1] || null

/** A fresh bot (random name: a reused one would rejoin an old raid) recording its head slot and effects. */
async function make () {
  const name = 'Fh' + Math.floor(Math.random() * 90000)
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldName = ''
  bot.heads = []   // {at, via, overlay}: every update of the bot's own head slot
  bot.effects = [] // {at, id, on}: potion effects put on or taken off the bot
  const world = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', world)
  bot._client.on('respawn', world)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot._client.on('entity_equipment', p => {
    if (!bot.entity || p.entityId !== bot.entity.id) return
    for (const e of p.equipments) if (e.slot === 5) bot.heads.push({ at: Date.now(), via: 'equipment', overlay: overlayOf(e.item) })
  })
  bot._client.on('set_slot', p => { if (p.windowId === 0 && p.slot === 5) bot.heads.push({ at: Date.now(), via: 'slot', overlay: overlayOf(p.item) }) })
  bot._client.on('window_items', p => { if (p.windowId === 0 && p.items) bot.heads.push({ at: Date.now(), via: 'window', overlay: overlayOf(p.items[5]) }) })
  // Where the head-slot list stood when the bot turned spectator: what came after is known by
  // packet order, not by the clock (the same tick sends both).
  bot.spectatorAt = -1
  bot._client.on('game_state_change', p => {
    if ((p.reason === 'change_game_mode' || p.reason === 3) && Math.round(p.gameMode) === 3) bot.spectatorAt = bot.heads.length
  })
  bot._client.on('entity_effect', p => { if (bot.entity && p.entityId === bot.entity.id) bot.effects.push({ at: Date.now(), id: p.effectId, on: true }) })
  bot._client.on('remove_entity_effect', p => { if (bot.entity && p.entityId === bot.entity.id) bot.effects.push({ at: Date.now(), id: p.effectId, on: false }) })
  bot.on('kicked', r => log(name, 'KICKED', r))
  bot.on('error', e => log(name, 'ERROR', e.message))
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  return bot
}

const inRaidWorld = bot => bot.worldName.startsWith('craftov_raid_')
const inLobby = bot => bot.worldName === 'craftov_lobby'

function heldSlot (bot, id) {
  return bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes(`"value":"${id}"`))
}

/** Solo into the proving ground with two flashbangs and {@code extra} items ("frag 1"). Returns whether it got in. */
async function deploy (bot, extra) {
  const name = bot.username
  await rcon(`cv give ${name} flash 2`)
  if (extra) await rcon(`cv give ${name} ${extra}`)
  bot.chat('/cv queue proving NORMAL SOLO')
  if (!await waitFor(() => inRaidWorld(bot) && bot.game.gameMode === 'adventure', 90000)) {
    log('not inserted')
    return false
  }
  await sleep(3500) // spawn protection and the landing
  // Soldiers shoot from afar; the bot must live until it is killed on purpose.
  await rcon(`effect give ${name} resistance 120 4 true`)
  return true
}

/**
 * A flashbang tapped a few blocks ahead (or, at pitch 90, at the feet, for a longer white-out),
 * facing it; it goes off 2 s later. Returns when it left the hand, or null.
 */
async function throwFlash (bot, attempt, pitch = 35) {
  const slot = heldSlot(bot, 'flash')
  if (slot < 0) return null
  bot.setQuickBarSlot(slot - 36)
  await rcon(`execute as ${bot.username} at @s run tp @s ~ ~ ~ ${attempt * 180} ${pitch}`)
  await sleep(600)
  const from = Date.now()
  bot.activateItem()
  await sleep(200)
  bot.deactivateItem()
  return from
}

const whiteSince = (bot, from) => bot.heads.find(h => h.at >= from && h.overlay === 'flash_4')

/**
 * Into the proving ground, then flashed until the screen is fully white (misc/flash_4). Returns
 * when it was, or null.
 */
async function flashed (bot) {
  if (!await deploy(bot)) return null
  for (let attempt = 0; attempt < 2; attempt++) {
    const from = await throwFlash(bot, attempt)
    if (from == null) break
    if (await waitFor(() => whiteSince(bot, from), 5000)) return whiteSince(bot, from).at
    log('no white-out from throw', attempt + 1)
  }
  return null
}

/** Head-slot updates and effects from {@code since}, relative to it, for the report. */
function seen (bot, since) {
  return {
    heads: bot.heads.filter(h => h.at >= since).map(h => `${h.at - since}ms ${h.via} ${h.overlay || 'plain'}`),
    blindness: bot.effects.filter(e => e.at >= since && e.id === BLINDNESS).map(e => `${e.at - since}ms ${e.on ? 'on' : 'off'}`)
  }
}

const scenarios = {
  async lobby (bot) {
    await rcon(`op ${bot.username}`)
    const white = await flashed(bot)
    if (white == null) return false
    await sleep(300)
    const home = Date.now()
    bot.chat('/cv lobby')
    if (!await waitFor(() => inLobby(bot), 5000)) {
      log('/cv lobby did not bring the bot home')
      return false
    }
    await sleep(4500) // longer than any white-out (80 ticks of a full facing flash)
    const after = bot.heads.filter(h => h.at >= home)
    const plain = after.find(h => h.overlay === null)
    check('lobby: the head slot is plain within 0.5 s of /cv lobby', !!plain && plain.at - home <= 500, seen(bot, home))
    check('lobby: no white-out on the head slot from 0.5 s after /cv lobby on',
      !after.some(h => h.at > home + 500 && h.overlay && h.overlay.startsWith('flash')))
    check('lobby: the last head-slot update is plain', after.length > 0 && after[after.length - 1].overlay === null)
    return true
  },

  async death (bot) {
    const white = await flashed(bot)
    if (white == null) return false
    await sleep(300)
    const blindBefore = bot.effects.filter(e => e.id === BLINDNESS)
    const blind = blindBefore.length > 0 && blindBefore[blindBefore.length - 1].on
    await rcon(`effect clear ${bot.username} resistance`)
    const killed = Date.now()
    await rcon(`damage ${bot.username} 500 minecraft:generic`)
    if (!await waitFor(() => bot.game.gameMode === 'spectator', 600)) await rcon(`damage ${bot.username} 500 minecraft:explosion`)
    if (!await waitFor(() => bot.game.gameMode === 'spectator', 2000)) {
      log('the bot did not die')
      return false
    }
    bot.physicsEnabled = false
    const dead = Date.now()
    await sleep(4000) // the death camera holds for five seconds without a killer to replay
    const after = bot.heads.filter(h => h.at >= killed)
    const plain = after.find(h => h.overlay === null)
    check('death: the head slot is plain by 300 ms after dying', !!plain && plain.at - dead <= 300, seen(bot, killed))
    check('death: no white-out on the head slot on the death camera from 300 ms on',
      !after.some(h => h.at > dead + 300 && h.overlay && h.overlay.startsWith('flash')))
    const off = bot.effects.find(e => e.at >= killed && e.id === BLINDNESS && !e.on)
    check('death: the white-out\'s blindness is lifted by 300 ms after dying', blind && !!off && off.at - dead <= 300,
      { blindWhenKilled: blind, liftedAfterMs: off ? off.at - dead : null })
    return true
  },

  async blast (bot) {
    if (!await deploy(bot, 'frag 1')) return false
    const frag = heldSlot(bot, 'frag')
    const thrown = await throwFlash(bot, 0, 90)
    if (frag < 0 || thrown == null) {
      log('no frag or flashbang in the hotbar')
      return false
    }
    await rcon(`effect clear ${bot.username} resistance`)
    // A frag going off in the hand takes about two thirds of full health through the proving
    // ground's vest: down to a tenth first, so it kills, with the low-health blood edge under the
    // white-out. Health is scaled to 20 on the client, so the real maximum comes from the server.
    const max = Number((await rcon(`attribute ${bot.username} minecraft:max_health get`)).match(/([\d.]+)\s*$/)?.[1] || 20)
    await rcon(`damage ${bot.username} ${Math.round(max * 0.9)} minecraft:generic`)
    // The pin comes out once the 1 s throw cooldown from the flashbang's release is over: the
    // flashbang goes off 0.6 s into the frag's 3.5 s fuse, so the frag goes off in the hand 2.9 s
    // into the white-out, which lasts about 3.6 s from a flashbang at the feet.
    await sleep(Math.max(0, thrown + 1400 - Date.now()))
    bot.setQuickBarSlot(frag - 36)
    await sleep(50)
    bot.activateItem()
    if (!await waitFor(() => bot.game.gameMode === 'spectator', 6000)) {
      bot.deactivateItem()
      log('the frag did not kill the bot')
      return false
    }
    bot.physicsEnabled = false
    await sleep(3000)
    const mark = bot.spectatorAt
    const report = { ...seen(bot, thrown), spectatorAfter: mark < 0 ? null : bot.heads.slice(0, mark).filter(h => h.at >= thrown).length }
    if (mark < 0 || !whiteSince(bot, thrown)) {
      log('no white-out or no switch to spectator', report)
      return false
    }
    // Dying clears the camera before the switch to spectator: the last overlay before it must be
    // the white-out (the frag went off with the screen still white, and clearing it let no blood
    // edge out from under it).
    const shown = bot.heads.slice(0, mark).filter(h => h.at >= thrown && h.overlay)
    check('blast: the white-out goes straight to a plain screen at death',
      shown.length > 0 && shown[shown.length - 1].overlay.startsWith('flash'), report)
    check('blast: no camera overlay on the head slot once dead', !bot.heads.slice(mark).some(h => h.overlay), report)
    const heads = bot.heads.filter(h => h.at >= thrown)
    check('blast: the last head-slot update is plain', heads[heads.length - 1].overlay === null)
    return true
  }
}

;(async () => {
  const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(scenarios)
  for (const name of wanted) {
    if (!scenarios[name]) {
      console.log('unknown scenario', name)
      failures += 2
      continue
    }
    log('scenario', name)
    const bot = await make()
    try {
      if (!await scenarios[name](bot)) {
        console.log(`FAIL ${name}: could not be set up`)
        failures += 2
      }
    } finally {
      await rcon(`deop ${bot.username}`).catch(() => {})
      bot.end('disconnect.quitting')
      await sleep(1000)
    }
  }
  console.log(failures ? `${failures} failure(s)` : 'all passed')
  process.exit(failures)
})().catch(e => { console.error(e); process.exit(99) })
