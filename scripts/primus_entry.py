"""PyInstaller entry for Primus.exe.

``freeze_support`` must be the first runtime call. Importing the UI or
``astra.__main__`` before that re-opens a window on every Windows spawn.

Worker argv (``-m unittest``, ``-c``, multiprocessing) must exit before
``launch`` so a frozen ``sys.executable`` cannot fork-bomb the GUI.
"""

from __future__ import annotations

import sys

_args = sys.argv[1:]
if any(part.startswith("--multiprocessing-") for part in _args) or (
    "-m" in _args or "-c" in _args
):
    import multiprocessing

    multiprocessing.freeze_support()
    raise SystemExit(0)

import multiprocessing

multiprocessing.freeze_support()


if __name__ == "__main__":
    from astra.boot import launch

    raise SystemExit(launch(sys.argv[1:]))
