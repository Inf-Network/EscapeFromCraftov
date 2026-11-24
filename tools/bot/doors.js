// Key doors and lever extracts on 锈港 (rustport), with one bot (a fresh account) in a solo raid:
//
//   locked   the bot right-clicks the command centre's server-room door (lock hq_server) without its
//            key: the door stays shut and the action bar names the key it needs (门被锁住了，需要 …).
//   key      given the key card (key_hq, 2 uses), the same click opens the door (已解锁 …, 剩余 1 次).
//   shut     before the lever is pulled, the bot stands 12 s in the drain extract (西南排水渠, 8 s to
//            extract once open): it stays in the raid and the action bar says the lever comes first
//            (需要先开启 …), so the drain check below proves the lever opened it.
//   lever    the bot pulls the radio tower's lever (switch radio): the raid's alert banner says it
//            was pulled (已被拉下, read with cv hud), and a second pull says it is already on (已经开启).
//   drain    standing in the drain extract (西南排水渠, opened by that lever), the bot is taken out
//            when the lever's countdown (extract.switch-countdown-seconds, 60) ends: back in the lobby.
//
//   node doors.js [door x y z] [lever x y z]     env: PORT, RCON_PORT, RCON_PASSWORD (env.js)
//
// The defaults are rustport.yml's: the door at 102.5 75 100.5, the lever at 58.5 88 182.5 and the
// drain at 6.5 65 200.5. Server config: the defaults; a short match.wait-seconds keeps it quick. The
// bot is made op, gets resistance (the map's soldiers would shoot it), and is deopped when done.
// Prints PASS / FAIL per check; the exit code is the number of failures (2+ when it broke).
const Vec3 = require('vec3')
const c = require('./common')

const args = process.argv.slice(2).map(Number)
const [dx, dy, dz] = args.length >= 3 ? args.slice(0, 3) : [102.5, 75, 100.5]
const [sx, sy, sz] = args.length >= 6 ? args.slice(3, 6) : [58.5, 88, 182.5]
const DRAIN = '6.5 65 200.5'

async function main () {
  const name = 'Key' + c.tag
  const bot = await c.make(name)
  await c.rcon(`op ${name}`)
  bot.chat('/cv queue rustport NORMAL SOLO')
  const landed = await c.waitFor(() => c.inRaid(bot), 150000)
  c.check('the bot lands on 锈港', landed, bot.worldName)
  if (!landed) return c.finish(2)
  await c.rcon(`effect give ${name} minecraft:resistance 600 4 true`)
  await c.sleep(3500)

  // locked, key
  const door = new Vec3(Math.floor(dx), Math.floor(dy), Math.floor(dz))
  await c.tp(bot, `${door.x + 0.5} ${door.y} ${door.z + 1.5} 180 0`)
  await c.sleep(800)
  const isOpen = () => { const b = bot.blockAt(door); return !!b && b.getProperties().open === true }
  let mark = bot.msgs.length
  const block = bot.blockAt(door)
  c.check('locked: the door is an iron door', !!block && block.name === 'iron_door', block && block.name)
  if (block) await bot.activateBlock(block)
  const refused = await c.heard(bot, /门被锁住了，需要/, mark, 3000)
  c.check('locked: without its key the door stays shut and says which key', !!refused && !isOpen(), refused)
  await c.rcon(`cv give ${name} key_hq`)
  await c.waitFor(() => c.carried(bot, 'key_hq') > 0, 3000)
  mark = bot.msgs.length
  if (block) await bot.activateBlock(bot.blockAt(door))
  const unlocked = await c.heard(bot, /已解锁/, mark, 3000)
  c.check('key: with the key card it opens, one use spent', !!unlocked && unlocked.includes('剩余 1 次') && await c.waitFor(isOpen, 2000), unlocked)

  // shut: the drain before its lever is pulled. Standing in it well past its 8 s must not take the
  // bot out (the control for the drain check below); the action bar says the lever comes first.
  let before = bot.worldChanges
  mark = bot.msgs.length
  await c.tp(bot, DRAIN)
  const shut = await c.heard(bot, /需要先开启/, mark, 5000)
  await c.sleep(12000)
  c.check('shut: before the lever, standing in the drain extract does not take the bot out', bot.worldChanges === before && c.inRaid(bot), bot.worldName)
  c.check('shut: ... and the action bar says its lever comes first', !!shut, shut)

  // lever
  const lever = new Vec3(Math.floor(sx), Math.floor(sy), Math.floor(sz))
  await c.tp(bot, `${lever.x + 0.5} ${lever.y} ${lever.z + 1.5} 180 30`)
  await c.sleep(800)
  const lb = bot.blockAt(lever)
  c.check('lever: the switch is a lever', !!lb && lb.name === 'lever', lb && lb.name)
  if (lb) await bot.activateBlock(lb)
  // Raid-wide news goes to the HUD's alert banner, which `cv hud` prints for bots.
  let hud = ''
  const pulled = await c.waitFor(async () => { hud = await c.rcon(`cv hud ${name}`); return hud.includes('已被拉下') }, 3000, 300)
  c.check('lever: pulling it is announced', pulled, hud.slice(0, 200))
  mark = bot.msgs.length
  if (lb) await bot.activateBlock(bot.blockAt(lever))
  c.check('lever: a second pull says it is already on', !!await c.heard(bot, /已经开启/, mark, 3000))

  // drain
  before = bot.worldChanges
  await c.tp(bot, DRAIN)
  const out = await c.waitFor(() => bot.worldChanges > before && c.inLobby(bot), 90000)
  c.check('drain: standing in the drain extract takes the bot out when the countdown ends', out, bot.worldName)
  await c.rcon(`deop ${name}`)
  return c.finish()
}

main().catch(c.crashed)
