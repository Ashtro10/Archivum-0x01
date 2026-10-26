#!/usr/bin/env python3
"""Creates a tiny random-weight GGUF model for exercising the llama.cpp JNI bridge in tests.

The tokenizer (and therefore tokenization / chat-template behaviour) is real: it is copied
from llama.cpp's vocab-only test file for Qwen2. The weights are random, so generated text
is meaningless. This model verifies plumbing (load, template, tokenize, decode, sample,
KV-cache reuse, grammar, cancellation) - NOT response quality.

usage: make_test_gguf.py <llama.cpp dir> <output.gguf> [seed] [--shape tiny|1b]

--shape 1b writes F16 weights with the dimensions of Llama 3.2 1B (2048 embd, 16 layers, 32/8 heads, 8192 ffn,
tied embeddings) for latency measurements: compute cost matches a real 1B model, output is still meaningless.
Quantise it with llama-quantize (e.g. Q4_K_M) to match what phones run.
"""
import sys
from pathlib import Path

import numpy as np

shape = sys.argv[sys.argv.index("--shape") + 1] if "--shape" in sys.argv else "tiny"
args = [a for i, a in enumerate(sys.argv) if a != "--shape" and (i == 0 or sys.argv[i - 1] != "--shape")]
llama_dir = Path(args[1])
out = Path(args[2])
sys.path.insert(0, str(llama_dir / "gguf-py"))
import gguf  # noqa: E402

vocab = gguf.GGUFReader(str(llama_dir / "models" / "ggml-vocab-qwen2.gguf"))

ARCH = "llama"
big = shape == "1b"
if big:
    n_embd, n_layer, n_head, n_head_kv, n_ff, n_ctx = 2048, 16, 32, 8, 8192, 8192
else:
    n_embd, n_layer, n_head, n_head_kv, n_ff, n_ctx = 64, 2, 4, 2, 128, 2048
w = gguf.GGUFWriter(str(out), ARCH)
w.add_name("clu-test-1b-shape-random" if big else "clu-test-tiny-random")
w.add_context_length(n_ctx)
w.add_embedding_length(n_embd)
w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff)
w.add_head_count(n_head)
w.add_head_count_kv(n_head_kv)
w.add_rope_dimension_count(n_embd // n_head)
w.add_layer_norm_rms_eps(1e-6)
w.add_file_type(gguf.LlamaFileType.MOSTLY_F16 if big else gguf.LlamaFileType.ALL_F32)

n_vocab = None
for field in vocab.fields.values():
    name = field.name
    if not name.startswith("tokenizer."):
        continue
    if name == "tokenizer.ggml.tokens":
        n_vocab = len(field.data)
    vtype = field.types[0]
    if vtype == gguf.GGUFValueType.ARRAY:
        sub = field.types[-1]
        if sub == gguf.GGUFValueType.STRING:
            vals = [bytes(field.parts[i]).decode("utf-8", errors="replace") for i in field.data]
        else:
            vals = [field.parts[i].tolist()[0] for i in field.data]
        w.add_key_value(name, vals, vtype, sub_type=sub)
    elif vtype == gguf.GGUFValueType.STRING:
        w.add_key_value(name, bytes(field.parts[field.data[0]]).decode("utf-8"), vtype)
    else:
        w.add_key_value(name, field.parts[field.data[0]].tolist()[0], vtype)

# ChatML-style template, as used by Qwen2.5 instruct models.
w.add_chat_template(
    "{% for message in messages %}{{'<|im_start|>' + message['role'] + '\n' + message['content'] + '<|im_end|>' + '\n'}}"
    "{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\n' }}{% endif %}"
)

rng = np.random.default_rng(int(args[3]) if len(args) > 3 else 42)
def t(*shape):
    if big:
        return (rng.standard_normal(shape, dtype=np.float32) * 0.02).astype(np.float16)
    return (rng.standard_normal(shape) * 0.02).astype(np.float32)

head_dim = n_embd // n_head
w.add_tensor("token_embd.weight", t(n_vocab, n_embd))
w.add_tensor("output_norm.weight", np.ones(n_embd, dtype=np.float32))
if not big:
    w.add_tensor("output.weight", t(n_vocab, n_embd))  # 1b shape: tied embeddings, as in Llama 3.2 1B
for i in range(n_layer):
    p = f"blk.{i}."
    w.add_tensor(p + "attn_norm.weight", np.ones(n_embd, dtype=np.float32))
    w.add_tensor(p + "attn_q.weight", t(n_embd, n_embd))
    w.add_tensor(p + "attn_k.weight", t(n_head_kv * head_dim, n_embd))
    w.add_tensor(p + "attn_v.weight", t(n_head_kv * head_dim, n_embd))
    w.add_tensor(p + "attn_output.weight", t(n_embd, n_embd))
    w.add_tensor(p + "ffn_norm.weight", np.ones(n_embd, dtype=np.float32))
    w.add_tensor(p + "ffn_gate.weight", t(n_ff, n_embd))
    w.add_tensor(p + "ffn_up.weight", t(n_ff, n_embd))
    w.add_tensor(p + "ffn_down.weight", t(n_embd, n_ff))

w.write_header_to_file()
w.write_kv_data_to_file()
w.write_tensors_to_file()
w.close()
print(f"wrote {out} (vocab={n_vocab})")
