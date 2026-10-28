/*
 * Translation worker: Opus-MT through onnxruntime-web (same algorithm as the phone/laptop apps).
 * Only one direction is loaded at a time.
 */
'use strict';
importScripts('store.js', 'core.js', '../vendor/ort.wasm.min.js');
ort.env.wasm.numThreads = 1;              // GitHub Pages can't enable multi-threaded WASM
ort.env.wasm.wasmPaths = '../vendor/';

let tr = null, trPair = null;

async function load(pair, base, pack) {
  if (trPair === pair && tr) return;
  if (tr) await tr.release();   // free the other direction's memory first
  tr = null; trPair = null;
  const byName = {};
  for (const f of pack.files) byName[f.name] = base + f.path;
  const [props, spm, vocab, encoder, decoder] = await Promise.all([
    FTStore.getFile(byName['model.properties'], true),
    FTStore.getFile(byName['spm_source.tsv'], true),
    FTStore.getFile(byName['vocab.txt'], true),
    FTStore.getFile(byName['encoder.onnx'], false),
    FTStore.getFile(byName['decoder.onnx'], false),
  ]);
  tr = await FT.MarianTranslator.create(ort, { props, spm, vocab, encoder, decoder });
  await tr.translate('ok');   // warm-up
  trPair = pair;
}

let queue = Promise.resolve();
self.onmessage = (e) => {
  const m = e.data;
  queue = queue.then(() => handle(m)).catch(err =>
    self.postMessage({ type: 'error', cmd: m.cmd, id: m.id, message: String(err && err.message || err) }));
};

async function handle(m) {
  if (m.cmd === 'load') {
    await load(m.pair, m.base, m.pack);
    self.postMessage({ type: 'loaded', pair: m.pair, id: m.id });
  } else if (m.cmd === 'translate') {
    if (trPair !== m.pair || !tr) { self.postMessage({ type: 'translation', id: m.id, text: null }); return; }
    const text = await tr.translate(m.text);
    self.postMessage({ type: 'translation', id: m.id, text });
  }
}
