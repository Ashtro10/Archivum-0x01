/*
 * CLU's first-run guide. Every number and statement on these pages comes from the host (/v1/setup/status), which reads
 * them from this PC; nothing here is decorative. It never downloads anything without the owner's confirmation, never
 * changes the chosen model by itself, and the whole app stays usable if it is skipped.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const MB = (b) => (b / 1048576 >= 1024 ? (b / 1073741824).toFixed(1) + " GB" : Math.round(b / 1048576) + " MB");
  const STEPS = ["Welcome", "Your PC", "Model", "Settings", "Try it", "Ready"];

  let api, onDone, status, step = 0, picked = null, tested = null, timer = null;
  const answers = { contextLength: 4096, threads: 0, acceleration: "cpu", ownerName: "", runInBackground: true, startWithWindows: false };

  async function maybeStart(a, done, force) {
    api = a; onDone = done;
    try { status = await api("/v1/setup/status"); } catch (e) { return false; }
    if (status.completed && !force) return false;
    step = 0; picked = status.activeModel || null; tested = null;
    answers.contextLength = status.defaults.contextLength; answers.threads = 0; answers.acceleration = status.defaults.acceleration;
    $("wizard").classList.remove("hidden"); render();
    $("wizBack").onclick = () => { if (step > 0) { step--; render(); } };
    $("wizNext").onclick = next; $("wizSkip").onclick = skip;
    return true;
  }

  function close() { clearInterval(timer); $("wizard").classList.add("hidden"); if (onDone) onDone(); }
  async function skip() { clearInterval(timer); $("wizard").classList.add("hidden"); if (onDone) onDone(); } // not recorded as completed: the guide offers itself again next time

  async function finish() {
    $("wizMsg").textContent = "Saving…";
    try {
      await api("/v1/setup/complete", { method: "POST", json: { contextLength: answers.contextLength, threads: answers.threads || undefined, acceleration: answers.acceleration, activeModel: picked || undefined, ownerName: answers.ownerName || undefined } });
      const cfg = { runInBackground: answers.runInBackground };
      await api("/v1/app/config", { method: "PUT", json: cfg });
      if (answers.startWithWindows) { try { await api("/v1/app/config", { method: "PUT", json: { startWithWindows: true } }); } catch (e) { $("wizMsg").textContent = e.message; return; } }
      close();
    } catch (e) { $("wizMsg").textContent = e.message; }
  }

  async function next() {
    $("wizMsg").textContent = "";
    if (step === STEPS.length - 1) return finish();
    step++; render();
  }

  function render() {
    $("wizTitle").textContent = STEPS[step];
    $("wizSteps").replaceChildren(...STEPS.map((_, i) => el("i", i <= step ? "on" : "")));
    $("wizBack").classList.toggle("hidden", step === 0);
    $("wizSkip").classList.toggle("hidden", step === STEPS.length - 1);
    $("wizNext").textContent = step === STEPS.length - 1 ? "Start using CLU" : "Next"; $("wizNext").disabled = false;
    clearInterval(timer);
    const body = $("wizBody"); body.replaceChildren();
    [welcome, system, model, settings, tryIt, ready][step](body);
  }

  function welcome(b) {
    b.append(el("p", null, "CLU is your personal assistant. It thinks on this computer: the model, your memories and your conversations stay here. This short guide checks your PC, helps you choose a language model, and lets you try it."),
      el("p", "muted", "Nothing is downloaded or installed without you saying so, and you can skip any step. CLU works without a model too (memories, people, documents), but it cannot talk until one is chosen."));
  }

  function system(b) {
    const s = status.system;
    const box = el("div"); s.checks.forEach((c) => { const r = el("div", "check"); r.append(el("span", "st " + c.status, c.status === "ok" ? "OK" : c.status === "warn" ? "Note" : "Problem"), (() => { const d = el("div"); d.append(el("strong", null, c.title), el("div", "muted", c.detail)); return d; })()); box.append(r); });
    b.append(box);
    const f = el("dl", "facts");
    [["Processor", s.cpu + `, ${s.logicalCores} logical cores`], ["Memory", MB(s.totalMemoryMB * 1048576) + " installed; CLU may use up to " + MB(s.memoryBudgetMB * 1048576)], ["Free storage", MB(s.freeDiskMB * 1048576) + " in " + s.dataFolder],
     ["Graphics", s.gpus.length ? s.gpus.join(", ") : "none reported"], ["Engine", s.loadedEngineBuild]].forEach(([k, v]) => { f.append(el("dt", null, k), el("dd", null, v)); });
    b.append(f);
    if (!s.compatible) { b.append(el("div", "notes", "This PC does not meet a requirement above. You can continue, but CLU may not run well or at all.")); }
  }

  function model(b) {
    b.append(el("p", "muted", "A language model is a file of a few gigabytes. Pick one you already have, or download a suggested one. Larger models are smarter but slower and use more memory."));
    const have = el("div"); b.append(have);
    const renderHave = () => {
      have.replaceChildren();
      if (status.models.length) {
        have.append(el("h4", null, "Models CLU already knows"));
        status.models.forEach((m) => have.append(choice(m.id, m.name, `${MB(m.sizeBytes)} · ${m.quantization || "unknown quantisation"} · ${m.path}`)));
      }
    };
    const choice = (id, title, sub, extra) => {
      const c = el("div", "choice" + (picked === id ? " picked" : "")); const lab = el("label", "inline"); const r = el("input"); r.type = "radio"; r.name = "wizModel"; r.checked = picked === id;
      r.addEventListener("change", () => { picked = id; tested = null; document.querySelectorAll("#wizBody .choice").forEach((x) => x.classList.remove("picked")); c.classList.add("picked"); });
      const t = el("span"); t.append(el("strong", null, title)); lab.append(r, t); c.append(lab, el("div", "muted", sub)); if (extra) c.append(extra); return c;
    };
    renderHave();
    const scanBox = el("div"); b.append(el("h4", null, "Use a file on this PC"));
    const scanBtn = el("button", "small", "Find models on this PC"); scanBtn.type = "button"; const scanMsg = el("span", "muted"); const row = el("div", "form-actions"); row.append(scanBtn, scanMsg); b.append(row, scanBox);
    scanBtn.addEventListener("click", async () => {
      scanMsg.textContent = "Looking in " + status.scanFolders.length + " folders…"; scanBox.replaceChildren();
      try {
        const r = await api("/v1/setup/scan", { method: "POST", json: {} });
        scanMsg.textContent = r.found.length ? `${r.found.length} found` : "No unregistered .gguf files found in " + status.scanFolders.join(", ");
        r.found.forEach((f) => { const c = el("div", "choice"); c.append(el("strong", null, f.name), el("div", "muted", `${MB(f.sizeBytes)} · ${f.quantization || ""} ${f.architecture || ""} · ${f.path}`));
          if (!f.usable) c.append(el("div", "notes", "Not usable: " + f.problem));
          else { const bt = el("button", "small primary", "Use this file"); bt.type = "button"; bt.addEventListener("click", async () => { bt.disabled = true; try { const m = await api("/v1/models", { method: "POST", json: { path: f.path } }); status = await api("/v1/setup/status"); picked = m.id; render(); } catch (e) { bt.disabled = false; scanMsg.textContent = e.message; } }); c.append(bt); }
          scanBox.append(c); });
      } catch (e) { scanMsg.textContent = e.message; }
    });
    const folder = el("form", "inline-form form"); const inp = el("input"); inp.placeholder = "Or paste the full path of a .gguf file"; inp.setAttribute("aria-label", "Model file path"); const add = el("button", "small", "Use it"); add.type = "submit"; folder.append(inp, add); b.append(folder);
    folder.addEventListener("submit", async (e) => { e.preventDefault(); if (!inp.value.trim()) return; try { const m = await api("/v1/models", { method: "POST", json: { path: inp.value.trim() } }); status = await api("/v1/setup/status"); picked = m.id; render(); } catch (err) { scanMsg.textContent = err.message; } });
    b.append(el("h4", null, "Or download one"));
    status.suggested.forEach((m) => {
      const c = el("div", "choice"); c.append(el("strong", null, `${m.name} (${m.quantization})`), el("div", "muted", `${MB(m.sizeBytes)} download · needs about ${MB(m.estimatedRamBytes)} of memory at ${m.estimateContext} tokens`),
        el("div", m.suitability === "suitable" ? "muted" : "notes", (m.recommended ? "Recommended for this PC. " : "") + m.suitabilityDetail));
      const dl = status.downloads.find((d) => d.modelId === m.id);
      if (m.present && !dl) c.append(el("div", "muted", "Already downloaded."));
      else if (dl && (dl.state === "downloading" || dl.state === "starting" || dl.state === "verifying")) c.append(progress(dl));
      else if (dl && dl.state === "done") { c.append(el("div", "muted", dl.message)); if (dl.registeredId) { const use = el("button", "small primary", picked === dl.registeredId ? "Selected" : "Use this model"); use.type = "button"; use.addEventListener("click", async () => { status = await api("/v1/setup/status"); picked = dl.registeredId; render(); }); c.append(use); } }
      else { if (dl) c.append(el("div", "notes", dl.message)); const bt = el("button", "small", "Download…"); bt.type = "button"; bt.addEventListener("click", () => CluWizard.confirmDownload(api, m, (job) => { status.downloads = [job]; render(); })); c.append(bt); }
      b.append(c);
    });
    if (status.downloads.some((d) => ["downloading", "starting", "verifying"].includes(d.state))) timer = setInterval(async () => { try { status = await api("/v1/setup/status"); if (!status.downloads.some((d) => ["downloading", "starting", "verifying"].includes(d.state))) { render(); } else document.querySelectorAll("#wizBody .bar > i").forEach((i, k) => { const d = status.downloads.filter((x) => ["downloading", "starting", "verifying"].includes(x.state))[k]; if (d) i.style.width = pct(d) + "%"; }); } catch (e) { /* retry */ } }, 1000);
    b.append(el("p", "muted", "No model yet? You can skip this and add one later in Models."));
  }
  const pct = (d) => (d.totalBytes ? Math.min(100, Math.round((100 * d.bytes) / d.totalBytes)) : 0);
  function progress(d) {
    const w = el("div"); w.append(el("div", "muted", `${d.state === "verifying" ? "Verifying" : "Downloading"}: ${MB(d.bytes)} of ${MB(d.totalBytes)}`)); const bar = el("div", "bar"); const i = el("i"); i.style.width = pct(d) + "%"; bar.append(i); w.append(bar);
    const c = el("button", "small", "Cancel"); c.type = "button"; c.addEventListener("click", () => api(`/v1/setup/downloads/${d.id}/cancel`, { method: "POST", json: {} })); w.append(c); return w;
  }

  function settings(b) {
    b.append(el("p", "muted", "These are sensible defaults. You can change all of them later in Models and Settings."));
    const field = (label, input, hint) => { const l = el("label", null, label); l.append(input); if (hint) l.append(el("span", "muted", " " + hint)); return l; };
    const ctx = el("select"); [2048, 4096, 8192, 16384].forEach((v) => ctx.append(new Option(v + " tokens" + (v === 4096 ? " (default)" : ""), v))); ctx.value = answers.contextLength; ctx.addEventListener("change", () => { answers.contextLength = Number(ctx.value); });
    const thr = el("input"); thr.type = "number"; thr.min = 0; thr.max = 128; thr.value = answers.threads; thr.addEventListener("input", () => { answers.threads = Number(thr.value) || 0; });
    const acc = el("select"); acc.append(new Option("CPU (works everywhere)", "cpu")); if (status.system.vulkanEngineInstalled) acc.append(new Option("GPU (Vulkan, experimental: used only if it starts)", "gpu")); acc.value = answers.acceleration; acc.addEventListener("change", () => { answers.acceleration = acc.value; });
    const name = el("input"); name.placeholder = "What should CLU call you? (optional)"; name.value = answers.ownerName; name.addEventListener("input", () => { answers.ownerName = name.value; });
    const bg = el("input"); bg.type = "checkbox"; bg.checked = answers.runInBackground; bg.addEventListener("change", () => { answers.runInBackground = bg.checked; });
    const st = el("input"); st.type = "checkbox"; st.checked = answers.startWithWindows; st.disabled = false; st.addEventListener("change", () => { answers.startWithWindows = st.checked; });
    b.append(field("Your name", name), field("Context length", ctx, "How much of the conversation the model can see. Longer uses more memory."), field("CPU threads", thr, "0 = automatic (" + status.defaults.threads + " here)"), field("Acceleration", acc, status.system.vulkanEngineInstalled ? "" : "No GPU engine is installed; CLU uses the CPU."));
    const l1 = el("label", "inline"); l1.append(bg, document.createTextNode("Keep CLU running in the notification area when I close its window")); const l2 = el("label", "inline"); l2.append(st, document.createTextNode("Start CLU when I sign in to Windows"));
    b.append(l1, l2, el("p", "muted", "Start with Windows is off unless you turn it on here. CLU runs as your own user, never as a service."));
  }

  function tryIt(b) {
    if (!picked) { b.append(el("p", null, "No model is chosen, so there is nothing to test yet."), el("p", "muted", "That is fine: CLU opens normally. Choose a model in Models whenever you are ready.")); return; }
    b.append(el("p", "muted", "CLU will load the chosen model and ask it for one short sentence. The first load can take a while."));
    const out = el("div"); const btn = el("button", "small primary", tested ? "Run the test again" : "Run a short test"); btn.type = "button"; b.append(btn, out);
    const show = (t) => { out.replaceChildren(); const f = el("dl", "facts"); [["Reply", t.text || "(no text)"], ["Engine", t.backend], ["Threads", t.threads], ["Context", t.contextSize + " tokens"], ["Load time", (t.loadMs / 1000).toFixed(1) + " s"], ["First word after", t.firstTokenMs + " ms"], ["Speed", t.tokensPerSecond.toFixed(1) + " tokens/s"]].forEach(([k, v]) => f.append(el("dt", null, k), el("dd", null, String(v)))); out.append(f); if (t.note) out.append(el("div", "notes", t.note)); };
    if (tested) show(tested);
    btn.addEventListener("click", async () => {
      btn.disabled = true; out.replaceChildren(el("div", "muted", "Loading the model and generating…"));
      try { await api("/v1/models/config", { method: "PUT", json: { contextLength: answers.contextLength, acceleration: answers.acceleration, ...(answers.threads ? { threads: answers.threads } : {}) } }).catch(() => {}); await api(`/v1/models/${picked}/select`, { method: "POST", json: {} }); tested = await api("/v1/setup/test", { method: "POST", json: {} }); show(tested); btn.textContent = "Run the test again"; }
      catch (e) { out.replaceChildren(el("div", "notes", "The test did not work: " + e.message + (e.data && e.data.hint ? " " + e.data.hint : "") + " You can still finish; the details are in Models.")); }
      btn.disabled = false;
    });
  }

  function ready(b) {
    b.append(el("p", null, picked ? "CLU is ready." : "CLU is ready, without a model for now."));
    const f = el("dl", "facts"); const m = status.models.find((x) => x.id === picked);
    [["Model", m ? m.name : "none yet (add one in Models)"], ["Context", answers.contextLength + " tokens"], ["Acceleration", answers.acceleration === "gpu" ? "GPU requested (CLU reports what actually runs)" : "CPU"], ["Closing the window", answers.runInBackground ? "keeps CLU in the notification area" : "quits CLU"], ["Start with Windows", answers.startWithWindows ? "yes" : "no"], ["Your data", status.system.dataFolder]].forEach(([k, v]) => f.append(el("dt", null, k), el("dd", null, v)));
    b.append(f, el("p", "muted", "Open this guide again any time from Settings."));
  }

  // The consent step for every download: what, from where, how big, which licence, what it needs, and an explicit button.
  function confirmDownload(apiFn, m, started) {
    const d = $("dlDialog"), body = $("dlBody"); body.replaceChildren();
    const f = el("dl", "facts");
    [["Model", `${m.name} (${m.parameters}, ${m.quantization})`], ["From", m.source], ["File", m.fileName], ["Size", MB(m.sizeBytes) + " (about " + MB(m.storageNeededBytes) + " of free space needed)"], ["Saved to", (status ? status.system.dataFolder : (window.__cluSystem || {}).dataFolder || "CLU's data folder") + "\\models"], ["Licence", m.license], ["Memory when running", `about ${MB(m.estimatedRamBytes)} at ${m.estimateContext} tokens`], ["Suitability", m.suitabilityDetail]].forEach(([k, v]) => f.append(el("dt", null, k), el("dd", null, v)));
    const lic = el("a", null, "Read the licence"); lic.href = m.licenseUrl; lic.target = "_blank"; lic.rel = "noopener noreferrer";
    body.append(f, lic, el("p", "muted", "The download can be cancelled and resumed. CLU checks the file's size, its checksum (when the server publishes one) and that it is a usable model before it is registered. It does not change your selected model."));
    const sel = el("label", "inline"); const cb = el("input"); cb.type = "checkbox"; sel.append(cb, document.createTextNode("Use it as my model when it is ready")); body.append(sel);
    const msg = el("div", "notes"); body.append(msg);
    d.classList.remove("hidden");
    $("dlCancel").onclick = () => d.classList.add("hidden");
    $("dlConfirm").onclick = async () => {
      $("dlConfirm").disabled = true;
      try { const job = await apiFn("/v1/setup/download", { method: "POST", json: { modelId: m.id, confirm: true, select: cb.checked } }); d.classList.add("hidden"); started(job); }
      catch (e) { msg.textContent = e.message; }
      $("dlConfirm").disabled = false;
    };
  }

  window.CluWizard = { maybeStart, confirmDownload, progress, pct, MB };
})();
