"""Logging System.

Handlers are attached to the ``astra`` logger rather than the root logger, so
Astra never changes logging behaviour for code that merely imports it.
Propagation to the root logger is disabled for the same reason.

Repeated calls to :func:`configure_logging` replace the previous handlers
instead of stacking new ones, so reconfiguration cannot cause duplicate lines.
"""

from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # keeps this package a leaf at runtime
    from astra.configuration.settings import LoggingSettings

ROOT_LOGGER_NAME = "astra"

DEFAULT_FORMAT = "%(asctime)s %(levelname)-8s %(name)s: %(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


def configure_logging(settings: "LoggingSettings") -> logging.Logger:
    """Configure and return Astra's root logger.

    Args:
        settings: Verbosity and destinations. See
            :class:`astra.configuration.settings.LoggingSettings`.

    Returns:
        The configured ``astra`` logger.

    Raises:
        OSError: if file logging is enabled and the log directory cannot be
            created or written. Logging that silently fails is worse than
            logging that refuses to start.
    """
    logger = logging.getLogger(ROOT_LOGGER_NAME)
    logger.setLevel(settings.level.upper())
    logger.propagate = False

    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        handler.close()

    formatter = logging.Formatter(DEFAULT_FORMAT, datefmt=DEFAULT_DATE_FORMAT)

    if settings.console_enabled:
        console = logging.StreamHandler(stream=sys.stderr)
        console.setFormatter(formatter)
        logger.addHandler(console)

    if settings.file_enabled:
        settings.directory.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            settings.file_path,
            maxBytes=settings.max_bytes,
            backupCount=settings.backup_count,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

    if not logger.handlers:
        # Silences the "no handlers" warning when both sinks are switched off.
        logger.addHandler(logging.NullHandler())

    return logger


def get_logger(name: str) -> logging.Logger:
    """Return a logger nested under ``astra``.

    Args:
        name: Usually ``__name__``. Names outside the ``astra`` tree are
            re-parented so that all Astra output shares one configuration.
    """
    if name == ROOT_LOGGER_NAME or name.startswith(f"{ROOT_LOGGER_NAME}."):
        return logging.getLogger(name)
    return logging.getLogger(f"{ROOT_LOGGER_NAME}.{name}")


def reset_logging() -> None:
    """Detach and close every handler on Astra's logger.

    Intended for tests and for shutdown paths that must release log files.
    """
    logger = logging.getLogger(ROOT_LOGGER_NAME)
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        handler.close()


__all__ = [
    "ROOT_LOGGER_NAME",
    "DEFAULT_FORMAT",
    "DEFAULT_DATE_FORMAT",
    "configure_logging",
    "get_logger",
    "reset_logging",
]
