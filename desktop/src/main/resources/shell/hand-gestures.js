/*
 * CLU hand gestures: turns MediaPipe hand landmarks (21 points per hand, normalised image coordinates) into a cursor
 * and gestures. Pure logic, no camera or DOM, so it is tested on its own.
 *
 * Measures are ratios of the hand's own size (wrist to middle knuckle), so they hold at any distance from the camera.
 * - pinch: thumb tip to index tip below 0.25 hand-sizes for 2 frames starts it; above 0.40 for 3 frames ends it
 *   (hysteresis + debounce: no flicker at the threshold)
 * - pose: "pinch", "open" (4 fingers extended), "point" (index only), "two" (index + middle), "fist" (none), "other"
 * - cursor: the index fingertip (mirrored like a mirror), smoothed more when slow (steady) and less when fast (responsive),
 *   mapped from the calibrated area of the camera picture to the whole screen
 * - events: pinchstart, pinchend, tap (short pinch that barely moved), drag (pinch held and moved), scale (two hands pinching)
 */
(function (root) {
  "use strict";
  const WRIST = 0, THUMB_TIP = 4, INDEX_PIP = 6, INDEX_TIP = 8, MIDDLE_MCP = 9, MIDDLE_PIP = 10, MIDDLE_TIP = 12, RING_PIP = 14, RING_TIP = 16, PINKY_PIP = 18, PINKY_TIP = 20;
  const d = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

  function create(opts) {
    opts = Object.assign({ enter: 0.25, exit: 0.40, enterFrames: 2, exitFrames: 3, tapMs: 450, tapMove: 0.035, dragMove: 0.02,
      box: { x0: 0.12, x1: 0.88, y0: 0.10, y1: 0.80 }, mirror: true }, opts || {});
    const hands = new Map(); // id -> state
    let scaleStart = null;

    function extended(lm, tip, pip) { return d(lm[WRIST], lm[tip]) > d(lm[WRIST], lm[pip]) * 1.12; }

    function measure(lm) {
      const size = Math.max(1e-6, d(lm[WRIST], lm[MIDDLE_MCP]));
      const pinchRatio = d(lm[THUMB_TIP], lm[INDEX_TIP]) / size;
      const fingers = [extended(lm, INDEX_TIP, INDEX_PIP), extended(lm, MIDDLE_TIP, MIDDLE_PIP), extended(lm, RING_TIP, RING_PIP), extended(lm, PINKY_TIP, PINKY_PIP)];
      return { size, pinchRatio, fingers };
    }

    function pose(m, pinched) {
      if (pinched) return "pinch";
      const n = m.fingers.filter(Boolean).length, [i, mi] = m.fingers;
      if (n === 4) return "open";
      if (n === 0) return "fist";
      if (n === 1 && i) return "point";
      if (n === 2 && i && mi) return "two";
      return "other";
    }

    function toScreen(p) {
      const b = opts.box, x = opts.mirror ? 1 - p.x : p.x;
      return { x: clamp((x - b.x0) / (b.x1 - b.x0), 0, 1), y: clamp((p.y - b.y0) / (b.y1 - b.y0), 0, 1) };
    }

    /** [landmarkSets]: array of 21-point arrays; [ids]: stable ids per hand ("Left"/"Right"); [t]: ms. Returns the frame's result. */
    function update(landmarkSets, ids, t) {
      const events = [], seen = new Set();
      (landmarkSets || []).forEach((lm, k) => {
        if (!lm || lm.length < 21) return;
        const id = (ids && ids[k]) || String(k); seen.add(id);
        let h = hands.get(id);
        const m = measure(lm), raw = toScreen(lm[INDEX_TIP]);
        if (!h) { h = { id, x: raw.x, y: raw.y, pinched: false, inCount: 0, outCount: 0, start: null, dragging: false, lastT: t }; hands.set(id, h); }
        // Adaptive smoothing: steady when slow, responsive when fast.
        const dt = Math.max(1, t - h.lastT), speed = Math.hypot(raw.x - h.x, raw.y - h.y) / dt * 1000;
        const a = clamp(0.25 + speed * 0.9, 0.25, 0.85);
        h.x += (raw.x - h.x) * a; h.y += (raw.y - h.y) * a; h.lastT = t;
        // Pinch with hysteresis and debounce.
        if (!h.pinched) { h.inCount = m.pinchRatio < opts.enter ? h.inCount + 1 : 0;
          if (h.inCount >= opts.enterFrames) { h.pinched = true; h.outCount = 0; h.start = { x: h.x, y: h.y, t }; h.dragging = false; events.push({ type: "pinchstart", hand: id, x: h.x, y: h.y }); } }
        else { h.outCount = m.pinchRatio > opts.exit ? h.outCount + 1 : 0;
          if (h.outCount >= opts.exitFrames) { h.pinched = false; h.inCount = 0;
            const moved = Math.hypot(h.x - h.start.x, h.y - h.start.y);
            events.push({ type: "pinchend", hand: id, x: h.x, y: h.y });
            if (!h.dragging && t - h.start.t <= opts.tapMs && moved <= opts.tapMove) events.push({ type: "tap", hand: id, x: h.start.x, y: h.start.y });
            h.start = null; h.dragging = false; } }
        if (h.pinched && h.start) {
          const moved = Math.hypot(h.x - h.start.x, h.y - h.start.y);
          if (h.dragging || moved > opts.dragMove) { h.dragging = true; events.push({ type: "drag", hand: id, x: h.x, y: h.y, dx: h.x - (h.px ?? h.x), dy: h.y - (h.py ?? h.y) }); }
        }
        h.px = h.x; h.py = h.y;
        h.pose = pose(m, h.pinched); h.pinchRatio = m.pinchRatio; h.size = m.size;
      });
      for (const id of [...hands.keys()]) if (!seen.has(id)) { const h = hands.get(id); if (h.pinched) events.push({ type: "pinchend", hand: id, x: h.x, y: h.y, lost: true }); hands.delete(id); }
      // Two hands pinching: scale by the change in distance between them.
      const pinching = [...hands.values()].filter((h) => h.pinched);
      if (pinching.length === 2) {
        const dist = Math.hypot(pinching[0].x - pinching[1].x, pinching[0].y - pinching[1].y);
        if (!scaleStart) scaleStart = dist; else if (scaleStart > 0.02) events.push({ type: "scale", factor: dist / scaleStart, x: (pinching[0].x + pinching[1].x) / 2, y: (pinching[0].y + pinching[1].y) / 2 });
      } else scaleStart = null;
      return { hands: [...hands.values()].map((h) => ({ id: h.id, x: h.x, y: h.y, pinched: h.pinched, pose: h.pose, pinchRatio: h.pinchRatio })), events };
    }

    /** Calibration: the camera-picture area the owner can comfortably reach becomes the whole screen. */
    function calibrate(topLeft, bottomRight) {
      const x0 = opts.mirror ? 1 - topLeft.x : topLeft.x, x1 = opts.mirror ? 1 - bottomRight.x : bottomRight.x;
      if (Math.abs(x1 - x0) < 0.15 || Math.abs(bottomRight.y - topLeft.y) < 0.15) return false;
      opts.box = { x0: Math.min(x0, x1), x1: Math.max(x0, x1), y0: Math.min(topLeft.y, bottomRight.y), y1: Math.max(topLeft.y, bottomRight.y) };
      return true;
    }

    return { update, calibrate, box: () => Object.assign({}, opts.box), setBox: (b) => { opts.box = b; }, measure, pose };
  }

  const api = { create, INDEX_TIP, THUMB_TIP };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.CluGestures = api;
})(typeof window !== "undefined" ? window : globalThis);
