// node shoot.js <out.png> <items> [pose]  (serves tools/preview over http and screenshots preview.html)
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = __dirname;
const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  fs.readFile(p, (err, data) => {
    if (err) { res.writeHead(404); res.end(); return; }
    const type = p.endsWith('.html') ? 'text/html' : p.endsWith('.json') ? 'application/json' : p.endsWith('.js') ? 'text/javascript' : p.endsWith('.png') ? 'image/png' : 'text/plain';
    res.writeHead(200, { 'Content-Type': type }); res.end(data);
  });
}).listen(0, async () => {
  const port = server.address().port;
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] });
  const page = await b.newPage({ viewport: { width: 1500, height: 900 } });
  page.on('console', m => console.log('console:', m.text()));
  page.on('pageerror', e => console.log('pageerror:', e.message));
  await page.goto(`http://127.0.0.1:${port}/preview.html?items=${process.argv[3] || ''}&pose=${process.argv[4] || 'stand'}`);
  await page.waitForFunction(() => document.title === 'ready', null, { timeout: 60000 });
  await page.screenshot({ path: process.argv[2] });
  await b.close(); server.close();
});
