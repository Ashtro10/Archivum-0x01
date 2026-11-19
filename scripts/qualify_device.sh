#!/usr/bin/env bash
# Model qualification on a real Android device (e.g. Xiaomi Pad 6) over adb.
#
#   scripts/qualify_device.sh [path/to/clu.apk] [path/to/model.gguf]
#
# 1. Installs the APK (optional) and pushes a model (optional) to the device's Download folder.
# 2. Opens CLU. On the tablet: import/select the model (Models), then Settings -> Diagnostics -> "Run model qualification".
#    Keep the screen on and the tablet unplugged if you want battery figures. The run takes several minutes.
# 3. This script waits for the JSON report to appear, pulls it to docs/measurements/, and prints the summary.
#    It also records logcat (crashes, low-memory kills) and the device's thermal/battery dumps during the run.
set -euo pipefail
APK="${1:-}"; MODEL="${2:-}"; PKG=ai.clu
OUT="docs/measurements/device"; mkdir -p "$OUT"
STAMP=$(date +%Y%m%d-%H%M%S)
adb get-state >/dev/null || { echo "No device: enable USB debugging and run 'adb devices'." >&2; exit 1; }
DEV=$(adb shell getprop ro.product.model | tr -d '\r ')
echo "Device: $DEV ($(adb shell getprop ro.build.version.release | tr -d '\r'))"
[ -n "$APK" ] && adb install -r "$APK"
[ -n "$MODEL" ] && adb push "$MODEL" /sdcard/Download/ && echo "Model pushed to /sdcard/Download/$(basename "$MODEL") - import it in CLU -> Models."
REMOTE=/sdcard/Android/data/$PKG/files/qualification
BEFORE=$(adb shell "ls $REMOTE 2>/dev/null" | tr -d '\r' | sort | tail -1 || true)
adb logcat -c
adb logcat -v time > "$OUT/logcat-$DEV-$STAMP.txt" &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null || true' EXIT
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "Now on the tablet: Settings -> Diagnostics -> Run model qualification. Waiting for the report..."
while :; do
  LATEST=$(adb shell "ls $REMOTE 2>/dev/null" | tr -d '\r' | sort | tail -1 || true)
  if [ -n "$LATEST" ] && [ "$LATEST" != "$BEFORE" ]; then break; fi
  adb shell dumpsys thermalservice 2>/dev/null | grep -E "Thermal Status|mTemperature|Temperature\{" | head -5 >> "$OUT/thermal-$DEV-$STAMP.txt" || true
  sleep 15
done
sleep 2
adb pull "$REMOTE/$LATEST" "$OUT/qualification-$DEV-$STAMP.json"
adb shell dumpsys meminfo $PKG > "$OUT/meminfo-$DEV-$STAMP.txt" || true
adb shell dumpsys battery > "$OUT/battery-$DEV-$STAMP.txt" || true
python3 - "$OUT/qualification-$DEV-$STAMP.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print("model:", r["model"].get("name"), "| load ms:", round(r.get("load_ms", 0)), "| peak MB:", r.get("peak_memory_mb"))
print(json.dumps(r["summary"], indent=1))
if r.get("errors"): print("ERRORS:", r["errors"])
PY
grep -E "FATAL EXCEPTION|lowmemorykiller|Out of memory|ANR in $PKG" "$OUT/logcat-$DEV-$STAMP.txt" && echo "^ stability problems found in logcat" || echo "logcat: no crash, ANR or low-memory kill recorded"
echo "Saved in $OUT/. Repeat with each model (Qwen3-0.6B, Qwen3-4B-Instruct-2507, Phi-4-mini-instruct) and commit the files."
