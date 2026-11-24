// Minimal RCON client for driving the local test server: the port and password come from env.js
// (RCON_PORT, RCON_PASSWORD) unless the caller passes its own.
//
// It never hangs: a connection the server closes before it answers (a "stop" often gets no reply,
// a server going down drops it) rejects at once, and so does one that stays silent for `timeout`
// ms (30 s by default; RCON_TIMEOUT in seconds overrides it).
const net = require('net')
const env = require('./env')

const TIMEOUT = Number(process.env.RCON_TIMEOUT || 30) * 1000

module.exports = function rcon (cmd, { host = env.HOST, port = env.RCON_PORT, password = env.RCON_PASSWORD, timeout = TIMEOUT } = {}) {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port })
    let buf = Buffer.alloc(0)
    let authed = false
    let settled = false
    const settle = (fn, value) => {
      if (settled) return
      settled = true
      sock.destroy()
      fn(value)
    }
    const send = (id, type, body) => {
      const payload = Buffer.from(body, 'utf8')
      const pkt = Buffer.alloc(14 + payload.length)
      pkt.writeInt32LE(10 + payload.length, 0)
      pkt.writeInt32LE(id, 4)
      pkt.writeInt32LE(type, 8)
      payload.copy(pkt, 12)
      sock.write(pkt)
    }
    sock.setTimeout(timeout, () => settle(reject, new Error(`rcon: no answer to "${cmd}" in ${timeout / 1000} s`)))
    sock.on('connect', () => send(1, 3, password))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < len + 4) return
        const id = buf.readInt32LE(4)
        const body = buf.slice(12, len + 2).toString('utf8')
        buf = buf.slice(len + 4)
        if (!authed) {
          authed = true
          if (id === -1) return settle(reject, new Error('rcon auth failed'))
          send(2, 2, cmd)
        } else {
          return settle(resolve, body)
        }
      }
    })
    sock.on('error', e => settle(reject, e))
    sock.on('close', () => settle(reject, new Error(`rcon: the connection closed before an answer to "${cmd}"`)))
  })
}
