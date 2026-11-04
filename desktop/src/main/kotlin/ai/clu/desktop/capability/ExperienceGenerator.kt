package ai.clu.desktop.capability

import ai.clu.core.util.CluJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/**
 * Writes the source of one experience version: `manifest.json`, `index.html` (the layout for its family and
 * features), `theme.css` (its look) and `experience.js` (its configuration and wiring). The pages run on CLU's shared
 * experience kit (`/kit/…`: runtime, map engine, weather client), so a new map experience reuses the map engine
 * instead of re-implementing it. Pages follow the shell's Content-Security-Policy: no inline script or style.
 */
object ExperienceGenerator {
    const val KIT_VERSION = "1"

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Words shown in the experience, in the voice of its style. */
    fun labels(kind: String, style: String): Map<String, String> {
        val loud = style in setOf("scifi", "tactical", "retro")
        val w = when (style) {
            "scifi" -> mapOf("now" to "Atmospheric scan", "daily" to "Long-range forecast", "hourly" to "Next 24 cycles", "search" to "Target location", "go" to "Scan", "places" to "Saved sectors", "sun" to "Stellar cycle",
                "pins" to "Beacons", "find" to "Locate", "tiles" to "Surface imagery", "measure" to "Range finder")
            "tactical" -> mapOf("now" to "WX intel", "daily" to "Outlook", "hourly" to "Next 24 hrs", "search" to "Grid search", "go" to "Acquire", "places" to "Rally points", "sun" to "Light conditions",
                "pins" to "Waypoints", "find" to "Acquire", "tiles" to "Recon imagery", "measure" to "Range")
            "fantasy" -> mapOf("now" to "The skies today", "daily" to "Omens of the coming days", "hourly" to "The turning hours", "search" to "Seek a place", "go" to "Seek", "places" to "Favoured lands", "sun" to "Dawn and dusk",
                "pins" to "Marks upon the map", "find" to "Seek", "tiles" to "Street charts", "measure" to "Leagues between")
            else -> mapOf("now" to "Now", "daily" to "Next days", "hourly" to "Next 24 hours", "search" to "Find a place", "go" to "Search", "places" to "Saved places", "sun" to "Sunrise and sunset",
                "pins" to "Pins", "find" to "Find", "tiles" to "Street tiles", "measure" to "Measure")
        }
        return if (loud) w.mapValues { it.value.uppercase() } else w
    }

    fun write(dir: File, capId: String, version: Int, bp: Blueprint): List<String> {
        dir.mkdirs()
        val files = linkedMapOf(
            "manifest.json" to manifest(capId, version, bp),
            "index.html" to html(capId, version, bp),
            "theme.css" to css(bp),
            "experience.js" to js(capId, version, bp),
        )
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        return files.keys.toList()
    }

    fun manifest(capId: String, version: Int, bp: Blueprint): String = CluJson.encodeToString(buildJsonObject {
        put("id", capId); put("version", version); put("kind", bp.kind); put("style", bp.style); put("name", bp.name); put("tagline", bp.tagline)
        put("entry", "index.html"); put("kit", KIT_VERSION)
        putJsonArray("features") { bp.features.forEach { add(it) } }
        putJsonArray("uses") { bp.uses.forEach { add(it) } }
        putJsonArray("services") { ExperienceCatalog.family(bp.kind)?.services?.forEach { add(it) } }
    })

    private fun scripts(kind: String) = when (kind) {
        "weather" -> listOf("clu-weather.js")
        "map" -> listOf("clu-map.js")
        else -> listOf("clu-map.js", "clu-weather.js")
    }

    fun html(capId: String, version: Int, bp: Blueprint): String {
        val l = bp.labels; val f = bp.features.toSet()
        val body = StringBuilder()
        fun weatherPanels(prefix: String = "") {
            body.append("""
    <section class="panel wx-search" aria-label="${esc(l["search"] ?: "Find a place")}">
      <h2>${esc(l["search"] ?: "")}</h2>
      <form id="wxSearch" class="search"><input id="wxQuery" type="search" autocomplete="off" placeholder="City" aria-label="City"><button type="submit">${esc(l["go"] ?: "Search")}</button></form>
      <ul id="wxResults" class="results"></ul>
      ${if ("places" in f) "<h3>${esc(l["places"] ?: "")}</h3><ul id=\"wxPlaces\" class=\"chips\"></ul>" else ""}
    </section>
    <section class="panel wx-now" aria-live="polite">
      <h2>${esc(l["now"] ?: "Now")}</h2>
      <div id="wxPlace" class="wx-place">—</div>
      <div class="wx-main"><span id="wxTemp" class="wx-temp">—</span><span id="wxDesc" class="wx-desc"></span></div>
      <dl id="wxFacts" class="facts"></dl>
      ${if ("units" in f) "<div class=\"units\" role=\"group\" aria-label=\"Units\"><button type=\"button\" id=\"wxC\" class=\"on\">°C</button><button type=\"button\" id=\"wxF\">°F</button></div>" else ""}
      <p id="wxNote" class="note"></p>
    </section>""")
            if ("hourly" in f) body.append("""
    <section class="panel wx-hourly"><h2>${esc(l["hourly"] ?: "")}</h2><ol id="wxHourly" class="strip"></ol></section>""")
            if ("daily" in f) body.append("""
    <section class="panel wx-daily"><h2>${esc(l["daily"] ?: "")}</h2><ol id="wxDaily" class="days"></ol></section>""")
            if ("sun" in f) body.append("""
    <section class="panel wx-sun"><h2>${esc(l["sun"] ?: "")}</h2><dl id="wxSun" class="facts"></dl></section>""")
        }
        fun mapPanel() {
            body.append("""
    <section class="panel map-panel">
      <div class="map-wrap" id="mapWrap">
        <canvas id="mapCanvas" aria-label="Interactive map: drag to pan, scroll or use + and − to zoom${if ("pins" in f) ", click to drop a pin" else ""}"></canvas>
        <div class="map-hud"><span id="mapCoords">—</span><span id="mapZoom"></span><span id="mapLayer"></span></div>
        <div class="map-controls"><button type="button" id="mapIn" aria-label="Zoom in">+</button><button type="button" id="mapOut" aria-label="Zoom out">−</button></div>
      </div>
    </section>
    <aside class="panel map-side">
      <h2>${esc(l["find"] ?: "Find")}</h2>
      <form id="mapSearch" class="search"><input id="mapQuery" type="search" autocomplete="off" placeholder="City" aria-label="City"><button type="submit">${esc(l["find"] ?: "Find")}</button></form>
      <ul id="mapResults" class="results"></ul>
      ${if ("tiles" in f) "<label class=\"toggle\"><input type=\"checkbox\" id=\"mapTiles\"> ${esc(l["tiles"] ?: "Street tiles")}</label><p id=\"mapTilesNote\" class=\"note\"></p>" else ""}
      ${if ("measure" in f) "<label class=\"toggle\"><input type=\"checkbox\" id=\"mapMeasure\"> ${esc(l["measure"] ?: "Measure")}</label><p id=\"mapMeasureOut\" class=\"note\"></p>" else ""}
      ${if ("pins" in f) "<h3>${esc(l["pins"] ?: "Pins")}</h3><ul id=\"mapPins\" class=\"results\"></ul>" else ""}
    </aside>""")
        }
        when (bp.kind) {
            "weather" -> weatherPanels()
            "map" -> mapPanel()
            else -> { mapPanel(); weatherPanels() }
        }
        val styleClass = "style-" + bp.style.replace(Regex("[^a-z0-9-]"), "")
        return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(bp.name)}</title>
  <link rel="stylesheet" href="/kit/clu-kit.css">
  <link rel="stylesheet" href="theme.css">
</head>
<body class="clu-exp kind-${bp.kind} $styleClass texture-${bp.design.texture}${if (bp.design.glow) " glow" else ""}${if (bp.design.upper) " upper" else ""}">
  <header class="exp-head">
    <div class="exp-title"><span class="exp-mark" aria-hidden="true"></span><h1 id="expName">${esc(bp.name)}</h1></div>
    <p class="exp-tagline">${esc(bp.tagline)}</p>
    <div id="expStatus" class="exp-status" role="status"></div>
  </header>
  <main class="exp-body layout-${bp.kind}">$body
  </main>
  <footer class="exp-foot"><span id="expSource"></span><span class="spacer"></span><span>${esc(capId)} · v$version</span></footer>
  <pre id="clu-selfcheck" hidden></pre>
${(listOf("/kit/clu-kit.js") + scripts(bp.kind).map { "/kit/$it" } + "experience.js").joinToString("\n") { "  <script src=\"$it\"></script>" }}
</body>
</html>
"""
    }

    fun css(bp: Blueprint): String {
        val d = bp.design
        val font = when (d.font) {
            "mono" -> "\"Cascadia Mono\", \"JetBrains Mono\", Consolas, \"DejaVu Sans Mono\", monospace"
            "serif" -> "\"Palatino Linotype\", Palatino, \"Book Antiqua\", Georgia, \"DejaVu Serif\", serif"
            "display" -> "\"Bahnschrift\", \"Segoe UI\", \"Eurostile\", \"DejaVu Sans\", sans-serif"
            else -> "\"Segoe UI\", system-ui, -apple-system, \"DejaVu Sans\", sans-serif"
        }
        val texture = when (d.texture) {
            "grid" -> "background-image: linear-gradient(${d.border}22 1px, transparent 1px), linear-gradient(90deg, ${d.border}22 1px, transparent 1px); background-size: 32px 32px;"
            "scanlines" -> "background-image: repeating-linear-gradient(0deg, ${d.accent}0d 0px, ${d.accent}0d 1px, transparent 1px, transparent 3px);"
            "parchment" -> "background-image: radial-gradient(ellipse at 20% 10%, #ffffff55, transparent 60%), radial-gradient(ellipse at 80% 90%, ${d.dim}33, transparent 55%), radial-gradient(circle at 50% 50%, transparent 60%, ${d.dim}44 100%);"
            "dots" -> "background-image: radial-gradient(${d.border}55 1px, transparent 1px); background-size: 14px 14px;"
            else -> ""
        }
        val glow = if (d.glow) """
.glow h1, .glow h2, .glow .wx-temp { text-shadow: 0 0 10px var(--accent), 0 0 2px var(--accent); }
.glow .panel { box-shadow: 0 0 0 1px var(--border), 0 0 18px ${d.accent}22 inset; }""" else ""
        return """/* ${bp.name}: generated by CLU for the ${bp.style} ${bp.kind} experience. */
:root {
  --bg: ${d.bg}; --panel: ${d.panel}; --ink: ${d.ink}; --dim: ${d.dim}; --accent: ${d.accent}; --accent2: ${d.accent2}; --warn: ${d.warn};
  --land: ${d.land}; --water: ${d.water}; --border: ${d.border}; --corner: ${d.corner}px;
  --font: $font;
}
body.clu-exp { background-color: var(--bg); color: var(--ink); font-family: var(--font); $texture }
.upper h1, .upper h2, .upper h3, .upper .exp-status, .upper .map-hud { text-transform: uppercase; letter-spacing: 0.08em; }$glow
""" + when (bp.style) {
            "tactical" -> """
.style-tactical .exp-mark { border-radius: 0; transform: none; border: 2px solid var(--accent); background: transparent; }
.style-tactical .panel { border-style: solid; border-width: 1px; }
.style-tactical .panel::before { content: ""; position: absolute; inset: 4px; border: 1px dashed ${d.accent}33; pointer-events: none; }
"""
            "fantasy" -> """
.style-fantasy h1 { font-variant: small-caps; letter-spacing: 0.04em; }
.style-fantasy .panel { border: 2px double var(--border); box-shadow: 0 2px 10px ${d.dim}55; }
.style-fantasy .exp-mark { background: var(--accent); border-radius: 50%; }
"""
            "scifi" -> """
.style-scifi .panel { clip-path: polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 14px 100%, 0 calc(100% - 14px)); }
.style-scifi .exp-mark { background: linear-gradient(135deg, var(--accent), var(--accent2)); }
"""
            else -> ""
        }
    }

    fun js(capId: String, version: Int, bp: Blueprint): String {
        val cfg = buildJsonObject {
            put("capId", capId); put("version", version); put("kind", bp.kind); put("style", bp.style); put("name", bp.name)
            putJsonArray("features") { bp.features.forEach { add(it) } }
            putJsonArray("uses") { bp.uses.forEach { add(it) } }
            put("labels", JsonObject(bp.labels.mapValues { JsonPrimitive(it.value) }))
            put("design", CluJson.encodeToJsonElement(DesignSpec.serializer(), bp.design))
        }
        val mount = when (bp.kind) {
            "weather" -> "return CluWeather.mount(kit, config);"
            "map" -> "return CluMap.mount(kit, config);"
            else -> "const map = await CluMap.mount(kit, config);\n    const wx = await CluWeather.mount(kit, config);\n    return CluKit.link(kit, map, wx);"
        }
        return """/* ${bp.name} v$version: generated by CLU. Configuration and wiring for the shared experience kit. */
(function () {
  "use strict";
  const config = $cfg;
  CluKit.start(config, async function (kit) {
    $mount
  });
})();
"""
    }
}
