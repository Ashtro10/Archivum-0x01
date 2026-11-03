#!/bin/bash
# Self-repair end-to-end demonstration. Everything happens in a disposable clone of CLU's source ($D/clu-src)
# and a disposable CLU data folder ($H). The real repository is only read (to prove it is unchanged).
set -u
S=${CLU_DEMO_DIR:-$(mktemp -d)}
D=$S/demo; H=$D/home; SRC=$D/clu-src; REAL=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
CLU="$REAL/desktop/build/install/clu-host/bin/clu-host --home $H"
R=core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt
step() { echo; echo "================================================================ $*"; }
clu() { echo "\$ clu-host $*"; $CLU "$@" 2>&1 | grep -v JAVA_TOOL; echo "(exit ${PIPESTATUS[0]})"; }

# Usage: scripts/self_repair_demo.sh   (env: CLU_DEMO_DIR = where to work, CLU_DEMO_MODEL = a GGUF model for CLU to try)
# Needs: ./gradlew :desktop:installDist first. Creates a disposable clone of this repository; never writes to it.
mkdir -p $D/evidence
git clone -q --shared --branch "$(git -C $REAL rev-parse --abbrev-ref HEAD)" $REAL $SRC
for l in toolchain native/build; do [ -e $REAL/$l ] && ln -s $REAL/$l $SRC/$l && echo $l >> $SRC/.git/info/exclude; done
git -C $SRC config user.name "Owner (via Claude Code)"; git -C $SRC config user.email owner@example.invalid
MODEL=${CLU_DEMO_MODEL:-}

step "0. Real repository before (must be identical at the end)"
( cd $REAL && git rev-parse HEAD && git status --porcelain | sha256sum ) | tee $D/evidence/real-before.txt

step "1. Register CLU's configured local model and connect the source repository"
if [ -n "$MODEL" ]; then clu --register-model "$MODEL" | head -3; else echo "(no CLU_DEMO_MODEL: step 4 will report that no model is available)"; fi
clu --self-repo $SRC

step "2. External development (as the owner / Claude Code would): a commit, plus uncommitted work"
cd $SRC
printf '\n/** Added by external development (demonstration). */\nfun reasoningModes(): List<ReasoningMode> = ReasoningMode.entries.toList()\n' >> $R
git commit -qam "Reasoning: list the reasoning modes (external change)"
echo "// owner's uncommitted experiment" >> core/src/main/kotlin/ai/clu/core/llm/Llm.kt
git log --oneline -1; git status --short
sha256sum core/src/main/kotlin/ai/clu/core/llm/Llm.kt > $D/evidence/uncommitted-before.txt
clu --self-index
clu --self-ask "Where is reasoningModes implemented?"
sha256sum -c $D/evidence/uncommitted-before.txt

step "3. A defect is introduced (controlled, in the disposable clone only)"
sed -i 's|                trimmed.endsWith("</think>") -> prompt$|                trimmed.endsWith("</think>") -> prompt + CLOSED_BLOCK|' $R
git commit -q $R -m "Reasoning: always append the closed think block (controlled defect)"
git log --oneline -1
clu --self-ask "What changed in your latest development update?"

step "4. Self-repair with CLU's own model writing the patch"
git rev-parse HEAD > $D/evidence/clone-head-before.txt; sha256sum $R > $D/evidence/clone-reasoning-before.txt
time clu --self-repair --module core --tests ai.clu.core.ReasoningTest

step "5. Same problem, patch supplied as a labelled TEST FIXTURE (the reverse of the defect commit)"
git diff HEAD HEAD~1 -- $R > $D/fixture-fix.diff
# (the fixture patch is the defect commit reversed; the earlier external commit stays)
cat $D/fixture-fix.diff
time clu --self-repair --module core --tests ai.clu.core.ReasoningTest --patch $D/fixture-fix.diff --patch-source fixture
ID=$($CLU --self-repairs 2>/dev/null | grep awaiting-approval | head -1 | cut -c1-8)
echo "repair awaiting approval: $ID"

step "6. Before approval: the clone's branch, commit and files are unchanged"
cd $SRC
diff <(git rev-parse HEAD) $D/evidence/clone-head-before.txt && echo "HEAD unchanged"
sha256sum -c $D/evidence/clone-reasoning-before.txt
sha256sum -c $D/evidence/uncommitted-before.txt
git branch --list 'clu/repair/*'

step "7. Approval is refused while the owner has uncommitted changes to tracked files"
clu --self-approve $ID
sha256sum -c $D/evidence/uncommitted-before.txt

step "8. The owner sets the experiment aside, approves, and gets the experiment back"
git stash -q && echo "stashed owner's work"
clu --self-approve $ID
git stash pop -q && echo "restored owner's work"; sha256sum -c $D/evidence/uncommitted-before.txt
git log --oneline -3
grep -n 'trimmed.endsWith("</think>")' $R

step "9. Independent check outside CLU: the test passes on the integrated branch"
./gradlew :core:test --rerun --tests ai.clu.core.ReasoningTest --offline -q --console=plain 2>&1 | grep -v JAVA_TOOL | tail -3; echo "(gradle exit ${PIPESTATUS[0]})"

step "10. Development history and records (a new process: persisted across restarts)"
clu --self-index
clu --self-repairs
clu --self-incidents
clu --self-history

step "11. Real repository after"
( cd $REAL && git rev-parse HEAD && git status --porcelain | sha256sum ) | tee $D/evidence/real-after.txt
diff $D/evidence/real-before.txt $D/evidence/real-after.txt && echo "REAL REPOSITORY UNCHANGED"
