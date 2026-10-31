"""Astra: a local-first, modular AI operating system.

Astra runs entirely on user-controlled hardware. No module in this package
performs network I/O at import time, and no module contacts a hosted service.
See ``CONSTITUTION.md`` for the rules that govern this package and
``docs/adr/`` for the decisions behind the stack.

The package currently contains interfaces only, apart from
:mod:`astra.configuration` and :mod:`astra.logging`, which are implemented.
"""

from __future__ import annotations

__version__ = "0.0.1"

__all__ = ["__version__"]
