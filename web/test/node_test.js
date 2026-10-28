// Checks the web app's JavaScript gives exactly the same results as the Python/Java versions.
// usage: node node_test.js <repo_root> [real-model cases: modelDir::ref.tsv ...]
'use strict';
const fs = require('fs');
const path = require('path');
const FT = require('../js/core.js');

const root = process.argv[2];
const extra = process.argv.slice(3);
let failures = 0;
function check(name, got, want) {
  if (got !== want) {
    failures++;
    if (failures < 15) console.log(`MISMATCH ${name}\n  want: ${want}\n  got:  ${got}`);
  }
}
const lines = f => fs.readFileSync(f, 'utf8').split('\n').filter(l => l.length);

async function main() {
  const tiny = path.join(root, 'core/src/test/resources/tiny');
  // 1. tokenizer vs sentencepiece
  const tok = new FT.SpmTokenizer(fs.readFileSync(path.join(tiny, 'spm_source.tsv'), 'utf8'));
  let n = 0;
  for (const line of fs.readFileSync(path.join(tiny, 'pieces.tsv'), 'utf8').split('\n')) {
    if (!line) continue;
    const tab = line.lastIndexOf('\t');
    check('pieces ' + line.slice(0, tab), tok.encode(line.slice(0, tab)).join('\u001f'), line.slice(tab + 1));
    n++;
  }
  console.log(`tokenizer: ${n} cases`);

  // 2. pronunciation + numbers
  const env = k => process.env[k];
  if (env('STRESS') && env('PRON_REF')) {
    const learn = JSON.parse(fs.readFileSync(env('LEARN_JSON'), 'utf8'));
    const pr = new FT.Pronouncer(new FT.StressDict(new Uint8Array(fs.readFileSync(env('STRESS')))), learn.pron_exceptions);
    let k = 0;
    for (const l of lines(env('PRON_REF'))) {
      const tab = l.indexOf('\t');
      check('pron ' + l.slice(0, tab), pr.pronounce(l.slice(0, tab)), l.slice(tab + 1));
      k++;
    }
    console.log(`Pronunciation (JS): ${k} cases, e.g. привет -> ${pr.pronounce('привет')}`);
  }
  if (env('NUM_REF')) {
    let k = 0;
    for (const l of lines(env('NUM_REF'))) {
      const [num, words] = l.split('\t');
      check('number ' + num, FT.numberToWords(Number(num)), words);
      k++;
    }
    console.log(`Numbers (JS): ${k} cases`);
  }

  // 3. translator (onnxruntime-web, same as the browser)
  const ort = require('onnxruntime-web');
  ort.env.wasm.numThreads = 1;
  const cases = [`${tiny}::${path.join(tiny, 'reference.tsv')}`].concat(extra);
  for (const c of cases) {
    const [dir, ref] = c.split('::');
    const rd = f => fs.readFileSync(path.join(dir, f));
    const tr = await FT.MarianTranslator.create(ort, {
      props: rd('model.properties').toString('utf8'), spm: rd('spm_source.tsv').toString('utf8'),
      vocab: rd('vocab.txt').toString('utf8'), encoder: rd('encoder.onnx'), decoder: rd('decoder.onnx'),
    });
    let k = 0, t0 = Date.now();
    for (const l of lines(ref)) {
      const [text, src, outIds] = l.split('\t');
      const ids = tr.encodeIds(FT.prepareText(text));
      check('src ids ' + text, ids.join(' '), src);
      const out = await tr.translateIds(ids);
      check('out ids ' + text, out.join(' '), outIds);
      if (k < 3) console.log(`  ${text} -> ${tr.decodeIds(out)}`);
      k++;
    }
    console.log(`translator (JS) ${path.basename(dir)}: ${k} cases, avg ${((Date.now() - t0) / k).toFixed(0)} ms`);
  }
  if (failures) { console.log(`${failures} mismatches`); process.exit(1); }
  console.log('ALL JS CHECKS PASSED');
}
main().catch(e => { console.error(e); process.exit(1); });
