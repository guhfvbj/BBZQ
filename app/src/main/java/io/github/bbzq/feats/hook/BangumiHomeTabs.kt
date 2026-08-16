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
            id = "50",
            title = "追番（大陆）",
            uri = "bilibili://pgc/home",
            reporterId = "bangumi",
            position = 50,
            existingUris = setOf("bilibili://pgc/bangumi_v2", "bilibili://pgc/home"),
        ),
        BangumiHomeTabSpec(
            id = "60",
            title = "追番（港澳台）",
            uri = "bilibili://following/home_activity_tab/6544",
            reporterId = "bangumi",
            position = 60,
            existingUris = setOf("bilibili://following/home_activity_tab/6544"),
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
