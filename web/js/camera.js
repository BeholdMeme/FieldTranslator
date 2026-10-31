/*
 * Camera reader: photo of Russian text -> text (Tesseract, offline) -> English (same offline
 * translator as the rest of the app). Photos are never saved: the picture only lives in memory
 * while it is read and is dropped when you leave this screen.
 * Kept in its own file so nothing here can affect the translator, voice or Learn screens.
 */
(function () {
  'use strict';

  // WebAssembly SIMD check (same bytes as the wasm-feature-detect library)
  const SIMD = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]);
  function hasSimd() { try { return WebAssembly.validate(SIMD); } catch (e) { return false; } }

  /** The files this phone needs from the 'ocr' pack: one engine build + the Russian text model. */
  function ocrFiles(manifest) {
    const pack = manifest && manifest.packs && manifest.packs.ocr;
    if (!pack) return null;
    const core = hasSimd() ? 'tesseract-core-simd-lstm.wasm.js' : 'tesseract-core-lstm.wasm.js';
    const files = pack.files.filter(f => f.name === core || f.name === 'rus.traineddata.gz');
    return files.length === 2 ? files : null;
  }

  async function ocrReady(manifest, base) {
    const files = ocrFiles(manifest);
    if (!files) return false;
    for (const f of files) if (!(await FTStore.isCached(base + f.path))) return false;
    return true;
  }

  /** Quietly fetch the reader's files in the background (only when online and not yet stored). */
  async function prefetch(manifest, base) {
    try {
      const files = ocrFiles(manifest);
      if (!files || !navigator.onLine || await ocrReady(manifest, base)) return;
      await FTStore.downloadAll(base, files, () => {});
    } catch (e) { /* tried again next time the app opens */ }
  }

  // ---------------------------------------------------------------- text clean-up
  const CYR = /[А-Яа-яЁё]/;

  /** OCR paragraphs -> sentences ready for the translator. */
  function toSentences(paragraphs) {
    const out = [];
    // an ALL-CAPS line (a heading like "ДОГОВОР АРЕНДЫ") stands on its own
    const split = [];
    for (const p of paragraphs) {
      let cur = [];
      for (const line of p.split('\n')) {
        const t = line.trim();
        const caps = t.length > 2 && CYR.test(t) && t === t.toUpperCase();
        if (caps) { if (cur.length) split.push(cur.join('\n')); split.push(t); cur = []; } else cur.push(line);
      }
      if (cur.length) split.push(cur.join('\n'));
    }
    paragraphs = split;
    for (let p of paragraphs) {
      p = p.replace(/(\S)-\s*\n\s*(\S)/g, '$1$2')         // word-\nwrap -> wordwrap
        .replace(/\s*\n\s*/g, ' ').replace(/\s+/g, ' ').trim();
      if (!p) continue;
      const parts = p.match(/[^.!?…]+(?:[.!?…]+["»)\]]*|$)/g) || [p];
      for (let s of parts) {
        s = s.trim();
        if (!s) continue;
        // very long sentences (contracts) are split at ; or , so the translator sees all of it
        while (s.length > 260) {
          let cut = -1;
          for (const sep of ['; ', ', ']) {
            const i = s.lastIndexOf(sep, 220);
            if (i > 60) { cut = i + 1; break; }
          }
          if (cut < 0) cut = s.lastIndexOf(' ', 220);
          if (cut < 40) break;
          out.push(s.slice(0, cut).trim());
          s = s.slice(cut).trim();
        }
        out.push(s);
      }
    }
    // join tiny fragments (OCR noise like "1." or "—") onto the next piece
    const merged = [];
    for (const s of out) {
      if (merged.length && merged[merged.length - 1].length < 4) merged[merged.length - 1] += ' ' + s;
      else merged.push(s);
    }
    return merged;
  }

  /** Photo file -> canvas, scaled so the long side is at most `max` pixels (keeps memory low). */
  function fileToCanvas(file, max) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        const s = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
        const c = document.createElement('canvas');
        c.width = Math.max(1, Math.round(img.naturalWidth * s));
        c.height = Math.max(1, Math.round(img.naturalHeight * s));
        const g = c.getContext('2d');
        g.fillStyle = '#fff';
        g.fillRect(0, 0, c.width, c.height);
        g.drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        resolve(c);
      };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('could not open this picture')); };
      img.src = url;
    });
  }

  function withTimeout(p, ms, what) {
    return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(what + ' took too long')), ms))]);
  }

  async function recognize(canvas, manifest, base, onProgress) {
    const files = ocrFiles(manifest);
    const core = files.find(f => f.name !== 'rus.traineddata.gz');
    const lang = files.find(f => f.name === 'rus.traineddata.gz');
    const vendor = new URL('vendor/', location.href).href;
    let failed = null;
    const worker = await withTimeout(Tesseract.createWorker('rus', 1, {
      workerPath: vendor + 'tesseract-worker.min.js',
      corePath: base + core.path,              // ends in .js, so used as-is
      langPath: base + lang.path.replace(/\/[^/]+$/, ''),   // folder holding rus.traineddata.gz (served offline)
      gzip: true,
      workerBlobURL: false,
      cacheMethod: 'none',                     // nothing written to the phone's storage
      logger: m => { if (m && m.status === 'recognizing text' && onProgress) onProgress(m.progress || 0); },
      errorHandler: e => { failed = e; },
    }), 90000, 'starting the text reader');
    if (failed) { try { await worker.terminate(); } catch (e) { /* ignore */ } throw new Error(String(failed)); }
    try {
      await worker.setParameters({ preserve_interword_spaces: '1' });
      const r = await withTimeout(worker.recognize(canvas, {}, { text: true, blocks: true }), 240000, 'reading the photo');
      const paras = [];
      const blocks = (r && r.data && r.data.blocks) || [];
      for (const b of blocks) {
        for (const p of (b.paragraphs || [])) {
          const lines = (p.lines || []).filter(l => l.confidence >= 35 || CYR.test(l.text) && l.confidence >= 20);
          const t = lines.map(l => l.text).join('\n');
          if (t.trim()) paras.push(t);
        }
      }
      if (!paras.length && r && r.data && r.data.text) paras.push(...r.data.text.split(/\n\s*\n/));
      return paras;
    } finally {
      try { await worker.terminate(); } catch (e) { /* frees its memory */ }
    }
  }

  // ---------------------------------------------------------------- saved scans
  // Only the text is kept (Russian + English), never the photo. Last 20 scans, on this phone only.
  const KEY = 'ft.scans', MAX_SCANS = 20;
  function loadScans() { try { return JSON.parse(localStorage.getItem(KEY)) || []; } catch (e) { return []; } }
  function saveScans(list) { try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX_SCANS))); } catch (e) { /* storage full or blocked */ } }
  function saveScan(scan) {
    const list = loadScans().filter(x => x.id !== scan.id);
    list.unshift(scan);
    saveScans(list);
  }
  function deleteScan(id) { saveScans(loadScans().filter(x => x.id !== id)); }
  function when(t) {
    const d = new Date(t);
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ', ' +
      d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  }

  // ALL-CAPS headings translate badly; give the translator normal case, show the original
  function forTranslator(s) {
    if (s === s.toUpperCase() && s !== s.toLowerCase()) {
      const l = s.toLowerCase();
      return l.charAt(0).toUpperCase() + l.slice(1);
    }
    return s;
  }

  // ---------------------------------------------------------------- the screen
  function open(app) {
    const { h, learnPage, say, manifest, base, state } = app;
    let alive = true, busy = false, run = 0;
    const leave = () => { alive = false; run++; app.back(); };

    const status = h('div', { class: 'muted', style: 'margin:10px 0 4px;min-height:20px' }, '');
    const bar = h('i');
    const barBox = h('div', { class: 'bar hidden', style: 'margin:6px 0 10px' }, bar);
    const results = h('div');
    const recent = h('div');
    const take = h('input', { type: 'file', accept: 'image/*', capture: 'environment', class: 'hidden' });
    const pick = h('input', { type: 'file', accept: 'image/*', class: 'hidden' });
    const btnTake = h('button', { class: 'btn center good', onclick: () => { if (!busy) take.click(); } }, '📷  Take a photo');
    const btnPick = h('button', { class: 'btn center', onclick: () => { if (!busy) pick.click(); } }, '🖼  Choose a photo');

    function setBusy(b) { busy = b; btnTake.disabled = b; btnPick.disabled = b; }
    function progress(p) { bar.style.width = (100 * p).toFixed(0) + '%'; }

    /** Show a scan and translate any lines that are still missing (also resumes after leaving the app). */
    async function showScan(scan, mine) {
      results.replaceChildren(h('div', { class: 'muted small', style: 'margin:8px 0 2px' }, 'Scan from ' + when(scan.time)));
      const cards = scan.lines.map(line => {
        const cyr = CYR.test(line.ru);
        const en = h('div', { class: 'en' }, line.en || (cyr ? '…' : ''));
        results.append(h('div', { class: 'lcard' }, h('div', { class: 'ru', style: 'font-size:19px' }, line.ru),
          state.pron && cyr ? h('div', { class: 'pr' }, app.pronounce(line.ru)) : null, en,
          cyr ? h('button', { class: 'hearlink', onclick: () => say(line.ru) }, '🔊  Hear it') : null));
        return { line, en, cyr };
      });
      const todo = cards.filter(c => c.cyr && !c.line.en);
      if (!todo.length) { status.textContent = scan.lines.length + (scan.lines.length === 1 ? ' line.' : ' lines.'); return; }
      barBox.classList.remove('hidden');
      try {
        await app.ensureRuEn();
        let done = 0;
        for (const c of todo) {
          if (!alive || mine !== run) return;
          try { c.line.en = (await app.translate(forTranslator(c.line.ru))) || '—'; } catch (e) { c.line.en = ''; c.en.textContent = '[could not translate - reopen to retry]'; continue; }
          c.en.textContent = c.line.en;
          saveScan(scan);                        // each finished line is kept straight away
          done++;
          progress(done / todo.length);
          status.textContent = 'Translating… ' + done + ' of ' + todo.length;
        }
        status.textContent = 'Done - ' + scan.lines.length + (scan.lines.length === 1 ? ' line' : ' lines') +
          '. Saved in Recent scans below.';
      } finally {
        if (mine === run) barBox.classList.add('hidden');
        drawRecent();
      }
    }

    function drawRecent() {
      const list = loadScans();
      recent.replaceChildren();
      if (!list.length) return;
      recent.append(h('h3', {}, 'Recent scans'),
        h('div', { class: 'muted small' }, 'Text only - the photos themselves are not kept. Tap to open.'));
      for (const sc of list) {
        const first = (sc.lines[0] && sc.lines[0].ru) || '';
        const missing = sc.lines.some(l => CYR.test(l.ru) && !l.en);
        recent.append(h('div', { class: 'numrow', onclick: () => { if (busy) return; const m = ++run; window.scrollTo(0, 0); document.getElementById('learn').scrollTop = 0; showScan(sc, m); } },
          h('div', { class: 'w' }, h('div', { style: 'font-size:16px' }, first.length > 60 ? first.slice(0, 60) + '…' : first),
            h('div', { style: 'color:var(--muted)' }, when(sc.time) + ' · ' + sc.lines.length + ' lines' + (missing ? ' · not finished' : ''))),
          h('button', { class: 'btn', style: 'width:auto;margin:0 0 0 8px;padding:8px 12px', onclick: e => {
            e.stopPropagation(); deleteScan(sc.id); drawRecent();
          } }, 'Delete')));
      }
      recent.append(h('button', { class: 'btn center', onclick: () => {
        if (confirmClear()) { saveScans([]); drawRecent(); }
      } }, 'Clear all scans'));
    }
    let clearArmed = 0;
    function confirmClear() {               // tap twice (no pop-up dialogs)
      if (Date.now() - clearArmed < 4000) return true;
      clearArmed = Date.now();
      status.textContent = 'Tap "Clear all scans" again to delete them all.';
      return false;
    }

    async function handle(input) {
      const file = input.files && input.files[0];
      input.value = '';                        // drop the reference to the photo right away
      if (!file || busy) return;
      const mine = ++run;
      setBusy(true);
      results.replaceChildren();
      barBox.classList.remove('hidden');
      progress(0);
      try {
        if (!(await ocrReady(manifest, base))) {
          if (!navigator.onLine) throw new Error('the camera reader is not downloaded yet - open the app once with internet');
          status.textContent = 'Getting the camera reader ready (one time, about 7 MB)…';
          await FTStore.downloadAll(base, ocrFiles(manifest), (d, t) => progress(d / t));
        }
        status.textContent = 'Preparing the photo…';
        let canvas = await fileToCanvas(file, 2400);
        status.textContent = 'Reading the Russian text…';
        progress(0);
        const paras = await recognize(canvas, manifest, base, p => {
          if (mine === run) { progress(p); status.textContent = 'Reading the Russian text… ' + Math.round(100 * p) + '%'; }
        });
        canvas.width = canvas.height = 0;     // release the picture's memory
        canvas = null;
        const sentences = toSentences(paras);
        if (!sentences.length) {
          status.textContent = 'No text found. Try again closer, in good light, with the phone straight above the page.';
          return;
        }
        const scan = { id: Date.now().toString(36), time: Date.now(), lines: sentences.map(ru => ({ ru, en: '' })) };
        saveScan(scan);                          // the Russian text is kept even if you leave right now
        if (!alive || mine !== run) return;
        status.textContent = 'Translating ' + sentences.length + (sentences.length === 1 ? ' line' : ' lines') + '…';
        progress(0);
        await showScan(scan, mine);
      } catch (e) {
        if (alive && mine === run) status.textContent = 'Could not read this photo: ' + (e && e.message || e);
      } finally {
        if (mine === run) { setBusy(false); barBox.classList.add('hidden'); }
      }
    }
    take.onchange = () => handle(take);
    pick.onchange = () => handle(pick);

    learnPage(leave,
      h('h2', {}, 'Read Russian text'),
      h('div', { class: 'muted' }, 'Photograph a sign, menu, label or page. Works offline. Photos are not saved - only the text.'),
      btnTake, btnPick, take, pick, barBox, status, results, recent,
      h('h3', {}, 'For best results'),
      ['Good light, no shadows or glare.', 'Hold the phone straight above the page; fill the screen with the text.',
        'For long documents, one page per photo.', 'Printed text works best; handwriting usually won\'t.',
        'Important documents (contracts): use this to get the gist, then have a person check before you sign.']
        .map(t => h('div', { style: 'margin:6px 0' }, '• ' + t)));

    // pick up an unfinished scan (e.g. the app was closed mid-page), otherwise just list recent scans
    const last = loadScans()[0];
    if (last && last.lines.some(l => CYR.test(l.ru) && !l.en)) { status.textContent = 'Finishing your last scan…'; showScan(last, ++run); }
    else drawRecent();
  }

  self.FTCamera = { open, prefetch, toSentences, ocrFiles, loadScans };
})();
