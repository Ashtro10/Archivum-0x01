#!/usr/bin/env bash
# Stronger-model qualification on a computer with network access (the build environment of this repository has none).
#
#   scripts/model_qualification.sh qwen3-4b|phi4-mini|qwen3-0.6b [models_dir]
#
# For the chosen model: checks free disk BEFORE downloading (refuses if the file plus 1 GB headroom does not fit),
# downloads the Q4_K_M GGUF, records its SHA-256, then runs the same evaluations used for Qwen3-0.6B:
#   1. goal evaluation, 30 cases, model alone + oracle attribution, 2 runs   -> docs/measurements/goal_eval_<model>.json
#   2. ordinary conversation, 17 cases, 2 runs                                 -> docs/measurements/conversation_eval_<model>_runN.json
#   3. speed / memory / context retention on THIS computer (not the tablet)  -> docs/measurements/qualification_host_<model>.json
#   4. live research on real websites (needs internet)                         -> docs/measurements/live_research_<model>.json
# Licences: read each model card before use. Repository names below were current when written but could not be
# verified from the build environment (Hugging Face unreachable); if a download 404s, find the model's GGUF repository.
set -euo pipefail
MODEL="${1:?model: qwen3-4b | phi4-mini | qwen3-0.6b}"; DIR="${2:-models}"; mkdir -p "$DIR" docs/measurements
case "$MODEL" in
  qwen3-4b)   REPO=unsloth/Qwen3-4B-Instruct-2507-GGUF; FILE=Qwen3-4B-Instruct-2507-Q4_K_M.gguf; NEED_MB=2600 ;;
  phi4-mini)  REPO=unsloth/Phi-4-mini-instruct-GGUF;    FILE=Phi-4-mini-instruct-Q4_K_M.gguf;    NEED_MB=2600 ;;
  qwen3-0.6b) REPO=unsloth/Qwen3-0.6B-GGUF;              FILE=Qwen3-0.6B-Q4_K_M.gguf;              NEED_MB=420 ;;
  *) echo "unknown model $MODEL" >&2; exit 2 ;;
esac
GGUF="$DIR/$FILE"
if [ ! -f "$GGUF" ]; then
  FREE_MB=$(df -Pm "$DIR" | awk 'NR==2{print $4}')
  if [ "$FREE_MB" -lt $((NEED_MB + 1024)) ]; then
    echo "Not downloading: $FILE needs about ${NEED_MB} MB plus 1 GB headroom; ${FREE_MB} MB free in $DIR." >&2; exit 3
  fi
  if command -v huggingface-cli >/dev/null; then huggingface-cli download "$REPO" "$FILE" --local-dir "$DIR"
  else curl -fL --retry 3 -o "$GGUF.part" "https://huggingface.co/$REPO/resolve/main/$FILE" && mv "$GGUF.part" "$GGUF"; fi
fi
sha256sum "$GGUF" | tee "docs/measurements/model_${MODEL}.sha256"
G=./gradlew
$G :core:test --tests ai.clu.core.GoalEvaluation -Dclu.goalModel="$GGUF" -Dclu.goalOracle=none,understand,full -Dclu.goalRuns=2 \
   -Dclu.goalOut="docs/measurements/goal_eval_${MODEL}.json" || echo "goal evaluation reported failures (see the JSON)"
for i in 1 2; do $G :core:cleanTest :core:test --tests ai.clu.core.ConversationQualityEvaluation -Dclu.convModel="$GGUF" \
   -Dclu.convOut="docs/measurements/conversation_eval_${MODEL}_run$i.json" || true; done
$G :core:cleanTest :core:test --tests ai.clu.core.DeviceQualificationTest -Dclu.qualifyModel="$GGUF" -Dclu.qualifyRounds=20 \
   -Dclu.qualifyOut="docs/measurements/qualification_host_${MODEL}.json" || true
$G :core:cleanTest :core:test --tests ai.clu.core.LiveResearchEvaluation -Dclu.liveResearch="$GGUF" \
   -Dclu.liveResearchOut="docs/measurements/live_research_${MODEL}.json" || true
python3 - "$MODEL" <<'PY'
import json, sys, glob
m = sys.argv[1]
def load(p):
    try: return json.load(open(p))
    except Exception: return None
g = load(f"docs/measurements/goal_eval_{m}.json")
if g: print("goals:", {k: v["task_success"] for k, v in g["by_mode"].items()}, "unwanted:", {k: v["unwanted_changes"] for k, v in g["by_mode"].items()})
for p in sorted(glob.glob(f"docs/measurements/conversation_eval_{m}_run*.json")):
    c = load(p); print("conversation", p[-10:-5], c["passed"], c["by_group"], "hijacks", c["goal_hijacks"]) if c else None
q = load(f"docs/measurements/qualification_host_{m}.json")
if q: print("host speed:", q["summary"])
l = load(f"docs/measurements/live_research_{m}.json")
print("live research:", l["passed"] if l else "not run (no network?)")
PY
echo "Next: copy $GGUF to the tablet and run scripts/qualify_device.sh (docs/PAD6_QUALIFICATION.md)."
