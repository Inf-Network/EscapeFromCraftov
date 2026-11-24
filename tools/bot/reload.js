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
//   node reload.js     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: the defaults (the range is open to everyone). Prints PASS / FAIL per check; the exit
// code is the number of failures (2+ when it broke). swap.js checks the cooldown's exact length.
const c = require('./common')

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

  c.check('the bot is on the private range', await c.enterRange(bot))
  await c.rcon(`cv give ${name} m4c`)
  await c.waitFor(() => c.hotbar(bot, 'm4c') >= 0, 3000)
  const gun = c.hotbar(bot, 'm4c')
  if (gun < 0) return c.finish(2)
  bot.setQuickBarSlot(gun)
  await c.sleep(1500) // the draw

  // reload: a full magazine has nothing to reload, so a few rounds go first.
  await c.pullTrigger(bot, 300)
  await c.sleep(800)
  let from = cooldowns.length
  c.dropKey(bot)
  await c.waitFor(() => reloads(from).length > 0, 2000)
  const started = reloads(from).find(x => x.ticks > 0)
  c.check('reload: Q starts the craftov:reload cooldown for the reload', !!started && started.ticks >= 20, reloads(from))
  await c.sleep(started ? started.ticks * 50 + 800 : 5000)
  let shots = bot.reports.length
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
