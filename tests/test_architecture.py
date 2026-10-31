"""The module map is an architectural rule, so it is tested like one.

These tests fail if a constitution component loses its home, if a module is
renamed, or if an undocumented top-level package appears.
"""

from __future__ import annotations

import importlib
import unittest
from pathlib import Path

# The fifteen packages of the System Architecture, as mapped in
# docs/architecture.md, plus:
#   astra.reasoning      ADR-0001: home for swappable model runtimes.
#   astra.state          ADR-0006: leaf package holding Presentation Layer contracts.
#   astra.presentation   ADR-0006: bridge assembling ApplicationState from live system.
EXPECTED_PACKAGES = {
    "astra.core",
    "astra.memory",
    "astra.knowledge",
    "astra.planning",
    "astra.reasoning",
    "astra.research",
    "astra.coding",
    "astra.testing",
    "astra.evaluation",
    "astra.deployment",
    "astra.security",
    "astra.plugins",
    "astra.tools",
    "astra.configuration",
    "astra.logging",
    "astra.state",
    "astra.presentation",
}

# Components the constitution names that live inside another package.
NESTED_COMPONENTS = {
    "astra.planning.goals": "GoalManager",
    "astra.deployment.versioning": "VersionManager",
}


class ModuleMapTest(unittest.TestCase):
    def test_every_expected_package_imports(self) -> None:
        for name in sorted(EXPECTED_PACKAGES):
            with self.subTest(package=name):
                self.assertIsNotNone(importlib.import_module(name))

    def test_no_undocumented_top_level_packages(self) -> None:
        package_root = Path(importlib.import_module("astra").__file__).parent
        found = {
            f"astra.{entry.name}"
            for entry in package_root.iterdir()
            if entry.is_dir() and (entry / "__init__.py").is_file()
        }
        self.assertEqual(EXPECTED_PACKAGES, found)

    def test_nested_components_expose_their_manager(self) -> None:
        for module_name, attribute in NESTED_COMPONENTS.items():
            with self.subTest(module=module_name):
                module = importlib.import_module(module_name)
                self.assertTrue(hasattr(module, attribute))


class ConstitutionCategoriesTest(unittest.TestCase):
    """Category enumerations must match the constitution exactly."""

    def test_memory_categories(self) -> None:
        from astra.memory import MemoryCategory

        expected = {
            "user_preferences",
            "project_knowledge",
            "documentation",
            "architecture",
            "research",
            "code_summaries",
            "experiments",
            "failures",
            "hardware",
            "ideas",
        }
        self.assertEqual(expected, {member.value for member in MemoryCategory})

    def test_knowledge_categories(self) -> None:
        from astra.knowledge import KnowledgeCategory

        expected = {
            "architecture",
            "programming",
            "artificial_intelligence",
            "mathematics",
            "operating_systems",
            "networking",
            "security",
            "hardware",
            "projects",
            "user_preferences",
            "experiments",
            "failures",
            "ideas",
        }
        self.assertEqual(expected, {member.value for member in KnowledgeCategory})

    def test_lifecycle_stages_are_ordered_as_written(self) -> None:
        from astra.core.lifecycle import LIFECYCLE_ORDER

        self.assertEqual(
            [
                "research",
                "design",
                "implementation",
                "testing",
                "evaluation",
                "documentation",
                "deployment",
            ],
            [stage.value for stage in LIFECYCLE_ORDER],
        )

    def test_human_approval_precedes_deployment(self) -> None:
        from astra.core.lifecycle import IMPROVEMENT_ORDER, ImprovementStage

        stages = list(IMPROVEMENT_ORDER)
        self.assertLess(
            stages.index(ImprovementStage.HUMAN_APPROVAL),
            stages.index(ImprovementStage.DEPLOYMENT),
        )


if __name__ == "__main__":
    unittest.main()
