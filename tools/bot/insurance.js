// Pins gear insurance to a full, immediate refund for the bot scenarios that check exactly what an
// insurance claim sends back. Each insured item is otherwise lost with insurance.loss-chance and
// reaches the mailbox only after insurance.return-minutes.
//
//   const pinInsurance = require('./insurance')
//   const restore = await pinInsurance(path.join(SERVER_DIR, 'plugins/Craftov'), rcon)
//   try { ... } finally { await restore() }
const path = require('path')
const config = require('./config')

/**
 * Sets insurance.loss-chance and insurance.return-minutes to 0 in <pluginDir>/config.yml and runs
 * `cv reload` (a stopped server reads them on its next start). Returns an async function that puts
 * the file back as it was and reloads it if the server is still up.
 */
module.exports = function pinInsurance (pluginDir, rcon, log = console.log) {
  return config.pin({ 'insurance.loss-chance': 0, 'insurance.return-minutes': 0 },
    { file: path.join(pluginDir, 'config.yml'), rcon, log })
}
