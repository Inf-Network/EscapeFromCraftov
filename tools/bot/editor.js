// Map editor: build a tiny map in a fresh world with the click tools, save it, then raid on it.
const mineflayer = require('mineflayer')
const fs = require('fs')
const Vec3 = require('vec3')
const rcon = require('./rcon')
const out = '/tmp/editor.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
const name = 'Mapper' + (Date.now() % 10000)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('messagestr', m => { if (m.trim()) log('CHAT', m) })
bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log('BAR', s) })
bot.once('spawn', async () => {
  try {
    await sleep(2500)
    await rcon('op ' + name)
    bot.chat('/cv editor world edittest_world')
    await sleep(2500)
    await rcon(`execute as ${name} at @s run fill ~-12 ~-1 ~-12 ~12 ~-1 ~12 minecraft:stone`)
    await rcon(`execute as ${name} at @s run setblock ~6 ~ ~6 minecraft:chest`)
    await rcon(`execute as ${name} at @s run setblock ~-6 ~ ~6 minecraft:barrel`)
    await sleep(800)
    bot.chat('/cv editor start testmap')
    await sleep(1500)
    const p = bot.entity.position.floored()
    const use = async (slot, dx, dz) => {
      bot.setQuickBarSlot(slot)
      await sleep(250)
      const block = bot.blockAt(new Vec3(p.x + dx, p.y - 1, p.z + dz))
      await bot.activateBlock(block)
      await sleep(350)
    }
    await use(0, 2, 0); await use(0, -2, 0)          // spawns
    await use(1, 8, -8)                              // extract
    for (let i = 0; i < 4; i++) await use(2, -8 + i * 3, -4) // containers
    await use(3, 0, 8)                               // AI
    bot.setQuickBarSlot(7); await sleep(300); await bot.activateBlock(bot.blockAt(new Vec3(p.x, p.y - 1, p.z))); await sleep(800) // scan chests
    bot.chat('/cv editor set name 测试地图'); await sleep(300)
    bot.chat('/cv editor info'); await sleep(500)
    bot.chat('/cv editor exit'); await sleep(1500)
    log('map file exists', fs.existsSync('../../server/run/plugins/Craftov/maps/testmap.yml'))
    bot.chat('/cv lobby'); await sleep(1500)
    await rcon(`gamemode adventure ${name}`)
    bot.chat('/cv queue testmap NORMAL SOLO')
    for (let i = 0; i < 80; i++) { await sleep(500); if (bot.game.gameMode === 'adventure' && Date.now() - t0 > 40000) break }
    await sleep(2000)
    log('raid position', bot.entity.position.toString())
    const boxes = Object.values(bot.entities).filter(e => e.name === 'interaction').length
    log('containers visible in raid', boxes)
    await rcon('cv raids')
    await rcon('cv end')
  } catch (e) { log('ERROR', e.stack) }
  await sleep(1000)
  bot.quit()
  setTimeout(() => process.exit(0), 500)
})
