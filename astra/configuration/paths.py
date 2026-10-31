"""Filesystem locations used by Astra.

Nothing here creates directories on import. Creation is explicit, via
:func:`ensure_directory`, so that importing Astra never writes to disk.
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path
from typing import Any

ENV_HOME = "ASTRA_HOME"
ENV_HOME_ALIAS = "PRIMUS_HOME"
ENV_CONFIG = "ASTRA_CONFIG"

DEFAULT_CONFIG_FILENAME = "astra.toml"


def project_root() -> Path:
    """Return the repository root, or the directory next to Primus.exe when frozen."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[2]


def data_root() -> Path:
    """Return the directory holding durable local state.

    Overridable with ``ASTRA_HOME`` or ``PRIMUS_HOME`` so that state can
    live outside the repository, for example on a larger drive.
    """
    override = os.environ.get(ENV_HOME) or os.environ.get(ENV_HOME_ALIAS)
    if override:
        return Path(override).expanduser().resolve()
    return project_root() / "data"


def default_database_path() -> Path:
    """Path of the SQLite file that holds the durable source of truth."""
    return data_root() / "astra.db"


def default_vector_index_path() -> Path:
    """Path of the derived vector index.

    Per ADR-0001 this file is disposable: it can be deleted at any time and
    rebuilt from the SQLite database.
    """
    return data_root() / "vectors.faiss"


def default_knowledge_database_path() -> Path:
    """Path of the SQLite file that holds the durable knowledge store.

    Separate from the memory database (ADR-0008 D1): knowledge and memory have
    independent schemas, independent migration paths, and must not share a file.
    """
    return data_root() / "knowledge.db"


def default_planning_database_path() -> Path:
    """Path of the SQLite file that holds the durable planning store.

    Separate from memory and knowledge databases (ADR-0009 D1).
    """
    return data_root() / "planning.db"


def default_scratch_directory() -> Path:
    """Dedicated overlay root outside the repository.

    A child of the OS temp directory. Not ``project_root/data``. Does not
    create the directory on import.
    """
    return Path(tempfile.gettempdir()) / "astra-scratch"


def default_log_directory() -> Path:
    """Directory for rotating log files."""
    return project_root() / "logs"


def ensure_directory(path: Path) -> Path:
    """Create ``path`` and its parents if absent, then return it."""
    path.mkdir(parents=True, exist_ok=True)
    return path


def project_read_exclusion_paths(settings: Any | None = None) -> tuple[Path, ...]:
    """Prefix exclusions for controlled project reads (ADR-0017 D4).

    Layout only. Does not open files or consult Security.
    """
    root = project_root()
    raw: list[Path] = [
        root / ".git",
        root / "data",
        root / "logs",
        root / "models",
        root / "benchmarks" / "models",
        root / ".venv",
        root / "venv",
    ]
    if settings is not None:
        for attr, field in (
            ("memory", "database_path"),
            ("knowledge", "database_path"),
            ("planning", "database_path"),
            ("memory", "vector_index_path"),
        ):
            section = getattr(settings, attr, None)
            path = getattr(section, field, None) if section is not None else None
            if path is not None:
                raw.append(Path(path).parent)
        logging = getattr(settings, "logging", None)
        directory = getattr(logging, "directory", None) if logging is not None else None
        if directory is not None:
            raw.append(Path(directory))
    return tuple(raw)


__all__ = [
    "ENV_HOME",
    "ENV_HOME_ALIAS",
    "ENV_CONFIG",
    "DEFAULT_CONFIG_FILENAME",
    "project_root",
    "data_root",
    "default_database_path",
    "default_vector_index_path",
    "default_knowledge_database_path",
    "default_planning_database_path",
    "default_log_directory",
    "default_scratch_directory",
    "ensure_directory",
    "project_read_exclusion_paths",
]
