package ai.clu.desktop.capability

import ai.clu.core.evolution.DevModel
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The look of one experience: palette, type and decoration. Every colour is `#rrggbb`. */
@Serializable data class DesignSpec(
    val bg: String, val panel: String, val ink: String, val dim: String, val accent: String, val accent2: String, val warn: String,
    val land: String, val water: String, val border: String,
    /** `mono`, `serif`, `sans` or `display`. */
    val font: String,
    /** `grid`, `parchment`, `scanlines`, `dots` or `none`. */
    val texture: String,
    val corner: Int, val glow: Boolean, val upper: Boolean,
)

/** What to build: family, style, name, the features it must have, its look, and the capabilities it builds on. */
@Serializable data class Blueprint(
    val kind: String, val style: String, val name: String, val tagline: String, val features: List<String>, val design: DesignSpec,
    val uses: List<String> = emptyList(), val labels: Map<String, String> = emptyMap(),
)

/** The families CLU can build, what each needs, and the improvements it knows how to add. */
object ExperienceCatalog {
    data class Family(val kind: String, val title: String, val category: String, val base: List<String>, val improvements: List<Pair<String, Regex>>,
                      val services: List<String>, val requires: List<String> = emptyList(), val description: String)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    val families = listOf(
        Family("weather", "Weather", "information", listOf("current", "daily", "search", "status"),
            listOf("hourly" to r("hour"), "units" to r("fahrenheit|celsius|unit|imperial|metric"), "sun" to r("sun(?:rise|set)?|daylight"),
                "places" to r("saved|favou?rite|places|locations|cities"), "wind" to r("wind|gust|breez")),
            listOf("weather (Open-Meteo, free, no key)", "places (offline gazetteer, OpenStreetMap Nominatim online)"),
            description = "Current conditions and forecast for a place you choose, from Open-Meteo."),
        Family("map", "Map", "navigation", listOf("world", "pan", "zoom", "search", "coords", "pins", "tiles"),
            listOf("measure" to r("measur|distance|ruler|range"), "graticule" to r("graticule|grid|latitude|longitude"),
                "savedpins" to r("save|remember|keep|pins?|markers?|waypoints?"), "minimap" to r("mini\\s?map|overview|inset")),
            listOf("world geography (offline Natural Earth data)", "map tiles (OpenStreetMap, online)", "places (offline gazetteer)"),
            description = "An interactive world map: pan, zoom, find places, drop pins; street tiles when online."),
        Family("dashboard", "Dashboard", "overview", listOf("weatherPanel", "mapPanel", "linked"),
            listOf("hourly" to r("hour"), "pins" to r("pins?|markers?")), listOf("weather capability", "map capability"), requires = listOf("weather", "map"),
            description = "One screen that combines your weather and map capabilities for one place."),
    )
    fun family(kind: String?) = families.firstOrNull { it.kind == kind }

    /** Improvements for [request] (what was asked), else the next ones this family does not have yet. */
    fun improvements(kind: String, have: List<String>, request: String): List<String> {
        val f = family(kind) ?: return emptyList()
        val asked = f.improvements.filter { (_, re) -> re.containsMatchIn(request) }.map { it.first }.filter { it !in have }
        return asked.ifEmpty { f.improvements.map { it.first }.filter { it !in have }.take(2) }
    }

    val FEATURE_TEXT = mapOf("current" to "current conditions", "daily" to "multi-day forecast", "search" to "place search", "status" to "honest loading/offline/stale states",
        "hourly" to "24-hour forecast", "units" to "°C/°F switch", "sun" to "sunrise and sunset", "places" to "saved places", "wind" to "wind detail",
        "world" to "offline world geography", "pan" to "drag to pan", "zoom" to "zoom", "coords" to "coordinate readout", "pins" to "pins", "tiles" to "street tiles when online",
        "measure" to "distance measuring", "graticule" to "latitude/longitude lines", "savedpins" to "pins kept between sessions", "minimap" to "overview inset",
        "weatherPanel" to "weather panel", "mapPanel" to "map panel", "linked" to "map and weather linked to one place")
}

/** Curated looks for the styles CLU knows; anything else is designed (model first, then a derived palette). */
object Styles {
    val known: Map<String, DesignSpec> = mapOf(
        "scifi" to DesignSpec("#04101c", "#0a1f33", "#d8f6ff", "#7fa9bd", "#3ee0ff", "#b26bff", "#ffb347", "#0f3550", "#04101c", "#2a7fa8", "display", "grid", 2, true, true),
        "tactical" to DesignSpec("#050a05", "#0b160b", "#c8f7c0", "#6f9a69", "#4dff6a", "#ffcf3d", "#ff5a4a", "#0f2a12", "#050a05", "#2f7a35", "mono", "scanlines", 0, true, true),
        "fantasy" to DesignSpec("#efe2c2", "#e6d3a8", "#3b2a17", "#7a6040", "#8b2e16", "#2f5d3a", "#a8321e", "#d9c28f", "#b9cdd0", "#6d5432", "serif", "parchment", 6, false, false),
        "classic" to DesignSpec("#f4f6f8", "#ffffff", "#1d2733", "#5e6b78", "#1f6feb", "#0f9d58", "#d93025", "#e9efe4", "#aad3df", "#9aa7b2", "sans", "none", 8, false, false),
        "retro" to DesignSpec("#1a0b2e", "#2a1145", "#ffe8fb", "#c79bd8", "#ff4fd8", "#3ee8ff", "#ffd23e", "#341a5c", "#120624", "#7a3fb0", "display", "scanlines", 4, true, true),
        "minimal" to DesignSpec("#fafafa", "#ffffff", "#222222", "#6b6b6b", "#111111", "#888888", "#c62828", "#ececec", "#f7f7f7", "#bdbdbd", "sans", "none", 4, false, false),
        "noir" to DesignSpec("#0b0b0b", "#151515", "#ededed", "#9a9a9a", "#ffffff", "#bdbdbd", "#ff5252", "#222222", "#0b0b0b", "#555555", "serif", "dots", 0, false, true),
    )
    val names = mapOf("scifi" to "Sci-Fi", "tactical" to "Tactical", "fantasy" to "Fantasy", "classic" to "Classic", "retro" to "Retro", "minimal" to "Minimal", "noir" to "Noir")
    fun title(style: String) = names[style] ?: style.split('-').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    // ---------------------------------------------------------------- colour checks (WCAG contrast) used to verify and fix designs
    fun rgb(hex: String): Triple<Double, Double, Double>? = Regex("^#([0-9a-fA-F]{6})$").find(hex)?.groupValues?.get(1)?.let {
        Triple(it.substring(0, 2).toInt(16) / 255.0, it.substring(2, 4).toInt(16) / 255.0, it.substring(4, 6).toInt(16) / 255.0) }
    private fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun luminance(hex: String) = rgb(hex)?.let { (r, g, b) -> 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b) } ?: 0.0
    fun contrast(a: String, b: String): Double { val la = luminance(a); val lb = luminance(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05) }
    fun valid(d: DesignSpec) = listOf(d.bg, d.panel, d.ink, d.dim, d.accent, d.accent2, d.warn, d.land, d.water, d.border).all { rgb(it) != null }

    /** Problems a reader would notice: text that is hard to read, accents that disappear. */
    fun problems(d: DesignSpec): List<String> = buildList {
        if (!valid(d)) add("a colour is not #rrggbb")
        else {
            if (contrast(d.ink, d.bg) < 4.5) add("text contrast %.1f:1 on the background (needs 4.5)".format(contrast(d.ink, d.bg)))
            if (contrast(d.ink, d.panel) < 4.5) add("text contrast %.1f:1 on panels (needs 4.5)".format(contrast(d.ink, d.panel)))
            if (contrast(d.accent, d.bg) < 3.0) add("accent contrast %.1f:1 (needs 3)".format(contrast(d.accent, d.bg)))
            if (contrast(d.dim, d.panel) < 3.0) add("secondary text contrast %.1f:1 (needs 3)".format(contrast(d.dim, d.panel)))
        }
    }

    private fun hsl(h: Double, s: Double, l: Double): String {
        val c = (1 - abs(2 * l - 1)) * s; val x = c * (1 - abs((h / 60) % 2 - 1)); val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt() % 6) { 0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x); 3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x) }
        return "#%02x%02x%02x".format(((r + m) * 255).toInt().coerceIn(0, 255), ((g + m) * 255).toInt().coerceIn(0, 255), ((b + m) * 255).toInt().coerceIn(0, 255))
    }

    /** Moves a colour's lightness away from [against] until it has [ratio] contrast with it (the fix applied when a check fails). */
    fun ensureContrast(fg: String, against: String, ratio: Double): String {
        if (rgb(fg) == null || rgb(against) == null || contrast(fg, against) >= ratio) return fg
        val dark = luminance(against) > 0.4
        var (r, g, b) = rgb(fg)!!
        repeat(40) {
            if (contrast("#%02x%02x%02x".format((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()), against) >= ratio) return@repeat
            if (dark) { r *= 0.9; g *= 0.9; b *= 0.9 } else { r += (1 - r) * 0.12; g += (1 - g) * 0.12; b += (1 - b) * 0.12 }
        }
        return "#%02x%02x%02x".format((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))
    }

    fun fixContrast(d: DesignSpec): DesignSpec {
        if (!valid(d)) return derived("fixed")
        val ink = ensureContrast(ensureContrast(d.ink, d.bg, 4.5), d.panel, 4.5)
        return d.copy(ink = ink, accent = ensureContrast(d.accent, d.bg, 3.0), dim = ensureContrast(d.dim, d.panel, 3.0))
    }

    /** A palette derived from the style's name (stable: the same words give the same look). */
    fun derived(style: String): DesignSpec {
        val h = (style.hashCode().toLong() and 0xffff) % 360 / 1.0
        val darkStyle = !Regex("(?i)light|paper|sun|beach|spring|pastel|snow|cloud").containsMatchIn(style)
        val bg = if (darkStyle) hsl(h, 0.45, 0.08) else hsl(h, 0.35, 0.95); val panel = if (darkStyle) hsl(h, 0.40, 0.14) else hsl(h, 0.30, 0.99)
        val ink = if (darkStyle) hsl(h, 0.30, 0.92) else hsl(h, 0.40, 0.14)
        val d = DesignSpec(bg, panel, ink, if (darkStyle) hsl(h, 0.20, 0.65) else hsl(h, 0.15, 0.38), hsl((h + 30) % 360, 0.85, if (darkStyle) 0.6 else 0.38),
            hsl((h + 180) % 360, 0.7, if (darkStyle) 0.6 else 0.4), hsl(20.0, 0.9, 0.55), if (darkStyle) hsl(h, 0.35, 0.2) else hsl(h, 0.25, 0.86),
            if (darkStyle) hsl(h, 0.45, 0.08) else hsl((h + 200) % 360, 0.45, 0.82), hsl(h, 0.35, if (darkStyle) 0.4 else 0.6), "sans", if (darkStyle) "grid" else "none", 6, darkStyle, false)
        return fixContrast(d)
    }
}

/**
 * Asks the development model for a name, a one-line description and (for a style CLU has no curated look for) a
 * palette, as JSON constrained by a grammar. The answer is validated; anything unusable is discarded and recorded,
 * and a curated or derived design is used instead. The model never writes code here.
 */
class DesignAdvisor(private val model: DevModel?) {
    data class Advice(val name: String?, val tagline: String?, val design: DesignSpec?, val raw: String?, val accepted: List<String>, val rejected: List<String>, val ms: Long)

    fun prompt(kind: String, style: String, features: List<String>) =
        "Design a $style themed $kind screen for CLU, a personal assistant app. Features: ${features.joinToString(", ") { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}.\n" +
            "Reply with JSON only: {\"name\": short product name (2-4 words), \"tagline\": one sentence under 90 characters, " +
            "\"bg\": \"#rrggbb\", \"panel\": \"#rrggbb\", \"ink\": \"#rrggbb\", \"accent\": \"#rrggbb\", \"font\": \"mono\"|\"serif\"|\"sans\"|\"display\"}."

    /** A name is useful only if it is new and says more than "CLU" and the family (a small model often answers just "CLU"). */
    fun usableName(name: String, kind: String, taken: Collection<String>): String? {
        if (taken.any { it.equals(name, ignoreCase = true) }) return "already used by another capability"
        val generic = setOf("clu", "the", "my", "app", "application", "service", "interface", "ui", "screen", "hud", "experience", "viewer", kind, "weather", "map", "maps", "dashboard", "forecast")
        if (name.lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }.all { it in generic }) return "too generic to tell experiences apart"
        return null
    }

    suspend fun advise(kind: String, style: String, features: List<String>, wantPalette: Boolean, taken: Collection<String> = emptyList()): Advice {
        val m = model ?: return Advice(null, null, null, null, emptyList(), listOf("no development model available"), 0)
        val t0 = System.currentTimeMillis()
        val raw = runCatching { m.complete("You are a product designer. Answer with one JSON object and nothing else.", prompt(kind, style, features), 220, 0.4f, 7, GRAMMAR, false) }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        if (raw == null) return Advice(null, null, null, null, emptyList(), listOf("the model gave no answer${m.lastError?.let { " ($it)" } ?: ""}"), ms)
        val o = runCatching { CluJson.parseToJsonElement(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
            ?: return Advice(null, null, null, raw, emptyList(), listOf("the answer was not JSON"), ms)
        val accepted = mutableListOf<String>(); val rejected = mutableListOf<String>()
        val proposed = o["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val shapeOk = proposed != null && Regex("^[\\p{L}0-9][\\p{L}0-9 '&:.-]{1,38}$").matches(proposed) && proposed.split(' ').size <= 5
        val why = if (!shapeOk) "not a short name" else usableName(proposed!!, kind, taken)
        val name = proposed.takeIf { why == null }
        if (name != null) accepted += "name" else rejected += "name \"${proposed?.take(60) ?: ""}\" ($why)"
        val tagline = o["tagline"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length in 8..120 && !it.contains('<') && !it.contains("http") }
        if (tagline != null) accepted += "tagline" else rejected += "tagline"
        var design: DesignSpec? = null
        if (wantPalette) {
            val c = listOf("bg", "panel", "ink", "accent").associateWith { o[it]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() }
            if (c.values.all { it != null && Styles.rgb(it) != null }) {
                val base = Styles.derived(style)
                val proposed = base.copy(bg = c["bg"]!!, panel = c["panel"]!!, ink = c["ink"]!!, accent = c["accent"]!!,
                    font = o["font"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("mono", "serif", "sans", "display") } ?: base.font)
                design = proposed; accepted += "palette"
            } else rejected += "palette (not four #rrggbb colours)"
        }
        return Advice(name, tagline, design, raw.take(600), accepted, rejected, ms)
    }

    companion object {
        /** GBNF: one flat JSON object with the five fields (hex colours constrained). */
        const val GRAMMAR = """root ::= "{" ws "\"name\":" ws str "," ws "\"tagline\":" ws str "," ws "\"bg\":" ws hex "," ws "\"panel\":" ws hex "," ws "\"ink\":" ws hex "," ws "\"accent\":" ws hex "," ws "\"font\":" ws font ws "}"
str ::= "\"" [^"\\\n]{1,90} "\""
hex ::= "\"#" [0-9a-f] [0-9a-f] [0-9a-f] [0-9a-f] [0-9a-f] [0-9a-f] "\""
font ::= "\"mono\"" | "\"serif\"" | "\"sans\"" | "\"display\""
ws ::= [ ]?"""
    }
}
