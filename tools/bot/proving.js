// Proving-ground smoke test (the test map), with two bots A and B (fresh accounts, made op):
//
//   pack       joining, each is offered the resource pack from this server's own port (CraftEngine
//              hosts it on the Minecraft port).
//   catalog    /cv items opens the test armory catalog; a click on its first item hands A one.
//   raid       both queue solo for the proving ground and land in the same raid.
//   armory     the armory weapons box (8.5 65 77.5) opens for A and reveals items.
//   door       B clicks the dorm door (lock dorm_201) without its key: it stays shut and says which key.
//   timed      A stands 10 s in the timed extract (opens 120 s into the raid): still in the raid.
//   always     A walks into the always-open extract: back in the lobby.
//   paid       B walks into the paid extract (◈5,000): back in the lobby, ◈5,000 poorer.
//
//   node proving.js [host]     env: PORT, RCON_PORT, RCON_PASSWORD (env.js); host defaults to 127.0.0.1
//
// Server config: the defaults (the bots are op, which opens the test map to them); a short
// match.wait-seconds keeps it quick. Soldiers are killed so they leave the bots alone. Prints PASS /
// FAIL per check; the exit code is the number of failures (2+ when it broke).
const c = require('./common')


async function makeWithPack (name) {
  const offers = []
  const promise = c.make(name)
  const bot = c.bots[c.bots.length - 1]
  bot._client.on('add_resource_pack', p => offers.push(String(p.url)))
  await promise
  bot.offers = offers
  return bot
}

/** Opens the proving ground's container whose interaction sits at x y z (the bot stands just south of it). */
async function openContainerAt (bot, x, y, z) {
  await c.tp(bot, `${x} ${y} ${z + 1.4} 180 20`)
  await c.sleep(400)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return c.openWith(bot, () => bot.activateEntity(target))
}

async function main () {
  const a = await makeWithPack('Pa' + c.tag)
  const b = await makeWithPack('Pb' + c.tag)
  for (const bot of [a, b]) await c.rcon(`op ${bot.username}`)

  // pack
  for (const bot of [a, b]) {
    c.check(`pack: ${bot.username} is offered the resource pack from the server's port`,
      bot.offers.some(u => /^https?:\/\//.test(u) && u.includes(':' + c.env.PORT)), bot.offers.map(u => u.slice(0, 60)))
  }

  // catalog
  const cat = await c.openWith(a, () => a.chat('/cv items'))
  const filled = cat ? cat.slots.slice(0, cat.inventoryStart).filter(s => s && c.itemId(s)).length : 0
  c.check('catalog: /cv items opens the test armory with items in it', !!cat && filled > 0, { filled })
  if (cat) {
    const first = cat.slots.findIndex((s, i) => i < cat.inventoryStart && s && c.itemId(s))
    const id = first >= 0 ? c.itemId(cat.slots[first]) : null
    const before = id ? c.carried(a, id) : 0
    if (first >= 0) { await a.clickWindow(first, 0, 0); await c.sleep(600) }
    c.check('catalog: a click on an item hands it over', !!id && c.carried(a, id) > before, id)
    a.closeWindow(a.currentWindow || cat)
    await c.sleep(500)
  }

  // raid
  a.chat('/cv queue proving NORMAL SOLO')
  b.chat('/cv queue proving NORMAL SOLO')
  const landed = await c.waitFor(() => c.inRaid(a) && c.inRaid(b), 120000)
  c.check('raid: both land in the same proving-ground raid', landed && a.worldName === b.worldName, `${a.worldName} / ${b.worldName}`)
  if (!landed) return c.finish(2)
  await c.sleep(4000)
  await c.clearSoldiers()

  // armory
  const w = await openContainerAt(a, 8.5, 65, 77.5)
  const revealed = () => w.slots.slice(0, w.inventoryStart).filter((s, i) => s && i % 9 < 6 && c.itemId(s) && !JSON.stringify(s.components || '').includes('search_')).length
  c.check('armory: the weapons box opens and reveals items', !!w && await c.waitFor(revealed, 15000), w ? revealed() : 'did not open')
  if (w) a.closeWindow(w)

  // door
  await c.tp(b, '8.5 65 49.6 180 0')
  await c.sleep(500)
  const door = b.blockAt(b.entity.position.offset(0, 0, -1).floored())
  const mark = b.msgs.length
  if (door) await b.activateBlock(door)
  const refused = await c.heard(b, /门被锁住了，需要/, mark, 3000)
  c.check('door: without its key the dorm door stays shut and says which key', !!refused && b.blockAt(door.position).getProperties().open !== true,
    refused || (door && door.name))

  // timed, always
  await c.clearSoldiers()
  let before = a.worldChanges
  await c.tp(a, '106.5 65 17.5')
  await c.sleep(10000)
  c.check('timed: the timed extract is still shut early in the raid', a.worldChanges === before && c.inRaid(a))
  c.check('always: the always-open extract takes A out', await c.extract(a))

  // paid
  const money = await c.money(b.username)
  const paidMark = b.msgs.length
  before = b.worldChanges
  await c.tp(b, '106.5 65 26.5')
  const out = await c.waitFor(() => b.worldChanges > before && c.inLobby(b), 30000)
  await c.sleep(1000)
  // Less what the extraction paid B (an extract contract, a level-up), which it is told about.
  const rewards = c.announcedSince(b, paidMark)
  const after = await c.money(b.username)
  c.check('paid: the paid extract takes B out for ◈5,000', out && money - (after - rewards) === 5000, `${money} -> ${after}, rewards ${rewards}`)

  for (const bot of [a, b]) await c.rcon(`deop ${bot.username}`)
  return c.finish()
}

main().catch(c.crashed)
