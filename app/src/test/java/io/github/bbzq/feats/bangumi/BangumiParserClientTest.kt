package io.github.bbzq.feats.bangumi

import io.github.bbzq.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiParserClientTest {
    @Test
    fun `normalizes https parser host only`() {
        assertEquals("parser.example:8443", ModuleSettings.normalizeBangumiServerHost("https://Parser.Example:8443/"))
        assertEquals("parser.example", ModuleSettings.normalizeBangumiServerHost("parser.example"))
        assertNull(ModuleSettings.normalizeBangumiServerHost("http://parser.example"))
        assertNull(ModuleSettings.normalizeBangumiServerHost("https://parser.example/pgc/player/api/playurl"))
    }

    @Test
    fun `parses optional access key and platform`() {
        val credential = ModuleSettings.parseBangumiServerCredential("token-value;bstar_a")
        assertEquals("token-value", credential?.accessKey)
        assertEquals("bstar_a", credential?.platform)
        assertNull(ModuleSettings.parseBangumiServerCredential("token value;bstar_a"))
    }

    @Test
    fun `leaves malformed thailand response unchanged`() {
        assertEquals("not-json", BangumiParserClient.convertThailandPlayUrl("not-json"))
    }
}
