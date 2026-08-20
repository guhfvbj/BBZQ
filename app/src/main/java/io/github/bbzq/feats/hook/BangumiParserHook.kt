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
import io.github.bbzq.feats.bangumi.BangumiSubtitleModel
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes region-specific JSON endpoints to a compatible parser.
 * The original request is left intact whenever a route cannot be rebuilt.
 */
class BangumiParserHook(env: RoamingEnv) : BaseRoamingHook(env) {
    // OkHttp may route the response callback to a different thread than the
    // Request.Builder hook. Keep this context process-wide so search cards are
    // still indexed before the user starts playback.
    private val pendingSearchRegion = AtomicReference<BangumiRegion?>()

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
        installed += installSubtitleParserHook()
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
            path.endsWith(DM_VIEW_PATH) -> selectDmViewRoute()
            path.endsWith("/intl/gateway/v2/app/search/type") ||
                path.endsWith("/intl/gateway/app/search/type") -> selectInternationalSearchRoute(query)
            path.endsWith("/x/v2/search/type") && query["type"] in areaSearchTypes -> selectSearchRoute(query)
            else -> null
        } ?: return
        val routedQuery = if (route.kind == RouteKind.PLAY) {
            BangumiRegionContext.resolvePlayQuery(query, route.region)
        } else query
        val url = when (route.kind) {
            RouteKind.PLAY -> BangumiParserClient.buildPlayUrl(route.region, route.host, routedQuery, route.credential, classLoader, route.useHttps)
            RouteKind.SEARCH -> BangumiParserClient.buildSearchUrl(route.region, route.host, routedQuery, route.credential, classLoader, route.useHttps)
            RouteKind.SEASON -> BangumiParserClient.buildSeasonUrl(route.region, route.host, routedQuery, route.credential, classLoader, route.useHttps)
            RouteKind.SUBTITLE -> BangumiParserClient.buildSubtitleUrl(route.host, routedQuery, route.credential, classLoader, route.useHttps)
            RouteKind.DM_VIEW -> BangumiParserClient.buildGrpcProxyUrl(route.host, uri, route.useHttps)
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
        BangumiRegionContext.recordEpisode(routedQuery["ep_id"], route.region)
        BangumiRegionContext.recordSeason(routedQuery["season_id"], route.region)
        if (route.kind == RouteKind.PLAY) {
            BangumiRegionContext.recordEpisodeReference(
                routedQuery["ep_id"],
                routedQuery["cid"],
                routedQuery["season_id"],
                route.region,
                isMovie = false,
            )
        }
    }

    private fun selectPlayRoute(query: Map<String, String>): Route? {
        val region = query["ep_id"]?.let(::findEpisodeRegion)
            ?: currentActiveRegion()
            ?: defaultMainRegion()
        return region?.let(::routeFor)
            ?.copy(kind = RouteKind.PLAY)
    }

    private fun selectSearchRoute(query: Map<String, String>): Route? = when (query["type"]) {
        AREA_HK_TW_SEARCH_TYPE -> orderedSearchRegions().firstNotNullOfOrNull(::routeFor)
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

    private fun selectDmViewRoute(): Route? =
        BangumiRegionContext.consumeDmViewRegion()
            ?.takeIf { it == BangumiRegion.HK || it == BangumiRegion.TW }
            ?.let(::routeFor)
            ?.copy(kind = RouteKind.DM_VIEW)

    private fun orderedSearchRegions(): List<BangumiRegion> =
        listOf(BangumiRegion.TW, BangumiRegion.HK)

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
        pendingSearchRegion.get()?.takeIf { output.contains("\"items\"") }?.let { region ->
            if (pendingSearchRegion.compareAndSet(region, null)) recordSearchRegions(output, region)
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
        fun firstNonBlank(item: JSONObject, vararg keys: String): String? =
            keys.asSequence().map { item.optString(it) }.firstOrNull { it.isNotBlank() }

        fun isMovie(item: JSONObject): Boolean =
            item.optInt("season_type") == 2 ||
                item.optInt("media_type") == 2 ||
                item.optString("season_type_name").contains("影") ||
                item.optString("type").equals("movie", ignoreCase = true)

        fun record(item: JSONObject, inheritedSeasonId: String? = null, inheritedMovie: Boolean = false) {
            val seasonId = firstNonBlank(item, "season_id", "seasonId") ?: inheritedSeasonId
            val movie = inheritedMovie || isMovie(item)
            BangumiRegionContext.recordSeason(seasonId, region)
            BangumiRegionContext.recordEpisode(firstNonBlank(item, "ep_id", "episode_id", "id"), region)
            val itemEpisode = firstNonBlank(item, "ep_id", "episode_id", "id", "object_id")
            val itemCid = firstNonBlank(item, "cid")
            if (itemEpisode != null && itemCid != null) {
                BangumiRegionContext.recordEpisodeReference(itemEpisode, itemCid, seasonId, region, movie)
            }
            item.optString("param").takeIf { it.matches(Regex("\\d+")) }?.let {
                if (itemCid != null) {
                    BangumiRegionContext.recordEpisodeReference(it, itemCid, seasonId, region, movie)
                } else {
                    BangumiRegionContext.recordSeason(it, region)
                }
            }
            item.optJSONArray("episodes")?.let { episodes ->
                for (index in 0 until episodes.length()) {
                    val episode = episodes.optJSONObject(index) ?: continue
                    val episodeId = firstNonBlank(episode, "id", "ep_id", "episode_id", "param")
                    val cid = firstNonBlank(episode, "cid")
                    if (episodeId != null && cid != null) {
                        BangumiRegionContext.recordEpisodeReference(episodeId, cid, seasonId, region, movie)
                    }
                    record(episode, seasonId, movie)
                }
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

    private fun installSubtitleParserHook(): Int {
        val biliCall = classLoader.findClassOrNull("com.bilibili.okretro.call.BiliCall") ?: return 0
        val requestType = classLoader.findClassOrNull("okhttp3.Request") ?: return 0
        val requestField = generateSequence(biliCall as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { requestType.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true } ?: return 0
        val setter = biliCall.allMethods().firstOrNull { method ->
            method.parameterCount == 1 && method.parameterTypes[0].let { type ->
                type.isInterface && type.interfaces.size == 1 && type.interfaces[0].declaredMethods.size == 1
            }
        } ?: return 0
        val parserType = setter.parameterTypes[0]
        val responseBodyType = classLoader.findClassOrNull("okhttp3.ResponseBody") ?: return 0
        val responseBodyFactory = responseBodyType.allMethods().firstOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.name == "create" && method.parameterCount == 2 &&
                method.parameterTypes.any { it == String::class.java }
        } ?: return 0
        env.hookBefore(setter) { param ->
            if (!ModuleSettings.isBangumiSubtitleHantToHansEnabled(prefs)) return@hookBefore
            val request = runCatching { requestField.get(param.thisObject) }.getOrNull() ?: return@hookBefore
            val url = request.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in setOf("url", "getUrl")
            }?.let { runCatching { it.invoke(request)?.toString() }.getOrNull() }
            if (!BangumiSubtitleModel.isConversionUrl(url)) return@hookBefore
            val parser = param.args.firstOrNull() ?: return@hookBefore
            param.args[0] = Proxy.newProxyInstance(
                parser.javaClass.classLoader ?: classLoader,
                arrayOf(parserType),
            ) { _, method, args ->
                if (method.declaringClass == Any::class.java || args.isNullOrEmpty()) {
                    return@newProxyInstance if (args == null) method.invoke(parser) else method.invoke(parser, *args)
                }
                val originalBody = args[0]
                val raw = originalBody?.javaClass?.methods?.firstOrNull {
                    it.name == "string" && it.parameterCount == 0 && it.returnType == String::class.java
                }?.let { runCatching { it.invoke(originalBody) as? String }.getOrNull() }
                val convertedBody = raw?.let { createConvertedResponseBody(originalBody, it, responseBodyFactory) }
                if (convertedBody != null) args[0] = convertedBody
                method.invoke(parser, *args)
            }
        }
        return 1
    }

    private fun createConvertedResponseBody(
        originalBody: Any,
        raw: String,
        factory: java.lang.reflect.Method,
    ): Any? = runCatching {
        val converter = Transliterator.getInstance("Hant-Hans")
        val converted = BangumiSubtitleModel.convertSubtitleJson(raw, converter::transliterate)
        val mediaType = originalBody.javaClass.methods.firstOrNull {
            it.name == "contentType" && it.parameterCount == 0
        }?.invoke(originalBody)
        val values = if (factory.parameterTypes[0] == String::class.java) {
            arrayOf(converted, mediaType)
        } else {
            arrayOf(mediaType, converted)
        }
        factory.invoke(null, *values)
    }.onFailure { log("Bangumi subtitle conversion failed", it) }.getOrNull()

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

    private enum class RouteKind { PLAY, SEARCH, SEASON, SUBTITLE, DM_VIEW }

    private companion object {
        const val AREA_HK_TW_SEARCH_TYPE = "1919"
        const val AREA_INTL_SEARCH_TYPE = "1920"
        const val DM_VIEW_PATH = "/bilibili.community.service.dm.v1.DM/DmView"
        val areaSearchTypes = setOf(AREA_HK_TW_SEARCH_TYPE, AREA_INTL_SEARCH_TYPE)
    }
}
