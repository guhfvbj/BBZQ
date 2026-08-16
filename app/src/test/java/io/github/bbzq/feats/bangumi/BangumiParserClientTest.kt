package io.github.bbzq.feats.bangumi

import io.github.bbzq.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class BangumiParserClientTest {
    @Test
    fun `normalizes http and https parser hosts`() {
        assertEquals("parser.example:8443", ModuleSettings.normalizeBangumiServerHost("https://Parser.Example:8443/"))
        assertEquals("parser.example", ModuleSettings.normalizeBangumiServerHost("parser.example"))
        assertEquals("parser.example", ModuleSettings.normalizeBangumiServerHost("http://parser.example"))
        assertNull(ModuleSettings.normalizeBangumiServerHost("   "))
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

    @Test
    fun `builds http parser and grpc proxy urls`() {
        assertEquals(
            "http://parser.example/pgc/player/api/playurl?ep_id=1",
            BangumiParserClient.buildUrl("parser.example", "/pgc/player/api/playurl", "ep_id=1", false),
        )
        assertEquals(
            "https://parser.example/bilibili.pgc.gateway.player.v2.PlayURL/PlayView?x=1",
            BangumiParserClient.buildGrpcProxyUrl(
                "parser.example",
                URI("https://grpc.biliapi.net/bilibili.pgc.gateway.player.v2.PlayURL/PlayView?x=1"),
                true,
            ),
        )
    }
}
