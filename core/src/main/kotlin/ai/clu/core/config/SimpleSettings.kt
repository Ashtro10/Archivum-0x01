package ai.clu.core.config

/**
 * The settings shown in the normal Settings screen, grouped by what the owner wants to do. Everything else (engine
 * internals, sync, LiveKit, sampling parameters...) stays reachable under "All settings (advanced)". Every key listed
 * here is a real registry setting with an automated test of its effect (SimpleSettingsTest checks they exist).
 */
object SimpleSettings {
    data class Section(val id: String, val title: String, val description: String, val keys: List<String>)

    val CONVERSATION = Section("conversation", "Conversation", "How Clu talks to you.", listOf(
        Settings.OWNER_NAME, Settings.OWNER_ADDRESS, Settings.RESPONSE_LENGTH, Settings.EXPLANATION, Settings.PROACTIVITY, Settings.LANGUAGE, Settings.DIRECTNESS, Settings.HUMOR,
        Settings.CUSTOM_INSTRUCTIONS, Settings.SPEAK_RESPONSES, Settings.QUIET_ENABLED, Settings.QUIET_START, Settings.QUIET_END))
    val MEMORY = Section("memory", "Memory & diary", "What Clu remembers between conversations, and how conversation summaries reach your diary.", listOf(
        Settings.MEMORY_ENABLED, Settings.MEMORY_RETRIEVAL, Settings.DIARY_MODE, Settings.PROPOSE_INSIGHTS))
    val TOOLS = Section("tools", "Search & internet", "What Clu may look up and do for you. Ordinary chat and voice never need the internet.", listOf(
        Settings.NET_ENABLED, Settings.NET_WEB, Settings.SEARCH_AUTO, Settings.SEARCH_PROVIDER, Settings.SEARXNG_URL, Settings.NET_MAPS, Settings.NET_TRAVEL,
        Settings.OFFLINE_BEHAVIOR, Settings.APP_POLICY, Settings.SHOW_SOURCES))
    val all = listOf(CONVERSATION, MEMORY, TOOLS)
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}
