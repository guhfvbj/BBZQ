package io.github.bbzq.feats.bangumi

import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.cert.CertificateFactory

/** Direct client for servers compatible with BiliRoaming's regional parser protocol. */
internal object BangumiParserClient {
    private const val TIMEOUT_MS = 10_000
    // Current Android PGC endpoints reject unsigned legacy requests when the
    // app identity is omitted. This is also the identity used by the host app.
    private const val MAIN_APP_KEY = "1d8b6e7d45233436"
    private const val MAIN_BUILD = "9060300"

    data class Result(
        val body: String?,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = body?.contains(Regex("\"code\"\\s*:\\s*0\\b")) == true
    }

    data class ProbeResult(val message: String)

    data class Compatibility(
        val region: BangumiRegion,
        val capabilities: Set<String>,
    )

    fun requestPlayUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean = true,
    ): Result {
        val params = LinkedHashMap(query)
        params["area"] = region.name.lowercase()
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        if (region == BangumiRegion.TH) {
            params.putIfAbsent("appkey", "7d089525d3611b1c")
            params.putIfAbsent("build", "1001310")
            params.putIfAbsent("mobi_app", "bstar_a")
            params.putIfAbsent("platform", "android")
        } else {
            params.putIfAbsent("appkey", MAIN_APP_KEY)
            params.putIfAbsent("build", MAIN_BUILD)
            params.putIfAbsent("mobi_app", "android")
            params.putIfAbsent("platform", "android")
        }
        return request(host, region.playUrlPath, sign(params, classLoader), credential?.platform ?: region.defaultPlatform, useHttps)
    }

    fun requestSearch(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean = true,
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
        return request(host, path, sign(params, classLoader), credential?.platform ?: region.defaultPlatform, useHttps)
    }

    fun requestSeason(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean = true,
    ): Result {
        val params = LinkedHashMap(query)
        credential?.accessKey?.takeIf(String::isNotBlank)?.let { params["access_key"] = it }
        val path = if (region == BangumiRegion.TH) {
            params.putIfAbsent("mobi_app", "bstar_a")
            params.putIfAbsent("build", "1001310")
            params.putIfAbsent("s_locale", "zh_SG")
            "/intl/gateway/v2/ogv/view/app/season"
        } else {
            params["area"] = region.name.lowercase()
            params.putIfAbsent("appkey", MAIN_APP_KEY)
            params.putIfAbsent("mobi_app", "android")
            params.putIfAbsent("platform", "android")
            params.putIfAbsent("build", MAIN_BUILD)
            "/pgc/view/v2/app/season"
        }
        return request(host, path, sign(params, classLoader), credential?.platform ?: region.defaultPlatform, useHttps)
    }

    fun buildPlayUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean = true,
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
        return buildUrl(host, region.playUrlPath, sign(params, classLoader), useHttps)
    }

    fun buildSearchUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean = true,
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
        return buildUrl(host, path, sign(params, classLoader), useHttps)
    }

    fun buildSeasonUrl(
        region: BangumiRegion,
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean,
    ): String {
        val params = LinkedHashMap(query).apply {
            credential?.accessKey?.takeIf(String::isNotBlank)?.let { put("access_key", it) }
            if (region == BangumiRegion.TH) {
                putIfAbsent("mobi_app", "bstar_a")
                putIfAbsent("build", "1001310")
                putIfAbsent("s_locale", "zh_SG")
            } else {
                put("area", region.name.lowercase())
                putIfAbsent("build", "6400000")
            }
        }
        val path = if (region == BangumiRegion.TH) {
            "/intl/gateway/v2/ogv/view/app/season"
        } else {
            "/pgc/view/v2/app/season"
        }
        return buildUrl(host, path, sign(params, classLoader), useHttps)
    }

    fun buildSubtitleUrl(
        host: String,
        query: Map<String, String>,
        credential: BangumiServerCredential?,
        classLoader: ClassLoader,
        useHttps: Boolean,
    ): String {
        val params = LinkedHashMap(query).apply {
            credential?.accessKey?.takeIf(String::isNotBlank)?.let { put("access_key", it) }
            putIfAbsent("mobi_app", "bstar_a")
            putIfAbsent("build", "1001310")
            putIfAbsent("s_locale", "zh_SG")
        }
        return buildUrl(host, "/intl/gateway/v2/app/subtitle", sign(params, classLoader), useHttps)
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

    fun probe(region: BangumiRegion, host: String, useHttps: Boolean, rawCredential: String): ProbeResult {
        val credential = ModuleSettings.parseBangumiServerCredential(rawCredential)
        val compatibility = request(host, "/api/bbzq/compat", "", region.defaultPlatform, useHttps, mapOf("x-bbzq-probe" to "1"))
        val declared = parseCompatibility(compatibility.body)
        if (declared != null && declared.region != region) {
            return ProbeResult("检查失败：服务器声明的地区是${declared.region.label}，不能用于${region.label}")
        }
        val required = buildSet {
            addAll(setOf("search", "season", "playurl"))
            if (region == BangumiRegion.TH) add("subtitle")
        }
        if (declared != null && !declared.capabilities.containsAll(required)) {
            return ProbeResult("检查失败：服务器缺少${(required - declared.capabilities).joinToString("、")}接口")
        }
        // A BBZQ-aware backend has already declared its route contract. The old
        // fallback probe used an unsigned, synthetic playurl and was rejected
        // by current Bilibili APIs with -400, producing a false failure even
        // when signed app requests worked normally.
        if (declared != null) {
            return ProbeResult("BBZQ兼容检查通过：${region.label}服务器已声明所需接口")
        }
        val params = if (region == BangumiRegion.TH) {
            mapOf("ep_id" to "285145", "s_locale" to "zh_SG")
        } else {
            mapOf("cid" to "120453316", "ep_id" to "285145", "otype" to "json", "fnval" to "16", "module" to "pgc", "platform" to "android", "test" to "true")
        }.toMutableMap().apply {
            put("area", region.name.lowercase())
            credential?.accessKey?.let { put("access_key", it) }
        }
        val result = request(host, region.playUrlPath, encode(params), credential?.platform ?: region.defaultPlatform, useHttps)
        return when {
            result.body == null -> ProbeResult("连接失败：${result.error ?: "服务器无响应"}")
            result.isSuccess -> ProbeResult("基础连接通过：服务器未提供BBZQ兼容声明，仅验证了播放接口")
            else -> ProbeResult("服务器可连接，但返回了业务错误：${result.body.take(160)}")
        }
    }

    private fun sign(params: Map<String, String>, classLoader: ClassLoader): String {
        // Requests intercepted from the host app already carry a time-bound
        // signature. BiliRoaming removes both fields before asking LibBili to
        // sign the modified query; retaining either makes the regenerated URL
        // invalid after changing its area or result type.
        val unsignedParams = LinkedHashMap(params).apply {
            remove("sign")
            remove("ts")
        }
        val signed = runCatching {
            val signedQuery = classLoader.loadClass("com.bilibili.nativelibrary.SignedQuery")
            val libBili = classLoader.loadClass("com.bilibili.nativelibrary.LibBili")
            val signer = libBili.declaredMethods.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(Map::class.java)) && it.returnType == signedQuery
            } ?: return@runCatching null
            signer.isAccessible = true
            signer.invoke(null, unsignedParams)?.toString()
        }.getOrNull()
        return signed?.takeIf { it.contains('=') } ?: encode(unsignedParams)
    }

    private fun parseCompatibility(raw: String?): Compatibility? = runCatching {
        val data = org.json.JSONObject(raw ?: return null).optJSONObject("data") ?: return null
        if (data.optString("protocol") != "bbzq-bangumi/1") return null
        val region = BangumiRegion.entries.firstOrNull { it.name.equals(data.optString("region"), true) } ?: return null
        val capabilities = data.optJSONArray("capabilities")?.let { values ->
            buildSet { for (index in 0 until values.length()) add(values.optString(index)) }
        }.orEmpty()
        Compatibility(region, capabilities)
    }.getOrNull()

    internal fun buildUrl(host: String, path: String, query: String, useHttps: Boolean): String {
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://$host$path${query.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()}"
    }

    private fun request(
        host: String,
        path: String,
        query: String,
        platform: String,
        useHttps: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result {
        return runCatching {
            val url = URL(buildUrl(host, path, query, useHttps))
            (url.openConnection() as HttpURLConnection).run {
                configureDirectIpTls(this, host)
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("platform-from-bbzq", platform)
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                val code = responseCode
                val stream = if (code in 200..299) inputStream else errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                disconnect()
                if (body == null) Result(null, "HTTP $code") else Result(body, if (code in 200..299) null else "HTTP $code")
            }
        }.getOrElse { Result(null, it.message ?: it.javaClass.simpleName) }
    }

    /**
     * The regional parser can be reached directly while the Cloudflare origin
     * is unavailable. This deliberately trusts only the IP-specific origin
     * certificate, rather than disabling TLS checks for arbitrary servers.
     */
    private fun configureDirectIpTls(connection: HttpURLConnection, host: String) {
        if (connection !is HttpsURLConnection || host.substringBefore(':') != DIRECT_PARSER_IP) return
        connection.sslSocketFactory = directParserSslContext.socketFactory
    }

    private fun encode(params: Map<String, String>): String = params.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
    }

    private val directParserSslContext: SSLContext by lazy {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(DIRECT_PARSER_CERTIFICATE.toByteArray(StandardCharsets.US_ASCII)),
        )
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("bbzq-direct-parser", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }

    private const val DIRECT_PARSER_IP = "47.98.174.251"

    private val DIRECT_PARSER_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIEIjCCAoqgAwIBAgIUIPUMF6a7M4T7yB9FchL6guqdAb8wDQYJKoZIhvcNAQEL
            BQAwGDEWMBQGA1UEAwwNNDcuOTguMTc0LjI1MTAeFw0yNjA4MTcwNTQ3MzhaFw0y
            NjA5MTYwNTQ3MzhaMBgxFjAUBgNVBAMMDTQ3Ljk4LjE3NC4yNTEwggGiMA0GCSqG
            SIb3DQEBAQUAA4IBjwAwggGKAoIBgQCvuvQg0OYlemLQMMRtGvx1Ou973KtIlwSA
            129UTzfC72HTLMszOJa31qGXrjxaPI1BKJCzDwwnOVhBxzVAG3G4UhT2KyJAHBFs
            HADhGI+npTqBTN9lohZ4CtqSAjfTT/E5imB7Qv+w3hE+W/x4QCu4GpQ2x3X8oTEu
            K4r7CKS5GziqK8YrA2uSVOl8fO5ArgJKCILmKVvYRa7gooWNsDGGdqNAZDhXW9Ep
            VNRKK0MQkSJvmapUQ+5Kxnd5Q13u6OxtAQyGUf0s6NQT9/g0+DyUbqW+yheQ2174
            w45Opgk0T8/WemZZoNEWX6WlOUGN3J2vqd7EpyKV30NUfnjMswxujyGLI5QN+36M
            R92UWVaN7VXZGX/IyAgeNPM29mJvMt03UomeJp55PCvMFegdZZVzVmpcJ/hnsOPz
            kcHsD4Fv2oNuNteS7UgjGoGREpV9om8lPni7FkIxbRXT+CZDhFjFiNL4G+NCGmk4
            Ok0TFnPT8Cdkq2elwPJjIifMAHUowfcCAwEAAaNkMGIwHQYDVR0OBBYEFBIr/A2b
            Af5QdQeD3IsqQKgGsCkuMB8GA1UdIwQYMBaAFBIr/A2bAf5QdQeD3IsqQKgGsCku
            MA8GA1UdEwEB/wQFMAMBAf8wDwYDVR0RBAgwBocEL2Ku+zANBgkqhkiG9w0BAQsF
            AAOCAYEAjWVx8q80uJvmp0aWh1ogRPbIa2uEGvmJ+LczWb8E8QfeSXyHy2SVr/BB
            YX9mKmW30TqpNpYiCV1a4d+pDXVwbVExxeil1RLw/ssTpRxZUustlOA7grmDxMwP
            PT0tYOi7tgb9bRICQHjVcTPUfi2oMyNPJcy8H1ar534uttcFldN4gBublh0FwMLl
            Jt7bbhULTugwSP7ONAqPemT053QI63CQMhUoz6YGJ7NjVsR6huf4awBDMMvwSyh3
            TZxGe/yXcGbDu9N8QsXhJBMeC2S8HtaaFiC4z0IU219WBTsX/3XZcNG5kqRhboV5
            EYB4YXsSK92O79nCWJZctR5t3vFKM9Y15q4X3ANpZWmB1Stf6RWnu3Jlc/rM8qL5
            +knlGnZjHhCUZKHxhEhrmNw9HEIBJp2UjjB3VwxJMlhkc0qpg00lo+yQ2PvZqLLX
            m3u1ivkwzlLwecIV1N+7RlJxeHeNJZYLEHjdJ6eOrI7PzdOfaW3hRAQnjKOjm8ow
            bvaHxUCW
            -----END CERTIFICATE-----
        """.trimIndent()
}
