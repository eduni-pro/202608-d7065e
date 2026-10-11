// Screenshot one URL through the DevTools Protocol. Node's built-in WebSocket, no dependencies.
// usage: node shot.js <url> <out.png> <width> <height> <waitMs>
const [, , url, out, wArg, hArg, waitArg] = process.argv;
const width = Number(wArg || 1600), height = Number(hArg || 1000);
const waitMs = Number(waitArg || 6000);
const PORT = process.env.CDP_PORT || '9222';
const fs = require('node:fs');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  // A fresh target per shot, so one slow page cannot poison the next.
  const created = await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' });
  const target = await created.json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const events = new Map();
  ws.addEventListener('message', (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
    else if (msg.method && events.has(msg.method)) { events.get(msg.method)(); events.delete(msg.method); }
  });
  const send = (method, params = {}) => new Promise((res) => {
    const n = ++id; pending.set(n, res); ws.send(JSON.stringify({ id: n, method, params }));
  });
  const once = (method) => new Promise((res) => events.set(method, res));

  await new Promise((res, rej) => {
    ws.addEventListener('open', res); ws.addEventListener('error', rej);
  });
  await send('Emulation.setDeviceMetricsOverride',
    { width, height, deviceScaleFactor: 2, mobile: false });
  await send('Page.enable');
  const loaded = once('Page.loadEventFired');
  await send('Page.navigate', { url });
  await Promise.race([loaded, sleep(20000)]);
  await send('Runtime.enable');
  // The 3D viewer paints its scene long after the load event, behind a splash overlay that
  // reports its own progress. Polling the overlay away is the only honest ready signal; a fixed
  // delay either truncates the render or pads every shot with the worst case.
  const probe = "(()=>{const t=(document.body&&document.body.innerText)||'';"
    + "return /ASSEMBLING|PREPARING|LOADING|BUILDING THE|STOREYS/i.test(t)?'splash':'ready';})()";
  let ready = false;
  for (let i = 0; i < 120; i++) {
    const r = await send('Runtime.evaluate', { expression: probe, returnByValue: true });
    if (r.result && r.result.result && r.result.result.value === 'ready') { ready = true; break; }
    await sleep(1000);
  }
  console.error(ready ? 'splash cleared' : 'splash never cleared (shooting anyway)');
  await sleep(waitMs);
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  if (!shot.result || !shot.result.data) throw new Error('no screenshot data: ' + JSON.stringify(shot));
  fs.writeFileSync(out, Buffer.from(shot.result.data, 'base64'));
  ws.close();
  await fetch(`http://127.0.0.1:${PORT}/json/close/${target.id}`);
  console.log(`${out} ${fs.statSync(out).size} bytes`);
}
main().then(() => process.exit(0)).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
