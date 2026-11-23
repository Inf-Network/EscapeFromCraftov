// Vanilla storage, mid-use med swaps and the medic's bonus (issue #46), with headless bots:
//
//   containers  next to a bot in the lobby (adventure, then survival) and in a proving-ground raid
//               (adventure): a copper chest and a shelf placed with /setblock, and a chest minecart,
//               a villager trading for paper, an item frame and an armour stand moved in from the
//               overworld. No window opens, the shelf, frame and stand take nothing from the hand,
//               and in the raid a hit leaves the frame its item and the stand standing (the lobby
//               cancels every hit anyway). Twenty quick hits leave a chest minecart (with a stone
//               in it) and a boat standing, in the lobby and the raid. In the raid, with a fixed,
//               invisible frame under the crosshair (a map's poster), the right click the vanilla
//               client sends (the clicks on the frame, then the item use) still pulls a frag's pin
//               (put back by changing slot: nothing goes off) and raises a K47's sights. In
//               creative mode (the map editor's) all of them work, a hit knocks the frame's item
//               out and one breaks a vehicle, which shows the checks can see a change. The terminal
//               still opens in both places. Before #46 every adventure check but the poster's
//               failed; the poster's and the vehicles' failed before the review fixes.
//   swap        in a proving-ground raid with a broken left leg: a painkiller is started and a
//               container-0 hotbar-swap click (what a modified client sends with no screen open)
//               puts a surgery kit into the hand. 1.5 s later the leg is still broken, no painkiller
//               took effect and the kit still has 160/160. The same when the server itself puts the
//               kit into the hand (/item replace, which no click handler sees), and when the use is
//               then sent again (it would restart the record but not vanilla's timer). A painkiller
//               used normally still works, and a loot box opens. Before #46 the kit set the leg.
//   medic       the medic (profile edited offline) at 70 HP uses a fresh medkit: "+30 HP", 95 of
//               120 left, 100 HP; again at 30 HP the kit heals 70 for 58.3 points (37 left). The
//               vanguard at 70 HP keeps 90. Before #46 the medic got "+36 HP" and kept 84.
//
//   node vanilla.js [all|containers|swap|medic]
//   env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local SERVER_DIR=../../server/run
//
// Needs an offline-mode server (setup.sh --local) without a whitelist. SERVER_DIR is read for player
// profiles, and its plugins/Craftov/config.yml is edited for the run (match.wait-seconds 2,
// cinematic.enabled false; put back at the end, then /cv reload). Bots use fresh random names and
// are made op (the proving ground is test-only). Exits 0 when every check passed.
//
// Server config (tools/bot/suite.js sets it for its run): no whitelist; the script sets
// match.wait-seconds 2 and cinematic.enabled false itself (and puts them back).
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const { Vec3 } = require('vec3')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const SERVER_DIR = env.SERVER_DIR
const CONFIG = path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

// ------------------------------------------------------------------ text, items, profiles, config

/** Plain text of a chat component as the protocol library decodes it (NBT-typed nodes). */
function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n['']) + flat(n.extra)
}
function comp (item, type) { const c = item && item.components && item.components.find(x => x.type === type); return c ? c.data : null }
function cid (item) {
  const data = comp(item, 'custom_data')
  const pbv = data && data.value && data.value.PublicBukkitValues
  const id = pbv && pbv.value['craftov:id']
  return id ? id.value : null
}
const lore = item => (comp(item, 'lore') || []).map(flat)
const pool = item => (lore(item).find(l => l.startsWith('剩余治疗量')) || '').replace('剩余治疗量 ', '')
/** Inventory window slot of the first stack of `id` (hotbar 36-44 first), or -1. */
function slotOf (bot, id) {
  for (const range of [[36, 45], [9, 36]]) {
    for (let s = range[0]; s < range[1]; s++) if (cid(bot.inventory.slots[s]) === id) return s
  }
  return -1
}
const count = (bot, id) => bot.inventory.slots.reduce((n, it) => n + (cid(it) === id ? it.count : 0), 0)
/**
 * Puts a stack of `id` in the selected hotbar slot (handing one out first if there is none): a
 * stack in the pockets goes to hotbar slot 5 with a number-key swap. Returns the hotbar index.
 */
async function hold (bot, id) {
  if (slotOf(bot, id) < 0) {
    await rcon(`cv give ${bot.username} ${id}`)
    await waitFor(() => slotOf(bot, id) >= 0, 3000)
  }
  let s = slotOf(bot, id)
  if (s < 0) throw new Error('no ' + id + ': ' + bot.inventory.slots.map(cid).join(','))
  if (s < 36) {
    await bot.clickWindow(s, 5, 2)
    await waitFor(() => cid(bot.inventory.slots[41]) === id, 2000)
    s = 41
  }
  bot.setQuickBarSlot(s - 36)
  await sleep(400)
  return s - 36
}

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(SERVER_DIR, 'plugins/Craftov/data/players', offlineUuid(name) + '.json')

const configBefore = fs.readFileSync(CONFIG, 'utf8')
/** Sets `section.key: value` lines in config.yml (adding the key under the section when missing). */
async function setConfig (section, values) {
  let s = fs.readFileSync(CONFIG, 'utf8')
  for (const [k, v] of Object.entries(values)) {
    const block = new RegExp(`^(${section}:\\n(?:(?:[ \\t]+.*|[ \\t]*#.*|)\\n)*?)[ \\t]+${k}:.*$`, 'm')
    s = block.test(s) ? s.replace(block, `$1  ${k}: ${v}`)
      : new RegExp(`^${section}:\\n`, 'm').test(s) ? s.replace(new RegExp(`^${section}:\\n`, 'm'), `${section}:\n  ${k}: ${v}\n`)
        : s + `\n${section}:\n  ${k}: ${v}\n`
  }
  fs.writeFileSync(CONFIG, s)
  log('config', section, values, await rcon('cv reload'))
}

// ------------------------------------------------------------------ bots

function spawnBot (prefix, name = prefix + tag) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.bars = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  bot.sounds = []
  const heard = s => { s = String(s); if (/craftov:throw\./.test(s)) bot.sounds.push({ name: s, at: Date.now() }) }
  bot.on('soundEffectHeard', heard)
  bot._client.on('entity_sound_effect', p => { const m = JSON.stringify(p).match(/craftov:[a-z0-9_.\/]+/); if (m) heard(m[0]) })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push(s); log(name, 'BAR', s) } })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', async () => { clearTimeout(t); await sleep(3000); resolve(bot) })
  })
}
async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(250)
  }
  return false
}
/** The window `fn` opens within `ms`, or null; a window that opens is closed again. */
function opens (bot, fn, ms = 1500) {
  return new Promise(resolve => {
    const done = w => { clearTimeout(t); bot.removeListener('windowOpen', done); if (w) { try { bot.closeWindow(w) } catch {} } resolve(w || null) }
    const t = setTimeout(() => done(null), ms)
    bot.on('windowOpen', done)
    Promise.resolve(fn()).catch(e => log('click failed', e.message))
  })
}
/**
 * One right click as the vanilla client sends it with `entity` under the crosshair when it does not
 * act on the entity itself (a fixed frame): the click at a point on it, the click on it, then the use
 * of the item in hand, all at once.
 */
function vanillaClick (bot, entity) {
  const hit = new Vec3(0, 0.03, 0)
  bot._client.write('use_entity', { target: entity.id, mouse: 2, sneaking: false, hand: 0, x: hit.x, y: hit.y, z: hit.z, location: hit })
  bot._client.write('use_entity', { target: entity.id, mouse: 0, sneaking: false, hand: 0, location: new Vec3(0, 0, 0) })
  bot.activateItem()
}
/** The gun's hand mark on a stack (the damage component): 3 aiming, 4 aimed. */
const handMark = item => { const d = comp(item, 'damage'); return typeof d === 'number' ? d : 0 }
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'
async function quit (bot) {
  bot.quit()
  await waitFor(async () => !(await rcon('list')).includes(bot.username), 15000)
  await sleep(1500) // the profile is written on the way out
}
async function deploy (bot) {
  bot.chat('/cv queue proving NORMAL SOLO')
  const ok = await waitFor(() => inRaid(bot), 120000)
  await sleep(1500)
  return ok
}
async function leaveRaid (bot) {
  await rcon(`damage ${bot.username} 1000 minecraft:generic`)
  await waitFor(() => inLobby(bot), 60000)
}
const hp = bot => Math.round(bot.health * 5 * 10) / 10 // health is scaled to 20 of 100
const wounds = async name => Number(((await rcon(`cv hud ${name}`)).match(/wounds=(\d+)/) || [0, -1])[1])
const FRACTURE = 4
const PAINKILLER = 8

// ------------------------------------------------------------------ containers

/** Walks up to the nearest interaction entities (loot boxes, lobby stations) until one opens a window. */
async function openNearestInteraction (bot) {
  const near = Object.values(bot.entities).filter(e => e.name === 'interaction').sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))
  for (const it of near.slice(0, 6)) {
    const p = it.position
    await rcon(`execute as ${bot.username} at @s run tp @s ${p.x.toFixed(2)} ${p.y.toFixed(2)} ${(p.z - 1.5).toFixed(2)}`)
    await sleep(800)
    const w = await opens(bot, () => bot.activateEntity(it), 2500)
    if (w) return w
  }
  return null
}

/**
 * Places each vanilla thing two blocks east of the bot and tries it in each game mode: in `modes`
 * other than creative nothing may happen, in creative it must work. Hits on frames and stands are
 * tried only where `hits` is set: the lobby cancels every hit on anything already, though not on a
 * vehicle, so those are tried everywhere. The poster (a fixed frame) is tried where `poster` is set,
 * in a raid: grenades and sights work only there.
 */
async function vanillaChecks (bot, where, modes, hits, poster) {
  const name = bot.username
  const at = bot.entity.position.floored()
  const spot = at.offset(2, 0, 0)
  const pos = `${spot.x} ${spot.y} ${spot.z}`
  const center = `${spot.x + 0.5} ${spot.y} ${spot.z + 0.5}`
  const run = cmd => rcon(`execute in minecraft:${bot.worldName} run ${cmd}`)
  log(name, where, 'at', at.toString(), 'world', bot.worldName)
  // Something of ours in the hand: the shelf, frame and stand would take it, the villager buys paper.
  // Not a med: outside a raid those refuse every right-click themselves, which would hide the guard.
  const ITEM = 'gold_bar'
  await hold(bot, ITEM)
  const held = () => cid(bot.heldItem)
  await run(`fill ${spot.x} ${spot.y} ${spot.z} ${spot.x} ${spot.y + 2} ${spot.z} minecraft:air`)
  const below = bot.blockAt(spot.offset(0, -1, 0))
  if (!below || below.name === 'air') {
    await run(`setblock ${spot.x} ${spot.y - 1} ${spot.z} minecraft:stone`) // the frame hangs on it
  }

  const entity = (type) => Object.values(bot.entities).filter(e => e.name === type && e.position.distanceTo(spot.offset(0.5, 0, 0.5)) < 1.5)[0]
  // Mobs and armour stands summoned in the lobby or a raid are refused (AiService lets only the
  // plugin's own spawns in), and nothing can be killed in the lobby. So each one is made in a
  // force-loaded chunk of the overworld and moved over (the way a map's own entities arrive, without
  // a spawn), and taken back there to be removed.
  await rcon('execute in minecraft:overworld run forceload add 0 0')
  const clear = async () => {
    await rcon('execute in minecraft:overworld run tp @e[tag=v46] 0.5 100 0.5')
    await rcon('kill @e[tag=v46]')
  }
  async function summon (type, nbt) {
    await clear()
    await rcon(`execute in minecraft:overworld run summon minecraft:${type} 0.5 100 0.5 {Tags:["v46"],NoGravity:1b${nbt ? ',' + nbt : ''}}`)
    await run(`tp @e[tag=v46] ${center}`)
    await waitFor(() => entity(type), 3000)
    return entity(type)
  }
  const data = p => run(`data get entity @e[tag=v46,limit=1] ${p}`)
  const alive = async () => /Count: 1/.test(await run('execute if entity @e[tag=v46]'))

  for (const mode of modes) {
    await rcon(`gamemode ${mode} ${name}`)
    await waitFor(() => bot.game.gameMode === mode, 3000)
    await sleep(300)
    const guarded = mode !== 'creative'
    const want = guarded ? 'nothing happens' : 'it works (control)'
    const label = `${where} ${mode}`

    // Copper chest.
    await run(`setblock ${pos} minecraft:copper_chest[facing=west]`)
    await sleep(400)
    let w = await opens(bot, () => bot.activateBlock(bot.blockAt(spot), new Vec3(-1, 0, 0), new Vec3(0, 0.5, 0.5)))
    check(`${label}: copper chest, ${want}`, guarded ? !w : !!w, w ? w.type : 'no window')

    // Shelf: a right-click swaps the hand with the slot it points at.
    await run(`setblock ${pos} minecraft:oak_shelf[facing=west]`)
    await sleep(400)
    w = await opens(bot, () => bot.activateBlock(bot.blockAt(spot), new Vec3(-1, 0, 0), new Vec3(0, 0.5, 0.5)))
    await sleep(500)
    const items = await run(`data get block ${pos} Items`)
    check(`${label}: shelf, ${guarded ? 'takes nothing from the hand' : 'takes the item (control)'}`,
      guarded ? !w && !/paper/.test(items) && held() === ITEM : /paper/.test(items), `${items} / holding ${held()}`)
    await run(`setblock ${pos} minecraft:air`)
    if (held() !== ITEM) await hold(bot, ITEM) // the shelf took it: each check starts with it in hand

    // Chest minecart.
    let e = await summon('chest_minecart')
    w = e ? await opens(bot, () => bot.activateEntity(e)) : null
    check(`${label}: chest minecart, ${want}`, !!e && (guarded ? !w : !!w), e ? (w ? w.type : 'no window') : 'not summoned')

    // Villager that buys paper.
    e = await summon('villager', 'NoAI:1b,VillagerData:{profession:"minecraft:librarian",level:2,type:"minecraft:plains"},' +
      'Offers:{Recipes:[{buy:{id:"minecraft:paper",count:1},sell:{id:"minecraft:emerald",count:1},maxUses:99}]}')
    w = e ? await opens(bot, () => bot.activateEntity(e)) : null
    check(`${label}: villager trades, ${want}`, !!e && (guarded ? !w : !!w), e ? (w ? w.type : 'no window') : 'not summoned')

    // Item frame lying on the floor: a right-click puts the hand's item in.
    e = await summon('item_frame', 'Facing:1b')
    if (e) {
      await bot.activateEntity(e)
      await sleep(600)
      const item = await data('Item')
      check(`${label}: item frame, ${want}`, guarded ? !/paper/.test(item) && held() === ITEM : /paper/.test(item), item)
    } else check(`${label}: item frame summoned`, false)

    // Armour stand with arms: a right-click at it puts the hand's item in its hand.
    e = await summon('armor_stand', 'ShowArms:1b,equipment:{head:{id:"minecraft:iron_helmet",count:1}}')
    if (e) {
      await bot.activateEntityAt(e, e.position.offset(0, 0.9, 0))
      await sleep(600)
      const eq = await data('equipment')
      check(`${label}: armour stand, ${guarded ? 'takes nothing and keeps its helmet' : 'takes the item (control)'}`,
        guarded ? !/paper/.test(eq) && /iron_helmet/.test(eq) && held() === ITEM : /paper/.test(eq), eq)
    } else check(`${label}: armour stand summoned`, false)

    if (hits) {
      // A hit knocks a frame's item out.
      e = await summon('item_frame', 'Facing:1b,Item:{id:"minecraft:stone",count:1}')
      if (e) {
        bot.attack(e)
        await sleep(600)
        const item = await data('Item')
        check(`${label}: hitting a frame, ${guarded ? 'leaves it and its item' : 'knocks the item out (control)'}`,
          guarded ? /stone/.test(item) && await alive() : !/stone/.test(item), item)
      } else check(`${label}: frame with an item summoned`, false)
      // An armour stand is no combatant, which RaidManager.canDamage already keeps from harm in a
      // raid even in creative mode: this only makes sure it stays that way.
      if (guarded) {
        e = await summon('armor_stand', 'equipment:{head:{id:"minecraft:iron_helmet",count:1}}')
        if (e) {
          bot.attack(e)
          await sleep(600)
          check(`${label}: hitting an armour stand leaves it standing`, await alive())
        } else check(`${label}: armour stand summoned`, false)
      }
    }

    // Vehicles: hit quickly enough to add up, a minecart or boat breaks into its item (a chest one
    // spills its stone too). Creative breaks one with a single hit.
    for (const [type, nbt] of [['chest_minecart', 'Items:[{Slot:0b,id:"minecraft:stone",count:1}]'], ['oak_boat', '']]) {
      e = await summon(type, nbt)
      if (!e) {
        check(`${label}: ${type} summoned`, false)
        continue
      }
      for (let i = 0; i < (guarded ? 20 : 1); i++) {
        bot.attack(e)
        await sleep(250)
      }
      await sleep(400)
      const standing = await alive()
      const inside = type === 'chest_minecart' && standing ? await data('Items') : ''
      check(`${label}: hitting a ${type}, ${guarded ? 'leaves it standing' + (inside ? ' and full' : '') : 'breaks it (control)'}`,
        guarded ? standing && (type !== 'chest_minecart' || /stone/.test(inside)) : !standing, `${standing ? 'standing' : 'gone'} ${inside}`)
    }

    if (poster && guarded) {
      // A fixed, invisible frame lying in front of the bot, as a map hangs a poster. The vanilla
      // client does nothing with a fixed frame, so a right click sends the clicks on it and then
      // the use of the item in hand: the guard refuses the clicks, the use must still go ahead.
      e = await summon('item_frame', 'Facing:1b,Fixed:1b,Invisible:1b,Item:{id:"minecraft:filled_map",count:1}')
      if (e) {
        await hold(bot, 'frag')
        await sleep(1200) // the item use of any earlier throw is over
        const frags = count(bot, 'frag')
        await bot.lookAt(e.position, true)
        const mark = Date.now()
        vanillaClick(bot, e)
        const pin = await waitFor(() => bot.sounds.some(x => x.name === 'craftov:throw.pin' && x.at > mark), 1500)
        bot.setQuickBarSlot((bot.quickBarSlot + 1) % 9) // the pin goes back in
        await sleep(400)
        bot.deactivateItem()
        await sleep(4000) // past the frag's 3.5 s fuse
        const blast = bot.sounds.some(x => x.name === 'craftov:throw.explode' && x.at > mark)
        check(`${label}: a right click on a poster (fixed frame) with a frag pulls the pin`, pin, bot.sounds.filter(x => x.at > mark))
        check(`${label}: and the pin goes back in with the slot changed: nothing went off`, !blast && count(bot, 'frag') === frags,
          `blast ${blast}, frags ${frags} -> ${count(bot, 'frag')}`)

        // Right mouse held on the poster with a gun: the client repeats the whole click every 4 ticks.
        const hand = await hold(bot, 'k47')
        await sleep(1500) // drawn
        await bot.lookAt(e.position, true)
        const marks = []
        const onSlot = (slot, before, after) => { if (slot === 36 + hand && handMark(before) !== handMark(after)) marks.push(handMark(after)) }
        bot.inventory.on('updateSlot', onSlot)
        for (let t = 0; t < 8; t++) {
          vanillaClick(bot, e)
          await sleep(200)
        }
        const up = handMark(bot.heldItem)
        bot.deactivateItem()
        await sleep(800)
        bot.inventory.removeListener('updateSlot', onSlot)
        check(`${label}: right mouse held on a poster with a K47 raises its sights`, marks.includes(3) && up === 4,
          `marks ${marks.join(',') || 'none'}, ${up} while held`)
      } else check(`${label}: poster summoned`, false)
    }
    await clear()
    if (held() !== ITEM) await hold(bot, ITEM)
  }
  await rcon('execute in minecraft:overworld run forceload remove 0 0')
  await rcon(`gamemode adventure ${name}`)
  await waitFor(() => bot.game.gameMode === 'adventure', 3000)
  await sleep(500)
}

async function containers () {
  const bot = await spawnBot('VgC')
  const name = bot.username
  await rcon('op ' + name)
  await waitFor(() => inLobby(bot), 10000)
  await vanillaChecks(bot, 'lobby', ['adventure', 'survival', 'creative'], false, false)
  let w = await opens(bot, () => bot.chat('/cv'), 4000)
  check('lobby: the terminal (a menu of ours) still opens', !!w, w ? flat(w.title) : 'no window')
  w = await openNearestInteraction(bot)
  check('lobby: a station (an interaction entity of ours) still opens its menu', !!w, w ? flat(w.title) : 'none opened')

  check('containers: deployed', await deploy(bot))
  await sleep(2000)
  await vanillaChecks(bot, 'raid', ['adventure', 'creative'], true, true)
  w = await opens(bot, () => bot.chat('/cv'), 4000)
  check('raid: the terminal still opens', !!w, w ? flat(w.title) : 'no window')
  await leaveRaid(bot)
  await rcon('deop ' + name)
  await quit(bot)
}

// ------------------------------------------------------------------ swap

/** Starts a painkiller from the selected hotbar slot (with a surgery kit somewhere else); returns that slot. */
async function startPainkiller (bot) {
  if (count(bot, 'surgery') === 0) {
    await rcon(`cv give ${bot.username} surgery`)
    await waitFor(() => slotOf(bot, 'surgery') >= 0, 3000)
  }
  const hand = await hold(bot, 'painkiller')
  bot.activateItem()
  await sleep(300)
  return hand
}
/** After a swap attempt: the leg is still broken, no painkiller took effect, the kit is untouched. */
async function unchanged (bot, label) {
  await sleep(2200) // the painkiller's 1.5 s and then some
  bot.deactivateItem()
  await sleep(600)
  const flags = await wounds(bot.username)
  const kit = bot.inventory.slots[slotOf(bot, 'surgery')]
  check(`${label}: the leg is still broken and no painkiller took effect`, flags >= 0 && (flags & FRACTURE) && !(flags & PAINKILLER), `wounds=${flags}`)
  check(`${label}: the surgery kit is untouched`, count(bot, 'surgery') === 1 && pool(kit) === '160/160', pool(kit))
}

async function swap () {
  const bot = await spawnBot('VgS')
  const name = bot.username
  await rcon('op ' + name)
  check('swap: deployed', await deploy(bot))
  await rcon(`cv injure ${name} left_leg fracture`)
  log(name, 'wounds', await wounds(name), 'painkiller', slotOf(bot, 'painkiller'), 'surgery', slotOf(bot, 'surgery'))

  // 1. A hotbar-swap click on the player's own inventory (window 0), no screen open.
  let from = bot.bars.length
  let hand = await startPainkiller(bot)
  await bot.clickWindow(slotOf(bot, 'surgery'), hand, 2)
  await sleep(300)
  check('click: the surgery kit is in the hand now', cid(bot.heldItem) === 'surgery', cid(bot.heldItem))
  await unchanged(bot, 'click')
  check('click: nothing was used', !bot.bars.slice(from).some(b => /已处理|已使用/.test(b)), bot.bars.slice(from).join(' | '))

  // 2. The server puts the kit into the hand mid-use: no click to see, so the finish must refuse it.
  from = bot.bars.length
  hand = await startPainkiller(bot)
  const kit = slotOf(bot, 'surgery')
  const container = kit >= 36 ? kit - 36 : kit
  await rcon(`item replace entity ${name} weapon.mainhand from entity ${name} container.${container}`)
  await rcon(`item replace entity ${name} container.${container} with minecraft:air`)
  await sleep(300)
  check('server swap: the surgery kit is in the hand now', cid(bot.heldItem) === 'surgery', cid(bot.heldItem))
  await unchanged(bot, 'server swap')
  check('server swap: the finish is refused and says so', bot.bars.slice(from).some(b => b.includes('使用中断')) &&
    !bot.bars.slice(from).some(b => /已处理|已使用/.test(b)), bot.bars.slice(from).join(' | '))

  // 3. The same, then the use sent again with the kit in hand: the record restarts, vanilla's timer does not.
  from = bot.bars.length
  hand = await startPainkiller(bot)
  const kit2 = slotOf(bot, 'surgery')
  const container2 = kit2 >= 36 ? kit2 - 36 : kit2
  await rcon(`item replace entity ${name} weapon.mainhand from entity ${name} container.${container2}`)
  await rcon(`item replace entity ${name} container.${container2} with minecraft:air`)
  await sleep(200)
  bot.activateItem()
  await unchanged(bot, 'use sent again')
  check('use sent again: the finish is refused', !bot.bars.slice(from).some(b => /已处理|已使用/.test(b)), bot.bars.slice(from).join(' | '))

  // A painkiller used as meant still works.
  from = bot.bars.length
  await startPainkiller(bot)
  await sleep(2000)
  bot.deactivateItem()
  await sleep(600)
  const flags = await wounds(name)
  check('a painkiller used normally works', bot.bars.slice(from).some(b => b.includes('已使用 止痛药')) && (flags & PAINKILLER) > 0,
    `wounds=${flags} / ${bot.bars.slice(from).join(' | ')}`)

  // A loot box (a menu of ours) still opens.
  const opened = await openNearestInteraction(bot)
  check('a loot box still opens', !!opened, opened ? flat(opened.title) : 'none of the nearest interaction entities opened')
  await leaveRaid(bot)
  await rcon('deop ' + name)
  await quit(bot)
}

// ------------------------------------------------------------------ medic

/** Brings the bot to `target` HP with generic damage. */
async function hurtTo (bot, target) {
  // The first three seconds in a raid are protected: try again until the damage lands.
  for (let i = 0; i < 12 && hp(bot) > target + 0.05; i++) {
    await rcon(`damage ${bot.username} ${(hp(bot) - target).toFixed(2)} minecraft:generic`)
    await sleep(700)
  }
  return hp(bot)
}
async function useMedkit (bot) {
  await hold(bot, 'medkit_s')
  const from = bot.bars.length
  bot.activateItem()
  await sleep(3700)
  bot.deactivateItem()
  await sleep(800)
  const bar = bot.bars.slice(from).find(b => b.includes('HP')) || ''
  return { bar, left: pool(bot.inventory.slots[slotOf(bot, 'medkit_s')]), hp: hp(bot) }
}

async function medic () {
  // The medic: a first visit makes the profile, which is then edited while the bot is away.
  let bot = await spawnBot('VgM')
  const name = bot.username
  await quit(bot)
  const p = JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
  p.operator = 'medic'
  fs.writeFileSync(profileFile(name), JSON.stringify(p, null, 2))
  bot = await spawnBot('VgM', name)
  await rcon('op ' + name)
  check('medic: deployed', await deploy(bot))
  let at = await hurtTo(bot, 70)
  let r = await useMedkit(bot)
  check('medic at 70 HP: a fresh medkit says +30 HP and keeps 95 of 120', at === 70 && r.bar.includes('+30 HP') && r.bar.includes('剩余治疗量 95') &&
    r.left === '95/120' && r.hp === 100, `${at} HP -> ${r.hp}; ${r.bar}; ${r.left}`)
  at = await hurtTo(bot, 30)
  r = await useMedkit(bot)
  // 60 per use x 1.2 = 72 would overshoot: it heals the 70 missing and pays 70 / 1.2 = 58.3.
  check('medic at 30 HP: the kit heals 70 for 58.3 points', at === 30 && r.bar.includes('+70 HP') && r.bar.includes('剩余治疗量 37') && r.hp === 100,
    `${at} HP -> ${r.hp}; ${r.bar}; ${r.left}`)
  await leaveRaid(bot)
  await rcon('deop ' + name)
  await quit(bot)

  // The vanguard (the default operator), same start.
  bot = await spawnBot('VgV')
  await rcon('op ' + bot.username)
  check('vanguard: deployed', await deploy(bot))
  at = await hurtTo(bot, 70)
  r = await useMedkit(bot)
  check('vanguard at 70 HP: +30 HP and 90 of 120 left', at === 70 && r.bar.includes('+30 HP') && r.left === '90/120' && r.hp === 100,
    `${at} HP -> ${r.hp}; ${r.bar}; ${r.left}`)
  await leaveRaid(bot)
  await rcon('deop ' + bot.username)
  await quit(bot)
}

// ------------------------------------------------------------------ run

;(async () => {
  const which = process.argv[2] || 'all'
  try {
    await setConfig('match', { 'wait-seconds': 2 })
    await setConfig('cinematic', { enabled: false })
    if (which === 'all' || which === 'containers') await containers()
    if (which === 'all' || which === 'swap') await swap()
    if (which === 'all' || which === 'medic') await medic()
  } catch (e) {
    check('ran without errors', false, e.stack)
  } finally {
    fs.writeFileSync(CONFIG, configBefore)
    log('config.yml put back', await rcon('cv reload').catch(e => e.message))
  }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
