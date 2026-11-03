// Knock-down / revive / second down / death test with two bots in a trio raid.
const mineflayer = require('mineflayer')
const fs = require('fs')
const rcon = require('./rcon')
const out = '/tmp/revive.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
const plain = (j) => JSON.stringify(j).match(/"text":\{"type":"string","value":"([^"]*)"/g)?.map(x => x.split('"value":"')[1].slice(0, -1)).join('') || ''

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) log(name, 'CHAT', m) })
  bot.on('title', (t, type) => log(name, 'TITLE', type, typeof t === 'string' ? t : plain(t)))
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

(async () => {
  try {
    const tag = String(Date.now() % 10000)
    const A = 'Med' + tag, B = 'Pat' + tag
    const a = await make(A)
    const b = await make(B)
    await sleep(2500)
    a.chat('/cv party invite ' + B); await sleep(700)
    b.chat('/cv party accept'); await sleep(700)
    a.chat('/cv queue rustport NORMAL TRIO')
    for (let i = 0; i < 120 && !(a.game.gameMode === 'adventure' && b.game.gameMode === 'adventure' && a.entity.position.y > 60 && Date.now() - t0 > 25000); i++) await sleep(500)
    log('inserted', a.entity.position.toString(), b.entity.position.toString())
    await sleep(4500)
    await rcon('damage ' + B + ' 500 minecraft:generic')
    await sleep(1000)
    await rcon('execute as ' + A + ' at ' + B + ' run tp @s ~0.8 ~ ~')
    await sleep(800)
    const target = a.nearestEntity(e => e.type === 'player')
    log('target', target ? target.username : 'none', target ? target.position.distanceTo(a.entity.position).toFixed(2) : '')
    for (let i = 0; i < 45 && target; i++) { a.activateEntity(target); await sleep(150) }
    await sleep(800)
    await rcon('damage ' + B + ' 500 minecraft:generic')
    await sleep(1500)
    log('second down issued; patient mode', b.game.gameMode)
    await rcon('damage ' + B + ' 500 minecraft:player_attack by ' + A)
    await sleep(800)
    await rcon('execute as ' + B + ' at @s run summon minecraft:tnt ~ ~ ~ {fuse:1}')
    await sleep(6000)
    log('patient mode at end', b.game.gameMode)
    a.quit(); b.quit()
  } catch (e) { log('ERROR', e.stack) }
  setTimeout(() => process.exit(0), 500)
})()
