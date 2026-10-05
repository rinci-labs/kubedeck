package dev.rafa.kubemobile.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url")
    val browser_download_url: String = "",
) {
    val browserDownloadUrl: String get() = browser_download_url
}

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url")
    val html_url: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val tagName: String get() = tag_name
    val htmlUrl: String get() = html_url
}

sealed class UpdateResult {
    data object UpToDate : UpdateResult()
    data class Available(
        val release: GithubRelease,
        val apkAsset: ReleaseAsset,
        val sumsAsset: ReleaseAsset? = null,
    ) : UpdateResult()
    data class NetworkError(val msg: String) : UpdateResult()
    data class ParseError(val msg: String) : UpdateResult()
}

sealed class InstallResult {
    data object Launched : InstallResult()
    data object NeedsPermission : InstallResult()
    data class HashMismatch(val expected: String, val actual: String) : InstallResult()
    data object PackageMismatch : InstallResult()
    data object CertMismatch : InstallResult()
    data class Error(val msg: String) : InstallResult()
}

fun parseSemver(v: String): Triple<Int, Int, Int> {
    val cleaned = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    val parts = cleaned.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return Triple(major, minor, patch)
}

fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
    val remote = parseSemver(remoteVersion)
    val current = parseSemver(currentVersion)
    if (remote.first != current.first) return remote.first > current.first
    if (remote.second != current.second) return remote.second > current.second
    return remote.third > current.third
}
