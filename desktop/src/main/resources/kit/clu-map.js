/*
 * CLU map engine (shared by every map experience): a Web-Mercator canvas map with real world geography that works
 * offline (Natural Earth country outlines and a gazetteer of ~3,000 cities, bundled with CLU), OpenStreetMap street
 * tiles through CLU when online, drag to pan, wheel/buttons to zoom, place search, pins, optional distance measuring,
 * graticule and overview inset. Each experience gives the look (colours, type, decorations); the engine is the same.
 */
(function () {
  "use strict";
  const TILE = 256, MAX_Z = 18, MIN_Z = 1;
  const rad = Math.PI / 180;
  const clampLat = (lat) => Math.max(-85.05, Math.min(85.05, lat));
  function project(lat, lon, z) { const s = TILE * Math.pow(2, z); const sin = Math.sin(clampLat(lat) * rad);
    return { x: (lon + 180) / 360 * s, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * s }; }
  function unproject(x, y, z) { const s = TILE * Math.pow(2, z); const lon = x / s * 360 - 180; const n = Math.PI - 2 * Math.PI * y / s;
    return { lat: 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))), lon }; }
  function haversine(a, b) { const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2; return 12742 * Math.asin(Math.sqrt(h)); }
  function hexRgb(h) { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }

  let worldPromise = null, citiesPromise = null;
  const loadWorld = () => worldPromise || (worldPromise = fetch("/kit/world.json").then((r) => { if (!r.ok) throw new Error("world map data missing (HTTP " + r.status + ")"); return r.json(); }));
  const loadCities = () => citiesPromise || (citiesPromise = fetch("/kit/gazetteer.json").then((r) => r.ok ? r.json() : []).catch(() => []));

  async function mount(kit, cfg) {
    const d = cfg.design, f = new Set(cfg.features), style = cfg.style, L = cfg.labels || {};
    const canvas = kit.$("mapCanvas"), ctx = canvas.getContext("2d");
    const world = await loadWorld();
    const cities = await loadCities();
    const saved = await kit.config.get();
    const state = { lat: (saved.view && saved.view.lat) || 20, lon: (saved.view && saved.view.lon) || 10, z: (saved.view && saved.view.z) || 2,
      pins: (f.has("savedpins") && Array.isArray(saved.pins)) ? saved.pins.slice(0, 200) : [], tiles: false, tilesAvailable: false, measure: [], drawn: 0 };
    const tiles = new Map(); let pending = 0, raf = 0;
    const api = { onPick: null };

    // ---- feature: which layer can be shown (street tiles need CLU to reach OpenStreetMap)
    let status = { tiles: { available: false, reason: "not checked" } };
    try { status = await kit.api("/v1/kit/status"); } catch (e) { status = { tiles: { available: false, reason: e.message } }; }
    state.tilesAvailable = !!(status.tiles && status.tiles.available);
    const tilesBox = kit.$("mapTiles"), tilesNote = kit.$("mapTilesNote");
    if (tilesBox) {
      tilesBox.disabled = !state.tilesAvailable;
      tilesBox.checked = state.tilesAvailable && saved.tiles !== false;
      state.tiles = tilesBox.checked;
      if (tilesNote) { tilesNote.textContent = state.tilesAvailable ? "Street tiles © OpenStreetMap contributors." : `Street tiles are unavailable (${status.tiles.reason}). Showing CLU's offline world map.`; tilesNote.className = "note" + (state.tilesAvailable ? "" : " warn"); }
      tilesBox.addEventListener("change", () => { state.tiles = tilesBox.checked; kit.config.set({ tiles: state.tiles }); draw(); });
    }
    kit.source(state.tiles ? "Map data © OpenStreetMap contributors · Natural Earth" : "Offline world map: Natural Earth · GeoNames cities");

    // ---- sizing
    function resize() { const r = canvas.getBoundingClientRect(); const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(1, Math.round(r.width * dpr)); canvas.height = Math.max(1, Math.round(r.height * dpr)); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); draw(); }
    const size = () => { const r = canvas.getBoundingClientRect(); return { w: r.width, h: r.height }; };
    function toScreen(lat, lon) { const { w, h } = size(); const c = project(state.lat, state.lon, state.z), p = project(lat, lon, state.z);
      const world = TILE * Math.pow(2, state.z); let dx = p.x - c.x; if (dx > world / 2) dx -= world; if (dx < -world / 2) dx += world; return { x: w / 2 + dx, y: h / 2 + (p.y - c.y) }; }
    function toLatLon(x, y) { const { w, h } = size(); const c = project(state.lat, state.lon, state.z); const ll = unproject(c.x + x - w / 2, c.y + y - h / 2, state.z);
      let lon = ((ll.lon + 540) % 360) - 180; return { lat: clampLat(ll.lat), lon }; }

    // ---- tiles (fetched through CLU's API: the page itself never contacts another site)
    function tile(z, x, y) {
      const n = Math.pow(2, z); const xx = ((x % n) + n) % n; if (y < 0 || y >= n) return null;
      const key = z + "/" + xx + "/" + y; let t = tiles.get(key);
      if (!t) {
        t = { img: null, failed: false }; tiles.set(key, t); pending++;
        kit.api(`/v1/kit/tiles/${z}/${xx}/${y}.png`, { raw: true }).then(async (r) => { if (!r.ok) throw new Error("HTTP " + r.status); t.img = await createImageBitmap(await r.blob()); })
          .catch(() => { t.failed = true; }).finally(() => { pending--; schedule(); });
        if (tiles.size > 600) { const first = tiles.keys().next().value; tiles.delete(first); }
      }
      return t;
    }

    // ---- drawing
    let parchment = null;
    function texture(w, h) {
      if (style !== "fantasy") return;
      if (!parchment || parchment.width !== Math.round(w) || parchment.height !== Math.round(h)) {
        parchment = document.createElement("canvas"); parchment.width = Math.max(1, Math.round(w)); parchment.height = Math.max(1, Math.round(h));
        const p = parchment.getContext("2d"), img = p.createImageData(parchment.width, parchment.height); let seed = 7;
        for (let i = 0; i < img.data.length; i += 4) { seed = (seed * 16807) % 2147483647; const v = (seed % 40) - 20; img.data[i] = 120 + v; img.data[i + 1] = 90 + v; img.data[i + 2] = 50 + v; img.data[i + 3] = 18; }
        p.putImageData(img, 0, 0);
      }
      ctx.drawImage(parchment, 0, 0, w, h);
    }
    function polygonPath(ring) {
      ctx.beginPath(); let prev = null;
      for (let i = 0; i < ring.length; i += 2) {
        const s = toScreen(ring[i + 1], ring[i]);
        if (prev && Math.abs(s.x - prev.x) > size().w * 0.6) { ctx.moveTo(s.x, s.y); } else if (i === 0) ctx.moveTo(s.x, s.y); else ctx.lineTo(s.x, s.y);
        prev = s;
      }
      ctx.closePath();
    }
    function draw() {
      raf = 0;
      const { w, h } = size(); if (!w || !h) return;
      ctx.fillStyle = d.water; ctx.fillRect(0, 0, w, h);
      let drawnTiles = 0;
      if (state.tiles && state.tilesAvailable) {
        const z = Math.max(0, Math.min(MAX_Z, Math.round(state.z))); const scale = Math.pow(2, state.z - z);
        const c = project(state.lat, state.lon, z); const x0 = Math.floor((c.x - w / 2 / scale) / TILE), x1 = Math.floor((c.x + w / 2 / scale) / TILE);
        const y0 = Math.floor((c.y - h / 2 / scale) / TILE), y1 = Math.floor((c.y + h / 2 / scale) / TILE);
        for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) {
          const t = tile(z, x, y); if (!t || !t.img) continue;
          ctx.drawImage(t.img, w / 2 + (x * TILE - c.x) * scale, h / 2 + (y * TILE - c.y) * scale, TILE * scale + 0.5, TILE * scale + 0.5); drawnTiles++;
        }
        if (style !== "classic") { ctx.fillStyle = d.bg + "55"; ctx.fillRect(0, 0, w, h); }
      }
      // Real geography (always drawn without tiles; borders on top of tiles).
      let drawn = 0;
      ctx.lineWidth = style === "fantasy" ? 1.4 : 1; ctx.strokeStyle = d.border; ctx.fillStyle = d.land;
      for (const c of world) for (const ring of c.r) {
        polygonPath(ring);
        if (!(state.tiles && drawnTiles)) ctx.fill();
        ctx.stroke(); drawn++;
      }
      state.drawn = drawn;
      texture(w, h);
      if (f.has("graticule") || style === "tactical" || style === "scifi") graticule(w, h);
      labels(w, h);
      decorations(w, h);
      pinsAndMeasure();
      if (f.has("minimap")) minimap(w, h);
      hud();
    }
    function schedule() { if (!raf) raf = requestAnimationFrame(draw); }
    function graticule(w, h) {
      const step = state.z < 3 ? 30 : state.z < 5 ? 10 : state.z < 7 ? 5 : 1;
      ctx.save(); ctx.strokeStyle = d.accent + (style === "tactical" ? "40" : "26"); ctx.lineWidth = 1; ctx.setLineDash(style === "tactical" ? [2, 4] : []);
      const tl = toLatLon(0, 0), br = toLatLon(w, h);
      for (let lon = Math.ceil(tl.lon / step) * step; lon <= br.lon; lon += step) { const a = toScreen(85, lon), b = toScreen(-85, lon); ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke(); }
      for (let lat = Math.ceil(br.lat / step) * step; lat <= tl.lat; lat += step) { const a = toScreen(lat, tl.lon), b = toScreen(lat, br.lon); ctx.beginPath(); ctx.moveTo(0, a.y); ctx.lineTo(w, b.y); ctx.stroke(); }
      ctx.restore();
    }
    function fontFor(px) { return `${px}px ${getComputedStyle(document.body).fontFamily}`; }
    function labels(w, h) {
      const minPop = state.z < 3 ? 8e6 : state.z < 4 ? 3e6 : state.z < 5 ? 1e6 : state.z < 6 ? 4e5 : 0;
      ctx.save(); ctx.font = fontFor(style === "fantasy" ? 13 : 11); ctx.textBaseline = "middle"; const used = [];
      for (const c of cities) {
        if (c[4] < minPop) break;
        const s = toScreen(c[2], c[3]); if (s.x < 0 || s.y < 0 || s.x > w || s.y > h) continue;
        if (used.some((u) => Math.abs(u.x - s.x) < 70 && Math.abs(u.y - s.y) < 16)) continue; used.push(s);
        ctx.fillStyle = d.accent; ctx.beginPath(); if (style === "tactical") ctx.rect(s.x - 2, s.y - 2, 4, 4); else ctx.arc(s.x, s.y, 2.2, 0, 7); ctx.fill();
        ctx.fillStyle = d.ink; const name = style === "tactical" || style === "scifi" ? c[0].toUpperCase() : c[0]; ctx.fillText(name, s.x + 5, s.y);
        if (used.length > 60) break;
      }
      ctx.restore();
    }
    function decorations(w, h) {
      ctx.save();
      if (style === "tactical") {
        ctx.strokeStyle = d.accent; ctx.lineWidth = 1; const cx = w / 2, cy = h / 2;
        for (const r of [60, 120, 180]) { ctx.globalAlpha = 0.5; ctx.beginPath(); ctx.arc(cx, cy, r, 0, 7); ctx.stroke(); }
        ctx.globalAlpha = 0.9; ctx.beginPath(); ctx.moveTo(cx - 14, cy); ctx.lineTo(cx - 4, cy); ctx.moveTo(cx + 4, cy); ctx.lineTo(cx + 14, cy); ctx.moveTo(cx, cy - 14); ctx.lineTo(cx, cy - 4); ctx.moveTo(cx, cy + 4); ctx.lineTo(cx, cy + 14); ctx.stroke();
        ctx.font = fontFor(10); ctx.fillStyle = d.accent; ctx.fillText("GRID " + state.lat.toFixed(2) + " / " + state.lon.toFixed(2), 10, 16);
      } else if (style === "fantasy") {
        const x = w - 70, y = h - 80; ctx.translate(x, y); ctx.strokeStyle = d.ink; ctx.fillStyle = d.accent; ctx.lineWidth = 1.2;
        ctx.beginPath(); ctx.arc(0, 0, 30, 0, 7); ctx.stroke();
        for (let i = 0; i < 4; i++) { ctx.rotate(Math.PI / 2); ctx.beginPath(); ctx.moveTo(0, -34); ctx.lineTo(7, 0); ctx.lineTo(-7, 0); ctx.closePath(); i === 3 ? ctx.fill() : ctx.stroke(); }
        ctx.setTransform(window.devicePixelRatio || 1, 0, 0, window.devicePixelRatio || 1, 0, 0);
        ctx.font = "italic " + fontFor(13); ctx.fillStyle = d.ink; ctx.fillText("N", x - 4, y - 40);
        ctx.strokeStyle = d.border; ctx.lineWidth = 6; ctx.strokeRect(3, 3, w - 6, h - 6); ctx.lineWidth = 1; ctx.strokeRect(10, 10, w - 20, h - 20);
      } else if (style === "scifi") {
        ctx.strokeStyle = d.accent; ctx.globalAlpha = 0.6; if (!kit.selftest) { const t = (Date.now() / 40) % h; ctx.beginPath(); ctx.moveTo(0, t); ctx.lineTo(w, t); ctx.stroke(); }
        ctx.globalAlpha = 1; for (const [x, y, sx, sy] of [[8, 8, 1, 1], [w - 8, 8, -1, 1], [8, h - 8, 1, -1], [w - 8, h - 8, -1, -1]]) { ctx.beginPath(); ctx.moveTo(x, y + 20 * sy); ctx.lineTo(x, y); ctx.lineTo(x + 20 * sx, y); ctx.stroke(); }
      }
      ctx.restore();
    }
    function pinsAndMeasure() {
      ctx.save();
      for (const p of state.pins) { const s = toScreen(p.lat, p.lon); ctx.fillStyle = d.warn; ctx.strokeStyle = d.bg; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(s.x, s.y); ctx.lineTo(s.x - 6, s.y - 14); ctx.arc(s.x, s.y - 16, 6, Math.PI * 0.8, Math.PI * 0.2); ctx.closePath(); ctx.fill(); ctx.stroke(); }
      if (state.measure.length) { ctx.strokeStyle = d.accent2; ctx.lineWidth = 2; ctx.setLineDash([6, 4]); ctx.beginPath();
        state.measure.forEach((p, i) => { const s = toScreen(p.lat, p.lon); i ? ctx.lineTo(s.x, s.y) : ctx.moveTo(s.x, s.y); }); ctx.stroke(); }
      ctx.restore();
    }
    function minimap(w, h) {
      const mw = 140, mh = 90, x = 10, y = h - mh - 40; ctx.save(); ctx.fillStyle = d.panel; ctx.strokeStyle = d.border; ctx.fillRect(x, y, mw, mh); ctx.strokeRect(x, y, mw, mh);
      const px = x + (state.lon + 180) / 360 * mw, py = y + (90 - state.lat) / 180 * mh; ctx.fillStyle = d.accent; ctx.fillRect(px - 3, py - 3, 6, 6); ctx.restore();
    }
    function hud() {
      const z = kit.$("mapZoom"); if (z) z.textContent = "z " + state.z.toFixed(1);
      const l = kit.$("mapLayer"); if (l) l.textContent = state.tiles && state.tilesAvailable ? "street tiles" : "offline map";
      const c = kit.$("mapCoords"); if (c && !c.dataset.hover) c.textContent = `${state.lat.toFixed(3)}, ${state.lon.toFixed(3)}`;
    }

    // ---- interaction
    let drag = null, saveTimer = 0;
    const saveView = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => kit.config.set({ view: { lat: state.lat, lon: state.lon, z: state.z } }), 600); };
    function panBy(dx, dy) { const c = project(state.lat, state.lon, state.z); const ll = unproject(c.x - dx, c.y - dy, state.z);
      state.lat = clampLat(ll.lat); state.lon = ((ll.lon + 540) % 360) - 180; schedule(); saveView(); }
    function zoomAt(delta, x, y) {
      const { w, h } = size(); x = x == null ? w / 2 : x; y = y == null ? h / 2 : y;
      const before = toLatLon(x, y); state.z = Math.max(MIN_Z, Math.min(MAX_Z, state.z + delta));
      const after = toScreen(before.lat, before.lon); panBy(x - after.x, y - after.y);
    }
    canvas.addEventListener("pointerdown", (e) => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; try { canvas.setPointerCapture(e.pointerId); } catch (x) {} });
    canvas.addEventListener("pointermove", (e) => {
      const r = canvas.getBoundingClientRect(); const ll = toLatLon(e.clientX - r.left, e.clientY - r.top);
      const c = kit.$("mapCoords"); if (c) { c.dataset.hover = "1"; c.textContent = `${ll.lat.toFixed(3)}, ${ll.lon.toFixed(3)}`; }
      if (!drag) return; const dx = e.clientX - drag.x, dy = e.clientY - drag.y; drag.moved += Math.abs(dx) + Math.abs(dy); drag.x = e.clientX; drag.y = e.clientY; panBy(dx, dy);
    });
    canvas.addEventListener("pointerleave", () => { const c = kit.$("mapCoords"); if (c) delete c.dataset.hover; hud(); });
    canvas.addEventListener("pointerup", (e) => {
      const was = drag; drag = null; if (!was || was.moved > 4) return;
      const r = canvas.getBoundingClientRect(); const ll = toLatLon(e.clientX - r.left, e.clientY - r.top); click(ll);
    });
    canvas.addEventListener("wheel", (e) => { e.preventDefault(); const r = canvas.getBoundingClientRect(); zoomAt(e.deltaY < 0 ? 0.5 : -0.5, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
    canvas.addEventListener("dblclick", (e) => { const r = canvas.getBoundingClientRect(); zoomAt(1, e.clientX - r.left, e.clientY - r.top); });
    kit.$("mapIn").addEventListener("click", () => zoomAt(1));
    kit.$("mapOut").addEventListener("click", () => zoomAt(-1));
    function click(ll) {
      if (f.has("measure") && kit.$("mapMeasure") && kit.$("mapMeasure").checked) {
        state.measure.push(ll); let km = 0; for (let i = 1; i < state.measure.length; i++) km += haversine(state.measure[i - 1], state.measure[i]);
        kit.$("mapMeasureOut").textContent = state.measure.length > 1 ? `${km.toFixed(km < 10 ? 2 : 0)} km over ${state.measure.length - 1} leg(s)` : "Click the next point.";
        schedule(); return;
      }
      if (api.onPick) { api.onPick(ll.lat, ll.lon); return; }
      if (f.has("pins")) addPin(ll.lat, ll.lon, `${ll.lat.toFixed(3)}, ${ll.lon.toFixed(3)}`);
    }
    if (kit.$("mapMeasure")) kit.$("mapMeasure").addEventListener("change", () => { state.measure = []; kit.$("mapMeasureOut").textContent = kit.$("mapMeasure").checked ? "Click points on the map." : ""; schedule(); });
    function renderPins() {
      const ul = kit.$("mapPins"); if (!ul) return; ul.replaceChildren();
      state.pins.forEach((p, i) => { const li = kit.el("li", null, p.name); const x = kit.el("button", null, "×"); x.type = "button"; x.setAttribute("aria-label", "Remove pin");
        x.addEventListener("click", (e) => { e.stopPropagation(); state.pins.splice(i, 1); renderPins(); persistPins(); schedule(); });
        li.addEventListener("click", () => flyTo(p.lat, p.lon, Math.max(state.z, 6))); li.append(" ", x); ul.append(li); });
    }
    const persistPins = () => { if (f.has("savedpins")) kit.config.set({ pins: state.pins }); };
    function addPin(lat, lon, name) { state.pins.push({ lat, lon, name }); renderPins(); persistPins(); schedule(); }
    function flyTo(lat, lon, z, name) { state.lat = clampLat(lat); state.lon = lon; if (z) state.z = z; schedule(); saveView(); if (name) kit.status(name, "ok"); }

    // ---- search (offline gazetteer through CLU)
    kit.$("mapSearch").addEventListener("submit", async (e) => {
      e.preventDefault(); const q = kit.$("mapQuery").value.trim(); if (!q) return;
      const ul = kit.$("mapResults"); ul.replaceChildren(kit.el("li", "note", "Searching…"));
      const list = await kit.places(q).catch((x) => { ul.replaceChildren(kit.el("li", "note warn", x.message)); return null; }); if (!list) return;
      ul.replaceChildren(...(list.length ? list.map((p) => { const li = kit.el("li", null, `${p.name}, ${p.country}`); li.addEventListener("click", () => flyTo(p.lat, p.lon, 7, p.name)); return li; })
        : [kit.el("li", "note", "No place by that name in CLU's offline gazetteer.")]));
    });

    window.addEventListener("resize", resize);
    resize(); renderPins();
    kit.status(state.tiles ? "Live street tiles" : "Offline world map", state.tiles ? "ok" : "");
    if (style === "scifi" && !kit.selftest) setInterval(schedule, 80);

    // ---- self-tests (run by CLU's verifier in a real browser)
    function pixel(lat, lon) { const s = toScreen(lat, lon); const dpr = window.devicePixelRatio || 1; const p = ctx.getImageData(Math.round(s.x * dpr), Math.round(s.y * dpr), 1, 1).data; return [p[0], p[1], p[2]]; }
    const near = (a, b, tol) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) <= tol;
    kit.test("map: real geography is drawn", async () => {
      const prevTiles = state.tiles; state.tiles = false; flyTo(0, 0, 2); draw();
      kit.check("map: country outlines drawn", state.drawn > 150, state.drawn + " outlines");
      // Land where the continents are, water where the oceans are: the map shows the real world, not decoration.
      const land = hexRgb(d.land), water = hexRgb(d.water);
      const africa = pixel(8, 22), pacific = pixel(-20, -130), brazil = pixel(-10, -52), atlantic = pixel(30, -40);
      kit.check("map: land drawn where Africa and Brazil are", near(africa, land, 60) && near(brazil, land, 60), `${africa} ${brazil} vs land ${land}`);
      kit.check("map: water drawn where the Pacific and Atlantic are", near(pacific, water, 60) && near(atlantic, water, 60), `${pacific} ${atlantic} vs water ${water}`);
      state.tiles = prevTiles; draw();
    });
    kit.test("map: drag pans", async () => {
      const r = canvas.getBoundingClientRect(); const before = state.lon;
      const ev = (type, x, y) => canvas.dispatchEvent(new PointerEvent(type, { clientX: r.left + x, clientY: r.top + y, bubbles: true, pointerId: 1 }));
      ev("pointerdown", 300, 200); ev("pointermove", 260, 200); ev("pointermove", 200, 200); ev("pointerup", 200, 200);
      kit.check("map: drag pans the view", Math.abs(state.lon - before) > 5, `${before.toFixed(2)} → ${state.lon.toFixed(2)}`);
    });
    kit.test("map: zoom", async () => {
      const z0 = state.z; kit.$("mapIn").click(); const z1 = state.z;
      canvas.dispatchEvent(new WheelEvent("wheel", { deltaY: 100, clientX: canvas.getBoundingClientRect().left + 100, clientY: canvas.getBoundingClientRect().top + 100, bubbles: true, cancelable: true }));
      kit.check("map: + and the mouse wheel zoom", z1 === z0 + 1 && state.z === z1 - 0.5, `${z0} → ${z1} → ${state.z}`);
    });
    kit.test("map: place search", async () => {
      kit.$("mapQuery").value = "Paris"; kit.$("mapSearch").dispatchEvent(new Event("submit", { cancelable: true }));
      for (let i = 0; i < 50 && !kit.$("mapResults").querySelector("li:not(.note)"); i++) await kit.sleep(100);
      const first = kit.$("mapResults").querySelector("li:not(.note)"); if (first) first.click();
      kit.check("map: search finds Paris and centres on it", first && Math.abs(state.lat - 48.85) < 0.3 && Math.abs(state.lon - 2.35) < 0.3, first ? first.textContent + ` → ${state.lat.toFixed(2)}, ${state.lon.toFixed(2)}` : "no result");
    });
    if (f.has("pins")) kit.test("map: pins", async () => {
      const n = state.pins.length; const r = canvas.getBoundingClientRect();
      canvas.dispatchEvent(new PointerEvent("pointerdown", { clientX: r.left + 150, clientY: r.top + 150, bubbles: true, pointerId: 2 }));
      canvas.dispatchEvent(new PointerEvent("pointerup", { clientX: r.left + 150, clientY: r.top + 150, bubbles: true, pointerId: 2 }));
      const ok = api.onPick ? true : state.pins.length === n + 1;
      kit.check("map: a click drops a pin", ok, `${n} → ${state.pins.length}`);
      if (!api.onPick && state.pins.length > n) { state.pins.pop(); renderPins(); persistPins(); schedule(); }
    });
    if (f.has("measure")) kit.test("map: measuring", async () => {
      kit.$("mapMeasure").checked = true; kit.$("mapMeasure").dispatchEvent(new Event("change"));
      click({ lat: 51.507, lon: -0.128 }); click({ lat: 48.857, lon: 2.352 });
      const km = parseFloat(kit.$("mapMeasureOut").textContent);
      kit.check("map: London to Paris measures about 344 km", km > 330 && km < 360, kit.$("mapMeasureOut").textContent);
      kit.$("mapMeasure").checked = false; kit.$("mapMeasure").dispatchEvent(new Event("change"));
    });
    if (f.has("tiles")) kit.test("map: street tiles", async () => {
      if (!state.tilesAvailable) { kit.unverified.push("street tiles: " + status.tiles.reason); kit.check("map: tiles unavailable is said plainly", tilesBox && tilesBox.disabled && /unavailable/i.test(tilesNote.textContent), tilesNote && tilesNote.textContent); return; }
      state.tiles = true; draw(); for (let i = 0; i < 60 && pending > 0; i++) await kit.sleep(100); draw();
      const loaded = [...tiles.values()].filter((t) => t.img).length;
      kit.check("map: street tiles load through CLU", loaded > 0, loaded + " tiles");
    });

    Object.assign(api, {
      flyTo, zoom: () => state.z, center: () => ({ lat: state.lat, lon: state.lon }), addPin, redraw: draw,
    });
    return api;
  }

  window.CluMap = { mount, project, unproject, haversine };
})();
