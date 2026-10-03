package dev.rafa.kubemobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.data.ClusterStore
import dev.rafa.kubemobile.data.KubeRepository
import dev.rafa.kubemobile.k8s.ApiCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifecycle of the one live connection the app keeps to the active cluster. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Connecting(val profile: ClusterProfile) : SessionState
    data class Ready(val session: ClusterSession, val catalog: ApiCatalog) : SessionState
    data class Failed(val profile: ClusterProfile?, val error: UiError) : SessionState
}

/**
 * Holds the cluster store, the repository and the single live [ClusterSession]. Every screen reads
 * its data through here so a cluster switch is one state change instead of a cache invalidation
 * cascade. Screen-local state (log buffers, editors, forwards) lives in the per-screen view models.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val store = ClusterStore(application)
    val repository = KubeRepository(application)

    val profiles: StateFlow<List<ClusterProfile>> =
        store.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> =
        store.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _namespaces = MutableStateFlow<List<String>>(emptyList())
    val namespaces: StateFlow<List<String>> = _namespaces.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Namespace choice per cluster and resource, kept in memory for the life of the process. */
    private val namespaceChoice = HashMap<String, String?>()

    /**
     * The namespace chosen on the Browse tab, per cluster. A single value rather than one per
     * resource: Browse picks the namespace *before* a kind is opened, and that choice is the
     * default the resource list inherits.
     */
    private val browseNamespaces = HashMap<String, String?>()

    /** Resource kinds opened on a cluster, most recent first. Drives the Browse "Recent" section. */
    private val recentKinds = HashMap<String, ArrayDeque<String>>()

    /**
     * Bumped every time a kind is opened, so a screen that reads the plain [recentKinds] map can
     * still react: Browse stays mounted on the back stack, and a value it captured on first
     * composition would otherwise never change.
     */
    private val _recentVersion = MutableStateFlow(0L)
    val recentVersion: StateFlow<Long> = _recentVersion.asStateFlow()

    private val connectLock = Mutex()
    private var namespaceJob: Job? = null

    val activeProfile: ClusterProfile?
        get() = (sessionState.value as? SessionState.Ready)?.session?.profile
            ?: profiles.value.firstOrNull { it.id == activeId.value }
            ?: profiles.value.firstOrNull()

    val session: ClusterSession?
        get() = (sessionState.value as? SessionState.Ready)?.session

    val catalog: ApiCatalog
        get() = (sessionState.value as? SessionState.Ready)?.catalog ?: ApiCatalog.EMPTY

    /** True once a session exists and discovery answered. */
    val isConnected: Boolean get() = session != null

    fun profileById(id: String?): ClusterProfile? = profiles.value.firstOrNull { it.id == id }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /** Fire-and-forget connect used by retry buttons; failures land in [sessionState]. */
    fun connectInBackground(profile: ClusterProfile, forceDiscovery: Boolean = false) {
        viewModelScope.launch {
            runCatching { connect(profile, forceDiscovery) }.onFailure { notify(it.shortMessage()) }
        }
    }

    /* --------------------------------------------------------------------------------------- */
    /* Connection                                                                               */
    /* --------------------------------------------------------------------------------------- */

    /**
     * Opens (or reuses) the session for [profile] and loads discovery. Concurrent callers share the
     * same attempt. On success the profile is marked active and its namespace list is refreshed.
     */
    suspend fun connect(profile: ClusterProfile, forceDiscovery: Boolean = false): ClusterSession {
        val current = session
        if (!forceDiscovery && current != null && current.profile.id == profile.id) return current
        return connectLock.withLock {
            val inside = session
            if (!forceDiscovery && inside != null && inside.profile.id == profile.id) return@withLock inside
            _sessionState.value = SessionState.Connecting(profile)
            val loaded = try {
                repository.session(profile, forceDiscovery)
            } catch (error: Throwable) {
                _sessionState.value = SessionState.Failed(profile, error.toUiError(discovery = true))
                throw error
            }
            _sessionState.value = SessionState.Ready(loaded, loaded.catalog)
            runCatching { store.setActive(profile.id) }
            runCatching { store.touch(profile.id) }
            refreshNamespaces(loaded)
            loaded
        }
    }

    fun refreshNamespaces(target: ClusterSession? = session) {
        val cluster = target ?: return
        namespaceJob?.cancel()
        namespaceJob = viewModelScope.launch {
            val values = runCatching { cluster.loadNamespaces() }.getOrNull() ?: cluster.namespaces
            if (cluster === session) _namespaces.value = values
        }
    }

    fun disconnect() {
        _sessionState.value = SessionState.Idle
        _namespaces.value = emptyList()
    }

    /** Drops cached discovery, list caches and pooled connections for the active cluster. */
    fun reloadDiscovery() {
        val profile = activeProfile ?: return
        repository.invalidate(profile.id)
        viewModelScope.launch {
            runCatching { connect(profile, forceDiscovery = true) }
                .onSuccess { notify(getApplication<Application>().getString(dev.rafa.kubemobile.R.string.settings_discovery_reloaded)) }
                .onFailure { notify(it.shortMessage()) }
        }
    }

    fun invalidateActive() {
        activeProfile?.let { repository.invalidate(it.id) }
    }

    /* --------------------------------------------------------------------------------------- */
    /* Namespace memory                                                                         */
    /* --------------------------------------------------------------------------------------- */

    fun rememberedNamespace(resourceKey: String, clusterId: String?): String? =
        clusterId?.let { namespaceChoice["$it|$resourceKey"] }

    fun rememberNamespace(resourceKey: String, clusterId: String?, namespace: String?) {
        if (clusterId == null) return
        namespaceChoice["$clusterId|$resourceKey"] = namespace
    }

    /** The namespace Browse is scoped to on this cluster, if one was chosen. */
    fun browseNamespace(clusterId: String?): String? = clusterId?.let { browseNamespaces[it] }

    fun rememberBrowseNamespace(clusterId: String?, namespace: String?) {
        if (clusterId == null) return
        browseNamespaces[clusterId] = namespace
    }

    /** Kinds opened on this cluster, newest first, so Browse can lead with what was just used. */
    fun recentResourceKeys(clusterId: String?): List<String> {
        val deque = clusterId?.let { recentKinds[it] } ?: return emptyList()
        return deque.toList()
    }

    fun rememberOpenedKind(clusterId: String?, resourceKey: String) {
        if (clusterId == null || resourceKey.isBlank()) return
        val deque = recentKinds.getOrPut(clusterId) { ArrayDeque() }
        deque.remove(resourceKey)
        deque.addFirst(resourceKey)
        while (deque.size > RECENT_KIND_LIMIT) deque.removeLast()
        _recentVersion.value++
    }

    /* --------------------------------------------------------------------------------------- */
    /* Profile mutations                                                                        */
    /* --------------------------------------------------------------------------------------- */

    suspend fun setActive(id: String) {
        store.setActive(id)
        val profile = profileById(id) ?: return
        runCatching { connect(profile) }.onFailure { notify(it.shortMessage()) }
    }

    suspend fun upsert(profiles: List<ClusterProfile>): List<ClusterProfile> = store.upsert(profiles)

    suspend fun update(profile: ClusterProfile) {
        store.update(profile)
        if (session?.profile?.id == profile.id) {
            // TLS material or the token may have changed; the pooled client must be rebuilt.
            repository.invalidate(profile.id)
            runCatching { connect(profile, forceDiscovery = false) }
        }
    }

    suspend fun updateProfile(profile: ClusterProfile) = update(profile)

    /** Flips TLS verification and rebuilds the connection, since the trust manager changed. */
    fun updateTlsVerification(profile: ClusterProfile, skipVerification: Boolean) {
        viewModelScope.launch {
            runCatching { update(profile.copy(insecureSkipTlsVerify = skipVerification)) }
                .onFailure { notify(it.shortMessage()) }
        }
    }

    /** Deletes a cluster's credentials and cached discovery, then reports the display name. */
    fun forget(profile: ClusterProfile, onDone: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.invalidate(profile.id)
                store.remove(profile.id)
                if (session?.profile?.id == profile.id) disconnect()
            }.onSuccess { onDone(profile.name) }
                .onFailure { notify(it.shortMessage()) }
        }
    }

    suspend fun removeProfile(id: String) {
        repository.invalidate(id)
        store.remove(id)
        if (session?.profile?.id == id) disconnect()
    }

    companion object {
        /** How many recently-opened kinds the Browse tab remembers per cluster. */
        private const val RECENT_KIND_LIMIT = 6
    }
}
