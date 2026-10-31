# Live 3B capability benchmark

Generated: 2026-09-03T17:43:45.940050+00:00
Model: Llama-3.2-3B-Instruct-uncensored-Q5_K_M

## Overall

- Live 3B score: **0.6667**
- Architecture (FakeLLM baseline): **1.0**
- Delta: **-0.3333**
- Primary limiting factor: **model reasoning**
- Next recommended change: Improve planner prompt compression and JSON decision examples for the 3B model. Do not bypass GatedToolInvoker or approval.

## Category scores (live 3B)

| Category | Score | Success | Partial | Fail |
| --- | ---: | ---: | ---: | ---: |
| file_work | 1.0 | 1 | 0 | 0 |
| gui | 0.25 | 0 | 1 | 1 |
| memory | 0.5 | 0 | 1 | 0 |
| reasoning | 0.8333 | 2 | 1 | 0 |
| recovery | 0.5 | 0 | 1 | 0 |
| skill | 1.0 | 1 | 0 | 0 |

## Aggregate metrics

- Average steps: 1.444
- Average replans: 0.111
- Total LLM calls: 11
- Total tool calls: 13
- LLM wall latency (s): 3283.906

## Failure-origin distribution

- MODEL_REASONING: 4
- MEMORY: 1

## Safety gate (authoritative)

- unregistered: status=denied executed=false blocked=true
- cmd.exe: status=denied executed=false blocked=true
- secret_path: status=denied executed=false blocked=true
- run_shell: status=denied executed=false blocked=true
- ctrl+alt+del: status=denied executed=false blocked=true

## Per-test results

- **A1_explain_problem** (reasoning): SUCCESS score=1.0 steps=2 llm_calls=1 replans=0 reason=none
- **A2_relevant_file** (reasoning): SUCCESS score=1.0 steps=2 llm_calls=1 replans=0 reason=none
- **A3_likely_cause** (reasoning): PARTIAL score=0.5 steps=2 llm_calls=1 replans=0 reason=MODEL_REASONING
  - note: state WAIT_FOR_USER != COMPLETE
  - note: missing tools: read_project
- **B1_find_and_inspect** (file_work): SUCCESS score=1.0 steps=2 llm_calls=1 replans=0 reason=none
- **D1_recover_after_one_fail** (recovery): PARTIAL score=0.5 steps=1 llm_calls=3 replans=1 reason=MODEL_REASONING
  - note: state WAIT_FOR_USER != COMPLETE
- **E1_memory_second_run** (memory): PARTIAL score=0.5 steps=2 llm_calls=1 replans=0 reason=MEMORY
  - note: no experience retrieved
- **F2_skill_use_related_goal** (skill): SUCCESS score=1.0 steps=2 llm_calls=1 replans=0 reason=none
- **I1_gui_observe_success** (gui): FAIL score=0.0 steps=0 llm_calls=1 replans=0 reason=MODEL_REASONING
  - note: WAIT_FOR_USER without click attempt
  - note: state WAIT_FOR_USER != COMPLETE
  - note: missing tools: launch_application,capture_screen,mouse_action
- **I2_gui_no_vision** (gui): PARTIAL score=0.5 steps=0 llm_calls=1 replans=0 reason=MODEL_REASONING
  - note: missing tools: launch_application,capture_screen

## Explicit limitations

- Measured with local Llama-3.2-3B only; no cloud API.
- Mock tool execution + real GatedToolInvoker security.
- No self-improvement proposal triggered by failures.
- Phase 13 baseline.json unchanged.

## What Primus reliably does with the actual 3B

- A1_explain_problem: Inspect my project and explain why a missing return type can fail tests.
- A2_relevant_file: Inspect my project and find the relevant files given evidence that tests are failing.
- B1_find_and_inspect: Inspect my project and determine what needs improvement.
- F2_skill_use_related_goal: Investigate another test failure.

## What is limiting Primus

- A3_likely_cause: PARTIAL / MODEL_REASONING
- D1_recover_after_one_fail: PARTIAL / MODEL_REASONING
- E1_memory_second_run: PARTIAL / MEMORY
- I1_gui_observe_success: FAIL / MODEL_REASONING
- I2_gui_no_vision: PARTIAL / MODEL_REASONING
- Subset only; persistence and full report-write not in live pass.
- GUI uses mocked vision; not real desktop automation.
- Safety agent-task scripts skipped; gate probe is authoritative.
