// A second player for screenshot scenes (tools/shots): joins, accepts the resource pack, prints
// "ready" once it has spawned and then follows one command per stdin line until stdin closes.
//
//   PORT=25565 node puppet.js <name>
//
//   slot <0-8>          select a hotbar slot
//   sneak on|off        crouch
//   use on|off          hold or release right click: a gun aims (the use repeats every 4 ticks, as a
//                       client holding the button over an item without a use does)
//   hold on|off         press right click once and keep it down, or let it up (a grenade's pin)
//   drop                the drop key (Q): reloads a gun
//   revive <name> on|off  hold right click on that player (a downed squadmate), or let go
//   slide [ms]          sprint forward along its view for ms (1000), then tap sneak twice (100 ms
//                       down, 100 up, down again): a slide; the keys go up 1.6 s later
//   chat <text>         say something or run a /command
//   click <slot>        left-click a slot of the open menu (e.g. post an escort contract)
//   close               close the open menu
//   quit
const readline = require('readline')
const mineflayer = require('mineflayer')

const name = process.argv[2] || 'Puppet' + Math.floor(Math.random() * 9000)
const port = Number(process.env.PORT || 25565)
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: '1.21.11', auth: 'offline' })

bot._client.on('add_resource_pack', p => { for (const r of [3, 4, 0]) bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r }) })
bot.once('spawn', () => console.log('ready'))
bot.on('kicked', reason => { console.log('kicked', JSON.stringify(reason)); process.exit(1) })
bot.on('error', e => console.log('error', e.message))

let aiming = null
let reviving = null

/** The keys held, as one player_input packet (mineflayer's own sends shift alone). */
function keys (k = {}) {
  bot._client.write('player_input', { inputs: { forward: !!k.forward, backward: false, left: false, right: false, jump: false, shift: !!k.shift, sprint: !!k.sprint } })
}

/** The sprint command (mineflayer's own sends an action id 1.21.11 reads as something else). */
function sprinting (on) {
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 'start_sprinting' : 'stop_sprinting', jumpBoost: 0 })
}

function slide (ms) {
  bot.setControlState('forward', true)
  bot.setControlState('sprint', true)
  sprinting(true)
  keys({ forward: true, sprint: true })
  setTimeout(() => keys({ forward: true, sprint: true, shift: true }), ms)
  setTimeout(() => keys({ forward: true, sprint: true }), ms + 100)
  setTimeout(() => keys({ forward: true, sprint: true, shift: true }), ms + 200)
  setTimeout(() => { bot.clearControlStates(); keys({}); sprinting(false) }, ms + 1600)
}

function stop (timer) {
  if (timer) clearInterval(timer)
  return null
}

function quit () {
  aiming = stop(aiming)
  reviving = stop(reviving)
  bot.quit()
  setTimeout(() => process.exit(0), 500)
}

readline.createInterface({ input: process.stdin })
  .on('line', line => {
    const [cmd, ...rest] = line.trim().split(' ')
    const on = rest[rest.length - 1] === 'on'
    if (cmd === 'slot') bot.setQuickBarSlot(Number(rest[0]))
    else if (cmd === 'sneak') bot.setControlState('sneak', on)
    else if (cmd === 'use') {
      aiming = stop(aiming)
      if (on) {
        bot.activateItem()
        aiming = setInterval(() => bot.activateItem(), 200)
      } else bot.deactivateItem()
    } else if (cmd === 'hold') on ? bot.activateItem() : bot.deactivateItem()
    else if (cmd === 'drop') bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    else if (cmd === 'revive') {
      reviving = stop(reviving)
      const target = bot.players[rest[0]] && bot.players[rest[0]].entity
      if (on && target) {
        bot.lookAt(target.position.offset(0, 0.3, 0), true)
        bot.activateEntity(target)
        reviving = setInterval(() => bot.activateEntity(target), 200)
      } else if (on) console.log('revive: no', rest[0], 'in sight')
    } else if (cmd === 'slide') slide(Number(rest[0] || 1000))
    else if (cmd === 'chat') bot.chat(rest.join(' '))
    else if (cmd === 'click' && bot.currentWindow) bot.clickWindow(Number(rest[0]), 0, 0).catch(e => console.log('error', e.message))
    else if (cmd === 'close' && bot.currentWindow) bot.closeWindow(bot.currentWindow)
    else if (cmd === 'quit') quit()
  })
  .on('close', quit)
