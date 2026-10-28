// End-to-end check of the built web app in real browser engines (Chromium = Android/desktop,
// WebKit = the engine every iPhone browser uses).
// usage: node smoke.js <site_dir> <chromium|webkit> <ru_test.wav>
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium, webkit } = require('playwright');

const [site, engine, wav] = process.argv.slice(2);
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.wasm': 'application/wasm', '.json': 'application/json', '.png': 'image/png', '.wav': 'audio/wav',
  '.webmanifest': 'application/manifest+json' };

function serve() {
  return new Promise(resolve => {
    const srv = http.createServer((req, res) => {
      let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      if (p.endsWith('/')) p += 'index.html';
      const f = path.join(site, p);
      if (!f.startsWith(path.resolve(site)) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream', 'Content-Length': fs.statSync(f).size });
      fs.createReadStream(f).pipe(res);
    });
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

const fail = msg => { console.log('::error::[' + engine + '] ' + msg); process.exitCode = 1; };
const t0 = Date.now();
const log = m => console.log(`[${engine} ${((Date.now() - t0) / 1000).toFixed(0)}s] ${m}`);

async function main() {
  const srv = await serve();
  const url = `http://127.0.0.1:${srv.address().port}/`;
  const args = engine === 'chromium'
    ? ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--use-file-for-fake-audio-capture=' + wav,
      '--autoplay-policy=no-user-gesture-required']
    : [];
  const browser = await (engine === 'chromium' ? chromium : webkit).launch({ args });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2,
    permissions: engine === 'chromium' ? ['microphone'] : [] });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.setDefaultTimeout(60000);

  await page.goto(url);
  await page.waitForSelector('.setup .big-btn');
  await page.screenshot({ path: `shot-${engine}-setup.png` });
  log('setup screen ok; downloading models');
  await page.click('.setup .big-btn');
  await page.waitForSelector('.listen:not([disabled])', { timeout: 15 * 60000 });
  log('models downloaded and loaded: ' + await page.textContent('.status'));
  await page.screenshot({ path: `shot-${engine}-translator.png` });

  const r = await page.evaluate(() => window.FTSelfTest());
  log('self-test: ' + JSON.stringify(r));
  if (!/ПРИ|при/i.test(r.pron || '')) fail('pronunciation guide: ' + r.pron);
  if (r.number !== 'две тысячи двадцать пять') fail('number words: ' + r.number);
  if (!/station/i.test(r.translation || '')) fail('translation: ' + r.translation);
  if (!r.transcript || r.transcript.length < 5) fail('Russian speech recognition returned nothing');
  if (!(r.voiceSeconds > 0.3)) fail('voice produced no audio');

  // live microphone (Chromium can feed a file in as the mic)
  if (engine === 'chromium') {
    // the self-test switched the recogniser to English; switch direction to reload Russian
    await page.click('.seg .en'); await page.waitForSelector('.listen:not([disabled])', { timeout: 120000 });
    await page.click('.seg .ru'); await page.waitForSelector('.listen:not([disabled])', { timeout: 120000 });
    await page.click('.listen');
    try {
      await page.waitForSelector('.card .dst:not(:text("…"))', { timeout: 90000 });
      log('live mic -> card: ' + (await page.textContent('.card .src')) + ' => ' + (await page.textContent('.card .dst')));
    } catch (e) { fail('live microphone produced no translation: ' + await page.textContent('.status')); }
    await page.click('.listen');
    await page.screenshot({ path: `shot-${engine}-live.png` });
  }

  // Learn section
  await page.click('.chips button:has-text("Learn")');
  await page.click('button:has-text("say any number")');
  await page.fill('.saynum input', '20000');
  const words = await page.textContent('.saynum .w');
  if (words !== 'двадцать тысяч') fail('say-any-number: ' + words);
  await page.fill('.saynum input', '1000000');
  if ((await page.textContent('.saynum .w')) !== 'миллион') fail('million: ' + await page.textContent('.saynum .w'));
  await page.screenshot({ path: `shot-${engine}-numbers.png`, fullPage: false });
  await page.click('button:has-text("numbers quiz")');
  await page.click('.learn .btn >> nth=1');
  await page.waitForSelector('.feedback:not(:empty)');
  await page.click('button:has-text("Back")'); await page.click('button:has-text("Back")');
  await page.click('button:has-text("Colors ·")');
  if ((await page.$$('.colrow')).length < 10) fail('colors list');
  await page.screenshot({ path: `shot-${engine}-colors.png` });
  await page.click('button:has-text("Back")');
  await page.click('button:has-text("Common phrases")');
  if ((await page.$$('.lcard')).length < 40) fail('phrases list');
  await page.click('button:has-text("Back")');
  await page.click('.grid button >> nth=0');
  await page.click('.row2 .accent');
  await page.screenshot({ path: `shot-${engine}-letter.png` });
  log('learn screens ok');

  // offline: reload with no network - the app must still start from its caches
  await context.setOffline(true);
  await page.reload();
  await page.waitForSelector('.listen:not([disabled])', { timeout: 180000 });
  log('offline reload ok: ' + await page.textContent('.status'));
  await context.setOffline(false);

  const bad = errors.filter(e => !/favicon|Failed to load resource.*404/.test(e) && !/net::ERR_INTERNET_DISCONNECTED/.test(e));
  if (bad.length) fail('console errors: ' + bad.slice(0, 5).join(' | '));
  await browser.close();
  srv.close();
  log(process.exitCode ? 'FAILED' : 'PASSED');
}
main().catch(e => { fail(e.stack || String(e)); process.exit(1); });
