// The new player's first steps (issue #7), checked with fresh random names:
//
//   deploy     the deploy menu lists no proving ground and has 锈港 selected; `/cv queue proving`
//              is refused (the server runs without test mode).
//   objective  the HUD objective (`cv hud`) says to open the terminal; once the terminal is open it
//              points at the range rack with a distance and the compass; the terminal's guide
//              button (slot 46) reads 1/5 and the range button shines as the next step.
//   dialog     a new name gets the briefing (show_dialog) a few seconds after joining; the bot clicks
//              its "跳过指引" button (custom_click_action with the button's own id and payload), is
//              told so in chat, has no objective line, and gets no briefing when it joins again.
//              `/cv guide start` turns the objective back on.
//   skip       the bot opens the terminal, skips the range and the gunsmith, and deploys straight to
//              锈港: in the raid the objective is the extraction, no action bar names a lobby step
//              during a minute in the raid, and after it extracts the profile has tutorialDone and the
//              money went up by tutorial.reward.
//
//   PORT=25565 RCON_PORT=25575 node onboarding.js [deploy objective dialog skip]
//
// The server runs without test.enabled (the proving ground must be hidden) and with a short
// match.wait-seconds. Exit code: failed checks.
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || process.env.MC_PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const RUN = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const PLAYERS = path.join(RUN, 'plugins/Craftov/data/players')
const t0 = Date.now()
const tag = String(Math.floor(Math.random() * 9000) + 1000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (who, ...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${who}`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = c => rconRaw(c, RCON).then(r => r.replace(/§./g, ''))
const wanted = process.argv.slice(2)
const scenarios = wanted.length ? wanted : ['deploy', 'objective', 'dialog', 'skip']
const failures = []
function check (name, ok, detail) {
  if (!ok) failures.push(name)
  log(ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : detail)
}

function flat (n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (Array.isArray(n)) return n.map(flat).join('')
  if (n.type === 'string') return n.value
  if (n.type === 'list') return flat(n.value && n.value.value)
  if (n.type === 'compound') return flat(n.value)
  return flat(n.text) + flat(n.extra) + flat(n.keybind)
}

async function waitFor (pred, ms, every = 200) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await pred()) return true; await sleep(every) }
  return false
}

function profile (name) {
  if (!fs.existsSync(PLAYERS)) return null
  for (const file of fs.readdirSync(PLAYERS)) {
    if (!file.endsWith('.json')) continue
    try {
      const p = JSON.parse(fs.readFileSync(path.join(PLAYERS, file), 'utf8'))
      if (p.name === name) return p
    } catch (e) { /* being written */ }
  }
  return null
}

async function money (name) {
  const m = (await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}

const hud = async name => rcon(`cv hud ${name}`)
const objectiveOf = async name => ((await hud(name)).match(/objective=([^|]*)/) || [, ''])[1].trim()

/** A bot that records chat, action bars, dialogs, screens and world changes. */
function join (prefix, name) {
  name = name || prefix + tag
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  Object.assign(bot, { chatLines: [], bars: [], dialogs: [], screens: [], worldChanges: 0 })
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLines.push(m); log(name, 'CHAT', m) } })
  bot._client.on('action_bar', p => { const s = flat(p.text); if (s.trim()) { bot.bars.push({ at: Date.now(), text: s }); log(name, 'BAR', s) } })
  bot._client.on('show_dialog', p => { bot.dialogs.push({ at: Date.now(), packet: p }); log(name, 'DIALOG', JSON.stringify(p).slice(0, 160)) })
  bot._client.on('respawn', () => { bot.worldChanges++ })
  bot._client.on('open_window', p => bot.screens.push(JSON.stringify(p.windowTitle || p.title)))
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise(resolve => bot.once('spawn', async () => { await sleep(3000); resolve(bot) }))
}

function nextWindow (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, ms)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}

function json (item) {
  return item ? JSON.stringify(item.components || item.nbt || {}) : ''
}

async function deploy () {
  const bot = await join('ObD')
  const w = await nextWindow(bot, () => bot.chat('/cv deploy'))
  check('the deploy menu opens', !!w)
  if (w) {
    const maps = []
    for (let i = 10; i <= 16; i++) if (w.slots[i]) maps.push(json(w.slots[i]))
    check('no proving ground among the maps', !maps.some(m => m.includes('试验场')), maps.length + ' maps')
    const selected = maps.find(m => m.includes('enchantment_glint_override') || m.includes('已选择'))
    check('锈港 is selected', !!selected && selected.includes('锈港'), (selected || '').slice(0, 160))
    bot.closeWindow(w)
  }
  const from = bot.chatLines.length
  bot.chat('/cv queue proving NORMAL SOLO')
  await sleep(1500)
  check('queueing for the proving ground is refused', bot.chatLines.slice(from).some(l => l.includes('测试模式')),
    bot.chatLines.slice(from))
  bot.chat('/cv leave')
  bot.quit()
}

async function objective () {
  const bot = await join('ObO')
  let o = await objectiveOf(bot.username)
  check('the first objective is the terminal', o.includes('战术终端'), o)
  const w = await nextWindow(bot, () => bot.chat('/cv'))
  check('the terminal opens', !!w)
  await sleep(600)
  o = await objectiveOf(bot.username)
  check('then the range rack, with a distance and the compass', o.includes('靶场武器架') && /\d+m/.test(o) && o.includes('(compass)'), o)
  if (w) {
    const guide = json(w.slots[46])
    check('the guide button reads 1/5', guide.includes('新手指引 1/5'), guide.slice(0, 200))
    const range = json(w.slots[37])
    check('the range button shines as the next step', range.includes('enchantment_glint_override') && range.includes('新手指引的下一步'),
      range.slice(0, 200))
    bot.closeWindow(w)
  }
  bot.quit()
}

/** Every {label, action} button in a dialog's NBT (prismarine-nbt simplified), wherever it sits. */
function buttons (node, out = []) {
  if (!node || typeof node !== 'object') return out
  if (node.label !== undefined && node.action !== undefined) out.push(node)
  for (const value of Object.values(node)) buttons(value, out)
  return out
}

function varint (n) {
  const out = []
  do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b) } while (n)
  return Buffer.from(out)
}

/**
 * The serverbound custom_click_action packet (0x41 in 1.21.11) answering a Paper callback button:
 * the action's id, then its payload, {id: <the callback's UUID as an int array>}, as a
 * length-prefixed network tag. Written raw: minecraft-data leaves out that length prefix.
 */
function customClick (id, uuidInts) {
  const name = Buffer.from(id, 'utf8')
  const ints = Buffer.alloc(4 * uuidInts.length)
  uuidInts.forEach((v, i) => ints.writeInt32BE(v, 4 * i))
  const count = Buffer.alloc(4)
  count.writeInt32BE(uuidInts.length)
  const tag = Buffer.concat([Buffer.from([0x0a, 0x0b, 0x00, 0x02]), Buffer.from('id'), count, ints, Buffer.from([0x00])])
  return Buffer.concat([varint(0x41), varint(name.length), name, varint(tag.length), tag])
}

function simple (nbt) {
  try { return require('prismarine-nbt').simplify(nbt) } catch (e) { return nbt }
}

async function dialog () {
  let bot = await join('ObB')
  const name = bot.username
  await waitFor(() => bot.dialogs.length > 0, 8000)
  check('a new player gets the briefing', bot.dialogs.length > 0)
  if (!bot.dialogs.length) { bot.quit(); return }
  const packet = bot.dialogs[0].packet
  const data = simple(packet.dialog && (packet.dialog.data || packet.dialog))
  const all = buttons(data)
  const skipButton = all.find(b => JSON.stringify(b.label).includes('跳过指引'))
  check('the briefing has a skip button', !!skipButton, all.map(b => JSON.stringify(b.label).slice(0, 60)))
  if (!skipButton) { bot.quit(); return }
  // Paper's callback buttons are "dynamic/custom" actions: the client sends their id with the
  // "additions" compound (the callback's UUID as an int array) as the payload.
  const action = skipButton.action
  log(name, 'clicking', JSON.stringify(action))
  const from = bot.chatLines.length
  bot._client.writeRaw(customClick(action.id, (action.additions || {}).id || []))
  const told = await waitFor(() => bot.chatLines.slice(from).some(l => l.includes('已跳过新手指引')), 5000)
  check('skipping is answered in chat', told, bot.chatLines.slice(from))
  await sleep(800)
  check('no objective line after skipping', (await objectiveOf(name)) === '')
  bot.quit()
  await sleep(2500)
  bot = await join('ObB', name)
  await sleep(6000)
  check('the briefing does not open again', bot.dialogs.length === 0, bot.dialogs.length)
  const p = profile(name) || {}
  check('the profile says briefed and muted', p.briefed === true && p.guideMuted === true, { briefed: p.briefed, muted: p.guideMuted })
  bot.chat('/cv guide start')
  await sleep(1200)
  const o = await objectiveOf(name)
  check('/cv guide start brings the objective back', o.includes('目标'), o)
  bot.quit()
}

async function skip () {
  const bot = await join('ObS')
  const name = bot.username
  const w = await nextWindow(bot, () => bot.chat('/cv'))
  if (w) bot.closeWindow(w)
  await sleep(500)
  const before = await money(name)
  const reward = 20000
  const worlds = bot.worldChanges
  bot.chat('/cv queue rustport NORMAL SOLO')
  const landed = await waitFor(() => bot.worldChanges > worlds && bot.game.gameMode === 'adventure', 120000)
  check('the bot lands in 锈港 without the range or the gunsmith', landed)
  if (!landed) { bot.quit(); return }
  await sleep(3000)
  const o = await objectiveOf(name)
  check('in the raid the objective is the extraction, with a distance', o.includes('撤离点') && /\d+m/.test(o), o)
  // A minute in the raid: reminders come every 45 s, and none may name a lobby step.
  await rcon(`effect give ${name} minecraft:resistance 120 4 true`)
  const barsFrom = bot.bars.length
  await sleep(60000)
  const guideBars = bot.bars.slice(barsFrom).filter(b => b.text.includes('新手指引'))
  check('no action bar in the raid names a lobby step',
    !guideBars.some(b => /战术终端|靶场|改枪台|开始行动/.test(b.text)), guideBars.map(b => b.text))
  log(name, 'guide bars in the raid', guideBars.map(b => b.text))
  const world = bot.worldChanges
  await rcon(`execute as ${name} at @s run tp @s 89.5 65 3.5`)
  const home = await waitFor(() => bot.worldChanges > world, 30000)
  check('the bot extracts', home)
  await sleep(2500)
  await waitFor(() => { const p = profile(name); return p && p.tutorialDone }, 10000)
  const p = profile(name) || {}
  check('the guide is done after the first extraction', p.tutorialDone === true, { tutorialDone: p.tutorialDone, steps: p.tutorialSteps })
  const after = await money(name)
  check('the bonus was paid', after - before === reward, `${before} -> ${after}`)
  check('chat says the guide is done', bot.chatLines.some(l => l.includes('新手指引完成')))
  bot.quit()
}

;(async () => {
  const runs = { deploy, objective, dialog, skip }
  for (const s of scenarios) {
    log('scenario', s)
    try {
      await runs[s]()
    } catch (e) {
      check(s + ' ran without an exception', false, e.stack)
    }
    await sleep(1500)
  }
  log('done', failures.length ? 'FAILED: ' + failures.join('; ') : 'all passed')
  process.exit(failures.length)
})()
