/*
 * CLU experience kit: the runtime every installed experience runs on. It obtains CLU's session (a one-time launch code
 * in the address, or the shell's session when shown inside CLU), talks only to CLU's local API, keeps the experience's
 * configuration in CLU's registry, reports errors, and runs the experience's self-tests when CLU verifies it
 * (`?selftest=1`): the results are written to #clu-selfcheck for the verifier to read from the rendered page.
 */
(function () {
  "use strict";
  const Kit = { checks: [], errors: [], tests: [], cfg: null, token: null };
  window.addEventListener("error", (e) => Kit.errors.push(String(e.message || e)));
  window.addEventListener("unhandledrejection", (e) => Kit.errors.push("unhandled: " + String((e.reason && e.reason.message) || e.reason)));
  const params = new URLSearchParams(location.search);
  Kit.selftest = params.has("selftest");
  Kit.preview = params.has("preview");

  Kit.$ = (id) => document.getElementById(id);
  Kit.el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  Kit.sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  Kit.check = (name, ok, detail) => { Kit.checks.push({ name, ok: !!ok, detail: detail == null ? "" : String(detail).slice(0, 300) }); return !!ok; };
  Kit.unverified = [];
  Kit.test = (name, fn) => Kit.tests.push({ name, fn });

  async function session() {
    const m = /[#&]code=([A-Za-z0-9_-]+)/.exec(location.hash);
    if (m) {
      history.replaceState(null, "", location.pathname + location.search);
      try {
        const r = await fetch("/v1/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: m[1] }) });
        if (r.ok) { const t = (await r.json()).token; try { sessionStorage.setItem("clu-token", t); } catch (e) {} return t; }
      } catch (e) { /* below */ }
    }
    try { return sessionStorage.getItem("clu-token"); } catch (e) { return null; }
  }

  Kit.api = async function (path, opts) {
    opts = opts || {};
    const headers = Object.assign({ Authorization: "Bearer " + Kit.token }, opts.json !== undefined ? { "Content-Type": "application/json" } : {});
    const r = await fetch(path, { method: opts.method || "GET", headers, body: opts.json !== undefined ? JSON.stringify(opts.json) : undefined });
    if (opts.raw) return r;
    const data = (r.headers.get("Content-Type") || "").includes("json") ? await r.json() : null;
    if (!r.ok) { const e = new Error((data && data.error) || ("HTTP " + r.status)); e.status = r.status; e.data = data; throw e; }
    return data;
  };

  Kit.status = function (text, level) {
    const s = Kit.$("expStatus"); if (!s) return;
    s.textContent = text; s.className = "exp-status" + (level ? " " + level : "");
  };
  Kit.source = function (text) { const s = Kit.$("expSource"); if (s) s.textContent = text; };

  /** The experience's own settings (place, pins, units), kept in CLU's capability registry. */
  // While CLU verifies an experience it starts from a clean slate and never changes the owner's saved settings.
  Kit.config = {
    async get() { if (Kit.selftest || Kit.preview) return {}; try { return (await Kit.api(`/v1/capabilities/${encodeURIComponent(Kit.cfg.capId)}/config`)) || {}; } catch (e) { return {}; } },
    async set(patch) { if (Kit.selftest || Kit.preview) return null; try { return await Kit.api(`/v1/capabilities/${encodeURIComponent(Kit.cfg.capId)}/config`, { method: "PUT", json: patch }); } catch (e) { return null; } },
  };

  /** Read-only files below the folder the owner permitted for this application (nothing without that permission). */
  const appPath = (op, q) => `/v1/apps/${encodeURIComponent(Kit.cfg.appId || Kit.cfg.capId)}/fs/${op}?${q}`;
  Kit.fs = {
    list: (path) => Kit.api(appPath("list", "path=" + encodeURIComponent(path || ""))),
    read: (path) => Kit.api(appPath("read", "path=" + encodeURIComponent(path || ""))),
    search: (q, path) => Kit.api(appPath("search", "q=" + encodeURIComponent(q || "") + "&path=" + encodeURIComponent(path || ""))),
  };

  Kit.places = async function (q) { return (await Kit.api("/v1/kit/places?q=" + encodeURIComponent(q))).places || []; };

  /** Dashboard wiring: choosing a place on the map shows its weather. */
  Kit.link = async function (kit, map, wx) {
    if (!map || !wx) return;
    map.onPick = async (lat, lon) => { const p = await Kit.api(`/v1/kit/places/near?lat=${lat}&lon=${lon}`).catch(() => null); if (p && p.place) await wx.choose(p.place, true); };
    wx.onPlace = (p) => map.flyTo(p.lat, p.lon, Math.max(map.zoom(), 5), p.name);
    Kit.test("dashboard: a place picked on the map is the place the weather shows", async () => {
      const before = wx.place() && wx.place().name;
      await map.onPick(35.68, 139.69); await Kit.sleep(50);
      const after = wx.place() && wx.place().name;
      return Kit.check("linked: map pick sets the weather place", after && after !== before && /Tokyo/i.test(after), `${before} → ${after}`);
    });
  };

  function finish() {
    const pre = Kit.$("clu-selfcheck");
    if (Kit.errors.length) Kit.check("no script errors", false, Kit.errors.join(" | "));
    else Kit.check("no script errors", true);
    if (pre) pre.textContent = JSON.stringify({ capId: Kit.cfg && Kit.cfg.capId, version: Kit.cfg && Kit.cfg.version, checks: Kit.checks, unverified: Kit.unverified });
    document.body.dataset.cluReady = "1";
    try { if (window.parent !== window) window.parent.postMessage({ type: "clu-capability", state: "ready", capId: Kit.cfg && Kit.cfg.capId }, location.origin); } catch (e) {}
  }

  Kit.start = async function (config, mount) {
    Kit.cfg = config;
    window.CluKit.current = Kit;
    const bg = getComputedStyle(document.body).backgroundColor;
    const want = config.design && config.design.bg;
    if (want) {
      const n = parseInt(want.slice(1), 16), rgb = `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
      Kit.check("theme applied", bg === rgb, `${bg} (expected ${rgb})`);
    }
    Kit.token = await session();
    if (!Kit.check("CLU session", !!Kit.token, Kit.token ? "" : "no session: open this from CLU")) { Kit.status("Not connected to CLU", "warn"); finish(); return; }
    try { await mount(Kit); Kit.check("mounted", true); }
    catch (e) { Kit.check("mounted", false, e && e.message); Kit.status("This experience failed to start: " + (e && e.message), "warn"); }
    if (Kit.selftest) {
      if (window.CluProbe) Kit.tests.push({ name: "requirement checks", fn: () => window.CluProbe.run(Kit) });
      for (const t of Kit.tests) {
        try { await Promise.race([t.fn(), Kit.sleep(15000).then(() => { throw new Error("timed out"); })]); }
        catch (e) { Kit.check(t.name, false, e && e.message); }
      }
    }
    finish();
  };

  window.CluKit = Kit;
})();
