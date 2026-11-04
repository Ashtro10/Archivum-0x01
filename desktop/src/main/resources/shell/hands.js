/*
 * CLU · Hands: camera-based palm and finger interaction. The camera picture fills the window; CLU's widgets float over
 * it as glass panels; MediaPipe's hand landmarker (run locally from CLU's verified gesture pack) finds 21 landmarks per
 * hand in every frame; CluGestures turns them into a cursor, taps, drags and two-hand resizing. Widgets show real data
 * from CLU: core status, weather, the applications CLU has installed, and the time.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (t, c, x) => { const e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; };
  let token = null, landmarker = null, gestures = CluGestures.create(), debug = false, lastHands = [], frames = 0, detected = 0;
  const state = { phase: "starting", error: "", widgets: [], events: [], fps: 0 };
  window.__cluHands = { state: () => Object.assign({}, state, { hands: lastHands, frames, detected }), gestures };

  // ---------------------------------------------------------------- session and API (CLU's own, same origin)
  async function session() {
    const m = /[#&]code=([A-Za-z0-9_-]+)/.exec(location.hash);
    if (m) { history.replaceState(null, "", location.pathname + location.search);
      const r = await fetch("/v1/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: m[1] }) }).catch(() => null);
      if (r && r.ok) { const t = (await r.json()).token; try { sessionStorage.setItem("clu-token", t); } catch (e) {} return t; } }
    try { return sessionStorage.getItem("clu-token"); } catch (e) { return null; }
  }
  async function api(path, opts) {
    opts = opts || {};
    const r = await fetch(path, { method: opts.method || "GET", headers: Object.assign({ Authorization: "Bearer " + token }, opts.json ? { "Content-Type": "application/json" } : {}),
      body: opts.json ? JSON.stringify(opts.json) : undefined });
    const d = (r.headers.get("Content-Type") || "").includes("json") ? await r.json() : null;
    if (!r.ok) throw new Error((d && d.error) || "HTTP " + r.status);
    return d;
  }
  function status(text, level) { $("status").textContent = text; $("dot").className = "dot" + (level ? " " + level : ""); }

  // ---------------------------------------------------------------- widgets (real data)
  const W = [];
  function widget(id, title, x, y, render, onTap) {
    const box = el("section", "widget"); box.dataset.widget = id; box.style.left = x + "px"; box.style.top = y + "px";
    box.append(el("h3", null, title)); const body = el("div", "body"); box.append(body); $("widgets").append(box);
    const w = { id, box, body, render, onTap, scale: 1 }; W.push(w); render(w); return w;
  }
  async function coreRender(w) {
    try { const s = await api("/v1/status"); const m = s.model || {};
      w.body.replaceChildren(el("div", "big", (m.status || "?").toLowerCase().replace(/_/g, " ")), el("div", "muted", m.name ? m.name : "no model selected"),
        el("div", "muted", `memory ${s.governor && s.governor.systemAvailableMB ? s.governor.systemAvailableMB + " MB free" : ""}`.trim()),
        el("div", "muted", `${s.conversations} conversations · ${s.pendingSuggestions} suggestions · ${s.activeTasks} tasks`)); }
    catch (e) { w.body.replaceChildren(el("div", "muted", "status unavailable: " + e.message)); }
  }
  let place = null;
  async function weatherRender(w) {
    try {
      if (!place) { const h = await api("/v1/kit/home").catch(() => null); place = h && h.place;
        if (!place) { const caps = await api("/v1/capabilities?kind=weather").catch(() => []);
          for (const c of caps) { const cfg = await api(`/v1/capabilities/${c.id}/config`).catch(() => null); if (cfg && cfg.place) { place = cfg.place; break; } } } }
      if (!place) { w.body.replaceChildren(el("div", "muted", "No place known: set your home location in Settings, or choose one in a weather experience.")); return; }
      const d = await api(`/v1/kit/weather?lat=${place.lat}&lon=${place.lon}&name=${encodeURIComponent(place.name)}`);
      if (!d.ok) { w.body.replaceChildren(el("div", "muted", place.name), el("div", "muted", "Live weather unavailable: " + d.reason)); return; }
      w.body.replaceChildren(el("div", "muted", place.name), el("div", "big", Math.round(d.current.temperature) + "°C"),
        el("div", "muted", `${d.stale ? "stale · " : ""}wind ${Math.round(d.current.wind || 0)} km/h · humidity ${d.current.humidity ?? "?"}%`));
    } catch (e) { w.body.replaceChildren(el("div", "muted", "weather unavailable: " + e.message)); }
  }
  async function launcherRender(w) {
    try { const caps = (await api("/v1/capabilities")).filter((c) => c.enabled && c.installed);
      const ul = el("ul"); caps.forEach((c) => { const li = el("li", null, c.name); li.dataset.cap = c.id; li.dataset.url = c.url; ul.append(li); });
      w.body.replaceChildren(caps.length ? ul : el("div", "muted", "CLU hasn't built any applications yet."), el("div", "muted", "tap an item to open it")); }
    catch (e) { w.body.replaceChildren(el("div", "muted", "unavailable: " + e.message)); }
  }
  function clockRender(w) { const d = new Date(); w.body.replaceChildren(el("div", "big", d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })), el("div", "muted", d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" }))); }

  function openCapability(id, url) {
    state.events.push({ type: "open", cap: id });
    if (window.parent !== window) window.parent.postMessage({ type: "clu-open-capability", capId: id }, location.origin); else location.href = url;
  }

  // ---------------------------------------------------------------- interaction
  const cursorEls = new Map(); let grabbed = new Map(); // hand -> {w, offX, offY}
  function hit(x, y) { // screen px → [widget, list item]
    const t = document.elementsFromPoint(x, y); const box = t.find((n) => n.classList && n.classList.contains("widget"));
    return box ? [W.find((w) => w.box === box), t.find((n) => n.tagName === "LI" && n.dataset.cap)] : [null, null];
  }
  function handle(result) {
    const vw = innerWidth, vh = innerHeight;
    lastHands = result.hands;
    // cursors
    const live = new Set();
    result.hands.forEach((h) => { live.add(h.id); let c = cursorEls.get(h.id);
      if (!c) { c = el("div", "cursor"); c.append(el("div", "label")); $("cursors").append(c); cursorEls.set(h.id, c); }
      c.style.left = h.x * vw + "px"; c.style.top = h.y * vh + "px"; c.classList.toggle("pinched", h.pinched);
      c.querySelector(".label").textContent = debug ? `${h.pose} ${h.pinchRatio.toFixed(2)}` : ""; });
    for (const [id, c] of cursorEls) if (!live.has(id)) { c.remove(); cursorEls.delete(id); }
    // hover
    document.querySelectorAll(".hover").forEach((n) => n.classList.remove("hover"));
    result.hands.forEach((h) => { const [w, li] = hit(h.x * vw, h.y * vh); if (w) w.box.classList.add("hover"); if (li) li.classList.add("hover"); });
    // gestures
    for (const e of result.events) {
      state.events.push({ type: e.type, hand: e.hand }); if (state.events.length > 200) state.events.shift();
      const px = e.x * vw, py = e.y * vh;
      if (e.type === "tap") { const [w, li] = hit(px, py);
        if (li) openCapability(li.dataset.cap, li.dataset.url); else if (w) { w.box.classList.remove("flash"); void w.box.offsetWidth; w.box.classList.add("flash"); w.onTap && w.onTap(w); } }
      else if (e.type === "pinchstart") { const [w] = hit(px, py); if (w) { const r = w.box.getBoundingClientRect(); grabbed.set(e.hand, { w, offX: px - r.left, offY: py - r.top }); w.box.classList.add("grabbed"); } }
      else if (e.type === "drag") { const g = grabbed.get(e.hand); if (g && grabbed.size === 1) {
          g.w.box.style.left = Math.max(0, Math.min(vw - 60, px - g.offX)) + "px"; g.w.box.style.top = Math.max(40, Math.min(vh - 60, py - g.offY)) + "px"; } }
      else if (e.type === "pinchend") { const g = grabbed.get(e.hand); if (g) { g.w.box.classList.remove("grabbed"); savePositions(); } grabbed.delete(e.hand); }
      else if (e.type === "scale") { const gs = [...grabbed.values()]; if (gs.length === 2 && gs[0].w === gs[1].w) { const w = gs[0].w;
          w.scale = Math.max(0.6, Math.min(2.2, (w.baseScale || (w.baseScale = w.scale)) * e.factor)); w.box.style.transform = `scale(${w.scale})`; } }
      if (e.type === "pinchend") W.forEach((w) => { w.baseScale = null; });
    }
  }
  function savePositions() { try { localStorage.setItem("clu-hands-layout", JSON.stringify(W.map((w) => ({ id: w.id, left: w.box.style.left, top: w.box.style.top, scale: w.scale })))); } catch (e) {} }
  function loadPositions() { try { (JSON.parse(localStorage.getItem("clu-hands-layout") || "[]")).forEach((p) => { const w = W.find((x) => x.id === p.id);
    if (w) { w.box.style.left = p.left; w.box.style.top = p.top; w.scale = p.scale || 1; w.box.style.transform = `scale(${w.scale})`; } }); } catch (e) {} }

  // ---------------------------------------------------------------- calibration: hold the fingertip still on two marks
  async function calibrate() {
    if (!landmarker) return;
    const marks = [{ x: 0.12, y: 0.15 }, { x: 0.88, y: 0.85 }], got = [];
    for (const m of marks) {
      const dot = el("div", "calib"); dot.style.left = m.x * 100 + "%"; dot.style.top = m.y * 100 + "%"; document.body.append(dot);
      status("Calibrating: hold your index fingertip on the circle", "warn");
      const sample = await new Promise((res) => { let still = null, since = 0;
        const iv = setInterval(() => { const h = rawTip; if (!h) return; if (still && Math.hypot(h.x - still.x, h.y - still.y) < 0.015) { if (performance.now() - since > 1200) { clearInterval(iv); res(h); } } else { still = h; since = performance.now(); } }, 50);
        setTimeout(() => { clearInterval(iv); res(null); }, 15000); });
      dot.remove(); if (!sample) { status("Calibration cancelled: no steady hand seen", "warn"); return; } got.push(sample);
    }
    if (gestures.calibrate(got[0], got[1])) { try { localStorage.setItem("clu-hands-box", JSON.stringify(gestures.box())); } catch (e) {} status("Calibrated", "on"); }
    else status("Calibration needs two points further apart", "warn");
  }

  // ---------------------------------------------------------------- camera + MediaPipe
  let rawTip = null;
  function softwareGraphics() {
    try { const gl = document.createElement("canvas").getContext("webgl2") || document.createElement("canvas").getContext("webgl"); if (!gl) return true;
      const ext = gl.getExtension("WEBGL_debug_renderer_info"); const r = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
      return /swiftshader|llvmpipe|software|basic render/i.test(String(r)); } catch (e) { return true; }
  }
  async function startTracking() {
    status("Loading hand tracking…");
    const vision = await import("/shell/gesture/vision_bundle.mjs");
    const fileset = await vision.FilesetResolver.forVisionTasks("/shell/gesture");
    // The GPU delegate needs real graphics hardware; on a software renderer (no GPU driver) the CPU path is much faster.
    const delegate = softwareGraphics() ? "CPU" : "GPU"; state.delegate = delegate;
    const options = (d) => ({ baseOptions: { modelAssetPath: "/shell/gesture/hand_landmarker.task", delegate: d },
      runningMode: "VIDEO", numHands: 2, minHandDetectionConfidence: 0.5, minHandPresenceConfidence: 0.5, minTrackingConfidence: 0.5 });
    landmarker = await vision.HandLandmarker.createFromOptions(fileset, options(delegate))
      .catch(() => { state.delegate = "CPU"; return vision.HandLandmarker.createFromOptions(fileset, options("CPU")); });
    status("Opening the camera…");
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: "user" }, audio: false }); }
    catch (e) {
      state.phase = "camera-error"; state.error = e.name;
      const why = e.name === "NotAllowedError" ? "Camera permission was refused. Allow the camera for CLU in the browser/window settings and reload."
        : e.name === "NotFoundError" || e.name === "OverconstrainedError" ? "No camera was found on this computer." : e.name === "NotReadableError" ? "The camera is in use by another program." : "The camera could not be opened (" + e.name + ").";
      showSetup([el("h2", null, "Camera unavailable"), el("p", null, why)]); status(why, "warn"); throw e;
    }
    const video = $("cam"); video.srcObject = stream; await video.play();
    state.phase = "tracking"; status("Tracking: show your hand", "on");
    let last = -1, t0 = performance.now();
    const canvas = $("overlay"), ctx = canvas.getContext("2d");
    function loop() {
      requestAnimationFrame(loop);
      if (video.readyState < 2 || video.currentTime === last) return;
      last = video.currentTime; frames++;
      const res = landmarker.detectForVideo(video, performance.now());
      const sets = res.landmarks || [], ids = (res.handedness || res.handednesses || []).map((h, i) => (h && h[0] && h[0].categoryName) || String(i));
      if (sets.length) detected++;
      rawTip = sets[0] ? sets[0][8] : null;
      handle(gestures.update(sets, ids, performance.now()));
      state.fps = Math.round(frames / ((performance.now() - t0) / 1000));
      canvas.width = innerWidth; canvas.height = innerHeight; ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (debug) { // landmarks over the hand: the picture is mirrored and cropped to cover the window (object-fit: cover)
        const k = Math.max(canvas.width / video.videoWidth, canvas.height / video.videoHeight), ox = (canvas.width - video.videoWidth * k) / 2, oy = (canvas.height - video.videoHeight * k) / 2;
        ctx.fillStyle = "#3ed6e6"; sets.forEach((lm) => lm.forEach((p) => { ctx.beginPath(); ctx.arc(ox + (1 - p.x) * video.videoWidth * k, oy + p.y * video.videoHeight * k, 3, 0, 7); ctx.fill(); })); }
      state.landmarks = sets.map((lm) => lm.length);
    }
    loop();
  }

  function showSetup(children) { const s = $("setup"); s.replaceChildren(...children); s.classList.remove("hidden"); }

  async function ensurePack() {
    const g = await api("/v1/gesture");
    if (g.installed) return true;
    state.phase = "needs-pack";
    const mb = (g.bytes / 1e6).toFixed(1);
    const btn = el("button", null, `Download ${mb} MB`); btn.type = "button";
    const note = el("p", "muted", g.progress || "");
    showSetup([el("h2", null, "Hand tracking is not installed yet"),
      el("p", null, `CLU uses Google MediaPipe's hand landmarker (${g.licence}). It is downloaded once (${mb} MB) from ${g.sources.join(" and ")}, every file is checked against a pinned SHA-256, and it then runs on this computer only. The camera picture never leaves it.`),
      btn, note]);
    status("Hand tracking needs a one-time download", "warn");
    return new Promise((resolve) => {
      btn.addEventListener("click", async () => { btn.disabled = true;
        try { await api("/v1/gesture/install", { method: "POST", json: { confirm: true } }); } catch (e) { note.textContent = e.message; btn.disabled = false; return; }
        const iv = setInterval(async () => { const s = await api("/v1/gesture").catch(() => null); if (!s) return; note.textContent = s.progress;
          if (s.installed) { clearInterval(iv); $("setup").classList.add("hidden"); resolve(true); } else if (/failed/.test(s.progress)) { clearInterval(iv); btn.disabled = false; } }, 1000); });
    });
  }

  async function main() {
    token = await session();
    if (!token) { status("Not connected to CLU: open Hands from CLU's window", "warn"); state.phase = "no-session"; return; }
    widget("core", "Core", 40, 70, coreRender, (w) => w.render(w));
    widget("weather", "Weather", 40, 260, weatherRender, (w) => w.render(w));
    widget("apps", "Applications", innerWidth - 300, 70, launcherRender, (w) => w.render(w));
    widget("clock", "Time", innerWidth - 300, 330, clockRender, (w) => w.render(w));
    loadPositions();
    try { const b = JSON.parse(localStorage.getItem("clu-hands-box") || "null"); if (b) gestures.setBox(b); } catch (e) {}
    setInterval(() => W.forEach((w) => w.id !== "weather" && w.render(w)), 5000);
    setInterval(() => { const w = W.find((x) => x.id === "weather"); w && w.render(w); }, 10 * 60_000);
    $("calibrate").addEventListener("click", calibrate);
    $("debug").addEventListener("click", () => { debug = !debug; });
    addEventListener("keydown", (e) => { if (e.key === "c" || e.key === "C") calibrate(); if (e.key === "d" || e.key === "D") debug = !debug; });
    try {
      if (!window.isSecureContext || !navigator.mediaDevices) throw Object.assign(new Error("no camera API"), { name: "NotSupportedError" });
      if (!(await ensurePack())) return;
      await startTracking();
    } catch (e) {
      if (state.phase === "tracking") return;
      if (state.phase !== "camera-error") { state.phase = "error"; state.error = String(e && e.message || e); status("Hand tracking could not start: " + state.error, "warn");
        showSetup([el("h2", null, "Hand tracking could not start"), el("p", null, state.error)]); }
    }
  }
  main();
})();
