package io.github.bbzq.feats.hook

import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.bangumi.BangumiParserClient
import io.github.bbzq.feats.bangumi.BangumiRegionContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/** Adds regional search categories and serves their results through BBZQ. */
class BangumiSearchMossHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "bbzq-bangumi-search").apply { isDaemon = true }
    }

    override fun startHook() {
        if (env.processName != env.packageName || !ModuleSettings.isAddBangumiEnabled(prefs)) return
        val searchMoss = SEARCH_MOSS_CLASSES.asSequence().mapNotNull(classLoader::findClassOrNull).firstOrNull()
        if (searchMoss == null) {
            log("startHook: BangumiSearchMoss class unavailable")
            return
        }
        var installed = 0
        searchMoss.allMethods()
            .filter { !Modifier.isStatic(it.modifiers) && it.name == "searchAll" && it.parameterCount >= 2 }
            .forEach { method ->
                env.hookBefore(method) { param ->
                    val callback = param.args.getOrNull(1) ?: return@hookBefore
                    wrapCallback(callback) { response -> addAreaNavigation(response) }
                        ?.let { param.args[1] = it }
                }
                installed++
            }
        searchMoss.allMethods()
            .filter { !Modifier.isStatic(it.modifiers) && it.name == "searchByType" && it.parameterCount >= 2 }
            .forEach { method ->
                env.hookBefore(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookBefore
                    val callback = param.args.getOrNull(1) ?: return@hookBefore
                    val area = BangumiSearchMossModel.areaSearch(request.number("getType")?.toString())
                        ?: return@hookBefore
                    val replacement = requestAreaSearch(request, callback, area)
                    if (replacement) param.result = null
                }
                installed++
            }
        log("startHook: BangumiSearchMoss methods=$installed")
    }

    private fun requestAreaSearch(request: Any, callback: Any, area: BangumiSearchMossModel.AreaSearch): Boolean {
        val keyword = request.string("getKeyword")
        val pagination = request.callMethod("getPagination")
        val playerArgs = request.callMethod("getPlayerArgs")
        val page = pagination?.string("getNext").orEmpty().ifBlank { "1" }
        val pageSize = pagination?.number("getPageSize")?.toInt()?.takeIf { it > 0 } ?: 20
        val query = BangumiSearchMossModel.query(
            keyword = keyword,
            page = page,
            pageSize = pageSize,
            qn = playerArgs?.number("getQn")?.toLong() ?: 80L,
            fnver = playerArgs?.number("getFnver")?.toInt() ?: 0,
            fnval = playerArgs?.number("getFnval")?.toInt() ?: 16,
        )
        executor.execute {
            val attempt = requestSearch(area, query)
            val result = attempt.result
                    val response = result.body?.let {
                        buildSearchResponse(it, keyword, page)
                    }
            when {
                response != null -> {
                    result.body?.let { recordSearchRegions(it, attempt.region) }
                    callback.callMethod("onNext", response)
                    callback.callMethod("onCompleted")
                }
                else -> callback.callMethod("onError", null)
            }
            log(
                "BangumiSearchMoss search region=${area.region.name} status=${result.httpStatus ?: "transport"} " +
                    "bytes=${result.byteSize ?: 0} json=${result.isJson} error=${result.error ?: "none"}",
            )
        }
        log("BangumiSearchMoss search type=${area.type} region=${area.region.name} keyword=${keyword.take(80)}")
        return true
    }

    private fun requestSearch(area: BangumiSearchMossModel.AreaSearch, query: Map<String, String>): SearchAttempt {
        val host = when {
            area.region == BangumiRegion.HK -> ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.HK)
                ?: ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.TW)
            else -> ModuleSettings.getBangumiServerHost(prefs, area.region)
        } ?: return SearchAttempt(area.region, BangumiParserClient.Result(null, error = "server not configured"))
        val region = if (area.region == BangumiRegion.HK && ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.HK) == null) {
            BangumiRegion.TW
        } else area.region
        val credential = parserCredential(region)
        return SearchAttempt(region, BangumiParserClient.requestSearch(
            region = region,
            host = host,
            query = query + ("type" to area.upstreamType),
            credential = credential,
            classLoader = classLoader,
            useHttps = ModuleSettings.isBangumiServerHttps(prefs, region),
        ))
    }

    private fun parserCredential(region: BangumiRegion): BangumiServerCredential? =
        ModuleSettings.getBangumiServerCredential(prefs, region)
            ?: AccessKeyRepository.read(prefs)?.let { BangumiServerCredential(it, region.defaultPlatform) }

    private fun addAreaNavigation(response: Any?): Any? {
        response ?: return null
        val navs = response.callMethod("getNavList") as? List<*> ?: return response
        if (navs.isEmpty()) return response
        val navType = navs.firstOrNull()?.javaClass ?: return response
        val existing = navs.mapNotNull { it?.number("getType")?.toString() }.toMutableSet()
        val additions = listOf(
            BangumiSearchMossModel.AreaSearch(BangumiSearchMossModel.HK_TW_TYPE, BangumiRegion.HK, "7") to "番剧（港澳台）",
            BangumiSearchMossModel.AreaSearch(BangumiSearchMossModel.INTERNATIONAL_TYPE, BangumiRegion.INTL, "8") to "影视（国际）",
        )
        val newNavs = additions.filter { (area, _) ->
            existing.add(area.type) && hasConfiguredServer(area.region)
        }.mapNotNull { (area, title) -> buildNav(navType, area.type, title) }
        if (newNavs.isEmpty()) return response
        response.callMethod("clearNav")
        newNavs.forEach { response.callMethod("addNav", it) }
        navs.forEach { if (it != null) response.callMethod("addNav", it) }
        return response
    }

    private fun hasConfiguredServer(region: BangumiRegion): Boolean =
        ModuleSettings.getBangumiServerHost(prefs, region) != null ||
            (region == BangumiRegion.HK && ModuleSettings.getBangumiServerHost(prefs, BangumiRegion.TW) != null)

    private fun buildNav(type: Class<*>, value: String, title: String): Any? = runCatching {
        val builder = type.staticCall("newBuilder") ?: return@runCatching null
        builder.invokeBuilder("setName", title)
        builder.invokeBuilder("setType", value.toInt())
        builder.invokeBuilder("setPages", 0)
        builder.invokeBuilder("setTotal", 0)
        builder.callMethod("build")
    }.getOrNull()

    private fun wrapCallback(callback: Any, transform: (Any?) -> Any?): Any? {
        callback.javaClass.interfaces.firstOrNull { type ->
            type.methods.any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return null
        return Proxy.newProxyInstance(
            callback.javaClass.classLoader ?: classLoader,
            callback.javaClass.interfaces,
        ) { _, method, args ->
            if (method.name == "onNext" && args?.isNotEmpty() == true) {
                args[0] = transform(args[0])
            }
            method.invoke(callback, *(args ?: emptyArray()))
        }
    }

    private fun Any.string(name: String): String = callMethod(name) as? String ?: ""
    private fun Any.number(name: String): Number? = callMethod(name) as? Number

    private fun buildSearchResponse(raw: String, keyword: String, page: String): Any? = runCatching {
        val responseType = SEARCH_BY_TYPE_RESPONSE_CLASSES.asSequence()
            .mapNotNull(classLoader::findClassOrNull)
            .firstOrNull() ?: return@runCatching null
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) return@runCatching null
        val data = root.optJSONObject("data") ?: return@runCatching null
        val response = responseType.staticCall("newBuilder") ?: return@runCatching null
        response.invokeBuilder("setKeyword", keyword)
        response.invokeBuilder("setPages", data.optInt("pages", 1))
        val currentPage = page.toIntOrNull() ?: 1
        val totalPages = data.optInt("pages", 1)
        if (currentPage < totalPages) {
            response.callMethod("getPaginationBuilder")?.apply {
                invokeBuilder("setNext", (currentPage + 1).toString())
            }
        }
        data.optJSONArray("items").forEachObject { item ->
            val searchItem = response.callMethod("addItemsBuilder") ?: return@forEachObject
            searchItem.copyFields(item, SEARCH_ITEM_FIELDS)
            val card = searchItem.callMethod("getBangumiBuilder") ?: return@forEachObject
            card.copyFields(item, BANGUMI_FIELDS)
            card.copyEpisodes(item.optJSONArray("episodes"), "addEpisodesBuilder", EPISODE_FIELDS)
            card.copyEpisodes(item.optJSONArray("episodes_new"), "addEpisodesNewBuilder", EPISODE_NEW_FIELDS)
            item.optJSONObject("watch_button")?.let { button ->
                card.callMethod("getWatchButtonBuilder")?.copyFields(button, WATCH_BUTTON_FIELDS)
            }
        }
        response.callMethod("build")
    }.onFailure { log("BangumiSearchMoss response build failed", it) }.getOrNull()

    private fun recordSearchRegions(raw: String, region: BangumiRegion) = runCatching {
        val root = JSONObject(raw)
        fun first(item: JSONObject, vararg keys: String): String? = keys.asSequence()
            .map(item::optString)
            .firstOrNull(String::isNotBlank)
        fun isMovie(item: JSONObject): Boolean =
            item.optInt("season_type") == 2 || item.optInt("media_type") == 2 ||
                item.optString("season_type_name").contains("影") || item.optString("type").equals("movie", true)
        fun record(item: JSONObject, parentSeason: String? = null, parentMovie: Boolean = false) {
            val seasonId = first(item, "season_id", "seasonId") ?: parentSeason
            val movie = parentMovie || isMovie(item)
            val episodeId = first(item, "ep_id", "episode_id", "id", "object_id")
            val cid = first(item, "cid")
            BangumiRegionContext.recordSeason(seasonId, region)
            BangumiRegionContext.recordEpisode(episodeId, region)
            if (episodeId != null && cid != null) {
                BangumiRegionContext.recordEpisodeReference(episodeId, cid, seasonId, region, movie)
            }
            item.optString("param").takeIf { it.matches(Regex("\\d+")) }?.let { param ->
                if (cid != null) BangumiRegionContext.recordEpisodeReference(param, cid, seasonId, region, movie)
                else BangumiRegionContext.recordSeason(param, region)
            }
            item.optJSONArray("episodes")?.forEachObject { record(it, seasonId, movie) }
            item.optJSONArray("episodes_new")?.forEachObject { record(it, seasonId, movie) }
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    record(value)
                    value.optJSONArray("items")?.forEachObject(::visit)
                    value.optJSONArray("result")?.forEachObject(::visit)
                    value.optJSONObject("data")?.let(::visit)
                }
                is JSONArray -> value.forEachObject(::visit)
            }
        }
        visit(root)
    }.onFailure { log("BangumiSearchMoss region indexing failed", it) }

    private fun Any.copyEpisodes(items: JSONArray?, builderMethod: String, fields: List<FieldSpec>) {
        items.forEachObject { item -> callMethod(builderMethod)?.copyFields(item, fields) }
    }

    private fun Any.copyFields(json: JSONObject, fields: List<FieldSpec>) {
        fields.forEach { field ->
            if (!json.has(field.jsonKey)) return@forEach
            invokeBuilder(field.setter, field.value(json))
        }
    }

    private fun Any.invokeBuilder(name: String, value: Any?) {
        val method = javaClass.allMethods().firstOrNull {
            it.name == name && it.parameterCount == 1 && accepts(it.parameterTypes[0], value)
        } ?: return
        runCatching { method.invoke(this, value) }
    }

    private fun Class<*>.staticCall(name: String): Any? = methods.firstOrNull {
        Modifier.isStatic(it.modifiers) && it.name == name && it.parameterCount == 0
    }?.let { runCatching { it.invoke(null) }.getOrNull() }

    private fun accepts(type: Class<*>, value: Any?): Boolean = when {
        value == null -> !type.isPrimitive
        type.isInstance(value) -> true
        !type.isPrimitive -> type.isAssignableFrom(value.javaClass)
        type == Int::class.javaPrimitiveType -> value is Int
        type == Long::class.javaPrimitiveType -> value is Long
        type == Double::class.javaPrimitiveType -> value is Double
        type == Float::class.javaPrimitiveType -> value is Float
        type == Boolean::class.javaPrimitiveType -> value is Boolean
        else -> false
    }

    private companion object {
        private data class SearchAttempt(
            val region: BangumiRegion,
            val result: BangumiParserClient.Result,
        )
        private data class FieldSpec(val jsonKey: String, val setter: String, val read: (JSONObject) -> Any?) {
            fun value(json: JSONObject): Any? = read(json)
        }

        private fun string(jsonKey: String, setter: String) = FieldSpec(jsonKey, setter) { it.optString(jsonKey) }
        private fun integer(jsonKey: String, setter: String) = FieldSpec(jsonKey, setter) { it.optInt(jsonKey) }
        private fun long(jsonKey: String, setter: String) = FieldSpec(jsonKey, setter) { it.optLong(jsonKey) }
        private fun decimal(jsonKey: String, setter: String) = FieldSpec(jsonKey, setter) { it.optDouble(jsonKey) }

        private val SEARCH_ITEM_FIELDS = listOf(
            string("uri", "setUri"), string("param", "setParam"), string("goto", "setGoto"),
            string("link_type", "setLinkType"), integer("position", "setPosition"), string("track_id", "setTrackId"),
        )
        private val BANGUMI_FIELDS = listOf(
            string("title", "setTitle"), string("cover", "setCover"), integer("media_type", "setMediaType"),
            integer("play_state", "setPlayState"), string("area", "setArea"), string("style", "setStyle"),
            string("styles", "setStyles"), string("cv", "setCv"), decimal("rating", "setRating"),
            integer("vote", "setVote"), string("target", "setTarget"), string("staff", "setStaff"),
            string("prompt", "setPrompt"), long("ptime", "setPtime"), string("season_type_name", "setSeasonTypeName"),
            integer("is_selection", "setIsSelection"), integer("is_atten", "setIsAtten"), string("label", "setLabel"),
            long("season_id", "setSeasonId"), string("out_name", "setOutName"), string("out_icon", "setOutIcon"),
            string("out_url", "setOutUrl"), integer("is_out", "setIsOut"), string("selection_style", "setSelectionStyle"),
            string("styles_v2", "setStylesV2"),
        )
        private val EPISODE_FIELDS = listOf(
            string("uri", "setUri"), string("param", "setParam"), string("index", "setIndex"), integer("position", "setPosition"),
        )
        private val EPISODE_NEW_FIELDS = listOf(
            string("title", "setTitle"), string("uri", "setUri"), string("param", "setParam"), integer("is_new", "setIsNew"),
            integer("type", "setType"), integer("position", "setPosition"), string("cover", "setCover"), string("label", "setLabel"),
        )
        private val WATCH_BUTTON_FIELDS = listOf(string("title", "setTitle"), string("link", "setLink"))

        val SEARCH_MOSS_CLASSES = listOf(
            "com.bapis.bilibili.polymer.app.search.v1.SearchMoss",
            "com.bapis.bilibili.p4218polymer.app.search.v1.SearchMoss",
            "com.bapis.bilibili.p4311polymer.app.search.v1.SearchMoss",
        )
        private val SEARCH_BY_TYPE_RESPONSE_CLASSES = listOf(
            "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse",
            "com.bapis.bilibili.p4218polymer.app.search.v1.SearchByTypeResponse",
            "com.bapis.bilibili.p4311polymer.app.search.v1.SearchByTypeResponse",
        )
    }
}

private fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
    if (this == null) return
    for (index in 0 until length()) optJSONObject(index)?.let(action)
}
