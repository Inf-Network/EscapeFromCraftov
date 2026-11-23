// Strobe light and flashbang check (issue #15): neither may freeze a soldier, and a flashed player gets
// a white screen instead of a black one with nausea. One bot fits a tactical light to an M4, enters the
// proving ground solo and then:
//   1. throws a flashbang against the outside of the AI arena wall while the soldiers inside are still
//      at their posts: the soldiers behind the wall within the blast radius must not all stand frozen
//      for the next 3.5 s (the blast draws them, so one walks off, or one shoots);
//   2. facing that flashbang itself, the bot must get a camera overlay craftov:misc/flash_* on its head
//      slot and the ringing sound, and no nausea. Blindness only comes with the fully white screen (so
//      F5 cannot clear it) and must be lifted before the first translucent fade step. The bot wears a
//      helmet that is swapped while it is white: the overlay must be back within half a second of the
//      head slot sync that replaces it on the client;
//   3. switches to strobe (two pick-block packets), waits for a soldier within strobe range to shoot
//      it, steps in to about 4.5 blocks and holds the beam on that soldier for 8 s: the soldier must
//      still hit the bot at least once and move over a block (from that close it backs off unless
//      frozen).
//
//   PORT=25565 RCON_PORT=25575 RCON_PASSWORD=craftov-local node strobe.js
// Exit code 0 when every check passes, 1 when one fails, 2 when the scenario could not be set up.
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = (cmd) => rconRaw(cmd, RCON)
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const SOLDIERS = ['zombie', 'husk', 'drowned']
const checks = {}

// Places outside the arena walls (ProvingGroundGenerator.arena) to throw from, clear of the entrance
// baffles: the bot stands 1.5 blocks off the wall facing it, and the grenade drops against it.
const THROW_SPOTS = []
for (let x = 2; x <= 87; x++) {
  if (x <= 16 || (x >= 25 && x <= 54) || x >= 64) THROW_SPOTS.push({ stand: [x + 0.5, 35.5], yaw: 180, blast: [x + 0.5, 34] })
}
for (let z = 2; z <= 32; z++) {
  if (z <= 15 || z >= 25) THROW_SPOTS.push({ stand: [92.5, z + 0.5], yaw: 90, blast: [92, z + 0.5] })
}
const insideArena = (p) => p.x < 91 && p.z < 33 && p.y < 69 // not the sniper on its tower

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (pred()) return true
    await sleep(200)
  }
  return false
}

function heldSlot (bot, id) {
  return bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes(`"value":"${id}"`))
}

function soldiers (bot) {
  return Object.values(bot.entities).filter(e => e !== bot.entity && SOLDIERS.includes(e.name))
}

const pick = (bot) => bot._client.write('pick_item_from_block', { position: { x: 0, y: 0, z: 0 }, includeData: false })

/**
 * An open spot about 4.5 blocks from the soldier on our side, with nothing between it and the soldier
 * at body height (cover would eat its rounds) and room behind the soldier to back off; or null.
 */
function openSpotNear (bot, soldier) {
  const from = soldier.position
  const away = bot.entity.position.minus(from)
  away.y = 0
  if (away.norm() < 1e-3) return null
  const dir = away.normalize()
  const empty = (b) => b && b.boundingBox === 'empty'
  const open = (p) => empty(bot.blockAt(p)) && empty(bot.blockAt(p.offset(0, 1, 0)))
  for (const d of [4.5, 4, 5, 3.5]) {
    for (const turn of [0, 0.35, -0.35, 0.7, -0.7]) {
      const x = dir.x * Math.cos(turn) - dir.z * Math.sin(turn)
      const z = dir.x * Math.sin(turn) + dir.z * Math.cos(turn)
      const spot = from.offset(x * d, 0, z * d)
      spot.y = Math.floor(from.y)
      let clear = bot.blockAt(spot.offset(0, -1, 0))?.boundingBox === 'block'
      for (let k = 0.5; k <= d && clear; k += 0.5) clear = open(from.offset(x * k, 0, z * k))
      for (let k = 1; k <= 4 && clear; k++) clear = open(from.offset(-x * k, 0, -z * k))
      if (clear) return spot
    }
  }
  return null
}

(async () => {
  const name = 'Sb' + Math.floor(Math.random() * 90000)
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldChanges = 0
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log('KICKED', r))
  bot.on('error', e => log('ERROR', e.message))
  bot.on('death', () => log('bot died'))
  const bars = []
  bot._client.on('action_bar', p => {
    const s = JSON.stringify(p)
    bars.push(s)
    const mode = ['爆闪', '常亮', '关'].find(m => s.includes('战术手电 ') && s.includes(`"value":"${m}"`))
    if (mode) log('light', mode)
  })
  // Who hit us: damage_event carries the attacker's entity id + 1. A soldier's hit names no attacker,
  // only where the shot came from, so that is the soldier nearest that spot.
  const hits = []
  bot._client.on('damage_event', p => {
    if (!bot.entity || p.entityId !== bot.entity.id) return
    let by = p.sourceCauseId - 1
    if (!bot.entities[by] && p.sourcePosition) {
      const from = new Vec3(p.sourcePosition.x, p.sourcePosition.y, p.sourcePosition.z)
      let best = 3
      for (const e of Object.values(bot.entities)) {
        const d = SOLDIERS.includes(e.name) ? e.position.distanceTo(from) : Infinity
        if (d < best) { best = d; by = e.id }
      }
    }
    hits.push({ by, at: Date.now() })
  })
  // Camera overlays arrive as a client-only helmet on our own head slot (5).
  const overlays = []
  bot._client.on('entity_equipment', p => {
    if (!bot.entity || p.entityId !== bot.entity.id) return
    for (const e of p.equipments) {
      if (e.slot !== 5) continue
      const m = JSON.stringify(e.item || {}).match(/misc\/([a-z0-9_]+)/)
      overlays.push({ overlay: m ? m[1] : null, at: Date.now() })
    }
  })
  const effects = []
  bot._client.on('entity_effect', p => { if (bot.entity && p.entityId === bot.entity.id) effects.push({ id: p.effectId, at: Date.now() }) })
  const removedEffects = []
  bot._client.on('remove_entity_effect', p => { if (bot.entity && p.entityId === bot.entity.id) removedEffects.push({ id: p.effectId, at: Date.now() }) })
  // The server syncing our real head slot (player inventory, slot 5) replaces any overlay helmet.
  const headSyncs = []
  bot._client.on('set_slot', p => { if (p.windowId === 0 && p.slot === 5) headSyncs.push({ at: Date.now() }) })
  const sounds = []
  bot.on('soundEffectHeard', (sound, pos) => sounds.push({ sound: String(sound), pos, at: Date.now() }))
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)

  // Loadout: an M4 with a tactical light, two flashbangs (in the hotbar) and ammo.
  for (const cmd of [`cv give ${name} m4c`, `cv give ${name} att_taclight`, `cv give ${name} flash 2`, `cv give ${name} ammo_556_m855 120`]) {
    log('rcon', cmd, (await rcon(cmd)).replace(/§./g, '').trim())
  }
  await sleep(800)
  bot.setQuickBarSlot(heldSlot(bot, 'm4c') - 36) // the bench works on the gun in hand (new players also carry a K47)
  await sleep(500)
  const bench = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), 5000)
    bot.once('windowOpen', w => { clearTimeout(timer); resolve(w) })
    bot.chat('/cv mod')
  })
  if (!bench) throw new Error('gunsmith did not open')
  await bot.clickWindow(45, 0, 0) // first carried attachment: the tactical light
  await sleep(800)
  bot.closeWindow(bench)
  await sleep(500)
  const gunSlot = heldSlot(bot, 'm4c')
  checks.lightFitted = gunSlot >= 0 && JSON.stringify(bot.inventory.slots[gunSlot].components).includes('att_taclight')
  log('light fitted', checks.lightFitted)

  // Solo raid on the proving ground.
  const before = bot.worldChanges
  bot.chat('/cv queue proving NORMAL SOLO')
  if (!await waitFor(() => bot.worldChanges > before && bot.game.gameMode === 'adventure', 90000)) throw new Error('not inserted')
  log('inserted at', bot.entity.position.toString())
  await sleep(3000)
  await rcon(`effect give ${name} resistance 600 2 true`)
  const heal = setInterval(() => rcon(`effect give ${name} instant_health 1 3 true`).catch(() => {}), 1500)

  // ---------------------------------------------------------------- 1. flashbang through the wall
  // Wait outside the arena (out of sight) until a soldier at its post is within 8.5 blocks of a spot
  // where a grenade would land against the wall.
  await rcon(`execute as ${name} at @s run tp @s 47.5 65 35.5 180 10`)
  let spot = null
  await waitFor(() => {
    let best = 0
    for (const s of THROW_SPOTS) {
      const n = soldiers(bot).filter(e => insideArena(e.position) && Math.hypot(e.position.x - s.blast[0], e.position.z - s.blast[1]) < 8.5).length
      if (n > best) { best = n; spot = s }
    }
    return best > 0
  }, 45000)
  if (!spot) throw new Error('no soldier near an arena wall')
  log('throwing from', spot.stand)
  await rcon(`execute as ${name} at @s run tp @s ${spot.stand[0]} 65 ${spot.stand[1]} ${spot.yaw} 10`)
  await sleep(1500)
  bot.setQuickBarSlot(heldSlot(bot, 'flash') - 36)
  await rcon(`item replace entity ${name} armor.head with minecraft:leather_helmet`)
  await sleep(600)
  const overlaysBefore = overlays.length
  const effectsBefore = effects.length
  const removedBefore = removedEffects.length
  const soundsBefore = sounds.length
  bot.activateItem() // the teleport left us facing the wall, slightly down
  await sleep(200)
  bot.deactivateItem()
  if (!await waitFor(() => sounds.slice(soundsBefore).some(s => s.sound.includes('throw.flash')), 6000)) throw new Error('no flashbang went off')
  const blast = sounds.slice(soundsBefore).find(s => s.sound.includes('throw.flash')).pos
  // A new helmet while the screen is white, like one losing durability to a headshot.
  const swapAt = Date.now()
  await rcon(`item replace entity ${name} armor.head with minecraft:iron_helmet`)
  const behind = soldiers(bot).filter(e => insideArena(e.position) && e.position.distanceTo(blast) <= 10)
  log('flashbang at', blast.toString(), 'soldiers behind the wall in range', behind.map(e => ({ id: e.id, d: e.position.distanceTo(blast).toFixed(1) })))
  // Watch them for the 3.5 s the old stun lasted, counting movement from 0.5 s in (a stunned soldier
  // still slides a little when its path is cut) and any shot fired next to one of them.
  const shotsFrom = sounds.length
  await sleep(500)
  const startPos = new Map(behind.map(e => [e.id, e.position.clone()]))
  const maxMove = new Map()
  const stop = Date.now() + 3000
  while (Date.now() < stop) {
    for (const e of behind) maxMove.set(e.id, Math.max(maxMove.get(e.id) || 0, e.position.distanceTo(startPos.get(e.id))))
    await sleep(100)
  }
  const shots = sounds.slice(shotsFrom).filter(s => s.sound.startsWith('craftov:gun.') && behind.some(e => e.position.distanceTo(s.pos) < 4))
  checks.wallSoldiersInRange = behind.length > 0
  // Stunned soldiers all stay within about half a block of where they were 0.5 s after the blast.
  checks.wallSoldiersActive = behind.some(e => (maxMove.get(e.id) || 0) > 1.0) || shots.length > 0
  log('behind the wall', behind.map(e => ({ id: e.id, moved: (maxMove.get(e.id) || 0).toFixed(2) })), 'shots nearby', shots.length)

  // ---------------------------------------------------------------- 2. what the bot got
  await sleep(1500)
  const mine = overlays.slice(overlaysBefore)
  const added = effects.slice(effectsBefore)
  const blindOn = added.filter(e => e.id === 14) // blindness
  const blindOff = removedEffects.slice(removedBefore).filter(e => e.id === 14)
  const firstFade = mine.find(o => /^flash_[123]$/.test(o.overlay || ''))
  checks.noNausea = !added.some(e => e.id === 8)
  checks.blindOnlyWhileWhite = blindOn.length > 0 && blindOff.length > 0 && !!firstFade &&
    blindOff[blindOff.length - 1].at >= blindOn[blindOn.length - 1].at && blindOff[blindOff.length - 1].at <= firstFade.at
  checks.flashOverlay = mine.some(o => o.overlay && o.overlay.startsWith('flash'))
  checks.ringing = sounds.slice(soundsBefore).some(s => s.sound.includes('status.tinnitus'))
  const syncs = headSyncs.filter(h => h.at >= swapAt)
  const lastSync = syncs[syncs.length - 1]
  const back = lastSync && mine.find(o => o.at >= lastSync.at && o.overlay && o.overlay.startsWith('flash'))
  checks.overlayBackAfterHelmetSync = !!back && back.at - lastSync.at < 500
  log('our overlays', [...new Set(mine.map(o => o.overlay))], 'sent', mine.length, 'effects', added.map(e => e.id),
    'blindness on/off ms after the blast', blindOn.map(e => e.at - swapAt), blindOff.map(e => e.at - swapAt),
    'first fade step', firstFade ? firstFade.at - swapAt : null,
    'head syncs', syncs.map(h => h.at - swapAt), 'overlay back', back ? back.at - swapAt : null)
  await rcon(`item replace entity ${name} armor.head with minecraft:air`)

  // ---------------------------------------------------------------- 3. strobe on a soldier
  await rcon(`execute as ${name} at @s run tp @s 36.5 65 30.5 180 0`)
  await sleep(1500)
  bot.setQuickBarSlot(heldSlot(bot, 'm4c') - 36)
  await sleep(1200)
  pick(bot)
  await sleep(400)
  pick(bot)
  await sleep(400)
  checks.strobeOn = bars.some(b => b.includes('爆闪'))
  // Light the first soldier within strobe range that shoots us: it is engaged and has a clear line.
  let target = null
  const waitFrom = hits.length
  await waitFor(() => {
    for (const h of hits.slice(waitFrom)) {
      const e = bot.entities[h.by]
      if (e && SOLDIERS.includes(e.name) && insideArena(e.position) && e.position.distanceTo(bot.entity.position) < 21) {
        target = e
        return true
      }
    }
    return false
  }, 30000)
  if (!target) throw new Error('no soldier within strobe range shot at us')
  // Step in to about 4.5 blocks along the line it shot us on: a soldier that is not frozen backs off
  // from that close (AiService.think), a frozen one stays put.
  const close = openSpotNear(bot, target)
  if (close) await rcon(`execute as ${name} at @s run tp @s ${close.x.toFixed(2)} ${close.y} ${close.z.toFixed(2)}`)
  const start = target.position.clone()
  log('lighting soldier', target.id, target.name, 'at', start.toString(), 'distance',
    start.distanceTo(close || bot.entity.position).toFixed(1), close ? 'after stepping in' : 'from where we stand')
  const lightFrom = Date.now()
  let moved = 0
  const end = lightFrom + 8000
  while (Date.now() < end && bot.entities[target.id]) {
    await bot.lookAt(target.position.offset(0, 1.55, 0), true)
    moved = Math.max(moved, target.position.distanceTo(start))
    await sleep(50)
  }
  // Rounds already in the air when the beam came on do not count.
  const fromTarget = hits.filter(h => h.at > lightFrom + 750 && h.by === target.id).length
  checks.strobeTargetHitUs = fromTarget >= 1
  // A frozen soldier gets a step or two in before the beam catches it; one that is not backs off.
  checks.strobeTargetMoved = moved > 1.0
  log('strobe result', { hitsFromTarget: fromTarget, hitsTotal: hits.filter(h => h.at > lightFrom).length, moved: moved.toFixed(2), alive: !!bot.entities[target.id] })

  clearInterval(heal)
  const ok = Object.values(checks).every(Boolean)
  log(ok ? 'PASS' : 'FAIL', checks)
  bot.quit()
  await sleep(500)
  await rcon('cv end').catch(() => {})
  process.exit(ok ? 0 : 1)
})().catch(async e => {
  log('ERROR', e.message, checks)
  await rcon('cv end').catch(() => {})
  process.exit(2)
})
