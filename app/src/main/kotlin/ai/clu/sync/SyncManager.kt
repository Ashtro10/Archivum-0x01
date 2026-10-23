package ai.clu.sync

import ai.clu.CluApp
import ai.clu.core.config.ServicePolicy
import ai.clu.core.config.Settings
import ai.clu.core.llm.LlmEngine
import ai.clu.core.sync.PairingHandler
import ai.clu.core.sync.PeerLocator
import ai.clu.core.sync.SyncNode
import ai.clu.core.sync.SyncResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

data class SyncUiState(val serving: Boolean = false, val port: Int = 0, val lastResult: SyncResult? = null, val syncing: Boolean = false,
                       val discovered: List<String> = emptyList(), val localAddresses: List<String> = emptyList(), val pairingCode: String? = null)

/**
 * Local-network continuity between the phone and tablet: TCP server, mDNS advertisement/discovery, the phone
 * hotspot gateway as a fallback address, periodic sync and the home device's model as a remote engine.
 */
class SyncManager(private val app: CluApp) {
    private val core = app.core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> get() = _state
    private val discovered = ConcurrentHashMap<String, String>() // service name -> host:port
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var periodic: Job? = null
    @Volatile private var remote: SyncNode.RemoteEngine? = null

    /** UI hook: shows the 6-digit code and returns the user's decision. */
    @Volatile var pairingPrompt: (suspend (String, String, String) -> Boolean)? = null

    val node: SyncNode = core.syncNode(PairingHandler { name, role, sas ->
        _state.value = _state.value.copy(pairingCode = sas)
        val ok = pairingPrompt?.invoke(name, role, sas) ?: false
        _state.value = _state.value.copy(pairingCode = null)
        ok
    })

    val locator = PeerLocator(core.settings, core.syncEngine, { discovered.values.toList() }, ::gatewayAddress)

    fun start() {
        core.remoteEngineProvider = { remoteEngine() }
        scope.launch { core.settings.observeAny(listOf(Settings.SYNC_ENABLED, Settings.SYNC_SERVE, Settings.SYNC_PORT, Settings.SYNC_INTERVAL)).collect { apply() } }
    }

    private fun apply() {
        val serve = ServicePolicy.shouldServeSync(core.settings)
        if (serve && !node.isServing) runCatching {
            val port = node.startServer(core.settings.int(Settings.SYNC_PORT))
            registerNsd(port)
            _state.value = _state.value.copy(serving = true, port = port)
        }.onFailure { core.diag.w("sync", "server failed: ${it.message}") }
        if (!serve && node.isServing) { node.stopServer(); unregisterNsd(); _state.value = _state.value.copy(serving = false) }
        if (core.settings.bool(Settings.SYNC_ENABLED)) discover() else stopDiscovery()
        periodic?.cancel()
        ServicePolicy.syncIntervalMs(core.settings)?.let { ms ->
            periodic = scope.launch { while (isActive) { delay(ms); if (core.syncEngine.peers().isNotEmpty()) syncNow() } }
        }
        _state.value = _state.value.copy(localAddresses = localAddresses())
    }

    suspend fun syncNow(): SyncResult {
        _state.value = _state.value.copy(syncing = true)
        val r = node.syncAny(locator.candidates())
        _state.value = _state.value.copy(syncing = false, lastResult = r)
        return r
    }

    suspend fun pairWith(address: String): SyncResult {
        val (h, p) = SyncNode.splitHostPort(address, core.settings.int(Settings.SYNC_PORT))
        val r = node.pair(h, p)
        _state.value = _state.value.copy(lastResult = r)
        return r
    }

    fun openPairing() = node.openPairingWindow()

    /** Remote engine on the paired home device, if reachable and it has a model loaded. */
    private suspend fun remoteEngine(): LlmEngine? = withContext(Dispatchers.IO) {
        remote?.let { if (it.probe()) return@withContext it }
        val home = core.syncEngine.peers().firstOrNull { it.role == "home" } ?: return@withContext null
        for (c in (listOfNotNull(home.lastAddress) + locator.candidates()).distinct()) {
            val (h, p) = SyncNode.splitHostPort(c, core.settings.int(Settings.SYNC_PORT))
            val r = node.remoteEngine(h, p)
            if (r.probe()) { remote = r; return@withContext r }
            r.close()
        }
        null
    }

    // ---------------------------------------------------------------- discovery
    private val nsd get() = app.getSystemService(NsdManager::class.java)

    private fun registerNsd(port: Int) {
        unregisterNsd()
        val info = NsdServiceInfo().apply { serviceName = "Clu-" + core.store.deviceId.take(8); serviceType = SERVICE_TYPE; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { core.diag.i("sync", "mDNS registered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { core.diag.w("sync", "mDNS registration failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdRegistration = l }
    }

    private fun unregisterNsd() { nsdRegistration?.let { runCatching { nsd.unregisterService(it) } }; nsdRegistration = null }

    private fun discover() {
        if (nsdDiscovery != null) return
        multicastLock = app.getSystemService(WifiManager::class.java).createMulticastLock("clu-nsd").apply { setReferenceCounted(false); runCatching { acquire() } }
        val myName = "Clu-" + core.store.deviceId.take(8)
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName == myName) return
                runCatching {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val host = r.host?.hostAddress ?: return
                            discovered[r.serviceName] = "$host:${r.port}"
                            _state.value = _state.value.copy(discovered = discovered.values.toList())
                        }
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                    })
                }
            }
            override fun onServiceLost(s: NsdServiceInfo) { discovered.remove(s.serviceName); _state.value = _state.value.copy(discovered = discovered.values.toList()) }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { core.diag.w("sync", "mDNS discovery failed $e"); nsdDiscovery = null }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l); nsdDiscovery = l }
    }

    private fun stopDiscovery() {
        nsdDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; nsdDiscovery = null
        runCatching { multicastLock?.release() }
    }

    /** IPv4 default gateway of the current network: on a phone hotspot this is the phone itself. */
    fun gatewayAddress(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    fun localAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    }.getOrDefault(emptyList())

    companion object { const val SERVICE_TYPE = "_clu-sync._tcp." }
}
