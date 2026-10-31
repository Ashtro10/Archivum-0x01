"""Structured conversation context. Not a second memory store. Not chain-of-thought."""

from __future__ import annotations

from dataclasses import dataclass, field


DEFAULT_PERSONALITY = (
    "You are Primus, a local-first personal AI for one user. "
    "Be calm, precise, and honest. The language model is replaceable; you are Primus. "
    "Use provided memory, goals, knowledge, research evidence, and file contents. "
    "Do not invent personal facts, URLs, or citations. "
    "Do not claim you searched the web unless external research evidence is present. "
    "Ask at most one useful clarification when a plan lacks skill level or time. "
    "Do not ask pointless questions when the request is already clear."
)


@dataclass(frozen=True, slots=True)
class IntentDecision:
    """Non-exclusive subsystem selection. ``primary`` stays backward-compatible."""

    primary: str
    memory: bool = False
    memory_update: bool = False
    research: bool = False
    knowledge: bool = False
    planning: bool = False
    continue_plan: bool = False
    goals: bool = False
    record_goal: bool = False
    tools: bool = False
    clarify: bool = False


@dataclass(frozen=True, slots=True)
class UserContext:
    """Stable user facts assembled from Memory and Goals. Not guessed."""

    name: str = ""
    preferred_name: str = ""
    creator: str = ""
    preferences: tuple[str, ...] = ()
    projects: tuple[str, ...] = ()
    goals: tuple[str, ...] = ()
    relevant_memories: tuple[str, ...] = ()

    def is_empty(self) -> bool:
        return not (
            self.name
            or self.preferred_name
            or self.creator
            or self.preferences
            or self.projects
            or self.goals
            or self.relevant_memories
        )


@dataclass(frozen=True, slots=True)
class OrchestrationTrace:
    """Safe execution facts. Not private reasoning."""

    intent: str = "conversation"
    intents: tuple[str, ...] = ()
    memory_retrieved: int = 0
    memory_candidates: int = 0
    memory_selected: int = 0
    memory_ids: tuple[str, ...] = ()
    memory_categories: tuple[str, ...] = ()
    knowledge_retrieved: int = 0
    research_used: bool = False
    sources_count: int = 0
    planning_used: bool = False
    goals_used: bool = False
    tools_used: tuple[str, ...] = ()
    llm_called: bool = False
    memory_written: bool = False
    goal_changed: bool = False
    personality_applied: bool = False

    def render(self) -> str:
        flags = ", ".join(self.intents) or self.intent
        tools = ", ".join(self.tools_used) or "none"
        ids = ", ".join(self.memory_ids) or "none"
        kinds = ", ".join(self.memory_categories) or "none"
        return (
            f"Intent: {flags}\n"
            f"Context: memories={self.memory_retrieved} knowledge={self.knowledge_retrieved}\n"
            f"Memory: candidates={self.memory_candidates} selected={self.memory_selected}\n"
            f"Memory ids: {ids}\n"
            f"Memory kinds: {kinds}\n"
            f"Research: used={self.research_used} sources={self.sources_count}\n"
            f"Planning: {self.planning_used}\n"
            f"Goals: {self.goals_used} changed={self.goal_changed}\n"
            f"Tools: {tools}\n"
            f"LLM: called={self.llm_called}\n"
            f"Persistence: durable_memory={self.memory_written}"
        )


@dataclass
class PersonalityProfile:
    """Primus identity outside the model. Configurable. Persistent."""

    name: str = "Primus"
    voice: str = DEFAULT_PERSONALITY
    principles: tuple[str, ...] = field(default_factory=tuple)

    def system_text(self) -> str:
        parts = [self.voice.strip() or DEFAULT_PERSONALITY]
        if self.principles:
            parts.append("Principles: " + "; ".join(self.principles))
        return "\n".join(parts)


def format_user_context(ctx: UserContext) -> str:
    """Render identity for the system message. Empty fields are omitted."""
    lines: list[str] = []
    if ctx.name:
        lines.append(f"Name: {ctx.name}")
    if ctx.preferences:
        lines.append("Preferences:")
        lines.extend(f"- {item}" for item in ctx.preferences[:6])
    if ctx.projects:
        lines.append("Projects:")
        lines.extend(f"- {item}" for item in ctx.projects[:4])
    if ctx.goals:
        lines.append("Active goals:")
        lines.extend(f"- {item}" for item in ctx.goals[:4])
    if ctx.relevant_memories:
        lines.append("Relevant memory:")
        lines.extend(f"- {item}" for item in ctx.relevant_memories[:6])
    if not lines:
        return ""
    return "User identity (do not invent missing facts):\n" + "\n".join(lines)


__all__ = [
    "DEFAULT_PERSONALITY",
    "IntentDecision",
    "OrchestrationTrace",
    "PersonalityProfile",
    "UserContext",
    "format_user_context",
]
