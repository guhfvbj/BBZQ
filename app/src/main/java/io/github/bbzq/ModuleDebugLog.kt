package io.github.bbzq

import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter

/** Small, opt-in diagnostic buffer shared by the settings process and hooks. */
object ModuleDebugLog {
    const val KEY_ENABLED = "debug_log_enabled"

    private const val KEY_CONTENT = "debug_log_content"
    private const val MAX_CHARS = 96 * 1024

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun append(prefs: SharedPreferences, message: String, throwable: Throwable? = null) {
        if (!isEnabled(prefs)) return
        val details = buildString {
            append(message)
            throwable?.let {
                append('\n')
                append(StringWriter().also { writer -> it.printStackTrace(PrintWriter(writer)) })
            }
        }
        val timestamped = "${System.currentTimeMillis()} ${sanitize(details)}"
        val current = prefs.getString(KEY_CONTENT, "").orEmpty()
        prefs.edit().putString(KEY_CONTENT, appendText(current, timestamped)).apply()
    }

    fun read(prefs: SharedPreferences): String =
        ModuleRemotePreferences.readString(KEY_CONTENT, prefs.getString(KEY_CONTENT, "").orEmpty())

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_CONTENT).apply()
        ModuleRemotePreferences.remove(KEY_CONTENT)
    }

    internal fun appendText(existing: String, line: String, maxChars: Int = MAX_CHARS): String {
        if (maxChars <= 0) return ""
        val combined = if (existing.isEmpty()) line else "$existing\n$line"
        return if (combined.length <= maxChars) combined else combined.takeLast(maxChars)
    }

    internal fun sanitize(value: String): String = SENSITIVE_QUERY.replace(value) { match ->
        "${match.groupValues[1]}=<redacted>"
    }

    private val SENSITIVE_QUERY = Regex(
        "(?i)(access[_-]?key|authorization|cookie|sign|token)=([^&\\s]+)",
    )
}
