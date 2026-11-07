package ai.clu.desktop.browser

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserException(message: String) : Exception(message)

/**
 * The browser CLU drives for the owner: Microsoft Edge (or Chrome/Chromium) started with its own profile folder, so it
 * is separate from the owner's own browsing, logins and tabs, and controlled through the Chrome DevTools Protocol over
 * a local WebSocket (no extra software). It starts on the first browser request, is visible on the owner's screen
 * unless [headless], and closes with CLU. One operation at a time.
 *
 * Page text is someone else's words: callers report it, they never treat it as instructions.
 */
class CluBrowser(private val exe: () -> File?, private val profile: File, private val headless: Boolean = false, private val timeoutMs: Long = 15_000) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).proxy(java.net.ProxySelector.of(null)).build()
    private var process: Process? = null
    private var ws: WebSocket? = null
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val lock = Any()

    val running: Boolean get() = process?.isAlive == true && ws != null

    // ------------------------------------------------------------------------------------------------ lifecycle
    private fun start() {
        if (running) return
        close()
        val bin = exe() ?: throw BrowserException("No Edge, Chrome or Chromium was found on this PC.")
        profile.mkdirs(); File(profile, "DevToolsActivePort").delete()
        val args = mutableListOf(bin.absolutePath, "--remote-debugging-port=0", "--user-data-dir=${profile.absolutePath}", "--no-first-run",
            "--no-default-browser-check", "--disable-features=msEdgeSidebarV2,Translate", "--window-size=1280,860")
        if (headless) args += "--headless=new"
        if (ai.clu.desktop.capability.ExperienceVerifier.needsNoSandbox()) args += "--no-sandbox"
        args += "about:blank"
        process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // The browser writes the port it chose; then find its page and attach to it.
        val portFile = File(profile, "DevToolsActivePort")
        val deadline = System.currentTimeMillis() + 20_000
        while (!portFile.isFile || portFile.length() == 0L) {
            if (System.currentTimeMillis() > deadline || process?.isAlive != true) { close(); throw BrowserException("The browser didn't start.") }
            Thread.sleep(100)
        }
        val port = portFile.readLines().first().trim().toInt()
        val wsUrl = pageSocket(port) ?: run { close(); throw BrowserException("The browser has no page to control.") }
        ws = http.newWebSocketBuilder().buildAsync(URI(wsUrl), Listener()).get(10, TimeUnit.SECONDS)
        call("Page.enable"); call("Runtime.enable")
        if (!headless) runCatching { call("Page.bringToFront") }
    }

    private fun pageSocket(port: Int): String? {
        repeat(50) {
            val list = runCatching { http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
            list?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonObject }.firstOrNull { o -> o["type"]?.jsonPrimitive?.content == "page" }
                ?.get("webSocketDebuggerUrl")?.jsonPrimitive?.content }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    fun close() = synchronized(lock) {
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "")?.get(2, TimeUnit.SECONDS) }
        ws = null
        process?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null
        waiting.values.forEach { it.completeExceptionally(BrowserException("The browser closed.")) }; waiting.clear()
    }

    private inner class Listener : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) { val msg = buf.toString(); buf.setLength(0)
                runCatching { Json.parseToJsonElement(msg).jsonObject }.getOrNull()?.let { o -> o["id"]?.jsonPrimitive?.intOrNull?.let { waiting.remove(it)?.complete(o) } } }
            webSocket.request(1); return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? { ws = null; return null }
        override fun onError(webSocket: WebSocket, error: Throwable) { ws = null }
    }

    private fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeout: Long = timeoutMs): JsonObject {
        val socket = ws ?: throw BrowserException("The browser isn't running.")
        val id = ids.incrementAndGet(); val f = CompletableFuture<JsonObject>(); waiting[id] = f
        socket.sendText(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString(), true)
        val r = try { f.get(timeout, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.TimeoutException) { waiting.remove(id); throw BrowserException("The browser didn't answer in time.") }
        r["error"]?.jsonObject?.let { throw BrowserException(it["message"]?.jsonPrimitive?.content ?: "browser error") }
        return r["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** Evaluates [js] in the page and returns its JSON value. */
    private fun eval(js: String): JsonElement {
        val r = call("Runtime.evaluate", buildJsonObject { put("expression", js); put("returnByValue", true); put("awaitPromise", true) })
        r["exceptionDetails"]?.let { throw BrowserException("The page refused that action.") }
        return r["result"]?.jsonObject?.get("value") ?: JsonNull
    }

    private fun waitLoaded(max: Long = timeoutMs) {
        val end = System.currentTimeMillis() + max
        Thread.sleep(150)
        while (System.currentTimeMillis() < end) {
            val state = runCatching { eval("document.readyState").jsonPrimitive.content }.getOrNull()
            if (state == "interactive" || state == "complete") return
            Thread.sleep(100)
        }
        throw BrowserException("The page took too long to load.")
    }

    private fun <T> op(block: () -> T): T = synchronized(lock) { start(); block() }

    data class Page(val url: String, val title: String)
    private fun page() = eval("JSON.stringify({u: location.href, t: document.title})").jsonPrimitive.content.let { Json.parseToJsonElement(it).jsonObject }
        .let { Page(it["u"]!!.jsonPrimitive.content, it["t"]!!.jsonPrimitive.content) }

    // ------------------------------------------------------------------------------------------------ actions
    fun open(url: String): Page = op {
        if (!ai.clu.core.browser.Sites.allowed(url)) throw BrowserException("Only complete web addresses (http or https) can be opened.")
        val r = call("Page.navigate", buildJsonObject { put("url", url) })
        r["errorText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { throw BrowserException("I couldn't open that page ($it).") }
        waitLoaded(); page()
    }

    data class Read(val page: Page, val text: String, val truncated: Boolean)
    fun read(maxChars: Int = 6_000): Read = op {
        val text = eval("(document.body ? document.body.innerText : '')").jsonPrimitive.content.replace(Regex("\\s+"), " ").trim()
        Read(page(), text.take(maxChars), text.length > maxChars)
    }

    /** The visible controls a person could use: links, buttons, fields, with the names CLU can click or type into. */
    data class Control(val kind: String, val name: String)
    fun inspect(max: Int = 60): Pair<Page, List<Control>> = op {
        val js = """JSON.stringify([...document.querySelectorAll('button,a[href],input,textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[role=searchbox],[role=textbox]')]
            .filter(e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && e.type !== 'hidden'; })
            .map(e => ({ k: (e.getAttribute('role') || (e.tagName === 'A' ? 'link' : e.tagName === 'INPUT' ? (e.type || 'text') : e.tagName.toLowerCase())),
                n: (e.getAttribute('aria-label') || (e.labels && e.labels[0] && e.labels[0].innerText) || e.getAttribute('placeholder') || e.getAttribute('title') || e.innerText || e.value || e.name || '').trim().replace(/\s+/g, ' ').slice(0, 80) }))
            .filter(c => c.n).slice(0, $max))"""
        val list = Json.parseToJsonElement(eval(js).jsonPrimitive.content).jsonArray.map { Control(it.jsonObject["k"]!!.jsonPrimitive.content, it.jsonObject["n"]!!.jsonPrimitive.content) }
        page() to list
    }

    private val FIND = """(function(t, fields){
        const norm = s => (s || '').trim().replace(/\s+/g, ' ').toLowerCase(); t = norm(t);
        const sel = fields ? 'input:not([type=hidden]):not([type=button]):not([type=submit]),textarea,[contenteditable=true],[role=textbox],[role=searchbox],[role=combobox]'
                           : 'button,a[href],[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],input[type=submit],input[type=button],input[type=checkbox],input[type=radio],summary,label';
        const vis = e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
        const name = e => norm(e.getAttribute('aria-label') || (e.labels && e.labels[0] && e.labels[0].innerText) || e.getAttribute('placeholder') || e.getAttribute('title') || e.innerText || e.value || e.name);
        const all = [...document.querySelectorAll(sel)].filter(vis);
        let hit = all.find(e => name(e) === t) || all.find(e => name(e).startsWith(t)) || all.find(e => name(e).includes(t));
        if (!hit && fields && /search|query|find|look/.test(t)) hit = all.find(e => e.type === 'search' || /search/i.test(e.getAttribute('aria-label') || e.placeholder || e.name || ''));
        if (!hit && fields && all.length === 1) hit = all[0];
        return hit;
    })"""

    fun click(target: String): Page = op {
        val ok = eval("(function(){ const e = $FIND(${Json.encodeToString(JsonPrimitive(target))}, false); if (!e) return false; e.scrollIntoView({block:'center'}); e.click(); return true; })()").jsonPrimitive.boolean
        if (!ok) throw BrowserException("I couldn't find a control called \"$target\" on this page.")
        runCatching { waitLoaded(5_000) }; page()
    }

    fun type(target: String, text: String): Page = op {
        val js = """(function(){ const e = $FIND(${Json.encodeToString(JsonPrimitive(target))}, true); if (!e) return false; e.scrollIntoView({block:'center'}); e.focus();
            if (e.isContentEditable) { e.textContent = ${Json.encodeToString(JsonPrimitive(text))}; }
            else { const p = Object.getPrototypeOf(e), d = Object.getOwnPropertyDescriptor(p, 'value'); d && d.set ? d.set.call(e, ${Json.encodeToString(JsonPrimitive(text))}) : (e.value = ${Json.encodeToString(JsonPrimitive(text))}); }
            e.dispatchEvent(new Event('input', {bubbles: true})); e.dispatchEvent(new Event('change', {bubbles: true})); return true; })()"""
        if (!eval(js).jsonPrimitive.boolean) throw BrowserException("I couldn't find a text field called \"$target\" on this page.")
        page()
    }

    private val KEYS = mapOf("Enter" to 13, "Escape" to 27, "Tab" to 9, "Backspace" to 8, "ArrowDown" to 40, "ArrowUp" to 38, "ArrowLeft" to 37, "ArrowRight" to 39, "PageDown" to 34, "PageUp" to 33)
    fun press(key: String): Page = op {
        val code = KEYS[key] ?: throw BrowserException("I can press Enter, Escape, Tab, Backspace, the arrow keys, Page Up or Page Down.")
        for (type in listOf("keyDown", "keyUp")) call("Input.dispatchKeyEvent", buildJsonObject {
            put("type", type); put("key", key); put("code", key); put("windowsVirtualKeyCode", code); put("nativeVirtualKeyCode", code)
            if (key == "Enter" && type == "keyDown") put("text", "\r") })
        runCatching { waitLoaded(5_000) }; page()
    }

    fun scroll(down: Boolean): Page = op { eval("window.scrollBy(0, ${if (down) 700 else -700}); true"); page() }

    fun back(): Page = op { eval("history.back(); true"); runCatching { waitLoaded(5_000) }; page() }

    fun screenshot(to: File): File = op {
        val data = call("Page.captureScreenshot", buildJsonObject { put("format", "png") })["data"]!!.jsonPrimitive.content
        to.parentFile.mkdirs(); to.writeBytes(Base64.getDecoder().decode(data)); to
    }
}
