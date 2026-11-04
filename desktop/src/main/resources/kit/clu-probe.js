/*
 * CLU requirement probe: while CLU verifies an application it developed (`?selftest=1`), this runs the application's
 * requirement checks (checks.json, written by CLU from the requirements, not by the application) against the running
 * page: it clicks, types and drags like the owner would and checks what appears. Results go to the kit's self-check.
 *
 * Steps: {wait: sel, ms?}, {click: sel}, {type: [sel, text]}, {key: [sel, key]}, {expect: sel}, {expectNot: sel},
 * {expectText: [sel, regex]}, {canvasNotBlank: sel}, {canvasDrag: sel}, {pause: ms}
 */
(function () {
  "use strict";
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  async function waitFor(sel, ms) { for (let t = 0; t < (ms || 6000); t += 100) { const e = document.querySelector(sel); if (e) return e; await sleep(100); } return null; }
  function pixels(c) { try { const x = c.getContext("2d"); return x.getImageData(0, 0, c.width, c.height).data; } catch (e) { return null; } }
  function spread(d) { if (!d) return 0; let n = 0, s = 0, s2 = 0; for (let i = 0; i < d.length; i += 4 * 37) { const l = d[i] * 0.3 + d[i + 1] * 0.59 + d[i + 2] * 0.11; n++; s += l; s2 += l * l; } const m = s / n; return Math.sqrt(Math.max(0, s2 / n - m * m)); }
  /** Share of the drawn (non-background) pixels that changed: a dark 3D scene is mostly background, so whole-canvas shares mislead. */
  function diff(a, b) { if (!a || !b || a.length !== b.length) return 1;
    const bg = [a[0], a[1], a[2]]; let c = 0, lit = 0;
    for (let i = 0; i < a.length; i += 4 * 7) {
      const litA = Math.abs(a[i] - bg[0]) + Math.abs(a[i + 1] - bg[1]) + Math.abs(a[i + 2] - bg[2]) > 40, litB = Math.abs(b[i] - bg[0]) + Math.abs(b[i + 1] - bg[1]) + Math.abs(b[i + 2] - bg[2]) > 40;
      if (litA || litB) { lit++; if (Math.abs(a[i] - b[i]) + Math.abs(a[i + 1] - b[i + 1]) + Math.abs(a[i + 2] - b[i + 2]) > 30) c++; } }
    return lit ? c / lit : 0; }

  async function step(s) {
    if (s.pause) { await sleep(s.pause); return [true, ""]; }
    if (s.wait) { const e = await waitFor(s.wait, s.ms); return [!!e, e ? "" : `nothing matched ${s.wait}`]; }
    if (s.click) { const e = await waitFor(s.click, s.ms || 4000); if (!e) return [false, `nothing to click: ${s.click}`];
      e.scrollIntoView && e.scrollIntoView(); e.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true })); await sleep(s.after || 400); return [true, ""]; }
    if (s.type) { const e = await waitFor(s.type[0]); if (!e) return [false, `no input ${s.type[0]}`]; e.focus(); e.value = s.type[1];
      e.dispatchEvent(new Event("input", { bubbles: true })); e.dispatchEvent(new Event("change", { bubbles: true })); await sleep(200); return [true, ""]; }
    if (s.key) { const e = await waitFor(s.key[0]); if (!e) return [false, `no element ${s.key[0]}`];
      for (const t of ["keydown", "keypress", "keyup"]) e.dispatchEvent(new KeyboardEvent(t, { key: s.key[1], code: s.key[1], bubbles: true, cancelable: true }));
      const form = e.form; if (s.key[1] === "Enter" && form) form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      await sleep(s.after || 600); return [true, ""]; }
    if (s.changes) { const e = await waitFor(s.changes[0]); if (!e) return [false, `nothing matched ${s.changes[0]}`]; const before = e.textContent;
      await sleep(parseInt(s.changes[1], 10) || 1500); const after = document.querySelector(s.changes[0]); const now = after ? after.textContent : "";
      return [now !== before, `"${before.trim().slice(0, 40)}" → "${now.trim().slice(0, 40)}"`]; }
    if (s.expect) { const e = await waitFor(s.expect, s.ms || 5000); return [!!e, e ? "" : `expected ${s.expect}`]; }
    if (s.expectNot) { await sleep(300); const e = document.querySelector(s.expectNot); return [!e, e ? `unexpected ${s.expectNot}` : ""]; }
    if (s.expectText) { const re = new RegExp(s.expectText[1], "i"); for (let t = 0; t < (s.ms || 5000); t += 100) { const e = document.querySelector(s.expectText[0]);
        if (e && re.test(e.textContent)) return [true, e.textContent.trim().slice(0, 120)]; await sleep(100); }
      const e = document.querySelector(s.expectText[0]); return [false, e ? `"${e.textContent.trim().slice(0, 120)}" does not match /${s.expectText[1]}/` : `nothing matched ${s.expectText[0]}`]; }
    if (s.canvasNotBlank) { const c = await waitFor(s.canvasNotBlank); if (!c || c.tagName !== "CANVAS") return [false, `no canvas ${s.canvasNotBlank}`];
      await sleep(300); const sp = spread(pixels(c)); return [sp > 2, `brightness spread ${sp.toFixed(1)}`]; }
    if (s.canvasDrag) { const c = await waitFor(s.canvasDrag); if (!c || c.tagName !== "CANVAS") return [false, `no canvas ${s.canvasDrag}`];
      const before = pixels(c); const r = c.getBoundingClientRect(); const ev = (t, x) => c.dispatchEvent(new PointerEvent(t, { clientX: r.left + x, clientY: r.top + r.height / 2, bubbles: true, pointerId: 7 }));
      ev("pointerdown", r.width / 2); for (let i = 1; i <= 6; i++) { ev("pointermove", r.width / 2 + i * 20); await sleep(30); } ev("pointerup", r.width / 2 + 120);
      await sleep(400); const d = diff(before, pixels(c)); return [d > 0.15, `${(d * 100).toFixed(1)}% of the drawn pixels changed after dragging`]; }
    return [false, "unknown step " + JSON.stringify(s)];
  }

  async function run(kit) {
    let checks = [];
    try { const r = await fetch("checks.json"); if (r.ok) checks = await r.json(); } catch (e) { kit.check("requirement checks available", false, e.message); return; }
    for (const c of checks) {
      let ok = true, detail = "";
      for (const s of c.steps) { const [good, d] = await step(s); if (!good) { ok = false; detail = d; break; } if (d) detail = d; }
      kit.check(c.name, ok, detail);
    }
  }
  window.CluProbe = { run };
})();
