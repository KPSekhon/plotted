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
) : WatchlistDirectory {
    override fun outstandingItems(userId: UUID): List<WatchlistDirectory.WatchlistEntry> {
        val watchlist = watchlists.findOrCreateDefault(userId)
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

    override fun watchersOf(titleId: UUID): List<WatchlistDirectory.Watcher> = watchlists.watchersOf(titleId).map {
        WatchlistDirectory.Watcher(userId = it.userId, priority = it.priority.value)
    }
}
