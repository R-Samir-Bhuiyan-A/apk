const WebSocket = require('ws');

const wss = new WebSocket.Server({ port: 6967 });

let phoneSocket = null;
const viewers = new Set();

wss.on('connection', (ws, req) => {
  // Differentiate between the phone and the web viewers
  const clientType = req.headers['user-agent'];

  if (clientType && clientType.startsWith('ScreenShareAndroid')) {
    console.log('Phone connected.');
    phoneSocket = ws;

    // When the phone sends data, forward it to all viewers
    ws.on('message', message => {
      viewers.forEach(viewer => {
        if (viewer.readyState === WebSocket.OPEN) {
          viewer.send(message);
        }
      });
    });

    ws.on('close', () => {
      console.log('Phone disconnected.');
      phoneSocket = null;
    });

  } else {
    console.log('Viewer connected.');
    viewers.add(ws);

    ws.on('close', () => {
      console.log('Viewer disconnected.');
      viewers.delete(ws);
    });
  }
});

console.log('WebSocket server started on port 6967');
