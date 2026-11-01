// Drives CLU's desktop shell in Chromium against a running host. Usage: node shell.ui.js <baseUrl> <launchCode> <screenshotDir>
// Started by ShellUiTest (which also checks the stores afterwards). Exits non-zero on the first failed check.
"use strict";
const { chromium } = require("playwright");
const path = require("path");
const [base, code, shots, tinyModel, workspaceDir, dataFolder, readOnlyFolder] = process.argv.slice(2);
const assert = (ok, what) => { if (!ok) throw new Error("FAILED: " + what); console.log("ok - " + what); };

const problems = []; let lastPage = null;
(async () => {
  const browser = await chromium.launch();
  const watch = (page) => {
    page.on("pageerror", (e) => problems.push("page error: " + e.message));
    page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource: the server responded with a status of 4\d\d/.test(m.text())) problems.push("console: " + m.text()); }); // 4xx = a refusal this script provoked on purpose
  };
  try {
    const ctx = await browser.newContext({ viewport: { width: 1100, height: 760 }, colorScheme: "dark" });

    // Without a launch code the window stays locked, even on the right address.
    const stranger = await ctx.newPage(); watch(stranger);
    await stranger.goto(base + "/");
    await stranger.waitForSelector("#locked:not(.hidden)");
    assert(await stranger.isHidden("#app"), "no launch code: locked screen, no app");
    await stranger.close();

    const page = await ctx.newPage(); watch(page); lastPage = page;
    await page.goto(`${base}/#code=${code}`);
    await page.waitForSelector("#app:not(.hidden)");
    assert(!page.url().includes("code="), "launch code removed from the address after use");

    // ---- M6: the first-run guide appears on a fresh install and the app is usable without finishing it.
    await page.waitForSelector("#wizard:not(.hidden)");
    assert(/Welcome/.test(await page.textContent("#wizTitle")), "wizard: shown on first run");
    await page.click("#wizNext");
    await page.waitForFunction(() => /Your PC/.test(document.querySelector("#wizTitle").textContent));
    const checks = await page.$$eval("#wizBody .check", (rs) => rs.map((r) => r.textContent));
    assert(checks.length >= 5 && checks.some((c) => /Memory/.test(c)) && checks.some((c) => /Storage/.test(c)) && checks.some((c) => /Graphics/.test(c)) && checks.some((c) => /Inference engine/.test(c)), "wizard: compatibility checks for memory, storage, engine and graphics are read from the PC: " + checks.length);
    await page.click("#wizNext");
    await page.waitForFunction(() => /Model/.test(document.querySelector("#wizTitle").textContent));
    const offers = await page.$$eval("#wizBody .choice", (rs) => rs.map((r) => r.textContent));
    assert(offers.length === 3 && offers.every((t) => /download/i.test(t) && /memory/i.test(t)), "wizard: suggested models show size and memory needs");
    await page.click("#wizBody .choice button:has-text('Download')");
    await page.waitForSelector("#dlDialog:not(.hidden)");
    const consent = await page.textContent("#dlBody");
    assert(/From/.test(consent) && /huggingface\.co/.test(consent) && /Licence/.test(consent) && /Size/.test(consent) && /Saved to/.test(consent) && /does not change your selected model/.test(consent), "wizard: the download asks first, with source, size, licence and destination");
    await page.screenshot({ path: path.join(shots, "m6-download-consent.png") });
    await page.click("#dlCancel");
    const none = await page.evaluate(async (t) => (await (await fetch("/v1/setup/downloads", { headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token") } })).json()).length, null);
    assert(none === 0, "wizard: declining the download downloads nothing");
    await page.fill('#wizBody form input', tinyModel.replace(/\.gguf$/, "-gone.gguf")); await page.click('#wizBody form button');
    await page.waitForFunction(() => /no such file/i.test(document.querySelector("#wizBody").textContent));
    assert(true, "wizard: a wrong file path is refused with the reason");
    await page.fill('#wizBody form input', tinyModel); await page.click('#wizBody form button');
    await page.waitForFunction(() => document.querySelector("#wizBody input[type=radio]:checked"), null, { timeout: 20000 });
    assert(true, "wizard: a model file the owner points to is registered and selected");
    await page.screenshot({ path: path.join(shots, "m6-wizard-model.png") });
    await page.click("#wizNext");
    await page.waitForFunction(() => /Settings/.test(document.querySelector("#wizTitle").textContent));
    assert(!(await page.isChecked('#wizBody input[type=checkbox] >> nth=1')), "wizard: start with Windows is off by default");
    await page.click("#wizNext");
    await page.waitForFunction(() => /Assistant tasks/.test(document.querySelector("#wizTitle").textContent));
    const taskStep = await page.textContent("#wizBody");
    assert(/Safe/.test(taskStep) && /approval/.test(taskStep) && /confirmation/.test(taskStep) && /only work inside this one folder/.test(taskStep), "wizard: the permission step explains the three kinds of action and the one folder");
    assert((await page.inputValue('#wizBody input[aria-label="Task folder"]')).endsWith("workspace"), "wizard: the task folder is the one given by this PC's setup");
    await page.screenshot({ path: path.join(shots, "m7-wizard-permissions.png") });
    await page.click("#wizNext");
    await page.waitForFunction(() => /Try it/.test(document.querySelector("#wizTitle").textContent));
    await page.click("#wizBody button.primary");
    await page.waitForFunction(() => /First word after/.test(document.querySelector("#wizBody").textContent), null, { timeout: 90000 });
    assert(/Engine/.test(await page.textContent("#wizBody")) && /tokens\/s/.test(await page.textContent("#wizBody")), "wizard: a real test generation reports the engine, load time and speed");
    await page.click("#wizNext");
    await page.waitForFunction(() => /Ready/.test(document.querySelector("#wizTitle").textContent));
    await page.screenshot({ path: path.join(shots, "m6-wizard-ready.png") });
    await page.click("#wizNext");
    await page.waitForSelector("#wizard", { state: "hidden" });
    assert(await page.evaluate(async () => (await (await fetch("/v1/setup/status", { headers: { Authorization: "Bearer " + sessionStorage.getItem("clu-token") } })).json()).completed), "wizard: finishing records that setup is done");
    // the wizard registered the tiny model; take it away again so the Models tests below start from nothing, as before
    await page.evaluate(async () => { const h = { Authorization: "Bearer " + sessionStorage.getItem("clu-token"), "Content-Type": "application/json" }; const ms = (await (await fetch("/v1/models", { headers: h })).json()).models; for (const m of ms) await fetch("/v1/models/" + m.id, { method: "DELETE", headers: h }); });

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

    // ---- M4: the memory screens
    await page.click('.nav[data-view="memory"]');
    await page.waitForSelector("#kindChips .chip");
    const chips = await page.$$eval("#kindChips .chip", (b) => b.map((x) => x.textContent));
    assert(chips.some((c) => /Core identity · \d+/.test(c)) && chips.some((c) => /Semantic · 1/.test(c)) && chips.some((c) => /Working · 1/.test(c)), "memory: kinds with counts: " + chips.join(" | "));
    await page.fill("#memorySearch", "");
    await page.click('#kindChips .chip:has-text("Semantic")');
    await page.waitForFunction(() => document.querySelectorAll("#memoryList li.clickable").length === 1 && /PID control/.test(document.querySelector("#memoryList").textContent));
    assert(true, "memory: the kind filter narrows to the one semantic memory");
    await page.click('#kindChips .chip:has-text("All")');
    await page.fill("#memoryPerson", "Riya");
    await page.waitForFunction(() => /Met Riya at the college fest/.test(document.querySelector("#memoryList").textContent) && document.querySelectorAll("#memoryList li.clickable").length === 1);
    assert(true, "memory: person filter");
    await page.fill("#memoryPerson", "");
    await page.fill("#memoryFrom", "2018-03-01"); await page.fill("#memoryTo", "2018-03-31");
    await page.waitForFunction(() => /Met Riya/.test(document.querySelector("#memoryList").textContent) && !/PID control/.test(document.querySelector("#memoryList").textContent));
    assert(true, "memory: time filter (March 2018)");
    await page.fill("#memoryFrom", ""); await page.fill("#memoryTo", "");
    await page.selectOption("#memoryCategory", "KNOWLEDGE");
    await page.waitForFunction(() => document.querySelectorAll("#memoryList li.clickable").length === 1);
    await page.selectOption("#memoryCategory", "");

    // Detail: where it came from, when, links.
    await page.fill("#memorySearch", "college fest");
    await page.waitForFunction(() => { const l = document.querySelectorAll("#memoryList li.clickable"); return l.length === 1 && /college fest/i.test(l[0].textContent); }); // the list for THIS search, not the previous one
    await page.click("#memoryList li.clickable");
    await page.waitForSelector("#memoryDetail:not(.hidden) #detailMeta dt");
    const meta = await page.textContent("#detailMeta");
    assert(/Kind.*Episodic/.test(meta) && /Created/.test(meta) && /Last changed/.test(meta) && /Entered by you in the desktop window/.test(meta) && /Last used by CLU/.test(meta), "memory detail: kind, created, changed, last used, source: " + meta.replace(/\s+/g, " "));
    await page.fill("#linkSearch", "PID");
    await page.click('#linkResults li.clickable:has-text("PID control")');
    await page.waitForFunction(() => /PID control/.test(document.querySelector("#relatedList").textContent));
    assert(/Related to/.test(await page.textContent("#relatedList")), "memory detail: link added and shown");
    await page.screenshot({ path: path.join(shots, "m4-memory-detail.png") });
    await page.click("#relatedList li button.x");
    await page.waitForFunction(() => /Nothing linked yet/.test(document.querySelector("#relatedList").textContent));
    assert(true, "memory detail: link removed");
    await page.click("#memoryCancel"); await page.fill("#memorySearch", "");

    // Documents beside memories: source code is indexed and comes back as a document.
    await page.click('.nav[data-view="documents"]');
    await page.setInputFiles("#docUpload", { name: "gait.kt", mimeType: "text/plain", buffer: Buffer.from("fun tripodGait(phase: Int) = List(6) { (it + phase) % 2 == 0 } // tripod leg schedule") });
    await page.waitForFunction(() => /gait.kt: imported/.test(document.querySelector("#docNotes").textContent));
    await page.click('.nav[data-view="memory"]');
    await page.fill("#memorySearch", "tripod");
    await page.waitForFunction(() => /Document/.test(document.querySelector("#memoryList").textContent) && /tripodGait/.test(document.querySelector("#memoryList").textContent));
    assert(true, "documents: source code found through memory search, labelled as a document");
    await page.fill("#memorySearch", "");

    // Working notes.
    await page.click('.sub[data-sub="working"]');
    await page.waitForFunction(() => /Parked on level 3/.test(document.querySelector("#workingList").textContent));
    await page.fill('#workingForm [name="text"]', "Call the clinic at five"); await page.click('#workingForm button[type="submit"]');
    await page.waitForFunction(() => /Call the clinic at five/.test(document.querySelector("#workingList").textContent));
    await page.click('#workingList li:has-text("Parked on level 3") button:has-text("Dismiss")');
    await page.waitForFunction(() => !/Parked on level 3/.test(document.querySelector("#workingList").textContent));
    assert(true, "working notes: listed, added, dismissed");

    // Tidy-up: a near-duplicate pair waits for the owner; merging keeps both texts.
    await page.click('.sub[data-sub="tidy"]');
    await page.waitForFunction(() => document.querySelectorAll("#tidyPending li .pair").length === 1);
    assert(/Chose STM32/.test(await page.textContent("#tidyPending")), "tidy-up: the near-duplicates are shown side by side");
    await page.screenshot({ path: path.join(shots, "m4-tidy.png") });
    await page.click('#tidyPending button:has-text("Merge")');
    await page.waitForFunction(() => /Nothing waiting/.test(document.querySelector("#tidyPending").textContent) && /approved/.test(document.querySelector("#tidyActivity").textContent));
    await page.click("#tidyRun"); await page.waitForFunction(() => /Checked \d+/.test(document.querySelector("#tidyResult").textContent));
    assert(true, "tidy-up: merged on approval, logged in the activity list; manual run works");

    // Learning: a question about a recurring preference; the reaction menu on replies only records.
    await page.click('.sub[data-sub="learning"]');
    await page.waitForFunction(() => /Make it my standing preference\?/.test(document.querySelector("#learnQuestions").textContent));
    assert(/Keeps coming up/.test(await page.textContent("#learnNoticed")), "learning: noticed list shows the scope");
    await page.screenshot({ path: path.join(shots, "m4-learning.png") });
    await page.click('#learnQuestions button:has-text("Yes")');
    await page.waitForFunction(() => /No questions/.test(document.querySelector("#learnQuestions").textContent) && /Standing preference/.test(document.querySelector("#learnNoticed").textContent));
    assert(true, "learning: accepted by the owner, now a standing preference");
    await page.click('.nav[data-view="chat"]');
    await page.selectOption("#messages li.assistant:last-child select.feedback", "too_long");
    await page.waitForFunction(() => /won't change anything on one reaction|keeps coming up/.test(document.querySelector("#messages li.assistant:last-child .meta").textContent));
    assert(true, "learning: a reaction on a reply is recorded without changing anything");

    // Health and backups.
    await page.click('.nav[data-view="memory"]'); await page.click('.sub[data-sub="health"]');
    await page.waitForFunction(() => /Database integrity: ok/.test(document.querySelector("#healthSummary").textContent));
    await page.click("#backupNow");
    await page.waitForFunction(() => document.querySelectorAll("#backupList li:not(.muted)").length >= 1 && /Backup made/.test(document.querySelector("#healthNotes").textContent));
    await page.click("#maintRun"); await page.waitForFunction(() => document.querySelector("#maintResult").textContent.length > 3 && !/Running/.test(document.querySelector("#maintResult").textContent));
    assert(/--restore-backup/.test(await page.textContent("#backupRestore")), "health: backups listed, restore instructions shown, background run works");
    await page.screenshot({ path: path.join(shots, "m4-health.png") });

    // Timeline grouped by year.
    await page.click('.nav[data-view="timeline"]');
    await page.waitForSelector("#timeline .year");
    assert((await page.textContent("#timeline .year")) === "2018", "timeline: grouped under 2018");

    // Suggestions: an inference and a conflicting statement; evidence is shown and only approval saves.
    await page.click('.nav[data-view="suggestions"]');
    await page.waitForFunction(() => document.querySelectorAll("#suggestionList li:has(textarea)").length === 2);
    assert((await page.textContent("#suggestionCount")) === "2", "suggestions: badge shows 2 waiting");
    const conflict = page.locator('#suggestionList li:has-text("May conflict")');
    assert(/Vim/.test(await conflict.textContent()) && /both stay/.test(await conflict.textContent()), "suggestions: the conflict with the existing memory is named");
    assert(/Inferred by CLU/.test(await page.textContent('#suggestionList li:has(.tag.warn)')), "suggestions: inferences are labelled as such");
    await page.screenshot({ path: path.join(shots, "m4-suggestions.png") });
    const inferred = page.locator("#suggestionList li:has(.tag.warn)");
    await inferred.locator("textarea").fill("Prefers green tea in the evening.");
    await inferred.locator("button.primary").click();
    await page.waitForFunction(() => document.querySelectorAll("#suggestionList li:has(textarea)").length === 1);
    await conflict.locator("button.primary").click();
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


    // ---- M7: assistant tasks, approvals, permissions, activity
    page.on("dialog", (d) => d.accept());
    const tok = () => page.evaluate(() => sessionStorage.getItem("clu-token"));
    await page.click('.nav[data-view="agents"]');
    await page.waitForSelector("#roleList li", { state: "attached" }).catch(() => {});
    await page.click('.asub[data-asub="agents"]');
    await page.waitForFunction(() => document.querySelectorAll("#roleList li").length === 5 && document.querySelectorAll("#toolList li").length >= 10);
    const roles = await page.$$eval("#roleList li", (l) => l.map((x) => x.textContent));
    assert(["Planner", "Research", "Knowledge", "Computer", "Verifier"].every((r) => roles.some((x) => x.includes(r))), "agents: the five roles are listed");
    const tools = await page.$$eval("#toolList li", (l) => l.map((x) => x.textContent));
    assert(tools.some((t) => /fs\.write/.test(t) && /Needs approval/.test(t)) && tools.some((t) => /fs\.delete/.test(t) && /confirmation/i.test(t)) && tools.some((t) => /proc\.run/.test(t) && /Switched off/.test(t)), "agents: tools show their risk category, and running programs is off");
    await page.screenshot({ path: path.join(shots, "m7-agents.png") });
    await page.click('.asub[data-asub="tasks"]');

    // A task of explicit steps: creating a file needs approval; nothing is written before it.
    const runSteps = async (goal, steps) => { await page.evaluate(() => { document.querySelector("#taskForm details").open = true; }); await page.fill("#taskGoal", goal); await page.fill("#taskSteps", JSON.stringify(steps)); await page.click("#taskRunSteps"); };
    await runSteps("Write a greeting file", [{ id: "s1", tool: "fs.write", args: { path: "hello.txt", content: "hi from CLU", mode: "create" } }]);
    await page.waitForSelector("#approvalBox .approval");
    const card = await page.textContent("#approvalBox .approval");
    assert(/hello\.txt/.test(card) && /Needs approval/.test(card) && /Can it be undone\?/.test(card) && /If it works/.test(card) && /If it fails/.test(card) && /Write a greeting file/.test(card), "approval card: intent, task, tool, what it affects, reversibility, outcomes");
    await page.waitForFunction(() => !document.querySelector("#approvalCount").classList.contains("hidden") && document.querySelector("#approvalCount").textContent === "1");
    assert(!require("fs").existsSync(path.join(workspaceDir, "hello.txt")), "approval card: while it is open the file does not exist");
    await page.screenshot({ path: path.join(shots, "m7-approval.png") });
    assert(await page.evaluate(async (tk) => (await (await fetch("/v1/agents/tasks", { headers: { Authorization: "Bearer " + tk } })).json())[0].status, await tok()) === "WAITING_APPROVAL", "approval: the task is waiting for the owner, nothing was written yet");
    await page.click("#approvalBox .approval button:has-text('Approve this once')");
    await page.waitForFunction(() => /Done/.test(document.querySelector("#taskList li .title")?.textContent || ""), null, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelector("#approvalBox").children.length === 0);
    await page.click("#taskList li");
    await page.waitForFunction(() => /Wrote hello\.txt/.test(document.querySelector("#taskDetail")?.textContent || "") || /1 of 1/.test(document.querySelector("#taskList").textContent));
    assert(true, "approval: approved once, the task finished and the card went away");

    // Rejecting: the step is refused and the task says so.
    await runSteps("Make a folder", [{ id: "s1", tool: "fs.mkdir", args: { path: "rejected-dir" } }]);
    await page.waitForSelector("#approvalBox .approval"); await page.click("#approvalBox .approval button:has-text('Reject')");
    await page.waitForFunction(() => /Failed/.test(document.querySelector("#taskList li .title")?.textContent || ""), null, { timeout: 20000 });
    await page.click("#taskList li"); await page.waitForFunction(() => /You rejected this action/.test(document.querySelector("#taskDetail").textContent));
    assert(true, "reject: the task shows the action was refused and nothing was created");

    // Category C: confirmation needs the explicit tick and is for this one action.
    await runSteps("Remove the greeting", [{ id: "s1", tool: "fs.delete", args: { path: "hello.txt" } }]);
    await page.waitForSelector("#approvalBox .approval.C");
    assert(/needs your confirmation/i.test(await page.textContent("#approvalBox .approval.C h3")) && /moved to the \.clu-trash folder/.test(await page.textContent("#approvalBox .approval.C")), "confirmation card: says what will happen to the file");
    assert(await page.isDisabled("#approvalBox .approval.C button:has-text('Confirm')"), "confirmation: the button stays disabled until the box is ticked");
    assert((await page.$$("#approvalBox .approval.C button:has-text('Approve for this task')")).length === 0, "confirmation: there is no 'for this task' shortcut for a delete");
    await page.screenshot({ path: path.join(shots, "m7-confirmation.png") });
    await page.check("#approvalBox .approval.C input[type=checkbox]"); await page.click("#approvalBox .approval.C button:has-text('Confirm')");
    await page.waitForFunction(() => /Done/.test(document.querySelector("#taskList li .title")?.textContent || ""), null, { timeout: 20000 });
    assert(true, "confirmation: confirmed once, the file was moved to the trash folder (checked on disk afterwards)");

    // A goal that is planned by the model: the plan is shown, nothing runs until it is started.
    await page.fill("#taskGoal", "Tell me what is in my workspace"); await page.click("#taskPlan");
    await page.waitForFunction(() => /Start \(ask me before each change\)/.test(document.querySelector("#taskDetail")?.textContent || ""), null, { timeout: 20000 });
    const plan = await page.textContent("#taskDetail");
    assert(/List the workspace/.test(plan) && /Nothing has been done yet/.test(plan) && /Knowledge/.test(plan) && /Computer/.test(plan), "planned task: the plan with its roles is shown before anything is done");
    await page.screenshot({ path: path.join(shots, "m7-plan.png") });
    await page.click("#taskDetail button:has-text('Start (ask me before each change)')");
    await page.waitForFunction(() => /The workspace holds/.test(document.querySelector("#taskDetail")?.textContent || ""), null, { timeout: 30000 });
    assert(true, "planned task: started by the owner, steps ran in order, the result is shown");

    // Text read by a task cannot give it orders: the warning is shown, no approval is requested, nothing is deleted.
    await runSteps("Summarise evil.txt", [{ id: "s1", tool: "fs.read", args: { path: "evil.txt" } }, { id: "s2", reason: "research", instruction: "Summarise the file", after: ["s1"] }]);
    await page.waitForFunction(() => /Done|Partly done|Failed/.test(document.querySelector("#taskList li .title")?.textContent || ""), null, { timeout: 30000 });
    await page.click("#taskList li"); await page.waitForFunction(() => /Careful: text this step read/.test(document.querySelector("#taskDetail")?.textContent || ""));
    assert((await page.$$("#approvalBox .approval")).length === 0, "injection: no approval was requested by what the file said");
    await page.screenshot({ path: path.join(shots, "m7-injection.png") });

    // Cancelling a task that waits for approval withdraws the request.
    await runSteps("Create a folder then cancel", [{ id: "s1", tool: "fs.mkdir", args: { path: "never-made" } }]);
    await page.waitForSelector("#approvalBox .approval"); await page.click("#taskList li"); await page.click("#taskDetail button:has-text('Cancel task')");
    await page.waitForFunction(() => document.querySelector("#approvalBox").children.length === 0 && /Cancelled/.test(document.querySelector("#taskList li .title")?.textContent || ""));
    assert(true, "cancel: the waiting request was withdrawn and the task is cancelled");

    // /task in chat.
    await page.click('.nav[data-view="chat"]'); await page.fill("#input", "/task list my workspace"); await page.keyboard.press("Enter");
    await page.waitForSelector("#view-agents:not(.hidden)"); 
    assert(true, "chat: /task <goal> starts a task and takes the owner to Agents");
    await page.waitForFunction(() => /list my workspace/.test(document.querySelector("#taskList li:first-child")?.textContent || ""), null, { timeout: 20000 });
    await page.click("#taskList li:first-child");
    await page.waitForFunction(() => /Start \(ask me before each change\)/.test(document.querySelector("#taskDetail")?.textContent || ""), null, { timeout: 20000 });
    assert(/list my workspace/.test(await page.textContent("#taskDetail")), "chat: the planned task is the one typed in chat, waiting to be started");
    await page.click("#taskDetail button:has-text('Cancel task')"); await page.waitForFunction(() => /Cancelled/.test(document.querySelector("#taskList li:first-child .title").textContent));

    // Permissions.
    await page.click('.asub[data-asub="permissions"]');
    await page.waitForFunction(() => /workspace/.test(document.querySelector("#rootList").textContent));
    assert(/may read and change files/.test(await page.textContent("#rootList")), "permissions: the allowed folder is shown with what tasks may do in it");
    await page.fill('#rootForm [name="path"]', dataFolder); await page.click('#rootForm button[type="submit"]');
    await page.waitForFunction(() => /own data/.test(document.querySelector("#rootMsg").textContent));
    assert(true, "permissions: CLU's own data folder cannot be allowed");
    await page.fill('#rootForm [name="path"]', "relative/folder"); await page.click('#rootForm button[type="submit"]');
    await page.waitForFunction(() => /full path/i.test(document.querySelector("#rootMsg").textContent));
    await page.fill('#rootForm [name="path"]', readOnlyFolder); await page.click('#rootForm button[type="submit"]');
    await page.waitForFunction((p3) => document.querySelector("#rootList").textContent.includes("only read"), null);
    assert(true, "permissions: a read-only folder was added");
    await page.evaluate(() => { document.querySelector("#standingForm").closest("details").open = true; });
    await page.waitForFunction(() => document.querySelector('#standingForm [name="tool"] option[value="fs.mkdir"]'));
    await page.selectOption('#standingForm [name="tool"]', "fs.mkdir"); await page.fill('#standingForm [name="resource"]', workspaceDir + "/"); await page.click('#standingForm button[type="submit"]');
    await page.waitForFunction(() => /fs\.mkdir/.test(document.querySelector("#grantList").textContent));
    assert(/until/.test(await page.textContent("#grantList")), "permissions: a standing permission is listed with its end");
    await page.screenshot({ path: path.join(shots, "m7-permissions.png") });
    await page.click("#grantList button:has-text('Revoke')"); await page.waitForFunction(() => /Nothing is currently approved/.test(document.querySelector("#grantList").textContent));
    assert(true, "permissions: revoked at once");
    await page.click("#rootList li:has-text('only read') button"); await page.waitForFunction(() => !/only read/.test(document.querySelector("#rootList").textContent));
    assert(/Earlier requests/.test(await page.textContent("#asub-permissions")) && (await page.$$("#approvalHistory li")).length > 2, "permissions: the history of requests is kept");

    // Activity.
    await page.click('.asub[data-asub="activity"]'); await page.waitForFunction(() => document.querySelectorAll("#auditList li").length > 10);
    for (const [kind, expect] of [["security", ["security.untrusted-content-flagged"]], ["grant", ["grant.created", "grant.revoked"]], ["approval", ["approval.requested", "approval.approved", "approval.rejected"]], ["permission", ["permission.allowed"]]]) {
      await page.selectOption("#auditKind", kind);
      await page.waitForFunction((k) => document.querySelectorAll("#auditList .k").length > 0 && [...document.querySelectorAll("#auditList .k")].every((x) => x.textContent.startsWith(k)), kind);
      const seen = await page.$$eval("#auditList .k", (l) => l.map((x) => x.textContent));
      assert(expect.every((e) => seen.includes(e)), `activity: "${kind}" shows ${expect.join(", ")} (saw ${[...new Set(seen)].join(", ")})`);
    }
    await page.click("#auditVerify"); await page.waitForFunction(() => /Intact/.test(document.querySelector("#auditMsg").textContent));
    assert(true, "activity: filtered by kind; the record's integrity check passes");
    await page.screenshot({ path: path.join(shots, "m7-activity.png") });
    await page.click('.asub[data-asub="tasks"]');

    // ---- M5: models and the runtime
    await page.click('.nav[data-view="models"]');
    await page.waitForFunction(() => /No model registered yet/.test(document.querySelector("#modelList").textContent));
    assert(/No model selected/.test(await page.textContent("#rtState")), "models: nothing registered, the runtime says so");
    // M6: finding, downloading (consent) and deleting models from the Models view
    await page.click("#scanModels");
    await page.waitForFunction(() => /found|No new/.test(document.querySelector("#scanMsg").textContent));
    assert(true, "models: scanning the PC for model files reports what it found: " + (await page.textContent("#scanMsg")));
    assert((await page.$$("#suggestedDl li")).length === 3, "models: suggested downloads listed with licence and fit");
    await page.click("#suggestedDl li button:has-text('Download')"); await page.waitForSelector("#dlDialog:not(.hidden)"); await page.click("#dlCancel");
    assert((await page.$$("#dlJobs li")).length === 0, "models: cancelling the consent dialog starts nothing");
    await page.fill('#modelRegister [name="path"]', "relative/model.gguf"); await page.click('#modelRegister button[type="submit"]');
    await page.waitForFunction(() => /full path/.test(document.querySelector("#modelNotes").textContent));
    await page.fill('#modelRegister [name="path"]', tinyModel.replace(/\.gguf$/, "-missing.gguf")); await page.click('#modelRegister button[type="submit"]');
    await page.waitForFunction(() => /no such file/i.test(document.querySelector("#modelNotes").textContent));
    assert(true, "models: a bad path is refused with the exact reason");
    await page.fill('#modelRegister [name="path"]', tinyModel); await page.click('#modelRegister button[type="submit"]');
    await page.waitForFunction(() => /Registered .*Needs about \d+ MB/.test(document.querySelector("#modelNotes").textContent));
    await page.waitForSelector("#modelList li[data-active='1']");
    assert(/Unloaded/.test(await page.textContent("#rtState")), "models: registering does not load anything");
    await page.click("#modelList li[data-active='1'] button.primary");
    await page.waitForFunction(() => /Ready/.test(document.querySelector("#rtState").textContent) && /backend cpu/.test(document.querySelector("#rtDetail").textContent), null, { timeout: 60000 });
    assert(/\d+ threads/.test(await page.textContent("#rtDetail")) && /-token context/.test(await page.textContent("#rtDetail")), "models: loaded; the runtime shows the real backend, threads and context: " + (await page.textContent("#rtDetail")));
    await page.waitForFunction(() => /ready \(cpu\)/.test(document.querySelector("#modelPill").textContent), null, { timeout: 15000 });
    assert(true, "models: the top pill follows the real state");
    await page.fill('#modelConfigFields [data-k="contextLength"]', "1024"); await page.click('#modelConfig button[type="submit"]');
    await page.waitForFunction(() => /1024-token context/.test(document.querySelector("#rtDetail").textContent), null, { timeout: 30000 });
    assert(true, "models: a setting changed in the form reloads the model with it");
    await page.evaluate(() => { document.querySelector("#view-models").scrollTop = 0; }); await page.screenshot({ path: path.join(shots, "m5-models.png") });
    await page.click("#rtUnload"); await page.waitForFunction(() => /Unloaded/.test(document.querySelector("#rtState").textContent));
    await page.click("#benchRun");
    await page.waitForSelector("#benchResult table.bench", { timeout: 120000 });
    const bench = await page.textContent("#benchResult");
    assert(/Generation\d/.test(bench.replace(/\s+/g, "")) && /backend cpu/.test(bench) && /measured|linux|windows|Linux|Windows/i.test(bench) && /not measured/.test(bench), "models: the benchmark ran through the window and shows its conditions");
    await page.evaluate(() => { document.querySelector("#benchResult").scrollIntoView(); }); await page.screenshot({ path: path.join(shots, "m5-benchmark.png") });
    await page.click("#modelList li[data-active='1'] button:has-text('Remove registration')");
    await page.waitForFunction(() => /No model registered yet/.test(document.querySelector("#modelList").textContent));
    assert(/was not touched/.test(await page.textContent("#modelNotes")), "models: registration removed, the file left alone");

    // Settings: host limits load; sensitive settings can't be changed here.
    await page.click('.nav[data-view="settings"]');
    await page.waitForFunction(() => /Your data:/.test(document.querySelector("#appInfo").textContent));
    assert(await page.isChecked('#appForm [name="runInBackground"]'), "settings: keeping CLU in the tray is on by default");
    assert(!(await page.isChecked('#appForm [name="startWithWindows"]')), "settings: starting with Windows is off by default");
    await page.uncheck('#appForm [name="runInBackground"]'); await page.click('#appForm button[type="submit"]');
    await page.waitForFunction(() => /Saved/.test(document.querySelector("#appSaved").textContent));
    await page.reload(); await page.waitForSelector("#app:not(.hidden)"); await page.click('.nav[data-view="settings"]');
    await page.waitForFunction(() => /Your data:/.test(document.querySelector("#appInfo").textContent));
    assert(!(await page.isChecked('#appForm [name="runInBackground"]')), "settings: the background choice persists");
    await page.check('#appForm [name="runInBackground"]'); await page.click('#appForm button[type="submit"]'); await page.waitForFunction(() => /Saved/.test(document.querySelector("#appSaved").textContent));
    await page.click("#runSetup"); await page.waitForSelector("#wizard:not(.hidden)"); assert(true, "settings: the setup guide can be opened again"); await page.click("#wizSkip");
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
  } catch (e) {
    // What the window looked like when a check failed, to make the failure diagnosable from the test log.
    try { if (lastPage) { console.log("visible text: " + (await lastPage.evaluate(() => document.body.innerText)).replace(/\s+/g, " ").slice(0, 800)); console.log("task detail: " + (await lastPage.evaluate(() => document.querySelector("#taskDetail")?.innerText || "")).replace(/\s+/g, " ").slice(0, 900)); await lastPage.screenshot({ path: path.join(shots, "failure.png") }); } } catch (x) { /* best effort */ }
    throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error(e.message || e); console.error((e.stack || "").split("\n").filter((l) => /shell\.ui\.js/.test(l)).slice(0, 2).join(" | ")); problems.forEach((p) => console.error("  " + p)); process.exit(1); });
