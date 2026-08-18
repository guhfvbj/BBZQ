package io.github.bbzq.feats.hook

import android.icu.text.Transliterator
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiRegion
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.bangumi.BangumiParserClient
import io.github.bbzq.feats.bangumi.BangumiRegionContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Routes region-specific JSON endpoints to a compatible parser.
 * The original request is left intact whenever a route cannot be rebuilt.
 */
class BangumiParserHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val pendingSearchRegion = ThreadLocal<BangumiRegion?>()

    override fun startHook() {
        if (env.processName != env.packageName || !ModuleSettings.isAddBangumiEnabled(prefs)) return
        val requestBuilder = classLoader.findClassOrNull("okhttp3.Request\$Builder")
        val responseBody = classLoader.findClassOrNull("okhttp3.ResponseBody")
        var installed = 0
        requestBuilder?.allMethods()
            ?.filter { it.name == "url" && it.parameterCount == 1 }
            ?.forEach { method ->
                env.hookBefore(method) { param ->
                    runCatching { routeRequest(param.thisObject, param.args) }.onFailure { log("BangumiParser URL route failed", it) }
                }
                installed++
            }
        responseBody?.allMethods()
            ?.filter { it.name == "string" && it.parameterCount == 0 && it.returnType == String::class.java }
            ?.forEach { method ->
                env.hookAfter(method) { param ->
                    val raw = param.result as? String ?: return@hookAfter
                    param.result = transformResponse(raw)
                }
                installed++
            }
        log("startHook: BangumiParser routes=$installed")
    }

    private fun routeRequest(builder: Any?, args: MutableList<Any?>) {
        val original = args.firstOrNull()?.toString() ?: return
        val uri = runCatching { URI(original) }.getOrNull() ?: return
        val path = uri.path ?: return
        val query = parseQuery(uri.rawQuery)
        // Recent Bilibili builds no longer expose a stable BiliAccounts
        // accessor, but their normal authenticated REST traffic still carries
        // the same short-lived key. Capture it before any route decision so a
        // later MOSS playback fallback can retain the viewer's VIP identity.
        if (AccessKeyRepository.capture(prefs, query["access_key"])) {
            log("Bangumi auth: host access key captured from request URL")
        }
        val route = when {
            path.endsWith("/pgc/player/api/playurl") -> selectPlayRoute(query)
            path.endsWith("/pgc/view/v2/app/season") -> selectSeasonRoute(query, isInternational = false)
            path.endsWith("/intl/gateway/v2/ogv/view/app/season") -> selectSeasonRoute(query, isInternational = true)
            path.endsWith("/intl/gateway/v2/app/subtitle") -> selectSubtitleRoute(query)
            path.endsWith("/intl/gateway/v2/app/search/type") ||
                path.endsWith("/intl/gateway/app/search/type") -> selectInternationalSearchRoute(query)
            path.endsWith("/x/v2/search/type") && query["type"] in areaSearchTypes -> selectSearchRoute(query)
            else -> null
        } ?: return
        val url = when (route.kind) {
            RouteKind.PLAY -> BangumiParserClient.buildPlayUrl(route.region, route.host, query, route.credential, classLoader, route.useHttps)
            RouteKind.SEARCH -> BangumiParserClient.buildSearchUrl(route.region, route.host, query, route.credential, classLoader, route.useHttps)
            RouteKind.SEASON -> BangumiParserClient.buildSeasonUrl(route.region, route.host, query, route.credential, classLoader, route.useHttps)
            RouteKind.SUBTITLE -> BangumiParserClient.buildSubtitleUrl(route.host, query, route.credential, classLoader, route.useHttps)
        }
        val httpUrl = runCatching {
            val type = classLoader.findClassOrNull("okhttp3.HttpUrl") ?: return@runCatching null
            type.getMethod("get", String::class.java).invoke(null, url)
        }.getOrNull() ?: return
        args[0] = httpUrl
        setParserHeader(builder, route.credential?.platform ?: route.region.defaultPlatform)
        log("BangumiParser routed ${route.kind.name.lowercase()}: region=${route.region.name}, path=$path, type=${query["type"].orEmpty()}")
        if (route.kind == RouteKind.SEARCH) pendingSearchRegion.set(route.region)
        if (route.kind == RouteKind.SEASON) activateRegion(route.region)
        BangumiRegionContext.recordEpisode(query["ep_id"], route.region)
        BangumiRegionContext.recordSeason(query["season_id"], route.region)
    }

    private fun selectPlayRoute(query: Map<String, String>): Route? {
        val region = query["ep_id"]?.let(::findEpisodeRegion)
            ?: currentActiveRegion()
            ?: defaultMainRegion()
        return region?.let(::routeFor)
            ?.copy(kind = RouteKind.PLAY)
    }

    private fun selectSearchRoute(query: Map<String, String>): Route? = when (query["type"]) {
        AREA_HK_TW_SEARCH_TYPE -> orderedRegions(BangumiRegion.HK).firstNotNullOfOrNull(::routeFor)
        AREA_INTL_SEARCH_TYPE -> routeFor(BangumiRegion.INTL)
        else -> null
    }?.copy(kind = RouteKind.SEARCH)

    private fun selectInternationalSearchRoute(query: Map<String, String>): Route? =
        routeFor(BangumiRegion.INTL)?.copy(kind = RouteKind.SEARCH)

    private fun selectSeasonRoute(query: Map<String, String>, isInternational: Boolean): Route? {
        val region = query["ep_id"]?.let(::findEpisodeRegion)
            ?: query["season_id"]?.let(::findSeasonRegion)
            ?: if (isInternational) BangumiRegion.INTL.takeIf { routeFor(it) != null } else defaultMainRegion()
        return region?.let(::routeFor)?.copy(kind = RouteKind.SEASON)
    }

    private fun selectSubtitleRoute(query: Map<String, String>): Route? =
        (query["ep_id"]?.let(::findEpisodeRegion)
            ?: currentActiveRegion()
            ?: BangumiRegion.INTL.takeIf { routeFor(it) != null })
            ?.takeIf { it == BangumiRegion.INTL }
            ?.let(::routeFor)
            ?.copy(kind = RouteKind.SUBTITLE)

    private fun orderedRegions(preferred: BangumiRegion?): List<BangumiRegion> =
        (listOfNotNull(preferred) + listOf(BangumiRegion.HK, BangumiRegion.TW, BangumiRegion.INTL, BangumiRegion.CN)).distinct()

    /**
     * A normal detail-page request has no region marker. Pick the first
     * configured main-site parser so that its season response establishes the
     * context used by player and gRPC requests that follow.
     */
    private fun defaultMainRegion(): BangumiRegion? =
        listOf(BangumiRegion.HK, BangumiRegion.TW, BangumiRegion.CN).firstOrNull { routeFor(it) != null }

    private fun routeFor(region: BangumiRegion): Route? {
        val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return null
        return Route(
            region,
            host,
            ModuleSettings.getBangumiServerCredential(prefs, region),
            ModuleSettings.isBangumiServerHttps(prefs, region),
            RouteKind.PLAY,
        )
    }

    private fun activateRegion(region: BangumiRegion) {
        BangumiRegionContext.activate(region)
    }

    private fun setParserHeader(builder: Any?, platform: String) {
        runCatching {
            builder?.javaClass?.methods?.firstOrNull { method ->
                method.name == "header" && method.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
            }?.invoke(builder, "platform-from-bbzq", platform)
        }.onFailure { log("BangumiParser header route failed", it) }
    }

    private fun currentActiveRegion(): BangumiRegion? = BangumiRegionContext.activeRegion()
    private fun findEpisodeRegion(id: String): BangumiRegion? =
        id.toLongOrNull()?.let(BangumiRegionContext::episodeRegion)
    private fun findSeasonRegion(id: String): BangumiRegion? =
        id.toLongOrNull()?.let(BangumiRegionContext::seasonRegion)

    private fun transformResponse(raw: String): String {
        var output = raw
        if (raw.contains("\"video_info\"")) output = BangumiParserClient.convertThailandPlayUrl(raw)
        if (ModuleSettings.isBangumiSubtitleHantToHansEnabled(prefs)) output = convertTraditionalSubtitles(output)
        pendingSearchRegion.get()?.let { region ->
            recordSearchRegions(output, region)
            pendingSearchRegion.remove()
        }
        return appendAreaSearchNavigation(output)
    }

    private fun appendAreaSearchNavigation(raw: String): String = runCatching {
        val hasHkTw = ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.HK) != null ||
            ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.TW) != null
        val hasIntl = ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.INTL) != null
        if (!hasHkTw && !hasIntl) return@runCatching raw
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: return@runCatching raw
        val nav = data.optJSONArray("nav") ?: return@runCatching raw
        val existingTypes = (0 until nav.length()).mapNotNull { nav.optJSONObject(it)?.optString("type") }.toSet()
        if (hasHkTw && AREA_HK_TW_SEARCH_TYPE !in existingTypes) {
            nav.put(JSONObject().put("name", "番剧（港澳台）").put("pages", 0).put("total", 0).put("type", AREA_HK_TW_SEARCH_TYPE))
        }
        if (hasIntl && AREA_INTL_SEARCH_TYPE !in existingTypes) {
            nav.put(JSONObject().put("name", "影视（国际）").put("pages", 0).put("total", 0).put("type", AREA_INTL_SEARCH_TYPE))
        }
        root.toString()
    }.getOrDefault(raw)

    private fun recordSearchRegions(raw: String, region: BangumiRegion) = runCatching {
        val root = JSONObject(raw)
        fun record(item: JSONObject) {
            BangumiRegionContext.recordSeason(item.optString("season_id"), region)
            BangumiRegionContext.recordEpisode(item.optString("ep_id"), region)
            item.optString("param").takeIf { it.matches(Regex("\\d+")) }?.let { BangumiRegionContext.recordSeason(it, region) }
            item.optJSONArray("episodes")?.let { episodes ->
                for (index in 0 until episodes.length()) episodes.optJSONObject(index)?.let(::record)
            }
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    record(value)
                    value.optJSONArray("items")?.let { items -> for (index in 0 until items.length()) visit(items.opt(index)) }
                    value.optJSONArray("result")?.let { items -> for (index in 0 until items.length()) visit(items.opt(index)) }
                    value.optJSONObject("data")?.let(::visit)
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
    }

    private fun convertTraditionalSubtitles(raw: String): String = runCatching {
        val root = JSONObject(raw)
        val body = root.optJSONArray("body") ?: return@runCatching raw
        if (!body.hasSubtitleEntries()) return@runCatching raw
        val converter = Transliterator.getInstance("Hant-Hans")
        for (index in 0 until body.length()) {
            body.optJSONObject(index)?.optString("content")?.takeIf(String::isNotEmpty)?.let {
                body.optJSONObject(index)?.put("content", converter.transliterate(it))
            }
        }
        root.toString()
    }.getOrDefault(raw)

    private fun JSONArray.hasSubtitleEntries(): Boolean = (0 until length()).any { index ->
        optJSONObject(index)?.let { it.has("from") && it.has("to") && it.has("content") } == true
    }

    private fun parseQuery(raw: String?): Map<String, String> = buildMap {
        raw.orEmpty().split('&').filter(String::isNotEmpty).forEach { item ->
            val (key, value) = item.split('=', limit = 2).let { it.first() to it.getOrElse(1) { "" } }
            put(decode(key), decode(value))
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private data class Route(
        val region: BangumiRegion,
        val host: String,
        val credential: io.github.bbzq.BangumiServerCredential?,
        val useHttps: Boolean,
        val kind: RouteKind,
    )

    private enum class RouteKind { PLAY, SEARCH, SEASON, SUBTITLE }

    private companion object {
        const val AREA_HK_TW_SEARCH_TYPE = "1919"
        const val AREA_INTL_SEARCH_TYPE = "1920"
        val areaSearchTypes = setOf(AREA_HK_TW_SEARCH_TYPE, AREA_INTL_SEARCH_TYPE)
    }
}
