// Unit tests for CLU's gesture engine (shell/hand-gestures.js) with synthetic landmarks: the hand shapes are made up here,
// so this checks the engine's logic (thresholds, hysteresis, debounce, tap/drag/scale, calibration), not the camera or the model.
// Usage: node hand-gestures.test.js <path to hand-gestures.js>
"use strict";
const assert = require("assert");
const G = require(require("path").resolve(process.argv[2]));
let n = 0; const test = (name, fn) => { fn(); n++; console.log("ok - " + name); };

// A right hand, palm to the camera, ~0.2 of the picture tall; [pinch] closes the thumb onto the index tip.
function hand({ x = 0.5, y = 0.5, pinch = false, fingers = [1, 1, 1, 1], s = 0.1 } = {}) {
  const p = (dx, dy) => ({ x: x + dx * s, y: y + dy * s, z: 0 });
  const lm = new Array(21);
  lm[0] = p(0, 1.6);                                   // wrist
  lm[9] = p(0, 0.6);                                   // middle knuckle: hand size = 1.0 s
  const finger = (mcp, dx, ext) => { lm[mcp] = p(dx, 0.6); lm[mcp + 1] = p(dx, 0.2); lm[mcp + 2] = ext ? p(dx, -0.1) : p(dx, 0.45); lm[mcp + 3] = ext ? p(dx, -0.4) : p(dx, 0.75); };
  finger(5, -0.3, fingers[0]); finger(9, 0, fingers[1]); finger(13, 0.3, fingers[2]); finger(17, 0.55, fingers[3]);
  lm[1] = p(-0.4, 1.3); lm[2] = p(-0.6, 1.0); lm[3] = p(-0.75, 0.7);
  lm[4] = pinch ? { x: lm[8].x + 0.01 * s, y: lm[8].y + 0.01 * s, z: 0 } : p(-0.9, 0.4); // thumb tip
  return lm;
}
const run = (g, frames, t0 = 0, dt = 33) => frames.flatMap((f, i) => g.update(f, f.map((_, k) => "H" + k), t0 + i * dt).events);

test("poses: open, point, two, fist", () => {
  const g = G.create();
  const pose = (fingers) => g.pose(g.measure(hand({ fingers })), false);
  assert.strictEqual(pose([1, 1, 1, 1]), "open"); assert.strictEqual(pose([1, 0, 0, 0]), "point");
  assert.strictEqual(pose([1, 1, 0, 0]), "two"); assert.strictEqual(pose([0, 0, 0, 0]), "fist");
});
test("ratios hold at any distance from the camera", () => {
  const g = G.create();
  for (const s of [0.04, 0.1, 0.25]) { const m = g.measure(hand({ s })); assert(m.pinchRatio > 0.4, "open hand at size " + s); assert(g.measure(hand({ s, pinch: true })).pinchRatio < 0.25); }
});
test("a pinch needs 2 frames to start (debounce) and a single noisy frame does nothing", () => {
  const g = G.create();
  assert.deepStrictEqual(run(g, [[hand()], [hand({ pinch: true })], [hand()], [hand()]]).map((e) => e.type), []);
  const ev = run(G.create(), [[hand()], [hand({ pinch: true })], [hand({ pinch: true })]]).map((e) => e.type);
  assert.deepStrictEqual(ev, ["pinchstart"]);
});
test("a quick still pinch is a tap", () => {
  const ev = run(G.create(), [[hand()], [hand({ pinch: true })], [hand({ pinch: true })], [hand({ pinch: true })], [hand()], [hand()], [hand()]]).map((e) => e.type);
  assert.deepStrictEqual(ev, ["pinchstart", "pinchend", "tap"]);
});
test("hysteresis: a half-open pinch inside the dead band does not end it", () => {
  const g = G.create(); run(g, [[hand({ pinch: true })], [hand({ pinch: true })]]);
  const half = hand({ pinch: true }); half[4] = { x: half[8].x + 0.03, y: half[8].y, z: 0 }; // 0.3 hand-sizes: between enter 0.25 and exit 0.40
  assert.deepStrictEqual(run(g, [[half], [half], [half], [half]], 100).map((e) => e.type), []);
});
test("a long pinch is not a tap", () => {
  const frames = [[hand({ pinch: true })], [hand({ pinch: true })]]; for (let i = 0; i < 20; i++) frames.push([hand({ pinch: true })]); frames.push([hand()], [hand()], [hand()]);
  assert.deepStrictEqual(run(G.create(), frames).map((e) => e.type), ["pinchstart", "pinchend"]);
});
test("pinch and move is a drag, then no tap", () => {
  const frames = [[hand({ pinch: true })], [hand({ pinch: true })]]; for (let i = 1; i <= 10; i++) frames.push([hand({ pinch: true, x: 0.5 - i * 0.02 })]); frames.push([hand()], [hand()], [hand()]);
  const ev = run(G.create(), frames).map((e) => e.type);
  assert(ev.includes("drag") && !ev.includes("tap") && ev[ev.length - 1] === "pinchend", ev.join(","));
});
test("cursor is mirrored and steady", () => {
  const g = G.create(); let r;
  for (let i = 0; i < 30; i++) r = g.update([hand({ x: 0.3 })], ["R"], i * 33);
  assert(r.hands[0].x > 0.5, "the hand on the camera's left is on the screen's right (mirror)");
  const jitter = []; for (let i = 0; i < 30; i++) { r = g.update([hand({ x: 0.3 + (i % 2 ? 0.002 : -0.002) })], ["R"], 1000 + i * 33); jitter.push(r.hands[0].x); }
  assert(Math.max(...jitter) - Math.min(...jitter) < 0.004 / 0.76, "sensor jitter is damped");
});
test("two hands pinching scale", () => {
  const g = G.create(); const ev = [];
  for (let i = 0; i < 12; i++) ev.push(...g.update([hand({ x: 0.4 - i * 0.01, pinch: true }), hand({ x: 0.6 + i * 0.01, pinch: true })], ["L", "R"], i * 33).events);
  const scale = ev.filter((e) => e.type === "scale"); assert(scale.length > 0 && scale[scale.length - 1].factor > 1.2, "hands apart = bigger");
});
test("a hand that disappears mid-pinch ends the pinch", () => {
  const g = G.create(); run(g, [[hand({ pinch: true })], [hand({ pinch: true })]]);
  const ev = g.update([], [], 200).events; assert.strictEqual(ev[0].type, "pinchend"); assert(ev[0].lost);
});
test("calibration maps the reachable area to the whole screen and refuses a tiny one", () => {
  const g = G.create();
  assert.strictEqual(g.calibrate({ x: 0.5, y: 0.5 }, { x: 0.52, y: 0.55 }), false);
  assert(g.calibrate({ x: 0.8, y: 0.2 }, { x: 0.2, y: 0.7 }));
  const r = g.update([hand({ x: 0.8 + 0.03, y: 0.2 + 0.04 })], ["R"], 0); // index tip at (0.8, 0.2) in the picture
  assert(r.hands[0].x < 0.05 && r.hands[0].y < 0.05, JSON.stringify(r.hands[0]));
});
console.log(`${n} tests passed`);
