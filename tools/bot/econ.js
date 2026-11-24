// The lobby's economy screens, end to end with one bot (a fresh account: starter kit and stash):
//
//   trader   /cv trader opens on the weapons tab; on the medical tab (slot 50) a left click on the
//            bandage buys one (the balance drops by the price its button shows, 购买 … ×1), and on
//            the ammo tab (46) a left click on 5.56 M855 buys 30 rounds (carried).
//   stash    /cv stash lists the starter stash from slot 9 on; a click on the SV9 takes it out (now
//            carried, gone from the stash) and a click on the carried SV9 puts it back.
//   market   a right click on the splint in the stash opens the pricing screen; confirming (slot 26)
//            lists it: the market's own-listings view (slot 53) shows it, and a click there takes it
//            down again (it goes to the mailbox).
//   secure   /cv secure opens the secure container; a click on a carried bandage moves it in.
//
//   node econ.js     env: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR (env.js)
//
// Server config: the defaults (market.min-sell-level 1 or below lets a new account list). Profiles are
// read from SERVER_DIR. Prints PASS / FAIL per check; the exit code is the number of failures.
const c = require('./common')

const title = w => w ? JSON.stringify(w.title) : ''
/** Slot of the first stack with this id in [from, to) of the window, or -1. */
const find = (w, id, from, to) => { for (let i = from; i < to; i++) if (w.slots[i] && c.itemId(w.slots[i]) === id) return i; return -1 }
/** The lore of a stack as plain lines. */
const lore = item => { const l = ((item && item.components) || []).find(x => x.type === 'lore'); return l ? l.data.map(c.flat) : [] }
/** Price on a trader button ("左键 购买 ◈1,200", or "左键 购买 30 发 ◈2,220" for ammo). */
const leftPrice = item => { const m = lore(item).join('\n').match(/左键 购买[^◈\n]*◈([\d,]+)/); return m ? Number(m[1].replace(/,/g, '')) : NaN }

async function main () {
  const name = 'Eco' + c.tag
  const bot = await c.make(name)

  // trader
  let w = await c.openWith(bot, () => bot.chat('/cv trader'))
  c.check('trader: /cv trader opens the trader', title(w).includes('商人'), w && c.flat(w.title).slice(0, 40))
  if (w) {
    await bot.clickWindow(50, 0, 0) // medical
    await c.sleep(500)
    w = bot.currentWindow || w
    let slot = find(w, 'bandage', 0, 45)
    let price = slot >= 0 ? leftPrice(w.slots[slot]) : NaN
    let money = await c.money(name)
    const mark = bot.msgs.length
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await c.sleep(700) }
    const bought = await c.heard(bot, /购买 .*×1/, mark, 2000)
    c.check('trader: a left click on the bandage buys one', slot >= 0 && await c.money(name) === money - price && !!bought,
      { slot, price, money: `${money} -> ${await c.money(name)}`, said: bought })
    await bot.clickWindow(46, 0, 0) // ammo
    await c.sleep(500)
    w = bot.currentWindow || w
    slot = find(w, 'ammo_556_m855', 0, 45)
    price = slot >= 0 ? leftPrice(w.slots[slot]) : NaN
    money = await c.money(name)
    const before = c.carried(bot, 'ammo_556_m855')
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await c.sleep(700) }
    c.check('trader: a left click on 5.56 M855 buys 30 rounds', slot >= 0 && await c.money(name) === money - price && c.carried(bot, 'ammo_556_m855') === before + 30,
      { slot, price, money: `${money} -> ${await c.money(name)}`, rounds: `${before} -> ${c.carried(bot, 'ammo_556_m855')}` })
    bot.closeWindow(bot.currentWindow || w)
    await c.sleep(500)
  }

  // stash: the window retitles itself on every change, so the current one is read after each click.
  w = await c.openWith(bot, () => bot.chat('/cv stash'))
  c.check('stash: /cv stash opens the stash', title(w).includes('仓库'), w && c.flat(w.title).slice(0, 40))
  if (w) {
    let slot = find(w, 'sv9', 9, 45)
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await c.sleep(600) }
    w = bot.currentWindow || w
    c.check('stash: a click on the SV9 takes it out', slot >= 0 && c.carried(bot, 'sv9') === 1 && find(w, 'sv9', 9, 45) < 0, { slot })
    slot = find(w, 'sv9', w.inventoryStart, w.slots.length)
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await c.sleep(600) }
    w = bot.currentWindow || w
    c.check('stash: a click on the carried SV9 puts it back', slot >= 0 && c.carried(bot, 'sv9') === 0 && find(w, 'sv9', 9, 45) >= 0, { slot })

    // market: a right click lists.
    slot = find(w, 'splint', 9, 45)
    const pm = slot >= 0 ? await c.openWith(bot, () => bot.clickWindow(slot, 1, 0)) : null
    c.check('market: a right click on the splint opens the pricing screen', !!pm, pm && c.flat(pm.title).slice(0, 40))
    if (pm) {
      const back = await c.openWith(bot, () => bot.clickWindow(26, 0, 0))
      if (back) bot.closeWindow(back)
      else bot.closeWindow(bot.currentWindow || pm)
      await c.sleep(500)
    } else bot.closeWindow(bot.currentWindow || w)
  }
  const market = await c.openWith(bot, () => bot.chat('/cv market'))
  if (market) {
    await bot.clickWindow(53, 0, 0) // own listings
    await c.sleep(600)
    const cur = bot.currentWindow || market
    const listed = find(cur, 'splint', 0, 45)
    c.check('market: the splint is among the bot\'s listings', listed >= 0)
    if (listed >= 0) { await bot.clickWindow(listed, 0, 0); await c.sleep(800) }
    const mailed = await c.waitFor(() => (c.profile(name).mailbox || []).some(r => r.id === 'splint'), 12000)
    c.check('market: taking it down sends it to the mailbox', listed >= 0 && mailed)
    bot.closeWindow(bot.currentWindow || cur)
    await c.sleep(500)
  } else c.check('market: /cv market opens', false)

  // secure container
  w = await c.openWith(bot, () => bot.chat('/cv secure'))
  c.check('secure: /cv secure opens the secure container', title(w).includes('安全箱'), w && c.flat(w.title).slice(0, 40))
  if (w) {
    const slot = find(w, 'bandage', w.inventoryStart, w.slots.length)
    if (slot >= 0) { await bot.clickWindow(slot, 0, 0); await c.sleep(600) }
    const cur = bot.currentWindow || w
    c.check('secure: a click on a carried bandage moves it in', slot >= 0 && find(cur, 'bandage', 0, cur.inventoryStart) >= 0, { slot })
    bot.closeWindow(cur)
  }
  return c.finish()
}

main().catch(c.crashed)
