# Voice security and privacy

## What is protected, and how

| Requirement | How CLU meets it | Verified by |
|---|---|---|
| No audio leaves the PC (except to the owner's paired phone on the local network) | Speech recognition (Kroko / Whisper), the model and synthesis run in the host process; the LiveKit server binds only to `127.0.0.1` and, with phone access, the PC's private address; no STUN/TURN/external IP | `LiveKitServer` config; `VoiceConversationTest`, `VoiceUiTest` run with networking off (`NET_ENABLED=false`) |
| No raw recordings | Microphone audio is processed in memory frame by frame; nothing is written to disk. There is no recording feature. Transcripts become conversation messages like typed text | code review of `VoiceAgent`; installer check "no recordings" |
| Production credentials, not dev keys | A random API key + 288-bit secret generated on first use, stored owner-only (`voice\livekit-keys.json`); `livekit-server` is started with CLU's config, never `--dev` | `LiveKitServer.keys()`; installer check (no `devkey`) |
| Short-lived tokens | Window: 15 min, phone: 10 min (LiveKit refreshes tokens of connected participants itself). Tokens grant one room, no admin/create | `VoiceService`; the phone's `TokenProvisioner.validate` rejects over-privileged tokens |
| The API secret never reaches the phone | The phone has only a device key (`<id>.<secret>`); the PC stores only its SHA-256. Tokens are minted on the PC | `aPairedPhoneGetsATokenFromThePCAndTalksToCLU` (asserts the secret is not in the token) |
| A phone can only join its own room | Room must be `clu-<device id>`; anything else → HTTP 403 | same test |
| Wrong keys, removed phones | 401; after 5 wrong keys in a minute from one address → 429 for a minute. Removing a phone deletes its key hash at once | same test |
| The window's voice API | Owner token required, like every `/v1/*` route; CSP allows WebSocket/HTTP only to `127.0.0.1` and `blob:` audio only | `ApiServer` CSP, `ShellApiTest` |
| No accidental public exposure | Binds never to `0.0.0.0` or a public address; phone access off by default; LAN endpoint exists only while it is on; detection accepts only RFC 1918 / 100.64/10 addresses | `LiveKitServer.detectLanAddress`, `LIVEKIT_LOCAL_NETWORKING.md` |
| Wake-word privacy | In wake-word mode nothing heard is shown, stored or sent to the model until a wake word is recognised; after a farewell or 30 s of quiet CLU sleeps again | `inWakeWordModeCLUIgnoresOrdinaryTalkAndAnswersWhenCalled` |
| Visible microphone state | The window shows **● Microphone on** whenever the microphone is published; Mute hides it; the phone shows its own mic state | `VoiceUiTest` |
| Downloads | Voices download only after confirmation, from the pinned URL, and are installed only if the SHA-256 matches; archive entries are extracted only to known file names inside CLU's voice folder | `VoiceService.fetch` |

## What is *not* protected

* **Signalling between phone and PC is not TLS** (`ws://`, `http://` on the private network). WebRTC media is always
  encrypted (DTLS-SRTP); the token request carries the device key in clear on the LAN. Anyone who can sniff your Wi-Fi
  could capture a device key and request tokens while phone access is on. Mitigations: WPA2/WPA3 Wi-Fi, phone access
  off when not needed, remove and re-pair a phone if you suspect the key leaked. The phone refuses cleartext to public
  addresses.
* **Other programs running as you** can read `livekit-keys.json` and the database (as with all of CLU's data).
* **Someone in the room** can talk to CLU: voice has no speaker verification. In wake-word mode a matching word wakes
  CLU for anyone. Actions that need approval still go through the permission broker (M7).
* The browser window's echo cancellation is what prevents CLU from hearing itself; with loud speakers barge-in may
  trigger on CLU's own voice (it stops speaking; nothing else happens).
