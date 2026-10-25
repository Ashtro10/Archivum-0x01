#!/usr/bin/env python3
"""Rebuilds a whisper.cpp ggml model from the fp32 sherpa-onnx Whisper export (HOST TESTING ONLY).

Why: the official ggml weights (huggingface.co) and OpenAI's checkpoints are unreachable from the build container, but
the sherpa-onnx release tarball (GitHub) contains the same OpenAI weights as fp32 ONNX. This script maps the ONNX
initializers back to the PyTorch parameter names by tracing the graph, and writes the format of whisper.cpp's
models/convert-pt-to-ggml.py (fp16 matrices, fp32 vectors/conv biases/positional embeddings).

The output is NOT the official file (different SHA1); the app downloads the official ggml model and verifies its SHA1.
Only used to run and measure whisper.cpp on the host.

usage: onnx_whisper_to_ggml.py <encoder.onnx> <decoder.onnx> <openai-whisper/whisper/assets> <out.bin>
"""
import base64
import struct
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper


def linears(model):
    """MatMul nodes with a constant 2-D weight, in graph order: (weight [in,out], bias or None)."""
    inits = {t.name: t for t in model.graph.initializer}
    consumers = {}
    for n in model.graph.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    out = []
    for n in model.graph.node:
        if n.op_type != "MatMul" or n.input[1] not in inits or len(inits[n.input[1]].dims) != 2:
            continue
        w = numpy_helper.to_array(inits[n.input[1]])
        bias = None
        for c in consumers.get(n.output[0], []):
            if c.op_type == "Add":
                other = [i for i in c.input if i != n.output[0]][0]
                if other in inits and len(inits[other].dims) == 1:
                    bias = numpy_helper.to_array(inits[other])
        out.append((w, bias))
    return out, {k: numpy_helper.to_array(v) for k, v in inits.items()}


def main():
    enc_p, dec_p, assets, out_p = sys.argv[1:5]
    enc = onnx.load(enc_p)
    dec = onnx.load(dec_p)
    meta = {p.key: p.value for p in enc.metadata_props}
    h = {k: int(meta[k]) for k in ["n_mels", "n_audio_ctx", "n_audio_state", "n_audio_head", "n_audio_layer", "n_vocab",
                                   "n_text_ctx", "n_text_state", "n_text_head", "n_text_layer"]}
    L_a, L_t = h["n_audio_layer"], h["n_text_layer"]
    tensors = {}

    # ---- encoder
    lin, init = linears(enc)
    assert len(lin) == 6 * L_a + 2 * L_t, len(lin)
    convs = [n for n in enc.graph.node if n.op_type == "Conv"]
    assert len(convs) == 2
    for i, c in enumerate(convs, 1):
        tensors[f"encoder.conv{i}.weight"] = init[c.input[1]]
        tensors[f"encoder.conv{i}.bias"] = init[c.input[2]]
    tensors["encoder.positional_embedding"] = init["audioEncoder.positional_embedding"]
    names = ["attn.query", "attn.key", "attn.value", "attn.out", "mlp.0", "mlp.2"]
    for b in range(L_a):
        for j, nm in enumerate(names):
            w, bias = lin[6 * b + j]
            tensors[f"encoder.blocks.{b}.{nm}.weight"] = w.T
            assert (bias is None) == (nm == "attn.key"), (b, nm)
            if bias is not None:
                tensors[f"encoder.blocks.{b}.{nm}.bias"] = bias
        for ln in ["attn_ln", "mlp_ln"]:
            for p in ["weight", "bias"]:
                tensors[f"encoder.blocks.{b}.{ln}.{p}"] = init[f"audioEncoder.blocks.{b}.{ln}.{p}"]
    for p in ["weight", "bias"]:
        tensors[f"encoder.ln_post.{p}"] = init[f"audioEncoder.ln_post.{p}"]
    # The export computes the decoder's cross-attention keys/values in the encoder graph (after ln_post).
    for b in range(L_t):
        for j, nm in enumerate(["cross_attn.key", "cross_attn.value"]):
            w, bias = lin[6 * L_a + 2 * b + j]
            tensors[f"decoder.blocks.{b}.{nm}.weight"] = w.T
            assert (bias is None) == (nm == "cross_attn.key"), (b, nm)
            if bias is not None:
                tensors[f"decoder.blocks.{b}.{nm}.bias"] = bias

    # ---- decoder
    lin, init = linears(dec)
    names = ["attn.query", "attn.key", "attn.value", "attn.out", "cross_attn.query", "cross_attn.out", "mlp.0", "mlp.2"]
    # The token embedding is also used as the (tied) output projection: skip a MatMul against it if present.
    lin = [x for x in lin if x[0].shape[0] != h["n_vocab"] and x[0].shape[1] != h["n_vocab"]]
    assert len(lin) == len(names) * L_t, len(lin)
    tensors["decoder.positional_embedding"] = init["textDecoder.positional_embedding"]
    tensors["decoder.token_embedding.weight"] = init["textDecoder.token_embedding.weight"]
    for b in range(L_t):
        for j, nm in enumerate(names):
            w, bias = lin[len(names) * b + j]
            tensors[f"decoder.blocks.{b}.{nm}.weight"] = w.T
            assert (bias is None) == (nm == "attn.key"), (b, nm)
            if bias is not None:
                tensors[f"decoder.blocks.{b}.{nm}.bias"] = bias
        for ln in ["attn_ln", "cross_attn_ln", "mlp_ln"]:
            for p in ["weight", "bias"]:
                tensors[f"decoder.blocks.{b}.{ln}.{p}"] = init[f"textDecoder.blocks.{b}.{ln}.{p}"]
    for p in ["weight", "bias"]:
        tensors[f"decoder.ln.{p}"] = init[f"textDecoder.ln.{p}"]

    # ---- write (format of whisper.cpp models/convert-pt-to-ggml.py, f16)
    filters = np.load(Path(assets) / "mel_filters.npz")[f"mel_{h['n_mels']}"]
    tokens = [base64.b64decode(line.split()[0]) for line in (Path(assets) / "multilingual.tiktoken").read_bytes().splitlines() if line]
    with open(out_p, "wb") as f:
        f.write(struct.pack("i", 0x67676d6c))
        for k in ["n_vocab", "n_audio_ctx", "n_audio_state", "n_audio_head", "n_audio_layer", "n_text_ctx", "n_text_state",
                  "n_text_head", "n_text_layer", "n_mels"]:
            f.write(struct.pack("i", h[k]))
        f.write(struct.pack("i", 1))  # f16
        f.write(struct.pack("ii", *filters.shape))
        f.write(filters.astype(np.float32).tobytes())
        f.write(struct.pack("i", len(tokens)))
        for t in tokens:
            f.write(struct.pack("i", len(t))); f.write(t)
        for name, data in tensors.items():
            data = np.ascontiguousarray(data.squeeze())
            if name in ("encoder.conv1.bias", "encoder.conv2.bias"):
                data = data.reshape(data.shape[0], 1)
            f32 = data.ndim < 2 or name in ("encoder.conv1.bias", "encoder.conv2.bias", "encoder.positional_embedding",
                                            "decoder.positional_embedding")
            data = data.astype(np.float32 if f32 else np.float16)
            nb = name.encode()
            f.write(struct.pack("iii", data.ndim, len(nb), 0 if f32 else 1))
            for i in range(data.ndim):
                f.write(struct.pack("i", data.shape[data.ndim - 1 - i]))
            f.write(nb)
            data.tofile(f)
    print(f"wrote {out_p}: {len(tensors)} tensors, hparams {h}")


if __name__ == "__main__":
    main()
