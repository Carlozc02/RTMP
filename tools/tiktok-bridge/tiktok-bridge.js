// Puente de chat de TikTok LIVE -> app RTMP Streamer
//
// Uso (en una PC o servidor con Node 18+):
//   cd tools/tiktok-bridge
//   npm install
//   node tiktok-bridge.js tio_carlo_zc 8787
//
// En la app, en la lista de chats, agrega:   ws://IP_DE_TU_PC:8787
// (el teléfono y la PC deben estar en la misma red, o abre el puerto en tu servidor).
//
// Opcional: SESSION_ID=tu_sessionid node tiktok-bridge.js ...   (si faltan mensajes)
//
// OJO: usa la librería no oficial tiktok-live-connector; si TikTok cambia su
// protocolo puede dejar de funcionar hasta que la librería se actualice.

const { WebSocketServer } = require('ws');
const lib = require('tiktok-live-connector');

const user = (process.argv[2] || '').replace(/^@/, '');
const port = Number(process.argv[3] || 8787);
if (!user) {
  console.error('Uso: node tiktok-bridge.js USUARIO [PUERTO]');
  process.exit(1);
}

const wss = new WebSocketServer({ port });
const broadcast = (obj) => {
  const msg = JSON.stringify(obj);
  wss.clients.forEach((c) => c.readyState === 1 && c.send(msg));
};

const Connection = lib.TikTokLiveConnection || lib.WebcastPushConnection;
const Events = lib.WebcastEvent || {};

function start() {
  const opts = process.env.SESSION_ID ? { sessionId: process.env.SESSION_ID } : {};
  const conn = new Connection(user, opts);

  conn.on(Events.CHAT || 'chat', (d) => {
    const name = d.user?.uniqueId ?? d.uniqueId ?? d.user?.nickname ?? d.nickname ?? '?';
    const text = d.comment ?? d.text ?? '';
    if (text) {
      console.log(`${name}: ${text}`);
      broadcast({ user: name, text });
    }
  });
  conn.on(Events.DISCONNECTED || 'disconnected', () => {
    console.log('Desconectado, reintentando en 10 s...');
    setTimeout(start, 10000);
  });

  conn.connect()
    .then(() => console.log(`Conectado al live de @${user}. Esperando mensajes...`))
    .catch((e) => {
      console.error(`No se pudo conectar (${e.message || e}). ¿Estás en vivo? Reintento en 15 s...`);
      setTimeout(start, 15000);
    });
}

console.log(`Puente escuchando en ws://0.0.0.0:${port}`);
start();
