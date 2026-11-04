# What Qwen3-0.6B wrote for the galaxy file explorer

These are the files from the last of five attempts in the acceptance run of 2026-11-04 (`CapabilityAuthoringUiTest`,
16k context, CLU's mechanical fix moved an inline `<script>` from `app.html` into `app.js`). They are kept as evidence
of the model's ability, unedited. CLU did not install them.

What went wrong, from CLU's browser checks and reading the code:

* It crashes on start: `document.getElementById('path')` returns null (the page has no element with that id).
* It draws three hardcoded nodes ("Root", "Alpha", "Beta") instead of the real folder's entries.
* It calls `scene.updateNodes`, which does not exist, and repeats the whole program twice.
* Attempts 3 to 5 returned the same files although the failures were sent back each time.

The model benchmark of the same day is in `../model-benchmark.tsv` (Qwen3-0.6B 1/6, SmolLM2-135M 0/6).
