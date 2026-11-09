// CLU 1.12 redesign in the real PC window (Chromium + software WebGL): start-up sequence, design, Markdown replies with
// code panels, streaming caret, entrance motion, interface sounds and their setting. Usage: node redesign.ui.js <baseUrl> <code> <outDir>
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, out] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };
(async () => {
  const browser = await chromium.launch({ args: ["--use-gl=swiftshader", "--enable-webgl", "--ignore-gpu-blocklist", "--autoplay-policy=no-user-gesture-required"] });
  const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 }, colorScheme: "dark" })).newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/status of 4\d\d/.test(m.text())) errors.push("console: " + m.text()); });
  try {
    await page.goto(`${base}/#code=${code}`); await page.waitForSelector("#app:not(.hidden)");
    // Start-up: the boot log shows CLU's real state, then the name; it never takes clicks and ends by itself.
    await page.waitForSelector("#boot", { timeout: 5000 });
    assert(await page.$eval("#boot", (b) => getComputedStyle(b).pointerEvents === "none"), "start-up sequence never takes clicks");
    await page.waitForFunction(() => /core \.+ online/.test(document.querySelector(".boot-log")?.textContent || ""), null, { timeout: 3000 });
    await page.screenshot({ path: path.join(out, "pc-boot-log.png") });
    await page.waitForSelector(".boot-title.on", { timeout: 3000 }); await page.waitForTimeout(120);
    await page.screenshot({ path: path.join(out, "pc-boot-title.png") });
    await page.waitForSelector("#boot", { state: "detached", timeout: 5000 });
    assert(true, "start-up sequence ends by itself");
    const played = () => page.evaluate(() => CluFx.played.slice());
    assert((await played()).includes("startup"), "the start-up sound played: " + (await played()).join(","));
    await page.waitForSelector("#wizard:not(.hidden)", { timeout: 4000 }).catch(() => {});
    for (let i = 0; i < 20 && await page.isVisible("#wizard:not(.hidden)"); i++) { await page.click("#wizSkip").catch(() => {}); await page.waitForTimeout(250); }
    // Design: chamfered panels, the numbered rail, the CLU // title.
    const look = await page.evaluate(() => ({ cut: getComputedStyle(document.querySelector("#composer")).clipPath, bg: getComputedStyle(document.body).backgroundColor,
      nav: getComputedStyle(document.querySelector(".nav"), "::before").content, title: getComputedStyle(document.querySelector(".topbar h2"), "::before").content }));
    console.log("  look:", JSON.stringify(look));
    assert(/polygon/.test(look.cut) && look.bg === "rgb(2, 4, 6)" && /counter\(nav/.test(look.nav) && /CLU \/\//.test(look.title), "TRON design: chamfered panels, near-black, numbered rail, CLU // titles");
    await page.waitForTimeout(1500); await page.screenshot({ path: path.join(out, "pc-chat-empty.png") });
    // A reply in Markdown with a code block: streamed with a caret, then rendered with a Copy control; the send sound played.
    let sawCaret = false;
    await page.fill("#input", "Show me a Kotlin loop"); await page.click("#send");
    for (let i = 0; i < 100 && !sawCaret; i++) { sawCaret = await page.$("#messages li.assistant:last-child .caret").then(Boolean); if (!sawCaret) await page.waitForTimeout(40); }
    assert(sawCaret, "the streaming reply shows a caret");
    const thinkingSeen = await page.evaluate(() => document.body.dataset.clu);
    await page.waitForFunction(() => !document.querySelector("#send").disabled, null, { timeout: 60000 });
    const reply = await page.evaluate(() => { const li = document.querySelector("#messages li.assistant:last-child");
      return { strong: li.querySelector(".body strong")?.textContent, code: li.querySelector(".codeblock code")?.textContent, lang: li.querySelector(".code-head span")?.textContent,
        copy: !!li.querySelector(".code-copy"), caret: !!li.querySelector(".caret"), items: li.querySelectorAll(".md-li").length, enter: li.classList.contains("enter"), raw: li.querySelector(".body").textContent }; });
    console.log("  reply:", JSON.stringify(reply), "state mid-turn:", thinkingSeen);
    assert(reply.strong === "for" && /for \(i in 1\.\.3\)/.test(reply.code) && reply.lang === "kotlin" && reply.copy && reply.items === 2 && !reply.caret,
      "the reply renders Markdown: bold, list, a kotlin code panel with Copy, no caret once done");
    assert(reply.enter, "new messages rise in");
    assert(!/\*\*|```/.test(reply.raw), "no raw Markdown markers are shown");
    assert((await played()).includes("process"), "the send sound played");
    await page.screenshot({ path: path.join(out, "pc-chat-markdown.png") });
    // Section change: the activate sound and the view rising in.
    await page.click('.nav[data-view="settings"]'); await page.waitForTimeout(200);
    assert((await played()).includes("activate") && await page.$eval("#view-settings", (v) => v.classList.contains("fx-rise")), "changing section sounds and rises in");
    await page.waitForTimeout(700); await page.screenshot({ path: path.join(out, "pc-settings.png") });
    // Settings → General: Interface sounds off silences everything; reduced motion skips animations.
    await page.selectOption("#settingsCategory", "GENERAL").catch(async () => { const v = await page.$$eval("#settingsCategory option", (o) => o.map((x) => x.value)); await page.selectOption("#settingsCategory", v.find((x) => /general/i.test(x))); });
    await page.waitForTimeout(200);
    const row = page.locator(".setting", { hasText: "Interface sounds" });
    assert(await row.count() === 1, "the Interface sounds setting is in Settings");
    await row.locator("input[type=checkbox]").uncheck(); await page.waitForTimeout(300);
    const before = (await played()).length; await page.click('.nav[data-view="memory"]'); await page.waitForTimeout(150);
    assert((await played()).length === before, "sounds off: switching sections is silent");
    await page.click('.nav[data-view="settings"]'); await page.waitForTimeout(150);
    await page.locator(".setting", { hasText: "Interface motion" }).locator("select").selectOption("reduced"); await page.waitForTimeout(200);
    assert(await page.evaluate(() => document.body.classList.contains("fx-reduced") && CluFx.reduced()), "reduced motion takes effect at once");
    // Voice conversation open: interface sounds stay silent even when on.
    await page.locator(".setting", { hasText: "Interface sounds" }).locator("input[type=checkbox]").check(); await page.waitForTimeout(200);
    const silent = await page.evaluate(() => { document.getElementById("voiceBar").classList.remove("hidden"); const r = CluFx.sound("click"); document.getElementById("voiceBar").classList.add("hidden"); return r; });
    assert(silent === false, "no interface sounds while a voice conversation is open");
    await page.locator(".setting", { hasText: "Interface motion" }).locator("select").selectOption("full");
    // Markdown safety: no HTML is ever interpreted, javascript: links never become links.
    const safe = await page.evaluate(() => { const b = document.createElement("div"); CluFx.md(b, "<img src=x onerror=alert(1)> [x](javascript:alert(1)) **ok**"); return { img: !!b.querySelector("img"), a: !!b.querySelector("a"), text: b.textContent }; });
    assert(!safe.img && !safe.a && /<img/.test(safe.text), "Markdown never interprets HTML or javascript: links");
    assert(errors.length === 0, "no page errors: " + errors.join(" | "));
  } catch (e) { console.error(String(e.stack || e)); await page.screenshot({ path: path.join(out, "pc-failure.png") }).catch(() => {}); await browser.close(); process.exit(1); }
  await browser.close();
})();
