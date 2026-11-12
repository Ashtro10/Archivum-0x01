// CLU's voice in the desktop window, in Chromium against a running host. Usage: node voice.ui.js <baseUrl> <launchCode> <screenshotDir> <micWav>
// The microphone is <micWav> (Chromium's fake capture device, played once). Started by VoiceUiTest. Exits non-zero on the first failed check.
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, shots, micWav] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };

const problems = []; let page = null;
(async () => {
  const browser = await chromium.launch({ args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", `--use-file-for-fake-audio-capture=${micWav}%noloop`, "--autoplay-policy=no-user-gesture-required"] });
  try {
    const ctx = await browser.newContext({ viewport: { width: 1100, height: 800 }, colorScheme: "dark" });
    page = await ctx.newPage();
    page.on("pageerror", (e) => problems.push("page error: " + e.message));
    page.on("console", (m) => { if (m.type() === "error" && !/status of 4\d\d/.test(m.text())) problems.push("console: " + m.text()); });
    const dialogs = [];
    page.on("dialog", (d) => { dialogs.push(d.message()); d.dismiss(); });
    await page.goto(`${base}/#code=${code}`);
    await page.waitForSelector("#app:not(.hidden)"); await page.evaluate(() => { const b = document.querySelector('.nav[data-view="chat"]'); if (b) b.click(); }) /* Core is the start view */;
    if (await page.isVisible("#wizard:not(.hidden)").catch(() => false)) await page.click("#wizSkip");
    await page.waitForSelector("#wizard.hidden", { state: "attached" });

    // ---- Settings → Voice
    await page.click('.nav[data-view="settings"]');
    await page.waitForSelector("#voiceGrid .voice-card");
    const cards = await page.$$eval("#voiceGrid .voice-card", (cs) => cs.map((c) => ({ id: c.dataset.voice, text: c.textContent, selected: c.classList.contains("selected") })));
    assert(cards.length >= 8, "voices listed: " + cards.map((c) => c.id).join(", "));
    assert(cards.find((c) => c.selected).id === "joe", "the deep, calm default voice (Joe) is selected on a fresh install");
    assert(cards.some((c) => /Not installed · \d+ MB download/.test(c.text)), "downloadable voices show their size");
    // A real preview: the host synthesises WAV with the selected engine.
    const wav = await page.evaluate(async () => { const r = await fetch("/v1/voice/voices/alan/preview", { method: "POST", headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token"), "Content-Type": "application/json" }, body: "{}" });
      const b = new Uint8Array(await r.arrayBuffer()); return { type: r.headers.get("Content-Type"), size: b.length, riff: String.fromCharCode(...b.slice(0, 4)) }; });
    assert(wav.type === "audio/wav" && wav.riff === "RIFF" && wav.size > 40000, `preview is real synthesised audio (${wav.size} bytes)`);
    await page.click('#voiceGrid .voice-card[data-voice="alan"] button:has-text("Preview")');
    await page.waitForFunction(() => !/Synthesising/.test(document.querySelector('#voiceGrid .voice-card[data-voice="alan"] button').textContent), null, { timeout: 15000 });
    assert(!/failed/i.test(await page.textContent("#vsMsg")), "the Preview button plays without error");
    // Download asks first (source, size, licence); declining downloads nothing.
    const notInstalled = cards.find((c) => /Not installed/.test(c.text));
    await page.click(`#voiceGrid .voice-card[data-voice="${notInstalled.id}"] button:has-text("Download")`);
    await page.waitForTimeout(300);
    assert(dialogs.length === 1 && /Size: \d+ MB/.test(dialogs[0]) && /From:/.test(dialogs[0]) && /Licence:/.test(dialogs[0]) && /fingerprint/.test(dialogs[0]), "download consent shows size, source, licence and the integrity check");
    await page.screenshot({ path: path.join(shots, "m8-voice-settings.png"), fullPage: true });
    // Choose Alan: saved on the host (shared with the phone).
    await page.click('#voiceGrid .voice-card[data-voice="alan"] button:has-text("Use this voice")');
    await page.waitForSelector('#voiceGrid .voice-card.selected[data-voice="alan"]');
    assert(/phone/.test(await page.textContent("#vsMsg")), "selection confirmed, and it applies to the phone too");
    // Settings that are not supported are said so (no fake pitch slider).
    assert(/no pitch control/.test(await page.textContent("#vsPitch")), "pitch is not offered because the engines cannot do it");

    // ---- Talk
    await page.click('.nav[data-view="chat"]');
    await page.click("#voiceBtn");
    await page.waitForFunction(() => /Listening/.test(document.querySelector("#voiceState").textContent), null, { timeout: 30000 });
    assert(await page.isVisible("#voicePrivacy"), "the microphone indicator is shown while CLU can hear");
    assert(/Alan/.test(await page.textContent("#voiceVoice")), "the bar shows the selected voice");
    // The fake microphone says the question after 3 s.
    await page.waitForFunction(() => [...document.querySelectorAll(".msg.user.voice .body")].some((b) => /introduce/i.test(b.textContent)), null, { timeout: 30000 });
    assert(true, "live transcript of what I said appeared in the chat");
    const sawState = await page.waitForFunction(() => /Thinking|Speaking/.test(document.querySelector("#voiceState").textContent), null, { timeout: 20000 }).then(() => true, () => false);
    assert(sawState, "CLU's state moved to thinking/speaking");
    await page.waitForFunction(() => [...document.querySelectorAll(".msg.assistant.voice:not(.partial) .body")].some((b) => /running on this computer/.test(b.textContent)), null, { timeout: 30000 });
    assert(true, "CLU's spoken reply appeared as text in the same chat");
    await page.waitForFunction(() => /reply began/.test(document.querySelector("#voiceMetrics").textContent), null, { timeout: 20000 });
    console.log("metrics: " + (await page.textContent("#voiceMetrics")));
    await page.screenshot({ path: path.join(shots, "m8-voice-chat.png") });
    // Mute: the indicator disappears; unmute brings it back.
    await page.click("#voiceMute"); await page.waitForSelector("#voicePrivacy.hidden", { state: "attached" });
    await page.click("#voiceMute"); await page.waitForSelector("#voicePrivacy:not(.hidden)");
    assert(true, "mute hides the microphone indicator, unmute shows it");
    await page.click("#voiceEnd");
    await page.waitForSelector("#voiceBar.hidden", { state: "attached" });
    const st = await page.evaluate(async () => (await (await fetch("/v1/voice/status", { headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token") } })).json()));
    assert(st.sessions.length === 0, "ending the session removes CLU from the room");
    // A typed follow-up in the same conversation sees the spoken turn.
    await page.fill("#input", "What did I ask you a moment ago?"); await page.click("#send");
    await page.waitForFunction(() => /introduce myself/.test(document.querySelector("#messages").textContent), null, { timeout: 20000 });
    assert(true, "typed and spoken turns share one conversation");
    assert(problems.length === 0, "no page errors: " + problems.join("; "));
  } catch (e) {
    console.log(e.stack || String(e)); if (page) await page.screenshot({ path: path.join(shots, "m8-voice-failure.png") }).catch(() => {});
    console.log("problems: " + problems.join("; "));
    process.exitCode = 1;
  } finally { await browser.close(); }
})();
