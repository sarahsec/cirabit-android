package com.cirabit.android.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences-backed state for app update checks.
 */
object AppUpdatePreferenceManager {
    private const val PREFS_NAME = "cirabit_settings"
    private const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
    private const val KEY_LAST_CHECKED_AT_MS = "update_check_last_checked_at_ms"
    private const val KEY_CURRENT_VERSION = "update_check_current_version"
    private const val KEY_LATEST_VERSION = "update_check_latest_version"
    private const val KEY_RELEASE_URL = "update_check_release_url"
    private const val KEY_UPDATE_AVAILABLE = "update_check_update_available"
    private const val KEY_LAST_ERROR = "update_check_last_error"
    private const val KEY_LAST_PROMPT_TOKEN = "update_check_last_prompt_token"

    // 12 hours
    const val CHECK_INTERVAL_MS: Long = 12L * 60L * 60L * 1000L

    data class UpdateInfo(
        val lastCheckedAtMs: Long = 0L,
        val currentVersion: String? = null,
        val latestVersion: String? = null,
        val releaseUrl: String? = null,
        val updateAvailable: Boolean = false,
        val lastError: String? = null
    )

    private val _updateCheckEnabled = MutableStateFlow(true)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo

    fun init(context: Context) {
        val prefs = prefs(context)
        _updateCheckEnabled.value = prefs.getBoolean(KEY_UPDATE_CHECK_ENABLED, true)
        _updateInfo.value = UpdateInfo(
            lastCheckedAtMs = prefs.getLong(KEY_LAST_CHECKED_AT_MS, 0L),
            currentVersion = prefs.getString(KEY_CURRENT_VERSION, null),
            latestVersion = prefs.getString(KEY_LATEST_VERSION, null),
            releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
            updateAvailable = prefs.getBoolean(KEY_UPDATE_AVAILABLE, false),
            lastError = prefs.getString(KEY_LAST_ERROR, null)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_UPDATE_CHECK_ENABLED, enabled)
            .apply()
        _updateCheckEnabled.value = enabled
    }

    fun isEnabled(): Boolean = _updateCheckEnabled.value

    fun shouldCheckNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled()) return false
        val lastCheckedAtMs = _updateInfo.value.lastCheckedAtMs
        if (lastCheckedAtMs <= 0L) return true
        return nowMs - lastCheckedAtMs >= CHECK_INTERVAL_MS
    }

    fun markCheckSuccess(
        context: Context,
        currentVersion: String,
        latestVersion: String?,
        releaseUrl: String?,
        updateAvailable: Boolean,
        checkedAtMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHECKED_AT_MS, checkedAtMs)
            .putString(KEY_CURRENT_VERSION, currentVersion)
            .putString(KEY_LATEST_VERSION, latestVersion)
            .putString(KEY_RELEASE_URL, releaseUrl)
            .putBoolean(KEY_UPDATE_AVAILABLE, updateAvailable)
            .remove(KEY_LAST_ERROR)
            .apply()

        _updateInfo.value = UpdateInfo(
            lastCheckedAtMs = checkedAtMs,
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            releaseUrl = releaseUrl,
            updateAvailable = updateAvailable,
            lastError = null
        )
    }

    fun markCheckFailure(
        context: Context,
        errorMessage: String,
        checkedAtMs: Long = System.currentTimeMillis()
    ) {
        val sanitizedError = errorMessage.take(160)
        val current = _updateInfo.value
        prefs(context).edit()
            .putLong(KEY_LAST_CHECKED_AT_MS, checkedAtMs)
            .putString(KEY_LAST_ERROR, sanitizedError)
            .apply()

        _updateInfo.value = current.copy(
            lastCheckedAtMs = checkedAtMs,
            lastError = sanitizedError
        )
    }

    fun shouldShowUpdatePrompt(context: Context, info: UpdateInfo): Boolean {
        if (!isEnabled() || !info.updateAvailable) return false
        val token = buildPromptToken(info.latestVersion, info.releaseUrl) ?: return false
        val lastPromptToken = prefs(context).getString(KEY_LAST_PROMPT_TOKEN, null)
        return token != lastPromptToken
    }

    fun markUpdatePromptShown(
        context: Context,
        latestVersion: String?,
        releaseUrl: String?
    ) {
        val token = buildPromptToken(latestVersion, releaseUrl) ?: return
        prefs(context).edit()
            .putString(KEY_LAST_PROMPT_TOKEN, token)
            .apply()
    }

    private fun buildPromptToken(latestVersion: String?, releaseUrl: String?): String? {
        val normalizedVersion = latestVersion?.trim().takeUnless { it.isNullOrBlank() }
        if (normalizedVersion != null) return "version:$normalizedVersion"

        val normalizedUrl = releaseUrl?.trim().takeUnless { it.isNullOrBlank() }
        if (normalizedUrl != null) return "url:$normalizedUrl"

        return null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
