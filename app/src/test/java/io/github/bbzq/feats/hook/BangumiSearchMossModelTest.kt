package io.github.bbzq.feats.hook

import io.github.bbzq.BangumiRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiSearchMossModelTest {
    @Test
    fun `maps synthetic search types to regional upstream types`() {
        assertEquals(BangumiRegion.HK, BangumiSearchMossModel.areaSearch("1919")?.region)
        assertEquals("7", BangumiSearchMossModel.areaSearch("1919")?.upstreamType)
        assertEquals(BangumiRegion.INTL, BangumiSearchMossModel.areaSearch("1920")?.region)
        assertEquals("8", BangumiSearchMossModel.areaSearch("1920")?.upstreamType)
        assertNull(BangumiSearchMossModel.areaSearch("7"))
    }

    @Test
    fun `prefers Taiwan then falls back to Hong Kong for the combined category`() {
        val area = requireNotNull(BangumiSearchMossModel.areaSearch("1919"))

        assertEquals(listOf(BangumiRegion.TW, BangumiRegion.HK), BangumiSearchMossModel.searchRegions(area))
    }

    @Test
    fun `builds paginated parser query`() {
        assertEquals(
            mapOf(
                "keyword" to "86",
                "pn" to "2",
                "ps" to "20",
                "qn" to "80",
                "fnver" to "0",
                "fnval" to "16",
            ),
            BangumiSearchMossModel.query("86", "2", 20, 80, 0, 16),
        )
    }

    @Test
    fun `maps result page origin to synthetic search type`() {
        assertEquals(BangumiSearchMossModel.HK_TW_TYPE, BangumiSearchMossModel.pageTypeFor("hk"))
        assertEquals(BangumiSearchMossModel.HK_TW_TYPE, BangumiSearchMossModel.pageTypeFor("tw"))
        assertEquals(BangumiSearchMossModel.INTERNATIONAL_TYPE, BangumiSearchMossModel.pageTypeFor("intl"))
        assertNull(BangumiSearchMossModel.pageTypeFor("cn"))
    }
}
