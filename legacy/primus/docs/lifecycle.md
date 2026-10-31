# Lifecycle in Practice

The constitution defines three pipelines. This document records what each stage
means concretely in this repository, so no stage can be skipped by accident.

## Development lifecycle

Applies to every feature.

| Stage | Concrete artefact |
|---|---|
| Research | Notes on prior art and options, in the task's backlog entry. In-product: `ResearchAgent` may retrieve local evidence and, when enabled, SSRF-gated HTTP/HTTPS documents (B-041). |
| Design | An ADR in `docs/adr/` when the decision is architectural |
| Implementation | Code in the owning package, behind its existing interface |
| Testing | Tests in `tests/`, mirroring the package path |
| Evaluation | Measured result against the property the change claims to improve |
| Documentation | Updates to `docs/`, docstrings, and the backlog entry |
| Deployment | Merge, version bump, entry in the version record |

A feature that reaches Implementation without a backlog entry has skipped
Research and Design. That is a process failure, not a shortcut.

## Self-improvement protocol

Astra never edits itself. Any change Astra proposes to its own source follows:

```
Research → Proposal → Implementation → Testing → Benchmarking → Human Approval → Deployment
```

Human Approval is a hard gate. Nothing in the codebase may automate past it, and
no interface should ever expose a way to bypass it. The Deployment Agent's
contract must require an explicit approval record as input.

The in-product pipeline (B-031+) implements those stages as ChangeProposal →
isolated unittest → EvaluationReport → human ApprovalRecord → deploy. See
`docs/architecture.md` and ADR-0036 for the live architecture. B-038 closed
this stage; empty test evidence is not a pass.

## Scientific method

Applies to experiments, especially performance and model-quality work.

```
Observation → Hypothesis → Experiment → Results → Conclusion → Documentation → Deployment
```

A negative result is a result. Failed experiments are stored in memory under the
`failures` category so the same dead end is not explored twice.

## Improvement policy

Every change must name at least one measurable property it improves, drawn from
the constitution's list: performance, simplicity, maintainability, reliability,
security, accuracy, resource efficiency, user experience.

"It looks cleaner" is not on the list.

## Continuous evaluation

After each completed task, record in the backlog entry or an ADR:

- Assumptions made.
- What can fail.
- Technical debt introduced.
- What should be automated.
- What was learned.
- Recommended improvements.
