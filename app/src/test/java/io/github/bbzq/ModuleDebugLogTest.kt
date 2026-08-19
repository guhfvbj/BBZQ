package io.github.bbzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleDebugLogTest {
    @Test
    fun `sanitizes credential query values but keeps regular parameters`() {
        val value = ModuleDebugLog.sanitize(
            "path=/intl/gateway?keyword=test&access_key=secret&sign=signature&pn=1",
        )

        assertTrue(value.contains("keyword=test"))
        assertTrue(value.contains("pn=1"))
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("signature"))
        assertTrue(value.contains("access_key=<redacted>"))
    }

    @Test
    fun `keeps only the newest bounded log content`() {
        assertEquals("\n345", ModuleDebugLog.appendText("12", "345", maxChars = 4))
    }

    @Test
    fun `sanitizes regional search status`() {
        val value = ModuleDebugLog.sanitize("type=1919&token=secret")

        assertEquals("type=1919&token=<redacted>", value)
    }
}
