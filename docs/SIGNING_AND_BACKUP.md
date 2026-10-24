# Signing key and backups

## The release signing key

Every update of Clu must be signed with the **same key**, or Android refuses to install it over the existing app.
Uninstalling and reinstalling deletes all local data.

| Item | Value |
|---|---|
| Keystore file | `clu-release.p12` (PKCS#12) |
| Keystore SHA-256 | `a5990509f4ad919ae97578a0f19f1166b0eb26494aff31316615cc7c9aa1dd2d` |
| Key alias | `clu` |
| Key | RSA 4096, SHA384withRSA, valid until 2108-12-13 |
| Certificate SHA-256 | `B8:D4:60:94:2F:88:EE:74:D2:59:6B:BD:D3:4E:6A:44:F4:4D:CF:47:BF:DE:0D:0C:51:06:BF:A5:53:70:BE:C6` |
| Password | random, 24 characters, the same for the keystore and the key. Given to the owner privately, **never written in this repository**. |

**Where the key is kept:**
* It is **not** in the Git repository, and must never be (`*.keystore`, `*.jks`, `*.p12` are ignored).
* It was handed to the owner directly as a file attachment in the build session. An automatic upload to Google Drive
  was attempted and refused (no permission), so the owner must store it.
* The build container that created it is temporary and will be deleted. After that, the owner's copies are the
  only ones.

**Keep at least two copies**, for example Google Drive plus a USB stick or a password manager's file attachment.
Store the password separately from the file, ideally in a password manager. If both the file and the password are
lost, future builds cannot update the installed app; you would have to export data, uninstall and reinstall.

**Building a signed update:**

```bash
export CLU_KEYSTORE=/secure/path/clu-release.p12
export CLU_KEYSTORE_PASSWORD='<password>'          # key password defaults to the same value
./gradlew :app:assembleRelease :app:verifyApk -Pclu.verify=release
# output: app/build/apk/clu-release.apk
```

Check that an APK was signed with this key:

```bash
java -jar toolchain/apksig.jar --onlyVerify -a clu-release.apk   # shows "SHA256: b8d46094…"
```

If the key is ever exposed (shared publicly or committed), assume anyone can sign "updates". Make a new key,
export your data, uninstall and reinstall with the new key. Android 13+ also supports key rotation (APK Signature
Scheme v3), but that needs the old key.

## Backing up Clu's data (on the device, no PC)

Android backup is turned off for Clu (`allowBackup=false`) so private memories do not go to cloud backup without
your say. Use these instead:

| What | How | Restore |
|---|---|---|
| Memories | ⋮ → Memory & diary → **Export (JSON)** → pick a folder (for example Drive or Downloads). Private memories need owner verification. | Memory & diary → **Import (JSON)** |
| Settings | ⋮ → Diagnostics → **Export settings** (requires *Developer diagnostics* on) | Diagnostics → Import settings |
| Everything shared | Pair the other device: memories, conversations, settings, characters, preferences, commands and people sync automatically. Voiceprints and the PIN stay on each device. | Pair again after reinstalling, then *Sync now* |
| Models and voice packs | Not backed up (large and re-downloadable). Keep the `.gguf` file in Downloads/Drive if you want to re-import it offline. | AI models → Import GGUF file… |

**Before any risky step** (installing a build signed with another key, factory reset, uninstall), export memories
and settings first, or make sure the other device is paired and synced.
