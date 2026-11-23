// What a raider reaches for in a fight, with one bot (a fresh account) solo on the proving ground:
//
//   medkit   down 45 HP (RCON damage), right mouse held on the starter medkit for 3.5 s: health goes
//            back up, and the action bar says what it healed and what is left (+N HP, 剩余治疗量).
//   frag     a frag given, right mouse pressed and let go facing the ground ahead: it leaves the hand
//            (none carried) and goes off (craftov:throw.explode) within 5 s.
//   skill    sneak + F (the operator skill, as the client sends it: Shift down, then the swap-hands
//            key): the action bar names the skill (技能 · …), and a second try at once says it is
//            cooling down (技能冷却中).
//   out      it extracts through the always-open extract.
//
//   node meds.js     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: the defaults; a short match.wait-seconds keeps it quick. The bot is made op (the
// proving ground is a test map) and deopped when done; soldiers are killed so they do not shoot it.
// Prints PASS / FAIL per check; the exit code is the number of failures (2+ when it broke).
const c = require('./common')


async function main () {
  const name = 'Med' + c.tag
  const bot = await c.make(name)
  await c.rcon(`op ${name}`)
  bot.chat('/cv queue proving NORMAL SOLO')
  const landed = await c.waitFor(() => c.inRaid(bot), 120000)
  c.check('the bot lands on the proving ground', landed, bot.worldName)
  if (!landed) return c.finish(2)
  await c.sleep(4000) // insertion protection
  await c.clearSoldiers()
  // Somewhere open and flat.
  await c.clearArea(bot)

  // medkit
  await c.rcon(`damage ${name} 45 minecraft:generic`)
  await c.sleep(800)
  const hurt = bot.health
  const med = c.hotbar(bot, 'medkit_s')
  const mark = bot.msgs.length
  if (med >= 0) {
    bot.setQuickBarSlot(med)
    await c.sleep(500)
    bot.activateItem()
    await c.sleep(3500)
    bot.deactivateItem()
    await c.sleep(800)
  }
  await c.clearSoldiers()
  c.check('medkit: the starter medkit heals', med >= 0 && bot.health > hurt, { slot: med, health: `${hurt} -> ${bot.health}` })
  const bar = bot.msgs.slice(mark).find(m => m.includes('剩余治疗量'))
  c.check('medkit: the action bar says what it healed and what is left', !!bar && /\+\d+ HP/.test(bar), bar || bot.msgs.slice(mark))

  // frag
  await c.rcon(`cv give ${name} frag`)
  await c.waitFor(() => c.hotbar(bot, 'frag') >= 0, 3000)
  const frag = c.hotbar(bot, 'frag')
  const from = bot.sounds.length
  if (frag >= 0) {
    bot.setQuickBarSlot(frag)
    await c.sleep(500)
    await bot.look(0, -0.3, true)
    bot.activateItem()
    await c.sleep(200)
    bot.deactivateItem()
    await c.sleep(300)
    await c.tp(bot, '45.5 65 70.5') // out of the blast
  }
  const blast = await c.waitFor(() => bot.sounds.slice(from).some(s => s.key === 'craftov:throw.explode'), 6000)
  c.check('frag: thrown, it leaves the hand', frag >= 0 && c.carried(bot, 'frag') === 0, { slot: frag })
  c.check('frag: and goes off', blast, bot.sounds.slice(from).map(s => s.key).join(', '))

  // skill: Shift down, then F in the same moment.
  const skill = async () => {
    const mark = bot.msgs.length
    bot.setControlState('sneak', true)
    await c.sleep(250)
    c.dig(bot, 6)
    await c.sleep(250)
    bot.setControlState('sneak', false)
    await c.sleep(600)
    return bot.msgs.slice(mark)
  }
  bot.setQuickBarSlot(0)
  await c.sleep(500)
  let said = await skill()
  c.check('skill: sneak + F uses the operator skill', said.some(m => m.includes('技能 ·')), said)
  said = await skill()
  c.check('skill: right after, it is cooling down', said.some(m => m.includes('技能冷却中')), said)

  // out
  await c.clearSoldiers()
  c.check('out: the bot extracts', await c.extract(bot))
  await c.rcon(`deop ${name}`)
  return c.finish()
}

main().catch(c.crashed)
