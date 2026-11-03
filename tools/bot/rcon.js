// Minimal RCON client for driving the local test server.
const net = require('net')

module.exports = function rcon (cmd, { host = '127.0.0.1', port = 25575, password = 'craftov-local' } = {}) {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port })
    let buf = Buffer.alloc(0)
    let authed = false
    const send = (id, type, body) => {
      const payload = Buffer.from(body, 'utf8')
      const pkt = Buffer.alloc(14 + payload.length)
      pkt.writeInt32LE(10 + payload.length, 0)
      pkt.writeInt32LE(id, 4)
      pkt.writeInt32LE(type, 8)
      payload.copy(pkt, 12)
      sock.write(pkt)
    }
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
          if (id === -1) return reject(new Error('rcon auth failed'))
          send(2, 2, cmd)
        } else {
          sock.end()
          resolve(body)
        }
      }
    })
    sock.on('error', reject)
  })
}
