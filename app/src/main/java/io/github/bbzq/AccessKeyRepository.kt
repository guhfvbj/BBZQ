package io.github.bbzq

import android.content.SharedPreferences

object AccessKeyRepository {
    @Volatile
    private var liveReader: (() -> String?)? = null

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
        if (prefs.getString(ModuleSettings.KEY_LAST_ACCESS_KEY, null) == accessKey) return false
        prefs.edit().putString(ModuleSettings.KEY_LAST_ACCESS_KEY, accessKey).apply()
        return true
    }

    fun looksLikeAccessKey(value: String): Boolean =
        ACCESS_KEY_PATTERN.matches(value)

    private val ACCESS_KEY_PATTERN = Regex("[0-9a-fA-F]{32}")
}
