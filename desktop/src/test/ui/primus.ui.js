// The Primus core in CLU's real window: it starts (WebGL), sits behind the interface, follows CLU's state during a real
// chat turn, and is not blank. Usage: node primus.ui.js <baseUrl> <launchCode> <outDir>
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, out] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };
(async () => {
  const browser = await chromium.launch({ args: ["--use-gl=swiftshader", "--enable-webgl", "--ignore-gpu-blocklist"] });
  const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 }, colorScheme: "dark" })).newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    await page.waitForSelector("#wizard:not(.hidden)", { timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    await page.waitForFunction(() => document.body.classList.contains("primus-on"), null, { timeout: 20000 });
    const frame = page.frame({ url: /\/shell\/primus\/index\.html/ });
    assert(!!frame, "the original Primus core page is loaded in CLU's window");
    const info = await frame.evaluate(() => ({ api: !!window.jarvisCore && typeof window.jarvisCore.setPrimusState, canvas: !!document.querySelector("#scene canvas"),
      bg: document.body.classList.contains("background"), gl: (() => { const c = document.querySelector("#scene canvas"); return c && !!(c.getContext("webgl2") || c.getContext("webgl")); })() }));
    console.log("  core:", JSON.stringify(info));
    assert(info.api === "function" && info.canvas && info.bg, "the core runs (jarvisCore API, WebGL canvas) in background mode, behind the interface");
    await page.waitForTimeout(2500); await page.screenshot({ path: path.join(out, "primus-idle.png") });
    // Real state change: thinking/speaking during a real chat turn.
    const states = [];
    await page.exposeFunction("__rec", (s) => states.push(s));
    await frame.evaluate(() => { const o = window.jarvisCore.setPrimusState; window.jarvisCore.setPrimusState = (s) => { window.parent.__rec && window.parent.__rec(s); o(s); }; });
    await page.fill("#input", "Hello"); await page.click("#send");
    await page.waitForFunction(() => !document.querySelector("#send").disabled, null, { timeout: 60000 });
    await page.waitForTimeout(400);
    console.log("  states during the turn:", states.join(" → "));
    assert(states.includes("THINKING") && states.includes("SPEAKING") && states[states.length - 1] === "IDLE", "the core followed CLU: thinking → speaking → idle");
    await page.screenshot({ path: path.join(out, "primus-chat.png") });
    assert(errors.length === 0, "no page errors: " + errors.join(" | "));
  } catch (e) { console.error(String(e.stack || e)); await page.screenshot({ path: path.join(out, "primus-failure.png") }).catch(() => {}); await browser.close(); process.exit(1); }
  await browser.close();
})();
