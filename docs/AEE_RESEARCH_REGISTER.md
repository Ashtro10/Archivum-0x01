# AEE Research Register

**Verification note.**

* Three identifiers were checked against arXiv or project pages in this session and are marked ✔: arXiv:2501.07278,
  arXiv:2505.22954, and OpenHands arXiv:2407.16741 with its MIT license.
* All other identifiers come from established reference knowledge and were **not re-fetched** here.
* Licenses of software must be confirmed from each repository's own LICENSE file before any code is adapted (spec
  §D). This register records them as commonly published.
* No benchmark numbers from papers are quoted. Where a paper reports results, this register says so without
  restating them.

## 1. Research literature

| # | Work | Venue / id | What it contributes | Relevance to the AEE | Recommendation |
|---|---|---|---|---|---|
| R1 | Sumers, Yao, Narasimhan, Griffiths, *Cognitive Architectures for Language Agents (CoALA)* | arXiv:2309.02427 (TMLR 2024) | A framework that splits an agent into working, episodic, semantic and procedural memory, an action space (internal reasoning or retrieval, external grounding), and a decision cycle | It structures the AEE: J holds episodic memory (incidents, attempts) and semantic memory (architecture, lessons); procedural memory is CLU's code itself; learning actions write to J; and code changes are explicitly *procedural learning* gated by evaluation | **Adopt as the conceptual frame** |
| R2 | Yao et al., *ReAct* | arXiv:2210.03629 (ICLR 2023) | Interleaving reasoning traces with tool actions and observations | The investigation and patch loops are reason-act-observe with tools (index, tests, bisect) | Adopt, but bound the steps and keep the plan fixed before untrusted text (the M7 rule) |
| R3 | Shinn et al., *Reflexion* | arXiv:2303.11366 (NeurIPS 2023) | Verbal self-reflection stored in memory improves later attempts | `failed-attempt` and `lesson` knowledge items are injected into later tasks (Workflow 6) | Adopt, with reflections tied to *evidence* (test results), not model opinion |
| R4 | Park et al., *Generative Agents* | arXiv:2304.03442 (UIST 2023) | A memory stream with recency, importance and relevance retrieval, plus reflection synthesis | The retrieval scoring of knowledge items. CLU's memory already uses recency and explicit ranking (T2). | Adapt the retrieval scoring; no simulation aspects |
| R5 | Packer et al., *MemGPT* | arXiv:2310.08560 | OS-style tiered memory: the context as main memory, paging to archival storage | Bounded context assembly for small local models: the index and knowledge pages are pulled in on demand | Adopt the principle (bounded context, explicit paging); no framework (the Letta project) |
| R6 | Wang et al., *Voyager* | arXiv:2305.16291 | A skill library of verified code, an automatic curriculum, and iterative prompting with execution feedback | Verified changes become reusable "procedures"; the curriculum idea maps to goal generation **with anti-proliferation limits** | Adopt "only verified code enters the library"; reject an unbounded curriculum |
| R7 | Zheng et al., *Lifelong Learning of LLM-based Agents: A Roadmap* ✔ | arXiv:2501.07278 | A survey that organises lifelong agents into perception, memory and action modules, with catastrophic forgetting as a central concern | Supports keeping learning in external memory and code (no weight updates) and the explicit handling of superseded knowledge | Reference for J's design |
| R8 | Zhang et al., *A Survey on the Memory Mechanism of LLM-based Agents* | arXiv:2404.13501 | A taxonomy of memory sources, forms (textual or parametric) and operations (write, manage, read) | Validates textual, structured memory with management (merge, supersede, forget) | Reference for retention tiers |
| R9 | Jimenez et al., *SWE-bench* | arXiv:2310.06770 (ICLR 2024) | Real GitHub issue → patch evaluation with the repository's own tests | The template for gate G0: a **CLU-specific** benchmark built from CLU's own fixed bugs in Git history | Adopt the method (build "CLU-bench") |
| R10 | Yang et al., *SWE-agent* | arXiv:2405.15793 (NeurIPS 2024) | An agent-computer interface: constrained file viewing and editing commands improve agent reliability | Search/replace edit blocks and windowed file views instead of free shell | Adopt the ACI principle |
| R11 | Xia et al., *Agentless* | arXiv:2407.01489 | A fixed three-phase pipeline (hierarchical localisation → repair candidates → validation with reproduction tests) without an autonomous agent loop | The main template for F and C: cheap, bounded, small-model-friendly | **Adopt as the primary coding workflow** |
| R12 | Zhang et al., *AutoCodeRover* | arXiv:2404.05427 (ISSTA 2024) | Program-structure-aware code search (class and method APIs) and spectrum-based fault localisation from tests | Index-based localisation; spectrum-based fault localisation from coverage of failing vs passing tests | Adopt SBFL as a deterministic localiser |
| R13 | Wang et al., *OpenHands (OpenDevin)* ✔ | arXiv:2407.16741 | An open platform for coding agents with sandboxed execution and an evaluation harness | Reference architecture for sandboxing and evaluation | **Do not embed** (see §3); borrow evaluation-harness ideas |
| R14 | Zhang, Hu, Lu, Lange, Clune, *Darwin Gödel Machine* ✔ | arXiv:2505.22954 (ICLR 2026 per arXiv v3) | Self-modifying coding agents: proof requirements replaced by empirical benchmark validation, an archive of variants, sandboxing and human oversight | The closest published analogue to "CLU improves its own code". It supports empirical gates and keeping prior versions. Its own safety discussion stresses sandboxing and oversight. | Adopt the empirical-gate and version-archive ideas; **reject** open-ended self-modification of the evaluator (our Ring 0/1) |
| R15 | Madaan et al., *Self-Refine* | arXiv:2303.17651 (NeurIPS 2023) | Iterative self-feedback improves output without training | Verifier-role diff review | Adopt with bounded iterations |
| R16 | Kephart & Chess, *The Vision of Autonomic Computing* | IEEE Computer 36(1), 2003 | The MAPE-K loop: Monitor, Analyze, Plan, Execute over shared Knowledge; self-healing and self-optimising | The canonical structure the AEE instantiates: A=Monitor, C=Analyze, E=Plan, F/G/I=Execute, J=Knowledge | **Adopt as the control-loop frame** |
| R17 | Le Goues, Pradel, Roychoudhury, *Automated Program Repair* | CACM 62(12), 2019 | An overview of APR. Patch overfitting to weak tests is a central risk. | Motivates the behaviour-coverage gate (repro fails before, passes after) and the ban on weakening tests | Adopt the overfitting safeguards |
| R18 | Monperrus, *Automatic Software Repair: a Bibliography* | ACM CSUR 51(1), 2018 | A taxonomy of behavioural repair and state repair (runtime self-healing) | Separates runtime recovery (rollback, restart: I) from code repair (F) | Reference |
| R19 | Zeller & Hildebrandt, *Simplifying and Isolating Failure-Inducing Input* (delta debugging) | IEEE TSE 28(2), 2002 | Systematic minimisation of failure causes | `git bisect` and input minimisation as deterministic experiments | Adopt (bisect first) |
| R20 | Greshake et al., *Not what you've signed up for: Indirect Prompt Injection* | arXiv:2302.12173 (AISec 2023) | Injected instructions in retrieved data can control LLM-integrated apps | Repository, docs and web text are data. The M7 structural defences extend to the AEE. | Adopt (already partly implemented) |
| R21 | Xie et al., *OSWorld* | arXiv:2404.07972 (NeurIPS 2024) | A benchmark for computer-use agents in real OSes | Computer use is deferred; the AEE needs files, Git and processes only | Not now |
| R22 | Liu et al., *AgentBench* | arXiv:2308.03688 (ICLR 2024) | Multi-environment evaluation of LLM agents | Informs AEE metrics; general benchmarks do not measure CLU-specific competence | Use CLU-specific gates instead |

## 2. Open-source technology assessment

| Technology | Provides | Why the AEE needs it | FOSS / license | Hardware | Offline | Integration | Duplicates CLU? | Custom build justified? | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **Git CLI** (Git for Windows) | History, worktrees, bisect, blame, rename detection | Workspaces, provenance, reconciliation, bisect | GPL-2.0, run as an external program (no linking) | trivial | yes | Low: ProcessBuilder, like `proc.run` | No | No | **Use** (owner installs Git; the installer can detect it) |
| JGit (Eclipse) | Git in Java | Avoid an external Git dependency | EDL-1.0 (BSD-style) | trivial | yes | Medium; worktree and bisect support is weaker than the CLI | No | — | Alternative if Git is absent; CLI preferred |
| **SQLite + FTS5** | Storage and full-text search | `evolution.db`, knowledge retrieval | Public domain | trivial | yes | None (already used, with the JDBC driver on the desktop) | Reuse | No | **Reuse** |
| **llama.cpp** | Local inference, GBNF grammars | Coding and investigation roles through the shared service | MIT | CPU, and Vulkan on the 860M (unmeasured) | yes | Exists | Reuse | No | **Reuse**; also `llama-server` as an optional OpenAI-compatible endpoint |
| Coding models (Qwen2.5-Coder family, others) | Code generation and repair | F, C | Varies **per model size**; some sizes are Apache-2.0, others carry custom licenses. Check each model card. | 7B Q4 ≈ 4.5–5.5 GB, 14B Q4 ≈ 9 GB (estimates) | yes | Low (GGUF) | No | — | **Choose by gate G0 on the PC**; no decision without measurement |
| **Gradle** (existing) | Build and test | G | Apache-2.0 | 1.5–3 GB during builds | yes (offline mode is used today) | Exists | Reuse | No | **Reuse** |
| **JaCoCo** | Line and branch coverage per test | Test map, behaviour-coverage gate | EPL-2.0 | modest | yes | Medium: a Gradle plugin on `core` and `desktop`; Android needs a custom step | No | No | **Use** (owner confirms the new build dependency) |
| PIT (pitest) | Mutation testing | Test adequacy spot checks | Apache-2.0 | CPU-heavy | yes | Medium; Kotlin support works at bytecode level with some noise | No | No | Stage 2, optional |
| tree-sitter + tree-sitter-kotlin | Precise incremental parsing | Symbol index precision | MIT | trivial | yes | Medium-high: native library and JNI on Windows (bindings exist, untested here) | No | — | Stage 2, only if lexer precision is measured as insufficient |
| Kotlin compiler PSI (`kotlin-compiler-embeddable`) | Exact resolution | Exact reference graph | Apache-2.0 | ~60 MB jar, heavy RAM | yes | High | No | — | Reserve |
| detekt / ktlint | Kotlin static analysis and formatting | Static gate (diff-only) | Apache-2.0 / MIT | modest | yes | Low-medium | No | No | **Use** (diff-only to avoid mass reformatting) |
| gitleaks | Secret scanning | Security gate | MIT | trivial | yes | Low (an external binary) | Partly (`redact` patterns) | — | Use, or port patterns into a Kotlin scanner (preferred: no extra binary) |
| OSV-Scanner + OSV data | Dependency vulnerability lookup | Security gate | Apache-2.0 | trivial | needs a periodically refreshed offline DB | Low | No | No | Use with a weekly data refresh through restricted network |
| Windows Job Objects, a dedicated standard user, Windows Firewall per-user or AppContainer rules | Process isolation and limits | Ring 3 sandbox | Part of Windows | — | yes | Medium (needs a small native helper or JNA) | No | Small native helper justified | **Use** |
| bubblewrap (Linux dev hosts) | Unprivileged sandbox | The same sandbox on Linux dev and CI | LGPL-2.0+ (external program) | — | yes | Low | No | — | Use on Linux |
| Docker / WSL2 | Containers | Sandbox alternative | Apache-2.0 / Windows | Several GB | yes | High; not guaranteed on the owner's PC | No | — | **Reject** as a requirement |
| OpenHands | A full coding-agent platform | — | MIT ✔ | Docker runtime, Python | yes with local models | High | **Yes** (agent loop, memory, sandbox) | — | **Reject embedding**; reference only |
| SWE-agent | Agent with ACI | — | MIT | Docker, Python | partly | High | Yes | — | Reference only |
| Aider | Repository map (tree-sitter + ranking), edit formats, Git-native workflow | — | Apache-2.0 | Python | yes with local models | Medium as an *external tool*; but a Python runtime and its own conversation state | Yes (identity and memory) | — | **Borrow techniques** (repository map, search/replace edit format); do not embed |
| Primus `legacy/` change pipeline | A Python proposal → test → evaluate → deploy pipeline | — | Owner's own code | Python | yes | High (Python) | Yes | — | **Port rules as tests**; do not run |

## 3. Key build-vs-adopt decision: the coding engine

| Criterion | Embed OpenHands / SWE-agent / Aider | CLU-native loop (Agentless-style) |
|---|---|---|
| One identity and memory | Each carries its own agent state, prompts and memory | Uses CLU's prompt, knowledge and inference queue |
| Runtime footprint on the PC | A Python runtime, and Docker for OpenHands and SWE-agent | None added |
| Small local models | Designed and evaluated mostly with strong hosted models; long free-form loops degrade with small models | Fixed pipeline phases and deterministic localisation suit weaker models (the Agentless thesis) |
| Permission integration | Would need wrapping, which duplicates the broker | The broker and audit are already in place |
| Effort | Lower initial effort, higher integration and maintenance effort | A moderate, testable Kotlin implementation reusing M7 |
| **Decision** | — | **CLU-native**, borrowing ACI edit blocks, the repository map and the Agentless phases |

Revisit condition: if gate G0 shows that no local model can fix any CLU-bench task, the native loop can still drive an
owner-configured external model endpoint through the same interface. No architectural change is needed.

## 4. Key build-vs-adopt decision: architecture index

Deterministic facts (Git, Gradle, lexer, JaCoCo) outperform LLM reading in cost and correctness. No existing open
project produces a Kotlin component, test and schema map for this custom build out of the box. A custom indexer of a
few thousand lines over existing tools is justified. tree-sitter is held in reserve for when measured precision
demands it.
