// CLU's window on a tablet (Xiaomi Pad 6 class: 1280x800 CSS px at 2.25x, touch), served the way the Android app serves
// it (ShellHost on 127.0.0.1), with the app's native bridge stubbed. Usage: node tablet.ui.js <baseUrl> <code> <outDir> <device|pc>
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, out, mode] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };
(async () => {
  const browser = await chromium.launch({ args: ["--use-gl=swiftshader", "--enable-webgl", "--ignore-gpu-blocklist", "--autoplay-policy=no-user-gesture-required"] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2.25, hasTouch: true, colorScheme: "dark",
    userAgent: "Mozilla/5.0 (Linux; Android 14; 23043RP34G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 CLU-Android/1.13.0" });
  // The app's native bridge (ShellActivity.Bridge), scripted: a voice turn as the native session reports it.
  await ctx.addInitScript(() => {
    const calls = []; let t0 = 0;
    const turn = [[0, { phase: "listening", userLive: "" }], [300, { phase: "listening", userLive: "what time" }], [600, { phase: "thinking", userLive: "what time is it" }],
      [900, { phase: "speaking", userLive: "", agentLive: "It is" }], [1200, { phase: "speaking", agentLive: "It is noon." }], [1700, { phase: "listening", agentLive: "" }]];
    window.CluNative = {
      calls,
      voiceStart() { calls.push("voiceStart"); t0 = Date.now(); return JSON.stringify({ ok: true }); },
      voiceEnd() { calls.push("voiceEnd"); }, setMic(on) { calls.push("setMic:" + on); }, interrupt() { calls.push("interrupt"); }, classic() { calls.push("classic"); },
      voiceState() {
        const dt = Date.now() - t0; let s = turn[0][1]; for (const [at, v] of turn) if (dt >= at) s = v;
        return JSON.stringify(Object.assign({ server: true, link: "connected", micOn: true, userLive: "", agentLive: "", busy: s.phase !== "listening", level: 0 }, s));
      },
    };
  });
  const page = await ctx.newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/status of (4\d\d|503)/.test(m.text())) errors.push("console: " + m.text()); });
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    assert(true, "the launch code opens the window (" + mode + ")");
    await page.waitForSelector("#boot", { state: "detached", timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    const cls = await page.evaluate(() => document.body.className);
    assert(/\bandroid\b/.test(cls) && /\btablet\b/.test(cls), "marked as the Android app on a tablet: " + cls);
    assert(!(await page.$('.nav[data-view="hands"]')), "the camera gesture view stays on the PC");
    // The same window as on Windows: chamfered panels, numbered rail, CLU // titles, the full two-column layout at 1280 px.
    const look = await page.evaluate(() => ({ cut: getComputedStyle(document.querySelector("#composer")).clipPath, cols: getComputedStyle(document.querySelector(".app")).gridTemplateColumns,
      title: getComputedStyle(document.querySelector(".topbar h2"), "::before").content, navs: document.querySelectorAll(".nav").length }));
    console.log("  look:", JSON.stringify(look));
    assert(/polygon/.test(look.cut) && /CLU \/\//.test(look.title) && look.cols.split(" ").length === 2, "the PC design and layout, unchanged");
    // The Primus core: running, drawn at 1.25x on Android.
    await page.waitForFunction(() => document.body.classList.contains("primus-on"), null, { timeout: 30000 });
    const ratio = await page.evaluate(() => { const f = document.getElementById("primusCore"); const c = f.contentWindow.document.querySelector("#scene canvas"); return c.width / f.contentWindow.innerWidth; });
    assert(Math.abs(ratio - 1.25) < 0.02, "the Primus core runs, drawn at 1.25x (" + ratio.toFixed(2) + ")");
    await page.waitForFunction(() => (document.getElementById("linkBadge") || {}).textContent, null, { timeout: 8000 });
    const badge = await page.textContent("#linkBadge");
    assert(mode === "pc" ? /your PC/.test(badge) : /this device/.test(badge), "shows where answers come from: " + badge);
    await page.waitForTimeout(800); await page.screenshot({ path: path.join(out, `tablet-${mode}-chat.png`) });
    // Capability detection: PC-only views are marked while the PC isn't answering, never hidden or faked.
    await page.waitForTimeout(1200);
    const marked = await page.evaluate(() => ["agents", "models", "capabilities"].map((v) => document.querySelector(`.nav[data-view="${v}"]`)?.classList.contains("needs-pc")));
    assert(mode === "pc" ? marked.every((m) => !m) : marked.every(Boolean), "PC-only views marked only when the PC isn't answering: " + marked);
    // Memory works on this device too (shared archive and memory routes).
    await page.tap('.nav[data-view="memory"]'); await page.waitForTimeout(1500);
    const memText = await page.textContent("#view-memory");
    assert(!/connected to your PC|can't be reached/.test(memText), "the Memory view works " + (mode === "pc" ? "from the PC" : "on the device alone"));
    await page.screenshot({ path: path.join(out, `tablet-${mode}-memory.png`) });
    await page.tap('.nav[data-view="chat"]'); await page.waitForTimeout(300);

    // Typing: the reply streams from wherever answers come from.
    await page.fill("#input", "Hello"); await page.tap("#send");
    await page.waitForFunction(() => /I'm here/.test(document.querySelector("#messages li.assistant:last-child")?.textContent || ""), null, { timeout: 60000 });
    await page.waitForFunction(() => !document.querySelector("#send").disabled, null, { timeout: 30000 });
    assert(true, "a typed message is answered and streamed");

    // Voice: the native session, shown in the PC's voice bar with live words.
    await page.tap("#voiceBtn");
    await page.waitForSelector("#voiceBar:not(.hidden)");
    await page.waitForFunction(() => document.getElementById("voiceState").dataset.state === "speaking", null, { timeout: 8000 });
    await page.tap("#voiceStop"); // Stop is offered while CLU speaks
    await page.waitForFunction(() => /It is noon\./.test(document.querySelector("#messages")?.textContent || ""), null, { timeout: 8000 });
    const voice = await page.evaluate(() => ({ calls: CluNative.calls.slice(), state: document.getElementById("voiceState").dataset.state,
      user: [...document.querySelectorAll("#messages li.user")].map((l) => l.textContent).pop(), priv: document.getElementById("voicePrivacy").textContent }));
    console.log("  voice:", JSON.stringify(voice));
    assert(voice.calls.includes("voiceStart") && voice.calls.includes("interrupt") && /what time is it/.test(voice.user) && /your PC/.test(voice.priv), "voice uses the native session; live words appear as bubbles");
    await page.screenshot({ path: path.join(out, `tablet-${mode}-voice.png`) });
    await page.tap("#voiceEnd");
    assert((await page.evaluate(() => CluNative.calls.slice())).includes("voiceEnd"), "ending voice ends the native session");
    await page.tap(".nav-classic");
    assert((await page.evaluate(() => CluNative.calls.slice())).includes("classic"), "Classic view opens the native screens");
    assert(errors.length === 0, "no script errors: " + errors.join(" | "));
  } catch (e) {
    await page.screenshot({ path: path.join(out, `tablet-${mode}-failure.png`) }).catch(() => {});
    console.log(e.stack || e.message); console.log("errors:", errors.join(" | ")); process.exitCode = 1;
  } finally { await browser.close(); }
})();
