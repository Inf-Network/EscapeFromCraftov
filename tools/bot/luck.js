// Personal loot luck (issue #80), end to end with headless bots on 锈港 (rustport) and the proving ground:
//
//   pity          G has gold pity 29 (hard 30), J has gone 1,000 raids without the first jackpot of loot.yml;
//                 each opens a crate in the same raid. G gets a gold item (handed out, or one already in the
//                 crate) and its gold count goes to 0; J is handed the jackpot and its count goes to 0. J dies
//                 without it: half its value comes back into the quota and the short raid leaves the count at 0.
//   quota         Q (luck.qualify.min-seconds 30 for the run) searches five containers, stays 40 s and
//                 extracts: the raid joins the history, every jackpot count goes to 1 and the quota earns
//                 (minutes x per-minute + searches x per-search) x the behaviour multiplier `/cv luck` shows.
//   compensation  A loses its starter kit in a 常规 raid, more than luck.compensation.threshold.NORMAL
//                 (20,000 for the run); B goes in with nothing, so it loses nothing. `/cv luck` then shows
//                 BOOST raids of compensation for A and none for B. In each raid (A and B always share it,
//                 no soldiers) A searches the even-numbered containers of the map and B the odd ones, every
//                 item revealed. The share of gold and red items among what A finds in its compensated raids
//                 is clearly above B's in the same raids and above everything rolled without the boost
//                 (one-sided two-proportion z-test, p < 0.01), and all of the difference is A's own
//                 hand-outs (server log, boost-GOLD / boost-RED): without them A's share does not differ
//                 from the unboosted rolls (two-sided, p > 0.01), so the containers themselves roll as for
//                 anyone. B's share in those raids does not differ from the first, unboosted raid, B is
//                 handed nothing, A's compensation counts down to 0 and B's never starts. Pity and quota
//                 hand-outs are switched off for this one, so only the boost differs, and the multiplier is
//                 3 for gold and red (luck.py checks the shipped ones exactly).
//   handover      H and M deploy as a squad; H leaves its K47 and P18 in a crate, M takes them and both
//                 extract. Once the raid closes the guns are credited back to H (server log) and neither
//                 has lost anything (0% of the threshold, no compensation), where H used to book them as a
//                 loss big enough to set off its compensation.
//   enemy         X and Y deploy solo into the same raid and do the same: Y's extraction notes the guns as
//                 another player's gear, and Y's luck books them as a gain (minus their value / threshold),
//                 while X's loss sets off its compensation.
//   insured       I deploys with auto insurance (loss-chance and return-minutes 0 for the run) and goes
//                 missing (`/cv end`); once the raid closes the insured gear coming back is taken off I's
//                 loss (server log), and what /cv luck shows follows from the settled net.
//   void          V (luck.quota.chance 1, quota 1,000,000) is handed a quota item, leaves it and goes to the
//                 lobby mid-raid (evacuated: the raid is voided for it): once the raid closes the quota is
//                 1,000,000 again. S (the same) and J (1,000 raids on every jackpot) leave their hand-outs
//                 and the server is stopped: after the restart S's quota is 1,000,000 and J's jackpot counts
//                 are 1,000 again. C (the same as S) leaves a hand-out and the server is killed (SIGKILL,
//                 Linux): when C logs in after the restart its quota is 1,000,000 again. This one restarts
//                 the server with SERVER_DIR/start.sh (detached, output appended to console.log).
//   refund        R starts with a quota of 1,000,000 and luck.quota.chance 1: the first container that can
//                 hold gold or red hands out an item (server log). Left in the container, with R dying
//                 (`/cv end`), half its value comes back into the quota once the raid closes. K does the
//                 same but takes the hand-out and extracts at 北门检查站: nothing comes back.
//   proving       T, with gold pity 29 (hard 30) and a quota of 500,000, goes onto the private range from the
//                 lobby rack and back, then opens every regular container of the proving ground (a test
//                 map) and dies there: nothing is handed out, and pity, quota, history and the loss window
//                 are as before after each.
//
//   node luck.js [all|pity|quota|compensation|refund|proving|handover|enemy|insured|void]
//   env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local SERVER_DIR=../../server/run BOOST=4
//
// SERVER_DIR is read for maps/rustport.yml, maps/proving.yml, items.yml and loot.yml (with python3 and its
// yaml module) and logs/latest.log, and its plugins/Craftov/config.yml is edited for the run (restored at
// the end, then /cv reload): match.wait-seconds 3, cinematic.enabled false, ai.count-multiplier 0,
// ai.operator-chance [0, 0, 0], events.per-raid 0, every search.seconds 0.05,
// luck.compensation.threshold.NORMAL 20000 and raids BOOST, plus per scenario the luck numbers named above.
// The server needs that config.yml to be the full one the plugin writes. Bots use fresh random names and
// are made op (the proving ground is test-only). Exits 0 when every check passed.
//
// Server config (tools/bot/suite.js sets it for its run): the full config.yml the plugin writes;
// the script sets everything else it needs itself (see above) and puts it back. python3 with its
// yaml module.
// Ports and folders come from env.js: PORT, RCON_PORT, RCON_PASSWORD, SERVER_DIR.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const rconRaw = require('./rcon')
const env = require('./env')

const PORT = env.PORT
const RCON = env.RCON
const SERVER_DIR = env.SERVER_DIR
const DATA = path.join(SERVER_DIR, 'plugins/Craftov')
const CONFIG = path.join(DATA, 'config.yml')
const LOG = path.join(SERVER_DIR, 'logs/latest.log')
const BOOST = Number(process.env.BOOST || 4)
const scenario = process.argv[2] || 'all'
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

// ------------------------------------------------------------------ data files

/** A YAML file as JSON, read by Python's yaml module (tools/econ needs it anyway; node has no YAML parser here). */
function yamlFile (file) {
  const code = 'import json, sys, yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1], encoding="utf-8"))))'
  return JSON.parse(execFileSync('python3', ['-c', code, file], { encoding: 'utf8' }))
}

/** Containers of a map file: [{type, x, y, z}] in file order. */
const containers = map => yamlFile(path.join(DATA, 'maps', map + '.yml')).containers
const ITEMS = yamlFile(path.join(DATA, 'items.yml')).items
const RARITY = Object.fromEntries(Object.entries(ITEMS).map(([id, s]) => [id, String(s.rarity || 'WHITE').toUpperCase()]))
const value = id => (ITEMS[id] ? ITEMS[id].value : NaN)

let logFrom = fs.existsSync(LOG) ? fs.statSync(LOG).size : 0
/** Server log lines written since the last call that match re. */
function logLines (re) {
  const buf = fs.readFileSync(LOG)
  const text = buf.slice(Math.min(logFrom, buf.length)).toString('utf8')
  return text.split('\n').filter(l => re.test(l))
}
const markLog = () => { logFrom = fs.statSync(LOG).size }

// ------------------------------------------------------------------ config

const original = fs.readFileSync(CONFIG, 'utf8')
const BASE = [
  [/^( {2}wait-seconds:) *\d+/m, '$1 3'],
  [/^(cinematic:\n {2}enabled:) *true/m, '$1 false'],
  [/^( {2}count-multiplier:) *[\d.]+/m, '$1 0'],
  [/^( {2}operator-chance:) *\[.*\]/m, '$1 [0, 0, 0]'],
  [/^( {2}per-raid:) *\d+/m, '$1 0'],
  [/^( {4}(?:WHITE|GREEN|BLUE|PURPLE|GOLD|RED):) *[\d.]+$/gm, '$1 0.05'],
  [/^( {4}threshold: \{ NORMAL:) *\d+/m, '$1 20000'],
  [/^( {4}raids:) *\d+(\n {4}multiplier:)/m, `$1 ${BOOST}$2`]
]
const NO_HANDOUTS = [
  [/^( {4}GOLD: \{ soft:) *\d+, hard: *\d+/m, '$1 99998, hard: 99999'],
  [/^( {4}RED: \{ soft:) *\d+, hard: *\d+/m, '$1 99998, hard: 99999'],
  [/^( {4}soft-raids:) *\d+/m, '$1 99998'],
  [/^( {4}hard-raids:) *\d+/m, '$1 99999'],
  [/^( {4}chance:) *[\d.]+(\n {4}refund:)/m, '$1 0$2']
]
const QUOTA_FIRST = NO_HANDOUTS.slice(0, 4).concat([[/^( {4}chance:) *[\d.]+(\n {4}refund:)/m, '$1 1$2']])
// As QUOTA_FIRST, with jackpot pity as configured.
const VOID_CFG = NO_HANDOUTS.slice(0, 2).concat([[/^( {4}chance:) *[\d.]+(\n {4}refund:)/m, '$1 1$2']])

async function useConfig (extra) {
  let s = original
  for (const [re, to] of BASE.concat(extra)) {
    if (!re.test(s)) throw new Error('config.yml has no ' + re)
    re.lastIndex = 0
    s = s.replace(re, to)
  }
  // search.seconds only: the pity GOLD/RED lines start with "{", which the 0.05 pattern does not match.
  fs.writeFileSync(CONFIG, s)
  log('config', await rcon('cv reload'))
}
async function restoreConfig () {
  fs.writeFileSync(CONFIG, original)
  log('config restored', await rcon('cv reload'))
}

// ------------------------------------------------------------------ bots

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
function pbv (item, key) {
  const data = comp(item, 'custom_data')
  const v = data && data.value && data.value.PublicBukkitValues
  const k = v && v.value['craftov:' + key]
  return k ? k.value : null
}
const cid = item => pbv(item, 'id')
const hidden = item => JSON.stringify(item ? item.components || '' : '').match(/search_(hidden|active)/)

function spawnBot (prefix, name = prefix + tag) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━')) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)))
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', async () => { clearTimeout(t); await sleep(3000); await rcon('op ' + name); resolve(bot) })
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
function openWith (bot, fn, ms = 4000) {
  return new Promise(resolve => {
    const t = setTimeout(() => resolve(null), ms)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
    fn()
  })
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'

/**
 * Queues every bot for the map at once (or what queue() says each one types, null for nothing: a
 * squad's leader queues for all of it) and waits until all of them have landed in the same raid.
 */
async function deploy (bots, map, queue = () => `/cv queue ${map} NORMAL SOLO`) {
  for (const b of bots) {
    if (!await waitFor(() => inLobby(b), 60000)) throw new Error(b.username + ' is not in the lobby')
  }
  await sleep(1500)
  for (const b of bots) {
    const line = queue(b)
    if (line) b.chat(line)
  }
  const ok = await waitFor(() => bots.every(inRaid), 120000)
  await sleep(2000)
  const raids = await rcon('cv raids')
  log('raids', raids)
  return ok && (raids.match(/\bR\d+ \S+ \S+ (RUNNING|LOADING)/g) || []).length === 1
}

/** Ends every raid: whoever is still in one goes missing (dies) and is back in the lobby soon after. */
async function endRaid (bots) {
  await rcon('cv end')
  const ok = await waitFor(() => bots.every(inLobby), 90000)
  await waitFor(async () => (await rcon('cv raids')).includes('没有进行中的行动'), 60000)
  return ok
}

/**
 * Opens container c (map coordinates), waits until everything in it is revealed and returns
 * {window, items: [{id, uid, slot}]}, or null when it could not be opened. The window stays open.
 */
async function search (bot, c) {
  await rcon(`execute as ${bot.username} at @s run tp @s ${c.x} ${c.y} ${c.z}`)
  await sleep(600)
  const d = e => Math.hypot(e.position.x - c.x, e.position.y - c.y, e.position.z - c.z)
  let target = null
  await waitFor(() => {
    target = Object.values(bot.entities).filter(e => e.name === 'interaction').sort((a, b) => d(a) - d(b))[0] || null
    return target && d(target) < 0.6
  }, 3000)
  if (!target || d(target) >= 0.6) return null
  await bot.lookAt(target.position.offset(0, 0.3, 0), true)
  const w = await openWith(bot, () => bot.activateEntity(target))
  if (!w) return null
  // The search screen's container is its 6x6 block; the columns on the right are the searcher's gear (#10).
  const size = w.inventoryStart
  const box = s => s % 9 < 6
  await waitFor(() => { for (let s = 0; s < size; s++) if (box(s) && hidden(w.slots[s])) return false; return true }, 10000)
  await sleep(150)
  const items = []
  for (let s = 0; s < size; s++) {
    if (!box(s)) continue
    const id = cid(w.slots[s])
    if (id) items.push({ id, uid: pbv(w.slots[s], 'uid'), slot: s })
  }
  return { window: w, items }
}

/** /cv luck for name, parsed. */
async function luck (name) {
  const t = await rcon('cv luck ' + name)
  const num = re => { const m = t.match(re); return m ? Number(m[1].replace(/,/g, '')) : NaN }
  return {
    text: t.replace(/\n/g, ' | '),
    quota: num(/额度池 ◈([\d,]+)/),
    gold: num(/金 (\d+)（/),
    red: num(/红 (\d+)（/),
    raids: num(/最近 (\d+) 场有效对局/),
    loss: num(/窗口内亏损 (-?\d+)%/),
    boost: /补偿局 还剩 (\d+) 局/.test(t) ? num(/补偿局 还剩 (\d+) 局/) : 0
  }
}

// ------------------------------------------------------------------ statistics

const gr = items => items.filter(i => RARITY[i.id] === 'GOLD' || RARITY[i.id] === 'RED').length
/** z of p1 - p2 for k1 of n1 against k2 of n2 (pooled two-proportion test). */
function z (k1, n1, k2, n2) {
  const p = (k1 + k2) / (n1 + n2)
  const se = Math.sqrt(p * (1 - p) * (1 / n1 + 1 / n2))
  return se === 0 ? 0 : (k1 / n1 - k2 / n2) / se
}
const share = (k, n) => `${k}/${n} = ${(100 * k / Math.max(1, n)).toFixed(1)}%`

// ------------------------------------------------------------------ scenarios

async function compensation () {
  // x3 for gold and red: at the shipped x2 / x2.5 the share comes out about 18% against 11%, which
  // a few hundred items tell apart from B's share in the same raids only now and then.
  await useConfig(NO_HANDOUTS.concat([[/^( {4}multiplier: \{ GOLD:) *[\d.]+, RED: *[\d.]+/m, '$1 3.0, RED: 3.0']]))
  markLog()
  const A = await spawnBot('LkA')
  const B = await spawnBot('LkB')
  await rcon(`clear ${B.username}`)
  await sleep(1000)
  const map = containers('rustport')
  const mine = (bot, i) => (bot === A ? i % 2 === 0 : i % 2 === 1)
  const found = { A: [], B: [] } // per raid: arrays of item lists
  const handed = { A: [], B: [] } // per raid: boost hand-outs (server log)
  for (let raid = 0; raid <= BOOST; raid++) {
    markLog()
    if (!await deploy([A, B], 'rustport')) { check(`raid ${raid}: A and B land in the same raid`, false); break }
    const run = async bot => {
      const items = []
      let opened = 0
      for (let i = 0; i < map.length; i++) {
        if (!mine(bot, i) || map[i].type === 'body_bag') continue
        const r = await search(bot, map[i])
        if (!r) continue
        opened++
        items.push(...r.items)
        bot.closeWindow(r.window)
        await sleep(100)
      }
      log(`${bot.username} raid ${raid}: ${opened} containers, ${share(gr(items), items.length)} gold or red`)
      return items
    }
    const [a, b] = await Promise.all([run(A), run(B)])
    found.A.push(a)
    found.B.push(b)
    for (const [k, bot] of [['A', A], ['B', B]]) {
      handed[k].push(logLines(new RegExp(`\\[luck\\] ${bot.username} is handed \\S+ \\(◈\\d+, boost-(GOLD|RED)\\)`)).length)
    }
    log(`boost hand-outs in raid ${raid}: A ${handed.A[raid]}, B ${handed.B[raid]}`)
    if (!await endRaid([A, B])) { check(`raid ${raid}: both back in the lobby`, false); break }
    await sleep(1500)
    const la = await luck(A.username)
    const lb = await luck(B.username)
    log('A', la.text)
    log('B', lb.text)
    const want = raid === 0 ? BOOST : BOOST - raid
    check(`after raid ${raid}: A has ${want} compensated raids left`, la.boost === want, la.boost)
    check(`after raid ${raid}: B has no compensation`, lb.boost === 0, lb.boost)
  }
  if (found.A.length !== BOOST + 1) return
  const flatten = arr => arr.flat()
  const aBoost = flatten(found.A.slice(1))
  const bBoost = flatten(found.B.slice(1))
  const base0 = flatten([found.A[0], found.B[0]])
  const unboosted = base0.concat(bBoost)
  const zAB = z(gr(aBoost), aBoost.length, gr(bBoost), bBoost.length)
  const zAU = z(gr(aBoost), aBoost.length, gr(unboosted), unboosted.length)
  const zB0 = z(gr(bBoost), bBoost.length, gr(base0), base0.length)
  // A's compensated finds without its own hand-outs (every one is a gold or red item A revealed).
  const extra = handed.A.slice(1).reduce((x, y) => x + y, 0)
  const zAown = z(gr(aBoost) - extra, aBoost.length - extra, gr(unboosted), unboosted.length)
  log(`A compensated ${share(gr(aBoost), aBoost.length)} (${extra} handed out), B same raids ${share(gr(bBoost), bBoost.length)}, first raid ${share(gr(base0), base0.length)}`)
  check(`A finds clearly more gold and red than B in the same raids (z ${zAB.toFixed(2)} > 2.33)`, zAB > 2.33)
  check(`A finds clearly more gold and red than everything unboosted (z ${zAU.toFixed(2)} > 2.33)`, zAU > 2.33)
  check(`without its ${extra} hand-outs A's containers roll as everyone's (|z| ${Math.abs(zAown).toFixed(2)} < 2.58)`, extra > 0 && Math.abs(zAown) < 2.58)
  check('A is handed nothing before its compensation starts', handed.A[0] === 0, handed.A)
  check('B is handed nothing (no compensation)', handed.B.every(n => n === 0), handed.B)
  check(`B in A's compensated raids finds what the unboosted first raid had (|z| ${Math.abs(zB0).toFixed(2)} < 2.58)`, Math.abs(zB0) < 2.58)
  A.quit()
  B.quit()
  await sleep(2000)
}

/** One quota hand-out; `keep` takes it and extracts, otherwise it is left behind and the bot dies. */
async function refundRun (prefix, keep) {
  markLog()
  const R = await spawnBot(prefix)
  const name = R.username
  log(await rcon(`cv luck ${name} quota 1000000`))
  if (!await deploy([R], 'rustport')) { check(`${name}: deployed on rustport`, false); return }
  const map = containers('rustport')
  let handed = null
  let window = null
  let items = []
  for (let i = 0; i < map.length && !handed; i++) {
    if (map[i].type === 'body_bag') continue
    const r = await search(R, map[i])
    if (!r) continue
    const line = logLines(new RegExp(`\\[luck\\] ${name} is handed`))[0]
    if (line) {
      const m = line.match(/is handed (\S+) \(◈(\d+), quota, quota left ◈(-?\d+)\)/)
      handed = m ? { id: m[1], value: Number(m[2]), left: Number(m[3]), line } : { line }
      window = r.window
      items = r.items
    } else {
      R.closeWindow(r.window)
      await sleep(100)
    }
  }
  check(`${name}: a container hands out a quota item`, !!handed && !!handed.id, handed && handed.line)
  if (!handed || !handed.id) return
  check(`${name}: it costs its value (◈${handed.value} = items.yml ${value(handed.id)}) out of the quota`,
    handed.value === value(handed.id) && handed.left === 1000000 - handed.value, handed.left)
  const payout = items.filter(i => i.id === handed.id && i.uid)
  check(`${name}: the hand-out is in the container with an instance id`, payout.length >= 1, items)
  if (keep) {
    for (const p of payout) {
      await R.clickWindow(p.slot, 0, 0)
      await sleep(400)
    }
    R.closeWindow(window)
    const carried = R.inventory.slots.some(s => s && cid(s) === handed.id && pbv(s, 'uid') === payout[0].uid)
    check(`${name}: took the hand-out`, carried)
    await rcon(`execute as ${name} at @s run tp @s 89.5 65 3.5`)
    check(`${name}: extracts at 北门检查站`, await waitFor(() => inLobby(R), 40000))
  } else {
    R.closeWindow(window)
    check(`${name}: goes missing when the raid is ended`, await endRaid([R]))
  }
  await waitFor(async () => (await rcon('cv raids')).includes('没有进行中的行动'), 60000)
  await sleep(1500)
  const after = await luck(name)
  log(name, after.text)
  const refunds = logLines(new RegExp(`\\[luck\\] ${name} did not carry out`))
  if (keep) {
    check(`${name}: carried out, nothing comes back (quota ◈${after.quota} = ◈${handed.left})`, after.quota === handed.left && refunds.length === 0, refunds)
  } else {
    const back = Math.round(handed.value * 0.5)
    check(`${name}: left behind, half its value comes back (◈${handed.left} + ◈${back})`,
      after.quota === handed.left + back && refunds.length === 1 && refunds[0].includes('◈' + back), `${after.quota} ${refunds}`)
  }
  R.quit()
  await sleep(2000)
}

async function refund () {
  await useConfig(QUOTA_FIRST)
  await refundRun('LkR', false)
  await refundRun('LkK', true)
}

/**
 * G has gold pity 29 (hard 30) and J has gone 1,000 raids without the first jackpot; in one 锈港 raid each
 * opens a crate. G's crate hands out a gold item unless it already holds one, and either way G's gold
 * count is 0 afterwards; J's hands out the jackpot, whose count goes to 0. J dies without it: half its
 * value comes back into the quota, and the short raid does not count, so the jackpot count stays 0.
 */
async function pity () {
  await useConfig([])
  markLog()
  const G = await spawnBot('LkG')
  const J = await spawnBot('LkJ')
  const jackpot = Object.keys(yamlFile(path.join(DATA, 'loot.yml')).jackpots || {})[0]
  if (!jackpot) { check('pity: loot.yml has jackpots', false); return }
  await rcon(`cv luck ${G.username} pity GOLD 29`)
  await rcon(`cv luck ${J.username} pity ${jackpot} 1000`)
  if (!await deploy([G, J], 'rustport')) { check('pity: G and J land in the same raid', false); return }
  const crates = containers('rustport').filter(c => c.type === 'crate')
  const [g, j] = await Promise.all([search(G, crates[0]), search(J, crates[1])])
  if (!g || !j) { check('pity: both crates open', false); return }
  G.closeWindow(g.window)
  J.closeWindow(j.window)
  await sleep(500)
  const gHand = logLines(new RegExp(`\\[luck\\] ${G.username} is handed (\\S+) \\(◈\\d+, pity-GOLD`))
  const gNatural = g.items.filter(i => RARITY[i.id] === 'GOLD' && !(ITEMS[i.id] && ITEMS[i.id].category === 'AMMO'))
  check('pity: G at the gold hard mark gets a gold item from the crate (handed out, or already in it)',
    gHand.length === 1 || gNatural.length > 0, gHand.concat(g.items.map(i => i.id)))
  const gl = await luck(G.username)
  check('pity: G\'s gold count is 0 after finding it', gl.gold === 0, gl.text)
  const jHand = logLines(new RegExp(`\\[luck\\] ${J.username} is handed ${jackpot} \\(◈(\\d+), jackpot`))
  check(`pity: J, 1000 raids without ${jackpot}, is handed it in the first crate`, jHand.length === 1
    && j.items.some(i => i.id === jackpot && i.uid), j.items.map(i => i.id))
  const count = l => { const m = l.text.match(new RegExp(ITEMS[jackpot].name + ' (\\d+)')); return m ? Number(m[1]) : NaN }
  const jl = await luck(J.username)
  check(`pity: J's ${jackpot} count is 0 once handed out`, count(jl) === 0, jl.text)
  check('pity: both end up in the lobby', await endRaid([G, J]))
  await sleep(1500)
  const jAfter = await luck(J.username)
  const back = Math.round(value(jackpot) * 0.5)
  check(`pity: J did not carry it out: ◈${back} into the quota, and the short raid leaves the count at 0`,
    jAfter.quota === back && count(jAfter) === 0, jAfter.text)
  G.quit()
  J.quit()
  await sleep(2000)
}

/**
 * Q (luck.qualify.min-seconds 30 for the run) searches five containers, stays past the qualifying time
 * and extracts: the raid joins the history, every jackpot count goes to 1 and the quota earns
 * (minutes x per-minute + searches x per-search) x the behaviour multiplier /cv luck shows.
 */
async function quota () {
  await useConfig([[/^( {4}min-seconds:) *\d+/m, '$1 30'], ...NO_HANDOUTS])
  markLog()
  const Q = await spawnBot('LkQ')
  const name = Q.username
  if (!await deploy([Q], 'rustport')) { check('quota: deployed on rustport', false); return }
  const landed = Date.now()
  let searched = 0
  for (const c of containers('rustport')) {
    if (searched >= 5) break
    if (c.type === 'body_bag') continue
    const r = await search(Q, c)
    if (!r) continue
    searched++
    Q.closeWindow(r.window)
    await sleep(100)
  }
  await waitFor(() => Date.now() - landed > 40000, 60000)
  await rcon(`execute as ${name} at @s run tp @s 89.5 65 3.5`)
  check('quota: extracts at 北门检查站', await waitFor(() => inLobby(Q), 40000))
  const seconds = (Date.now() - landed) / 1000
  await raidsOver() // the quota is paid in when the raid closes
  await sleep(1500)
  const l = await luck(name)
  log(name, l.text)
  const m = Number((l.text.match(/行为系数 ×([\d.]+)/) || [0, NaN])[1])
  const cfg = yamlFile(CONFIG).luck.quota
  const expected = (seconds / 60 * cfg['per-minute'] + searched * cfg['per-search']) * m
  check(`quota: the raid qualifies and joins the history (${searched} searches, ${seconds.toFixed(0)} s)`, searched === 5 && l.raids === 1, l.text)
  check(`quota: earns about ◈${Math.round(expected)} (x${m})`, Math.abs(l.quota - expected) <= 300 + 0.03 * expected, l.quota)
  const jackpots = Object.keys(yamlFile(path.join(DATA, 'loot.yml')).jackpots || {})
  check('quota: every jackpot count is 1', jackpots.every(j => new RegExp(ITEMS[j].name + ' 1\\b').test(l.text)), l.text)
  Q.quit()
  await sleep(2000)
}

async function proving () {
  await useConfig([])
  markLog()
  const T = await spawnBot('LkT')
  const name = T.username
  await rcon(`cv luck ${name} pity GOLD 29`)
  await rcon(`cv luck ${name} quota 500000`)
  const before = await luck(name)
  log('before', before.text)
  // The private range first: in from the lobby rack, a few shots at nothing, out again.
  await rcon(`execute as ${name} at @s run tp @s 9.5 65 -6 0 0`)
  await sleep(1200)
  const rack = T.nearestEntity(e => e.name === 'interaction')
  if (rack) T.activateEntity(rack)
  check('range: entered from the lobby rack', await waitFor(() => T.worldName.startsWith('craftov_range'), 8000), T.worldName)
  for (let i = 0; i < 5; i++) { T.swingArm(); await sleep(200) }
  T.chat('/cv lobby')
  check('range: back in the lobby', await waitFor(() => inLobby(T), 10000), T.worldName)
  await sleep(1000)
  const ranged = await luck(name)
  check('range: luck unchanged', ranged.text === before.text, ranged.text)
  if (!await deploy([T], 'proving')) { check('proving: deployed', false); return }
  let opened = 0
  for (const c of containers('proving')) {
    if (c.type.startsWith('test_') || c.type === 'body_bag') continue
    const r = await search(T, c)
    if (!r) continue
    opened++
    T.closeWindow(r.window)
    await sleep(100)
  }
  check(`proving: opened the regular containers (${opened})`, opened >= 20)
  check('proving: dies when the raid is ended', await endRaid([T]))
  await sleep(1500)
  const after = await luck(name)
  log('after', after.text)
  const lines = logLines(new RegExp(`\\[luck\\] ${name} `))
  check('proving: nothing is handed out', lines.length === 0, lines)
  check('proving: pity, quota, history and loss window unchanged',
    after.gold === 29 && after.red === before.red && after.quota === 500000 && after.raids === before.raids && after.loss === before.loss,
    after.text)
  T.quit()
  await sleep(2000)
}


// ------------------------------------------------------------------ what a raid cost

const GUNS = ['k47', 'p18']
/** Window slot of player inventory index i (0-8 hotbar, 9-35 main) in an open container window. */
const bottom = (w, i) => i < 9 ? w.inventoryStart + 27 + i : w.inventoryStart + i - 9

async function extractAt (bot) {
  await rcon(`execute as ${bot.username} at @s run tp @s 89.5 65 3.5`)
  return waitFor(() => inLobby(bot), 40000)
}
const raidsOver = () => waitFor(async () => (await rcon('cv raids')).includes('没有进行中的行动'), 90000)

/**
 * giver opens the first crate of the map and leaves its K47 and P18 in it; taker opens it and takes
 * them. Returns how many guns the taker carries afterwards (with the giver's instance ids).
 */
async function handGuns (giver, taker) {
  const crate = containers('rustport').find(c => c.type === 'crate')
  const g = await search(giver, crate)
  if (!g) return 0
  for (const id of GUNS) {
    for (let i = 0; i < 36; i++) {
      const it = g.window.slots[bottom(g.window, i)]
      // Shift-left puts an own stack into the container (#10: a plain click picks it up).
      if (it && cid(it) === id) { await giver.clickWindow(bottom(g.window, i), 0, 1); await sleep(400); break }
    }
  }
  giver.closeWindow(g.window)
  await sleep(600)
  const t = await search(taker, crate)
  if (!t) return 0
  const uids = []
  for (let s = 0; s < t.window.inventoryStart; s++) {
    const it = t.window.slots[s]
    if (s % 9 < 6 && it && GUNS.includes(cid(it))) { uids.push(pbv(it, 'uid')); await taker.clickWindow(s, 0, 0); await sleep(400) }
  }
  taker.closeWindow(t.window)
  await sleep(600)
  return taker.inventory.slots.filter(it => it && uids.includes(pbv(it, 'uid'))).length
}

/** H leaves its guns to squadmate M, both extract: nobody's luck books a loss or a gain. */
async function handover () {
  await useConfig([])
  markLog()
  const H = await spawnBot('LkH')
  const M = await spawnBot('LkM')
  H.chat(`/cv party invite ${M.username}`)
  await sleep(1200)
  M.chat('/cv party accept')
  await sleep(1200)
  if (!await deploy([H, M], 'rustport', b => (b === H ? '/cv queue rustport NORMAL TRIO false false' : null))) {
    check('handover: H and M land in the same raid as a squad', false)
    return
  }
  check('handover: M takes both of H\'s guns out of the crate', await handGuns(H, M) === 2)
  check('handover: H extracts', await extractAt(H))
  check('handover: M extracts', await extractAt(M))
  await raidsOver()
  await sleep(1500)
  const credit = logLines(new RegExp(`\\[luck\\] ${H.username}'s loss in raid \\S+ is ◈(\\d+) less \\(gear a squadmate carried out ◈(\\d+), insured gear coming back ◈0\\): net ◈(-?\\d+)`))
  const m = credit[0] && credit[0].match(/is ◈(\d+) less \(gear a squadmate carried out ◈(\d+).*net ◈(-?\d+)/)
  check('handover: the guns M carried out are credited back to H', !!m && Number(m[2]) >= 20000 && Number(m[3]) === 0, credit)
  const h = await luck(H.username)
  const mm = await luck(M.username)
  log('H', h.text)
  log('M', mm.text)
  check('handover: H has lost nothing and gets no compensation', h.loss === 0 && h.boost === 0, h.text)
  check('handover: M has gained nothing from them', mm.loss === 0 && mm.boost === 0, mm.text)
  H.quit()
  M.quit()
  await sleep(2000)
}

/** X leaves its guns to Y, an enemy in the same raid: Y's luck books them as a gain, X's as a loss. */
async function enemy () {
  await useConfig([])
  markLog()
  const X = await spawnBot('LkX')
  const Y = await spawnBot('LkY')
  if (!await deploy([X, Y], 'rustport')) { check('enemy: X and Y land in the same raid', false); return }
  check('enemy: Y takes both of X\'s guns out of the crate', await handGuns(X, Y) === 2)
  const mark = Y.chatLog.length
  check('enemy: X extracts', await extractAt(X))
  check('enemy: Y extracts', await extractAt(Y))
  await sleep(1500)
  const note = Y.chatLog.slice(mark).find(c => c.includes('其他玩家带进本局的装备')) || ''
  const value = Number((note.match(/◈([\d,]+)/) || ['', '0'])[1].replace(/,/g, ''))
  check('enemy: Y\'s extraction leaves X\'s guns out of its profit (rank, experience)', value >= 20000, note)
  await raidsOver()
  await sleep(1500)
  const x = await luck(X.username)
  const y = await luck(Y.username)
  log('X', x.text)
  log('Y', y.text)
  const want = -Math.round(value / 20000 * 100)
  check(`enemy: Y's luck books the guns as a gain (${want}% of the threshold)`, y.loss === want && y.boost === 0, y.text)
  check(`enemy: X's loss to an enemy sets off its compensation (${BOOST} raids)`, x.boost === BOOST, x.text)
  X.quit()
  Y.quit()
  await sleep(2000)
}

/** I goes missing with insured gear: what insurance sends back is taken off I's loss. */
async function insured () {
  await useConfig([[/^( {2}loss-chance:) *[\d.]+/m, '$1 0'], [/^( {2}return-minutes:) *\d+/m, '$1 0']])
  markLog()
  const I = await spawnBot('LkI')
  const w = await openWith(I, () => I.chat('/cv deploy'))
  if (w) {
    await I.clickWindow(34, 0, 0) // auto insurance on
    await sleep(400)
    I.closeWindow(w)
  }
  const mark = I.chatLog.length
  if (!await deploy([I], 'rustport')) { check('insured: deployed on rustport', false); return }
  check('insured: gear insured on deploy', I.chatLog.slice(mark).some(c => /件装备投保/.test(c)), I.chatLog.slice(mark))
  check('insured: goes missing when the raid is ended', await endRaid([I]))
  await sleep(1500)
  const lines = logLines(new RegExp(`\\[luck\\] ${I.username}'s loss in raid`))
  const m = lines[0] && lines[0].match(/is ◈(\d+) less \(gear a squadmate carried out ◈0, insured gear coming back ◈(\d+)\): net ◈(-?\d+)/)
  check('insured: the insured gear coming back is taken off the loss', !!m && Number(m[1]) === Number(m[2]) && Number(m[2]) > 0, lines)
  const l = await luck(I.username)
  log('I', l.text)
  if (m) {
    const net = Number(m[3])
    check(`insured: /cv luck follows the settled net (◈${net})`, net <= -20000 ? l.boost === BOOST
      : l.boost === 0 && l.loss === Math.round(-net / 20000 * 100), l.text)
  }
  I.quit()
  await sleep(2000)
}

// ------------------------------------------------------------------ voided raids

const SERVER_REAL = fs.realpathSync(SERVER_DIR)
function serverPids () {
  const out = []
  for (const pid of fs.readdirSync('/proc').filter(p => /^\d+$/.test(p))) {
    try {
      if (fs.readlinkSync(`/proc/${pid}/cwd`) === SERVER_REAL && fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('paper.jar')) out.push(pid)
    } catch { }
  }
  return out
}
/** Stops the server (RCON stop, or SIGKILL when kill), starts it again with start.sh and waits for Done. */
async function restart (kill) {
  const oldLog = fs.existsSync(LOG) ? fs.statSync(LOG).ino : -1
  if (kill) {
    for (const pid of serverPids()) { log('SIGKILL', pid); process.kill(Number(pid), 'SIGKILL') }
  } else {
    await rcon('stop').catch(() => {})
  }
  const gone = await waitFor(() => serverPids().length === 0, 90000)
  check(`the server ${kill ? 'is killed' : 'stops'}`, gone)
  if (!gone) return false
  await sleep(2000)
  const out = fs.openSync(path.join(SERVER_DIR, 'console.log'), 'a')
  require('child_process').spawn(path.join(SERVER_DIR, 'start.sh'), [], { cwd: SERVER_DIR, detached: true, stdio: ['ignore', out, out] }).unref()
  // A new latest.log (the old one is rolled over at start) that says Done, and RCON answering.
  const up = await waitFor(async () => fs.existsSync(LOG) && fs.statSync(LOG).ino !== oldLog && /Done \(/.test(fs.readFileSync(LOG, 'utf8'))
    && await rcon('list').then(() => true, () => false), 180000)
  check('the server is back', up)
  await sleep(3000)
  logFrom = 0 // a new latest.log
  return up
}

/**
 * Searches containers until the server log says bot was handed something by `source` (a regex part),
 * leaving it where it is. Returns {item, value} or null.
 */
async function handedOut (bot, source) {
  for (const c of containers('rustport')) {
    if (c.type === 'body_bag') continue
    const r = await search(bot, c)
    if (!r) continue
    bot.closeWindow(r.window)
    await sleep(200)
    const line = logLines(new RegExp(`\\[luck\\] ${bot.username} is handed (\\S+) \\(◈(\\d+), ${source}`))[0]
    if (line) {
      const m = line.match(/is handed (\S+) \(◈(\d+)/)
      return { item: m[1], value: Number(m[2]) }
    }
  }
  return null
}

async function voided () {
  await useConfig(VOID_CFG)
  const jackpots = Object.keys(yamlFile(path.join(DATA, 'loot.yml')).jackpots || {})
  const counts = l => jackpots.map(j => { const m = l.text.match(new RegExp(ITEMS[j].name + ' (\\d+)')); return m ? Number(m[1]) : NaN })

  // Evacuated: leaves the raid world with the hand-out left behind.
  markLog()
  const V = await spawnBot('LkV')
  await rcon(`cv luck ${V.username} quota 1000000`)
  if (!await deploy([V], 'rustport')) { check('void: V deployed', false); return }
  const v = await handedOut(V, 'quota')
  check('void: V is handed a quota item', !!v, v)
  V.chat('/cv lobby')
  check('void: V is back in the lobby (evacuated)', await waitFor(() => inLobby(V), 20000))
  await rcon('cv end')
  await raidsOver()
  await sleep(1500)
  const vl = await luck(V.username)
  const back = logLines(new RegExp(`\\[luck\\] ${V.username}'s raid \\S+ was voided and \\S+ \\(quota\\) did not go home: ◈(\\d+) back`))
  check('void: V\'s quota is 1,000,000 again once the raid closes', vl.quota === 1000000 && back.length === 1, `${vl.quota} ${back}`)
  V.quit()

  // Server stop: S leaves a quota hand-out, J a jackpot after 1,000 raids.
  markLog()
  const S = await spawnBot('LkS')
  const J = await spawnBot('LkJ')
  await rcon(`cv luck ${S.username} quota 1000000`)
  for (const j of jackpots) await rcon(`cv luck ${J.username} pity ${j} 1000`)
  if (!await deploy([S, J], 'rustport')) { check('void: S and J deployed', false); return }
  const [sOut, jOut] = await Promise.all([handedOut(S, 'quota'), handedOut(J, 'jackpot')])
  check('void: S is handed a quota item', !!sOut, sOut)
  check('void: J is handed a jackpot', !!jOut && jackpots.includes(jOut.item), jOut)
  const jMid = counts(await luck(J.username))
  check('void: J\'s jackpot counts moved (one 0, the others back to soft-raids)', jMid.includes(0), jMid)
  if (!await restart(false)) return
  const sl = await luck(S.username)
  const jl = await luck(J.username)
  log('S', sl.text)
  log('J', jl.text)
  check('void: after the stop S\'s quota is 1,000,000 again', sl.quota === 1000000, sl.text)
  check('void: after the stop J\'s jackpot counts are 1,000 again', counts(jl).every(n => n === 1000), counts(jl))

  // Crash: C leaves a quota hand-out and the server is killed.
  await useConfig(VOID_CFG) // still in the file after the restart: written again to be sure
  markLog()
  const name = 'LkC' + tag
  let C = await spawnBot('LkC', name)
  await rcon(`cv luck ${name} quota 1000000`)
  if (!await deploy([C], 'rustport')) { check('void: C deployed', false); return }
  const cOut = await handedOut(C, 'quota')
  check('void: C is handed a quota item', !!cOut, cOut)
  await sleep(11000) // the profile flush runs every 10 s: the spent quota is on disk
  if (!await restart(true)) return
  const before = await luck(name)
  check('void: before C logs in its saved quota is less the hand-out', !!cOut && before.quota === 1000000 - cOut.value, before.text)
  C = await spawnBot('LkC', name)
  await sleep(2000)
  check('void: C is told the raid was voided', C.chatLog.some(m => m.includes('因服务器中断已作废')))
  const cl = await luck(name)
  log('C', cl.text)
  check('void: after the crash C\'s quota is 1,000,000 again', cl.quota === 1000000, cl.text)
  C.quit()
  await sleep(2000)
}
;(async () => {
  try {
    if (scenario === 'all' || scenario === 'proving') await proving()
    if (scenario === 'all' || scenario === 'pity') await pity()
    if (scenario === 'all' || scenario === 'quota') await quota()
    if (scenario === 'all' || scenario === 'refund') await refund()
    if (scenario === 'all' || scenario === 'compensation') await compensation()
    if (scenario === 'all' || scenario === 'handover') await handover()
    if (scenario === 'all' || scenario === 'enemy') await enemy()
    if (scenario === 'all' || scenario === 'insured') await insured()
    if (scenario === 'all' || scenario === 'void') await voided()
  } catch (e) {
    check('no error', false, e.stack)
  } finally {
    await restoreConfig().catch(e => log('restore failed', e.message))
  }
  log(failures.length ? `FAILED: ${failures.length}\n  ${failures.join('\n  ')}` : 'all luck checks passed')
  process.exit(failures.length ? 1 : 0)
})()
