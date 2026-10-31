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
    fun open(url: String, log: (String) -> Unit = ::println): Boolean {
        if (IS_WINDOWS) runCatching {
            ProcessBuilder("cmd", "/c", "start", "", "msedge", "--app=$url", "--window-size=1100,780").redirectErrorStream(true).start()
            return true
        }.onFailure { log("could not start Edge (${it.message}); trying the default browser") }
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            return runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
        return runCatching { ProcessBuilder("xdg-open", url).start(); true }.getOrDefault(false)
    }

    /** Adds CLU's tray icon. Returns false where there is no desktop session (servers, tests). */
    fun installTray(host: RunningHost, quit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return false
        return runCatching {
            val menu = PopupMenu()
            val icon = TrayIcon(icon(), "CLU", menu).apply { isImageAutoSize = true }
            menu.add(MenuItem("Open CLU").apply { addActionListener { host.api.openShell?.invoke() } })
            menu.add(MenuItem("Unload the model now").apply { addActionListener { kotlinx.coroutines.runBlocking { host.core.llm.unload() } } })
            menu.addSeparator()
            menu.add(MenuItem("Quit CLU").apply { addActionListener { SystemTray.getSystemTray().remove(icon); quit() } })
            icon.addActionListener { host.api.openShell?.invoke() } // double-click
            SystemTray.getSystemTray().add(icon)
            true
        }.getOrDefault(false)
    }

    /** CLU's mark: a cyan hexagon, drawn at run time (no image files to ship). */
    fun icon(size: Int = 64): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { img ->
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
        g.color = Color(62, 214, 230); g.stroke = BasicStroke(size / 12f); g.draw(hex)
        g.fillOval(size / 2 - size / 10, size / 2 - size / 10, size / 5, size / 5)
        g.dispose()
    }
}
