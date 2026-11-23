// End-to-end smoke test: one bot with a fresh name goes through a new player's first raids.
//
//   lobby    it joins in the lobby with the starter kit (a K47 on key 1, a P18 on key 2); /cv opens the
//            tactical terminal and /cv stash the stash.
//   range    onto the private range from the lobby's weapon rack (its own loadout: a K47 is handed
//            over there): a left click fires (its own report is heard), and /cv lobby takes it back to
//            the lobby with its kit.
//   extract  a solo raid on 锈港: the entry camera (spectator), then it lands (adventure, a raid world);
//            a container near it opens and reveals what is inside, the first item revealed is taken;
//            the K47 fires; the north gate (北门检查站, always open) takes it back to the lobby with
//            撤离成功 and the item it took.
//   death    a second solo raid: killed there (RCON), it is back in the lobby within 40 s, told
//            行动失败, without the K47 it took in (the knife stays).
//
//   node test.js [name] [full|range|extract|death]     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: the defaults (rank.min-level does not apply to 常规); a short match.wait-seconds keeps
// it quick. The bot is made op (no test map is used, but /cv lobby needs it) and deopped when done.
// Prints PASS / FAIL per check; the exit code is the number of failures (2+ when it broke).
const c = require('./common')

const name = process.argv[2] || 'Tst' + c.tag
const scenario = process.argv[3] || 'full'
const want = s => scenario === 'full' || scenario === s
const NORTH_GATE = '89.5 65 3.5'

/** Queues a solo raid on 锈港 and waits for the entry camera, then the landing. */
async function deploy (bot) {
  const before = bot.worldChanges
  bot.chat('/cv queue rustport NORMAL SOLO')
  const camera = await c.waitFor(() => bot.worldChanges > before && bot.game.gameMode === 'spectator', 90000)
  const landed = await c.waitFor(() => c.inRaid(bot), 60000)
  await c.sleep(3500) // insertion protection
  return { camera, landed }
}

/** Opens the containers nearest the bot, one after another, until one reveals an item; takes it. */
async function loot (bot) {
  const tried = new Set()
  for (let attempt = 0; attempt < 4; attempt++) {
    const box = bot.nearestEntity(e => e.name === 'interaction' && !tried.has(e.id) && e.position.distanceTo(bot.entity.position) < 80)
    if (!box) return { opened: attempt > 0, taken: null }
    tried.add(box.id)
    await c.tp(bot, `${box.position.x.toFixed(2)} ${box.position.y.toFixed(2)} ${(box.position.z + 1.3).toFixed(2)}`)
    const target = bot.nearestEntity(e => e.name === 'interaction')
    if (!target) continue
    await bot.lookAt(target.position.offset(0, 0.4, 0), true)
    const w = await c.openWith(bot, () => bot.activateEntity(target))
    if (!w) continue
    // The container's own cells are its 6x6 block (#10); a search placeholder until revealed.
    const revealed = () => w.slots.slice(0, w.inventoryStart).map((s, i) => [i, s])
      .filter(([i, s]) => s && i % 9 < 6 && c.itemId(s) && !JSON.stringify(s.components || '').includes('search_'))
    await c.waitFor(() => revealed().length > 0, 20000)
    const first = revealed()[0]
    if (first) {
      const id = c.itemId(first[1])
      await bot.clickWindow(first[0], 0, 0)
      await c.sleep(600)
      bot.closeWindow(w)
      await c.sleep(400)
      return { opened: true, taken: id }
    }
    bot.closeWindow(w)
  }
  return { opened: true, taken: null }
}

async function main () {
  const bot = await c.make(name)
  await c.rcon(`op ${name}`)
  c.check('lobby: the bot joins in the lobby', c.inLobby(bot), bot.worldName)
  c.check('lobby: the starter kit has a K47 on key 1', c.itemId(bot.inventory.slots[36]) === 'k47', c.itemId(bot.inventory.slots[36]))
  let w = await c.openWith(bot, () => bot.chat('/cv'))
  c.check('lobby: /cv opens the terminal', !!w && JSON.stringify(w.title).includes('终端'), w && c.flat(w.title).slice(0, 40))
  if (w) bot.closeWindow(w)
  await c.sleep(400)
  w = await c.openWith(bot, () => bot.chat('/cv stash'))
  c.check('lobby: /cv stash opens the stash', !!w && JSON.stringify(w.title).includes('仓库'), w && c.flat(w.title).slice(0, 40))
  if (w) bot.closeWindow(w)
  await c.sleep(400)

  if (want('range')) {
    c.check('range: onto the private range from the weapon rack', await c.enterRange(bot))
    // The range has its own loadout (the kit waits in the lobby): a loaner K47.
    await c.rcon(`cv give ${name} k47`)
    await c.waitFor(() => c.hotbar(bot, 'k47') >= 0, 3000)
    bot.setQuickBarSlot(Math.max(0, c.hotbar(bot, 'k47')))
    await c.sleep(1200) // the draw
    const from = bot.reports.length
    await c.pullTrigger(bot)
    await c.waitFor(() => bot.reports.length > from, 2000)
    c.check('range: a left click fires the K47', c.roundsSince(bot, from) > 0, `${c.roundsSince(bot, from)} rounds heard`)
    bot.chat('/cv lobby')
    c.check('range: /cv lobby takes it back with its kit', await c.waitFor(() => c.inLobby(bot) && c.hotbar(bot, 'k47') === 0, 8000), bot.worldName)
  }

  if (want('extract')) {
    const raid = await deploy(bot)
    c.check('extract: the entry camera runs first', raid.camera)
    c.check('extract: the bot lands in a raid', raid.landed, bot.worldName)
    if (!raid.landed) return c.finish(2)
    // 锈港's soldiers would shoot it while it searches.
    await c.rcon(`effect give ${name} minecraft:resistance 300 4 true`)
    const got = await loot(bot)
    c.check('extract: a container opens and reveals an item, which is taken', !!got.taken, got)
    bot.setQuickBarSlot(0)
    await c.sleep(1200)
    const from = bot.reports.length
    await c.pullTrigger(bot, 300)
    await c.waitFor(() => bot.reports.length > from, 2000)
    c.check('extract: the K47 fires in the raid', c.roundsSince(bot, from) > 0, `${c.roundsSince(bot, from)} rounds heard`)
    const mark = bot.msgs.length
    c.check('extract: the north gate takes it back to the lobby', await c.extract(bot, NORTH_GATE))
    c.check('extract: told it extracted', !!await c.heard(bot, /撤离成功/, mark))
    await c.sleep(1500)
    c.check('extract: it comes home with what it took', !got.taken || c.carried(bot, got.taken) > 0 ||
      (c.profile(name).mailbox || []).some(r => r.id === got.taken), got.taken)
  }

  if (want('death')) {
    if (c.hotbar(bot, 'k47') < 0) await c.rcon(`cv give ${name} k47`)
    await c.sleep(800)
    const raid = await deploy(bot)
    c.check('death: the bot lands in a raid', raid.landed, bot.worldName)
    if (!raid.landed) return c.finish(2)
    const mark = bot.msgs.length
    await c.rcon(`kill ${name}`)
    c.check('death: back in the lobby after dying', await c.waitFor(() => c.inLobby(bot) && bot.game.gameMode === 'adventure', 40000), bot.worldName)
    c.check('death: told the raid failed', !!await c.heard(bot, /行动失败/, mark))
    await c.sleep(1000)
    c.check('death: the K47 it took in is lost', c.carried(bot, 'k47') === 0)
    c.check('death: the knife is kept', c.hotbar(bot, 'knife_starter') === 2)
  }
  await c.rcon(`deop ${name}`)
  return c.finish()
}

main().catch(c.crashed)
