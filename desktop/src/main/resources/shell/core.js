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
    stage: "idle", stagesDone: [], stageNote: "waiting for input", stageAt: 0, answer: "", sources: [], suggestions: 0,
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
  const W = 2000, H = 1244; // the reference's coordinate space: every region below is placed in it
  let stage = null, svgRoot = null;
  const pct = (x, y, w, h) => ({ left: (x / W * 100) + "%", top: (y / H * 100) + "%", width: (w / W * 100) + "%", height: (h / H * 100) + "%" });
  function glitch(node) { if (!node || reduced()) return; node.classList.remove("glitch"); void node.getBoundingClientRect(); node.classList.add("glitch"); setTimeout(() => node.classList.remove("glitch"), 160); }
  function frags(node) {
    if (!stage || !node || reduced()) return;
    const s = stage.getBoundingClientRect(), r = node.getBoundingClientRect();
    for (let i = 0; i < 5; i++) { const f = el("span", "frag"); f.style.left = (r.left - s.left + Math.random() * r.width) + "px"; f.style.top = (r.top - s.top + Math.random() * r.height) + "px"; stage.append(f); setTimeout(() => f.remove(), 320); }
  }
  /** Opens or closes an HTML surface with the materialization sequence (scan-line, outline, fragments, light). */
  function materialize(node, open, done) {
    if (!node) return;
    if (reduced()) { node.classList.toggle("hidden", !open); if (done) done(); return; }
    if (open) node.classList.remove("hidden");
    node.classList.remove("mz-in", "mz-out"); void node.offsetWidth; node.classList.add(open ? "mz-in" : "mz-out");
    Sound.play(open ? "materialize" : "dissolve", xOf(node)); glitch(node); if (open) frags(node);
    setTimeout(() => { node.classList.remove("mz-in", "mz-out"); if (!open) node.classList.add("hidden"); if (done) done(); }, open ? 440 : 300);
  }
  /** Brings an SVG region up (start-up): it flickers into place. */
  function regionIn(g, delay) { if (!g || reduced()) return; g.style.animationDelay = delay + "ms"; g.classList.add("region-in"); setTimeout(() => { frags(g); Sound.play("tick", xOf(g)); }, delay + 80); }
  /** Mechanical feedback on a control: a pressed flash, a ripple where it was touched, a click. */
  function press(node, e) {
    if (!node) return; Sound.play("click", xOf(node));
    node.classList.add("pressed"); setTimeout(() => node.classList.remove("pressed"), 140);
    if (reduced() || !e || !stage || e.clientX == null) return;
    const s = stage.getBoundingClientRect(), rp = el("span", "ripple"), r = node.getBoundingClientRect(), d = Math.max(r.width, r.height) * 1.5;
    rp.style.width = rp.style.height = d + "px"; rp.style.left = (e.clientX - s.left) + "px"; rp.style.top = (e.clientY - s.top) + "px";
    stage.append(rp); setTimeout(() => rp.remove(), 480);
  }
  /** Makes an SVG group a real control: click, Enter/Space, accessible name. */
  function control(g, label, fn) {
    g.classList.add("hit"); g.setAttribute("role", "button"); g.setAttribute("tabindex", "0"); g.setAttribute("aria-label", label);
    const t = svg("title", {}, g); t.textContent = label;
    g.addEventListener("click", (e) => { press(g, e); fn(e); });
    g.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); press(g); fn(e); } });
    return g;
  }
  const hexPts = (cx, cy, r, flat = true) => Array.from({ length: 6 }, (_, i) => { const a = Math.PI / 3 * i + (flat ? 0 : Math.PI / 6); return `${(cx + r * Math.cos(a)).toFixed(1)},${(cy + r * Math.sin(a)).toFixed(1)}`; }).join(" ");
  /** A frame line: faint halo + thin bright stroke + an energy pulse travelling along it (a conduit). */
  const conduits = {};
  function frame(parent, d, id, cls) {
    svg("path", { class: "halo", d }, parent); svg("path", { class: "ln" + (cls ? " " + cls : ""), d }, parent);
    const f = svg("path", { class: "flow", d }, parent); f.style.animationDelay = (-Math.random() * 9).toFixed(2) + "s";
    if (id) conduits[id] = (conduits[id] || []).concat(f);
    return f;
  }
  function hot(ids, onNow) { ids.forEach((id) => (conduits[id] || []).forEach((f) => f.classList.toggle("hot", onNow))); }
  function fo(parent, x, y, w, h, cls) { const f = svg("foreignObject", { x, y, width: w, height: h }, parent); const d = document.createElement("div"); d.className = "fo" + (cls ? " " + cls : ""); f.append(d); return d; }
  const T = (parent, x, y, text, cls, attrs) => { const t = svg("text", Object.assign({ x, y, class: cls || "" }, attrs || {}), parent); t.textContent = text; return t; };

  // ------------------------------------------------------------------------------------------ console / terminal output
  let consoleEl = null; const MAX_LINES = 300;
  function log(level, text) {
    if (!consoleEl) return;
    const d = new Date(), ts = d.toTimeString().slice(0, 8);
    const line = el("span", "ln " + (level || "")); line.append(el("span", "ts", ts), document.createTextNode(text));
    consoleEl.append(line); while (consoleEl.childNodes.length > MAX_LINES) consoleEl.firstChild.remove();
    consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  // ------------------------------------------------------------------------------------------ the machine
  const UI = {}; // references into the reconstruction
  function build() {
    root.replaceChildren();
    stage = el("div", "core-stage"); root.append(stage);
    const s = svgRoot = svg("svg", { class: "core-svg", viewBox: `0 0 ${W} ${H}`, role: "application", "aria-label": "CLU core" }, stage);
    const parts = el("canvas", "core-particles"); parts.setAttribute("aria-hidden", "true"); stage.append(parts); Particles.attach(parts);
    UI.regions = {
      frame: buildFrame(s), arc: buildArc(s), rings: buildRings(s), source: buildSource(s), pipe: buildPipeline(s),
      mods: buildMods(s), files: buildFiles(s), keys: buildCommand(s), hex: buildHex(s), corner: buildCorner(s),
    };
    stage.append(buildSourceWindow(), buildKeyboard(), buildAudioPanel());
    built = true;
  }

  // The structural line work of the reference (frames, separators, the long conduits) ------------------------------
  function buildFrame(s) {
    const g = svg("g", { id: "reg-frame" }, s);
    frame(g, "M572 106 V440", "arc-rings");                                         // arc's right edge
    frame(g, "M62 576 H384", "arc");                                                // arc base
    frame(g, "M612 110 H1110 L1330 482 H1874", "rings-source");                     // top frame → right ruler
    frame(g, "M628 124 H1100 L1312 468", null, "thin dim");                         // inner top frame
    frame(g, "M1310 470 V482", null, "thin");
    frame(g, "M62 666 H404", "mods");                                               // left separator
    frame(g, "M438 668 H1150 L1262 586 H1300", "pipe-hex");                         // middle separator → octagon
    frame(g, "M452 722 H1075 L1112 684 H1170 L1270 600", "files-hex", "thin");      // lower frame top
    frame(g, "M452 722 L246 1062", "files");                                        // lower frame left diagonal
    frame(g, "M1170 668 L1210 630", null, "thin dim");
    frame(g, "M440 668 L420 690 V700", null, "thin dim");
    // progress rail under the lower frame (upload / processing progress)
    svg("rect", { x: 760, y: 766, width: 262, height: 10, class: "fill-deep" }, g);
    UI.rail = svg("rect", { x: 762, y: 768, width: 0, height: 6, fill: "#8fd3f0" }, g);
    for (let i = 0; i < 26; i++) svg("rect", { x: 764 + i * 10, y: 769, width: 6, height: 4, fill: "#5d7c89", opacity: 0.4 }, g);
    return g;
  }

  // A. Arc data matrix (top-left quadrant) + five lamps + shield emblem ----------------------------------------------
  const ARC_ROWS = [
    { key: "tasks", label: "TASKS / APPROVALS" }, { key: "sugg", label: "SUGGESTIONS" }, { key: "records", label: "MEMORY RECORDS" },
    { key: "infer", label: "INFERENCE QUEUE" }, { key: "sys", label: "SYSTEM MEMORY" }, { key: "proc", label: "CLU MEMORY" },
  ];
  const arcBlocks = [];
  function buildArc(s) {
    const g = svg("g", { id: "reg-arc" }, s);
    const cx = 572, cy = 576, A0 = Math.PI, A1 = Math.PI * 1.5;
    const pt = (r, a) => [cx + r * Math.cos(a), cy + r * Math.sin(a)];
    const arcPath = (r0, r1, a0, a1) => { const [x0, y0] = pt(r1, a0), [x1, y1] = pt(r1, a1), [x2, y2] = pt(r0, a1), [x3, y3] = pt(r0, a0);
      return `M${x0} ${y0} A${r1} ${r1} 0 0 1 ${x1} ${y1} L${x2} ${y2} A${r0} ${r0} 0 0 0 ${x3} ${y3} Z`; };
    svg("path", { class: "band", d: arcPath(206, 472, A0, A1) }, g);
    ARC_ROWS.forEach((row, ri) => {
      const r0 = 210 + ri * 44, r1 = r0 + 38, n = 12 + ri * 5, blocks = [];
      svg("path", { class: "band", d: arcPath(r0 - 2, r1 + 2, A0, A1) }, g);
      // blocks fill the first 72% of the quarter; the rest carries the curved label (as in the reference)
      for (let i = 0; i < n; i++) {
        const a0 = A0 + (i / n) * (Math.PI / 2) * 0.72 + 0.006, a1 = A0 + ((i + 1) / n) * (Math.PI / 2) * 0.72 - 0.006;
        const rr0 = r0 + 4 + (i % 3 === 1 ? 6 : 0), rr1 = r1 - 4 - (i % 4 === 2 ? 8 : 0);
        const b = svg("path", { class: "blk", d: arcPath(rr0, rr1, a0, a1) }, g);
        b.style.setProperty("--base", (0.22 + ((i * 7 + ri * 3) % 5) * 0.09).toFixed(2)); // static structure texture, not data
        blocks.push(b);
      }
      arcBlocks.push(blocks);
      const id = "arcp" + ri; const [lx0, ly0] = pt(r0 + 13, A0 + Math.PI / 2 * 0.745), [lx1, ly1] = pt(r0 + 13, A1 - 0.01);
      svg("path", { id, d: `M${lx0} ${ly0} A${r0 + 13} ${r0 + 13} 0 0 1 ${lx1} ${ly1}`, fill: "none" }, g);
      const t = svg("text", { class: "arc-label" }, g); const tp = svg("textPath", { href: "#" + id }, t); tp.textContent = row.label;
      const [vx0, vy0] = pt(r0 + 30, A0 + Math.PI / 2 * 0.745), [vx1, vy1] = pt(r0 + 30, A1 - 0.01);
      svg("path", { id: id + "v", d: `M${vx0} ${vy0} A${r0 + 30} ${r0 + 30} 0 0 1 ${vx1} ${vy1}`, fill: "none" }, g);
      const tv = svg("text", { class: "val" }, g); const tpv = svg("textPath", { href: "#" + id + "v", id: "arc-" + row.key }, tv); tpv.textContent = "…";
    });
    // five lamps under the arc, joined by a bus (model, voice, network, host, research)
    UI.lamps = {};
    [["model", "MODEL"], ["voice", "VOICE"], ["net", "NET"], ["host", "HOST"], ["research", "WEB"]].forEach(([k, label], i) => {
      const x = 88 + i * 66.5, lg = svg("g", { class: "lamp" }, g);
      svg("circle", { class: "ring", cx: x, cy: 606, r: 19 }, lg); svg("circle", { class: "core", cx: x, cy: 606, r: 11 }, lg);
      svg("path", { class: "ln thin", d: `M${x} 625 V${i % 2 ? 650 : 642}` }, g);
      T(g, x, 662, label, "lbl", { "text-anchor": "middle", "font-size": 10 });
      UI.lamps[k] = lg;
    });
    svg("path", { class: "ln thin", d: "M88 642 H354 M154 650 H287" }, g);
    // shield emblem (system status): pentagon with nested triangles
    const shield = svg("g", {}, g);
    svg("polygon", { class: "fillp", points: "486,598 572,522 656,598 622,652 522,652" }, shield);
    svg("polygon", { class: "ln", points: "526,632 572,552 618,632" }, shield);
    svg("polygon", { class: "ln thin", points: "543,622 572,572 601,622" }, shield);
    svg("polygon", { class: "glyph dim", points: "560,612 572,592 584,612" }, shield);
    control(shield, "System status: model, memory, governor", () => { log("", "open status"); show("status"); });
    UI.shield = shield;
    return g;
  }
  function setArc(key, fraction, text) {
    const i = ARC_ROWS.findIndex((r) => r.key === key); if (i < 0) return;
    const blocks = arcBlocks[i]; const lit = fraction == null ? 0 : Math.round(clamp(fraction, 0, 1) * blocks.length);
    blocks.forEach((b, j) => { b.classList.toggle("on", j < lit); b.classList.toggle("hi", j < lit && j >= lit - 2); });
    const t = $("arc-" + key); if (t) { t.textContent = text; t.setAttribute("opacity", fraction == null ? "0.45" : "1"); }
  }
  let arcScan = 0;
  function scanArc() { if (reduced()) return; const row = arcBlocks[arcScan % arcBlocks.length]; arcScan++; row.forEach((b, j) => setTimeout(() => { b.classList.add("scan"); setTimeout(() => b.classList.remove("scan"), 140); }, j * 35)); }

  // C. Process core (centre frame): CLU mark, three count blocks, trefoil hex, dotted concentric rings ---------------
  const STAGES = ["input", "analysis", "synthesis", "output"];
  const rings = [];
  function buildRings(s) {
    const g = svg("g", { id: "reg-rings" }, s);
    // CLU mark (the reference's angular glyph)
    [["612,128 636,128 656,158 676,128 700,128 668,178 668,212 644,212 644,178"], ["612,190 634,190 634,242 612,242"], ["646,226 668,226 668,248 646,248"], ["612,254 634,254 634,300 612,300"], ["650,262 672,262 672,280 650,280"]]
      .forEach(([p]) => svg("polygon", { class: "glyph", points: p }, g));
    // three count blocks: conversations, memory kinds, documents (cells lit by real counts)
    UI.blocks = {};
    [["conv", "CONV", 758], ["mem", "MEM", 848], ["docs", "DOCS", 938]].forEach(([k, label, x]) => {
      T(g, x + 30, 134, label, "lbl", { "text-anchor": "middle", "font-size": 11 });
      const cells = []; for (let r = 0; r < 5; r++) for (let c = 0; c < 4; c++) cells.push(svg("rect", { class: "cell", x: x + 6 + c * 14, y: 144 + r * 11, width: 10, height: 7 }, g));
      svg("path", { class: "ln thin", d: `M${x} 142 V200 M${x - 4} ${150 + (k === "mem" ? 30 : 10)} h-6` }, g);
      svg("circle", { class: "ln thin", cx: x - 12, cy: 150 + (k === "mem" ? 30 : 10), r: 3 }, g);
      UI.blocks[k] = { cells, n: T(g, x + 30, 214, "…", "val", { "text-anchor": "middle", "font-size": 12 }) };
    });
    // trefoil hexagon (top right of the frame): opens the conversation
    const tre = svg("g", {}, g);
    svg("polygon", { class: "fillp", points: hexPts(1070, 192, 44) }, tre);
    svg("polygon", { class: "ln thin", points: hexPts(1070, 192, 34) }, tre);
    [[0], [2], [4]].forEach(([k]) => { const a = Math.PI / 3 * k; const p = (r, b) => `${1070 + r * Math.cos(b)},${192 + r * Math.sin(b)}`; svg("polygon", { class: "glyph", points: `${p(4, a + 0.5)} ${p(28, a + 0.2)} ${p(28, a + 0.85)}` }, tre); });
    control(tre, "Conversation with CLU (Chat)", () => { log("", "open chat"); show("chat"); });
    // dotted concentric rings: INPUT (outer) → OUTPUT (inner)
    const rg = svg("g", {}, g);
    [[112, "4 12"], [90, "6 10"], [68, "3 9"], [46, "5 11"]].forEach(([r, dash], i) => {
      const gg = svg("g", {}, rg); const c = svg("circle", { class: "pring", cx: 880, cy: 312, r, "stroke-dasharray": dash }, gg);
      if (i === 0) svg("circle", { class: "ln thin dim", cx: 880, cy: 312, r: 128, "stroke-dasharray": "2 16" }, rg);
      rings.push({ g: gg, c, a: i * 37, v: (i % 2 ? -1 : 1) * 4 });
    });
    for (let i = 0; i < 22; i++) { const a = Math.random() * Math.PI * 2, r = Math.random() * 38; svg("rect", { class: "glyph dim", x: 880 + r * Math.cos(a), y: 312 + r * Math.sin(a), width: 3, height: 3 }, rg); }
    // the cradle under the rings, as in the reference
    svg("path", { class: "ln", d: "M748 300 V378 L838 454 H924 L1004 384 V300" }, g);
    svg("path", { class: "ln thin", d: "M726 236 H748 M1004 236 H1030 M1004 300 H1030" }, g);
    svg("circle", { class: "ln thin", cx: 723, cy: 236, r: 4 }, g); svg("circle", { class: "ln thin", cx: 1033, cy: 300, r: 4 }, g);
    // stage list (the reference's ≡○ marker and short bars)
    UI.stageRows = {};
    T(g, 1120, 282, "≡ ○", "lbl", { "font-size": 16 });
    STAGES.forEach((st, i) => { const row = svg("g", { class: "stage-row" }, g); svg("rect", { class: "dia", x: 1124, y: 302 + i * 22, width: 9, height: 9, transform: `rotate(45 1128.5 ${306.5 + i * 22})` }, row); T(row, 1144, 311 + i * 22, st.toUpperCase(), "", { "font-size": 12 }); UI.stageRows[st] = row; });
    UI.stageNote = T(g, 880, 482, "IDLE · WAITING FOR INPUT", "lbl", { "text-anchor": "middle", "font-size": 12 });
    return g;
  }
  const RING_SPEED = { idle: 4, pending: 7, active: 140, done: 22 };
  let ringRaf = 0, ringLast = 0;
  function ringLoop() {
    const t = now(), dt = Math.min(0.05, (t - ringLast) / 1000); ringLast = t;
    const cur = STAGES.indexOf(state.stage);
    rings.forEach((r, i) => {
      const mode = cur < 0 ? "idle" : i === cur ? "active" : i < cur || state.stagesDone.includes(STAGES[i]) ? "done" : "pending";
      const target = RING_SPEED[mode] * (i % 2 ? -1 : 1);
      r.v += (target - r.v) * Math.min(1, dt * (mode === "active" ? 3 : 1.4));
      r.a = (r.a + r.v * dt) % 360; r.g.setAttribute("transform", `rotate(${r.a.toFixed(2)} 880 312)`);
      r.c.classList.toggle("active", mode === "active"); r.c.classList.toggle("done", mode === "done");
    });
    ringRaf = visible && !document.hidden && !reduced() ? requestAnimationFrame(ringLoop) : 0;
  }
  function startLoops() { if (!ringRaf && visible && !reduced()) { ringLast = now(); ringRaf = requestAnimationFrame(ringLoop); } }

  // Pipeline node (lower centre): wire hexagon fed by the connector lines: where documents are injected -------------
  function buildPipeline(s) {
    const g = svg("g", { id: "reg-pipe" }, s);
    frame(g, "M724 536 H930 V512 H972", "pipe", "thin"); frame(g, "M742 560 H948", "pipe", "thin"); frame(g, "M796 606 H900 L930 576 H1000", "pipe", "thin");
    [[724, 536], [742, 560], [796, 606]].forEach(([x, y]) => svg("circle", { class: "ln thin", cx: x, cy: y, r: 4 }, g));
    svg("path", { class: "ln thin", d: "M940 488 H1000 V510 M960 500 H990" }, g);
    const hx = svg("g", {}, g);
    svg("polygon", { class: "ln", points: hexPts(1090, 566, 82, false) }, hx);
    svg("polygon", { class: "ln thin", points: hexPts(1090, 566, 62, false) }, hx);
    svg("path", { class: "ln thin", d: "M1090 492 V640 M1022 566 H1158 M1040 520 L1060 532 M1140 520 L1120 532 M1040 612 L1060 600 M1140 612 L1120 600" }, hx);
    svg("rect", { class: "glyph", x: 1024, y: 484, width: 34, height: 8 }, hx);
    UI.pipeHex = hx; UI.pipeLabel = T(g, 856, 584, "", "lbl", { "font-size": 11 });
    // barcode and readout marks at the bottom-left of the frame
    for (let i = 0; i < 16; i++) svg("rect", { class: "glyph" + (i % 3 ? " dim" : ""), x: 662 + i * 4.5, y: 622, width: i % 2 ? 2 : 3, height: 14 }, g);
    UI.pipeCount = T(g, 740, 634, "", "lbl", { "font-size": 10 });
    svg("rect", { class: "ln thin", x: 952, y: 612, width: 18, height: 30 }, g);
    return g;
  }

  // B. Source / idea container (diagonal diagnostics band + segmented ring + ticker) -------------------------------
  const stripes = [];
  function buildSource(s) {
    const g = svg("g", { id: "reg-source" }, s);
    // header marks + diagonal hatch
    svg("rect", { class: "ln thin", x: 1264, y: 98, width: 24, height: 14 }, g); T(g, 1294, 111, ":cc", "lbl", { "font-size": 13 });
    for (let i = 0; i < 9; i++) svg("path", { class: "ln thin", d: `M${1168 + i * 10} 132 L${1176 + i * 10} 118` }, g);
    svg("path", { class: "ln thin", d: "M1166 118 H1258 V132 H1166 Z" }, g);
    // two diagonal stripe ladders: lit stripes = the current turn's progress (real stage)
    for (let i = 0; i < 26; i++) { const x = 1190 + i * 5.6, y = 150 + i * 12.5, w = 8 + (i % 5) * 7; stripes.push(svg("rect", { class: "stripe", x, y, width: w, height: 6 }, g)); }
    for (let i = 0; i < 28; i++) { const x = 1300 + i * 6.2, y = 196 + i * 10.6, w = 20 + (i % 4) * 9; stripes.push(svg("rect", { class: "stripe", x, y, width: w, height: 5 }, g)); }
    svg("path", { class: "ln thin dim", d: "M1222 150 L1360 420 M1262 136 L1420 440" }, g);
    [[1232, 154], [1278, 256], [1338, 330], [1364, 236]].forEach(([x, y]) => svg("polygon", { class: "arrow-g", points: `${x},${y} ${x + 12},${y + 20} ${x - 4},${y + 16}` }, g));
    // the ⊖ control: opens the intelligence container
    const opn = svg("g", {}, g);
    svg("circle", { class: "fill-deep", cx: 1346, cy: 160, r: 22 }, opn); svg("rect", { class: "glyph", x: 1334, y: 157, width: 24, height: 6 }, opn);
    svg("path", { class: "ln", d: "M1322 160 H1310 M1370 160 H1382 M1346 136 V128 M1346 184 V192" }, opn);
    control(opn, "Sources and ideas: open the intelligence container", () => openSource(true));
    // small text bars and the warning triangle (lit on a fault)
    [[1150, 302, 60], [1162, 316, 52], [1150, 330, 70], [1168, 344, 44], [1150, 358, 64]].forEach(([x, y, w]) => svg("rect", { class: "glyph dim", x, y, width: w, height: 7 }, g));
    svg("rect", { class: "ln thin", x: 1226, y: 398, width: 30, height: 12 }, g);
    UI.warn = svg("polygon", { class: "ln", points: "1268,432 1286,462 1250,462" }, g); svg("rect", { class: "glyph dim", x: 1264, y: 446, width: 8, height: 8 }, g);
    // segmented ring (the container proper)
    const rg = svg("g", {}, g); const cx = 1692, cy = 240, R = 136;
    const seg = (a0, a1, r, cls) => { const p = (a) => `${cx + r * Math.cos(a)} ${cy + r * Math.sin(a)}`; svg("path", { class: "sseg " + (cls || ""), d: `M${p(a0)} A${r} ${r} 0 ${a1 - a0 > Math.PI ? 1 : 0} 1 ${p(a1)}` }, rg); };
    const D = Math.PI / 180;
    seg(-80 * D, -30 * D, R); seg(-24 * D, 18 * D, R); seg(24 * D, 70 * D, R, "accent"); seg(78 * D, 150 * D, R); seg(158 * D, 196 * D, R); seg(204 * D, 262 * D, R, "accent");
    svg("path", { class: "sseg accent", d: `M${cx + 168 * Math.cos(-62 * D)} ${cy + 168 * Math.sin(-62 * D)} A168 168 0 0 1 ${cx + 168 * Math.cos(-18 * D)} ${cy + 168 * Math.sin(-18 * D)}`, "stroke-width": 22 }, rg);
    svg("path", { class: "ln thin", d: `M${cx - 168} ${cy - 40} V${cy + 40}` }, rg);
    UI.sprog = svg("circle", { class: "sprog", cx, cy, r: 112, "stroke-dasharray": "0 999", transform: `rotate(-90 ${cx} ${cy})` }, rg);
    UI.spct = T(rg, cx, cy - 4, "—", "big", { "text-anchor": "middle" });
    UI.scount = T(rg, cx, cy + 30, "SRC 0 · SUG 0", "lbl", { "text-anchor": "middle", "font-size": 12 });
    control(rg, "Sources and ideas: open the intelligence container", () => openSource(true));
    UI.preview = fo(g, 1548, 392, 290, 50, "");
    UI.preview.classList.add("preview-txt"); UI.preview.textContent = "No answer yet.";
    // ticker (dotted ruler): characters streamed in the current answer
    UI.ticks = []; for (let i = 0; i < 52; i++) UI.ticks.push(svg("rect", { class: "tick-bar", x: 1562 + i * 6, y: 454, width: 3, height: 12 }, g));
    return g;
  }

  // F. Module honeycomb (left) + five pointer toggles (sound and motion modes) ---------------------------------------
  const ICONS = {
    clu: "M0 -12 L10 -6 V6 L0 12 L-10 6 V-6 Z M0 -5 V5", research: "M-3 -9 a7 7 0 1 0 0.1 0 M3 3 L10 10", terminal: "M-10 -7 L-3 0 L-10 7 M0 8 H10",
    system: "M-10 -8 H10 V5 H-10 Z M-4 10 H4", memory: "M-7 -10 H7 V10 H-7 Z M-7 -3 H7 M-7 4 H7", voice: "M0 -10 V2 M-6 -2 A6 6 0 0 0 6 -2 M0 4 V9",
    files: "M-10 -8 H10 V9 H-10 Z M-10 -3 H10", utility: "M0 -5 A5 5 0 1 0 0.1 -5 M0 -12 V-8 M0 8 V12 M-12 0 H-8 M8 0 H12",
  };
  const MODS = [
    ["clu", "CLU", "Conversation with CLU"], ["research", "WEB", "Web research on or off (Settings → Network)"], ["terminal", "TERM", "Command line"],
    ["system", "SYS", "Status: model, memory, governor"], ["memory", "MEM", "Memory and archive"], ["voice", "VOICE", "Voice conversation"],
    ["files", "FILES", "File explorer"], ["utility", "UTIL", "Settings"],
  ];
  function buildMods(s) {
    const g = svg("g", { id: "reg-mods" }, s); UI.mods = {};
    // flat-top honeycomb in the reference's position under the arc
    const cells = [[236, 724], [312, 724], [388, 724], [274, 790], [350, 790], [236, 856], [312, 856], [388, 856]];
    cells.forEach(([x, y], i) => {
      const [id, label, title] = MODS[i]; const m = svg("g", { class: "modhex" }, g);
      svg("polygon", { class: "fillp", points: hexPts(x, y, 36) }, m);
      const ic = svg("path", { d: ICONS[id], transform: `translate(${x} ${y - 8})`, fill: "none", stroke: "currentColor", class: "ln thin" }, m);
      T(m, x, y + 22, label, "", { "text-anchor": "middle" });
      control(m, title, () => module(id, m)); UI.mods[id] = m; void ic;
    });
    // pointer toggles: dot + line + label (interface sound, ambient, voice cues, mute, motion)
    UI.dots = {};
    [["ui", "INTERFACE", 88, 746, 176], ["ambient", "AMBIENT", 132, 860, 214], ["motion", "MOTION", 258, 940, 302], ["voice", "VOICE CUES", 134, 968, 186], ["mute", "MUTE", 186, 1008, 252]]
      .forEach(([k, label, x, y, x2]) => {
        const d = svg("g", { class: "dotsw" }, g);
        svg("line", { class: "ln thin", x1: x + 10, y1: y, x2, y2: y }, d); svg("circle", { class: "d", cx: x, cy: y, r: 9 }, d);
        T(d, x - 4, y - 14, label, "", { "font-size": 11 });
        control(d, label.toLowerCase() + " on or off", () => toggleMode(k)); UI.dots[k] = d;
      });
    return g;
  }
  function toggleMode(k) {
    if (k === "motion") { const r = !root.classList.contains("reduced"); root.classList.toggle("reduced", r); try { localStorage.setItem("clu-core-reduced", r ? "1" : "0"); } catch (e) { /* private */ } log("ok", "motion " + (r ? "reduced" : "full")); }
    else { Sound.set(k, !Sound.prefs[k]); log("ok", `${k === "ui" ? "interface sounds" : k === "voice" ? "voice cues" : k} ${Sound.prefs[k] ? "on" : "off"}`); }
    syncModes(); Sound.play("confirm");
  }
  function syncModes() {
    if (!UI.dots) return;
    ["ui", "ambient", "voice", "mute"].forEach((k) => UI.dots[k].classList.toggle("on", !!Sound.prefs[k]));
    UI.dots.motion.classList.toggle("on", !root.classList.contains("reduced"));
    const p = $("coreAudio"); if (p) { p.querySelector("input[type=range]").value = Math.round(Sound.prefs.volume * 100); p.querySelectorAll("input[type=checkbox]").forEach((c) => { c.checked = !!Sound.prefs[c.dataset.k]; }); }
  }
  const syncAudioUi = syncModes;

  // G. File explorer (the open lower region) --------------------------------------------------------------------------
  let docs = [], picked = null, selectedDoc = null;
  function buildFiles(s) {
    const g = svg("g", { id: "reg-files" }, s);
    T(g, 520, 760, "FILE EXPLORER", "lbl"); UI.docCount = T(g, 1180, 760, "", "lbl", { "text-anchor": "end" });
    const box = fo(g, 520, 788, 660, 186, ""); // stops above the emblem (y 986), as the reference's lower region does
    const files = el("div", "files"); box.append(files);
    const left = el("div"); const drop = el("div", "drop", "Drop a file here, or tap to choose"); const input = el("input"); input.type = "file"; input.className = "hidden-input"; input.setAttribute("aria-label", "Choose a file");
    const list = el("ul", "file-list"); left.append(drop, input, list); files.append(left);
    const right = el("div", "right"); const meta = el("div", "meta-grid"); const prev = el("div", "preview"); const acts = el("div", "acts");
    const up = el("button", "kbtn", "Upload"), inj = el("button", "kbtn", "Inject"); up.disabled = inj.disabled = true; inj.title = "Ask CLU about this document (it goes into the conversation)";
    acts.append(up, inj); right.append(meta, prev, acts); files.append(right);
    Object.assign(UI, { fList: list, fMeta: meta, fPrev: prev, fUp: up, fInj: inj, fDrop: drop });
    drop.addEventListener("click", (e) => { press(drop, e); input.click(); });
    input.addEventListener("change", () => input.files[0] && pick(input.files[0]));
    ["dragenter", "dragover"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("over"); }));
    ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, () => drop.classList.remove("over")));
    drop.addEventListener("drop", (e) => { e.preventDefault(); const f = e.dataTransfer && e.dataTransfer.files[0]; if (f) pick(f); });
    up.addEventListener("click", (e) => { press(up, e); upload(); });
    inj.addEventListener("click", (e) => { press(inj, e); inject(); });
    return g;
  }
  const kb = (n) => n == null ? "?" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";
  function meta(pairs) { UI.fMeta.replaceChildren(); pairs.forEach(([k, v]) => UI.fMeta.append(el("span", "k", k), el("span", "v", v))); }
  function railTo(f) { UI.rail.setAttribute("width", (clamp(f, 0, 1) * 258).toFixed(1)); }
  function pick(f) {
    picked = f; selectedDoc = null;
    meta([["NAME", f.name], ["TYPE", f.type || "unknown"], ["SIZE", kb(f.size)], ["MODIFIED", new Date(f.lastModified).toLocaleString()], ["STATE", "not uploaded"]]);
    UI.fPrev.replaceChildren(el("div", "cdim", "Preview…"));
    if (/^image\//.test(f.type)) { const img = el("img"); img.alt = f.name; img.src = URL.createObjectURL(f); img.onload = () => URL.revokeObjectURL(img.src); UI.fPrev.replaceChildren(img); }
    else if (/^text\/|json|xml|csv|markdown/.test(f.type) || /\.(txt|md|csv|json|log|kt|js|py)$/i.test(f.name)) { f.slice(0, 6000).text().then((t) => UI.fPrev.replaceChildren(el("pre", null, t))).catch(() => UI.fPrev.replaceChildren(el("div", "cdim", "No preview."))); }
    else UI.fPrev.replaceChildren(el("div", "cdim", "No preview for this type. CLU reads PDF, Word and text documents when uploaded."));
    UI.fUp.disabled = false; UI.fInj.disabled = true; Sound.play("chirp", xOf(UI.fDrop)); railTo(0);
    UI.fList.querySelectorAll("li").forEach((x) => x.classList.remove("sel"));
  }
  async function upload() {
    if (!picked) return;
    UI.fUp.disabled = true; hot(["files", "files-hex", "pipe"], true); railTo(0.35); log("", `upload ${picked.name} (${kb(picked.size)})…`); Sound.play("processing", 0.4);
    try {
      const r = await fetch("/v1/documents", { method: "POST", body: picked, headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token"), "X-File-Name": encodeURIComponent(picked.name) } });
      const d = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
      railTo(1); log("ok", `synchronised: ${d.name} · ${d.textStatus === "indexed" ? (d.chars || 0).toLocaleString() + " characters indexed" : "stored, no searchable text"}`);
      Sound.play("success", 0.45); Particles.at(UI.pipeHex, 70, { speed: 170 }); glitch(UI.regions.pipe);
      picked = null; await loadDocs(); const li = [...UI.fList.children].find((x) => x._doc && x._doc.id === d.id); if (li) li.click();
    } catch (e) { railTo(0); log("err", "upload refused: " + e.message); Sound.play("error", 0.4); glitch(UI.regions.files); UI.fUp.disabled = false; }
    setTimeout(() => hot(["files", "files-hex", "pipe"], false), 900);
  }
  function inject() {
    if (!selectedDoc || !window.CluShell) return;
    const q = `Summarise the document "${selectedDoc.name}" and tell me what matters in it.`;
    log("you", "› " + q); hot(["pipe", "pipe-hex"], true); setTimeout(() => hot(["pipe", "pipe-hex"], false), 1500);
    Particles.at(UI.pipeHex, 30, { speed: 120 }); CluShell.send(q);
  }
  async function loadDocs() {
    try { docs = await api("/v1/documents"); } catch (e) { UI.fList.replaceChildren(el("li", "cdim", "Documents: " + e.message)); return; }
    UI.fList.replaceChildren(); UI.docCount.textContent = docs.length + " IN ARCHIVE"; UI.pipeCount.textContent = docs.length + " DOCS";
    setCells("docs", docs.length);
    if (!docs.length) UI.fList.append(el("li", "cdim", "No documents yet."));
    docs.forEach((d) => {
      const li = el("li"); li._doc = d; li.append(el("span", null, d.name), el("small", null, kb(d.sizeBytes)));
      li.addEventListener("click", () => {
        UI.fList.querySelectorAll("li").forEach((x) => x.classList.remove("sel")); li.classList.add("sel"); selectedDoc = d; picked = null;
        meta([["NAME", d.name], ["TYPE", d.type], ["SIZE", kb(d.sizeBytes)], ["TEXT", d.textStatus === "indexed" ? (d.chars || 0).toLocaleString() + " chars indexed" : d.textStatus], ["DATE", d.date || "—"]]);
        UI.fPrev.replaceChildren(el("div", "cdim", "Stored in CLU's archive. Inject sends it into the pipeline: CLU reads it in this conversation."));
        UI.fUp.disabled = true; UI.fInj.disabled = false; Sound.play("tick", xOf(li));
      });
      UI.fList.append(li);
    });
  }
  function setCells(k, n) { const b = UI.blocks && UI.blocks[k]; if (!b) return; const lit = n == null ? 0 : Math.round(clamp(Math.log10(n + 1) / 3, 0, 1) * b.cells.length); b.cells.forEach((c, i) => c.classList.toggle("on", i < lit)); b.n.textContent = n == null ? "n/a" : String(n); }

  async function module(id, m) {
    glitch(UI.regions.mods); hot(["mods"], true); setTimeout(() => hot(["mods"], false), 900);
    if (!reduced()) Particles.at(m, 14, { speed: 90, life: 0.4 });
    Sound.play("activate", xOf(m));
    const go = (view) => { log("", "open " + view); show(view); };
    if (id === "clu") go("chat"); else if (id === "system") go("status"); else if (id === "memory") go("memory"); else if (id === "utility") go("settings");
    else if (id === "terminal") { $("coreCmd").focus(); log("", "terminal ready. Type help."); }
    else if (id === "voice") sensor("talk", m);
    else if (id === "files") { UI.fDrop.click(); }
    else if (id === "research") {
      if (state.research == null) { log("warn", "research setting not available"); return; }
      try { await api("/v1/settings", { method: "PUT", json: { key: "net.web", value: !state.research } }); set({ research: !state.research }); log("ok", "web research " + (state.research ? "on" : "off")); }
      catch (e) { log("err", "research: " + e.message); Sound.play("error"); }
    }
  }

  // E. Command strip (bottom): history ◂ | command line | ▸ keyboard, with the hold-to-execute emblem ----------------
  const history = (() => { try { return JSON.parse(localStorage.getItem("clu-core-history") || "[]"); } catch (e) { return []; } })(); let hIdx = -1;
  function buildCommand(s) {
    const g = svg("g", { id: "reg-keys" }, s);
    // left segment: history back
    const hl = svg("g", {}, g);
    svg("path", { class: "fill-deep", d: "M314 1080 H546 L560 1096 L546 1122 H330 L314 1104 Z" }, hl);
    svg("polygon", { class: "glyph", points: "378,1090 390,1108 366,1108" }, hl); svg("polygon", { class: "glyph dim", points: "508,1104 520,1086 496,1086" }, hl);
    svg("path", { class: "ln thin", d: "M334 1114 H360 M402 1100 H486" }, hl);
    control(hl, "Previous command", () => recall(1));
    // centre bar with the command line
    svg("path", { class: "fill-deep", d: "M566 1080 H1090 L1072 1124 H584 Z" }, g);
    const box = fo(g, 584, 1082, 488, 40, "");
    const bar = el("div", "cmdbar"); box.append(bar);
    const input = el("input", "cmd"); input.id = "coreCmd"; input.placeholder = "command or question (help)"; input.autocomplete = "off"; input.spellcheck = false; input.setAttribute("aria-label", "Command");
    const ind = el("span", "exec-ind"); ind.id = "coreExec"; bar.append(el("span", "prompt", "CLU›"), input, ind);
    input.addEventListener("keydown", (e) => {
      light(e.key);
      if (e.key === "Enter") { e.preventDefault(); run(input.value); }
      else if (e.key === "ArrowUp") { e.preventDefault(); recall(1); } else if (e.key === "ArrowDown") { e.preventDefault(); recall(-1); }
    });
    input.addEventListener("focus", () => setMod("terminal", true)); input.addEventListener("blur", () => setMod("terminal", false));
    // emblem: triangle of triangles in a pentagon: hold to execute (a deliberate confirmation, not a security check)
    const em = svg("g", {}, g);
    svg("polygon", { class: "fill-deep", points: "742,1076 836,986 930,1076" }, em);
    svg("polygon", { class: "ln", points: "760,1070 836,1000 912,1070" }, em);
    const tri = (x, y, s2, up) => svg("polygon", { class: "glyph", points: up ? `${x},${y - s2} ${x + s2},${y + s2 * 0.7} ${x - s2},${y + s2 * 0.7}` : `${x},${y + s2} ${x + s2},${y - s2 * 0.7} ${x - s2},${y - s2 * 0.7}` }, em);
    UI.emTris = [tri(836, 1022, 14, true), tri(816, 1052, 14, true), tri(856, 1052, 14, true), tri(836, 1050, 9, false), tri(796, 1062, 7, false), tri(876, 1062, 7, false)];
    UI.emTris.forEach((t, i) => t.setAttribute("opacity", 0.35 + (i % 3) * 0.2));
    control(em, "Hold to execute the command (a visual confirmation, not a security check)", () => {});
    let holdT = 0, holdI = 0;
    em.addEventListener("pointerdown", () => { em.classList.add("on"); Sound.play("tick", 0.42); let k = 0; holdI = setInterval(() => { UI.emTris.forEach((t, i) => t.setAttribute("opacity", i <= k % 6 ? 1 : 0.3)); k++; }, 90);
      holdT = setTimeout(() => { clearInterval(holdI); em.classList.remove("on"); run(input.value); }, 650); });
    ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => em.addEventListener(ev, () => { clearTimeout(holdT); clearInterval(holdI); em.classList.remove("on"); UI.emTris.forEach((t, i) => t.setAttribute("opacity", 0.35 + (i % 3) * 0.2)); }));
    // right segment: on-screen keyboard
    const hr = svg("g", {}, g);
    svg("path", { class: "fill-deep", d: "M1100 1096 L1116 1080 H1340 V1122 H1116 Z" }, hr);
    svg("polygon", { class: "glyph", points: "1146,1104 1158,1086 1134,1086" }, hr); svg("polygon", { class: "glyph", points: "1270,1090 1282,1108 1258,1108" }, hr);
    svg("path", { class: "ln thin", d: "M1170 1100 H1246 M1294 1114 H1326" }, hr);
    control(hr, "On-screen keyboard", () => { const k = $("coreKeyboard"); materialize(k, k.classList.contains("hidden")); });
    frame(g, "M246 1062 L312 1062", "keys", "thin");
    return g;
  }
  function recall(dir) { const input = $("coreCmd"); if (!history.length) return; hIdx = clamp(hIdx + dir, -1, history.length - 1); input.value = hIdx < 0 ? "" : history[history.length - 1 - hIdx]; input.focus(); }
  function buildKeyboard() {
    const k = el("div", "surface hidden"); k.id = "coreKeyboard"; Object.assign(k.style, pct(452, 806, 760, 262)); k.setAttribute("aria-label", "On-screen keyboard");
    const head = el("div", "head"); head.append(el("span", null, "KEYBOARD"), (() => { const b = el("button", "kbtn", "Close"); b.addEventListener("click", (e) => { press(b, e); materialize(k, false); }); return b; })()); k.append(head);
    const kbd = el("div", "kb"); k.append(kbd); UI.kbd = kbd;
    const input = () => $("coreCmd");
    const key = (label, value, cls) => {
      const b = el("button", "key" + (cls ? " " + cls : ""), label); b.type = "button"; b.dataset.k = value.toLowerCase();
      b.addEventListener("click", () => {
        Sound.play("click", xOf(b)); b.classList.add("lit"); setTimeout(() => b.classList.remove("lit"), 120);
        const i = input(); if (value === "Enter") return run(i.value); if (value === "Backspace") i.value = i.value.slice(0, -1); else if (value === "Clear") i.value = ""; else i.value += value;
      });
      return b;
    };
    ["1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"].forEach((r, i) => { const rr = el("div", "kb-row"); [...r].forEach((ch) => rr.append(key(ch, ch.toLowerCase()))); if (i === 3) rr.append(key("⌫", "Backspace", "wide")); kbd.append(rr); });
    const last = el("div", "kb-row"); last.append(key("CLR", "Clear", "wide"), key("SPACE", " ", "space"), key("ENTER", "Enter", "wide")); kbd.append(last);
    return k;
  }
  function light(k) { if (!UI.kbd) return; const b = UI.kbd.querySelector(`.key[data-k="${CSS.escape(k.length === 1 ? k.toLowerCase() : k.toLowerCase())}"]`); if (b) { b.classList.add("lit"); setTimeout(() => b.classList.remove("lit"), 120); } }
  function execInd(cls) { const i = $("coreExec"); if (i) i.className = "exec-ind" + (cls ? " " + cls : ""); }

  // D. Core control octagon (right): crosshair sensors, inner screen (console), honeycomb readouts, waveform ---------
  let waveCanvas = null, waveCtx = null, waveLabel = null, micStream = null, micAnalyser = null, playAnalyser = null, playStream = null;
  function buildHex(s) {
    const g = svg("g", { id: "reg-hex" }, s);
    svg("polygon", { class: "fillp", points: "1206,770 1298,588 1872,588 1960,770 1872,958 1746,1124 1420,1124 1296,958" }, g);
    svg("polygon", { class: "ln thin dim", points: "1226,770 1310,604 1860,604 1940,770 1860,944 1738,1108 1428,1108 1308,944" }, g);
    // honeycomb texture behind the readouts
    // inner screen (console)
    svg("polygon", { class: "fill-deep", points: "1316,786 1386,660 1600,660 1658,786 1600,918 1386,918" }, g);
    svg("path", { class: "ln thin", d: "M1330 800 L1340 820 M1336 830 L1356 870" }, g);
    T(g, 1404, 686, "SYSTEM LOG", "lbl", { "font-size": 11 });
    const cbox = fo(g, 1384, 694, 236, 214, ""); consoleEl = el("div", "console"); consoleEl.setAttribute("role", "log"); consoleEl.setAttribute("aria-live", "polite"); cbox.append(consoleEl);
    UI.consoleBox = cbox;
    // honeycomb readouts (the reference's hex cluster at the screen's right)
    const RO = [["model", "MODEL", 1700, 704], ["link", "HOST", 1766, 742], ["stt", "STT", 1700, 780], ["resp", "RESPONSE", 1766, 818], ["queue", "QUEUE", 1700, 856], ["voice", "VOICE", 1832, 780]];
    [[1832, 704], [1832, 856], [1766, 894]].forEach(([x, y]) => svg("polygon", { class: "ln thin dim", points: hexPts(x, y, 36) }, g));
    RO.forEach(([k, label, x, y]) => {
      svg("polygon", { class: "fill-deep", points: hexPts(x, y, 36) }, g);
      T(g, x, y - 6, label, "lbl", { "text-anchor": "middle", "font-size": 9 });
      const v = T(g, x, y + 12, "…", "val", { "text-anchor": "middle", "font-size": 11, textLength: 0 }); v.removeAttribute("textLength"); v.id = "ro-" + k; v.classList.add("ro");
    });
    svg("path", { class: "ln", d: "M1840 852 L1856 836 L1866 852 Z" }, g); // small right triangle (as in the reference)
    // crosshair head with two chevrons: the three touch sensors (STOP ‹ TALK › NEW)
    UI.sensors = {};
    const talk = svg("g", {}, g);
    svg("polygon", { class: "fillp", points: hexPts(1584, 598, 58) }, talk); svg("polygon", { class: "ln thin", points: hexPts(1584, 598, 44) }, talk);
    svg("circle", { class: "ln", cx: 1584, cy: 598, r: 16 }, talk); svg("path", { class: "ln", d: "M1584 538 V690 M1532 598 H1636" }, talk);
    control(talk, "Start or end a voice conversation", () => sensor("talk", talk)); UI.sensors.talk = talk;
    const stop = svg("g", {}, g);
    svg("polygon", { class: "fillp", points: "1512,566 1486,598 1512,630 1534,630 1514,598 1534,566" }, stop); svg("polygon", { class: "ln thin", points: "1500,598 1514,580 1514,616" }, stop);
    control(stop, "Stop CLU thinking or speaking", () => sensor("stop", stop)); UI.sensors.stop = stop;
    const nw = svg("g", {}, g);
    svg("polygon", { class: "fillp", points: "1656,566 1682,598 1656,630 1634,630 1654,598 1634,566" }, nw); svg("polygon", { class: "ln thin", points: "1668,598 1654,580 1654,616" }, nw);
    control(nw, "Start a new conversation", () => sensor("new", nw)); UI.sensors.new = nw;
    // left black triangle: clear the console
    const clr = svg("g", {}, g); svg("polygon", { class: "fill-deep", points: "1286,938 1316,872 1346,938" }, clr);
    control(clr, "Clear the system log", () => { consoleEl.replaceChildren(); log("", "log cleared"); });
    // waveform band and the bottom trapezoid with the mic button
    svg("path", { class: "ln thin", d: "M1556 998 L1566 1010 L1576 998 M1580 998 L1590 1010 L1600 998" }, g);
    UI.waveLabel = waveLabel = T(g, 1412, 962, "NO SIGNAL", "lbl", { "font-size": 11 }); waveLabel.id = "coreWaveLabel";
    const wbox = fo(g, 1404, 968, 352, 28, ""); waveCanvas = el("canvas", "wave"); wbox.append(waveCanvas); waveCtx = waveCanvas.getContext("2d");
    svg("polygon", { class: "fill-deep", points: "1468,1018 1702,1018 1688,1100 1482,1100" }, g);
    const mic = svg("g", {}, g); svg("circle", { class: "fillp", cx: 1600, cy: 1060, r: 30 }, mic);
    [[1586, 1046], [1600, 1046], [1586, 1060], [1600, 1060], [1614, 1060], [1600, 1074]].forEach(([x, y]) => svg("rect", { class: "glyph", x: x - 5, y: y - 5, width: 11, height: 11 }, mic));
    control(mic, ANDROID ? "Microphone level comes from the app's voice session" : "Mic monitor: show your microphone level (nothing is recorded or sent)", () => { if (ANDROID) log("", "the app's voice session owns the microphone; its level is shown when voice is on"); else toggleMic(mic); });
    UI.mic = mic;
    return g;
  }

  // Bottom-right corner: sound panel (star) and navigation (hex crosshair) --------------------------------------------
  function buildCorner(s) {
    const g = svg("g", { id: "reg-corner" }, s);
    const star = svg("g", {}, g); svg("path", { class: "fillp", d: "M1816 1028 Q1822 1060 1852 1066 Q1822 1072 1816 1104 Q1810 1072 1780 1066 Q1810 1060 1816 1028 Z" }, star);
    control(star, "Sound settings", () => { const p = $("coreAudio"); materialize(p, p.classList.contains("hidden")); syncModes(); });
    const nav = svg("g", {}, g); svg("polygon", { class: "fillp", points: hexPts(1912, 1080, 34) }, nav); svg("circle", { class: "ln", cx: 1912, cy: 1080, r: 12 }, nav);
    svg("path", { class: "ln", d: "M1912 1050 V1110 M1882 1080 H1942" }, nav);
    control(nav, "Navigation: all of CLU's views", () => { document.body.classList.toggle("core-nav"); Sound.play("activate"); });
    return g;
  }
  function buildAudioPanel() {
    const p = el("div", "surface audio-panel hidden"); p.id = "coreAudio"; Object.assign(p.style, pct(1500, 830, 440, 180));
    const head = el("div", "head"); const close = el("button", "kbtn", "Close"); close.addEventListener("click", (e) => { press(close, e); materialize(p, false); }); head.append(el("span", null, "SOUND"), close); p.append(head);
    const vol = el("input"); vol.type = "range"; vol.min = 0; vol.max = 100; vol.value = Math.round(Sound.prefs.volume * 100); vol.setAttribute("aria-label", "Master volume");
    vol.addEventListener("input", () => Sound.set("volume", vol.value / 100));
    const vl = el("label"); vl.append(document.createTextNode("VOLUME"), vol); p.append(vl);
    [["ui", "INTERFACE"], ["ambient", "AMBIENT HUM"], ["voice", "VOICE CUES"], ["mute", "MUTE"]].forEach(([k, t]) => {
      const c = el("input"); c.type = "checkbox"; c.checked = !!Sound.prefs[k]; c.dataset.k = k; c.addEventListener("change", () => { Sound.set(k, c.checked); syncModes(); Sound.play("click"); });
      const l = el("label"); l.append(c, document.createTextNode(t)); p.append(l);
    });
    return p;
  }
  function buildSourceWindow() {
    const w = el("section", "surface hidden"); w.id = "coreSources"; Object.assign(w.style, pct(1150, 96, 820, 470)); w.setAttribute("role", "dialog"); w.setAttribute("aria-label", "Sources and ideas");
    const head = el("div", "head"); const close = el("button", "kbtn", "Close"); head.append(el("span", null, "INTELLIGENCE CONTAINER"), close); w.append(head);
    const body = el("div"); w.append(body); UI.srcBody = body;
    close.addEventListener("click", (e) => { press(close, e); openSource(false); });
    addEventListener("keydown", (e) => { if (e.key === "Escape" && state.sourceOpen) openSource(false); });
    return w;
  }
  async function openSource(open) {
    const w = $("coreSources"); set({ sourceOpen: open });
    if (!open) return materialize(w, false);
    const b = UI.srcBody; b.replaceChildren(el("div", "cdim", "Loading…")); materialize(w, true); hot(["rings-source"], true);
    const [sugg, ov] = await Promise.all([api("/v1/suggestions").catch(() => null), api("/v1/memory/overview").catch(() => null)]);
    b.replaceChildren();
    const sec = (t) => { const s = el("div", "sec"); s.append(el("h4", null, t)); b.append(s); return s; };
    sec("Last answer").append(el("div", state.answer ? "cval" : "cdim", state.answer || "Nothing answered in this session yet."));
    const s = sec(`Sources (${state.sources.length})`);
    if (state.sources.length) { const ul = el("ul"); state.sources.forEach((x) => { const li = el("li"); const l = el("a", null, x.title || x.url); l.href = x.url; l.target = "_blank"; l.rel = "noopener noreferrer"; li.append(l); ul.append(li); }); s.append(ul); }
    else s.append(el("div", "cdim", "The last answer used no outside sources."));
    const g = sec("Proactive suggestions (waiting for you)");
    if (!sugg) g.append(el("div", "cdim", "Not available right now."));
    else if (!sugg.length) g.append(el("div", "cdim", "None waiting."));
    else sugg.slice(0, 12).forEach((x) => {
      const row = el("div", "sugg"); row.append(el("span", "cval", x.text)); const btns = el("span"); const ok = el("button", "kbtn", "Keep"), no = el("button", "kbtn", "Drop"); btns.append(ok, no); row.append(btns); g.append(row);
      const act = async (verb, btn, e) => { press(btn, e); try { await api(`/v1/suggestions/${x.id}/${verb}`, { method: "POST", json: {} }); row.remove(); Sound.play(verb === "approve" ? "success" : "confirm", xOf(btn)); log("ok", `suggestion ${verb === "approve" ? "kept" : "dropped"}: ${x.text.slice(0, 60)}`); refresh(); } catch (err) { Sound.play("error"); log("err", "suggestion: " + err.message); } };
      ok.addEventListener("click", (e) => act("approve", ok, e)); no.addEventListener("click", (e) => act("reject", no, e));
    });
    const d = sec("Background discoveries");
    if (ov) { d.append(el("div", "cval", `Merge suggestions waiting: ${ov.consolidation ?? 0} · questions about your preferences: ${ov.learning ?? 0} · working notes: ${ov.working ?? 0}`)); const go = el("button", "kbtn", "Open Memory"); go.addEventListener("click", () => { openSource(false); show("memory"); }); d.append(go); }
    else d.append(el("div", "cdim", "Not available right now."));
  }
  /** Honeycomb readouts hold about nine characters; the full value stays available as a tooltip. */
  function shortRo() {
    document.querySelectorAll("#reg-hex text.ro").forEach((t) => {
      const cur = [...t.childNodes].filter((n) => n.nodeType === 3).map((n) => n.data).join("");
      const full = t.dataset.full && cur === t.dataset.short ? t.dataset.full : cur; // unchanged since we shortened it, or a new value
      const short = full.length > 9 ? full.slice(0, 8) + "…" : full;
      if (cur === short && t.dataset.full === full) return;
      t.dataset.full = full; t.dataset.short = short; t.replaceChildren(document.createTextNode(short));
      if (full !== short) { const ti = svg("title", {}, t); ti.textContent = full; }
    });
  }
  function setMod(id, onNow) { const m = UI.mods && UI.mods[id]; if (m) m.classList.toggle("on", !!onNow); }

  async function sensor(id, b) {
    if (id === "talk") { const vb = $("voiceBtn"); if (vb && !vb.disabled) { vb.click(); Sound.play("listen", xOf(b)); log("ok", "voice: " + (state.voice ? "ending" : "starting")); } else log("warn", "voice is not available here"); }
    else if (id === "stop") { try { await api("/v1/stop", { method: "POST", json: {} }); if (state.voice) { const vs = $("voiceStop"); if (vs && !vs.disabled) vs.click(); } log("ok", "stop requested"); Sound.play("confirm", xOf(b)); } catch (e) { log("err", "stop: " + e.message); Sound.play("error"); } }
    else if (id === "new") { const nb = $("newConversation") || document.querySelector('[data-action="new-conversation"]'); if (nb) { nb.click(); log("ok", "new conversation"); Sound.play("confirm", xOf(b)); } else log("warn", "new conversation: not available in this window"); }
  }
  async function toggleMic(btn) {
    if (micStream) { micStream.getTracks().forEach((t) => t.stop()); micStream = null; micAnalyser = null; btn.classList.remove("on"); log("", "mic monitor off"); return; }
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      const c = new (window.AudioContext || window.webkitAudioContext)(); micAnalyser = c.createAnalyser(); micAnalyser.fftSize = 1024; c.createMediaStreamSource(micStream).connect(micAnalyser);
      btn.classList.add("on"); Sound.play("record", xOf(btn)); log("ok", "mic monitor on (level only; nothing is recorded)");
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
      waveLabel.textContent = source + " · LIVE"; waveLabel.setAttribute("fill", "#7fe9ff");
    } else if (ANDROID && typeof window.CluNativeLevel === "number" && state.voice) { // the app's native voice session reports its level
      const lv = clamp(window.CluNativeLevel, 0, 1), mic = window.CluNativeMic === true;
      g.strokeStyle = "#00e5ff"; g.beginPath(); for (let x = 0; x <= w; x += 3) { const y = h / 2 + Math.sin(x / 7 + now() / 90) * lv * h * 0.45; x ? g.lineTo(x, y) : g.moveTo(x, y); } g.stroke();
      waveLabel.textContent = (lv > 0.02 ? "CLU VOICE" : mic ? "LISTENING" : "VOICE") + " · LEVEL " + Math.round(lv * 100); waveLabel.setAttribute("fill", "#7fe9ff");
    } else {
      g.strokeStyle = "rgba(66,97,107,0.8)"; g.beginPath(); g.moveTo(0, h / 2); g.lineTo(w, h / 2); g.stroke();
      waveLabel.textContent = "NO SIGNAL"; waveLabel.removeAttribute("fill");
    }
  }

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
      set({ model: m, host: st.host || "pc", hostLink: st.link }); setCells("conv", st.conversations);
      $("ro-model").textContent = m.name ? `${m.name} · ${String(m.status).toLowerCase()}` : m.status === "NO_MODEL" ? "none registered" : String(m.status || "?").toLowerCase();
      $("ro-link").textContent = st.host === "device" ? (st.link === "offline" ? "device (PC offline)" : "this device") : ANDROID ? "your PC" : "this PC";
    } else { setArc("proc", null, "n/a"); setArc("sys", null, "n/a"); setArc("tasks", null, "n/a"); }
    if (inf && (inf.running !== undefined || inf.queued !== undefined)) {
      const run = inf.running ? 1 : 0, q = Array.isArray(inf.queued) ? inf.queued.length : (inf.queued || 0), max = (inf.limits && inf.limits.maxQueued) || 4;
      setArc("infer", (run + q) / (max + 1), `${run} running · ${q} queued`); $("ro-queue").textContent = `${run} / ${q}`;
    } else if (st && st.model && st.model.running !== undefined) { const q = st.model.queued || 0; setArc("infer", ((st.model.running ? 1 : 0) + q) / 5, `${st.model.running ? 1 : 0} running · ${q} queued`); $("ro-queue").textContent = `${st.model.running ? 1 : 0} / ${q}`; }
    else { setArc("infer", null, "n/a"); $("ro-queue").textContent = "n/a"; }
    if (ov && ov.kinds) { const n = Object.values(ov.kinds).reduce((a, k) => a + (k.count || 0), 0); setArc("records", Math.log10(n + 1) / 4, n.toLocaleString()); setCells("mem", n); } else { setArc("records", null, "n/a"); setCells("mem", null); }
    if (Array.isArray(sugg)) { setArc("sugg", Math.min(1, sugg.length / 10), String(sugg.length)); set({ suggestions: sugg.length }); } else setArc("sugg", null, "n/a");
    if (settings && settings.settings) { const s = settings.settings.find((x) => x.key === "net.web"); if (s) set({ research: s.value === true || s.value === "true" }); }
    shortRo();
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
  const PCT = { input: 25, analysis: 50, synthesis: 75, output: 100 };
  function render(s, patch) {
    if (!built) return;
    if ("stage" in patch || "stageNote" in patch || "stagesDone" in patch) {
      root.dataset.stage = s.stage;
      STAGES.forEach((st) => { const r = UI.stageRows[st]; r.classList.toggle("active", st === s.stage); r.classList.toggle("done", s.stagesDone.includes(st) && st !== s.stage); });
      UI.stageNote.textContent = (s.stage === "idle" ? "IDLE · " : s.stage.toUpperCase() + " · ") + (s.stageNote || "").slice(0, 70);
      UI.stageNote.setAttribute("fill", s.stage === "error" ? "#ff6b7a" : s.stage === "idle" ? "#5d7c89" : "#8fd3f0");
      const p = s.stage === "idle" ? (s.answer ? 100 : 0) : (PCT[s.stage] ?? 0);
      UI.sprog.setAttribute("stroke-dasharray", `${(p / 100 * 703.7).toFixed(1)} 999`); UI.spct.textContent = s.stage === "idle" && !s.answer ? "—" : p + "%";
      const lit = Math.round(p / 100 * stripes.length); stripes.forEach((x, i) => x.classList.toggle("on", s.stage !== "idle" && i < lit));
      UI.warn.setAttribute("stroke", s.stage === "error" ? "#ff6b7a" : "#8fd3f0");
      $("ro-resp").textContent = s.stage === "idle" ? "ready" : s.stage;
      const busy = !["idle", "error"].includes(s.stage);
      hot(["arc-rings", "rings-source", "pipe-hex", "keys"], busy);
      if (busy !== s.processing) { set({ processing: busy }); Sound.ambient(busy); }
    }
    if ("answer" in patch || "sources" in patch || "suggestions" in patch) {
      UI.preview.textContent = s.answer ? s.answer.slice(0, 140) : "No answer yet.";
      UI.scount.textContent = `SRC ${s.sources.length} · SUG ${s.suggestions}`;
      const n = Math.min(UI.ticks.length, Math.round((s.answer || "").length / 12)); UI.ticks.forEach((t, i) => t.classList.toggle("on", i < n));
    }
    if ("voice" in patch || "presence" in patch) {
      UI.sensors.talk.classList.toggle("on", s.voice); setMod("voice", s.voice); UI.lamps.voice.classList.toggle("on", s.voice);
      $("ro-voice").textContent = s.voice ? "on" : "off";
      const vs = $("voiceState"); $("ro-stt").textContent = s.voice ? (vs && vs.textContent ? vs.textContent.toLowerCase().slice(0, 10) : "listening") : "idle";
    }
    if ("research" in patch) { setMod("research", s.research === true); UI.lamps.research.classList.toggle("on", s.research === true); }
    if ("model" in patch && s.model) UI.lamps.model.classList.toggle("on", s.model.status === "READY" || s.model.status === "GENERATING");
    if ("host" in patch) { UI.lamps.host.classList.toggle("on", s.host != null); UI.lamps.host.classList.toggle("warn", s.hostLink === "offline"); }
    UI.lamps.net.classList.toggle("on", navigator.onLine !== false);
    shortRo();
  }
  on(render);

  // ------------------------------------------------------------------------------------------ public API
  let timers = [], stageIdle = 0;
  window.CluCore = {
    /** Called once by shell.js after the session is established. */
    init(apiFn, showFn) {
      api = apiFn; show = showFn; root = $("view-core"); if (!root) return;
      let red = false; try { red = localStorage.getItem("clu-core-reduced") === "1"; } catch (e) { /* private */ }
      build(); root.classList.toggle("reduced", !!reduced() || red); syncModes();
      render(state, { stage: 1, answer: 1, voice: 1, research: 1, model: 1, host: 1 }); // every readout starts from the real state
      log("ok", `CLU core online · ${ANDROID ? "Android" : "PC"} interface`);
      if (ANDROID) api("/v1/shell/platform").then((p) => set({ platform: p, link: p.link })).catch(() => {});
      document.addEventListener("visibilitychange", () => { if (!document.hidden) startLoops(); });
      addEventListener("online", () => render(state, {})); addEventListener("offline", () => render(state, {}));
    },
    /** The Core became the visible view (shell.js navigation): the window's rail and title bar step aside. */
    open() {
      if (!built) return; visible = true; document.body.classList.add("core-mode"); document.body.classList.remove("core-nav");
      if (!root.dataset.opened) {
        root.dataset.opened = "1"; const order = ["frame", "arc", "rings", "source", "pipe", "mods", "files", "keys", "hex", "corner"];
        order.forEach((k, i) => regionIn(UI.regions[k], i * 70)); setTimeout(() => { Particles.at(UI.regions.rings, 40, { speed: 120 }); Sound.play("success", 0.5); }, 760);
      }
      refresh(); loadDocs(); pollRequests(); startLoops();
      timers.forEach(clearInterval);
      timers = [setInterval(() => { if (visible && !document.hidden) refresh(); }, 5000), setInterval(() => { if (visible && !document.hidden) pollRequests(); }, 4000),
        setInterval(() => { if (visible && !document.hidden) scanArc(); }, 2400), setInterval(() => { if (visible && !document.hidden) drawWave(); }, 60)];
    },
    close() { visible = false; document.body.classList.remove("core-mode", "core-nav"); timers.forEach(clearInterval); timers = []; Sound.ambient(false); },
    /** A turn's real progress, from shell.js's stream: input → analysis → synthesis → output (or error), then idle. */
    turn(stage, data) {
      clearTimeout(stageIdle);
      if (stage === "input") { set({ stage: "input", stagesDone: [], stageAt: now(), stageNote: "› " + (data.text || "").slice(0, 120), answer: "", sources: [] }); log("you", "› " + (data.text || "")); Sound.play("processing", 0.45); glitch(UI.regions && UI.regions.rings); }
      else if (stage === "analysis") { if (state.stage === "input") set({ stagesDone: ["input"] }); set({ stage: "analysis", stageNote: data.text || "Analysing…" }); if (data.text) log("", data.text); }
      else if (stage === "synthesis") { if (state.stage !== "synthesis") { set({ stagesDone: ["input", "analysis"], stage: "synthesis" }); Sound.play("tick", 0.45); } set({ answer: state.answer + (data.delta || ""), stageNote: (state.answer + (data.delta || "")).slice(-70) }); }
      else if (stage === "output") {
        const ms = Math.round(now() - state.stageAt);
        set({ stagesDone: ["input", "analysis", "synthesis"], stage: "output", answer: data.text || state.answer, sources: data.sources || [], stageNote: data.error ? "Finished with a problem: " + data.error : data.interrupted ? "Stopped." : `Complete · ${ms} ms · ${(data.sources || []).length} sources` });
        log(data.error ? "err" : "ok", data.error ? "error: " + data.error : data.interrupted ? "stopped" : `answer complete (${ms} ms)`);
        Sound.play(data.error ? "error" : "ready", 0.45);
        if (!data.error && built) Particles.at(UI.regions.rings, 24, { speed: 110, life: 0.5 });
      }
      else if (stage === "error") { set({ stage: "error", stageNote: data.text || "Error" }); log("err", data.text || "error"); Sound.play("error", 0.45); glitch(UI.regions && UI.regions.rings); }
      else if (stage === "suggestion") { log("ok", "new suggestion: " + (data.text || "").slice(0, 80)); set({ suggestions: state.suggestions + 1 }); }
      else if (stage === "action") log("", "capability action: " + (data.type || data.kind || "update"));
      if (stage === "idle" || stage === "output") stageIdle = setTimeout(() => set({ stage: "idle", stagesDone: state.stage === "error" ? [] : STAGES.slice() }), stage === "idle" ? 1400 : 2600);
    },
    /** CLU's presence (voice states included), from shell.js. */
    presence(st) {
      const voice = !!(window.CluVoice && CluVoice.active && CluVoice.active());
      if (voice !== state.voice) { Sound.play(voice ? "listen" : "dissolve", 0.8); log("", voice ? "voice session on" : "voice session off"); }
      set({ voice, presence: st });
    },
    log, sound: Sound, particles: Particles, state: () => Object.assign({}, state),
  };
})();
