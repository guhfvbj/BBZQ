package io.github.bbzq

import android.content.SharedPreferences

object AccessKeyRepository {
    @Volatile
    private var liveReader: (() -> String?)? = null
    private val captureLock = Any()
    private var capturedAccessKey: String? = null

    fun register(reader: () -> String?) {
        liveReader = reader
    }

    fun read(prefs: SharedPreferences): String? {
        val liveValue = runCatching { liveReader?.invoke() }
            .getOrNull()
            ?.takeIf(::looksLikeAccessKey)
        if (liveValue != null) {
            prefs.edit().putString(ModuleSettings.KEY_LAST_ACCESS_KEY, liveValue).apply()
            return liveValue
        }
        return prefs.getString(ModuleSettings.KEY_LAST_ACCESS_KEY, null)
            ?.takeIf(::looksLikeAccessKey)
    }

    /** Cache a credential observed on an already-authenticated host request. */
    fun capture(prefs: SharedPreferences, value: String?): Boolean {
        val accessKey = value?.takeIf(::looksLikeAccessKey) ?: return false
        return synchronized(captureLock) {
            if (capturedAccessKey == accessKey ||
                prefs.getString(ModuleSettings.KEY_LAST_ACCESS_KEY, null) == accessKey
            ) {
                capturedAccessKey = accessKey
                false
            } else {
                prefs.edit().putString(ModuleSettings.KEY_LAST_ACCESS_KEY, accessKey).apply()
                capturedAccessKey = accessKey
                true
            }
        }
    }

    fun looksLikeAccessKey(value: String): Boolean =
        ACCESS_KEY_PATTERN.matches(value)

    // Current Bilibili clients use a long opaque access token rather than the
    // old 32-character hexadecimal key. Keep validation strict enough to
    // reject malformed input without discarding the host's valid login token.
    private val ACCESS_KEY_PATTERN = Regex("[A-Za-z0-9._~-]{16,1024}")
}
