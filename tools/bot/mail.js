// System mail in the stash mailbox (issue #67), with headless bots. Letters are written into the
// profiles before the bots first join, the way a server feature files them:
//   reader  - two letters: one with ◈1,234, a splint and three bandages attached, one with nothing.
//             Told of 2 unread letters on login; the stash's mailbox button counts them; the
//             letters are listed newest first; claiming the first pays the money and puts both
//             items in the stash, claiming it again pays nothing; the empty one is marked read
//             by a click and deleted by a shift-click, while an unclaimed one cannot be deleted.
//   full    - a stash with no free cell and a letter with a splint: claiming is refused with
//             nothing paid, the letter stays unclaimed.
//
//   PORT=25565 RCON_PORT=25575 SERVER_DIR=../../server/run node mail.js
//
// The server must be running. Exits 0 when every check passes, 1 otherwise.
//
// Server config (tools/bot/suite.js sets it for its run): the defaults.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const rconRaw = require('./rcon')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const env = require('./env')

const PORT = env.PORT
const RCON_PORT = env.RCON_PORT
const RCON_PASSWORD = env.RCON_PASSWORD
const SERVER_DIR = env.SERVER_DIR
const rcon = cmd => rconRaw(cmd, { port: RCON_PORT, password: RCON_PASSWORD }).then(s => s.replace(/§./g, ''))
const tag = String(Math.floor(1000 + Math.random() * 9000))
const NAMES = { reader: 'MlRead' + tag, full: 'MlFull' + tag }
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a)

const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`)
}

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const profileFile = name => path.join(SERVER_DIR, 'plugins', 'Craftov', 'data', 'players', offlineUuid(name) + '.json')
const profile = name => JSON.parse(fs.readFileSync(profileFile(name), 'utf8'))
const balance = async name => Number(((await rcon(`cv money ${name} 0`)).match(/◈([\d,]+)/) || [, 'NaN'])[1].replace(/,/g, ''))
const letter = (id, title, money, items, time) => ({ id, time, title, body: '测试邮件第一行\n第二行', money, items, claimed: false })
const text = slot => JSON.stringify(slot && slot.components || '')

const bots = {}
function make (role) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: NAMES[role], version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.chatLog = []
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(role, 'CHAT', m) } })
  // Action bars (claim results) come as their own packet.
  bot._client.on('action_bar', p => bot.chatLog.push('[bar] ' + JSON.stringify(p.text)))
  bot.on('error', e => log(role, 'ERROR', e.message))
  bots[role] = bot
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(role + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(timer); resolve(bot) })
  })
}
function openWindow (bot, fn, timeout = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', done); resolve(null) }, timeout)
    function done (w) { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', done)
    fn()
  })
}

async function reader () {
  const name = NAMES.reader
  const now = Date.now()
  fs.writeFileSync(profileFile(name), JSON.stringify({
    uuid: offlineUuid(name),
    name,
    mail: [
      letter('mailtest1', '测试补偿', 1234, [{ id: 'splint', amount: 1 }, { id: 'bandage', amount: 3 }], now - 60000),
      letter('mailtest2', '测试通知', 0, [], now)
    ]
  }))
  const bot = await make('reader')
  await sleep(5000)
  check('reader: told of 2 unread letters on login', bot.chatLog.some(m => m.includes('2 封未读邮件')))
  const stash = await openWindow(bot, () => bot.chat('/cv stash'))
  // The name is "邮箱 " with the count as a child component, which may come first (as carry.js reads it).
  const label = stash && (stash.slots[50]?.components || []).find(c => c.type === 'item_name')
  const texts = (JSON.stringify(label || '').match(/"text":\{"type":"string","value":"[^"]*"/g) || []).map(t => t.split('"value":"')[1].slice(0, -1))
  check('reader: the stash mailbox button counts the letters', texts.includes('邮箱 ') && texts.includes('2'), texts.join('|'))
  if (stash) bot.closeWindow(stash)
  await sleep(500)

  const money = await balance(name)
  let w = await openWindow(bot, () => bot.chat('/cv mail'))
  if (!w) return check('reader: the mailbox opens', false)
  check('reader: newest letter first', text(w.slots[0]).includes('测试通知') && text(w.slots[1]).includes('测试补偿'))
  await bot.clickWindow(1, 0, 1) // a shift-click on an unclaimed letter claims it, never deletes it
  await sleep(800)
  w = bot.currentWindow || w
  check('reader: claiming pays the money', await balance(name) === money + 1234, `${money} -> ${await balance(name)}`)
  check('reader: an unclaimed letter is not deleted by shift-click', text(w.slots[1]).includes('测试补偿'))
  await bot.clickWindow(1, 0, 0)
  await sleep(800)
  check('reader: claiming again pays nothing', await balance(name) === money + 1234)
  await bot.clickWindow(0, 0, 0) // the letter with nothing attached: marked read
  await sleep(800)
  w = bot.currentWindow || w
  check('reader: both letters read', !text(w.slots[0]).includes('点击') || text(w.slots[0]).includes('Shift'), text(w.slots[0]).slice(0, 200))
  await bot.clickWindow(0, 0, 1) // shift-click a read letter: deleted
  await sleep(800)
  w = bot.currentWindow || w
  check('reader: a read letter is deleted by shift-click', text(w.slots[0]).includes('测试补偿') && !text(w.slots[1]).includes('测试'),
    text(w.slots[0]).slice(0, 120))
  bot.closeWindow(w)
  await sleep(500)
  bot.quit()
  await sleep(2500) // the quit save
  const p = profile(name)
  const ids = (p.stash || []).map(r => r.id)
  check('reader: the attached items are in the stash', ids.includes('splint') && (p.stash || []).some(r => r.id === 'bandage' && r.amount === 3), ids.join(','))
  check('reader: on disk one letter left, claimed', (p.mail || []).length === 1 && p.mail[0].id === 'mailtest1' && p.mail[0].claimed,
    JSON.stringify(p.mail).slice(0, 200))
}

async function full () {
  const name = NAMES.full
  fs.writeFileSync(profileFile(name), JSON.stringify({
    uuid: offlineUuid(name),
    name,
    receivedStarter: true, // no starter kit: the stash holds exactly the one bandage
    stashCapacity: 1,
    stash: [{ id: 'bandage', amount: 1 }],
    mail: [letter('mailtest3', '测试物品', 500, [{ id: 'splint', amount: 1 }], Date.now())]
  }))
  const bot = await make('full')
  await sleep(4000)
  const money = await balance(name)
  const w = await openWindow(bot, () => bot.chat('/cv mail'))
  if (!w) return check('full: the mailbox opens', false)
  await bot.clickWindow(0, 0, 0)
  await sleep(800)
  check('full: refused for want of stash space', bot.chatLog.some(m => m.includes('仓库空间不足')))
  check('full: nothing paid', await balance(name) === money)
  bot.closeWindow(w)
  await sleep(300)
  bot.quit()
  await sleep(2500)
  const p = profile(name)
  check('full: the letter stays unclaimed, the stash as it was', p.mail.length === 1 && !p.mail[0].claimed && p.stash.length === 1,
    JSON.stringify(p.mail).slice(0, 160))
}

;(async () => {
  try {
    await reader()
    await full()
  } catch (e) {
    check('scenario ran without errors', false, e.stack)
  }
  for (const bot of Object.values(bots)) { try { bot.quit() } catch { } }
  log(failures.length ? `FAILED ${failures.length}: ${failures.join('; ')}` : 'ALL PASSED')
  setTimeout(() => process.exit(failures.length ? 1 : 0), 500)
})()
setTimeout(() => { log('timeout'); process.exit(1) }, 180000)
