"""Harmless ProcessRunner probe (B-024, ADR-0023).

Not a test runner. Not a TestingAgent. Prints a fixed token and exits 0.
"""

from __future__ import annotations

TOKEN = "ASTRA_PROCESS_PROBE_OK"


def main() -> int:
    print(TOKEN, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
