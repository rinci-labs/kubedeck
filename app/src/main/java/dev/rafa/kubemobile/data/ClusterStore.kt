package dev.rafa.kubemobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.config.KeystoreCrypto
import dev.rafa.kubemobile.k8s.KubeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.clusterDataStore: DataStore<Preferences> by preferencesDataStore(name = "clusters")

/**
 * Encrypted-at-rest store for cluster profiles. The payload is AES-GCM encrypted with a
 * hardware-backed key, so `adb backup` / rooted dumps expose nothing usable.
 */
class ClusterStore(private val context: Context) {

    private val profilesKey = stringPreferencesKey("profiles.v1")
    private val activeKey = stringPreferencesKey("active.v1")

    val profiles: Flow<List<ClusterProfile>> = context.clusterDataStore.data.map { prefs ->
        decode(prefs[profilesKey])
    }

    val activeId: Flow<String?> = context.clusterDataStore.data.map { it[activeKey] }

    suspend fun snapshot(): List<ClusterProfile> = profiles.first()

    suspend fun activeProfile(): ClusterProfile? {
        val prefs = context.clusterDataStore.data.first()
        val list = decode(prefs[profilesKey])
        val id = prefs[activeKey]
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    suspend fun setActive(id: String) {
        context.clusterDataStore.edit { it[activeKey] = id }
    }

    suspend fun touch(id: String) {
        mutate { list -> list.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it } }
    }

    /**
     * Adds profiles, replacing any existing entry with the same server + context name so repeated
     * imports of an evolving kubeconfig do not pile up duplicates.
     */
    suspend fun upsert(incoming: List<ClusterProfile>): List<ClusterProfile> {
        var added = emptyList<ClusterProfile>()
        mutate { existing ->
            val byIdentity = existing.associateBy { identity(it) }
            val merged = existing.toMutableList()
            added = incoming.map { profile ->
                val key = identity(profile)
                val previous = byIdentity[key]
                val resolved = if (previous != null) {
                    profile.copy(
                        id = previous.id,
                        createdAt = previous.createdAt,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                } else {
                    profile.copy(
                        createdAt = if (profile.createdAt == 0L) System.currentTimeMillis() else profile.createdAt,
                    )
                }
                val index = merged.indexOfFirst { identity(it) == key }
                if (index >= 0) merged[index] = resolved else merged += resolved
                resolved
            }
            merged
        }
        return added
    }

    suspend fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    suspend fun update(profile: ClusterProfile) {
        mutate { list -> list.map { if (it.id == profile.id) profile else it } }
    }

    private fun identity(profile: ClusterProfile): String =
        "${profile.server.trimEnd('/')}|${profile.contextName ?: profile.name}"

    private suspend fun mutate(transform: (List<ClusterProfile>) -> List<ClusterProfile>) {
        context.clusterDataStore.edit { prefs ->
            val current = decode(prefs[profilesKey])
            val next = transform(current)
            prefs[profilesKey] = encode(next)
            if (prefs[activeKey] !in next.map { it.id }) {
                prefs[activeKey] = next.firstOrNull()?.id.orEmpty()
            }
        }
    }

    private fun encode(list: List<ClusterProfile>): String {
        val json = KubeJson.encodeToString(ListSerializer(ClusterProfile.serializer()), list)
        val encrypted = KeystoreCrypto.encryptText(json)
        return java.util.Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decode(encoded: String?): List<ClusterProfile> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val blob = java.util.Base64.getDecoder().decode(encoded)
            val json = KeystoreCrypto.decryptText(blob)
            KubeJson.decodeFromString(ListSerializer(ClusterProfile.serializer()), json)
        }.getOrElse { emptyList() }
    }
}
