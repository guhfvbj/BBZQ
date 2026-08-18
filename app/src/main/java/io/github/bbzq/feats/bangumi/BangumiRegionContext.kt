package io.github.bbzq.feats.bangumi

import io.github.bbzq.BangumiRegion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Shared region information collected from parser-backed search and season responses. */
internal object BangumiRegionContext {
    private const val ACTIVE_REGION_TTL_MS = 5 * 60 * 1000L
    private val episodeRegions = ConcurrentHashMap<String, BangumiRegion>()
    private val seasonRegions = ConcurrentHashMap<String, BangumiRegion>()
    private val activeRegion = AtomicReference<ActiveRegion?>(null)

    fun recordEpisode(id: String?, region: BangumiRegion) {
        id?.takeIf(String::isNotBlank)?.let { episodeRegions[it] = region }
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

    fun activeRegion(): BangumiRegion? = currentActiveRegion()

    private fun currentActiveRegion(): BangumiRegion? {
        val current = activeRegion.get() ?: return null
        if (current.expiresAtMillis > System.currentTimeMillis()) return current.region
        activeRegion.compareAndSet(current, null)
        return null
    }

    private data class ActiveRegion(val region: BangumiRegion, val expiresAtMillis: Long)
}
