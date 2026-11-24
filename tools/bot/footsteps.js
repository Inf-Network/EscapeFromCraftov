// Footsteps (issue #3, FootstepService), on a stone runway built high over the lobby. A walks, B
// listens; everything is read from the sound packets B receives.
//
//   walk     A walks about 12 blocks 6 blocks beside B: B hears craftov:step.stone.walk about once per
//            footsteps.stride (1.6) blocks A covered, and no vanilla step sound from A at all
//   sprint   A sprints the same way: craftov:step.stone.run, as often per block
//   sneak    A sneaks the same way: nothing
//   jump     A sprint-jumps about 24 blocks past B: one step (craftov:step.stone.run) on every
//            landing, and no landing sound (a jump falls short of land-min-fall)
//   range    A walks 20 blocks from B (past walk-range 16): nothing; sprints there (inside 32): steps
//   wall     a stone wall between them, A walking 10 blocks away (past 16 x wall-share 0.5): nothing;
//            6 blocks away: steps, quieter than without the wall at that distance
//   land     A drops 3 blocks onto the runway: one craftov:step.stone.land
//   self     A never hears its own steps from the server
//
//   PORT=25565 RCON_PORT=25575 node footsteps.js
//
// Server config (tools/bot/suite.js sets it for its run): the defaults (footsteps.enabled true, the
// stride and ranges above); it all happens over the lobby, so no test mode.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
//
// The bots are made op and deopped at the end; the runway is removed. Exit code: number of failed
// checks (2+ also when the setup failed).
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args)

let failures = 0
function check (ok, what) {
  log(ok ? 'PASS' : 'FAIL', what)
  if (!ok) failures++
}

const bots = []
function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.heard = []
  const keyOf = p => (JSON.stringify(p.sound || p.soundId || p).match(/[a-z_]+:[a-z0-9_.\/]+/) || [null])[0]
  const onSound = p => {
    bot.heard.push({ at: Date.now(), key: keyOf(p), raw: p.sound, category: p.soundCategory, volume: p.volume,
      x: p.x / 8, y: p.y / 8, z: p.z / 8 })
  }
  bot._client.on('sound_effect', onSound)
  bot._client.on('entity_sound_effect', onSound)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

const tp = (bot, p, yaw = 0) => rcon(`execute as ${bot.username} at @s run tp @s ${p.x} ${p.y} ${p.z} ${yaw} 0`)

/**
 * Moves A along +X for ms with the given controls; returns the blocks it covered and what B heard
 * meanwhile. onStart is called as the controls go down.
 */
async function run (A, B, ms, controls, onStart = () => {}) {
  await sleep(600)
  const from = A.entity.position.clone()
  B.heard.length = 0
  A.heard.length = 0
  await A.look(-Math.PI / 2, 0, true) // yaw -90: facing +X
  for (const c of controls) A.setControlState(c, true)
  A.setControlState('forward', true)
  // mineflayer does not send the sprint command itself
  const sprinting = controls.includes('sprint')
  if (sprinting) A._client.write('entity_action', { entityId: A.entity.id, actionId: 'start_sprinting', jumpBoost: 0 })
  onStart()
  await sleep(ms)
  A.clearControlStates()
  if (sprinting) A._client.write('entity_action', { entityId: A.entity.id, actionId: 'stop_sprinting', jumpBoost: 0 })
  await sleep(700)
  const covered = Math.hypot(A.entity.position.x - from.x, A.entity.position.z - from.z)
  const steps = kind => B.heard.filter(h => h.key === `craftov:step.stone.${kind}`)
  // A vanilla step or fall from A: a players-category sound at A's feet that is not ours.
  // A vanilla step comes as a sound registry id, which has no name here: up on the runway nothing else
  // makes a sound, so any sound B cannot name is one.
  const vanilla = B.heard.filter(h => !h.key || !h.key.startsWith('craftov:'))
  return { covered, steps, vanilla, own: A.heard.filter(h => h.key && h.key.startsWith('craftov:step.')) }
}

async function main () {
  const tag = Math.floor(Math.random() * 9000)
  const A = await make('StepA' + tag)
  const B = await make('StepB' + tag)
  for (const bot of [A, B]) await rcon(`op ${bot.username}`)
  const base = A.entity.position.floored().offset(0, 60, 0)
  const runway = `${base.x - 4} ${base.y - 1} ${base.z - 24} ${base.x + 40} ${base.y - 1} ${base.z + 4}`
  try {
    await rcon(`execute as ${A.username} at @s run fill ${runway} minecraft:stone`)
    await rcon(`execute as ${A.username} at @s run fill ${base.x - 4} ${base.y} ${base.z - 24} ${base.x + 40} ${base.y + 4} ${base.z + 4} minecraft:air`)
    const start = base.offset(0.5, 0, 0.5)
    const beside = (d) => new Vec3(base.x + 6.5, base.y, base.z + 0.5 - d)
    await sleep(500)

    // walk, sprint, sneak, 6 blocks to the side of A's run
    const lanes = [['walk', [], 'walk'], ['sprint', ['sprint'], 'run'], ['sneak', ['sneak'], null]]
    for (const [name, controls, kind] of lanes) {
      await tp(A, start, -90)
      await tp(B, beside(6), 0)
      const r = await run(A, B, 2800, controls)
      if (kind) {
        const expected = r.covered / 1.6
        check(r.steps(kind).length >= Math.floor(expected) - 1 && r.steps(kind).length <= Math.ceil(expected) + 1,
          `${name}: ${r.steps(kind).length} steps for ${r.covered.toFixed(1)} blocks (about ${expected.toFixed(1)} expected)`)
      } else {
        const any = B.heard.filter(h => h.key && h.key.startsWith('craftov:step.'))
        check(any.length === 0 && r.covered > 2, `${name}: ${any.length} steps for ${r.covered.toFixed(1)} blocks (none expected)`)
      }
      check(r.vanilla.length === 0, `${name}: no vanilla step or fall sound from A (${r.vanilla.map(h => h.key || JSON.stringify(h.raw)).join(', ') || 'none'})`)
      check(r.own.length === 0, `${name}: A hears none of its own steps from the server (${r.own.length})`)
    }

    // jump: sprinting with jump held, A lands about every 4 blocks; each landing is one step. B stands
    // 6 blocks off the middle of the run, so the last landing (after the sprint, walk-range) is heard.
    await tp(A, start, -90)
    await tp(B, new Vec3(base.x + 12.5, base.y, base.z - 5.5), 0)
    let landings = 0
    let wasOnGround = null
    const onTick = () => {
      if (wasOnGround === false && A.entity.onGround) landings++
      if (wasOnGround !== null) wasOnGround = A.entity.onGround
    }
    A.on('physicsTick', onTick)
    const jumped = await run(A, B, 3200, ['sprint', 'jump'], () => { wasOnGround = A.entity.onGround })
    A.removeListener('physicsTick', onTick)
    // the last landing can come after the sprint is let go: a walk step then
    const footfalls = jumped.steps('run').length + jumped.steps('walk').length
    check(landings >= 3 && footfalls >= landings - 1 && footfalls <= landings + 1,
      `jump: ${footfalls} steps (${jumped.steps('run').length} run) for ${landings} landings over ${jumped.covered.toFixed(1)} blocks (one a landing expected)`)
    check(jumped.steps('land').length === 0, `jump: ${jumped.steps('land').length} landing sounds (none expected, a jump is no fall)`)
    check(jumped.vanilla.length === 0, `jump: no vanilla step or fall sound from A (${jumped.vanilla.map(h => h.key || JSON.stringify(h.raw)).join(', ') || 'none'})`)

    // range: 20 blocks to the side
    await tp(A, start, -90)
    await tp(B, beside(20), 0)
    let r = await run(A, B, 2200, [])
    check(r.steps('walk').length === 0, `range: walking 20 blocks away, ${r.steps('walk').length} steps heard (none expected)`)
    await tp(A, start, -90)
    r = await run(A, B, 2200, ['sprint'])
    check(r.steps('run').length > 0, `range: sprinting 20 blocks away, ${r.steps('run').length} steps heard (some expected)`)

    // wall: a stone wall along the runway between A's lane and B
    await rcon(`execute as ${A.username} at @s run fill ${base.x - 4} ${base.y} ${base.z - 3} ${base.x + 40} ${base.y + 4} ${base.z - 3} minecraft:stone`)
    await tp(A, start, -90)
    await tp(B, beside(10), 0)
    r = await run(A, B, 2200, [])
    check(r.steps('walk').length === 0, `wall: walking 10 blocks away behind a wall, ${r.steps('walk').length} steps heard (none expected)`)
    await tp(A, start, -90)
    await tp(B, beside(6), 0)
    r = await run(A, B, 2200, [])
    const walled = r.steps('walk')
    await rcon(`execute as ${A.username} at @s run fill ${base.x - 4} ${base.y} ${base.z - 3} ${base.x + 40} ${base.y + 4} ${base.z - 3} minecraft:air`)
    await tp(A, start, -90)
    r = await run(A, B, 2200, [])
    const open = r.steps('walk')
    const mean = list => list.reduce((s, h) => s + h.volume, 0) / Math.max(1, list.length)
    check(walled.length > 0 && open.length > 0 && mean(walled) < mean(open),
      `wall: 6 blocks away ${walled.length} steps behind the wall at volume ${mean(walled).toFixed(2)}, ${open.length} in the open at ${mean(open).toFixed(2)}`)

    // land: a 3-block drop
    await tp(B, beside(6), 0)
    await tp(A, start.offset(0, 3, 0), -90)
    B.heard.length = 0
    await sleep(2000)
    const lands = B.heard.filter(h => h.key === 'craftov:step.stone.land')
    check(lands.length === 1, `land: ${lands.length} landing sounds for a 3-block drop (one expected)`)
  } finally {
    await rcon(`execute as ${A.username} at @s run fill ${runway} minecraft:air`).catch(() => {})
    for (const bot of bots) {
      await rcon(`deop ${bot.username}`).catch(() => {})
      bot.quit()
    }
  }
}

main().then(() => {
  log(failures === 0 ? 'ALL PASS' : `${failures} FAILED`)
  setTimeout(() => process.exit(failures), 500)
}).catch(e => {
  log('SETUP FAILED', e && e.stack || e)
  setTimeout(() => process.exit(2 + failures), 500)
})
