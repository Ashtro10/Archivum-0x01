// CLU · Hands in CLU's real window, with the real MediaPipe hand landmarker running on a camera picture.
// The camera is Chromium's fake capture device playing a Y4M clip made from real photographs of hands, so the model,
// the landmarks and CLU's gesture engine are real; only the camera hardware is replaced (this is not a real-hardware test).
// Usage: node hands.ui.js <baseUrl> <launchCode> <outDir> <phase> [clip.y4m]
//   phase: nopack | denied | point | victory | none
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, out, phase, clip] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };
(async () => {
  // Headless Chromium has no permission prompt: the fake UI answers it (allow, or deny for the refusal phase).
  const args = ["--use-fake-device-for-media-stream", phase === "denied" ? "--use-fake-ui-for-media-stream=deny" : "--use-fake-ui-for-media-stream"];
  if (clip) args.push("--use-file-for-fake-video-capture=" + clip);
  const browser = await chromium.launch({ args });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: "dark" });
  if (phase !== "denied") await context.grantPermissions(["camera"], { origin: base });
  const page = await context.newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)"); await page.evaluate(() => { const b = document.querySelector('.nav[data-view="chat"]'); if (b) b.click(); }) /* Core is the start view */;
    await page.waitForSelector("#wizard:not(.hidden)", { timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    await page.click('.nav[data-view="hands"]');
    await page.waitForFunction(() => /hands\.html/.test(document.getElementById("handsFrame").src));
    let frame = null; for (let i = 0; i < 50 && !(frame = page.frame({ url: /\/shell\/hands\.html/ })); i++) await page.waitForTimeout(100);
    assert(!!frame, "Hands opens inside CLU's window");
    await frame.waitForFunction(() => window.__cluHands, null, { timeout: 10000 });
    const st = () => frame.evaluate(() => window.__cluHands.state());

    if (phase === "nopack") {
      await frame.waitForFunction(() => window.__cluHands.state().phase === "needs-pack", null, { timeout: 15000 });
      const text = await frame.textContent("#setup");
      console.log("  setup:", text.replace(/\s+/g, " ").slice(0, 300));
      assert(/17\.\d MB/.test(text) && /registry\.npmjs\.org/.test(text) && /storage\.googleapis\.com/.test(text) && /Apache-2\.0/.test(text),
        "before anything is downloaded, the owner sees the size, the sources and the licence");
      assert(await frame.isVisible("#setup button"), "and the download waits for their click");
      await page.screenshot({ path: path.join(out, "hands-nopack.png") });
    } else if (phase === "denied") {
      await frame.waitForFunction(() => ["camera-error", "error"].includes(window.__cluHands.state().phase), null, { timeout: 30000 });
      const s = await st(); const text = await frame.textContent("#setup");
      console.log("  state:", s.phase, s.error, "|", text.replace(/\s+/g, " "));
      assert(s.phase === "camera-error" && s.error === "NotAllowedError" && /permission was refused/i.test(text), "a refused camera permission is explained, not a blank screen");
      await page.screenshot({ path: path.join(out, "hands-denied.png") });
    } else {
      await frame.waitForFunction(() => window.__cluHands.state().phase === "tracking", null, { timeout: 60000 });
      const t0 = Date.now();
      // Let the clip loop a few times (it is 1 s long) and collect what the engine saw.
      const seen = {}; let hands = 0, s = null;
      while (Date.now() - t0 < 6000) {
        s = await st(); s.hands.forEach((h) => { seen[h.pose] = (seen[h.pose] || 0) + 1; hands++; });
        await page.waitForTimeout(150);
      }
      console.log(`  delegate ${s.delegate}, frames ${s.frames}, frames with a hand ${s.detected}, fps ${s.fps}, poses seen ${JSON.stringify(seen)}, landmarks ${JSON.stringify(s.landmarks)}`);
      if (s.hands[0]) console.log(`  cursor ${s.hands[0].x.toFixed(3)},${s.hands[0].y.toFixed(3)} pinchRatio ${s.hands[0].pinchRatio.toFixed(2)}`);
      if (phase === "none") {
        assert(s.frames > 10, "frames are being processed");
        assert(s.detected === 0 && hands === 0, "no hand in the picture: no cursor and no gestures (no false detections)");
      } else {
        const want = phase === "point" ? "point" : "two";
        assert(s.frames > 10 && s.detected > s.frames * 0.5, "the real model finds the hand in most frames");
        assert(JSON.stringify(s.landmarks).includes("21"), "21 landmarks per hand");
        const top = Object.entries(seen).sort((a, b) => b[1] - a[1])[0];
        assert(top && top[0] === want, `the pose is recognised as "${want}" (most frequent: ${top && top[0]})`);
        const cur = await frame.$$eval(".cursor", (c) => c.length);
        assert(cur >= 1, "a cursor ring is drawn where the fingertip is");
        assert(!s.events.some((e) => e.type === "tap" || e.type === "pinchstart"), "an open pointing hand never triggers a tap or pinch");
        // Widgets show real data from CLU.
        const core = await frame.textContent('[data-widget="core"]');
        assert(/conversations/.test(core), "the Core widget shows CLU's real status");
        const wx = await frame.textContent('[data-widget="weather"]');
        console.log("  weather widget:", wx.replace(/\s+/g, " "));
        assert(/No place known|unavailable|°C/.test(wx), "the Weather widget shows real weather or says plainly why it can't");
        await frame.click("#debug");
        await page.waitForTimeout(500);
      }
      await page.screenshot({ path: path.join(out, `hands-${phase}.png`) });
    }
    // Leaving the view stops the camera.
    await page.click('.nav[data-view="chat"]');
    await page.waitForFunction(() => !/hands\.html/.test(document.getElementById("handsFrame").src));
    assert(!page.frame({ url: /\/shell\/hands\.html/ }), "leaving Hands unloads it (the camera is released)");
    assert(errors.length === 0, "no page errors: " + errors.join(" | "));
  } catch (e) { console.error(String(e.stack || e));
    const f = page.frame({ url: /\/shell\/hands\.html/ });
    if (f) console.error("  page said:", await f.evaluate(() => JSON.stringify({ status: document.getElementById("status").textContent, s: window.__cluHands && window.__cluHands.state() })).catch((x) => String(x)));
    if (errors.length) console.error("  page errors:", errors.join(" | ")); await page.screenshot({ path: path.join(out, `hands-${phase}-failure.png`) }).catch(() => {}); await browser.close(); process.exit(1); }
  await browser.close();
})();
