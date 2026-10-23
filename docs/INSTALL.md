# Installing Clu on the Xiaomi Pad 6 and iQOO Neo 9 Pro

Requirements: Android 13 or newer, arm64 (both devices qualify), ~400 MB free (APK + voice) plus ~2.2 GB for a 3B model.

## 1. Install the APK (both devices)

1. Download `dist/clu-debug.apk` from the repository on the device (GitHub → `dist` → `clu-debug.apk` → *Download raw*),
   or copy it over USB.
2. Open it from Files/Downloads. When asked, allow installing apps from that source:
   * **Xiaomi Pad 6 (HyperOS/MIUI):** Settings → Privacy protection → Special permissions → Install unknown apps →
     your file manager/browser → Allow. HyperOS may show a security scan – choose *Continue installation*.
   * **iQOO Neo 9 Pro (OriginOS/Funtouch):** Settings → Security/Privacy → Install unknown apps → allow the app
     you're installing from. You may need to confirm with your vivo account password.
3. Open **Clu**, fill in the welcome screen (your name, form of address, device role: *Home* on the tablet,
   *Portable* on the phone), allow notifications.

Updating: install a newer APK over the old one. The APK in this repository is signed with a debug key generated
in the build environment; an APK built elsewhere (different key) cannot update it — Android would require an
uninstall, which deletes local data. **Before installing a differently signed build, export your memories
(Memory → Export) and settings (Diagnostics → Export settings)**, or sync them to the other device first.

## 2. Keep Clu alive in the background (only if you use background listening / proactive messages)

* **Xiaomi Pad 6:** Settings → Apps → Manage apps → Clu → *Autostart* ON; *Battery saver* → **No restrictions**.
  In Recents, pull Clu down to lock it.
* **iQOO Neo 9 Pro:** Settings → Battery → Background power consumption management → Clu → **Allow background
  high power consumption**; Settings → Apps → Clu → *Autostart* ON (if shown).
* Then Settings (in Clu) → Wake words & activation → **Background listening** ON. A notification shows while the
  microphone is in use. Android 14 only allows starting it while Clu is open.

## 3. Load a model and voice

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
