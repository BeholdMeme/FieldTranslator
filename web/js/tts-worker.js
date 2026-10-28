/*
 * Voice worker: the Russian read-aloud voice (Piper "irina" through sherpa-onnx WebAssembly).
 * Same settings as core/.../Speaker.java. Separate from speech recognition so each has its own memory.
 */
'use strict';
importScripts('store.js', '../vendor/sherpa-onnx-wasm-web.js', '../vendor/sherpa-onnx-tts.js');

let Module = null;
let tts = null;

function post(msg, transfer) { self.postMessage(msg, transfer || []); }

async function ensureModule() {
  if (Module) return Module;
  Module = await SherpaOnnx({ locateFile: p => '../vendor/' + p, print: () => {}, printErr: () => {} });
  return Module;
}

async function writeFiles(dir, base, files, keepAfterLoad) {
  const FS = Module.FS;
  const written = [];
  for (const f of files) {
    const target = dir + '/' + f.name;
    FS.mkdirTree(target.slice(0, target.lastIndexOf('/')));
    FS.writeFile(target, await FTStore.getFile(base + f.path, false));
    if (!keepAfterLoad(f.name)) written.push(target);
  }
  return written;
}

async function loadTts(base, pack) {
  await ensureModule();
  if (tts) return;
  const dir = '/tts-ru';
  const tmp = await writeFiles(dir, base, pack.files, name => name.indexOf('espeak-ng-data') === 0);
  const vits = {
    model: dir + '/model.onnx', lexicon: '', tokens: dir + '/tokens.txt', dataDir: dir + '/espeak-ng-data',
    noiseScale: 0.667, noiseScaleW: 0.8, lengthScale: 1.0,
  };
  const cfg = {
    offlineTtsModelConfig: {
      offlineTtsVitsModelConfig: vits,
      offlineTtsMatchaModelConfig: { acousticModel: '', vocoder: '', lexicon: '', tokens: '', dataDir: '', noiseScale: 0.667, lengthScale: 1.0 },
      offlineTtsKokoroModelConfig: { model: '', voices: '', tokens: '', dataDir: '', lengthScale: 1.0, lexicon: '', lang: '' },
      offlineTtsKittenModelConfig: { model: '', voices: '', tokens: '', dataDir: '', lengthScale: 1.0 },
      offlineTtsZipVoiceModelConfig: { tokens: '', encoder: '', decoder: '', vocoder: '', dataDir: '', lexicon: '', featScale: 0.1, tShift: 0.5, targetRMS: 0.1, guidanceScale: 1.0 },
      offlineTtsPocketModelConfig: { lmFlow: '', lmMain: '', encoder: '', decoder: '', textConditioner: '', vocabJson: '', tokenScoresJson: '', voiceEmbeddingCacheCapacity: 50 },
      numThreads: 1,
      debug: 0,
      provider: 'cpu',
    },
    ruleFsts: '',
    ruleFars: '',
    maxNumSentences: 1,
  };
  tts = createOfflineTts(Module, cfg);
  for (const f of tmp) { try { Module.FS.unlink(f); } catch (e) { /* ignore */ } }
}

let queue = Promise.resolve();
self.onmessage = (e) => {
  const m = e.data;
  queue = queue.then(() => handle(m)).catch(err => post({ type: 'error', cmd: m.cmd, id: m.id, message: String(err && err.message || err) }));
};

async function handle(m) {
  if (m.cmd !== 'speak') throw new Error('unknown command ' + m.cmd);
  await loadTts(m.base, m.pack);
  const a = tts.generate({ text: m.text, sid: 0, speed: 0.85 });
  post({ type: 'speech', id: m.id, samples: a.samples, rate: a.sampleRate }, [a.samples.buffer]);
}
