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
        val reflectedEpisodeId = request.number("getPid", "getEpId", "getEpisodeId", "getAid", "getObjectId")
        val reflectedContentId = if (methodName == "dmView") {
            request.number("getOid", "getCid", "getContentId")
        } else {
            request.number("getCid", "getContentId")
        }
        val identity = if (methodName == "dmView") {
            resolveDmViewIdentity(
                parsedPid = parsedDmView?.pid ?: 0L,
                parsedOid = parsedDmView?.oid ?: 0L,
                reflectedPid = reflectedEpisodeId,
                reflectedOid = reflectedContentId,
                seasonId = request.number("getSeasonId", "getSeason"),
            )
        } else {
            DmViewIdentity(
                rawPid = reflectedEpisodeId,
                rawCid = reflectedContentId,
                episodeId = reflectedEpisodeId,
                cid = reflectedContentId,
                seasonId = request.number("getSeasonId", "getSeason"),
                reference = null,
            )
        }
        if (methodName == "dmView" && identity.reference == null) {
            log(
                "Bangumi DmView fallback skipped: source=$source, " +
                    "unrecognized request rawPid=${identity.rawPid}, rawCid=${identity.rawCid}, " +
                    "season=${identity.seasonId}",
            )
            return null
        }
        val augmentedBody = if (methodName == "dmView") {
            identity.reference?.let { augmentDmViewRequest(originalBody, it.episodeId, it.cid) }
        } else {
            null
        }
        val body = augmentedBody ?: originalBody
        val augmented = augmentedBody != null
        val regions = BangumiRegionContext.candidates(identity.episodeId, identity.seasonId, identity.cid)
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
                        "subtitles=$hasSubtitle, rawPid=${identity.rawPid}, effectivePid=${identity.episodeId}, " +
                        "rawCid=${identity.rawCid}, cid=${identity.cid}, augmented=$augmented",
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

    internal fun augmentDmViewRequest(body: ByteArray, episodeId: Long): ByteArray? =
        augmentDmViewRequest(body, episodeId, 0L)

    internal fun augmentDmViewRequest(body: ByteArray, episodeId: Long, cid: Long): ByteArray? {
        if (episodeId <= 0L || cid < 0L) return null
        val request = runCatching { DmViewRequest.parseFrom(body) }.getOrNull() ?: return null
        val builder = request.toBuilder()
        var changed = false
        if (request.pid != episodeId) {
            builder.setPid(episodeId)
            changed = true
        }
        if (cid > 0L) builder.setOid(cid)
        if (cid > 0L && request.oid != cid) changed = true
        // DmView silently omits the subtitle block when type is absent. The
        // host often sends a zero-valued request for unlocked PGC playback,
        // so normalize it before forwarding to the regional endpoint.
        if (request.type == 0) {
            builder.setType(1)
            changed = true
        }
        return if (changed) builder.build().toByteArray() else null
    }

    private fun Any.number(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
        callMethod(name)?.let { it as? Number }?.toLong()?.takeIf { it > 0L }
    } ?: 0L

    internal fun resolveDmViewIdentity(
        parsedPid: Long,
        parsedOid: Long,
        reflectedPid: Long,
        reflectedOid: Long,
        seasonId: Long,
    ): DmViewIdentity {
        val positiveParsedPid = parsedPid.takeIf { it > 0L } ?: 0L
        val positiveParsedOid = parsedOid.takeIf { it > 0L } ?: 0L
        val positiveReflectedPid = reflectedPid.takeIf { it > 0L } ?: 0L
        val positiveReflectedOid = reflectedOid.takeIf { it > 0L } ?: 0L
        val reference = sequenceOf(
            BangumiRegionContext.referenceFor(positiveParsedPid, positiveParsedOid, seasonId),
            BangumiRegionContext.referenceFor(positiveParsedPid, positiveReflectedOid, seasonId),
            BangumiRegionContext.referenceFor(positiveReflectedPid, positiveParsedOid, seasonId),
            BangumiRegionContext.referenceFor(positiveReflectedPid, positiveReflectedOid, seasonId),
            BangumiRegionContext.referenceFor(0L, positiveParsedOid, seasonId),
            BangumiRegionContext.referenceFor(0L, positiveReflectedOid, seasonId),
            BangumiRegionContext.referenceFor(0L, 0L, seasonId),
        ).filterNotNull().firstOrNull()
        return DmViewIdentity(
            rawPid = parsedPid.takeIf { it != 0L } ?: reflectedPid,
            rawCid = parsedOid.takeIf { it != 0L } ?: reflectedOid,
            episodeId = reference?.episodeId ?: 0L,
            cid = reference?.cid ?: 0L,
            seasonId = seasonId.takeIf { it > 0L } ?: reference?.seasonId ?: 0L,
            reference = reference,
        )
    }

    internal data class DmViewIdentity(
        val rawPid: Long,
        val rawCid: Long,
        val episodeId: Long,
        val cid: Long,
        val seasonId: Long,
        val reference: BangumiRegionContext.EpisodeReference?,
    )
}
