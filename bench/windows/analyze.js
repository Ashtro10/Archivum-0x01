/*
 * CLU benchmark analysis (M1). Turns the raw results written by Run-CluBenchmark.ps1 into per-run figures and one
 * recommendation: which backend (CPU or Vulkan), how many threads, which main and fast model fit the budget.
 * Pure functions, no I/O: used by report.html in the browser and by the Node tests (bench/windows/test).
 * The rules are documented in docs/convergence/M1_BENCHMARK.md; change them there and here together.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.CluBenchAnalysis = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const GB = 1024 * 1024 * 1024;

  /** The default rules (the config file may override any of them). */
  const DEFAULT_RULES = {
    totalBudgetGB: 16,       // CLU's whole ceiling (a ceiling, not a target)
    mainPeakGB: 7,           // main model incl. context and GPU allocations (UMA: from system RAM)
    fastPeakGB: 3,
    mainMinGenTps: 8,        // spoken replies need ~4-6 tokens/s; 8 leaves margin
    mainMinPromptTps: 50,    // a 512-token prompt in about 10 s at worst (the prompt cache cuts repeats)
    fastMinGenTps: 20,
    vulkanMinAdvantage: 0.10, // the GPU is only worth it when clearly faster (it competes with games and video)
    threadsTolerance: 0.05,   // fewer threads when within 5 % of the best: leaves cores for the user
    coexistenceWarnSlowdown: 0.25,
  };

  function rulesFrom(config) {
    return Object.assign({}, DEFAULT_RULES, (config && config.rules) || {});
  }

  /** Average tokens/s of the prompt (pp) and generation (tg) tests in one llama-bench JSON array. */
  function speeds(bench) {
    const out = { promptTps: null, genTps: null };
    for (const r of bench || []) {
      if (typeof r.avg_ts !== "number") continue;
      if (r.n_prompt > 0 && !r.n_gen) out.promptTps = r.avg_ts;
      else if (r.n_gen > 0 && !r.n_prompt) out.genTps = r.avg_ts;
    }
    return out;
  }

  /** Peak memory a run used: process private bytes plus GPU shared memory (iGPU allocations come out of RAM). */
  function peakBytes(run) {
    const priv = Number(run.peakPrivateBytes || 0);
    const gpu = Number(run.peakGpuSharedBytes || 0) + Number(run.peakGpuDedicatedBytes || 0);
    return priv + gpu;
  }

  /** One row per run: what was measured, and whether it ran at all. */
  function summarise(results) {
    return (results.runs || []).map((run) => {
      const s = speeds(run.bench);
      const ok = run.exitCode === 0 && s.genTps !== null;
      return {
        model: run.model, role: run.role, eligible: run.eligible !== false, params: Number(run.params || 0),
        backend: run.backend, threads: run.threads, gpuLayers: run.gpuLayers,
        promptTps: s.promptTps, genTps: s.genTps, peakGB: peakBytes(run) / GB, ok,
        error: ok ? "" : (run.error || ("exit code " + run.exitCode)),
      };
    });
  }

  function fits(row, rules, role) {
    if (!row.ok) return { pass: false, why: "did not run: " + row.error };
    const peak = role === "main" ? rules.mainPeakGB : rules.fastPeakGB;
    const minGen = role === "main" ? rules.mainMinGenTps : rules.fastMinGenTps;
    if (row.peakGB > peak) return { pass: false, why: `peak ${row.peakGB.toFixed(1)} GB is over the ${peak} GB limit` };
    if (row.genTps < minGen) return { pass: false, why: `${row.genTps.toFixed(1)} tokens/s is under ${minGen}` };
    if (role === "main" && row.promptTps !== null && row.promptTps < rules.mainMinPromptTps)
      return { pass: false, why: `prompt ${row.promptTps.toFixed(0)} tokens/s is under ${rules.mainMinPromptTps}` };
    return { pass: true, why: "" };
  }

  /** The best configuration of one model: the fastest generation, with fewer threads when nearly as fast, and the GPU only when clearly faster. */
  function bestConfig(rows, rules) {
    const ok = rows.filter((r) => r.ok);
    if (!ok.length) return null;
    const pickThreads = (list) => {
      if (!list.length) return null;
      const top = Math.max(...list.map((r) => r.genTps));
      return list.filter((r) => r.genTps >= top * (1 - rules.threadsTolerance))
        .sort((a, b) => (a.threads - b.threads) || (b.genTps - a.genTps))[0];
    };
    const cpu = pickThreads(ok.filter((r) => r.backend === "cpu"));
    const gpu = pickThreads(ok.filter((r) => r.backend === "vulkan"));
    if (cpu && gpu) return gpu.genTps >= cpu.genTps * (1 + rules.vulkanMinAdvantage) ? gpu : cpu;
    return cpu || gpu;
  }

  /** Picks the main and fast model: the largest eligible model of each role that passes, at its best configuration. */
  function recommend(results, config) {
    const rules = rulesFrom(config);
    const rows = summarise(results);
    const warnings = [];
    const hw = results.hardware || {};
    if (hw.onBattery) warnings.push("Measured on battery power: plug in and run again for representative numbers.");
    if (results.llama && results.llama.assets) for (const a of results.llama.assets)
      if (!a.verified) warnings.push(`The download ${a.name} could not be checked against a published SHA-256.`);
    if (rows.some((r) => r.backend === "vulkan") && !rows.some((r) => r.backend === "vulkan" && r.ok))
      warnings.push("Vulkan was tried but no Vulkan run succeeded: the 860M is not usable through llama.cpp Vulkan on this driver (CPU only).");
    if (!rows.some((r) => r.backend === "vulkan")) warnings.push("No Vulkan run in the results: the GPU was not measured.");

    const decide = (role) => {
      const byModel = new Map();
      for (const r of rows.filter((x) => x.role === role)) {
        if (!byModel.has(r.model)) byModel.set(r.model, []);
        byModel.get(r.model).push(r);
      }
      const verdicts = [];
      for (const [model, list] of byModel) {
        const best = bestConfig(list, rules);
        const check = best ? fits(best, rules, role) : { pass: false, why: "no successful run" };
        verdicts.push({ model, eligible: list[0].eligible, params: list[0].params, best, pass: check.pass, why: check.why });
      }
      const chosen = verdicts.filter((v) => v.pass && v.eligible).sort((a, b) => (b.params - a.params) || (b.best.genTps - a.best.genTps))[0] || null;
      return { chosen, verdicts };
    };
    const main = decide("main");
    const fast = decide("fast");
    if (!main.chosen) warnings.push("No main model passed: CLU should run its fast model for everything, or try a smaller main model.");
    if (!fast.chosen) warnings.push("No fast model passed the speed threshold.");

    // The two models are never resident at the same time at peak, but both together must still fit the ceiling.
    const peak = (main.chosen ? main.chosen.best.peakGB : 0) + (fast.chosen ? fast.chosen.best.peakGB : 0);
    if (peak > rules.totalBudgetGB) warnings.push(`Main and fast together peak at ${peak.toFixed(1)} GB, over the ${rules.totalBudgetGB} GB ceiling: never load both at once.`);

    const co = results.coexistence;
    if (co && typeof co.aloneMBps === "number" && typeof co.withInferenceMBps === "number" && co.aloneMBps > 0) {
      co.slowdown = 1 - co.withInferenceMBps / co.aloneMBps;
      if (co.slowdown > rules.coexistenceWarnSlowdown)
        warnings.push(`A foreground task ran ${(co.slowdown * 100).toFixed(0)} % slower while CLU generated at low priority: the governor must pause or downgrade inference when you are busy.`);
    }

    const backend = main.chosen ? main.chosen.best.backend : (fast.chosen ? fast.chosen.best.backend : null);
    return { rules, rows, main, fast, backend, peakTogetherGB: peak, coexistence: co || null, warnings };
  }

  return { DEFAULT_RULES, speeds, peakBytes, summarise, bestConfig, recommend, GB };
});
