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
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "dark" })).newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/status of (4\d\d|503)/.test(m.text())) errors.push("console: " + m.text()); });
  const consoleText = () => page.textContent("#view-core .console");
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    await page.waitForSelector("#boot", { state: "detached", timeout: 8000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    assert(await page.isVisible("#view-core"), "the Core console is the start view");
    const parts = await page.evaluate(() => ["arc", "rings", "source", "hex", "files", "mods", "keys"].map((k) => !!document.querySelector("#view-core .core-" + k)));
    assert(parts.every(Boolean), "all seven modules present: " + parts);
    await page.waitForTimeout(900);
    assert(await page.evaluate(() => document.querySelectorAll("#view-core .core-conduits path.flow").length >= 7), "energy conduits join the modules");
    await page.waitForFunction(() => document.getElementById("arc-proc") && document.getElementById("arc-proc").textContent !== "…", null, { timeout: 8000 });
    const arc = await page.evaluate(() => ["proc", "sys", "infer", "records", "sugg", "tasks"].map((k) => document.getElementById("arc-" + k).textContent));
    console.log("  arc:", JSON.stringify(arc));
    assert(/MB/.test(arc[0]) && /GB/.test(arc[1]) && /running/.test(arc[2]), "the data matrix shows CLU's real figures (memory, system, inference queue)");
    assert(/NO SIGNAL/.test(await page.textContent("#view-core .wave-label")), "the waveform says NO SIGNAL when there is no audio (nothing simulated)");
    await page.screenshot({ path: path.join(out, "core-idle.png") });

    // Terminal: typed and on-screen keys; help; a question streams through the real turn path and drives the rings.
    await page.click("#coreCmd"); await page.keyboard.type("help"); await page.keyboard.press("Enter");
    assert(/open <view>/.test(await consoleText()), "the terminal runs commands (help)");
    for (const k of ["s", "t", "a", "t", "u", "s"]) await page.click(`#view-core .key[data-k="${k}"]`);
    assert((await page.inputValue("#coreCmd")) === "status", "the on-screen keyboard types into the command line");
    await page.click('#view-core .key[data-k="enter"]');
    await page.waitForFunction(() => /model: /.test(document.querySelector("#view-core .console").textContent));
    assert(true, "status runs from the keyboard and reports the real model state");
    await page.fill("#coreCmd", "bogus-command-xyz"); await page.keyboard.press("Enter");
    // an unknown word is a question for CLU: it goes to the conversation (never a fake success)
    const stagesSeen = new Set();
    for (let i = 0; i < 80; i++) { stagesSeen.add(await page.evaluate(() => document.getElementById("view-core").dataset.stage)); if (/answer complete/.test(await consoleText())) break; await page.waitForTimeout(100); }
    console.log("  stages:", [...stagesSeen].join(","));
    assert(stagesSeen.has("synthesis") || stagesSeen.has("output"), "the rings follow the real turn (input → … → output)");
    assert(/answer complete/.test(await consoleText()), "the console logs the real completion");
    assert(/Hello/.test(await page.textContent("#view-core .source-preview")), "the source container previews the real answer");
    await page.screenshot({ path: path.join(out, "core-answer.png") });
    await page.waitForFunction(() => document.getElementById("view-core").dataset.stage === "idle", null, { timeout: 8000 });
    assert(true, "back to idle after the answer");

    // Source window materialises and closes.
    await page.click("#view-core .core-source"); await page.waitForSelector("#view-core .core-window:not(.hidden)");
    await page.waitForTimeout(500); assert(/Last answer/.test(await page.textContent("#view-core .core-window")), "the intelligence container opens with the last answer, sources, suggestions");
    await page.screenshot({ path: path.join(out, "core-sources.png") });
    await page.click("#view-core .core-window .cbtn"); await page.waitForSelector("#view-core .core-window.hidden", { state: "attached" });

    // File explorer: a real upload into CLU's archive, then it appears in the list.
    const f = path.join(out, "core-note.txt"); fs.writeFileSync(f, "Project Gridline: the light-cycle arena opens on 12 March.\n");
    await page.setInputFiles("#view-core .core-files input[type=file]", f);
    assert(/core-note\.txt/.test(await page.textContent("#view-core .meta-grid")), "a picked file shows its metadata and preview");
    await page.click("#view-core .core-files .cbtn:text('Upload')");
    await page.waitForFunction(() => /synchronised: core-note\.txt/.test(document.querySelector("#view-core .console").textContent), null, { timeout: 15000 });
    await page.waitForFunction(() => /core-note\.txt/.test(document.querySelector("#view-core .file-list").textContent), null, { timeout: 5000 });
    assert(true, "uploaded into the archive and listed");

    // Modules switch real views; the stop sensor really asks CLU to stop.
    await page.click('#view-core .mod[aria-label="Memory and archive"]'); await page.waitForSelector("#view-memory:not(.hidden)");
    assert(true, "the Memory module opens Memory");
    await page.click('.nav[data-view="core"]'); await page.waitForSelector("#view-core:not(.hidden)");
    await page.click('#view-core .sensor[title="Stop CLU thinking or speaking"]');
    await page.waitForFunction(() => /stop requested/.test(document.querySelector("#view-core .console").textContent));
    assert(true, "the STOP sensor sends a real stop");
    const research0 = await page.evaluate(() => CluCore.state().research);
    await page.click('#view-core .mod[aria-label="Web research on or off (Settings → Network)"]');
    await page.waitForFunction((r0) => CluCore.state().research === !r0, research0);
    assert(true, "the Research module toggles the real setting");

    // Responsive: Pad 6 and phone keep every module.
    for (const [w, h, name] of [[1280, 800, "tablet"], [390, 844, "phone"]]) {
      await page.setViewportSize({ width: w, height: h }); await page.waitForTimeout(500);
      const vis = await page.evaluate(() => ["arc", "rings", "source", "hex", "files", "mods", "keys"].every((k) => { const r = document.querySelector("#view-core .core-" + k).getBoundingClientRect(); return r.width > 40 && r.height > 40; }));
      assert(vis, `all modules laid out at ${w}x${h}`);
      const overflow = await page.evaluate(() => document.getElementById("view-core").scrollWidth - document.getElementById("view-core").clientWidth);
      assert(overflow <= 2, `no sideways scrolling at ${w}x${h} (${overflow}px)`);
      await page.screenshot({ path: path.join(out, `core-${name}.png`) });
    }
    assert(errors.length === 0, "no script errors: " + errors.join(" | "));
  } catch (e) {
    await page.screenshot({ path: path.join(out, "core-failure.png") }).catch(() => {});
    console.log(e.stack || e.message); console.log("errors:", errors.join(" | ")); process.exitCode = 1;
  } finally { await browser.close(); }
})();
