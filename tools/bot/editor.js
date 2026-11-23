// Map editor drafts, publishing, lever ids and working copies (issue #44), with headless bots on a
// local test server. A is an operator who edits maps, B an ordinary player who queues for them.
//
//   1. draft: A starts a new map in a fresh build world and disconnects; maps/<id>.yml is saved with
//      enabled: false, B's deploy menu does not list it and "/cv queue" refuses it;
//   2. publish refused: back in the editor (it takes A to the map's own world), "/cv editor publish"
//      lists the missing always-open extract, spawn groups and containers;
//   3. lever ids: levers switch_1 and switch_2 each get a lever extract; a click in another world
//      places nothing; removing switch_1 unbinds its extract (said in chat, gone from the saved file);
//      the next lever is switch_3, not a second switch_2, and picks the orphaned extract up;
//   4. a hand-written map whose lever extract names switch_9, with two levers sharing an id, gets
//      both warnings in the server log on /cv reload;
//   5. publish and raid: with 4 spawn groups, an always-open extract and 5 containers the map
//      publishes, A is back in the lobby, B's deploy menu lists it, and B's solo raid on it lands on a
//      spawn point and extracts at the always-open extract;
//   6. maintenance: a published map whose template world has a player in it is listed as under
//      maintenance and refused, can be queued once the world is empty, and a queue already waiting is
//      cancelled when someone walks into the template; an unpublished map file naming the same world
//      does not open it for editing in place;
//   7. working copy: "/cv editor world" on rustport's template is refused; "/cv editor start
//      rustport" from the lobby takes A to craftov_edit_rustport while B can still queue for rustport;
//      a block and a container marker added there, then "exit": A is back in the lobby, the draft is in
//      maps/drafts/, the live rustport.yml and the template's region files are untouched and the copy
//      is unloaded; "start" again keeps the block, and "discard" now asks for a confirm; "publish"
//      swaps the copy in (template .prev and rustport.yml.prev kept, draft and copy gone) and B's raid
//      on rustport has the block; a chest the scan tool removes in a fresh copy is back after
//      "discard"; a last publish takes the block and the marker out again.
//
//   node editor.js     env: PORT=25565 RCON_PORT=25575 RCON_PASS=craftov-local
//                           SERVER_DIR=<server folder, default the repository's server/run>
//
// Needs an offline-mode server (setup.sh --local) without a whitelist, match.max-squads 4 and
// match.wait-seconds between 5 and 30 (5 keeps it quick). It publishes rustport twice, ending with
// the markers and blocks it had, and leaves craftov_tpl_rustport.prev and rustport.yml.prev behind.
// Exit code 0 when every check passed, 1 when one failed, 2 when the scenario broke.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')
const Vec3 = require('vec3')
const rconRaw = require('./rcon')

const PORT = Number(process.env.PORT || 25565)
const RCON = { port: Number(process.env.RCON_PORT || 25575), password: process.env.RCON_PASS || 'craftov-local' }
const SERVER_DIR = process.env.SERVER_DIR || path.join(__dirname, '../../server/run')
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

// ------------------------------------------------------------------ text, items, files

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
const title = item => flat(comp(item, 'custom_name') || comp(item, 'item_name'))

/** A map file as JSON (PyYAML), or null when it does not exist. */
function yml (file) {
  if (!fs.existsSync(file)) return null
  const code = 'import json,sys,yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1], encoding="utf-8"))))'
  return JSON.parse(execFileSync('python3', ['-c', code, file], { encoding: 'utf8' }))
}
const mapFile = id => path.join(MAPS, id + '.yml')
const draftFile = id => path.join(MAPS, 'drafts', id + '.yml')
const world = name => path.join(SERVER_DIR, name)
/** sha1 over a folder's files (names and contents), to tell whether a template changed. */
function treeHash (dir) {
  const h = crypto.createHash('sha1')
  const walk = d => {
    for (const f of fs.readdirSync(d).sort()) {
      const p = path.join(d, f)
      if (fs.statSync(p).isDirectory()) walk(p)
      else { h.update(path.relative(dir, p)); h.update(fs.readFileSync(p)) }
    }
  }
  if (fs.existsSync(dir)) walk(dir)
  return h.digest('hex')
}
const logSince = offset => fs.readFileSync(LOG).subarray(offset).toString('utf8')

// ------------------------------------------------------------------ bots

function join (name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  bot.chatLog = []
  bot.bars = []
  bot.worldName = ''
  const track = p => { if (p.worldState && p.worldState.name) bot.worldName = p.worldState.name.replace(/^minecraft:/, '') }
  bot._client.on('login', track)
  bot._client.on('respawn', track)
  bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
  bot.on('messagestr', m => { if (m.trim() && !m.includes('━━━━')) { bot.chatLog.push(m); log(name, 'CHAT', m) } })
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
/** Says something and waits for a chat line matching re; returns the hit and every line since. */
async function say (bot, text, re, ms = 6000) {
  const from = bot.chatLog.length
  bot.chat(text)
  let hit = null
  await waitFor(() => { hit = bot.chatLog.slice(from).find(m => re.test(m)) || null; return hit }, ms)
  return { hit, lines: bot.chatLog.slice(from) }
}
function openWith (bot, fn) {
  return new Promise(resolve => {
    const t = setTimeout(() => resolve(null), 6000)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
    fn()
  })
}
/** Titles of the map buttons on B's deploy screen. */
async function deployMaps (bot) {
  const w = await openWith(bot, () => bot.chat('/cv deploy'))
  if (!w) return null
  const titles = []
  for (let i = 10; i <= 16; i++) if (w.slots[i]) titles.push(title(w.slots[i]))
  bot.closeWindow(w)
  await sleep(400)
  return titles
}
const inRaid = bot => bot.worldName.startsWith('craftov_raid_') && bot.game.gameMode === 'adventure'
const inLobby = bot => bot.worldName === 'craftov_lobby'

// Editor tool slots, as EditorService.Tool lists them.
const SPAWN = 0
const EXTRACT = 1
const CONTAINER = 2
const LOCK = 5
const REMOVE = 6
const SCAN = 7

/** Right-clicks the block at p with the tool in a hotbar slot. */
async function click (bot, slot, p) {
  bot.setQuickBarSlot(slot)
  await sleep(250)
  const block = bot.blockAt(p)
  if (!block) throw new Error('no block loaded at ' + p)
  await bot.activateBlock(block)
  await sleep(450)
}
/**
 * Sneak + right-click air with a tool, n times: the next option each time. Looking up first: a
 * right-click in the air while a block is in reach reaches the server as a click on that block.
 */
async function cycle (bot, slot, n) {
  bot.setQuickBarSlot(slot)
  await bot.look(bot.entity.yaw, Math.PI / 2, true)
  bot.setControlState('sneak', true)
  await sleep(500)
  for (let i = 0; i < n; i++) {
    bot.activateItem()
    await sleep(150)
    bot.deactivateItem()
    await sleep(300)
  }
  bot.setControlState('sneak', false)
  await sleep(400)
}
/** In world w, runs a command at a position (the console's own commands run in the overworld). */
const inWorld = (w, cmd) => rcon(`execute in minecraft:${w} run ${cmd}`)
const blockIs = async (w, x, y, z, type) => /Test passed/.test(await rcon(`execute in minecraft:${w} if block ${x} ${y} ${z} minecraft:${type}`))

// ------------------------------------------------------------------ scenario

async function main () {
  if (!fs.existsSync(LOG) || !fs.existsSync(mapFile('rustport'))) {
    log('SETUP: no', LOG, 'or maps/rustport.yml (set SERVER_DIR)')
    return 2
  }
  const logStart = fs.statSync(LOG).size
  const aName = `ed${run}a`
  let A = await join(aName)
  const B = await join(`ed${run}b`)
  await rcon(`op ${aName}`)
  await sleep(1000)
  const id = `t_ed_${run}`

  // ---- 1. a new map's save is a draft nobody can queue for
  let r = await say(A, `/cv editor world ${id}`, /已进入建图世界/, 20000)
  if (!r.hit || !(await waitFor(() => A.worldName === id, 10000))) {
    log('SETUP: no build world', r.lines, A.worldName)
    return 2
  }
  await sleep(1500)
  await rcon(`execute as ${aName} at @s run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone`)
  r = await say(A, `/cv editor start ${id}`, /开始编辑地图/)
  check('a new map opens in the editor', !!r.hit, r.lines)
  A.quit()
  await sleep(2500)
  const draft = yml(mapFile(id))
  check(`leaving the server saved maps/${id}.yml as a draft (enabled: false)`, !!draft && draft.enabled === false, draft && draft.enabled)
  let maps = await deployMaps(B)
  check('the draft is not on the deploy screen', !!maps && maps.length > 0 && !maps.some(t => t.includes(id)), maps)
  r = await say(B, `/cv queue ${id} NORMAL SOLO`, /还没有发布|开始匹配/)
  check('"/cv queue" refuses the draft', !!r.hit && r.hit.includes('还没有发布'), r.lines)

  // ---- 2. publishing a map that cannot carry a raid is refused, with the reasons
  A = await join(aName)
  r = await say(A, `/cv editor start ${id}`, /开始编辑地图/)
  const back = await waitFor(() => A.worldName === id, 8000)
  check('"/cv editor start" on the draft takes the editor from the lobby to its world', !!r.hit && back, [r.lines, A.worldName])
  await sleep(1500)
  r = await say(A, '/cv editor publish', /还不能发布|已发布/)
  await sleep(800)
  const reasons = A.chatLog.slice(-8).join('\n')
  check('"/cv editor publish" is refused', !!r.hit && r.hit.includes('还不能发布'), r.lines)
  check('... naming the missing always-open extract, spawn groups and containers',
    reasons.includes('常驻撤离点') && reasons.includes('出生点只有 0 组') && reasons.includes('物资箱只有 0 个'), reasons)
  check('... and the map stays a draft', yml(mapFile(id)).enabled === false)

  // ---- 3. lever ids and bindings
  const p = A.entity.position.floored()
  const at = (dx, dy, dz) => new Vec3(p.x + dx, p.y + dy, p.z + dz)
  for (const [dx, dz] of [[3, 0], [-3, 0], [0, 3]]) {
    await inWorld(id, `setblock ${p.x + dx} ${p.y} ${p.z + dz} minecraft:lever[face=floor]`)
  }
  await sleep(800)
  let from = A.chatLog.length
  await click(A, LOCK, at(3, 0, 0))
  check('the first lever is switch_1', A.chatLog.slice(from).some(l => l.includes('已设置电闸 switch_1')), A.chatLog.slice(from))
  await cycle(A, EXTRACT, 3)
  check('the extract tool is on lever extracts', A.bars.slice(-1)[0] && A.bars.slice(-1)[0].includes('需拉闸'), A.bars.slice(-1))
  await click(A, EXTRACT, at(2, -1, -3))
  from = A.chatLog.length
  await click(A, LOCK, at(-3, 0, 0))
  check('the second lever is switch_2', A.chatLog.slice(from).some(l => l.includes('已设置电闸 switch_2')), A.chatLog.slice(from))
  await click(A, EXTRACT, at(-2, -1, -3))
  await say(A, '/cv editor save', /草稿已保存/)
  let saved = yml(mapFile(id))
  const sw = e => e && (e.switch || '')
  const ex = n => (saved.extracts || []).find(e => e.id === 'extract_' + n)
  check('extract_1 is bound to switch_1 and extract_2 to switch_2', sw(ex(1)) === 'switch_1' && sw(ex(2)) === 'switch_2', saved.extracts)

  // A click in another world: nothing is placed.
  r = await say(A, `/cv editor world t_ot_${run}`, /已进入建图世界/, 20000)
  await waitFor(() => A.worldName === `t_ot_${run}`, 8000)
  await sleep(1500)
  const there = A.entity.position.floored()
  const bars = A.bars.length
  await click(A, SPAWN, new Vec3(there.x, there.y - 1, there.z))
  check('a tool click in another world is refused', A.bars.slice(bars).some(b => b.includes('不是正在编辑的世界')), A.bars.slice(bars))
  await inWorld(id, `tp ${aName} ${p.x + 0.5} ${p.y} ${p.z + 0.5}`)
  await waitFor(() => A.worldName === id, 8000)
  await sleep(1500)
  await say(A, '/cv editor save', /草稿已保存/)
  saved = yml(mapFile(id))
  check('... and saved no spawn point', (saved.spawns || []).length === 0, saved.spawns)

  from = A.chatLog.length
  await click(A, REMOVE, at(3, -1, 0))
  await sleep(500)
  const removed = A.chatLog.slice(from).find(l => l.includes('已删除电闸 switch_1')) || ''
  check('removing switch_1 says its extract is unbound', removed.includes('撤离点 1') && removed.includes('不再绑定电闸'), A.chatLog.slice(from))
  await say(A, '/cv editor save', /草稿已保存/)
  saved = yml(mapFile(id))
  check('... the saved extract_1 has no switch, extract_2 keeps switch_2', sw(ex(1)) === '' && sw(ex(2)) === 'switch_2', saved.extracts)
  check('... and only switch_2 is left', JSON.stringify((saved.switches || []).map(s => s.id)) === '["switch_2"]', saved.switches)
  from = A.chatLog.length
  await click(A, LOCK, at(0, 0, 3))
  const third = A.chatLog.slice(from).find(l => l.includes('已设置电闸')) || ''
  check('the next lever is switch_3 (not a second switch_2)', third.includes('switch_3'), A.chatLog.slice(from))
  check('... and picks up the unbound extract', third.includes('绑定撤离点') && third.includes('撤离点 1'), third)
  await say(A, '/cv editor save', /草稿已保存/)
  saved = yml(mapFile(id))
  check('saved: extract_1 -> switch_3, extract_2 -> switch_2, levers switch_2 and switch_3',
    sw(ex(1)) === 'switch_3' && sw(ex(2)) === 'switch_2' &&
    JSON.stringify((saved.switches || []).map(s => s.id).sort()) === '["switch_2","switch_3"]', [saved.extracts, saved.switches])

  // ---- 4. warnings for dangling lever extracts and duplicate ids
  const warnId = `t_wn_${run}`
  fs.writeFileSync(mapFile(warnId), [
    `id: ${warnId}`, 'name: 警告测试', `template: ${id}`, 'enabled: false',
    'extracts:', '- {id: gate, type: SWITCH, switch: switch_9, x: 0, y: 65, z: 0}',
    'switches:', '- {id: dup, x: 1, y: 65, z: 1}', '- {id: dup, x: 2, y: 65, z: 2}', ''].join('\n'))
  const beforeReload = fs.statSync(LOG).size
  await rcon('cv reload')
  await sleep(1000)
  const reloadLog = logSince(beforeReload)
  check('/cv reload warns about the lever extract that needs switch_9', reloadLog.includes(`lever extract gate needs switch switch_9`),
    reloadLog.split('\n').filter(l => l.includes(warnId)))
  check('... and about the two levers sharing an id', reloadLog.includes('more than one switch has the id dup'))
  fs.rmSync(mapFile(warnId), { force: true })
  await rcon('cv reload')

  // ---- 5. a complete map publishes and plays
  const spawns = [[-3, 3], [3, 3], [-3, -3], [3, -3]]
  for (let g = 0; g < spawns.length; g++) {
    if (g > 0) await cycle(A, SPAWN, 1)
    await click(A, SPAWN, at(spawns[g][0], -1, spawns[g][1]))
  }
  await cycle(A, EXTRACT, 3) // lever extracts (3) -> always open (6 % 6)
  check('the extract tool is back on always-open extracts', A.bars.slice(-1)[0] && A.bars.slice(-1)[0].includes('常驻撤离'), A.bars.slice(-1))
  // Clear of both lever extracts (radius 3.5): a player standing in two extract areas uses the first.
  await click(A, EXTRACT, at(1, -1, 3))
  for (const [dx, dz] of [[-1, 1], [1, 1], [-1, -1], [1, -1], [-2, 1]]) await click(A, CONTAINER, at(dx, -1, dz))
  r = await say(A, '/cv editor publish', /已发布|还不能发布/, 10000)
  check('the complete map publishes', !!r.hit && r.hit.includes('已发布'), r.lines)
  check('... the editor is back in the lobby', await waitFor(() => inLobby(A), 8000), A.worldName)
  saved = yml(mapFile(id))
  check(`... maps/${id}.yml is published with 4 spawn groups and 5 containers`, saved.enabled === true &&
    new Set(saved.spawns.map(s => s.group)).size === 4 && saved.containers.length === 5, saved.enabled)
  maps = await deployMaps(B)
  check('... and B\'s deploy screen lists it', !!maps && maps.some(t => t.includes(id)), maps)
  r = await say(B, `/cv queue ${id} NORMAL SOLO`, /开始匹配|不能匹配|不存在/)
  check('B can queue for it', !!r.hit && r.hit.includes('开始匹配'), r.lines)
  const landed = await waitFor(() => inRaid(B), 60000)
  check('B lands in a raid on it', landed, B.worldName)
  if (landed) {
    await sleep(1500)
    const pos = B.entity.position
    const near = saved.spawns.some(s => Math.hypot(s.x - pos.x, s.z - pos.z) < 1.5)
    check('... on one of its spawn points', near, [pos.toString(), saved.spawns.map(s => [s.x, s.z])])
    const always = saved.extracts.find(e => e.type === 'ALWAYS')
    await rcon(`execute as ${B.username} at @s run tp @s ${always.x} ${always.y} ${always.z}`)
    check('... and extracts at the always-open extract', await waitFor(() => inLobby(B), 40000), B.worldName)
    await sleep(2000)
  }

  // ---- 6. maintenance while someone is in a published map's template
  const mntId = `t_mn_${run}`
  const mntWorld = `t_mw_${run}`
  const mntYml = enabled => [`id: ${mntId}`, 'name: 维护测试', `template: ${mntWorld}`, `enabled: ${enabled}`,
    'spawns:', ...[1, 2, 3, 4].map(g => `- {group: ${g}, x: ${g}, y: 65, z: 0}`),
    'extracts:', '- {id: out, type: ALWAYS, x: 0, y: 65, z: 5}', ''].join('\n')
  fs.writeFileSync(mapFile(mntId), mntYml(false))
  await rcon('cv reload')
  r = await say(A, `/cv editor world ${mntWorld}`, /已进入建图世界/, 20000)
  await waitFor(() => A.worldName === mntWorld, 8000)
  await sleep(1000)
  const mntPos = A.entity.position.clone()
  fs.writeFileSync(mapFile(mntId), mntYml(true)) // published by hand while A stands in its world
  await rcon('cv reload')
  maps = await deployMaps(B)
  const listed = (maps || []).find(t => t.includes('维护测试')) || ''
  check('a template with someone in it is listed as under maintenance', listed.includes('维护中'), maps)
  r = await say(B, `/cv queue ${mntId} NORMAL SOLO`, /正在维护|开始匹配/)
  check('... and cannot be queued for', !!r.hit && r.hit.includes('正在维护'), r.lines)
  await say(A, '/cv lobby', /./, 3000)
  await waitFor(() => inLobby(A), 8000)
  await sleep(500)
  r = await say(B, `/cv queue ${mntId} NORMAL SOLO`, /正在维护|开始匹配/)
  check('once the template is empty it can be queued for again', !!r.hit && r.hit.includes('开始匹配'), r.lines)
  from = B.chatLog.length
  await inWorld(mntWorld, `tp ${aName} ${mntPos.x} ${mntPos.y} ${mntPos.z}`)
  const cancelled = await waitFor(() => B.chatLog.slice(from).some(l => l.includes('已取消匹配') && l.includes('维护')), 4000)
  check('a waiting queue is cancelled when someone walks into the template', cancelled && !inRaid(B), B.chatLog.slice(from))
  await say(A, '/cv lobby', /./, 3000)
  await waitFor(() => inLobby(A), 8000)
  // An unpublished map file naming the same world must not open it for editing in place.
  const twinId = `t_tw_${run}`
  fs.writeFileSync(mapFile(twinId), [`id: ${twinId}`, 'name: 同模板', `template: ${mntWorld}`, 'enabled: false', ''].join('\n'))
  await rcon('cv reload')
  r = await say(A, `/cv editor start ${twinId}`, /开始编辑地图|已发布地图/)
  check('an unpublished map on a published map\'s template does not open in place', !!r.hit && r.hit.includes('已发布地图') && inLobby(A), r.lines)
  fs.rmSync(mapFile(twinId), { force: true })
  fs.rmSync(mapFile(mntId), { force: true })
  await rcon('cv reload')

  // ---- 7. rustport on a working copy
  r = await say(A, '/cv editor world craftov_tpl_rustport', /模板世界|已进入建图世界/)
  check('"/cv editor world" refuses the published template', !!r.hit && r.hit.includes('已发布地图') && inLobby(A), r.lines)
  const liveText = fs.readFileSync(mapFile('rustport'), 'utf8')
  const liveContainers = yml(mapFile('rustport')).containers.length
  const tplHash = treeHash(path.join(world('craftov_tpl_rustport'), 'region'))
  const COPY = 'craftov_edit_rustport'
  fs.rmSync(draftFile('rustport'), { force: true })
  r = await say(A, '/cv editor start rustport', /开始编辑地图/, 20000)
  check('"/cv editor start rustport" from the lobby takes A to the working copy', !!r.hit && await waitFor(() => A.worldName === COPY, 8000),
    [r.lines, A.worldName])
  maps = await deployMaps(B)
  const rust = (maps || []).find(t => t.includes('锈港')) || ''
  check('... rustport is not under maintenance meanwhile', !!rust && !rust.includes('维护中'), maps)
  r = await say(B, '/cv queue rustport NORMAL SOLO', /开始匹配|不能匹配/)
  check('... and B can queue for it', !!r.hit && r.hit.includes('开始匹配'), r.lines)
  await say(B, '/cv leave', /已取消匹配/)
  // A block high above the map, with a container marker on it.
  const G = [104, 120, 104]
  const C = [107, 121, 104]
  await inWorld(COPY, `setblock ${G.join(' ')} minecraft:gold_block`)
  await inWorld(COPY, `tp ${aName} ${G[0] + 0.5} ${G[1] + 1} ${G[2] + 0.5}`)
  await sleep(1500)
  await click(A, CONTAINER, new Vec3(...G))
  r = await say(A, '/cv editor exit', /草稿和编辑副本已保存|保存失败/, 10000)
  check('"/cv editor exit" saves the draft and the copy', !!r.hit && r.hit.includes('已保存'), r.lines)
  check('... A is back in the lobby', await waitFor(() => inLobby(A), 8000), A.worldName)
  const rdraft = yml(draftFile('rustport'))
  check('... the draft in maps/drafts/rustport.yml has the new container', !!rdraft && rdraft.containers.length === liveContainers + 1,
    rdraft && rdraft.containers.length)
  check('... the live rustport.yml is untouched', fs.readFileSync(mapFile('rustport'), 'utf8') === liveText)
  check('... and so are the template\'s region files', treeHash(path.join(world('craftov_tpl_rustport'), 'region')) === tplHash)
  await sleep(1500)
  const unloaded = await rcon(`execute in minecraft:${COPY} if block ${G.join(' ')} minecraft:gold_block`)
  check('... the copy is kept on disk and unloaded', fs.existsSync(world(COPY)) && !/Test (passed|failed)/.test(unloaded), unloaded)

  r = await say(A, '/cv editor start rustport', /开始编辑地图/, 20000)
  await waitFor(() => A.worldName === COPY, 8000)
  await sleep(1500)
  check('starting again continues the saved draft', A.chatLog.slice(-6).some(l => l.includes('继续编辑上次保存')), A.chatLog.slice(-6))
  check('... in the same copy (the gold block is there)', await blockIs(COPY, ...G, 'gold_block'))
  r = await say(A, '/cv editor discard', /确认放弃|已退出编辑模式/)
  check('"/cv editor discard" with a saved draft asks for a confirm first', !!r.hit && r.hit.includes('确认放弃') && A.worldName === COPY, r.lines)
  await inWorld(COPY, `setblock ${C.join(' ')} minecraft:chest`)
  r = await say(A, '/cv editor publish', /已发布|还不能发布|发布失败/, 20000)
  check('"/cv editor publish" publishes rustport', !!r.hit && r.hit.includes('已发布'), r.lines)
  check('... A is back in the lobby', await waitFor(() => inLobby(A), 8000), A.worldName)
  const published = yml(mapFile('rustport'))
  check('... rustport.yml has the new container, and the old one is kept as .prev',
    published.containers.length === liveContainers + 1 && fs.existsSync(mapFile('rustport') + '.prev'), published.containers.length)
  check('... the draft and the copy are gone, the old template kept as craftov_tpl_rustport.prev',
    !fs.existsSync(draftFile('rustport')) && !fs.existsSync(world(COPY)) && fs.existsSync(world('craftov_tpl_rustport.prev')))
  r = await say(B, '/cv queue rustport NORMAL SOLO', /开始匹配|不能匹配/)
  if (await waitFor(() => inRaid(B), 60000)) {
    await sleep(1500)
    check('B\'s next rustport raid has the gold block', await blockIs(B.worldName, ...G, 'gold_block'), B.worldName)
    await rcon('cv end')
    await waitFor(() => inLobby(B), 40000)
  } else {
    check('B lands in a rustport raid', false, [r.lines, B.worldName])
  }

  // A chest the scan tool takes out of a fresh copy is back after discarding.
  r = await say(A, '/cv editor start rustport', /开始编辑地图/, 20000)
  await waitFor(() => A.worldName === COPY, 8000)
  check('a fresh copy has the published chest', await blockIs(COPY, ...C, 'chest'))
  await inWorld(COPY, `tp ${aName} ${G[0] + 0.5} ${G[1] + 1} ${G[2] + 0.5}`)
  await sleep(1500)
  from = A.chatLog.length
  await click(A, SCAN, new Vec3(...G))
  const scanned = A.chatLog.slice(from).find(l => l.includes('转换为物资箱')) || ''
  check('the scan tool takes the chest out of the copy', /已把 [1-9]\d* 个/.test(scanned) && !(await blockIs(COPY, ...C, 'chest')), scanned)
  r = await say(A, '/cv editor discard', /已放弃|确认放弃/)
  check('"/cv editor discard" without a saved draft needs no confirm', !!r.hit && r.hit.includes('已放弃'), r.lines)
  check('... the copy is deleted', await waitFor(() => !fs.existsSync(world(COPY)), 3000))
  r = await say(A, '/cv editor start rustport', /开始编辑地图/, 20000)
  await waitFor(() => A.worldName === COPY, 8000)
  await sleep(1000)
  check('the next copy has the chest back', await blockIs(COPY, ...C, 'chest'))
  // Put rustport back: the marker off the gold block, both blocks gone, published again.
  await inWorld(COPY, `tp ${aName} ${G[0] + 0.5} ${G[1] + 1} ${G[2] + 0.5}`)
  await sleep(1500)
  await click(A, REMOVE, new Vec3(...G))
  await inWorld(COPY, `setblock ${G.join(' ')} minecraft:air`)
  await inWorld(COPY, `setblock ${C.join(' ')} minecraft:air`)
  r = await say(A, '/cv editor publish', /已发布|还不能发布|发布失败/, 20000)
  check('a last publish puts rustport back', !!r.hit && r.hit.includes('已发布') && yml(mapFile('rustport')).containers.length === liveContainers,
    r.lines)

  // ---- done
  await rcon(`deop ${aName}`)
  fs.rmSync(mapFile(id), { force: true })
  await rcon('cv reload')
  A.quit()
  B.quit()
  await sleep(1500)
  const traces = logSince(logStart).split('\n')
    .filter(l => /Exception|\tat net\.infnetwork/.test(l) && !l.includes('ConcurrentModificationException'))
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
