// Acceptance tests A–H for CLU's capability development, in CLU's real window (Chromium) against a running host.
// Usage: node capability.ui.js <baseUrl> <launchCode> <screenshotDir> <phase: build|restarted>
// Started by CapabilityUiTest, which restarts the host between the two phases. Exits non-zero on the first failed check.
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, shots, phase] = process.argv.slice(2);
const log = (s) => console.log(s);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); log("ok - " + what); };
const problems = [];

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 }, colorScheme: "dark" });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => problems.push("page error: " + e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource/.test(m.text())) problems.push("console: " + m.text()); });
  const shot = (name) => page.screenshot({ path: path.join(shots, name + ".png") });
  const frame = () => page.frameLocator("#expFrame");

  async function sayInChat(text) {
    await page.click('.nav[data-view="chat"]');
    const before = await page.$$eval("#messages li.msg.assistant", (l) => l.length);
    await page.fill("#input", text); await page.click("#send");
    await page.waitForFunction((n) => document.querySelectorAll("#messages li.msg.assistant").length > n && !document.querySelector("#send").disabled, before, { timeout: 60000 });
    return page.locator("#messages li.msg.assistant").last();
  }
  async function develop(text, shotName) {
    const reply = await sayInChat(text);
    log("  CLU: " + (await reply.locator(".body").textContent()));
    await reply.locator(".cap-progress").waitFor({ timeout: 30000 });
    await page.waitForFunction(() => { const s = [...document.querySelectorAll("#messages li.msg.assistant")].pop().querySelector(".cap-state"); return s && !/Developing/.test(s.textContent); }, null, { timeout: 300000 });
    const state = await reply.locator(".cap-state").last().textContent();
    const steps = await reply.locator(".cap-steps li .step-name").allTextContents();
    log("  steps: " + steps.join(" | "));
    log("  CLU: " + (await reply.locator(".body").textContent()));
    assert(state === "Installed", `"${text}": developed, verified and installed (${state})`);
    // CLU opens what it just installed; then the chat (with the steps) is captured, and the experience reopened from the rail.
    await page.waitForSelector("#view-experience:not(.hidden)", { timeout: 15000 });
    const cap = (/\/x\/([a-z0-9-]+)\//.exec(await page.getAttribute("#expFrame", "src")) || [])[1];
    assert(!!cap, `"${text}": CLU opened the new experience (${cap})`);
    await page.click('.nav[data-view="chat"]'); await reply.scrollIntoViewIfNeeded(); await shot(shotName + "-chat");
    await page.click(`#capNav .cap-entry[data-cap="${cap}"]`);
    await page.waitForSelector("#view-experience:not(.hidden)", { timeout: 15000 });
    return reply;
  }

  try {
    await page.goto(`${base}/#code=${code}`);
    await page.waitForSelector("#app:not(.hidden)");
    if (await page.isVisible("#wizard:not(.hidden)")) await page.click("#wizSkip").catch(() => {});
    await page.waitForTimeout(300);
    if (await page.isVisible("#wizard:not(.hidden)")) { while (await page.isVisible("#wizSkip")) await page.click("#wizSkip"); }

    if (phase === "build") {
      // ---- TEST A: "Give yourself a sci-fi weather service."
      await develop("Give yourself a sci-fi weather service", "A-weather");
      await frame().locator("body[data-clu-ready='1']").waitFor({ timeout: 30000 });
      assert(/SCI-FI|Sci-Fi/i.test(await page.textContent("#viewTitle")) || /weather/i.test(await page.textContent("#expTitle")), "A: the weather experience opened inside CLU");
      await frame().locator("#wxQuery").fill("London"); await frame().locator("#wxSearch button").click();
      await frame().locator("#wxResults li:not(.note)").first().waitFor(); await frame().locator("#wxResults li:not(.note)", { hasText: "London, GB" }).click();
      let temp = "", note = "";
      for (let i = 0; i < 100; i++) { temp = await frame().locator("#wxTemp").textContent(); note = await frame().locator("#wxNote").textContent();
        if (/\d/.test(temp) || /unavailable/i.test(note)) break; await page.waitForTimeout(200); }
      log(`  weather shown: ${temp} · ${note}`);
      assert(/^-?\d+°C$/.test(temp) || /unavailable/i.test(note), "A: the weather experience works: real figures, or an honest 'unavailable' with no invented numbers");
      await shot("A-weather-open");
      assert(await page.locator("#capNav .cap-entry", { hasText: /weather/i }).count() === 1, "A: the new capability appears in CLU's navigation");

      // ---- TEST B: "Give yourself a tactical map HUD."
      await develop("Give yourself a tactical map HUD", "B-tactical");
      await frame().locator("body.style-tactical[data-clu-ready='1']").waitFor({ timeout: 30000 });
      await frame().locator("#mapQuery").fill("Paris"); await frame().locator("#mapSearch button").click();
      await frame().locator("#mapResults li:not(.note)").first().click();
      await page.waitForTimeout(400);
      assert(/48\.8\d*, 2\.3/.test(await frame().locator("#mapCoords").textContent()), "B: the tactical map is interactive (search moved it to Paris)");
      await shot("B-tactical-open");

      // ---- TEST C: "Create a fantasy map too."
      await develop("Create a fantasy map too", "C-fantasy");
      await frame().locator("body.style-fantasy[data-clu-ready='1']").waitFor({ timeout: 30000 });
      await shot("C-fantasy-open");
      await page.click('.nav[data-view="capabilities"]');
      await page.waitForSelector("#capList .cap-card");
      const names = await page.$$eval("#capList .cap-card .title", (l) => l.map((x) => x.textContent));
      log("  capabilities: " + names.join(", "));
      assert(names.length === 3 && names.filter((n) => /map/i.test(n)).length >= 1, "C: both maps and the weather service are installed side by side");
      await shot("C-capabilities");

      // ---- TEST D: "Show me my maps."
      const list = await sayInChat("Show me my maps");
      const cards = await list.locator(".cap-card").count();
      const previews = await list.locator(".cap-card img.cap-preview").count();
      log("  CLU: " + (await list.locator(".body").textContent()));
      assert(cards === 2 && previews === 2, "D: CLU presents both map experiences with previews");
      await shot("D-show-maps");

      // ---- TEST E: "Open my map" → CLU asks; the owner chooses the fantasy map.
      const ask = await sayInChat("Open my map");
      log("  CLU: " + (await ask.locator(".body").textContent()));
      assert(await ask.locator(".cap-card").count() === 2 && /Which one/i.test(await ask.locator(".body").textContent()), "E: with two maps and no default, CLU asks which one");
      await shot("E-which-map");
      await ask.locator(".cap-card", { hasText: /fantasy/i }).locator("button", { hasText: "Choose" }).click();
      await page.waitForSelector("#view-experience:not(.hidden)", { timeout: 30000 });
      assert(/map-fantasy/.test(await page.getAttribute("#expFrame", "src")), "E: the fantasy map opened");
      await frame().locator("body.style-fantasy[data-clu-ready='1']").waitFor();
      await shot("E-fantasy-opened");

      // ---- TEST F: "Make the tactical map my default."
      const def = await sayInChat("Make the tactical map my default");
      log("  CLU: " + (await def.locator(".body").textContent()));
      await page.click('.nav[data-view="capabilities"]'); await page.waitForSelector("#capList .cap-default.on", { timeout: 10000 });
      const tacDefault = await page.locator("#capList .cap-card", { hasText: /tactical/i }).locator(".cap-default.on").count();
      const fanDefault = await page.locator("#capList .cap-card", { hasText: /fantasy/i }).locator(".cap-default.on").count();
      assert(tacDefault === 1 && fanDefault === 0, "F: the tactical map is the default; the fantasy map is kept, not default");
      await shot("F-default");
    } else {
      // ---- TEST G: after a restart everything is still registered and usable, and the default persisted.
      await page.click('.nav[data-view="capabilities"]'); await page.waitForSelector("#capList .cap-card");
      const cards = await page.$$eval("#capList .cap-card", (l) => l.map((c) => ({ t: c.querySelector(".title").textContent, chips: [...c.querySelectorAll(".chip-s.on")].map((x) => x.textContent) })));
      log("  after restart: " + JSON.stringify(cards));
      assert(cards.length === 3 && cards.every((c) => c.chips.includes("installed") && c.chips.includes("verified") && c.chips.includes("enabled")), "G: weather and both maps are still installed, verified and enabled");
      assert(cards.filter((c) => c.chips.includes("default")).length === 1 && /tactical/i.test(cards.find((c) => c.chips.includes("default")).t), "G: the tactical map is still the default");
      await shot("G-after-restart");
      const open = await sayInChat("Open my map");
      log("  CLU: " + (await open.locator(".body").textContent()));
      await page.waitForSelector("#view-experience:not(.hidden)");
      assert(/map-tactical/.test(await page.getAttribute("#expFrame", "src")), "G: \"open my map\" opens the default (tactical) without asking");
      await frame().locator("body[data-clu-ready='1']").waitFor();
      await page.locator("#capNav .cap-entry", { hasText: /fantasy/i }).click();
      await frame().locator("body.style-fantasy[data-clu-ready='1']").waitFor();
      await page.locator("#capNav .cap-entry", { hasText: /weather/i }).click();
      await frame().locator("body.kind-weather[data-clu-ready='1']").waitFor();
      assert(true, "G: each experience opens from the navigation after the restart");
      await shot("G-weather-after-restart");

      // ---- TEST H: "Improve my weather service."
      const reply = await develop("Improve my weather service", "H-improve");
      assert(/improved/i.test(await reply.locator(".body").textContent()) && /v2/.test(await reply.locator(".body").textContent()), "H: understood as an update to the existing capability (v2)");
      await frame().locator("body.kind-weather[data-clu-ready='1']").waitFor({ timeout: 30000 });
      assert(await frame().locator(".wx-hourly").count() === 1, "H: the improved version (with the 24-hour forecast) is the one running");
      await page.click('.nav[data-view="capabilities"]'); await page.waitForFunction(() => [...document.querySelectorAll("#capList .cap-card .sub")].some((s) => /weather · v2/.test(s.textContent)), null, { timeout: 10000 });
      const weather = await page.$$eval("#capList .cap-card", (l) => l.map((c) => c.querySelector(".sub").textContent).filter((s) => /weather/.test(s)));
      log("  weather capabilities: " + weather.join("; "));
      assert(weather.length === 1 && /v2/.test(weather[0]), "H: still one weather capability, now v2 (not a duplicate)");
      await shot("H-capabilities-v2");
    }
    assert(problems.length === 0, "no page errors: " + problems.join(" | "));
  } catch (e) {
    console.error(e.stack || String(e)); await shot("failure-" + phase).catch(() => {});
    if (problems.length) console.error("page problems: " + problems.join(" | "));
    await browser.close(); process.exit(1);
  }
  await browser.close();
})();
