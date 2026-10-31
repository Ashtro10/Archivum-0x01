package ai.clu.core.search

import ai.clu.core.tools.HttpFetcher
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** One current natural-hazard event as an official alert service reports it (nothing here is written by a model). */
@Serializable
data class HazardEvent(
    val type: String,            // TC (tropical cyclone), EQ, FL, VO, WF, DR (GDACS event types)
    val name: String,            // e.g. "Tropical Cyclone MOCHA-23"
    val lat: Double, val lon: Double,
    val alert: String = "",      // Green | Orange | Red
    val severity: String = "",   // e.g. "Tropical Cyclone (maximum wind speed of 213 km/h)"
    val countries: String = "",
    val source: String = "",     // who produced the underlying data (e.g. JTWC)
    val updated: String = "",    // as reported (UTC)
    val url: String = "",
)

/** What a hazard check found: the events, when, and from whom (for the reply's attribution and later follow-ups). */
@Serializable
data class HazardCheck(val type: String, val events: List<HazardEvent>, val checkedAt: Long, val provider: String = "GDACS", val error: String? = null)

/**
 * Current natural hazards from GDACS (Global Disaster Alert and Coordination System, a joint UN–European Commission
 * service): the global list of active events, so no location is ever sent; distances are computed on the device.
 * Used instead of general web search for current emergencies, where an encyclopedia is not evidence.
 * API: https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH (GeoJSON FeatureCollection).
 */
class Gdacs(private val http: HttpFetcher, private val base: String = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH") {
    suspend fun current(type: String, now: Long): HazardCheck {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val url = "$base?eventlist=$type&fromDate=${day.format(java.util.Date(now - 14 * 86_400_000L))}&toDate=${day.format(java.util.Date(now))}&alertlevel=Green;Orange;Red"
        val r = try { http.get(url, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)) } catch (e: Exception) {
            return HazardCheck(type, emptyList(), now, error = "GDACS could not be reached (${e.message ?: e::class.simpleName})")
        }
        // GDACS answers "no events" for an empty period with an empty body or 204/404.
        if (r.code == 204 || (r.code == 404 && r.body.isBlank())) return HazardCheck(type, emptyList(), now)
        if (r.code != 200) return HazardCheck(type, emptyList(), now, error = "GDACS returned HTTP ${r.code}")
        return runCatching { HazardCheck(type, parse(r.body, type), now) }.getOrElse { HazardCheck(type, emptyList(), now, error = "GDACS returned an unexpected response") }
    }

    companion object {
        const val USER_AGENT = "Clu/1.7 (personal assistant)"

        /** Events of [type] from a GDACS GeoJSON FeatureCollection; finished events ("iscurrent": false) are left out. */
        fun parse(body: String, type: String): List<HazardEvent> {
            if (body.isBlank()) return emptyList()
            val features = CluJson.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: return emptyList()
            return features.mapNotNull { f ->
                val o = f.jsonObject
                val p = o["properties"]?.jsonObject ?: return@mapNotNull null
                fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (s("eventtype").isNotEmpty() && !s("eventtype").equals(type, true)) return@mapNotNull null
                if (s("iscurrent").equals("false", true)) return@mapNotNull null
                val coords = (o["geometry"] as? JsonObject)?.get("coordinates")?.let { it as? JsonArray }
                val lon = coords?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val severity = (p["severitydata"] as? JsonObject)?.let { (it["severitytext"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val url = (p["url"] as? JsonObject)?.let { (it["report"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val name = s("description").ifEmpty { s("name") }.ifEmpty { s("eventname") }
                if (name.isEmpty()) return@mapNotNull null
                HazardEvent(type.uppercase(), WebContent.clean(name), lat, lon, s("alertlevel"), WebContent.clean(severity), WebContent.clean(s("country")),
                    s("source"), s("datemodified").ifEmpty { s("todate") }, url)
            }
        }
    }
}

/**
 * Turns a hazard check into Clu's answer, deterministically: names, positions, distances, winds and alert levels come
 * from the data, and what the data does not contain (forecast track, landfall time) is said to be missing, with the
 * official authority for that region named for warnings.
 */
object HazardAnswers {
    const val NEAR_KM = 1500.0

    private val WORDS = mapOf("TC" to "tropical cyclone", "EQ" to "earthquake", "FL" to "flood", "VO" to "volcanic eruption", "WF" to "wildfire", "DR" to "drought")
    fun noun(type: String) = WORDS[type] ?: "hazard"

    /** The official forecasting centre for a tropical cyclone at this position (WMO Regional Specialized Meteorological Centres). */
    fun authority(e: HazardEvent): String = when {
        e.type != "TC" -> "your national disaster-management authority"
        e.lat >= 0 && e.lon in 40.0..100.0 -> "the India Meteorological Department (IMD)"           // North Indian Ocean
        e.lat >= 0 && e.lon in 100.0..180.0 -> "the Japan Meteorological Agency"                   // western North Pacific
        e.lat >= 0 && e.lon in -180.0..-140.0 -> "the Central Pacific Hurricane Center (NOAA)"
        e.lat >= 0 && e.lon in -140.0..0.0 -> "the US National Hurricane Center (NOAA)"            // eastern Pacific, Atlantic
        e.lat < 0 && e.lon in 30.0..90.0 -> "Météo-France La Réunion"                              // south-west Indian Ocean
        e.lat < 0 && e.lon in 90.0..160.0 -> "the Australian Bureau of Meteorology"
        e.lat < 0 && (e.lon > 160.0 || e.lon < -120.0) -> "the Fiji Meteorological Service"         // South Pacific
        else -> "the national meteorological service"
    }

    fun alertMeaning(alert: String) = when (alert.lowercase()) {
        "red" -> "Red, GDACS's highest alert: a severe humanitarian impact is likely"
        "orange" -> "Orange: a significant impact is possible"
        "green" -> "Green: limited impact is expected, though local conditions can still be dangerous"
        else -> "not given"
    }

    private fun km(d: Double) = if (d < 100) "%.0f".format(d) else "%,d".format(((d / 10).toInt()) * 10)

    private fun position(e: HazardEvent) = "%.1f°%s %.1f°%s".format(java.util.Locale.ROOT, kotlin.math.abs(e.lat), if (e.lat >= 0) "N" else "S", kotlin.math.abs(e.lon), if (e.lon >= 0) "E" else "W")

    private fun relative(e: HazardEvent, home: Place?) = home?.let { "about ${km(Places.distanceKm(it.lat, it.lon, e.lat, e.lon))} km ${Places.direction(it.lat, it.lon, e.lat, e.lon)} of ${it.name}" }

    fun distance(e: HazardEvent, home: Place) = Places.distanceKm(home.lat, home.lon, e.lat, e.lon)

    /** The storm's own name ("DANA-24" from "Tropical Cyclone DANA-24"); other events keep their full name. */
    fun shortName(e: HazardEvent): String = e.name.substringAfterLast(' ').takeIf { Regex("^[\\p{L}][\\p{L}-]*-\\d{2}$").matches(it) } ?: e.name

    /** The event a message names by its own name ("is DANA dangerous?", "VAYU"), if exactly one matches. */
    fun named(text: String, events: List<HazardEvent>): HazardEvent? {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toSet()
        return events.filter { e -> shortName(e).lowercase().split('-', ' ').first().let { it.length >= 3 && it in words } }.singleOrNull()
    }

    private fun stamp(c: HazardCheck, e: HazardEvent?) = buildString {
        append("(GDACS")
        e?.source?.takeIf { it.isNotBlank() }?.let { append(", based on $it data") }
        e?.updated?.takeIf { it.isNotBlank() }?.let { append(", updated ${it.replace('T', ' ').take(16)} UTC") }
        append(".)")
    }

    /** The events relevant to [home] (nearest first), or all of them when the location is unknown. */
    fun relevant(c: HazardCheck, home: Place?): List<HazardEvent> =
        if (home == null) c.events else c.events.sortedBy { distance(it, home) }.filter { distance(it, home) <= NEAR_KM || (home.country.isNotEmpty() && it.countries.contains(home.country, true)) }

    fun describe(e: HazardEvent, home: Place?) = buildString {
        append(e.name)
        append(relative(e, home)?.let { " is $it, near ${position(e)}" } ?: " is near ${position(e)}")
        if (e.severity.isNotBlank()) append("; ${e.severity.replaceFirstChar { it.lowercase() }}")
        append(".")
        if (e.alert.isNotBlank()) append(" GDACS alert level: ${e.alert}.")
        if (e.countries.isNotBlank()) append(" Countries GDACS lists as affected: ${e.countries}.")
    }

    /** The answer to "is there a cyclone heading towards us?" and similar. */
    fun answer(c: HazardCheck, home: Place?): String {
        val n = noun(c.type)
        if (c.error != null) return "I couldn't reach GDACS, the official disaster-alert service, just now (${c.error.substringAfter("GDACS ").trimEnd('.')}), " +
            "so I can't tell you which ${n} is relevant to you. Please check your national weather service's current warnings."
        if (c.events.isEmpty()) return "GDACS lists no active ${n}s right now. ${stamp(c, null)}"
        val near = relevant(c, home)
        if (home != null && near.isEmpty()) {
            val nearest = c.events.minBy { distance(it, home) }
            return "No active $n is within about ${km(NEAR_KM)} km of ${home.name}, according to GDACS. The nearest active one is ${nearest.name}, " +
                "${relative(nearest, home)}. ${stamp(c, nearest)}"
        }
        val e = near.first()
        if (home != null && near.size > 1) return buildString {
            // Several near the owner: each one named with its own distance, so "which one?" can be answered.
            val shown = near.take(3)
            append("${WORDS_NUM.getOrElse(shown.size) { "${shown.size}" }.replaceFirstChar { it.uppercase() }} active ${n}s are within about ${km(NEAR_KM)} km of ${home.name}. ")
            shown.forEach { append(describe(it, home)).append(" ") }
            if (e.type == "TC") append("The data I have doesn't include their directions of movement or landfall times; " +
                shown.map { authority(it) }.distinct().joinToString(" and ") + " issue${if (shown.map { authority(it) }.distinct().size == 1) "s" else ""} the official forecasts and warnings. ")
            append(stamp(c, e))
        }
        return buildString {
            if (home != null) append("The active $n nearest to ${home.name}: ")
            append(describe(e, home))
            if (e.type == "TC") append(" The data I have doesn't include its direction of movement or a landfall time; ${authority(e)} issues the official forecasts and warnings for this area.")
            if (near.size > 1) append(" GDACS also lists ${near.drop(1).joinToString(" and ") { it.name }}.")
            append(" ").append(stamp(c, e))
        }
    }

    private val WORDS_NUM = listOf("no", "one", "two", "three")

    enum class Ask { LOCATION, ARRIVAL, DIRECTION, DANGER, MORE, COMPARE }

    /** Follow-ups answered from what was already found (no new request unless it is stale). */
    fun followUp(ask: Ask, c: HazardCheck, home: Place?, previous: HazardCheck?, focus: String? = null): String {
        val e = focus?.let { f -> c.events.firstOrNull { it.name.equals(f, true) || shortName(it).equals(f, true) } }
            ?: relevant(c, home).firstOrNull() ?: c.events.firstOrNull() ?: return answer(c, home)
        return when (ask) {
            Ask.LOCATION -> "${e.name} is near ${position(e)}${relative(e, home)?.let { ", $it" } ?: ""}. ${stamp(c, e)}"
            Ask.ARRIVAL, Ask.DIRECTION -> "The data I have gives its current position, not a forecast track, so I can't say ${if (ask == Ask.ARRIVAL) "when or whether it will reach you" else "which way it is moving"} without guessing. " +
                "Right now it is ${relative(e, home) ?: "near ${position(e)}"}. ${authority(e).replaceFirstChar { it.uppercase() }} publishes the forecast track and warnings. ${stamp(c, e)}"
            Ask.DANGER -> "GDACS rates ${e.name} ${alertMeaning(e.alert)}." + (if (e.severity.isNotBlank()) " ${e.severity}." else "") +
                (relative(e, home)?.let { " It is $it." } ?: "") + " For what to do locally, follow ${authority(e)} and your local authorities. ${stamp(c, e)}"
            Ask.MORE -> c.events.joinToString(" ") { describe(it, home) } + " " + stamp(c, e)
            Ask.COMPARE -> {
                val other = previous?.events?.firstOrNull { it.name != e.name } ?: c.events.firstOrNull { it.name != e.name }
                if (other == null) "I only have information on ${e.name} in this conversation, so there's no earlier one to compare it with."
                else "${e.name}: ${e.severity.ifBlank { "intensity not given" }}, alert ${e.alert.ifBlank { "not given" }}${relative(e, home)?.let { ", $it" } ?: ""}. " +
                    "${other.name}: ${other.severity.ifBlank { "intensity not given" }}, alert ${other.alert.ifBlank { "not given" }}${relative(other, home)?.let { ", $it" } ?: ""}. ${stamp(c, e)}"
            }
        }
    }
}

/** Current weather and today's forecast for one place, as the weather service reported it. */
@Serializable
data class WeatherNow(val place: String, val temperatureC: Double?, val humidity: Int?, val windKmh: Double?, val code: Int?,
                      val minC: Double?, val maxC: Double?, val rainChance: Int?, val time: String, val checkedAt: Long, val error: String? = null,
                      /** Today, tomorrow and the day after, as forecast (empty in states saved before 1.8.2). */
                      val days: List<WeatherDay> = emptyList())

/** One day of the forecast. */
@Serializable
data class WeatherDay(val date: String, val code: Int?, val minC: Double?, val maxC: Double?, val rainChance: Int?)

/**
 * Current conditions and today's forecast from Open-Meteo (free, no key; forecasts from national weather services'
 * models). Only the coordinates of the place asked about — the owner's configured city or one named in the question —
 * are sent; nothing else.
 */
class OpenMeteo(private val http: HttpFetcher, private val base: String = "https://api.open-meteo.com/v1/forecast") {
    suspend fun now(place: Place, now: Long): WeatherNow {
        val url = "$base?latitude=${"%.2f".format(java.util.Locale.ROOT, place.lat)}&longitude=${"%.2f".format(java.util.Locale.ROOT, place.lon)}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&daily=weather_code&timezone=auto&forecast_days=3"
        val r = try { http.get(url, mapOf("User-Agent" to Gdacs.USER_AGENT)) } catch (e: Exception) {
            return WeatherNow(place.name, null, null, null, null, null, null, null, "", now, error = "the weather service could not be reached")
        }
        if (r.code != 200) return WeatherNow(place.name, null, null, null, null, null, null, null, "", now, error = "the weather service returned HTTP ${r.code}")
        return runCatching {
            val o = CluJson.parseToJsonElement(r.body).jsonObject
            val c = o["current"]!!.jsonObject; val d = o["daily"]?.jsonObject
            fun first(k: String) = d?.get(k)?.jsonArray?.firstOrNull()?.jsonPrimitive
            fun at(k: String, i: Int) = d?.get(k)?.jsonArray?.getOrNull(i)?.jsonPrimitive
            val dates = d?.get("time")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val days = dates.indices.map { i -> WeatherDay(dates[i], at("weather_code", i)?.intOrNull, at("temperature_2m_min", i)?.doubleOrNull,
                at("temperature_2m_max", i)?.doubleOrNull, at("precipitation_probability_max", i)?.intOrNull) }
            WeatherNow(place.name, c["temperature_2m"]?.jsonPrimitive?.doubleOrNull, c["relative_humidity_2m"]?.jsonPrimitive?.intOrNull,
                c["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull, c["weather_code"]?.jsonPrimitive?.intOrNull,
                first("temperature_2m_min")?.doubleOrNull, first("temperature_2m_max")?.doubleOrNull, first("precipitation_probability_max")?.intOrNull,
                c["time"]?.jsonPrimitive?.contentOrNull.orEmpty(), now, days = days)
        }.getOrElse { WeatherNow(place.name, null, null, null, null, null, null, null, "", now, error = "the weather service returned an unexpected response") }
    }

    companion object {
        /** WMO weather interpretation codes, in words. */
        fun describe(code: Int?) = when (code) {
            0 -> "clear"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "foggy"
            51, 53, 55 -> "drizzly"; 56, 57 -> "freezing drizzle"; 61, 63 -> "rainy"; 65 -> "raining heavily"; 66, 67 -> "freezing rain"
            71, 73, 75, 77 -> "snowy"; 80, 81 -> "showery"; 82 -> "violent showers"; 85, 86 -> "snow showers"
            95 -> "thunderstorms"; 96, 99 -> "thunderstorms with hail"; else -> null
        }

        /** Which day a question is about: 0 today (or now), 1 tomorrow, 2 the day after tomorrow. */
        fun dayAsked(text: String): Int = when {
            Regex("(?i)\\bday after tomorrow\\b").containsMatchIn(text) -> 2
            Regex("(?i)\\btomorrow\\b").containsMatchIn(text) -> 1
            else -> 0
        }

        fun answer(w: WeatherNow, day: Int = 0): String {
            if (w.error != null) return "I couldn't get the weather for ${w.place} just now (${w.error}), so I won't guess."
            if (day > 0) {
                val label = if (day == 1) "Tomorrow" else "The day after tomorrow"
                val f = w.days.getOrNull(day) ?: return "The forecast I have for ${w.place} doesn't cover ${label.lowercase()}, so I won't guess."
                return buildString {
                    append("$label in ${w.place}: ")
                    append(listOfNotNull(describe(f.code), if (f.minC != null && f.maxC != null) "%.0f to %.0f°C".format(f.minC, f.maxC) else null,
                        f.rainChance?.let { "$it% chance of rain" }).joinToString(", ").ifEmpty { "no details in the forecast" })
                    append(". (Open-Meteo forecast for ${f.date}.)")
                }
            }
            return buildString {
                append("In ${w.place} it's ")
                append(listOfNotNull(w.temperatureC?.let { "%.0f°C".format(it) }, describe(w.code)).joinToString(" and ").ifEmpty { "currently unreported" })
                w.humidity?.let { append(", humidity $it%") }
                w.windKmh?.let { append(", wind %.0f km/h".format(it)) }
                append(".")
                if (w.minC != null && w.maxC != null) append(" Today: %.0f to %.0f°C".format(w.minC, w.maxC))
                w.rainChance?.let { append(if (w.minC != null) ", $it% chance of rain." else " Chance of rain today: $it%.") } ?: if (w.minC != null) append(".") else Unit
                append(" (Open-Meteo forecast data${if (w.time.isNotBlank()) ", ${w.time.replace('T', ' ')} local time" else ""}.)")
            }
        }
    }
}
