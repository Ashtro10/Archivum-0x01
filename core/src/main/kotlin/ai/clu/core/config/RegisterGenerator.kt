package ai.clu.core.config

import java.io.File

/**
 * Generates docs/SETTINGS_REGISTER.md from the settings registry and the JUnit XML results of the test that
 * verifies each setting. Run via the :app Robolectric test (so Android tools' per-tool settings are registered).
 */
object RegisterGenerator {
    private fun results(dirs: List<File>): Map<String, String> {
        val out = HashMap<String, String>()
        val tc = Regex("""<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)""", RegexOption.DOT_MATCHES_ALL)
        for (d in dirs) d.listFiles { f -> f.name.endsWith(".xml") }?.forEach { f ->
            for (m in tc.findAll(f.readText())) {
                val cls = m.groupValues[2].substringAfterLast('.')
                val name = m.groupValues[1].removeSuffix("()")
                val body = m.groupValues[4]
                out["$cls#$name"] = when { body.contains("<failure") || body.contains("<error") -> "FAILED"; body.contains("<skipped") -> "skipped"; else -> "passed" }
            }
        }
        return out
    }

    private fun accepted(t: SettingType): String = when (t) {
        SettingType.Bool -> "true / false"
        is SettingType.IntRange -> "integer ${t.min}–${t.max}"
        is SettingType.FloatRange -> "number ${t.min}–${t.max}"
        is SettingType.Choice -> t.options.joinToString(", ") { "`${it.first}`" }
        is SettingType.Text -> "text ≤ ${t.maxLength} chars" + (if (!t.allowEmpty) ", non-empty" else "") + (if (t.pattern != null) ", validated format" else "")
        SettingType.TimeOfDay -> "HH:MM (24 h)"
        is SettingType.TextList -> "list of ≤ ${t.maxItems} entries (≤ ${t.maxLength} chars each" + (if (t.minItems > 0) ", at least ${t.minItems}" else "") + ")"
        is SettingType.Structured -> "structured list, validated (one primary, unique, ≥1 enabled)"
    }

    private fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun generate(out: File, resultDirs: List<File>, buildInfo: String) {
        val res = results(resultDirs)
        val all = Settings.all
        val sb = StringBuilder()
        val passed = all.count { res[it.verifiedBy] == "passed" }
        sb.appendLine("# Settings Implementation Register")
        sb.appendLine()
        sb.appendLine("Generated from the settings registry (`core/.../config/Settings.kt`) and the JUnit results of the verifying tests. $buildInfo")
        sb.appendLine()
        sb.appendLine("* Settings: **${all.size}** · verifying test passed: **$passed** · other: **${all.size - passed}**")
        sb.appendLine("* Storage: every value is persisted in the SQLite table `settings` (key, JSON value, scope, HLC, origin). `SHARED` settings")
        sb.appendLine("  synchronise between paired devices; `DEVICE` settings stay on the device. Snapshots live in `settings_snapshots` (rollback / known-good).")
        sb.appendLine("* Persistence across restart and rejection of invalid values are verified for every setting by `RegistryTest`.")
        sb.appendLine("* \"Device effect\" = the binding is unit-tested, but the audible/visible effect itself can only be confirmed on a physical device")
        sb.appendLine("  (**unverified on device** in this build: no device was available to the build environment).")
        sb.appendLine()
        for (cat in SettingCategory.values()) {
            val defs = all.filter { it.category == cat }
            if (defs.isEmpty()) continue
            sb.appendLine("## ${cat.title}")
            sb.appendLine()
            sb.appendLine("| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (d in defs) {
                val r = res[d.verifiedBy] ?: "not run"
                val result = if (d.deviceOnlyEffect && r == "passed") "passed (binding) · device effect unverified" else r
                sb.appendLine("| `${d.key}` | ${esc(d.title)}${if (d.sensitive) " 🔒" else ""} | `${esc(d.default.toString().take(60))}` | ${esc(accepted(d.type))} | ${d.scope} | " +
                    "${esc(d.component)} | ${esc(d.effect)} | ${esc(d.apply.label)} | `${d.verifiedBy}` | $result | ${esc(d.limitations.ifBlank { "–" })} |")
            }
            sb.appendLine()
        }
        sb.appendLine("🔒 = changing it requires owner verification (PIN / device credential).")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
