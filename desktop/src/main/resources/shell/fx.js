/*
 * CLU's interface effects on the PC (1.12 redesign, shared with the phone): the start-up sequence, the motion
 * vocabulary (panels opening out of a line, content rising in), synthesised interface sounds, and the Markdown
 * renderer for CLU's replies. No libraries and no recordings: sounds are made with the Web Audio API, Markdown is built
 * as DOM nodes (never innerHTML), links are http(s) only.
 *
 * Settings → General: "Interface sounds" (ui.sounds) and "Interface motion" (ui.motion). Sounds stay silent while a
 * voice conversation is open, so they never reach the microphone or talk over CLU. The system's reduced-motion
 * preference is honoured like "Reduced".
 */
(function () {
  "use strict";
  const prefs = { sounds: true, motion: "full" };
  const sysReduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const reduced = () => sysReduced || prefs.motion === "reduced";

  // ------------------------------------------------------------------------------------ sound
  let ac = null, lastClick = 0;
  const played = [];                    // what actually started (tests and diagnostics; bounded)
  function ctx() {
    if (!ac) { try { ac = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { ac = null; } }
    if (ac && ac.state === "suspended") ac.resume().catch(() => {});
    return ac;
  }
  /** A voice conversation is open (listening, thinking or speaking): interface sounds stay silent. */
  function quiet() { const v = document.getElementById("voiceBar"); return !!(v && !v.classList.contains("hidden")); }

  /** One sine voice: an exponential sweep f0→f1 over [sweep] s, linear attack [a] s, exponential decay τ [d] s. */
  function tone(c, at, { f0, f1 = f0, sweep = 0, a = 0.004, d = 0.05, gain = 0.1, dur = 0.2, harm = 0 }) {
    const o = c.createOscillator(), g = c.createGain();
    o.type = "sine"; o.frequency.setValueAtTime(f0, at);
    if (sweep > 0 && f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, at + sweep);
    g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(gain, at + a); g.gain.setTargetAtTime(0, at + a, d);
    g.gain.setValueAtTime(g.gain.value, at + dur); g.gain.linearRampToValueAtTime(0, at + dur + 0.003);
    o.connect(g).connect(c.destination); o.start(at); o.stop(at + dur + 0.01);
    if (harm > 0) tone(c, at, { f0: f0 * 2, f1: f1 * 2, sweep, a, d, gain: gain * harm, dur });
  }
  const SOUNDS = {
    click: (c, t) => { tone(c, t, { f0: 2300, a: 0.002, d: 0.007, gain: 0.10, dur: 0.03 }); tone(c, t, { f0: 4600, a: 0.001, d: 0.003, gain: 0.03, dur: 0.02 }); },
    tick: (c, t) => tone(c, t, { f0: 3200, a: 0.001, d: 0.003, gain: 0.05, dur: 0.014 }),
    activate: (c, t) => tone(c, t, { f0: 520, f1: 1320, sweep: 0.2, a: 0.012, d: 0.07, gain: 0.16, dur: 0.24, harm: 0.24 }),
    listen: (c, t) => tone(c, t, { f0: 480, f1: 920, sweep: 0.11, a: 0.008, d: 0.035, gain: 0.15, dur: 0.13 }),
    process: (c, t) => { tone(c, t, { f0: 1040, a: 0.003, d: 0.012, gain: 0.11, dur: 0.045 }); tone(c, t + 0.085, { f0: 1040, a: 0.003, d: 0.014, gain: 0.11, dur: 0.06 }); },
    notify: (c, t) => { tone(c, t, { f0: 1318.5, a: 0.004, d: 0.06, gain: 0.13, dur: 0.14 }); tone(c, t + 0.10, { f0: 1760, a: 0.004, d: 0.08, gain: 0.13, dur: 0.24 }); },
    shutdown: (c, t) => { tone(c, t, { f0: 880, f1: 190, sweep: 0.4, a: 0.01, d: 0.16, gain: 0.14, dur: 0.42 }); tone(c, t, { f0: 110, a: 0.01, d: 0.16, gain: 0.04, dur: 0.42 }); },
    startup: (c, t) => {
      [[110, 0.07], [165, 0.04], [220, 0.02]].forEach(([f, g]) => tone(c, t, { f0: f, a: 0.45, d: 0.35, gain: g, dur: 0.95 }));
      [[0.28, 880], [0.42, 1174.7], [0.56, 1760]].forEach(([at, f]) => tone(c, t + at, { f0: f, a: 0.004, d: 0.09, gain: 0.1, dur: 0.35 }));
    },
  };
  /** Plays a sound if sounds are on and no voice conversation is open. Returns whether it was started. */
  function sound(name) {
    if (!prefs.sounds || quiet() || !SOUNDS[name]) return false;
    if (name === "click" || name === "tick") { const n = performance.now(); if (n - lastClick < 45) return false; lastClick = n; }
    played.push(name); if (played.length > 64) played.shift();
    const c = ctx(); if (!c) return true;
    try { SOUNDS[name](c, c.currentTime + 0.01); } catch (e) { /* audio unavailable: silent */ }
    return true;
  }

  // ------------------------------------------------------------------------------------ motion
  /** A panel opens out of a line (width, then height). */
  function expand(node, delay = 0) { if (!node || reduced()) return; node.style.animationDelay = delay + "ms"; node.classList.remove("fx-expand"); void node.offsetWidth; node.classList.add("fx-expand"); }
  /** Content rises in, children one after another. */
  function stagger(node, start = 0, step = 40, max = 14) {
    if (!node || reduced()) return;
    [...node.children].forEach((ch, i) => { ch.style.animationDelay = (start + step * Math.min(i, max)) + "ms"; ch.classList.remove("fx-rise"); void ch.offsetWidth; ch.classList.add("fx-rise"); });
  }
  function rise(node, delay = 0) { if (!node || reduced()) return; node.style.animationDelay = delay + "ms"; node.classList.remove("fx-rise"); void node.offsetWidth; node.classList.add("fx-rise"); }

  // ------------------------------------------------------------------------------------ boot
  /**
   * The start-up sequence, once per window: CLU's real state line by line, then the name (filled, outlined, a short
   * derez), then the interface opens out. About 1.5 s; never takes clicks; any key or click ends it.
   */
  function boot(lines, onDone) {
    const done = () => { if (onDone) { const f = onDone; onDone = null; f(); } };
    if (reduced() || sessionStorage.getItem("clu-booted")) { done(); return; }
    sessionStorage.setItem("clu-booted", "1");
    const o = document.createElement("div"); o.id = "boot"; o.setAttribute("aria-hidden", "true");
    const log = document.createElement("pre"); log.className = "boot-log";
    const title = document.createElement("div"); title.className = "boot-title"; title.textContent = "CLU"; title.dataset.text = "CLU";
    o.append(log, title); document.body.append(o);
    const timers = [];
    const end = () => { timers.forEach(clearTimeout); o.classList.add("boot-out"); setTimeout(() => o.remove(), 320); done(); removeEventListener("pointerdown", end, true); removeEventListener("keydown", end, true); };
    addEventListener("pointerdown", end, true); addEventListener("keydown", end, true);
    lines.forEach((l, i) => timers.push(setTimeout(() => { log.textContent += l + "\n"; if (i % 2 === 0) sound("tick"); }, 40 + i * 85)));
    const t0 = 40 + lines.length * 85 + 60;
    timers.push(setTimeout(() => { title.classList.add("on"); sound("startup"); }, t0));
    timers.push(setTimeout(() => title.classList.add("filled"), t0 + 180));
    timers.push(setTimeout(() => { title.classList.remove("filled"); title.classList.add("framed"); }, t0 + 290));
    timers.push(setTimeout(() => title.classList.add("derez"), t0 + 400));
    timers.push(setTimeout(() => { title.classList.remove("derez"); o.classList.add("scan"); }, t0 + 620));
    timers.push(setTimeout(end, t0 + 900));
  }

  // ------------------------------------------------------------------------------------ markdown
  const HEADING = /^(#{1,4})\s+(.*)$/, BULLET = /^(\s*)([-*+•])\s+(.*)$/, NUMBERED = /^(\s*)(\d{1,3})[.)]\s+(.*)$/, RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
  /** Blocks of a reply (same rules as the phone's Markdown.kt). An unclosed fence is code up to the end (streaming). */
  function parse(src) {
    const out = [], lines = String(src).replace(/\r\n/g, "\n").split("\n"); let para = [];
    const flush = () => { const t = para.join("\n").trim(); if (t) out.push({ t: "p", text: t }); para = []; };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i], trimmed = line.trimStart(); let m;
      if (trimmed.startsWith("```")) {
        flush(); const lang = trimmed.slice(3).trim(), code = []; let closed = false; i++;
        for (; i < lines.length; i++) { if (lines[i].trimStart().startsWith("```")) { closed = true; break; } code.push(lines[i]); }
        out.push({ t: "code", lang, code: code.join("\n"), closed }); continue;
      }
      if ((m = HEADING.exec(line))) { flush(); out.push({ t: "h", level: m[1].length, text: m[2].trim() }); continue; }
      if (RULE.test(line)) { flush(); out.push({ t: "hr" }); continue; }
      if ((m = BULLET.exec(line))) { flush(); out.push({ t: "li", marker: "•", text: m[3], depth: Math.floor(m[1].length / 2) }); continue; }
      if ((m = NUMBERED.exec(line))) { flush(); out.push({ t: "li", marker: m[2] + ".", text: m[3], depth: Math.floor(m[1].length / 2) }); continue; }
      if (line.startsWith(">")) { flush(); out.push({ t: "quote", text: line.slice(1).trim() }); continue; }
      if (!line.trim()) flush(); else para.push(line);
    }
    flush(); return out;
  }
  /** Inline spans as DOM: **bold**, *italic*, `code`, [label](https://…). Unmatched markers stay literal. */
  function inline(s, into) {
    let buf = "", i = 0;
    const text = () => { if (buf) { into.append(document.createTextNode(buf)); buf = ""; } };
    const node = (tag, t, cls) => { const e = document.createElement(tag); if (cls) e.className = cls; e.textContent = t; return e; };
    while (i < s.length) {
      const c = s[i];
      if (c === "`") { const e = s.indexOf("`", i + 1); if (e > i + 1) { text(); into.append(node("code", s.slice(i + 1, e))); i = e + 1; continue; } }
      if (s.startsWith("**", i) || s.startsWith("__", i)) { const mk = s.slice(i, i + 2), e = s.indexOf(mk, i + 2); if (e > i + 2) { text(); into.append(node("strong", s.slice(i + 2, e))); i = e + 2; continue; } buf += mk; i += 2; continue; }
      if ((c === "*" || c === "_") && i + 1 < s.length && !/\s/.test(s[i + 1]) && (i === 0 || !/[A-Za-z0-9]/.test(s[i - 1]))) {
        const e = s.indexOf(c, i + 1);
        if (e > i + 1 && !/\s/.test(s[e - 1]) && (e + 1 >= s.length || !/[A-Za-z0-9]/.test(s[e + 1]))) { text(); into.append(node("em", s.slice(i + 1, e))); i = e + 1; continue; }
      }
      if (c === "[") {
        const close = s.indexOf("](", i + 1), end = close > 0 ? s.indexOf(")", close + 2) : -1, url = end > 0 ? s.slice(close + 2, end) : "";
        if (close > i && end > close && /^https?:\/\//.test(url)) { text(); const a = node("a", s.slice(i + 1, close)); a.href = url; a.target = "_blank"; a.rel = "noopener noreferrer"; into.append(a); i = end + 1; continue; }
      }
      buf += c; i++;
    }
    text();
  }
  /** Renders [text] into [body] (replacing its content). [caret] adds the streaming caret. */
  function md(body, text, caret) {
    body.replaceChildren(); body.classList.add("md");
    for (const b of parse(text)) {
      if (b.t === "p") { const p = document.createElement("p"); inline(b.text, p); body.append(p); }
      else if (b.t === "h") { const h = document.createElement("h" + Math.min(6, b.level + 2)); inline(b.text, h); body.append(h); }
      else if (b.t === "li") { const p = document.createElement("div"); p.className = "md-li"; p.style.paddingLeft = (16 + 16 * b.depth) + "px";
        const m = document.createElement("span"); m.className = "md-mark"; m.textContent = b.marker; p.append(m); inline(b.text, p); body.append(p); }
      else if (b.t === "quote") { const q = document.createElement("blockquote"); inline(b.text, q); body.append(q); }
      else if (b.t === "hr") body.append(document.createElement("hr"));
      else if (b.t === "code") body.append(codePanel(b.lang, b.code));
    }
    if (caret) { const c = document.createElement("span"); c.className = "caret"; c.setAttribute("aria-hidden", "true"); (body.lastElementChild && body.lastElementChild.tagName === "P" ? body.lastElementChild : body).append(c); }
  }
  function codePanel(lang, code) {
    const box = document.createElement("div"); box.className = "codeblock";
    const head = document.createElement("div"); head.className = "code-head";
    const l = document.createElement("span"); l.textContent = lang || "code";
    const copy = document.createElement("button"); copy.type = "button"; copy.className = "small code-copy"; copy.textContent = "Copy"; copy.title = "Copy code";
    copy.addEventListener("click", () => { (navigator.clipboard ? navigator.clipboard.writeText(code) : Promise.reject()).then(() => { copy.textContent = "Copied"; setTimeout(() => (copy.textContent = "Copy"), 1200); }, () => { copy.textContent = "Select and copy"; }); });
    head.append(l, copy);
    const pre = document.createElement("pre"); const c = document.createElement("code"); c.textContent = code; pre.append(c);
    box.append(head, pre); return box;
  }

  // ------------------------------------------------------------------------------------ wiring
  /** Interface sounds for every control: clicks, and the "activate" sweep for section changes. */
  document.addEventListener("click", (e) => {
    const b = e.target.closest && e.target.closest("button, .nav, summary, .list li, .cards li.clickable");
    if (!b || b.disabled) return;
    if (b.id === "voiceBtn") { sound("listen"); return; }
    if (b.id === "send") return;                       // the send sound is "process", played by the chat
    sound(b.classList.contains("nav") ? "activate" : "click");
  }, true);

  function setPrefs(s) {
    if (s && s.settings) s.settings.forEach((d) => { if (d.key === "ui.sounds") prefs.sounds = d.value !== false; if (d.key === "ui.motion") prefs.motion = d.value || "full"; });
    document.body.classList.toggle("fx-reduced", reduced());
  }
  function setPref(key, value) { if (key === "ui.sounds") prefs.sounds = value !== false; if (key === "ui.motion") prefs.motion = value || "full"; document.body.classList.toggle("fx-reduced", reduced()); }

  window.CluFx = { sound, expand, stagger, rise, boot, md, parse, setPrefs, setPref, reduced, prefs, played };
})();
