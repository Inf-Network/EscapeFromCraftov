// Two bots as a squad, end to end (revive.js and credit.js go deeper into each step):
//
//   range    A (Sqa####) on the private range shoots a training dummy with an M4C (left mouse, the
//            capture block in front of the crosshair): it hears a hit (craftov:hit.*); the leave item
//            on key 9 takes it back to the lobby.
//   party    A invites B (Sqb####), B accepts: /cv party lists both (2/3).
//   raid     A queues the party for a trio raid on the proving ground: both land in the same raid.
//   down     B takes a lethal hit (RCON damage 500): it is downed (第一次倒地), not dead, and A's HUD
//            alert banner names it (cv hud).
//   revive   A next to B holds right click on it: B is up again (已被救起) within 10 s.
//   second   B takes another lethal hit: downed a second time (第二次倒地); a blast while it is down
//            kills it (spectator; generic damage does not finish a downed player, hostile damage does).
//   out      A extracts through the proving ground's always-open extract.
//
//   node squad.js     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// Server config: test.enabled true (the bots, not op, queue for the proving ground); a short
// match.wait-seconds keeps it quick. Soldiers are killed now and then so they do not join in.
// Prints PASS / FAIL per check; the exit code is the number of failures (2+ when it broke).
const c = require('./common')

const plain = j => [...JSON.stringify(j || '').matchAll(/"text":\{"type":"string","value":"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('')

async function main () {
  const a = await c.make('Sqa' + c.tag)
  const b = await c.make('Sqb' + c.tag)
  for (const bot of [a, b]) {
    bot.titles = []
    bot._client.on('set_title_text', p => bot.titles.push(plain(p.text)))
    bot._client.on('set_title_subtitle', p => bot.titles.push(plain(p.text)))
  }
  const titled = (bot, text) => bot.titles.some(t => t.includes(text))

  // range
  c.check('range: A is on the private range', await c.enterRange(a))
  await c.rcon(`cv give ${a.username} m4c`)
  await c.sleep(800)
  const slot = c.hotbar(a, 'm4c')
  if (slot >= 0) a.setQuickBarSlot(slot)
  await c.sleep(1200) // the draw
  c.dropKey(a) // load it
  await c.sleep(6500)
  const dummy = a.nearestEntity(e => e.name === 'zombie')
  if (dummy) await a.lookAt(dummy.position.offset(0, 1.3, 0), true)
  const from = a.sounds.length
  for (let i = 0; i < 3 && !a.sounds.slice(from).some(s => /craftov:hit\./.test(s.key)); i++) {
    await c.pullTrigger(a)
    await c.sleep(500)
  }
  c.check('range: A shoots a training dummy and hears the hit', a.sounds.slice(from).some(s => /craftov:hit\./.test(s.key)),
    dummy ? a.sounds.slice(from).map(s => s.key).join(', ') : 'no dummy in sight')
  c.check('range: the leave item takes A back to the lobby', await c.leaveRange(a), a.worldName)
  await c.sleep(1000)

  // party
  a.chat(`/cv party invite ${b.username}`)
  await c.sleep(800)
  b.chat('/cv party accept')
  await c.sleep(800)
  const mark = a.msgs.length
  a.chat('/cv party')
  c.check('party: A and B are one party', !!await c.heard(a, new RegExp(`小队 \\(2/3\\).*${b.username}`), mark, 3000))

  // raid
  a.chat('/cv queue proving NORMAL TRIO')
  const landed = await c.waitFor(() => c.inRaid(a) && c.inRaid(b), 120000)
  c.check('raid: both land in the same raid', landed && a.worldName === b.worldName, `${a.worldName} / ${b.worldName}`)
  if (!landed) return c.finish(2)
  await c.sleep(4000) // insertion protection
  await c.clearSoldiers()
  // Somewhere flat for the revive.
  await c.clearArea(a)
  await c.tp(b, '45.5 65 95.5')

  // down
  await c.rcon(`damage ${b.username} 500 minecraft:generic`)
  c.check('down: B is knocked down, not killed', await c.waitFor(() => titled(b, '第一次倒地'), 3000) && c.inRaid(b), b.titles.slice(-2))
  const banner = await c.waitFor(async () => { const h = await c.rcon(`cv hud ${a.username}`); return h.includes(b.username) && h.includes('倒地') }, 4000)
  c.check('down: A\'s alert banner names the downed B', banner)

  // revive
  const target = () => a.players[b.username] && a.players[b.username].entity
  await c.clearSoldiers()
  const revived = await c.waitFor(async () => {
    const t = target()
    if (t) a._client.write('use_entity', { target: t.id, mouse: 0, hand: 0, sneaking: false })
    return titled(b, '已被救起')
  }, 10000, 150)
  c.check('revive: A holding right click on B gets it up', revived && c.inRaid(b), b.titles.slice(-2))

  // second
  await c.sleep(1500)
  await c.rcon(`damage ${b.username} 500 minecraft:generic`)
  c.check('second: B is knocked down a second time', await c.waitFor(() => titled(b, '第二次倒地'), 3000), b.titles.slice(-2))
  await c.sleep(1000)
  // Only hostile damage finishes a downed player (RaidManager.onDamage): an explosion does.
  await c.rcon(`damage ${b.username} 500 minecraft:explosion`)
  c.check('second: a hit while downed kills B', await c.waitFor(() => b.game.gameMode === 'spectator', 4000), b.game.gameMode)

  // out: dead B watches A until A is out, and Shift takes it to the lobby (or the raid's end does).
  c.check('out: A extracts', await c.extract(a))
  if (!await c.waitFor(() => c.inLobby(b), 10000)) {
    b.setControlState('sneak', true)
    await c.sleep(500)
    b.setControlState('sneak', false)
  }
  c.check('out: B is back in the lobby', await c.waitFor(() => c.inLobby(b), 40000), b.worldName)
  return c.finish()
}

main().catch(c.crashed)
