# CLU security model (desktop host + assistant tasks)

## Boundaries
* **The host** listens on 127.0.0.1 only; every call except health, session and the shell's own files needs the owner's bearer token; Host/Origin must be the host's own address (DNS-rebinding and cross-site requests refused); the window gets a token through a one-time 60-second launch code in the URL fragment; the shell is served with a strict CSP (no inline script/style, no framing). Data folder permissions are restricted to the owner where the OS supports it.
* **Agents are not the owner.** Assistant tasks have no access to the API token, the database, or the audit log: tools never receive a database handle, and agent file tools cannot reach CLU's data folder. Only the owner (window/token) can approve, reject, revoke, add folders or switch tools.
* **Models are untrusted advisors.** A model can propose text and plans; a plan is validated (tools exist, enabled, arguments match schemas, paths inside allowed folders, ≤ limits) and shown to the owner; nothing a model says can execute a tool directly.

## Permission broker
Categories A/B/C (safe / approval / explicit confirmation). Least privilege by role, tool switch, folder, scope, expiry, uses, revocation; checked at the moment of use; rejections remembered; C never covered by any broader grant; standing grants ≤ 7 days, only for B, only inside a named folder. `proc.run` off by default, allow-list of absolute paths, no shell, no elevation path, confirmation each run; **not an OS sandbox**.

## Untrusted content and prompt injection
Files, web pages, documents and memory passages are *data*: wrapped in `<untrusted-data>` (closing marker unforgeable), model told to treat as information, injection-looking text flagged to the owner (detection is advisory). Structural defences: plan fixed before data is read; reasoning steps cannot call tools; earlier results flow only into declared data fields; no tool creates tasks or agents; identical calls refused. Tested with an obedient model (see M7 doc).

## Audit
Append-only (DB triggers) and hash-chained; `verify` detects tampering by someone with file access (it cannot prevent it, nor detect deletion of the *latest* rows). Secrets redacted before storage; file contents summarised, not stored. Host log redacts tokens/launch codes.

## Downloads and installer
Models only on explicit confirmation, HTTPS, size + SHA-256 (when published) + format verified, then registered but not selected. Installer: per-user, no service, no admin, no model, no network use at install; unsigned (SmartScreen warns); bundled runtime pinned by SHA-256.

## Known gaps
No code signing; no OS sandbox for allowed programs; the local token is as strong as the user account (any process running as the owner can read it, as with any desktop app); Windows path edge cases untested on Windows; web tools untested against the live internet; audit tail truncation is undetectable.
