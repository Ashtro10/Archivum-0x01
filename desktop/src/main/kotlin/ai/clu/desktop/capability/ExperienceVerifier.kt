package ai.clu.desktop.capability

import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Verifies an experience the way the owner would meet it: loads it in a real browser (headless Chromium or Edge, the
 * engine of CLU's own window), lets it run its self-tests against CLU's live services, reads the results from the
 * rendered page, then takes a screenshot and inspects the pixels (not blank, the theme visible, and for maps the land
 * and water actually on screen). Without a browser it cannot verify, and says so; it never passes by default.
 */
class ExperienceVerifier(private val browsers: () -> List<File> = { candidates() }, private val scratch: File) {
    /** The first browser that actually renders a page here (some builds hang headless in locked-down environments). */
    @Volatile private var working: File? = null
    fun browser(): File? {
        working?.let { if (it.isFile) return it }
        for (b in browsers()) {
            val dom = run(b, listOf("--dump-dom", "data:text/html,<p id=probe>ok</p>"), 25)
            if (dom?.contains("id=\"probe\"") == true) { working = b; return b }
        }
        return null
    }

    data class Result(val checks: List<Verification.Check>, val unverified: List<String>, val screenshot: File?, val browser: String) {
        val passed get() = checks.isNotEmpty() && checks.all { it.ok }
    }

    /** The page's DOM after it ran (used by the model benchmark to run tests in a real browser). */
    fun dumpDom(url: String): String? = browser()?.let { run(it, listOf("--allow-file-access-from-files", "--dump-dom", url), 60) }

    fun verify(pageUrl: String, code: () -> String, shot: File, bp: Blueprint): Result {
        val exe = browser() ?: return Result(listOf(Verification.Check("browser available", false,
            "no Chromium-based browser that renders headless was found (tried ${browsers().joinToString { it.name }.ifEmpty { "none" }}; set -Dclu.browser=<path to msedge/chrome>)")), emptyList(), null, "")
        val checks = mutableListOf<Verification.Check>(); val unverified = mutableListOf<String>()
        // 1. Self-tests in the running page.
        val dom = run(exe, listOf("--dump-dom", "$pageUrl?selftest=1#code=${code()}"), 120)
        if (dom == null) checks += Verification.Check("page rendered", false, "the browser did not return the page")
        else {
            val pre = Regex("<pre id=\"clu-selfcheck\"[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(dom)?.groupValues?.get(1)
                ?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&#39;", "'")?.replace("&amp;", "&")
            val ready = dom.contains("data-clu-ready=\"1\"")
            checks += Verification.Check("page rendered and finished starting", ready && pre != null, if (ready) "" else "the page never reported ready (script failure or blocked file)")
            pre?.let { runCatching { CluJson.parseToJsonElement(it).jsonObject }.getOrNull() }?.let { o ->
                o["checks"]?.jsonArray?.forEach { e -> val c = e.jsonObject
                    checks += Verification.Check(c["name"]!!.jsonPrimitive.content, c["ok"]!!.jsonPrimitive.boolean, c["detail"]?.jsonPrimitive?.contentOrNull ?: "") }
                o["unverified"]?.jsonArray?.forEach { unverified += it.jsonPrimitive.content }
            }
        }
        // 2. What it looks like.
        shot.parentFile.mkdirs(); shot.delete()
        run(exe, listOf("--screenshot=${shot.absolutePath}", "$pageUrl?preview=1#code=${code()}"), 90)
        if (!shot.isFile) checks += Verification.Check("screenshot taken", false, "the browser produced no screenshot")
        else checks += inspect(shot, bp)
        return Result(checks, unverified, shot.takeIf { it.isFile }, exe.name)
    }

    /** Pixel checks on the screenshot. */
    fun inspect(png: File, bp: Blueprint): List<Verification.Check> {
        val img = runCatching { ImageIO.read(png) }.getOrNull() ?: return listOf(Verification.Check("screenshot readable", false, png.name))
        val w = img.width; val h = img.height
        val samples = ArrayList<Int>(20000)
        val stepX = maxOf(1, w / 160); val stepY = maxOf(1, h / 110)
        for (y in 0 until h step stepY) for (x in 0 until w step stepX) samples += img.getRGB(x, y) and 0xffffff
        fun lum(c: Int) = 0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)
        val mean = samples.map(::lum).average(); val sd = sqrt(samples.map { (lum(it) - mean).let { d -> d * d } }.average())
        fun hex(s: String) = s.removePrefix("#").toInt(16)
        fun near(a: Int, b: Int, tol: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255)) <= tol
        fun share(color: String, tol: Int = 40) = samples.count { near(it, hex(color), tol) }.toDouble() / samples.size
        val out = mutableListOf<Verification.Check>()
        out += Verification.Check("screenshot: not blank", sd > 12, "brightness spread %.1f".format(sd))
        if (bp.kind == "app") {
            // An application CLU wrote has no curated theme: content must stand out from the page's dominant colour.
            val dominant = samples.groupingBy { (it shr 16 and 0xf0) shl 16 or ((it shr 8 and 0xf0) shl 8) or (it and 0xf0) }.eachCount().maxByOrNull { it.value }!!.key
            fun d(a: Int, b: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
            val fg = samples.count { d(it, dominant) > 120 }.toDouble() / samples.size
            out += Verification.Check("screenshot: content stands out from the background", fg > 0.003, "%.1f%% of the screen".format(fg * 100))
            return out.map { if (it.name == "screenshot: not blank") it.copy(ok = sd > 5, detail = it.detail + " (applications: more than 5)") else it }
        }
        // Share of the screen in the theme's own background colours (each pixel counted once).
        val themed = samples.count { near(it, hex(bp.design.bg), 50) || near(it, hex(bp.design.panel), 50) }.toDouble() / samples.size
        out += Verification.Check("screenshot: the ${Styles.title(bp.style)} look is what is on screen", themed > 0.25, "%.0f%% of the screen in the theme's background colours".format(themed * 100))
        // Foreground: pixels that stand clearly apart from both background colours (text, figures, lines, accents; antialiased text included).
        fun dist(a: Int, b: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
        val fg = samples.count { dist(it, hex(bp.design.bg)) > 120 && dist(it, hex(bp.design.panel)) > 120 }.toDouble() / samples.size
        out += Verification.Check("screenshot: text and accents are drawn", fg > 0.008, "%.1f%% of the screen stands out from the background".format(fg * 100))
        if (bp.kind == "map" || bp.kind == "dashboard") {
            val land = share(bp.design.land, 30); val water = share(bp.design.water, 30)
            out += Verification.Check("screenshot: the map shows land and water", land > 0.02 && water > 0.02, "land %.1f%%, water %.1f%%".format(land * 100, water * 100))
        }
        return out
    }

    private fun run(exe: File, extra: List<String>, timeoutS: Long): String? {
        val profile = File(scratch, "browser-" + System.nanoTime()).apply { mkdirs() }
        val args = buildList {
            add(exe.absolutePath); if (!exe.name.contains("headless")) add("--headless=new"); add("--disable-gpu"); add("--hide-scrollbars"); add("--no-first-run"); add("--no-default-browser-check")
            add("--disable-extensions"); add("--disable-background-networking"); add("--disable-component-update"); add("--disable-sync"); add("--no-pings"); add("--user-data-dir=${profile.absolutePath}"); add("--window-size=1280,860")
            add("--virtual-time-budget=25000"); add("--force-device-scale-factor=1")
            if (needsNoSandbox()) add("--no-sandbox")
            addAll(extra)
        }
        val out = File(profile, "stdout.txt")
        return try {
            val p = ProcessBuilder(args).redirectOutput(out).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.takeIf { it.isFile }?.readText()
        } catch (e: Exception) { null }
        finally { runCatching { ai.clu.core.util.SafeDelete.tree(profile, listOf(scratch)) } }
    }

    companion object {
        /** Chromium refuses its sandbox when running as root (containers, CI). */
        fun needsNoSandbox() = System.getProperty("os.name").lowercase().contains("linux") && System.getProperty("user.name") == "root"

        fun find(): File? = candidates().firstOrNull()

        /** `-Dclu.browser`, `CLU_BROWSER`, then Edge/Chrome/Chromium (headless shell builds first) in their usual places. */
        fun candidates(): List<File> {
            val explicit = listOfNotNull(System.getProperty("clu.browser"), System.getenv("CLU_BROWSER")).map(::File)
            val win = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")).flatMap { base ->
                listOf("Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe").map { File(base, it) } }
            val mac = listOf("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/Applications/Chromium.app/Contents/MacOS/Chromium").map(::File)
            val linux = listOf("/usr/bin/microsoft-edge", "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser").map(::File) +
                (File("/opt/pw-browsers").listFiles { f -> f.name.startsWith("chromium_headless_shell-") }?.sortedDescending()?.map { File(it, "chrome-headless-shell-linux64/chrome-headless-shell") } ?: emptyList()) +
                (File("/opt/pw-browsers").listFiles { f -> f.name.startsWith("chromium-") }?.sortedDescending()?.map { File(it, "chrome-linux64/chrome") } ?: emptyList())
            return (explicit + win + mac + linux).filter { it.isFile && it.canExecute() }.distinct()
        }
    }
}
