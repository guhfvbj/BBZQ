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
            .setPid(9001L)
            .setOid(1186996969L)
            .build()

        assertNull(BangumiDmViewFallback.augmentDmViewRequest(request.toByteArray(), 757987L))
    }
}
