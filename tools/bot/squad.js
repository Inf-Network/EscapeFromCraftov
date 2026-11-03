// Two-bot test: shooting range hits, party + trio queue, knock-down, revive, second down, death.
const mineflayer = require('mineflayer')
const rcon = require('./rcon')

const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const fs = require('fs'); fs.writeFileSync('/tmp/squad.log', ''); const log = (who, ...a) => { const line = `[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who} ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); fs.appendFileSync('/tmp/squad.log', line + '\n') }
const text = (j) => { try { return typeof j === 'string' ? j : JSON.stringify(j).match(/"text":\{"type":"string","value":"([^"]*)"/g)?.map(x => x.split('"value":"')[1].slice(0, -1)).join('') || '' } catch (e) { return '' } }

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
  bot.worldChanges = 0
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) log(name, 'CHAT', m) })
  bot.on('title', (t, type) => { const s = text(t); if (s) log(name, 'TITLE', type, s) })
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  bot.on('respawn', () => bot.worldChanges++)
  bot.on('soundEffectHeard', (sound) => { if (String(sound).includes('craftov')) log(name, 'SOUND', sound) })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(250) }
  return false
}

(async () => {
  const a = await make('Alpha')
  const b = await make('Bravo')
  await sleep(2500)
  await rcon('op Alpha')
  // --- shooting range
  await rcon('cv give Alpha m4c')
  await rcon('cv give Alpha ammo_556_m855 60')
  await rcon('execute as Alpha at @s run tp @s 11.5 65 -7.5 180 0')
  await sleep(1200)
  const dummy = a.nearestEntity(e => e.name === 'zombie')
  log('Alpha', 'dummy at', dummy && dummy.position.toString())
  const slot = a.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes('m4c'))
  log('Alpha', 'm4c hotbar slot', slot)
  if (slot >= 0) a.setQuickBarSlot(slot - 36)
  await sleep(300)
  if (dummy) await a.lookAt(dummy.position.offset(0, 1.3, 0), true)
  a.activateItem(); await sleep(1500); a.deactivateItem()
  await sleep(500)
  a.toss = a.toss
  // reload with Q (drop key)
  a._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  await sleep(2800)
  a.activateItem(); await sleep(600); a.deactivateItem()
  await sleep(500)
  a.chat('/cv lobby')
  await sleep(1000)
  // --- party + trio raid
  a.chat('/cv party invite Bravo')
  await sleep(800)
  b.chat('/cv party accept')
  await sleep(800)
  a.chat('/cv queue rustport NORMAL TRIO')
  const changes = a.worldChanges
  const ok = await waitFor(() => a.game.gameMode === 'adventure' && a.worldChanges > changes && b.game.gameMode === 'adventure', 60000)
  log('both', 'inserted', ok, a.entity.position.toString(), b.entity.position.toString())
  await sleep(1000)
  // first knock-down
  await rcon('damage Bravo 500 minecraft:generic')
  await sleep(1500)
  // Alpha moves to Bravo and crouches to revive
  await rcon(`execute as Alpha at Bravo run tp @s ~0.8 ~ ~`)
  await sleep(600)
  const target = a.nearestEntity(e => e.type === 'player' && e.username === 'Bravo')
  log('Alpha', 'revive target', target && target.username, target && target.position.distanceTo(a.entity.position).toFixed(2))
  for (let i = 0; i < 40 && target; i++) { a._client.write('use_entity', { target: target.id, mouse: 0, hand: 0, sneaking: false }); await sleep(160) }
  await sleep(1000)
  // second knock-down then let it bleed with damage until death
  await rcon('damage Bravo 500 minecraft:generic')
  await sleep(1500)
  await rcon('damage Bravo 500 minecraft:generic')
  await rcon('damage Bravo 500 minecraft:generic')
  await sleep(6500)
  log('Bravo', 'mode after death sequence', b.game.gameMode)
  await rcon('cv raids')
  a.quit(); b.quit()
  await sleep(500)
  process.exit(0)
})()
