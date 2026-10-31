// Drives CLU's desktop shell in Chromium against a running host. Usage: node shell.ui.js <baseUrl> <launchCode> <screenshotDir>
// Started by ShellUiTest (which also checks the stores afterwards). Exits non-zero on the first failed check.
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, shots] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };

const problems = [];
(async () => {
  const browser = await chromium.launch();
  const watch = (page) => {
    page.on("pageerror", (e) => problems.push("page error: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") problems.push("console: " + m.text()); });
  };
  try {
    const ctx = await browser.newContext({ viewport: { width: 1100, height: 760 }, colorScheme: "dark" });

    // Without a launch code the window stays locked, even on the right address.
    const stranger = await ctx.newPage(); watch(stranger);
    await stranger.goto(base + "/");
    await stranger.waitForSelector("#locked:not(.hidden)");
    assert(await stranger.isHidden("#app"), "no launch code: locked screen, no app");
    await stranger.close();

    const page = await ctx.newPage(); watch(page);
    await page.goto(`${base}/#code=${code}`);
    await page.waitForSelector("#app:not(.hidden)");
    assert(!page.url().includes("code="), "launch code removed from the address after use");

    // Chat: streamed reply, face goes back to idle.
    await page.fill("#input", "Hello CLU");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => /Hello from CLU/.test(document.querySelector("#messages li.assistant:last-child .body")?.textContent || ""));
    await page.waitForFunction(() => document.querySelector("#presenceState").textContent === "Ready");
    assert(await page.isHidden("#stop"), "chat: streamed reply arrived; Stop hidden again");
    await page.waitForSelector("#conversationList li");
    assert(true, "chat: conversation listed");

    // Research: answer first, one collapsed source line, the list only on request.
    await page.fill("#input", "Who is Leman Russ?");
    await page.click("#send");
    await page.waitForSelector("#messages li.assistant:last-child .sources-toggle", { timeout: 20000 });
    const answer = await page.textContent("#messages li.assistant:last-child .body");
    assert(/Primarch of the Space Wolves/.test(answer) && !/wikipedia|https?:|\[1\]/i.test(answer), "research: answered from the evidence, no source talk: " + answer);
    assert((await page.textContent("#messages li.assistant:last-child .sources-toggle")).startsWith("Sources (1)"), "research: one collapsed sources line");
    assert(await page.isHidden("#messages li.assistant:last-child .sources"), "research: source list collapsed by default");
    await page.click("#messages li.assistant:last-child .sources-toggle");
    const href = await page.getAttribute("#messages li.assistant:last-child .sources a", "href");
    assert(href === "https://en.wikipedia.org/wiki/Leman_Russ", "research: source link shown on request: " + href);
    await page.screenshot({ path: path.join(shots, "m3-chat.png") });

    // Memory: add one with a historical date; live date reading; search finds it.
    await page.click('.nav[data-view="memory"]');
    await page.click("#memoryAdd");
    await page.fill('#memoryForm [name="title"]', "Met Riya at the college fest");
    await page.fill('#memoryForm [name="content"]', "We talked about robots all evening.");
    await page.fill('#memoryForm [name="when"]', "around March 2018");
    await page.waitForFunction(() => /Understood as: .*March 2018/.test(document.querySelector("#whenReading").textContent));
    assert(true, "memory: date read back as " + (await page.textContent("#whenReading")));
    await page.fill('#memoryForm [name="when"]', "the blue Tuesday of nothing");
    await page.waitForFunction(() => /Not understood/.test(document.querySelector("#whenReading").textContent));
    assert(true, "memory: unreadable date flagged before saving");
    await page.fill('#memoryForm [name="when"]', "around March 2018");
    await page.fill('#memoryForm [name="people"]', "Riya");
    await page.click('#memoryForm button[type="submit"]');
    await page.waitForFunction(() => /Met Riya at the college fest/.test(document.querySelector("#memoryList").textContent));
    await page.fill("#memorySearch", "college fest");
    await page.waitForFunction(() => document.querySelectorAll("#memoryList li.clickable").length === 1);
    assert(/2018/.test(await page.textContent("#memoryList li.clickable .sub")), "memory: saved, searchable, dated 2018");
    await page.screenshot({ path: path.join(shots, "m3-memory.png") });

    // Recall in chat: the memory reaches the model through the core.
    await page.click('.nav[data-view="chat"]');
    await page.fill("#input", "What do you remember about Riya?");
    await page.click("#send");
    await page.waitForFunction(() => document.querySelector("#presenceState").textContent === "Ready" && !document.querySelector("#send").disabled);
    const recall = await page.textContent("#messages li.assistant:last-child .body");
    assert(/Riya/.test(recall) && /robots all evening/.test(recall) && /March 2018/.test(recall), "recall: chat answer came from the saved memory: " + recall);

    // Timeline grouped by year.
    await page.click('.nav[data-view="timeline"]');
    await page.waitForSelector("#timeline .year");
    assert((await page.textContent("#timeline .year")) === "2018", "timeline: grouped under 2018");

    // Suggestions: badge, edit, approve.
    assert((await page.textContent("#suggestionCount")) === "1", "suggestions: badge shows 1 waiting");
    await page.click('.nav[data-view="suggestions"]');
    await page.waitForSelector("#suggestionList textarea");
    await page.fill("#suggestionList textarea", "Prefers green tea in the evening.");
    await page.click("#suggestionList button.primary");
    await page.waitForFunction(() => /No suggestions waiting/.test(document.querySelector("#suggestionList").textContent));
    await page.waitForFunction(() => document.querySelector("#suggestionCount").classList.contains("hidden"));
    assert(true, "suggestions: approved with an edit; badge cleared");

    // People.
    await page.click('.nav[data-view="people"]');
    await page.fill('#personForm [name="name"]', "Riya Sharma");
    await page.fill('#personForm [name="relation"]', "friend from college");
    await page.click('#personForm button[type="submit"]');
    await page.waitForFunction(() => /Riya Sharma/.test(document.querySelector("#peopleList").textContent));
    assert(true, "people: added");

    // Documents: import, then search inside.
    await page.click('.nav[data-view="documents"]');
    await page.setInputFiles("#docUpload", { name: "club-notes.txt", mimeType: "text/plain", buffer: Buffer.from("Robotics club minutes. We named the walking robot Hexapod.") });
    await page.waitForFunction(() => /club-notes.txt: imported/.test(document.querySelector("#docNotes").textContent));
    await page.fill("#docSearch", "Hexapod");
    await page.waitForFunction(() => /Hexapod/.test(document.querySelector("#docResults").textContent));
    assert(true, "documents: imported and searchable");

    // Status.
    await page.click('.nav[data-view="status"]');
    await page.waitForFunction(() => /MB of/.test(document.querySelector("#memoryBars").textContent));
    assert(true, "status: memory bars shown");
    await page.screenshot({ path: path.join(shots, "m3-status.png") });

    // Settings: host limits load; sensitive settings can't be changed here.
    await page.click('.nav[data-view="settings"]');
    await page.waitForFunction(() => document.querySelector('#hostForm [name="memoryBudgetGB"]').value === "16");
    await page.fill('#hostForm [name="memoryBudgetGB"]', "12");
    await page.click('#hostForm button[type="submit"]');
    await page.waitForFunction(() => /Saved/.test(document.querySelector("#hostSaved").textContent));
    const cats = await page.$$eval("#settingsCategory option", (os) => os.map((o) => o.value));
    let sensitiveDisabled = null;
    for (const c of cats) {
      await page.selectOption("#settingsCategory", c);
      const r = await page.$$eval("#settingsList .setting", (rows) => rows.filter((r) => r.textContent.includes("🔒")).map((r) => r.querySelector("input,select,textarea").disabled));
      if (r.length) { sensitiveDisabled = r.every(Boolean); break; }
    }
    assert(sensitiveDisabled === true, "settings: host limits saved; sensitive settings disabled in the window");
    await page.selectOption("#settingsCategory", cats[0]);
    await page.screenshot({ path: path.join(shots, "m3-settings.png") });

    assert(problems.length === 0, "no script errors or CSP violations: " + problems.join(" | "));
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error(e.message || e); problems.forEach((p) => console.error("  " + p)); process.exit(1); });
