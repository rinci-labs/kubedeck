package dev.rafa.kubemobile.config

import kotlinx.serialization.Serializable

/**
 * A single cluster connection, flattened from one kubeconfig `cluster` + `context` + `user` triple.
 * Everything needed to talk to the API server lives here, including the TLS material.
 */
@Serializable
data class ClusterProfile(
    val id: String,
    val name: String,
    val server: String,
    val contextName: String? = null,
    val namespace: String? = null,
    /** Bearer token, either from the kubeconfig or from an auth-provider block. */
    val token: String? = null,
    val username: String? = null,
    val password: String? = null,
    val caCertPem: String? = null,
    val clientCertPem: String? = null,
    val clientKeyPem: String? = null,
    val insecureSkipTlsVerify: Boolean = false,
    val tlsServerName: String? = null,
    val proxyUrl: String? = null,
    /** OIDC / GCP / Azure style auth-provider left in the kubeconfig. */
    val authProvider: String? = null,
    /** kubectl credential plugin; only executable on a desktop, kept so imports are lossless. */
    val execCommand: String? = null,
    val execArgs: List<String> = emptyList(),
    val source: String = SOURCE_IMPORT,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    /** Server URL normalised without a trailing slash. */
    val baseUrl: String get() = server.trimEnd('/')

    val authKind: AuthKind
        get() = when {
            !clientCertPem.isNullOrBlank() && !clientKeyPem.isNullOrBlank() -> AuthKind.CLIENT_CERT
            !token.isNullOrBlank() -> AuthKind.TOKEN
            !username.isNullOrBlank() -> AuthKind.BASIC
            else -> AuthKind.NONE
        }

    val displayNamespace: String get() = namespace?.takeIf { it.isNotBlank() } ?: "default"

    companion object {
        const val SOURCE_IMPORT = "kubeconfig"
        const val SOURCE_MANUAL = "manual"
    }
}

enum class AuthKind(val label: String) {
    CLIENT_CERT("Client certificate"),
    TOKEN("Bearer token"),
    BASIC("Basic auth"),
    NONE("Unauthenticated"),
}

data class ImportResult(
    val profiles: List<ClusterProfile>,
    val warnings: List<String>,
)
