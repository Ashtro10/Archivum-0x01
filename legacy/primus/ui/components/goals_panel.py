"""GoalsPanel — render-only goal snapshots (ADR-0013 D9).

Reads ``ApplicationState.goals`` only. No buttons. Does not send commands.
Theme stays in ``ui.themes``.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, GoalSnapshot
from ui.themes.base import Theme


def _status_counts(goals: tuple[GoalSnapshot, ...]) -> dict[str, int]:
    counts = {"proposed": 0, "active": 0, "achieved": 0, "abandoned": 0}
    for g in goals:
        key = (g.status or "").strip().lower()
        if key in counts:
            counts[key] += 1
    return counts


def _met_label(met: bool | None) -> str:
    if met is None:
        return "unevaluated"
    return "True" if met else "False"


def render_goals_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    goals = state.goals
    counts = _status_counts(goals)
    width = theme.panel_width_normal

    with dpg.child_window(width=width, height=height, border=True, tag="_goals_panel"):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  GOALS", color=list(theme.text_accent))
            dpg.add_spacer(width=12)
            dpg.add_text(
                f"total {len(goals)}  |  proposed {counts['proposed']}  "
                f"active {counts['active']}  achieved {counts['achieved']}  "
                f"abandoned {counts['abandoned']}",
                color=list(theme.text_secondary),
            )
        dpg.add_separator()
        dpg.add_spacer(height=4)

        if not goals:
            dpg.add_text("  no goals recorded", color=list(theme.text_dim))
            dpg.add_text(
                "  live list requires a started controller",
                color=list(theme.text_dim),
            )
            return

        for g in goals:
            _render_goal(g, theme)
            dpg.add_spacer(height=4)


def _render_goal(g: GoalSnapshot, theme: Theme) -> None:
    status = (g.status or "unknown").upper()
    with dpg.group(horizontal=True):
        dpg.add_text(f"  {g.goal_id}", color=list(theme.text_primary))
        dpg.add_spacer(width=8)
        dpg.add_text(f"[{status}]", color=list(theme.text_accent))
        if g.blocked:
            dpg.add_spacer(width=8)
            dpg.add_text("[BLOCKED]", color=list(theme.color_configured))
    dpg.add_text(f"  {g.description}", color=list(theme.text_secondary), wrap=700)

    criteria = g.success_criteria or ()
    if criteria:
        dpg.add_text("  success criteria:", color=list(theme.text_dim))
        for c in criteria:
            dpg.add_text(f"    - {c}", color=list(theme.text_dim), wrap=700)
    else:
        dpg.add_text("  success criteria: (none)", color=list(theme.text_dim))

    task_ids = g.task_ids or ()
    linked = ", ".join(task_ids) if task_ids else "(none)"
    dpg.add_text(f"  linked task IDs: {linked}", color=list(theme.text_dim), wrap=700)
    dpg.add_text(
        f"  task progress: {g.tasks_done}/{g.tasks_total} linked tasks DONE",
        color=list(theme.text_dim),
    )
    dpg.add_text(
        f"  latest evaluation: {_met_label(g.last_evaluate_met)}",
        color=list(theme.text_dim),
    )
    ts = g.last_evaluated_at or "(none)"
    dpg.add_text(f"  evaluation timestamp: {ts}", color=list(theme.text_dim))
    dpg.add_text(
        f"  evidence-hit count: {g.evidence_hit_count}",
        color=list(theme.text_dim),
    )
    dpg.add_text(
        f"  derived blocked: {'yes' if g.blocked else 'no'}",
        color=list(theme.text_dim),
    )
