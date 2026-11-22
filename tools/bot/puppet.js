// A second player for screenshot scenes (tools/shots): joins, accepts the resource pack, prints
// "ready" once it has spawned and then follows one command per stdin line until stdin closes.
//
//   PORT=25565 node puppet.js <name>
//
//   slot <0-8>       select a hotbar slot
//   sneak on|off     crouch
//   use on|off       hold or release right click (aim a gun)
//   chat <text>      say something or run a /command
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

function quit () {
  bot.quit()
  setTimeout(() => process.exit(0), 500)
}

readline.createInterface({ input: process.stdin })
  .on('line', line => {
    const [cmd, ...rest] = line.trim().split(' ')
    const on = rest[0] === 'on'
    if (cmd === 'slot') bot.setQuickBarSlot(Number(rest[0]))
    else if (cmd === 'sneak') bot.setControlState('sneak', on)
    else if (cmd === 'use') on ? bot.activateItem() : bot.deactivateItem()
    else if (cmd === 'chat') bot.chat(rest.join(' '))
    else if (cmd === 'quit') quit()
  })
  .on('close', quit)
