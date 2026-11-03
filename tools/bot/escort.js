// Escort contract: client posts, escort accepts (auto-party), trio raid, client extracts, escort paid.
const mineflayer = require('mineflayer')
const fs = require('fs')
const rcon = require('./rcon')
const out = '/tmp/escort.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) log(name, 'CHAT', m) })
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}
const openWith = (bot, fn) => new Promise(res => { const t = setTimeout(() => res(null), 4000); bot.once('windowOpen', w => { clearTimeout(t); res(w) }); fn() })
;(async () => {
  try {
    const tag = String(Date.now() % 10000)
    const C = 'Boss' + tag, E = 'Guard' + tag
    const c = await make(C)
    const e = await make(E)
    await sleep(2500)
    await rcon('op ' + C)
    let w = await openWith(c, () => c.chat('/cv'))
    const escort = await openWith(c, () => c.clickWindow(16, 0, 0))
    await c.clickWindow(38, 0, 0); await sleep(500)
    c.closeWindow(escort)
    w = await openWith(e, () => e.chat('/cv'))
    const list = await openWith(e, () => e.clickWindow(16, 0, 0))
    await e.clickWindow(0, 0, 0); await sleep(800)
    e.closeWindow(list)
    log('money before', (await rcon('cv money ' + C + ' 0')).replace(/§./g, ''), (await rcon('cv money ' + E + ' 0')).replace(/§./g, ''))
    c.chat('/cv queue rustport NORMAL TRIO')
    for (let i = 0; i < 120; i++) { await sleep(500); if (c.game.gameMode === 'adventure' && Date.now() - t0 > 30000 && c.entity.position.distanceTo({ x: 0.5, y: 65, z: 12.5 }) > 5) break }
    log('client in raid at', c.entity.position.toString(), 'escort at', e.entity.position.toString())
    await sleep(3500)
    await rcon(`execute as ${C} at @s run tp @s 89.5 65 3.5`)
    await sleep(10000)
    log('money after', (await rcon('cv money ' + C + ' 0')).replace(/§./g, ''), (await rcon('cv money ' + E + ' 0')).replace(/§./g, ''))
    await rcon('cv end')
    c.quit(); e.quit()
  } catch (err) { log('ERROR', err.stack) }
  setTimeout(() => process.exit(0), 800)
})()
