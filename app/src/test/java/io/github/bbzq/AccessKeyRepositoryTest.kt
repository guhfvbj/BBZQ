package io.github.bbzq

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AccessKeyRepositoryTest {
    @Test
    fun `concurrent capture only reports the first write`() {
        val values = Collections.synchronizedMap(mutableMapOf<String, String?>())
        val prefs = fakePreferences(values)
        val accessKey = "concurrent-access-key-123456"
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map {
                executor.submit(Callable { AccessKeyRepository.capture(prefs, accessKey) })
            }.map { it.get() }

            assertEquals(1, results.count { it })
            assertEquals(accessKey, values[ModuleSettings.KEY_LAST_ACCESS_KEY])
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fakePreferences(values: MutableMap<String, String?>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1] as String?
                "edit" -> fakeEditor(values)
                else -> defaultValue(method.returnType)
            }
        } as SharedPreferences

    private fun fakeEditor(values: MutableMap<String, String?>): SharedPreferences.Editor {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args!![0] as String] = args[1] as String?
                    editor
                }
                "apply", "commit" -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                else -> defaultValue(method.returnType)
            }
        } as SharedPreferences.Editor
        return editor
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        else -> null
    }
}
