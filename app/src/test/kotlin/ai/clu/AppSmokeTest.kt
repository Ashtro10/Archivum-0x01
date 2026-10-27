package ai.clu

import ai.clu.core.config.SettingCategory
import ai.clu.core.config.Settings
import ai.clu.core.memory.CLU_SCOPE
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.*
import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import android.view.View
import android.view.ViewGroup

/**
 * Runs the real application and every screen on Android 13 framework code (Robolectric) with the real
 * Android SQLite driver. Catches runtime crashes in startup, UI construction and the Android persistence layer.
 * Native engines (llama.cpp, sherpa-onnx) are not loaded on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34, 35], application = CluApp::class)
class AppSmokeTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp

    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }

    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T {
        val c = if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)
        val a = c.setup().get()
        idle()
        return a
    }

    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()

    @Test fun applicationStartsWithAllSubsystems() {
        val core = app.core
        assertEquals("Clu", core.settings.string(Settings.CLU_NAME))
        listOf("app.open", "alarm.set", "timer.set", "device.flashlight", "maps.open", "web.search", "memory.remember", "device.status")
            .forEach { assertNotNull("tool $it registered", core.toolRegistry[it]) }
        assertNotNull(core.people.owner())
        assertTrue(app.statusLine().contains("Model: none"))
    }

    @Test fun androidSqliteDriverRunsCoreRepositories() {
        val core = app.core
        core.settings.set(Settings.HUMOR, "none")
        assertEquals("none", core.settings.string(Settings.HUMOR))
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Lonavala trip", content = "Trip to Lonavala with Ashutosh; owe him 2000",
            source = MemorySource.OWNER_SUPPLIED, people = listOf("Ashutosh")))
        val hits = core.memories.rank("How much do I owe Ashutosh?", listOf(CLU_SCOPE))
        assertEquals("Lonavala trip", hits.first().memory.title)
        val conv = core.conversations.create()
        core.conversations.addMessage(conv.id, ai.clu.core.conversation.Role.USER, "hello")
        assertEquals(1, core.conversations.messages(conv.id).size)
        assertTrue(core.conversations.search("hello").isNotEmpty())
        core.credentials.set(ai.clu.core.security.CredentialStore.Kind.PIN, "482916")
        assertEquals(ai.clu.core.security.CredentialStore.VerifyResult.Ok, core.credentials.verify(ai.clu.core.security.CredentialStore.Kind.PIN, "482916"))
    }

    @Test fun chatScreenSendsAMessageAndShowsAnHonestReply() {
        app.core.store.setMeta("onboarded", "1")
        val a = launch(MainActivity::class.java)
        val views = allViews(a.window.decorView)
        val input = views.filterIsInstance<EditText>().first()
        input.setText("tell me something interesting")
        views.filterIsInstance<android.widget.TextView>().first { it.contentDescription == "Send" }.performClick()
        val deadline = System.currentTimeMillis() + 10_000
        val list = views.filterIsInstance<ListView>().first()
        while (System.currentTimeMillis() < deadline) { idle(); if (app.core.conversations.messages(app.currentConversation()).size >= 2) break }
        val msgs = app.core.conversations.messages(app.currentConversation())
        assertEquals(2, msgs.size)
        assertTrue(msgs[1].content.contains("language model isn't loaded"))
        idle()
        assertTrue(list.adapter.count >= 2)
    }

    @Test fun deviceCommandRunsThroughToolsFromChat() {
        app.core.store.setMeta("onboarded", "1")
        val a = launch(MainActivity::class.java)
        val views = allViews(a.window.decorView)
        views.filterIsInstance<EditText>().first().setText("what time is it?")
        views.filterIsInstance<android.widget.TextView>().first { it.contentDescription == "Send" }.performClick()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { idle(); if (app.core.conversations.messages(app.currentConversation()).size >= 2) break }
        assertTrue(app.core.conversations.messages(app.currentConversation())[1].content.startsWith("It is"))
    }

    @Test fun everyScreenOpens() {
        listOf(ConversationsActivity::class.java, MemoryActivity::class.java, MemoryEditActivity::class.java, ProposalsActivity::class.java, DiaryActivity::class.java, DiaryEditActivity::class.java,
            SettingsActivity::class.java, WakeWordsActivity::class.java, ModelsActivity::class.java, VoiceActivity::class.java,
            CharactersActivity::class.java, CharacterEditActivity::class.java, PeopleActivity::class.java, CommandsActivity::class.java,
            DevicesActivity::class.java, PreferencesActivity::class.java, ToolHistoryActivity::class.java, DiagnosticsActivity::class.java,
            ConnectionActivity::class.java, VoiceChatActivity::class.java)
            .forEach { cls -> val a = launch(cls); assertFalse("${cls.simpleName} finished unexpectedly", a.isFinishing) }
    }

    @Test fun everySettingsCategoryRendersAControlPerSetting() {
        app.core.settings.set(Settings.DEV_DIAGNOSTICS, true)
        for (cat in SettingCategory.values()) {
            val a = launch(SettingsCategoryActivity::class.java, Intent(RuntimeEnvironment.getApplication(), SettingsCategoryActivity::class.java).putExtra("cat", cat.name))
            val texts = allViews(a.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
            for (d in ai.clu.core.config.UiPolicy.visibleSettings(app.core.settings, cat))
                assertTrue("${d.key} has a control in ${cat.name}", texts.any { it.startsWith(d.title) })
        }
    }

    @Test fun memoryInserterSavesOriginalText() {
        val a = launch(MemoryEditActivity::class.java)
        val edits = allViews(a.window.decorView).filterIsInstance<EditText>()
        edits[0].setText("Old chat with Ashutosh")
        edits[3].setText("Ashutosh: are we going to Goa?\nMe: yes in December")
        allViews(a.window.decorView).filterIsInstance<Button>().first { it.text == "Save" }.performClick()
        idle()
        val m = app.core.memories.list().first { it.title == "Old chat with Ashutosh" }
        assertEquals("Ashutosh: are we going to Goa?\nMe: yes in December", m.content)
        assertEquals(MemorySource.OWNER_SUPPLIED, m.source)
    }
}
