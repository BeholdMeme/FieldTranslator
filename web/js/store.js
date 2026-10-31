/*
 * Offline file store: every model file is kept in Cache Storage after the first download.
 * Shared by the page and the workers (both have `caches`).
 */
(function (root) {
  'use strict';
  const CACHE = 'ft-models-v1';

  // scripts must be stored with a script type or the browser refuses to run them from the cache
  function typeOf(path) {
    const p = path.replace(/~[0-9a-f]+$/, '');
    if (/\.m?js$/.test(p)) return 'text/javascript';
    if (/\.wasm$/.test(p)) return 'application/wasm';
    if (/\.json$/.test(p)) return 'application/json';
    return 'application/octet-stream';
  }

  async function getFile(url, asText) {
    let res = null;
    try {
      const c = await caches.open(CACHE);
      res = await c.match(url);
      if (!res) {
        const net = await fetch(url);
        if (!net.ok) throw new Error('download failed: ' + url + ' (' + net.status + ')');
        await c.put(url, net.clone());
        res = net;
      }
    } catch (e) {
      // Cache Storage unavailable (private mode etc.): plain fetch
      if (!res) {
        res = await fetch(url);
        if (!res.ok) throw e;
      }
    }
    return asText ? res.text() : new Uint8Array(await res.arrayBuffer());
  }

  async function isCached(url) {
    try {
      const c = await caches.open(CACHE);
      return !!(await c.match(url));
    } catch (e) {
      return false;
    }
  }

  /** Downloads files [{path,size}] (relative to base) with progress(doneBytes, totalBytes). */
  async function downloadAll(base, files, progress) {
    const c = await caches.open(CACHE);
    const total = files.reduce((a, f) => a + f.size, 0);
    let done = 0;
    for (const f of files) {
      const url = base + f.path;
      if (await c.match(url)) { done += f.size; progress(done, total); continue; }
      let attempt = 0;
      for (;;) {
        try {
          const res = await fetch(url, { cache: 'no-store' });
          if (!res.ok) throw new Error(res.status + ' ' + url);
          // stream to report progress
          const reader = res.body.getReader();
          const chunks = [];
          let got = 0;
          for (;;) {
            const { done: d, value } = await reader.read();
            if (d) break;
            chunks.push(value);
            got += value.length;
            progress(done + got, total);
          }
          await c.put(url, new Response(new Blob(chunks), { headers: { 'Content-Type': typeOf(f.path) } }));
          done += f.size;
          break;
        } catch (e) {
          if (++attempt >= 3) throw e;
          await new Promise(r => setTimeout(r, 1500 * attempt));
        }
      }
    }
    progress(total, total);
  }

  root.FTStore = { CACHE, getFile, isCached, downloadAll };
})(typeof self !== 'undefined' ? self : this);
