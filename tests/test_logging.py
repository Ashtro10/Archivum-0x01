"""Logging System tests."""

from __future__ import annotations

import logging as stdlib_logging
import tempfile
import unittest
from pathlib import Path

from astra.configuration import LoggingSettings
from astra.logging import ROOT_LOGGER_NAME, configure_logging, get_logger, reset_logging


class ConfigureLoggingTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.directory = Path(self._temp.name)
        self.addCleanup(self._cleanup)

    def _cleanup(self) -> None:
        reset_logging()
        self._temp.cleanup()

    def _settings(self, **overrides: object) -> LoggingSettings:
        defaults: dict[str, object] = {
            "directory": self.directory,
            "console_enabled": False,
            "file_enabled": True,
        }
        defaults.update(overrides)
        return LoggingSettings(**defaults)  # type: ignore[arg-type]

    def test_writes_to_the_configured_file(self) -> None:
        settings = self._settings()
        configure_logging(settings)
        get_logger("astra.test").warning("written to disk")

        stdlib_logging.getLogger(ROOT_LOGGER_NAME).handlers[0].flush()
        self.assertIn("written to disk", settings.file_path.read_text(encoding="utf-8"))

    def test_reconfiguring_does_not_stack_handlers(self) -> None:
        settings = self._settings()
        first = configure_logging(settings)
        count = len(first.handlers)
        second = configure_logging(settings)
        self.assertEqual(count, len(second.handlers))

    def test_does_not_touch_the_root_logger(self) -> None:
        root_handlers = list(stdlib_logging.getLogger().handlers)
        configure_logging(self._settings())
        self.assertEqual(root_handlers, stdlib_logging.getLogger().handlers)
        self.assertFalse(stdlib_logging.getLogger(ROOT_LOGGER_NAME).propagate)

    def test_disabling_both_sinks_leaves_a_null_handler(self) -> None:
        logger = configure_logging(
            self._settings(console_enabled=False, file_enabled=False)
        )
        self.assertEqual(1, len(logger.handlers))
        self.assertIsInstance(logger.handlers[0], stdlib_logging.NullHandler)

    def test_level_is_applied(self) -> None:
        logger = configure_logging(self._settings(level="ERROR"))
        self.assertEqual(stdlib_logging.ERROR, logger.level)


class GetLoggerTest(unittest.TestCase):
    def test_names_are_reparented_under_astra(self) -> None:
        self.assertEqual(f"{ROOT_LOGGER_NAME}.plugins.demo", get_logger("plugins.demo").name)

    def test_astra_names_are_left_alone(self) -> None:
        self.assertEqual("astra.memory", get_logger("astra.memory").name)

    def test_stdlib_logging_is_not_shadowed(self) -> None:
        # astra.logging must not break `import logging` for absolute imports.
        self.assertEqual("logging", stdlib_logging.__name__)


if __name__ == "__main__":
    unittest.main()
