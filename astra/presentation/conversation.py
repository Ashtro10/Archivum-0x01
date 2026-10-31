"""Conversation port observed by the UI.

Protocol only. Implementations are constructed outside ``ui/`` so the
dashboard never imports ``astra.core``. Not an approval or deploy path.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal, Protocol

_MODEL_UNAVAILABLE = (
    "I couldn't reach my language model. Open System to check the model path "
    "and llama.cpp runtime."
)
_EMPTY = "Say something and I will answer."
PromptFormat = Literal["chatml", "llama3"]
_SPECIAL_TOKEN = re.compile(r"<\s*\|[^|>]*\|\s*>", re.IGNORECASE)
_THINK_TAG = r"(?:think(?:ing)?|redacted_thinking)"
_THINK_BLOCK = re.compile(
    rf"<\s*{_THINK_TAG}\s*>[\s\S]*?<\s*/\s*{_THINK_TAG}\s*>",
    re.IGNORECASE,
)
_UNCLOSED_THINK = re.compile(
    rf"<\s*{_THINK_TAG}\s*>[\s\S]*$",
    re.IGNORECASE,
)
_LEADING_THINK = re.compile(
    rf"^[\s\S]*?<\s*/\s*{_THINK_TAG}\s*>",
    re.IGNORECASE,
)


def _model_error(llm: object | None, detail: str = "") -> ConversationReply:
    reason = ""
    probe = getattr(llm, "unavailable_reason", None)
    if callable(probe):
        try:
            reason = str(probe() or "").strip()
        except Exception:
            reason = ""
    extra = (reason or detail or "").strip()
    text = _MODEL_UNAVAILABLE
    if extra:
        text = f"{_MODEL_UNAVAILABLE}\n{extra}"
    return ConversationReply(text=text, status="error")


@dataclass(frozen=True, slots=True)
class ConversationReply:
    """One user-facing reply. Not chain-of-thought. Not a TestReport."""

    text: str
    status: str = "success"
    activity: str = ""
    trace: str = ""


class ConversationPort(Protocol):
    """Ask Primus a question. Implementations must not invent model output."""

    def ask(self, text: str) -> ConversationReply:
        """Return a real model result or an honest unavailable reply."""


class UnavailableConversation:
    """Honest fallback when no controller is wired (demo / degraded)."""

    def ask(self, text: str) -> ConversationReply:
        del text
        from astra.product import PRODUCT_NAME

        return ConversationReply(
            text=(
                f"{PRODUCT_NAME} is in a read-only session. "
                "I can listen, but I cannot reach the language model from here."
            ),
            status="warning",
            activity="",
        )


class AssistantConversation:
    """Normal talk path: user text → configured LLMBackend.generate.

    Not the research agent. Not a summarizer unless the user asks to summarize.
    Does not invent replies when the model is missing.
    """

    def __init__(self, llm: object | None) -> None:
        self._llm = llm
        self._turns: list[tuple[str, str]] = []

    def ask(self, text: str) -> ConversationReply:
        message = str(text or "").strip()
        if not message:
            return ConversationReply(text=_EMPTY, status="warning")
        llm = self._llm
        available = getattr(llm, "is_available", None)
        generate = getattr(llm, "generate", None)
        if llm is None or not callable(available) or not callable(generate):
            return _model_error(llm)
        try:
            if not available():
                return _model_error(llm)
        except Exception as exc:
            return _model_error(llm, str(exc))

        from astra.product import PRODUCT_NAME
        from astra.reasoning.backends import GenerationOptions

        model_name = str(getattr(llm, "model_name", "") or "")
        prompt_format = detect_prompt_format(model_name)
        prompt = _chat_prompt(
            PRODUCT_NAME,
            sanitize_chat_turns(self._turns),
            message,
            prompt_format=prompt_format,
            model_name=model_name,
            fast_chat=True,
        )
        try:
            raw = generate(
                prompt,
                generation_options_for_format(prompt_format, model_name=model_name),
            )
        except Exception as exc:
            return _model_error(llm, str(exc))
        reply = _collapse_repeats(_sanitize_model_output(str(raw or "").strip()))
        if not reply:
            return _model_error(llm, "The model returned an empty reply.")
        self._turns.append((message, reply))
        if len(self._turns) > 8:
            self._turns = self._turns[-8:]
        return ConversationReply(text=reply, status="success")


def detect_prompt_format(model_name: str) -> PromptFormat:
    """Pick chat template from model filename / label."""
    label = str(model_name or "").strip().lower()
    if any(token in label for token in ("llama", "meta-llama", "llama3", "dolphin")):
        return "llama3"
    return "chatml"


def is_qwen3_model(model_name: str) -> bool:
    label = str(model_name or "").strip().lower()
    return "qwen3" in label or "qwen-3" in label


def strip_thinking(text: str) -> str:
    """Drop Qwen3 thinking blocks. Return user-visible reply only."""
    cleaned = str(text or "")
    if not cleaned.strip():
        return ""
    while True:
        next_text = _THINK_BLOCK.sub("", cleaned)
        if next_text == cleaned:
            break
        cleaned = next_text
    cleaned = _UNCLOSED_THINK.sub("", cleaned.strip())
    cleaned = _LEADING_THINK.sub("", cleaned.strip())
    return cleaned.strip()


def is_internal_trace(text: str) -> bool:
    """True for agent-loop dumps that must not become chat history."""
    cleaned = str(text or "").strip()
    if not cleaned:
        return False
    head = cleaned[:160].lower()
    if head.startswith("objective:"):
        return True
    lower = cleaned.lower()
    if "actions:" in head and "succeeded:" in lower:
        return True
    if cleaned.startswith("Approval required."):
        return True
    return False


def sanitize_chat_turns(
    turns: list[tuple[str, str]], *, limit: int = 8
) -> list[tuple[str, str]]:
    """Keep only user-visible turns safe to embed in the next prompt."""
    cleaned: list[tuple[str, str]] = []
    for user, assistant in turns:
        user_text = str(user or "").strip()
        if not user_text:
            continue
        reply = _sanitize_model_output(str(assistant or "").strip())
        if not reply or is_internal_trace(reply):
            continue
        cleaned.append((user_text, reply))
    return cleaned[-limit:]


def extract_thinking(text: str) -> str:
    """Return model reasoning inside think tags. Empty when none."""
    cleaned = str(text or "")
    if not cleaned.strip():
        return ""
    inner_pattern = re.compile(
        rf"<\s*{_THINK_TAG}\s*>([\s\S]*?)<\s*/\s*{_THINK_TAG}\s*>",
        re.IGNORECASE,
    )
    parts: list[str] = []
    for match in inner_pattern.finditer(cleaned):
        piece = str(match.group(1) or "").strip()
        if piece:
            parts.append(piece)
    open_pattern = re.compile(
        rf"<\s*{_THINK_TAG}\s*>([\s\S]*)$",
        re.IGNORECASE,
    )
    open_match = open_pattern.search(cleaned)
    if open_match is not None:
        tail = str(open_match.group(1) or "").strip()
        if tail and (not parts or tail != parts[-1]):
            parts.append(tail)
    return "\n".join(parts).strip()


def thinking_delta(previous: str, raw: str) -> str:
    """Return newly extracted thinking suffix from accumulated raw output."""
    current = extract_thinking(str(raw or ""))
    prev = str(previous or "")
    if current.startswith(prev):
        return current[len(prev) :]
    return current if not prev else current


def visible_reply_delta(previous_visible: str, raw: str) -> str:
    """Return newly visible suffix after stripping thinking from accumulated raw."""
    visible = strip_thinking(str(raw or ""))
    visible = _SPECIAL_TOKEN.sub("", visible).strip()
    prev = str(previous_visible or "")
    if visible.startswith(prev):
        return visible[len(prev) :]
    return visible if not prev else visible


def generation_options_for_format(
    prompt_format: PromptFormat,
    *,
    model_name: str = "",
    allow_thinking: bool = False,
):
    from astra.reasoning.backends import GenerationOptions

    if prompt_format == "llama3":
        return GenerationOptions(
            temperature=0.7,
            max_tokens=768,
            stop=("<|eot_id|>", "<|start_header_id|>"),
            repeat_penalty=1.15,
        )
    if is_qwen3_model(model_name):
        max_tokens = 2048 if allow_thinking else 1024
    else:
        max_tokens = 768
    stops = ["<|im_end|>", "<|im_start|>"]
    return GenerationOptions(
        temperature=0.7,
        max_tokens=max_tokens,
        stop=tuple(stops),
        repeat_penalty=1.15,
    )


def _system_prompt(name: str, system_extra: str = "", *, fast_chat: bool = False, allow_thinking: bool = False) -> str:
    system = (
        f"You are {name}, a local personal AI assistant. "
        "Answer the user directly, once. Do not repeat yourself. "
        "Use plain text only; do not use markdown, asterisks, or bullet formatting. "
        "Do not mention summarization unless asked. "
        "Never claim you searched the web or invent URLs unless "
        "external research evidence is provided. "
        "When external research evidence is present in the context, "
        "answer from that evidence — including fictional characters "
        "from games, books, or films. "
        "If current external facts are needed and no research evidence "
        "is present, ask whether to look that up. "
        "Refuse requests for illegal acts, violence, or harm."
    )
    if fast_chat and not allow_thinking:
        system += (
            " Reply with the final answer only. "
            "Never output hidden reasoning or analysis tags."
        )
    extra = str(system_extra or "").strip()
    if extra:
        system = system + "\n\n" + extra
    return system


def _chat_prompt(
    name: str,
    turns: list[tuple[str, str]],
    message: str,
    *,
    system_extra: str = "",
    prompt_format: PromptFormat = "chatml",
    model_name: str = "",
    fast_chat: bool = False,
    allow_thinking: bool = False,
) -> str:
    """Build an instruct prompt for Qwen ChatML or Llama 3."""
    user_message = str(message or "").strip()
    if (
        not allow_thinking
        and is_qwen3_model(model_name)
        and "/no_think" not in user_message.lower()
    ):
        user_message = f"{user_message} /no_think"
    system = _system_prompt(name, system_extra, fast_chat=fast_chat, allow_thinking=allow_thinking)
    history = sanitize_chat_turns(turns)
    if prompt_format == "llama3":
        parts = []
        parts.append(
            f"<|start_header_id|>system<|end_header_id|>\n\n{system}<|eot_id|>"
        )
        for user, assistant in history:
            parts.append(
                f"<|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|>"
            )
            parts.append(
                f"<|start_header_id|>assistant<|end_header_id|>\n\n{assistant}<|eot_id|>"
            )
        parts.append(
            f"<|start_header_id|>user<|end_header_id|>\n\n{user_message}<|eot_id|>"
        )
        parts.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return "".join(parts)

    parts = [f"<|im_start|>system\n{system}<|im_end|>"]
    for user, assistant in history:
        parts.append(f"<|im_start|>user\n{user}<|im_end|>")
        parts.append(f"<|im_start|>assistant\n{assistant}<|im_end|>")
    parts.append(f"<|im_start|>user\n{user_message}<|im_end|>")
    parts.append("<|im_start|>assistant\n")
    return "\n".join(parts)


def _sanitize_model_output(text: str) -> str:
    """Drop thinking blocks and leaked special tokens from model output."""
    if not text:
        return text
    cleaned = strip_thinking(str(text or ""))
    cleaned = _SPECIAL_TOKEN.sub("", cleaned)
    return cleaned.strip()


def _collapse_repeats(text: str) -> str:
    """Drop consecutive duplicate lines/sentences. Does not invent words."""
    if not text:
        return text
    lines: list[str] = []
    for raw_line in text.splitlines():
        collapsed = _collapse_sentence_run(raw_line.strip())
        if collapsed and lines and collapsed == lines[-1]:
            continue
        if collapsed:
            lines.append(collapsed)
    return "\n".join(lines).strip()


def _collapse_sentence_run(line: str) -> str:
    if not line:
        return ""
    kept: list[str] = []
    for sentence in _split_sentences(line):
        if kept and sentence == kept[-1]:
            continue
        kept.append(sentence)
    return " ".join(kept)


def _split_sentences(line: str) -> list[str]:
    parts: list[str] = []
    buf: list[str] = []
    for char in line:
        buf.append(char)
        if char in ".!?" and buf:
            piece = "".join(buf).strip()
            if piece:
                parts.append(piece)
            buf = []
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts or [line]


__all__ = [
    "AssistantConversation",
    "ConversationPort",
    "ConversationReply",
    "UnavailableConversation",
    "detect_prompt_format",
    "extract_thinking",
    "generation_options_for_format",
    "is_internal_trace",
    "is_qwen3_model",
    "sanitize_chat_turns",
    "strip_thinking",
    "thinking_delta",
    "visible_reply_delta",
    "_chat_prompt",
    "_sanitize_model_output",
]
