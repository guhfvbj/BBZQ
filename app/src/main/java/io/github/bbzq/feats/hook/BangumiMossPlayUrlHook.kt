package io.github.bbzq.feats.hook

import com.google.protobuf.ByteString
import com.google.protobuf.Any as ProtoAny
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.bangumi.BangumiParserClient
import io.github.bbzq.feats.bangumi.BangumiRegionContext
import io.github.bbzq.proto.CodeType
import io.github.bbzq.proto.DashItem
import io.github.bbzq.proto.DashVideo
import io.github.bbzq.proto.PlayViewReply
import io.github.bbzq.proto.PlayViewUniteReply
import io.github.bbzq.proto.PlayViewUniteReq
import io.github.bbzq.proto.PlayViewReq
import io.github.bbzq.proto.ResponseUrl
import io.github.bbzq.proto.SegmentVideo
import io.github.bbzq.proto.Stream
import io.github.bbzq.proto.StreamInfo
import io.github.bbzq.proto.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import android.os.Looper
import android.os.Handler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BangumiMossPlayUrlHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName || !ModuleSettings.isAddBangumiEnabled(prefs)) return
        val methods = discoverMossMethods()
        var installed = 0
        methods.forEach { method ->
            runCatching {
                env.hookBefore(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookBefore
                    val callback = param.args.getOrNull(1) ?: return@hookBefore
                    wrapCallback(callback, request, method.name)?.let { param.args[1] = it }
                }
                env.hookAfter(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookAfter
                    val response = param.result
                    replaceIfBlocked(request, response, "direct:${method.name}")?.let { param.result = it }
                }
                installed++
            }.onFailure { log("Bangumi MOSS hook install failed", it) }
        }
        log("startHook: BangumiMossPlayUrl methods=" + installed)
    }

    /**
     * Do not depend on the trial-quality scanner: it is optional and its cached
     * result can be absent after Bilibili updates. These are the same concrete
     * MOSS entry points used by BiliRoaming for PGC playback.
     */
    private fun discoverMossMethods(): List<Method> {
        val resolved = env.symbols?.tryFreeQuality?.restore(classLoader)?.playViewMethods.orEmpty()
        val direct = MOSS_CLASSES.asSequence()
            .mapNotNull(classLoader::findClassOrNull)
            .flatMap { type ->
                type.allMethods().filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        !Modifier.isAbstract(method.modifiers) &&
                        method.name in MOSS_METHOD_NAMES &&
                        method.parameterCount >= 1 &&
                        method.parameterTypes[0].isMossPlayViewRequest()
                }
            }
            .toList()
        return (resolved + direct).distinctBy(Method::toGenericString)
    }

    private fun Class<*>.isMossPlayViewRequest(): Boolean {
        if (name in PGC_PLAY_VIEW_REQUESTS || name.endsWith(".PlayViewUniteReq")) return true
        val getters = allMethods().map(Method::getName).toSet()
        return "getBvid" in getters &&
            getters.any { it in setOf("getVod", "getAid", "getCid", "getEpId", "getIsNeedViewInfo") }
    }

    private fun wrapCallback(callback: Any, request: Any, methodName: String): Any? {
        val primary = callback.javaClass.interfaces.firstOrNull { type ->
            type.methods.any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return null
        val pendingFallback = AtomicBoolean(false)
        val callbackCompleted = AtomicBoolean(false)
        fun deliver(method: Method, args: Array<Any?>?) {
            runCatching {
                if (args == null) method.invoke(callback) else method.invoke(callback, *args)
            }.onFailure { error ->
                if (error is InvocationTargetException) throw error.targetException
                throw error
            }
        }
        return Proxy.newProxyInstance(
            callback.javaClass.classLoader ?: classLoader,
            (callback.javaClass.interfaces.toSet() + primary).toTypedArray(),
        ) { _, method, args ->
            if (method.name == "onNext" && args?.isNotEmpty() == true) {
                val response = args[0]
                if (response != null && isRegionBlocked(response)) {
                    val source = "callback:$methodName"
                    log("Bangumi MOSS [$source] blocked=true; parser fallback scheduled")
                    pendingFallback.set(true)
                    PARSER_EXECUTOR.execute {
                        val replacement = replaceIfBlocked(request, response, source, alreadyBlocked = true)
                        MAIN_HANDLER.post {
                            @Suppress("UNCHECKED_CAST")
                            (args as Array<Any?>)[0] = replacement ?: response
                            deliver(method, args)
                            if (callbackCompleted.get()) deliverCompletion(callback, primary)
                        }
                    }
                    return@newProxyInstance null
                }
            }
            if (method.name in COMPLETION_METHODS && pendingFallback.get()) {
                callbackCompleted.set(true)
                return@newProxyInstance null
            }
            deliver(method, args)
        }
    }

    private fun deliverCompletion(callback: Any, primary: Class<*>) {
        primary.methods.firstOrNull { it.name in COMPLETION_METHODS && it.parameterCount == 0 }
            ?.let { method -> runCatching { method.invoke(callback) } }
    }

    private fun replaceIfBlocked(request: Any, response: Any?, source: String, alreadyBlocked: Boolean = false): Any? {
        val requestKind = request.javaClass.name.substringAfterLast('.')
        if (response == null) {
            log("Bangumi MOSS [$source] no response; request=$requestKind")
            return null
        }
        val blocked = alreadyBlocked || isRegionBlocked(response)
        log("Bangumi MOSS [$source] response=${response.javaClass.name.substringAfterLast('.')} request=$requestKind blocked=$blocked")
        if (!blocked) return null
        val requestBytes = request.callMethod("toByteArray") as? ByteArray
        if (requestBytes == null) {
            log("Bangumi MOSS [$source] fallback skipped: request cannot be serialized")
            return null
        }
        val replyBytes = response.callMethod("toByteArray") as? ByteArray
        if (replyBytes == null) {
            log("Bangumi MOSS [$source] fallback skipped: response cannot be serialized")
            return null
        }
        val fallback = if (request.callMethod("getVod") != null) {
            fallbackUnite(requestBytes, replyBytes)
        } else {
            fallbackPlayView(requestBytes, replyBytes)
        } ?: run {
            log("Bangumi MOSS [$source] fallback failed before host reconstruction")
            return null
        }
        return parseHostReply(response.javaClass, fallback)?.also {
            log("Bangumi MOSS [$source] fallback replacement created")
        }
    }

    private fun isRegionBlocked(response: Any): Boolean = runCatching {
        val hasVideo = response.callMethod("hasVideoInfo") as? Boolean
        val hasVod = response.callMethod("hasVodInfo") as? Boolean
        if (hasVideo == false || hasVod == false) return@runCatching true
        val view = response.callMethod("getViewInfo")
        val dialogType = view?.callMethod("getDialog")?.callMethod("getType") as? String
        val endDialogType = view?.callMethod("getEndPage")?.callMethod("getDialog")?.callMethod("getType") as? String
        if (dialogType == "area_limit" || endDialogType == "area_limit") return@runCatching true

        // PlayerUnite wraps the PGC PlayView response in Any.supplement.  On
        // current clients the area-limit dialog is frequently present there,
        // while the outer reply has neither a ViewInfo object nor a usable vod.
        val bytes = response.callMethod("toByteArray") as? ByteArray ?: return@runCatching false
        val unite = runCatching { PlayViewUniteReply.parseFrom(bytes) }.getOrNull()
            ?: return@runCatching false
        if (unite.hasSupplement()) {
            val supplement = runCatching { PlayViewReply.parseFrom(unite.supplement.value) }.getOrNull()
            if (supplement != null) {
                if (!supplement.hasVideoInfo()) return@runCatching true
                if (supplement.viewInfo.toByteArray().containsAscii("area_limit")) return@runCatching true
            }
        }
        unite.viewInfo.toByteArray().containsAscii("area_limit")
    }.getOrDefault(false)

    private fun fallbackPlayView(requestBytes: ByteArray, replyBytes: ByteArray): ByteArray? = runCatching {
        val request = PlayViewReq.parseFrom(requestBytes)
        val payload = requestParserPayload(
            request.epId, request.seasonId, request.cid, request.qn, request.fnver,
            request.fnval, request.forceHost, request.fourk,
        ) ?: return null
        PlayViewReply.parseFrom(replyBytes).toBuilder()
            .setVideoInfo(payload.toVideoInfo(request.preferCodecType))
            .clearViewInfo()
            .build()
            .toByteArray()
    }.onFailure { log("Bangumi MOSS PlayView fallback failed", it) }.getOrNull()

    private fun fallbackUnite(requestBytes: ByteArray, replyBytes: ByteArray): ByteArray? = runCatching {
        val request = PlayViewUniteReq.parseFrom(requestBytes)
        if (!request.hasVod()) return null
        val vod = request.vod
        val original = PlayViewUniteReply.parseFrom(replyBytes)
        val supplementBytes = original.supplement.takeIf { original.hasSupplement() }?.value?.toByteArray()
        val hostEpisode = supplementBytes?.let(::extractHostEpisode)
        val requestedEpId = request.extraContentMap["ep_id"]?.toLongOrNull() ?: 0L
        val epId = requestedEpId.takeIf { it != 0L } ?: (hostEpisode?.id ?: 0L)
        val seasonId = request.extraContentMap["season_id"]?.toLongOrNull() ?: 0L
        val cid = vod.cid.takeIf { it != 0L } ?: (hostEpisode?.cid ?: 0L)
        if (hostEpisode != null) {
            log("Bangumi MOSS supplement episode found: ep=${hostEpisode.id}, cid=${hostEpisode.cid}")
        }
        val payload = requestParserPayload(
            epId, seasonId, cid, vod.qn, vod.fnver, vod.fnval, vod.forceHost, vod.fourk,
        ) ?: return null
        val supplement = original.supplement.takeIf { original.hasSupplement() }?.let {
            runCatching { PlayViewReply.parseFrom(it.value) }.getOrNull()
        } ?: PlayViewReply.getDefaultInstance()
        val newSupplement = supplement.toBuilder()
            .setVideoInfo(payload.toVideoInfo(vod.preferCodeType))
            .clearViewInfo()
            .build()
        original.toBuilder()
            .setVodInfo(payload.toVideoInfo(vod.preferCodeType))
            .setSupplement(
                ProtoAny.newBuilder()
                    .setTypeUrl(PGC_ANY_MODEL_TYPE_URL)
                    .setValue(ByteString.copyFrom(newSupplement.toByteArray()))
                    .build(),
            )
            .clearViewInfo()
            .build()
            .toByteArray()
    }.onFailure { log("Bangumi MOSS PlayerUnite fallback failed", it) }.getOrNull()

    /**
     * BBZQ deliberately keeps the PGC business payload opaque for cross-version
     * compatibility. The installed Bilibili protobuf is authoritative here and
     * exposes the episode data needed to call the legacy parser endpoint.
     */
    private fun extractHostEpisode(bytes: ByteArray): Episode? {
        HOST_PLAY_VIEW_REPLIES.forEach { className ->
            val episode = runCatching {
                val replyClass = classLoader.loadClass(className)
                val parseFrom = replyClass.methods.firstOrNull {
                    it.name == "parseFrom" && it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
                } ?: return@runCatching null
                val reply = parseFrom.invoke(null, bytes) ?: return@runCatching null
                val info = reply.callMethod("getBusiness")?.callMethod("getEpisodeInfo") ?: return@runCatching null
                val id = (info.callMethod("getEpId") as? Number)?.toLong() ?: 0L
                val cid = (info.callMethod("getCid") as? Number)?.toLong() ?: 0L
                Episode(id, cid).takeIf { it.id != 0L && it.cid != 0L }
            }.getOrNull()
            if (episode != null) return episode
        }
        return null
    }

    private fun requestParserPayload(
        epId: Long, seasonId: Long, cid: Long, qn: Long, fnver: Int, fnval: Int,
        forceHost: Int, fourk: Boolean,
    ): JSONObject? = requestParserPayloadInternal(epId, seasonId, cid, qn, fnver, fnval, forceHost, fourk)

    private fun requestParserPayloadInternal(
        epId: Long, seasonId: Long, cid: Long, qn: Long, fnver: Int, fnval: Int,
        forceHost: Int, fourk: Boolean,
    ): JSONObject? {
        val reference = BangumiRegionContext.referenceFor(epId, cid, seasonId)
        val requestedEpisode = reference?.let { Episode(it.episodeId, it.cid) } ?: Episode(epId, cid)
        val requestedSeasonId = seasonId.takeIf { it != 0L } ?: reference?.seasonId ?: 0L
        val completion = ExecutorCompletionService<ParserAttempt>(REGION_EXECUTOR)
        val submitted = BangumiRegionContext.candidates(requestedEpisode.id, requestedSeasonId).mapNotNull { region ->
            val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return@mapNotNull null
            completion.submit {
                val lookupMovie = region == io.github.bbzq.BangumiRegion.INTL && reference?.isMovie == true
                val episode = resolveEpisode(
                    region,
                    host,
                    requestedSeasonId,
                    requestedEpisode,
                    forceLookup = lookupMovie,
                    fallback = requestedEpisode.takeIf { reference != null },
                ) ?: requestedEpisode
                if (episode.id == 0L || episode.cid == 0L) {
                    log("Bangumi MOSS fallback skipped: region=${region.name}, missing ep/cid after season lookup")
                    return@submit ParserAttempt(region, episode, null, "missing episode metadata")
                }
                val query = linkedMapOf(
                    "ep_id" to episode.id.toString(), "cid" to episode.cid.toString(), "qn" to qn.toString(),
                    "fnver" to fnver.toString(), "fnval" to fnval.toString(),
                    "force_host" to forceHost.toString(), "fourk" to if (fourk) "1" else "0",
                )
                log("Bangumi MOSS parser request: region=${region.name}, ep=${episode.id}, season=$requestedSeasonId, cid=${episode.cid}, mapped=${reference != null}")
                val credential = parserCredential(region)
                val result = BangumiParserClient.requestPlayUrl(
                    region, host, query, credential,
                    classLoader, ModuleSettings.isBangumiServerHttps(prefs, region),
                )
                log(
                    "Bangumi MOSS parser result: region=${region.name}, status=${result.httpStatus ?: "transport"}, " +
                        "contentType=${result.contentType ?: "unknown"}, bytes=${result.byteSize ?: 0}, " +
                        "json=${result.isJson}, html=${result.isHtml}, error=${result.error ?: "none"}",
                )
                ParserAttempt(region, episode, result.body?.let(::normalizePayload), result.error)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARSER_RACE_TIMEOUT_MS)
        repeat(submitted.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@repeat
            val attempt = runCatching { completion.poll(remaining, TimeUnit.NANOSECONDS)?.get() }.getOrNull() ?: return@repeat
            if (attempt.payload?.hasPlayableStream() == true) {
                submitted.forEach { it.cancel(true) }
                BangumiRegionContext.recordEpisode(attempt.episode.id.toString(), attempt.region)
                BangumiRegionContext.recordEpisodeReference(
                    attempt.episode.id.toString(),
                    attempt.episode.cid.toString(),
                    requestedSeasonId.toString(),
                    attempt.region,
                    reference?.isMovie == true,
                )
                requestedSeasonId.takeIf { it != 0L }?.toString()?.let {
                    BangumiRegionContext.recordSeason(it, attempt.region)
                }
                BangumiRegionContext.activate(attempt.region)
                log("Bangumi MOSS fallback succeeded: region=${attempt.region.name}, ep=${attempt.episode.id}")
                return attempt.payload
            }
            log("Bangumi MOSS fallback rejected: region=${attempt.region.name}, ep=${attempt.episode.id}, transport=${attempt.error ?: "ok"}")
        }
        submitted.forEach { it.cancel(true) }
        return null
    }

    private fun resolveEpisode(
        region: io.github.bbzq.BangumiRegion,
        host: String,
        seasonId: Long,
        requested: Episode,
        forceLookup: Boolean = false,
        fallback: Episode? = null,
    ): Episode? {
        if (!forceLookup && requested.id != 0L && requested.cid != 0L) return requested
        if (seasonId == 0L) return fallback ?: requested.takeIf { it.id != 0L && it.cid != 0L }
        log("Bangumi MOSS season lookup: region=${region.name}, season=$seasonId, requestedEp=${requested.id}")
        val result = BangumiParserClient.requestSeason(
            region, host,
            mapOf("season_id" to seasonId.toString(), "ep_id" to requested.id.toString()),
            parserCredential(region), classLoader,
            ModuleSettings.isBangumiServerHttps(prefs, region),
        )
        val root = result.body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: run {
                log(
                    "Bangumi MOSS season result: region=${region.name}, status=${result.httpStatus ?: "transport"}, " +
                        "contentType=${result.contentType ?: "unknown"}, bytes=${result.byteSize ?: 0}, " +
                        "json=${result.isJson}, html=${result.isHtml}, error=${result.error ?: "none"}",
                )
                log("Bangumi MOSS season lookup rejected: region=${region.name}, transport=${result.error ?: "ok"}")
            return fallback
            }
        val season = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
        val episodes = ArrayList<JSONObject>()
        collectEpisodes(season, episodes)
        val episode = episodes.firstOrNull { requested.id != 0L && it.optLong("id") == requested.id }
            ?: season.optJSONObject("new_ep")
            ?: episodes.firstOrNull()
            ?: return fallback
        return Episode(episode.optLong("id"), episode.optLong("cid")).takeIf { it.id != 0L && it.cid != 0L }
            ?: fallback
    }

    /**
     * BiliRoaming forwards the signed-in account's access key for parser
     * requests. Without it the upstream returns the anonymous 480P ladder.
     * A user-supplied server credential remains an explicit override.
     */
    private fun parserCredential(region: io.github.bbzq.BangumiRegion): BangumiServerCredential? =
        ModuleSettings.getBangumiServerCredential(prefs, region)
            ?: AccessKeyRepository.read(prefs)?.let { accessKey ->
                BangumiServerCredential(accessKey, region.defaultPlatform)
            }

    private fun collectEpisodes(node: Any?, result: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("id") && node.has("cid")) result += node
                node.optJSONArray("episodes")?.let { array ->
                    for (index in 0 until array.length()) collectEpisodes(array.opt(index), result)
                }
                node.optJSONArray("modules")?.let { array ->
                    for (index in 0 until array.length()) collectEpisodes(array.opt(index), result)
                }
                node.optJSONObject("data")?.let { collectEpisodes(it, result) }
            }
            is JSONArray -> for (index in 0 until node.length()) collectEpisodes(node.opt(index), result)
        }
    }

    private fun normalizePayload(raw: String): JSONObject? = runCatching {
        val converted = if (raw.contains("\"video_info\"")) BangumiParserClient.convertThailandPlayUrl(raw) else raw
        val root = JSONObject(converted)
        root.optJSONObject("data") ?: root.optJSONObject("result") ?: root
    }.getOrNull()

    private fun JSONObject.hasPlayableStream(): Boolean =
        optJSONObject("dash")?.optJSONArray("video")?.length()?.let { it > 0 } == true ||
            optJSONArray("durl")?.length()?.let { it > 0 } == true

    private fun JSONObject.toVideoInfo(preferredCodec: CodeType): VideoInfo {
        val builder = VideoInfo.newBuilder()
            .setQuality(optInt("quality"))
            .setFormat(optString("format"))
            .setTimelength(optLong("timelength"))
            .setVideoCodecid(optInt("video_codecid"))
        val formats = optJSONArray("support_formats").toFormats()
        val dash = optJSONObject("dash")
        val audioIds = ArrayList<Int>()
        dash?.optJSONArray("audio").forEachObject { audio ->
            val id = audio.optInt("id")
            audioIds += id
            builder.addDashAudio(audio.toDashItem(id))
        }
        val videos = dash?.optJSONArray("video").objects()
        val preferredId = when (preferredCodec) {
            CodeType.CODE264 -> 7
            CodeType.CODE265 -> 12
            CodeType.CODEAV1 -> 13
            else -> optInt("video_codecid")
        }
        val selected = videos.filter { it.optInt("codecid") == preferredId }
            .takeIf { it.isNotEmpty() } ?: videos
        selected.forEach { video ->
            val quality = video.optInt("id")
            builder.addStreamList(
                Stream.newBuilder()
                    .setStreamInfo(video.toStreamInfo(quality, formats[quality]))
                    .setDashVideo(video.toDashVideo(audioIds.maxOrNull() ?: 0))
                    .build(),
            )
        }
        if (selected.isEmpty()) {
            optJSONArray("durl").forEachObject { segment ->
                val quality = optInt("quality")
                builder.addStreamList(
                    Stream.newBuilder()
                        .setStreamInfo(JSONObject().toStreamInfo(quality, formats[quality]))
                        .setSegmentVideo(SegmentVideo.newBuilder().addSegment(segment.toResponseUrl()).build())
                        .build(),
                )
            }
        }
        return builder.build()
    }

    private fun JSONObject.toStreamInfo(quality: Int, format: JSONObject?): StreamInfo =
        StreamInfo.newBuilder()
            .setQuality(quality).setIntact(true).setAttribute(0)
            .setFormat(format?.optString("format").orEmpty())
            .setDescription(format?.optString("description").orEmpty())
            .setNewDescription(format?.optString("new_description").orEmpty())
            .setDisplayDesc(format?.optString("display_desc").orEmpty())
            .setSuperscript(format?.optString("superscript").orEmpty())
            .setNeedVip(format?.optBoolean("need_vip", false) ?: false)
            .setNeedLogin(format?.optBoolean("need_login", false) ?: false)
            .build()

    private fun JSONObject.toDashVideo(audioId: Int): DashVideo = DashVideo.newBuilder()
        .setBaseUrl(optString("base_url")).addAllBackupUrl(optJSONArray("backup_url").strings())
        .setBandwidth(optInt("bandwidth")).setCodecid(optInt("codecid")).setMd5(optString("md5"))
        .setSize(optLong("size")).setAudioId(audioId).setNoRexcode(optInt("no_rexcode") != 0).build()

    private fun JSONObject.toDashItem(id: Int): DashItem = DashItem.newBuilder()
        .setId(id).setBaseUrl(optString("base_url")).addAllBackupUrl(optJSONArray("backup_url").strings())
        .setBandwidth(optInt("bandwidth")).setCodecid(optInt("codecid")).setMd5(optString("md5"))
        .setSize(optLong("size")).build()

    private fun JSONObject.toResponseUrl(): ResponseUrl = ResponseUrl.newBuilder()
        .setOrder(optInt("order")).setLength(optLong("length")).setSize(optLong("size"))
        .setUrl(optString("url")).addAllBackupUrl(optJSONArray("backup_url").strings())
        .setMd5(optString("md5")).build()

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        this@objects?.forEachObject { add(it) }
    }

    private fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
        if (this == null) return
        for (index in 0 until length()) optJSONObject(index)?.let(action)
    }

    private fun JSONArray?.strings(): List<String> = buildList {
        if (this@strings != null) for (index in 0 until this@strings.length()) {
            this@strings.optString(index).takeIf(String::isNotBlank)?.let { add(it) }
        }
    }

    private fun JSONArray?.toFormats(): Map<Int, JSONObject> = buildMap {
        this@toFormats?.forEachObject { put(it.optInt("quality"), it) }
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || size < needle.size) return false
        for (start in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return true
        }
        return false
    }

    private fun parseHostReply(type: Class<*>, bytes: ByteArray): Any? = runCatching {
        type.methods.firstOrNull {
            it.name == "parseFrom" && it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }?.invoke(null, bytes)
    }.onFailure { log("Bangumi MOSS host reply reconstruction failed", it) }.getOrNull()

    private data class Episode(val id: Long, val cid: Long)

    private data class ParserAttempt(
        val region: io.github.bbzq.BangumiRegion,
        val episode: Episode,
        val payload: JSONObject?,
        val error: String?,
    )

    private companion object {
        val PARSER_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "BBZQ-BangumiParser").apply { isDaemon = true }
        }
        val REGION_EXECUTOR = Executors.newFixedThreadPool(3) { task ->
            Thread(task, "BBZQ-BangumiRegionParser").apply { isDaemon = true }
        }
        const val PARSER_RACE_TIMEOUT_MS = 12_000L
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
        val COMPLETION_METHODS = setOf("onCompleted", "onComplete")
        val MOSS_CLASSES = arrayOf(
            "com.bapis.bilibili.pgc.gateway.player.v1.PlayURLMoss",
            "com.bapis.bilibili.pgc.gateway.player.v2.PlayURLMoss",
            "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
            "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss",
            "com.bapis.bilibili.p4218app.playerunite.p4240v1.PlayerMoss",
            "com.bapis.bilibili.p4218app.playerunite.p4240v1.KPlayerMoss",
        )
        val PGC_PLAY_VIEW_REQUESTS = setOf(
            "com.bapis.bilibili.pgc.gateway.player.v1.PlayViewReq",
            "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReq",
        )
        val HOST_PLAY_VIEW_REPLIES = arrayOf(
            "com.bapis.bilibili.pgc.gateway.player.v1.PlayViewReply",
            "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReply",
        )
        val MOSS_METHOD_NAMES = setOf(
            "playView",
            "executePlayView",
            "playViewUnite",
            "executePlayViewUnite",
        )
        const val PGC_ANY_MODEL_TYPE_URL =
            "type.googleapis.com/bilibili.app.playerunite.pgcanymodel.PGCAnyModel"
    }
}
