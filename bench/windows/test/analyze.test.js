// node --test bench/windows/test/*.test.js
// Real llama-bench output (b11844, Linux build container) checks parsing; synthetic runs (labelled) check the rules.
const test = require("node:test");
const assert = require("node:assert/strict");
const A = require("../analyze.js");
const real = require("./fixtures/llama-bench-b11844-linux.json").bench;

const GB = A.GB;
function run(model, role, backend, threads, bench, extra = {}) {
  return Object.assign({ model, role, backend, threads, gpuLayers: backend === "vulkan" ? 99 : 0, bench, exitCode: 0,
    peakPrivateBytes: 1 * GB, eligible: true, params: 8e9 }, extra);
}
// Synthetic llama-bench rows (rule tests only; not measurements).
function bench(pp, tg) {
  return [{ n_prompt: 512, n_gen: 0, avg_ts: pp }, { n_prompt: 0, n_gen: 128, avg_ts: tg }];
}

test("parses real llama-bench JSON: prompt and generation speeds per thread count", () => {
  const four = real["llama-1b-q4_k_m"].filter((r) => r.n_threads === 4);
  const s = A.speeds(four);
  assert.ok(s.promptTps > 100 && s.genTps > 5, JSON.stringify(s));
  assert.equal(real["llama-1b-q4_k_m"][0].build_number, 11844, "the same llama.cpp build CLU vendors");
});

test("real data: fewer threads win when they are faster (the container is oversubscribed at 8)", () => {
  const rows = [4, 8].map((t) => run("llama-1b", "fast", "cpu", t, real["llama-1b-q4_k_m"].filter((r) => r.n_threads === t), { params: 1e9 }));
  const r = A.recommend({ runs: rows }, { rules: { fastMinGenTps: 5 } });
  assert.equal(r.fast.chosen.best.threads, 4);
  assert.equal(r.backend, "cpu");
});

test("Vulkan is chosen only when clearly faster than the best CPU run", () => {
  const close = A.recommend({ runs: [run("m", "main", "cpu", 8, bench(120, 10)), run("m", "main", "vulkan", 4, bench(300, 10.5))] });
  assert.equal(close.backend, "cpu", "5 % faster is not worth competing with games for the GPU");
  const clear = A.recommend({ runs: [run("m", "main", "cpu", 8, bench(120, 10)), run("m", "main", "vulkan", 4, bench(300, 14))] });
  assert.equal(clear.backend, "vulkan");
});

test("a model over its memory limit is rejected even when fast; GPU shared memory counts (UMA)", () => {
  const r = A.recommend({ runs: [
    run("big", "main", "vulkan", 4, bench(200, 15), { params: 14e9, peakPrivateBytes: 1 * GB, peakGpuSharedBytes: 8 * GB }),
    run("ok", "main", "cpu", 8, bench(90, 9), { params: 8e9, peakPrivateBytes: 5.5 * GB }),
  ] });
  assert.equal(r.main.chosen.model, "ok");
  assert.match(r.main.verdicts.find((v) => v.model === "big").why, /over the 7 GB limit/);
});

test("the largest eligible model that passes is the main model; ineligible ones never become the default", () => {
  const r = A.recommend({ runs: [
    run("7b", "main", "cpu", 8, bench(90, 11), { params: 7e9, peakPrivateBytes: 5 * GB }),
    run("8b", "main", "cpu", 8, bench(80, 9), { params: 8e9, peakPrivateBytes: 5.6 * GB }),
    run("8b-abliterated", "main", "cpu", 8, bench(85, 12), { params: 8e9, eligible: false, peakPrivateBytes: 4 * GB }),
  ] });
  assert.equal(r.main.chosen.model, "8b");
});

test("too slow to speak: no main model, and the report says what to do instead", () => {
  const r = A.recommend({ runs: [run("8b", "main", "cpu", 8, bench(40, 4))] });
  assert.equal(r.main.chosen, null);
  assert.ok(r.warnings.some((w) => w.startsWith("No main model passed")));
  assert.match(r.main.verdicts[0].why, /tokens\/s is under 8/);
});

test("a failed Vulkan run is reported, and CPU is used", () => {
  const r = A.recommend({ runs: [run("m", "main", "cpu", 8, bench(90, 9)), run("m", "main", "vulkan", 4, [], { exitCode: 1, error: "no Vulkan device" })] });
  assert.equal(r.backend, "cpu");
  assert.ok(r.warnings.some((w) => /no Vulkan run succeeded/.test(w)));
  assert.equal(r.rows.find((x) => x.backend === "vulkan").error, "no Vulkan device");
});

test("battery power, unverified downloads and foreground slowdown become warnings", () => {
  const r = A.recommend({ hardware: { onBattery: true }, llama: { assets: [{ name: "x.zip", verified: false }] },
    coexistence: { aloneMBps: 1000, withInferenceMBps: 600 }, runs: [run("m", "main", "cpu", 8, bench(90, 9))] });
  assert.ok(r.warnings.some((w) => /battery/.test(w)));
  assert.ok(r.warnings.some((w) => /x\.zip could not be checked/.test(w)));
  assert.ok(r.warnings.some((w) => /40 % slower/.test(w)));
});
