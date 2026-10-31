"""Local conversation transcript. Status never becomes a message."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class ConversationMessage:
    speaker: str
    text: str


@dataclass
class ConversationStore:
    """Stable message list. Refresh/status must not append here."""

    messages: list[ConversationMessage] = field(default_factory=list)

    def add(self, speaker: str, text: str) -> ConversationMessage:
        message = ConversationMessage(speaker=speaker, text=text)
        self.messages.append(message)
        return message

    def greet(self, text: str) -> None:
        if self.messages:
            return
        self.add("primus", text)


__all__ = ["ConversationMessage", "ConversationStore"]
