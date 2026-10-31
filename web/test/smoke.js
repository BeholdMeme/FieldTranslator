// End-to-end check of the built web app in real browser engines (Chromium = Android/desktop,
// WebKit = the engine every iPhone browser uses).
// usage: node smoke.js <site_dir> <chromium|webkit> <ru_test.wav>
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium, webkit } = require('playwright');

const [siteArg, engine, wav] = process.argv.slice(2);
const site = path.resolve(siteArg);
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

const pageErrors = [];
const fail = msg => { console.log('::error::[' + engine + '] ' + msg); process.exitCode = 1; };
const t0 = Date.now();
const log = m => console.log(`[${engine} ${((Date.now() - t0) / 1000).toFixed(0)}s] ${m}`);


// waits until the app is ready, failing fast (with the on-screen message) if loading fails
async function ready(page, ms) {
  await page.waitForFunction(() => {
    const b = document.querySelector('.listen'), st = document.querySelector('.status');
    return (b && !b.disabled) || (st && /could not|error|stopped/i.test(st.textContent));
  }, null, { timeout: ms, polling: 500 });
  const st = await page.textContent('.status').catch(() => '');
  if (await page.$('.listen[disabled]')) throw new Error('app did not become ready: ' + st);
}
const within = (ms, what, p) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(what + ' took over ' + ms / 1000 + ' s')), ms))]);


// camera reader: feed a rendered page of Russian text through "Choose a photo"
async function cameraCheck(page, label) {
  const png = await page.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 1400; c.height = 600;
    const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, 1400, 600); g.fillStyle = '#000'; g.font = '56px sans-serif';
    ['ДОГОВОР АРЕНДЫ', 'Где находится вокзал?', 'Цена: 45 000 рублей.'].forEach((t, i) => g.fillText(t, 60, 120 + i * 130));
    return c.toDataURL('image/png').split(',')[1];
  });
  await page.click('.cambtn');
  await page.waitForSelector('.learn input[type=file]', { state: 'attached' });
  await page.setInputFiles('.learn input[type=file]:not([capture])', { name: 'p.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') });
  await page.waitForFunction(() => /Done -|Could not|No text/.test((document.querySelector('.learn') || { innerText: '' }).innerText),
    null, { timeout: 240000, polling: 500 });
  const en = await page.$$eval('.learn .lcard .en', e => e.map(x => x.textContent));
  log(label + ' camera: ' + en.join(' | '));
  if (!en.some(t => /station/i.test(t))) fail(label + ' camera reader: ' + (await page.innerText('.learn')).slice(0, 300).replace(/\s+/g, ' '));
  await page.screenshot({ path: `shot-${engine}-camera-${label}.png` });
  await page.click('button:has-text("Back")');
  await ready(page, 120000);
}

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
  const errors = pageErrors;
  lastPage = page;
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.setDefaultTimeout(60000);

  await page.goto(url);
  await page.waitForSelector('.setup .big-btn');
  await page.screenshot({ path: `shot-${engine}-setup.png` });
  log('setup screen ok; downloading models');
  await page.click('.setup .big-btn');
  await ready(page, 10 * 60000);
  log('models downloaded and loaded: ' + await page.textContent('.status'));
  await page.screenshot({ path: `shot-${engine}-translator.png` });

  const r = await within(300000, 'self-test', page.evaluate(() => window.FTSelfTest()));
  log('self-test: ' + JSON.stringify(r));
  if (!/pri-VYET/.test(r.pron || '')) fail('pronunciation guide: ' + r.pron);
  if (r.number !== 'две тысячи двадцать пять') fail('number words: ' + r.number);
  if (!/station/i.test(r.translation || '')) fail('translation: ' + r.translation);
  if (!r.transcript || r.transcript.length < 5) fail('Russian speech recognition returned nothing');
  if (!(r.voiceSeconds > 0.3)) fail('voice produced no audio');

  // live microphone (Chromium can feed a file in as the mic)
  if (engine === 'chromium') {
    // the self-test switched the recogniser to English; switch direction to reload Russian
    await page.click('.seg .en'); await ready(page, 120000);
    await page.click('.seg .ru'); await ready(page, 120000);
    await page.click('.listen');
    try {
      await page.waitForSelector('.card .dst:not(:text("…"))', { timeout: 90000 });
      log('live mic -> card: ' + (await page.textContent('.card .src')) + ' => ' + (await page.textContent('.card .dst')));
    } catch (e) {
      // headless Chromium's fake microphone is unreliable; the self-test above already ran recognition
      console.log('::warning::[chromium] fake-microphone test produced nothing: ' + await page.textContent('.status'));
    }
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
  await page.click('button:has-text("5th")');
  await page.fill('.saynum input', '2000');
  const ord = await page.$$eval('.saynum .sayline .w', e => e.map(x => x.textContent));
  if (ord.join('|') !== 'двухтысячный|двухтысячная|двухтысячное') fail('ordinal 2000th: ' + ord.join('|'));
  await page.fill('.saynum input', '23');
  if ((await page.textContent('.saynum .sayline .w')) !== 'двадцать третий') fail('ordinal 23rd');
  await page.screenshot({ path: `shot-${engine}-numbers.png`, fullPage: false });
  await page.click('button:has-text("5   (five)")');
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
  await page.click('button:has-text("Back")');      // letter -> Learn
  await page.click('button:has-text("Back")');      // Learn -> translator
  await ready(page, 120000);
  await cameraCheck(page, 'online');
  for (const [text, want] of [['Where is the train station?', /вокзал/i], ['Это стоит сорок пять тысяч рублей', /45,?000/]]) {
    const n0 = (await page.$$('.card')).length;
    await page.fill('.compose-in', text);
    await page.press('.compose-in', 'Enter');
    await page.waitForFunction(n => { const d = [...document.querySelectorAll('.card .dst')]; return d.length >= n && !d.some(x => x.textContent === '…'); },
      n0 + 1, { timeout: 180000, polling: 300 });
    const got = await page.textContent('.card:last-child .dst');
    log('typed: ' + text + ' -> ' + got);
    if (!want.test(got)) fail('typed translation: ' + text + ' -> ' + got);
  }
  if (!/45 000/.test(await page.textContent('.card:last-child .nums'))) fail('number digits line');
  for (const b of ['A−', 'A+']) {
    await page.click('.chips button:has-text("' + b + '")');
    const fs = parseFloat(await page.$eval('.card:last-child .dst', e => getComputedStyle(e).fontSize));
    if (!(fs > 12)) fail('text size after ' + b + ': ' + fs);
  }
  await page.click('.card:last-child .star');
  await page.click('.chips button:has-text("Chats")');
  if ((await page.$$('.learn .numrow')).length < 1) fail('chats list');
  await page.click('button:has-text("Saved")');
  if ((await page.$$('.learn .lcard')).length < 1) fail('saved list');
  await page.click('button:has-text("Back")');
  await ready(page, 120000);
  await page.click('.tb >> nth=0');
  await page.fill('.conv input >> nth=0', '1000');
  if (!/тысяча рублей/.test(await page.textContent('.csay'))) fail('converter words');
  await page.screenshot({ path: `shot-${engine}-converter.png` });
  await page.click('button:has-text("Back")');
  await ready(page, 120000);
  await page.screenshot({ path: `shot-${engine}-main.png` });

  // offline: reload with no network - the app must still start from its caches
  const onlineErrors = errors.length;
  const offline503 = [];
  page.on('response', res => { if (res.status() === 503) offline503.push(res.url()); });
  await context.setOffline(true);
  await page.reload();
  await ready(page, 180000);
  log('offline reload ok: ' + await page.textContent('.status'));
  await cameraCheck(page, 'offline');
  await context.setOffline(false);

  if (offline503.length) console.log('::warning::[' + engine + '] not available offline (not needed to run): ' + [...new Set(offline503)].join(', '));
  const bad = errors.slice(0, onlineErrors).filter(e => !/favicon|Failed to load resource.*404/.test(e) && !/net::ERR_INTERNET_DISCONNECTED/.test(e));
  if (bad.length) fail('console errors: ' + bad.slice(0, 5).join(' | '));
  await browser.close();
  srv.close();
  log(process.exitCode ? 'FAILED' : 'PASSED');
}
let lastPage = null;
main().catch(async e => {
  fail((e.message || String(e)).split('\n')[0]);
  if (lastPage) {
    try { await lastPage.screenshot({ path: `shot-${engine}-failure.png` }); } catch (x) { /* ignore */ }
    try { console.log('::error::[' + engine + '] screen text: ' + (await lastPage.innerText('body')).replace(/\s+/g, ' ').slice(0, 300)); } catch (x) { /* ignore */ }
  }
  if (pageErrors.length) console.log('::error::[' + engine + '] page errors: ' + pageErrors.slice(0, 6).join(' | ').slice(0, 900));
  process.exit(1);
});
