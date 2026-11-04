/*
 * The shell's side of CLU's capabilities: the Capabilities view (what CLU has built for itself, with previews and
 * status: installed / verified / enabled / active / default), one rail entry per enabled experience, the experience
 * host (an installed experience running inside CLU's window), and the chat actions that capability instructions
 * produce: development progress, "which one?", open, lists and confirmations. All text is set as text.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  let api = null, say = null, showView = null, current = null, beat = 0;
  const STEP_NAMES = { understand: "Understand", existing: "Existing capabilities", memory: "Development memory", research: "Research", plan: "Plan", modules: "Modules",
    implement: "Implement", build: "Build check", test: "Test in a browser", inspect: "Inspect the screen", fix: "Fix", integrate: "Integrate", install: "Install", report: "Report" };

  function chips(c) {
    const box = el("div", "cap-chips");
    [["installed", c.installed], ["verified", c.verified], ["enabled", c.enabled], ["open", c.active], ["default", c.default]].forEach(([k, v]) => {
      const s = el("span", "chip-s cap-" + k + (v ? " on" : ""), (v ? "" : "not ") + k); s.title = { installed: "Its files are on disk and unchanged since install", verified: "This version passed CLU's checks in a browser",
        enabled: "You haven't turned it off", open: "Open in this window now", default: "Opens when you ask for this kind without naming one" }[k]; box.append(s); });
    return box;
  }

  function card(c, opts) {
    opts = opts || {};
    const li = el("li", "cap-card"); li.dataset.cap = c.id;
    if (c.preview) { const img = el("img", "cap-preview"); img.src = c.preview; img.alt = "Preview of " + c.name; li.append(img); }
    const body = el("div", "cap-body");
    const title = el("div", "title", c.name); body.append(title);
    body.append(el("div", "sub", `${c.styleName} ${c.kind} · v${c.version}`));
    body.append(el("div", "cap-desc", c.description));
    body.append(chips(c));
    if (c.limitations && c.limitations.length) body.append(el("div", "cap-limits", c.limitations.join(" · ")));
    const row = el("div", "cap-actions");
    const btn = (label, fn, cls) => { const b = el("button", "small" + (cls ? " " + cls : ""), label); b.type = "button"; b.addEventListener("click", fn); row.append(b); return b; };
    if (opts.choose) btn(opts.chooseLabel || "Open", () => opts.choose(c), "primary");
    else {
      if (c.enabled && c.installed) btn("Open", () => open(c), "primary");
      if (!opts.compact) {
        if (!c.default && c.enabled) btn("Make default", () => act(c, "default"));
        btn(c.enabled ? "Disable" : "Enable", () => act(c, c.enabled ? "disable" : "enable"));
        if (c.version > 1) btn("Roll back", () => act(c, "rollback"));
        btn("Details", () => details(c));
        btn("Uninstall", () => uninstall(c), "danger");
      }
    }
    body.append(row); li.append(body);
    return li;
  }

  async function act(c, op) {
    try { await api(`/v1/capabilities/${c.id}/${op}`, { method: "POST", json: {} }); } catch (e) { alert(e.message); }
    refresh();
  }
  async function uninstall(c) {
    if (!confirm(`Uninstall ${c.name}? Its files move to a recovery folder; your other capabilities are not affected.`)) return;
    try { await api(`/v1/capabilities/${c.id}/uninstall`, { method: "POST", json: { confirm: true } }); } catch (e) { alert(e.message); }
    if (current && current.id === c.id) close(); refresh();
  }

  async function details(c) {
    const d = await api(`/v1/capabilities/${c.id}`);
    const box = $("capDetail"); box.replaceChildren(); box.classList.remove("hidden");
    box.append(el("h3", null, d.name + " · v" + d.version));
    const dl = el("dl", "meta");
    const row = (k, v) => { dl.append(el("dt", null, k), el("dd", null, v)); };
    row("Kind / style", `${d.kind} / ${d.styleName}`); row("Features", (d.features || []).join(", ")); row("Uses", (d.dependencies || []).join(", ") || "nothing else");
    row("Interfaces", (d.interfaces || []).join("; ")); row("Recovery", d.recovery || ""); row("Limitations", (d.limitations || []).join("; ") || "none recorded");
    box.append(dl);
    box.append(el("h3", null, "Versions"));
    const vs = el("ul", "cards compact");
    (d.versions || []).forEach((v) => { const li = el("li", null, `v${v.version} · ${v.verified ? "verified" : "NOT verified"} (${v.checksPassed}/${v.checks} checks) · ${new Date(v.installedAt).toLocaleString()} · ${(v.features || []).join(", ")}`);
      if (v.version !== d.version && v.verified) { const b = el("button", "small", "Switch to v" + v.version); b.type = "button";
        b.addEventListener("click", async () => { await api(`/v1/capabilities/${c.id}/activate`, { method: "POST", json: { version: v.version } }); refresh(); details(c); }); li.append(" ", b); }
      vs.append(li); });
    box.append(vs);
    box.append(el("h3", null, "History"));
    const hs = el("ul", "cards compact"); (d.history || []).forEach((h) => hs.append(el("li", null, `${new Date(h.at).toLocaleString()} · ${h.kind} · ${h.detail}`))); box.append(hs);
  }

  async function runs() {
    const list = await api("/v1/capabilities/runs").catch(() => []);
    const ul = $("capRuns"); ul.replaceChildren();
    list.slice(0, 15).forEach((r) => { const li = el("li", "clickable");
      li.append(el("div", "title", `${r.request}`), el("div", "sub", `${r.state} · ${new Date(r.startedAt).toLocaleString()}${r.capability ? " · " + r.capability + (r.version ? " v" + r.version : "") : ""}`));
      if (r.state === "running" || r.state === "paused") { const b = el("button", "small", r.state === "running" ? "Pause" : "Resume"); b.type = "button";
        b.addEventListener("click", async (e) => { e.stopPropagation(); try { await api(`/v1/capabilities/runs/${r.id}/${r.state === "running" ? "pause" : "resume"}`, { method: "POST", json: {} }); } catch (x) { alert(x.message); } runs(); }); li.append(b); }
      li.addEventListener("click", async () => { const full = await api(`/v1/capabilities/runs/${r.id}`); const box = $("capDetail"); box.replaceChildren(); box.classList.remove("hidden");
        box.append(el("h3", null, full.request)); box.append(progress(full)); });
      ul.append(li); });
  }

  async function models() {
    const m = await api("/v1/capabilities/models").catch(() => null); if (!m) return;
    const box = $("capModels"); box.replaceChildren();
    (m.models || []).forEach((x) => box.append(el("div", "sub", `${x.remote ? "Remote" : "Local"}: ${x.id} — ${x.available ? "available" : "not available"} (${x.reason})`)));
    const byModel = {}; (m.scores || []).filter((s) => ["code", "edit", "debug"].includes(s.task)).forEach((s) => { (byModel[s.model] = byModel[s.model] || []).push(`${s.task} ${s.successes}/${s.attempts}`); });
    Object.entries(byModel).forEach(([k, v]) => box.append(el("div", "sub", `Measured: ${k}: ${v.join(", ")}`)));
    if (m.roles) box.append(el("div", "sub", `Chosen for coding: ${m.roles.code || "none"}; editing: ${m.roles.edit || "none"}; debugging: ${m.roles.debug || "none"}`));
    box.append(el("div", "sub", "Used for development now: " + (m.chosen || "none (curated designs are used)")));
  }

  async function permissions() {
    const p = await api("/v1/capabilities/permissions").catch(() => null), b = await api("/v1/capabilities/budget").catch(() => null);
    if (p) $("capRoots").value = (p.roots || []).join("\n");
    if (b) { $("capAttempts").value = b.attempts; $("capMinutes").value = b.minutes; }
  }

  async function refresh() {
    const list = await api("/v1/capabilities").catch(() => []);
    // Rail: one entry per enabled experience (dynamic navigation).
    const nav = $("capNav"); nav.replaceChildren();
    list.filter((c) => c.enabled && c.installed).forEach((c) => { const b = el("button", "nav cap-entry" + (current && current.id === c.id ? " active" : ""), c.name); b.type = "button"; b.dataset.cap = c.id;
      b.title = `${c.styleName} ${c.kind}${c.default ? " (default)" : ""}`; b.addEventListener("click", () => open(c)); nav.append(b); });
    if (!$("view-capabilities").classList.contains("hidden")) {
      const ul = $("capList"); ul.replaceChildren(...list.map((c) => card(c)));
      $("capEmpty").classList.toggle("hidden", list.length > 0);
      runs(); models(); permissions();
    }
    return list;
  }

  function open(c) {
    if (current && current.id !== c.id) api(`/v1/capabilities/${current.id}/closed`, { method: "POST", json: {} }).catch(() => {});
    current = c;
    const hands = $("handsFrame"); if (hands && /hands\.html/.test(hands.src)) hands.src = "about:blank"; // the camera stops when Hands is left
    document.querySelectorAll(".nav").forEach((b) => b.classList.toggle("active", b.dataset.cap === c.id));
    document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-experience"));
    document.body.classList.add("primus-paused");
    $("viewTitle").textContent = c.name;
    $("expTitle").textContent = `${c.styleName} ${c.kind} · v${c.version}`;
    $("expDefault").classList.toggle("hidden", !!c.default); $("expDefault").onclick = async () => { await act(c, "default"); c.default = true; $("expDefault").classList.add("hidden"); };
    const f = $("expFrame");
    if (f.getAttribute("src") !== c.url) f.setAttribute("src", c.url);
    f.title = c.name;
    api(`/v1/capabilities/${c.id}/opened`, { method: "POST", json: {} }).catch(() => {});
    clearInterval(beat); beat = setInterval(() => { if (current && !document.hidden) api(`/v1/capabilities/${current.id}/opened`, { method: "POST", json: {} }).catch(() => {}); }, 30000);
    refresh();
  }
  function close() {
    if (current) api(`/v1/capabilities/${current.id}/closed`, { method: "POST", json: {} }).catch(() => {});
    current = null; clearInterval(beat); $("expFrame").setAttribute("src", "about:blank");
  }

  /** Development progress: the workflow's steps as they happen, then the result. */
  function progress(r) {
    const box = el("div", "cap-progress");
    const head = el("div", "cap-state cap-state-" + r.state, { running: "Developing…", installed: "Installed", failed: "Not installed", declined: "Not built" }[r.state] || r.state);
    box.append(head);
    const ol = el("ol", "cap-steps");
    // Every step in order: a development that took several attempts shows each attempt and what failed.
    (r.steps || []).forEach((s) => { const li = el("li", "step-" + s.status); li.append(el("span", "step-name", (STEP_NAMES[s.step] || s.step) + ": "), el("span", "step-detail", s.detail)); ol.append(li); });
    box.append(ol);
    if (r.verification && r.verification.checks) {
      const d = el("details"); d.append(el("summary", null, `Verification: ${r.verification.checks.filter((c) => c.ok).length}/${r.verification.checks.length} checks passed in ${r.verification.browser}`));
      const ul = el("ul", "cap-checks"); r.verification.checks.forEach((c) => ul.append(el("li", c.ok ? "ok" : "bad", (c.ok ? "✓ " : "✗ ") + c.name + (c.detail ? " — " + c.detail : "")))); d.append(ul);
      (r.verification.unverified || []).forEach((u) => d.append(el("div", "cap-limits", "Not verified here: " + u)));
      box.append(d);
    }
    return box;
  }

  async function follow(runId, li) {
    const slot = el("div", "cap-slot"); li.append(slot);
    for (let i = 0; i < 900; i++) {
      const r = await api(`/v1/capabilities/runs/${runId}`).catch(() => null);
      if (r) { slot.replaceChildren(progress(r)); if (r.state !== "running") {
        li.querySelector(".body").textContent = r.message;
        if (r.result) { slot.append(card(r.result, { compact: true })); await refresh(); if (r.state === "installed") open(r.result); }
        return r; } }
      await new Promise((res) => setTimeout(res, 1000));
    }
  }

  /** What a capability instruction in chat asks the window to do. */
  function handle(action, li) {
    if (!action) return;
    if (action.action === "develop") follow(action.run, li);
    else if (action.action === "open") { refresh().then(() => open(action.capability)); }
    else if (action.action === "choose") {
      const ul = el("ul", "cap-cards"); (action.options || []).forEach((c) => ul.append(card(c, { choose: (x) => say(x.say), chooseLabel: "Choose" }))); li.append(ul);
    } else if (action.action === "list") { const ul = el("ul", "cap-cards"); (action.items || []).forEach((c) => ul.append(card(c, { compact: true }))); li.append(ul); }
    else if (action.action === "confirm") {
      const b = el("button", "small danger", "Uninstall " + action.capability.name); b.type = "button";
      b.addEventListener("click", async () => { b.disabled = true; try { const r = await api(`/v1/capabilities/${action.capability.id}/uninstall`, { method: "POST", json: { confirm: true } }); li.append(el("span", "meta", r.message)); } catch (e) { li.append(el("span", "meta", e.message)); } refresh(); });
      li.append(b);
    } else if (action.action === "updated" || action.action === "inspect") refresh();
  }

  function init(apiFn, sayFn, show) {
    api = apiFn; say = sayFn; showView = show;
    $("capBack").addEventListener("click", () => { close(); show("capabilities"); });
    $("capRefresh").addEventListener("click", refresh);
    $("capSavePerms").addEventListener("click", async () => {
      try { await api("/v1/capabilities/permissions", { method: "PUT", json: { roots: $("capRoots").value.split("\n").map((x) => x.trim()).filter(Boolean) } });
        await api("/v1/capabilities/budget", { method: "PUT", json: { attempts: +$("capAttempts").value, minutes: +$("capMinutes").value } }); $("capPermsNote").textContent = "Saved."; }
      catch (e) { $("capPermsNote").textContent = e.message; }
    });
    $("capBench").addEventListener("click", async () => { try { const r = await api("/v1/capabilities/models/benchmark", { method: "POST", json: {} }); $("capBench").textContent = `Measuring ${r.measuring.join(", ")} on ${r.tasks} tasks…`; } catch (e) { alert(e.message); } });
    $("capAsk").addEventListener("submit", (e) => { e.preventDefault(); const t = $("capAskText").value.trim(); if (!t) return; $("capAskText").value = ""; show("chat"); say(t); });
    document.querySelectorAll(".nav:not(.cap-entry)").forEach((b) => b.addEventListener("click", () => { if (current) close(); }));
    refresh();
  }

  window.CluCapabilities = { init, refresh, handle, open };
})();
