/*
 * The same shell on a tablet or phone. On the PC this file does nothing. Inside CLU's Android app (a WebView whose user
 * agent contains "CLU-Android" and which provides window.CluNative) it:
 *  - marks the page (body.android, body.tablet) so the layout suits touch and the screen size;
 *  - talks through the app's native voice session instead of the browser LiveKit client (the app keeps one LiveKit
 *    connection to the PC, with Android's echo cancellation and audio routing): same voice bar, same states;
 *  - shows where answers come from (your PC, this device, or offline) and reloads when that changes;
 *  - hides what only the PC window can do (the camera gesture view) and adds "Classic view" (the native screens).
 * Loaded after voice.js and before shell.js, so shell.js initialises this voice client.
 */
(function () {
  "use strict";
  const N = window.CluNative;
  if (!/CLU-Android/.test(navigator.userAgent) || !N) return;
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const parse = (s) => { try { return JSON.parse(s); } catch (e) { return null; } };

  document.documentElement.classList.add("android");
  const mark = () => { document.body.classList.add("android"); document.body.classList.toggle("tablet", Math.min(screen.width, screen.height) >= 600); };
  if (document.body) mark(); else document.addEventListener("DOMContentLoaded", mark);

  // ------------------------------------------------------------------------------------------ voice (native)
  const STATE_LABEL = { idle: "Waiting", listening: "Listening", thinking: "Thinking…", speaking: "Speaking", connecting: "Connecting…", reconnecting: "Reconnecting…", absent: "Connecting…" };
  const FACE = { thinking: "thinking", speaking: "speaking", listening: "idle", idle: "idle", connecting: "thinking", reconnecting: "thinking", absent: "thinking" };
  let hooks = null, active = false, timer = 0, last = null, userB = null, agentB = null;

  function init(api, h) {
    hooks = h;
    $("voiceBtn").disabled = false; $("voiceBtn").title = "";
    $("voiceBtn").addEventListener("click", () => (active ? end() : start()));
    $("voiceEnd").addEventListener("click", end);
    $("voiceMute").addEventListener("click", () => { if (last) N.setMic(!last.micOn); });
    $("voiceStop").addEventListener("click", () => N.interrupt());
    $("voiceTalk").classList.add("hidden");
  }
  function start() {
    active = true; setBar(true); show("connecting");
    const r = parse(N.voiceStart());
    if (r && r.error) { fail(r.error); return; }
    poll();
  }
  function end() {
    N.voiceEnd(); active = false; clearTimeout(timer); finishTurn();
    setBar(false); hooks.onIdle();
  }
  function fail(message) { active = false; clearTimeout(timer); setBar(true); $("voiceState").textContent = message; $("voiceState").dataset.state = "error"; hooks.onIdle(); }

  function poll() {
    clearTimeout(timer);
    if (!active) return;
    const s = parse(N.voiceState());
    if (s) render(s);
    timer = setTimeout(poll, document.hidden ? 1000 : 100);
  }
  function render(s) {
    const prev = last; last = s;
    if (s.error && (!prev || prev.error !== s.error)) note(s.error);
    const state = s.link === "reconnecting" ? "reconnecting" : s.link !== "connected" ? "connecting" : s.phase;
    show(state);
    $("voiceMute").textContent = s.micOn ? "Mute" : "Unmute"; $("voiceMute").disabled = s.link !== "connected";
    $("voiceStop").disabled = !s.busy;
    $("voiceHint").textContent = s.server ? "Just talk. Talk over CLU to interrupt it." : "Just talk. CLU answers on this device.";
    const priv = $("voicePrivacy"); priv.classList.toggle("hidden", !s.micOn);
    priv.textContent = s.server ? "● Microphone on — heard only by CLU on your PC" : "● Microphone on — heard only by CLU on this device";
    $("voiceVoice").textContent = s.server ? "Voice: your PC" : "Voice: this device";
    // Live words: your words while you speak, CLU's as they are spoken; a turn ends when CLU stops answering.
    if (s.userLive) { if (!userB || agentB) { finishTurn(); userB = hooks.bubble("user", ""); hooks.append(userB); } setText(userB, s.userLive); hooks.scroll(); }
    if (s.agentLive) { if (!agentB) { agentB = hooks.bubble("assistant", ""); hooks.append(agentB); } setText(agentB, s.agentLive); hooks.scroll(); }
    if (prev && prev.agentLive && !s.agentLive) finishTurn();
  }
  function finishTurn() { if (agentB) hooks.onReply(agentB); userB = null; agentB = null; }
  function setText(b, t) { const body = b.querySelector(".body") || b; body.textContent = t; }
  function show(state) {
    const label = STATE_LABEL[state] || state;
    $("voiceState").textContent = label; $("voiceState").dataset.state = state;
    hooks.presence(FACE[state] || "idle", "Voice: " + label);
  }
  function note(text) { $("voiceNote").textContent = text; clearTimeout(note.t); note.t = setTimeout(() => { $("voiceNote").textContent = ""; }, 6000); }
  function setBar(on) { $("voiceBar").classList.toggle("hidden", !on); $("voiceBtn").textContent = on ? "End voice" : "🎙 Talk"; $("voiceBtn").classList.toggle("active", on); if (!on) { $("voiceState").textContent = ""; $("voiceNote").textContent = ""; } }

  // Settings → Voice belongs to the PC's voice (shown when connected); the browser voice client is replaced.
  const original = window.CluVoice || {};
  window.CluVoice = { init, loadSettings: original.loadSettings || (() => {}), active: () => active };
  addEventListener("pagehide", () => { if (active) N.voiceEnd(); });

  // ------------------------------------------------------------------------------------------ the rest of the window
  const LINK_LABEL = { pc: "Connected to your PC", device: "On this device", offline: "PC offline — on this device" };
  let link = null;
  async function checkLink() {
    const token = sessionStorage.getItem("clu-token"); if (!token) return;
    try {
      const r = await fetch("/v1/shell/link", { method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: "{}" });
      if (!r.ok) return;
      const now = (await r.json()).link;
      const badge = $("linkBadge"); if (badge) { badge.textContent = LINK_LABEL[now] || now; badge.dataset.link = now; }
      markPcOnly(now);
      // Conversations, memories and settings come from wherever answers come from: show the new source's data.
      if (link && now !== link && !active && !($("input") && $("input").value.trim()) && !/thinking|speaking/.test(document.body.dataset.clu || "")) location.reload();
      link = now;
    } catch (e) { /* the app is closing */ }
  }
  // Capability detection: views that only the PC provides (coding agents, models, capabilities) are marked "PC" while
  // the PC isn't answering; they stay visible and explain themselves rather than pretending to work.
  let pcOnly = [];
  async function loadPlatform() {
    const token = sessionStorage.getItem("clu-token"); if (!token) return;
    try { const r = await fetch("/v1/shell/platform", { headers: { Authorization: "Bearer " + token } }); if (r.ok) { const p = await r.json(); pcOnly = p.pcOnly || []; markPcOnly(p.link); } } catch (e) { /* closing */ }
  }
  function markPcOnly(now) {
    pcOnly.forEach((v) => document.querySelectorAll(`.nav[data-view="${v}"]`).forEach((b) => {
      b.classList.toggle("needs-pc", now !== "pc"); b.title = now !== "pc" ? "Available when this device is connected to your PC" : "";
    }));
  }
  document.addEventListener("DOMContentLoaded", () => {
    mark();
    document.querySelectorAll('.nav[data-view="hands"]').forEach((b) => b.remove()); // the camera gesture view stays on the PC
    const rail = document.querySelector(".rail");
    if (rail) {
      const classic = el("button", "nav-classic", "Classic view");
      classic.type = "button"; classic.addEventListener("click", () => N.classic());
      rail.append(classic);
    }
    const top = document.querySelector(".topbar");
    if (top) { const b = el("span", "link-badge", ""); b.id = "linkBadge"; top.append(b); }
    setInterval(() => { if (!document.hidden) checkLink(); }, 5000); setTimeout(() => { loadPlatform(); checkLink(); }, 1500);
  });
})();
