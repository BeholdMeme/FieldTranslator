/*
 * Service worker: keeps the app itself (pages, code, engines) available offline.
 * Model files are stored separately by js/store.js in the 'ft-models-v1' cache.
 * VERSION is replaced by the build so each release refreshes the app shell.
 */
'use strict';
const VERSION = '__BUILD_VERSION__';
const SHELL = 'ft-shell-' + VERSION;
const MODELS = 'ft-models-v1';
const FILES = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'js/app.js', 'js/core.js', 'js/store.js', 'js/speech-worker.js', 'js/tts-worker.js', 'js/mt-worker.js',
  'vendor/sherpa-onnx-wasm-web.js', 'vendor/sherpa-onnx-wasm-web.wasm',
  'vendor/sherpa-onnx-asr.js', 'vendor/sherpa-onnx-tts.js',
  'vendor/ort.wasm.min.js', 'vendor/ort-wasm-simd-threaded.mjs', 'vendor/ort-wasm-simd-threaded.wasm',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES.map(f => new Request(f, { cache: 'reload' }))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys
    .filter(k => k.startsWith('ft-shell-') && k !== SHELL).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  // the model list is fetched fresh when online (so updates are seen), cached copy otherwise
  if (url.pathname.endsWith('/models/manifest.json')) {
    e.respondWith((async () => {
      const shell = await caches.open(SHELL);
      try {
        const res = await fetch(req);
        if (res.ok) await shell.put('models/manifest.json', res.clone());
        return res;
      } catch (err) {
        return (await shell.match('models/manifest.json')) || new Response('offline', { status: 503 });
      }
    })());
    return;
  }
  e.respondWith((async () => {
    const shell = await caches.open(SHELL);
    const hit = await shell.match(req, { ignoreSearch: true });
    if (hit) return hit;
    const model = await (await caches.open(MODELS)).match(req);
    if (model) return model;
    if (req.mode === 'navigate') {
      const index = await shell.match('index.html');
      if (index) return index;
    }
    try { return await fetch(req); } catch (err) { return new Response('offline', { status: 503 }); }
  })());
});
