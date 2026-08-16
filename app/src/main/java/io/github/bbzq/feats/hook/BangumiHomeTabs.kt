package io.github.bbzq.feats.hook

internal data class BangumiHomeTabSpec(
    val id: String,
    val title: String,
    val uri: String,
    val reporterId: String,
    val position: Int,
    val existingUris: Set<String>,
)

internal object BangumiHomeTabs {
    val specs = listOf(
        BangumiHomeTabSpec(
            id = "60",
            title = "番剧（港澳台）",
            uri = "bilibili://following/home_activity_tab/6544",
            reporterId = "bangumi",
            position = 60,
            existingUris = setOf("bilibili://following/home_activity_tab/6544"),
        ),
        BangumiHomeTabSpec(
            id = "70",
            title = "影视（国际）",
            uri = "bilibili://browser?url=https%3A%2F%2Fwww.bilibili.tv%2Fzh-Hans",
            reporterId = "bangumi",
            position = 70,
            existingUris = setOf(
                "bilibili://browser?url=https%3A%2F%2Fwww.bilibili.tv%2Fzh-Hans",
                "https://www.bilibili.tv/zh-Hans",
                "https://www.bilibili.tv/zh-Hans/",
            ),
        ),
    )

    fun <T : Any> appendMissing(
        existing: List<T?>,
        uriOf: (T) -> String?,
        create: (BangumiHomeTabSpec) -> T?,
    ): List<T?> {
        val result = existing.toMutableList()
        val existingUris = existing.mapNotNull { item -> item?.let(uriOf) }.toMutableSet()
        specs.forEach { spec ->
            if (spec.existingUris.any(existingUris::contains)) return@forEach
            create(spec)?.let { item ->
                result += item
                existingUris += spec.uri
            }
        }
        return result
    }
}
