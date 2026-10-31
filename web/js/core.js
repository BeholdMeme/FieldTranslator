/*
 * Field Translator - shared logic for the web app (browser main thread, workers and Node tests).
 * Line-for-line mirrors of the Java code in core/src/main/java/app/translator/core/:
 *   SpmTokenizer, MarianTranslator (needs onnxruntime-web as `ort`), Pronouncer, RussianNumbers.
 * CI checks these produce exactly the same output as the Python/Java versions.
 */
(function (root) {
  'use strict';

  // ------------------------------------------------------------------ SentencePiece unigram tokenizer
  const SPACE = '▁';
  const UNK_PENALTY = 10.0;

  function isWs(c) {
    // Java: Character.isWhitespace || Character.isSpaceChar
    return /[\t\n\u000b\f\r\u001c-\u001f \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/.test(c);
  }

  class SpmTokenizer {
    constructor(tsvText) {
      this.scores = new Map();
      let minScore = Infinity, maxLen = 1;
      for (const line of tsvText.split('\n')) {
        if (!line) continue;
        const p = line.split('\t');
        if (p.length < 3) continue;
        const type = parseInt(p[2], 10);
        const score = parseFloat(p[1]);
        if (type === 1 || type === 4) {
          this.scores.set(p[0], type === 4 ? 0 : Math.fround(score));
          maxLen = Math.max(maxLen, p[0].length);
        }
        if (type === 1) minScore = Math.min(minScore, Math.fround(score));
      }
      this.maxPieceLen = maxLen;
      this.unkScore = Math.fround(minScore - UNK_PENALTY);
    }

    static normalize(text) {
      const s = text.normalize('NFKC');
      let b = '';
      let lastSpace = true;
      for (const c of s) {
        if (isWs(c)) {
          if (!lastSpace) { b += ' '; lastSpace = true; }
        } else if (/\p{Cc}/u.test(c)) {
          // drop control characters
        } else {
          b += c;
          lastSpace = false;
        }
      }
      b = b.replace(/ +$/, '');
      if (!b) return '';
      return (SPACE + b).split(' ').join(SPACE);
    }

    encode(text) {
      const s = SpmTokenizer.normalize(text);
      const n = s.length;
      const out = [];
      if (n === 0) return out;
      const best = new Float32Array(n + 1).fill(-Infinity);
      const from = new Int32Array(n + 1);
      const unk = new Uint8Array(n + 1);
      best[0] = 0;
      for (let i = 0; i < n; i++) {
        if (best[i] === -Infinity) continue;
        const code = s.charCodeAt(i);
        if (code >= 0xdc00 && code <= 0xdfff && i > 0) {
          const prev = s.charCodeAt(i - 1);
          if (prev >= 0xd800 && prev <= 0xdbff) continue;
        }
        let matchedSingle = false;
        const charLen = (code >= 0xd800 && code <= 0xdbff && i + 1 < n) ? 2 : 1;
        const limit = Math.min(n, i + this.maxPieceLen);
        for (let j = i + 1; j <= limit; j++) {
          const sc = this.scores.get(s.substring(i, j));
          if (sc === undefined) continue;
          if (j === i + charLen) matchedSingle = true;
          const v = Math.fround(best[i] + sc);
          if (v > best[j]) { best[j] = v; from[j] = i; unk[j] = 0; }
        }
        if (!matchedSingle) {
          const j = i + charLen;
          const v = Math.fround(best[i] + this.unkScore);
          if (v > best[j]) { best[j] = v; from[j] = i; unk[j] = 1; }
        }
      }
      const rev = [], revUnk = [];
      for (let j = n; j > 0; j = from[j]) { rev.push(s.substring(from[j], j)); revUnk.push(unk[j]); }
      let lastWasUnk = false;
      for (let k = rev.length - 1; k >= 0; k--) {
        if (revUnk[k] && lastWasUnk) out[out.length - 1] += rev[k];
        else out.push(rev[k]);
        lastWasUnk = !!revUnk[k];
      }
      return out;
    }

    static detokenize(pieces) {
      return pieces.join('').split(SPACE).join(' ').trim();
    }
  }

  // ------------------------------------------------------------------ Marian (Opus-MT) translator
  function parseProps(text) {
    const p = {};
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (!line || line[0] === '#' || line.indexOf('=') < 0) continue;
      const k = line.slice(0, line.indexOf('=')).trim();
      p[k] = line.slice(line.indexOf('=') + 1).trim();
    }
    return p;
  }

  function prepareText(text) {
    let t = text.trim().split(/\s+/).filter(Boolean).join(' ');
    if (!t) return t;
    const first = String.fromCodePoint(t.codePointAt(0));
    t = first.toUpperCase() + t.slice(first.length);
    if ('.!?…'.indexOf(t[t.length - 1]) < 0) t += '.';
    return t;
  }

  class MarianTranslator {
    /** files: {props, spm, vocab (strings), encoder, decoder (ArrayBuffer/Uint8Array)}; ort = onnxruntime-web */
    static async create(ort, files) {
      const tr = new MarianTranslator();
      const p = parseProps(files.props);
      tr.ort = ort;
      tr.eos = +p.eos_token_id; tr.pad = +p.pad_token_id; tr.unk = +p.unk_token_id;
      tr.start = +p.decoder_start_token_id; tr.heads = +p.decoder_attention_heads;
      tr.headDim = (+p.d_model) / tr.heads;
      tr.maxInputTokens = +(p.max_input_tokens || 200);
      tr.tok = new SpmTokenizer(files.spm);
      tr.id2piece = files.vocab.split('\n');
      if (tr.id2piece.length && tr.id2piece[tr.id2piece.length - 1] === '') tr.id2piece.pop();
      tr.piece2id = new Map();
      tr.id2piece.forEach((pc, i) => { if (!tr.piece2id.has(pc)) tr.piece2id.set(pc, i); });
      // memory-lean settings (phones): no arena / pre-planned buffers; results are identical
      const opts = { executionProviders: ['wasm'], graphOptimizationLevel: 'all', enableCpuMemArena: false, enableMemPattern: false };
      const bytes = b => (b instanceof Uint8Array ? b : new Uint8Array(b));
      tr.encoder = await ort.InferenceSession.create(bytes(files.encoder), opts);
      tr.decoder = await ort.InferenceSession.create(bytes(files.decoder), opts);
      return tr;
    }

    async release() {
      try { if (this.encoder) await this.encoder.release(); } catch (e) { /* ignore */ }
      try { if (this.decoder) await this.decoder.release(); } catch (e) { /* ignore */ }
      this.encoder = this.decoder = null;
    }

    encodeIds(text) {
      const pieces = this.tok.encode(text);
      const n = Math.min(pieces.length, this.maxInputTokens - 1);
      const ids = new Array(n + 1);
      for (let i = 0; i < n; i++) {
        const id = this.piece2id.get(pieces[i]);
        ids[i] = id === undefined ? this.unk : id;
      }
      ids[n] = this.eos;
      return ids;
    }

    async translateIds(src) {
      const ort = this.ort;
      const T = (type, data, dims) => new ort.Tensor(type, data, dims);
      const ids = T('int64', BigInt64Array.from(src.map(BigInt)), [1, src.length]);
      const mask = T('int64', new BigInt64Array(src.length).fill(1n), [1, src.length]);
      const enc = await this.encoder.run({ input_ids: ids, attention_mask: mask });
      const hidden = enc.last_hidden_state;
      const dummy = T('float32', new Float32Array(this.heads * this.headDim), [1, this.heads, 1, this.headDim]);
      const maxLen = Math.min(256, 3 * src.length + 10);
      let first = null, prev = null;
      let last = this.start;
      const out = [];
      for (let step = 0; step < maxLen; step++) {
        const feeds = {};
        for (const name of this.decoder.inputNames) {
          if (name === 'input_ids') feeds[name] = T('int64', BigInt64Array.from([BigInt(last)]), [1, 1]);
          else if (name === 'encoder_attention_mask') feeds[name] = mask;
          else if (name === 'encoder_hidden_states') feeds[name] = hidden;
          else if (name === 'use_cache_branch') feeds[name] = T('bool', new Uint8Array([step > 0 ? 1 : 0]), [1]);
          else if (name.startsWith('past_key_values')) {
            if (step === 0) feeds[name] = dummy;
            else {
              const present = 'present' + name.slice('past_key_values'.length);
              feeds[name] = (present.indexOf('.encoder.') >= 0 ? first : prev)[present];
            }
          } else throw new Error('unexpected decoder input ' + name);
        }
        const res = await this.decoder.run(feeds);
        if (step === 0) first = res;
        prev = res;
        const logits = res.logits;
        const vocab = logits.dims[logits.dims.length - 1];
        const data = logits.data;
        const base = data.length - vocab;
        let best = -1, bestV = -Infinity;
        for (let v = 0; v < vocab; v++) {
          if (v === this.pad) continue;
          const x = data[base + v];
          if (x > bestV) { bestV = x; best = v; }
        }
        if (best === this.eos || best < 0) break;
        out.push(best);
        last = best;
      }
      return out;
    }

    decodeIds(ids) {
      const pieces = [];
      for (const id of ids) {
        if (id === this.pad || id === this.eos || id === this.unk || id < 0 || id >= this.id2piece.length) continue;
        pieces.push(this.id2piece[id]);
      }
      return SpmTokenizer.detokenize(pieces);
    }

    async translate(text) {
      const t = prepareText(text);
      if (!t) return '';
      return this.decodeIds(await this.translateIds(this.encodeIds(t)));
    }
  }

  // ------------------------------------------------------------------ stress dictionary + pronouncer
  class StressDict {
    /** bytes: Uint8Array of the sorted "word\tindex\n" file */
    constructor(bytes) {
      this.buf = bytes || new Uint8Array(0);
      let n = 0;
      const b = this.buf, lim = b.length;
      for (let i = 0; i < lim; i++) if (b[i] === 10) n++;
      if (lim > 0 && b[lim - 1] !== 10) n++;
      this.starts = new Int32Array(n);
      let k = 0;
      if (lim > 0) this.starts[k++] = 0;
      for (let i = 0; i < lim - 1 && k < n; i++) if (b[i] === 10) this.starts[k++] = i + 1;
      this.dec = new TextDecoder('utf-8');
    }

    key(line) {
      const s = this.starts[line];
      let e = s;
      while (e < this.buf.length && this.buf[e] !== 9 && this.buf[e] !== 10) e++;
      return this.dec.decode(this.buf.subarray(s, e));
    }

    value(line) {
      let p = this.starts[line];
      while (this.buf[p] !== 9) p++;
      let v = 0;
      for (p++; p < this.buf.length && this.buf[p] >= 48 && this.buf[p] <= 57; p++) v = v * 10 + (this.buf[p] - 48);
      return v;
    }

    get(w) {
      let lo = 0, hi = this.starts.length - 1;
      while (lo <= hi) {
        const mid = (lo + hi) >>> 1;
        const k = this.key(mid);
        const c = k < w ? -1 : (k > w ? 1 : 0);   // UTF-16 code unit order == Java compareTo
        if (c === 0) return this.value(mid);
        if (c < 0) lo = mid + 1; else hi = mid - 1;
      }
      return null;
    }
  }

  const VOWELS = 'аеёиоуыэюя';
  const IOTATED = 'еёюя';
  const CONS = {
    'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ж': 'zh', 'з': 'z', 'й': 'y', 'к': 'k', 'л': 'l', 'м': 'm',
    'н': 'n', 'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'ф': 'f', 'х': 'kh', 'ц': 'ts', 'ч': 'ch', 'ш': 'sh', 'щ': 'sh',
  };
  const DEVOICE = {}, VOICE = {};
  'бвгджз'.split('').forEach((c, i) => { DEVOICE[c] = 'пфктшс'[i]; VOICE['пфктшс'[i]] = c; });
  const VOICED_OBSTRUENTS = 'бгджз', VOICELESS = 'пфктшсхцчщ', NO_GLIDE_AFTER = 'жшцчщй', HARD_ONLY = 'жшц';
  const OGO_KEEP_G = new Set(['много', 'немного', 'строго', 'нестрого', 'дорого', 'недорого', 'убого', 'полого', 'отлого']);
  const WORD = /[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*/g;
  const isVowel = c => VOWELS.indexOf(c) >= 0;
  const has = (set, c) => c.length === 1 && set.indexOf(c) >= 0;

  const COMPOUND_TAILS = [['тысячн', 1], ['миллионн', 5], ['миллиардн', 5], ['сотый', 1], ['сотая', 1], ['сотое', 1]];
  class Pronouncer {
    constructor(dict, exceptions) {
      this.dict = dict;
      this.ex = exceptions || {};
    }

    stressOf(w) {
      const yo = w.indexOf('ё');
      if (yo >= 0) return yo;
      const vi = [];
      for (let i = 0; i < w.length; i++) if (isVowel(w[i])) vi.push(i);
      if (vi.length === 1) return vi[0];
      if (!vi.length) return null;
      const st = this.dict ? this.dict.get(w) : null;
      if (st === null || st === undefined) {
        // number words fused with a round number ("двухтысячный") keep the stress of their tail
        for (const [tail, off] of COMPOUND_TAILS) {
          const p = w.lastIndexOf(tail);
          if (p > 0) return p + off;
        }
        return null;
      }
      return st;
    }

    static assimilate(word) {
      const w = word.split('');
      let nxt = null;
      for (let i = w.length - 1; i >= 0; i--) {
        const ch = w[i];
        if (ch === 'ь' || ch === 'ъ') continue;
        if (isVowel(ch)) { nxt = 'V'; continue; }
        if (CONS[ch] !== undefined) {
          if (nxt === null) { if (DEVOICE[ch]) w[i] = DEVOICE[ch]; }
          else if (has(VOICELESS, nxt) && DEVOICE[ch]) w[i] = DEVOICE[ch];
          else if (has(VOICED_OBSTRUENTS, nxt) && VOICE[ch]) w[i] = VOICE[ch];
          nxt = w[i];
        }
      }
      return w;
    }

    word(word) {
      const lw = word.toLowerCase();
      if (Object.prototype.hasOwnProperty.call(this.ex, lw)) return this.ex[lw];
      let st = this.stressOf(lw);
      const known = st !== null;
      let w = lw;
      if ((w.endsWith('ого') || w.endsWith('его')) && w.length > 3 && !OGO_KEEP_G.has(w)) {
        w = w.slice(0, -2) + 'в' + w[w.length - 1];
      }
      for (const [a, b] of [['вств', 'ств'], ['здн', 'зн'], ['стн', 'сн'], ['лнц', 'нц'], ['рдц', 'рц'], ['дц', 'ц']]) {
        const off = w.indexOf(a);
        if (off >= 0) {
          if (st !== null && st > off) st -= 1;
          w = w.slice(0, off) + b + w.slice(off + a.length);
        }
      }
      for (const a of ['сч', 'зч']) {
        let off;
        while ((off = w.indexOf(a)) >= 0) {
          if (st !== null && st > off) st -= 1;
          w = w.slice(0, off) + 'щ' + w.slice(off + 2);
        }
      }
      let suffix = '';
      for (const end of ['ться', 'тся']) {
        if (w.endsWith(end) && w.length > end.length) { w = w.slice(0, -end.length); suffix = 'tsuh'; break; }
      }
      const letters = Pronouncer.assimilate(w);
      const vowelPos = [];
      for (let i = 0; i < letters.length; i++) if (isVowel(letters[i])) vowelPos.push(i);
      const stressedVn = (known && vowelPos.indexOf(st) >= 0) ? vowelPos.indexOf(st) : -1;

      const units = [];
      let vn = -1;
      for (let i = 0; i < letters.length; i++) {
        const ch = letters[i];
        const prev = i > 0 ? letters[i - 1] : '#';
        if (isVowel(ch)) {
          vn++;
          const stressed = stressedVn >= 0 && vn === stressedVn;
          const last = vn === vowelPos.length - 1;
          let glide = (has(IOTATED, ch) && !has(NO_GLIDE_AFTER, prev)) || (ch === 'и' && prev === 'ь');
          let v;
          if (known && !stressed) {
            const pretonic = stressedVn >= 0 && vn === stressedVn - 1;
            const initial = i === 0;
            if (ch === 'о' || ch === 'а') {
              if ((prev === 'ч' || prev === 'щ') && !last) v = 'i';
              else v = (pretonic || initial) ? 'a' : 'uh';
            } else if (ch === 'я') {
              if (last) v = 'a';
              else { v = 'i'; if (CONS[prev] !== undefined) glide = false; }
            } else if (ch === 'е') {
              v = last ? 'e' : (has(HARD_ONLY, prev) ? 'y' : 'i');
              if (!last && CONS[prev] !== undefined) glide = false;
            } else if (ch === 'и') {
              v = has(HARD_ONLY, prev) ? 'y' : 'i';
            } else if (ch === 'ы' && last && i + 2 === letters.length && letters[letters.length - 1] === 'й') {
              v = 'i';
            } else {
              v = { 'у': 'oo', 'ю': 'oo', 'ы': 'y', 'э': 'e' }[ch] || 'o';
            }
          } else {
            if (ch === 'и' && has(HARD_ONLY, prev)) v = 'y';
            else if (ch === 'е' && has(HARD_ONLY, prev)) v = 'e';
            else {
              v = { 'а': 'a', 'я': 'a', 'о': 'o', 'ё': 'o', 'у': 'oo', 'ю': 'oo', 'ы': 'y', 'э': 'e', 'е': 'e' }[ch];
              if (v === undefined) v = known ? 'ee' : 'i';
            }
          }
          if (glide) v = 'y' + v;
          units.push({ t: v, k: 'v', s: stressed });
        } else if (ch === 'ь' || ch === 'ъ') {
          const nxt = i + 1 < letters.length ? letters[i + 1] : '#';
          if (isVowel(nxt)) units.push({ t: '', k: 'b', s: false });
          else if (ch === 'ь') units.push({ t: "'", k: 's', s: false });
        } else if (CONS[ch] !== undefined) {
          units.push({ t: CONS[ch], k: 'c', s: false });
        } else {
          units.push({ t: ch, k: 'c', s: false });
        }
      }

      const syl = [[]];
      let seenVowel = false;
      let pending = [];
      for (const u of units) {
        if (u.k === 'v') {
          if (seenVowel) {
            let k = 0;
            const consCount = pending.filter(x => x.k === 'c').length;
            if (pending.length && pending[pending.length - 1].k === 'b') k = pending.length;
            else if (consCount >= 2) { k = 1; while (k < pending.length && pending[k].k === 's') k++; }
            syl[syl.length - 1].push(...pending.slice(0, k));
            syl.push(pending.slice(k).concat([u]));
          } else {
            syl[syl.length - 1].push(...pending, u);
          }
          pending = [];
          seenVowel = true;
        } else pending.push(u);
      }
      syl[syl.length - 1].push(...pending);
      const oneSyllable = vowelPos.length <= 1;
      const parts = [];
      for (const s of syl) {
        if (!s.length) continue;
        const t = s.map(x => x.t).join('');
        parts.push((!oneSyllable && s.some(x => x.s)) ? t.toUpperCase() : t);
      }
      let out = parts.join('-');
      if (suffix) out += (units.some(u => u.k === 'v') ? '-' : '') + suffix;
      return out;
    }

    pronounce(text) {
      return text.replace(WORD, token => {
        const lw = token.toLowerCase();
        if (Object.prototype.hasOwnProperty.call(this.ex, lw)) return this.ex[lw];
        return token.split('-').map(p => this.word(p)).join('-');
      });
    }
  }

  // ------------------------------------------------------------------ numbers
  const UNITS_M = ['ноль', 'один', 'два', 'три', 'четыре', 'пять', 'шесть', 'семь', 'восемь', 'девять'];
  const UNITS_F = ['ноль', 'одна', 'две', 'три', 'четыре', 'пять', 'шесть', 'семь', 'восемь', 'девять'];
  const TEENS = ['десять', 'одиннадцать', 'двенадцать', 'тринадцать', 'четырнадцать', 'пятнадцать', 'шестнадцать',
    'семнадцать', 'восемнадцать', 'девятнадцать'];
  const TENS = ['', '', 'двадцать', 'тридцать', 'сорок', 'пятьдесят', 'шестьдесят', 'семьдесят', 'восемьдесят', 'девяносто'];
  const HUNDREDS = ['', 'сто', 'двести', 'триста', 'четыреста', 'пятьсот', 'шестьсот', 'семьсот', 'восемьсот', 'девятьсот'];
  const SCALES = [['тысяча', 'тысячи', 'тысяч'], ['миллион', 'миллиона', 'миллионов'], ['миллиард', 'миллиарда', 'миллиардов']];
  const MAX = 999999999999;

  function pluralForm(n) {
    const n100 = n % 100, n10 = n % 10;
    if (n100 >= 11 && n100 <= 14) return 2;
    if (n10 === 1) return 0;
    if (n10 >= 2 && n10 <= 4) return 1;
    return 2;
  }

  function triple(n, feminine, out) {
    const h = Math.floor(n / 100), rest = n % 100;
    if (h) out.push(HUNDREDS[h]);
    if (rest >= 10 && rest <= 19) out.push(TEENS[rest - 10]);
    else {
      const t = Math.floor(rest / 10), u = rest % 10;
      if (t) out.push(TENS[t]);
      if (u) out.push((feminine ? UNITS_F : UNITS_M)[u]);
    }
  }

  function numberToWords(n) {
    n = Math.trunc(Number(n));
    if (n < 0) return 'минус ' + numberToWords(-n);
    if (n > MAX || !isFinite(n)) throw new Error('number too large');
    if (n === 0) return 'ноль';
    const groups = [];
    while (n > 0) { groups.push(n % 1000); n = Math.floor(n / 1000); }
    const out = [];
    for (let i = groups.length - 1; i >= 0; i--) {
      const g = groups[i];
      if (!g) continue;
      if (i === 0) triple(g, false, out);
      else {
        const forms = SCALES[i - 1];
        if (g === 1) out.push(forms[0]);
        else { triple(g, i === 1, out); out.push(forms[pluralForm(g)]); }
      }
    }
    return out.join(' ');
  }

  function formatDigits(n) {
    return Math.trunc(Number(n)).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }


  // ---------------------------------------------------------------- ordinals (mirror of laptop/ru_numbers.py)
  const ORD_UNITS = ['нулевой', 'первый', 'второй', 'третий', 'четвёртый', 'пятый', 'шестой', 'седьмой', 'восьмой', 'девятый'];
  const ORD_TEENS = ['десятый', 'одиннадцатый', 'двенадцатый', 'тринадцатый', 'четырнадцатый', 'пятнадцатый', 'шестнадцатый',
    'семнадцатый', 'восемнадцатый', 'девятнадцатый'];
  const ORD_TENS = ['', '', 'двадцатый', 'тридцатый', 'сороковой', 'пятидесятый', 'шестидесятый', 'семидесятый', 'восьмидесятый', 'девяностый'];
  const ORD_HUNDREDS = ['', 'сотый', 'двухсотый', 'трёхсотый', 'четырёхсотый', 'пятисотый', 'шестисотый', 'семисотый', 'восьмисотый', 'девятисотый'];
  const GEN_UNITS = ['', 'одно', 'двух', 'трёх', 'четырёх', 'пяти', 'шести', 'семи', 'восьми', 'девяти'];
  const GEN_TEENS = ['десяти', 'одиннадцати', 'двенадцати', 'тринадцати', 'четырнадцати', 'пятнадцати', 'шестнадцати',
    'семнадцати', 'восемнадцати', 'девятнадцати'];
  const GEN_TENS = ['', '', 'двадцати', 'тридцати', 'сорока', 'пятидесяти', 'шестидесяти', 'семидесяти', 'восьмидесяти', 'девяноста'];
  const GEN_HUNDREDS = ['', 'сто', 'двухсот', 'трёхсот', 'четырёхсот', 'пятисот', 'шестисот', 'семисот', 'восьмисот', 'девятисот'];
  const SCALE_ORD = ['тысячный', 'миллионный', 'миллиардный'];

  function genderForm(w, g) {
    if (g === 'm') return w;
    const stem = w.slice(0, -2);
    if (w.endsWith('ий')) return stem + (g === 'f' ? 'ья' : 'ье');
    return stem + (g === 'f' ? 'ая' : 'ое');
  }

  function ordTriple(n, out) {
    const h = Math.floor(n / 100), rest = n % 100;
    if (rest === 0) { out.push(ORD_HUNDREDS[h]); return; }
    if (h) out.push(HUNDREDS[h]);
    if (rest >= 10 && rest <= 19) out.push(ORD_TEENS[rest - 10]);
    else {
      const t = Math.floor(rest / 10), u = rest % 10;
      if (u === 0) out.push(ORD_TENS[t]);
      else { if (t) out.push(TENS[t]); out.push(ORD_UNITS[u]); }
    }
  }

  function genPrefix(n) {
    if (n === 1) return '';
    const h = Math.floor(n / 100), rest = n % 100;
    let s = GEN_HUNDREDS[h];
    if (rest >= 10 && rest <= 19) s += GEN_TEENS[rest - 10];
    else s += GEN_TENS[Math.floor(rest / 10)] + GEN_UNITS[rest % 10];
    return s;
  }

  /** 5 -> 'пятый' ('m'), 'пятая' ('f'), 'пятое' ('n'); 2000 -> 'двухтысячный'. */
  function numberToOrdinal(n, g) {
    g = g || 'm';
    n = Math.trunc(Number(n));
    if (!(n >= 0 && n <= MAX)) throw new Error('number out of range');
    if (n === 0) return genderForm(ORD_UNITS[0], g);
    const groups = [];
    for (let m = n; m > 0; m = Math.floor(m / 1000)) groups.push(m % 1000);
    let k = 0;
    while (!groups[k]) k++;
    const scale = Math.pow(1000, k + 1);
    const upper = n - n % scale;
    const out = upper ? numberToWords(upper).split(' ') : [];
    if (k === 0) ordTriple(groups[0], out);
    else out.push(genPrefix(groups[k]) + SCALE_ORD[k - 1]);
    out[out.length - 1] = genderForm(out[out.length - 1], g);
    return out.join(' ');
  }

  /** English '1st', '2nd', '23rd', '11th'. */
  function ordinalSuffix(n) {
    n = Math.trunc(Number(n));
    if (n % 100 >= 11 && n % 100 <= 13) return n + 'th';
    return n + ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th');
  }


  // ---------------------------------------------------------------- spoken number words -> digits
  // "сорок пять тысяч рублей" -> "45 000 рублей" (prices, times, phone numbers heard in speech)
  const NUMW = {};
  ['ноль', 'один', 'два', 'три', 'четыре', 'пять', 'шесть', 'семь', 'восемь', 'девять'].forEach((w, i) => { NUMW[w] = [i, 1]; });
  NUMW['одна'] = [1, 1]; NUMW['одно'] = [1, 1]; NUMW['две'] = [2, 1];
  ['десять', 'одиннадцать', 'двенадцать', 'тринадцать', 'четырнадцать', 'пятнадцать', 'шестнадцать', 'семнадцать',
    'восемнадцать', 'девятнадцать'].forEach((w, i) => { NUMW[w] = [10 + i, 1.5]; });
  ['двадцать', 'тридцать', 'сорок', 'пятьдесят', 'шестьдесят', 'семьдесят', 'восемьдесят', 'девяносто']
    .forEach((w, i) => { NUMW[w] = [20 + 10 * i, 2]; });
  ['сто', 'двести', 'триста', 'четыреста', 'пятьсот', 'шестьсот', 'семьсот', 'восемьсот', 'девятьсот']
    .forEach((w, i) => { NUMW[w] = [100 * (i + 1), 3]; });
  const SCALEW = {};
  ['тысяча', 'тысячи', 'тысяч', 'тыщ', 'тыщи'].forEach(w => { SCALEW[w] = 1e3; });
  ['миллион', 'миллиона', 'миллионов'].forEach(w => { SCALEW[w] = 1e6; });
  ['миллиард', 'миллиарда', 'миллиардов'].forEach(w => { SCALEW[w] = 1e9; });

  const ORDINAL_WORD = /^(перв|втор|трет|четв[её]рт|пят|шест|седьм|восьм|девят|десят|[а-яё]*надцат|двадцат|тридцат|сороков|[а-яё]*десят|девяност|[а-яё]*сот|[а-яё]*тысячн|[а-яё]*миллионн)(ый|ой|ий|ая|ое|ье|ья|ого|его|ому|ему|ом|ем|ую|ых|их|ые|ие|ым|им|ой)$/;

  function groupDigits(n) {
    const s = String(n);
    return n >= 10000 ? s.replace(/\B(?=(\d{3})+(?!\d))/g, ' ') : s;
  }

  /** Replaces runs of Russian number words with digits. Returns { text, changed }. */
  function wordsToDigits(text) {
    const toks = text.split(/(\s+)/);
    const out = [];
    let changed = false;
    let i = 0;
    while (i < toks.length) {
      const m = /^([А-Яа-яЁё]+)([^А-Яа-яЁё]*)$/.exec(toks[i]);
      const w = m && m[1].toLowerCase();
      if (!w || !(w in NUMW || w in SCALEW)) { out.push(toks[i]); i++; continue; }
      // collect one number: [hundreds][tens][units] [scale] ... with each part only once, biggest first
      let total = 0, group = 0, last = 9, lastScale = Infinity, words = 0, j = i, tail = '', endTok = i;
      while (j < toks.length) {
        const mm = /^([А-Яа-яЁё]+)([^А-Яа-яЁё]*)$/.exec(toks[j]);
        const ww = mm && mm[1].toLowerCase();
        if (!ww) break;
        if (ww in NUMW) {
          const [v, cls] = NUMW[ww];
          if (cls >= last || (cls === 1 && last === 1.5) || (v === 0 && words)) break;
          group += v; last = cls;
        } else if (ww in SCALEW) {
          const sc = SCALEW[ww];
          if (sc >= lastScale) break;
          total += (group || 1) * sc; group = 0; last = 9; lastScale = sc;
        } else break;
        words++; endTok = j; tail = mm[2];
        if (tail) break;                       // punctuation ends the number
        j += 2;                                // skip the whitespace token
      }
      const value = total + group;
      // "двадцать пятый" is an ordinal (date, floor): leave the whole phrase as words
      const after = toks[endTok + 2] && /^([А-Яа-яЁё]+)/.exec(toks[endTok + 2]);
      const ordinalNext = !tail && after && ORDINAL_WORD.test(after[1].toLowerCase());
      if (!ordinalNext && (words >= 2 || value >= 10)) {
        out.push(groupDigits(value) + tail);
        changed = true;
      } else {
        for (let k = i; k <= endTok; k++) out.push(toks[k]);
      }
      i = endTok + 1;
    }
    return { text: out.join(''), changed };
  }

  const api = { SpmTokenizer, MarianTranslator, prepareText, StressDict, Pronouncer, numberToWords, numberToOrdinal, ordinalSuffix, formatDigits, wordsToDigits, MAX_NUMBER: MAX };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FT = api;
})(typeof self !== 'undefined' ? self : this);
