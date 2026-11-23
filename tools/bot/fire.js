// Firing a gun the way the vanilla client does it now (issue #33): the trigger is LEFT mouse, which
// the client only sends while it digs a block, so the server puts capture blocks in front of the
// crosshair; RIGHT mouse aims down the sights and must never fire. On the private range, one bot:
//
//   click   an M4C, loaded: one left click (dig start on the capture block, a swing, abort) fires;
//           the bot hears its own report (gun.m4c.fp, or gun.m4c.x<n>.fp holding n rounds).
//   hold    left mouse held 1.2 s on full auto (a swing every tick, then the abort): at least five
//           reports, one about every 150 ms, and nothing after the abort.
//   aim     right mouse held 1.2 s (a use packet every 4 ticks, as the client sends them): no report,
//           and the sights come up (gun.ads).
//   dm14    a DM14 (semi-auto) held 1 s: one shot only.
//
//   node fire.js     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: the defaults (no test mode needed: the range is open to everyone). The bot has a
// fresh random name (Gun####). Prints PASS / FAIL per check; the exit code is the number of failures
// (2+ when the bot could not get onto the range).
const mineflayer = require('mineflayer')
const conv = require('mineflayer/lib/conversions')
const rcon = require('./rcon')
const env = require('./env')

const name = 'Gun' + Math.floor(1000 + Math.random() * 9000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)
const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === '' ? '' : ' | ' + detail}`)
}

const bot = mineflayer.createBot({ host: env.HOST, port: env.PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
bot.on('error', e => log('ERROR', e.message))

// First-person reports the shooter hears: gun.<id>.fp, or gun.<id>.x<n>.fp holding n rounds.
const reports = []
const ads = []
const onSound = p => {
  const sound = JSON.stringify(p.sound || p.soundId || '')
  const m = sound.match(/gun\.([a-z0-9_]+?)(?:\.x(\d))?\.fp\b(?!_)/)
  if (m) reports.push({ at: Date.now(), gun: m[1], rounds: m[2] ? Number(m[2]) : 1 })
  if (/gun\.ads\b/.test(sound)) ads.push(Date.now())
}
bot._client.on('entity_sound_effect', onSound)
bot._client.on('sound_effect', onSound)

const itemId = item => { const m = JSON.stringify(item && item.components || '').match(/"craftov:id":\{"type":"\w+","value":"([a-z0-9_]+)"/); return m ? m[1] : null }
const hotbar = id => bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && itemId(s) === id)
let sequence = 1
const dig = (status, cell) => bot._client.write('block_dig', { status, location: cell || { x: 0, y: 0, z: 0 }, face: 1, sequence: sequence++ })
const swing = () => bot._client.write('arm_animation', { hand: 0 })
const use = () => bot._client.write('use_item', { hand: 0, sequence: sequence++, rotation: { x: conv.toNotchianYaw(bot.entity.yaw), y: conv.toNotchianPitch(bot.entity.pitch) } })
const rounds = from => reports.slice(from).reduce((n, r) => n + r.rounds, 0)

/** The capture block the server put in front of the crosshair. */
async function captureCell () {
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
  throw new Error('no capture block in front of the crosshair')
}

/** Left mouse down for `ms` as the vanilla client sends it: dig start, a swing every tick, abort. */
async function holdLeft (ms) {
  const cell = await captureCell()
  dig(0, cell)
  const end = Date.now() + ms
  do { swing(); await sleep(50) } while (Date.now() < end)
  dig(1, cell)
}

async function enterRange () {
  await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = bot.nearestEntity(e => e.name === 'interaction')
  if (rack) bot.activateEntity(rack)
  await sleep(2500)
  const dim = await rcon(`data get entity ${name} Dimension`)
  if (!dim.includes('craftov_range')) throw new Error('not on the range: ' + dim)
}

/** Gives the gun, holds it, loads it (Q) and faces down the range. */
async function arm (id) {
  if (hotbar(id) < 0) await rcon(`cv give ${name} ${id}`)
  await sleep(800)
  const slot = hotbar(id)
  if (slot < 0) throw new Error(id + ' did not reach the hotbar')
  bot.setQuickBarSlot(slot - 36)
  await sleep(1200) // the draw
  dig(4) // Q: reload
  await sleep(6500)
}

;(async () => {
  await new Promise(resolve => bot.once('spawn', resolve))
  await sleep(2500)
  try {
    await enterRange()
  } catch (e) {
    log('ERROR', e.message)
    bot.quit()
    await sleep(500)
    process.exit(2)
  }
  await arm('m4c')

  let from = reports.length
  const cell = await captureCell()
  dig(0, cell)
  swing()
  await sleep(50)
  dig(1, cell)
  await sleep(700)
  check('click: a left click fires the M4C', rounds(from) >= 1, `${rounds(from)} rounds heard`)

  await sleep(500)
  from = reports.length
  await holdLeft(1200)
  const after = reports.length
  await sleep(800)
  const held = reports.slice(from, after).filter(r => r.gun === 'm4c')
  const gaps = held.slice(1).map((r, i) => r.at - held[i].at)
  const mean = gaps.length ? gaps.reduce((a, b) => a + b, 0) / gaps.length : 0
  check('hold: left mouse held fires full auto', held.length >= 5 && Math.abs(mean - 150) <= 30,
    `${held.length} reports, ${rounds(from)} rounds, mean gap ${mean.toFixed(0)} ms`)
  check('hold: nothing fires after letting go', reports.length - after <= 1, `${reports.length - after} reports after the abort`)

  await sleep(500)
  from = reports.length
  const adsFrom = ads.length
  const end = Date.now() + 1200
  while (Date.now() < end) { use(); await sleep(200) }
  await sleep(600)
  check('aim: right mouse held does not fire', reports.length === from, `${reports.length - from} reports`)
  check('aim: right mouse held raises the sights', ads.length > adsFrom, `${ads.length - adsFrom} gun.ads sounds`)

  await sleep(800)
  await arm('dm14')
  from = reports.length
  await holdLeft(1000)
  await sleep(700)
  check('dm14: a semi-auto rifle held down fires once', rounds(from) === 1, `${rounds(from)} rounds heard`)

  log(failures.length ? 'FAIL ' + failures.join('; ') : 'PASS all checks')
  bot.quit()
  await sleep(500)
  process.exit(failures.length)
})().catch(e => {
  log('ERROR', e.stack || e.message)
  bot.quit()
  setTimeout(() => process.exit(2 + failures.length), 500)
})
