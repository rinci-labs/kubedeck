package dev.rafa.kubemobile.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import dev.rafa.kubemobile.BuildConfig
import dev.rafa.kubemobile.k8s.KubeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateResult.NetworkError("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body.string().ifBlank {
                return@withContext UpdateResult.ParseError("Empty response")
            }

            val release: GithubRelease = try {
                KubeJson.decodeFromString(body)
            } catch (e: Exception) {
                return@withContext UpdateResult.ParseError(e.message ?: "JSON parse error")
            }

            if (!isNewerVersion(release.tag_name, BuildConfig.VERSION_NAME)) {
                return@withContext UpdateResult.UpToDate
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext UpdateResult.ParseError("No APK asset in release")
            val sumsAsset = release.assets.firstOrNull {
                it.name.equals("SHA256SUMS.txt", ignoreCase = true)
            }

            UpdateResult.Available(release, apkAsset, sumsAsset)
        } catch (e: Exception) {
            UpdateResult.NetworkError(e.message ?: "Unknown error")
        }
    }

    suspend fun downloadUpdate(
        asset: ReleaseAsset,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "update.apk")

            val request = Request.Builder()
                .url(asset.browserDownloadUrl)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body
            val totalBytes = responseBody.contentLength().let { if (it > 0) it else asset.size }

            responseBody.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            onProgress((bytesRead.toFloat() / totalBytes.toFloat()).coerceAtMost(1f))
                        }
                    }
                }
            }

            onProgress(1f)
            outFile
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    suspend fun verifyApk(
        apkFile: File,
        sumsAsset: ReleaseAsset?,
    ): InstallResult? = withContext(Dispatchers.IO) {
        try {
            // (a) SHA-256 verification
            if (sumsAsset != null) {
                val sumsText = fetchText(sumsAsset.browserDownloadUrl)
                if (sumsText != null) {
                    val actualHash = sha256Hex(apkFile)
                    val expectedHash = parseExpectedHash(sumsText, apkFile.name)
                    if (expectedHash != null && !expectedHash.equals(actualHash, ignoreCase = true)) {
                        return@withContext InstallResult.HashMismatch(expectedHash, actualHash)
                    }
                }
            }

            // (b) Package name verification
            val archiveInfo = getArchiveInfo(apkFile)
            if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                return@withContext InstallResult.PackageMismatch
            }

            // (c) Signing certificate verification
            val installedFingerprint = getInstalledCertFingerprint()
            val archiveFingerprint = getArchiveCertFingerprint(archiveInfo)
            if (installedFingerprint == null || archiveFingerprint == null ||
                !installedFingerprint.equals(archiveFingerprint, ignoreCase = true)
            ) {
                return@withContext InstallResult.CertMismatch
            }

            null
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Verification failed")
        }
    }

    suspend fun verifyAndInstall(
        apkFile: File,
        sumsAsset: ReleaseAsset?,
        activity: Activity,
    ): InstallResult = withContext(Dispatchers.IO) {
        val check = verifyApk(apkFile, sumsAsset)
        if (check != null) return@withContext check

        try {
            // (d) Install permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    return@withContext InstallResult.NeedsPermission
                }
            }

            // (e) Launch installer via FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            InstallResult.Launched
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun fetchText(url: String): String? = try {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) response.body.string() else null
    } catch (_: Exception) {
        null
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseExpectedHash(sumsText: String, fileName: String): String? {
        for (line in sumsText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            // Standard format: "hash  filename" or "hash *filename"
            val parts = trimmed.split("\\s+".toRegex(), limit = 2)
            if (parts.size == 2) {
                val hash = parts[0]
                val name = parts[1].removePrefix("*")
                if (name == fileName && hash.length == 64) return hash
            }
        }
        // Fallback: if only one APK hash line exists, use the first 64-char hex
        for (line in sumsText.lineSequence()) {
            val trimmed = line.trim()
            val parts = trimmed.split("\\s+".toRegex(), limit = 2)
            if (parts.isNotEmpty() && parts[0].length == 64 && parts[0].all { it in "0123456789abcdefABCDEF" }) {
                val name = parts.getOrNull(1)?.removePrefix("*").orEmpty()
                if (name.endsWith(".apk")) return parts[0]
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(apkFile: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES).toLong(),
                ),
            )
        } else {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledCertFingerprint(): String? {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        return certFingerprint(pkgInfo)
    }

    @Suppress("DEPRECATION")
    private fun getArchiveCertFingerprint(archiveInfo: PackageInfo): String? {
        return certFingerprint(archiveInfo)
    }

    @Suppress("DEPRECATION")
    private fun certFingerprint(pkgInfo: PackageInfo): String? {
        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        }
        if (certBytes == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(certBytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/rinci-labs/kubedeck/releases/latest"
    }
}
