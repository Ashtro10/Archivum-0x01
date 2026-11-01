/*
 * The Agents screens: Tasks, Agents, Permissions, Activity, and the approval cards. Everything shown comes from the host
 * (/v1/agents/*); text from tasks, files and web pages is only ever set as text. Approvals are the owner's: this is the only place
 * (besides the API with the owner's token) where a request can be approved.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const time = (ms) => (ms ? new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "");
  const dt = (ms) => (ms ? new Date(ms).toLocaleString() : "");
  const left = (ms) => { const m = Math.max(0, Math.round((ms - Date.now()) / 60000)); return m >= 90 ? Math.round(m / 60) + " h" : m + " min"; };
  const MB = (b) => Math.round(b / 1048576) + " MB";
  let api, tab = "tasks", opened = null, timer = null, status = null, last = {}, auditBefore = null, onBadge = () => {};
  const same = (key, v) => { const s = JSON.stringify(v); if (last[key] === s) return true; last[key] = s; return false; };

  function init(a, badgeFn) {
    api = a; onBadge = badgeFn || onBadge;
    document.querySelectorAll(".asub").forEach((b) => b.addEventListener("click", () => { tab = b.dataset.asub; showTab(); }));
    $("taskForm").addEventListener("submit", async (e) => {
      e.preventDefault(); $("taskFormMsg").textContent = "Planning…"; $("taskPlan").disabled = true;
      try { const t = await api("/v1/agents/tasks", { method: "POST", json: { goal: $("taskGoal").value } }); $("taskGoal").value = ""; $("taskFormMsg").textContent = ""; opened = t.id; last = {}; refresh(); }
      catch (err) { $("taskFormMsg").textContent = err.message; }
      $("taskPlan").disabled = false;
    });
    $("taskRunSteps").addEventListener("click", async () => {
      let steps; try { steps = JSON.parse($("taskSteps").value); } catch (e) { $("taskFormMsg").textContent = "The steps are not valid JSON."; return; }
      try { const t = await api("/v1/agents/tasks", { method: "POST", json: { goal: $("taskGoal").value || "Steps I wrote", steps, start: true, report: false } }); $("taskFormMsg").textContent = ""; opened = t.id; last = {}; refresh(); }
      catch (err) { $("taskFormMsg").textContent = err.message; }
    });
    $("rootForm").addEventListener("submit", async (e) => {
      e.preventDefault(); const f = e.target;
      try { await api("/v1/agents/roots", { method: "POST", json: { path: f.elements.path.value, writable: f.elements.writable.checked } }); f.reset(); $("rootMsg").textContent = ""; last = {}; refresh(); }
      catch (err) { $("rootMsg").textContent = err.message; }
    });
    $("revokeAll").addEventListener("click", async () => { if (!confirm("Revoke every permission now and withdraw all waiting requests? Running tasks will ask again for what they still need.")) return; const r = await api("/v1/agents/permissions/revoke-all", { method: "POST", json: {} }); $("grantMsg").textContent = `${r.revoked} revoked.`; last = {}; refresh(); });
    $("standingForm").addEventListener("submit", async (e) => {
      e.preventDefault(); const f = e.target;
      try { await api("/v1/agents/permissions", { method: "POST", json: { tool: f.elements.tool.value, resources: [f.elements.resource.value], hours: Number(f.elements.hours.value) } }); $("grantMsg").textContent = "Permission given."; last = {}; refresh(); }
      catch (err) { $("grantMsg").textContent = err.message; }
    });
    $("auditTask").addEventListener("change", () => { auditBefore = null; loadAudit(true); }); $("auditKind").addEventListener("change", () => { auditBefore = null; loadAudit(true); });
    $("auditMore").addEventListener("click", () => loadAudit(false));
    $("auditVerify").addEventListener("click", async () => { const v = await api("/v1/agents/audit/verify"); $("auditMsg").textContent = v.ok ? `Intact: ${v.records} records, none changed or missing.` : `NOT intact: ${v.message}`; $("auditMsg").className = v.ok ? "muted" : "err"; });
  }

  function open() { last = {}; refresh(); clearInterval(timer); timer = setInterval(() => { if ($("view-agents").classList.contains("hidden")) { clearInterval(timer); return; } if (!document.hidden) refresh(); }, 1500); }
  function showTab() {
    document.querySelectorAll(".asub").forEach((b) => b.classList.toggle("active", b.dataset.asub === tab));
    document.querySelectorAll(".aview").forEach((v) => v.classList.toggle("hidden", v.id !== "asub-" + tab)); last = {}; refresh();
  }

  async function refresh() {
    try { status = await api("/v1/agents/status"); } catch (e) { return; }
    const pending = await api("/v1/agents/approvals");
    onBadge(pending.length);
    $("agentsOff").classList.toggle("hidden", status.enabled);
    renderApprovals(pending);
    if (tab === "tasks") await loadTasks(); else if (tab === "agents") await loadAgents(); else if (tab === "permissions") await loadPermissions(); else if (tab === "activity" && !last.audit) { await loadAudit(true); last.audit = "1"; }
  }

  // ------------------------------------------------------------------------------------------ approvals
  function renderApprovals(list) {
    if (same("approvals", list.map((a) => a.id + a.status))) return;
    const box = $("approvalBox"); box.replaceChildren(...list.map(approvalCard));
  }

  function approvalCard(a) {
    const c = el("div", "approval " + a.category); c.dataset.id = a.id;
    c.append(el("h3", null, a.category === "C" ? "CLU needs your confirmation" : "CLU asks for your approval"));
    c.append(el("div", null, a.intent));
    const f = el("dl", "facts");
    const rows = [["Task", a.taskGoal || a.task], ["Why", a.why || "It is a step of the task"], ["Tool", `${a.tool} (${a.categoryLabel})`], ["Affects", a.resources.join("\n")], ["Can it be undone?", a.reversible ? "Yes. " : "Not by CLU. "], ["If it works", a.onSuccess], ["If it fails", a.onFailure], ["Details", a.args], ["Asked until", `${time(a.expiresAt)} (${left(a.expiresAt)} left)`]];
    rows.forEach(([k, v]) => { if (!v) return; const dd = el("dd", null, v); if (k === "Affects" || k === "Details") dd.style.whiteSpace = "pre-wrap"; f.append(el("dt", null, k), dd); });
    c.append(f);
    const btns = el("div", "btns"); let ack = null;
    const act = async (decision, scope) => { btns.querySelectorAll("button").forEach((b) => (b.disabled = true)); try { await api(`/v1/agents/approvals/${a.id}`, { method: "POST", json: { decision, scope, acknowledge: ack ? ack.checked : false } }); last = {}; refresh(); } catch (e) { msg.textContent = e.message; btns.querySelectorAll("button").forEach((b) => (b.disabled = false)); } };
    const msg = el("span", "err");
    if (a.category === "C") {
      const lab = el("label", "inline"); ack = el("input"); ack.type = "checkbox"; lab.append(ack, document.createTextNode("I understand and want this done (this one time)")); c.append(lab);
      const ok = el("button", "small primary", "Confirm"); ok.type = "button"; ok.disabled = true; ack.addEventListener("change", () => { ok.disabled = !ack.checked; }); ok.addEventListener("click", () => act("approve", "once")); btns.append(ok);
    } else {
      const once = el("button", "small primary", "Approve this once"); once.type = "button"; once.addEventListener("click", () => act("approve", "once"));
      const task = el("button", "small", "Approve for this task"); task.type = "button"; task.title = "Covers this tool on exactly these items until the task ends"; task.addEventListener("click", () => act("approve", "task"));
      btns.append(once, task);
    }
    const no = el("button", "small danger", "Reject"); no.type = "button"; no.addEventListener("click", () => act("reject", "once")); btns.append(no, msg);
    c.append(btns); return c;
  }

  // ------------------------------------------------------------------------------------------ tasks
  const stChip = (st) => { const k = st === "DONE" ? "done" : st === "RUNNING" ? "running" : st === "WAITING_APPROVAL" ? "waiting" : ["FAILED", "REFUSED", "CANCELLED"].includes(st) ? "failed" : ""; return el("span", "chip-s " + k, st.toLowerCase().replace("_", " ")); };

  async function loadTasks() {
    const tasks = await api("/v1/agents/tasks");
    if (!same("tasks", tasks.map((t) => [t.id, t.status, t.stepsDone, t.updatedAt, t.note]))) {
      $("taskList").replaceChildren(...(tasks.length ? tasks.map((t) => {
        const li = el("li", "clickable"); li.dataset.id = t.id; if (t.id === opened) li.style.borderColor = "var(--accent)";
        const head = el("div", "title"); head.append(el("span", "chip-s " + (t.status === "COMPLETED" ? "done" : t.status === "FAILED" ? "failed" : t.status === "WAITING_APPROVAL" || t.status === "RUNNING" ? "running" : ""), t.statusLabel), document.createTextNode(t.goal));
        li.append(head, el("div", "sub", `${t.stepsDone} of ${t.stepsTotal} steps done · ${dt(t.createdAt)}${t.note ? " · " + t.note : ""}`));
        li.addEventListener("click", () => { opened = t.id; last.detail = null; last.tasks = null; loadTasks(); });
        return li; }) : [el("li", "muted", "No tasks yet. Describe a goal above, or type /task in chat.")]));
    }
    if (opened) {
      let t; try { t = await api(`/v1/agents/tasks/${opened}`); } catch (e) { opened = null; $("taskDetail").replaceChildren(); return; }
      if (!same("detail", [t.id, t.status, t.updatedAt, t.steps.map((s) => s.status + s.attempts + s.output?.length)])) renderTask(t);
    } else $("taskDetail").replaceChildren();
  }

  function renderTask(t) {
    const box = el("div", "card"); const head = el("h3", null, t.goal); box.append(head);
    const line = el("div"); line.append(el("span", "chip-s " + (t.status === "COMPLETED" ? "done" : t.status === "FAILED" ? "failed" : "running"), t.statusLabel), document.createTextNode(t.note || "")); box.append(line);
    if (t.error) box.append(el("div", "notes", t.error)); if (t.summary) box.append(el("div", "muted", "Plan: " + t.summary));
    const ol = el("ol", "steps");
    t.steps.forEach((s) => {
      const li = el("li", "s-" + s.status); const row = el("div"); row.append(stChip(s.status), el("span", "chip-s", s.roleTitle));
      if (s.category) row.append(el("span", "chip-s " + s.category, s.category === "A" ? "safe" : s.category === "B" ? "needs approval" : "needs confirmation"));
      row.append(document.createTextNode(s.description || s.tool || s.id)); li.append(row);
      li.append(el("div", "muted", (s.kind === "TOOL" ? s.tool : "reasoning") + (s.after.length ? " · after " + s.after.join(", ") : "") + (s.attempts > 1 ? ` · ${s.attempts} attempts` : "")));
      if (s.flagged.length) li.append(el("div", "warnbox", "Careful: text this step read " + s.flagged.join("; ") + ". CLU treated it as information only and did not follow it."));
      if (s.error) li.append(el("div", "notes", s.error));
      if (s.output) { const d = el("details"); d.append(el("summary", null, "What it found / produced")); d.append(el("pre", null, s.output)); li.append(d); }
      if (s.args && Object.keys(s.args).length) { const d = el("details"); d.append(el("summary", null, "Details")); d.append(el("pre", null, JSON.stringify(s.args, null, 1))); li.append(d); }
      ol.append(li);
    });
    box.append(ol);
    if (t.result) { box.append(el("h4", null, "Result")); box.append(el("pre", null, t.result)); }
    const acts = el("div", "form-actions");
    const post = async (what, body) => { try { await api(`/v1/agents/tasks/${t.id}/${what}`, { method: "POST", json: body || {} }); } catch (e) { acts.append(el("span", "err", e.message)); return; } last = {}; refresh(); };
    const btn = (label, fn, cls) => { const b = el("button", "small " + (cls || ""), label); b.type = "button"; b.addEventListener("click", fn); acts.append(b); return b; };
    if (t.status === "AWAITING_START") {
      const changes = t.steps.filter((s) => s.category === "B").length, confirm_ = t.steps.filter((s) => s.category === "C").length;
      box.append(el("div", "notes", `Review the plan above. ${changes ? changes + " step(s) need approval. " : ""}${confirm_ ? confirm_ + " step(s) will need your explicit confirmation each time. " : ""}Nothing has been done yet.`));
      btn("Start (ask me before each change)", () => post("start", { approveActions: false }), "primary");
      if (changes) btn("Start and approve this plan's changes", () => post("start", { approveActions: true }));
    }
    if (["RUNNING", "WAITING_APPROVAL", "QUEUED"].includes(t.status)) btn("Pause", () => post("pause"));
    if (t.status === "PAUSED") btn("Resume", () => post("resume"), "primary");
    if (!t.terminal) btn("Cancel task", () => { if (confirm("Cancel this task? Anything already done stays done.")) post("cancel"); }, "danger");
    if (t.terminal && t.result) btn("Suggest remembering this", () => post("remember"));
    box.append(acts); $("taskDetail").replaceChildren(box);
  }

  // ------------------------------------------------------------------------------------------ agents
  async function loadAgents() {
    const [tools] = await Promise.all([api("/v1/agents/permissions")]);
    if (same("agents", [status.roles, tools.tools])) return;
    $("roleList").replaceChildren(...status.roles.map((r) => { const li = el("li"); li.append(el("div", "title", r.title), el("div", "sub", r.purpose),
      el("div", "sub", r.tools.length ? "Tools it may use: " + r.tools.join(", ") : (r.reasons ? "Uses no tools: it only thinks, through the shared model." : "")), el("div", "sub", "Works on the one loaded model: " + (r.reasons ? "yes" : "no model needed"))); return li; }));
    $("toolList").replaceChildren(...tools.tools.map((t) => { const li = el("li"); const head = el("div", "title"); head.append(el("span", "chip-s " + t.category, t.categoryLabel), document.createTextNode(t.id)); li.append(head, el("div", "sub", t.description),
      el("div", "sub", `Permission scope ${t.scope} · stops after ${Math.round(t.timeoutMs / 1000)} s · ${t.cancellable ? "can be cancelled" : "runs to completion"} · runs ${t.environment} · used by ${t.roles.join(", ")}`));
      if (t.note) li.append(el("div", "sub", t.note)); if (t.untrustedOutput) li.append(el("div", "sub", "What it reads is treated as information, never as instructions."));
      const lab = el("label", "inline"); const cb = el("input"); cb.type = "checkbox"; cb.checked = t.enabled; cb.addEventListener("change", async () => { try { await api(`/v1/agents/tools/${t.id}`, { method: "PUT", json: { enabled: cb.checked } }); last = {}; } catch (e) { cb.checked = !cb.checked; } });
      lab.append(cb, document.createTextNode(t.enabled ? "Switched on" : "Switched off" + (t.defaultEnabled ? "" : " (off unless you turn it on)"))); li.append(lab); return li; }));
  }

  // ------------------------------------------------------------------------------------------ permissions
  async function loadPermissions() {
    const p = await api("/v1/agents/permissions");
    const g = $("grantCount"); g.textContent = p.grants.length; g.classList.toggle("hidden", !p.grants.length);
    if (same("perms", p)) return;
    $("rootList").replaceChildren(...(p.roots.length ? p.roots.map((r) => { const li = el("li"); li.append(el("div", "title", r.path), el("div", "sub", (r.writable ? "Tasks may read and change files here" : "Tasks may only read here") + (r.exists ? "" : " · the folder is missing")));
      const b = el("button", "small danger", "Stop allowing"); b.type = "button"; b.addEventListener("click", async () => { if (!confirm(`Stop tasks from using ${r.path}?`)) return; await api("/v1/agents/roots/remove", { method: "POST", json: { path: r.path } }); last = {}; refresh(); }); li.append(b); return li; }) : [el("li", "muted", "No folder is allowed. Tasks can use CLU's memory and the web (with approval) but no files.")]));
    $("grantList").replaceChildren(...(p.grants.length ? p.grants.map((x) => { const li = el("li"); li.append(el("div", "title", `${x.tool} · ${x.kind.toLowerCase()}`), el("div", "sub", x.resources.join(", ")), el("div", "sub", `until ${dt(x.expiresAt)}${x.usesLeft != null ? " · " + x.usesLeft + " uses left" : ""}${x.task ? " · for one task" : ""}`));
      const b = el("button", "small danger", "Revoke"); b.type = "button"; b.addEventListener("click", async () => { await api(`/v1/agents/permissions/${x.id}/revoke`, { method: "POST", json: {} }); last = {}; refresh(); }); li.append(b); return li; }) : [el("li", "muted", "Nothing is currently approved.")]));
    const sel = $("standingForm").elements.tool; const cur = sel.value; sel.replaceChildren(...p.tools.filter((t) => t.category === "B").map((t) => new Option(t.id, t.id))); if (cur) sel.value = cur;
    $("shellInfo").textContent = p.shellAllowlist.length ? "Programs a task may ask to run (each run needs your confirmation): " + p.shellAllowlist.join(", ") : "No program is allowed. Running programs is off; to allow one, add its full path to \"Programs tasks may run\" in Settings and switch the tool on under Agents.";
    $("approvalHistory").replaceChildren(...(p.approvals.length ? p.approvals.map((a) => { const li = el("li"); li.append(el("div", "title", `${a.status.toLowerCase()} · ${a.intent}`), el("div", "sub", `${a.tool} · ${a.categoryLabel} · ${dt(a.createdAt)}${a.decidedBy ? " · by " + a.decidedBy : ""}`)); return li; }) : [el("li", "muted", "No requests yet.")]));
  }

  // ------------------------------------------------------------------------------------------ activity
  async function loadAudit(reset) {
    const tasks = await api("/v1/agents/tasks"); const sel = $("auditTask"); const cur = sel.value;
    if (sel.options.length !== tasks.length + 1) { sel.replaceChildren(new Option("All tasks", ""), ...tasks.map((t) => new Option(t.goal.slice(0, 50), t.id))); sel.value = cur; }
    const q = new URLSearchParams({ limit: "60" }); if (sel.value) q.set("task", sel.value); if ($("auditKind").value) q.set("kind", $("auditKind").value); if (!reset && auditBefore) q.set("before", auditBefore);
    const rows = await api("/v1/agents/audit?" + q); const list = $("auditList"); if (reset) list.replaceChildren();
    rows.forEach((e) => { const li = el("li"); li.append(el("span", "t", time(e.ts)), el("span", "k", e.kind), (() => { const d = el("div"); d.append(el("span", null, `${e.actor}${e.subject ? " · " + e.subject : ""}`), el("div", "muted", Object.entries(e.detail).map(([k, v]) => k + ": " + (typeof v === "string" ? v : JSON.stringify(v))).join(" · ").slice(0, 400))); return d; })()); list.append(li); auditBefore = e.seq; });
    $("auditMore").classList.toggle("hidden", rows.length < 60);
    if (reset && !rows.length) list.append(el("li", "muted", "Nothing recorded yet."));
  }

  window.CluAgents = { init, open, refresh: () => refresh() };
})();
