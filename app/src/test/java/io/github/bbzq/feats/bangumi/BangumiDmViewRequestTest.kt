package io.github.bbzq.feats.bangumi

import io.github.bbzq.proto.DmViewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiDmViewRequestTest {
    @Test
    fun `fills missing pid while preserving oid and unknown fields`() {
        val original = DmViewRequest.newBuilder()
            .setOid(1186996969L)
            .build()
            .toByteArray()
        val withUnknownField = original + byteArrayOf(0x22, 0x03, 0x01, 0x02, 0x03)
        val augmented = BangumiDmViewFallback.augmentDmViewRequest(withUnknownField, 757987L)
            ?: error("request was not augmented")
        val result = DmViewRequest.parseFrom(augmented)

        assertEquals(757987L, result.pid)
        assertEquals(1186996969L, result.oid)
        assertTrue(augmented.toList().windowed(5).any { window ->
            window == listOf(0x22, 0x03, 0x01, 0x02, 0x03).map(Int::toByte)
        })
    }

    @Test
    fun `does not overwrite an existing pid`() {
        val request = DmViewRequest.newBuilder()
            .setPid(757987L)
            .setOid(1186996969L)
            .build()

        assertNull(BangumiDmViewFallback.augmentDmViewRequest(request.toByteArray(), 757987L))
    }

    @Test
    fun `uses known cid mapping instead of bogus protobuf pid`() {
        BangumiRegionContext.recordEpisodeReference(
            episodeId = "990701",
            cid = "991301",
            seasonId = "992001",
            region = io.github.bbzq.BangumiRegion.TW,
            isMovie = false,
        )

        val identity = BangumiDmViewFallback.resolveDmViewIdentity(
            parsedPid = 117069517492482L,
            parsedOid = 991301L,
            reflectedPid = 0L,
            reflectedOid = -2233L,
            seasonId = 0L,
        )

        assertEquals(117069517492482L, identity.rawPid)
        assertEquals(991301L, identity.rawCid)
        assertEquals(990701L, identity.episodeId)
        assertEquals(991301L, identity.cid)
        assertEquals(true, identity.reference != null)
    }

    @Test
    fun `skips an unrecognized ordinary video request`() {
        val identity = BangumiDmViewFallback.resolveDmViewIdentity(
            parsedPid = 117069517492482L,
            parsedOid = 40770340494L,
            reflectedPid = 0L,
            reflectedOid = -2233L,
            seasonId = 0L,
        )

        assertEquals(0L, identity.episodeId)
        assertEquals(0L, identity.cid)
        assertNull(identity.reference)
    }

    @Test
    fun `rewrites both pid and oid when mapped values are required`() {
        val original = DmViewRequest.newBuilder()
            .setPid(117069517492482L)
            .setOid(40770340494L)
            .build()
            .toByteArray()

        val augmented = BangumiDmViewFallback.augmentDmViewRequest(original, 990701L, 991301L)
            ?: error("request was not augmented")
        val result = DmViewRequest.parseFrom(augmented)

        assertEquals(990701L, result.pid)
        assertEquals(991301L, result.oid)
    }
}
