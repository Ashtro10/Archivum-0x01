# Repair e3c9ea23 — approved and integrated

| | |
|---|---|
| State | **approved-integrated** |
| Verdict | **verified-fix** — before: 8/9 passed (1 failed); after: 9/9 passed (0 failed) |
| Patch source | test fixture (demonstration) |
| Workspace | `/tmp/claude-0/-home-user-Archivum-0x01/a97d5855-f7c4-555c-911c-40b94de91443/scratchpad/demo/home/evolution/workspaces/e3c9ea23` (branch `clu/repair/e3c9ea23`, from `aff89e7b6a28`) |
| Patch commit | `2de29664eafe` (only on the repair branch until you approve it) |
| Integrated as | `2de29664eafe` by owner at 2026-11-03 20:50:13 |

## 1. Problem detected
ReasoningTest › templatesAreClassifiedAndReasoningIsSwitchedOffInThePrompt() fails — test-failure, seen 2× (first 2026-11-03 20:49:38, last 2026-11-03 20:50:05) at `aff89e7b6a28`.

Failing: `ai.clu.core.ReasoningTest#templatesAreClassifiedAndReasoningIsSwitchedOffInThePrompt()`
Failure: org.opentest4j.AssertionFailedError: never closed twice ==> — expected <<|im_start|>user
Hi<|im_end|>
<|im_start|>assistant
<think>

</think>

> but was <<|im_start|>user
Hi<|im_end|>
<|im_start|>assistant
<think>

</think>

<think>

</think>

>
Exception: `org.opentest4j.AssertionFailedError: never closed twice ==> expected: <<|im_start|>user`

```kotlin
   51          assertEquals(ReasoningMode.TOGGLE, Reasoning.mode(qwen3)); assertEquals(ReasoningMode.FORCED, Reasoning.mode(r1))
   52          assertEquals(ReasoningMode.NONE, Reasoning.mode(chatml)); assertEquals(ReasoningMode.NONE, Reasoning.mode(null))
   53          val p = "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n"
   54          assertEquals(p + "<think>\n\n</think>\n\n", Reasoning.disable(p, ReasoningMode.TOGGLE), "exactly what the template renders for enable_thinking=false")
   55          assertEquals("<｜Assistant｜><think>\n\n</think>\n\n", Reasoning.disable("<｜Assistant｜><think>\n", ReasoningMode.FORCED))
   56          assertEquals(p, Reasoning.disable(p, ReasoningMode.NONE))
>  57          assertEquals(p + "<think>\n\n</think>\n\n", Reasoning.disable(p + "<think>\n\n</think>\n\n", ReasoningMode.TOGGLE), "never closed twice")
   58      }
   59  
   60      @Test fun storedTextIsCleaned() {
   61          assertEquals("Hello!", Reasoning.strip("<think>\nOkay, the user said hello.\n</think>\n\nHello!"))
   62          assertEquals("", Reasoning.strip("<think>\nOkay, the user said"), "an unclosed block is all reasoning")
   63          assertEquals("Paris.", Reasoning.strip("The user asks about France.</think>Paris."))
```

## 2. Cause
**Confirmed by experiment:** A defect in disable (core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt:34) produces the wrong result the failing assertion reports.

Suspected code (from the stack trace, the failing line and the index):
- `core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt:34` disable — called on the failing test line (ReasoningTest.kt:57), imported by the test

Hypotheses:
- **H1** [confirmed by experiment] A defect in disable (core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt:34) produces the wrong result the failing assertion reports.
  - for: called on the failing test line (ReasoningTest.kt:57), imported by the test
  - for: assertion: org.opentest4j.AssertionFailedError: never closed twice ==> — expected <<|im_start|>user
Hi<|im_end|>
<|im_start|>assistant
<think>

</think>

> but was <<|im_start|>user
Hi<|im_end|>
<|im_start|>assistant
<think>

</think>

<think>

</think>

>
  - for: changed recently: aff89e7 Reasoning: always append the closed think block (controlled defect), 8fec85d Reasoning: list the reasoning modes (external change), 1fd8d98 M1: foundation fixes found by the audit
  - for: experiment 'repair': all 1 failing test(s) now pass; 9 of 9 executed tests pass; no regressions
- **H2** [refuted by experiment] The test's expectation is wrong (the test, not the code, changed).
  - against: the suspected code changed after the test (aff89e7 Reasoning: always append the closed think block (controlled defect))
  - against: experiment 'repair': all 1 failing test(s) now pass; 9 of 9 executed tests pass; no regressions
- **H3** [refuted by experiment] The failure is environmental or flaky (timing, memory, ordering), not a code defect.
  - against: experiment 'reproduction': it failed again (exit 1)

Experiments:
- reproduction: reproduced. Expected: the test fails again. Observed: it failed again (exit 1)
- repair: verified-fix. Expected: the failing test passes, no regressions. Observed: all 1 failing test(s) now pass; 9 of 9 executed tests pass; no regressions

Reproduce: `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain`

Earlier attempts that failed:
- 13b426d8 (model, patch-failed, patch ) "the model's answer contained no usable edit block (output kept in the development history)"

## 3. Files changed and why this should fix it
- `core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt`

How the patch was produced: patch supplied externally (14 lines)

The change is confined to the confirmed cause; the experiment above shows the failing test passes with it and fails without it.

Review: no test removed, weakened or disabled; no trust-critical file touched; within the expected files.

## 4. Tests executed and actual results
**before (unchanged code)** — `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain` → exit 1, compiled, 8 passed / 1 failed / 0 skipped of 9, 3.649 s
  - failed: `ai.clu.core.ReasoningTest#templatesAreClassifiedAndReasoningIsSwitchedOffInThePrompt()` org.opentest4j.AssertionFailedError: never closed twice ==> — expected <<|im_start|>user
Hi<|im_end|>
<|im_start|>assistant
<think>

</think>

> but was <<|im_start|>user
Hi<|im_end|>
<|im_start|>assi
**after (with the repair)** — `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain` → exit 0, compiled, 9 passed / 0 failed / 0 skipped of 9, 3.236 s

Verdict: **verified-fix**
- all 1 failing test(s) now pass; 9 of 9 executed tests pass; no regressions
- fixed: `ai.clu.core.ReasoningTest#templatesAreClassifiedAndReasoningIsSwitchedOffInThePrompt()`

## 5. Remaining risks
- Only 9 test(s) ran after the change; code outside them is not exercised by this verification.
- Verification ran on this machine only (tests that need a device, Windows or a real model are not part of it).
- The patch is a test fixture supplied for a demonstration, not generated by CLU.

## 6. Proposed diff
```diff
diff --git a/core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt b/core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt
index 4401ac3..641f063 100644
--- a/core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt
+++ b/core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt
@@ -36,7 +36,7 @@ object Reasoning {
         else -> {
             val trimmed = prompt.trimEnd()
             when {
-                trimmed.endsWith("</think>") -> prompt + CLOSED_BLOCK
+                trimmed.endsWith("</think>") -> prompt
                 trimmed.endsWith("<think>") -> trimmed + "\n\n</think>\n\n" // the template already opened the block
                 else -> prompt + CLOSED_BLOCK
             }
```

## 7. Commands run (exact, with exit codes)
- [detect] `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain` in `/tmp/claude-0/-home-user-Archivum-0x01/a97d5855-f7c4-555c-911c-40b94de91443/scratchpad/demo/home/evolution/workspaces/e3c9ea23` → exit 1, 3649 ms
- [reproduce] `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain` in `/tmp/claude-0/-home-user-Archivum-0x01/a97d5855-f7c4-555c-911c-40b94de91443/scratchpad/demo/home/evolution/workspaces/e3c9ea23` → exit 1, 2126 ms
- [verify] `./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline --continue --console=plain` in `/tmp/claude-0/-home-user-Archivum-0x01/a97d5855-f7c4-555c-911c-40b94de91443/scratchpad/demo/home/evolution/workspaces/e3c9ea23` → exit 0, 3236 ms

Integrated after owner approval.
