"""Prompt format tests."""

from __future__ import annotations

import unittest

from astra.presentation.conversation import (
    _chat_prompt,
    _sanitize_model_output,
    detect_prompt_format,
    generation_options_for_format,
    sanitize_chat_turns,
    strip_thinking,
)


class PromptFormatTest(unittest.TestCase):
    def test_llama_model_uses_llama3_template(self) -> None:
        self.assertEqual("llama3", detect_prompt_format("Llama-3.2-3B-Instruct-Q5_K_M"))
        self.assertEqual("llama3", detect_prompt_format("Dolphin3.0-Llama3.1-8B-Q5_K_S"))
        prompt = _chat_prompt(
            "Primus",
            [],
            "hello",
            prompt_format="llama3",
        )
        self.assertIn("<|start_header_id|>system", prompt)
        self.assertIn("<|eot_id|>", prompt)
        self.assertNotIn("<|im_start|>", prompt)

    def test_qwen_model_uses_chatml_template(self) -> None:
        self.assertEqual("chatml", detect_prompt_format("Qwen2.5-3B-Instruct-Q5_K_M"))
        prompt = _chat_prompt("Primus", [], "hello", prompt_format="chatml")
        self.assertIn("<|im_start|>user", prompt)
        self.assertIn("<|im_end|>", prompt)

    def test_sanitize_strips_leaked_tokens(self) -> None:
        raw = "Hello there. <|im_end|>"
        self.assertEqual("Hello there.", _sanitize_model_output(raw))

    def test_strip_thinking_keeps_reply_only(self) -> None:
        think_open = "<" + "think" + ">"
        think_close = "</" + "think" + ">"
        raw = f"{think_open}hidden{think_close}\nVisible answer."
        self.assertEqual("Visible answer.", strip_thinking(raw))
        self.assertEqual("Visible answer.", _sanitize_model_output(raw))

    def test_strip_redacted_thinking_tag(self) -> None:
        raw = "<think>hidden</think>\nFinal reply."
        self.assertEqual("Final reply.", _sanitize_model_output(raw))

    def test_qwen3_fast_chat_adds_no_think(self) -> None:
        prompt = _chat_prompt(
            "Primus",
            [],
            "hello",
            prompt_format="chatml",
            model_name="Huihui-Qwen3-8B-abliterated-v2-Q2_K",
            fast_chat=True,
        )
        self.assertIn("/no_think", prompt)

    def test_qwen3_chat_max_tokens(self) -> None:
        options = generation_options_for_format(
            "chatml",
            model_name="Huihui-Qwen3-8B-abliterated-v2-Q2_K",
        )
        self.assertGreaterEqual(options.max_tokens, 1024)
        think_opts = generation_options_for_format(
            "chatml",
            model_name="Huihui-Qwen3-8B-abliterated-v2-Q2_K",
            allow_thinking=True,
        )
        self.assertGreater(think_opts.max_tokens, options.max_tokens)

    def test_llama_stop_tokens(self) -> None:
        options = generation_options_for_format("llama3")
        self.assertIn("<|eot_id|>", options.stop)

    def test_sanitize_chat_turns_drops_thinking_and_traces(self) -> None:
        think_open = "<" + "think" + ">"
        think_close = "</" + "think" + ">"
        turns = [
            ("hi", f"{think_open}hidden{think_close}\nHello."),
            ("go", "Objective: continue. Actions: 2 succeeded."),
            ("ok", "   "),
        ]
        cleaned = sanitize_chat_turns(turns)
        self.assertEqual([("hi", "Hello.")], cleaned)


__all__ = []
