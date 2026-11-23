// What other players are told about someone's hands (issue #3, StanceService). A, B and C queue as
// one squad for the proving ground; A holds a K47 and B, in front of it, records every equipment and
// entity-data packet about A (and A about B, for the revive). C waits out of everyone's sight (a short
// view distance, at the far corner of the map) until the revive.
//
//   low      standing with the gun: B sees A's main hand as the gun (paper) with custom_model_data
//            flag 0 on and a crossbow use animation, and A using it (living flags bit 1), the use
//            restarted (not using, then using) about every other tick
//   crouch   A sneaks: the gun B sees also has flag 1 on; it goes off again when A stands
//   aim      A holds right mouse: B sees a charged crossbow (the shouldered gun) and A using nothing;
//            the low carry comes back within 2.2 s of letting go
//   throw    A cooks a smoke grenade: B sees it with a trident use (the overhead throw)
//   knife    A switches to its knife: B sees the knife as it is, not in use, and no restarts
//   downed   A is knocked down: B sees A's hands empty and A not using anything
//   revive   B holds right click on A: A sees B holding a bandage (craftov:bandage) with a brush use,
//            and in use; still the bandage once B switches to an empty hotbar slot; C, coming into
//            view of B halfway through, sees the bandage in use too; A gets up
//   self     A with the gun and B reviving are never told they are using their item (A's client must
//            keep digging for the trigger)
//
//   PORT=25565 RCON_PORT=25575 node stances.js
//
// Needs test.enabled (the proving ground) and a short match.wait-seconds. The bots are made op and
// deopped at the end, and their raid is ended. Exit code: number of failed checks (2+ also when the
// setup failed).
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const rcon = cmd => rconRaw(cmd, RCON).then(s => s.replace(/§./g, ''))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args)

let failures = 0
function check (ok, what) {
  log(ok ? 'PASS' : 'FAIL', what)
  if (!ok) failures++
}

const bots = []
function make (name, viewDistance = 'far') {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline', viewDistance })
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  bots.push(bot)
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(2500); resolve(bot) }))
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(250) }
  return false
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_')

/** The living-entity flags (entity data index 8) in an entity_metadata packet, or null. */
function livingFlags (packet) {
  const entry = (packet.metadata || []).find(m => m.key === 8 && typeof m.value === 'number')
  return entry ? entry.value : null
}

/** Records what `viewer` is told about `subject`'s main hand and living flags, and about itself. */
function watch (viewer, subject) {
  const seen = []
  viewer._client.on('entity_equipment', p => {
    if (p.entityId !== subject.entity.id) return
    for (const e of p.equipments || []) if (e.slot === 0) seen.push({ at: Date.now(), kind: 'hand', item: e.item })
  })
  viewer._client.on('entity_metadata', p => {
    const v = livingFlags(p)
    if (v === null) return
    if (p.entityId === subject.entity.id) seen.push({ at: Date.now(), kind: 'flags', value: v })
    if (p.entityId === viewer.entity.id) viewer.ownFlags.push(v)
  })
  return seen
}

const text = item => JSON.stringify(item || {})
const cmdFlags = item => {
  const c = (item && item.components || []).find(x => x.type === 'custom_model_data')
  return c && c.data && c.data.flags ? c.data.flags : []
}
const hand = (seen, from = 0) => seen.filter(s => s.kind === 'hand' && s.at >= from).pop()
const flags = (seen, from = 0) => seen.filter(s => s.kind === 'flags' && s.at >= from)
const lastFlags = seen => { const f = flags(seen); return f.length ? f[f.length - 1].value : 0 }

async function main () {
  const tag = Math.floor(Math.random() * 9000)
  const A = await make('PoseA' + tag)
  const B = await make('PoseB' + tag)
  // Two chunks: C only sees players within 32 blocks, so it can be put out of view inside the map.
  const C = await make('PoseC' + tag, 2)
  A.ownFlags = []
  B.ownFlags = []
  C.ownFlags = []
  for (const bot of [A, B, C]) await rcon(`op ${bot.username}`)
  const crossbowId = B.registry.itemsByName.crossbow.id
  const paperId = B.registry.itemsByName.paper.id
  const before = new Set((await rcon('cv raids')).match(/R\d+/g) || [])
  for (const bot of [B, C]) {
    A.chat(`/cv party invite ${bot.username}`)
    await sleep(1000)
    bot.chat('/cv party accept')
    await sleep(1000)
  }
  A.chat('/cv queue proving NORMAL TRIO')
  if (!await waitFor(() => inRaid(A) && inRaid(B) && inRaid(C), 120000)) throw new Error('the bots never reached a raid')
  const raids = ((await rcon('cv raids')).match(/R\d+/g) || []).filter(r => !before.has(r))
  // the landing: the entry camera over, everyone on the ground
  if (!await waitFor(() => [A, B, C].every(bot => bot.game.gameMode === 'adventure'), 30000)) throw new Error('the bots never landed')
  await sleep(1500)
  // C to the corner of the proving ground (bounds x 0..119, z 0..103) farthest from A, on a block of its own
  const corner = [[5, 5], [114, 5], [5, 98], [114, 98]]
    .map(([x, z]) => ({ x, z, d: Math.hypot(x - A.entity.position.x, z - A.entity.position.z) }))
    .sort((a, b) => b.d - a.d)[0]
  await rcon(`execute in minecraft:${A.worldName} run setblock ${corner.x} 120 ${corner.z} minecraft:stone`)
  await rcon(`execute in minecraft:${A.worldName} run tp ${C.username} ${corner.x + 0.5} 121 ${corner.z + 0.5}`)
  const seen = watch(B, A)
  const seenB = watch(A, B)
  const seenByC = watch(C, B)
  try {
    await rcon('kill @e[type=minecraft:zombie]')
    await rcon('kill @e[type=minecraft:husk]')
    for (const id of ['k47', 'knife_tactical', 'smoke']) await rcon(`cv give ${A.username} ${id} 1`)
    await sleep(1000)
    const slotOf = id => A.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes(`"${id}"`))
    const gun = slotOf('k47')
    const knife = slotOf('knife_tactical')
    const smoke = slotOf('smoke')
    if (gun < 0 || knife < 0 || smoke < 0) throw new Error(`k47 in ${gun}, knife in ${knife}, smoke in ${smoke}`)
    await rcon(`execute as ${B.username} at ${A.username} run tp @s ^ ^ ^3 facing entity ${A.username} feet`)
    // the knife first, so that B is sent the gun coming into A's hand (A may have landed holding one)
    A.setQuickBarSlot(knife - 36)
    await sleep(800)
    A.setQuickBarSlot(gun - 36)
    await sleep(2500)

    // low
    let from = Date.now()
    await sleep(2000)
    let h = hand(seen)
    check(!!h && h.item.itemId === paperId && cmdFlags(h.item)[0] === true && text(h.item).includes('consumable'),
      `low: B sees A's gun as paper with flag 0 and a use (${h ? `item ${h.item.itemId}, flags ${JSON.stringify(cmdFlags(h.item))}` : 'no equipment seen'})`)
    let f = flags(seen, from)
    const restarts = f.filter((x, i) => i > 0 && (f[i - 1].value & 1) === 0 && (x.value & 1) === 1).length
    check((lastFlags(seen) & 1) === 1 && restarts >= 12 && restarts <= 25, `low: A is using it, the use restarted ${restarts} times in 2 s (about 20)`)

    // crouch
    A.setControlState('sneak', true)
    await sleep(800)
    h = hand(seen, Date.now() - 800)
    check(!!h && cmdFlags(h.item)[0] === true && cmdFlags(h.item)[1] === true, `crouch: flag 1 on (${h ? JSON.stringify(cmdFlags(h.item)) : 'no new equipment'})`)
    A.setControlState('sneak', false)
    await sleep(800)
    h = hand(seen, Date.now() - 800)
    check(!!h && cmdFlags(h.item)[1] === false, `crouch: flag 1 off again standing (${h ? JSON.stringify(cmdFlags(h.item)) : 'no new equipment'})`)

    // aim
    A.activateItem()
    const aiming = setInterval(() => A.activateItem(), 200)
    await sleep(1200)
    from = Date.now()
    await sleep(800)
    h = hand(seen)
    f = flags(seen, from)
    check(!!h && h.item.itemId === crossbowId && text(h.item).includes('charged_projectiles'),
      `aim: B sees a charged crossbow (${h ? `item ${h.item.itemId}` : 'nothing'})`)
    check((lastFlags(seen) & 1) === 0 && f.every(x => (x.value & 1) === 0), `aim: A is using nothing (${f.length} flag updates, last ${lastFlags(seen)})`)
    clearInterval(aiming)
    A.deactivateItem()
    await sleep(2200)
    h = hand(seen)
    check(!!h && h.item.itemId === paperId && (lastFlags(seen) & 1) === 1, `aim: low again 2.2 s after letting go (item ${h && h.item.itemId}, flags ${lastFlags(seen)})`)

    // self, with the gun: A is never told it uses it (cooking the grenade below is a real use, which
    // the server tells A about as vanilla does)
    const gunFlags = A.ownFlags.slice()
    check(gunFlags.every(v => (v & 1) === 0), `self: A with the gun was never told it uses it (${[...new Set(gunFlags)].join(',') || 'no updates'})`)

    // throw
    A.setQuickBarSlot(smoke - 36)
    await sleep(800)
    A.activateItem()
    await sleep(700)
    h = hand(seen)
    // mineflayer's protocol data still calls use animation 5 (the trident's) "spear"
    check(!!h && /"animation":\s*"?(trident|spear|5)"?/i.test(text(h.item)) && (lastFlags(seen) & 1) === 1,
      `throw: B sees the grenade with a trident use, in use (${h ? text(h.item).match(/"consumable"[^\]]{0,160}/) : 'nothing'})`)
    await rcon(`execute as ${A.username} at @s run tp @s ~ ~ ~ 180 -40`)
    A.deactivateItem()
    await sleep(1500)

    // knife
    A.setQuickBarSlot(knife - 36)
    await sleep(1000)
    from = Date.now()
    await sleep(1000)
    h = hand(seen)
    f = flags(seen, from)
    check(!!h && !text(h.item).includes('consumable') && (lastFlags(seen) & 1) === 0 && f.length === 0,
      `knife: the knife as it is, not in use, no restarts (${f.length} flag updates)`)

    // downed
    A.setQuickBarSlot(gun - 36)
    await sleep(1500)
    await rcon(`damage ${A.username} 500 minecraft:generic`)
    await sleep(1500)
    h = hand(seen)
    check(!!h && (h.item.itemCount === 0 || h.item.itemId === undefined || text(h.item) === '{"itemCount":0}') && (lastFlags(seen) & 1) === 0,
      `downed: B sees A's hands empty, not in use (${h ? text(h.item).slice(0, 80) : 'nothing'}, flags ${lastFlags(seen)})`)

    // revive
    await rcon(`execute as ${B.username} at ${A.username} run tp @s ^ ^ ^1.4 facing entity ${A.username} feet`)
    await sleep(500)
    const target = B.players[A.username] && B.players[A.username].entity
    if (!target) throw new Error('B cannot see A')
    const bandaged = (seenOf, from) => {
      const shown = hand(seenOf, from)
      return !!shown && text(shown.item).includes('craftov:bandage') && /"animation":\s*"?(brush|9)"?/i.test(text(shown.item)) && (lastFlags(seenOf) & 1) === 1
    }
    B.activateEntity(target)
    const reviving = setInterval(() => B.activateEntity(target), 200)
    await sleep(1200)
    h = hand(seenB)
    check(bandaged(seenB, 0), `revive: A sees B with a bandage and a brush use, in use (${h ? text(h.item).slice(0, 120) : 'nothing'})`)
    // an empty hand: the server sends the empty slot, and the bandage has to be shown instead
    const empty = B.inventory.slots.findIndex((it, i) => i >= 36 && i <= 44 && !it)
    if (empty < 0) throw new Error('B has no empty hotbar slot')
    from = Date.now()
    B.setQuickBarSlot(empty - 36)
    await sleep(700)
    h = hand(seenB, from)
    check(bandaged(seenB, from), `revive: empty-handed, A still sees B with the bandage in use (${h ? text(h.item).slice(0, 120) : 'no new equipment'})`)
    // someone coming into view of the empty-handed reviver
    const told = seenByC.length
    if (C.entity.position.distanceTo(B.entity.position) < 40) throw new Error(`C was not kept out of view (${C.entity.position.floored()}, B at ${B.entity.position.floored()})`)
    await rcon(`execute as ${C.username} at ${B.username} run tp @s ~ ~ ~`)
    await sleep(1200)
    h = hand(seenByC)
    check(seenByC.length > told && bandaged(seenByC, 0),
      `revive: C, coming into view, sees B with the bandage in use (${h ? text(h.item).slice(0, 120) : 'no equipment'}, flags ${lastFlags(seenByC)})`)
    await sleep(3500)
    clearInterval(reviving)
    const health = await rcon(`data get entity ${A.username} Health`)
    check(/: (3[0-9]|20)\./.test(health), `revive: A is up (${health.replace(/.*data: /, '')})`)

    // self, reviving: B is never told it uses the bandage it is shown with
    check(B.ownFlags.every(v => (v & 1) === 0), `self: B reviving was never told it uses anything (${[...new Set(B.ownFlags)].join(',') || 'no updates'})`)
  } finally {
    for (const raid of raids) await rcon(`cv end ${raid}`).catch(() => {})
    await sleep(1000)
    for (const bot of bots) {
      await rcon(`deop ${bot.username}`).catch(() => {})
      bot.quit()
    }
  }
}

main().then(() => {
  log(failures === 0 ? 'ALL PASS' : `${failures} FAILED`)
  setTimeout(() => process.exit(failures), 500)
}).catch(e => {
  log('SETUP FAILED', e && e.stack || e)
  for (const bot of bots) bot.quit()
  setTimeout(() => process.exit(2 + failures), 500)
})
