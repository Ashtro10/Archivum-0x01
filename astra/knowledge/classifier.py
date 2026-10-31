"""Keyword-based knowledge classifier.

Implements :class:`astra.knowledge.contracts.KnowledgeClassifier` with a
deterministic, rule-based approach that requires no model and no network
access. Classification is fully testable and reproducible (ADR-0008 D2).

Confidence semantics (binding for this classifier, ADR-0008 D2):

+-------+------------------------------------------------------------+
| Value | Meaning                                                    |
+=======+============================================================+
|  1.0  | Unique match on ≥1 primary keyword; no other category      |
|       | has any primary hit.                                       |
+-------+------------------------------------------------------------+
|  0.75 | Primary keyword match, but ≥1 other category also has     |
|       | a primary hit (ambiguous primary).                         |
+-------+------------------------------------------------------------+
|  0.5  | Secondary keyword match only; no primary hits anywhere.    |
+-------+------------------------------------------------------------+
|  0.25 | Secondary keyword match, but ≥1 other category also has   |
|       | secondary hits (weak ambiguous).                           |
+-------+------------------------------------------------------------+
|  0.0  | No keyword matched at all; fallback to IDEAS (ADR-0008 D3).|
+-------+------------------------------------------------------------+

These are constants, not computed floats, so test assertions can use ``==``.
"""

from __future__ import annotations

from astra.knowledge.contracts import (
    Classification,
    KnowledgeCategory,
    KnowledgeClassifier,
)

# ---------------------------------------------------------------------------
# Confidence constants — documented semantics, not arbitrary numbers.
# ---------------------------------------------------------------------------
CONFIDENCE_UNIQUE_PRIMARY = 1.0
CONFIDENCE_AMBIGUOUS_PRIMARY = 0.75
CONFIDENCE_UNIQUE_SECONDARY = 0.5
CONFIDENCE_AMBIGUOUS_SECONDARY = 0.25
CONFIDENCE_NO_MATCH = 0.0

# ---------------------------------------------------------------------------
# Keyword vocabulary — (primary_keywords, secondary_keywords) per category.
#
# Primary keywords are strong, domain-specific signals.
# Secondary keywords are broader terms that may appear in multiple categories.
#
# All keywords are lowercase. Classification lowercases content before matching.
# ---------------------------------------------------------------------------
_VOCABULARY: dict[KnowledgeCategory, tuple[frozenset[str], frozenset[str]]] = {
    KnowledgeCategory.ARCHITECTURE: (
        frozenset({
            "architecture", "microservice", "monolith", "api design",
            "design pattern", "mvc", "mvvm", "cqrs", "event sourcing",
            "dependency injection", "hexagonal", "clean architecture",
            "domain driven", "ddd", "repository pattern", "service mesh",
            "message queue", "event bus", "pub/sub", "grpc",
        }),
        frozenset({
            "system design", "module", "interface", "contract", "layer",
            "abstraction", "component", "service", "boundary",
        }),
    ),
    KnowledgeCategory.PROGRAMMING: (
        frozenset({
            "python", "javascript", "typescript", "rust", "go", "java",
            "c++", "c#", "kotlin", "swift", "ruby", "scala", "haskell",
            "closure", "lambda", "generator", "decorator", "metaclass",
            "async", "coroutine", "recursion", "algorithm", "data structure",
            "linked list", "hash map", "binary tree", "sorting", "regex",
            "oop", "functional programming", "type annotation",
        }),
        frozenset({
            "code", "function", "class", "method", "variable", "loop",
            "conditional", "import", "library", "package", "module",
            "debug", "refactor", "test", "unit test",
        }),
    ),
    KnowledgeCategory.ARTIFICIAL_INTELLIGENCE: (
        frozenset({
            "neural network", "machine learning", "deep learning",
            "transformer", "attention mechanism", "llm", "language model",
            "embedding", "vector", "rag", "fine-tuning", "qlora", "lora",
            "reinforcement learning", "supervised learning",
            "unsupervised learning", "gradient descent", "backpropagation",
            "convolutional", "recurrent", "lstm", "bert", "gpt", "llama",
            "mistral", "qwen", "inference", "tokenizer", "gguf",
            "quantization", "perplexity", "hallucination",
        }),
        frozenset({
            "model", "training", "dataset", "accuracy", "loss",
            "prediction", "classification", "regression", "clustering",
            "feature", "weight", "parameter", "epoch", "batch",
        }),
    ),
    KnowledgeCategory.MATHEMATICS: (
        frozenset({
            "calculus", "linear algebra", "matrix", "eigenvalue",
            "differential equation", "integral", "derivative",
            "probability", "statistics", "bayesian", "normal distribution",
            "entropy", "information theory", "graph theory", "topology",
            "number theory", "combinatorics", "fourier", "laplace",
            "convolution", "dot product", "cross product",
        }),
        frozenset({
            "equation", "formula", "proof", "theorem", "variable",
            "function", "vector", "scalar", "set", "sum",
        }),
    ),
    KnowledgeCategory.OPERATING_SYSTEMS: (
        frozenset({
            "linux", "windows", "macos", "kernel", "process", "thread",
            "scheduler", "memory management", "virtual memory", "paging",
            "segmentation", "file system", "inode", "syscall",
            "system call", "pipe", "signal", "fork", "exec",
            "mutex", "semaphore", "deadlock", "context switch",
            "interrupt", "driver", "boot", "daemon",
        }),
        frozenset({
            "os", "shell", "command line", "terminal", "process",
            "cpu", "ram", "disk", "directory", "permissions",
        }),
    ),
    KnowledgeCategory.NETWORKING: (
        frozenset({
            "tcp", "udp", "ip", "http", "https", "dns", "dhcp",
            "socket", "port", "firewall", "vpn", "proxy", "nat",
            "subnet", "cidr", "osi model", "ethernet", "wifi",
            "tls", "ssl", "certificate", "bandwidth", "latency",
            "packet", "router", "switch", "load balancer",
        }),
        frozenset({
            "network", "protocol", "connection", "server", "client",
            "request", "response", "api", "endpoint",
        }),
    ),
    KnowledgeCategory.SECURITY: (
        frozenset({
            "authentication", "authorisation", "authorization",
            "encryption", "decryption", "hash", "salt", "jwt",
            "oauth", "csrf", "xss", "sql injection", "buffer overflow",
            "vulnerability", "exploit", "cve", "penetration test",
            "zero day", "malware", "ransomware", "phishing",
            "certificate authority", "public key", "private key",
            "symmetric", "asymmetric", "aes", "rsa",
        }),
        frozenset({
            "security", "password", "token", "permission", "role",
            "access control", "audit", "log",
        }),
    ),
    KnowledgeCategory.HARDWARE: (
        frozenset({
            "cpu", "gpu", "vram", "ram", "ssd", "nvme", "pcie",
            "cuda", "nvidia", "amd", "intel", "ryzen", "geforce",
            "rtx", "gtx", "processor", "core", "thread",
            "clock speed", "thermal", "tdp", "fps", "benchmark",
            "memory bandwidth", "tensor core", "shader",
        }),
        frozenset({
            "hardware", "device", "chip", "motherboard", "power",
            "voltage", "temperature", "performance",
        }),
    ),
    KnowledgeCategory.PROJECTS: (
        frozenset({
            "astra", "project", "milestone", "sprint", "backlog",
            "feature", "requirement", "specification", "roadmap",
            "release", "version", "deployment", "ci/cd", "pipeline",
            "pull request", "code review", "issue tracker",
        }),
        frozenset({
            "task", "goal", "plan", "implement", "build", "develop",
            "team", "deadline",
        }),
    ),
    KnowledgeCategory.USER_PREFERENCES: (
        frozenset({
            "prefer", "preference", "favourite", "favorite",
            "like", "dislike", "want", "need", "style",
            "habit", "workflow", "setup", "config",
            "i prefer", "i like", "my preference",
        }),
        frozenset({
            "user", "personal", "setting", "option", "choose",
        }),
    ),
    KnowledgeCategory.EXPERIMENTS: (
        frozenset({
            "experiment", "hypothesis", "observation", "result",
            "measured", "benchmark", "profiling", "ablation",
            "trial", "iteration", "tested", "verified", "evidence",
        }),
        frozenset({
            "test", "run", "sample", "output", "log", "data",
        }),
    ),
    KnowledgeCategory.FAILURES: (
        frozenset({
            "failed", "failure", "error", "bug", "crash", "exception",
            "traceback", "segfault", "oom", "out of memory",
            "timeout", "deadlock", "race condition", "regression",
            "broken", "corrupt", "data loss",
        }),
        frozenset({
            "problem", "issue", "fix", "workaround", "wrong",
            "unexpected", "missing",
        }),
    ),
    KnowledgeCategory.IDEAS: (
        frozenset({
            "idea", "proposal", "suggestion", "thought", "concept",
            "brainstorm", "consider", "might", "could", "what if",
            "explore", "potential", "possible",
        }),
        frozenset({
            "maybe", "perhaps", "try", "approach", "design",
        }),
    ),
}


class KeywordKnowledgeClassifier(KnowledgeClassifier):
    """Deterministic keyword-based classifier (ADR-0008 D2).

    Scans content for primary and secondary keywords per category. Returns
    the highest-scoring match with documented confidence semantics.

    This classifier produces the same output for the same input on every call,
    making it fully testable without any running model.
    """

    def classify(self, content: str) -> Classification:
        """Assign a category based on keyword presence.

        Case-insensitive. Confidence semantics are defined in the module
        docstring. The fallback category when no keyword matches is ``IDEAS``
        with confidence ``0.0`` (ADR-0008 D3).
        """
        if not content or not content.strip():
            return Classification(
                category=KnowledgeCategory.IDEAS,
                confidence=CONFIDENCE_NO_MATCH,
                rationale="empty content; fallback to IDEAS",
            )

        lower = content.lower()
        primary_hits: dict[KnowledgeCategory, int] = {}
        secondary_hits: dict[KnowledgeCategory, int] = {}

        for category, (primary_kw, secondary_kw) in _VOCABULARY.items():
            p = sum(1 for kw in primary_kw if kw in lower)
            s = sum(1 for kw in secondary_kw if kw in lower)
            if p:
                primary_hits[category] = p
            elif s:
                secondary_hits[category] = s

        if primary_hits:
            best = max(primary_hits, key=lambda c: primary_hits[c])
            if len(primary_hits) == 1:
                confidence = CONFIDENCE_UNIQUE_PRIMARY
                rationale = (
                    f"unique primary keyword match for "
                    f"{best.value!r} ({primary_hits[best]} hit(s))"
                )
            else:
                confidence = CONFIDENCE_AMBIGUOUS_PRIMARY
                others = [c.value for c in primary_hits if c is not best]
                rationale = (
                    f"primary keyword match for {best.value!r} "
                    f"({primary_hits[best]} hit(s)); also matched: "
                    + ", ".join(others)
                )
            return Classification(
                category=best, confidence=confidence, rationale=rationale
            )

        if secondary_hits:
            best = max(secondary_hits, key=lambda c: secondary_hits[c])
            if len(secondary_hits) == 1:
                confidence = CONFIDENCE_UNIQUE_SECONDARY
                rationale = (
                    f"unique secondary keyword match for "
                    f"{best.value!r} ({secondary_hits[best]} hit(s))"
                )
            else:
                confidence = CONFIDENCE_AMBIGUOUS_SECONDARY
                others = [c.value for c in secondary_hits if c is not best]
                rationale = (
                    f"weak secondary match for {best.value!r} "
                    f"({secondary_hits[best]} hit(s)); also matched: "
                    + ", ".join(others)
                )
            return Classification(
                category=best, confidence=confidence, rationale=rationale
            )

        return Classification(
            category=KnowledgeCategory.IDEAS,
            confidence=CONFIDENCE_NO_MATCH,
            rationale="no keyword matched; fallback to IDEAS",
        )


__all__ = [
    "KeywordKnowledgeClassifier",
    "CONFIDENCE_UNIQUE_PRIMARY",
    "CONFIDENCE_AMBIGUOUS_PRIMARY",
    "CONFIDENCE_UNIQUE_SECONDARY",
    "CONFIDENCE_AMBIGUOUS_SECONDARY",
    "CONFIDENCE_NO_MATCH",
]
