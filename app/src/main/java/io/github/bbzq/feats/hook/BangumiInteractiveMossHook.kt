package io.github.bbzq.feats.hook

import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.bangumi.BangumiParserClient
import io.github.bbzq.feats.bangumi.BangumiRegionContext
import io.github.bbzq.feats.bangumi.BangumiSubtitleModel
import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort regional proxy for comments and ordinary danmaku. */
class BangumiInteractiveMossHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName || !ModuleSettings.isAddBangumiEnabled(prefs)) return
        val entries = discoverMethods()
        entries.forEach { entry ->
            runCatching {
                if (entry.method.parameterTypes.drop(1).any(::isCallbackType)) {
                    env.hookBefore(entry.method) { param ->
                        val request = param.args.firstOrNull() ?: return@hookBefore
                        prepareDmViewContext(request, entry)
                        val callbackIndex = param.args.indexOfFirst(::isCallback)
                        if (callbackIndex < 0) return@hookBefore
                        val callback = param.args[callbackIndex] ?: return@hookBefore
                        wrapCallback(callback, request, entry)?.let { param.args[callbackIndex] = it }
                    }
                } else {
                    env.hookAfter(entry.method) { param ->
                        val request = param.args.firstOrNull() ?: return@hookAfter
                        val response = param.result ?: return@hookAfter
                        prepareDmViewContext(request, entry)
                        val routed = if (isBlockedInteractiveResponse(response, entry.methodName)) {
                            requestFallback(request, response, entry) ?: response
                        } else response
                        param.result = addSimplifiedTrack(routed, entry)
                    }
                }
            }.onFailure { log("Interactive MOSS hook install failed: ${entry.method.name}", it) }
        }
        log("startHook: interactive MOSS methods=${entries.size}")
    }

    private fun discoverMethods(): List<Entry> = MOSS_CLASSES.asSequence()
        .mapNotNull(classLoader::findClassOrNull)
        .flatMap { type -> type.allMethods() }
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount >= 1 &&
                method.name.canonicalInteractiveMethod() != null &&
                method.parameterTypes.firstOrNull()?.allMethods()?.any { it.name == "toByteArray" && it.parameterCount == 0 } == true &&
                (method.parameterCount == 1 || method.parameterTypes.drop(1).any(::isCallbackType))
        }
        .mapNotNull { method ->
            val methodName = method.name.canonicalInteractiveMethod() ?: return@mapNotNull null
            val service = when {
                methodName == "subjectDescription" -> REPLY_V2_SERVICE
                methodName in REPLY_METHODS -> REPLY_SERVICE
                else -> DM_SERVICE
            }
            Entry(method, service, methodName)
        }
        .distinctBy { it.method.toGenericString() }
        .toList()

    private fun wrapCallback(callback: Any, request: Any, entry: Entry): Any? {
        val primary = callback.javaClass.interfaces.firstOrNull(::isCallbackType) ?: return null
        val pending = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

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
            if ((method.name == "onNext" || method.name == "resumeWith") && args?.isNotEmpty() == true) {
                val response = args[0]
                if (response != null && response.javaClass.allMethods().any { it.name == "toByteArray" && it.parameterCount == 0 } &&
                    isBlockedInteractiveResponse(response, entry.methodName)
                ) {
                    pending.set(true)
                    EXECUTOR.execute {
                        val replacement = requestFallback(request, response, entry)
                        MAIN_HANDLER.post {
                            @Suppress("UNCHECKED_CAST")
                            (args as Array<Any?>)[0] = addSimplifiedTrack(replacement ?: response, entry)
                            deliver(method, args)
                            if (completed.get()) deliverCompletion(callback, primary)
                        }
                    }
                    return@newProxyInstance null
                }
                if (response != null) {
                    @Suppress("UNCHECKED_CAST")
                    (args as Array<Any?>)[0] = addSimplifiedTrack(response, entry)
                }
            }
            if (method.name in COMPLETION_METHODS && pending.get()) {
                completed.set(true)
                return@newProxyInstance null
            }
            deliver(method, args)
        }
    }

    private fun requestFallback(request: Any, original: Any, entry: Entry): Any? {
        val bytes = request.callMethod("toByteArray") as? ByteArray ?: return null
        val epId = request.number("getEpId", "getEpisodeId", "getAid", "getOid", "getObjectId")
        val cid = request.number("getCid", "getContentId")
        val seasonId = request.number("getSeasonId", "getSeason")
        val regions = BangumiRegionContext.candidates(epId, seasonId)
        val attempts = ExecutorCompletionService<ByteArray?>(EXECUTOR)
        val submitted = regions.mapNotNull { region ->
            val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return@mapNotNull null
            attempts.submit {
                val credential = parserCredential(region)
                val result = BangumiParserClient.requestGrpc(
                    host = host,
                    service = entry.service,
                    method = entry.methodName.grpcMethodName(),
                    body = bytes,
                    credential = credential,
                    useHttps = ModuleSettings.isBangumiServerHttps(prefs, region),
                )
                if (result != null) {
                    log("Interactive MOSS fallback succeeded: kind=${entry.methodName}, region=${region.name}, ep=$epId, cid=$cid")
                }
                result
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FALLBACK_TIMEOUT_MS)
        repeat(submitted.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@repeat
            val payload = runCatching { attempts.poll(remaining, TimeUnit.NANOSECONDS)?.get() }.getOrNull()
            if (payload != null && payload.isNotEmpty()) {
                submitted.forEach { it.cancel(true) }
                return parseHostResponse(original.javaClass, payload)
            }
        }
        submitted.forEach { it.cancel(true) }
        return null
    }

    private fun prepareDmViewContext(request: Any, entry: Entry) {
        if (entry.methodName != "dmView") return
        val contentId = request.number("getOid", "getCid", "getContentId")
        BangumiRegionContext.prepareDmView(contentId)
    }

    private fun addSimplifiedTrack(response: Any, entry: Entry): Any {
        if (entry.methodName != "dmView" || !ModuleSettings.isBangumiSubtitleHantToHansEnabled(prefs)) return response
        val raw = response.callMethod("toByteArray") as? ByteArray ?: return response
        val converted = BangumiSubtitleModel.addSimplifiedTrack(raw) ?: return response
        return parseHostResponse(response.javaClass, converted)?.also {
            log("Bangumi subtitle: generated simplified Chinese track")
        } ?: response
    }

    private fun isBlockedInteractiveResponse(response: Any, methodName: String): Boolean = runCatching {
        if (containsRestrictionMarker(response.callMethod("toByteArray") as? ByteArray)) return@runCatching true
        if (response.number("getCode", "getErrorCode", "getStatusCode") != 0L) return@runCatching true
        if (response.string("getMessage", "getErrorMessage", "getMsg", "getType").containsRestrictionMarker()) {
            return@runCatching true
        }
        val countNames = if (methodName in REPLY_METHODS) {
            arrayOf("getRepliesCount", "getReplyCount", "getListCount", "getItemsCount")
        } else {
            arrayOf("getElemsCount", "getDmSgeCount", "getDmListCount", "getCommandsCount", "getSegmentIndexCount")
        }
        val count = countNames.firstNotNullOfOrNull { name ->
            response.javaClass.allMethods().firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.let { it.invoke(response) as? Number }
                ?.toInt()
        }
        count != null && count == 0
    }.getOrDefault(false)

    private fun Any.string(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        callMethod(name) as? String
    }.orEmpty()

    private fun String.containsRestrictionMarker(): Boolean {
        val value = lowercase()
        return listOf(
            "area_limit", "area limit", "region_limit", "region limit",
            "地区限制", "区域限制", "地区不可用", "not available in your region",
            "not available in this region", "unavailable in your region",
        ).any(value::contains)
    }

    private fun containsRestrictionMarker(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        return bytes.toString(Charsets.ISO_8859_1).containsRestrictionMarker()
    }

    private fun parseHostResponse(type: Class<*>, bytes: ByteArray): Any? = runCatching {
        type.allMethods().firstOrNull {
            it.name == "parseFrom" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }?.invoke(null, bytes)
    }.getOrNull()

    private fun deliverCompletion(callback: Any, primary: Class<*>) {
        primary.methods.firstOrNull { it.name in COMPLETION_METHODS && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(callback) } }
    }

    private fun parserCredential(region: BangumiRegion): BangumiServerCredential? =
        ModuleSettings.getBangumiServerCredential(prefs, region)
            ?: AccessKeyRepository.read(prefs)?.let { BangumiServerCredential(it, region.defaultPlatform) }

    private fun Any.number(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
        callMethod(name)?.let { it as? Number }?.toLong()?.takeIf { it != 0L }
    } ?: 0L

    private fun isCallback(value: Any?): Boolean = value?.javaClass?.interfaces?.any(::isCallbackType) == true

    private fun isCallbackType(type: Class<*>): Boolean =
        type.methods.any {
            (it.name == "onNext" || it.name == "resumeWith") && it.parameterCount == 1
        }

    private data class Entry(val method: Method, val service: String, val methodName: String)

    private companion object {
        val EXECUTOR = Executors.newFixedThreadPool(3) { task ->
            Thread(task, "BBZQ-InteractiveMoss").apply { isDaemon = true }
        }
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
        val COMPLETION_METHODS = setOf("onCompleted", "onComplete")
        val REPLY_METHODS = setOf("mainList", "subjectDescription")
        val METHOD_NAMES = REPLY_METHODS + setOf("dmView", "dmSegMobile")
        val MOSS_CLASSES = arrayOf(
            "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            "com.bapis.bilibili.main.community.reply.v1.KReplyMoss",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.ReplyMoss",
            "com.bapis.bilibili.p4311main.community.reply.p4312v1.KReplyMoss",
            "com.bapis.bilibili.community.service.dm.v1.DMMoss",
            "com.bapis.bilibili.community.service.dm.v1.KDMMoss",
        )
        const val REPLY_SERVICE = "bilibili.main.community.reply.v1.Reply"
        const val REPLY_V2_SERVICE = "bilibili.main.community.reply.v2.Reply"
        const val DM_SERVICE = "bilibili.community.service.dm.v1.DM"
        const val FALLBACK_TIMEOUT_MS = 8_000L

        fun String.grpcMethodName(): String = replaceFirstChar { it.uppercase() }

        fun String.canonicalInteractiveMethod(): String? = when (this) {
            "mainList", "executeMainList" -> "mainList"
            "subjectDescription", "executeSubjectDescription" -> "subjectDescription"
            "dmView", "executeDmView" -> "dmView"
            "dmSegMobile", "executeDmSegMobile" -> "dmSegMobile"
            else -> null
        }
    }
}
