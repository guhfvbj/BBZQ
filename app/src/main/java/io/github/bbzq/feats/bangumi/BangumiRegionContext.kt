package io.github.bbzq.feats.bangumi

import io.github.bbzq.BangumiRegion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Shared region information collected from parser-backed search and season responses. */
internal object BangumiRegionContext {
    private const val ACTIVE_REGION_TTL_MS = 5 * 60 * 1000L
    private const val DM_VIEW_ROUTE_TTL_MS = 10_000L
    private val episodeRegions = ConcurrentHashMap<String, BangumiRegion>()
    private val seasonRegions = ConcurrentHashMap<String, BangumiRegion>()
    private val contentRegions = ConcurrentHashMap<String, BangumiRegion>()
    private val episodeReferences = ConcurrentHashMap<String, EpisodeReference>()
    private val activeRegion = AtomicReference<ActiveRegion?>(null)
    private val pendingDmViewRegion = AtomicReference<ActiveRegion?>(null)

    fun recordEpisode(id: String?, region: BangumiRegion) {
        id?.takeIf(String::isNotBlank)?.let { episodeRegions[it] = region }
    }

    fun recordEpisodeReference(
        episodeId: String?,
        cid: String?,
        seasonId: String?,
        region: BangumiRegion,
        isMovie: Boolean,
    ) {
        val episode = episodeId?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val content = cid?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val season = seasonId?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
        val reference = EpisodeReference(region, season, episode, content, isMovie)
        episodeRegions[episode.toString()] = region
        contentRegions[content.toString()] = region
        episodeReferences[episode.toString()] = reference
        if (season != 0L) seasonRegions[season.toString()] = region
    }

    fun referenceFor(episodeId: Long, cid: Long, seasonId: Long): EpisodeReference? {
        val direct = episodeId.takeIf { it != 0L }?.let { episodeReferences[it.toString()] }
        if (direct != null) return direct
        return seasonId.takeIf { it != 0L }
            ?.let { season -> episodeReferences.values.firstOrNull { it.seasonId == season } }
    }

    fun resolvePlayQuery(query: Map<String, String>, region: BangumiRegion): Map<String, String> {
        if (region != BangumiRegion.INTL) return query
        val episodeId = query["ep_id"]?.toLongOrNull() ?: return query
        val cid = query["cid"]?.toLongOrNull() ?: 0L
        val seasonId = query["season_id"]?.toLongOrNull() ?: 0L
        val reference = referenceFor(episodeId, cid, seasonId) ?: return query
        return LinkedHashMap(query).apply {
            put("ep_id", reference.episodeId.toString())
            put("cid", reference.cid.toString())
        }
    }

    fun recordSeason(id: String?, region: BangumiRegion) {
        id?.takeIf(String::isNotBlank)?.let { seasonRegions[it] = region }
    }

    fun activate(region: BangumiRegion) {
        activeRegion.set(ActiveRegion(region, System.currentTimeMillis() + ACTIVE_REGION_TTL_MS))
    }

    fun candidates(epId: Long, seasonId: Long): List<BangumiRegion> {
        val known = listOfNotNull(
            episodeRegion(epId),
            seasonRegion(seasonId),
            currentActiveRegion(),
        )
        return (known + listOf(BangumiRegion.HK, BangumiRegion.TW, BangumiRegion.INTL)).distinct()
    }

    fun episodeRegion(id: Long): BangumiRegion? =
        id.takeIf { it != 0L }?.let { episodeRegions[it.toString()] }

    fun seasonRegion(id: Long): BangumiRegion? =
        id.takeIf { it != 0L }?.let { seasonRegions[it.toString()] }

    fun contentRegion(id: Long): BangumiRegion? =
        id.takeIf { it != 0L }?.let { contentRegions[it.toString()] }

    fun prepareDmView(contentId: Long) {
        val region = contentRegion(contentId)
        pendingDmViewRegion.set(region?.let { ActiveRegion(it, System.currentTimeMillis() + DM_VIEW_ROUTE_TTL_MS) })
    }

    fun consumeDmViewRegion(): BangumiRegion? {
        val pending = pendingDmViewRegion.getAndSet(null) ?: return null
        return pending.region.takeIf { pending.expiresAtMillis > System.currentTimeMillis() }
    }

    fun activeRegion(): BangumiRegion? = currentActiveRegion()

    data class EpisodeReference(
        val region: BangumiRegion,
        val seasonId: Long,
        val episodeId: Long,
        val cid: Long,
        val isMovie: Boolean,
    )

    private fun currentActiveRegion(): BangumiRegion? {
        val current = activeRegion.get() ?: return null
        if (current.expiresAtMillis > System.currentTimeMillis()) return current.region
        activeRegion.compareAndSet(current, null)
        return null
    }

    private data class ActiveRegion(val region: BangumiRegion, val expiresAtMillis: Long)

}
