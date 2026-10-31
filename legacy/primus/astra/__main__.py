"""Entry point for ``python -m astra`` and Primus.exe.

Usage:
    python -m astra          # live Primus (controller started)
    python -m astra --demo   # demo/read-only mode (no controller)
    python -m astra --smoke  # create UI, exit (packaging / CI)

Do not import ``ui.app`` at module level. Windows multiprocessing children
re-exec this file; a top-level UI import opens another window.
"""

from __future__ import annotations

import multiprocessing
import sys
from pathlib import Path

if any(part.startswith("--multiprocessing-") for part in sys.argv[1:]) or (
    "-m" in sys.argv[1:] or "-c" in sys.argv[1:]
):
    multiprocessing.freeze_support()
    raise SystemExit(0)

multiprocessing.freeze_support()

_ROOT = Path(__file__).parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))


def _gui_entry() -> None:
    from astra.boot import launch

    raise SystemExit(launch(sys.argv[1:]))


if __name__ == "__main__":
    from astra.boot import launch

    raise SystemExit(launch(sys.argv[1:]))
