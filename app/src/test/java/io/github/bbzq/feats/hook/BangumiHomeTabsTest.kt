package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BangumiHomeTabsTest {
    @Test
    fun `adds mainland and hk tw tabs after existing items`() {
        val original = listOf(TestTab("bilibili://pegasus/promo"))

        val result = BangumiHomeTabs.appendMissing(original, TestTab::uri) { spec ->
            TestTab(spec.uri, spec.title, spec.id, spec.reporterId, spec.position)
        }

        assertSame(original.first(), result.first())
        assertEquals(
            listOf(
                "bilibili://pegasus/promo",
                "bilibili://pgc/home",
                "bilibili://following/home_activity_tab/6544",
            ),
            result.map { it?.uri },
        )
        assertEquals(listOf(null, 50, 60), result.map { it?.position })
    }

    @Test
    fun `recognizes mainland alias and does not duplicate existing tabs`() {
        val original = listOf(
            TestTab("bilibili://pgc/bangumi_v2"),
            TestTab("bilibili://following/home_activity_tab/6544"),
        )

        val result = BangumiHomeTabs.appendMissing(original, TestTab::uri) { spec ->
            TestTab(spec.uri)
        }

        assertEquals(original, result)
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
