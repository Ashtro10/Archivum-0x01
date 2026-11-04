#!/bin/bash
# CLU writes its own repair: end-to-end demonstration in a disposable clone of this repository.
# Nothing is supplied: the patch must come from CLU's configured model through the coding loop.
# Usage: CLU_DEMO_MODEL=/path/model.gguf scripts/coding_demo.sh   (after ./gradlew :desktop:installDist)
set -u
S=${CLU_DEMO_DIR:-$(mktemp -d)}
D=$S/coding-demo; H=$D/home; SRC=$D/clu-src; REAL=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
CLU="$REAL/desktop/build/install/clu-host/bin/clu-host --home $H"
DEFECT=${CLU_DEMO_DEFECT:-cap}
if [ "$DEFECT" = phase ]; then F=core/src/main/kotlin/ai/clu/core/live/LiveConversation.kt; TESTS=ai.clu.core.LiveConversationTest
else F=core/src/main/kotlin/ai/clu/core/agent/TaskPlan.kt; TESTS=ai.clu.core.TaskPlanTest; fi
step() { echo; echo "================================================================ $*"; }
clu() { echo "\$ clu-host $*"; $CLU "$@" 2>&1 | grep -v JAVA_TOOL; echo "(exit ${PIPESTATUS[0]})"; }
mkdir -p $D/evidence

step "0. Real repository before"
( cd $REAL && git rev-parse HEAD && git status --porcelain | sha256sum ) | tee $D/evidence/real-before.txt

step "1. Disposable clone of CLU, CLU's model, connect"
git clone -q --shared --branch "$(git -C $REAL rev-parse --abbrev-ref HEAD)" $REAL $SRC
for l in toolchain native/build; do [ -e $REAL/$l ] && ln -s $REAL/$l $SRC/$l && echo $l >> $SRC/.git/info/exclude; done
git -C $SRC config user.name "Owner (via Claude Code)"; git -C $SRC config user.email owner@example.invalid
clu --register-model "${CLU_DEMO_MODEL:?set CLU_DEMO_MODEL}" | head -3
clu --self-repo $SRC

cd $SRC
if [ "$DEFECT" = phase ]; then
  step "2. A controlled defect in a non-critical component (AgentPhase.from: \"speaking\" maps to THINKING)"
  sed -i 's/"speaking" -> SPEAKING/"speaking" -> THINKING/' $F
elif [ "$DEFECT" = overflow ]; then
  step "2. A controlled defect in a non-critical component (TaskPlanner.overflow: '- MAX_STEPS' becomes '+ MAX_STEPS')"
  sed -i 's/\.size - MAX_STEPS)\.coerceAtLeast(0)/.size + MAX_STEPS).coerceAtLeast(0)/' $F
else
  step "2. A controlled defect in a non-critical component (TaskPlanner.plan: the 5-step cap becomes 6)"
  sed -i 's/\.take(MAX_STEPS)$/.take(MAX_STEPS + 1)/' $F
fi
git diff --stat; git diff | grep '^[-+] '
git commit -qam "TaskPlanner: controlled defect ($DEFECT)"
git rev-parse HEAD > $D/evidence/clone-head-before.txt; sha256sum $F > $D/evidence/clone-file-before.txt

step "3. CLU detects, investigates, writes the patch with its own model, builds, tests, corrects, verifies"
time clu --self-repair --module core --tests $TESTS --attempts 10
ID=$($CLU --self-repairs 2>/dev/null | grep -E "awaiting-approval +model" | head -1 | cut -c1-8)
echo "verified model-written repair: ${ID:-none}"

step "4. Every attempt (patch, failure kind, feedback, raw model output)"
LAST=$($CLU --self-repairs 2>/dev/null | head -1 | cut -c1-8)
clu --self-attempts ${ID:-$LAST}

step "5. Before approval: the clone is unchanged"
diff <(git rev-parse HEAD) $D/evidence/clone-head-before.txt && echo "HEAD unchanged"
sha256sum -c $D/evidence/clone-file-before.txt

step "6. The report"
clu --self-report ${ID:-$LAST} | sed -n '1,80p'

if [ -n "${ID:-}" ]; then
  step "7. Owner approval integrates the repair"
  clu --self-approve $ID
  git log --oneline -3; git log -1 --format=%B | tail -4
  grep -n 'take(MAX_STEPS\|MAX_STEPS).coerce\|"speaking" ->' $F
  step "8. Independent check outside CLU (tests run, not cached)"
  ./gradlew :core:test --rerun --tests $TESTS --offline -q --console=plain 2>&1 | grep -v JAVA_TOOL | tail -3; echo "(gradle exit ${PIPESTATUS[0]})"
fi

step "9. Coding experience and history"
clu --self-coding-stats
clu --self-history | tail -12

step "10. Real repository after"
( cd $REAL && git rev-parse HEAD && git status --porcelain | sha256sum ) | tee $D/evidence/real-after.txt
diff $D/evidence/real-before.txt $D/evidence/real-after.txt && echo "REAL REPOSITORY UNCHANGED"
