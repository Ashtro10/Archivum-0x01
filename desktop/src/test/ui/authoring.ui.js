// Milestone acceptance in CLU's real window: the galaxy file explorer, opening it by name, the search modification,
// and "show me all the applications you have developed", with the real development model.
// Usage: node authoring.ui.js <baseUrl> <launchCode> <outDir> <phase: build|restarted>
// It records what actually happened in <outDir>/outcome-<phase>.json; CapabilityAuthoringUiTest judges it.
"use strict";
const { chromium } = require("playwright");
const fs = require("fs"), path = require("path");
const [base, code, out, phase] = process.argv.slice(2);
const outcome = { phase, steps: [] };
const note = (k, v) => { outcome[k] = v; console.log(`  ${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`); };
const save = () => fs.writeFileSync(path.join(out, `outcome-${phase}.json`), JSON.stringify(outcome, null, 2));

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 }, colorScheme: "dark" })).newPage();
  const problems = []; page.on("pageerror", (e) => problems.push(e.message));
  const shot = (n) => page.screenshot({ path: path.join(out, n + ".png") });
  async function say(text) {
    await page.click('.nav[data-view="chat"]');
    const n = await page.$$eval("#messages li.msg.assistant", (l) => l.length);
    await page.fill("#input", text); await page.click("#send");
    await page.waitForFunction((k) => document.querySelectorAll("#messages li.msg.assistant").length > k && !document.querySelector("#send").disabled, n, { timeout: 120000 });
    const reply = page.locator("#messages li.msg.assistant").last();
    console.log(`> ${text}\n  CLU: ${(await reply.locator(".body").textContent()).slice(0, 400)}`);
    return reply;
  }
  async function develop(text, shotName) {
    const reply = await say(text);
    if (!(await reply.locator(".cap-progress").count())) { await reply.locator(".cap-progress").waitFor({ timeout: 15000 }).catch(() => {}); }
    if (!(await reply.locator(".cap-progress").count())) return { state: "not-started", message: await reply.locator(".body").textContent() };
    await page.waitForFunction(() => { const s = [...document.querySelectorAll("#messages li.msg.assistant")].pop().querySelector(".cap-state"); return s && !/Developing/.test(s.textContent); }, null, { timeout: 3_000_000, polling: 2000 });
    const state = await reply.locator(".cap-state").last().textContent();
    const steps = await reply.locator(".cap-steps li").allTextContents();
    const message = await reply.locator(".body").textContent();
    await page.click('.nav[data-view="chat"]'); await reply.scrollIntoViewIfNeeded(); await shot(shotName);
    console.log(`  → ${state}\n  ${steps.map((s) => s.slice(0, 260)).join("\n  ")}`);
    return { state, message, steps };
  }
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    // The first-run guide opens asynchronously: wait for it, then skip it until it is closed.
    await page.waitForSelector("#wizard:not(.hidden)", { timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    if (phase === "build") {
      // Existing kit experiences, so the final list has something to compare with.
      note("weather", (await develop("Give yourself a sci-fi weather service", "X-weather")).state);
      note("map", (await develop("Give yourself a tactical map HUD", "X-map")).state);
      // The milestone request.
      const r = await develop("Give yourself a futuristic file explorer with a three-dimensional galaxy interface", "1-galaxy-explorer-run");
      note("explorer", r);
      if (r.state === "Installed") {
        const open = await say("Open the futuristic file explorer");
        await page.waitForSelector("#view-experience:not(.hidden)", { timeout: 20000 });
        const f = page.frameLocator("#expFrame");
        await f.locator("body[data-clu-ready='1']").waitFor({ timeout: 30000 });
        const entries = await f.locator("[data-entry]").evaluateAll((l) => l.map((e) => e.getAttribute("data-entry")));
        note("explorerEntries", entries); await shot("2-galaxy-explorer-open");
      }
    } else {
      const list1 = await say("Show me all the applications you have developed");
      note("listAfterRestart", await list1.locator(".cap-card .title").allTextContents());
      const mod = await develop("Add a search function to your galaxy file explorer", "3-search-modification-run");
      await page.click('.nav[data-view="chat"]'); await shot("3-search-modification-reply");
      note("modification", mod);
      const list2 = await say("Show me all the applications you have developed");
      note("listFinal", await list2.locator(".cap-card").evaluateAll((l) => l.map((c) => c.querySelector(".title").textContent + " | " + c.querySelector(".sub").textContent + " | " + [...c.querySelectorAll(".chip-s.on")].map((x) => x.textContent).join(","))));
      await list2.scrollIntoViewIfNeeded(); await shot("4-applications-list");
    }
    note("pageErrors", problems);
  } catch (e) { note("scriptError", String(e.stack || e)); await shot("failure-" + phase).catch(() => {}); }
  save(); await browser.close();
})();
