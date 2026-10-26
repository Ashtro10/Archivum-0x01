# Installing Clu on the Xiaomi Pad 6 and iQOO Neo 9 Pro

Requirements: Android 13 or newer, arm64 (both devices qualify), ~400 MB free (APK + voice) plus ~2.2 GB for a 3B model.

## 1. Install the APK on the Xiaomi Pad 6, with no PC (same steps on the phone)

**Before you start:** at least 1 GB of free space for now (Settings → About tablet → Storage). A model needs about
2.2 GB more later. You need Wi-Fi for the download (65 MB).

1. **Download.** On the tablet, open **Chrome** (or the Mi Browser) and enter this address exactly:

   `https://github.com/Ashtro10/Archivum-0x01/raw/claude/clu-android-ai-build-5cspi2/dist/clu-release.apk`

   The download starts by itself; no GitHub account is needed. If Chrome warns *"File might be harmful"*, tap
   **Download anyway**. This warning appears for every APK from outside Play.
   *Alternative:* open `https://github.com/Ashtro10/Archivum-0x01/blob/claude/clu-android-ai-build-5cspi2/dist/clu-release.apk`
   and tap the **download (↓) icon** at the top right of the file view.
2. **Optional check:** in **Files** (or Chrome → ⋮ → Downloads), long-press `clu-release.apk` → Details. The size
   must be **83,142,567 bytes** (shown as about 79.3 MB or 83.1 MB depending on the app). A hash-checker app can
   confirm SHA-256 `041a43042356a25d61b05eba0b42103594814c804f35f378f291542140a034e1`.
3. **Open it.** Tap the finished download in Chrome's notification or download list, or open
   **Files → Downloads → clu-release.apk**.
4. **Allow the source (first time only).** HyperOS shows *"For your security, your tablet is not allowed to install
   unknown apps from this source"* → **Settings** → turn on **Allow from this source** → press Back. Manual path:
   Settings → **Apps** (or *Privacy protection*) → **Special app access / Special permissions** →
   **Install unknown apps** → **Chrome** (or Files) → Allow.
5. **Install.** Tap **Install**. HyperOS may run a security scan, ask you to sign in to a Xiaomi account, or wait
   through a 5–10 s countdown. If it reports *"unknown/unsafe app"*, choose **Continue installation / Install
   anyway**.
   *iQOO Neo 9 Pro (OriginOS):* the same flow. It may ask for your vivo account password, or show *"Install
   via secure installation"*; choose to continue.
6. **Open Clu** → complete the welcome screen (name, form of address, device role: **Home** on the tablet,
   **Portable** on the phone) → allow notifications. The microphone permission is asked when you first use voice.
7. **If installation fails:**

   | Message | Meaning | Fix |
   |---|---|---|
   | *App not installed as package conflicts with an existing package* | An older Clu with a different signature is installed (for example the earlier debug build). | Export memories first if you have any, then uninstall the old Clu and install again. |
   | *App not installed* (no detail) | Incomplete download or not enough space | Delete the file, free space, download again, and check the size. |
   | *There was a problem parsing the package* | Incomplete or corrupt download | Download again over stable Wi-Fi. |
   | *App isn't compatible with your device* | Not arm64 or not Android 13+ | Both target devices qualify; check Settings → About. |

**Updating later:** install a newer `clu-release.apk` over the existing app. Data is kept as long as the new APK is
signed with the same release key ([SIGNING_AND_BACKUP.md](SIGNING_AND_BACKUP.md)). Export memories before updates
anyway while the app is a prototype.

## 2. Keep Clu alive in the background (only if you use background listening / proactive messages)

* **Xiaomi Pad 6:** Settings → Apps → Manage apps → Clu → *Autostart* ON; *Battery saver* → **No restrictions**.
  In Recents, pull Clu down to lock it.
* **iQOO Neo 9 Pro:** Settings → Battery → Background power consumption management → Clu → **Allow background
  high power consumption**; Settings → Apps → Clu → *Autostart* ON (if shown).
* Then Settings (in Clu) → Wake words & activation → **Background listening** ON. A notification shows while the
  microphone is in use. Android 14 only allows starting it while Clu is open.

## 3. Connect Clu to its server (recommended)

Clu's best conversation quality comes from its server engine: a LiveKit Agents worker on your computer or on LiveKit
Cloud. Set it up with [LIVEKIT_AGENT.md](LIVEKIT_AGENT.md) §3, then in Clu: ☰ → **Connection** → *Clu server* →
**Token endpoint…** and **Device key…** → **Test connection**. Server mode needs network access for the whole
conversation. To use Clu without any network, choose *This device* and do step 3b.

## 3b. Load an on-device model and voice (the *This device* engine)

See [MODELS_AND_VOICE.md](MODELS_AND_VOICE.md). Recommended: Qwen2.5-3B-Instruct Q4_K_M on both devices (or the
1.5B model on the phone for speed). Then Voice → download the *Clu voice – Ryan* pack (67 MB).

## 4. Pair the phone and the tablet over the phone's hotspot

1. Turn on the hotspot on the **iQOO** and connect the **Pad 6** to it.
2. On the **tablet**: Clu → ⋮ → Phone & tablet → **Allow pairing (2 min)** (verify with PIN/device lock).
3. On the **phone**: Clu → ⋮ → Phone & tablet → **Pair with…** and pick the tablet's address (shown on the tablet
   under *This device*, usually `10.x`/`192.168.x`). On a hotspot, mDNS discovery is often blocked; the manual
   address always works. (If instead the tablet hosts the network, the phone can pick the *Gateway* address.)
4. Both screens show a **6-digit code**. Confirm only if they match.
5. Pairing performs a first sync. Afterwards sync runs every 5 minutes while the app is open (configurable) and on
   *Sync now*. With *Use the home device's model* = "fallback", the phone uses the tablet's model when it has none
   loaded itself.

## 5. Verify on the device

Clu → ⋮ → Settings → Developer & diagnostics → *Developer diagnostics* ON → ⋮ → **Diagnostics** →
*Run self-tests on this device*. It checks the database, voice models, speech synthesis, the language model (with
first-token latency and tokens/s), network and sync server. Diagnostics also lists measured latency samples.
