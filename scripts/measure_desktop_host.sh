#!/bin/bash
# Measures the installed CLU host as its own process (Linux build container): idle RSS/CPU, loaded, after unload.
set -u
INST="$(cd "$(dirname "$0")/.." && pwd)/desktop/build/install/clu-host"
MODEL=$1; HOME_DIR=$(mktemp -d); PORT=47999
rss() { awk '/VmRSS/{print int($2/1024)}' /proc/$1/status; }
cpu() { awk '{print $14+$15}' /proc/$1/stat; }
$INST/bin/clu-host --home $HOME_DIR --register-model $MODEL > /dev/null
$INST/bin/clu-host --home $HOME_DIR --port $PORT > $HOME_DIR/host.log 2>&1 &
LAUNCHER=$!
for i in $(seq 1 60); do curl -sf http://127.0.0.1:$PORT/v1/health >/dev/null && break; sleep 0.5; done
PID=$(pgrep -f "ai.clu.desktop.HostMainKt.*--home $HOME_DIR" | head -1)
TOKEN=$(cat $HOME_DIR/api-token); H="Authorization: Bearer $TOKEN"
sleep 15
C0=$(cpu $PID); sleep 30; C1=$(cpu $PID)
IDLE_RSS=$(rss $PID); IDLE_CPU_PCT=$(echo "scale=3; ($C1-$C0)/30" | bc)   # clock ticks (100/s) over 30 s -> % of one core
CONV=$(curl -sf -H "$H" -X POST -d '{}' http://127.0.0.1:$PORT/v1/conversations | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
T0=$(date +%s%N)
REPLY=$(curl -sf -H "$H" -X POST -d '{"text":"Say hello in one short sentence."}' http://127.0.0.1:$PORT/v1/conversations/$CONV/turns)
T1=$(date +%s%N)
LOADED_RSS=$(rss $PID)
curl -sf -H "$H" -X POST http://127.0.0.1:$PORT/v1/model/unload > /dev/null; sleep 2
UNLOADED_RSS=$(rss $PID)
STATUS=$(curl -sf -H "$H" http://127.0.0.1:$PORT/v1/status)
kill $PID; wait $LAUNCHER 2>/dev/null
python3 - "$MODEL" "$IDLE_RSS" "$IDLE_CPU_PCT" "$LOADED_RSS" "$UNLOADED_RSS" "$(( (T1-T0)/1000000 ))" "$REPLY" "$STATUS" <<'PY'
import sys, json, os
m, idle, cpu, loaded, unloaded, turn, reply, status = sys.argv[1:]
print(json.dumps({"model": os.path.basename(m), "modelMB": os.path.getsize(m)//1048576, "idleRssMB": int(idle), "idleCpuPercentOfOneCore": float(cpu),
  "loadedRssMB": int(loaded), "afterUnloadRssMB": int(unloaded), "firstTurnMs": int(turn), "reply": json.loads(reply)["text"][:160],
  "governor": {k: v for k, v in json.loads(status)["governor"].items() if k != "events"}}, indent=1))
PY
rm -rf $HOME_DIR
