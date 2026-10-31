"""Tests for astra.presentation — StateProvider and project progress.

Does not test UI rendering (no Dear PyGui imported).
Tests the state-assembly logic and milestone detection.
"""

from __future__ import annotations

import unittest

from astra.state import ApplicationState, CapabilityStatus
from astra.presentation.progress import MILESTONES, Milestone
from astra.presentation.provider import AstraStateProvider


class MilestoneTest(unittest.TestCase):
    def test_milestones_have_unique_ids(self) -> None:
        ids = [m.id for m in MILESTONES]
        self.assertEqual(len(ids), len(set(ids)))

    def test_milestones_have_unique_stage_numbers(self) -> None:
        stages = [m.stage for m in MILESTONES]
        self.assertEqual(len(stages), len(set(stages)))

    def test_milestones_are_ordered_by_stage(self) -> None:
        stages = [m.stage for m in MILESTONES]
        self.assertEqual(stages, sorted(stages))

    def test_complete_is_callable_and_safe(self) -> None:
        for ms in MILESTONES:
            result = ms.complete()
            self.assertIsInstance(result, bool)

    def test_for_ui_skips_live_interpreter_spawn(self) -> None:
        spawn = next(m for m in MILESTONES if m.id == "B-024")
        suite = next(m for m in MILESTONES if m.id == "B-030")
        self.assertTrue(spawn.for_ui())
        self.assertTrue(suite.for_ui())

    def test_frozen_complete_skips_live_interpreter_spawn(self) -> None:
        import sys
        from unittest.mock import patch

        with patch.object(sys, "frozen", True, create=True):
            for ms in MILESTONES:
                self.assertIsInstance(ms.complete(), bool)
            spawn = next(m for m in MILESTONES if m.id == "B-024")
            suite = next(m for m in MILESTONES if m.id == "B-030")
            self.assertTrue(spawn.complete())
            self.assertTrue(suite.complete())

    def test_all_complete_fields_are_callable(self) -> None:
        for ms in MILESTONES:
            self.assertTrue(callable(ms.is_complete))

    def test_b001_is_complete(self) -> None:
        """Memory Engine is done — sqlite_store module exists."""
        b001 = next(m for m in MILESTONES if m.id == "B-001")
        self.assertTrue(b001.complete())

    def test_b002_is_complete(self) -> None:
        """Reasoning runtime is done — llama_cpp_backend module exists."""
        b002 = next(m for m in MILESTONES if m.id == "B-002")
        self.assertTrue(b002.complete())

    def test_b003_is_complete(self) -> None:
        """FAISS adapter (B-003) is implemented: astra.memory.faiss_index exists."""
        b003 = next(m for m in MILESTONES if m.id == "B-003")
        self.assertTrue(b003.complete())

    def test_b013_is_complete(self) -> None:
        """MemoryIndexer is importable; check is not a hard-coded True."""
        b013 = next(m for m in MILESTONES if m.id == "B-013")
        self.assertTrue(b013.complete())
        self.assertIsNot(b013.is_complete, True)

    def test_b014_is_complete(self) -> None:
        """SQLiteGoalManager is a GoalManager; check is not a hard-coded True."""
        b014 = next(m for m in MILESTONES if m.id == "B-014")
        self.assertTrue(b014.complete())
        self.assertIsNot(b014.is_complete, True)
        self.assertIsNot(b014.is_complete, False)

    def test_b015_is_complete(self) -> None:
        """PolicySecurityAgent deny-by-default; check is not a hard-coded True."""
        b015 = next(m for m in MILESTONES if m.id == "B-015")
        self.assertTrue(b015.complete())
        self.assertIsNot(b015.is_complete, True)

    def test_b016_is_complete(self) -> None:
        """InMemoryToolRegistry + gated deny; check is not a hard-coded True."""
        b016 = next(m for m in MILESTONES if m.id == "B-016")
        self.assertTrue(b016.complete())
        self.assertIsNot(b016.is_complete, True)

    def test_b017_is_complete(self) -> None:
        """read_text gated and allowlisted; check is not a hard-coded True."""
        b017 = next(m for m in MILESTONES if m.id == "B-017")
        self.assertTrue(b017.complete())
        self.assertIsNot(b017.is_complete, True)

    def test_b018_is_complete(self) -> None:
        """read_project gated and allowlisted; check is not a hard-coded True."""
        b018 = next(m for m in MILESTONES if m.id == "B-018")
        self.assertTrue(b018.complete())
        self.assertIsNot(b018.is_complete, True)

    def test_b019_is_complete(self) -> None:
        """LocalCodingAgent proposes via ToolInvoker; check is not a hard-coded True."""
        b019 = next(m for m in MILESTONES if m.id == "B-019")
        self.assertTrue(b019.complete())
        self.assertIsNot(b019.is_complete, True)

    def test_b020_is_complete(self) -> None:
        """Last proposal retained and reviewed once; check is not a hard-coded True."""
        b020 = next(m for m in MILESTONES if m.id == "B-020")
        self.assertTrue(b020.complete())
        self.assertIsNot(b020.is_complete, True)


class AstraStateProviderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider = AstraStateProvider()

    def test_get_state_returns_application_state(self) -> None:
        state = self.provider.get_state()
        self.assertIsInstance(state, ApplicationState)

    def test_state_never_raises(self) -> None:
        """get_state must not propagate exceptions."""
        state = self.provider.get_state()
        self.assertIsNotNone(state)

    def test_schema_version_is_positive(self) -> None:
        state = self.provider.get_state()
        self.assertGreater(state.schema_version, 0)

    def test_timestamp_is_present(self) -> None:
        state = self.provider.get_state()
        self.assertTrue(state.timestamp_iso)
        self.assertIn("T", state.timestamp_iso)  # ISO-8601

    def test_completed_stages_are_strings(self) -> None:
        state = self.provider.get_state()
        for s in state.completed_stages:
            self.assertIsInstance(s, str)

    def test_b001_b002_are_completed(self) -> None:
        state = self.provider.get_state()
        self.assertIn("B-001", state.completed_stages)
        self.assertIn("B-002", state.completed_stages)

    def test_b003_b004_b006_in_completed(self) -> None:
        """B-003, B-004, B-006 are implemented and must appear in completed_stages."""
        state = self.provider.get_state()
        self.assertIn("B-003", state.completed_stages)
        self.assertIn("B-004", state.completed_stages)
        self.assertIn("B-006", state.completed_stages)

    def test_b013_in_completed(self) -> None:
        state = self.provider.get_state()
        self.assertIn("B-013", state.completed_stages)

    def test_b014_in_completed(self) -> None:
        state = self.provider.get_state()
        self.assertIn("B-014", state.completed_stages)
        self.assertEqual((), state.goals)

    def test_b015_in_completed(self) -> None:
        state = self.provider.get_state()
        self.assertIn("B-015", state.completed_stages)
        self.assertEqual(0, state.security.checks_total)

    def test_b016_in_completed(self) -> None:
        """Tool gate exists. Probe has no live allowlist so executable count is 0."""
        b016 = next(m for m in MILESTONES if m.id == "B-016")
        self.assertTrue(b016.complete())
        self.assertIsNot(b016.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-016", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        self.assertEqual(0, state.tools.executable_tool_count)
        self.assertEqual(0, state.tools.executed_count)

    def test_b017_in_completed(self) -> None:
        """read_text exists; probe does not fake a live execute count."""
        b017 = next(m for m in MILESTONES if m.id == "B-017")
        self.assertTrue(b017.complete())
        self.assertIsNot(b017.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-017", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        self.assertEqual(0, state.tools.executed_count)
        tools = next((m for m in state.modules if m.module == "astra.tools"), None)
        self.assertIsNotNone(tools)
        assert tools is not None
        exec_cap = next(c for c in tools.capabilities if c.name == "executable_tools")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, exec_cap.status)
        self.assertIn("read_text", exec_cap.notes)

    def test_b018_in_completed(self) -> None:
        """read_project exists; probe does not fake a live execute count."""
        b018 = next(m for m in MILESTONES if m.id == "B-018")
        self.assertTrue(b018.complete())
        self.assertIsNot(b018.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-018", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        self.assertEqual(0, state.tools.executed_count)
        tools = next((m for m in state.modules if m.module == "astra.tools"), None)
        self.assertIsNotNone(tools)
        assert tools is not None
        exec_cap = next(c for c in tools.capabilities if c.name == "executable_tools")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, exec_cap.status)
        self.assertIn("read_project", exec_cap.notes)

    def test_b019_in_completed(self) -> None:
        """Coding Agent exists; probe does not fake a live propose or applied=True."""
        b019 = next(m for m in MILESTONES if m.id == "B-019")
        self.assertTrue(b019.complete())
        self.assertIsNot(b019.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-019", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        self.assertIs(False, state.coding.applied)
        self.assertNotEqual("applied", state.coding.last_proposal_status)
        coding = next((m for m in state.modules if m.module == "astra.coding"), None)
        self.assertIsNotNone(coding)
        assert coding is not None
        self.assertEqual(CapabilityStatus.IMPLEMENTED, coding.status)
        apply_cap = next(c for c in coding.capabilities if c.name == "proposal_apply")
        self.assertEqual(CapabilityStatus.PLANNED, apply_cap.status)

    def test_b020_in_completed(self) -> None:
        """Proposal inspectability; probe does not fake live paths or applied=True."""
        b020 = next(m for m in MILESTONES if m.id == "B-020")
        self.assertTrue(b020.complete())
        self.assertIsNot(b020.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-020", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        self.assertIs(False, state.coding.applied)
        self.assertEqual((), state.coding.last_change_paths)
        self.assertEqual("", state.coding.last_preview)
        self.assertNotEqual("applied", state.coding.last_proposal_status)
        coding = next((m for m in state.modules if m.module == "astra.coding"), None)
        self.assertIsNotNone(coding)
        assert coding is not None
        apply_cap = next(c for c in coding.capabilities if c.name == "proposal_apply")
        self.assertEqual(CapabilityStatus.PLANNED, apply_cap.status)

    def test_b021_is_complete(self) -> None:
        """Proposal workspace overlays scratch; check is not a hard-coded True."""
        b021 = next(m for m in MILESTONES if m.id == "B-021")
        self.assertTrue(b021.complete())
        self.assertIsNot(b021.is_complete, True)

    def test_b021_in_completed(self) -> None:
        """Workspace exists; probe does not fake live overlay counts or test runs."""
        b021 = next(m for m in MILESTONES if m.id == "B-021")
        self.assertTrue(b021.complete())
        self.assertIsNot(b021.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-021", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        self.assertEqual(CapabilityStatus.IMPLEMENTED, testing.status)
        workspace_cap = next(c for c in testing.capabilities if c.name == "proposal_workspace")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, workspace_cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, test_prop.status)
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, run_suite.status)

    def test_b022_is_complete(self) -> None:
        """Process policy denies by default; check is not a hard-coded True."""
        b022 = next(m for m in MILESTONES if m.id == "B-022")
        self.assertTrue(b022.complete())
        self.assertIsNot(b022.is_complete, True)

    def test_b022_in_completed(self) -> None:
        """Runner exists; probe does not fake spawn or test runs."""
        b022 = next(m for m in MILESTONES if m.id == "B-022")
        self.assertTrue(b022.complete())
        self.assertIsNot(b022.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-022", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        runner_cap = next(c for c in testing.capabilities if c.name == "process_runner")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, runner_cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, test_prop.status)
        sec = next((m for m in state.modules if m.module == "astra.security"), None)
        self.assertIsNotNone(sec)
        assert sec is not None
        proc = next(c for c in sec.capabilities if c.name == "process_policy")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, proc.status)

    def test_b023_is_complete(self) -> None:
        """Copied-subset workspace copies allowlist; check is not a hard-coded True."""
        b023 = next(m for m in MILESTONES if m.id == "B-023")
        self.assertTrue(b023.complete())
        self.assertIsNot(b023.is_complete, True)

    def test_b023_in_completed(self) -> None:
        """Subset exists; probe does not fake test runs."""
        b023 = next(m for m in MILESTONES if m.id == "B-023")
        self.assertTrue(b023.complete())
        self.assertIsNot(b023.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-023", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        subset_cap = next(
            c for c in testing.capabilities if c.name == "copied_subset_workspace"
        )
        self.assertEqual(CapabilityStatus.IMPLEMENTED, subset_cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, test_prop.status)
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, run_suite.status)

    def test_b024_is_complete(self) -> None:
        """Controlled spawn in copied session; check is not a hard-coded True."""
        b024 = next(m for m in MILESTONES if m.id == "B-024")
        self.assertTrue(b024.complete())
        self.assertIsNot(b024.is_complete, True)

    def test_b024_in_completed(self) -> None:
        """Runner can spawn; probe does not fake test runs."""
        b024 = next(m for m in MILESTONES if m.id == "B-024")
        self.assertTrue(b024.complete())
        self.assertIsNot(b024.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-024", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        runner_cap = next(c for c in testing.capabilities if c.name == "process_runner")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, runner_cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, test_prop.status)
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, run_suite.status)

    def test_b025_is_complete(self) -> None:
        """Proposal testing runs unittest in a copy; check is not a hard-coded True."""
        b025 = next(m for m in MILESTONES if m.id == "B-025")
        self.assertTrue(b025.complete())
        self.assertIsNot(b025.is_complete, True)

    def test_b025_in_completed(self) -> None:
        """test_proposal implemented; schema stays 6."""
        b025 = next(m for m in MILESTONES if m.id == "B-025")
        self.assertTrue(b025.complete())
        self.assertIsNot(b025.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-025", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, test_prop.status)
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, run_suite.status)

    def test_b030_is_complete(self) -> None:
        """run_suite executes unittest in a copy; check is not a hard-coded True."""
        b030 = next(m for m in MILESTONES if m.id == "B-030")
        self.assertTrue(b030.complete())
        self.assertIsNot(b030.is_complete, True)

    def test_b030_in_completed(self) -> None:
        """run_suite implemented; schema stays 6."""
        b030 = next(m for m in MILESTONES if m.id == "B-030")
        self.assertTrue(b030.complete())
        self.assertIsNot(b030.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-030", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        testing = next((m for m in state.modules if m.module == "astra.testing"), None)
        self.assertIsNotNone(testing)
        assert testing is not None
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, run_suite.status)

    def test_b031_is_complete(self) -> None:
        """Change pipeline composes existing stages; check is not a hard-coded True."""
        b031 = next(m for m in MILESTONES if m.id == "B-031")
        self.assertTrue(b031.complete())
        self.assertIsNot(b031.is_complete, True)

    def test_b031_in_completed(self) -> None:
        """process_proposal implemented; schema stays 6."""
        b031 = next(m for m in MILESTONES if m.id == "B-031")
        self.assertTrue(b031.complete())
        self.assertIsNot(b031.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-031", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        deployment = next((m for m in state.modules if m.module == "astra.deployment"), None)
        self.assertIsNotNone(deployment)
        assert deployment is not None
        pipeline = next(c for c in deployment.capabilities if c.name == "process_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, pipeline.status)

    def test_b032_is_complete(self) -> None:
        """Adversarial fail-closed check is not a hard-coded True."""
        b032 = next(m for m in MILESTONES if m.id == "B-032")
        self.assertTrue(b032.complete())
        self.assertIsNot(b032.is_complete, True)

    def test_b032_in_completed(self) -> None:
        """B-032 complete; schema stays 6."""
        b032 = next(m for m in MILESTONES if m.id == "B-032")
        self.assertTrue(b032.complete())
        self.assertIsNot(b032.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-032", state.completed_stages)
        self.assertEqual(6, state.schema_version)

    def test_b033_is_complete(self) -> None:
        """Git history check is not a hard-coded True."""
        b033 = next(m for m in MILESTONES if m.id == "B-033")
        self.assertTrue(b033.complete())
        self.assertIsNot(b033.is_complete, True)

    def test_b033_in_completed(self) -> None:
        """B-033 complete; schema stays 6; git is not a Tool."""
        b033 = next(m for m in MILESTONES if m.id == "B-033")
        self.assertTrue(b033.complete())
        self.assertIsNot(b033.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-033", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        deployment = next((m for m in state.modules if m.module == "astra.deployment"), None)
        self.assertIsNotNone(deployment)
        assert deployment is not None
        git_cap = next(c for c in deployment.capabilities if c.name == "git_history")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, git_cap.status)
        self.assertIn("not authorization", git_cap.notes)
        self.assertIn("does not run git", git_cap.notes)

    def test_b034_is_complete(self) -> None:
        """Plugin registry check is not a hard-coded True."""
        b034 = next(m for m in MILESTONES if m.id == "B-034")
        self.assertTrue(b034.complete())
        self.assertIsNot(b034.is_complete, True)

    def test_b034_in_completed(self) -> None:
        """B-034 complete; schema stays 6; plugins are not a Tool."""
        b034 = next(m for m in MILESTONES if m.id == "B-034")
        self.assertTrue(b034.complete())
        self.assertIsNot(b034.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-034", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        plugins = next((m for m in state.modules if m.module == "astra.plugins"), None)
        self.assertIsNotNone(plugins)
        assert plugins is not None
        cap = next(c for c in plugins.capabilities if c.name == "plugin_registry")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, cap.status)
        self.assertIn("not authority", cap.notes)

    def test_b035_is_complete(self) -> None:
        """Hardening check is not a hard-coded True."""
        b035 = next(m for m in MILESTONES if m.id == "B-035")
        self.assertTrue(b035.complete())
        self.assertIsNot(b035.is_complete, True)

    def test_b035_in_completed(self) -> None:
        """B-035 complete; schema stays 6; allowlist unchanged."""
        b035 = next(m for m in MILESTONES if m.id == "B-035")
        self.assertTrue(b035.complete())
        self.assertIsNot(b035.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-035", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b036_is_complete(self) -> None:
        """Integration check is not a hard-coded True."""
        b036 = next(m for m in MILESTONES if m.id == "B-036")
        self.assertTrue(b036.complete())
        self.assertIsNot(b036.is_complete, True)

    def test_b036_in_completed(self) -> None:
        """B-036 complete; schema stays 6; allowlist unchanged."""
        b036 = next(m for m in MILESTONES if m.id == "B-036")
        self.assertTrue(b036.complete())
        self.assertIsNot(b036.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-036", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b037_is_complete(self) -> None:
        """Architecture-consistency check is not a hard-coded True."""
        b037 = next(m for m in MILESTONES if m.id == "B-037")
        self.assertTrue(b037.complete())
        self.assertIsNot(b037.is_complete, True)

    def test_b037_in_completed(self) -> None:
        """B-037 complete; schema stays 6; allowlist unchanged; B-038 absent."""
        b037 = next(m for m in MILESTONES if m.id == "B-037")
        self.assertTrue(b037.complete())
        self.assertIsNot(b037.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-037", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b038_is_complete(self) -> None:
        """Readiness check is not a hard-coded True."""
        b038 = next(m for m in MILESTONES if m.id == "B-038")
        self.assertTrue(b038.complete())
        self.assertIsNot(b038.is_complete, True)

    def test_b038_in_completed(self) -> None:
        """B-038 complete; schema stays 6; allowlist unchanged."""
        b038 = next(m for m in MILESTONES if m.id == "B-038")
        self.assertTrue(b038.complete())
        self.assertIsNot(b038.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-038", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b039_is_complete(self) -> None:
        """Orchestrator check is not a hard-coded True."""
        b039 = next(m for m in MILESTONES if m.id == "B-039")
        self.assertTrue(b039.complete())
        self.assertIsNot(b039.is_complete, True)

    def test_b039_in_completed(self) -> None:
        """B-039 complete; schema stays 6; allowlist unchanged."""
        b039 = next(m for m in MILESTONES if m.id == "B-039")
        self.assertTrue(b039.complete())
        self.assertIsNot(b039.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-039", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b040_is_complete(self) -> None:
        """Runtime-capability check is not a hard-coded True."""
        b040 = next(m for m in MILESTONES if m.id == "B-040")
        self.assertTrue(b040.complete())
        self.assertIsNot(b040.is_complete, True)

    def test_b040_in_completed(self) -> None:
        """B-040 complete; schema stays 6; allowlist unchanged."""
        b040 = next(m for m in MILESTONES if m.id == "B-040")
        self.assertTrue(b040.complete())
        self.assertIsNot(b040.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-040", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b041_is_complete(self) -> None:
        """Controlled-research check is not a hard-coded True."""
        b041 = next(m for m in MILESTONES if m.id == "B-041")
        self.assertTrue(b041.complete())
        self.assertIsNot(b041.is_complete, True)

    def test_b041_in_completed(self) -> None:
        """B-041 complete; schema stays 6; allowlist unchanged."""
        b041 = next(m for m in MILESTONES if m.id == "B-041")
        self.assertTrue(b041.complete())
        self.assertIsNot(b041.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-041", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_b026_is_complete(self) -> None:
        """Proposal evaluation judges DTOs; check is not a hard-coded True."""
        b026 = next(m for m in MILESTONES if m.id == "B-026")
        self.assertTrue(b026.complete())
        self.assertIsNot(b026.is_complete, True)

    def test_b026_in_completed(self) -> None:
        """evaluate_proposal implemented; schema stays 6."""
        b026 = next(m for m in MILESTONES if m.id == "B-026")
        self.assertTrue(b026.complete())
        self.assertIsNot(b026.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-026", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        evaluation = next((m for m in state.modules if m.module == "astra.evaluation"), None)
        self.assertIsNotNone(evaluation)
        assert evaluation is not None
        eval_prop = next(c for c in evaluation.capabilities if c.name == "evaluate_proposal")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, eval_prop.status)

    def test_b029_is_complete(self) -> None:
        """Baseline comparison is honest; check is not a hard-coded True."""
        b029 = next(m for m in MILESTONES if m.id == "B-029")
        self.assertTrue(b029.complete())
        self.assertIsNot(b029.is_complete, True)

    def test_b029_in_completed(self) -> None:
        """record_baseline implemented; schema stays 6; no deploy authority."""
        b029 = next(m for m in MILESTONES if m.id == "B-029")
        self.assertTrue(b029.complete())
        self.assertIsNot(b029.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-029", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        evaluation = next((m for m in state.modules if m.module == "astra.evaluation"), None)
        self.assertIsNotNone(evaluation)
        assert evaluation is not None
        baseline = next(c for c in evaluation.capabilities if c.name == "record_baseline")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, baseline.status)

    def test_b027_is_complete(self) -> None:
        """Controlled deploy writes only with ApprovalRecord; check is not a hard-coded True."""
        b027 = next(m for m in MILESTONES if m.id == "B-027")
        self.assertTrue(b027.complete())
        self.assertIsNot(b027.is_complete, True)

    def test_b027_in_completed(self) -> None:
        """deploy implemented; approval required; schema stays 6."""
        b027 = next(m for m in MILESTONES if m.id == "B-027")
        self.assertTrue(b027.complete())
        self.assertIsNot(b027.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-027", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        deployment = next((m for m in state.modules if m.module == "astra.deployment"), None)
        self.assertIsNotNone(deployment)
        assert deployment is not None
        deploy_cap = next(c for c in deployment.capabilities if c.name == "deploy")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, deploy_cap.status)
        self.assertIn("ApprovalRecord", deploy_cap.notes)
        rollback = next(c for c in deployment.capabilities if c.name == "rollback")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, rollback.status)
        self.assertIn("ApprovalRecord", rollback.notes)

    def test_b028_is_complete(self) -> None:
        """Version snapshot + approved rollback; check is not a hard-coded True."""
        b028 = next(m for m in MILESTONES if m.id == "B-028")
        self.assertTrue(b028.complete())
        self.assertIsNot(b028.is_complete, True)

    def test_b028_in_completed(self) -> None:
        """rollback implemented; requires ApprovalRecord; schema stays 6."""
        b028 = next(m for m in MILESTONES if m.id == "B-028")
        self.assertTrue(b028.complete())
        self.assertIsNot(b028.is_complete, True)
        state = self.provider.get_state()
        self.assertIn("B-028", state.completed_stages)
        self.assertEqual(6, state.schema_version)
        deployment = next((m for m in state.modules if m.module == "astra.deployment"), None)
        self.assertIsNotNone(deployment)
        assert deployment is not None
        rollback = next(c for c in deployment.capabilities if c.name == "rollback")
        self.assertEqual(CapabilityStatus.IMPLEMENTED, rollback.status)
        self.assertIn("ApprovalRecord", rollback.notes)

    def test_modules_present(self) -> None:
        state = self.provider.get_state()
        self.assertTrue(len(state.modules) > 0)

    def test_memory_module_is_operational(self) -> None:
        state = self.provider.get_state()
        mem = next((m for m in state.modules if m.module == "astra.memory"), None)
        self.assertIsNotNone(mem)
        self.assertEqual(CapabilityStatus.OPERATIONAL, mem.status)

    def test_active_model_label_is_set(self) -> None:
        state = self.provider.get_state()
        self.assertTrue(state.active_model)

    def test_test_count_is_positive(self) -> None:
        state = self.provider.get_state()
        self.assertGreater(state.tests.total, 0)

    def test_system_info_has_cpu(self) -> None:
        state = self.provider.get_state()
        self.assertTrue(state.system.cpu or state.system.platform)

    def test_stage_is_current_unfinished_milestone(self) -> None:
        state = self.provider.get_state()
        completed = set(state.completed_stages)
        # stage should be first milestone not in completed
        expected = next(
            (m.id for m in MILESTONES if m.id not in completed), "done"
        )
        self.assertEqual(expected, state.stage)


class DemoProviderTest(unittest.TestCase):
    def test_demo_provider_returns_valid_state(self) -> None:
        from ui.demo.provider import DemoStateProvider
        state = DemoStateProvider().get_state()
        self.assertIsInstance(state, ApplicationState)

    def test_demo_notes_indicate_demo_mode(self) -> None:
        from ui.demo.provider import DemoStateProvider
        state = DemoStateProvider().get_state()
        self.assertIn("Demo", state.notes)

    def test_demo_does_not_fabricate_operational_for_unconfigured(self) -> None:
        """Reasoning must not be OPERATIONAL if model file is not present."""
        from ui.demo.provider import DemoStateProvider
        state = DemoStateProvider().get_state()
        rea = next((m for m in state.modules if m.module == "astra.reasoning"), None)
        if rea is not None:
            # Demo mode may show CONFIGURED but never OPERATIONAL if not verified
            self.assertNotEqual(CapabilityStatus.OPERATIONAL, rea.status)

    def test_demo_completed_stages_match_reality(self) -> None:
        """Demo completed stages must match actual milestone completion."""
        from ui.demo.provider import DemoStateProvider
        demo_completed = set(DemoStateProvider().get_state().completed_stages)
        real_completed = set(m.id for m in MILESTONES if m.complete())
        self.assertEqual(real_completed, demo_completed)
