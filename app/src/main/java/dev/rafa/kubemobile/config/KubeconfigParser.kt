package dev.rafa.kubemobile.config

import dev.rafa.kubemobile.k8s.KubeJson
import dev.rafa.kubemobile.k8s.YamlIo
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/**
 * Reads kubeconfig files. Every `contexts[]` entry becomes its own [ClusterProfile] so one file
 * holding staging + prod yields two switchable clusters, matching `kubectl config get-contexts`.
 */
object KubeconfigParser {

    fun parse(text: String, baseDir: File? = null): ImportResult {
        val root = YamlIo.parseAll(text).firstOrNull() as? JsonObject
            ?: return ImportResult(emptyList(), listOf("File is not a Kubernetes config document"))

        val clusters = root.arrayAt("clusters").orEmpty().mapNotNull { it as? JsonObject }
            .associateBy({ it.str("name").orEmpty() }, { it.objAt("cluster") ?: JsonObject(emptyMap()) })
        val users = root.arrayAt("users").orEmpty().mapNotNull { it as? JsonObject }
            .associateBy({ it.str("name").orEmpty() }, { it.objAt("user") ?: JsonObject(emptyMap()) })
        val contexts = root.arrayAt("contexts").orEmpty().mapNotNull { it as? JsonObject }

        if (contexts.isEmpty()) {
            return ImportResult(emptyList(), listOf("Config contains no contexts"))
        }

        val warnings = mutableListOf<String>()
        val profiles = contexts.mapNotNull { context ->
            val contextName = context.str("name") ?: return@mapNotNull null
            val body = context.objAt("context") ?: JsonObject(emptyMap())
            val clusterName = body.str("cluster")
            val cluster = clusterName?.let { clusters[it] }
            if (cluster == null) {
                warnings += "Context \"$contextName\" references unknown cluster \"$clusterName\""
                return@mapNotNull null
            }
            val server = cluster.str("server")
            if (server.isNullOrBlank()) {
                warnings += "Cluster \"$clusterName\" has no server URL"
                return@mapNotNull null
            }

            val user = body.str("user")?.let { users[it] }
            val auth = readAuth(user, baseDir, warnings, contextName)

            val caData = cluster.str("certificate-authority-data")?.let { decodeText(it) }
                ?: cluster.str("certificate-authority")?.let { readFile(it, baseDir, warnings) }

            ClusterProfile(
                id = UUID.randomUUID().toString(),
                name = contextName,
                server = server,
                contextName = contextName,
                namespace = body.str("namespace"),
                token = auth.token,
                username = auth.username,
                password = auth.password,
                caCertPem = caData?.takeIf { it.isNotBlank() },
                clientCertPem = auth.clientCert,
                clientKeyPem = auth.clientKey,
                insecureSkipTlsVerify = cluster["insecure-skip-tls-verify"].let { it != null && it.toString() == "true" },
                tlsServerName = cluster.str("tls-server-name"),
                proxyUrl = cluster.str("proxy-url"),
                authProvider = auth.provider,
                execCommand = auth.execCommand,
                execArgs = auth.execArgs,
                source = ClusterProfile.SOURCE_IMPORT,
                createdAt = System.currentTimeMillis(),
            )
        }

        if (profiles.isEmpty()) warnings += "No usable contexts found"
        return ImportResult(profiles, warnings)
    }

    private class Auth(
        val token: String? = null,
        val username: String? = null,
        val password: String? = null,
        val clientCert: String? = null,
        val clientKey: String? = null,
        val provider: String? = null,
        val execCommand: String? = null,
        val execArgs: List<String> = emptyList(),
    )

    private fun readAuth(
        user: JsonObject?,
        baseDir: File?,
        warnings: MutableList<String>,
        contextName: String,
    ): Auth {
        if (user == null) return Auth()

        val cert = user.str("client-certificate-data")?.let { decodeText(it) }
            ?: user.str("client-certificate")?.let { readFile(it, baseDir, warnings) }
        val key = user.str("client-key-data")?.let { decodeText(it) }
            ?: user.str("client-key")?.let { readFile(it, baseDir, warnings) }

        val token = user.str("token")
            ?: user.str("tokenFile")?.let { readFile(it, baseDir, warnings) }?.trim()
            ?: providerToken(user)

        val provider = user.objAt("auth-provider")?.str("name")
        val exec = user.objAt("exec")
        if (exec != null) {
            warnings += "Context \"$contextName\" uses the exec credential plugin " +
                "\"${exec.str("command")}\", which cannot run on Android. " +
                "Paste a token manually or use a kubeconfig with a static credential."
        }
        if (provider != null && token == null) {
            warnings += "Context \"$contextName\" uses the \"$provider\" auth-provider. " +
                "Import a kubeconfig that carries a valid access token, or set a token manually."
        }

        return Auth(
            token = token?.takeIf { it.isNotBlank() },
            username = user.str("username"),
            password = user.str("password"),
            clientCert = cert?.takeIf { it.isNotBlank() },
            clientKey = key?.takeIf { it.isNotBlank() },
            provider = provider,
            execCommand = exec?.str("command"),
            execArgs = exec?.arrayAt("args")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
        )
    }

    /** Pulls a usable bearer token out of the legacy auth-provider configs. */
    private fun providerToken(user: JsonObject): String? {
        val config = user.objAt("auth-provider")?.objAt("config") ?: return null
        return config.str("access-token")
            ?: config.str("id-token")
            ?: config.str("token")
    }

    private fun decodeText(value: String): String? = runCatching {
        String(dev.rafa.kubemobile.k8s.Pem.decodeBase64(value), Charsets.UTF_8)
    }.getOrNull()

    private fun readFile(path: String, baseDir: File?, warnings: MutableList<String>): String? {
        val candidates = buildList {
            add(File(path))
            if (baseDir != null) add(File(baseDir, path))
        }
        val resolved = candidates.firstOrNull { it.isFile } ?: run {
            warnings += "Referenced file not found: $path"
            return null
        }
        return runCatching { resolved.readText() }.getOrElse {
            warnings += "Could not read $path: ${it.message}"
            null
        }
    }

    fun encode(profile: ClusterProfile): String = KubeJson.encodeToString(profile)
}
