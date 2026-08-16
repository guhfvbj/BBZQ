package io.github.bbzq.feats.hook

import android.icu.text.Transliterator
import io.github.bbzq.BangumiRegion
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.bangumi.BangumiParserClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes the JSON endpoints used by the domestic client to a compatible HTTPS parser.
 * The original request is left intact whenever a route cannot be rebuilt.
 */
class BangumiParserHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val episodeRegions = ConcurrentHashMap<String, BangumiRegion>()

    override fun startHook() {
        if (env.processName != env.packageName || !ModuleSettings.isAddBangumiEnabled(prefs)) return
        val requestBuilder = classLoader.findClassOrNull("okhttp3.Request\$Builder")
        val responseBody = classLoader.findClassOrNull("okhttp3.ResponseBody")
        var installed = 0
        requestBuilder?.allMethods()
            ?.filter { it.name == "url" && it.parameterCount == 1 && it.parameterTypes[0].name == "okhttp3.HttpUrl" }
            ?.forEach { method ->
                env.hookBefore(method) { param ->
                    runCatching { routeRequest(param.args) }.onFailure { log("BangumiParser URL route failed", it) }
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

    private fun routeRequest(args: MutableList<Any?>) {
        val original = args.firstOrNull()?.toString() ?: return
        val uri = runCatching { URI(original) }.getOrNull() ?: return
        val path = uri.path ?: return
        val query = parseQuery(uri.rawQuery)
        val route = when {
            path.endsWith("/pgc/player/api/playurl") -> selectPlayRoute(query)
            path.endsWith("/x/v2/search/type") && query["type"] == AREA_SEARCH_TYPE -> selectSearchRoute(query)
            else -> null
        } ?: return
        val url = when (route.kind) {
            RouteKind.PLAY -> BangumiParserClient.buildPlayUrl(route.region, route.host, query, route.credential, classLoader)
            RouteKind.SEARCH -> BangumiParserClient.buildSearchUrl(route.region, route.host, query, route.credential, classLoader)
        }
        val httpUrl = runCatching {
            val type = classLoader.findClassOrNull("okhttp3.HttpUrl") ?: return@runCatching null
            type.getMethod("get", String::class.java).invoke(null, url)
        }.getOrNull() ?: return
        args[0] = httpUrl
        query["ep_id"]?.takeIf(String::isNotBlank)?.let { episodeRegions[it] = route.region }
    }

    private fun selectPlayRoute(query: Map<String, String>): Route? {
        val episode = query["ep_id"]
        val preferred = episode?.let(episodeRegions::get)
        return orderedRegions(preferred).firstNotNullOfOrNull(::routeFor)
            ?.copy(kind = RouteKind.PLAY)
    }

    private fun selectSearchRoute(query: Map<String, String>): Route? =
        orderedRegions(BangumiRegion.HK).firstNotNullOfOrNull(::routeFor)?.copy(kind = RouteKind.SEARCH)

    private fun orderedRegions(preferred: BangumiRegion?): List<BangumiRegion> =
        (listOfNotNull(preferred) + listOf(BangumiRegion.HK, BangumiRegion.TW, BangumiRegion.TH, BangumiRegion.CN)).distinct()

    private fun routeFor(region: BangumiRegion): Route? {
        val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return null
        return Route(region, host, ModuleSettings.getBangumiServerCredential(prefs, region), RouteKind.PLAY)
    }

    private fun transformResponse(raw: String): String {
        var output = raw
        if (raw.contains("\"video_info\"")) output = BangumiParserClient.convertThailandPlayUrl(raw)
        if (ModuleSettings.isBangumiSubtitleHantToHansEnabled(prefs)) output = convertTraditionalSubtitles(output)
        return appendAreaSearchNavigation(output)
    }

    private fun appendAreaSearchNavigation(raw: String): String = runCatching {
        if (ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.HK) == null &&
            ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.TW) == null
        ) return@runCatching raw
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: return@runCatching raw
        val nav = data.optJSONArray("nav") ?: return@runCatching raw
        if ((0 until nav.length()).any { nav.optJSONObject(it)?.optString("type") == AREA_SEARCH_TYPE }) return@runCatching raw
        nav.put(JSONObject().put("name", "港澳台番剧").put("pages", 0).put("total", 0).put("type", AREA_SEARCH_TYPE))
        root.toString()
    }.getOrDefault(raw)

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
        val kind: RouteKind,
    )

    private enum class RouteKind { PLAY, SEARCH }

    private companion object {
        const val AREA_SEARCH_TYPE = "1919"
    }
}
