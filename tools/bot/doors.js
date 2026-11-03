// Key doors and switch-activated extract.
const mineflayer = require('mineflayer')
const fs = require('fs')
const Vec3 = require('vec3')
const rcon = require('./rcon')
const out = '/tmp/doors.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
const name = 'Key' + (Date.now() % 10000)
const [dx, dy, dz] = process.argv.slice(2, 5).map(Number)
const [sx, sy, sz] = process.argv.slice(5, 8).map(Number)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) log('CHAT', m) })
bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log('BAR', s) })
bot.once('spawn', async () => {
  try {
    await sleep(2500)
    bot.chat('/cv queue rustport NORMAL SOLO')
    for (let i = 0; i < 80; i++) { await sleep(500); if (bot.game.gameMode === 'adventure' && Date.now() - t0 > 30000 && bot.entity.position.distanceTo({ x: 0.5, y: 65, z: 12.5 }) > 5) break }
    await sleep(3500)
    // Door without key, then with key.
    const door = new Vec3(Math.floor(dx), Math.floor(dy), Math.floor(dz))
    await rcon(`execute as ${name} at @s run tp @s ${door.x + 0.5} ${door.y} ${door.z + 1.5}`)
    await sleep(1000)
    await bot.activateBlock(bot.blockAt(door)); await sleep(600)
    await rcon(`cv give ${name} key_hq`); await sleep(400)
    await bot.activateBlock(bot.blockAt(door)); await sleep(600)
    log('door open?', (await rcon(`execute as ${name} at @s if block ${door.x} ${door.y} ${door.z} minecraft:iron_door[open=true]`)).replace(/§./g, '') || 'no output')
    log('door state', JSON.stringify(bot.blockAt(door).getProperties()))
    // Switch.
    const lever = new Vec3(Math.floor(sx), Math.floor(sy), Math.floor(sz))
    await rcon(`execute as ${name} at @s run tp @s ${lever.x + 0.5} ${lever.y} ${lever.z + 1.5}`)
    await sleep(1000)
    log('lever block', bot.blockAt(lever) && bot.blockAt(lever).name)
    await bot.activateBlock(bot.blockAt(lever)); await sleep(800)
    await rcon('cv end')
  } catch (e) { log('ERROR', e.stack) }
  await sleep(1000)
  bot.quit()
  setTimeout(() => process.exit(0), 500)
})
