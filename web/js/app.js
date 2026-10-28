/*
 * Field Translator - web app (works on iPhone, Android, laptops). Fully offline after the
 * one-time download. Same features as the Android app: live translator, pronunciation,
 * read-aloud and the whole Learn section.
 */
(function () {
  'use strict';

  // ------------------------------------------------------------------ helpers
  const $app = document.getElementById('app');
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style') el.setAttribute('style', v);
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'html') el.innerHTML = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid === null || kid === undefined || kid === false) continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }
  const store = {
    get(k, d) { try { const v = localStorage.getItem('ft.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('ft.' + k, JSON.stringify(v)); } catch (e) { /* private mode */ } },
  };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const MB = b => (b / 1048576).toFixed(0) + ' MB';
  const rnd = n => Math.floor(Math.random() * n);
  function shuffle(a) { for (let i = a.length - 1; i > 0; i--) { const j = rnd(i + 1); [a[i], a[j]] = [a[j], a[i]]; } return a; }

  const APP_VERSION = '__BUILD_VERSION__';
  const BASE = new URL('models/', location.href).href;   // absolute: workers live in js/
  let manifest = null, learn = null, pronouncer = null;

  // ------------------------------------------------------------------ workers (simple request/response)
  function makeWorker(url, onEvent) {
    const w = new Worker(url);
    let seq = 0;
    const waiting = new Map();
    w.onmessage = e => {
      const m = e.data;
      if (m.id !== undefined && waiting.has(m.id)) {
        const { resolve, reject } = waiting.get(m.id);
        waiting.delete(m.id);
        if (m.type === 'error') reject(new Error(m.message)); else resolve(m);
      } else if (m.type === 'error') {
        onEvent && onEvent(m);
      } else onEvent && onEvent(m);
    };
    const api = { dead: false };
    w.onerror = e => {
      const msg = e.message || 'worker failed to start';
      api.dead = true;
      // a crashed worker never answers: fail everything that was waiting instead of hanging
      for (const { reject } of waiting.values()) reject(new Error(msg));
      waiting.clear();
      onEvent && onEvent({ type: 'error', message: msg });
    };
    api.call = (msg, transfer) => {
      const id = ++seq;
      return new Promise((resolve, reject) => { waiting.set(id, { resolve, reject }); w.postMessage({ ...msg, id }, transfer || []); });
    };
    api.send = (msg, transfer) => w.postMessage(msg, transfer || []);
    api.terminate = () => {
      api.dead = true;
      try { w.terminate(); } catch (e) { /* ignore */ }
      for (const { reject } of waiting.values()) reject(new Error('stopped'));
      waiting.clear();
    };
    return api;
  }

  // Three workers, each with its own memory: speech recognition, translation, and the voice.
  // iPhones kill a page's worker when memory runs short; a dead worker is simply recreated.
  let speech = null, mt = null, tts = null, speechLang = null;
  function speechWorker() {
    if (!speech || speech.dead) { speech = makeWorker('js/speech-worker.js', onSpeechEvent); speechLang = null; }
    return speech;
  }
  async function speechFor(lang) {
    // a fresh worker per language: the old model's memory is returned to the phone, not reused
    if (speech && !speech.dead && speechLang && speechLang !== lang) { speech.terminate(); speech = null; }
    const w = speechWorker();
    await w.call({ cmd: 'loadAsr', lang, base: BASE, pack: manifest.packs['asr-' + lang] });
    speechLang = lang;
    return w;
  }
  let mtPair = null;
  async function mtFor(pair) {
    // a fresh worker per direction so the other direction's memory is really given back
    if (mt && !mt.dead && mtPair && mtPair !== pair) { mt.terminate(); mt = null; }
    if (!mt || mt.dead) mtPair = null;
    await mtWorker().call({ cmd: 'load', pair, base: BASE, pack: manifest.packs['mt-' + pair] });
    mtPair = pair;
  }
  function mtWorker() {
    if (!mt || mt.dead) mt = makeWorker('js/mt-worker.js', e => { if (e.type === 'error') setStatus('Translation error: ' + e.message); });
    return mt;
  }
  function ttsWorker() {
    if (!tts || tts.dead) tts = makeWorker('js/tts-worker.js', null);
    return tts;
  }

  // ------------------------------------------------------------------ audio out (read aloud)
  // Playback and microphone use separate audio contexts. On iPhone, a context that has been used
  // with the mic stays routed to the quiet earpiece (and goes 'interrupted' after the app is
  // backgrounded), so the voice gets a fresh context whenever the old one may be stuck.
  const AC = window.AudioContext || window.webkitAudioContext;
  let outCtx = null, outStale = false;
  function playCtx() {
    if (outCtx && (outStale || outCtx.state === 'closed' || outCtx.state === 'interrupted')) {
      try { outCtx.close(); } catch (e) { /* already closed */ }
      outCtx = null;
    }
    outStale = false;
    if (!outCtx) outCtx = new AC();
    if (outCtx.state !== 'running') { try { outCtx.resume(); } catch (e) { /* ignore */ } }
    return outCtx;
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) outStale = true; });
  window.addEventListener('pageshow', () => { outStale = true; });
  function audioSession(type) {
    try { if (navigator.audioSession) navigator.audioSession.type = type; } catch (e) { /* not supported */ }
  }
  // The voice plays through a normal <audio> element (like music): on iPhone that goes to the
  // loudspeaker and keeps working in silent mode. Web Audio is only a fallback.
  function wavBlob(samples, rate) {
    const n = samples.length, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
    const str = (o, t) => { for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); };
    str(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    str(36, 'data'); v.setUint32(40, n * 2, true);
    for (let i = 0; i < n; i++) { const x = Math.max(-1, Math.min(1, samples[i])); v.setInt16(44 + i * 2, x < 0 ? x * 32768 : x * 32767, true); }
    return new Blob([buf], { type: 'audio/wav' });
  }
  const player = new Audio();
  player.preload = 'auto';
  player.setAttribute('playsinline', '');
  const SILENCE = URL.createObjectURL(wavBlob(new Float32Array(2205), 22050));
  let playerUrl = null;
  function unlockPlayer() {           // must run inside the tap so later play() calls are allowed
    try {
      if (playerUrl) { URL.revokeObjectURL(playerUrl); playerUrl = null; }
      player.src = SILENCE;
      const p = player.play();
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* ignore */ }
  }
  let speakSeq = 0, currentSource = null;
  async function say(text) {
    if (!text || !text.trim()) return;
    const mine = ++speakSeq;
    if (!state.listening) audioSession('playback');
    unlockPlayer();
    let c = null;
    try { c = playCtx(); } catch (e) { c = null; }   // fallback path, also unlocked by this tap
    try {
      const speak = () => ttsWorker().call({ cmd: 'speak', text, base: BASE, pack: manifest.packs['tts-ru'] });
      let r;
      try { r = await speak(); } catch (e) { if (!tts || !tts.dead) throw e; r = await speak(); }   // restarted engine
      if (mine !== speakSeq) return;
      try {
        playerUrl = URL.createObjectURL(wavBlob(r.samples, r.rate));
        player.src = playerUrl;
        await player.play();
        return;
      } catch (e) {
        if (!c) throw e;                // no fallback available
      }
      if (c.state !== 'running') { try { await c.resume(); } catch (e) { /* ignore */ } }
      const buf = c.createBuffer(1, r.samples.length, r.rate);
      buf.copyToChannel(r.samples, 0);
      if (currentSource) { try { currentSource.stop(); } catch (e) { /* ended */ } }
      const src = c.createBufferSource();
      src.buffer = buf;
      src.connect(c.destination);
      src.start();
      currentSource = src;
    } catch (e) {
      toast('Voice unavailable: ' + (e && e.message || e), 8000);
    }
  }
  function toast(msg, ms) {
    const t = h('div', { style: 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#20252E;' +
      'border:1px solid #2A303A;padding:10px 16px;border-radius:14px;z-index:9;max-width:90%;text-align:center' }, msg);
    document.body.append(t);
    setTimeout(() => t.remove(), ms || 3500);
  }

  // ------------------------------------------------------------------ start-up
  async function boot() {
    if ('serviceWorker' in navigator) {
      // when an update arrives, switch to it right away (unless the mic is in use)
      const hadController = !!navigator.serviceWorker.controller;
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        if (hadController && !state.listening) location.reload();
      });
      try { await navigator.serviceWorker.register('sw.js'); } catch (e) { /* file:// or blocked */ }
    }
    try {
      manifest = await (await fetch(BASE + 'manifest.json', { cache: 'no-cache' })).json();
      store.set('manifest', manifest);
    } catch (e) {
      manifest = store.get('manifest', null);           // offline: use the saved copy
    }
    if (!manifest) {
      render(h('div', { class: 'setup' }, h('h2', {}, 'Field Translator'),
        h('div', { class: 'box' }, h('p', {}, 'Could not load the app files. Connect to the internet once to set it up.'))));
      return;
    }
    if (await packsReady(Object.keys(manifest.packs))) { await loadLearn(); showTranslator(); }
    else showSetup();
  }

  async function packsReady(names) {
    for (const n of names) {
      for (const f of manifest.packs[n].files) if (!(await FTStore.isCached(BASE + f.path))) return false;
    }
    return true;
  }

  async function loadLearn() {
    if (learn) return;
    const pack = manifest.packs.learn;
    const byName = {};
    for (const f of pack.files) byName[f.name] = BASE + f.path;
    learn = JSON.parse(await FTStore.getFile(byName['learn.json'], true));
    const stress = await FTStore.getFile(byName['stress.tsv'], false);
    pronouncer = new FT.Pronouncer(new FT.StressDict(stress), learn.pron_exceptions);
  }

  function render(...nodes) {
    $app.replaceChildren(...nodes);
  }

  // ------------------------------------------------------------------ setup / download screen
  function isIOS() { return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1); }
  function isStandalone() { return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true; }

  function showSetup() {
    const total = Object.values(manifest.packs).reduce((a, p) => a + p.files.reduce((b, f) => b + f.size, 0), 0);
    const bar = h('i'), info = h('p', {}, ''), btn = h('button', { class: 'big-btn green' }, 'Download for offline use (' + MB(total) + ')');
    const installHelp = isIOS() && !isStandalone()
      ? h('div', { class: 'box' }, h('b', {}, 'Put it on your Home Screen first'),
        h('ol', { class: 'steps' }, h('li', {}, 'Tap the Share button (square with an arrow) in Safari.'),
          h('li', {}, 'Choose “Add to Home Screen”, then Add.'),
          h('li', {}, 'Open Translator from your Home Screen and download from there.')),
        h('p', {}, 'The Home Screen app keeps its offline files; plain Safari tabs may lose them.'))
      : null;
    btn.onclick = async () => {
      btn.disabled = true;
      try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch (e) { /* ignore */ }
      const files = [];
      for (const name of ['learn', 'tts-ru', 'asr-ru', 'asr-en', 'mt-ru-en', 'mt-en-ru']) files.push(...manifest.packs[name].files);
      try {
        await FTStore.downloadAll(BASE, files, (d, t) => {
          bar.style.width = (100 * d / t).toFixed(1) + '%';
          info.textContent = MB(d) + ' of ' + MB(t) + ' - keep this screen open';
        });
        info.textContent = 'Done! Everything now works without internet.';
        await loadLearn();
        await sleep(600);
        showTranslator();
      } catch (e) {
        info.textContent = 'Download stopped: ' + e.message + '. Tap to continue where it left off.';
        btn.disabled = false;
        btn.textContent = 'Continue download';
      }
    };
    render(h('div', { class: 'setup' },
      h('div', { class: 'header' }, h('h1', {}, 'Field Translator'), h('span', { class: 'chip-offline' }, '● Offline')),
      h('div', { class: 'box' }, h('h2', {}, 'One-time setup'),
        h('p', {}, 'Downloads the speech, translation and voice models so the app works with no internet at all - ' +
          'airplane mode, abroad, anywhere.'),
        h('p', {}, 'Use Wi-Fi. It only happens once; updates download just what changed.')),
      installHelp,
      h('div', { class: 'box' }, btn, h('div', { class: 'bar' }, bar), info)));
  }

  // ------------------------------------------------------------------ translator screen
  const DIRS = {
    'ru-en': { asr: 'asr-ru', mt: 'mt-ru-en', color: 'var(--ru)', hint: 'speak Russian', lang: 'ru' },
    'en-ru': { asr: 'asr-en', mt: 'mt-en-ru', color: 'var(--en)', hint: 'speak English', lang: 'en' },
  };
  const state = {
    pair: store.get('pair', 'ru-en'), pron: store.get('pron', true), scale: store.get('scale', 1),
    ready: false, listening: false, loadingFor: null, pending: 0, lastPreview: 0, lastPreviewSrc: '',
  };
  let ui = null;   // translator screen elements
  const entries = [];   // conversation entries kept across screens

  function setStatus(t) { if (ui) ui.status.textContent = t; }

  function showTranslator() {
    document.documentElement.style.setProperty('--scale', state.scale);
    const segRu = h('button', { class: 'ru', onclick: () => switchPair('ru-en') }, h('b', {}, 'Русский'), h('span', {}, '→  English'));
    const segEn = h('button', { class: 'en', onclick: () => switchPair('en-ru') }, h('b', {}, 'English'), h('span', {}, '→  Русский'));
    const convo = h('div', { class: 'convo' });
    const empty = h('div', { class: 'empty' }, 'Pick a direction, tap Listen,\nand start talking.\n\nTranslations appear here.');
    convo.append(empty);
    for (const e of entries) convo.append(e.el);
    const partial = h('div', { class: 'partial hidden' }), preview = h('div', { class: 'preview hidden' });
    const status = h('div', { class: 'status' }, '');
    const diag = h('div', { class: 'diag hidden' }, '');
    const levelI = h('i');
    const pronBtn = h('button', { class: 'w2', onclick: togglePron });
    const listen = h('button', { class: 'listen', onclick: () => (state.listening ? stopListening() : startListening()) }, 'Loading…');
    ui = { segRu, segEn, convo, empty, partial, preview, status, diag, levelI, pronBtn, listen };
    render(
      h('div', { class: 'header' }, h('h1', {}, 'Field Translator'), h('span', { class: 'chip-offline' }, '● Offline')),
      h('div', { class: 'seg' }, segRu, segEn),
      convo,
      h('div', { class: 'live' }, partial, preview),
      h('div', { class: 'panel' }, status, diag, h('div', { class: 'level' }, levelI),
        h('div', { class: 'chips' }, pronBtn,
          h('button', { onclick: () => { if (state.listening) stopListening(); showLearnHome(); } }, 'Learn'),
          h('button', { onclick: () => setScale(state.scale / 1.15) }, 'A−'),
          h('button', { onclick: () => setScale(state.scale * 1.15) }, 'A+'),
          h('button', { onclick: clearConvo }, 'Clear')),
        listen));
    updatePron();
    refresh();
    if (!state.ready || state.loadedPair !== state.pair) loadPair(state.pair);
    else setStatus('Ready. Tap Listen and ' + DIRS[state.pair].hint + '.');
    convo.scrollTop = convo.scrollHeight;
  }

  function refresh() {
    if (!ui) return;
    const ru = state.pair === 'ru-en';
    ui.segRu.classList.toggle('on', ru);
    ui.segEn.classList.toggle('on', !ru);
    ui.empty.classList.toggle('hidden', entries.length > 0);
    ui.listen.disabled = !state.ready;
    ui.listen.classList.toggle('stop', state.listening);
    ui.listen.textContent = state.listening ? '■   Stop' : (state.ready ? '●   Listen' : 'Loading…');
  }

  async function loadPair(pair) {
    state.ready = false;
    state.loadingFor = pair;
    refresh();
    setStatus('Loading ' + (pair === 'ru-en' ? 'Russian' : 'English') + ' models… (a few seconds)');
    const d = DIRS[pair];
    try {
      await Promise.all([
        speechFor(d.lang),
        mtFor(pair),
      ]);
      if (state.loadingFor !== pair) return;      // switched again meanwhile
      state.ready = true;
      state.loadedPair = pair;
      setStatus('Ready. Tap Listen and ' + d.hint + '.');
      refresh();
      if (state.resume) { state.resume = false; startListening(); }
    } catch (e) {
      setStatus('Could not load models: ' + e.message);
    }
  }

  async function switchPair(pair) {
    if (pair === state.pair) return;
    const was = state.listening;
    if (was) await stopListening();
    state.pair = pair;
    store.set('pair', pair);
    state.resume = was;
    loadPair(pair);
  }

  // --- microphone
  let mic = null;
  // Mic samples -> onChunk(Float32Array ~100 ms). AudioWorklet loaded from code inside this file
  // (no separate download, nothing for the offline cache to get wrong); ScriptProcessor as fallback.
  const WORKLET = `class MicTap extends AudioWorkletProcessor {
    constructor() { super(); this.buf = new Float32Array(Math.round(sampleRate / 10)); this.n = 0; }
    process(inputs) {
      const ch = inputs[0] && inputs[0][0];
      if (ch) for (let i = 0; i < ch.length; i++) {
        this.buf[this.n++] = ch[i];
        if (this.n === this.buf.length) { this.port.postMessage(this.buf.slice(0)); this.n = 0; }
      }
      return true;
    }
  }
  registerProcessor('mic-tap', MicTap);`;
  const workletLoaded = new WeakSet();
  async function micTap(c, source, sink, onChunk) {
    if (c.audioWorklet && window.AudioWorkletNode) {
      try {
        if (!workletLoaded.has(c)) {
          const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }));
          try { await Promise.race([c.audioWorklet.addModule(url), sleep(4000).then(() => { throw new Error('worklet timeout'); })]); }
          finally { URL.revokeObjectURL(url); }
          workletLoaded.add(c);
        }
        const node = new AudioWorkletNode(c, 'mic-tap');
        node.port.onmessage = e => onChunk(e.data);
        source.connect(node); node.connect(sink);
        return node;
      } catch (e) { /* fall through to the older method */ }
    }
    const size = 4096;
    const node = c.createScriptProcessor(size, 1, 1);
    node.onaudioprocess = e => onChunk(new Float32Array(e.inputBuffer.getChannelData(0)));
    source.connect(node); node.connect(sink);
    return node;
  }

  async function startListening() {
    if (!state.ready || state.listening) return;
    const lang = DIRS[state.pair].lang;
    if (!speech || speech.dead || speechLang !== lang) {      // engine was shut down by the phone: bring it back
      state.ready = false; refresh();
      setStatus('Restarting speech engine…');
      try { await speechFor(lang); } catch (e) { setStatus('Speech engine failed: ' + e.message); return; }
      finally { state.ready = true; refresh(); }
    }
    audioSession('play-and-record');
    setStatus('Starting microphone…');
    // an engine started right now, inside the tap, is always allowed to run on iPhone: keep it as a backup
    let backup = null;
    try { backup = new AC(); backup.resume(); } catch (e) { backup = null; }
    let c = null, stream = null;
    try {
      // iPhone: open the mic first, then create the audio engine, so the engine runs at the mic's
      // current sample rate (an engine made earlier can receive pure silence from the mic)
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      c = new AC();
      if (c.state !== 'running') {
        try { await Promise.race([c.resume(), sleep(1500)]); } catch (e) { /* checked below */ }
      }
      if (c.state !== 'running' && backup && backup.state === 'running') { try { c.close(); } catch (e) { /* ignore */ } c = backup; backup = null; }
      if (backup) { try { backup.close(); } catch (e) { /* ignore */ } backup = null; }
      const source = c.createMediaStreamSource(stream);
      const sink = c.createGain();
      sink.gain.value = 0;                      // keep the graph running without playing the mic back
      sink.connect(c.destination);
      mic = { ctx: c, stream, source, sink, node: null, started: Date.now(), peak: 0, warned: false, pair: state.pair, chunks: 0 };
      speechWorker().send({ cmd: 'start' });
      const rate = c.sampleRate;
      const onChunk = samples => {
        mic.chunks++;
        let p = 0;
        for (let i = 0; i < samples.length; i++) { const a = Math.abs(samples[i]); if (a > p) p = a; }
        if (ui) ui.levelI.style.width = Math.min(100, p * 400) + '%';
        if (p > mic.peak) mic.peak = p;
        if (!mic.warned && Date.now() - mic.started > 2500 && mic.peak < 0.001) {
          mic.warned = true;
          setStatus('The microphone is sending silence. Check the mic permission for this app in Settings.');
        }
        speechWorker().send({ cmd: 'audio', samples, rate }, [samples.buffer]);
      };
      mic.node = await micTap(c, source, sink, onChunk);
      const me = mic;
      const track = stream.getAudioTracks()[0];
      me.events = [];
      if (track) {
        track.addEventListener('ended', () => onMicLost(me, 'ended'));
        track.addEventListener('mute', () => { me.events.push('muted'); });
        track.addEventListener('unmute', () => { me.events.push('unmuted'); });
      }
      c.addEventListener('statechange', () => me.events.push('audio ' + c.state));
      me.diagTimer = setInterval(() => showDiag(me), 500);
      setTimeout(() => {
        if (mic !== me || !state.listening || me.chunks > 0) return;
        // nothing at all arrived: tell the user exactly what the phone reports
        const tr = me.stream.getAudioTracks()[0];
        setStatus('No sound is reaching the app (audio ' + me.ctx.state + ', ' + me.ctx.sampleRate + ' Hz, mic ' +
          (tr ? tr.readyState + (tr.muted ? ' muted' : '') : 'none') + '). Tap Stop, then Listen again.');
      }, 3000);
      state.listening = true;
      setStatus('Listening… ' + DIRS[state.pair].hint);
      refresh();
    } catch (e) {
      setStatus('Microphone error: ' + (e.name === 'NotAllowedError' ? 'permission denied - allow the microphone in Settings' : (e.name || '') + ' ' + e.message));
      try { if (stream) stream.getTracks().forEach(t => t.stop()); } catch (x) { /* ignore */ }
      try { if (c) c.close(); } catch (x) { /* ignore */ }
      try { if (backup) backup.close(); } catch (x) { /* ignore */ }
      audioSession('playback');
    }
  }

  // live readings shown while listening (helps find phone-specific problems)
  function showDiag(m) {
    if (!ui || mic !== m || !state.listening) { if (ui) ui.diag.classList.add('hidden'); clearInterval(m.diagTimer); return; }
    const tr = m.stream.getAudioTracks()[0];
    let session = '';
    try { session = navigator.audioSession ? navigator.audioSession.type : 'n/a'; } catch (e) { session = '?'; }
    ui.diag.textContent = 'mic ' + (tr ? tr.readyState + (tr.muted ? ' (muted)' : '') : 'none') +
      ' · audio ' + m.ctx.state + ' ' + m.ctx.sampleRate + ' Hz · ' + (m.node && m.node.port ? 'worklet' : 'processor') +
      ' · chunks ' + m.chunks + ' · peak ' + m.peak.toFixed(3) + ' · session ' + session +
      (m.events.length ? ' · ' + m.events.slice(-3).join(', ') : '') + ' · v' + APP_VERSION;
    ui.diag.classList.remove('hidden');
  }

  async function onMicLost(m, why) {
    if (mic !== m || !state.listening) return;
    m.events.push('mic ' + why);
    // iPhone switched the mic off by itself: restart it once automatically
    if (!m.restarted) {
      m.restarted = true;
      setStatus('iPhone turned the microphone off - restarting it…');
      await stopListening();
      mic && (mic.restarted = true);
      await startListening();
      if (mic) mic.restarted = true;
    } else {
      await stopListening();
      setStatus('iPhone keeps turning the microphone off (' + why + '). Tap Listen to try again.');
    }
  }

  async function stopListening() {
    if (!state.listening || !mic) return;
    state.listening = false;
    clearInterval(mic.diagTimer);
    if (ui) ui.diag.classList.add('hidden');
    try {
      if (mic.node) { if (mic.node.port) mic.node.port.onmessage = null; else mic.node.onaudioprocess = null; mic.node.disconnect(); }
      mic.source.disconnect(); mic.sink.disconnect();
      mic.stream.getTracks().forEach(t => t.stop());
    } catch (e) { /* already stopped */ }
    const rate = mic.ctx.sampleRate;
    try { mic.ctx.close(); } catch (e) { /* ignore */ }
    audioSession('playback');
    outStale = true;                     // next voice playback gets a context routed to the speaker
    try { await speech.call({ cmd: 'finish', rate }); } catch (e) { /* engine gone; nothing left to finish */ }
    if (ui) { ui.levelI.style.width = '0'; setStatus('Stopped. Tap Listen to continue.'); }
    refresh();
  }

  function onSpeechEvent(m) {
    if (m.type === 'partial') {
      if (!ui || !state.listening) return;
      ui.partial.textContent = m.text;
      ui.partial.classList.toggle('hidden', !m.text);
      maybePreview(m.text);
    } else if (m.type === 'final') {
      finalText(mic ? mic.pair : state.pair, m.text);
    } else if (m.type === 'error') {
      if (speech && speech.dead) {
        if (state.listening) stopListening();
        setStatus('The phone stopped the speech engine (low memory). Tap Listen to restart it.');
      } else setStatus('Speech error: ' + m.message);
    }
  }

  async function translate(pair, text) {
    state.pending++;
    try {
      if ((!mt || mt.dead || mtPair !== pair) && pair === state.pair) await mtFor(pair);   // restarted by the phone
      const r = await mtWorker().call({ cmd: 'translate', pair, text });
      return r.text;
    } catch (e) {
      if (e.message === 'stopped') return null;      // direction switched while translating
      throw e;
    } finally { state.pending--; }
  }

  function maybePreview(text) {
    const words = text ? text.split(' ').length : 0;
    const now = Date.now();
    if (words < 4 || state.pending > 0 || now - state.lastPreview < 1500 || text === state.lastPreviewSrc) return;
    state.lastPreview = now;
    state.lastPreviewSrc = text;
    const pair = state.pair;
    translate(pair, text).then(res => {
      if (!ui || res === null || pair !== state.pair || ui.partial.classList.contains('hidden')) return;
      ui.preview.textContent = res;
      ui.preview.classList.remove('hidden');
    }).catch(() => {});
  }

  function finalText(pair, text) {
    if (ui) {
      ui.partial.textContent = ''; ui.partial.classList.add('hidden');
      ui.preview.textContent = ''; ui.preview.classList.add('hidden');
    }
    state.lastPreviewSrc = '';
    if (!text.trim()) return;
    const e = addEntry(pair, text);
    if (pair === 'ru-en') setRussian(e, text);
    translate(pair, text).then(res => {
      e.dst.textContent = res === null ? '(switched language before this was translated)' : (res || '—');
      if (pair === 'en-ru' && res) setRussian(e, res);
    }).catch(err => { e.dst.textContent = '[translation error: ' + err.message + ']'; });
  }

  function addEntry(pair, original) {
    const src = h('div', { class: 'src' }, original);
    const dst = h('div', { class: 'dst' }, '…');
    const pron = h('div', { class: 'pron hidden' });
    const spk = h('button', { class: 'spk', style: 'visibility:hidden', 'aria-label': 'Hear it' }, '🔊');
    const el = h('div', { class: 'card' }, h('div', { class: 'stripe', style: 'background:' + DIRS[pair].color }),
      h('div', { class: 'body' }, src, dst, pron), spk);
    const e = { el, src, dst, pron, spk, russian: '' };
    entries.push(e);
    while (entries.length > 150) entries.shift().el.remove();
    if (ui) { ui.convo.append(el); ui.convo.scrollTop = ui.convo.scrollHeight; refresh(); }
    return e;
  }

  function setRussian(e, ru) {
    e.russian = ru;
    try { e.pron.textContent = pronouncer ? pronouncer.pronounce(ru) : ''; } catch (err) { /* ignore */ }
    e.pron.classList.toggle('hidden', !state.pron || !e.pron.textContent);
    e.spk.style.visibility = 'visible';
    e.spk.onclick = () => say(ru);
  }

  function togglePron() {
    state.pron = !state.pron;
    store.set('pron', state.pron);
    updatePron();
  }
  function updatePron() {
    if (!ui) return;
    ui.pronBtn.textContent = state.pron ? 'Pronounce ✓' : 'Pronounce';
    ui.pronBtn.classList.toggle('on', state.pron);
    for (const e of entries) e.pron.classList.toggle('hidden', !state.pron || !e.pron.textContent);
  }
  function setScale(s) {
    state.scale = Math.max(0.7, Math.min(2.5, s));
    store.set('scale', state.scale);
    document.documentElement.style.setProperty('--scale', state.scale);
  }
  function clearConvo() {
    entries.splice(0).forEach(e => e.el.remove());
    refresh();
  }

  // ------------------------------------------------------------------ learn section
  const best = (k, v) => { const all = store.get('best', {}); if (v === undefined) return all[k]; all[k] = v; store.set('best', all); };

  function learnPage(onBack, ...content) {
    const back = h('button', { class: 'btn center', style: 'width:auto;display:inline-block;padding:10px 16px', onclick: onBack }, '←  Back');
    render(h('div', { class: 'learn', id: 'learn' }, back, ...content));
    document.getElementById('learn').scrollTop = 0;
  }
  const hearBtn = text => h('button', { class: 'btn hear', onclick: () => say(text) }, '🔊  Hear it');
  const lcard = (label, body) => h('div', { class: 'lcard' }, h('div', { class: 'lbl' }, label), h('div', { style: 'font-size:20px;white-space:pre-line' }, body));

  function showLearnHome() {
    const grid = h('div', { class: 'grid' }, learn.alphabet.map((l, i) =>
      h('button', { onclick: () => showLetter(i) }, l.upper + l.lower)));
    const lessons = learn.lessons.map((les, i) => {
      const b = best('lesson' + i);
      const done = (b || 0) >= 8;
      return h('button', { class: 'btn' + (done ? ' good' : ''), onclick: () => showLesson(i) },
        (i + 1) + '. ' + les.title, h('span', { class: 'sub' }, les.letters.join(' ') + '   ·   ' +
          (b === undefined ? 'not started' : 'best quiz score ' + b + '/10' + (done ? '  ✓' : ''))));
    });
    const listBtn = (key, title) => learn[key] ? h('button', { class: 'btn', onclick: () => showList(key) }, title + '   ·   ' + learn[key].length) : null;
    learnPage(showTranslator,
      h('h2', {}, 'Learn to read Russian'),
      h('div', { class: 'muted' }, '33 letters. Tap a letter to see and hear it, then work through the lessons in order.'),
      h('h3', {}, 'Alphabet'), grid,
      h('h3', {}, 'Reading lessons'), lessons,
      h('h3', {}, 'Numbers & colors'),
      learn.numbers ? h('button', { class: 'btn', onclick: showNumbers }, 'Numbers   ·   say any number') : null,
      learn.colors ? h('button', { class: 'btn', onclick: showColors }, 'Colors   ·   ' + learn.colors.items.length) : null,
      h('h3', {}, 'Phrases & verses'),
      listBtn('phrases', 'Common phrases'), listBtn('verses', 'Bible verses'), listBtn('military', 'Military phrases'),
      h('h3', {}, 'Pronunciation tips'),
      learn.tips.map(t => h('div', { style: 'margin:6px 0' }, '• ' + t)),
      h('div', { class: 'muted small', style: 'margin-top:14px' }, learn.source),
      h('div', { class: 'muted small', style: 'margin-top:6px' }, 'App version ' + APP_VERSION));
  }

  function showLetter(i) {
    const abc = learn.alphabet, l = abc[i], n = abc.length;
    const prev = (i + n - 1) % n, next = (i + 1) % n;
    learnPage(showLearnHome,
      h('div', { class: 'muted' }, 'Letter ' + (i + 1) + ' of ' + n),
      h('div', { class: 'bigletter' }, l.upper + ' ' + l.lower),
      hearBtn(l.say || l.example),
      lcard('Name', l.name), lcard('Sound', l.sound),
      lcard('Example', l.example + '\n' + l.example_pron + '\n' + l.example_meaning),
      h('div', { class: 'row2' },
        h('button', { class: 'btn center', onclick: () => showLetter(prev) }, '←  ' + abc[prev].upper),
        h('button', { class: 'btn center accent', onclick: () => showLetter(next) }, abc[next].upper + '  →')));
  }

  const letterObj = u => learn.alphabet.find(x => x.upper === u);

  function showLesson(i) {
    const les = learn.lessons[i];
    learnPage(showLearnHome,
      h('h2', {}, (i + 1) + '. ' + les.title),
      h('div', {}, les.intro),
      h('h3', {}, 'New letters'),
      les.letters.map(u => { const l = letterObj(u); return l ? lcard(l.upper + ' ' + l.lower, l.sound) : null; }),
      h('button', { class: 'btn center accent', onclick: () => flashcards(les.words.map(w => ({ ru: w.word, pron: w.pron, en: w.meaning })), () => showLesson(i)) }, 'Practice words (flashcards)'),
      h('button', { class: 'btn center good', onclick: () => lessonQuiz(i) }, 'Take the quiz (10 questions)'));
  }

  function flashcards(items, onBack, start) {
    if (!items.length) { onBack(); return; }
    const n = items.length;
    const i = (((start || 0) % n) + n) % n;
    const it = items[i];
    const ans = h('div', { class: 'answer', style: 'visibility:hidden' }, it.pron + '\n\n' + it.en);
    learnPage(onBack,
      h('div', { class: 'muted' }, 'Card ' + (i + 1) + ' of ' + n + ' - read it out loud, then tap Show'),
      it.ref ? h('div', { style: 'color:var(--accent);font-weight:700;margin-top:10px' }, it.ref) : null,
      h('div', { class: 'flash' + (it.ru.length > 20 ? ' long' : '') }, it.ru),
      ans,
      hearBtn(it.ru),
      h('button', { class: 'btn center', onclick: () => { ans.style.visibility = 'visible'; } }, 'Show'),
      h('div', { class: 'row2' },
        h('button', { class: 'btn center', onclick: () => flashcards(items, onBack, i - 1) }, '←  Previous'),
        h('button', { class: 'btn center accent', onclick: () => flashcards(items, onBack, i + 1) }, 'Next  →')));
  }

  function pickChoices(answer, pool) {
    const opts = shuffle([...new Set(pool.filter(x => x && x !== answer))]).slice(0, 3);
    return shuffle([answer, ...opts]);
  }

  function lessonQuiz(i) {
    const lessons = learn.lessons;
    const pool = lessons.slice(0, i + 1).flatMap(l => l.words);
    const letters = lessons.slice(0, i + 1).flatMap(l => l.letters).map(letterObj).filter(Boolean);
    const newWords = lessons[i].words, newLetters = lessons[i].letters.map(letterObj).filter(Boolean);
    const qs = [];
    for (let n = 0; n < 10; n++) {
      const t = n % 3;
      if (t === 0 && newLetters.length && letters.length >= 4) {
        const l = newLetters[rnd(newLetters.length)];
        qs.push({ prompt: 'What sound does this letter make?', big: l.upper + ' ' + l.lower, answer: l.sound, choices: pickChoices(l.sound, letters.map(x => x.sound)) });
      } else if (newWords.length && pool.length >= 4) {
        const w = newWords[rnd(newWords.length)];
        if (t === 1 || !newLetters.length) qs.push({ prompt: 'How do you read this word?', big: w.word, answer: w.pron, choices: pickChoices(w.pron, pool.map(x => x.pron)) });
        else qs.push({ prompt: 'What does this word mean?', big: w.word, answer: w.meaning, choices: pickChoices(w.meaning, pool.map(x => x.meaning)) });
      }
    }
    quiz('lesson' + i, qs, () => showLesson(i), () => lessonQuiz(i), 'Great - you\'ve got these letters. On to the next lesson!');
  }

  function quiz(key, qs, onBack, again, goodMsg, n, score) {
    n = n || 0; score = score || 0;
    if (n >= qs.length) {
      const b = best(key);
      if (b === undefined || score > b) best(key, score);
      learnPage(onBack, h('h2', {}, 'Score: ' + score + ' / ' + qs.length),
        h('div', { style: 'font-size:18px;margin:8px 0' }, score >= 8 ? goodMsg : 'Keep practicing, then retake the quiz.'),
        h('button', { class: 'btn center', onclick: again }, 'Retake quiz'),
        h('button', { class: 'btn center accent', onclick: onBack }, 'Back'));
      return;
    }
    const q = qs[n];
    const fb = h('div', { class: 'feedback' });
    const btns = [];
    const next = h('div');
    const big = q.swatch ? h('div', { class: 'swatch-big', style: 'background:' + q.swatch })
      : h('div', { class: 'flash' + (q.big.length > 14 ? ' long' : '') }, q.big);
    for (const c of q.choices) {
      const b = h('button', { class: 'btn' }, c);
      b.onclick = () => {
        const right = c === q.answer;
        btns.forEach(x => { x.disabled = true; if (x.textContent === q.answer) x.classList.add('good'); });
        if (!right) b.classList.add('bad');
        fb.textContent = right ? 'Correct!' : 'Not quite. It\'s: ' + q.answer;
        next.append(h('button', { class: 'btn center accent', onclick: () => quiz(key, qs, onBack, again, goodMsg, n + 1, score + (right ? 1 : 0)) },
          n + 1 < qs.length ? 'Next  →' : 'See score'));
        document.getElementById('learn').scrollTop = 1e6;
      };
      btns.push(b);
    }
    learnPage(onBack, h('div', { class: 'muted' }, 'Question ' + (n + 1) + ' of ' + qs.length + '   ·   score ' + score),
      h('div', { style: 'font-size:18px' }, q.prompt), big, btns, fb, next);
  }

  function showList(key) {
    const titles = { phrases: 'Common phrases', verses: 'Bible verses', military: 'Military phrases' };
    const notes = {
      phrases: 'Gold = how to say it. The CAPITAL syllable is stressed.',
      verses: 'Russian Synodal translation with the King James Version. Gold = how to say it.',
      military: 'Short field commands, said loud and sharp. Gold = how to say it.',
    };
    const items = learn[key];
    let last = '';
    const cards = [];
    for (const it of items) {
      if (it.category && it.category !== last) { cards.push(h('h3', {}, it.category)); last = it.category; }
      cards.push(h('div', { class: 'lcard' },
        it.ref ? h('div', { class: 'ref' }, it.ref) : null,
        h('div', { class: 'ru' }, it.ru), h('div', { class: 'pr' }, it.pron), h('div', { class: 'en' }, it.en),
        h('button', { class: 'hearlink', onclick: () => say(it.ru) }, '🔊  Hear it')));
    }
    learnPage(showLearnHome, h('h2', {}, titles[key]), h('div', { class: 'muted' }, notes[key]),
      h('button', { class: 'btn center accent', onclick: () => flashcards(items, () => showList(key)) }, 'Practice as flashcards'),
      cards);
  }

  // --- numbers
  function numberRow(it) {
    return h('div', { class: 'numrow', onclick: () => say(it.ru) },
      h('div', { class: 'd' }, it.digits), h('div', { class: 'w' }, h('div', {}, it.ru), h('div', {}, it.pron)),
      h('div', { style: 'color:var(--hear);font-size:20px' }, '🔊'));
  }

  function showNumbers() {
    const nums = learn.numbers;
    const input = h('input', { type: 'text', inputmode: 'numeric', pattern: '[0-9]*', placeholder: 'Type a number, e.g. 2025', maxlength: 15 });
    const w = h('div', { class: 'w' }), p = h('div', { class: 'p' });
    let cur = '';
    input.oninput = () => {
      const t = input.value.replace(/[,\s]/g, '');
      if (!t) { w.textContent = ''; p.textContent = ''; cur = ''; return; }
      if (!/^\d+$/.test(t) || Number(t) > FT.MAX_NUMBER) { w.textContent = 'Whole numbers up to 999,999,999,999'; p.textContent = ''; cur = ''; return; }
      cur = FT.numberToWords(Number(t));
      w.textContent = cur;
      p.textContent = pronouncer.pronounce(cur);
    };
    learnPage(showLearnHome,
      h('h2', {}, 'Numbers'),
      h('div', { class: 'saynum' }, h('div', { style: 'color:var(--hear);font-weight:700;font-size:14px' }, 'Say any number'),
        input, w, p, h('button', { class: 'btn hear', onclick: () => say(cur) }, '🔊  Hear it')),
      h('button', { class: 'btn center good', onclick: numberQuiz }, 'Take the numbers quiz (10 questions)'),
      nums.groups.map(g => [h('h3', {}, g.title), h('div', { class: 'muted small' }, g.note), g.items.map(numberRow)]),
      h('h3', {}, 'How it works'),
      nums.tips.map(t => h('div', { style: 'margin:6px 0' }, '• ' + t)));
  }

  function randomQuizNumber() {
    const r = rnd(10);
    if (r < 3) return rnd(20);
    if (r < 5) return 20 + rnd(80);
    if (r < 7) return 100 + rnd(900);
    if (r < 9) return 1000 + rnd(99000);
    return 100000 + rnd(9900000);
  }

  function numberQuiz() {
    const qs = [];
    for (let n = 0; n < 10; n++) {
      const num = randomQuizNumber();
      const opts = [num];
      while (opts.length < 4) {
        let d = Math.max(0, num + (Math.random() < 0.5 ? 1 : -1) * (1 + rnd(Math.max(3, Math.min(Math.floor(num / 3) + 3, 5000)))));
        if (rnd(3) === 0) d = randomQuizNumber();
        if (opts.indexOf(d) < 0) opts.push(d);
      }
      shuffle(opts);
      if (n % 2 === 0) qs.push({ prompt: 'How do you say this number?', big: FT.formatDigits(num), answer: FT.numberToWords(num), choices: opts.map(FT.numberToWords) });
      else qs.push({ prompt: 'Which number is this?', big: FT.numberToWords(num), answer: FT.formatDigits(num), choices: opts.map(FT.formatDigits) });
    }
    quiz('numbers', qs, showNumbers, numberQuiz, 'Great - your numbers are solid!');
  }

  // --- colors
  function showColors() {
    const cols = learn.colors;
    learnPage(showLearnHome,
      h('h2', {}, 'Colors'), h('div', { class: 'muted' }, 'Tap a color to hear it.'),
      cols.items.map(c => h('div', { class: 'colrow', onclick: () => say(c.ru) },
        h('div', { class: 'sw', style: 'background:' + c.hex }),
        h('div', { class: 'w' }, h('div', {}, c.ru), h('div', {}, c.pron + '   ·   ' + c.en)),
        h('div', { style: 'color:var(--hear);font-size:20px' }, '🔊'))),
      h('button', { class: 'btn center good', onclick: colorQuiz }, 'Take the colors quiz (10 questions)'),
      h('h3', {}, 'Good to know'), cols.tips.map(t => h('div', { style: 'margin:6px 0' }, '• ' + t)),
      h('h3', {}, 'Examples'),
      cols.examples.map(it => h('div', { class: 'lcard' }, h('div', { class: 'ru' }, it.ru), h('div', { class: 'pr' }, it.pron),
        h('div', { class: 'en' }, it.en), h('button', { class: 'hearlink', onclick: () => say(it.ru) }, '🔊  Hear it'))));
  }

  function colorQuiz() {
    const items = learn.colors.items;
    const qs = [];
    for (let n = 0; n < 10; n++) {
      const c = items[rnd(items.length)];
      if (n % 2 === 0) qs.push({ prompt: 'What color is this word?', big: c.ru, answer: c.en, choices: pickChoices(c.en, items.map(x => x.en)) });
      else qs.push({ prompt: 'How do you say this color in Russian?', swatch: c.hex, big: '', answer: c.ru, choices: pickChoices(c.ru, items.map(x => x.ru)) });
    }
    quiz('colors', qs, showColors, colorQuiz, 'Great - you know your colors!');
  }

  // ------------------------------------------------------------------ self-test hook (used by CI)
  window.FTSelfTest = async function () {
    const out = {};
    await loadLearn();
    out.pron = pronouncer.pronounce('Привет, как дела?');
    out.number = FT.numberToWords(2025);
    await mtFor('ru-en');
    out.translation = (await mtWorker().call({ cmd: 'translate', pair: 'ru-en', text: 'где находится вокзал' })).text;
    const wav = await (await fetch('test/test.wav')).arrayBuffer();
    const dv = new DataView(wav);
    const rate = dv.getUint32(24, true);
    let pos = 12, start = 0, len = 0;
    while (pos + 8 <= wav.byteLength) {
      const id = String.fromCharCode(dv.getUint8(pos), dv.getUint8(pos + 1), dv.getUint8(pos + 2), dv.getUint8(pos + 3));
      const sz = dv.getUint32(pos + 4, true);
      if (id === 'data') { start = pos + 8; len = Math.min(sz, wav.byteLength - start); break; }
      pos += 8 + sz + (sz & 1);
    }
    const pcm = new Int16Array(wav.slice(start, start + len - (len % 2)));
    const f = new Float32Array(pcm.length);
    for (let i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768;
    await speechFor('ru');
    out.transcript = (await speechWorker().call({ cmd: 'transcribe', lang: 'ru', base: BASE, pack: manifest.packs['asr-ru'], samples: f, rate })).text;
    const sp = await ttsWorker().call({ cmd: 'speak', text: 'Привет', base: BASE, pack: manifest.packs['tts-ru'] });
    out.voiceSeconds = sp.samples.length / sp.rate;
    await speechFor('en');
    out.englishLoaded = true;
    return out;
  };

  boot();
})();
