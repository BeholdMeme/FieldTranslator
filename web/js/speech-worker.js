/*
 * Speech worker: streaming speech recognition (Russian + English Zipformer models) through
 * sherpa-onnx compiled to WebAssembly. Same settings as core/.../StreamingAsr.java.
 * (The read-aloud voice lives in tts-worker.js so each has its own memory.)
 */
'use strict';
importScripts('store.js', '../vendor/sherpa-onnx-wasm-web.js', '../vendor/sherpa-onnx-asr.js');

let Module = null;
let recognizer = null, recognizerLang = null, stream = null, lastPartial = '';

function post(msg, transfer) { self.postMessage(msg, transfer || []); }

async function ensureModule() {
  if (Module) return Module;
  const m = await SherpaOnnx({
    locateFile: p => '../vendor/' + p,
    print: () => {},
    printErr: () => {},
  });
  Module = m;
  return m;
}

async function writeFiles(dir, base, files, keepAfterLoad) {
  const FS = Module.FS;
  const written = [];
  for (const f of files) {
    const target = dir + '/' + f.name;
    const parent = target.slice(0, target.lastIndexOf('/'));
    FS.mkdirTree(parent);
    const data = await FTStore.getFile(base + f.path, false);
    FS.writeFile(target, data);
    if (!keepAfterLoad(f.name)) written.push(target);
  }
  return written;   // files that can be deleted once the model is in memory
}

function clean(t) {
  t = (t || '').trim().replace(/\s+/g, ' ');
  if (t && t === t.toUpperCase() && t !== t.toLowerCase()) t = t.toLocaleLowerCase();
  return t;
}

async function loadAsr(lang, base, pack) {
  await ensureModule();
  if (recognizerLang === lang && recognizer) return;
  if (stream) { stream.free(); stream = null; }
  if (recognizer) { recognizer.free(); recognizer = null; recognizerLang = null; }
  const dir = '/asr-' + lang;
  const tmp = await writeFiles(dir, base, pack.files, () => false);
  const cfg = {
    featConfig: { sampleRate: 16000, featureDim: 80 },
    modelConfig: {
      transducer: { encoder: dir + '/encoder.int8.onnx', decoder: dir + '/decoder.int8.onnx', joiner: dir + '/joiner.int8.onnx' },
      paraformer: { encoder: '', decoder: '' },
      zipformer2Ctc: { model: '' },
      nemoCtc: { model: '' },
      toneCtc: { model: '' },
      tokens: dir + '/tokens.txt',
      numThreads: 1,
      provider: 'cpu',
      debug: 0,
      modelType: '',
      modelingUnit: 'cjkchar',
      bpeVocab: '',
    },
    decodingMethod: 'modified_beam_search',
    maxActivePaths: 4,
    enableEndpoint: 1,
    rule1MinTrailingSilence: 2.4,
    rule2MinTrailingSilence: 0.8,
    rule3MinUtteranceLength: 15,
    hotwordsFile: '',
    hotwordsScore: 1.5,
    ctcFstDecoderConfig: { graph: '', maxActive: 3000 },
    ruleFsts: '',
    ruleFars: '',
  };
  recognizer = createOnlineRecognizer(Module, cfg);
  if (!recognizer || !recognizer.handle) throw new Error('could not load the ' + lang + ' speech model');
  for (const f of tmp) { try { Module.FS.unlink(f); } catch (e) { /* ignore */ } }
  recognizerLang = lang;
}

function drain(last) {
  while (recognizer.isReady(stream)) recognizer.decode(stream);
  const text = clean(recognizer.getResult(stream).text);
  if (last || recognizer.isEndpoint(stream)) {
    if (text) post({ type: 'final', text });
    if (!last) recognizer.reset(stream);
    lastPartial = '';
  } else if (text !== lastPartial) {
    lastPartial = text;
    post({ type: 'partial', text });
  }
}

let queue = Promise.resolve();
self.onmessage = (e) => {
  const m = e.data;
  // run messages strictly in order
  queue = queue.then(() => handle(m)).catch(err => post({ type: 'error', cmd: m.cmd, id: m.id, message: String(err && err.message || err) }));
};

async function handle(m) {
  switch (m.cmd) {
    case 'loadAsr':
      await loadAsr(m.lang, m.base, m.pack);
      post({ type: 'asrReady', lang: m.lang, id: m.id });
      break;
    case 'start':
      if (!recognizer) throw new Error('speech model not loaded');
      if (stream) stream.free();
      stream = recognizer.createStream();
      lastPartial = '';
      break;
    case 'audio':
      if (!stream) return;
      stream.acceptWaveform(m.rate, m.samples);
      drain(false);
      break;
    case 'finish':
      if (stream) {
        stream.acceptWaveform(m.rate, new Float32Array(Math.floor(m.rate * 0.6)));
        stream.inputFinished();
        drain(true);
        stream.free();
        stream = null;
      }
      post({ type: 'finished', id: m.id });
      break;
    case 'transcribe': {   // whole buffer at once (used by the self-test)
      await loadAsr(m.lang, m.base, m.pack);
      const s = recognizer.createStream();
      const finals = [];
      const step = Math.floor(m.rate / 10);
      let lp = '';
      for (let i = 0; i < m.samples.length; i += step) {
        s.acceptWaveform(m.rate, m.samples.subarray(i, i + step));
        while (recognizer.isReady(s)) recognizer.decode(s);
        if (recognizer.isEndpoint(s)) {
          const t = clean(recognizer.getResult(s).text);
          if (t) finals.push(t);
          recognizer.reset(s);
        }
      }
      s.acceptWaveform(m.rate, new Float32Array(Math.floor(m.rate * 0.6)));
      s.inputFinished();
      while (recognizer.isReady(s)) recognizer.decode(s);
      const t = clean(recognizer.getResult(s).text);
      if (t) finals.push(t);
      s.free();
      post({ type: 'transcript', id: m.id, text: finals.join(' | ') });
      break;
    }
    default:
      throw new Error('unknown command ' + m.cmd);
  }
}
