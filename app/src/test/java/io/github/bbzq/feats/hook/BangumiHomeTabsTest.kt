package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BangumiHomeTabsTest {
    @Test
    fun `adds regional tabs after existing items`() {
        val original = listOf(TestTab("bilibili://pegasus/promo"))

        val result = BangumiHomeTabs.appendMissing(original, TestTab::uri) { spec ->
            TestTab(spec.uri, spec.title, spec.id, spec.reporterId, spec.position)
        }

        assertSame(original.first(), result.first())
        assertEquals(
            listOf(
                "bilibili://pegasus/promo",
                "bilibili://following/home_activity_tab/6544",
                "bilibili://following/home_activity_tab/168644",
            ),
            result.map { it?.uri },
        )
        assertEquals(listOf(null, "60", "80"), result.map { it?.id })
        assertEquals(listOf(null, "番剧（港澳台）", "影视（国际）"), result.map { it?.title })
        assertEquals(listOf(null, 60, 80), result.map { it?.position })
        assertEquals(listOf(null, "bangumi", "jptv"), result.map { it?.reporterId })
    }

    @Test
    fun `recognizes existing tabs and does not duplicate them`() {
        val original = listOf(
            TestTab("bilibili://following/home_activity_tab/6544"),
            TestTab("bilibili://following/home_activity_tab/168644"),
        )

        val result = BangumiHomeTabs.appendMissing(original, TestTab::uri) { spec ->
            TestTab(spec.uri)
        }

        assertEquals(original, result)
    }

    @Test
    fun `recognizes the legacy international web entry for migration`() {
        assertEquals(
            "影视（国际）",
            BangumiHomeTabs.forExistingUri("https://www.bilibili.tv/zh-Hans")?.title,
        )
    }

    @Test
    fun `keeps original list when tab creation fails`() {
        val original = listOf(TestTab("bilibili://pegasus/promo"))

        val result = BangumiHomeTabs.appendMissing(original, TestTab::uri) { null }

        assertEquals(original, result)
    }

    private data class TestTab(
        val uri: String,
        val title: String? = null,
        val id: String? = null,
        val reporterId: String? = null,
        val position: Int? = null,
    )
}
