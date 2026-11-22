// Pins gear insurance to a full, immediate refund for the bot scenarios that check exactly what an
// insurance claim sends back. Each insured item is otherwise lost with insurance.loss-chance and
// reaches the mailbox only after insurance.return-minutes.
//
//   const pinInsurance = require('./insurance')
//   const restore = await pinInsurance(path.join(SERVER_DIR, 'plugins/Craftov'), rcon)
//   try { ... } finally { await restore() }
const fs = require('fs')
const path = require('path')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/**
 * Sets insurance.loss-chance and insurance.return-minutes to 0 in <pluginDir>/config.yml and runs
 * `cv reload` (a stopped server reads them on its next start). Returns an async function that puts
 * the file back as it was and reloads it if the server is still up.
 */
module.exports = async function pinInsurance (pluginDir, rcon, log = console.log) {
  const file = path.join(pluginDir, 'config.yml')
  const before = fs.readFileSync(file, 'utf8')
  const pinned = before.replace(/^ {2}(loss-chance|return-minutes):.*\n/gm, '')
  fs.writeFileSync(file, /^insurance:\n/m.test(pinned)
    ? pinned.replace(/^insurance:\n/m, 'insurance:\n  loss-chance: 0\n  return-minutes: 0\n')
    : pinned + '\ninsurance:\n  loss-chance: 0\n  return-minutes: 0\n')
  log('insurance pinned to a full, immediate refund:', await rcon('cv reload'))
  return async () => {
    fs.writeFileSync(file, before)
    const answer = await Promise.race([rcon('cv reload'), sleep(3000).then(() => 'no answer')]).catch(() => 'server down')
    log('config.yml put back:', answer)
  }
}
