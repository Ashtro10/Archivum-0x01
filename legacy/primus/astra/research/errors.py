"""Research Agent error types."""

from __future__ import annotations


class ResearchError(Exception):
    """Base class for research failures."""


class SourceError(ResearchError):
    """Raised when a research source cannot be queried.

    Attributes:
        source_name: Name of the failing source.
    """

    def __init__(self, message: str, *, source_name: str = "") -> None:
        super().__init__(message)
        self.source_name = source_name


__all__ = ["ResearchError", "SourceError"]
