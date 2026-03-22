package com.cirabit.android.update

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object AppUpdateCheckService {
    private const val TAG = "AppUpdateCheckService"
    private const val UPDATE_ENDPOINT = "https://cirabit.smaia.dev/api/latest-release"
    private const val GITHUB_FALLBACK_ENDPOINT = "https://api.github.com/repos/sarahsec/cirabit-android/releases/latest"
    const val DOWNLOAD_PAGE_URL = "https://cirabit.smaia.dev/download"

    data class LatestReleaseInfo(
        val version: String?,
        val releaseUrl: String?
    )

    private val checkMutex = Mutex()
    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking
    private val updateHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(40, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdatesIfNeeded(context: Context, force: Boolean = false) {
        AppUpdatePreferenceManager.init(context)

        if (!AppUpdatePreferenceManager.isEnabled()) return
        if (!force && !AppUpdatePreferenceManager.shouldCheckNow()) return

        if (!checkMutex.tryLock()) return
        _isChecking.value = true

        try {
            val currentVersion = getCurrentVersionName(context)
            val latestReleaseInfo = fetchLatestReleaseInfo()
            val updateAvailable = latestReleaseInfo.version?.let { latest ->
                isRemoteVersionNewer(latest, currentVersion)
            } ?: false

            AppUpdatePreferenceManager.markCheckSuccess(
                context = context,
                currentVersion = currentVersion,
                latestVersion = latestReleaseInfo.version,
                releaseUrl = latestReleaseInfo.releaseUrl,
                updateAvailable = updateAvailable
            )
        } catch (error: CancellationException) {
            Log.d(TAG, "Version check cancelled")
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Version check failed", error)
            AppUpdatePreferenceManager.markCheckFailure(
                context = context,
                errorMessage = error.message ?: "Unknown error while checking updates"
            )
        } finally {
            _isChecking.value = false
            checkMutex.unlock()
        }
    }

    private suspend fun fetchLatestReleaseInfo(): LatestReleaseInfo = withContext(Dispatchers.IO) {
        val endpoints = listOf(UPDATE_ENDPOINT, GITHUB_FALLBACK_ENDPOINT)
        var lastError: Exception? = null

        for (endpoint in endpoints) {
            try {
                val info = fetchLatestReleaseInfoFromEndpoint(endpoint)
                Log.d(TAG, "Update endpoint ok: $endpoint (version=${info.version})")
                return@withContext info
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Update endpoint failed: $endpoint", error)
            }
        }

        throw IllegalStateException(
            "All update endpoints failed",
            lastError
        )
    }

    private fun fetchLatestReleaseInfoFromEndpoint(endpoint: String): LatestReleaseInfo {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "cirabit-android-update-check")
            .get()
            .build()

        return updateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Update API responded with ${response.code}")
            }

            val body = response.body?.string()
                ?: throw IllegalStateException("Update API returned empty body")

            val root = JsonParser.parseString(body).asJsonObject
            val release = if (root.has("release") && root.get("release").isJsonObject) {
                root.getAsJsonObject("release")
            } else {
                root
            }

            val latestVersion = release.firstStringOf("tag_name", "tagName", "name")
            val htmlUrl = release.firstStringOf("html_url", "htmlUrl", "url")
            val assetUrl = release.firstAssetDownloadUrl()

            LatestReleaseInfo(
                version = latestVersion,
                releaseUrl = htmlUrl ?: assetUrl
            )
        }
    }

    private fun getCurrentVersionName(context: Context): String {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    private fun isRemoteVersionNewer(remoteVersionRaw: String, currentVersionRaw: String): Boolean {
        val remote = parseVersionSegments(remoteVersionRaw)
        val current = parseVersionSegments(currentVersionRaw)
        val max = maxOf(remote.size, current.size)

        for (index in 0 until max) {
            val remotePart = remote.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (remotePart != currentPart) {
                return remotePart > currentPart
            }
        }

        return false
    }

    private fun parseVersionSegments(versionRaw: String): List<Int> {
        val normalized = versionRaw
            .trim()
            .lowercase()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')

        return normalized
            .split('.')
            .mapNotNull { segment ->
                segment.takeWhile { it.isDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.toIntOrNull()
            }
            .ifEmpty { listOf(0) }
    }

    private fun JsonObject.firstStringOf(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key)) continue
            val element = get(key)
            if (!element.isJsonPrimitive) continue
            val value = element.asString?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.firstAssetDownloadUrl(): String? {
        if (!has("assets") || !get("assets").isJsonArray) return null
        val assets = getAsJsonArray("assets")

        for (assetElement in assets) {
            if (!assetElement.isJsonObject) continue
            val asset = assetElement.asJsonObject
            val downloadUrl = asset.firstStringOf("browser_download_url", "url")
            if (downloadUrl.isNullOrBlank()) continue
            if (downloadUrl.endsWith(".apk", ignoreCase = true)) return downloadUrl
        }

        for (assetElement in assets) {
            if (!assetElement.isJsonObject) continue
            val asset = assetElement.asJsonObject
            val downloadUrl = asset.firstStringOf("browser_download_url", "url")
            if (!downloadUrl.isNullOrBlank()) return downloadUrl
        }

        return null
    }
}
