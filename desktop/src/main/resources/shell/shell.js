/*
 * CLU's desktop shell. Talks only to CLU's local API on this computer, with the token obtained from a one-time
 * launch code (kept in sessionStorage for this window only). No frameworks; all user content is set as text.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  let token = null;

  // ------------------------------------------------------------------------------------------ session
  async function bootstrap() {
    const m = /[#&]code=([A-Za-z0-9_-]+)/.exec(location.hash);
    if (m) {
      history.replaceState(null, "", location.pathname); // the code is single-use; drop it from the address
      try {
        const r = await fetch("/v1/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: m[1] }) });
        if (r.ok) sessionStorage.setItem("clu-token", (await r.json()).token);
      } catch (e) { /* falls through to the locked screen */ }
    }
    token = sessionStorage.getItem("clu-token");
    if (!token || !(await api("/v1/status").then(() => true, () => false))) {
      sessionStorage.removeItem("clu-token");
      $("locked").classList.remove("hidden"); CluFace.attach($("lockedFace")); return;
    }
    $("app").classList.remove("hidden");
    CluFace.attach($("face"));
    initNav(); initChat(); initMemory(); initPeople(); initDocuments(); initStatus(); initSettings();
    refreshStatus(); setInterval(() => { if (!document.hidden) refreshStatus(); }, 10000);
  }

  async function api(path, opts = {}) {
    const headers = Object.assign({ Authorization: "Bearer " + token }, opts.json !== undefined ? { "Content-Type": "application/json" } : {}, opts.headers || {});
    const r = await fetch(path, { method: opts.method || "GET", headers, body: opts.json !== undefined ? JSON.stringify(opts.json) : opts.body });
    const data = (r.headers.get("Content-Type") || "").includes("json") ? await r.json() : null;
    if (!r.ok) { const e = new Error((data && data.error) || ("HTTP " + r.status)); e.status = r.status; e.data = data; throw e; }
    return data;
  }

  // ------------------------------------------------------------------------------------------ navigation
  const loaders = {};
  function initNav() {
    document.querySelectorAll(".nav").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));
  }
  function show(view) {
    document.querySelectorAll(".nav").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
    document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-" + view));
    $("viewTitle").textContent = document.querySelector(`.nav[data-view="${view}"]`).firstChild.textContent.trim();
    if (loaders[view]) loaders[view]();
  }

  // ------------------------------------------------------------------------------------------ chat
  let conversation = null, streaming = false;
  function initChat() {
    $("newConversation").addEventListener("click", async () => { const c = await api("/v1/conversations", { method: "POST", json: {} }); await listConversations(); openConversation(c.id); });
    $("composer").addEventListener("submit", (e) => { e.preventDefault(); send(); });
    $("input").addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } });
    $("stop").addEventListener("click", () => api("/v1/stop", { method: "POST", json: {} }));
    loaders.chat = listConversations;
    listConversations().then((list) => { if (list.length) openConversation(list[0].id); });
  }
  async function listConversations() {
    const list = await api("/v1/conversations");
    const ul = $("conversationList"); ul.replaceChildren();
    list.forEach((c) => { const li = el("li", c.id === conversation ? "active" : "", c.title); li.addEventListener("click", () => openConversation(c.id)); ul.append(li); });
    return list;
  }
  async function openConversation(id) {
    conversation = id;
    await listConversations();
    const msgs = await api(`/v1/conversations/${id}/messages`);
    $("messages").replaceChildren(...msgs.map((m) => bubble(m.role, m.content, m.sources, m.error)));
    scrollChat();
  }
  function bubble(role, text, sources, error) {
    const li = el("li", "msg " + role + (error ? " error" : ""));
    const body = el("span", "body", text); li.append(body);
    if (sources && sources.length) addSources(li, sources);
    return li;
  }
  /** Sources stay one collapsed line under the answer; the list appears only when asked for. */
  function addSources(li, sources) {
    const toggle = el("button", "sources-toggle", `Sources (${sources.length}) ▸`); toggle.type = "button";
    const list = el("ul", "sources hidden");
    sources.forEach((s) => { const item = el("li"); const a = el("a", null, s.title || s.url); a.href = s.url; a.target = "_blank"; a.rel = "noopener noreferrer"; item.append(a); list.append(item); });
    toggle.addEventListener("click", () => { list.classList.toggle("hidden"); toggle.textContent = `Sources (${sources.length}) ${list.classList.contains("hidden") ? "▸" : "▾"}`; });
    li.append(toggle, list);
  }
  function scrollChat() { const m = $("messages"); m.scrollTop = m.scrollHeight; }
  function setPresence(state, label) { CluFace.set(state); $("presenceState").textContent = label; }

  async function send() {
    const text = $("input").value.trim();
    if (!text || streaming) return;
    if (!conversation) { const c = await api("/v1/conversations", { method: "POST", json: {} }); conversation = c.id; }
    $("input").value = "";
    $("messages").append(bubble("user", text));
    const reply = bubble("assistant", ""); const body = reply.querySelector(".body");
    $("messages").append(reply); scrollChat();
    streaming = true; $("stop").classList.remove("hidden"); $("send").disabled = true; setPresence("thinking", "Thinking…");
    try {
      const r = await fetch(`/v1/conversations/${conversation}/turns`, { method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json", Accept: "text/event-stream" }, body: JSON.stringify({ text }) });
      if (!r.ok) throw new Error(((await r.json().catch(() => ({}))).error) || ("HTTP " + r.status));
      const reader = r.body.getReader(); const dec = new TextDecoder(); let buf = "";
      for (;;) {
        const { value, done } = await reader.read(); if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf("\n\n")) >= 0) {
          const chunk = buf.slice(0, i); buf = buf.slice(i + 2);
          const name = (/^event: (.*)$/m.exec(chunk) || [])[1]; const data = JSON.parse((/^data: (.*)$/m.exec(chunk) || [])[1] || "{}");
          if (name === "status") $("presenceState").textContent = data.text;
          else if (name === "text") { if (CluFace.state !== "speaking") setPresence("speaking", "Answering"); body.textContent += data.delta; CluFace.pulse(); scrollChat(); }
          else if (name === "suggestion") refreshStatus();
          else if (name === "error") { reply.classList.add("error"); reply.append(el("span", "meta", data.text)); }
          else if (name === "done") {
            body.textContent = data.text || body.textContent;
            if (data.interrupted) reply.append(el("span", "meta", "Stopped."));
            if (data.sources && data.sources.length) addSources(reply, data.sources);
          }
        }
      }
    } catch (e) { reply.classList.add("error"); reply.append(el("span", "meta", "Couldn't reach CLU: " + e.message)); }
    streaming = false; $("stop").classList.add("hidden"); $("send").disabled = false; setPresence("idle", "Ready"); scrollChat();
    listConversations();
  }

  // ------------------------------------------------------------------------------------------ memory
  let editing = null, categories = [];
  function initMemory() {
    loaders.memory = loadMemories; loaders.timeline = loadTimeline;
    $("memorySearch").addEventListener("input", debounce(loadMemories, 250));
    $("memoryCategory").addEventListener("change", loadMemories);
    $("memoryAdd").addEventListener("click", () => openMemoryForm(null));
    $("memoryCancel").addEventListener("click", () => $("memoryForm").classList.add("hidden"));
    $("memoryForm").elements.when.addEventListener("input", debounce(readWhen, 200));
    $("memoryForm").addEventListener("submit", saveMemory);
    $("memoryDelete").addEventListener("click", async () => {
      if (!editing || !confirm(`Delete "${editing.title}"? It is removed with its earlier versions.`)) return;
      await api(`/v1/memories/${editing.id}`, { method: "DELETE" }); $("memoryForm").classList.add("hidden"); loadMemories();
    });
    categories = [["CORE_FACT", "Core fact"], ["EXPERIENCE", "Relationship / experience"], ["PREFERENCE", "Preference"], ["PROJECT", "Project"],
      ["DECISION", "Decision"], ["INSTRUCTION", "Instruction"], ["MANUAL", "Note"]];
  }
  async function loadMemories() {
    const q = $("memorySearch").value.trim(), cat = $("memoryCategory").value;
    const list = await api("/v1/memories?" + new URLSearchParams(Object.assign(q ? { q } : {}, cat ? { category: cat } : {})));
    const seen = new Map(list.map((m) => [m.category, m.categoryLabel]));
    const sel = $("memoryCategory"); if (sel.options.length === 1) seen.forEach((label, id) => sel.append(new Option(label, id)));
    $("memoryList").replaceChildren(...(list.length ? list.map(memoryCard) : [el("li", "muted", q ? "Nothing matches." : "No memories yet. Add one, or tell CLU in the chat.")]));
  }
  function memoryCard(m) {
    const li = el("li", "clickable");
    li.append(el("div", "title", m.title || "(untitled)"),
      el("div", "sub", [m.when || "date unknown", m.categoryLabel, m.people.join(", "), m.places.join(", ")].filter(Boolean).join(" · ")),
      el("div", null, m.content.slice(0, 240)));
    li.addEventListener("click", () => openMemoryForm(m));
    return li;
  }
  function openMemoryForm(m) {
    editing = m; const f = $("memoryForm");
    const sel = f.elements.category; if (!sel.options.length) categories.forEach(([id, label]) => sel.append(new Option(label, id)));
    $("memoryFormTitle").textContent = m ? "Edit memory" : "Add memory";
    f.elements.title.value = m ? m.title : ""; f.elements.content.value = m ? m.content : ""; f.elements.when.value = m ? m.when : "";
    f.elements.people.value = m ? m.people.join(", ") : ""; f.elements.places.value = m ? m.places.join(", ") : "";
    f.elements.category.value = m ? m.category : "EXPERIENCE"; f.elements.certainty.value = m ? m.certainty : "RECOLLECTION"; f.elements.importance.value = m ? String(m.importance) : "0";
    $("memoryDelete").classList.toggle("hidden", !m); $("memoryNotes").replaceChildren();
    f.classList.remove("hidden"); readWhen(); f.elements.title.focus();
  }
  async function readWhen() {
    const v = $("memoryForm").elements.when.value.trim();
    const r = await api("/v1/dates/parse?" + new URLSearchParams({ q: v }));
    $("whenReading").textContent = !r.understood ? "⚠ Not understood — try \"March 2018\", \"around 2019\" or \"between 2018 and 2019\"." : r.label ? "Understood as: " + r.label : "Date unknown (that's fine).";
  }
  async function saveMemory(e) {
    e.preventDefault(); const f = $("memoryForm");
    const body = { title: f.elements.title.value, content: f.elements.content.value, when: f.elements.when.value, people: f.elements.people.value, places: f.elements.places.value,
      category: f.elements.category.value, certainty: f.elements.certainty.value, importance: Number(f.elements.importance.value) };
    try {
      const r = await api(editing ? `/v1/memories/${editing.id}` : "/v1/memories", { method: editing ? "PUT" : "POST", json: body });
      const notes = [];
      r.possibleDuplicates.forEach((d) => notes.push(`Looks like an existing memory: "${d.title}" (${d.when || "date unknown"}).`));
      r.conflicts.forEach((c) => notes.push(`Another record gives a different ${c.field} (${c.otherValue}); both are kept.`));
      Object.entries(r.ambiguousPeople).forEach(([n, ps]) => notes.push(`"${n}" could be ${ps.join(" or ")}: not linked. Use the full name.`));
      r.hints.forEach((h) => notes.push(h));
      $("memoryNotes").textContent = notes.join("\n");
      if (!notes.length) f.classList.add("hidden");
      editing = r.memory; $("memoryDelete").classList.remove("hidden"); $("memoryFormTitle").textContent = "Edit memory";
      loadMemories();
    } catch (err) { $("memoryNotes").textContent = err.message; }
  }
  async function loadTimeline() {
    const list = await api("/v1/timeline"); const box = $("timeline"); box.replaceChildren();
    if (!list.length) { box.append(el("p", "muted", "No dated memories yet.")); return; }
    let year = null; let ul = null;
    list.forEach((m) => {
      const y = (m.sortDate || "").slice(0, 4) || "?";
      if (y !== year) { year = y; box.append(el("div", "year", y)); ul = el("ul", "cards"); box.append(ul); }
      ul.append(memoryCard(m));
    });
  }

  // ------------------------------------------------------------------------------------------ people
  function initPeople() {
    loaders.people = loadPeople;
    $("personForm").addEventListener("submit", async (e) => {
      e.preventDefault(); const f = e.target;
      try {
        const r = await api("/v1/people", { method: "POST", json: { name: f.elements.name.value, relation: f.elements.relation.value, aliases: f.elements.aliases.value } });
        $("personNotes").textContent = r.possibleDuplicates.length ? `Already in People: ${r.possibleDuplicates.join(", ")}. Kept as a separate person; merge on the phone if they're the same.` : "";
        f.reset(); loadPeople();
      } catch (err) { $("personNotes").textContent = err.message; }
    });
  }
  async function loadPeople() {
    const list = await api("/v1/people");
    $("peopleList").replaceChildren(...(list.length ? list.map((p) => {
      const li = el("li"); li.append(el("div", "title", p.name + (p.aliases.length ? ` (${p.aliases.join(", ")})` : "")),
        el("div", "sub", [p.relation, `${p.memories} memories`].filter(Boolean).join(" · ")));
      return li;
    }) : [el("li", "muted", "Nobody yet.")]));
  }

  // ------------------------------------------------------------------------------------------ documents
  function initDocuments() {
    loaders.documents = loadDocuments;
    $("docSearch").addEventListener("input", debounce(searchDocuments, 300));
    $("docUpload").addEventListener("change", async (e) => {
      const lines = [];
      for (const file of e.target.files) {
        try {
          const r = await api("/v1/documents", { method: "POST", body: file, headers: { "X-File-Name": encodeURIComponent(file.name) } });
          lines.push(`${file.name}: imported${r.textStatus === "indexed" ? ` (${r.chars.toLocaleString()} characters indexed)` : " (no searchable text)"}.`);
        } catch (err) { lines.push(`${file.name}: ${err.message}`); }
      }
      $("docNotes").textContent = lines.join("\n"); e.target.value = ""; loadDocuments();
    });
  }
  async function loadDocuments() {
    const list = await api("/v1/documents");
    $("docList").replaceChildren(...(list.length ? list.map((d) => {
      const li = el("li"); li.append(el("div", "title", d.name),
        el("div", "sub", [d.type.toUpperCase(), Math.max(1, Math.round(d.sizeBytes / 1024)) + " KB", d.date, d.textStatus === "indexed" ? `${d.chars.toLocaleString()} characters` : "no searchable text"].filter(Boolean).join(" · ")));
      return li;
    }) : [el("li", "muted", "No documents yet.")]));
  }
  async function searchDocuments() {
    const q = $("docSearch").value.trim();
    if (!q) { $("docResults").replaceChildren(); return; }
    const hits = await api("/v1/documents/search?" + new URLSearchParams({ q }));
    $("docResults").replaceChildren(...(hits.length ? hits.map((h) => { const li = el("li"); li.append(el("div", "title", h.document), el("div", null, h.text)); return li; })
      : [el("li", "muted", "Nothing found in your documents.")]));
  }

  // ------------------------------------------------------------------------------------------ suggestions + status
  function initStatus() {
    loaders.suggestions = loadSuggestions; loaders.status = refreshStatus;
    $("modelLoad").addEventListener("click", async () => { $("modelInfo").textContent = "Loading…"; await api("/v1/model/load", { method: "POST", json: {} }).catch(() => {}); refreshStatus(); });
    $("modelUnload").addEventListener("click", async () => { await api("/v1/model/unload", { method: "POST", json: {} }); refreshStatus(); });
  }
  async function loadSuggestions() {
    const list = await api("/v1/suggestions");
    $("suggestionList").replaceChildren(...(list.length ? list.map((s) => {
      const li = el("li"); const text = el("textarea"); text.rows = 2; text.value = s.text;
      const approve = el("button", "primary small", "Approve"), reject = el("button", "small", "Reject");
      approve.addEventListener("click", async () => { await api(`/v1/suggestions/${s.id}/approve`, { method: "POST", json: { text: text.value } }); loadSuggestions(); refreshStatus(); });
      reject.addEventListener("click", async () => { await api(`/v1/suggestions/${s.id}/reject`, { method: "POST", json: {} }); loadSuggestions(); refreshStatus(); });
      const actions = el("div", "form-actions"); actions.append(approve, reject);
      li.append(text, el("div", "sub", "Why: " + s.rationale), actions);
      return li;
    }) : [el("li", "muted", "No suggestions waiting.")]));
  }
  async function refreshStatus() {
    let s; try { s = await api("/v1/status"); } catch (e) { $("modelPill").textContent = "CLU unreachable"; return; }
    const m = s.model; const pill = $("modelPill");
    pill.textContent = m.status === "READY" ? `${m.name} · ready` : m.status === "NO_MODEL" ? "no model registered" : `model: ${m.status.toLowerCase()}`;
    pill.classList.toggle("ready", m.status === "READY");
    const badge = $("suggestionCount"); badge.textContent = s.pendingSuggestions; badge.classList.toggle("hidden", !s.pendingSuggestions);
    const info = $("modelInfo"); info.replaceChildren(el("div", null, `${m.name || "No model"} — ${m.status}`), el("div", "muted", m.message || ""));
    if (m.status === "READY") info.append(el("div", "muted", `Context ${m.contextSize} tokens · loaded in ${m.loadMs} ms`));
    const g = s.governor; const bars = $("memoryBars"); bars.replaceChildren();
    const bar = (label, used, total) => { bars.append(el("div", null, `${label}: ${used.toLocaleString()} MB of ${total.toLocaleString()} MB`));
      const b = el("div", "bar"); const fill = el("div"); fill.style.width = Math.min(100, (100 * used) / Math.max(1, total)) + "%"; b.append(fill); bars.append(b); };
    bar("CLU (incl. model)", g.processMB, g.budgetMB);
    bar("PC memory in use", g.systemTotalMB - g.systemAvailableMB, g.systemTotalMB);
    bars.append(el("div", "muted", `A model may use up to ${g.availableForModelMB.toLocaleString()} MB right now (${g.reserveMB.toLocaleString()} MB kept free for you).`));
    $("governorEvents").replaceChildren(...(g.events.length ? g.events.slice().reverse().map((e) => el("li", null, `${new Date(e.at).toLocaleTimeString()} — ${e.action}: ${e.reason}`))
      : [el("li", "muted", "Nothing needed doing.")]));
  }

  // ------------------------------------------------------------------------------------------ settings
  let settingsData = null;
  function initSettings() {
    loaders.settings = loadSettings;
    $("settingsCategory").addEventListener("change", renderSettings);
    $("hostForm").addEventListener("submit", async (e) => {
      e.preventDefault(); const f = e.target;
      try {
        await api("/v1/host-config", { method: "PUT", json: { memoryBudgetGB: Number(f.elements.memoryBudgetGB.value), reserveGB: Number(f.elements.reserveGB.value), pressureFreeGB: Number(f.elements.pressureFreeGB.value) } });
        $("hostSaved").textContent = "Saved; the governor uses the new limits now."; refreshStatus();
      } catch (err) { $("hostSaved").textContent = err.message; }
    });
  }
  async function loadSettings() {
    const [h, s] = await Promise.all([api("/v1/host-config"), api("/v1/settings")]);
    const f = $("hostForm"); f.elements.memoryBudgetGB.value = h.memoryBudgetGB; f.elements.reserveGB.value = h.reserveGB; f.elements.pressureFreeGB.value = h.pressureFreeGB;
    settingsData = s;
    const sel = $("settingsCategory"); if (!sel.options.length) s.categories.forEach((c) => sel.append(new Option(c.title, c.id)));
    renderSettings();
  }
  function renderSettings() {
    const cat = $("settingsCategory").value; const box = $("settingsList"); box.replaceChildren();
    settingsData.settings.filter((d) => d.category === cat && !d.advanced).forEach((d) => {
      const row = el("div", "setting"); const left = el("div"); left.append(el("div", null, d.title + (d.sensitive ? " 🔒" : "")), el("div", "desc", d.description));
      const err = el("div", "err"); left.append(err);
      const control = settingControl(d, async (value) => {
        err.textContent = "";
        try { const r = await api("/v1/settings", { method: "PUT", json: { key: d.key, value } }); d.value = r.value; } catch (e) { err.textContent = e.message; }
      });
      if (d.sensitive) { control.disabled = true; control.title = "Needs owner verification (M6)"; }
      row.append(left, control); box.append(row);
    });
  }
  function settingControl(d, save) {
    const t = d.type; let c;
    if (t.kind === "bool") { c = el("input"); c.type = "checkbox"; c.checked = d.value === true; c.addEventListener("change", () => save(c.checked)); return c; }
    if (t.kind === "choice") { c = el("select"); t.options.forEach((o) => c.append(new Option(o.label, o.value))); c.value = d.value; c.addEventListener("change", () => save(c.value)); return c; }
    if (t.kind === "int" || t.kind === "float") { c = el("input"); c.type = "number"; c.min = t.min; c.max = t.max; c.step = t.kind === "int" ? 1 : "any"; c.value = d.value;
      c.addEventListener("change", () => save(t.kind === "int" ? parseInt(c.value, 10) : parseFloat(c.value))); return c; }
    if (t.kind === "list") { c = el("input"); c.value = (d.value || []).join(", "); c.addEventListener("change", () => save(c.value.split(",").map((x) => x.trim()).filter(Boolean))); return c; }
    if (t.kind === "structured") { c = el("input"); c.value = JSON.stringify(d.value); c.disabled = true; c.title = "Edit on the phone"; return c; }
    c = el(t.kind === "multiline" ? "textarea" : "input"); c.value = d.value == null ? "" : d.value; c.addEventListener("change", () => save(c.value)); return c;
  }

  function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
  bootstrap();
})();
