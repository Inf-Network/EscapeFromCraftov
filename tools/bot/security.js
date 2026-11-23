// Command arguments, the /squad alias and the offline-mode operator guard (issue #39), with
// headless bots on a local test server:
//
//   1. a player's "/cv queue x NORMAL duo" (and "/cv queue x") prints the Chinese usage line, and
//      the server log gets no stack trace from any step below;
//   2. an operator's "/cv give <bot> m4c abc" and "/cv money <bot> abc" print only their usage
//      lines (no item, no money);
//   3. in a fresh build world, "/cv editor start ../config" and "../../CraftEngine/config" are
//      refused, "/cv editor save" then has no session, and neither plugins/Craftov/config.yml nor
//      CraftEngine's config.yml is touched; with a real session "set minutes abc" prints its usage
//      and "set camera" works without a value; a map file whose id does not fit the new rule
//      (upper case and a dash, as older versions allowed) still opens in the editor and saves its
//      draft under its own id (maps/drafts/, the live file untouched), and once it is gone the
//      same id is refused for a new map;
//   4. a non-op player's "/squad invite <other>", the other's "/squad accept", then "/party" and
//      "/craftov:party" list a squad of two, and tab completion of "/squad " offers
//      invite / accept / leave / kick / list;
//   5. with security.admin-addresses set to entries that are not literal addresses (192.168.1.300,
//      localhost) in the server's config.yml (and /cv reload), both are reported as ignored, an
//      operator bot logging in from 127.0.0.1 is refused with the admin message and the refusal is
//      logged, while a non-op bot still gets in; "admin-guard: off" (a bare YAML off) lets the
//      operator in despite the list, and so does the default list restored. The config file is
//      restored however the run ends.
//
//   node security.js     env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local
//                             SERVER_DIR=<server folder, default the repository's server/run>
//
// Needs an offline-mode server (setup.sh --local) without a whitelist, or with these random bot
// names allowed, and security.admin-guard left at "auto". The operator bot is deopped when done.
// Exit code 0 when every check passed, 1 when one failed, 2 when the scenario broke.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
const CONFIG = path.join(SERVER_DIR, 'plugins/Craftov/config.yml')
const CE_CONFIG = path.join(SERVER_DIR, 'plugins/CraftEngine/config.yml')
const MAPS = path.join(SERVER_DIR, 'plugins/Craftov/maps')
const LOG = path.join(SERVER_DIR, 'logs/latest.log')
const t0 = Date.now()
const run = (Date.now() % 1679616).toString(36)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)))
const rcon = cmd => rconRaw(cmd, RCON).then(r => r.replace(/§./g, ''))
const failures = []
function check (label, ok, detail) {
  log(ok ? 'PASS' : 'FAIL', label, detail === undefined ? '' : detail)
  if (!ok) failures.push(label)
}

/** The plain text of a chat component or kick reason: JSON text, or NBT-typed nodes, by packet. */
function strings (x) {
  if (x == null) return ''
  if (typeof x === 'string') {
    try { const parsed = JSON.parse(x); if (typeof parsed === 'object') return strings(parsed) } catch (e) {}
    return x
  }
  if (Array.isArray(x)) return x.map(strings).join('')
  if (typeof x !== 'object') return ''
  if (x.type === 'string') return String(x.value)
  // text and extra in JSON; value wraps compounds and lists in NBT; '' holds plain text in compact NBT.
  return ['text', '', 'value', 'extra'].map(k => strings(x[k])).join('')
}

/** Joins; resolves to the bot once spawned, or { kicked: reason } when the login is refused. */
function join (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim()) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
  bot.on('error', e => log(name, 'ERROR', e.message))
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' neither spawned nor was refused')), 60000)
    bot.once('kicked', reason => { clearTimeout(t); log(name, 'KICKED', strings(reason)); resolve({ kicked: strings(reason) }) })
    bot.once('spawn', async () => { clearTimeout(t); await sleep(2500); resolve(bot) })
  })
}

async function waitFor (pred, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await pred()) return true
    await sleep(200)
  }
  return false
}

/** Says something and waits for a chat line matching re (only lines after the command count). */
async function say (bot, text, re, ms = 5000) {
  const from = bot.chatLog.length
  bot.chat(text)
  let hit = null
  await waitFor(() => { hit = bot.chatLog.slice(from).find(m => re.test(m)) || null; return hit }, ms)
  return { hit, lines: bot.chatLog.slice(from) }
}

const mtime = file => { try { return fs.statSync(file).mtimeMs } catch (e) { return null } }
/** The server log from a byte offset (a size taken earlier: the log has Chinese text, so not a string index). */
const logSince = offset => fs.readFileSync(LOG).subarray(offset).toString('utf8')
const listMaps = () => { try { return fs.readdirSync(MAPS).filter(f => f.endsWith('.yml')).sort().join(',') } catch (e) { return '' } }

async function main () {
  if (!fs.existsSync(CONFIG) || !fs.existsSync(LOG)) {
    log('SETUP: no', CONFIG, 'or', LOG, '(set SERVER_DIR)')
    return 2
  }
  const logStart = fs.statSync(LOG).size
  const A = await join(`sec${run}a`) // the operator
  const B = await join(`sec${run}b`) // an ordinary player
  if (A.kicked || B.kicked) {
    log('SETUP: a bot could not join')
    return 2
  }

  // ---- 1. a player's bad queue arguments
  let r = await say(B, '/cv queue x NORMAL duo', /用法：\/cv queue/)
  check('"/cv queue x NORMAL duo" prints the queue usage', !!r.hit, r.lines)
  check('... and no internal error', !r.lines.some(l => /internal error|unexpected error|内部错误/i.test(l)), r.lines)
  r = await say(B, '/cv queue x', /用法：\/cv queue/)
  check('"/cv queue x" prints the queue usage', !!r.hit, r.lines)

  // ---- 2. an operator's bad numbers
  await rcon(`op ${A.username}`)
  await sleep(1500)
  const moneyBefore = await rcon(`cv money ${A.username} 0`)
  r = await say(A, `/cv give ${A.username} m4c abc`, /用法：\/cv give/)
  check('"/cv give <bot> m4c abc" prints the give usage', !!r.hit && !r.lines.some(l => l.includes('已给予')), r.lines)
  r = await say(A, `/cv money ${A.username} abc`, /用法：\/cv money/)
  check('"/cv money <bot> abc" prints the money usage', !!r.hit && !r.lines.some(l => l.includes('余额')), r.lines)
  r = await say(A, `/cv money ${A.username} 99999999999999999999`, /用法：\/cv money/)
  check('"/cv money" with a number past a long prints the usage', !!r.hit, r.lines)
  const moneyAfter = await rcon(`cv money ${A.username} 0`)
  check('... and the balance did not change', moneyBefore === moneyAfter, [moneyBefore, moneyAfter])

  // ---- 3. map ids that leave the maps folder
  r = await say(A, '/cv editor world sectest_world', /已进入建图世界/, 20000)
  if (!r.hit) {
    log('SETUP: no build world', r.lines)
    return 2
  }
  await sleep(1500)
  const configTime = mtime(CONFIG)
  const ceTime = mtime(CE_CONFIG)
  const mapsBefore = listMaps()
  r = await say(A, '/cv editor start ../config', /ID只能包含/)
  check('"/cv editor start ../config" is refused', !!r.hit && !r.lines.some(l => l.includes('开始编辑地图')), r.lines)
  r = await say(A, '/cv editor start ../../CraftEngine/config', /ID只能包含/)
  check('"/cv editor start ../../CraftEngine/config" is refused', !!r.hit, r.lines)
  r = await say(A, '/cv editor save', /你不在编辑模式/)
  check('"/cv editor save" afterwards has no session to save', !!r.hit && !r.lines.some(l => l.includes('地图已保存')), r.lines)
  await sleep(1000)
  check('plugins/Craftov/config.yml was not written', mtime(CONFIG) === configTime, [configTime, mtime(CONFIG)])
  check('CraftEngine config.yml was not written', mtime(CE_CONFIG) === ceTime, [ceTime, mtime(CE_CONFIG)])
  check('no map file appeared', listMaps() === mapsBefore, [mapsBefore, listMaps()])
  r = await say(A, '/cv editor start SecTest_Map', /开始编辑地图/)
  check('"/cv editor start SecTest_Map" starts a session (id lowercased)', !!r.hit && r.hit.includes('(sectest_map)'), r.lines)
  r = await say(A, '/cv editor set minutes abc', /用法：\/cv editor set minutes/)
  check('"/cv editor set minutes abc" prints its usage', !!r.hit, r.lines)
  r = await say(A, '/cv editor set camera', /已设置 camera/)
  check('"/cv editor set camera" works without a value', !!r.hit, r.lines)
  r = await say(A, '/cv editor set name', /可设置：/)
  check('"/cv editor set name" without a value lists the keys', !!r.hit && !r.lines.some(l => l.includes('已设置')), r.lines)
  r = await say(A, '/cv editor discard', /已退出编辑模式/)
  check('"/cv editor discard" leaves without saving', !!r.hit && listMaps() === mapsBefore, [r.lines, listMaps()])
  // A map saved before ids were checked: it must stay editable, and save over its own file.
  const legacyId = `Sec-Legacy-${run}`
  const legacyFile = path.join(MAPS, legacyId + '.yml')
  const legacyDraft = path.join(MAPS, 'drafts', legacyId + '.yml')
  const withLegacy = [...mapsBefore.split(',').filter(Boolean), legacyId + '.yml'].sort().join(',')
  const legacyText = `id: ${legacyId}\nname: 旧地图\ntemplate: sectest_world\ntest: true\n`
  fs.writeFileSync(legacyFile, legacyText)
  try {
    await rcon('cv reload')
    r = await say(A, `/cv editor start ${legacyId}`, /开始编辑地图|ID只能包含/)
    check(`an existing map with the id ${legacyId} opens in the editor`, !!r.hit && r.hit.includes(`(${legacyId})`), r.lines)
    // A file without "enabled" counts as published: the save is a draft beside it, under the same id.
    r = await say(A, '/cv editor save', /草稿已保存|保存失败/)
    const draft = fs.existsSync(legacyDraft) ? fs.readFileSync(legacyDraft, 'utf8') : ''
    check('... and saves its draft under its own id', !!r.hit && r.hit.includes('草稿已保存') && draft.includes('raid-minutes') &&
      draft.includes(`id: ${legacyId}`) && listMaps() === withLegacy, [r.lines, listMaps()])
    check('... leaving the live file as it was', fs.readFileSync(legacyFile, 'utf8') === legacyText)
    // Discarding a saved draft of a published map throws away its working copy too: it asks first.
    r = await say(A, '/cv editor discard', /确认放弃|已退出编辑模式/)
    check('"/cv editor discard" with a saved draft asks for a confirm', !!r.hit && r.hit.includes('确认放弃'), r.lines)
    r = await say(A, '/cv editor discard confirm', /已放弃/)
    check('... and "discard confirm" deletes the draft', !!r.hit && !fs.existsSync(legacyDraft), r.lines)
  } finally {
    fs.rmSync(legacyFile, { force: true })
    fs.rmSync(legacyDraft, { force: true })
    await rcon('cv reload')
  }
  r = await say(A, `/cv editor start ${legacyId}`, /开始编辑地图|ID只能包含/)
  check(`with no such map, ${legacyId} is refused as a new id`, !!r.hit && r.hit.includes('ID只能包含'), r.lines)
  check('... and no map file is left', listMaps() === mapsBefore, [mapsBefore, listMaps()])
  A.chat('/cv lobby')
  await sleep(1500)

  // ---- 4. /squad for an ordinary player
  const invited = A.chatLog.length
  r = await say(B, `/squad invite ${A.username}`, /已邀请/)
  check('"/squad invite" sends an invitation, not the /cv help', !!r.hit && !r.lines.some(l => l.includes('Craftov 指令')), r.lines)
  check('... which the other player receives', await waitFor(() => A.chatLog.slice(invited).some(l => l.includes('邀请你加入小队')), 3000))
  r = await say(A, '/squad accept', /加入了小队 \(2\/3\)/)
  check('"/squad accept" joins the squad', !!r.hit, r.lines)
  r = await say(B, '/party', /小队 \(2\/3\)/)
  check('"/party" lists a squad of two', !!r.hit && r.hit.includes(A.username) && r.hit.includes(B.username), r.lines)
  r = await say(B, '/craftov:party list', /小队 \(2\/3\)/)
  check('"/craftov:party list" lists it too', !!r.hit, r.lines)
  let matches = []
  try {
    matches = (await B.tabComplete('/squad ', true, false, 5000)).map(m => typeof m === 'string' ? m : m.match)
  } catch (e) {
    log('tab complete failed', e.message)
  }
  const wanted = ['invite', 'accept', 'leave', 'kick', 'list']
  check('tab completion of "/squad " offers the party actions', wanted.every(w => matches.includes(w)) && !matches.includes('deploy'), matches)
  await say(A, '/squad leave', /你已离开小队/)

  // ---- 5. the operator guard
  const original = fs.readFileSync(CONFIG, 'utf8')
  // Neither is an address: one has an octet past 255 (which InetAddress would hand to DNS), one is a
  // host name. With both ignored, no operator is trusted.
  const empty = original.replace(/^([ \t]*)admin-addresses:[ \t]*\n(?:\1[ \t]+-.*\n)+/m,
    '$1admin-addresses: ["192.168.1.300", "localhost"]\n')
  if (empty === original) {
    log('SETUP: no security.admin-addresses list in', CONFIG)
    return 2
  }
  A.quit()
  await sleep(2000)
  fs.writeFileSync(CONFIG, empty)
  try {
    if (!(await rcon('cv reload')).includes('已重新加载')) {
      log('SETUP: cv reload failed')
      return 2
    }
    const logBefore = fs.statSync(LOG).size
    const refused = await join(A.username)
    check('with admin-addresses [192.168.1.300, localhost] the operator is refused at login',
      !!refused.kicked && refused.kicked.includes('管理员账号'), refused.kicked || 'spawned')
    if (!refused.kicked) refused.quit()
    await sleep(1000)
    const tail = logSince(logBefore)
    const ignored = tail.split('\n').filter(l => l.includes('security.admin-addresses:') && l.includes('ignored'))
    check('... both entries are reported as not addresses', ['"192.168.1.300"', '"localhost"'].every(e => ignored.some(l => l.includes(e))), ignored)
    check('... and the refusal is logged', tail.includes(`Refused the login of operator ${A.username}`), tail.split('\n').filter(l => l.includes('Refused')))
    const C = await join(`sec${run}c`)
    check('a non-op player still gets in', !C.kicked, C.kicked)
    if (!C.kicked) C.quit()
    // A bare "off" is a YAML boolean (false): it must still turn the guard off.
    fs.writeFileSync(CONFIG, empty.replace(/^([ \t]*admin-guard:[ \t]*).*$/m, '$1off'))
    await rcon('cv reload')
    await sleep(1000)
    const unguarded = await join(A.username)
    check('with admin-guard: off the operator gets in despite the list', !unguarded.kicked, unguarded.kicked)
    if (!unguarded.kicked) unguarded.quit()
    await sleep(1500)
  } finally {
    fs.writeFileSync(CONFIG, original)
    await rcon('cv reload').catch(() => {})
  }
  await sleep(1000)
  const back = await join(A.username)
  check('with the list restored the operator gets in from 127.0.0.1', !back.kicked, back.kicked)
  await rcon(`deop ${A.username}`)
  if (!back.kicked) back.quit()
  B.quit()
  await sleep(1500)

  const added = logSince(logStart)
  const traces = added.split('\n').filter(l => /Exception|internal error|\tat net\.infnetwork/.test(l) && !l.includes('ConcurrentModificationException'))
  check('no stack trace in the server log', traces.length === 0, traces.slice(0, 5))
  return failures.length ? 1 : 0
}

main().then(code => {
  log(failures.length ? `FAILED: ${failures.join(' | ')}` : code === 0 ? 'ALL PASSED' : 'BROKEN')
  process.exit(code)
}, e => {
  log('BROKEN', e.stack || e.message)
  process.exit(2)
})
