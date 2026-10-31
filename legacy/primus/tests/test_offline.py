"""Offline enforcement.

The local-first policy is only real if it is checked. This test imports every
module in the package with outbound networking disabled. Any module that opens
a socket at import time fails the build.
"""

from __future__ import annotations

import importlib
import pkgutil
import socket
import sys
import unittest
from typing import Any, Iterator

import astra

BLOCKED_ATTRIBUTES = ("socket", "create_connection", "getaddrinfo")


class NetworkAccessAttempted(AssertionError):
    """Raised when Astra touches the network while it must not."""


def _blocked(*args: Any, **kwargs: Any) -> Any:
    raise NetworkAccessAttempted(
        "network access attempted during import; Astra must run offline"
    )


def _astra_modules() -> Iterator[str]:
    yield "astra"
    for module in pkgutil.walk_packages(astra.__path__, prefix="astra."):
        yield module.name


class OfflineImportTest(unittest.TestCase):
    def test_importing_every_module_touches_no_network(self) -> None:
        names = list(_astra_modules())
        cached = {
            name: module
            for name, module in sys.modules.items()
            if name == "astra" or name.startswith("astra.")
        }
        originals = {name: getattr(socket, name) for name in BLOCKED_ATTRIBUTES}

        for name in cached:
            del sys.modules[name]
        for name in BLOCKED_ATTRIBUTES:
            setattr(socket, name, _blocked)

        try:
            for name in names:
                with self.subTest(module=name):
                    importlib.import_module(name)
        finally:
            for name, original in originals.items():
                setattr(socket, name, original)
            sys.modules.update(cached)


class ConfigurationOfflineTest(unittest.TestCase):
    def test_reasoning_configuration_has_no_network_settings(self) -> None:
        """Stricter than the loopback rule it replaced (ADR-0004 D5).

        There is no host to point somewhere else, so the property holds by
        construction rather than by validation.
        """
        from astra.configuration import ReasoningSettings

        fields = set(ReasoningSettings.model_fields)
        self.assertEqual(set(), fields & {"host", "port", "url", "endpoint", "base_url"})

    def test_outbound_network_is_off_by_default(self) -> None:
        from astra.configuration import SecuritySettings

        self.assertFalse(SecuritySettings().allow_outbound_network)

    def test_load_settings_touches_no_socket(self) -> None:
        """Configuration loading must not open sockets (B-005)."""
        import tempfile
        from pathlib import Path

        from astra.configuration import load_settings

        originals = {name: getattr(socket, name) for name in BLOCKED_ATTRIBUTES}
        for name in BLOCKED_ATTRIBUTES:
            setattr(socket, name, _blocked)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "astra.toml"
                path.write_text("[reasoning]\ncontext_tokens = 4096\n", encoding="utf-8")
                settings = load_settings(path)
                self.assertEqual(4096, settings.reasoning.context_tokens)
        finally:
            for name, original in originals.items():
                setattr(socket, name, original)

    def test_load_settings_does_not_import_http_or_subprocess(self) -> None:
        from pathlib import Path

        import astra.configuration.loader as loader
        import astra.configuration.settings as settings_mod

        for module in (loader, settings_mod):
            source = Path(module.__file__).read_text(encoding="utf-8")
            for forbidden in (
                "import httpx",
                "import urllib",
                "from urllib",
                "import requests",
                "import subprocess",
                "from subprocess",
                "import socket",
                "from socket",
            ):
                with self.subTest(module=module.__name__, statement=forbidden):
                    self.assertNotIn(forbidden, source)


class DependencyTest(unittest.TestCase):
    # B-005: pydantic is the approved config validator (ADR-0001). It may
    # appear only in the configuration settings module.
    _PYDANTIC_ALLOWED = frozenset({"astra.configuration.settings"})

    def test_scaffold_imports_no_third_party_packages(self) -> None:
        """Third-party imports stay confined to the modules that need them.

        B-005: ``pydantic`` is allowed in ``astra.configuration.settings``
        only. ``httpx``, ``numpy``, and ``faiss`` remain forbidden in scanned
        sources.
        """
        for name in _astra_modules():
            module = importlib.import_module(name)
            source = getattr(module, "__file__", None)
            if source is None:
                continue
            with open(source, encoding="utf-8") as handle:
                text = handle.read()
            for forbidden in ("import httpx", "import numpy", "import faiss"):
                with self.subTest(module=name, statement=forbidden):
                    self.assertNotIn(forbidden, text)
            uses_pydantic = False
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("#"):
                    continue
                if (
                    stripped.startswith("import pydantic")
                    or stripped.startswith("from pydantic")
                    or stripped.startswith("from pydantic_")
                ):
                    uses_pydantic = True
                    break
            if uses_pydantic and name not in self._PYDANTIC_ALLOWED:
                self.fail(
                    f"{name} imports pydantic; only "
                    f"{sorted(self._PYDANTIC_ALLOWED)} may (B-005)"
                )


if __name__ == "__main__":
    unittest.main()
