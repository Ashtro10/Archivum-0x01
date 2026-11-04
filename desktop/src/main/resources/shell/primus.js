/*
 * The Primus core in CLU's window: the original JARVIS Core particle head (shell/primus/, as bundled with Primus) runs
 * full-window behind the interface, the way Primus shows it, and follows CLU's real state: idle, listening, thinking,
 * speaking, with the mouth driven by the actual audio level while CLU speaks and the gaze by the pointer.
 * If the core cannot start (no WebGL), it is removed and CLU's own face stays.
 */
(function () {
  "use strict";
  const STATES = { idle: "IDLE", listening: "LISTENING", thinking: "THINKING", speaking: "SPEAKING" };
  let frame = null, core = null, level = 0, decay = 0;

  function api() {
    if (core) return core;
    try { core = frame && frame.contentWindow && frame.contentWindow.jarvisCore; } catch (e) { core = null; }
    return core;
  }

  function softwareGl() {
    try {
      const c = frame.contentWindow.document.querySelector("#scene canvas");
      const gl = c && (c.getContext("webgl2") || c.getContext("webgl")); if (!gl) return false;
      const ext = gl.getExtension("WEBGL_debug_renderer_info");
      const r = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
      return /swiftshader|llvmpipe|software|basic render/i.test(String(r));
    } catch (e) { return false; }
  }

  function set(state) { const c = api(); if (c && STATES[state]) c.setPrimusState(STATES[state]); }
  /** Speech level 0..1 from the real audio meter (voice) or from streamed text (pulses that decay). */
  function speak(v) { level = Math.max(level, Math.max(0, Math.min(1, v))); }

  function attach(el) {
    frame = el;
    const started = Date.now();
    const ready = setInterval(() => {
      if (api()) { clearInterval(ready); document.body.classList.add("primus-on");
        // Software WebGL (no GPU acceleration: SwiftShader, llvmpipe) shares the window's thread: draw at half resolution.
        if (softwareGl()) document.body.classList.add("primus-software");
        const reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        if (reduced && core.setReducedMotion) core.setReducedMotion(true);
        set(window.CluFace ? CluFace.state || "idle" : "idle"); return; }
      if (Date.now() - started > 15000) { clearInterval(ready); el.remove(); frame = null; document.body.classList.remove("primus-on"); }
    }, 250);
    // The mouth: a decaying level, fed by the audio meter or text pulses.
    decay = setInterval(() => { const c = api(); if (!c) return; c.setSpeakLevel(level); level *= 0.6; }, 80);
    // The gaze follows the pointer.
    window.addEventListener("pointermove", (e) => { const c = api(); if (c) c.setCursorLook(e.clientX / innerWidth * 2 - 1, -(e.clientY / innerHeight * 2 - 1)); }, { passive: true });
    // Follow CLU's presence: wrap the face's state and pulse so every existing caller drives the core too.
    if (window.CluFace) {
      const origSet = CluFace.set.bind(CluFace), origPulse = CluFace.pulse.bind(CluFace);
      CluFace.set = function (s) { origSet(s); set(s); };
      CluFace.pulse = function (v) { origPulse(v); speak(v == null ? 0.55 : v); };
    }
  }

  window.CluPrimus = { attach, set, speak };
})();
