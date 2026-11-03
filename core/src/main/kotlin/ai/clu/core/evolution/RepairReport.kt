package ai.clu.core.evolution

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The reviewable report the owner reads before approving a repair. Every statement comes from a record; nothing is summarised by a model. */
object RepairReport {
    fun markdown(r: RepairRecord, incident: Incident?, inv: Investigation?, commands: List<EvolutionStore.CommandRecord>): String = buildString {
        val v = r.verdict
        appendLine("# Repair ${r.id.take(8)} — ${stateLabel(r)}")
        appendLine()
        appendLine("| | |"); appendLine("|---|---|")
        appendLine("| State | **${r.state}** |")
        appendLine("| Verdict | **${v?.outcome ?: "not verified"}** ${v?.summary?.let { "— $it" } ?: ""} |")
        appendLine("| Patch source | ${sourceLabel(r.source)} |")
        appendLine("| Workspace | `${r.worktree}` (branch `${r.branch}`, from `${r.baseCommit.take(12)}`) |")
        if (r.patchCommit.isNotEmpty()) appendLine("| Patch commit | `${r.patchCommit.take(12)}` (only on the repair branch until you approve it) |")
        if (r.integratedCommit.isNotEmpty()) appendLine("| Integrated as | `${r.integratedCommit.take(12)}` by ${r.decidedBy} at ${time(r.decidedAt)} |")
        appendLine()
        appendLine("## 1. Problem detected")
        if (incident != null) {
            appendLine("${incident.title} — ${incident.kind}, seen ${incident.count}× (first ${time(incident.firstSeen)}, last ${time(incident.lastSeen)}) at `${incident.commit.take(12)}`.")
        }
        inv?.let { i ->
            appendLine(); appendLine("Failing: ${i.failingTests.joinToString { "`$it`" }}")
            if (i.assertion.isNotBlank()) appendLine("Failure: ${i.assertion}")
            if (i.exception.isNotBlank()) appendLine("Exception: `${i.exception}`")
            if (i.testExcerpt.isNotBlank()) { appendLine(); appendLine("```kotlin"); appendLine(i.testExcerpt); appendLine("```") }
        }
        appendLine()
        appendLine("## 2. Cause")
        if (inv == null) appendLine("No investigation recorded.") else {
            val confirmed = inv.confirmedCause
            appendLine(if (confirmed != null) "**Confirmed by experiment:** ${confirmed.claim}" else "**Not confirmed.** ${inv.conclusion}")
            appendLine()
            appendLine("Suspected code (from the stack trace, the failing line and the index):")
            inv.suspects.forEach { appendLine("- `${it.file}:${it.line}` ${it.symbol} — ${it.reason}") }
            appendLine(); appendLine("Hypotheses:")
            inv.hypotheses.forEach { h ->
                appendLine("- **${h.id}** [${h.status}${if (h.origin == "model") ", model-generated" else ""}] ${h.claim}")
                h.evidenceFor.forEach { appendLine("  - for: $it") }
                h.evidenceAgainst.forEach { appendLine("  - against: $it") }
                if (h.status != Hypothesis.CONFIRMED && h.status != Hypothesis.REFUTED) appendLine("  - would be shown by: ${h.prediction}")
            }
            if (inv.experiments.isNotEmpty()) { appendLine(); appendLine("Experiments:"); inv.experiments.forEach { appendLine("- ${it.name}: ${it.verdict}. Expected: ${it.expected}. Observed: ${it.observed}") } }
            appendLine(); appendLine("Reproduce: `${inv.reproduction}`")
            if (inv.priorAttempts.isNotEmpty()) { appendLine(); appendLine("Earlier attempts that failed:"); inv.priorAttempts.forEach { appendLine("- $it") } }
        }
        appendLine()
        appendLine("## 3. Files changed and why this should fix it")
        if (r.files.isEmpty()) appendLine("No files changed.") else r.files.forEach { appendLine("- `$it`") }
        if (r.modelNotes.isNotBlank()) { appendLine(); appendLine("How the patch was produced: ${r.modelNotes}") }
        inv?.confirmedCause?.let { appendLine(); appendLine("The change is confined to the confirmed cause; the experiment above shows the failing test passes with it and fails without it.") }
        if (r.review.isNotEmpty()) { appendLine(); appendLine("Review findings:"); r.review.forEach { appendLine("- **${it.severity}** ${it.file} ${it.message}") } }
        else if (r.diff.isNotBlank()) { appendLine(); appendLine("Review: no test removed, weakened or disabled; no trust-critical file touched; within the expected files.") }
        appendLine()
        appendLine("## 4. Tests executed and actual results")
        listOfNotNull(r.before, r.after).forEach { t ->
            appendLine("**${t.label}** — `${t.command}` → exit ${t.exitCode}, ${if (t.compiled) "compiled" else "did NOT compile"}, ${t.passed} passed / ${t.failed.size} failed / ${t.results.count { it.status == TestCaseResult.SKIPPED }} skipped of ${t.results.size}, ${t.durationMs / 1000.0} s")
            t.failed.take(10).forEach { appendLine("  - failed: `${it.id}` ${Investigator.parseAssertion(it.message).take(200)}") }
        }
        v?.let {
            appendLine(); appendLine("Verdict: **${it.outcome}**"); it.reasons.forEach { s -> appendLine("- $s") }
            if (it.fixed.isNotEmpty()) appendLine("- fixed: ${it.fixed.joinToString { s -> "`$s`" }}")
            if (it.regressions.isNotEmpty()) appendLine("- regressions: ${it.regressions.joinToString { s -> "`$s`" }}")
        }
        appendLine()
        appendLine("## 5. Remaining risks")
        risks(r, inv).forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 6. Proposed diff")
        if (r.diff.isBlank()) appendLine("(none)") else { appendLine("```diff"); appendLine(r.diff.trimEnd()); appendLine("```") }
        appendLine()
        appendLine("## 7. Commands run (exact, with exit codes)")
        commands.forEach { c -> appendLine("- [${c.phase}] `${c.command}` in `${c.cwd}` → exit ${c.exitCode}${if (c.timedOut) " (timed out)" else ""}, ${c.durationMs} ms") }
        appendLine()
        appendLine(when (r.state) {
            RepairRecord.AWAITING_APPROVAL -> "Nothing has been integrated. Approve with `clu --self-approve ${r.id.take(8)}` (or POST /v1/evolution/repairs/${r.id}/approve); reject with `--self-reject ${r.id.take(8)}`."
            RepairRecord.APPROVED -> "Integrated after owner approval."
            else -> "This repair will not be offered for integration."
        })
    }

    private fun risks(r: RepairRecord, inv: Investigation?): List<String> = buildList {
        if (r.verdict?.verified != true) add("The repair is not verified: do not integrate it.")
        if (inv?.confirmedCause == null) add("The cause is not confirmed by an experiment; the change may hide the symptom rather than fix the cause.")
        r.after?.let { if (it.executed < 20) add("Only ${it.executed} test(s) ran after the change; code outside them is not exercised by this verification.") }
        add("Verification ran on this machine only (tests that need a device, Windows or a real model are not part of it).")
        if (r.source == "model") add("The patch was written by a language model; read the diff.")
        if (r.source == "fixture") add("The patch is a test fixture supplied for a demonstration, not generated by CLU.")
    }

    private fun stateLabel(r: RepairRecord) = when (r.state) {
        RepairRecord.AWAITING_APPROVAL -> "verified, waiting for your approval"; RepairRecord.APPROVED -> "approved and integrated"
        RepairRecord.FAILED_VERIFICATION -> "did not pass verification"; RepairRecord.PATCH_FAILED -> "no applicable patch"; RepairRecord.REJECTED -> "rejected"; else -> r.state }
    private fun sourceLabel(s: String) = when (s) { "model" -> "generated by CLU's configured model"; "external" -> "supplied externally (owner / development tool)"; "fixture" -> "test fixture (demonstration)"; else -> s }
    private fun time(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(ms))
}
