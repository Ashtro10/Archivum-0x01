"""Logging System.

A foundation layer: imports nothing else from :mod:`astra` at runtime.

Example:
    >>> from astra.configuration import load_settings
    >>> from astra.logging import configure_logging, get_logger
    >>> configure_logging(load_settings().logging)  # doctest: +SKIP
    >>> get_logger(__name__).info("ready")          # doctest: +SKIP

Note that this package shadows the standard library's ``logging`` only inside
the ``astra`` namespace. Modules using absolute imports (the default) still get
the standard library when they write ``import logging``.
"""

from __future__ import annotations

from astra.logging.setup import (
    DEFAULT_DATE_FORMAT,
    DEFAULT_FORMAT,
    ROOT_LOGGER_NAME,
    configure_logging,
    get_logger,
    reset_logging,
)

__all__ = [
    "ROOT_LOGGER_NAME",
    "DEFAULT_FORMAT",
    "DEFAULT_DATE_FORMAT",
    "configure_logging",
    "get_logger",
    "reset_logging",
]
