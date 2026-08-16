package io.github.bbzq.feats.hook

import com.google.protobuf.ByteString
import com.google.protobuf.Any as ProtoAny
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

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
                    wrapCallback(callback, request)?.let { param.args[1] = it }
                }
                env.hookAfter(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookAfter
                    val response = param.result ?: return@hookAfter
                    replaceIfBlocked(request, response)?.let { param.result = it }
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

    private fun wrapCallback(callback: Any, request: Any): Any? {
        val primary = callback.javaClass.interfaces.firstOrNull { type ->
            type.methods.any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return null
        return Proxy.newProxyInstance(
            callback.javaClass.classLoader ?: classLoader,
            (callback.javaClass.interfaces.toSet() + primary).toTypedArray(),
        ) { _, method, args ->
            if (method.name == "onNext" && args?.isNotEmpty() == true) {
                replaceIfBlocked(request, args[0])?.let { replacement ->
                    @Suppress("UNCHECKED_CAST")
                    (args as Array<Any?>)[0] = replacement
                }
            }
            try {
                if (args == null) method.invoke(callback) else method.invoke(callback, *args)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun replaceIfBlocked(request: Any, response: Any?): Any? {
        if (response == null || !isRegionBlocked(response)) return null
        val requestBytes = request.callMethod("toByteArray") as? ByteArray ?: return null
        val replyBytes = response.callMethod("toByteArray") as? ByteArray ?: return null
        val fallback = if (request.callMethod("getVod") != null) {
            fallbackUnite(requestBytes, replyBytes)
        } else {
            fallbackPlayView(requestBytes, replyBytes)
        } ?: return null
        return parseHostReply(response.javaClass, fallback)
    }

    private fun isRegionBlocked(response: Any): Boolean = runCatching {
        val hasVideo = response.callMethod("hasVideoInfo") as? Boolean
        val hasVod = response.callMethod("hasVodInfo") as? Boolean
        if (hasVideo == false || hasVod == false) return@runCatching true
        val view = response.callMethod("getViewInfo")
        val dialogType = view?.callMethod("getDialog")?.callMethod("getType") as? String
        val endDialogType = view?.callMethod("getEndPage")?.callMethod("getDialog")?.callMethod("getType") as? String
        dialogType == "area_limit" || endDialogType == "area_limit"
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
        val epId = request.extraContentMap["ep_id"]?.toLongOrNull() ?: 0L
        val seasonId = request.extraContentMap["season_id"]?.toLongOrNull() ?: 0L
        val payload = requestParserPayload(
            epId, seasonId, vod.cid, vod.qn, vod.fnver, vod.fnval, vod.forceHost, vod.fourk,
        ) ?: return null
        val original = PlayViewUniteReply.parseFrom(replyBytes)
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

    private fun requestParserPayload(
        epId: Long, seasonId: Long, cid: Long, qn: Long, fnver: Int, fnval: Int,
        forceHost: Int, fourk: Boolean,
    ): JSONObject? {
        val query = linkedMapOf(
            "ep_id" to epId.toString(), "cid" to cid.toString(), "qn" to qn.toString(),
            "fnver" to fnver.toString(), "fnval" to fnval.toString(),
            "force_host" to forceHost.toString(), "fourk" to if (fourk) "1" else "0",
        )
        BangumiRegionContext.candidates(epId, seasonId).forEach { region ->
            val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return@forEach
            val result = BangumiParserClient.requestPlayUrl(
                region, host, query, ModuleSettings.getBangumiServerCredential(prefs, region),
                classLoader, ModuleSettings.isBangumiServerHttps(prefs, region),
            )
            val payload = result.body?.let(::normalizePayload)
            if (payload != null && payload.hasPlayableStream()) {
                BangumiRegionContext.recordEpisode(epId.toString(), region)
                BangumiRegionContext.recordSeason(seasonId.toString(), region)
                BangumiRegionContext.activate(region)
                log("Bangumi MOSS fallback succeeded: region=" + region.name + ", ep=" + epId)
                return payload
            }
            log("Bangumi MOSS fallback rejected: region=" + region.name + ", ep=" + epId)
        }
        return null
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

    private fun parseHostReply(type: Class<*>, bytes: ByteArray): Any? = runCatching {
        type.methods.firstOrNull {
            it.name == "parseFrom" && it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }?.invoke(null, bytes)
    }.onFailure { log("Bangumi MOSS host reply reconstruction failed", it) }.getOrNull()

    private companion object {
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
