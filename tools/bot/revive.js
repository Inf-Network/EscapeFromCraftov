// Knock-down / revive / second down / death test with two bots in a trio raid. The patient holds a
// P18: once revived it must take the pistol's draw time again (6 ticks) before it can fire.
//
//   PORT=25565 RCON_PORT=25575 SERVER_LOG=../../server/run/logs/latest.log node revive.js
//
// The draw check needs `debug: true` in the server's config.yml; the rest is logged to /tmp/revive.log.
// Exit code: number of failed checks.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')
const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASSWORD || 'craftov-local' }
const LOG = process.env.SERVER_LOG || path.join(__dirname, '../../server/run/logs/latest.log')
const rcon = cmd => rconRaw(cmd, RCON)
const out = '/tmp/revive.log'
fs.writeFileSync(out, '')
const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => fs.appendFileSync(out, `[${((Date.now() - t0) / 1000).toFixed(1)}s] ` + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')
const failures = []
function check (label, ok, detail = '') {
  if (!ok) failures.push(label)
  const line = `${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' :: ' + detail : ''}`
  log(line)
  console.log(line)
}
const logSize = () => fs.statSync(LOG).size
function logSince (from) {
  const fd = fs.openSync(LOG, 'r')
  try {
    const buf = Buffer.alloc(Math.max(0, fs.fstatSync(fd).size - from))
    fs.readSync(fd, buf, 0, buf.length, from)
    return buf.toString('utf8').split('\n')
  } finally { fs.closeSync(fd) }
}
/** Every title a bot was shown: { name, at, text }. */
const titles = []
const sawTitle = (name, text, since = 0) => titles.some(t => t.name === name && t.at >= since && t.text.includes(text))
const plain = (j) => JSON.stringify(j).match(/"text":\{"type":"string","value":"([^"]*)"/g)?.map(x => x.split('"value":"')[1].slice(0, -1)).join('') || ''

function make (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot._client.on('add_resource_pack', (p) => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) log(name, 'CHAT', m) })
  bot.on('title', (t, type) => { const text = typeof t === 'string' ? t : plain(t); titles.push({ name, at: Date.now(), text }); log(name, 'TITLE', type, text) })
  bot.on('actionBar', m => { const s = m.toString(); if (s.trim()) log(name, 'BAR', s) })
  return new Promise(resolve => bot.once('spawn', () => resolve(bot)))
}

(async () => {
  try {
    const tag = String(Date.now() % 10000)
    const A = 'Med' + tag, B = 'Pat' + tag
    const a = await make(A)
    const b = await make(B)
    await sleep(2500)
    a.chat('/cv party invite ' + B); await sleep(700)
    b.chat('/cv party accept'); await sleep(700)
    a.chat('/cv queue rustport NORMAL TRIO')
    // In the raid once both are past the entry flight: matching and the flight in can take a while.
    for (let i = 0; i < 240 && !(sawTitle(A, '行动开始') && sawTitle(B, '行动开始')); i++) await sleep(500)
    await sleep(1000)
    log('inserted', a.entity.position.toString(), b.entity.position.toString())
    check('the raid started for both', sawTitle(A, '行动开始') && sawTitle(B, '行动开始'))
    await rcon('cv give ' + B + ' p18')
    await sleep(1000)
    const pistol = b.inventory.slots.findIndex((s, i) => i >= 36 && i <= 44 && s && JSON.stringify(s.components || '').includes('craftov:p18'))
    if (pistol >= 0) b.setQuickBarSlot(pistol - 36)
    await sleep(3500)
    const downAt = Date.now()
    await rcon('damage ' + B + ' 500 minecraft:generic')
    await sleep(1000)
    check('the patient goes down', sawTitle(B, '第一次倒地', downAt))
    await rcon('execute as ' + A + ' at ' + B + ' run tp @s ~0.8 ~ ~')
    await sleep(800)
    const beforeRevive = logSize()
    const target = a.nearestEntity(e => e.type === 'player')
    log('target', target ? target.username : 'none', target ? target.position.distanceTo(a.entity.position).toFixed(2) : '')
    for (let i = 0; i < 45 && target; i++) { a.activateEntity(target); await sleep(150) }
    await sleep(800)
    // Revived, the pistol comes up again: one new draw of 6 ticks, and it is up on its ready tick.
    const lines = logSince(beforeRevive)
    const draws = lines.map(l => l.match(new RegExp(`\\[debug\\] ${B} draws p18 tick=(\\d+) ready=(\\d+)`))).filter(Boolean)
    const ups = lines.map(l => l.match(new RegExp(`\\[debug\\] ${B} up p18 tick=(\\d+)`))).filter(Boolean)
    check('the medic revives the patient', sawTitle(B, '已被救起', downAt))
    check('revive: the pistol is drawn again once the patient is up', pistol >= 0 && draws.length === 1 &&
      Number(draws[0][2]) - Number(draws[0][1]) === 6 && ups.length === 1 && ups[0][1] === draws[0][2],
      pistol < 0 ? 'no P18 on the hotbar' : `${draws.length} draws (${draws.map(m => m[1] + '->' + m[2]).join(', ')}), up at ${ups.map(m => m[1]).join(',')}`)
    await rcon('damage ' + B + ' 500 minecraft:generic')
    await sleep(1500)
    log('second down issued; patient mode', b.game.gameMode)
    await rcon('damage ' + B + ' 500 minecraft:player_attack by ' + A)
    await sleep(800)
    await rcon('execute as ' + B + ' at @s run summon minecraft:tnt ~ ~ ~ {fuse:1}')
    await sleep(6000)
    log('patient mode at end', b.game.gameMode)
    a.quit(); b.quit()
  } catch (e) { log('ERROR', e.stack); failures.push('error') }
  setTimeout(() => process.exit(failures.length), 500)
})()
