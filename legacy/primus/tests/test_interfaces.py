"""Interface tests.

The scaffold's promise is that nothing is faked. These tests hold it to that:
every contract is abstract, and every abstract method raises
``NotImplementedError`` rather than quietly returning a plausible value.
"""

from __future__ import annotations

import inspect
import unittest
from abc import ABC

from astra.coding import CodingAgent
from astra.core import Agent, Controller
from astra.deployment import DeploymentAgent, VersionManager
from astra.evaluation import EvaluationAgent
from astra.knowledge import KnowledgeClassifier, KnowledgeStore
from astra.memory import MemoryStore, VectorIndex
from astra.planning import Backlog, GoalManager, PlanningEngine
from astra.plugins import Plugin, PluginManager
from astra.reasoning import EmbeddingBackend, LLMBackend
from astra.research import ResearchAgent
from astra.security import SecurityAgent
from astra.testing import TestingAgent
from astra.tools import Tool, ToolRegistry

CONTRACTS = (
    Agent,
    Controller,
    MemoryStore,
    VectorIndex,
    KnowledgeStore,
    KnowledgeClassifier,
    PlanningEngine,
    Backlog,
    GoalManager,
    LLMBackend,
    EmbeddingBackend,
    ResearchAgent,
    CodingAgent,
    TestingAgent,
    EvaluationAgent,
    DeploymentAgent,
    VersionManager,
    SecurityAgent,
    Plugin,
    PluginManager,
    Tool,
    ToolRegistry,
)


class ContractTest(unittest.TestCase):
    def test_contracts_are_abstract(self) -> None:
        for contract in CONTRACTS:
            with self.subTest(contract=contract.__name__):
                self.assertTrue(issubclass(contract, ABC))
                self.assertTrue(
                    getattr(contract, "__abstractmethods__", frozenset()),
                    f"{contract.__name__} declares no abstract methods",
                )

    def test_contracts_cannot_be_instantiated(self) -> None:
        for contract in CONTRACTS:
            with self.subTest(contract=contract.__name__):
                with self.assertRaises(TypeError):
                    contract()  # type: ignore[abstract]

    def test_abstract_methods_refuse_to_run(self) -> None:
        """A subclass that forwards to the base must get NotImplementedError.

        This is what stops an unfinished module from returning a value that
        looks like a real answer.
        """
        for contract in CONTRACTS:
            for name in sorted(getattr(contract, "__abstractmethods__", ())):
                attribute = inspect.getattr_static(contract, name)
                function = (
                    attribute.fget if isinstance(attribute, property) else attribute
                )
                with self.subTest(contract=contract.__name__, method=name):
                    arguments = _dummy_arguments(function)
                    with self.assertRaises(NotImplementedError):
                        function(_Stub(), *arguments)


class _Stub:
    """Stands in for ``self`` when calling an unbound abstract method."""


def _dummy_arguments(function: object) -> tuple[object, ...]:
    signature = inspect.signature(function)  # type: ignore[arg-type]
    required = [
        parameter
        for name, parameter in signature.parameters.items()
        if name != "self"
        and parameter.default is inspect.Parameter.empty
        and parameter.kind
        not in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD)
    ]
    return tuple(None for _ in required)


if __name__ == "__main__":
    unittest.main()
