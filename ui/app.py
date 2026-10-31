"""Primus browser UI entry."""

from __future__ import annotations

import sys

from astra.presentation.conversation import ConversationPort, UnavailableConversation
from astra.state import StateProvider

_WINDOW_OPEN = False


class PrimusApp:
    """Conversation-first browser shell. JARVIS background + chat overlay."""

    def __init__(
        self,
        provider: StateProvider,
        theme: object,
        *,
        demo_mode: bool = False,
        conversation: ConversationPort | None = None,
        controller: object | None = None,
        reduced_motion: bool | None = None,
    ) -> None:
        del theme  # web UI uses its own stylesheet
        self._provider = provider
        self._demo_mode = demo_mode
        self._talk: ConversationPort = conversation or UnavailableConversation()
        self._controller = controller
        self._reduced_motion = reduced_motion

    def cleanup(self) -> None:
        return

    def run(self, *, smoke: bool = False) -> None:
        from ui.web_server import run_browser_ui

        run_browser_ui(
            provider=self._provider,
            conversation=self._talk,
            controller=getattr(self, "_controller", None),
            smoke=smoke,
            reduced_motion=self._reduced_motion,
        )


AstraApp = PrimusApp


def main(
    argv: list[str] | None = None,
    *,
    provider: StateProvider | None = None,
    conversation: ConversationPort | None = None,
    controller: object | None = None,
) -> None:
    """Entry point. Called from ``astra.boot.launch``."""
    global _WINDOW_OPEN
    if _WINDOW_OPEN:
        return
    _WINDOW_OPEN = True
    if argv is None:
        argv = sys.argv[1:]
    demo_mode = "--demo" in argv
    smoke = "--smoke" in argv
    if provider is None:
        if demo_mode:
            from ui.demo.provider import DemoStateProvider

            provider = DemoStateProvider()
        else:
            from astra.presentation.provider import AstraStateProvider

            provider = AstraStateProvider()
    try:
        PrimusApp(
            provider,
            theme=None,
            demo_mode=demo_mode,
            conversation=conversation,
            controller=controller,
        ).run(smoke=smoke)
    finally:
        _WINDOW_OPEN = False


__all__ = ["AstraApp", "PrimusApp", "main"]
