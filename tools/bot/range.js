// Range + trigger check: knife in hotbar key 3, the lobby rack opens a private range in its own world,
// Q / right-click doors with a gun never fire, and leaving restores the lobby inventory without range items.
const mineflayer = require('mineflayer')
const rcon = require('./rcon')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'Loader', version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
let shots = 0
bot._client.on('sound_effect', (p) => { const n = JSON.stringify(p.sound || p.soundId || ''); if (/gun\.(rifle|smg|pistol)/.test(n)) shots++ })
bot.on('actionBar', m => console.log('bar', m.toString()))
const hotbar = () => bot.inventory.slots.slice(36, 45).map(s => s ? (JSON.stringify(s.components || '').match(/craftov:([a-z0-9_]+)/) || [, s.name])[1] : '-')
const dropKey = () => bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
bot.once('spawn', async () => {
  await sleep(3000)
  console.log('lobby hotbar', hotbar().join(' '))
  await rcon('execute as Loader at @s run tp @s 9.5 65 -6 0 0')
  await sleep(1000)
  const rack = Object.values(bot.entities).filter(e => e.name === 'interaction').sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
  console.log('rack at', rack && rack.position)
  bot.activateEntity(rack)
  await sleep(2000)
  console.log('world after enter', bot.game.dimension, 'pos', bot.entity.position.floored(), 'hotbar', hotbar().join(' '))
  await rcon('cv give Loader m4c')
  await rcon('cv give Loader ammo_556_m855 60')
  await sleep(800)
  const slot = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes('m4c'))
  bot.setQuickBarSlot(slot - 36)
  await sleep(500)
  shots = 0
  for (let i = 0; i < 3; i++) { dropKey(); bot.swingArm(); await sleep(400) }
  await sleep(3500)
  console.log('shots after 3x Q (expect 0):', shots)
  shots = 0
  bot.swingArm(); await sleep(600)
  console.log('shots after a real left click (expect >=1):', shots)
  bot.setQuickBarSlot(8)
  await sleep(300)
  bot.activateItem()
  await sleep(2000)
  console.log('after leave pos', bot.entity.position.floored(), 'hotbar', hotbar().join(' '))
  bot.quit()
  process.exit(0)
})
setTimeout(() => { console.log('timeout'); process.exit(1) }, 40000)
