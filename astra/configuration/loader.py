"""Load Astra settings from an optional TOML file.

Resolution order:

1. An explicit path passed to :func:`load_settings`.
2. The ``ASTRA_CONFIG`` environment variable.
3. ``astra.toml`` in the project root.
4. Built-in defaults.

Unknown keys are rejected rather than ignored. A silently ignored typo is a
configuration that lies about what the system is doing.

This module does not import Pydantic. Settings classes own validation;
this loader owns file discovery, TOML parsing, unknown-key rejection, and
Path coercion (including ``~`` expansion).
"""

from __future__ import annotations

import json
import os
import sys
import tomllib
from pathlib import Path
from typing import Any, TypeVar, get_args

from astra.configuration.errors import ConfigurationError
from astra.configuration.paths import (
    DEFAULT_CONFIG_FILENAME,
    ENV_CONFIG,
    data_root,
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
    VoiceSettings,
    PersonalitySettings,
)
from astra.logging import get_logger

_LOGGER = get_logger(__name__)


def _path_is_file(path: Path | None) -> bool:
    if path is None:
        return False
    try:
        return path.is_file()
    except OSError:
        return False

_PERSIST_PATHS = frozenset(
    {
        ("memory", "database_path"),
        ("memory", "vector_index_path"),
        ("knowledge", "database_path"),
        ("planning", "database_path"),
    }
)
_SECTIONS: dict[str, type] = {
    "reasoning": ReasoningSettings,
    "memory": MemorySettings,
    "knowledge": KnowledgeSettings,
    "planning": PlanningSettings,
    "logging": LoggingSettings,
    "security": SecuritySettings,
    "testing": TestingSettings,
    "git": GitSettings,
    "plugins": PluginSettings,
    "research": ResearchSettings,
    "voice": VoiceSettings,
    "personality": PersonalitySettings,
}

T = TypeVar("T")


def find_config_file(explicit: Path | str | None = None) -> Path | None:
    """Return the configuration file to read, or ``None`` to use defaults.

    Raises:
        ConfigurationError: if an explicitly requested file does not exist. A
            path given on purpose and then missed is an error, not a fallback.
    """
    if explicit is not None:
        path = Path(explicit).expanduser()
        if not path.is_file():
            raise ConfigurationError(f"configuration file not found: {path}")
        return path

    from_env = os.environ.get(ENV_CONFIG)
    if from_env:
        path = Path(from_env).expanduser()
        if not path.is_file():
            raise ConfigurationError(
                f"{ENV_CONFIG} points at a missing file: {path}"
            )
        return path

    default = project_root() / DEFAULT_CONFIG_FILENAME
    return default if default.is_file() else None


def load_settings(path: Path | str | None = None) -> AstraSettings:
    """Build an :class:`AstraSettings` from file and defaults."""
    config_file = find_config_file(path)
    raw = _read_toml(config_file) if config_file is not None else {}

    unknown = set(raw) - set(_SECTIONS)
    if unknown:
        known = ", ".join(sorted(_SECTIONS))
        raise ConfigurationError(
            f"unknown configuration section(s): {', '.join(sorted(unknown))}; "
            f"known sections: {known}"
        )

    reasoning = _build(ReasoningSettings, raw.get("reasoning", {}), "reasoning")
    if reasoning.model_path is None or not _path_is_file(reasoning.model_path):
        found = _discover_chat_gguf()
        if found is not None:
            if reasoning.model_path is not None and not _path_is_file(reasoning.model_path):
                _LOGGER.warning(
                    "configured chat model missing (%s); using %s",
                    reasoning.model_path,
                    found,
                )
            reasoning = reasoning.model_copy(update={"model_path": found})
            if not str(reasoning.model or "").strip():
                reasoning = reasoning.model_copy(update={"model": found.stem})
    if reasoning.embedding_model_path is None:
        embed = _discover_embed_gguf()
        if embed is not None:
            reasoning = reasoning.model_copy(update={"embedding_model_path": embed})

    return AstraSettings(
        reasoning=reasoning,
        memory=_build(MemorySettings, raw.get("memory", {}), "memory"),
        knowledge=_build(KnowledgeSettings, raw.get("knowledge", {}), "knowledge"),
        planning=_build(PlanningSettings, raw.get("planning", {}), "planning"),
        logging=_build(LoggingSettings, raw.get("logging", {}), "logging"),
        security=_build(SecuritySettings, raw.get("security", {}), "security"),
        testing=_build(TestingSettings, raw.get("testing", {}), "testing"),
        git=_build(GitSettings, raw.get("git", {}), "git"),
        plugins=_build(PluginSettings, raw.get("plugins", {}), "plugins"),
        research=_build(ResearchSettings, raw.get("research", {}), "research"),
        voice=_build(VoiceSettings, raw.get("voice", {}), "voice"),
        personality=_build(PersonalitySettings, raw.get("personality", {}), "personality"),
    )


def _gguf_search_dirs() -> tuple[Path, ...]:
    root = project_root()
    dirs = [root / "models", root / "benchmarks" / "models"]
    if getattr(sys, "frozen", False):
        parent = root.parent
        dirs.extend([parent / "models", parent / "benchmarks" / "models"])
    return tuple(dirs)


def _discover_gguf(folder: Path, *, skip_substrings: tuple[str, ...] = ()) -> Path | None:
    """First *.gguf in folder, if any. Does not download."""
    try:
        if not folder.is_dir():
            return None
        found = []
        for path in folder.glob("*.gguf"):
            if not path.is_file():
                continue
            name = path.name.lower()
            if any(part in name for part in skip_substrings):
                continue
            found.append(path)
        found.sort()
    except OSError:
        return None
    return found[0] if found else None


def _discover_chat_gguf() -> Path | None:
    for folder in _gguf_search_dirs():
        found = _discover_gguf(folder, skip_substrings=("embed", "nomic"))
        if found is not None:
            return found
    return None


def _discover_embed_gguf() -> Path | None:
    for folder in _gguf_search_dirs():
        found = _discover_gguf(folder)
        if found is not None and (
            "embed" in found.name.lower() or "nomic" in found.name.lower()
        ):
            return found
        named = _discover_gguf(folder, skip_substrings=())
        if named is not None and "nomic" in named.name.lower():
            return named
    for folder in _gguf_search_dirs():
        try:
            if not folder.is_dir():
                continue
            for path in sorted(folder.glob("*nomic*.gguf")):
                if path.is_file():
                    return path
            for path in sorted(folder.glob("*embed*.gguf")):
                if path.is_file():
                    return path
        except OSError:
            continue
    return None


def _read_toml(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as handle:
            return tomllib.load(handle)
    except tomllib.TOMLDecodeError as exc:
        raise ConfigurationError(f"{path} is not valid TOML: {exc}") from exc
    except OSError as exc:
        raise ConfigurationError(f"could not read {path}: {exc}") from exc


def _build(cls: type[T], values: Any, section: str) -> T:
    """Instantiate a settings model from a mapping of raw TOML values."""
    if not isinstance(values, dict):
        raise ConfigurationError(f"[{section}] must be a table, got {type(values).__name__}")

    declared = cls.model_fields  # type: ignore[attr-defined]
    unknown = set(values) - set(declared)
    if unknown:
        raise ConfigurationError(
            f"unknown key(s) in [{section}]: {', '.join(sorted(unknown))}; "
            f"known keys: {', '.join(sorted(declared))}"
        )

    kwargs: dict[str, Any] = {}
    for name, value in values.items():
        kwargs[name] = _coerce(declared[name], value, section, name)

    try:
        return cls(**kwargs)
    except ConfigurationError:
        raise
    except TypeError as exc:
        raise ConfigurationError(f"invalid values in [{section}]: {exc}") from exc


def _is_path_annotation(annotation: Any) -> bool:
    if annotation is Path:
        return True
    return any(arg is Path for arg in get_args(annotation))


def _coerce(field_info: Any, value: Any, section: str, name: str) -> Any:
    """Convert TOML scalars to the field's declared type where needed.

    Only ``Path`` requires conversion; TOML already produces the other types
    used here. Anything else is passed through and validated by the settings
    class itself. Bool is not accepted for int fields (bool is a subclass of
    int); that trap is preserved from the dataclass loader.
    """
    if _is_path_annotation(field_info.annotation):
        if value is None:
            return None
        if not isinstance(value, str):
            raise ConfigurationError(
                f"[{section}].{name} must be a string path, "
                f"got {type(value).__name__}"
            )
        path = Path(value).expanduser()
        if not path.is_absolute():
            if (section, name) in _PERSIST_PATHS:
                path = (data_root() / path).resolve()
            elif section == "voice" and name in {
                "optimus_model_path",
                "optimus_index_path",
            }:
                path = (project_root() / path).resolve()
        return path

    if field_info.default_factory is not None or field_info.default is None:
        return value

    expected = type(field_info.default)
    if expected is float and isinstance(value, int) and not isinstance(value, bool):
        return float(value)

    # bool is a subclass of int, so an int field would silently accept `true`.
    mistyped = not isinstance(value, expected) or (
        expected is not bool and isinstance(value, bool)
    )
    if mistyped:
        raise ConfigurationError(
            f"[{section}].{name} must be {expected.__name__}, "
            f"got {type(value).__name__}"
        )

    return value


def config_file_path() -> Path:
    """Return the persistent configuration file path."""
    found = find_config_file()
    if found is not None:
        return found
    return project_root() / DEFAULT_CONFIG_FILENAME


def _toml_string(value: str) -> str:
    return json.dumps(value)


def save_settings(settings: AstraSettings, path: Path | str | None = None) -> Path:
    """Persist selected settings sections to TOML."""
    target = Path(path).expanduser() if path is not None else config_file_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    existing = _read_toml(target) if target.is_file() else {}
    personality = settings.personality
    research = settings.research
    voice = settings.voice
    existing["personality"] = {
        "name": personality.name,
        "voice": personality.voice,
        "warmth": personality.warmth,
        "directness": personality.directness,
        "humor": personality.humor,
        "formality": personality.formality,
        "verbosity": personality.verbosity,
    }
    existing["research"] = {"allow_external": research.allow_external}
    reasoning = settings.reasoning
    existing["reasoning"] = {
        "model": reasoning.model,
        "model_path": reasoning.model_path,
        "deep_model": reasoning.deep_model,
        "deep_model_path": reasoning.deep_model_path,
        "deep_gpu_layers": reasoning.deep_gpu_layers,
        "embedding_model_path": reasoning.embedding_model_path,
        "gpu_layers": reasoning.gpu_layers,
        "context_tokens": reasoning.context_tokens,
        "threads": reasoning.threads,
    }
    existing["voice"] = {
        "tts_enabled": voice.tts_enabled,
        "tts_engine": voice.tts_engine,
        "kokoro_voice": voice.kokoro_voice,
        "kokoro_lang": voice.kokoro_lang,
        "kokoro_speed": voice.kokoro_speed,
        "kokoro_device": voice.kokoro_device,
        "kokoro_repo": voice.kokoro_repo,
        "optimus_model_path": voice.optimus_model_path,
        "optimus_index_path": voice.optimus_index_path,
        "optimus_device": voice.optimus_device,
        "optimus_index_rate": voice.optimus_index_rate,
        "optimus_rms_mix_rate": voice.optimus_rms_mix_rate,
        "optimus_protect": voice.optimus_protect,
        "optimus_f0_method": voice.optimus_f0_method,
        "optimus_warm_rvc": voice.optimus_warm_rvc,
        "stt_enabled": voice.stt_enabled,
        "stt_engine": voice.stt_engine,
        "whisper_model": voice.whisper_model,
        "whisper_device": voice.whisper_device,
        "whisper_compute_type": voice.whisper_compute_type,
    }
    lines: list[str] = []
    for section, table in existing.items():
        if not isinstance(table, dict):
            continue
        lines.append(f"[{section}]")
        for key, value in table.items():
            if isinstance(value, bool):
                lines.append(f"{key} = {'true' if value else 'false'}")
            elif isinstance(value, int):
                lines.append(f"{key} = {value}")
            elif isinstance(value, float):
                lines.append(f"{key} = {value}")
            elif value is None:
                continue
            elif isinstance(value, float):
                lines.append(f"{key} = {value}")
            elif value is None:
                continue
            elif isinstance(value, str):
                lines.append(f"{key} = {_toml_string(value)}")
            elif isinstance(value, Path):
                lines.append(f'{key} = {_toml_string(str(value.as_posix()))}')
        lines.append("")
    target.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return target


__all__ = ["config_file_path", "find_config_file", "load_settings", "save_settings"]
