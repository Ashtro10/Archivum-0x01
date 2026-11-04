package ai.clu.desktop.capability

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.NetCategory
import ai.clu.core.util.CluJson
import ai.clu.desktop.Call
import ai.clu.desktop.ShellAssets
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The services CLU's experiences use, all through CLU's own API (experience pages never contact another site):
 *
 * | | |
 * |---|---|
 * | GET /v1/kit/status | which online sources are reachable now (weather, street tiles), with the reason when not |
 * | GET /v1/kit/weather?lat&lon&name | Open-Meteo current/hourly/daily; last good answer marked stale if a refresh fails; never invented |
 * | GET /v1/kit/places?q | offline gazetteer (GeoNames cities ≥150k people and capitals) |
 * | GET /v1/kit/places/near?lat&lon | nearest gazetteer city |
 * | GET /v1/kit/home | the owner's home location (Settings → identity) if it is in the gazetteer |
 * | GET /v1/kit/tiles/{z}/{x}/{y}.png | OpenStreetMap tiles, cached on disk; 503 with the reason when unreachable |
 */
class KitServices(private val core: CluCore, private val http: HttpFetcher, private val tileCache: File, private val now: () -> Long = System::currentTimeMillis) {
    /** Open-Meteo's forecast endpoint (overridable for tests with `-Dclu.kit.weatherUrl`). */
    var weatherUrl: String = System.getProperty("clu.kit.weatherUrl") ?: "https://api.open-meteo.com/v1/forecast"
    var tileUrl: String = System.getProperty("clu.kit.tileUrl") ?: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private val tiles = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build()

    data class Place(val name: String, val country: String, val lat: Double, val lon: Double, val population: Long)
    val gazetteer: List<Place> by lazy {
        val text = KitServices::class.java.getResourceAsStream("/kit/gazetteer.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "[]"
        CluJson.parseToJsonElement(text).jsonArray.map { e -> val a = e.jsonArray
            Place(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content, a[2].jsonPrimitive.double, a[3].jsonPrimitive.double, a[4].jsonPrimitive.long) }
    }
    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    fun places(q: String, limit: Int = 8): List<Place> {
        val k = fold(q).substringBefore(',').trim(); if (k.length < 2) return emptyList()
        val cc = fold(q).substringAfter(',', "").trim().uppercase().takeIf { it.length == 2 }
        val exact = gazetteer.filter { fold(it.name) == k && (cc == null || it.country == cc) }
        val prefix = gazetteer.filter { fold(it.name).startsWith(k) && it !in exact && (cc == null || it.country == cc) }
        return (exact + prefix).take(limit)
    }

    fun near(lat: Double, lon: Double): Place? = gazetteer.minByOrNull { val dx = (it.lon - lon) * cos(Math.toRadians(lat)); val dy = it.lat - lat; sqrt(dx * dx + dy * dy) }

    // ------------------------------------------------------------------ reachability (cached briefly; a probe is one tiny request)
    data class Reach(val available: Boolean, val reason: String, val at: Long)
    private val reach = ConcurrentHashMap<String, Reach>()
    fun status(force: Boolean = false): Map<String, Reach> = mapOf("weather" to probe("weather", force) { weatherProbe() }, "tiles" to probe("tiles", force) { tileProbe() })
    private fun probe(key: String, force: Boolean, fn: () -> Reach): Reach {
        val r = reach[key]; if (!force && r != null && now() - r.at < 120_000) return r
        return fn().also { reach[key] = it }
    }
    private fun blocked(cat: NetCategory?): String? = when {
        !core.network.isOnline() -> "this computer is offline"
        cat != null -> core.network.check(cat)
        else -> null
    }
    private fun weatherProbe(): Reach {
        blocked(null)?.let { return Reach(false, it, now()) }
        return runCatching { runBlocking { http.get("$weatherUrl?latitude=0&longitude=0&current=temperature_2m", mapOf("User-Agent" to UA)) } }
            .fold({ if (it.code == 200) Reach(true, "", now()) else Reach(false, "Open-Meteo answered HTTP ${it.code}", now()) }, { Reach(false, "Open-Meteo could not be reached (${reasonOf(it)})", now()) })
    }
    private fun tileProbe(): Reach {
        blocked(NetCategory.MAPS)?.let { return Reach(false, it, now()) }
        return runCatching { fetchTile(0, 0, 0) }.fold({ Reach(true, "", now()) }, { Reach(false, "OpenStreetMap tiles could not be reached (${reasonOf(it)})", now()) })
    }
    private fun reasonOf(e: Throwable): String = (e.message ?: e::class.simpleName ?: "error").let { m ->
        when { m.contains("403") -> "the network refused the connection"; m.contains("timed out", true) -> "timed out"; else -> m.take(120) } }

    // ------------------------------------------------------------------ weather (Open-Meteo: free, no key)
    private data class Cached(val body: JsonObject, val at: Long)
    private val weatherCache = ConcurrentHashMap<String, Cached>()

    fun weather(lat: Double, lon: Double, name: String): JsonObject {
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, lat, lon)
        weatherCache[key]?.takeIf { now() - it.at < 10 * 60_000 }?.let { return it.body }
        val fail: (String) -> JsonObject = { reason ->
            weatherCache[key]?.let { c -> JsonObject(c.body + mapOf("stale" to JsonPrimitive(true), "reason" to JsonPrimitive(reason))) }
                ?: buildJsonObject { put("ok", false); put("reason", reason); put("provider", "Open-Meteo") }
        }
        blocked(null)?.let { return fail(it) }
        val url = "$weatherUrl?latitude=${"%.4f".format(java.util.Locale.ROOT, lat)}&longitude=${"%.4f".format(java.util.Locale.ROOT, lon)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=24" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&forecast_days=5&timezone=auto"
        val r = try { runBlocking { http.get(url, mapOf("User-Agent" to UA)) } } catch (e: Exception) { reach["weather"] = Reach(false, "Open-Meteo could not be reached (${reasonOf(e)})", now()); return fail("Open-Meteo could not be reached (${reasonOf(e)})") }
        if (r.code != 200) return fail("Open-Meteo answered HTTP ${r.code}")
        val o = runCatching { CluJson.parseToJsonElement(r.body).jsonObject }.getOrNull() ?: return fail("Open-Meteo sent an answer CLU could not read")
        val c = o["current"]?.jsonObject ?: return fail("Open-Meteo's answer had no current conditions")
        fun num(obj: JsonObject?, k: String) = obj?.get(k)?.jsonPrimitive?.doubleOrNull
        fun arr(obj: JsonObject?, k: String) = obj?.get(k)?.jsonArray ?: JsonArray(emptyList())
        val d = o["daily"]?.jsonObject; val h = o["hourly"]?.jsonObject
        val body = buildJsonObject {
            put("ok", true); put("stale", false); put("provider", "Open-Meteo"); put("fetchedAt", now()); put("name", name)
            put("observedAt", c["time"]?.jsonPrimitive?.contentOrNull ?: ""); put("timezone", o["timezone"]?.jsonPrimitive?.contentOrNull ?: "")
            putJsonObject("current") {
                put("temperature", num(c, "temperature_2m")); put("apparent", num(c, "apparent_temperature")); put("humidity", num(c, "relative_humidity_2m")?.toInt())
                put("precipitation", num(c, "precipitation")); put("code", num(c, "weather_code")?.toInt()); put("wind", num(c, "wind_speed_10m"))
                put("windDirection", num(c, "wind_direction_10m")); put("gusts", num(c, "wind_gusts_10m"))
            }
            putJsonArray("daily") { arr(d, "time").forEachIndexed { i, t -> addJsonObject {
                put("date", t.jsonPrimitive.content); put("code", arr(d, "weather_code").getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toInt())
                put("max", arr(d, "temperature_2m_max").getOrNull(i)?.jsonPrimitive?.doubleOrNull); put("min", arr(d, "temperature_2m_min").getOrNull(i)?.jsonPrimitive?.doubleOrNull)
                put("precipitationChance", arr(d, "precipitation_probability_max").getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toInt())
                put("sunrise", arr(d, "sunrise").getOrNull(i)?.jsonPrimitive?.contentOrNull); put("sunset", arr(d, "sunset").getOrNull(i)?.jsonPrimitive?.contentOrNull) } } }
            putJsonArray("hourly") { arr(h, "time").forEachIndexed { i, t -> addJsonObject {
                put("time", t.jsonPrimitive.content); put("temperature", arr(h, "temperature_2m").getOrNull(i)?.jsonPrimitive?.doubleOrNull)
                put("precipitationChance", arr(h, "precipitation_probability").getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toInt())
                put("code", arr(h, "weather_code").getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toInt()) } } }
        }
        if (num(c, "temperature_2m") == null) return fail("Open-Meteo's answer had no temperature")
        weatherCache[key] = Cached(body, now()); reach["weather"] = Reach(true, "", now())
        return body
    }

    // ------------------------------------------------------------------ OpenStreetMap tiles (cached; usage policy: identify, cache, no bulk download)
    fun fetchTile(z: Int, x: Int, y: Int): ByteArray {
        require(z in 0..18 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "no such tile" }
        val f = File(tileCache, "$z/$x/$y.png")
        if (f.isFile && now() - f.lastModified() < 14L * 86_400_000) return f.readBytes()
        val req = HttpRequest.newBuilder(URI(tileUrl.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))).timeout(Duration.ofSeconds(10))
            .header("User-Agent", UA).GET().build()
        val r = tiles.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (r.statusCode() != 200) throw java.io.IOException("HTTP ${r.statusCode()}")
        f.parentFile.mkdirs(); f.writeBytes(r.body())
        return r.body()
    }

    fun home(): Place? {
        val h = core.settings.string(Settings.HOME_LOCATION).trim(); if (h.isEmpty()) return null
        Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$").find(h)?.let { m -> return near(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }
        return places(h, 1).firstOrNull()
    }

    private fun place(p: Place) = buildJsonObject { put("name", p.name); put("country", p.country); put("lat", p.lat); put("lon", p.lon); put("population", p.population) }

    fun handle(c: Call): Boolean {
        if (!c.path.startsWith("/v1/kit/")) return false
        val tile = Regex("^/v1/kit/tiles/(\\d{1,2})/(\\d{1,6})/(\\d{1,6})\\.png$").find(c.path)
        when {
            c.path == "/v1/kit/status" && c.method == "GET" -> c.ok(buildJsonObject {
                status(c.query("refresh") == "1").forEach { (k, r) -> putJsonObject(k) { put("available", r.available); put("reason", r.reason); put("checkedAt", r.at) } } })
            c.path == "/v1/kit/weather" && c.method == "GET" -> {
                val lat = c.query("lat")?.toDoubleOrNull(); val lon = c.query("lon")?.toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return true.also { c.fail(400, "lat and lon are required") }
                c.ok(weather(lat, lon, c.query("name")?.take(80) ?: ""))
            }
            c.path == "/v1/kit/places" && c.method == "GET" -> c.ok(buildJsonObject { put("source", "offline gazetteer (GeoNames via all-the-cities)"); putJsonArray("places") { places(c.query("q") ?: "").forEach { add(place(it)) } } })
            c.path == "/v1/kit/places/near" && c.method == "GET" -> {
                val lat = c.query("lat")?.toDoubleOrNull(); val lon = c.query("lon")?.toDoubleOrNull()
                if (lat == null || lon == null) return true.also { c.fail(400, "lat and lon are required") }
                c.ok(buildJsonObject { near(lat, lon)?.let { put("place", place(it)) } })
            }
            c.path == "/v1/kit/home" && c.method == "GET" -> c.ok(buildJsonObject { home()?.let { put("place", place(it)) } })
            tile != null && c.method == "GET" -> {
                val (z, x, y) = tile.destructured
                val bytes = try {
                    blocked(NetCategory.MAPS)?.let { throw java.io.IOException(it) }
                    fetchTile(z.toInt(), x.toInt(), y.toInt())
                } catch (e: IllegalArgumentException) { return true.also { c.fail(404, "no such tile") } }
                catch (e: Exception) { reach["tiles"] = Reach(false, "OpenStreetMap tiles could not be reached (${reasonOf(e)})", now()); return true.also { c.fail(503, "street tiles unavailable: ${reasonOf(e)}") } }
                c.ex.responseHeaders.apply { set("Content-Type", "image/png"); set("Cache-Control", "private, max-age=86400"); set("X-Content-Type-Options", "nosniff") }
                c.ex.sendResponseHeaders(200, bytes.size.toLong()); c.ex.responseBody.use { it.write(bytes) }
            }
            else -> c.fail(404, "no such endpoint")
        }
        return true
    }

    companion object {
        const val UA = "CLU-personal-assistant/1.0 (desktop; experiences)"
    }
}

/**
 * Static files of experiences: the shared kit (`/kit/<file>` from CLU's jar), installed versions
 * (`/x/<capability>/<version>/<file>`) and development previews (`/x/preview/<run>/<file>`), with the same strict
 * Content-Security-Policy as the shell (no inline code, no other sites); experiences may be framed by the shell only.
 */
class ExperienceFiles(private val installedRoot: () -> File, private val workspaces: () -> File) {
    private val NAME = Regex("^[a-z0-9_-]+\\.(html|css|js|json|png|txt)$", RegexOption.IGNORE_CASE)
    private val TYPES = mapOf("html" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8", "js" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8", "png" to "image/png", "txt" to "text/plain; charset=utf-8")
    val CSP = ShellAssets.CSP.replace("img-src 'self' data:", "img-src 'self' data: blob:").replace("frame-ancestors 'none'", "frame-ancestors 'self'")

    fun serves(path: String) = path.startsWith("/kit/") || path.startsWith("/x/")

    fun serve(ex: HttpExchange, path: String) {
        val bytes: ByteArray? = when {
            path.startsWith("/kit/") -> path.removePrefix("/kit/").takeIf { NAME.matches(it) }?.let { ExperienceFiles::class.java.getResourceAsStream("/kit/$it")?.use { s -> s.readBytes() } }
            path.startsWith("/x/preview/") -> path.removePrefix("/x/preview/").split('/').takeIf { it.size == 2 && Regex("^[a-z0-9]{8,32}$").matches(it[0]) && NAME.matches(it[1]) }
                ?.let { (run, name) -> File(File(workspaces(), run), "out/$name").takeIf { it.isFile }?.readBytes() }
            else -> path.removePrefix("/x/").split('/').takeIf { it.size == 3 && Regex("^[a-z0-9-]{1,60}$").matches(it[0]) && Regex("^\\d{1,4}$").matches(it[1]) && NAME.matches(it[2]) }
                ?.let { (cap, ver, name) -> File(installedRoot(), "$cap/$ver/$name").takeIf { it.isFile }?.readBytes() }
        }
        ex.responseHeaders.apply { set("Content-Security-Policy", CSP); set("X-Content-Type-Options", "nosniff"); set("Referrer-Policy", "no-referrer"); set("Cache-Control", "no-store") }
        if (bytes == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        ex.responseHeaders.set("Content-Type", TYPES.getValue(path.substringAfterLast('.').lowercase()))
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
