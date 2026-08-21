package io.github.bbzq.feats.bangumi

import com.google.protobuf.ByteString
import io.github.bbzq.proto.DmViewReply
import io.github.bbzq.proto.SubtitleItem
import io.github.bbzq.proto.VideoSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiSubtitleModelTest {
    @Test
    fun detectsWhetherDmViewContainsSubtitleTracks() {
        val withTrack = DmViewReply.newBuilder()
            .setSubtitle(
                VideoSubtitle.newBuilder().addSubtitles(
                    SubtitleItem.newBuilder().setId(1).setLan("zh-Hant").setSubtitleUrl("https://example.com/1.json"),
                ),
            )
            .build()

        assertTrue(BangumiSubtitleModel.hasSubtitleTrack(withTrack.toByteArray()))
        assertFalse(BangumiSubtitleModel.hasSubtitleTrack(DmViewReply.getDefaultInstance().toByteArray()))
    }

    @Test
    fun `merges external subtitle into original reply without dropping other fields`() {
        val original = DmViewReply.newBuilder()
            .setClosed(true)
            .setMask(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()
        val external = reply(track(10, "zh-Hant", "繁體中文", "https://example.com/subtitle.json"))

        // Field 10 is intentionally absent from the local schema. It must survive
        // the merge so newer host reply fields are not discarded.
        val originalBytes = original.toByteArray() + byteArrayOf(0x50, 0x7B)
        val mergedBytes = requireNotNull(BangumiSubtitleModel.mergeSubtitleTrack(originalBytes, external.toByteArray()))
        val merged = DmViewReply.parseFrom(mergedBytes)

        assertTrue(merged.closed)
        assertEquals(ByteString.copyFrom(byteArrayOf(1, 2, 3)), merged.mask)
        assertEquals(external.subtitle, merged.subtitle)
        assertTrue(mergedBytes.toList().windowed(2).any { it == listOf(0x50.toByte(), 0x7B.toByte()) })
    }

    @Test
    fun `does not replace an existing subtitle track`() {
        val original = reply(track(1, "zh-Hant", "繁體中文", "https://example.com/original.json"))
        val external = reply(track(2, "zh-Hant", "繁體中文", "https://example.com/external.json"))

        assertNull(BangumiSubtitleModel.mergeSubtitleTrack(original.toByteArray(), external.toByteArray()))
    }

    @Test
    fun `adds separate simplified track without changing traditional track`() {
        val original = reply(track(10, "zh-Hant", "繁體中文", "https://aisubtitle.hdslb.com/a.json?token=1"))

        val converted = DmViewReply.parseFrom(requireNotNull(BangumiSubtitleModel.addSimplifiedTrack(original.toByteArray())))
        val tracks = converted.subtitle.subtitlesList

        assertEquals(2, tracks.size)
        assertEquals(original.subtitle.subtitlesList[0], tracks[0])
        assertEquals("zh-CN", tracks[1].lan)
        assertEquals("简中（转换）", tracks[1].lanDoc)
        assertEquals("简中", tracks[1].lanDocBrief)
        assertEquals(11L, tracks[1].id)
        assertTrue(tracks[1].subtitleUrl.contains("token=1&zh_converter=t2cn"))
        assertTrue(BangumiSubtitleModel.isConversionUrl(tracks[1].subtitleUrl))
        assertFalse(BangumiSubtitleModel.isConversionUrl(tracks[0].subtitleUrl))
    }

    @Test
    fun `does not add duplicate when official simplified track exists`() {
        val original = reply(
            track(10, "zh-Hant", "繁體中文", "https://aisubtitle.hdslb.com/a.json"),
            track(20, "zh-CN", "简体中文", "https://aisubtitle.hdslb.com/b.json"),
        )

        assertNull(BangumiSubtitleModel.addSimplifiedTrack(original.toByteArray()))
    }

    @Test
    fun `does not add track without traditional subtitles`() {
        val original = reply(track(10, "en-US", "English", "https://aisubtitle.hdslb.com/a.json"))

        assertNull(BangumiSubtitleModel.addSimplifiedTrack(original.toByteArray()))
    }

    @Test
    fun `converts only subtitle content and preserves timing`() {
        val raw = """{"body":[{"from":1.5,"to":3.0,"content":"繁體字幕"}],"type":"json"}"""

        val converted = BangumiSubtitleModel.convertSubtitleJson(raw) { "简体字幕" }

        assertTrue(converted.contains("\"content\":\"简体字幕\""))
        assertTrue(converted.contains("\"from\":1.5"))
        assertTrue(converted.contains("\"to\":3"))
        assertTrue(converted.contains("\"type\":\"json\""))
    }

    private fun reply(vararg tracks: SubtitleItem): DmViewReply = DmViewReply.newBuilder()
        .setSubtitle(VideoSubtitle.newBuilder().addAllSubtitles(tracks.toList()))
        .build()

    private fun track(id: Long, language: String, title: String, url: String): SubtitleItem =
        SubtitleItem.newBuilder()
            .setId(id)
            .setIdStr(id.toString())
            .setLan(language)
            .setLanDoc(title)
            .setSubtitleUrl(url)
            .build()
}
