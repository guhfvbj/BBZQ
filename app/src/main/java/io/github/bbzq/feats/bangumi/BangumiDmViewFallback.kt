package io.github.bbzq.feats.bangumi

import android.content.SharedPreferences
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.BangumiRegion
import io.github.bbzq.BangumiServerCredential
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.callMethod
import io.github.bbzq.proto.DmViewRequest
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared regional fallback for MOSS and Chronos DmView responses. */
internal object BangumiDmViewFallback {
    private const val TIMEOUT_MS = 8_000L
    private const val DM_SERVICE = "bilibili.community.service.dm.v1.DM"
    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "BBZQ-DmViewFallback").apply { isDaemon = true }
    }

    data class Response(
        val region: BangumiRegion,
        val payload: ByteArray,
        val hasSubtitle: Boolean,
    )

    fun request(
        request: Any,
        service: String = DM_SERVICE,
        methodName: String = "dmView",
        prefs: SharedPreferences,
        source: String,
        log: (String) -> Unit,
    ): Response? {
        val originalBody = request.callMethod("toByteArray") as? ByteArray ?: return null
        val parsedDmView = if (methodName == "dmView") {
            runCatching { DmViewRequest.parseFrom(originalBody) }.getOrNull()
        } else {
            null
        }
        val reflectedEpisodeId = request.number("getEpId", "getEpisodeId", "getAid", "getObjectId")
        val reflectedContentId = if (methodName == "dmView") {
            request.number("getOid", "getCid", "getContentId")
        } else {
            request.number("getCid", "getContentId")
        }
        val epId = if (parsedDmView != null) {
            parsedDmView.pid.takeIf { it > 0L } ?: 0L
        } else {
            reflectedEpisodeId
        }
        val contentId = parsedDmView?.oid?.takeIf { it > 0L } ?: reflectedContentId
        val seasonId = request.number("getSeasonId", "getSeason")
        val reference = BangumiRegionContext.referenceFor(epId, contentId, seasonId)
        val effectiveEpisodeId = if (epId == 0L) reference?.episodeId ?: 0L else epId
        val effectiveSeasonId = seasonId.takeIf { it > 0L } ?: reference?.seasonId ?: 0L
        val augmentedBody = if (methodName == "dmView" && epId == 0L) {
            reference?.episodeId?.let { augmentDmViewRequest(originalBody, it) }
        } else {
            null
        }
        val body = augmentedBody ?: originalBody
        val augmented = augmentedBody != null
        val regions = BangumiRegionContext.candidates(effectiveEpisodeId, effectiveSeasonId, contentId)
        val attempts = ExecutorCompletionService<Response?>(executor)
        val submitted = regions.mapNotNull { region ->
            val host = ModuleSettings.getBangumiServerHost(prefs, region) ?: return@mapNotNull null
            attempts.submit {
                val result = BangumiParserClient.requestGrpc(
                    host = host,
                    service = service,
                    method = methodName.replaceFirstChar { it.uppercase() },
                    body = body,
                    credential = parserCredential(prefs, region),
                    useHttps = ModuleSettings.isBangumiServerHttps(prefs, region),
                )
                val hasSubtitle = methodName == "dmView" &&
                    result?.let { BangumiSubtitleModel.hasSubtitleTrack(it) } == true
                log(
                    "Bangumi DmView fallback: source=$source, region=${region.name}, " +
                        "requestBytes=${body.size}, responseBytes=${result?.size ?: 0}, " +
                        "subtitles=$hasSubtitle, rawPid=$epId, effectivePid=$effectiveEpisodeId, " +
                        "cid=$contentId, augmented=$augmented",
                )
                result?.let { Response(region, it, hasSubtitle) }
            }
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        repeat(submitted.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@repeat
            val attempt = runCatching {
                attempts.poll(remaining, TimeUnit.NANOSECONDS)?.get()
            }.getOrNull() ?: return@repeat
            if (attempt.payload.isEmpty()) return@repeat
            if (methodName == "dmView" && !attempt.hasSubtitle) return@repeat
            submitted.forEach { it.cancel(true) }
            return attempt
        }
        submitted.forEach { it.cancel(true) }
        return null
    }

    private fun parserCredential(prefs: SharedPreferences, region: BangumiRegion): BangumiServerCredential? =
        ModuleSettings.getBangumiServerCredential(prefs, region)
            ?: AccessKeyRepository.read(prefs)?.let { BangumiServerCredential(it, region.defaultPlatform) }

    internal fun augmentDmViewRequest(body: ByteArray, episodeId: Long): ByteArray? {
        if (episodeId <= 0L) return null
        val request = runCatching { DmViewRequest.parseFrom(body) }.getOrNull() ?: return null
        if (request.pid != 0L) return null
        return request.toBuilder().setPid(episodeId).build().toByteArray()
    }

    private fun Any.number(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
        callMethod(name)?.let { it as? Number }?.toLong()?.takeIf { it != 0L }
    } ?: 0L
}
