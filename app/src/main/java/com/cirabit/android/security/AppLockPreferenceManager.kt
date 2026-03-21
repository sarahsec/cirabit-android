package com.cirabit.android.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences-backed state for app lock toggle.
 * Uses a StateFlow so UI can react to preference changes.
 */
object AppLockPreferenceManager {
    private const val PREFS_NAME = "cirabit_settings"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"

    const val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _appLockEnabled.value = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        _appLockEnabled.value = enabled
    }

    fun isEnabled(): Boolean = _appLockEnabled.value

    fun authenticationStatus(context: Context): Int =
        BiometricManager.from(context).let { manager ->
            val combinedStatus = manager.canAuthenticate(AUTHENTICATORS)
            if (combinedStatus == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED) {
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            } else {
                combinedStatus
            }
        }

    fun promptAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AUTHENTICATORS
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    fun isAuthenticationAvailable(context: Context): Boolean =
        authenticationStatus(context) == BiometricManager.BIOMETRIC_SUCCESS
}
