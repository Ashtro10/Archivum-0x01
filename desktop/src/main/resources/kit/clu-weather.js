/*
 * CLU weather client (shared by every weather experience and dashboards): place search, current conditions and
 * forecasts from CLU's weather service (Open-Meteo through CLU). It never shows a number it did not receive: when the
 * service cannot be reached it says so, and data that could not be refreshed is labelled with its age.
 */
(function () {
  "use strict";
  const WMO = { 0: "Clear", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Rime fog", 51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle",
    56: "Freezing drizzle", 57: "Freezing drizzle", 61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain", 71: "Light snow", 73: "Snow",
    75: "Heavy snow", 77: "Snow grains", 80: "Showers", 81: "Showers", 82: "Violent showers", 85: "Snow showers", 86: "Snow showers", 95: "Thunderstorm", 96: "Thunderstorm, hail", 99: "Thunderstorm, hail" };
  const describe = (c) => WMO[c] || (c == null ? "" : "Code " + c);

  async function mount(kit, cfg) {
    const f = new Set(cfg.features), L = cfg.labels || {};
    const saved = await kit.config.get();
    let place = saved.place || null, unit = saved.unit === "F" ? "F" : "C", last = null, places = Array.isArray(saved.places) ? saved.places.slice(0, 12) : [];
    const api = { onPlace: null };
    const temp = (c) => c == null ? "—" : unit === "F" ? Math.round(c * 9 / 5 + 32) + "°F" : Math.round(c) + "°C";
    const time = (iso) => { try { return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); } catch (e) { return iso; } };
    const day = (iso) => { try { return new Date(iso + "T12:00:00").toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" }); } catch (e) { return iso; } };

    function clear(msg, warn) {
      kit.$("wxTemp").textContent = "—"; kit.$("wxDesc").textContent = ""; kit.$("wxFacts").replaceChildren();
      ["wxHourly", "wxDaily", "wxSun"].forEach((id) => { const e = kit.$(id); if (e) e.replaceChildren(); });
      const n = kit.$("wxNote"); n.textContent = msg; n.className = "note" + (warn ? " warn" : "");
    }
    function facts(el, rows) { el.replaceChildren(...rows.filter((r) => r[1] != null && r[1] !== "").flatMap(([k, v]) => [kit.el("dt", null, k), kit.el("dd", null, v)])); }
    function render() {
      const w = last; if (!w) return;
      kit.$("wxPlace").textContent = place ? `${place.name}${place.country ? ", " + place.country : ""}` : "—";
      if (!w.ok) {
        clear(`Live weather is unavailable: ${w.reason}. Nothing here is guessed; try again when CLU can reach ${w.provider || "the weather service"}.`, true);
        kit.status("Weather unavailable", "warn"); kit.source(""); return;
      }
      const c = w.current;
      kit.$("wxTemp").textContent = temp(c.temperature); kit.$("wxTemp").dataset.celsius = c.temperature;
      kit.$("wxDesc").textContent = describe(c.code);
      facts(kit.$("wxFacts"), [["Feels like", c.apparent == null ? null : temp(c.apparent)], ["Humidity", c.humidity == null ? null : c.humidity + "%"],
        ["Wind", c.wind == null ? null : Math.round(c.wind) + " km/h" + (f.has("wind") && c.windDirection != null ? " from " + Math.round(c.windDirection) + "°" : "")],
        f.has("wind") ? ["Gusts", c.gusts == null ? null : Math.round(c.gusts) + " km/h"] : ["", null], ["Precipitation", c.precipitation == null ? null : c.precipitation + " mm"]]);
      const n = kit.$("wxNote");
      const age = Math.round((Date.now() - w.fetchedAt) / 60000);
      n.textContent = w.stale ? `Showing data from ${age} min ago: CLU couldn't refresh it (${w.reason}).` : `Updated ${time(w.observedAt)} local time at ${place.name}.`;
      n.className = "note" + (w.stale ? " warn" : "");
      kit.status(w.stale ? "Stale data" : "Live data", w.stale ? "warn" : "ok");
      kit.source("Weather: Open-Meteo.com (CC BY 4.0)");
      const daily = kit.$("wxDaily");
      if (daily) daily.replaceChildren(...(w.daily || []).map((d) => { const li = kit.el("li"); li.append(kit.el("div", "t", day(d.date)), kit.el("div", "v", `${temp(d.max)} / ${temp(d.min)}`),
        kit.el("div", "t", describe(d.code) + (d.precipitationChance != null ? ` · ${d.precipitationChance}%` : ""))); return li; }));
      const hourly = kit.$("wxHourly");
      if (hourly) hourly.replaceChildren(...(w.hourly || []).slice(0, 24).map((h) => { const li = kit.el("li"); li.append(kit.el("div", "t", time(h.time)), kit.el("div", "v", temp(h.temperature)),
        kit.el("div", "t", h.precipitationChance != null ? h.precipitationChance + "%" : "")); return li; }));
      const sun = kit.$("wxSun");
      if (sun && w.daily && w.daily[0]) facts(sun, [["Sunrise", w.daily[0].sunrise ? time(w.daily[0].sunrise) : null], ["Sunset", w.daily[0].sunset ? time(w.daily[0].sunset) : null]]);
    }
    async function load() {
      if (!place) { clear("Choose a place to see its weather."); kit.status("No place chosen"); return null; }
      kit.status("Loading…"); kit.$("wxNote").textContent = "Asking the weather service…"; kit.$("wxNote").className = "note";
      try { last = await kit.api(`/v1/kit/weather?lat=${place.lat}&lon=${place.lon}&name=${encodeURIComponent(place.name)}`); }
      catch (e) { last = { ok: false, reason: e.message }; }
      render(); return last;
    }
    async function choose(p, quiet) {
      place = { name: p.name, country: p.country || "", lat: p.lat, lon: p.lon };
      await kit.config.set({ place }); if (api.onPlace && !quiet) api.onPlace(place);
      return load();
    }
    function renderPlaces() {
      const ul = kit.$("wxPlaces"); if (!ul) return;
      ul.replaceChildren(...places.map((p) => { const li = kit.el("li", null, p.name); li.addEventListener("click", () => choose(p)); return li; }));
      if (place && !places.some((p) => p.name === place.name)) { const add = kit.el("li", null, "+ " + place.name); add.title = "Save this place";
        add.addEventListener("click", () => { places.push(place); kit.config.set({ places }); renderPlaces(); }); ul.append(add); }
    }

    kit.$("wxSearch").addEventListener("submit", async (e) => {
      e.preventDefault(); const q = kit.$("wxQuery").value.trim(); if (!q) return;
      const ul = kit.$("wxResults"); ul.replaceChildren(kit.el("li", "note", "Searching…"));
      const list = await kit.places(q).catch((x) => { ul.replaceChildren(kit.el("li", "note warn", x.message)); return null; }); if (!list) return;
      ul.replaceChildren(...(list.length ? list.map((p) => { const li = kit.el("li", null, `${p.name}, ${p.country}`);
        li.addEventListener("click", async () => { ul.replaceChildren(); await choose(p); renderPlaces(); }); return li; })
        : [kit.el("li", "note", "No place by that name in CLU's offline gazetteer.")]));
    });
    if (f.has("units")) {
      const sync = () => { kit.$("wxC").classList.toggle("on", unit === "C"); kit.$("wxF").classList.toggle("on", unit === "F"); };
      kit.$("wxC").addEventListener("click", () => { unit = "C"; kit.config.set({ unit }); sync(); render(); });
      kit.$("wxF").addEventListener("click", () => { unit = "F"; kit.config.set({ unit }); sync(); render(); });
      sync();
    }

    if (!place) { try { const h = await kit.api("/v1/kit/home"); if (h && h.place) place = h.place; } catch (e) {} }
    // The preview CLU takes while verifying shows a real place (data, or the honest "unavailable" state), never an empty screen.
    if (!place && kit.preview) { const p = (await kit.places("London").catch(() => []))[0]; if (p) place = p; }
    await load(); renderPlaces();

    // ---- self-tests (run by CLU's verifier in a real browser)
    kit.test("weather: place search", async () => {
      kit.$("wxQuery").value = "London"; kit.$("wxSearch").dispatchEvent(new Event("submit", { cancelable: true }));
      for (let i = 0; i < 50 && !kit.$("wxResults").querySelector("li:not(.note)"); i++) await kit.sleep(100);
      const items = [...kit.$("wxResults").querySelectorAll("li:not(.note)")].map((li) => li.textContent);
      kit.check("weather: search finds London", items.some((t) => /London, GB/.test(t)), items.slice(0, 3).join("; "));
      const first = [...kit.$("wxResults").querySelectorAll("li:not(.note)")].find((li) => /London, GB/.test(li.textContent));
      if (first) { first.click(); for (let i = 0; i < 100 && kit.$("wxNote").textContent === "Asking the weather service…"; i++) await kit.sleep(100); await kit.sleep(100); }
    });
    kit.test("weather: data flow", async () => {
      const w = last;
      kit.check("weather: the chosen place is shown", /London/.test(kit.$("wxPlace").textContent), kit.$("wxPlace").textContent);
      if (!w || !w.ok) {
        kit.unverified.push("live weather data: " + ((w && w.reason) || "no answer"));
        kit.check("weather: unavailable is said plainly and no figures are invented", !/\d/.test(kit.$("wxTemp").textContent + kit.$("wxDesc").textContent) && /unavailable/i.test(kit.$("wxNote").textContent),
          kit.$("wxNote").textContent);
        return;
      }
      const shown = kit.$("wxTemp").textContent, expected = temp(w.current.temperature);
      kit.check("weather: the temperature shown is the one the service returned", shown === expected, `${shown} vs ${expected}`);
      if (f.has("daily")) kit.check("weather: forecast days shown", kit.$("wxDaily").children.length >= 3 && kit.$("wxDaily").children.length === (w.daily || []).length, kit.$("wxDaily").children.length + " days");
      if (f.has("hourly")) kit.check("weather: 24-hour forecast shown", kit.$("wxHourly").children.length >= 12, kit.$("wxHourly").children.length + " hours");
      if (f.has("sun")) kit.check("weather: sunrise and sunset shown", kit.$("wxSun").querySelectorAll("dd").length === 2, kit.$("wxSun").textContent);
      if (f.has("units")) { kit.$("wxF").click(); const fah = kit.$("wxTemp").textContent; kit.$("wxC").click();
        kit.check("weather: °F switch converts", fah === Math.round(w.current.temperature * 9 / 5 + 32) + "°F", fah); }
      kit.check("weather: freshness is stated", /Updated|ago/.test(kit.$("wxNote").textContent), kit.$("wxNote").textContent);
    });

    Object.assign(api, { choose, place: () => place, reload: load });
    return api;
  }

  window.CluWeather = { mount, describe };
})();
