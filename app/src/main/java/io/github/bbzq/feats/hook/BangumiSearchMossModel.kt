package io.github.bbzq.feats.hook

import io.github.bbzq.BangumiRegion

/** Pure search mapping shared by the MOSS hook and unit tests. */
internal object BangumiSearchMossModel {
    const val HK_TW_TYPE = "1919"
    const val INTERNATIONAL_TYPE = "1920"

    data class AreaSearch(
        val type: String,
        val region: BangumiRegion,
        val upstreamType: String,
    )

    fun areaSearch(type: String?): AreaSearch? = when (type) {
        HK_TW_TYPE -> AreaSearch(HK_TW_TYPE, BangumiRegion.HK, "7")
        INTERNATIONAL_TYPE -> AreaSearch(INTERNATIONAL_TYPE, BangumiRegion.INTL, "8")
        else -> null
    }

    fun query(
        keyword: String,
        page: String,
        pageSize: Int,
        qn: Long,
        fnver: Int,
        fnval: Int,
    ): Map<String, String> = linkedMapOf(
        "keyword" to keyword,
        "pn" to page.ifBlank { "1" },
        "ps" to pageSize.toString(),
        "qn" to qn.toString(),
        "fnver" to fnver.toString(),
        "fnval" to fnval.toString(),
    )
}
