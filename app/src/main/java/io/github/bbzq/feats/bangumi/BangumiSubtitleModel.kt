package io.github.bbzq.feats.bangumi

import io.github.bbzq.proto.DmViewReply
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object BangumiSubtitleModel {
    const val CONVERTER_PARAMETER = "zh_converter"
    const val CONVERTER_VALUE = "t2cn"

    fun addSimplifiedTrack(raw: ByteArray): ByteArray? = runCatching {
        val reply = DmViewReply.parseFrom(raw)
        if (!reply.hasSubtitle()) return null
        val tracks = reply.subtitle.subtitlesList
        if (tracks.any { it.lan.isSimplifiedChinese() }) return null
        val traditional = tracks.firstOrNull { it.lan.isTraditionalChinese() } ?: return null
        val convertedUrl = markForConversion(traditional.subtitleUrl) ?: return null
        val usedIds = tracks.mapTo(HashSet()) { it.id }
        var generatedId = (tracks.maxOfOrNull { it.id } ?: 0L) + 1L
        while (generatedId in usedIds) generatedId++
        val converted = traditional.toBuilder()
            .setId(generatedId)
            .setIdStr(generatedId.toString())
            .setLan("zh-CN")
            .setLanDoc("简中（转换）")
            .setLanDocBrief("简中")
            .setSubtitleUrl(convertedUrl)
            .build()
        reply.toBuilder()
            .setSubtitle(reply.subtitle.toBuilder().addSubtitles(converted))
            .build()
            .toByteArray()
    }.getOrNull()

    fun isConversionUrl(raw: String?): Boolean = runCatching {
        URI(raw ?: return false).rawQuery.orEmpty().split('&').any { item ->
            item.substringBefore('=') == CONVERTER_PARAMETER &&
                item.substringAfter('=', "") == CONVERTER_VALUE
        }
    }.getOrDefault(false)

    fun convertSubtitleJson(raw: String, converter: (String) -> String): String = runCatching {
        val root = JSONObject(raw)
        val body = root.optJSONArray("body") ?: return raw
        if (!body.hasSubtitleEntries()) return raw
        for (index in 0 until body.length()) {
            body.optJSONObject(index)?.optString("content")?.takeIf(String::isNotEmpty)?.let { content ->
                body.optJSONObject(index)?.put("content", converter(content))
            }
        }
        root.toString()
    }.getOrDefault(raw)

    private fun markForConversion(raw: String): String? = runCatching {
        if (raw.isBlank()) return null
        val uri = URI(raw)
        val query = listOfNotNull(uri.rawQuery?.takeIf(String::isNotBlank), "$CONVERTER_PARAMETER=$CONVERTER_VALUE")
            .joinToString("&")
        URI(uri.scheme, uri.rawAuthority, uri.rawPath, query, uri.rawFragment).toASCIIString()
    }.getOrNull()

    private fun String.isTraditionalChinese(): Boolean {
        val value = lowercase().replace('_', '-')
        return value == "zh-hant" || value.startsWith("zh-hant-") || value in setOf("zh-tw", "zh-hk")
    }

    private fun String.isSimplifiedChinese(): Boolean {
        val value = lowercase().replace('_', '-')
        return value in setOf("zh-cn", "zh-hans") || value.startsWith("zh-hans-")
    }

    private fun JSONArray.hasSubtitleEntries(): Boolean = (0 until length()).any { index ->
        optJSONObject(index)?.let { it.has("from") && it.has("to") && it.has("content") } == true
    }
}
