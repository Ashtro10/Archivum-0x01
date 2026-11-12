// CLU Core console in a real browser against a real host (scripted model): every module does what it shows, with real
// data only. Usage: node core.ui.js <baseUrl> <code> <outDir>
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const fs = require("fs");
const [base, code, out] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };
(async () => {
  const browser = await chromium.launch({ args: ["--use-gl=swiftshader", "--enable-webgl", "--ignore-gpu-blocklist", "--autoplay-policy=no-user-gesture-required"] });
  const page = await (await browser.newContext({ viewport: { width: 1608, height: 1000 }, colorScheme: "dark" })).newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/status of (4\d\d|503)/.test(m.text())) errors.push("console: " + m.text()); });
  const consoleText = () => page.textContent("#view-core .console");
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    await page.waitForSelector("#boot", { state: "detached", timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    assert(await page.isVisible("#view-core"), "the Core console is the start view");
    const parts = await page.evaluate(() => ["arc", "rings", "source", "hex", "files", "mods", "keys"].map((k) => !!document.querySelector("#reg-" + k)));
    assert(parts.every(Boolean), "all seven regions of the reference present: " + parts);
    assert(await page.evaluate(() => getComputedStyle(document.querySelector(".rail")).display === "none" && getComputedStyle(document.querySelector(".topbar")).display === "none"),
      "no sidebar or title bar: the machine fills the window");
    await page.waitForTimeout(900);
    assert(await page.evaluate(() => document.querySelectorAll("#view-core path.flow").length >= 10), "energy conduits run along the frame lines");
    await page.waitForFunction(() => document.getElementById("arc-proc") && document.getElementById("arc-proc").textContent !== "…", null, { timeout: 8000 });
    const arc = await page.evaluate(() => ["proc", "sys", "infer", "records", "sugg", "tasks"].map((k) => document.getElementById("arc-" + k).textContent));
    console.log("  arc:", JSON.stringify(arc));
    assert(/MB/.test(arc[0]) && /GB/.test(arc[1]) && /running/.test(arc[2]), "the data matrix shows CLU's real figures (memory, system, inference queue)");
    assert(/NO SIGNAL/.test(await page.textContent("#coreWaveLabel")), "the waveform says NO SIGNAL when there is no audio (nothing simulated)");
    await page.screenshot({ path: path.join(out, "core-idle.png") });

    // Terminal: typed and on-screen keys; help; a question streams through the real turn path and drives the rings.
    await page.click("#coreCmd"); await page.keyboard.type("help"); await page.keyboard.press("Enter");
    assert(/open <view>/.test(await consoleText()), "the terminal runs commands (help)");
    await page.click('[aria-label="On-screen keyboard"]'); await page.waitForSelector("#coreKeyboard:not(.hidden)"); await page.waitForTimeout(500);
    for (const k of ["s", "t", "a", "t", "u", "s"]) await page.click(`#coreKeyboard .key[data-k="${k}"]`);
    assert((await page.inputValue("#coreCmd")) === "status", "the on-screen keyboard types into the command line");
    await page.click('#coreKeyboard .key[data-k="enter"]'); await page.click("#coreKeyboard .kbtn");
    await page.waitForFunction(() => /model: /.test(document.querySelector("#view-core .console").textContent));
    assert(true, "status runs from the keyboard and reports the real model state");
    await page.fill("#coreCmd", "bogus-command-xyz"); await page.keyboard.press("Enter");
    // an unknown word is a question for CLU: it goes to the conversation (never a fake success)
    const stagesSeen = new Set();
    for (let i = 0; i < 80; i++) { stagesSeen.add(await page.evaluate(() => document.getElementById("view-core").dataset.stage)); if (/answer complete/.test(await consoleText())) break; await page.waitForTimeout(100); }
    console.log("  stages:", [...stagesSeen].join(","));
    assert(stagesSeen.has("synthesis") || stagesSeen.has("output"), "the rings follow the real turn (input → … → output)");
    assert(/answer complete/.test(await consoleText()), "the console logs the real completion");
    assert(/Hello/.test(await page.textContent("#view-core .preview-txt")), "the source container previews the real answer");
    await page.screenshot({ path: path.join(out, "core-answer.png") });
    await page.waitForFunction(() => document.getElementById("view-core").dataset.stage === "idle", null, { timeout: 8000 });
    assert(true, "back to idle after the answer");

    // Source window materialises and closes.
    await page.click('[aria-label="Sources and ideas: open the intelligence container"] >> nth=0'); await page.waitForSelector("#coreSources:not(.hidden)");
    await page.waitForTimeout(500); assert(/Last answer/.test(await page.textContent("#coreSources")), "the intelligence container materialises with the last answer, sources, suggestions");
    await page.screenshot({ path: path.join(out, "core-sources.png") });
    await page.click("#coreSources .kbtn"); await page.waitForSelector("#coreSources.hidden", { state: "attached" });

    // File explorer: a real upload into CLU's archive, then it appears in the list.
    const f = path.join(out, "core-note.txt"); fs.writeFileSync(f, "Project Gridline: the light-cycle arena opens on 12 March.\n");
    await page.setInputFiles("#view-core input[type=file]", f);
    assert(/core-note\.txt/.test(await page.textContent("#view-core .meta-grid")), "a picked file shows its metadata and preview");
    await page.click("#view-core .files .kbtn:text('Upload')");
    await page.waitForFunction(() => /synchronised: core-note\.txt/.test(document.querySelector("#view-core .console").textContent), null, { timeout: 15000 });
    await page.waitForFunction(() => /core-note\.txt/.test(document.querySelector("#view-core .file-list").textContent), null, { timeout: 5000 });
    assert(true, "uploaded into the archive and listed");

    // Modules switch real views; the stop sensor really asks CLU to stop.
    await page.click('[aria-label="Memory and archive"]'); await page.waitForSelector("#view-memory:not(.hidden)");
    assert(await page.evaluate(() => getComputedStyle(document.querySelector(".rail")).display !== "none"), "the Memory module opens Memory (with the rail back)");
    await page.click('.nav[data-view="core"]'); await page.waitForSelector("#view-core:not(.hidden)");
    await page.click('[aria-label="Navigation: all of CLU\'s views"]');
    assert(await page.evaluate(() => getComputedStyle(document.querySelector(".rail")).display !== "none"), "the navigation control brings the rail over the machine");
    await page.click('[aria-label="Navigation: all of CLU\'s views"]');
    await page.click('[aria-label="Stop CLU thinking or speaking"]');
    await page.waitForFunction(() => /stop requested/.test(document.querySelector("#view-core .console").textContent));
    assert(true, "the STOP sensor sends a real stop");
    const research0 = await page.evaluate(() => CluCore.state().research);
    await page.click('[aria-label="Web research on or off (Settings → Network)"]');
    await page.waitForFunction((r0) => CluCore.state().research === !r0, research0);
    assert(true, "the Research module toggles the real setting");

    // The composition is fixed: it scales as a whole (desktop, Pad 6) and pans on portrait phones; never restacks.
    for (const [w, h, name] of [[1440, 900, "desktop"], [1280, 800, "tablet"], [390, 844, "phone"]]) {
      await page.setViewportSize({ width: w, height: h }); await page.waitForTimeout(500);
      const g = await page.evaluate(() => { const r = document.querySelector(".core-stage").getBoundingClientRect(); const v = document.getElementById("view-core");
        return { ratio: r.width / r.height, w: r.width, h: r.height, pan: v.scrollWidth > v.clientWidth + 2 }; });
      assert(Math.abs(g.ratio - 2000 / 1244) < 0.02, `${w}x${h}: the reference's proportions are kept (${g.ratio.toFixed(3)})`);
      if (name === "phone") assert(g.pan && Math.abs(g.h - h) < 4, "phone: the machine fills the height and pans sideways (no stacking)");
      else assert(!g.pan && g.w <= w + 1 && g.h <= h + 1, `${name}: the whole machine is visible`);
      await page.screenshot({ path: path.join(out, `core-${name}.png`) });
    }
    assert(errors.length === 0, "no script errors: " + errors.join(" | "));
  } catch (e) {
    await page.screenshot({ path: path.join(out, "core-failure.png") }).catch(() => {});
    console.log(e.stack || e.message); console.log("errors:", errors.join(" | ")); process.exitCode = 1;
  } finally { await browser.close(); }
})();
