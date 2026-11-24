// The reload animation runs on a cooldown (the item model plays its frames from the craftov:reload
// cooldown group), so the server must start that cooldown for the length of a reload and clear it
// when the reload is cut short. One bot (a fresh account) on the private range with an M4C:
//
//   reload   a few rounds down, Q with the M4C in hand starts a craftov:reload cooldown of at least
//            20 ticks; once it has run out the gun fires (its report is heard).
//   cut      a few rounds down again, Q starts the cooldown; switching to another hotbar slot 0.4 s
//            later clears it (a craftov:reload cooldown of 0) and the action bar says the reload was
//            cut short (换弹已中断).
//
// With `arrive` the M4C goes into the bot's hand the moment it lands on the range (when its respawn
// packet comes, so in the server tick of the world change or the next), before the range's chunks
// reach it, and it is never switched to: the capture blocks are sent before the chunk they are in,
// and the crosshair never moves, so nothing sends them again (#141). The checks are the same; it
// logs when the capture blocks and their chunk arrived.
//
//   node reload.js [arrive]     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: the defaults (the range is open to everyone). Prints PASS / FAIL per check; the exit
// code is the number of failures (2+ when it broke). swap.js checks the cooldown's exact length.
const conv = require('mineflayer/lib/conversions')
const c = require('./common')

const ARRIVE = process.argv.includes('arrive')

/**
 * For `arrive`: holds key 1 (the range clears it, and a gun goes to key 1 or 2, #10) and hands the
 * M4C over as soon as the bot's respawn packet comes. Returns report(), which logs when the bot was
 * first sent blocks in the chunk of the cell in front of its crosshair, when that chunk came, and
 * when blocks in it came next after the chunk.
 */
async function armOnArrival (bot, name) {
  bot.setQuickBarSlot(0)
  await c.sleep(300)
  let landed = 0
  const blocks = {}
  const chunks = {}
  const later = {}
  const first = (map, x, z) => { const k = x + ',' + z; if (landed && map[k] == null) map[k] = Date.now() - landed }
  const block = (x, z) => { first(blocks, x, z); if (chunks[x + ',' + z] != null) first(later, x, z) }
  bot._client.once('respawn', () => {
    landed = Date.now()
    c.rcon(`cv give ${name} m4c`).then(() => c.log(`m4c handed over ${Date.now() - landed} ms after the respawn`), () => {})
  })
  bot._client.on('map_chunk', p => first(chunks, p.x, p.z))
  bot._client.on('multi_block_change', p => block(p.chunkCoordinates.x, p.chunkCoordinates.z))
  bot._client.on('block_change', p => block(p.location.x >> 4, p.location.z >> 4))
  return {
    report () {
      const yaw = conv.toNotchianYaw(bot.entity.yaw) * Math.PI / 180
      const pitch = conv.toNotchianPitch(bot.entity.pitch) * Math.PI / 180
      const eye = bot.entity.position.offset(0, 1.62, 0)
      const x = Math.floor(eye.x - Math.sin(yaw) * Math.cos(pitch) * 3.2) >> 4
      const z = Math.floor(eye.z + Math.cos(yaw) * Math.cos(pitch) * 3.2) >> 4
      const k = x + ',' + z
      const at = map => map[k] == null ? 'none' : '+' + map[k] + ' ms'
      c.log(`chunk ${k} in front: first block update ${at(blocks)}, chunk ${at(chunks)}, next block update ${at(later)} ` +
        'after the respawn' + (blocks[k] != null && chunks[k] != null && blocks[k] < chunks[k] ? ' (blocks before their chunk)' : ''))
    }
  }
}

async function main () {
  const name = 'Rld' + c.tag
  const bot = await c.make(name)
  const cooldowns = []
  bot._client.on('set_cooldown', p => {
    const group = Object.values(p).find(v => typeof v === 'string') || ''
    const ticks = Object.values(p).find(v => typeof v === 'number')
    cooldowns.push({ at: Date.now(), group, ticks })
  })
  const reloads = from => cooldowns.slice(from).filter(x => x.group.includes('craftov:reload'))

  const arrival = ARRIVE ? await armOnArrival(bot, name) : null
  c.check('the bot is on the private range', await c.enterRange(bot))
  if (!ARRIVE) await c.rcon(`cv give ${name} m4c`)
  await c.waitFor(() => c.hotbar(bot, 'm4c') >= 0, 3000)
  const gun = c.hotbar(bot, 'm4c')
  if (gun < 0) return c.finish(2)
  if (ARRIVE) {
    // Switching to it now would send the capture blocks again, after the chunk.
    if (bot.quickBarSlot !== gun) {
      c.log(`the M4C went to key ${gun + 1}, not the held key ${bot.quickBarSlot + 1}`)
      return c.finish(2)
    }
  } else bot.setQuickBarSlot(gun)
  await c.sleep(1500) // the draw
  if (arrival) arrival.report()

  // reload: a full magazine has nothing to reload, so a few rounds go first.
  let shots = bot.reports.length
  const dug = await c.pullTrigger(bot, 300)
  await c.sleep(800)
  c.check('reload: a left click with the crosshair where it landed fires the first rounds', dug && bot.reports.length > shots,
    dug ? `${bot.reports.length - shots} reports` : 'no capture block in front of the crosshair')
  let from = cooldowns.length
  c.dropKey(bot)
  await c.waitFor(() => reloads(from).length > 0, 2000)
  const started = reloads(from).find(x => x.ticks > 0)
  c.check('reload: Q starts the craftov:reload cooldown for the reload', !!started && started.ticks >= 20, reloads(from))
  await c.sleep(started ? started.ticks * 50 + 800 : 5000)
  shots = bot.reports.length
  await c.pullTrigger(bot)
  await c.waitFor(() => bot.reports.length > shots, 2000)
  c.check('reload: once it has run out the gun fires', bot.reports.length > shots)

  // cut
  await c.sleep(800)
  shots = bot.reports.length
  await c.pullTrigger(bot, 300)
  await c.sleep(800)
  from = cooldowns.length
  const mark = bot.msgs.length
  c.dropKey(bot)
  await c.sleep(400)
  bot.setQuickBarSlot((gun + 1) % 8 === 2 ? 3 : (gun + 1) % 8)
  // The hint waits its turn on the action bar behind a line shown a moment ago (util/Notify).
  const said = await c.heard(bot, /换弹已中断/, mark, 3000)
  const cut = reloads(from)
  c.check('cut: Q after a few rounds starts the cooldown again', cut.some(x => x.ticks > 0), cut)
  c.check('cut: switching away clears it', cut.length >= 2 && cut[cut.length - 1].ticks === 0, cut)
  c.check('cut: the action bar says the reload was cut short', !!said, bot.msgs.slice(mark))
  return c.finish()
}

main().catch(c.crashed)
