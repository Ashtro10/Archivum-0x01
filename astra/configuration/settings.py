"""Settings for Astra.

Implemented with Pydantic v2 models (B-005, ADR-0001 dependency register).
The public names, defaults, TOML sections, and validation rules match the
previous dataclass implementation. Callers still construct these types with
keyword arguments and still receive :class:`ConfigurationError` on invalid
input — never a raw Pydantic ``ValidationError``.

Validation here is not cosmetic. It enforces constitutional rules that would
otherwise depend on everyone remembering them, most importantly that human
approval cannot be switched off.

There is deliberately **no host, port, or URL field anywhere in this module**.
ADR-0004 removes network transport from the inference path entirely, so the
older rule "a reasoning host must be loopback" is replaced by the stronger
property that no such setting exists to misconfigure.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic import ValidationError

from astra.configuration.errors import ConfigurationError
from astra.configuration.paths import (
    default_database_path,
    default_knowledge_database_path,
    default_log_directory,
    default_planning_database_path,
    default_scratch_directory,
    default_vector_index_path,
)

SUPPORTED_REASONING_BACKENDS = frozenset({"llama_cpp"})
"""Local runtimes. ADR-0004 removed ``ollama``: reaching it requires an HTTP
API, which Astra does not depend on for inference."""

LOG_LEVELS = frozenset({"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"})


def _format_pydantic_error(exc: ValidationError) -> str:
    """Turn a Pydantic error into a single ConfigurationError message."""
    parts: list[str] = []
    for err in exc.errors():
        loc = ".".join(str(item) for item in err.get("loc", ()))
        msg = err.get("msg", "invalid value")
        parts.append(f"{loc}: {msg}" if loc else msg)
    return "; ".join(parts) or str(exc)


class _SettingsModel(BaseModel):
    """Shared config: extra keys forbidden, frozen, defaults validated."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        validate_default=True,
        str_strip_whitespace=False,
    )

    def __init__(self, **data: Any) -> None:
        try:
            super().__init__(**data)
        except ValidationError as exc:
            raise ConfigurationError(_format_pydantic_error(exc)) from exc


class ReasoningSettings(_SettingsModel):
    """Which local model files to load, and how to load them.

    Models are GGUF files on disk, loaded in this process (ADR-0004). Changing
    a model is a configuration change, never a code change. Astra never
    downloads models or installs packages on your behalf.

    Default values reflect the B-002 stage 4 benchmark results (ADR-0005 §16,
    accepted 2026-08-14): Qwen2.5-3B-Instruct Q5_K_M at 4096-token context,
    full GPU offload, on an RTX 3050 Laptop with 4 GiB of VRAM.

    Attributes:
        backend: Runtime implementation. Only ``llama_cpp`` is supported.
        model: Human-readable label for the chat model. The label does not
            affect which file is loaded; set ``model_path`` to the GGUF file
            for generation.
        embedding_model: Human-readable label for the embedding model.
        model_path: Absolute path to the GGUF file for generation. ``None``
            until the user provides it; the backend reports itself unavailable
            rather than failing silently.
        embedding_model_path: Absolute path to the GGUF file for embeddings.
        deep_model: Label for the optional deep-reasoning chat model.
        deep_model_path: Absolute path to the deep GGUF. Agent loop uses this
            when present; everyday chat uses ``model_path``.
        deep_gpu_layers: GPU layers for the deep model. ``None`` uses
            ``gpu_layers``.
        embedding_dim: Expected output vector width. Must match the chosen
            embedding model. Default 768 matches nomic-embed-text-v1.5.
        context_tokens: Context window in tokens. Default 4096 is the
            benchmarked value: fits the 3B model in VRAM with 1356 MiB to
            spare, and gives a realistic agentic context.
        gpu_layers: Layers offloaded to the GPU for generation. ``99``
            (convention: any value exceeding the layer count) means all layers,
            which is correct for the Qwen2.5-3B on this GPU. Set to 0 for
            CPU-only; set to 18 for the optional 8B deep-reasoning model.
        embedding_gpu_layers: GPU layers for the embedding model. Defaults to
            ``0`` (CPU) per ADR-0004 D7: a 4 GiB VRAM budget is better spent
            on the generation model's full offload.
        embedding_context_tokens: Context window for the embedding model.
            Must be set independently from ``context_tokens`` (generation).
            B-010 measured that nomic-embed-text-v1.5 Q8_0 has
            ``n_ctx_train = 2048``; using a larger value triggers a context-
            overflow warning and may degrade quality for long inputs.
            Default 2048 matches the GGUF training context.
        threads: CPU threads for compute not on the GPU, or ``None`` to let
            the runtime decide.
        vision_model_path: Optional local vision-model file. Empty means
            visual reasoning is unavailable. Capture still works.
        screen_max_width: Default screenshot width cap in pixels.
        screen_max_height: Default screenshot height cap in pixels.
    """

    backend: str = Field(default="llama_cpp", strict=True)
    model: str = Field(default="Qwen2.5-3B-Instruct-Q5_K_M", strict=True)
    embedding_model: str = Field(default="nomic-embed-text-v1.5", strict=True)
    model_path: Path | None = None
    deep_model: str = Field(default="", strict=True)
    deep_model_path: Path | None = None
    deep_gpu_layers: int | None = Field(default=None, strict=True)
    embedding_model_path: Path | None = None
    embedding_dim: int = Field(default=768, strict=True)
    context_tokens: int = Field(default=4096, strict=True)
    gpu_layers: int = Field(default=99, strict=True)
    embedding_gpu_layers: int = Field(default=0, strict=True)
    embedding_context_tokens: int = Field(default=2048, strict=True)
    threads: int | None = Field(default=None, strict=True)
    vision_model_path: Path | None = None
    screen_max_width: int = Field(default=1280, strict=True)
    screen_max_height: int = Field(default=720, strict=True)

    @field_validator("backend")
    @classmethod
    def _backend_supported(cls, value: str) -> str:
        if value not in SUPPORTED_REASONING_BACKENDS:
            supported = ", ".join(sorted(SUPPORTED_REASONING_BACKENDS))
            raise ConfigurationError(
                f"unsupported reasoning backend {value!r}; supported: {supported}"
            )
        return value

    @field_validator("embedding_dim")
    @classmethod
    def _embedding_dim_positive(cls, value: int) -> int:
        if value <= 0:
            raise ConfigurationError("embedding_dim must be positive")
        return value

    @field_validator("context_tokens")
    @classmethod
    def _context_tokens_positive(cls, value: int) -> int:
        if value <= 0:
            raise ConfigurationError("context_tokens must be positive")
        return value

    @field_validator("gpu_layers", "embedding_gpu_layers")
    @classmethod
    def _gpu_layers_not_negative(cls, value: int) -> int:
        if value < 0:
            raise ConfigurationError("gpu layer counts must not be negative")
        return value

    @field_validator("threads")
    @classmethod
    def _threads_positive_when_set(cls, value: int | None) -> int | None:
        if value is not None and value <= 0:
            raise ConfigurationError("threads must be positive when set")
        return value

    @field_validator("screen_max_width", "screen_max_height")
    @classmethod
    def _screen_dims_positive(cls, value: int) -> int:
        if value <= 0:
            raise ConfigurationError("screen capture dimensions must be positive")
        return value

    @field_validator("model", "embedding_model")
    @classmethod
    def _model_labels_not_empty(cls, value: str) -> str:
        if not value:
            raise ConfigurationError("model and embedding_model must not be empty")
        return value


class MemorySettings(_SettingsModel):
    """Where durable state lives.

    ``database_path`` is the source of truth. ``vector_index_path`` is derived
    and may be deleted and rebuilt at any time (ADR-0001 D1).
    """

    database_path: Path = Field(default_factory=default_database_path)
    vector_index_path: Path = Field(default_factory=default_vector_index_path)

    @model_validator(mode="after")
    def _truth_and_index_must_differ(self) -> MemorySettings:
        if self.database_path == self.vector_index_path:
            raise ConfigurationError(
                "database_path and vector_index_path must differ: the index is "
                "disposable and the database is not"
            )
        return self


class LoggingSettings(_SettingsModel):
    """Logging destinations and verbosity."""

    level: str = Field(default="INFO", strict=True)
    directory: Path = Field(default_factory=default_log_directory)
    filename: str = Field(default="astra.log", strict=True)
    console_enabled: bool = Field(default=True, strict=True)
    file_enabled: bool = Field(default=True, strict=True)
    max_bytes: int = Field(default=5_000_000, strict=True)
    backup_count: int = Field(default=5, strict=True)

    @field_validator("level")
    @classmethod
    def _known_log_level(cls, value: str) -> str:
        if value.upper() not in LOG_LEVELS:
            levels = ", ".join(sorted(LOG_LEVELS))
            raise ConfigurationError(
                f"unknown log level {value!r}; expected one of: {levels}"
            )
        return value

    @field_validator("max_bytes")
    @classmethod
    def _max_bytes_positive(cls, value: int) -> int:
        if value <= 0:
            raise ConfigurationError("max_bytes must be positive")
        return value

    @field_validator("backup_count")
    @classmethod
    def _backup_count_not_negative(cls, value: int) -> int:
        if value < 0:
            raise ConfigurationError("backup_count must not be negative")
        return value

    @property
    def file_path(self) -> Path:
        """Full path of the active log file."""
        return self.directory / self.filename


class SecuritySettings(_SettingsModel):
    """Guardrails that the rest of the system must honour."""

    allow_outbound_network: bool = Field(default=False, strict=True)
    require_human_approval: bool = Field(default=True, strict=True)
    allowed_application_executables: tuple[Path, ...] = Field(default_factory=tuple)
    max_input_actions: int = Field(default=10, strict=True)
    max_visual_observations: int = Field(default=10, strict=True)
    max_interaction_time_seconds: float = Field(default=60.0)

    @field_validator("require_human_approval")
    @classmethod
    def _approval_cannot_be_disabled(cls, value: bool) -> bool:
        if not value:
            raise ConfigurationError(
                "require_human_approval cannot be disabled: the constitution "
                "requires human approval before permanent changes"
            )
        return value

    @field_validator("max_input_actions", "max_visual_observations")
    @classmethod
    def _input_limits_positive(cls, value: int) -> int:
        if value <= 0:
            raise ConfigurationError("input and observation limits must be positive")
        return value

    @field_validator("max_interaction_time_seconds")
    @classmethod
    def _interaction_time_positive(cls, value: float) -> float:
        if value <= 0:
            raise ConfigurationError("max_interaction_time_seconds must be positive")
        return value


class KnowledgeSettings(_SettingsModel):
    """Where the Knowledge Engine stores its durable state.

    Separate from :class:`MemorySettings`: knowledge and memory have different
    schemas, different migration paths, and must not share a database file
    (ADR-0008 D1).

    ``database_path`` is the source of truth. Any future vector index over
    knowledge will be derived from it and must be rebuildable at any time,
    following the same ADR-0001 D1 principle applied to memory.
    """

    database_path: Path = Field(default_factory=default_knowledge_database_path)


class PlanningSettings(_SettingsModel):
    """Where the Planning Engine stores its durable state.

    Separate from Memory and Knowledge databases (ADR-0009 D1).
    ``database_path`` is the source of truth for tasks and dependencies.
    """

    database_path: Path = Field(default_factory=default_planning_database_path)


class TestingSettings(_SettingsModel):
    """Where proposal overlays live (B-021, ADR-0020).

    Scratch is a dedicated directory outside ``project_root``. It is **not**
    added to ``check_file_write`` roots. Importing Astra does not create it.

    ``allowed_process_executables`` stays empty in config and is **not** a
    production allowlist (B-024). ``PolicySecurityAgent.from_settings``
    authorizes ``sys.executable`` only. Constructor injection remains for tests.
    """

    scratch_directory: Path = Field(default_factory=default_scratch_directory)
    allowed_process_executables: tuple[Path, ...] = Field(default_factory=tuple)


class GitSettings(_SettingsModel):
    """Controller-owned Git history configuration (B-033, ADR-0032).

    ``executable`` is explicit. Production does not search PATH. ``None``
    means Git is unavailable: deployments still succeed, history is not
    claimed. Identity is used only as repository-local / process env for
    commits. Global Git configuration is never modified.
    """

    executable: Path | None = None
    init_if_missing: bool = Field(default=False, strict=True)
    author_name: str = Field(default="Astra", strict=True)
    author_email: str = Field(default="astra@localhost", strict=True)
    timeout_seconds: float = Field(default=30.0)

    @field_validator("author_name")
    @classmethod
    def _author_name_safe(cls, value: str) -> str:
        if not re.fullmatch(r"^[A-Za-z0-9 ._'-]{1,64}$", value or ""):
            raise ConfigurationError("git author_name rejected")
        return value

    @field_validator("author_email")
    @classmethod
    def _author_email_safe(cls, value: str) -> str:
        if not re.fullmatch(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$", value or ""):
            raise ConfigurationError("git author_email rejected")
        if any(ch in value for ch in ("\n", "\r", "\0")):
            raise ConfigurationError("git author_email rejected")
        return value

    @field_validator("timeout_seconds")
    @classmethod
    def _timeout_bounded(cls, value: float) -> float:
        if value <= 0 or value > 60:
            raise ConfigurationError("git timeout_seconds must be > 0 and <= 60")
        return value


class ResearchSettings(_SettingsModel):
    """Whether the Research Agent may retrieve approved external documents.

    This is a capability flag, not a URL, host, or port (ADR-0004). Default
    is deny. Destination validation stays in Security, not in this section.
    """

    allow_external: bool = Field(default=False, strict=True)


SUPPORTED_TTS_ENGINES = frozenset({"optimus", "kokoro"})
SUPPORTED_STT_ENGINES = frozenset({"whisper"})


class VoiceSettings(_SettingsModel):
    """Offline speech I/O — Kokoro TTS or Optimus Prime RVC.

    Install optional deps::

        pip install kokoro torch huggingface_hub
        pip install git+https://github.com/Sergey004/rvc_py.git
        pip install faster-whisper transformers soundfile librosa
    """

    tts_enabled: bool = Field(default=True, strict=True)
    tts_engine: str = Field(default="kokoro", strict=True)
    kokoro_voice: str = Field(default="am_adam", strict=True)
    kokoro_lang: str = Field(default="a", strict=True)
    kokoro_speed: float = Field(default=1.0, ge=0.5, le=2.0, strict=True)
    kokoro_device: str = Field(default="cpu", strict=True)
    kokoro_repo: str = Field(default="hexgrad/Kokoro-82M", strict=True)
    optimus_model_path: Path | None = None
    optimus_index_path: Path | None = None
    optimus_device: str = Field(default="auto", strict=True)
    optimus_index_rate: float = Field(default=0.78, ge=0.0, le=1.0, strict=True)
    optimus_rms_mix_rate: float = Field(default=0.55, ge=0.0, le=1.0, strict=True)
    optimus_protect: float = Field(default=0.33, ge=0.0, le=0.5, strict=True)
    optimus_f0_method: str = Field(default="rmvpe", strict=True)
    optimus_warm_rvc: bool = Field(default=False, strict=True)

    stt_enabled: bool = Field(default=True, strict=True)
    stt_engine: str = Field(default="whisper", strict=True)
    whisper_model: str = Field(default="base", strict=True)
    whisper_device: str = Field(default="cpu", strict=True)
    whisper_compute_type: str = Field(default="int8", strict=True)

    @field_validator("tts_engine")
    @classmethod
    def _tts_engine_supported(cls, value: str) -> str:
        if value not in SUPPORTED_TTS_ENGINES:
            raise ConfigurationError(
                f"voice.tts_engine must be one of: {', '.join(sorted(SUPPORTED_TTS_ENGINES))}"
            )
        return value

    @field_validator("optimus_f0_method")
    @classmethod
    def _optimus_f0_method_supported(cls, value: str) -> str:
        allowed = frozenset({"rmvpe", "torchcrepe"})
        if value not in allowed:
            raise ConfigurationError(
                f"voice.optimus_f0_method must be one of: {', '.join(sorted(allowed))}"
            )
        return value

    @field_validator("stt_engine")
    @classmethod
    def _stt_engine_supported(cls, value: str) -> str:
        if value not in SUPPORTED_STT_ENGINES:
            raise ConfigurationError(
                f"voice.stt_engine must be one of: {', '.join(sorted(SUPPORTED_STT_ENGINES))}"
            )
        return value


class PersonalitySettings(_SettingsModel):
    """Stable Primus identity. Independent of the loaded GGUF model.

    No host, port, or URL. Changing the model must not change who Primus is.
    Trait sliders (0–100) adjust the system prompt; they are not cosmetic.
    """

    name: str = Field(default="Primus", strict=True)
    voice: str = Field(
        default=(
            "You are Primus, a local-first personal AI for one user. "
            "Be calm, precise, and honest. The language model is replaceable; "
            "you are Primus. Use provided memory, goals, knowledge, research "
            "evidence, and file contents. Do not invent personal facts, URLs, "
            "or citations. Do not claim you searched the web unless external "
            "research evidence is present. Ask at most one useful clarification "
            "when a plan lacks skill level or time."
        ),
        strict=True,
    )
    warmth: int = Field(default=55, ge=0, le=100, strict=True)
    directness: int = Field(default=60, ge=0, le=100, strict=True)
    humor: int = Field(default=25, ge=0, le=100, strict=True)
    formality: int = Field(default=35, ge=0, le=100, strict=True)
    verbosity: int = Field(default=45, ge=0, le=100, strict=True)

    def trait_guidance(self) -> str:
        """Render slider values as concise system guidance."""
        def band(value: int, low: str, mid: str, high: str) -> str:
            if value <= 33:
                return low
            if value >= 67:
                return high
            return mid

        parts = [
            band(self.warmth, "Keep tone cool and neutral.", "Keep tone balanced.", "Keep tone warm and supportive."),
            band(self.directness, "Be gentle and indirect.", "Be clear and balanced.", "Be direct and concise."),
            band(self.humor, "Stay serious.", "Use light humor sparingly.", "Use playful humor when appropriate."),
            band(self.formality, "Use casual language.", "Use neutral professional language.", "Use formal language."),
            band(self.verbosity, "Keep answers short.", "Keep answers moderately detailed.", "Provide fuller explanations."),
        ]
        return "Personality tuning: " + " ".join(parts)


class PluginSettings(_SettingsModel):
    """Controller-owned plugin registry configuration (B-034, ADR-0033).

    ``plugin_root`` is optional. ``None`` means no filesystem discovery.
    ``enabled_plugin_ids`` is an explicit enable list; discovery never
    auto-enables. Configuration does not grant process, Git, Tool, or
    Approval authority.
    """

    plugin_root: Path | None = None
    enabled_plugin_ids: tuple[str, ...] = Field(default_factory=tuple)

    @field_validator("enabled_plugin_ids")
    @classmethod
    def _enabled_ids_safe(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        cleaned: list[str] = []
        for item in value:
            if not isinstance(item, str) or not re.fullmatch(
                r"^[a-z][a-z0-9_]{1,31}$", item
            ):
                raise ConfigurationError("plugins.enabled_plugin_ids rejected")
            if item not in cleaned:
                cleaned.append(item)
        return tuple(cleaned)


class AstraSettings(_SettingsModel):
    """Root settings object, composed of one section per concern."""

    reasoning: ReasoningSettings = Field(default_factory=ReasoningSettings)
    memory: MemorySettings = Field(default_factory=MemorySettings)
    knowledge: KnowledgeSettings = Field(default_factory=KnowledgeSettings)
    planning: PlanningSettings = Field(default_factory=PlanningSettings)
    logging: LoggingSettings = Field(default_factory=LoggingSettings)
    security: SecuritySettings = Field(default_factory=SecuritySettings)
    testing: TestingSettings = Field(default_factory=TestingSettings)
    git: GitSettings = Field(default_factory=GitSettings)
    plugins: PluginSettings = Field(default_factory=PluginSettings)
    research: ResearchSettings = Field(default_factory=ResearchSettings)
    personality: PersonalitySettings = Field(default_factory=PersonalitySettings)
    voice: VoiceSettings = Field(default_factory=VoiceSettings)


__all__ = [
    "SUPPORTED_REASONING_BACKENDS",
    "LOG_LEVELS",
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
    "VoiceSettings",
    "SUPPORTED_TTS_ENGINES",
    "SUPPORTED_STT_ENGINES",
    "PersonalitySettings",
    "AstraSettings",
]
