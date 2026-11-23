// Stacks of the same item merge whatever their size (issue #43). Lore on a stackable item used to
// carry the weight and value of the whole stack, so two stacks of different sizes were different
// items to the client's click merge and to CarryService#give, and a stack shrunk by a reload kept
// its old figures. One headless bot with a fresh random name (Stk#####):
//
//   1. lobby: /cv give 23 and then 37 rounds of 5.56 M855 leaves one cell of 60; 5 more go to a new
//      cell whose lore is the same as the full one, and shows one round's weight and price;
//   2. lobby: a 5-round stack on the cursor clicked onto a fresh 23-round cell merges into 28 (the
//      server merges only identical stacks; the old build swapped them);
//   3. proving-ground raid: the bot fires an M4C and reloads it; the ammo cell shrinks, its lore stays
//      as it was, and 5 more rounds given afterwards top that cell up instead of taking a new one.
//      Then it extracts through the permanent extract.
//
//   PORT=25565 RCON_PORT=25575 node stacking.js     env: RCON_PASSWORD (craftov-local), EXTRACT
//
// Needs a local test server with RCON on and the whitelist off (or the bot added). For a short run,
// in plugins/Craftov/config.yml: match.wait-seconds 3, cinematic.enabled false, and no soldiers to
// shoot the bot (ai.count-multiplier 0, ai.operator-chance [0, 0, 0], events.per-raid 0). The bot is
// made op for the test map and deopped when done. Prints PASS / FAIL per check; exit code 0 when
// every check passed, 1 otherwise.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const EXTRACT = process.env.EXTRACT || '106.5 65 8.5' // the proving ground's permanent extraction
const AMMO = 'ammo_556_m855'
const name = 'Stk' + Math.floor(10000 + Math.random() * 90000)
const t0 = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))

const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

// ------------------------------------------------------------------ items

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
/** The raw lore component, colours included: what the server compares when it merges stacks. */
const rawLore = item => JSON.stringify(comp(item, 'lore') || null)

// ------------------------------------------------------------------ bot

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
bot.on('error', e => log('ERROR', e.message))
let worldName = ''
const track = p => { if (p.worldState && p.worldState.name) worldName = p.worldState.name.replace(/^minecraft:/, '') }
bot._client.on('login', track)
bot._client.on('respawn', track)

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(250)
  }
  return false
}
const inRaid = () => worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = () => worldName === 'craftov_lobby'

/** Inventory window slots (9..44) holding the ammo, with their counts and lore. */
function ammoCells () {
  const out = []
  for (let i = 9; i <= 44; i++) {
    const s = bot.inventory.slots[i]
    if (cid(s) === AMMO) out.push({ slot: i, count: s.count, lore: lore(s), raw: rawLore(s) })
  }
  return out
}
const show = cells => cells.map(c => `${c.slot}:${c.count}`).join(' ') || '(none)'

/** What the server holds: rounds of the ammo in the whole inventory and how many hotbar cells hold some. */
async function serverAmmo () {
  const test = 'paper[custom_data~{PublicBukkitValues:{"craftov:id":"' + AMMO + '"}}]'
  const count = s => { const m = s.match(/count: (\d+)/i); return m ? Number(m[1]) : 0 }
  const total = count(await rcon(`execute if items entity ${name} container.* ${test}`))
  let cells = 0
  for (let i = 0; i < 9; i++) if (/passed/i.test(await rcon(`execute if items entity ${name} hotbar.${i} ${test}`))) cells++
  return { total, cells }
}

async function give (amount) {
  await rcon(`cv give ${name} ${AMMO} ${amount}`)
  await sleep(700)
}

async function clearAll () {
  await rcon(`clear ${name}`)
  await sleep(1200)
}

// ------------------------------------------------------------------ scenario

async function lobbyPart () {
  await clearAll()
  await give(23)
  await give(37)
  let cells = ammoCells()
  let server = await serverAmmo()
  check('23 + 37 rounds given make one cell of 60', cells.length === 1 && cells[0].count === 60 && server.total === 60 && server.cells === 1,
    `${show(cells)} server ${JSON.stringify(server)}`)
  const full = cells[0]
  log('lore of the 60-round stack', full ? full.lore : null)

  await give(5)
  cells = ammoCells()
  const five = cells.find(c => c.count === 5)
  check('5 more rounds go to a new cell (the 60 is full)', cells.length === 2 && !!five, show(cells))
  check('and its lore is the same as the full stack\'s', !!five && !!full && five.raw === full.raw,
    five && full ? `${five.lore.slice(-2).join(' / ')} vs ${full.lore.slice(-2).join(' / ')}` : '')
  const text = full ? full.lore.join(' | ') : ''
  check('the lore gives one round\'s weight and price', /重量 0\.012kg\/发/.test(text) && /单价 ◈ 32\/发/.test(text), text)
  check('and no 0.0kg anywhere', !text.includes('0.0kg'), text)

  // A partial stack on the cursor clicked onto another partial stack of a different size.
  if (five) {
    await bot.clickWindow(five.slot, 0, 0) // the 5 onto the cursor
    await sleep(500)
    await give(23) // the 60 is full and the 5 has left: a fresh cell
    const fresh = ammoCells().find(c => c.count === 23)
    if (fresh) {
      await bot.clickWindow(fresh.slot, 0, 0)
      await sleep(800)
    }
    cells = ammoCells()
    server = await serverAmmo()
    const cursor = bot.inventory.selectedItem
    check('a 5-round stack clicked onto a 23-round one merges into 28', !!fresh && cells.some(c => c.slot === fresh.slot && c.count === 28) &&
      !cursor && server.total === 88 && server.cells === 2, `${show(cells)} cursor ${cursor ? cursor.count : 0} server ${JSON.stringify(server)}`)
  }
}

async function raidPart () {
  await clearAll()
  bot.chat('/cv queue proving NORMAL SOLO')
  if (!await waitFor(inRaid, 150000)) throw new Error('the bot never reached the raid')
  await sleep(3500) // a freshly landed player is protected for three seconds
  await clearAll()
  await rcon(`cv give ${name} m4c`)
  await give(37)
  await give(23)
  let cells = ammoCells()
  check('in the raid too, 37 + 23 rounds make one cell of 60', cells.length === 1 && cells[0].count === 60, show(cells))
  const before = cells[0]
  const gun = bot.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && cid(s) === 'm4c')
  if (gun < 0 || !before) throw new Error('no m4c or no ammo in the hotbar')
  bot.setQuickBarSlot(gun - 36)
  await sleep(600)
  for (let i = 0; i < 3; i++) { bot.swingArm(); await sleep(350) }
  await sleep(600)
  bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }) // Q: reload
  await waitFor(() => { const c = ammoCells(); return c.length === 1 && c[0].count < 60 }, 8000)
  await sleep(500)
  cells = ammoCells()
  const after = cells[0]
  check('the reload took rounds from the cell', !!after && after.count < 60, show(cells))
  check('and left its lore as it was', !!after && after.raw === before.raw,
    after ? `${after.lore.slice(-2).join(' / ')} vs ${before.lore.slice(-2).join(' / ')}` : '')
  const left = after ? after.count : 0
  await give(5)
  cells = ammoCells()
  const topped = cells.find(c => c.slot === before.slot)
  check('5 rounds given after the reload top up the used cell first', !!topped && topped.count === Math.min(60, left + 5) &&
    cells.reduce((n, c) => n + c.count, 0) === left + 5 && cells.length === (left + 5 > 60 ? 2 : 1), show(cells))

  await rcon(`execute as ${name} at @s run tp @s ${EXTRACT}`)
  check('extracted back to the lobby', await waitFor(inLobby, 30000), worldName)
}

bot.once('spawn', async () => {
  try {
    await sleep(3000)
    await rcon('op ' + name)
    await lobbyPart()
    await raidPart()
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  await rcon('deop ' + name).catch(() => {})
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  bot.quit()
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})
setTimeout(() => { log('timeout'); process.exit(1) }, 300000)
