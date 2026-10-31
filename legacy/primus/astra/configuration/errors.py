"""Configuration error types."""

from __future__ import annotations


class ConfigurationError(Exception):
    """Raised when configuration is missing, malformed, or forbidden.

    Forbidden configuration is configuration that would violate the project
    constitution, such as pointing a reasoning backend at a remote host.
    """


__all__ = ["ConfigurationError"]
