package io.github.bbzq.feats.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleDebugLog
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.appendEnumConstantOrNull
import io.github.bbzq.feats.bangumi.BangumiParserClient
import io.github.bbzq.feats.bangumi.BangumiRegionContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Adds regional search categories and serves their results through BBZQ. */
class BangumiSearchMossHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "bbzq-bangumi-search").apply { isDaemon = true }
    }
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bbzq-bangumi-search-timeout").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installedFragmentClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private var classLoadHookInstalled = false

    override fun startHook() {
        val isMainProcess = env.processName == env.packageName
        val isEnabled = ModuleSettings.isAddBangumiEnabled(prefs)
        if (!isMainProcess || !isEnabled) {
            reportStatus("skipped main=$isMainProcess enabled=$isEnabled")
            return
        }
        installClassLoadBridge()
        val searchMosses = SEARCH_MOSS_CLASSES.mapNotNull(classLoader::findClassOrNull)
        if (searchMosses.isEmpty()) {
            reportStatus("unavailable enabled=true classes=0")
            return
        }
        val navigationMethods = searchMosses.asSequence()
            .flatMap { it.allMethods() }
            .filter { !Modifier.isStatic(it.modifiers) && it.name == "searchAll" && it.parameterCount >= 2 }
            .distinctBy { it.toGenericString() }
            .toList()
        navigationMethods
            .forEach { method ->
                env.hookBefore(method) { param ->
                    val callbackIndex = param.args.indexOfFirst { it?.isSearchCallback() == true }
                    if (callbackIndex < 0) {
                        log("BangumiSearchMoss navigation skipped: callback unavailable method=${method.signature()}")
                        return@hookBefore
                    }
                    val callback = param.args[callbackIndex] ?: return@hookBefore
                    wrapCallback(callback) { response -> addAreaNavigation(response) }
                        ?.let { param.args[callbackIndex] = it }
                }
            }
        val pageTypeStatus = installResultPageTypes()
        val fragmentStatus = installResultFragmentTypeFix()
        val searchMethods = searchMosses.asSequence()
            .flatMap { it.allMethods() }
            .filter { !Modifier.isStatic(it.modifiers) && it.name == "searchByType" && it.parameterCount >= 2 }
            .distinctBy { it.toGenericString() }
            .toList()
        searchMethods
            .forEach { method ->
                env.hookBefore(method) { param ->
                    val invocation = findSearchInvocation(param.args)
                    if (invocation == null) {
                        log("BangumiSearchMoss search skipped: request/callback unavailable method=${method.signature()}")
                        return@hookBefore
                    }
                    val type = invocation.request.searchType()
                    val area = BangumiSearchMossModel.areaSearch(type) ?: return@hookBefore
                    val replacement = requestAreaSearch(
                        request = invocation.request,
                        callback = invocation.callback,
                        area = area,
                        methodSignature = method.signature(),
                        responseType = responseTypeFor(invocation.callback, method),
                    )
                    if (replacement) param.result = null
                }
            }
        reportStatus(
            "installed enabled=true classes=${searchMosses.joinToString { it.name }} " +
                "nav=${navigationMethods.size} search=${searchMethods.size} " +
                "pageTypes=$pageTypeStatus fragments=$fragmentStatus",
        )
    }

    private fun installResultPageTypes(): String {
        val targets = listOf(
            "com.bilibili.search2.result.pages.BiliMainSearchResultPage\$PageTypes",
            "com.bilibili.search.result.pages.BiliMainSearchResultPage\$PageTypes",
        )
        var found = 0
        var added = 0
        targets.mapNotNull(classLoader::findClassOrNull).forEach { type ->
            found++
            val hk = type.appendEnumConstantOrNull(
                "PAGE_BANGUMI_HK_TW",
                "bilibili://search-result/new-bangumi?from=hk", 1919, "bangumi",
            )
            val intl = type.appendEnumConstantOrNull(
                "PAGE_MOVIE_INTL",
                "bilibili://search-result/new-movie?from=intl", 1920, "movie",
            )
            if (hk) added++
            if (intl) added++
        }
        return "found=$found constants=$added"
    }

    private fun installResultFragmentTypeFix(): String {
        val targets = listOf(
            "com.bilibili.search2.ogv.OgvSearchResultFragment",
            "com.bilibili.search.ogv.OgvSearchResultFragment",
            "com.bilibili.search.result.bangumi.ogv.BangumiSearchResultFragment",
            "com.bilibili.bangumi.ui.page.search.BangumiSearchResultFragment",
        )
        var methods = 0
        targets.mapNotNull(classLoader::findClassOrNull).forEach { type ->
            if (!installedFragmentClasses.add(type.name)) return@forEach
            methods += type.allMethods()
                .filter { it.name == "setUserVisibleCompat" && it.parameterCount == 1 }
                .distinctBy { it.toGenericString() }
                .onEach { method ->
                    env.hookBefore(method) { param ->
                        val visible = param.args.firstOrNull() as? Boolean ?: return@hookBefore
                        if (!visible) return@hookBefore
                        val from = param.thisObject?.callMethod("getArguments")?.callMethod("getString", "from") as? String
                            ?: return@hookBefore
                        val targetType = BangumiSearchMossModel.pageTypeFor(from)?.toInt() ?: return@hookBefore
                        var changed = 0
                        param.thisObject?.javaClass?.allFields()?.filter {
                            it.type == Int::class.javaPrimitiveType || it.type == Int::class.javaObjectType
                        }?.forEach { field ->
                            runCatching {
                                if ((field.get(param.thisObject) as? Number)?.toInt() in setOf(7, 8, 1919, 1920)) {
                                    field.set(param.thisObject, targetType)
                                    changed++
                                }
                            }
                        }
                        reportStatus("fragment from=$from type=$targetType changed=$changed")
                    }
                }.count()
        }
        return "methods=$methods"
    }

    private fun installClassLoadBridge() {
        if (classLoadHookInstalled) return
        val loadClass = runCatching {
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull() ?: return
        classLoadHookInstalled = true
        env.hookAfter(loadClass) { param ->
            val name = param.args.firstOrNull() as? String ?: return@hookAfter
            if (name == "com.bilibili.search2.result.pages.BiliMainSearchResultPage\$PageTypes" ||
                name == "com.bilibili.search.result.pages.BiliMainSearchResultPage\$PageTypes"
            ) {
                installResultPageTypes()
            }
            if (name.contains("OgvSearchResultFragment") || name.contains("BangumiSearchResultFragment")) {
                installResultFragmentTypeFix()
            }
        }
    }

    private fun requestAreaSearch(
        request: Any,
        callback: Any,
        area: BangumiSearchMossModel.AreaSearch,
        methodSignature: String,
        responseType: SearchResponseTypeResolution,
    ): Boolean {
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
        val startedAt = SystemClock.elapsedRealtime()
        val delivery = SearchDeliveryGate()
        val resolvedType = responseType.type
        if (resolvedType == null) {
            finishSearch(
                delivery = delivery,
                callback = callback,
                response = null,
                area = area,
                source = area.region.name,
                reason = "response_type_unavailable",
                methodSignature = methodSignature,
                startedAt = startedAt,
            )
            val status =
                "intercepted type=${area.type} region=${area.region.name} response_type=unavailable " +
                    "type_source=${responseType.source} keyword_length=${keyword.length}"
            reportStatus(status)
            log("BangumiSearchMoss $status method=$methodSignature")
            return true
        }
        val timeout = timeoutExecutor.schedule({
            val empty = buildEmptySearchResponse(resolvedType, keyword)
            finishSearch(
                delivery = delivery,
                callback = callback,
                response = empty,
                area = area,
                source = area.region.name,
                reason = "timeout",
                methodSignature = methodSignature,
                startedAt = startedAt,
            )
        }, SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        executor.execute {
            var attempt: SearchAttempt? = null
            var failure: Throwable? = null
            val response = runCatching {
                attempt = requestSearch(area, query)
                val result = attempt!!.result
                result.body?.let { buildSearchResponse(it, keyword, page, resolvedType) }
                    ?: null
            }.onFailure { failure = it }.getOrNull()
            val deliveredResponse = response ?: buildEmptySearchResponse(resolvedType, keyword)
            val result = attempt?.result
            val source = attempt?.region?.name ?: area.region.name
            val reason = when {
                response != null -> "success"
                failure != null -> "exception_${failure!!.javaClass.simpleName}"
                result?.body == null -> result?.error ?: "empty_body"
                else -> "empty_or_incompatible"
            }
            val accepted = finishSearch(
                delivery = delivery,
                callback = callback,
                response = deliveredResponse,
                area = area,
                source = source,
                reason = reason,
                methodSignature = methodSignature,
                startedAt = startedAt,
            )
            if (accepted) timeout.cancel(false)
            if (accepted && response?.itemMode != "empty" && result?.body != null) {
                recordSearchRegions(result.body, attempt!!.region)
            }
            result?.let {
                val status =
                    "response type=${area.type} source=$source status=${it.httpStatus ?: "transport"} " +
                        "bytes=${it.byteSize ?: 0} json=${it.isJson} error=${it.error ?: "none"}"
                reportStatus(status)
                log("BangumiSearchMoss $status")
            }
        }
        val status =
            "intercepted type=${area.type} region=${area.region.name} response_type=${resolvedType.name} " +
                "type_source=${responseType.source} keyword_length=${keyword.length}"
        reportStatus(status)
        log("BangumiSearchMoss $status method=$methodSignature")
        return true
    }

    private fun finishSearch(
        delivery: SearchDeliveryGate,
        callback: Any,
        response: SearchResponse?,
        area: BangumiSearchMossModel.AreaSearch,
        source: String,
        reason: String,
        methodSignature: String,
        startedAt: Long,
    ): Boolean {
        if (!delivery.tryFinish()) return false
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        mainHandler.post {
            runCatching {
                deliverSearchCallback(
                    response = response?.response,
                    onNext = { invokeCallback(callback, "onNext", it) },
                    onCompleted = { invokeCallback(callback, "onCompleted") },
                )
                val status =
                    "delivered type=${area.type} source=$source reason=$reason elapsed_ms=$elapsed " +
                        "json_items=${response?.jsonItems ?: 0} proto_items=${response?.protoItems ?: 0} " +
                        "item_mode=${response?.itemMode ?: "complete_only"}"
                reportStatus(status)
                log("BangumiSearchMoss $status method=$methodSignature")
            }.onFailure { error ->
                reportStatus("callback failed type=${area.type} reason=$reason ${error.javaClass.simpleName}")
                log("BangumiSearchMoss callback failed type=${area.type} method=$methodSignature", error)
            }
        }
        return true
    }

    private fun requestSearch(area: BangumiSearchMossModel.AreaSearch, query: Map<String, String>): SearchAttempt {
        var lastAttempt: SearchAttempt? = null
        for (region in BangumiSearchMossModel.searchRegions(area)) {
            val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: continue
            val attempt = SearchAttempt(region, BangumiParserClient.requestSearch(
                region = region,
                host = host,
                query = query + ("type" to area.upstreamType),
                credential = parserCredential(region),
                classLoader = classLoader,
                useHttps = ModuleSettings.isBangumiServerHttps(prefs, region),
            ))
            if (attempt.result.isSuccess && hasSearchItems(attempt.result.body)) return attempt
            lastAttempt = attempt
        }
        return lastAttempt ?: SearchAttempt(area.region, BangumiParserClient.Result(null, error = "server not configured"))
    }

    private fun findSearchInvocation(args: Iterable<Any?>): SearchInvocation? {
        val request = args.filterNotNull().firstOrNull { it.isSearchRequest() } ?: return null
        val callback = args.filterNotNull().firstOrNull { it !== request && it.isSearchCallback() } ?: return null
        return SearchInvocation(request, callback)
    }

    private fun Any.isSearchRequest(): Boolean =
        javaClass.allMethods().any { it.name == "getType" && it.parameterCount == 0 } &&
            javaClass.allMethods().any { it.name == "getKeyword" && it.parameterCount == 0 }

    private fun Any.isSearchCallback(): Boolean =
        hasCallbackMethod("onNext", 1) && hasCallbackMethod("onCompleted", 0)

    private fun Any.hasCallbackMethod(name: String, parameterCount: Int): Boolean =
        javaClass.allMethods().any { it.name == name && it.parameterCount == parameterCount } ||
            callbackInterfaces(javaClass).any { type ->
                type.methods.any { it.name == name && it.parameterCount == parameterCount }
            }

    private fun Any.searchType(): String? {
        val value = callMethod("getType") ?: return null
        return when (value) {
            is Number -> value.toString()
            else -> (value.callMethod("getNumber") as? Number)?.toString() ?: value.toString()
        }
    }

    private fun parserCredential(region: BangumiRegion): BangumiServerCredential? =
        ModuleSettings.getBangumiServerCredential(prefs, region)
            ?: AccessKeyRepository.read(prefs)?.let { BangumiServerCredential(it, region.defaultPlatform) }

    private fun addAreaNavigation(response: Any?): Any? {
        response ?: return null
        installResultPageTypes()
        installResultFragmentTypeFix()
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
        val primary = callbackInterfaces(callback.javaClass).firstOrNull { type ->
            type.methods.any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return null
        return Proxy.newProxyInstance(
            callback.javaClass.classLoader ?: classLoader,
            (callback.javaClass.interfaces.toSet() + primary).toTypedArray(),
        ) { _, method, args ->
            if (method.name == "onNext" && args?.isNotEmpty() == true) {
                args[0] = transform(args[0])
            }
            method.invoke(callback, *(args ?: emptyArray()))
        }
    }

    private fun responseTypeFor(callback: Any, method: java.lang.reflect.Method): SearchResponseTypeResolution {
        callback.searchCallbackResponseType()?.let {
            return SearchResponseTypeResolution(it, "callback")
        }
        method.genericParameterTypes.asSequence()
            .flatMap { it.concreteTypes() }
            .firstOrNull { it.isSearchResponseCandidate() }
            ?.let { return SearchResponseTypeResolution(it, "method_generic") }
        val responseClassName = method.declaringClass.name.replace("SearchMoss", "SearchByTypeResponse")
        classLoader.findClassOrNull(responseClassName)?.let {
            return SearchResponseTypeResolution(it, "declaring_package")
        }
        SEARCH_BY_TYPE_RESPONSE_CLASSES.asSequence().mapNotNull(classLoader::findClassOrNull).firstOrNull()?.let {
            return SearchResponseTypeResolution(it, "known_class")
        }
        return SearchResponseTypeResolution(null, "none")
    }

    private fun invokeCallback(callback: Any, name: String, vararg args: Any?) {
        val method = callback.javaClass.allMethods().firstOrNull {
            it.name == name && it.parameterCount == args.size &&
                it.parameterTypes.indices.all { index -> accepts(it.parameterTypes[index], args[index]) }
        } ?: callbackInterfaces(callback.javaClass).flatMap { it.methods.asSequence() }.firstOrNull {
            it.name == name && it.parameterCount == args.size &&
                it.parameterTypes.indices.all { index -> accepts(it.parameterTypes[index], args[index]) }
        } ?: throw IllegalStateException("callback method unavailable: $name/${args.size}")
        method.isAccessible = true
        method.invoke(callback, *args)
    }

    private fun reportStatus(status: String) {
        ModuleDebugLog.recordRegionalSearchStatus(prefs, status)
        log("BangumiSearchMoss status: $status")
    }

    private fun Any.string(name: String): String = callMethod(name) as? String ?: ""
    private fun Any.number(name: String): Number? = callMethod(name) as? Number

    private fun buildSearchResponse(
        raw: String,
        keyword: String,
        page: String,
        responseType: Class<*>?,
    ): SearchResponse? = runCatching {
        responseType ?: return@runCatching null
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) return@runCatching null
        val data = root.optJSONObject("data") ?: return@runCatching null
        val items = data.optJSONArray("items") ?: return@runCatching null
        if (items.length() == 0) return@runCatching null
        val response = responseType.staticCall("newBuilder") ?: return@runCatching null
        response.invokeBuilder("setKeyword", keyword)
        response.invokeBuilder("setPages", data.optInt("pages", 1))
        val currentPage = page.toIntOrNull() ?: 1
        val totalPages = data.optInt("pages", 1)
        if (currentPage < totalPages) {
            response.withNestedBuilder("getPaginationBuilder", "setPagination") {
                it.invokeBuilder("setNext", (currentPage + 1).toString())
            }
        }
        val itemAssembly = response.createSearchItemAssembly()
            ?: throw IllegalStateException(
                "SearchItem builder unavailable response=${responseType.name} " +
                    "methods=${response.javaClass.methods.filter { it.name.contains("Items") }.joinToString { it.signature() }}",
            )
        var cardAccess: CardAccess? = null
        var cardAccessResolved = false
        items.forEachObject { item ->
            val searchItem = itemAssembly.newBuilder()
            searchItem.copyFields(item, SEARCH_ITEM_FIELDS)
            if (!cardAccessResolved) {
                cardAccess = searchItem.resolveBangumiCardAccess()
                cardAccessResolved = true
            }
            val access = cardAccess
                ?: throw IllegalStateException(
                    "Bangumi card builder unavailable: ${searchItem.cardAccessDiagnostics()}"
                )
            val card = if (access.viaGetter) {
                runCatching { access.method.invoke(searchItem) }.getOrNull()
                    ?: throw IllegalStateException("Bangumi card builder invoke failed: ${access.method.name}")
            } else {
                access.method.parameterTypes[0].staticCallNoArgs("newBuilder")
                    ?: throw IllegalStateException("Bangumi card newBuilder failed: ${access.method.name}")
            }
            card.copyFields(item, BANGUMI_FIELDS)
            card.copyEpisodes(item.optJSONArray("episodes"), "addEpisodesBuilder", "addEpisodes", EPISODE_FIELDS)
            card.copyEpisodes(item.optJSONArray("episodes_new"), "addEpisodesNewBuilder", "addEpisodesNew", EPISODE_NEW_FIELDS)
            item.optJSONObject("watch_button")?.let { button ->
                card.withNestedBuilder("getWatchButtonBuilder", "setWatchButton") {
                    it.copyFields(button, WATCH_BUTTON_FIELDS)
                }
            }
            if (!access.viaGetter) searchItem.invokeBuilder(access.method.name, card)
            if (!itemAssembly.commit(searchItem)) {
                throw IllegalStateException("SearchItem append failed mode=${itemAssembly.mode}")
            }
        }
        val protoItems = response.number("getItemsCount")?.toInt()
            ?: throw IllegalStateException("Search response item count unavailable")
        check(protoItems == items.length()) { "Search response lost items: json=${items.length()} proto=$protoItems" }
        val built = response.callMethod("build") ?: throw IllegalStateException("Search response build failed")
        val builtItems = built.number("getItemsCount")?.toInt()
            ?: throw IllegalStateException("Built search response item count unavailable")
        check(builtItems == items.length()) { "Built search response lost items: json=${items.length()} proto=$builtItems" }
        SearchResponse(built, items.length(), builtItems, itemAssembly.mode)
    }.onFailure { log("BangumiSearchMoss response build failed", it) }.getOrNull()

    private fun buildEmptySearchResponse(responseType: Class<*>, keyword: String): SearchResponse? = runCatching {
        val builder = responseType.staticCall("newBuilder") ?: return@runCatching null
        val built = builder.createEmptySearchResponse(keyword) ?: return@runCatching null
        SearchResponse(built, 0, 0, "empty")
    }.onFailure { log("BangumiSearchMoss empty response build failed", it) }.getOrNull()

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

    private fun Any.copyEpisodes(
        items: JSONArray?,
        builderMethod: String,
        addMethod: String,
        fields: List<FieldSpec>,
    ) {
        if (items == null || items.length() == 0) return
        if (javaClass.allMethods().any { it.name == builderMethod && it.parameterCount == 0 }) {
            items.forEachObject { item -> callMethod(builderMethod)?.copyFields(item, fields) }
            return
        }
        // protobuf-javalite generates no get/add*Builder helpers. Repeated
        // messages are attached through addX(E.Builder) overloads instead.
        val adder = javaClass.allMethods().firstOrNull {
            it.name == addMethod && it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive &&
                it.parameterTypes[0].methods.any { m ->
                    Modifier.isStatic(m.modifiers) && m.name == "newBuilder" && m.parameterCount == 0
                }
        } ?: return
        items.forEachObject { item ->
            val builder = adder.parameterTypes[0].staticCallNoArgs("newBuilder") ?: return@forEachObject
            builder.copyFields(item, fields)
            invokeBuilder(addMethod, builder)
        }
    }

    /**
     * Fills a nested message field on a protobuf builder. Full-protobuf
     * runtimes expose getXxxBuilder() which mutates in place; javalite only
     * has setXxx(Message.Builder) overloads, so a fresh builder is attached.
     */
    private fun Any.withNestedBuilder(getterName: String, setterName: String, fill: (Any) -> Unit) {
        callMethod(getterName)?.let { fill(it); return }
        val setter = javaClass.allMethods().firstOrNull {
            it.name == setterName && it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive &&
                it.parameterTypes[0].methods.any { m ->
                    Modifier.isStatic(m.modifiers) && m.name == "newBuilder" && m.parameterCount == 0
                }
        } ?: return
        val builder = setter.parameterTypes[0].staticCallNoArgs("newBuilder") ?: return
        fill(builder)
        invokeBuilder(setterName, builder)
    }

    private class CardAccess(val method: java.lang.reflect.Method, val viaGetter: Boolean)

    /**
     * Locates the bangumi card on a SearchItem builder. Newer host versions
     * renamed the oneof card field, and current builds ship protobuf-javalite
     * which has no getXxxBuilder() helpers at all, so three shapes are tried:
     * the historical getter, any renamed getter with bangumi setters, and any
     * message setter whose type builds into a bangumi card.
     */
    private fun Any.resolveBangumiCardAccess(): CardAccess? {
        javaClass.allMethods()
            .firstOrNull { it.name == "getBangumiBuilder" && it.parameterCount == 0 }
            ?.let { return CardAccess(it, viaGetter = true) }
        javaClass.allMethods()
            .firstOrNull {
                it.parameterCount == 0 && it.name.startsWith("get") && it.name.endsWith("Builder") &&
                    it.returnType.hasBangumiCardSetters()
            }
            ?.let { return CardAccess(it, viaGetter = true) }
        return javaClass.allMethods()
            .firstOrNull {
                it.parameterCount == 1 && it.name.startsWith("set") && !it.parameterTypes[0].isPrimitive &&
                    runCatching { it.parameterTypes[0].getMethod("newBuilder").returnType }
                        .getOrNull()?.hasBangumiCardSetters() == true
            }
            ?.let { CardAccess(it, viaGetter = false) }
    }

    private fun Class<*>.hasBangumiCardSetters(): Boolean {
        val names = allMethods().filter { it.parameterCount == 1 }.map { it.name }.toSet()
        return "setSeasonId" in names && "setSeasonTypeName" in names
    }

    private fun Any.cardAccessDiagnostics(): String {
        val getters = javaClass.allMethods()
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .map { "${it.name}:${it.returnType.simpleName}" }.toList()
        val setters = javaClass.allMethods()
            .filter { it.parameterCount == 1 && it.name.startsWith("set") }
            .map { "${it.name}(${it.parameterTypes[0].simpleName})" }.toList()
        return "getters=$getters setters=$setters"
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

    private fun callbackInterfaces(type: Class<*>): Sequence<Class<*>> = sequence {
        val seen = mutableSetOf<Class<*>>()
        val pending = ArrayDeque<Class<*>>().apply { add(type) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            current.interfaces.forEach { interfaceType ->
                if (seen.add(interfaceType)) {
                    yield(interfaceType)
                    pending.addLast(interfaceType)
                }
            }
            current.superclass?.let(pending::addLast)
        }
    }

    private fun java.lang.reflect.Method.signature(): String =
        "$name(${parameterTypes.joinToString { it.simpleName }})"

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
        private data class SearchInvocation(val request: Any, val callback: Any)
        private data class SearchResponse(
            val response: Any,
            val jsonItems: Int,
            val protoItems: Int,
            val itemMode: String,
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
            string("trackid", "setTrackId"),
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
            string("styles_v2", "setStylesV2"), integer("season_type", "setSeasonType"), string("badge", "setBadge"),
        )
        private val EPISODE_FIELDS = listOf(
            string("uri", "setUri"), string("param", "setParam"), string("index", "setIndex"), integer("position", "setPosition"),
        )
        private val EPISODE_NEW_FIELDS = listOf(
            string("title", "setTitle"), string("uri", "setUri"), string("param", "setParam"), integer("is_new", "setIsNew"),
            integer("type", "setType"), integer("position", "setPosition"), string("cover", "setCover"), string("label", "setLabel"),
        )
        private val WATCH_BUTTON_FIELDS = listOf(string("title", "setTitle"), string("link", "setLink"))

        private const val SEARCH_TIMEOUT_MS = 8_000L

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

internal class SearchItemAssembly(
    private val create: () -> Any,
    val mode: String,
    private val append: (Any) -> Boolean = { true },
) {
    fun newBuilder(): Any = create()
    fun commit(builder: Any): Boolean = append(builder)
}

internal data class SearchResponseTypeResolution(
    val type: Class<*>?,
    val source: String,
)

internal class SearchDeliveryGate {
    private val finished = AtomicBoolean(false)

    fun tryFinish(): Boolean = finished.compareAndSet(false, true)
}

internal fun deliverSearchCallback(
    response: Any?,
    onNext: (Any) -> Unit,
    onCompleted: () -> Unit,
) {
    try {
        response?.let(onNext)
    } finally {
        onCompleted()
    }
}

internal fun Any.searchCallbackResponseType(): Class<*>? {
    val direct = javaClass.allMethods()
        .asSequence()
        .filter { it.name == "onNext" && it.parameterCount == 1 }
        .map { it.parameterTypes[0] }
        .firstOrNull { it.isSearchResponseCandidate() }
    if (direct != null) return direct
    return (sequenceOf(javaClass.genericSuperclass) + javaClass.genericInterfaces.asSequence())
        .flatMap { it.concreteTypes() }
        .firstOrNull { it.isSearchResponseCandidate() }
}

private fun Type.concreteTypes(): Sequence<Class<*>> = sequence {
    when (this@concreteTypes) {
        is Class<*> -> {
            yield(this@concreteTypes)
            this@concreteTypes.genericSuperclass?.let { yieldAll(it.concreteTypes()) }
            this@concreteTypes.genericInterfaces.forEach { yieldAll(it.concreteTypes()) }
        }
        is ParameterizedType -> {
            yieldAll(rawType.concreteTypes())
            actualTypeArguments.forEach { yieldAll(it.concreteTypes()) }
        }
        is WildcardType -> upperBounds.forEach { yieldAll(it.concreteTypes()) }
    }
}

private fun Class<*>.isSearchResponseCandidate(): Boolean {
    if (this == Any::class.java || isPrimitive || isInterface) return false
    return methods.any { Modifier.isStatic(it.modifiers) && it.name == "newBuilder" && it.parameterCount == 0 }
}

internal fun Any.createSearchItemAssembly(): SearchItemAssembly? {
    val responseBuilder = this
    if (javaClass.methods.any { it.name == "addItemsBuilder" && it.parameterCount == 0 }) {
        return SearchItemAssembly(
            create = { responseBuilder.callMethod("addItemsBuilder")
                ?: error("SearchItem builder unavailable") },
            mode = "addItemsBuilder",
        )
    }
    val addItems = javaClass.methods.asSequence()
        .filter { it.name == "addItems" && it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive }
        .firstOrNull { candidate ->
            candidate.parameterTypes[0].methods.any {
                Modifier.isStatic(it.modifiers) && it.name == "newBuilder" && it.parameterCount == 0
            }
        } ?: return null
    val itemType = addItems.parameterTypes[0]
    return SearchItemAssembly(
        create = { itemType.staticCallNoArgs("newBuilder")
            ?: error("SearchItem builder unavailable") },
        mode = "addItems(${itemType.simpleName})",
        append = { itemBuilder ->
            runCatching {
                addItems.invoke(responseBuilder, itemBuilder.callMethod("build"))
                true
            }.getOrDefault(false)
        },
    )
}

internal fun Any.createEmptySearchResponse(keyword: String): Any? {
    callMethod("setKeyword", keyword)
    callMethod("setPages", 1)
    val built = callMethod("build") ?: return null
    val itemCount = (built.callMethod("getItemsCount") as? Number)?.toInt()
    return built.takeIf { itemCount == null || itemCount == 0 }
}

internal fun hasSearchItems(raw: String?): Boolean = runCatching {
    val root = JSONObject(raw ?: return@runCatching false)
    if (root.optInt("code") != 0) return@runCatching false
    val items = root.optJSONObject("data")?.optJSONArray("items") ?: return@runCatching false
    items.length() > 0
}.getOrDefault(false)

private fun Class<*>.staticCallNoArgs(name: String): Any? = methods.asSequence()
    .firstOrNull { Modifier.isStatic(it.modifiers) && it.name == name && it.parameterCount == 0 }
    ?.let { runCatching { it.invoke(null) }.getOrNull() }

private fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
    if (this == null) return
    for (index in 0 until length()) optJSONObject(index)?.let(action)
}
