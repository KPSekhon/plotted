package app.plotted.watchlist.domain

import app.plotted.platform.spi.WatchlistDirectory
import app.plotted.watchlist.persistence.WatchlistRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Watchlist's side of the [WatchlistDirectory] contract.
 *
 * Thin, like the other adapters: it keeps [Priority] and [WatchStatus] on this
 * side of the boundary so the recommender depends on an `Int` and a set of ids
 * rather than on this module's model. See ADR 0008.
 */
@Component
class WatchlistDirectoryAdapter(
    private val watchlists: WatchlistRepository,
    private val seriesProgress: SeriesProgressService,
) : WatchlistDirectory {
    override fun outstandingItems(userId: UUID): List<WatchlistDirectory.WatchlistEntry> {
        // A recommender asking what is on the list must not bring the list into
        // existence. Somebody with no watchlist has nothing outstanding, which is
        // an answer both recommenders already know how to render.
        val watchlist = watchlists.findDefault(userId) ?: return emptyList()
        return watchlists.findItems(watchlist.id)
            // Filtered here rather than by the caller: "outstanding" is a
            // watchlist concept, and letting another module re-derive it from
            // raw statuses is how two definitions start disagreeing.
            .filter { it.status.isOutstanding }
            .map {
                WatchlistDirectory.WatchlistEntry(
                    titleId = it.titleId,
                    priority = it.priority.value,
                    desiredByDate = it.desiredByDate,
                )
            }
    }

    override fun blockedTitleIds(userId: UUID): Set<UUID> = watchlists.blockedTitleIds(userId)

    /**
     * Only the series that have a next episode appear.
     *
     * A film, or a series whose episodes the catalogue does not hold, simply has
     * no entry -- the caller falls back to what it already knew. Returning a
     * half-populated row for those would make "no next episode" and "caught up"
     * the same value, and they are opposite answers.
     */
    override fun seriesProgress(userId: UUID, titleIds: Collection<UUID>): Map<UUID, WatchlistDirectory.NextUp> =
        seriesProgress.viewAll(userId, titleIds).mapNotNull { (titleId, view) ->
            val next = view.next ?: return@mapNotNull null
            titleId to WatchlistDirectory.NextUp(
                episodeId = next.episodeId,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                name = next.name,
                runtimeMinutes = next.runtimeMinutes,
                started = view.started,
                remainingEpisodes = view.remaining.episodes,
            )
        }.toMap()

    override fun watchersOf(titleId: UUID): List<WatchlistDirectory.Watcher> = watchlists.watchersOf(titleId).map {
        WatchlistDirectory.Watcher(userId = it.userId, priority = it.priority.value)
    }
}
