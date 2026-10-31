package ai.clu.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.net.URI

/**
 * Opens CLU's window and puts CLU in the notification area.
 * * **Window:** Microsoft Edge in app mode (`msedge --app=…`): a frameless window on the same Chromium engine as
 *   WebView2, already part of Windows 11. Elsewhere, the default browser.
 * * **Tray:** the JDK's own SystemTray. Open, unload the model, quit.
 * The URL carries a one-time launch code (in the fragment, which is never sent to a server), never the token itself.
 */
object ShellLauncher {
    /** Edge started with CLU's own profile folder, so its processes can be told apart (and counted against the memory ceiling). */
    fun edgeCommand(url: String, profile: java.io.File?): List<String> = buildList {
        add("cmd"); add("/c"); add("start"); add(""); add("msedge"); add("--app=$url"); add("--window-size=1100,780")
        if (profile != null) { add("--user-data-dir=${profile.absolutePath}"); add("--no-first-run"); add("--disable-features=msEdgeSidebarV2") }
    }

    fun open(url: String, profile: java.io.File? = null, log: (String) -> Unit = ::println): Boolean {
        if (IS_WINDOWS) runCatching {
            profile?.mkdirs()
            val p = ProcessBuilder(edgeCommand(url, profile)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            // "start" returns at once; a non-zero answer means Windows found no msedge, so use the default browser (below).
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() == 0) return true
            log("Edge is not available; opening the default browser")
        }.onFailure { log("could not start Edge (${it.message}); trying the default browser") }
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            return runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
        return runCatching { ProcessBuilder("xdg-open", url).start(); true }.getOrDefault(false)
    }

    /** What the tray shows, from the model's real state. */
    fun trayState(status: ai.clu.core.llm.LlmStatus): Pair<String, java.awt.Color> = when (status) {
        ai.clu.core.llm.LlmStatus.READY -> "model ready" to Color(62, 214, 230)
        ai.clu.core.llm.LlmStatus.GENERATING -> "thinking" to Color(120, 230, 140)
        ai.clu.core.llm.LlmStatus.LOADING -> "loading the model" to Color(240, 200, 80)
        ai.clu.core.llm.LlmStatus.ERROR -> "needs attention (model error)" to Color(240, 90, 90)
        ai.clu.core.llm.LlmStatus.NO_MODEL -> "no model chosen yet" to Color(150, 160, 170)
        else -> "idle" to Color(120, 140, 150)
    }

    /** Adds CLU's tray icon, kept in step with the model's state. Returns false where there is no desktop session (servers, tests). */
    fun installTray(host: RunningHost, restart: () -> Unit, quit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return false
        return runCatching {
            val menu = PopupMenu()
            val icon = TrayIcon(icon(), "CLU", menu).apply { isImageAutoSize = true }
            menu.add(MenuItem("Open CLU").apply { addActionListener { host.api.openShell?.invoke() } })
            menu.add(MenuItem("Unload the model now").apply { addActionListener { kotlinx.coroutines.runBlocking { host.core.llm.unload() } } })
            menu.add(MenuItem("Restart CLU").apply { addActionListener { SystemTray.getSystemTray().remove(icon); restart() } })
            menu.addSeparator()
            menu.add(MenuItem("Quit CLU").apply { addActionListener { SystemTray.getSystemTray().remove(icon); quit() } })
            icon.addActionListener { host.api.openShell?.invoke() } // double-click
            SystemTray.getSystemTray().add(icon)
            val t = java.util.Timer("clu-tray", true)
            var last: ai.clu.core.llm.LlmStatus? = null
            t.schedule(object : java.util.TimerTask() { override fun run() {
                val st = host.core.llm.state.value.status; if (st == last) return; last = st
                val (label, color) = trayState(st); icon.toolTip = "CLU - $label"; icon.image = icon(64, color)
            } }, 1000, 1500)
            true
        }.getOrDefault(false)
    }

    /** CLU's mark: a cyan hexagon, drawn at run time (no image files to ship). */
    fun icon(size: Int = 64, accent: Color = Color(62, 214, 230)): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { img ->
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val hex = Path2D.Double()
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i - 90)
            val x = size / 2 + size * 0.42 * Math.cos(a); val y = size / 2 + size * 0.42 * Math.sin(a)
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.closePath()
        g.color = Color(8, 20, 28); g.fill(hex)
        g.color = accent; g.stroke = BasicStroke(size / 12f); g.draw(hex)
        g.fillOval(size / 2 - size / 10, size / 2 - size / 10, size / 5, size / 5)
        g.dispose()
    }
}
