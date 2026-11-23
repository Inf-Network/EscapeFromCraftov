// Where the bot scripts find the local test server. Every script in tools/bot takes these from the
// environment, so the same script runs against the main checkout's server or a worktree's own:
//
//   PORT           Minecraft port (MC_PORT is read too), default 25565
//   RCON_PORT      RCON port, default PORT + 10 (25575 for 25565, 27101 for 27091)
//   RCON_PASSWORD  RCON password (RCON_PASS is read too), default craftov-local
//   SERVER_DIR     the server's run folder (CRAFTOV_RUN is read too), default the repository's
//                  server/run; scripts read profiles, logs and config.yml from it
//
//   const { PORT, RCON, SERVER_DIR } = require('./env')
//   mineflayer.createBot({ host: HOST, port: PORT, ... })
//   rcon('list')  // rcon.js uses RCON unless told otherwise
const path = require('path')

const PORT = Number(process.env.PORT || process.env.MC_PORT || 25565)
const RCON_PORT = Number(process.env.RCON_PORT || PORT + 10)
const RCON_PASSWORD = process.env.RCON_PASSWORD || process.env.RCON_PASS || 'craftov-local'
const DEFAULT_SERVER_DIR = path.join(__dirname, '..', '..', 'server', 'run')
const SERVER_DIR = path.resolve(process.env.SERVER_DIR || process.env.CRAFTOV_RUN || DEFAULT_SERVER_DIR)

module.exports = {
  HOST: '127.0.0.1',
  PORT,
  RCON_PORT,
  RCON_PASSWORD,
  RCON: { port: RCON_PORT, password: RCON_PASSWORD },
  SERVER_DIR,
  /** True when SERVER_DIR (or CRAFTOV_RUN) was given; some scripts only touch a folder named on purpose. */
  SERVER_DIR_SET: !!(process.env.SERVER_DIR || process.env.CRAFTOV_RUN),
  /** plugins/Craftov of SERVER_DIR (CRAFTOV_DATA overrides it). */
  DATA: path.resolve(process.env.CRAFTOV_DATA || path.join(SERVER_DIR, 'plugins', 'Craftov')),
  /** The server's log (SERVER_LOG overrides it). */
  LOG: path.resolve(process.env.SERVER_LOG || path.join(SERVER_DIR, 'logs', 'latest.log'))
}
