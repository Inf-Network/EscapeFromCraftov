// End-to-end smoke test for Craftov: join, menus, range, raid, loot, extract, death.
const mineflayer = require('mineflayer')
const rcon = require('./rcon')

const name = process.argv[2] || 'Tester1'
const scenario = process.argv[3] || 'full'
const t0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })

// Answer resource pack offers in both configuration and play state: accepted -> downloaded -> loaded.
bot._client.on('add_resource_pack', (packet) => {
  log('resource pack offered:', String(packet.url).slice(0, 70), 'state', bot._client.state)
  for (const result of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: packet.uuid, result })
})
bot.on('messagestr', (m) => { if (m.trim()) log('CHAT', m) })
bot.on('title', (t, type) => log('TITLE', type, typeof t === 'string' ? t : JSON.stringify(t).slice(0, 160)))
bot.on('actionBar', (m) => { const s = m.toString(); if (s.trim()) log('ACTIONBAR', s) })
bot.on('kicked', (r) => log('KICKED', JSON.stringify(r)))
bot.on('error', (e) => log('ERROR', e.message))
bot.on('end', (r) => { log('END', r); process.exit(0) })
let worldChanges = 0
bot.on('respawn', () => { worldChanges++; log('respawn / world change, pos', bot.entity && bot.entity.position.toString()) })
bot.on('game', () => log('game state: mode', bot.game.gameMode))

function itemName (item) {
  if (!item) return null
  let custom = ''
  try {
    const comp = item.components && item.components.find(c => c.type === 'item_name' || c.type === 'custom_name')
    if (comp) custom = JSON.stringify(comp.data).replace(/\\"/g, '"').slice(0, 60)
  } catch (e) {}
  return `${item.name}x${item.count}${custom ? ' ' + custom : ''}`
}

function dumpInventory (label) {
  const slots = bot.inventory.slots
  const parts = []
  for (let i = 5; i <= 45; i++) if (slots[i]) parts.push(`${i}:${itemName(slots[i])}`)
  log(label, parts.join(' | '))
}

async function openWindowWith (fn, timeout = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeout)
    bot.once('windowOpen', (w) => { clearTimeout(timer); resolve(w) })
    fn()
  })
}

function windowSummary (w) {
  const out = []
  for (let i = 0; i < w.inventoryStart; i++) if (w.slots[i]) out.push(`${i}:${itemName(w.slots[i])}`)
  return out.join(' | ')
}

async function waitFor (pred, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (pred()) return true
    await sleep(step)
  }
  return false
}

async function fire (ms) {
  bot.activateItem()
  await sleep(ms)
  bot.deactivateItem()
}

async function raid (outcome) {
  log('--- queue solo raid, outcome =', outcome)
  const startPos = bot.entity.position.clone()
  const changes = worldChanges
  bot.chat('/cv queue rustport NORMAL SOLO')
  const moved = await waitFor(() => bot.game.gameMode === 'spectator', 40000)
  log('in cinematic:', moved, 'mode', bot.game.gameMode)
  const inserted = await waitFor(() => bot.game.gameMode === 'adventure' && worldChanges > changes, 30000)
  log('inserted into raid:', inserted, 'pos', bot.entity.position.toString())
  await sleep(1500)
  dumpInventory('raid inventory')
  // Open the nearest container.
  const container = bot.nearestEntity(e => e.name === 'interaction' && e.position.distanceTo(bot.entity.position) < 60)
  if (container) {
    log('nearest container at', container.position.toString(), 'dist', container.position.distanceTo(bot.entity.position).toFixed(1))
    await rcon(`execute as ${name} at @s run tp @s ${container.position.x.toFixed(2)} ${container.position.y.toFixed(2)} ${(container.position.z + 1.2).toFixed(2)}`)
    await sleep(1200)
    const target = bot.nearestEntity(e => e.name === 'interaction')
    await bot.lookAt(target.position.offset(0, 0.4, 0), true)
    const w = await openWindowWith(() => bot.activateEntity(target))
    if (w) {
      log('container window:', JSON.stringify(w.title).slice(0, 80))
      for (let i = 0; i < 6; i++) { await sleep(1000); log('  search', i, windowSummary(w)) }
      const first = w.slots.findIndex((s, idx) => idx < w.inventoryStart && s && s.name !== 'paper' ? true : (s && idx < w.inventoryStart && JSON.stringify(s.components || '').includes('craftov') && !JSON.stringify(s.components || '').includes('search_')))
      if (first >= 0) {
        await bot.clickWindow(first, 0, 0)
        await sleep(500)
        log('took slot', first, '->', windowSummary(w))
      }
      bot.closeWindow(w)
    } else {
      log('container did not open')
    }
  } else {
    log('no container nearby')
  }
  bot.setQuickBarSlot(0)
  await sleep(300)
  log('firing primary for 1.2s')
  await fire(1200)
  await sleep(500)
  bot.toss = bot.toss // keep linter quiet
  if (outcome === 'extract') {
    await rcon(`execute as ${name} at @s run tp @s 89.5 65 3.5`)
    const lobbyChanges = worldChanges
    const back = await waitFor(() => worldChanges > lobbyChanges, 20000)
    log('extracted and back in lobby:', back, bot.entity.position.toString())
  } else {
    await rcon(`kill ${name}`)
    const deathChanges = worldChanges
    const back = await waitFor(() => bot.game.gameMode === 'adventure' && worldChanges > deathChanges, 15000)
    log('died and back in lobby:', back)
  }
  await sleep(1500)
  dumpInventory('lobby inventory after raid')
}

bot.once('spawn', async () => {
  log('spawned at', bot.entity.position.toString(), 'mode', bot.game.gameMode)
  await sleep(3000)
  try {
    await rcon(`op ${name}`)
    dumpInventory('lobby inventory')
    const w = await openWindowWith(() => bot.chat('/cv'))
    log('main menu:', w ? windowSummary(w).slice(0, 400) : 'none')
    if (w) bot.closeWindow(w)
    const s = await openWindowWith(() => bot.chat('/cv stash'))
    log('stash:', s ? windowSummary(s).slice(0, 300) : 'none')
    if (s) bot.closeWindow(s)
    if (scenario === 'full' || scenario === 'range') {
      await rcon(`execute as ${name} at @s run tp @s 14.5 65 -6.5 180 0`)
      await sleep(1000)
      const dummy = bot.nearestEntity(e => e.name === 'zombie')
      if (dummy) await bot.lookAt(dummy.position.offset(0, 1.4, 0), true)
      bot.setQuickBarSlot(0)
      await sleep(300)
      log('range: firing')
      await fire(1500)
      await sleep(400)
      bot.chat('/cv lobby')
      await sleep(1000)
    }
    if (scenario === 'full' || scenario === 'extract') await raid('extract')
    if (scenario === 'full' || scenario === 'death') await raid('death')
  } catch (e) {
    log('TEST ERROR', e.stack)
  }
  log('done')
  bot.quit()
})
