(function () {

  "use strict";



  const messages = document.getElementById("messages");

  const form = document.getElementById("composer");

  const input = document.getElementById("input");

  const sendBtn = document.getElementById("send-btn");

  const stopBtn = document.getElementById("stop-btn");

  const micBtn = document.getElementById("mic-btn");

  const statusLine = document.getElementById("status-line");

  const jarvisFrame = document.getElementById("jarvis-bg");

  const toast = document.getElementById("toast");

  const viewTalk = document.getElementById("view-talk");

  const viewSystem = document.getElementById("view-system");

  const thinkingLayer = document.getElementById("thinking-layer");

  const memoryGroups = document.getElementById("memory-groups");

  const memoryRefresh = document.getElementById("memory-refresh");

  const memoryAdd = document.getElementById("memory-add");

  const personalityForm = document.getElementById("personality-form");

  const researchForm = document.getElementById("research-form");



  let busy = false;

  let toastTimer = null;

  let audioCtx = null;

  let cancelSpeak = null;

  let activeSpeaker = null;

  async function primeAudio() {
    const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextCtor) return;
    if (!audioCtx) audioCtx = new AudioContextCtor();
    if (audioCtx.state === "suspended") {
      try { await audioCtx.resume(); } catch (_err) {}
    }
  }

  function stripMarkdown(text) {
    return String(text || "")
      .replace(/\*\*([^*]+)\*\*/g, "$1")
      .replace(/\*([^*\n]+)\*/g, "$1")
      .replace(/__([^_]+)__/g, "$1")
      .replace(/_([^_\n]+)_/g, "$1")
      .replace(/`([^`]+)`/g, "$1")
      .replace(/^#+\s+/gm, "")
      .replace(/\*\*/g, "")
      .replace(/\*/g, "");
  }

  function plaintextForSpeech(text) {
    return stripMarkdown(text).replace(/\s+/g, " ").trim();
  }

  function clearThinking() {
    if (!thinkingLayer) return;
    thinkingLayer.textContent = "";
    thinkingLayer.classList.remove("active", "is-streaming");
  }

  function appendThinking(text) {
    if (!thinkingLayer || !text) return;
    thinkingLayer.classList.add("active", "is-streaming");
    thinkingLayer.textContent += String(text || "");
  }

  function finishThinking() {
    if (!thinkingLayer) return;
    thinkingLayer.classList.remove("is-streaming");
    if (!thinkingLayer.textContent.trim()) {
      thinkingLayer.classList.remove("active");
    }
  }

  function setJarvisState(name) {

    try {

      const core = jarvisFrame && jarvisFrame.contentWindow && jarvisFrame.contentWindow.jarvisCore;

      if (core && typeof core.setPrimusState === "function") {

        core.setPrimusState(name);

      }

    } catch (_err) {

      /* same-origin localhost */

    }

  }



  function forwardSpeakLevel(level) {
    try {
      const core = jarvisFrame && jarvisFrame.contentWindow && jarvisFrame.contentWindow.jarvisCore;
      if (core && typeof core.setSpeakLevel === "function") {
        core.setSpeakLevel(level);
      }
    } catch (_err) {}
  }

  async function postVoiceMode(state) {
    try {
      await fetch("/api/voice/mode", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ state: state }),
      });
    } catch (_err) {}
  }

  function speechChunks(text) {
    const line = plaintextForSpeech(text);
    if (!line) return [];
    // One RVC pass per request (~10s). Sentence splitting multiplied latency.
    const maxLen = 900;
    if (line.length <= maxLen) return [line];
    const chunks = [];
    let rest = line;
    while (rest.length > maxLen) {
      let cut = rest.lastIndexOf(". ", maxLen);
      if (cut < maxLen * 0.4) cut = rest.lastIndexOf(" ", maxLen);
      if (cut < 1) cut = maxLen;
      const end = rest[cut] === "." ? cut + 1 : cut;
      chunks.push(rest.slice(0, end).trim());
      rest = rest.slice(end).trim();
    }
    if (rest) chunks.push(rest);
    return chunks;
  }

  const SENTENCE_PAUSE_MS = 0;

  function pauseMs(ms) {
    return new Promise(function (resolve) {
      setTimeout(resolve, ms);
    });
  }

  async function synthesizeChunkOnce(line, signal) {
    const res = await fetch("/api/voice/tts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: line }),
      signal: signal,
    });
    const contentType = (res.headers.get("content-type") || "").toLowerCase();
    if (res.ok && (contentType.indexOf("audio/") >= 0 || contentType.indexOf("octet-stream") >= 0)) {
      const rate = parseInt(res.headers.get("x-sample-rate") || "48000", 10) || 48000;
      return { pcm: await res.arrayBuffer(), rate: rate };
    }
    const data = await res.json().catch(function () { return {}; });
    throw new Error(data.error || ("Offline TTS failed (HTTP " + res.status + ")"));
  }

  async function synthesizeChunk(line) {
    const controller = new AbortController();
    const timeout = setTimeout(function () { controller.abort(); }, 180000);
    try {
      try {
        return await synthesizeChunkOnce(line, controller.signal);
      } catch (err) {
        const msg = String(err || "");
        const transient = msg.indexOf("Failed to fetch") >= 0 || msg.indexOf("NetworkError") >= 0;
        if (!transient || controller.signal.aborted) throw err;
        await pauseMs(400);
        return await synthesizeChunkOnce(line, controller.signal);
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  async function backendUnreachableMessage() {
    try {
      const res = await fetch("/api/health", { method: "GET", cache: "no-store" });
      if (res.ok) {
        return "Chat request dropped. Primus is still running — send the message again. If voice is silent, wait for TTS warmup.";
      }
    } catch (_err) {}
    return "Backend unreachable. Restart Primus and hard-refresh (Ctrl+F5).";
  }

  async function speakReply(text) {
    const chunks = speechChunks(text);
    if (!chunks.length) return;
    if (cancelSpeak) {
      cancelSpeak();
      cancelSpeak = null;
    }

    let cancelled = false;
    cancelSpeak = function () {
      cancelled = true;
    };

    try {
      setStatus("Synthesizing voice…");
      await primeAudio();
      for (let i = 0; i < chunks.length; i++) {
        if (cancelled) break;
        const audio = await synthesizeChunk(chunks[i]);
        if (cancelled) break;
        setStatus("Speaking…");
        await playPcm(audio.pcm, audio.rate);
      }
      setStatus("");
    } catch (err) {
      showToast(String(err), true);
      setStatus("");
    }
  }

  async function transcribeBlob(blob) {
    const arrayBuffer = await blob.arrayBuffer();
    const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextCtor) throw new Error("Web Audio unavailable");
    const ctx = new AudioContextCtor();
    const decoded = await ctx.decodeAudioData(arrayBuffer.slice(0));
    await ctx.close();

    let samples;
    if (decoded.numberOfChannels > 1) {
      const left = decoded.getChannelData(0);
      const right = decoded.getChannelData(1);
      samples = new Float32Array(left.length);
      for (let i = 0; i < left.length; i++) samples[i] = (left[i] + right[i]) * 0.5;
    } else {
      samples = decoded.getChannelData(0);
    }

    const int16 = new Int16Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
      int16[i] = Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767)));
    }

    const res = await fetch("/api/voice/stt", {
      method: "POST",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Sample-Rate": String(decoded.sampleRate),
      },
      body: int16.buffer,
    });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || "STT failed");
    const text = String(data.text || "").trim();
    if (!text) {
      showToast("No speech detected.", true);
      return;
    }
    if (input) input.value = text;
    sendMessage(text);
  }

  function setupMic() {
    if (!micBtn) return;

    let mediaRecorder = null;
    let audioChunks = [];
    let micStream = null;
    let recording = false;

    function micError(message) {
      showToast(message, true);
      recording = false;
      micBtn.classList.remove("active");
      micBtn.setAttribute("aria-pressed", "false");
      setJarvisState("IDLE");
      postVoiceMode("idle");
    }

    async function toggleMic(event) {
      if (event) event.preventDefault();
      if (busy) return;

      if (recording) {
        recording = false;
        if (mediaRecorder && mediaRecorder.state !== "inactive") {
          mediaRecorder.stop();
        }
        return;
      }

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        micError("Microphone API unavailable.");
        return;
      }

      try {
        micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch (_err) {
        micError("Microphone permission denied.");
        return;
      }

      audioChunks = [];
      mediaRecorder = new MediaRecorder(micStream);
      mediaRecorder.ondataavailable = function (event) {
        if (event.data && event.data.size) audioChunks.push(event.data);
      };
      mediaRecorder.onstop = async function () {
        if (micStream) {
          micStream.getTracks().forEach(function (track) { track.stop(); });
          micStream = null;
        }
        micBtn.classList.remove("active");
        micBtn.setAttribute("aria-pressed", "false");
        if (!audioChunks.length) {
          setJarvisState("IDLE");
          postVoiceMode("idle");
          return;
        }
        setJarvisState("PROCESSING");
        postVoiceMode("processing");
        try {
          await transcribeBlob(new Blob(audioChunks, { type: mediaRecorder.mimeType || "audio/webm" }));
        } catch (err) {
          micError(String(err));
        } finally {
          setJarvisState("IDLE");
          postVoiceMode("idle");
        }
      };

      try {
        mediaRecorder.start();
        recording = true;
        setJarvisState("LISTENING");
        postVoiceMode("listening");
        micBtn.classList.add("active");
        micBtn.setAttribute("aria-pressed", "true");
      } catch (_err) {
        micError("Could not start microphone.");
      }
    }

    micBtn.title = "Record voice (offline Whisper STT)";
    micBtn.addEventListener("click", toggleMic);
  }

  async function postVoicePcm(samples) {
    if (!samples || !samples.length) return;
    try {
      await fetch("/api/voice/pcm", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ samples: samples, state: "speaking" }),
      });
    } catch (_err) {}
  }

  async function playPcm(arrayBuffer, sampleRate, options) {
    const opts = options || {};
    const background = !!opts.background;
    const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextCtor) {
      throw new Error("Web Audio unavailable");
    }
    if (!audioCtx) audioCtx = new AudioContextCtor();
    if (audioCtx.state === "suspended") {
      await audioCtx.resume();
      if (audioCtx.state === "suspended") {
        throw new Error("Browser blocked audio — click Send again after the reply appears.");
      }
    }

    const int16 = new Int16Array(arrayBuffer);
    const float32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768;

    const buffer = audioCtx.createBuffer(1, float32.length, sampleRate);
    buffer.copyToChannel(float32, 0);
    const source = audioCtx.createBufferSource();
    const analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    source.buffer = buffer;
    source.connect(analyser);
    analyser.connect(audioCtx.destination);

    setJarvisState("SPEAKING");
    postVoiceMode("speaking");

    let raf = 0;
    const freq = new Uint8Array(analyser.frequencyBinCount);
    function tick() {
      analyser.getByteFrequencyData(freq);
      let sum = 0;
      for (let i = 0; i < freq.length; i++) sum += freq[i];
      forwardSpeakLevel(Math.min(1, (sum / freq.length) / 128));
      raf = requestAnimationFrame(tick);
    }

    return new Promise(function (resolve) {
      source.onended = function () {
        if (raf) cancelAnimationFrame(raf);
        forwardSpeakLevel(0);
        if (!background) {
          setJarvisState("IDLE");
          postVoiceMode("idle");
        }
        cancelSpeak = null;
        resolve();
      };
      cancelSpeak = function () {
        try { source.stop(0); } catch (_err) {}
        if (raf) cancelAnimationFrame(raf);
        forwardSpeakLevel(0);
        if (!background) {
          setJarvisState("IDLE");
          postVoiceMode("idle");
        }
        cancelSpeak = null;
        resolve();
      };
      source.start(0);
      tick();
      postVoicePcm(Array.from(float32.filter(function (_v, idx) { return idx % 64 === 0; })));
    });
  }

  function forwardCursorLook(clientX, clientY) {

    try {

      const core = jarvisFrame && jarvisFrame.contentWindow && jarvisFrame.contentWindow.jarvisCore;

      if (core && typeof core.setCursorLook === "function") {

        core.setCursorLook(

          clientX / window.innerWidth - 0.5,

          clientY / window.innerHeight - 0.5

        );

      }

    } catch (_err) {}

  }



  function setStatus(text) {

    if (statusLine) statusLine.textContent = text || "";

  }



  function showToast(text, isError) {

    if (!toast || !text) return;

    toast.textContent = text;

    toast.hidden = false;

    toast.classList.toggle("error", !!isError);

    if (toastTimer) clearTimeout(toastTimer);

    toastTimer = setTimeout(function () {

      toast.hidden = true;

    }, 3200);

  }



  function appendMessage(speaker, text) {

    if (!messages) return;

    const wrap = document.createElement("article");

    wrap.className = "msg " + (speaker === "human" ? "user" : "primus");

    const label = document.createElement("div");

    label.className = "msg-label";

    label.textContent = speaker === "human" ? "USER" : "PRIMUS";

    const body = document.createElement("div");

    body.className = "msg-text";

    body.textContent = text;

    wrap.appendChild(label);

    wrap.appendChild(body);

    messages.appendChild(wrap);

    messages.scrollTop = messages.scrollHeight;

    return body;

  }

  const TTS_BATCH_MIN = 100;
  const TTS_BATCH_MAX = 520;
  const TTS_SINGLE_MIN = 45;

  function createStreamSpeaker() {
    let pending = "";
    let cancelled = false;
    let lastTtsError = null;
    let spokeOnce = false;
    const queued = new Set();
    const linesToSynth = [];
    const audioReady = [];
    const sentenceQueue = [];
    let synthRunning = false;
    let playRunning = false;

    function extractSentences(buffer, flush) {
      const ready = [];
      let rest = String(buffer || "");
      // Do not split numbered lists ("1. item") — only real sentence ends.
      const pattern = /(?:^|(?<=\D))([.!?…]+["']?)(?:\s+|\n+|$)/;
      while (rest.length > 0) {
        const match = rest.match(pattern);
        if (!match || match.index === undefined) break;
        const end = match.index + match[0].length;
        const sentence = rest.slice(0, end).trim();
        rest = rest.slice(end);
        if (sentence.length >= 3) ready.push(sentence);
        if (end === 0) break;
      }
      if (flush) {
        const tail = rest.trim();
        if (tail.length >= 3) ready.push(tail);
        rest = "";
      }
      return { ready: ready, rest: rest };
    }

    async function playLoop() {
      if (playRunning || cancelled) return;
      playRunning = true;
      try {
        while (audioReady.length && !cancelled) {
          const audio = audioReady.shift();
          if (!audio) continue;
          setStatus("Speaking…");
          await playPcm(audio.pcm, audio.rate, { background: true });
        }
      } catch (err) {
        if (!cancelled) {
          lastTtsError = err;
          showToast(String(err), true);
        }
      } finally {
        playRunning = false;
        if (audioReady.length && !cancelled) void playLoop();
      }
    }

    async function synthLoop() {
      if (synthRunning || cancelled) return;
      synthRunning = true;
      try {
        while (linesToSynth.length && !cancelled) {
          const line = linesToSynth.shift();
          if (!line) continue;
          setStatus("Synthesizing voice…");
          void primeAudio();
          try {
            const audio = await synthesizeChunk(line);
            if (cancelled || !audio) continue;
            audioReady.push(audio);
            void playLoop();
          } catch (err) {
            lastTtsError = err;
            if (!cancelled) showToast(String(err), true);
          }
        }
      } finally {
        synthRunning = false;
        if (linesToSynth.length && !cancelled) void synthLoop();
      }
    }

    function queueSpeech(batch) {
      const line = plaintextForSpeech(batch);
      if (cancelled || !line || queued.has(line)) return;
      queued.add(line);
      linesToSynth.push(line);
      void synthLoop();
    }

    function drainSentenceQueue(flushAll) {
      while (!cancelled) {
        if (!sentenceQueue.length) break;
        if (!spokeOnce) {
          queueSpeech(sentenceQueue.shift());
          spokeOnce = true;
          continue;
        }
        if (sentenceQueue.length >= 2) {
          let batch = sentenceQueue[0];
          let count = 1;
          while (
            count < sentenceQueue.length
            && (batch + " " + sentenceQueue[count]).length <= TTS_BATCH_MAX
          ) {
            batch = batch + " " + sentenceQueue[count];
            count++;
          }
          if (batch.length >= TTS_SINGLE_MIN || count >= 2) {
            for (let i = 0; i < count; i++) sentenceQueue.shift();
            queueSpeech(batch);
            continue;
          }
          break;
        }
        if (sentenceQueue[0].length >= TTS_SINGLE_MIN || flushAll) {
          queueSpeech(sentenceQueue.shift());
          continue;
        }
        break;
      }
    }

    function flushPending(flushAll) {
      const split = extractSentences(pending, flushAll);
      pending = split.rest;
      for (let i = 0; i < split.ready.length; i++) {
        sentenceQueue.push(split.ready[i]);
      }
      if (flushAll && pending.trim()) {
        sentenceQueue.push(pending.trim());
        pending = "";
      }
      drainSentenceQueue(flushAll);
    }

    const handle = {
      cancel: function () {
        cancelled = true;
        linesToSynth.length = 0;
        audioReady.length = 0;
        sentenceQueue.length = 0;
        if (activeSpeaker === handle) activeSpeaker = null;
      },
      onDelta: function (text) {
        pending += String(text || "");
        flushPending(false);
      },
      finish: async function () {
        flushPending(true);
        while ((synthRunning || playRunning || linesToSynth.length || audioReady.length) && !cancelled) {
          await pauseMs(120);
        }
        if (lastTtsError && !cancelled) throw lastTtsError;
      },
    };
    return handle;
  }

  async function consumeChatStream(message, handlers) {
    const controller = new AbortController();
    const timeout = setTimeout(function () { controller.abort(); }, 120000);
    let finished = false;
    try {
      const res = await fetch("/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: message }),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        let detail = "HTTP " + res.status;
        try {
          const err = await res.json();
          if (err && err.error) detail = err.error;
        } catch (_err) {}
        throw new Error(detail);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      while (!finished) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() || "";
        for (let i = 0; i < blocks.length; i++) {
          const block = blocks[i].trim();
          if (!block.startsWith("data:")) continue;
          const payload = JSON.parse(block.slice(5).trim());
          if (handlers.onEvent) handlers.onEvent(payload);
          if (payload.type === "done" || payload.type === "error") {
            finished = true;
          }
        }
      }
      try { await reader.cancel(); } catch (_err) {}
    } finally {
      clearTimeout(timeout);
    }
  }

  async function fetchChatReply(message, retries) {
    retries = retries == null ? 2 : retries;
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: message }),
    });
    const data = await res.json();
    if (!data.ok && data.error === "busy" && retries > 0) {
      setStatus("Still working on the last request…");
      await pauseMs(3000);
      return fetchChatReply(message, retries - 1);
    }
    if (!res.ok || !data.ok) {
      throw new Error((data && data.error) || ("HTTP " + res.status));
    }
    return data;
  }



  function setBusy(on) {

    busy = on;

    if (form) form.classList.toggle("busy", on);

    if (sendBtn) sendBtn.disabled = on;

    if (input) input.disabled = on;

  }



  function showView(name) {

    const talk = name === "talk";

    if (viewTalk) {

      viewTalk.classList.toggle("active", talk);

      viewTalk.hidden = !talk;

    }

    if (viewSystem) {

      viewSystem.classList.toggle("active", !talk);

      viewSystem.hidden = talk;

    }

    document.querySelectorAll(".nav-btn").forEach(function (btn) {

      btn.classList.toggle("active", btn.getAttribute("data-view") === name);

    });

    if (talk && input) input.focus();

    if (!talk && name === "system") {
      if (window.PrimusSystem && typeof window.PrimusSystem.onOpen === "function") {
        window.PrimusSystem.onOpen();
      }
    }

  }



  function showPanel(name) {

    document.querySelectorAll(".sys-btn").forEach(function (btn) {

      btn.classList.toggle("active", btn.getAttribute("data-panel") === name);

    });

    document.querySelectorAll(".panel").forEach(function (panel) {

      panel.classList.toggle("active", panel.id === "panel-" + name);

    });

    if (window.PrimusSystem && typeof window.PrimusSystem.onPanel === "function") {
      window.PrimusSystem.onPanel(name);
    }

  }



  async function loadGreeting() {

    try {

      const res = await fetch("/api/greeting");

      const data = await res.json();

      if (data && data.greeting) appendMessage("primus", data.greeting);

    } catch (_err) {

      appendMessage("primus", "Hello. I'm Primus.");

    }

    setJarvisState("IDLE");

  }



  async function loadConfig() {

    try {

      const res = await fetch("/api/config");

      const data = await res.json();

      if (!data.ok) return;

      const p = data.personality || {};

      const set = function (id, value) {

        const el = document.getElementById(id);

        if (el) el.value = value;

      };

      set("p-name", p.name || "");

      set("p-warmth", p.warmth != null ? p.warmth : 50);

      set("p-directness", p.directness != null ? p.directness : 50);

      set("p-humor", p.humor != null ? p.humor : 50);

      set("p-formality", p.formality != null ? p.formality : 50);

      set("p-verbosity", p.verbosity != null ? p.verbosity : 50);

      ["p-warmth", "p-directness", "p-humor", "p-formality", "p-verbosity"].forEach(function (id) {
        var el = document.getElementById(id);
        var label = document.getElementById(id + "-label");
        if (el && label) label.textContent = String(el.value);
      });

      const external = document.getElementById("r-external");

      if (external) external.checked = !!(data.research && data.research.allow_external);

      if (data.reduced_motion && jarvisFrame && jarvisFrame.contentWindow) {

        try {

          const core = jarvisFrame.contentWindow.jarvisCore;

          if (core && core.setReducedMotion) core.setReducedMotion(true);

        } catch (_err) {}

      }

    } catch (_err) {}

  }



  async function sendMessage(text) {

    const message = String(text || "").trim();

    if (!message || busy) return;

    if (activeSpeaker) {
      activeSpeaker.cancel();
      activeSpeaker = null;
    }
    if (cancelSpeak) {
      cancelSpeak();
      cancelSpeak = null;
    }

    void primeAudio();

    appendMessage("human", message);

    if (input) input.value = "";

    setBusy(true);

    setStatus("Preparing an answer…");

    setJarvisState("THINKING");

    clearThinking();

    const replyBody = appendMessage("primus", "");

    let replyText = "";

    const speaker = createStreamSpeaker();
    activeSpeaker = speaker;

    cancelSpeak = function () { speaker.cancel(); };

    try {
      let streamFailed = false;
      try {
        await Promise.race([
          consumeChatStream(message, {
            onEvent: function (event) {
              const kind = String(event.type || "");
              if (kind === "status") {
                setStatus(event.activity || "Preparing an answer…");
                setJarvisState(event.jarvis_state || "THINKING");
                return;
              }
              if (kind === "thinking") {
                appendThinking(String(event.text || ""));
                return;
              }
              if (kind === "delta") {
                replyText += String(event.text || "");
                if (replyBody) replyBody.textContent = stripMarkdown(replyText);
                if (messages) messages.scrollTop = messages.scrollHeight;
                if (replyText.length >= 8) {
                  setStatus("Answering…");
                  void primeAudio();
                }
                speaker.onDelta(String(event.text || ""));
                return;
              }
              if (kind === "done") {
                finishThinking();
                const full = String(event.reply || "");
                if (full && full.length > replyText.length) {
                  speaker.onDelta(full.slice(replyText.length));
                  replyText = full;
                } else if (full && !replyText) {
                  speaker.onDelta(full);
                  replyText = full;
                } else if (full) {
                  replyText = full;
                }
                if (replyBody) replyBody.textContent = stripMarkdown(replyText);
                setStatus("");
                setJarvisState(event.jarvis_state || "IDLE");
                if (event.memory_updated) showToast(event.memory_notice || "Memory updated");
                else if (event.memory_notice) showToast(event.memory_notice, true);
                if (!event.ok && replyText) {
                  setStatus("I could not complete that.");
                }
                return;
              }
              if (kind === "agent_state" || kind === "task" || kind === "tool" || kind === "plan" || kind === "reflection" || kind === "completed" || kind === "approval" || kind === "experience" || kind === "skill" || kind === "progress") {
                setStatus(event.state || event.message || event.name || "Working…");
                setJarvisState("THINKING");
                return;
              }
              if (kind === "tool_result") {
                setStatus(event.success ? "Observed tool result" : (event.message || "Tool failed"));
                return;
              }
              if (kind === "error") {
                if (event.ok === false) {
                  throw new Error(event.error || "Stream failed");
                }
                setStatus(event.message || event.error || "Approval required.");
                return;
              }
            },
          }),
          new Promise(function (_resolve, reject) {
            setTimeout(function () { reject(new Error("stream timeout")); }, 120000);
          }),
        ]);
      } catch (_streamErr) {
        streamFailed = true;
        if (!replyText.trim()) {
          setStatus("Preparing an answer…");
          const data = await fetchChatReply(message);
          replyText = String(data.reply || "");
          if (replyBody) replyBody.textContent = stripMarkdown(replyText);
          speaker.onDelta(replyText);
          setJarvisState(data.jarvis_state || "IDLE");
        }
        setStatus("");
        if (streamFailed && !replyText.trim()) {
          throw _streamErr;
        }
      }

      setBusy(false);
      if (input) input.focus();

      if (!replyText.trim()) {
        if (replyBody) replyBody.textContent = "Request failed.";
        setStatus("I could not complete that.");
      } else {
        await primeAudio();
        void speaker.finish().catch(function (err) {
          if (String(err)) showToast(String(err), true);
        }).finally(function () {
          setStatus("");
          setJarvisState("IDLE");
          postVoiceMode("idle");
          if (activeSpeaker === speaker) activeSpeaker = null;
        });
      }

    } catch (err) {

      speaker.cancel();
      if (activeSpeaker === speaker) activeSpeaker = null;

      if (replyBody && !replyText.trim()) {
        const errText = String(err || "");
        if (errText.indexOf("stream timeout") >= 0) {
          replyBody.textContent = "That took too long (model loading or research). Wait a moment and try again.";
        } else if (errText.indexOf("busy") >= 0) {
          replyBody.textContent = "Primus is still answering the previous message. Wait a few seconds and try again.";
        } else if (errText.indexOf("Failed to fetch") >= 0 || errText.indexOf("NetworkError") >= 0) {
          replyBody.textContent = await backendUnreachableMessage();
        } else {
          replyBody.textContent = errText || "Request failed.";
        }
      }

      setStatus(String(err));

      setJarvisState("THINKING");

      showToast(String(err), true);

    } finally {

      setBusy(false);

      cancelSpeak = null;

      if (input) input.focus();

    }

  }



  document.querySelectorAll(".nav-btn").forEach(function (btn) {

    btn.addEventListener("click", function () {

      showView(btn.getAttribute("data-view") || "talk");

    });

  });



  if (form) {

    form.addEventListener("submit", function (event) {

      event.preventDefault();

      sendMessage(input ? input.value : "");

    });

  }



  if (stopBtn) {

    stopBtn.addEventListener("click", function () {

      setBusy(false);

      setStatus("Stopped.");

      setJarvisState("IDLE");

      if (cancelSpeak) {
        cancelSpeak();
        cancelSpeak = null;
      }

    });

  }



  if (personalityForm) {

    personalityForm.addEventListener("submit", async function (event) {

      event.preventDefault();

      const payload = {

        name: document.getElementById("p-name").value,

        warmth: Number(document.getElementById("p-warmth").value),

        directness: Number(document.getElementById("p-directness").value),

        humor: Number(document.getElementById("p-humor").value),

        formality: Number(document.getElementById("p-formality").value),

        verbosity: Number(document.getElementById("p-verbosity").value),

      };

      try {

        const res = await fetch("/api/personality", {

          method: "POST",

          headers: { "Content-Type": "application/json" },

          body: JSON.stringify(payload),

        });

        const data = await res.json();

        showToast(data.ok ? "Personality saved." : (data.error || "Save failed."), !data.ok);

      } catch (_err) {

        showToast("Save failed.", true);

      }

    });

  }



  if (researchForm) {

    researchForm.addEventListener("submit", async function (event) {

      event.preventDefault();

      const external = document.getElementById("r-external");

      try {

        const res = await fetch("/api/research", {

          method: "POST",

          headers: { "Content-Type": "application/json" },

          body: JSON.stringify({ allow_external: !!(external && external.checked) }),

        });

        const data = await res.json();

        showToast(data.ok ? "Research settings saved." : (data.error || "Save failed."), !data.ok);

      } catch (_err) {

        showToast("Save failed.", true);

      }

    });

  }



  window.primusShowToast = showToast;
  window.primusLoadConfig = loadConfig;

  loadGreeting();

  loadConfig();
  setupMic();

  window.addEventListener("pointermove", function (event) {

    forwardCursorLook(event.clientX, event.clientY);

  });

  if (input) input.focus();

})();

