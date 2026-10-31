/*
 * CLU's presence: a hexagonal ring of points that breathes when idle, turns while thinking and pulses with the words
 * while speaking. Deliberately light (2D canvas, a few hundred points): it draws at most a few frames a second when
 * idle, nothing at all while the window is hidden, and a still frame when the system asks for reduced motion.
 */
(function () {
  "use strict";
  const reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const faces = [];
  let state = "idle", energy = 0, last = 0, raf = 0;

  function colour() { return getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#3ed6e6"; }

  function draw(canvas, t) {
    const ctx = canvas.getContext("2d"), w = canvas.width, h = canvas.height, cx = w / 2, cy = h / 2;
    const r = Math.min(w, h) * 0.36;
    ctx.clearRect(0, 0, w, h);
    const c = colour();
    const spin = state === "thinking" ? t / 900 : state === "speaking" ? t / 2400 : t / 9000;
    const breathe = 1 + 0.03 * Math.sin(t / 1400) + (state === "speaking" ? 0.10 * energy : 0);
    ctx.fillStyle = c;
    const n = 6, per = 14;
    for (let s = 0; s < n; s++) {
      const a1 = spin + (Math.PI * 2 * s) / n - Math.PI / 2, a2 = spin + (Math.PI * 2 * (s + 1)) / n - Math.PI / 2;
      const x1 = cx + Math.cos(a1) * r * breathe, y1 = cy + Math.sin(a1) * r * breathe;
      const x2 = cx + Math.cos(a2) * r * breathe, y2 = cy + Math.sin(a2) * r * breathe;
      for (let i = 0; i < per; i++) {
        const f = i / per, wobble = state === "thinking" ? Math.sin(t / 160 + s * 3 + i) * 1.6 : 0;
        const x = x1 + (x2 - x1) * f, y = y1 + (y2 - y1) * f + wobble;
        const size = 1.2 + (state === "speaking" ? energy * 1.8 * Math.abs(Math.sin(i + t / 120)) : 0.4);
        ctx.globalAlpha = 0.45 + 0.55 * Math.abs(Math.sin(f * Math.PI));
        ctx.beginPath(); ctx.arc(x, y, size, 0, Math.PI * 2); ctx.fill();
      }
    }
    ctx.globalAlpha = state === "idle" ? 0.55 : 0.9;
    ctx.beginPath(); ctx.arc(cx, cy, r * 0.16 * (1 + (state === "speaking" ? energy * 0.6 : 0.05 * Math.sin(t / 700))), 0, Math.PI * 2); ctx.fill();
    ctx.globalAlpha = 1;
  }

  function frame(t) {
    raf = 0;
    if (document.hidden) return;                       // nothing drawn while hidden; resumes on visibilitychange
    const interval = reduced ? Infinity : state === "idle" ? 250 : 33; // ~4 fps idle, ~30 fps when active
    if (t - last >= interval || last === 0) { last = t; faces.forEach((c) => draw(c, t)); energy *= 0.86; }
    if (!reduced) raf = requestAnimationFrame(frame);
  }

  function kick() { if (!raf) raf = requestAnimationFrame(frame); }
  document.addEventListener("visibilitychange", () => { last = 0; kick(); });

  window.CluFace = {
    attach(canvas) { if (canvas && !faces.includes(canvas)) faces.push(canvas); last = 0; kick(); },
    set(next) { if (next !== state) { state = next; last = 0; kick(); } },
    /** Called as reply text arrives: a burst of energy for the speaking pulse. */
    pulse() { energy = Math.min(1, energy + 0.35); kick(); },
    get state() { return state; },
  };
})();
