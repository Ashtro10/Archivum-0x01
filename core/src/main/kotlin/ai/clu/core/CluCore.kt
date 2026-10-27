package ai.clu.core

import ai.clu.core.agent.CluAgent
import ai.clu.core.agent.InferenceRouter
import ai.clu.core.commands.CommandRepository
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.conversation.ConversationRepository
import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlDriver
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.learning.PreferenceEngine
import ai.clu.core.llm.LlmEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ai.clu.core.llm.LlmPlatform
import ai.clu.core.llm.LlmService
import ai.clu.core.llm.ModelRegistry
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.memory.ProposalRepository
import ai.clu.core.persona.*
import ai.clu.core.proactive.ProactivePlanner
import ai.clu.core.security.*
import ai.clu.core.sync.KeyVault
import ai.clu.core.sync.PairingHandler
import ai.clu.core.sync.SyncEngine
import ai.clu.core.sync.SyncNode
import ai.clu.core.tools.*
import ai.clu.core.util.Clock
import ai.clu.core.util.SystemClock

/** Platform services supplied by the host (Android app or tests). */
interface CluPlatform {
    val llm: LlmPlatform
    val http: HttpFetcher
    val keyVault: KeyVault
    fun isOnline(): Boolean
    fun deviceName(): String
    /** Secret storage for API keys (Android: Keystore-encrypted). */
    fun secret(name: String): String?
}

/**
 * Composition root: one Clu entity per installation, regardless of which device runs it. All components operate on
 * behalf of this single identity; they are modules, not separate agents.
 */
class CluCore(val db: SqlDriver, val platform: CluPlatform, val clock: Clock = SystemClock) {
    val store = CluStore(db, clock)
    val settings = SettingsStore(store)
    val diag = DiagnosticsLog(store, settings)
    val conversations = ConversationRepository(store)
    val memories = MemoryRepository(store, settings)
    val proposals = ProposalRepository(store, settings, memories)
    /** The owner's diary: separate from memory; entries reach memory only when promoted. */
    val diary = ai.clu.core.diary.DiaryRepository(store, memories)
    /** Web search through documented provider APIs (key/URL configured on the device; none built in). */
    val search = ai.clu.core.search.SearchService(settings, platform.http, { platform.secret(BRAVE_SEARCH_KEY) }, clock::now)
    val prefs = PreferenceEngine(store, settings)
    val characters = CharacterRepository(store, memories)
    val personas = PersonaManager(settings, characters)
    val people = PeopleRepository(store)
    val credentials = CredentialStore(store)
    val auth = AuthorizationPolicy(settings, people, clock::now)
    val commands = CommandRepository(store)
    val templates = ResponseTemplates(settings)
    val promptBuilder = PromptBuilder(settings, prefs)
    val models = ModelRegistry(store)
    val llm = LlmService(settings, models, platform.llm, diag, clock::now)
    val network = NetworkPolicy(settings, platform::isOnline)
    val toolRegistry = ToolRegistry()
    val tools = ToolExecutor(store, toolRegistry, settings, auth, network, diag)
    val proactive = ProactivePlanner(store, settings, memories, templates, diag = diag)
    /** Optional LiveKit voice path: room tokens from the owner's token endpoint (no LiveKit secrets in the app). */
    val liveKitTokens = ai.clu.core.livekit.TokenProvisioner(settings, platform.http, { platform.secret(LIVEKIT_DEVICE_KEY) }, clock::now)
    val syncEngine = SyncEngine(store, settings, conversations, memories, characters, people, prefs, commands, diag, diary)
    val syncFiles = ai.clu.core.sync.SyncBundle(store, syncEngine)

    /** Remote engine provider (set by the app when a paired home device is configured). */
    @Volatile var remoteEngineProvider: (suspend () -> LlmEngine?)? = null

    val inference = object : InferenceRouter {
        override suspend fun select(): LlmEngine? {
            val mode = settings.string(Settings.REMOTE_INFERENCE)
            val iAmHome = settings.string(Settings.DEVICE_ROLE) == "home"
            val remote = if (iAmHome || mode == "local") null else remoteEngineProvider
            return when (mode) {
                "prefer_home" -> remote?.invoke() ?: llm.engine()
                "fallback" -> llm.engine() ?: remote?.invoke()
                else -> llm.engine()
            }
        }
        override fun unavailableReason(): String? = llm.state.value.takeIf { it.status == ai.clu.core.llm.LlmStatus.ERROR }?.message
    }

    val agent = CluAgent(settings, conversations, memories, proposals, prefs, personas, promptBuilder, templates, tools, commands, auth, network,
        inference, diag, clock::now, deviceRole = { settings.string(Settings.DEVICE_ROLE) }, diary = diary)

    private var node: SyncNode? = null

    fun syncNode(pairing: PairingHandler): SyncNode = node ?: SyncNode(store, settings, syncEngine, platform.keyVault, platform::deviceName, pairing,
        inferenceProvider = { llm.engine() }, diag).also { node = it }

    init {
        registerCoreTools()
        proposals.diarySink = { text, at, conv ->
            diary.add(text, source = ai.clu.core.diary.DiarySource.CONVERSATION, conversationId = conv, date = ai.clu.core.diary.DiaryRepository.dayOf(at)) }
        people.ensureOwner(settings.string(Settings.OWNER_NAME))
        commands.seedDefaults()
        memories.purgeExpired()
        tools.purgeHistory()
        diag.i("core", "Clu core started (entity ${store.entityId.take(8)}, device ${store.deviceId.take(8)})")
    }

    private fun registerCoreTools() {
        val web = WebSearchTool(search)
        val geo = Geocoder(platform.http)
        listOf(TimeTool(), CalcTool(), MemorySearchTool(memories), RememberTool(memories, settings), DiaryRecentTool(diary), DiarySearchTool(diary),
            DiaryAddTool(diary, conversations), PersonaSwitchTool(personas),
            web, GeocodeTool(geo), DistanceTool(platform.http, geo), TrainTool(web), ExternalAiTool(platform.http, settings) { platform.secret("external_ai_key") })
            .forEach(toolRegistry::register)
    }

    companion object {
        /** SecretStore name of the per-device key that authorises token requests at the owner's token endpoint. */
        const val LIVEKIT_DEVICE_KEY = "livekit_device_key"
        /** SecretStore name of the owner's Brave Search API key (entered on the device; never in the app). */
        const val BRAVE_SEARCH_KEY = "brave_search_key"
    }

    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Typed chat requests, owned by the app rather than a screen. */
    val chat = ai.clu.core.agent.ChatTurns(agent, warmScope)

    /** Starts the model service and warms the instruction prefix after every model load (see [CluAgent.warmUp]). */
    fun startModel() {
        llm.start()
        warmScope.launch {
            var warmed = 0L
            llm.state.collect { st ->
                if (st.status == ai.clu.core.llm.LlmStatus.READY && st.loadSeq != warmed) { warmed = st.loadSeq; agent.warmUp() }
            }
        }
    }

    fun shutdown() {
        warmScope.cancel()
        node?.shutdown()
        llm.shutdown()
    }
}
