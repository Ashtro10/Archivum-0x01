/*
 * Talking to CLU from this window (M8). The window is an ordinary LiveKit participant: it joins a room on CLU's own
 * LiveKit server on 127.0.0.1 with a short-lived token from the host, publishes the microphone (with the browser's echo
 * cancellation, noise suppression and gain control, so CLU can be interrupted while it speaks) and plays CLU's voice.
 * Everything else happens on this PC: CLU (the agent in the room) runs voice detection, speech recognition, the local
 * model and speech synthesis. Nothing is recorded, and no audio leaves the PC.
 *
 * What CLU is doing comes from its lk.agent.state attribute (initializing/idle/listening/thinking/speaking); live words
 * arrive as lk.transcription text streams; Stop, mute, push-to-talk and manual talk are RPCs to CLU. Settings → Voice is
 * at the end of this file.
 */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const LK = window.LivekitClient;
  const STATE_LABEL = { initializing: "Getting ready…", idle: "Waiting for its name", listening: "Listening", thinking: "Thinking…", speaking: "Speaking", connecting: "Connecting…", reconnecting: "Reconnecting…" };
  const FACE = { thinking: "thinking", speaking: "speaking", listening: "idle", idle: "idle", initializing: "thinking", connecting: "thinking", reconnecting: "thinking" };
  const MODE_HINT = { continuous: "Just talk. Talk over CLU to interrupt it.", push_to_talk: "Hold Talk (or the space bar) while you speak.", wake_word: "Say “Clue” (or another wake word) first.", manual: "Press Talk to open the microphone, press again when done." };

  let api, hooks, room = null, session = null, agentState = "", mode = "continuous", muted = false, manualOpen = false, ending = false, retries = 0, analyser = null, meterTimer = 0;
  const segs = new Map(); // transcription segment id → bubble
  let lastMetrics = null;

  function init(a, h) {
    api = a; hooks = h;
    if (!LK) { $("voiceBtn").disabled = true; $("voiceBtn").title = "The voice client did not load."; return; }
    $("voiceBtn").addEventListener("click", () => (room ? end() : start()));
    $("voiceEnd").addEventListener("click", end);
    $("voiceMute").addEventListener("click", toggleMute);
    $("voiceStop").addEventListener("click", () => rpc("clu.interrupt", {}));
    const talk = $("voiceTalk");
    talk.addEventListener("pointerdown", () => { if (mode === "push_to_talk") ptt(true); });
    ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => talk.addEventListener(ev, () => { if (mode === "push_to_talk") ptt(false); }));
    talk.addEventListener("click", () => { if (mode === "manual") { manualOpen = !manualOpen; rpc("clu.set_mic", { enabled: manualOpen }); renderBar(); } });
    // Space bar = push-to-talk while the session runs and the focus is not in a text field.
    const typing = () => /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || "");
    document.addEventListener("keydown", (e) => { if (room && mode === "push_to_talk" && e.code === "Space" && !e.repeat && !typing()) { e.preventDefault(); ptt(true); } });
    document.addEventListener("keyup", (e) => { if (room && mode === "push_to_talk" && e.code === "Space" && !typing()) { e.preventDefault(); ptt(false); } });
    window.addEventListener("beforeunload", () => { if (session) navigator.sendBeacon && fetchStop(); });
  }

  // ------------------------------------------------------------------------------------------ session
  async function start() {
    ending = false; setBar(true); show("connecting", "Starting CLU's voice…");
    try {
      session = await api("/v1/voice/session", { method: "POST", json: { conversationId: hooks.conversation() || null } });
      hooks.setConversation(session.conversationId);
      const s = await api("/v1/voice/settings"); mode = s.mode; manualOpen = false;
      await connect();
      retries = 0;
    } catch (e) { fail(e.message); }
  }

  async function connect() {
    room = new LK.Room({ adaptiveStream: false, dynacast: false, disconnectOnPageLeave: true,
      audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1, deviceId: pref("mic") || undefined },
      audioOutput: { deviceId: pref("speaker") || undefined } });
    const E = LK.RoomEvent;
    room.on(E.TrackSubscribed, (track, pub, p) => { if (track.kind === "audio") { const a = track.attach(); a.id = "cluVoiceAudio"; a.className = "hidden"; document.body.append(a); } });
    room.on(E.TrackUnsubscribed, (track) => track.detach().forEach((x) => x.remove()));
    room.on(E.ParticipantAttributesChanged, (changed, p) => { if (p && p.identity === "clu") readAgent(p); });
    room.on(E.ParticipantConnected, (p) => { if (p.identity === "clu") readAgent(p); });
    room.on(E.ParticipantDisconnected, (p) => { if (p.identity === "clu" && !ending) show("reconnecting", "CLU left the room; reconnecting…"); });
    room.on(E.Reconnecting, () => show("reconnecting"));
    room.on(E.Reconnected, () => { const c = room.remoteParticipants.get("clu"); c ? readAgent(c) : show("connecting"); });
    room.on(E.Disconnected, () => { if (!ending) recover(); });
    room.on(E.DataReceived, (payload, p, kind, topic) => {
      try { const d = JSON.parse(new TextDecoder().decode(payload));
        if (topic === "clu.metrics") { lastMetrics = d; renderMetrics(); renderTrace(d); }
        else if (topic === "clu.events" && d.type === "interrupted") note(d.why === "barge-in" ? "Interrupted — go ahead." : "Stopped.");
      } catch (e) { /* not ours */ }
    });
    room.on(E.MediaDevicesError, (e) => fail("The microphone could not be opened: " + (e && e.message ? e.message : e)));
    room.on(E.AudioPlaybackStatusChanged, () => { if (!room.canPlaybackAudio) note("Click anywhere in the window to let CLU's voice play."); });
    room.registerTextStreamHandler("lk.transcription", async (reader, from) => {
      const text = await reader.readAll(); const at = reader.info.attributes || {};
      transcript(from.identity, at["lk.segment_id"] || reader.info.id, text, at["lk.transcription_final"] === "true", at["lk.transcribed_track_id"] === "mic");
    });
    show("connecting");
    await room.connect(session.url, session.token, { autoSubscribe: true });
    await room.startAudio().catch(() => {});
    await room.localParticipant.setMicrophoneEnabled(true);
    meter();
    const c = room.remoteParticipants.get("clu"); if (c) readAgent(c);
    renderBar();
  }

  /** The connection dropped for good (the browser gives up after its own retries): start a fresh session, up to 3 times. */
  async function recover() {
    cleanup(false);
    if (retries >= 3) { fail("Lost the connection to CLU's voice. Press Talk to try again."); return; }
    retries++; show("reconnecting", `Reconnecting (try ${retries} of 3)…`);
    await new Promise((r) => setTimeout(r, 1000 * retries));
    if (ending) return;
    try { session = await api("/v1/voice/session", { method: "POST", json: { conversationId: hooks.conversation() || null } }); await connect(); retries = 0; }
    catch (e) { recover(); }
  }

  async function end() {
    ending = true;
    const r = session && session.room; cleanup(true); session = null;
    if (r) await api("/v1/voice/session/stop", { method: "POST", json: { room: r } }).catch(() => {});
    setBar(false); hooks.onIdle();
  }
  function fetchStop() { try { fetch("/v1/voice/session/stop", { method: "POST", keepalive: true, headers: { Authorization: "Bearer " + hooks.token(), "Content-Type": "application/json" }, body: JSON.stringify({ room: session.room }) }); } catch (e) { /* closing */ } }

  function cleanup(all) {
    clearInterval(meterTimer); if (analyser) { analyser.cleanup(); analyser = null; }
    const r = room; room = null;
    if (r) { r.removeAllListeners(); r.disconnect().catch(() => {}); }
    document.querySelectorAll("#cluVoiceAudio").forEach((a) => a.remove());
    if (all) segs.clear();
  }

  function fail(message) { cleanup(true); session = null; setBar(true); show("error", message); $("voicePrivacy").classList.add("hidden"); hooks.onIdle(); }

  // ------------------------------------------------------------------------------------------ controls
  async function rpc(method, payload) {
    if (!room) return;
    try { await room.localParticipant.performRpc({ destinationIdentity: "clu", method, payload: JSON.stringify(payload), responseTimeout: 4000 }); }
    catch (e) { note("CLU did not answer: " + e.message); }
  }
  async function toggleMute() {
    if (!room) return; muted = !muted;
    await room.localParticipant.setMicrophoneEnabled(!muted).catch((e) => note(e.message));
    renderBar();
  }
  function ptt(down) { if (!room) return; $("voiceTalk").classList.toggle("active", down); rpc("clu.push_to_talk", { down }); }

  // ------------------------------------------------------------------------------------------ what CLU is doing
  function readAgent(p) {
    const a = p.attributes || {};
    if (a["clu.mode"]) mode = a["clu.mode"];
    agentState = a["lk.agent.state"] || "initializing";
    show(agentState); renderBar();
  }
  function show(state, text) {
    const label = text || STATE_LABEL[state] || state;
    $("voiceState").textContent = label; $("voiceState").dataset.state = state;
    if (state !== "error") hooks.presence(FACE[state] || "idle", "Voice: " + label);
  }
  function note(text) { $("voiceNote").textContent = text; clearTimeout(note.t); note.t = setTimeout(() => { $("voiceNote").textContent = ""; }, 6000); }

  function setBar(on) { $("voiceBar").classList.toggle("hidden", !on); $("voiceBtn").textContent = on ? "End voice" : "🎙 Talk"; $("voiceBtn").classList.toggle("active", on); if (!on) { $("voiceState").textContent = ""; $("voiceNote").textContent = ""; } }
  function renderBar() {
    const live = !!room;
    $("voiceMute").textContent = muted ? "Unmute" : "Mute"; $("voiceMute").disabled = !live;
    $("voiceStop").disabled = !live;
    const talk = $("voiceTalk");
    talk.classList.toggle("hidden", !(mode === "push_to_talk" || mode === "manual"));
    talk.textContent = mode === "manual" ? (manualOpen ? "Done talking" : "Talk") : "Hold to talk";
    talk.classList.toggle("active", mode === "manual" && manualOpen);
    $("voiceHint").textContent = MODE_HINT[mode] || "";
    // Privacy: visible whenever the microphone is published to CLU.
    const priv = $("voicePrivacy"); priv.classList.toggle("hidden", !live || muted);
    priv.textContent = mode === "wake_word" ? "● Microphone on — CLU only acts after its name" : "● Microphone on — heard only by CLU on this PC";
    $("voiceVoice").textContent = session ? "Voice: " + (session.voiceName || session.voice) : "";
  }
  function renderMetrics() {
    const m = lastMetrics; if (!m) return;
    const parts = [];
    if (m.speech_end_to_first_audio_ms > 0) parts.push(`reply began ${(m.speech_end_to_first_audio_ms / 1000).toFixed(1)} s after you stopped`);
    if (m.stt_finalize_ms >= 0) parts.push(`recognition ${m.stt_finalize_ms} ms`);
    if (m.first_token_ms > 0) parts.push(`first words ${m.first_token_ms} ms`);
    $("voiceMetrics").textContent = parts.join(" · ");
  }

  /** Every stage of the last turn, from the moment speech was detected (also in GET /v1/voice/status). */
  function renderTrace(d) {
    if (!d.stages) return;
    $("voiceTrace").textContent = `turn ${d.turn} — ${d.outcome}\n` + d.stages.map((s) => `+${String(s.ms).padStart(6)} ms  ${s.name}${s.detail ? "  " + s.detail : ""}`).join("\n");
  }

  /** Live words: your words replace themselves as recognition improves; CLU's arrive sentence by sentence. */
  function transcript(identity, seg, text, final, isUser) {
    const role = isUser ? "user" : "assistant";
    let b = segs.get(seg);
    if (!b) { b = hooks.bubble(role, ""); b.classList.add("voice"); segs.set(seg, b); hooks.append(b); }
    const body = b.querySelector(".body");
    if (isUser || final) body.textContent = text; else body.textContent += (body.textContent ? " " : "") + text;
    b.classList.toggle("partial", !final);
    if (final) { segs.delete(seg); if (!isUser) hooks.onReply(b); }
    hooks.scroll();
  }

  /** Microphone level → CLU's face, a few times a second (nothing is stored). */
  function meter() {
    clearInterval(meterTimer);
    const pub = room && room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
    if (!pub || !pub.track || !LK.createAudioAnalyser) return;
    try { analyser = LK.createAudioAnalyser(pub.track, { cloneTrack: true }); } catch (e) { return; }
    meterTimer = setInterval(() => { if (!analyser || document.hidden) return; const v = analyser.calculateVolume(); $("voiceLevel").style.width = Math.min(100, Math.round(v * 300)) + "%"; if (agentState === "speaking") window.CluFace && CluFace.pulse(); }, 150);
  }

  function pref(k) { try { return localStorage.getItem("clu-voice-" + k) || ""; } catch (e) { return ""; } }
  function setPref(k, v) { try { localStorage.setItem("clu-voice-" + k, v); } catch (e) { /* private mode */ } }

  // ------------------------------------------------------------------------------------------ Settings → Voice
  let voices = [], dlTimer = 0;
  async function loadSettings() {
    const box = $("voiceSettings"); if (!box) return;
    let st, s;
    try { [st, s, voices] = await Promise.all([api("/v1/voice/status"), api("/v1/voice/settings"), api("/v1/voice/voices")]); }
    catch (e) { $("vsMsg").textContent = e.message; return; }
    $("vsProblem").textContent = st.available ? "" : st.problem; $("vsProblem").classList.toggle("hidden", st.available);
    renderVoices(s);
    const f = $("voiceForm");
    f.elements.speed.value = s.speed; f.elements.volume.value = s.volume; f.elements.mode.value = s.mode; f.elements.endSilenceMs.value = s.endSilenceMs; f.elements.bargeInMs.value = s.bargeInMs;
    const lang = f.elements.language; lang.replaceChildren(new Option("English (live words while you speak)", "en"));
    if (s.whisperInstalled) [["auto", "Automatic (Whisper)"], ["hi", "Hindi (Whisper)"], ["de", "German (Whisper)"], ["fr", "French (Whisper)"], ["es", "Spanish (Whisper)"]].forEach(([v, l]) => lang.append(new Option(l, v)));
    lang.value = s.language; $("vsLangNote").textContent = s.whisperInstalled ? "Other languages are transcribed after you finish speaking." : "Other languages need the optional multilingual speech model, chosen in the installer.";
    f.elements.wakeWords.value = s.wakeWords.join(", "); f.elements.wakeThreshold.value = s.wakeThreshold; f.elements.farewells.value = s.farewells.join(", "); f.elements.vocabulary.value = (s.vocabulary || []).join(", ");
    f.elements.pronunciations.value = Object.entries(s.pronunciations).map(([k, v]) => `${k} = ${v}`).join("\n");
    f.elements.phoneAccess.checked = s.phoneAccess;
    $("vsPitch").textContent = s.supports.pitch ? "" : s.supports.pitchNote;
    $("vsStatus").textContent = `Speech: ${st.recognizer}. LiveKit server: ${st.server.toLowerCase()}${st.server === "RUNNING" ? " on " + st.localUrl : ""}.` + (st.sessions.length ? ` ${st.sessions.length} voice session(s) active.` : "");
    devicesUi(); phones(st);
    if (!box.dataset.wired) wireSettings();
  }

  function renderVoices(s) {
    const grid = $("voiceGrid"); grid.replaceChildren();
    voices.forEach((v) => {
      const c = el("div", "voice-card" + (v.selected ? " selected" : "")); c.dataset.voice = v.id;
      c.append(el("div", "voice-name", v.name + (v.selected ? " ✓" : "")), el("div", "muted", `${v.language} · ${v.accent} · ${v.presentation}`), el("div", "desc", v.character));
      const meta = el("div", "meta", v.installed ? (v.bundled ? "Installed with CLU" : "Downloaded") : `Not installed · ${Math.round(v.sizeBytes / 1048576)} MB download`);
      meta.title = `Licence: ${v.license}. Source: ${v.source}.`; c.append(meta);
      const btns = el("div", "btns");
      const prev = el("button", "small", "▶ Preview"); prev.type = "button"; prev.disabled = !v.installed; prev.addEventListener("click", () => preview(v.id, prev));
      btns.append(prev);
      if (v.installed) { const use = el("button", "small primary", v.selected ? "CLU's voice" : "Use this voice"); use.type = "button"; use.disabled = v.selected; use.addEventListener("click", () => save({ voice: v.id }, "Voice saved: CLU uses it on this PC and on your phone.")); btns.append(use); }
      else { const dl = el("button", "small", "Download…"); dl.type = "button"; dl.addEventListener("click", () => askDownload(v)); btns.append(dl); }
      c.append(btns); grid.append(c);
    });
  }

  let previewAudio = null;
  async function preview(id, btn) {
    btn.disabled = true; const was = btn.textContent; btn.textContent = "Synthesising…";
    try {
      const r = await fetch(`/v1/voice/voices/${id}/preview`, { method: "POST", headers: { Authorization: "Bearer " + hooks.token(), "Content-Type": "application/json" }, body: JSON.stringify({ text: $("vsPreviewText").value || null }) });
      if (!r.ok) throw new Error(((await r.json().catch(() => ({}))).error) || "HTTP " + r.status);
      const url = URL.createObjectURL(await r.blob());
      if (previewAudio) { previewAudio.pause(); URL.revokeObjectURL(previewAudio.src); }
      previewAudio = new Audio(url); if (pref("speaker") && previewAudio.setSinkId) await previewAudio.setSinkId(pref("speaker")).catch(() => {});
      await previewAudio.play();
    } catch (e) { $("vsMsg").textContent = "Preview failed: " + e.message; }
    btn.disabled = false; btn.textContent = was;
  }

  function askDownload(v) {
    const ok = confirm(`Download the voice “${v.name}”?\n\nSize: ${Math.round(v.sizeBytes / 1048576)} MB\nFrom: ${v.url || v.source}\nLicence: ${v.license}\n\nThe file is checked against its known fingerprint before it is installed. It is not selected automatically.`);
    if (!ok) return;
    api(`/v1/voice/packs/${v.pack}/download`, { method: "POST", json: { confirm: true } }).then(watchDownloads, (e) => { $("vsMsg").textContent = e.message; });
  }
  function watchDownloads() {
    clearInterval(dlTimer);
    const tick = async () => {
      const list = await api("/v1/voice/downloads").catch(() => []);
      $("vsDownloads").textContent = list.map((d) => `${d.pack}: ${d.state}${d.total ? " " + Math.round((100 * d.bytes) / d.total) + "%" : ""}${d.message ? " — " + d.message : ""}`).join("\n");
      if (!list.some((d) => d.state === "starting" || d.state === "downloading" || d.state === "verifying" || d.state === "unpacking")) { clearInterval(dlTimer); loadSettings(); }
    };
    tick(); dlTimer = setInterval(tick, 1000);
  }

  async function save(body, okText) {
    $("vsMsg").textContent = "";
    try { const r = await api("/v1/voice/settings", { method: "PUT", json: body }); $("vsMsg").textContent = (okText || "Saved.") + (r.note ? " " + r.note : ""); await loadSettings(); return r; }
    catch (e) { $("vsMsg").textContent = e.message; return null; }
  }

  function wireSettings() {
    $("voiceSettings").dataset.wired = "1";
    const f = $("voiceForm");
    f.addEventListener("submit", (e) => {
      e.preventDefault();
      const pron = {}; f.elements.pronunciations.value.split("\n").forEach((line) => { const i = line.indexOf("="); if (i > 0) { const k = line.slice(0, i).trim(), v = line.slice(i + 1).trim(); if (k && v) pron[k] = v; } });
      const list = (x) => x.split(",").map((w) => w.trim()).filter(Boolean);
      save({ speed: Number(f.elements.speed.value), volume: Number(f.elements.volume.value), language: f.elements.language.value, mode: f.elements.mode.value,
        endSilenceMs: Number(f.elements.endSilenceMs.value), bargeInMs: Number(f.elements.bargeInMs.value), wakeWords: list(f.elements.wakeWords.value),
        wakeThreshold: Number(f.elements.wakeThreshold.value), farewells: list(f.elements.farewells.value), vocabulary: list(f.elements.vocabulary.value), pronunciations: pron, phoneAccess: f.elements.phoneAccess.checked },
      "Saved. A running voice session picks up the change at once (listening mode: at the next session).");
    });
    $("vsMic").addEventListener("change", (e) => { setPref("mic", e.target.value); if (room) room.switchActiveDevice("audioinput", e.target.value).catch((x) => note(x.message)); });
    $("vsSpeaker").addEventListener("change", (e) => { setPref("speaker", e.target.value); if (room) room.switchActiveDevice("audiooutput", e.target.value).catch((x) => note(x.message)); });
    $("vsPair").addEventListener("click", async () => {
      const name = prompt("A name for the phone (e.g. ‘Pixel’):", "Phone"); if (name == null) return;
      try { const r = await api("/v1/voice/devices", { method: "POST", json: { name } });
        $("vsPairResult").textContent = `Paired “${r.name}”.\nEndpoint: ${r.endpoint || "(turn on phone access first)"}\nKey: ${r.key}\n${r.note}`; phones(await api("/v1/voice/status")); }
      catch (e) { $("vsMsg").textContent = e.message; }
    });
  }

  async function devicesUi() {
    if (!LK || !LK.Room.getLocalDevices) return;
    try {
      const [mics, outs] = await Promise.all([LK.Room.getLocalDevices("audioinput", false), LK.Room.getLocalDevices("audiooutput", false)]);
      const fill = (sel, list, cur) => { sel.replaceChildren(new Option("System default", "")); list.forEach((d) => sel.append(new Option(d.label || "Device " + d.deviceId.slice(0, 6), d.deviceId))); sel.value = cur; };
      fill($("vsMic"), mics, pref("mic")); fill($("vsSpeaker"), outs, pref("speaker"));
    } catch (e) { /* no device access yet: names appear after the first voice session */ }
  }

  async function phones(st) {
    const r = await api("/v1/voice/devices").catch(() => null); if (!r) return;
    const ul = $("vsPhones"); ul.replaceChildren();
    r.devices.forEach((d) => { const li = el("li", null, `${d.name} — ${d.lastSeen ? "last connected " + new Date(d.lastSeen).toLocaleString() : "not connected yet"} `);
      const rm = el("button", "small danger", "Remove"); rm.type = "button"; rm.addEventListener("click", async () => { if (!confirm(`Remove ${d.name}? Its key stops working at once.`)) return; await api(`/v1/voice/devices/${d.id}/remove`, { method: "POST", json: {} }); phones(st); });
      li.append(rm); ul.append(li); });
    $("vsPhoneInfo").textContent = r.phoneAccess ? (r.endpoint ? `Phones on your network connect to ${r.endpoint} (only this private network; never the internet).` : "Phone access is on, but this PC has no private-network address.") : "Phone access is off: CLU's voice is reachable from this PC only.";
  }

  window.CluVoice = { init, loadSettings, active: () => !!room };
})();
