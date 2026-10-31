"""Configuration Manager.

Loads and validates Astra's settings. This package is a foundation layer: it
imports nothing else from :mod:`astra`.

Settings models are Pydantic v2 (B-005). Loading still uses stdlib ``tomllib``.
The public API (class names, attributes, ``load_settings``,
``ConfigurationError``) is unchanged from the dataclass era.

Example:
    >>> from astra.configuration import load_settings
    >>> settings = load_settings()
    >>> settings.reasoning.backend
    'llama_cpp'
"""

from __future__ import annotations

from astra.configuration.errors import ConfigurationError
from astra.configuration.loader import find_config_file, load_settings, save_settings
from astra.configuration.paths import (
    data_root,
    default_database_path,
    default_knowledge_database_path,
    default_log_directory,
    default_planning_database_path,
    default_scratch_directory,
    default_vector_index_path,
    ensure_directory,
    project_root,
)
from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PlanningSettings,
    ReasoningSettings,
    SecuritySettings,
    TestingSettings,
    GitSettings,
    PluginSettings,
    ResearchSettings,
    PersonalitySettings,
)

__all__ = [
    "ConfigurationError",
    "AstraSettings",
    "ReasoningSettings",
    "MemorySettings",
    "KnowledgeSettings",
    "PlanningSettings",
    "LoggingSettings",
    "SecuritySettings",
    "TestingSettings",
    "GitSettings",
    "PluginSettings",
    "ResearchSettings",
    "PersonalitySettings",
    "load_settings",
    "save_settings",
    "find_config_file",
    "project_root",
    "data_root",
    "default_database_path",
    "default_knowledge_database_path",
    "default_planning_database_path",
    "default_vector_index_path",
    "default_log_directory",
    "default_scratch_directory",
    "ensure_directory",
]
