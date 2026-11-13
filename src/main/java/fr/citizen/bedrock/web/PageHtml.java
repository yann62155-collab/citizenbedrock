package fr.citizen.bedrock.web;

/** Page web des cinématiques (thème du royaume : noir, or ancien, rouge sombre). */
final class PageHtml {
    private PageHtml() {
    }

    static final String HTML = """
            <!doctype html>
            <html lang="fr">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Cinématiques de Citizen</title>
            <style>
              :root { --bg:#0d0b09; --panel:#17130f; --gold:#c9a24a; --gold-soft:#8a6f35; --red:#7a1f1f; --text:#efe6d2; --muted:#a3967d; }
              * { box-sizing:border-box; }
              html,body { margin:0; height:100%; background:var(--bg); color:var(--text);
                font-family: Georgia, "Times New Roman", serif; }
              body { display:flex; flex-direction:column; align-items:center; min-height:100%; padding:16px; }
              header { text-align:center; margin:8px 0 16px; }
              h1 { margin:0; font-size:1.5rem; color:var(--gold); letter-spacing:.04em; font-weight:normal; }
              .sub { color:var(--muted); font-size:.9rem; margin-top:4px; }
              .screen { width:100%; max-width:960px; aspect-ratio:16/9; background:#000; border:1px solid var(--gold-soft);
                border-radius:6px; position:relative; overflow:hidden; box-shadow:0 0 0 4px var(--panel); }
              canvas { width:100%; height:100%; display:block; }
              .overlay { position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center;
                gap:14px; text-align:center; padding:20px; background:rgba(13,11,9,.88); }
              .overlay p { margin:0; max-width:34ch; line-height:1.45; }
              button { font:inherit; font-size:1.1rem; color:var(--bg); background:var(--gold); border:none; border-radius:4px;
                padding:12px 26px; cursor:pointer; }
              button:active { transform:translateY(1px); }
              .title { margin-top:12px; color:var(--gold); min-height:1.4em; text-align:center; }
              .hint { color:var(--muted); font-size:.85rem; margin-top:6px; text-align:center; max-width:60ch; }
              .dot { display:inline-block; width:8px; height:8px; border-radius:50%; background:var(--red); margin-right:6px; vertical-align:middle; }
              .live .dot { background:#c0392b; box-shadow:0 0 8px #c0392b; }
            </style>
            </head>
            <body>
              <header>
                <h1>Cinématiques du Royaume</h1>
                <div class="sub">Pour les joueurs console et téléphone de Citizen</div>
              </header>
              <div class="screen">
                <canvas id="c" width="1280" height="720"></canvas>
                <div class="overlay" id="ov">
                  <p id="msg">Appuie sur « Regarder » une fois : la cinématique démarrera ensuite toute seule, en même temps que sur le serveur.</p>
                  <button id="go">Regarder</button>
                </div>
              </div>
              <div class="title" id="t"><span class="dot"></span>En attente d'une cinématique…</div>
              <div class="hint">Garde cette page ouverte et le son allumé. Si l'image s'arrête, recharge la page.</div>
            <script>
            (() => {
              const cv = document.getElementById('c'), ctx = cv.getContext('2d');
              const ov = document.getElementById('ov'), msg = document.getElementById('msg'), go = document.getElementById('go');
              const title = document.getElementById('t');
              const audio = new Audio(); audio.preload = 'auto';
              let unlocked = false, st = null, offset = 0, sha = null, frames = new Map(), loading = new Set(), audioOk = true;
              const BATCH = 48;

              go.onclick = () => {
                unlocked = true;
                audio.src = 'data:audio/ogg;base64,'; audio.play().catch(() => {});
                ov.style.display = 'none';
                if (!st || !st.playing) { showWaiting(); }
              };

              function showWaiting() {
                ctx.fillStyle = '#000'; ctx.fillRect(0, 0, cv.width, cv.height);
                ctx.fillStyle = '#8a6f35'; ctx.font = '36px Georgia'; ctx.textAlign = 'center';
                ctx.fillText('En attente de la prochaine cinématique…', cv.width / 2, cv.height / 2);
              }

              function serverNow() { return Date.now() + offset; }
              function elapsed() { return st ? (serverNow() - st.startedAt) / 1000 : 0; }

              async function poll() {
                try {
                  const r = await fetch('/status', { cache: 'no-store' });
                  const s = await r.json();
                  offset = s.now - Date.now();
                  const changed = !st || s.sha !== st.sha || s.startedAt !== st.startedAt;
                  st = s;
                  if (s.playing) {
                    title.className = 'title live';
                    title.innerHTML = '<span class="dot"></span>' + escapeHtml(s.name.replace(/\\.[a-z0-9]{2,4}$/i, ''));
                    if (changed) start(s);
                  } else {
                    title.className = 'title';
                    title.innerHTML = '<span class="dot"></span>En attente d\\'une cinématique…';
                    if (sha) stop();
                  }
                } catch (e) { /* serveur injoignable : on réessaie */ }
              }

              function start(s) {
                sha = s.sha; frames = new Map(); loading = new Set();
                if (!unlocked) { ov.style.display = 'flex'; msg.textContent = 'Une cinématique est en cours : « ' + s.name + ' ».'; return; }
                audioOk = true;
                audio.src = '/v/' + sha + '/audio';
                audio.onerror = () => { audioOk = false; };
                audio.addEventListener('loadedmetadata', () => { audio.currentTime = Math.max(0, elapsed()); audio.play().catch(() => { audioOk = false; }); }, { once: true });
                audio.load();
              }

              function stop() { sha = null; audio.pause(); frames = new Map(); showWaiting(); }

              async function loadBatch(index) {
                const b = Math.floor(index / BATCH) * BATCH;
                if (loading.has(b) || !sha || b >= st.frames) return;
                loading.add(b);
                const mine = sha;
                try {
                  const r = await fetch('/v/' + mine + '/b/' + b);
                  const buf = new Uint8Array(await r.arrayBuffer());
                  let p = 0, i = b;
                  while (p + 4 <= buf.length) {
                    const len = (buf[p] << 24 | buf[p + 1] << 16 | buf[p + 2] << 8 | buf[p + 3]) >>> 0; p += 4;
                    const blob = new Blob([buf.subarray(p, p + len)], { type: 'image/jpeg' }); p += len;
                    if (mine !== sha) return;
                    frames.set(i++, await createImageBitmap(blob));
                  }
                } catch (e) { loading.delete(b); }
              }

              function draw(img) {
                const r = Math.min(cv.width / img.width, cv.height / img.height);
                const w = img.width * r, h = img.height * r;
                ctx.fillStyle = '#000'; ctx.fillRect(0, 0, cv.width, cv.height);
                ctx.drawImage(img, (cv.width - w) / 2, (cv.height - h) / 2, w, h);
              }

              let lastShown = -1;
              function loop() {
                requestAnimationFrame(loop);
                if (!sha || !unlocked || !st || !st.playing) return;
                const t = (audioOk && !audio.paused && audio.readyState >= 2) ? audio.currentTime : elapsed();
                const f = Math.floor(t * st.fps);
                if (f >= st.frames) return;
                loadBatch(f); loadBatch(f + BATCH);
                for (const k of frames.keys()) if (k < f - 4) { frames.get(k).close && frames.get(k).close(); frames.delete(k); }
                let img = frames.get(f);
                if (!img) for (let k = f - 1; k >= f - 6 && !img; k--) img = frames.get(k);
                if (img && f !== lastShown) { draw(img); lastShown = f; }
              }

              setInterval(() => {
                if (sha && audioOk && !audio.paused && Math.abs(audio.currentTime - elapsed()) > 0.5) audio.currentTime = Math.max(0, elapsed());
              }, 2000);

              function escapeHtml(s) { return s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

              showWaiting(); poll(); setInterval(poll, 1000); requestAnimationFrame(loop);
            })();
            </script>
            </body>
            </html>
            """;
}
