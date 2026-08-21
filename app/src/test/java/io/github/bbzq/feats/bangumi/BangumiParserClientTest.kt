package io.github.bbzq.feats.bangumi

import io.github.bbzq.ModuleSettings
import io.github.bbzq.BangumiRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `maps synthetic international tab search to movie type`() {
        assertEquals("8", BangumiParserClient.internationalSearchType("1920"))
        assertEquals("8", BangumiParserClient.internationalSearchType(null))
        assertEquals("7", BangumiParserClient.internationalSearchType("7"))
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
        assertEquals(
            "https://parser.example/bilibili.community.service.dm.v1.DM/DmView?x=1",
            BangumiParserClient.buildGrpcProxyUrl(
                "parser.example",
                URI("https://grpc.biliapi.net/bilibili.community.service.dm.v1.DM/DmView?x=1"),
                true,
            ),
        )
    }

    @Test
    fun `international play url omits area but preserves quality parameters`() {
        val url = BangumiParserClient.buildPlayUrl(
            BangumiRegion.INTL,
            "parser.example",
            linkedMapOf(
                "ep_id" to "1",
                "area" to "th",
                "fnval" to "84948",
                "qn" to "120",
                "fourk" to "1",
                "force_host" to "0",
                "fnver" to "0",
            ),
            null,
            javaClass.classLoader!!,
            false,
        )
        assertEquals(false, url.substringAfter('?').contains("area="))
        listOf(
            "fnval=84948", "qn=120", "fourk=1", "force_host=0", "fnver=0",
            "s_locale=zh_SG", "c_locale=zh_SG", "lang=hans",
        ).forEach {
            assertEquals(true, url.contains(it))
        }
    }

    @Test
    fun `accepts successful regional web season response`() {
        val result = BangumiParserClient.Result(
            body = "{\"code\":0,\"result\":{\"episodes\":[]}}",
            httpStatus = 200,
            contentType = "application/json",
            isJson = true,
            businessCode = 0,
        )

        assertTrue(BangumiParserClient.isUsableSeasonResult(result))
    }

    @Test
    fun `rejects regional season business errors`() {
        val result = BangumiParserClient.Result(
            body = "{\"code\":-10403,\"message\":\"抱歉您所在地区不可观看！\"}",
            httpStatus = 200,
            contentType = "application/json",
            isJson = true,
            businessCode = -10403,
        )

        assertFalse(BangumiParserClient.isUsableSeasonResult(result))
    }
}
