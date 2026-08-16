package io.github.bbzq.feats.bangumi

import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/** Direct client for servers compatible with BiliRoaming's regional parser protocol. */
internal object BangumiParserClient {
    private const val TIMEOUT_MS = 10_000

    data class Result(
        val body: String?,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = body?.contains(Regex("\"code\"\\s*:\\s*0\\b")) == true
    }

    data class ProbeResult(val message: String)

    fun requestPlayUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
    ): Result {
        val params = LinkedHashMap(query)
        params["area"] = region.name.lowercase()
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        if (region == BangumiRegion.TH) {
            params.putIfAbsent("appkey", "7d089525d3611b1c")
            params.putIfAbsent("build", "1001310")
            params.putIfAbsent("mobi_app", "bstar_a")
            params.putIfAbsent("platform", "android")
        }
        return request(host, region.playUrlPath, sign(params, classLoader), credential?.platform ?: region.defaultPlatform)
    }

    fun requestSearch(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
    ): Result {
        val params = LinkedHashMap(query)
        val path = if (region == BangumiRegion.TH) "/intl/gateway/v2/app/search/type" else "/x/v2/search/type"
        if (region == BangumiRegion.TH) {
            params.putAll(mapOf(
                "appkey" to "7d089525d3611b1c",
                "build" to "1001310",
                "mobi_app" to "bstar_a",
                "platform" to "android",
                "s_locale" to "zh_SG",
                "c_locale" to "zh_SG",
                "lang" to "hans",
            ))
        } else {
            params["area"] = region.name.lowercase()
            params.putIfAbsent("build", "6400000")
        }
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        return request(host, path, sign(params, classLoader), credential?.platform ?: region.defaultPlatform)
    }

    fun buildPlayUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
    ): String {
        val params = LinkedHashMap(query)
        params["area"] = region.name.lowercase()
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        if (region == BangumiRegion.TH) {
            params.putIfAbsent("appkey", "7d089525d3611b1c")
            params.putIfAbsent("build", "1001310")
            params.putIfAbsent("mobi_app", "bstar_a")
            params.putIfAbsent("platform", "android")
        }
        return "https://$host${region.playUrlPath}?${sign(params, classLoader)}"
    }

    fun buildSearchUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
    ): String {
        val params = LinkedHashMap(query)
        val path = if (region == BangumiRegion.TH) "/intl/gateway/v2/app/search/type" else "/x/v2/search/type"
        params["type"] = "7"
        if (region == BangumiRegion.TH) {
            params.putAll(mapOf("appkey" to "7d089525d3611b1c", "build" to "1001310", "mobi_app" to "bstar_a", "platform" to "android", "s_locale" to "zh_SG", "c_locale" to "zh_SG", "lang" to "hans"))
        } else {
            params["area"] = region.name.lowercase()
            params.putIfAbsent("build", "6400000")
        }
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        return "https://$host$path?${sign(params, classLoader)}"
    }

    fun convertThailandPlayUrl(raw: String): String = runCatching {
        val input = org.json.JSONObject(raw)
        val videoInfo = input.optJSONObject("data")?.optJSONObject("video_info") ?: return@runCatching raw
        val streams = videoInfo.optJSONArray("stream_list") ?: return@runCatching raw
        val dash = org.json.JSONObject().put("audio", videoInfo.optJSONArray("dash_audio") ?: org.json.JSONArray())
        val videos = org.json.JSONArray()
        val formats = org.json.JSONArray()
        val qualities = org.json.JSONArray()
        val descriptions = org.json.JSONArray()
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val dashVideo = stream.optJSONObject("dash_video") ?: continue
            if (dashVideo.optString("base_url").isBlank()) continue
            val streamInfo = stream.optJSONObject("stream_info") ?: continue
            dashVideo.put("id", streamInfo.optInt("quality"))
            videos.put(dashVideo)
            formats.put(streamInfo)
            qualities.put(streamInfo.optInt("quality"))
            descriptions.put(streamInfo.optString("new_description"))
        }
        dash.put("video", videos).put("duration", 0).put("min_buffer_time", 0.0)
        org.json.JSONObject()
            .put("code", input.optInt("code"))
            .put("message", input.optString("message"))
            .put("format", "flv720")
            .put("type", "DASH")
            .put("timelength", videoInfo.optInt("timelength"))
            .put("quality", videoInfo.optInt("quality"))
            .put("accept_quality", qualities)
            .put("accept_description", descriptions)
            .put("support_formats", formats)
            .put("dash", dash)
            .toString()
    }.getOrDefault(raw)

    fun probe(region: BangumiRegion, host: String, rawCredential: String): ProbeResult {
        val credential = ModuleSettings.parseBangumiServerCredential(rawCredential)
        val params = if (region == BangumiRegion.TH) {
            mapOf("ep_id" to "285145", "s_locale" to "zh_SG")
        } else {
            mapOf("cid" to "120453316", "ep_id" to "285145", "otype" to "json", "fnval" to "16", "module" to "pgc", "platform" to "android", "test" to "true")
        }.toMutableMap().apply {
            put("area", region.name.lowercase())
            credential?.accessKey?.let { put("access_key", it) }
        }
        val result = request(host, region.playUrlPath, encode(params), credential?.platform ?: region.defaultPlatform)
        return when {
            result.body == null -> ProbeResult("连接失败：${result.error ?: "服务器无响应"}")
            result.isSuccess -> ProbeResult("连接成功：服务器返回可用播放地址")
            else -> ProbeResult("服务器可连接，但返回了业务错误：${result.body.take(160)}")
        }
    }

    private fun sign(params: Map<String, String>, classLoader: ClassLoader): String {
        val signed = runCatching {
            val signedQuery = classLoader.loadClass("com.bilibili.nativelibrary.SignedQuery")
            val libBili = classLoader.loadClass("com.bilibili.nativelibrary.LibBili")
            val signer = libBili.declaredMethods.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(Map::class.java)) && it.returnType == signedQuery
            } ?: return@runCatching null
            signer.isAccessible = true
            signer.invoke(null, params)?.toString()
        }.getOrNull()
        return signed?.takeIf { it.contains('=') } ?: encode(params)
    }

    private fun request(host: String, path: String, query: String, platform: String): Result {
        return runCatching {
            val url = URL("https://$host$path?$query")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("platform-from-bbzq", platform)
                val code = responseCode
                val stream = if (code in 200..299) inputStream else errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                disconnect()
                if (body == null) Result(null, "HTTP $code") else Result(body, if (code in 200..299) null else "HTTP $code")
            }
        }.getOrElse { Result(null, it.message ?: it.javaClass.simpleName) }
    }

    private fun encode(params: Map<String, String>): String = params.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
    }
}
