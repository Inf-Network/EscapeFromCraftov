// Proving-ground smoke test: LAN pack URL, item catalog, solo raid on the test map, armory, locked door, extracts.
const mineflayer = require('mineflayer')
const rcon = require('./rcon')

const host = process.argv[2] || '127.0.0.1'
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))

function make (name) {
  const bot = mineflayer.createBot({ host, port: 25565, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldChanges = 0
  bot._client.on('add_resource_pack', (p) => {
    log(name, 'pack url', String(p.url).slice(0, 60))
    for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r })
  })
  bot.on('messagestr', m => { if (m.trim()) log(name, 'CHAT', m) })
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(250) }
  return false
}

function openWindow (bot, fn, timeout = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeout)
    bot.once('windowOpen', (w) => { clearTimeout(timer); resolve(w) })
    fn()
  })
}

const filled = (w) => w.slots.slice(0, w.inventoryStart).filter(s => s && !JSON.stringify(s.components || '').includes('search_')).length

async function openContainerAt (bot, name, x, y, z) {
  await rcon(`execute as ${name} at @s run tp @s ${x} ${y} ${z + 1.4} 180 20`)
  await sleep(1200)
  const target = bot.nearestEntity(e => e.name === 'interaction')
  if (!target) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  return openWindow(bot, () => bot.activateEntity(target))
}

(async () => {
  const id = Math.floor(Math.random() * 9000)
  const a = await make('Pa' + id)
  await sleep(5000)
  const b = await make('Pb' + id)
  await sleep(2500)
  // Catalog.
  const cat = await openWindow(a, () => a.chat('/cv items'))
  log('A', 'catalog', cat && JSON.stringify(cat.title).slice(0, 60), cat && filled(cat))
  if (cat) {
    await a.clickWindow(9, 0, 0)
    await sleep(400)
    a.closeWindow(cat)
  }
  await sleep(500)
  log('A', 'has gun', a.inventory.items().some(i => JSON.stringify(i.components || '').includes('craftov:')))
  // Solo raid on the test map.
  a.chat('/cv queue proving NORMAL SOLO')
  b.chat('/cv queue proving NORMAL SOLO')
  const ca = a.worldChanges
  const ok = await waitFor(() => a.game.gameMode === 'adventure' && a.worldChanges > ca && b.game.gameMode === 'adventure', 60000)
  log('both', 'inserted', ok, a.entity.position.toString(), b.entity.position.toString())
  await rcon('cv raids')
  await sleep(1000)
  // Armory container: weapons.
  const w = await openContainerAt(a, a.username, 8.5, 65, 77.5)
  if (w) {
    log('A', 'armory window', JSON.stringify(w.title).slice(0, 80))
    await sleep(3500)
    log('A', 'armory revealed items', filled(w))
    a.closeWindow(w)
  } else log('A', 'armory did not open')
  // Locked door without key.
  await rcon(`execute as ${b.username} at @s run tp @s 8.5 65 49.6 180 0`)
  await sleep(800)
  const door = b.blockAt(b.entity.position.offset(0, 0, -1).floored())
  log('B', 'door block', door && door.name)
  if (door) await b.activateBlock(door)
  await sleep(800)
  // Timed extract is closed for the first 2 minutes.
  await rcon(`execute as ${a.username} at @s run tp @s 106.5 65 17.5`)
  await sleep(3000)
  // Always extract.
  const before = a.worldChanges
  await rcon(`execute as ${a.username} at @s run tp @s 106.5 65 8.5`)
  log('A', 'extracted', await waitFor(() => a.worldChanges > before, 15000))
  // Paid extract.
  const beforeB = b.worldChanges
  await rcon(`execute as ${b.username} at @s run tp @s 106.5 65 26.5`)
  log('B', 'paid extract', await waitFor(() => b.worldChanges > beforeB, 15000))
  await sleep(2000)
  a.quit(); b.quit()
  await sleep(500)
  process.exit(0)
})()
