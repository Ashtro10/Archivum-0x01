/*
 * CLU Core: the main console (PC window and Android app). Seven modules on one composition: arc data matrix, concentric
 * processing rings, source/idea container, hexagonal core control, file explorer, module grid, keyboard/command strip,
 * joined by energy conduits; a procedural sound engine and a particle engine.
 *
 * Everything shown is real: the arc reads CLU's status (memory, inference queue, records, suggestions, tasks), the rings
 * follow the actual turn stream (shell.js reports input/analysis/synthesis/output), the console shows real request traces
 * and app events, the waveform reads a real audio signal or says there is none, commands really run (or say why not).
 * A value CLU can't measure here is shown as "n/a", never invented.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const NS = "http://www.w3.org/2000/svg";
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const svg = (tag, attrs, parent) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.append(e); return e; };
  const now = () => performance.now();
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const ANDROID = /CLU-Android/.test(navigator.userAgent);

  // ------------------------------------------------------------------------------------------ state (one store)
  const state = {
    stage: "idle", stagesDone: [], stageNote: "Idle. Waiting for input.", stageAt: 0, answer: "", sources: [], suggestions: 0,
    voice: false, presence: "idle", filesOpen: true, sourceOpen: false, link: ANDROID ? "?" : "pc", model: null, platform: null,
    research: null, processing: false,
  };
  const subs = [];
  function set(patch) { Object.assign(state, patch); subs.forEach((f) => { try { f(state, patch); } catch (e) { /* one view's failure must not stop the others */ } }); }
  const on = (f) => subs.push(f);

  let api = null, show = null, root = null, built = false, visible = false;
  const reduced = () => (window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches) || (window.CluFx && CluFx.prefs && CluFx.prefs.motion === false);

  // ------------------------------------------------------------------------------------------ sound engine
  const Sound = (() => {
    const KEY = "clu-core-audio";
    const prefs = Object.assign({ volume: 0.6, ui: true, ambient: false, voice: true, mute: false }, (() => { try { return JSON.parse(localStorage.getItem(KEY) || "{}"); } catch (e) { return {}; } })());
    if (window.CluFx && CluFx.prefs && CluFx.prefs.sounds === false && !localStorage.getItem(KEY)) prefs.ui = false; // follow Settings → Interface sounds
    let ac = null, master = null, verb = null, unlocked = false, hum = null;
    const save = () => { try { localStorage.setItem(KEY, JSON.stringify(prefs)); } catch (e) { /* private mode */ } };
    function impulse(c) { // short metallic room: decaying stereo noise
      const len = Math.floor(c.sampleRate * 0.9), b = c.createBuffer(2, len, c.sampleRate);
      for (let ch = 0; ch < 2; ch++) { const d = b.getChannelData(ch); for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3.2); }
      return b;
    }
    function ctx() {
      if (!unlocked) return null; // browsers allow audio only after the user did something
      if (!ac) {
        try { ac = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; }
        master = ac.createGain(); master.connect(ac.destination);
        verb = ac.createConvolver(); verb.buffer = impulse(ac); const vg = ac.createGain(); vg.gain.value = 0.16; verb.connect(vg); vg.connect(master);
        apply();
      }
      if (ac.state === "suspended") ac.resume().catch(() => {});
      return ac;
    }
    function apply() { if (master) master.gain.value = prefs.mute ? 0 : prefs.volume * 0.5; }
    const unlock = () => { unlocked = true; document.removeEventListener("pointerdown", unlock, true); document.removeEventListener("keydown", unlock, true); };
    document.addEventListener("pointerdown", unlock, true); document.addEventListener("keydown", unlock, true);

    function voice({ type = "sine", f0 = 880, f1 = f0, dur = 0.08, gain = 0.25, pan = 0, delay = 0, wet = 0.3, attack = 0.004 }) {
      const c = ctx(); if (!c) return;
      const t = c.currentTime + delay, o = c.createOscillator(), g = c.createGain(), p = c.createStereoPanner ? c.createStereoPanner() : null;
      o.type = type; o.frequency.setValueAtTime(f0, t); o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t + dur);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(gain, t + attack); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(g); let out = g; if (p) { p.pan.value = clamp(pan, -1, 1); g.connect(p); out = p; }
      out.connect(master); if (wet > 0) { const w = c.createGain(); w.gain.value = wet; out.connect(w); w.connect(verb); }
      o.start(t); o.stop(t + dur + 0.05);
    }
    function noise({ dur = 0.03, gain = 0.12, freq = 4000, q = 2, pan = 0, delay = 0 }) {
      const c = ctx(); if (!c) return;
      const t = c.currentTime + delay, len = Math.max(1, Math.floor(c.sampleRate * dur)), b = c.createBuffer(1, len, c.sampleRate), d = b.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
      const s = c.createBufferSource(), f = c.createBiquadFilter(), g = c.createGain(), p = c.createStereoPanner ? c.createStereoPanner() : null;
      s.buffer = b; f.type = "bandpass"; f.frequency.value = freq; f.Q.value = q; g.gain.value = gain;
      s.connect(f); f.connect(g); let out = g; if (p) { p.pan.value = pan; g.connect(p); out = p; } out.connect(master); s.start(t);
    }
    // Short, clean, restrained; each synchronised with the animation that calls it.
    const LIB = {
      click: (p) => { noise({ dur: 0.018, gain: 0.10, freq: 5200, q: 6, pan: p }); voice({ type: "square", f0: 2400, f1: 1900, dur: 0.025, gain: 0.03, pan: p, wet: 0.1 }); },
      chirp: (p) => voice({ f0: 1800, f1: 2900, dur: 0.06, gain: 0.08, pan: p }),
      confirm: (p) => { voice({ f0: 1320, dur: 0.06, gain: 0.08, pan: p }); voice({ f0: 1760, dur: 0.09, gain: 0.07, pan: p, delay: 0.06 }); },
      activate: (p) => { voice({ type: "triangle", f0: 420, f1: 880, dur: 0.12, gain: 0.10, pan: p }); voice({ f0: 1320, f1: 1760, dur: 0.08, gain: 0.06, pan: p, delay: 0.1 }); noise({ dur: 0.02, gain: 0.05, freq: 900, pan: p, delay: 0.1 }); },
      materialize: (p) => { voice({ type: "sawtooth", f0: 300, f1: 1400, dur: 0.22, gain: 0.035, pan: p, wet: 0.45 }); for (let i = 0; i < 4; i++) noise({ dur: 0.012, gain: 0.05, freq: 6000 + i * 600, pan: p, delay: 0.06 + i * 0.045 }); },
      dissolve: (p) => voice({ type: "sawtooth", f0: 1300, f1: 220, dur: 0.2, gain: 0.03, pan: p, wet: 0.45 }),
      tick: (p) => noise({ dur: 0.008, gain: 0.05, freq: 7000, q: 8, pan: p }),
      listen: (p) => { voice({ f0: 660, dur: 0.07, gain: 0.08, pan: p }); voice({ f0: 990, dur: 0.1, gain: 0.08, pan: p, delay: 0.07 }); },
      record: (p) => voice({ type: "triangle", f0: 1500, dur: 0.05, gain: 0.07, pan: p }),
      processing: (p) => voice({ type: "triangle", f0: 220, f1: 330, dur: 0.18, gain: 0.05, pan: p, wet: 0.5 }),
      ready: (p) => { voice({ f0: 990, dur: 0.06, gain: 0.07, pan: p }); voice({ f0: 1485, dur: 0.12, gain: 0.06, pan: p, delay: 0.07, wet: 0.5 }); },
      warn: (p) => { voice({ type: "square", f0: 330, f1: 300, dur: 0.12, gain: 0.04, pan: p }); voice({ type: "square", f0: 330, f1: 300, dur: 0.12, gain: 0.04, pan: p, delay: 0.16 }); },
      error: (p) => voice({ type: "sawtooth", f0: 220, f1: 110, dur: 0.32, gain: 0.05, pan: p, wet: 0.35 }),
      success: (p) => { [880, 1175, 1568].forEach((f, i) => voice({ f0: f, dur: 0.12, gain: 0.06, pan: p, delay: i * 0.06, wet: 0.4 })); voice({ type: "triangle", f0: 3136, dur: 0.5, gain: 0.015, pan: p, delay: 0.18, wet: 0.7 }); },
      celebrate: (p) => { for (let i = 0; i < 7; i++) voice({ f0: 660 * Math.pow(1.26, i % 4), dur: 0.14, gain: 0.05, pan: (i % 3 - 1) * 0.6, delay: i * 0.09, wet: 0.5 }); },
    };
    const VOICE_SOUNDS = new Set(["listen", "record", "ready"]);
    /** Plays [name] panned to where it happens on screen ([x] = 0..1 across the window). */
    function play(name, x) {
      if (prefs.mute || !LIB[name]) return false;
      const isVoice = VOICE_SOUNDS.has(name);
      if (isVoice ? !prefs.voice : !prefs.ui) return false;
      if (!isVoice && window.CluVoice && CluVoice.active && CluVoice.active()) return false; // never over a spoken conversation
      LIB[name](x == null ? 0 : clamp(x * 2 - 1, -1, 1) * 0.7); return true;
    }
    /** The quiet processing hum: on only while CLU is working and Ambient is on. */
    function ambient(onNow) {
      const c = ctx();
      if (!onNow || !prefs.ambient || prefs.mute || !c) { if (hum) { const h = hum; hum = null; h.g.gain.setTargetAtTime(0.0001, h.c.currentTime, 0.3); setTimeout(() => { try { h.o1.stop(); h.o2.stop(); h.lfo.stop(); } catch (e) { /* stopped */ } }, 1200); } return; }
      if (hum) return;
      const o1 = c.createOscillator(), o2 = c.createOscillator(), f = c.createBiquadFilter(), g = c.createGain(), lfo = c.createOscillator(), lg = c.createGain();
      o1.frequency.value = 55; o2.frequency.value = 55.6; o2.type = "triangle"; f.type = "lowpass"; f.frequency.value = 240;
      lfo.frequency.value = 0.6; lg.gain.value = 0.006; lfo.connect(lg); lg.connect(g.gain);
      g.gain.value = 0.0001; g.gain.setTargetAtTime(0.018, c.currentTime, 0.5);
      o1.connect(f); o2.connect(f); f.connect(g); g.connect(master); const w = c.createGain(); w.gain.value = 0.4; g.connect(w); w.connect(verb);
      o1.start(); o2.start(); lfo.start(); hum = { c, o1, o2, g, lfo };
    }
    return { prefs, play, ambient, set: (k, v) => { prefs[k] = v; save(); apply(); if (k === "ambient" || k === "mute") ambient(state.processing); } };
  })();
  const xOf = (node) => { const r = node && node.getBoundingClientRect ? node.getBoundingClientRect() : null; return r ? (r.left + r.width / 2) / innerWidth : 0.5; };

  // ------------------------------------------------------------------------------------------ particles
  const Particles = (() => {
    let canvas = null, g = null, parts = [], raf = 0, dpr = 1;
    function attach(c) { canvas = c; g = c.getContext("2d"); }
    function size() { if (!canvas) return; dpr = Math.min(devicePixelRatio || 1, 2); const r = canvas.getBoundingClientRect(); canvas.width = r.width * dpr; canvas.height = r.height * dpr; }
    function burst(x, y, n, opts = {}) {
      if (!canvas || reduced()) return;
      if (!raf) size();
      for (let i = 0; i < n && parts.length < 420; i++) {
        const a = (opts.angle != null ? opts.angle : Math.random() * Math.PI * 2) + (Math.random() - 0.5) * (opts.spread || Math.PI * 2);
        const sp = (opts.speed || 140) * (0.35 + Math.random() * 0.9);
        parts.push({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, life: 0, max: 0.45 + Math.random() * (opts.life || 0.7), kind: Math.random() < 0.25 ? "frag" : Math.random() < 0.5 ? "streak" : "spark", rot: Math.random() * 6 });
      }
      if (!raf) { last = now(); raf = requestAnimationFrame(step); }
    }
    let last = 0;
    function step() {
      const t = now(), dt = Math.min(0.05, (t - last) / 1000); last = t;
      g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, canvas.width, canvas.height);
      parts = parts.filter((p) => (p.life += dt) < p.max);
      for (const p of parts) {
        p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= 0.96; p.vy = p.vy * 0.96 + 30 * dt; const k = 1 - p.life / p.max;
        g.globalAlpha = k; g.strokeStyle = g.fillStyle = k > 0.6 ? "#eafbff" : "#00e5ff";
        if (p.kind === "streak") { g.lineWidth = 1; g.beginPath(); g.moveTo(p.x, p.y); g.lineTo(p.x - p.vx * 0.04, p.y - p.vy * 0.04); g.stroke(); }
        else if (p.kind === "frag") { g.save(); g.translate(p.x, p.y); g.rotate(p.rot += dt * 6); g.strokeRect(-2, -2, 4, 4); g.restore(); }
        else g.fillRect(p.x - 1, p.y - 1, 2, 2);
      }
      g.globalAlpha = 1;
      raf = parts.length ? requestAnimationFrame(step) : 0;
      if (!raf) g.clearRect(0, 0, canvas.width, canvas.height);
    }
    function at(node, n, opts) { if (!canvas || !node) return; const c = canvas.getBoundingClientRect(), r = node.getBoundingClientRect(); burst(r.left - c.left + r.width / 2, r.top - c.top + r.height / 2, n, opts); }
    function fireworks() { if (!canvas) return; size(); const r = canvas.getBoundingClientRect(); for (let i = 0; i < 6; i++) setTimeout(() => burst(r.width * (0.15 + Math.random() * 0.7), r.height * (0.15 + Math.random() * 0.45), 60, { speed: 220, life: 1.1 }), i * 260); }
    return { attach, at, burst, fireworks, size };
  })();

  // ------------------------------------------------------------------------------------------ motion helpers
  function glitch(node) { if (!node || reduced()) return; node.classList.remove("glitch"); void node.offsetWidth; node.classList.add("glitch"); setTimeout(() => node.classList.remove("glitch"), 160); }
  function materialize(node, open, done) {
    if (!node) return;
    if (reduced()) { node.classList.toggle("hidden", !open); if (done) done(); return; }
    if (open) node.classList.remove("hidden");
    node.classList.remove("mz-in", "mz-out"); void node.offsetWidth; node.classList.add(open ? "mz-in" : "mz-out");
    Sound.play(open ? "materialize" : "dissolve", xOf(node)); glitch(node);
    if (open) for (let i = 0; i < 5; i++) { const f = el("span", "frag"); f.style.left = Math.random() * 90 + "%"; f.style.top = Math.random() * 90 + "%"; node.append(f); setTimeout(() => f.remove(), 320); }
    setTimeout(() => { node.classList.remove("mz-in", "mz-out"); if (!open) node.classList.add("hidden"); if (done) done(); }, open ? 440 : 300);
  }
  function press(node, e) { // mechanical feedback: ripple at the touch point, click
    if (!node) return; Sound.play("click", xOf(node));
    if (reduced() || !e) return;
    const r = node.getBoundingClientRect(), rp = el("span", "ripple"); const s = Math.max(r.width, r.height) * 1.6;
    rp.style.width = rp.style.height = s + "px"; rp.style.left = (e.clientX - r.left) + "px"; rp.style.top = (e.clientY - r.top) + "px";
    if (getComputedStyle(node).position === "static") node.style.position = "relative";
    node.append(rp); setTimeout(() => rp.remove(), 480);
  }

  // ------------------------------------------------------------------------------------------ console / terminal output
  let consoleEl = null; const MAX_LINES = 300;
  function log(level, text) {
    if (!consoleEl) return;
    const d = new Date(), ts = d.toTimeString().slice(0, 8);
    const line = el("span", "ln " + (level || "")); line.append(el("span", "ts", ts), document.createTextNode(text));
    consoleEl.append(line); while (consoleEl.childNodes.length > MAX_LINES) consoleEl.firstChild.remove();
    consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  // ------------------------------------------------------------------------------------------ build
  function panel(cls, title, right) {
    const p = el("section", "cpanel " + cls); const l = el("div", "clabel"); l.append(el("span", null, title)); const b = el("b", null, right || ""); l.append(b); p.append(l); p._label = b; return p;
  }

  function build() {
    root.replaceChildren();
    const conduits = svg("svg", { class: "core-conduits", "aria-hidden": "true" }); root.append(conduits);
    const grid = el("div", "core-grid"); root.append(grid);
    const parts = el("canvas", "core-particles"); parts.setAttribute("aria-hidden", "true"); root.append(parts); Particles.attach(parts);
    const P = { arc: buildArc(), rings: buildRings(), source: buildSource(), hex: buildHex(), files: buildFiles(), mods: buildMods(), keys: buildKeys() };
    Object.values(P).forEach((p) => grid.append(p));
    root._panels = P; root._conduits = conduits;
    root.append(buildSourceWindow());
    layoutConduits();
    if (window.ResizeObserver) new ResizeObserver(() => { layoutConduits(); Particles.size(); }).observe(grid);
    built = true;
  }

  // A. arc data matrix ----------------------------------------------------------------------------
  const ARC_ROWS = [
    { key: "proc", label: "CLU MEMORY" }, { key: "sys", label: "SYSTEM MEMORY" }, { key: "infer", label: "INFERENCE QUEUE" },
    { key: "records", label: "MEMORY RECORDS (log)" }, { key: "sugg", label: "SUGGESTIONS" }, { key: "tasks", label: "TASKS / APPROVALS" },
  ];
  const arcBlocks = [];
  function buildArc() {
    const p = panel("core-arc", "Data matrix", "live");
    const s = svg("svg", { class: "arc-svg", viewBox: "0 0 260 240", role: "img", "aria-label": "Processing load, memory and background activity" }, p);
    const cx = 250, cy = 230;
    ARC_ROWS.forEach((row, ri) => {
      const r0 = 70 + ri * 26, n = 10 + ri * 4, blocks = [];
      for (let i = 0; i < n; i++) {
        const a0 = Math.PI + (i / n) * (Math.PI / 2) + 0.012, a1 = Math.PI + ((i + 1) / n) * (Math.PI / 2) - 0.012, r1 = r0 + 17;
        const pt = (r, a) => `${(cx + r * Math.cos(a)).toFixed(1)},${(cy + r * Math.sin(a)).toFixed(1)}`;
        blocks.push(svg("polygon", { class: "blk", points: [pt(r0, a0), pt(r1, a0), pt(r1, a1), pt(r0, a1)].join(" ") }, s));
      }
      arcBlocks.push(blocks);
    });
    for (let i = 0; i <= 5; i++) { const a = Math.PI + (i / 5) * (Math.PI / 2); svg("line", { class: "tick", x1: cx + 62 * Math.cos(a), y1: cy + 62 * Math.sin(a), x2: cx + 56 * Math.cos(a), y2: cy + 56 * Math.sin(a) }, s); }
    const leg = el("div", "arc-legend"); ARC_ROWS.forEach((r) => { const sp = el("span"); sp.append(el("i", "cdim", r.label)); const v = el("b", "cval", "…"); v.id = "arc-" + r.key; sp.append(v); leg.append(sp); });
    p.append(leg);
    return p;
  }
  function setArc(key, fraction, text) {
    const i = ARC_ROWS.findIndex((r) => r.key === key); if (i < 0) return;
    const blocks = arcBlocks[i]; const lit = fraction == null ? 0 : Math.round(clamp(fraction, 0, 1) * blocks.length);
    blocks.forEach((b, j) => { b.classList.toggle("on", j < lit); b.classList.toggle("hi", j < lit && j >= lit - 2); });
    $("arc-" + key).textContent = text; $("arc-" + key).className = fraction == null ? "cdim" : "cval";
  }
  let arcScan = 0;
  function scanArc() { // a slow sequential scan along one row at a time (idle motion, not data)
    if (reduced()) return;
    const row = arcBlocks[arcScan % arcBlocks.length]; arcScan++;
    row.forEach((b, j) => setTimeout(() => { b.classList.add("scan"); setTimeout(() => b.classList.remove("scan"), 140); }, j * 35));
  }

  // C. concentric rings -----------------------------------------------------------------------------
  const STAGES = ["input", "analysis", "synthesis", "output"];
  const rings = [];
  function buildRings() {
    const p = panel("core-rings", "Core process", "idle");
    const wrap = el("div", "rings-wrap"); p.append(wrap);
    const s = svg("svg", { class: "rings-svg", viewBox: "0 0 220 220", role: "img", "aria-label": "Processing stages" }, wrap);
    [100, 82, 64, 46].forEach((r, i) => svg("circle", { class: "hair", cx: 110, cy: 110, r: r + 8 }, s));
    const dashes = ["3 5", "18 6 4 6", "40 10", "8 4 2 4"];
    [100, 82, 64, 46].forEach((r, i) => {
      const g = svg("g", {}, s); const c = svg("circle", { class: "ring", cx: 110, cy: 110, r, "stroke-dasharray": dashes[i] }, g);
      rings.push({ g, c, a: i * 40, v: (i % 2 ? -1 : 1) * 4 });
    });
    svg("circle", { class: "core-dot", cx: 110, cy: 110, r: 14 }, s);
    const right = el("div"); const ul = el("ul", "stages"); STAGES.forEach((st) => { const li = el("li"); li.dataset.stage = st; li.append(el("i"), el("span", null, st.toUpperCase()), el("small", "cdim", "")); ul.append(li); });
    right.append(ul, el("div", "stage-note")); wrap.append(right);
    p._note = right.querySelector(".stage-note"); p._list = ul;
    return p;
  }
  const RING_SPEED = { idle: 4, pending: 7, active: 140, done: 22 };
  let ringRaf = 0, ringLast = 0;
  function ringLoop() {
    const t = now(), dt = Math.min(0.05, (t - ringLast) / 1000); ringLast = t;
    const cur = STAGES.indexOf(state.stage);
    rings.forEach((r, i) => {
      const mode = cur < 0 ? "idle" : i === cur ? "active" : i < cur || state.stagesDone.includes(STAGES[i]) ? "done" : "pending";
      const dir = i % 2 ? -1 : 1, target = RING_SPEED[mode] * dir;
      r.v += (target - r.v) * Math.min(1, dt * (mode === "active" ? 3 : 1.4)); // controlled acceleration and deceleration
      r.a = (r.a + r.v * dt) % 360; r.g.setAttribute("transform", `rotate(${r.a.toFixed(2)} 110 110)`);
      r.c.classList.toggle("active", mode === "active"); r.c.classList.toggle("done", mode === "done");
    });
    ringRaf = visible && !document.hidden && !reduced() ? requestAnimationFrame(ringLoop) : 0;
  }
  function startLoops() { if (!ringRaf && visible && !reduced()) { ringLast = now(); ringRaf = requestAnimationFrame(ringLoop); } }

  // B. source / idea container -----------------------------------------------------------------------
  function buildSource() {
    const p = panel("core-source press", "Sources & ideas", "open ›");
    const m = el("div", "source-min"); p.append(m);
    const s = svg("svg", { class: "source-ring", viewBox: "0 0 74 74" }, m);
    svg("circle", { class: "trk", cx: 37, cy: 37, r: 30 }, s); p._bar = svg("circle", { class: "bar", cx: 37, cy: 37, r: 30 }, s); svg("circle", { class: "spin", cx: 37, cy: 37, r: 20 }, s);
    p._pct = svg("text", { x: 37, y: 41, "text-anchor": "middle", fill: "#eafbff", "font-size": "11", "font-family": "monospace" }, s); p._pct.textContent = "—";
    const right = el("div"); right.append(el("div", "stripes"), el("div", "source-preview", "No answer yet.")); const counts = el("div", "source-counts");
    counts.append(el("span", null, "SOURCES "), el("b", null, "0"), el("span", null, " SUGGESTIONS "), el("b", null, "0")); right.append(counts); m.append(right);
    p._preview = right.querySelector(".source-preview"); p._srcN = counts.children[1]; p._sugN = counts.children[3];
    p.addEventListener("click", (e) => { press(p, e); openSource(true); });
    return p;
  }
  function buildSourceWindow() {
    const w = el("section", "cpanel core-window hidden"); w.setAttribute("role", "dialog"); w.setAttribute("aria-label", "Sources and ideas");
    const head = el("div", "clabel"); head.append(el("span", null, "Intelligence container")); const close = el("button", "cbtn", "Close"); head.append(close); w.append(head);
    const body = el("div", "cw-body"); w.append(body); w._body = body;
    close.addEventListener("click", (e) => { press(close, e); openSource(false); });
    addEventListener("keydown", (e) => { if (e.key === "Escape" && state.sourceOpen) openSource(false); });
    return w;
  }
  async function openSource(open) {
    const w = root.querySelector(".core-window"); set({ sourceOpen: open });
    if (!open) return materialize(w, false);
    const b = w._body; b.replaceChildren(el("div", "cdim", "Loading…")); materialize(w, true);
    const [sugg, ov] = await Promise.all([api("/v1/suggestions").catch(() => null), api("/v1/memory/overview").catch(() => null)]);
    b.replaceChildren();
    const sec = (t) => { const s = el("div"); s.append(el("h4", null, t)); b.append(s); return s; };
    const a = sec("Last answer"); a.append(el("div", state.answer ? "cval" : "cdim", state.answer || "Nothing answered in this session yet."));
    const s = sec(`Sources (${state.sources.length})`);
    if (state.sources.length) { const ul = el("ul"); state.sources.forEach((x) => { const li = el("li"); const l = el("a", null, x.title || x.url); l.href = x.url; l.target = "_blank"; l.rel = "noopener noreferrer"; li.append(l); ul.append(li); }); s.append(ul); }
    else s.append(el("div", "cdim", "The last answer used no outside sources."));
    const g = sec("Proactive suggestions (waiting for you)");
    if (!sugg) g.append(el("div", "cdim", "Not available right now."));
    else if (!sugg.length) g.append(el("div", "cdim", "None waiting."));
    else sugg.slice(0, 12).forEach((x) => {
      const row = el("div", "sugg"); row.append(el("span", "cval", x.text)); const btns = el("span"); const ok = el("button", "cbtn", "Keep"), no = el("button", "cbtn", "Drop"); btns.append(ok, no); row.append(btns); g.append(row);
      const act = async (verb, btn, e) => { press(btn, e); try { await api(`/v1/suggestions/${x.id}/${verb}`, { method: "POST", json: {} }); row.remove(); Sound.play(verb === "approve" ? "success" : "confirm", xOf(btn)); log("ok", `suggestion ${verb === "approve" ? "kept" : "dropped"}: ${x.text.slice(0, 60)}`); refresh(); } catch (err) { Sound.play("error"); log("err", "suggestion: " + err.message); } };
      ok.addEventListener("click", (e) => act("approve", ok, e)); no.addEventListener("click", (e) => act("reject", no, e));
    });
    const d = sec("Background discoveries");
    if (ov) { d.append(el("div", "cval", `Merge suggestions waiting: ${ov.consolidation ?? 0} · questions about your preferences: ${ov.learning ?? 0} · working notes: ${ov.working ?? 0}`)); const go = el("button", "cbtn", "Open Memory"); go.addEventListener("click", () => { openSource(false); show("memory"); }); d.append(go); }
    else d.append(el("div", "cdim", "Not available right now."));
  }

  // D. hexagonal core control -------------------------------------------------------------------------
  let waveCanvas = null, waveCtx = null, waveLabel = null, micStream = null, micAnalyser = null, playAnalyser = null, playStream = null;
  function hexPoints(cx, cy, rx, ry) { return Array.from({ length: 6 }, (_, i) => { const a = Math.PI / 3 * i; return `${(cx + rx * Math.cos(a)).toFixed(1)},${(cy + ry * Math.sin(a)).toFixed(1)}`; }).join(" "); }
  function buildHex() {
    const p = panel("core-hex", "Core control", "");
    const f = el("div", "hex-frame"); p.append(f);
    const s = svg("svg", { class: "hex-bg", viewBox: "0 0 300 320", preserveAspectRatio: "none", "aria-hidden": "true" }, f);
    const defs = svg("defs", {}, s); const pat = svg("pattern", { id: "honey", width: 14, height: 24.2, patternUnits: "userSpaceOnUse" }, defs);
    svg("polygon", { points: hexPoints(7, 6, 6.2, 5.6), fill: "none", stroke: "rgba(66,97,107,0.25)", "stroke-width": 0.6 }, pat);
    svg("polygon", { points: hexPoints(14, 18.1, 6.2, 5.6), fill: "none", stroke: "rgba(66,97,107,0.25)", "stroke-width": 0.6 }, pat);
    svg("polygon", { points: hexPoints(150, 160, 146, 156), fill: "url(#honey)", class: "hx outer" }, s);
    svg("polygon", { points: hexPoints(150, 160, 132, 142), class: "hx" }, s);
    svg("polygon", { points: hexPoints(150, 160, 120, 130), class: "hx inner breathe" }, s);
    const sensors = el("div", "sensors"); f.append(sensors);
    const SENS = [["talk", "TALK", "Start or end a voice conversation"], ["stop", "STOP", "Stop CLU thinking or speaking"], ["new", "NEW", "Start a new conversation"]];
    p._sensors = {};
    SENS.forEach(([id, label, title]) => {
      const b = el("button", "sensor"); b.type = "button"; b.title = title; b.setAttribute("aria-label", title);
      const t = svg("svg", { viewBox: "0 0 44 34" }, b); svg("polygon", { points: "22,31 3,3 41,3" }, t); b.append(el("span", null, label));
      b.addEventListener("pointerdown", () => b.classList.add("pressed")); ["pointerup", "pointerleave"].forEach((ev) => b.addEventListener(ev, () => b.classList.remove("pressed")));
      b.addEventListener("click", (e) => { press(b, e); sensor(id, b); });
      sensors.append(b); p._sensors[id] = b;
    });
    const inner = el("div", "hex-inner"); f.append(inner);
    const ro = el("div", "readouts"); const R = (k, label) => { const sp = el("span"); sp.append(el("i", "cdim", label)); const v = el("b", "cval", "…"); v.id = "ro-" + k; sp.append(v); ro.append(sp); };
    R("model", "MODEL"); R("link", "HOST"); R("stt", "STT"); R("resp", "RESPONSE"); R("queue", "QUEUE"); R("voice", "VOICE");
    inner.append(ro);
    const wl = el("div", "wave-label"); waveLabel = el("span", null, "NO SIGNAL"); const mic = el("button", "cbtn", "Mic monitor"); mic.type = "button";
    mic.title = "Show your microphone's level here (nothing is recorded or sent)"; if (ANDROID) mic.classList.add("hidden"); // Android: the native session's level is shown
    wl.append(waveLabel, mic); inner.append(wl);
    waveCanvas = el("canvas", "wave"); inner.append(waveCanvas); waveCtx = waveCanvas.getContext("2d");
    mic.addEventListener("click", (e) => { press(mic, e); toggleMic(mic); });
    consoleEl = el("div", "console"); consoleEl.setAttribute("role", "log"); consoleEl.setAttribute("aria-live", "polite"); inner.append(consoleEl);
    return p;
  }
  async function sensor(id, b) {
    if (id === "talk") { const vb = $("voiceBtn"); if (vb && !vb.disabled) { vb.click(); Sound.play("listen", xOf(b)); log("ok", "voice: " + (state.voice ? "ending" : "starting")); } else log("warn", "voice is not available here"); }
    else if (id === "stop") { try { await api("/v1/stop", { method: "POST", json: {} }); if (state.voice) { const vs = $("voiceStop"); if (vs && !vs.disabled) vs.click(); } log("ok", "stop requested"); Sound.play("confirm", xOf(b)); } catch (e) { log("err", "stop: " + e.message); Sound.play("error"); } }
    else if (id === "new") { const nb = $("newConversation") || document.querySelector('[data-action="new-conversation"]'); if (nb) { nb.click(); log("ok", "new conversation"); Sound.play("confirm", xOf(b)); } else log("warn", "new conversation: not available in this window"); }
  }
  async function toggleMic(btn) {
    if (micStream) { micStream.getTracks().forEach((t) => t.stop()); micStream = null; micAnalyser = null; btn.textContent = "Mic monitor"; log("", "mic monitor off"); return; }
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      const c = new (window.AudioContext || window.webkitAudioContext)(); micAnalyser = c.createAnalyser(); micAnalyser.fftSize = 1024; c.createMediaStreamSource(micStream).connect(micAnalyser);
      btn.textContent = "Mic off"; Sound.play("record", xOf(btn)); log("ok", "mic monitor on (level only; nothing is recorded)");
    } catch (e) { micStream = null; log("err", "microphone: " + (e.message || e.name)); Sound.play("error"); }
  }
  function playbackAnalyser() { // the PC voice session's audio element (LiveKit) → analyser, without changing playback
    const a = document.getElementById("cluVoiceAudio");
    if (!a || !a.srcObject) { playAnalyser = null; playStream = null; return null; }
    if (playStream === a.srcObject && playAnalyser) return playAnalyser;
    try { const c = new (window.AudioContext || window.webkitAudioContext)(); playAnalyser = c.createAnalyser(); playAnalyser.fftSize = 1024; c.createMediaStreamSource(a.srcObject).connect(playAnalyser); playStream = a.srcObject; } catch (e) { playAnalyser = null; }
    return playAnalyser;
  }
  const waveBuf = new Uint8Array(1024), freqBuf = new Uint8Array(512);
  function drawWave() {
    if (!waveCtx) return;
    const w = waveCanvas.clientWidth, h = waveCanvas.clientHeight; if (waveCanvas.width !== w) { waveCanvas.width = w; waveCanvas.height = h; }
    const g = waveCtx; g.clearRect(0, 0, w, h);
    let source = null, an = null;
    if (micAnalyser) { an = micAnalyser; source = "MIC"; }
    else if ((an = playbackAnalyser())) source = "CLU VOICE";
    if (an) {
      an.getByteFrequencyData(freqBuf); const bars = 48, bw = w / bars;
      g.fillStyle = "rgba(0,131,143,0.6)"; for (let i = 0; i < bars; i++) { const v = freqBuf[Math.floor(i * freqBuf.length / bars / 2)] / 255; g.fillRect(i * bw + 1, h - v * h, bw - 2, v * h); }
      an.getByteTimeDomainData(waveBuf); g.strokeStyle = "#00e5ff"; g.lineWidth = 1.2; g.beginPath();
      for (let i = 0; i < waveBuf.length; i += 8) { const x = i / waveBuf.length * w, y = waveBuf[i] / 255 * h; i ? g.lineTo(x, y) : g.moveTo(x, y); } g.stroke();
      waveLabel.textContent = source + " · LIVE"; waveLabel.className = "cok";
    } else if (ANDROID && typeof window.CluNativeLevel === "number" && state.voice) { // the app's native voice session reports its level
      const lv = clamp(window.CluNativeLevel, 0, 1), mic = window.CluNativeMic === true;
      g.strokeStyle = "#00e5ff"; g.beginPath(); for (let x = 0; x <= w; x += 3) { const y = h / 2 + Math.sin(x / 7 + now() / 90) * lv * h * 0.45; x ? g.lineTo(x, y) : g.moveTo(x, y); } g.stroke();
      waveLabel.textContent = (lv > 0.02 ? "CLU VOICE" : mic ? "LISTENING" : "VOICE") + " · LEVEL " + Math.round(lv * 100); waveLabel.className = "cok";
    } else {
      g.strokeStyle = "rgba(66,97,107,0.8)"; g.beginPath(); g.moveTo(0, h / 2); g.lineTo(w, h / 2); g.stroke();
      waveLabel.textContent = "NO SIGNAL"; waveLabel.className = "";
    }
  }

  // G. file explorer -----------------------------------------------------------------------------------
  let docs = [], picked = null, selectedDoc = null;
  function buildFiles() {
    const p = panel("core-files", "File explorer", "");
    const tools = el("div", "clabel"); const hide = el("button", "cbtn", "Collapse"); tools.append(el("span", null, ""), hide); p.append(tools);
    const body = el("div", "files-body"); p.append(body); p._body = body;
    const left = el("div"); const drop = el("div", "drop press", "Drop a file here, or tap to choose"); const input = el("input"); input.type = "file"; input.className = "hidden";
    const list = el("ul", "file-list"); left.append(drop, input, list); body.append(left);
    const right = el("div"); const meta = el("div", "meta-grid"); const prev = el("div", "preview"); const acts = el("div"); acts.className = "clabel";
    const up = el("button", "cbtn", "Upload"), inj = el("button", "cbtn", "Inject"); up.disabled = inj.disabled = true; inj.title = "Ask CLU about this document (it goes into the conversation)";
    acts.append(up, inj); right.append(meta, prev, acts); body.append(right);
    p._list = list; p._meta = meta; p._prev = prev; p._up = up; p._inj = inj;
    drop.addEventListener("click", (e) => { press(drop, e); input.click(); });
    input.addEventListener("change", () => input.files[0] && pick(input.files[0]));
    ["dragenter", "dragover"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("over"); }));
    ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, () => drop.classList.remove("over")));
    drop.addEventListener("drop", (e) => { e.preventDefault(); const f = e.dataTransfer && e.dataTransfer.files[0]; if (f) pick(f); });
    up.addEventListener("click", (e) => { press(up, e); upload(); });
    inj.addEventListener("click", (e) => { press(inj, e); inject(); });
    hide.addEventListener("click", (e) => { press(hide, e); toggleFiles(); });
    return p;
  }
  function toggleFiles(open) {
    const p = root._panels.files; const o = open == null ? !state.filesOpen : open; set({ filesOpen: o });
    p.querySelector(".clabel .cbtn").textContent = o ? "Collapse" : "Expand";
    materialize(p._body, o);
  }
  const kb = (n) => n == null ? "?" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";
  function meta(pairs) { const m = root._panels.files._meta; m.replaceChildren(); pairs.forEach(([k, v]) => m.append(el("span", "cdim", k), el("span", "cval", v))); }
  function pick(f) {
    picked = f; selectedDoc = null; const P = root._panels.files;
    meta([["NAME", f.name], ["TYPE", f.type || "unknown"], ["SIZE", kb(f.size)], ["MODIFIED", new Date(f.lastModified).toLocaleString()], ["STATE", "not uploaded"]]);
    P._prev.replaceChildren(el("div", "cdim", "Preview…"));
    if (/^image\//.test(f.type)) { const img = el("img"); img.alt = f.name; img.src = URL.createObjectURL(f); img.onload = () => URL.revokeObjectURL(img.src); P._prev.replaceChildren(img); }
    else if (/^text\/|json|xml|csv|markdown/.test(f.type) || /\.(txt|md|csv|json|log|kt|js|py)$/i.test(f.name)) { f.slice(0, 6000).text().then((t) => P._prev.replaceChildren(el("pre", null, t))).catch(() => P._prev.replaceChildren(el("div", "cdim", "No preview."))); }
    else P._prev.replaceChildren(el("div", "cdim", "No preview for this type. CLU reads PDF, Word and text documents when uploaded."));
    P._up.disabled = false; P._inj.disabled = true; Sound.play("chirp", xOf(P));
    P._list.querySelectorAll("li").forEach((x) => x.classList.remove("sel"));
  }
  async function upload() {
    const P = root._panels.files; if (!picked) return;
    P._up.disabled = true; hot(["files-hex"], true); log("", `upload ${picked.name} (${kb(picked.size)})…`); Sound.play("processing", xOf(P));
    try {
      const r = await fetch("/v1/documents", { method: "POST", body: picked, headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token"), "X-File-Name": encodeURIComponent(picked.name) } });
      const d = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
      log("ok", `synchronised: ${d.name} · ${d.textStatus === "indexed" ? (d.chars || 0).toLocaleString() + " characters indexed" : "stored, no searchable text"}`);
      Sound.play("success", xOf(P)); Particles.at(P._list, 70, { speed: 170 }); glitch(P);
      picked = null; await loadDocs(); const li = [...P._list.children].find((x) => x._doc && x._doc.id === d.id); if (li) li.click();
    } catch (e) { log("err", "upload refused: " + e.message); Sound.play("error", xOf(P)); glitch(P); P._up.disabled = false; }
    hot(["files-hex"], false);
  }
  function inject() {
    if (!selectedDoc || !window.CluShell) return;
    const q = `Summarise the document "${selectedDoc.name}" and tell me what matters in it.`;
    log("you", "› " + q); CluShell.send(q); hot(["files-hex"], true); setTimeout(() => hot(["files-hex"], false), 1500);
  }
  async function loadDocs() {
    const P = root._panels.files;
    try { docs = await api("/v1/documents"); } catch (e) { P._list.replaceChildren(el("li", "cdim", "Documents: " + e.message)); return; }
    P._list.replaceChildren(); P._label.textContent = docs.length + " in archive";
    if (!docs.length) P._list.append(el("li", "cdim", "No documents yet."));
    docs.forEach((d) => {
      const li = el("li"); li._doc = d; li.append(el("span", null, d.name), el("small", null, kb(d.sizeBytes)));
      li.addEventListener("click", () => {
        P._list.querySelectorAll("li").forEach((x) => x.classList.remove("sel")); li.classList.add("sel"); selectedDoc = d; picked = null;
        meta([["NAME", d.name], ["TYPE", d.type], ["SIZE", kb(d.sizeBytes)], ["TEXT", d.textStatus === "indexed" ? (d.chars || 0).toLocaleString() + " chars indexed" : d.textStatus], ["DATE", d.date || "—"]]);
        P._prev.replaceChildren(el("div", "cdim", "Stored in CLU's archive. Inject to bring it into the conversation."));
        P._up.disabled = true; P._inj.disabled = false; Sound.play("tick", xOf(li));
      });
      P._list.append(li);
    });
  }

  // F. module grid ------------------------------------------------------------------------------------
  const ICONS = {
    clu: "M12 3l8 4.5v9L12 21l-8-4.5v-9z M12 8v8", research: "M10 4a6 6 0 1 0 0 12a6 6 0 0 0 0-12z M15 15l5 5", terminal: "M4 6l5 5-5 5 M11 17h9",
    system: "M4 5h16v10H4z M9 19h6 M12 15v4", memory: "M7 4h10v16H7z M7 9h10 M7 14h10", voice: "M12 4v10 M8 10a4 4 0 0 0 8 0 M12 18v2 M5 10a7 7 0 0 0 14 0",
    browser: "M4 5h16v14H4z M4 9h16 M7 7h1", utilities: "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8z M12 2v3 M12 19v3 M2 12h3 M19 12h3",
  };
  const MODS = [
    ["clu", "CLU", "Conversation with CLU"], ["research", "RESEARCH", "Web research on or off (Settings → Network)"], ["terminal", "TERMINAL", "Command line"],
    ["system", "SYSTEM", "Status: model, memory, governor"], ["memory", "MEMORY", "Memory and archive"], ["voice", "VOICE", "Voice conversation"],
    ["browser", "FILES", "File explorer"], ["utilities", "UTILITY", "Settings"],
  ];
  function buildMods() {
    const p = panel("core-mods", "Modules", "");
    const g = el("div", "mods-grid"); p.append(g); p._mods = {};
    MODS.forEach(([id, label, title]) => {
      const b = el("button", "mod press"); b.type = "button"; b.title = title; b.setAttribute("aria-label", title);
      const s = svg("svg", { viewBox: "0 0 24 24", "aria-hidden": "true" }, b); svg("path", { d: ICONS[id] }, s); b.append(el("b", null, label));
      b.addEventListener("click", (e) => { press(b, e); module(id, b); }); g.append(b); p._mods[id] = b;
    });
    return p;
  }
  async function module(id, b) {
    glitch(root._panels.mods); hot(["mods-files"], true); setTimeout(() => hot(["mods-files"], false), 900);
    if (!reduced()) Particles.at(b, 14, { speed: 90, life: 0.4 });
    Sound.play("activate", xOf(b));
    const go = (view) => { log("", "open " + view); show(view); };
    if (id === "clu") go("chat");
    else if (id === "system") go("status");
    else if (id === "memory") go("memory");
    else if (id === "utilities") go("settings");
    else if (id === "terminal") { $("coreCmd").focus(); log("", "terminal ready. Type help."); }
    else if (id === "voice") sensor("talk", b);
    else if (id === "browser") toggleFiles();
    else if (id === "research") {
      if (state.research == null) { log("warn", "research setting not available"); return; }
      try { await api("/v1/settings", { method: "PUT", json: { key: "net.web", value: !state.research } }); set({ research: !state.research }); log("ok", "web research " + (state.research ? "on" : "off")); }
      catch (e) { log("err", "research: " + e.message); Sound.play("error"); }
    }
  }

  // E. keyboard and command strip ------------------------------------------------------------------------
  const history = (() => { try { return JSON.parse(localStorage.getItem("clu-core-history") || "[]"); } catch (e) { return []; } })(); let hIdx = -1;
  function buildKeys() {
    const p = panel("core-keys", "Command", "");
    const left = el("div", "keys-left"); p.append(left);
    const row = el("div", "cmd-row"); const ind = el("span", "exec-ind"); ind.id = "coreExec";
    const input = el("input", "cmd"); input.id = "coreCmd"; input.placeholder = "Type a command or a question for CLU (help)"; input.autocomplete = "off"; input.spellcheck = false; input.setAttribute("aria-label", "Command");
    row.append(el("span", "prompt", "CLU›"), input, ind); left.append(row);
    const kbd = el("div", "kb"); left.append(kbd);
    const ROWS = ["1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"];
    ROWS.forEach((r, i) => { const rr = el("div", "kb-row"); [...r].forEach((ch) => rr.append(key(ch, ch.toLowerCase()))); if (i === 3) { rr.append(key("⌫", "Backspace", "wide")); } kbd.append(rr); });
    const last = el("div", "kb-row"); last.append(key("CLR", "Clear", "wide"), key("SPACE", " ", "space"), key("ENTER", "Enter", "wide")); kbd.append(last);
    const print = el("button", "print"); print.type = "button"; print.title = "Hold to execute the command (a visual confirmation, not a security check)";
    const ps = svg("svg", { viewBox: "0 0 62 72", "aria-hidden": "true" }, print);
    [[31, 38, 26, 30], [31, 38, 20, 24], [31, 38, 14, 18], [31, 38, 8, 12]].forEach(([cx, cy, rx, ry]) => svg("ellipse", { cx, cy, rx, ry, "stroke-dasharray": "14 5" }, ps));
    print.append(el("i", "scan"), el("span", null, "HOLD · EXECUTE")); p.append(print);
    let holdT = 0;
    print.addEventListener("pointerdown", () => { print.classList.add("holding"); Sound.play("tick", xOf(print)); holdT = setTimeout(() => { print.classList.remove("holding"); run(input.value); }, 650); });
    ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => print.addEventListener(ev, () => { clearTimeout(holdT); print.classList.remove("holding"); }));
    input.addEventListener("keydown", (e) => {
      light(e.key);
      if (e.key === "Enter") { e.preventDefault(); run(input.value); }
      else if (e.key === "ArrowUp") { e.preventDefault(); if (history.length) { hIdx = Math.min(history.length - 1, hIdx + 1); input.value = history[history.length - 1 - hIdx]; } }
      else if (e.key === "ArrowDown") { e.preventDefault(); hIdx = Math.max(-1, hIdx - 1); input.value = hIdx < 0 ? "" : history[history.length - 1 - hIdx]; }
    });
    input.addEventListener("focus", () => setMod("terminal", true)); input.addEventListener("blur", () => setMod("terminal", false));
    p._keys = kbd;
    function key(label, value, cls) {
      const k = el("button", "key" + (cls ? " " + cls : ""), label); k.type = "button"; k.dataset.k = value.toLowerCase();
      k.addEventListener("click", () => {
        Sound.play("click", xOf(k)); k.classList.add("lit"); setTimeout(() => k.classList.remove("lit"), 120);
        if (value === "Enter") return run(input.value);
        if (value === "Backspace") input.value = input.value.slice(0, -1);
        else if (value === "Clear") input.value = "";
        else input.value += value;
      });
      return k;
    }
    return p;
  }
  function light(k) {
    const kb = root && root._panels && root._panels.keys._keys; if (!kb) return;
    const name = k === " " ? " " : k.length === 1 ? k.toLowerCase() : k.toLowerCase();
    const b = kb.querySelector(`.key[data-k="${CSS.escape(name)}"]`); if (b) { b.classList.add("lit"); setTimeout(() => b.classList.remove("lit"), 120); }
  }
  function execInd(cls) { const i = $("coreExec"); if (i) i.className = "exec-ind" + (cls ? " " + cls : ""); }

  const HELP = [
    "help                     this list", "<question>               ask CLU (same conversation as Chat)", "ask <question>           ask CLU",
    "stop                     stop CLU", "status                   model, host and memory figures", "memory <words>           search CLU's memory",
    "docs                     list the archive's documents", "open <view>              chat, memory, people, timeline, documents, status, settings, models, agents",
    "history                  commands you typed", "clear                    clear this console", "sound on|off|mute|volume N|ambient on|off   sound settings",
    "celebrate                light show",
  ];
  async function run(raw) {
    const input = $("coreCmd"); const cmd = (raw || "").trim(); if (!cmd) { log("cdim", "nothing to execute"); return; }
    input.value = ""; hIdx = -1; history.push(cmd); if (history.length > 50) history.shift(); try { localStorage.setItem("clu-core-history", JSON.stringify(history)); } catch (e) { /* private mode */ }
    log("you", "› " + cmd); Sound.play("confirm", 0.5);
    const [verb, ...rest] = cmd.split(/\s+/); const arg = rest.join(" "); const v = verb.toLowerCase();
    execInd("run");
    try {
      if (v === "help") HELP.forEach((h) => log("", h));
      else if (v === "clear") consoleEl.replaceChildren();
      else if (v === "history") history.slice(-15).forEach((h, i) => log("", `${i + 1}  ${h}`));
      else if (v === "stop") { await api("/v1/stop", { method: "POST", json: {} }); log("ok", "stop requested"); }
      else if (v === "status") { const s = await api("/v1/status"); const m = s.model || {}; log("", `model: ${m.name || "none"} · ${String(m.status || "?").toLowerCase()}${m.backend ? " · " + m.backend : ""}`); log("", `host: ${s.host === "device" ? "this device" : "PC"} · version ${s.version} · conversations ${s.conversations} · suggestions ${s.pendingSuggestions}`); const g = s.governor || {}; if (g.budgetMB != null) log("", `memory: CLU ${g.processMB} MB of ${g.budgetMB} MB budget · system free ${g.systemAvailableMB} MB`); else log("cdim", "memory figures: n/a on this host"); }
      else if (v === "memory") { if (!arg) throw new Error("memory <words>"); const r = await api("/v1/memory/search?" + new URLSearchParams({ q: arg, docs: "0", limit: "8" })); const hits = r.results || r.hits || r; (Array.isArray(hits) ? hits : []).slice(0, 8).forEach((h) => { const m = h.memory || h; log("", `· ${m.title || m.content || JSON.stringify(m).slice(0, 80)}`); }); if (!Array.isArray(hits) || !hits.length) log("cdim", "no matches"); }
      else if (v === "docs") { await loadDocs(); docs.slice(0, 20).forEach((d) => log("", `· ${d.name} (${kb(d.sizeBytes)}, ${d.textStatus})`)); if (!docs.length) log("cdim", "no documents"); }
      else if (v === "open") { const views = ["chat", "memory", "people", "timeline", "documents", "suggestions", "status", "settings", "models", "agents", "capabilities"]; if (!views.includes(arg)) throw new Error("open one of: " + views.join(", ")); show(arg); }
      else if (v === "sound") {
        const [a, b] = rest;
        if (a === "on" || a === "off") Sound.set("ui", a === "on"); else if (a === "mute") Sound.set("mute", !Sound.prefs.mute);
        else if (a === "volume" && !isNaN(+b)) Sound.set("volume", clamp(+b / 100, 0, 1)); else if (a === "ambient") Sound.set("ambient", b === "on");
        else throw new Error("sound on|off|mute|volume N|ambient on|off");
        syncAudioUi(); log("ok", `sound: ui ${Sound.prefs.ui ? "on" : "off"}, ambient ${Sound.prefs.ambient ? "on" : "off"}, volume ${Math.round(Sound.prefs.volume * 100)}${Sound.prefs.mute ? ", muted" : ""}`);
      }
      else if (v === "celebrate") { Particles.fireworks(); Sound.play("celebrate", 0.5); }
      else { // a question for CLU: the same conversation, model and history as the Chat view
        const q = v === "ask" ? arg : cmd; if (!q) throw new Error("ask <question>");
        if (!window.CluShell) throw new Error("the conversation is not ready");
        if (CluShell.busy()) throw new Error("CLU is still answering: stop first, or wait");
        CluShell.send(q); execInd(""); return;
      }
      execInd("");
    } catch (e) { execInd("fail"); log("err", e.message); Sound.play("error", 0.5); setTimeout(() => execInd(""), 1500); }
  }

  // ------------------------------------------------------------------------------------------ conduits
  const LINKS = [["arc", "rings"], ["rings", "source"], ["rings", "hex"], ["files", "hex"], ["mods", "files"], ["keys", "hex"], ["arc", "mods"]];
  const flows = {};
  function layoutConduits() {
    const c = root && root._conduits; if (!c || !root._panels) return;
    const base = root.getBoundingClientRect(); c.setAttribute("viewBox", `0 0 ${root.scrollWidth} ${root.scrollHeight}`); c.replaceChildren();
    const box = (k) => { const r = root._panels[k].getBoundingClientRect(); return { l: r.left - base.left + root.scrollLeft, t: r.top - base.top + root.scrollTop, r: r.right - base.left + root.scrollLeft, b: r.bottom - base.top + root.scrollTop }; };
    LINKS.forEach(([a, b]) => {
      const A = box(a), B = box(b); let x1, y1, x2, y2, d;
      if (A.r <= B.l) { x1 = A.r; y1 = (A.t + A.b) / 2; x2 = B.l; y2 = clamp(y1, B.t + 14, B.b - 14); const mx = (x1 + x2) / 2; d = `M${x1} ${y1} H${mx} V${y2} H${x2}`; }
      else if (B.r <= A.l) { x1 = A.l; y1 = (A.t + A.b) / 2; x2 = B.r; y2 = clamp(y1, B.t + 14, B.b - 14); const mx = (x1 + x2) / 2; d = `M${x1} ${y1} H${mx} V${y2} H${x2}`; }
      else { x1 = clamp((A.l + A.r) / 2, B.l + 14, B.r - 14); y1 = A.b <= B.t ? A.b : A.t; y2 = A.b <= B.t ? B.t : B.b; x2 = x1; d = `M${x1} ${y1} V${y2}`; }
      const id = a + "-" + b;
      svg("path", { class: "base", d }, c); const f = svg("path", { class: "flow", d }, c); f.style.animationDelay = (-Math.random() * 6).toFixed(2) + "s";
      const n1 = svg("circle", { class: "node", cx: x1, cy: y1, r: 3 }, c), n2 = svg("circle", { class: "node", cx: x2, cy: y2, r: 3 }, c);
      flows[id] = { f, n: [n1, n2], hot: flows[id] ? flows[id].hot : false };
      if (flows[id].hot) hot([id], true);
    });
  }
  function hot(ids, onNow) { ids.forEach((id) => { const x = flows[id]; if (!x) return; x.hot = onNow; x.f.classList.toggle("hot", onNow); x.n.forEach((n) => n.classList.toggle("hot", onNow)); }); }

  // ------------------------------------------------------------------------------------------ live data
  async function refresh() {
    if (!api) return;
    const [st, inf, ov, sugg, settings] = await Promise.all([api("/v1/status").catch(() => null), api("/v1/inference/status").catch(() => null),
      api("/v1/memory/overview").catch(() => null), api("/v1/suggestions").catch(() => null), state.research == null ? api("/v1/settings").catch(() => null) : Promise.resolve(null)]);
    if (st) {
      const g = st.governor || {}, m = st.model || {};
      if (g.budgetMB != null) setArc("proc", g.processMB / Math.max(1, g.budgetMB), `${g.processMB} / ${g.budgetMB} MB`); else setArc("proc", null, "n/a");
      if (g.systemTotalMB != null) setArc("sys", (g.systemTotalMB - g.systemAvailableMB) / Math.max(1, g.systemTotalMB), `${Math.round((g.systemTotalMB - g.systemAvailableMB) / 1024 * 10) / 10} / ${Math.round(g.systemTotalMB / 1024 * 10) / 10} GB`); else setArc("sys", null, "n/a");
      const tasks = (st.activeTasks || 0) + (st.pendingApprovals || 0); setArc("tasks", Math.min(1, tasks / 6), String(tasks));
      set({ model: m });
      $("ro-model").textContent = m.name ? `${m.name} · ${String(m.status).toLowerCase()}` : m.status === "NO_MODEL" ? "none registered" : String(m.status || "?").toLowerCase();
      $("ro-link").textContent = st.host === "device" ? (st.link === "offline" ? "device (PC offline)" : "this device") : ANDROID ? "your PC" : "this PC";
    } else { setArc("proc", null, "n/a"); setArc("sys", null, "n/a"); setArc("tasks", null, "n/a"); }
    if (inf && (inf.running !== undefined || inf.queued !== undefined)) {
      const run = inf.running ? 1 : 0, q = Array.isArray(inf.queued) ? inf.queued.length : (inf.queued || 0), max = (inf.limits && inf.limits.maxQueued) || 4;
      setArc("infer", (run + q) / (max + 1), `${run} running · ${q} queued`); $("ro-queue").textContent = `${run} / ${q}`;
    } else if (st && st.model && st.model.running !== undefined) { const q = st.model.queued || 0; setArc("infer", ((st.model.running ? 1 : 0) + q) / 5, `${st.model.running ? 1 : 0} running · ${q} queued`); $("ro-queue").textContent = `${st.model.running ? 1 : 0} / ${q}`; }
    else { setArc("infer", null, "n/a"); $("ro-queue").textContent = "n/a"; }
    if (ov && ov.kinds) { const n = Object.values(ov.kinds).reduce((a, k) => a + (k.count || 0), 0); setArc("records", Math.log10(n + 1) / 4, n.toLocaleString()); } else setArc("records", null, "n/a");
    if (Array.isArray(sugg)) { setArc("sugg", Math.min(1, sugg.length / 10), String(sugg.length)); set({ suggestions: sugg.length }); } else setArc("sugg", null, "n/a");
    if (settings && settings.settings) { const s = settings.settings.find((x) => x.key === "net.web"); if (s) set({ research: s.value === true || s.value === "true" }); }
  }
  let seenReq = new Set(), reqPrimed = false;
  async function pollRequests() { // real request traces from the host (stage timings, no content)
    let list; try { list = await api("/v1/requests"); } catch (e) { return; }
    if (!Array.isArray(list)) return;
    list.slice().reverse().forEach((r) => {
      if (seenReq.has(r.id + r.outcome)) return; seenReq.add(r.id + r.outcome);
      if (reqPrimed && r.outcome !== "running") log(/complete|success|done|answer/.test(r.outcome) ? "ok" : /fail|error|timeout/.test(r.outcome) ? "err" : /cancel|stop|interrupt/.test(r.outcome) ? "warn" : "", `trace ${r.origin} · ${r.summary} · ${r.outcome}`);
    });
    reqPrimed = true; if (seenReq.size > 500) seenReq = new Set([...seenReq].slice(-200));
  }

  // ------------------------------------------------------------------------------------------ rendering from state
  function render(s, patch) {
    if (!built) return;
    const P = root._panels;
    if ("stage" in patch || "stageNote" in patch || "stagesDone" in patch) {
      root.dataset.stage = s.stage;
      P.rings._label.textContent = s.stage === "idle" ? "idle" : s.stage === "error" ? "fault" : "processing";
      P.rings._list.querySelectorAll("li").forEach((li) => { const st = li.dataset.stage; li.classList.toggle("active", st === s.stage); li.classList.toggle("done", s.stagesDone.includes(st) && st !== s.stage); });
      P.rings._note.textContent = s.stageNote; P.rings._note.className = "stage-note" + (s.stage === "error" ? " cerr" : "");
      const pct = { idle: s.answer ? 100 : 0, input: 25, analysis: 50, synthesis: 75, output: 100, error: 0 }[s.stage] ?? 0;
      P.source._bar.setAttribute("stroke-dasharray", `${(pct / 100 * 188.5).toFixed(1)} 999`); P.source._pct.textContent = s.stage === "idle" && !s.answer ? "—" : pct + "%";
      $("ro-resp").textContent = s.stage === "idle" ? "ready" : s.stage;
      const busy = !["idle", "error"].includes(s.stage);
      hot(["rings-hex", "keys-hex", "arc-rings"], busy); P.rings.classList.toggle("hot", busy); P.hex.classList.toggle("hot", busy);
      if (busy !== s.processing) { set({ processing: busy }); Sound.ambient(busy); }
    }
    if ("answer" in patch || "sources" in patch || "suggestions" in patch) {
      P.source._preview.textContent = s.answer ? s.answer.slice(0, 160) : "No answer yet."; P.source._srcN.textContent = s.sources.length; P.source._sugN.textContent = s.suggestions;
      hot(["rings-source"], s.sources.length > 0 || s.suggestions > 0);
    }
    if ("voice" in patch || "presence" in patch) {
      P.hex._sensors.talk.classList.toggle("on", s.voice); setMod("voice", s.voice);
      $("ro-voice").textContent = s.voice ? "session on" : "off";
      const vs = $("voiceState"); $("ro-stt").textContent = s.voice ? (vs && vs.textContent ? vs.textContent.toLowerCase() : "listening") : "idle";
    }
    if ("research" in patch) setMod("research", s.research === true);
    if ("filesOpen" in patch) { setMod("browser", s.filesOpen); hot(["files-hex", "mods-files"], false); }
  }
  function setMod(id, onNow) { const b = root && root._panels && root._panels.mods._mods[id]; if (b) b.classList.toggle("on", !!onNow); }
  on(render);

  // ------------------------------------------------------------------------------------------ audio settings UI
  function buildAudioUi() {
    const box = el("div", "core-audio"); box.setAttribute("aria-label", "Sound settings");
    const vol = el("input"); vol.type = "range"; vol.min = 0; vol.max = 100; vol.value = Math.round(Sound.prefs.volume * 100); vol.setAttribute("aria-label", "Master volume");
    const lab = (t, inp) => { const l = el("label"); l.append(inp, document.createTextNode(t)); return l; };
    const cb = (k, t) => { const c = el("input"); c.type = "checkbox"; c.checked = !!Sound.prefs[k]; c.dataset.k = k; c.addEventListener("change", () => { Sound.set(k, c.checked); Sound.play("click"); }); return lab(t, c); };
    const vl = el("label"); vl.append(document.createTextNode("VOLUME "), vol); vol.addEventListener("input", () => Sound.set("volume", vol.value / 100));
    box.append(vl, cb("ui", "INTERFACE"), cb("ambient", "AMBIENT"), cb("voice", "VOICE CUES"), cb("mute", "MUTE"));
    box._vol = vol; return box;
  }
  function syncAudioUi() { const box = root.querySelector(".core-audio"); if (!box) return; box._vol.value = Math.round(Sound.prefs.volume * 100); box.querySelectorAll("input[type=checkbox]").forEach((c) => { c.checked = !!Sound.prefs[c.dataset.k]; }); }

  // ------------------------------------------------------------------------------------------ public API
  let timers = [], stageIdle = 0;
  window.CluCore = {
    /** Called once by shell.js after the session is established. */
    init(apiFn, showFn) {
      api = apiFn; show = showFn; root = $("view-core"); if (!root) return;
      build(); root._panels.keys.append(buildAudioUi());
      render(state, { stage: 1, answer: 1, voice: 1, research: 1, filesOpen: 1 }); // every readout starts from the real state
      root.classList.toggle("reduced", !!reduced());
      log("ok", `CLU core online · ${ANDROID ? "Android" : "PC"} interface`);
      if (ANDROID) api("/v1/shell/platform").then((p) => { set({ platform: p, link: p.link }); (p.pcOnly || []).forEach(() => {}); }).catch(() => {});
      document.addEventListener("visibilitychange", () => { if (!document.hidden) startLoops(); });
    },
    /** The Core view became visible (shell.js navigation). */
    open() {
      if (!built) return; visible = true;
      if (!root.dataset.opened) { root.dataset.opened = "1"; Object.values(root._panels).forEach((p, i) => setTimeout(() => materialize(p, true), i * 70)); setTimeout(() => Particles.at(root._panels.rings, 40, { speed: 120 }), 520); }
      layoutConduits(); refresh(); loadDocs(); pollRequests(); startLoops();
      timers.forEach(clearInterval);
      timers = [setInterval(() => { if (visible && !document.hidden) refresh(); }, 5000), setInterval(() => { if (visible && !document.hidden) pollRequests(); }, 4000),
        setInterval(() => { if (visible && !document.hidden) { scanArc(); drawWave(); } }, 2400), setInterval(() => { if (visible && !document.hidden) drawWave(); }, 60)];
    },
    close() { visible = false; timers.forEach(clearInterval); timers = []; Sound.ambient(false); },
    /** A turn's real progress, from shell.js's stream: input → analysis → synthesis → output (or error), then idle. */
    turn(stage, data) {
      clearTimeout(stageIdle);
      if (stage === "input") { set({ stage: "input", stagesDone: [], stageAt: now(), stageNote: "› " + (data.text || "").slice(0, 120), answer: "", sources: [] }); log("you", "› " + (data.text || "")); Sound.play("processing", 0.5); glitch(root && root._panels && root._panels.rings); }
      else if (stage === "analysis") { if (state.stage === "input") set({ stagesDone: ["input"] }); set({ stage: "analysis", stageNote: data.text || "Analysing…" }); if (data.text) log("", data.text); }
      else if (stage === "synthesis") { if (state.stage !== "synthesis") { set({ stagesDone: ["input", "analysis"], stage: "synthesis" }); Sound.play("tick", 0.5); } set({ answer: state.answer + (data.delta || ""), stageNote: (state.answer + (data.delta || "")).slice(-140) }); }
      else if (stage === "output") {
        const ms = Math.round(now() - state.stageAt);
        set({ stagesDone: ["input", "analysis", "synthesis"], stage: "output", answer: data.text || state.answer, sources: data.sources || [], stageNote: data.error ? "Finished with a problem: " + data.error : data.interrupted ? "Stopped." : `Complete · ${ms} ms · ${(data.sources || []).length} sources` });
        log(data.error ? "err" : "ok", data.error ? "error: " + data.error : data.interrupted ? "stopped" : `answer complete (${ms} ms)`);
        Sound.play(data.error ? "error" : "ready", 0.5);
        if (!data.error && root && built) Particles.at(root._panels.rings, 24, { speed: 110, life: 0.5 });
      }
      else if (stage === "error") { set({ stage: "error", stageNote: data.text || "Error" }); log("err", data.text || "error"); Sound.play("error", 0.5); glitch(root && root._panels && root._panels.rings); }
      else if (stage === "suggestion") { log("ok", "new suggestion: " + (data.text || "").slice(0, 80)); set({ suggestions: state.suggestions + 1 }); }
      else if (stage === "action") log("", "capability action: " + (data.type || data.kind || "update"));
      if (stage === "idle" || stage === "output") stageIdle = setTimeout(() => set({ stage: "idle", stagesDone: state.stage === "error" ? [] : STAGES.slice() }), stage === "idle" ? 1400 : 2600);
    },
    /** CLU's presence (voice states included), from shell.js. */
    presence(st, label) {
      const voice = !!(window.CluVoice && CluVoice.active && CluVoice.active());
      if (voice !== state.voice) { Sound.play(voice ? "listen" : "dissolve", 0.8); log("", voice ? "voice session on" : "voice session off"); }
      set({ voice, presence: st });
    },
    log, sound: Sound, particles: Particles, state: () => Object.assign({}, state),
  };
})();
