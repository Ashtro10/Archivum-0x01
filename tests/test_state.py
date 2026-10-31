"""Tests for astra.state — Presentation Layer contracts (ADR-0006).

Guards three properties:

1. The contract types are correct and usable.
2. The five capability statuses are present and distinct.
3. The package is a dependency leaf: it imports nothing from the rest of astra.
"""

from __future__ import annotations

import ast
import importlib
import unittest
from pathlib import Path

import astra.state
from astra.state import (
    ApplicationState,
    CapabilityInfo,
    CapabilityStatus,
    GoalSnapshot,
    ModuleStatus,
    StateProvider,
    SystemInfo,
    TestStatus,
)


class CapabilityStatusTest(unittest.TestCase):
    def test_five_statuses_exist(self) -> None:
        """ADR-0006 D2: exactly five statuses, no more, no less."""
        values = {s.value for s in CapabilityStatus}
        self.assertEqual(
            {"planned", "implemented", "configured", "available", "operational"},
            values,
        )

    def test_statuses_are_distinct(self) -> None:
        statuses = list(CapabilityStatus)
        self.assertEqual(len(statuses), len(set(statuses)))

    def test_planned_is_accessible(self) -> None:
        self.assertIs(CapabilityStatus.PLANNED, CapabilityStatus("planned"))

    def test_operational_is_accessible(self) -> None:
        self.assertIs(CapabilityStatus.OPERATIONAL, CapabilityStatus("operational"))


class CapabilityInfoTest(unittest.TestCase):
    def test_minimal_construction(self) -> None:
        info = CapabilityInfo(name="memory_engine", status=CapabilityStatus.OPERATIONAL)
        self.assertEqual("memory_engine", info.name)
        self.assertEqual(CapabilityStatus.OPERATIONAL, info.status)

    def test_empty_name_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            CapabilityInfo(name="", status=CapabilityStatus.PLANNED)

    def test_optional_fields_have_safe_defaults(self) -> None:
        info = CapabilityInfo(name="x", status=CapabilityStatus.PLANNED)
        self.assertEqual("", info.label)
        self.assertEqual("", info.notes)
        self.assertEqual({}, info.metadata)

    def test_is_frozen(self) -> None:
        info = CapabilityInfo(name="x", status=CapabilityStatus.PLANNED)
        with self.assertRaises(Exception):
            info.name = "y"  # type: ignore[misc]


class ModuleStatusTest(unittest.TestCase):
    def test_construction_with_capabilities(self) -> None:
        cap = CapabilityInfo(name="store", status=CapabilityStatus.OPERATIONAL)
        mod = ModuleStatus(
            module="astra.memory",
            status=CapabilityStatus.OPERATIONAL,
            capabilities=(cap,),
        )
        self.assertEqual("astra.memory", mod.module)
        self.assertEqual(1, len(mod.capabilities))

    def test_empty_capabilities_by_default(self) -> None:
        mod = ModuleStatus(module="astra.planning", status=CapabilityStatus.PLANNED)
        self.assertEqual((), mod.capabilities)


class SystemInfoTest(unittest.TestCase):
    def test_all_fields_default_to_empty(self) -> None:
        info = SystemInfo()
        self.assertEqual("", info.cpu)
        self.assertEqual("", info.gpu)
        self.assertEqual({}, info.extras)

    def test_is_frozen(self) -> None:
        info = SystemInfo()
        with self.assertRaises(Exception):
            info.cpu = "something"  # type: ignore[misc]


class TestStatusTest(unittest.TestCase):
    def test_all_passing_when_no_failures(self) -> None:
        ts = TestStatus(total=102, passed=102)
        self.assertTrue(ts.all_passing)

    def test_not_all_passing_when_failed(self) -> None:
        ts = TestStatus(total=102, passed=101, failed=1)
        self.assertFalse(ts.all_passing)

    def test_not_all_passing_when_total_zero(self) -> None:
        self.assertFalse(TestStatus().all_passing)

    def test_not_all_passing_when_errors(self) -> None:
        ts = TestStatus(total=5, passed=4, errors=1)
        self.assertFalse(ts.all_passing)


class ApplicationStateTest(unittest.TestCase):
    def test_constructs_with_defaults(self) -> None:
        state = ApplicationState()
        self.assertEqual(6, state.schema_version)
        self.assertEqual((), state.modules)
        self.assertEqual(CapabilityStatus.PLANNED, state.active_model_status)

    def test_constructs_with_modules(self) -> None:
        mod = ModuleStatus(module="astra.memory", status=CapabilityStatus.OPERATIONAL)
        state = ApplicationState(modules=(mod,), stage="B-003")
        self.assertEqual("B-003", state.stage)
        self.assertEqual(1, len(state.modules))

    def test_is_frozen(self) -> None:
        state = ApplicationState()
        with self.assertRaises(Exception):
            state.stage = "mutated"  # type: ignore[misc]

    def test_goals_default_empty(self) -> None:
        state = ApplicationState()
        self.assertEqual((), state.goals)

    def test_goal_snapshot_is_leaf_dto(self) -> None:
        snap = GoalSnapshot(
            goal_id="g1",
            description="ship goal manager",
            status="active",
            success_criteria=("alphaevidence",),
            task_ids=("T-1",),
            tasks_done=0,
            tasks_total=1,
            blocked=True,
            last_evaluate_met=False,
            last_evaluated_at="2026-08-14T00:00:00.000000Z",
            evidence_hit_count=0,
        )
        self.assertEqual("g1", snap.goal_id)
        self.assertTrue(snap.blocked)
        self.assertIs(False, snap.last_evaluate_met)

    def test_security_snapshot_defaults_deny(self) -> None:
        from astra.state import SecuritySnapshot

        snap = SecuritySnapshot()
        self.assertFalse(snap.allow_outbound_network)
        self.assertTrue(snap.require_human_approval)
        self.assertEqual(0, snap.checks_total)

    def test_tool_snapshot_defaults_zero_executable(self) -> None:
        from astra.state import ToolSnapshot

        snap = ToolSnapshot()
        self.assertEqual(0, snap.executable_tool_count)
        self.assertEqual(0, snap.executed_count)
        self.assertEqual(0, snap.pending_approvals)
        self.assertEqual("planned", snap.registry_status)

    def test_coding_snapshot_defaults_not_applied(self) -> None:
        from astra.state import CodingSnapshot

        snap = CodingSnapshot()
        self.assertIs(False, snap.applied)
        self.assertEqual("none", snap.last_proposal_status)
        self.assertEqual("planned", snap.agent_status)
        self.assertEqual(0, snap.last_change_count)
        self.assertEqual((), snap.last_change_paths)
        self.assertEqual((), snap.last_objections)
        self.assertEqual("", snap.last_preview)
        self.assertEqual(0, snap.last_objection_count)


class StateProviderTest(unittest.TestCase):
    def test_get_state_raises_not_implemented(self) -> None:
        provider = StateProvider()
        with self.assertRaises(NotImplementedError):
            provider.get_state()

    def test_concrete_provider_can_override(self) -> None:
        class _ConcreteProvider(StateProvider):
            def get_state(self) -> ApplicationState:
                return ApplicationState(stage="test")

        state = _ConcreteProvider().get_state()
        self.assertEqual("test", state.stage)


class LeafPackageTest(unittest.TestCase):
    """ADR-0006 D3: astra.state imports nothing from the rest of astra."""

    def _state_sources(self) -> list[Path]:
        package_root = Path(astra.state.__file__).parent
        return sorted(package_root.glob("*.py"))

    def test_package_has_sources(self) -> None:
        self.assertTrue(self._state_sources())

    def test_no_intra_astra_imports(self) -> None:
        """No source file in astra.state may import from another astra module.

        Parsed with ast rather than import inspection so the check is cheap
        and independent of runtime import order.
        """
        forbidden_prefix = "astra."
        allowed = {"astra.state"}

        for source in self._state_sources():
            tree = ast.parse(source.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        if alias.name.startswith(forbidden_prefix):
                            target = alias.name
                            if not any(target == a or target.startswith(a + ".")
                                       for a in allowed):
                                self.fail(
                                    f"{source.name} imports {alias.name!r} "
                                    f"from within astra (forbidden by ADR-0006 D3)"
                                )
                elif isinstance(node, ast.ImportFrom):
                    module = node.module or ""
                    if module.startswith(forbidden_prefix):
                        if not any(module == a or module.startswith(a + ".")
                                   for a in allowed):
                            self.fail(
                                f"{source.name} imports from {module!r} "
                                f"within astra (forbidden by ADR-0006 D3)"
                            )

    def test_package_importable_with_no_other_astra_modules(self) -> None:
        """astra.state can be imported without any other astra submodule."""
        mod = importlib.import_module("astra.state")
        self.assertTrue(hasattr(mod, "ApplicationState"))
        self.assertTrue(hasattr(mod, "CapabilityStatus"))
        self.assertTrue(hasattr(mod, "StateProvider"))
        self.assertTrue(hasattr(mod, "ToolSnapshot"))
        self.assertTrue(hasattr(mod, "CodingSnapshot"))


if __name__ == "__main__":
    unittest.main()
