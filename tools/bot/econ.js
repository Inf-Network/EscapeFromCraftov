// Economy & item systems: trader, stash, market listing, secure container, meds, grenade, skill.
const mineflayer = require('mineflayer')
const fs = require('fs')
const rcon = require('./rcon')
const out = '/tmp/econ.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
const plain = (j) => { const m = JSON.stringify(j).match(/"text":\{"type":"string","value":"([^"]*)"/g); return m ? m.map(x => x.split('"value":"')[1].slice(0, -1)).join('') : '' }
const name = 'Eco' + (Date.now() % 10000)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('messagestr', m => { if (m.trim()) log('CHAT', m) })
bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log('BAR', s) })
bot.on('soundEffectHeard', s => { if (/throw|med|skill|ui\.(buy|sell)/.test(String(s))) log('SOUND', s) })

function id (item) { const s = JSON.stringify(item && item.components || ''); const m = s.match(/craftov:([a-z0-9_/]+)/); return m ? m[1] : (item ? item.name : null) }
function inv () { const o = []; for (let i = 9; i <= 44; i++) { const it = bot.inventory.slots[i]; if (it && id(it) !== 'ui/locked') o.push(i + ':' + id(it) + 'x' + it.count) } return o.join(' ') }
async function openWith (fn) { return new Promise(res => { const t = setTimeout(() => res(null), 4000); bot.once('windowOpen', w => { clearTimeout(t); res(w) }); fn() }) }
const top = (w) => { const o = []; for (let i = 0; i < w.inventoryStart; i++) if (w.slots[i]) o.push(i + ':' + id(w.slots[i])); return o.join(' ') }
async function money () { const r = await rcon(`cv money ${name} 0`); return r.replace(/§./g, '') }

bot.once('spawn', async () => {
  try {
    await sleep(2500)
    await rcon('op ' + name)
    log('money', await money())
    // Trader: buy medical items and ammo.
    let w = await openWith(() => bot.chat('/cv trader'))
    log('trader weapons tab', top(w).slice(0, 200))
    await bot.clickWindow(50, 0, 0); await sleep(400) // medical tab
    log('medical tab', top(w).slice(0, 200))
    await bot.clickWindow(3, 0, 0); await sleep(400) // medkit_s?
    await bot.clickWindow(0, 0, 0); await sleep(400)
    await bot.clickWindow(46, 0, 0); await sleep(400) // ammo tab
    await bot.clickWindow(0, 0, 0); await sleep(400)
    bot.closeWindow(w)
    log('after buy', inv())
    log('money', await money())
    // Stash: take the first stash entry (sv9) and deposit it back.
    w = await openWith(() => bot.chat('/cv stash'))
    log('stash', top(w).slice(0, 200))
    await bot.clickWindow(0, 0, 0); await sleep(400)
    log('after take', inv())
    const slot = w.slots.findIndex((s, i) => i >= w.inventoryStart && s && id(s) === 'sv9')
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await sleep(400) }
    log('after deposit', inv(), '| stash', top(w).slice(0, 120))
    // Market: switch to LIST mode and list the first stash item at reference price.
    await bot.clickWindow(48, 0, 0); await sleep(300)
    await bot.clickWindow(48, 0, 0); await sleep(300)
    const priceWin = await openWith(() => bot.clickWindow(0, 0, 0))
    if (priceWin) {
      log('price menu', top(priceWin))
      await bot.clickWindow(21, 0, 0); await sleep(300) // 0.9x reference
      const listed = await openWith(() => bot.clickWindow(26, 0, 0))
      log('listed, back in stash', !!listed)
      if (listed) bot.closeWindow(listed)
    }
    w = await openWith(() => bot.chat('/cv market'))
    log('market', top(w).slice(0, 200))
    await bot.clickWindow(53, 0, 0); await sleep(300)
    log('my listings', top(w).slice(0, 200))
    bot.closeWindow(w)
    // Secure container: put a bandage in.
    w = await openWith(() => bot.chat('/cv secure'))
    const band = w.slots.findIndex((s, i) => i >= w.inventoryStart && s && id(s) === 'bandage')
    if (band >= 0) await bot.clickWindow(band, 0, 0)
    await sleep(300)
    log('secure', top(w))
    bot.closeWindow(w)
    // Raid: meds, grenade, skill.
    bot.chat('/cv queue rustport NORMAL SOLO')
    for (let i = 0; i < 120; i++) { await sleep(500); if (bot.game.gameMode === 'adventure' && Date.now() - t0 > 40000 && bot.entity.position.distanceTo({ x: 0.5, y: 65, z: 12.5 }) > 5) break }
    log('in raid at', bot.entity.position.toString())
    await sleep(4000)
    await rcon(`damage ${name} 45 minecraft:generic`)
    await sleep(500)
    log('hp after damage', bot.health)
    const med = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 43 && s && id(s) === 'medkit_s')
    log('medkit slot', med)
    if (med >= 0) { bot.setQuickBarSlot(med - 36); await sleep(300); bot.activateItem(); await sleep(3500); bot.deactivateItem(); await sleep(500) }
    log('hp after medkit', bot.health)
    await rcon(`cv give ${name} frag`)
    await sleep(500)
    const frag = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 43 && s && id(s) === 'frag')
    if (frag >= 0) { bot.setQuickBarSlot(frag - 36); await sleep(300); await bot.look(0, -0.3, true); bot.activateItem(); await sleep(200); bot.deactivateItem() }
    await sleep(4500)
    w = await openWith(() => bot.chat('/cv'))
    if (w) { log('terminal', top(w)); await bot.clickWindow(16, 0, 0); await sleep(800) }
    log('money end', await money())
    await rcon(`cv end`)
  } catch (e) { log('ERROR', e.stack) }
  await sleep(1000)
  bot.quit()
  setTimeout(() => process.exit(0), 500)
})
