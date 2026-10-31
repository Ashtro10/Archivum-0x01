(function () {
  "use strict";

  var resourceTimer = null;
  var activePanel = "memory";

  function toast(text, isError) {
    if (typeof window.primusShowToast === "function") {
      window.primusShowToast(text, isError);
    }
  }

  function meterFill(id, percent) {
    var bar = document.getElementById(id);
    if (!bar) return;
    var value = percent == null ? 0 : Math.max(0, Math.min(100, Number(percent) || 0));
    bar.style.width = value + "%";
    bar.parentElement.setAttribute("aria-valuenow", String(Math.round(value)));
  }

  function bindSliderLabel(sliderId, labelId, suffix) {
    var slider = document.getElementById(sliderId);
    var label = document.getElementById(labelId);
    if (!slider || !label) return;
    var update = function () {
      label.textContent = String(slider.value) + (suffix || "");
    };
    slider.addEventListener("input", update);
    update();
  }

  function renderMemoryList(data) {
    var root = document.getElementById("memory-groups");
    if (!root) return;
    root.innerHTML = "";
    if (!data.ok) {
      root.textContent = data.error || "Memory unavailable.";
      return;
    }
    var items = data.memories || [];
    if (!items.length) {
      root.textContent = "No personal memories stored yet.";
      return;
    }
    items.forEach(function (row) {
      var line = document.createElement("div");
      line.className = "memory-row";
      var main = document.createElement("div");
      var title = document.createElement("div");
      title.className = "memory-title";
      var text = row.content || row.value || "";
      title.textContent = row.label ? row.label + " — " + text : text;
      var meta = document.createElement("div");
      meta.className = "memory-meta";
      meta.textContent = (row.source || "") + (row.updated_at ? " · " + row.updated_at : "");
      main.appendChild(title);
      main.appendChild(meta);
      var controls = document.createElement("div");
      controls.className = "memory-controls";
      var impLabel = document.createElement("label");
      impLabel.className = "memory-importance";
      impLabel.textContent = "Importance ";
      var imp = document.createElement("input");
      imp.type = "range";
      imp.min = "0";
      imp.max = "100";
      imp.value = String(row.importance != null ? row.importance : 50);
      imp.addEventListener("change", function () {
        setMemoryImportance(row.record_id, imp.value);
      });
      impLabel.appendChild(imp);
      var forget = document.createElement("button");
      forget.type = "button";
      forget.textContent = "Forget";
      forget.addEventListener("click", function () {
        forgetMemory(row.record_id);
      });
      controls.appendChild(impLabel);
      controls.appendChild(forget);
      line.appendChild(main);
      line.appendChild(controls);
      root.appendChild(line);
    });
  }

  async function loadMemory() {
    try {
      var res = await fetch("/api/memory");
      renderMemoryList(await res.json());
    } catch (_err) {
      renderMemoryList({ ok: false, error: "Could not load memory." });
    }
  }

  async function forgetMemory(recordId) {
    try {
      var res = await fetch("/api/memory/forget", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ record_id: recordId }),
      });
      var data = await res.json();
      if (data.ok) {
        toast("Memory forgotten.");
        loadMemory();
      } else {
        toast(data.error || "Could not forget memory.", true);
      }
    } catch (_err) {
      toast("Could not forget memory.", true);
    }
  }

  async function setMemoryImportance(recordId, importance) {
    try {
      var res = await fetch("/api/memory/importance", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ record_id: recordId, importance: Number(importance) }),
      });
      var data = await res.json();
      if (!data.ok) toast(data.error || "Importance save failed.", true);
    } catch (_err) {
      toast("Importance save failed.", true);
    }
  }

  async function loadKnowledge() {
    var root = document.getElementById("knowledge-list");
    if (!root) return;
    root.textContent = "Loading…";
    try {
      var res = await fetch("/api/knowledge");
      var data = await res.json();
      root.innerHTML = "";
      if (!data.ok) {
        root.textContent = data.error || "Knowledge unavailable.";
        return;
      }
      var rows = data.records || [];
      if (!rows.length) {
        root.textContent = "No knowledge records yet.";
        return;
      }
      rows.forEach(function (row) {
        var line = document.createElement("div");
        line.className = "knowledge-row";
        line.textContent = (row.category || "unknown") + " · " + (row.content || "");
        root.appendChild(line);
      });
    } catch (_err) {
      root.textContent = "Could not load knowledge.";
    }
  }

  async function loadRagStatus() {
    var root = document.getElementById("rag-status");
    if (!root) return;
    try {
      var res = await fetch("/api/rag/status");
      var data = await res.json();
      if (!data.ok) {
        root.textContent = data.error || "RAG status unavailable.";
        return;
      }
      if (data.embedding_ready == null && data.research_ready == null) {
        root.innerHTML =
          "<div class=\"rag-line warn\">Status unavailable — restart Primus (python -m astra) and hard-refresh (Ctrl+Shift+R).</div>" +
          "<div>Memory DB: " + (data.memory_db || "") + "</div>" +
          "<div>Vector index: " + (data.vector_index || "") + "</div>" +
          "<div>Knowledge DB: " + (data.knowledge_db || "") + "</div>";
        return;
      }
      root.innerHTML =
        "<div class=\"rag-line " + (data.research_ready ? "ok" : "warn") + "\">Research: " +
        (data.research_message || (data.research_ready ? "ready" : "unavailable")) + "</div>" +
        "<div class=\"rag-line " + (data.embedding_ready ? "ok" : "warn") + "\">Embeddings: " +
        (data.embedding_message || (data.embedding_ready ? "ready" : "unavailable")) + "</div>" +
        "<div>Memory DB: " + (data.memory_db || "") + "</div>" +
        "<div>Vector index: " + (data.vector_index || "") + "</div>" +
        "<div>Knowledge DB: " + (data.knowledge_db || "") + "</div>" +
        "<div>Memory records: " + String(data.memory_records != null ? data.memory_records : data.indexed_records || 0) + "</div>" +
        "<div>Knowledge records: " + String(data.knowledge_records || 0) + "</div>" +
        "<div>Index file: " + (data.index_exists ? "present" : "missing") + "</div>" +
        (data.allow_external ? "<div>Web search: enabled</div>" : "<div>Web search: disabled</div>");
    } catch (_err) {
      root.textContent = "Could not load RAG status.";
    }
  }

  async function loadResources() {
    try {
      var res = await fetch("/api/resources");
      var data = await res.json();
      if (!data.ok) {
        toast(data.error || "Could not load resources.", true);
        return;
      }
      meterFill("meter-cpu", data.cpu_percent);
      meterFill("meter-ram", data.ram_percent);
      meterFill("meter-gpu", data.gpu_util_percent);
      var cpuLabel = document.getElementById("label-cpu");
      var ramLabel = document.getElementById("label-ram");
      var gpuLabel = document.getElementById("label-gpu");
      var vramLabel = document.getElementById("label-vram");
      if (cpuLabel) cpuLabel.textContent = data.cpu_percent != null ? data.cpu_percent.toFixed(0) + "%" : "n/a";
      if (ramLabel) ramLabel.textContent = data.ram_percent != null ? data.ram_percent.toFixed(0) + "%" : "n/a";
      if (gpuLabel) gpuLabel.textContent = data.gpu_util_percent != null ? data.gpu_util_percent.toFixed(0) + "%" : "n/a";
      if (vramLabel) {
        vramLabel.textContent =
          data.vram_used_gb != null && data.vram_total_gb != null
            ? data.vram_used_gb + " / " + data.vram_total_gb + " GB"
            : data.gpu_name || "n/a";
      }
      var llmRoot = document.getElementById("llm-status");
      var llmPath = document.getElementById("reasoning-model-path");
      if (llmRoot) {
        var llmClass = data.llm_ready ? "ok" : "warn";
        llmRoot.innerHTML =
          "<div class=\"rag-line " + llmClass + "\">" +
          (data.llm_message || (data.llm_ready ? "Ready" : "Unavailable")) +
          "</div>" +
          "<div>Model: " + (data.model || "—") + "</div>" +
          "<div>Path: " + (data.model_path || "—") + "</div>" +
          "<div>File: " + (data.model_exists ? "found" : "missing") + "</div>";
      }
      if (llmPath && data.model_path && !llmPath.dataset.touched) {
        llmPath.value = data.model_path;
      }
    } catch (_err) {
      toast("Could not load resources.", true);
    }
  }

  function stopResourcePoll() {
    if (resourceTimer) {
      clearInterval(resourceTimer);
      resourceTimer = null;
    }
  }

  function startResourcePoll() {
    stopResourcePoll();
    loadResources();
    resourceTimer = setInterval(loadResources, 2000);
  }

  async function loadAutomation() {
    var events = document.getElementById("automation-events");
    var scheduled = document.getElementById("automation-scheduled");
    var recurring = document.getElementById("automation-recurring");
    try {
      var res = await fetch("/api/automation");
      var data = await res.json();
      if (!data.ok) {
        toast(data.error || "Could not load automation.", true);
        return;
      }
      function fill(el, rows, formatter) {
        if (!el) return;
        el.innerHTML = "";
        (rows || []).forEach(function (row) {
          var line = document.createElement("div");
          line.className = "automation-row";
          line.textContent = formatter(row);
          el.appendChild(line);
        });
        if (!rows || !rows.length) el.textContent = "None yet.";
      }
      fill(events, data.events, function (r) { return r.name + " · " + r.trigger + " → " + r.action; });
      fill(scheduled, data.scheduled, function (r) { return r.name + " @ " + r.run_at + " → " + r.action; });
      fill(recurring, data.recurring, function (r) { return r.name + " every " + r.interval_hours + "h → " + r.action; });
    } catch (_err) {
      toast("Could not load automation.", true);
    }
  }

  function applyVisualSettings() {
    var raw = localStorage.getItem("primus.visual") || "{}";
    var settings;
    try {
      settings = JSON.parse(raw);
    } catch (_err) {
      settings = {};
    }
    var theme = settings.theme || "blue";
    var accent = settings.accent || "#22e9ff";
    var transparency = settings.transparency != null ? settings.transparency : 72;
    var animate = settings.animate !== false;
    document.documentElement.style.setProperty("--primus-accent", accent);
    document.documentElement.style.setProperty("--panel-alpha", String(transparency / 100));
    document.body.classList.toggle("primus-no-anim", !animate);
    var frame = document.getElementById("jarvis-bg");
    if (frame) {
      var src = "/jarvis/index.html?background=1&theme=" + encodeURIComponent(theme) + "&v=4";
      if (frame.getAttribute("src") !== src) frame.setAttribute("src", src);
    }
    var themeEl = document.getElementById("v-theme");
    var accentEl = document.getElementById("v-accent");
    var transEl = document.getElementById("v-transparency");
    var animEl = document.getElementById("v-animate");
    if (themeEl) themeEl.value = theme;
    if (accentEl) accentEl.value = accent;
    if (transEl) transEl.value = String(transparency);
    if (animEl) animEl.checked = animate;
    var transLabel = document.getElementById("v-transparency-label");
    if (transLabel) transLabel.textContent = transparency + "%";
  }

  function saveVisualSettings() {
    var themeEl = document.getElementById("v-theme");
    var accentEl = document.getElementById("v-accent");
    var transEl = document.getElementById("v-transparency");
    var animEl = document.getElementById("v-animate");
    var payload = {
      theme: themeEl ? themeEl.value : "blue",
      accent: accentEl ? accentEl.value : "#22e9ff",
      transparency: transEl ? Number(transEl.value) : 72,
      animate: animEl ? animEl.checked : true,
    };
    localStorage.setItem("primus.visual", JSON.stringify(payload));
    applyVisualSettings();
    toast("Visual settings saved.");
  }

  async function loadReasoningPath() {
    var pathEl = document.getElementById("reasoning-model-path");
    if (!pathEl || pathEl.dataset.touched) return;
    try {
      var res = await fetch("/api/config");
      var data = await res.json();
      var reasoning = data.reasoning || {};
      if (reasoning.model_path) pathEl.value = reasoning.model_path;
    } catch (_err) {
      /* loadResources will try again */
    }
  }

  function onPanelOpen(name) {
    activePanel = name || "memory";
    if (name === "memory") loadMemory();
    if (name === "knowledge") loadKnowledge();
    if (name === "research") {
      loadRagStatus();
      if (typeof window.primusLoadConfig === "function") window.primusLoadConfig();
    }
    if (name === "resources") {
      loadReasoningPath();
      startResourcePoll();
    }
    else stopResourcePoll();
    if (name === "automation") loadAutomation();
    if (name === "visual") applyVisualSettings();
    if (name === "personality" && typeof window.primusLoadConfig === "function") {
      window.primusLoadConfig();
    }
  }

  function onSystemOpen() {
    onPanelOpen(activePanel);
  }

  function wireForms() {
    bindSliderLabel("memory-importance-new", "memory-importance-new-label", "");
    bindSliderLabel("p-warmth", "p-warmth-label", "");
    bindSliderLabel("p-directness", "p-directness-label", "");
    bindSliderLabel("p-humor", "p-humor-label", "");
    bindSliderLabel("p-formality", "p-formality-label", "");
    bindSliderLabel("p-verbosity", "p-verbosity-label", "");
    bindSliderLabel("v-transparency", "v-transparency-label", "%");

    var refreshBtn = document.getElementById("memory-refresh");
    if (refreshBtn) refreshBtn.addEventListener("click", loadMemory);

    var memoryAdd = document.getElementById("memory-add");
    if (memoryAdd) {
      memoryAdd.addEventListener("submit", async function (event) {
        event.preventDefault();
        var labelEl = document.getElementById("memory-label");
        var valueEl = document.getElementById("memory-content");
        var impEl = document.getElementById("memory-importance-new");
        try {
          var res = await fetch("/api/memory/add", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              label: labelEl ? labelEl.value : "",
              content: valueEl ? valueEl.value : "",
              importance: impEl ? Number(impEl.value) : 50,
            }),
          });
          var data = await res.json();
          if (data.ok) {
            toast("Memory updated");
            if (valueEl) valueEl.value = "";
            loadMemory();
          } else {
            toast(data.error || "Save failed.", true);
          }
        } catch (_err) {
          toast("Save failed.", true);
        }
      });
    }

    var uploadForm = document.getElementById("knowledge-upload");
    if (uploadForm) {
      uploadForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var fileEl = document.getElementById("knowledge-file");
        if (!fileEl || !fileEl.files || !fileEl.files[0]) {
          toast("Pick a file.", true);
          return;
        }
        try {
          var file = fileEl.files[0];
          var text = await file.text();
          var res = await fetch("/api/knowledge/upload", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ content: text, source: file.name }),
          });
          var data = await res.json();
          toast(data.ok ? "Document ingested." : (data.error || "Upload failed."), !data.ok);
          if (data.ok) loadKnowledge();
        } catch (_err) {
          toast("Upload failed.", true);
        }
      });
    }

    var syncBtn = document.getElementById("knowledge-sync-btn");
    if (syncBtn) {
      syncBtn.addEventListener("click", async function () {
        try {
          var pathEl = document.getElementById("knowledge-folder");
          var res = await fetch("/api/knowledge/sync", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path: pathEl ? pathEl.value : "" }),
          });
          var data = await res.json();
          toast(
            data.ok ? "Synced " + data.ingested + " files." : (data.error || "Sync failed."),
            !data.ok
          );
          if (data.ok) loadKnowledge();
        } catch (_err) {
          toast("Sync failed.", true);
        }
      });
    }

    var reasoningPath = document.getElementById("reasoning-model-path");
    if (reasoningPath) {
      reasoningPath.addEventListener("input", function () {
        reasoningPath.dataset.touched = "1";
      });
    }
    var reasoningForm = document.getElementById("reasoning-form");
    if (reasoningForm) {
      reasoningForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var pathEl = document.getElementById("reasoning-model-path");
        var modelPath = pathEl ? String(pathEl.value || "").trim() : "";
        if (!modelPath) {
          toast("Enter the full path to a .gguf file.", true);
          return;
        }
        var submitBtn = reasoningForm.querySelector("button[type='submit']");
        if (submitBtn) submitBtn.disabled = true;
        try {
          var res = await fetch("/api/reasoning", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ model_path: modelPath }),
          });
          var raw = await res.text();
          var data = {};
          try {
            data = raw ? JSON.parse(raw) : {};
          } catch (_parseErr) {
            toast(
              res.status === 404
                ? "Model save API missing — stop and restart Primus (python -m astra), then hard-refresh."
                : "Save failed (HTTP " + res.status + ").",
              true
            );
            return;
          }
          if (!data.ok) {
            toast(data.error || "Save failed.", true);
            return;
          }
          toast(
            data.restart_recommended
              ? "Model path saved. Restart Primus to load it."
              : "Model path saved.",
            false
          );
          if (pathEl) delete pathEl.dataset.touched;
          loadResources();
        } catch (err) {
          toast("Save failed: " + (err && err.message ? err.message : "network error"), true);
        } finally {
          if (submitBtn) submitBtn.disabled = false;
        }
      });
    }

    var reindexBtn = document.getElementById("rag-reindex");
    if (reindexBtn) {
      reindexBtn.addEventListener("click", async function () {
        reindexBtn.disabled = true;
        try {
          var res = await fetch("/api/rag/reindex", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
          var data = await res.json();
          toast(data.ok ? "Re-indexed " + data.indexed + " records." : (data.error || "Re-index failed."), !data.ok);
          loadRagStatus();
        } finally {
          reindexBtn.disabled = false;
        }
      });
    }

    var eventForm = document.getElementById("automation-event-form");
    if (eventForm) {
      eventForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var res = await fetch("/api/automation/event", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: document.getElementById("auto-event-name").value,
            trigger: document.getElementById("auto-event-trigger").value,
            action: document.getElementById("auto-event-action").value,
          }),
        });
        var data = await res.json();
        toast(data.ok ? "Event saved." : (data.error || "Save failed."), !data.ok);
        if (data.ok) loadAutomation();
      });
    }

    var schedForm = document.getElementById("automation-schedule-form");
    if (schedForm) {
      schedForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var res = await fetch("/api/automation/schedule", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: document.getElementById("auto-sched-name").value,
            run_at: document.getElementById("auto-sched-at").value,
            action: document.getElementById("auto-sched-action").value,
          }),
        });
        var data = await res.json();
        toast(data.ok ? "Task scheduled." : (data.error || "Save failed."), !data.ok);
        if (data.ok) loadAutomation();
      });
    }

    var recurForm = document.getElementById("automation-recurring-form");
    if (recurForm) {
      recurForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var res = await fetch("/api/automation/recurring", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: document.getElementById("auto-recur-name").value,
            interval_hours: document.getElementById("auto-recur-hours").value,
            action: document.getElementById("auto-recur-action").value,
          }),
        });
        var data = await res.json();
        toast(data.ok ? "Recurring task saved." : (data.error || "Save failed."), !data.ok);
        if (data.ok) loadAutomation();
      });
    }

    var visualForm = document.getElementById("visual-form");
    if (visualForm) {
      visualForm.addEventListener("submit", function (event) {
        event.preventDefault();
        saveVisualSettings();
      });
    }

    document.querySelectorAll(".sys-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var name = btn.getAttribute("data-panel") || "memory";
        document.querySelectorAll(".sys-btn").forEach(function (b) {
          b.classList.toggle("active", b === btn);
        });
        document.querySelectorAll(".panel").forEach(function (panel) {
          panel.classList.toggle("active", panel.id === "panel-" + name);
        });
        onPanelOpen(name);
      });
    });
  }

  window.PrimusSystem = {
    onOpen: onSystemOpen,
    onPanel: onPanelOpen,
    loadMemory: loadMemory,
    applyVisual: applyVisualSettings,
  };

  document.addEventListener("DOMContentLoaded", function () {
    wireForms();
    applyVisualSettings();
  });
})();
