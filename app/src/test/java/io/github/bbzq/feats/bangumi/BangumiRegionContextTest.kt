package io.github.bbzq.feats.bangumi

import io.github.bbzq.BangumiRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiRegionContextTest {
    @Test
    fun `resolves international movie playback ids from search reference`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "9002",
            cid = "7002",
            seasonId = "9000",
            region = BangumiRegion.INTL,
            isMovie = true,
        )

        val reference = BangumiRegionContext.referenceFor(9002, 0, 9000)
        assertEquals(BangumiRegion.INTL, reference?.region)
        assertEquals(9002L, reference?.episodeId)
        assertEquals(7002L, reference?.cid)
        assertTrue(reference?.isMovie == true)
    }

    @Test
    fun `resolves episode reference from dm view content id`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "9202",
            cid = "7202",
            seasonId = "9200",
            region = BangumiRegion.TW,
            isMovie = false,
        )

        val reference = BangumiRegionContext.referenceFor(0, 7202, 0)
        assertEquals(BangumiRegion.TW, reference?.region)
        assertEquals(9202L, reference?.episodeId)
        assertEquals(7202L, reference?.cid)
    }

    @Test
    fun `rewrites only international playback query with known reference`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "9102",
            cid = "7102",
            seasonId = "9100",
            region = BangumiRegion.INTL,
            isMovie = true,
        )
        val query = mapOf("ep_id" to "9102", "cid" to "9999")

        val intl = BangumiRegionContext.resolvePlayQuery(query, BangumiRegion.INTL)
        assertEquals("9102", intl["ep_id"])
        assertEquals("7102", intl["cid"])
        assertFalse(intl.containsKey("season_id"))

        val hk = BangumiRegionContext.resolvePlayQuery(query, BangumiRegion.HK)
        assertFalse(hk.containsKey("season_id"))
        assertEquals("9999", hk["cid"])
    }

    @Test
    fun `keeps movie references keyed by the episode id used by the card`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "679957",
            cid = "859778302",
            seasonId = "0",
            region = BangumiRegion.INTL,
            isMovie = true,
        )

        val resolved = BangumiRegionContext.resolvePlayQuery(
            mapOf("ep_id" to "679957", "cid" to "1"),
            BangumiRegion.INTL,
        )
        assertEquals("859778302", resolved["cid"])
    }

    @Test
    fun `records content region for dm view routing`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "94001",
            cid = "95001",
            seasonId = "96001",
            region = BangumiRegion.TW,
            isMovie = false,
        )

        assertEquals(BangumiRegion.TW, BangumiRegionContext.contentRegion(95001L))
        BangumiRegionContext.prepareDmView(95001L)
        assertEquals(BangumiRegion.TW, BangumiRegionContext.consumeDmViewRegion())
        assertEquals(null, BangumiRegionContext.consumeDmViewRegion())

        BangumiRegionContext.prepareDmView(99999999L)
        assertEquals(null, BangumiRegionContext.consumeDmViewRegion())
    }

    @Test
    fun `prioritizes content region before episode and season mappings`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "97101",
            cid = "97201",
            seasonId = "97301",
            region = BangumiRegion.INTL,
            isMovie = false,
        )
        BangumiRegionContext.recordEpisode("97101", BangumiRegion.HK)
        BangumiRegionContext.recordSeason("97301", BangumiRegion.TW)

        val candidates = BangumiRegionContext.candidates(97101L, 97301L, 97201L)

        assertEquals(BangumiRegion.INTL, candidates[0])
        assertEquals(BangumiRegion.HK, candidates[1])
        assertEquals(BangumiRegion.TW, candidates[2])
    }
}
