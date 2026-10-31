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
  let conversation = null, streaming = false, ready = Promise.resolve();
  function initChat() {
    $("newConversation").addEventListener("click", async () => { const c = await api("/v1/conversations", { method: "POST", json: {} }); await listConversations(); openConversation(c.id); });
    $("composer").addEventListener("submit", (e) => { e.preventDefault(); send(); });
    $("input").addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } });
    $("stop").addEventListener("click", () => api("/v1/stop", { method: "POST", json: {} }));
    loaders.chat = listConversations;
    ready = listConversations().then((list) => (list.length ? openConversation(list[0].id) : undefined)).catch(() => {});
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
    if (role === "assistant" && text && !error) addFeedback(li);
    return li;
  }
  /** One small control per reply: a reaction is recorded as evidence only; CLU asks before it ever becomes a preference. */
  function addFeedback(li) {
    const sel = el("select", "feedback"); sel.setAttribute("aria-label", "Feedback on this reply");
    [["", "Feedback…"], ["good", "👍 Good"], ["too_long", "Too long"], ["too_short", "Too short"], ["too_formal", "Too formal"], ["too_casual", "Too casual"], ["wrong", "👎 Wrong"]].forEach(([v, l]) => sel.append(new Option(l, v)));
    const note = el("span", "meta");
    sel.addEventListener("change", async () => { if (!sel.value) return;
      try { note.textContent = (await api("/v1/memory/learning/feedback", { method: "POST", json: { reason: sel.value, conversationId: conversation || "" } })).message; } catch (e) { note.textContent = e.message; }
      sel.classList.add("hidden"); });
    li.append(sel, note);
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
    await ready; // the latest conversation is still being opened: don't race it (and don't wipe what we are about to show)
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
    if (!reply.querySelector("select.feedback") && body.textContent && !reply.classList.contains("error")) addFeedback(reply);
    streaming = false; $("stop").classList.add("hidden"); $("send").disabled = false; setPresence("idle", "Ready"); scrollChat();
    listConversations();
  }

  // ------------------------------------------------------------------------------------------ memory
  let editing = null, categories = [], kindFilter = "", promoteWorking = null;
  const RELATIONS = [["related", "Related to"], ["belongs_to", "Belongs to"], ["supports", "Supports"], ["influences", "Influences"],
    ["updates", "Updates / changes the status of"], ["contradicts", "Contradicts"], ["derived_from", "Derived from"]];
  const relationLabel = (r) => (RELATIONS.find((x) => x[0] === r) || [r, r.replace(/_/g, " ")])[1];
  const fmt = (ms) => ms ? new Date(ms).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "";
  const ago = (ms) => { const m = Math.round((Date.now() - ms) / 60000); return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.round(m / 60) + " h ago" : Math.round(m / 1440) + " days ago"; };

  function initMemory() {
    loaders.memory = () => { loadKinds(); loadMemories(); };
    loaders.timeline = loadTimeline;
    categories = [["CORE_FACT", "Core fact"], ["EXPERIENCE", "Relationship / experience"], ["PREFERENCE", "Preference"], ["PROJECT", "Project / status"],
      ["DECISION", "Decision / commitment"], ["INSTRUCTION", "Instruction for CLU"], ["KNOWLEDGE", "Knowledge"], ["PROCEDURE", "Procedure / workflow"],
      ["MANUAL", "Manually inserted"], ["TEMPORARY", "Temporary context"], ["INSIGHT", "Approved insight"]];
    const filter = $("memoryCategory"); categories.forEach(([id, label]) => filter.append(new Option(label, id)));
    document.querySelectorAll(".sub").forEach((b) => b.addEventListener("click", () => showSub(b.dataset.sub)));
    ["memorySearch", "memoryPerson"].forEach((id) => $(id).addEventListener("input", debounce(loadMemories, 250)));
    ["memoryCategory", "memoryFrom", "memoryTo", "memoryDocs"].forEach((id) => $(id).addEventListener("change", loadMemories));
    $("memoryAdd").addEventListener("click", () => openMemoryForm(null));
    $("memoryCancel").addEventListener("click", () => { $("memoryForm").classList.add("hidden"); $("memoryDetail").classList.add("hidden"); promoteWorking = null; });
    $("memoryForm").elements.when.addEventListener("input", debounce(readWhen, 200));
    $("memoryForm").addEventListener("submit", saveMemory);
    $("memoryDelete").addEventListener("click", async () => {
      if (!editing || !confirm(`Delete "${editing.title}"? It is removed with its earlier versions.`)) return;
      await api(`/v1/memories/${editing.id}`, { method: "DELETE" }); $("memoryForm").classList.add("hidden"); $("memoryDetail").classList.add("hidden"); editing = null; loadMemories(); loadKinds();
    });
    const rel = $("linkRelation"); RELATIONS.forEach(([v, l]) => rel.append(new Option(l, v)));
    $("linkSearch").addEventListener("input", debounce(searchLinkTargets, 250));
    initWorking(); initTidy(); initLearning(); initHealth();
  }
  function showSub(name) {
    document.querySelectorAll(".sub").forEach((b) => b.classList.toggle("active", b.dataset.sub === name));
    document.querySelectorAll(".subview").forEach((v) => v.classList.toggle("hidden", v.id !== "sub-" + name));
    ({ browse: () => { loadKinds(); loadMemories(); }, working: loadWorking, tidy: loadTidy, learning: loadLearning, health: loadHealth })[name]();
  }
  function badge(id, n) { const b = $(id); b.textContent = n; b.classList.toggle("hidden", !n); }

  async function loadKinds() {
    const o = await api("/v1/memory/overview"); const box = $("kindChips"); box.replaceChildren();
    const chip = (value, label, n) => { const b = el("button", "chip" + (kindFilter === value ? " active" : ""), n == null ? label : `${label} · ${n}`); b.type = "button";
      b.addEventListener("click", () => { kindFilter = value; loadKinds(); loadMemories(); }); box.append(b); };
    chip("", "All", null); Object.entries(o.kinds).forEach(([k, v]) => chip(k, v.label, v.count));
    badge("workingCount", o.working); badge("tidyCount", o.consolidation); badge("learningCount", o.learning);
  }

  async function loadMemories() {
    const p = { q: $("memorySearch").value.trim(), kind: kindFilter, category: $("memoryCategory").value, person: $("memoryPerson").value.trim(),
      from: $("memoryFrom").value, to: $("memoryTo").value, docs: $("memoryDocs").checked ? "1" : "0" };
    Object.keys(p).forEach((k) => { if (!p[k]) delete p[k]; });
    const list = await api("/v1/memory/search?" + new URLSearchParams(p));
    $("memoryList").replaceChildren(...(list.length ? list.map(resultCard) : [el("li", "muted", p.q || p.kind || p.category || p.person || p.from || p.to ? "Nothing matches." : "No memories yet. Add one, or tell CLU in the chat.")]));
  }
  function resultCard(r) {
    if (r.type === "document") {
      const li = el("li"); const head = el("div", "title"); head.append(el("span", "tag doc", "Document"), document.createTextNode(r.document.name));
      li.append(head, el("div", "sub", `passage ${r.chunk} · from your files, not a memory`), el("div", null, r.text));
      return li;
    }
    return memoryCard(r.memory, r.why);
  }
  function memoryCard(m, why) {
    const li = el("li", "clickable"); const head = el("div", "title");
    head.append(el("span", "tag", m.kindLabel || ""), document.createTextNode(m.title || "(untitled)"));
    if (m.source === "APPROVED_INFERENCE") head.append(el("span", "tag warn", "inferred · approved"));
    li.append(head, el("div", "sub", [m.when || "date unknown", m.categoryLabel, m.people.join(", "), m.places.join(", ")].filter(Boolean).join(" · ")), el("div", null, m.content.slice(0, 240)));
    if (why && why.length && !why.every((w) => w === "keyword match" || w === "matches the filters")) li.append(el("div", "why", why.join(" · ")));
    li.addEventListener("click", () => openMemoryForm(m));
    return li;
  }

  function openMemoryForm(m, prefill) {
    editing = m; const f = $("memoryForm");
    const sel = f.elements.category; if (!sel.options.length) categories.filter(([id]) => id !== "INSIGHT").forEach(([id, label]) => sel.append(new Option(label, id)));
    if (m && ![...sel.options].some((o) => o.value === m.category)) sel.append(new Option(m.categoryLabel, m.category));
    $("memoryFormTitle").textContent = m ? "Edit memory" : "Add memory";
    f.elements.title.value = m ? m.title : (prefill && prefill.title) || ""; f.elements.content.value = m ? m.content : (prefill && prefill.content) || "";
    f.elements.when.value = m ? m.when : ""; f.elements.people.value = m ? m.people.join(", ") : ""; f.elements.places.value = m ? m.places.join(", ") : "";
    f.elements.category.value = m ? m.category : "EXPERIENCE"; f.elements.certainty.value = m ? m.certainty : "RECOLLECTION"; f.elements.importance.value = m ? String(m.importance) : "0";
    $("memoryDelete").classList.toggle("hidden", !m); $("memoryNotes").replaceChildren();
    f.classList.remove("hidden"); readWhen(); f.elements.title.focus();
    if (m) loadDetail(m.id); else $("memoryDetail").classList.add("hidden");
  }

  async function loadDetail(id) {
    let d; try { d = await api(`/v1/memory/${id}`); } catch (e) { $("memoryDetail").classList.add("hidden"); return; }
    const box = $("memoryDetail"); box.classList.remove("hidden");
    const dl = $("detailMeta"); dl.replaceChildren();
    const row = (k, v) => { if (v === "" || v == null) return; dl.append(el("dt", null, k), el("dd", null, v)); };
    const mt = d.meta, pv = d.provenance;
    row("Kind", mt.kindLabel); row("Category", d.memory.categoryLabel);
    row("Created", fmt(mt.createdAt)); row("Last changed", fmt(mt.updatedAt));
    row("Last used by CLU", mt.lastAccessed ? `${fmt(mt.lastAccessed)} (${ago(mt.lastAccessed)}), ${mt.accessCount} time${mt.accessCount === 1 ? "" : "s"}` : "not used yet");
    if (mt.reinforced) row("Repeated by you", `${mt.reinforced} time${mt.reinforced === 1 ? "" : "s"}`);
    if (mt.confidence != null) row("Confidence", Math.round(mt.confidence * 100) + "%");
    if (mt.reviewAt) row("Review by", fmt(mt.reviewAt));
    row("Source", mt.sourceLabel + (pv.reference ? " — " + pv.reference : ""));
    row("Approval", pv.approval === "APPROVED" ? "Approved by you before it was saved" : "Not needed (you entered or stated it)");
    if (pv.documents.length) row("Supported by documents", pv.documents.join(", "));
    if (pv.mergedFrom.length) row("Merged from", pv.mergedFrom.map((t) => `"${t}"`).join(", ") + " (kept in earlier versions)");
    $("detailConflicts").textContent = d.conflicts.length ? "Conflicts: " + d.conflicts.join("; ") : "";
    const rel = $("relatedList"); rel.replaceChildren(...(d.related.length ? d.related.map((r) => {
      const li = el("li", r.kind === "memory" ? "clickable" : ""); li.append(el("span", "tag", relationLabel(r.relation)), document.createTextNode(r.title), el("span", "tag", r.kind));
      if (r.kind === "memory") li.addEventListener("click", async () => { try { openMemoryForm((await api(`/v1/memory/${r.id}`)).memory); } catch (e) { /* gone */ } });
      const x = el("button", "x small", "×"); x.type = "button"; x.title = "Remove this link";
      x.addEventListener("click", async (e) => { e.stopPropagation(); await api(`/v1/memory/${id}/links`, { method: "DELETE", json: { linkId: r.linkId } }); loadDetail(id); });
      li.append(x); return li; }) : [el("li", "muted", "Nothing linked yet.")]));
    $("revisionCount").textContent = d.revisions.length;
    $("revisionList").replaceChildren(...d.revisions.map((r) => { const li = el("li"); li.append(el("div", "sub", fmt(r.at)), el("div", "title", r.title), el("div", null, r.content.slice(0, 300))); return li; }));
    $("linkSearch").value = ""; $("linkResults").replaceChildren();
  }
  async function searchLinkTargets() {
    const q = $("linkSearch").value.trim(); if (!q || !editing) { $("linkResults").replaceChildren(); return; }
    const hits = (await api("/v1/memory/search?" + new URLSearchParams({ q, docs: "0", limit: "8" }))).filter((r) => r.type === "memory" && r.memory.id !== editing.id);
    $("linkResults").replaceChildren(...(hits.length ? hits.map((r) => { const li = el("li", "clickable", `Link "${r.memory.title}"`);
      li.addEventListener("click", async () => { try { await api(`/v1/memory/${editing.id}/links`, { method: "POST", json: { otherId: r.memory.id, relation: $("linkRelation").value } }); loadDetail(editing.id); } catch (e) { $("memoryNotes").textContent = e.message; } });
      return li; }) : [el("li", "muted", "No memory matches.")]));
  }

  async function readWhen() {
    const field = $("memoryForm").elements.when, v = field.value.trim();
    const r = await api("/v1/dates/parse?" + new URLSearchParams({ q: v }));
    if (field.value.trim() !== v) return; // typed on since: a newer reading is on its way
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
      editing = r.memory; $("whenReading").textContent = r.memory.when ? "Saved as: " + r.memory.when : "Date unknown (that's fine).";
      $("memoryDelete").classList.remove("hidden"); $("memoryFormTitle").textContent = "Edit memory";
      if (promoteWorking) { await api(`/v1/memory/working/${promoteWorking}`, { method: "DELETE" }).catch(() => {}); promoteWorking = null; }
      loadMemories(); loadKinds(); loadDetail(r.memory.id);
    } catch (err) { $("memoryNotes").textContent = err.message; }
  }

  // ---- working notes
  function initWorking() {
    $("workingForm").addEventListener("submit", async (e) => {
      e.preventDefault(); const f = e.target;
      await api("/v1/memory/working", { method: "POST", json: { text: f.elements.text.value, kind: f.elements.kind.value } }); f.reset(); loadWorking();
    });
  }
  async function loadWorking() {
    const list = await api("/v1/memory/working");
    $("workingList").replaceChildren(...(list.length ? list.map((w) => {
      const li = el("li"); li.append(el("div", "title", w.text), el("div", "sub", `${w.kind.toLowerCase()} · expires ${fmt(w.expiresAt)}`));
      const keep = el("button", "small", "Keep as a memory"), drop = el("button", "small", "Dismiss"); keep.type = drop.type = "button";
      keep.addEventListener("click", () => { promoteWorking = w.id; showSub("browse"); openMemoryForm(null, { title: w.text.slice(0, 60), content: w.text }); });
      drop.addEventListener("click", async () => { await api(`/v1/memory/working/${w.id}`, { method: "DELETE" }); loadWorking(); loadKinds(); });
      const actions = el("div", "form-actions"); actions.append(keep, drop); li.append(actions); return li; }) : [el("li", "muted", "No working notes.")]));
    loadKinds();
  }

  // ---- tidy-up (consolidation)
  function initTidy() {
    $("tidyRun").addEventListener("click", async () => {
      $("tidyResult").textContent = "Looking…"; const r = await api("/v1/memory/consolidation/run", { method: "POST", json: {} });
      $("tidyResult").textContent = `Checked ${r.scanned}: merged ${r.autoMerged} exact duplicate${r.autoMerged === 1 ? "" : "s"}, ${r.suggested} suggestion${r.suggested === 1 ? "" : "s"}, ${r.expiredMemories + r.expiredWorking} expired removed.`; loadTidy();
    });
  }
  async function loadTidy() {
    const d = await api("/v1/memory/consolidation");
    $("tidyPending").replaceChildren(...(d.pending.length ? d.pending.map((e) => {
      const li = el("li"); li.append(el("div", "title", "These look like the same thing"), el("div", "sub", e.reason));
      const pair = el("div", "pair"); e.memories.forEach((m) => { const c = el("div"); c.append(el("strong", null, m.title), el("div", "sub", m.when), el("div", null, m.content)); pair.append(c); }); li.append(pair);
      const merge = el("button", "primary small", "Merge (keep both texts)"), keep = el("button", "small", "Keep separate"); merge.type = keep.type = "button";
      merge.addEventListener("click", async () => { try { await api(`/v1/memory/consolidation/${e.id}/approve`, { method: "POST", json: {} }); } catch (err) { alert(err.message); } loadTidy(); loadKinds(); });
      keep.addEventListener("click", async () => { await api(`/v1/memory/consolidation/${e.id}/reject`, { method: "POST", json: {} }); loadTidy(); loadKinds(); });
      const actions = el("div", "form-actions"); actions.append(merge, keep); li.append(actions); return li; }) : [el("li", "muted", d.enabled ? "Nothing waiting." : "Background tidy-up is switched off in Settings → Memory.")]));
    $("tidyActivity").replaceChildren(...(d.activity.length ? d.activity.map((e) => { const li = el("li"); li.append(el("span", "tag", e.status.toLowerCase()), document.createTextNode(e.reason), el("span", "sub", "  " + fmt(e.createdAt))); return li; }) : [el("li", "muted", "No activity yet.")]));
  }

  // ---- learning
  function initLearning() {
    $("learnReset").addEventListener("click", async () => { if (!confirm("Forget everything CLU has noticed about how you like replies? Preferences already in use stay until you change them in Settings.")) return;
      await api("/v1/memory/learning/reset", { method: "POST", json: {} }); loadLearning(); });
  }
  async function loadLearning() {
    const d = await api("/v1/memory/learning");
    $("learnQuestions").replaceChildren(...(d.questions.length ? d.questions.map((q) => {
      const li = el("li"); li.append(el("div", "title", q.question), el("div", "sub", "Evidence: " + q.evidence));
      const yes = el("button", "primary small", "Yes, make it standing"), no = el("button", "small", "No, not now"); yes.type = no.type = "button";
      yes.addEventListener("click", async () => { try { await api(`/v1/memory/learning/${q.id}/accept`, { method: "POST", json: {} }); } catch (e) { alert(e.message); } loadLearning(); });
      no.addEventListener("click", async () => { await api(`/v1/memory/learning/${q.id}/reject`, { method: "POST", json: {} }); loadLearning(); });
      const a = el("div", "form-actions"); a.append(yes, no); li.append(a); return li; }) : [el("li", "muted", d.enabled ? "No questions. CLU asks only when something keeps coming up." : "Learning is switched off in Settings → Memory.")]));
    $("learnNoticed").replaceChildren(...(d.noticed.length ? d.noticed.map((n) => { const li = el("li"); li.append(el("span", "tag", n.scopeLabel), document.createTextNode(n.description),
      el("span", "sub", `  ${n.signals} time${n.signals === 1 ? "" : "s"}, ${n.conversations} conversation${n.conversations === 1 ? "" : "s"}, ${n.days} day${n.days === 1 ? "" : "s"}${n.against ? `, ${n.against} against` : ""}`)); return li; }) : [el("li", "muted", "Nothing yet.")]));
    $("learnLearned").replaceChildren(...(d.learned.length ? d.learned.map((p) => { const li = el("li"); li.append(document.createTextNode(p.label), el("span", "sub", "  " + p.source)); return li; }) : [el("li", "muted", "No learned preferences in use.")]));
    loadKinds();
  }

  // ---- health, backups, background work
  function initHealth() {
    const run = async (fn) => { $("healthNotes").textContent = "Working…"; try { await fn(); } catch (e) { $("healthNotes").textContent = e.message; } };
    $("healthCheck").addEventListener("click", () => run(async () => { await loadHealth(); }));
    $("healthRepair").addEventListener("click", () => run(async () => { const r = await api("/v1/memory/health", { method: "POST", json: { action: "repair" } }); await loadHealth(); $("healthNotes").textContent = r.done.length ? r.done.join("; ") : "Nothing needed repairing."; }));
    $("healthReindex").addEventListener("click", () => run(async () => { const r = await api("/v1/memory/health", { method: "POST", json: { action: "reindex" } }); await loadHealth(); $("healthNotes").textContent = `Search index rebuilt for ${r.reindexed} memories.`; }));
    $("backupNow").addEventListener("click", () => run(async () => { await api("/v1/memory/backups", { method: "POST", json: {} }); await loadHealth(); $("healthNotes").textContent = "Backup made."; }));
    $("maintRun").addEventListener("click", async () => { $("maintResult").textContent = "Running…"; const r = await api("/v1/memory/maintenance", { method: "POST", json: {} }); $("maintResult").textContent = r.summary; loadHealth(); });
  }
  async function loadHealth() {
    const [h, b, m] = await Promise.all([api("/v1/memory/health"), api("/v1/memory/backups"), api("/v1/memory/maintenance")]);
    $("healthSummary").textContent = `${h.ok ? "Database integrity: ok" : "Database problem: " + h.integrity} · ${h.memories} memories · ${h.documents} documents`;
    $("healthIssues").replaceChildren(...(h.issues.length ? h.issues.map((i) => { const li = el("li"); li.append(el("span", "tag" + (i.repairable ? "" : " warn"), i.repairable ? "repairable" : "needs a backup"), document.createTextNode(`${i.count} · ${i.detail}`)); return li; }) : [el("li", "muted", "No problems found.")]));
    $("backupFolder").textContent = b.folder;
    $("backupList").replaceChildren(...(b.backups.length ? b.backups.map((x) => { const li = el("li"); li.append(el("span", "tag", x.kind), document.createTextNode(`${x.name} · ${Math.max(1, Math.round(x.sizeBytes / 1024))} KB`), el("span", "sub", "  " + fmt(x.createdAt))); return li; }) : [el("li", "muted", "No backups yet.")]));
    $("backupRestore").textContent = b.restore;
    $("maintStatus").textContent = (m.scheduled ? "Runs in the background when the PC and the model are idle." : "Background runs are switched off (maintenanceMinutes = 0 in host.json).") +
      (m.waiting ? ` Waiting: ${m.waiting}.` : "") + (m.last ? ` Last run ${ago(m.last.at)}: ${m.last.summary}` : "");
    $("maintJobs").replaceChildren(...m.jobs.map((j) => { const li = el("li"); li.append(el("span", "tag", j.state.toLowerCase()), document.createTextNode(`${j.name}${j.detail ? " — " + j.detail : ""}`), el("span", "sub", j.lastRun ? "  " + ago(j.lastRun) : "")); return li; }));
    $("healthNotes").textContent = "";
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
      const li = el("li"); const head = el("div", "title"); head.append(el("span", "tag" + (s.inferred ? " warn" : ""), s.inferred ? "Inferred by CLU" : s.kindLabel), document.createTextNode(s.inferred ? "CLU noticed something about you" : "Save this?"));
      const text = el("textarea"); text.rows = 2; text.value = s.text; text.setAttribute("aria-label", "Text to save");
      const cat = el("select"); cat.setAttribute("aria-label", "Category"); categories.filter(([id]) => id !== "TEMPORARY").forEach(([id, label]) => cat.append(new Option(label, id)));
      cat.value = s.category || (s.inferred ? "INSIGHT" : "CORE_FACT");
      const approve = el("button", "primary small", "Approve and save"), reject = el("button", "small", "Reject"); approve.type = reject.type = "button";
      approve.addEventListener("click", async () => { try { await api(`/v1/suggestions/${s.id}/approve`, { method: "POST", json: { text: text.value, category: cat.value } }); } catch (e) { alert(e.message); } loadSuggestions(); refreshStatus(); });
      reject.addEventListener("click", async () => { await api(`/v1/suggestions/${s.id}/reject`, { method: "POST", json: {} }); loadSuggestions(); refreshStatus(); });
      const actions = el("div", "form-actions"); actions.append(approve, reject);
      li.append(head, text, el("div", "sub", "Why: " + s.rationale + (s.conversation ? ` (conversation "${s.conversation}")` : "")));
      if (s.conflictsWith) { const c = el("div", "notes", `May conflict with an existing memory: "${s.conflictsWith.title}" — ${s.conflictsWith.content}. If you approve, both stay and are marked as conflicting.`); li.append(c); }
      li.append(el("label", "inline", "Save as "), cat, actions); return li;
    }) : [el("li", "muted", "No suggestions waiting.")]));
  }
  async function refreshStatus() {
    let s; try { s = await api("/v1/status"); } catch (e) { $("modelPill").textContent = "CLU unreachable"; return; }
    const m = s.model; const pill = $("modelPill");
    pill.textContent = m.status === "READY" ? `${m.name} · ready` : m.status === "NO_MODEL" ? "no model registered" : `model: ${m.status.toLowerCase()}`;
    pill.classList.toggle("ready", m.status === "READY");
    const badge = $("suggestionCount"); badge.textContent = s.pendingSuggestions; badge.classList.toggle("hidden", !s.pendingSuggestions);
    const info = $("modelInfo"); info.replaceChildren(el("div", null, `${m.name || "No model"} — ${m.status}`),
      el("div", "muted", m.status === "NO_MODEL" ? "Add a GGUF model on this PC with: clu-host --register-model <file.gguf>" : m.message || ""));
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
