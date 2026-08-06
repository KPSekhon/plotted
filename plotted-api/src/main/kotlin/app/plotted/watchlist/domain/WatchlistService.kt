package app.plotted.watchlist.domain

import app.plotted.platform.error.NotFoundException
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.WatchlistRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * The watchlist, as the rest of the application sees it.
 *
 * Everything is scoped to the calling user's default list. No method takes a
 * watchlist id from outside, for the same reason no identity endpoint takes a
 * user id: an id you never accept is an id nobody can tamper with.
 */
@Service
class WatchlistService(
    private val watchlists: WatchlistRepository,
    private val titles: TitleDirectory,
) {
    @Transactional
    fun list(userId: UUID): WatchlistView {
        val watchlist = watchlists.findOrCreateDefault(userId)
        return WatchlistView(watchlist, hydrate(watchlists.findItems(watchlist.id)))
    }

    @Transactional
    fun add(userId: UUID, titleId: UUID, priority: Int?, desiredByDate: LocalDate?, notes: String?): WatchlistEntry {
        val watchlist = watchlists.findOrCreateDefault(userId)

        // Check the title exists before writing a row that references it. The
        // foreign key would catch this too, but as a 500 from a constraint
        // violation rather than as the 404 it actually is.
        if (titles.findSummaries(listOf(titleId)).isEmpty()) {
            throw NotFoundException("Title")
        }

        val item = watchlists.addItem(
            watchlistId = watchlist.id,
            titleId = titleId,
            priority = Priority.of(priority),
            desiredByDate = desiredByDate,
            notes = notes,
        )
        return hydrate(listOf(item)).single()
    }

    @Transactional
    fun update(userId: UUID, itemId: UUID, patch: WatchlistItemPatch): WatchlistEntry {
        val watchlist = watchlists.findOrCreateDefault(userId)
        val changed = watchlists.updateItem(
            watchlistId = watchlist.id,
            itemId = itemId,
            priority = patch.priority?.let(Priority::of),
            status = patch.status,
            desiredByDate = patch.desiredByDate,
            clearDesiredByDate = patch.clearDesiredByDate,
            notes = patch.notes,
            clearNotes = patch.clearNotes,
        )
        if (!changed) throw NotFoundException("Watchlist item")

        val item = watchlists.findItem(watchlist.id, itemId)
            ?: throw NotFoundException("Watchlist item")
        return hydrate(listOf(item)).single()
    }

    @Transactional
    fun remove(userId: UUID, itemId: UUID) {
        val watchlist = watchlists.findOrCreateDefault(userId)
        if (!watchlists.removeItem(watchlist.id, itemId)) {
            throw NotFoundException("Watchlist item")
        }
    }

    /**
     * Attaches title details to items, in one lookup for the whole list.
     *
     * An item whose title has since been deleted keeps its place with a null
     * summary rather than disappearing. Dropping it here would make the list
     * silently shorter than the number of things the user added, and "where did
     * it go" is a worse failure than a row that admits it lost its title.
     */
    private fun hydrate(items: List<WatchlistItem>): List<WatchlistEntry> {
        if (items.isEmpty()) return emptyList()
        val summaries = titles.findSummaries(items.map { it.titleId }).associateBy { it.titleId }
        return items.map { WatchlistEntry(item = it, title = summaries[it.titleId]) }
    }

    data class WatchlistView(
        val watchlist: Watchlist,
        val entries: List<WatchlistEntry>,
    )

    data class WatchlistEntry(
        val item: WatchlistItem,
        val title: TitleDirectory.TitleSummary?,
    )

    /**
     * A partial update. The `clear` flags exist because JSON cannot distinguish
     * an absent field from one explicitly set to null, and "remove the date I
     * set" is a request the user can genuinely make.
     */
    data class WatchlistItemPatch(
        val priority: Int? = null,
        val status: WatchStatus? = null,
        val desiredByDate: LocalDate? = null,
        val clearDesiredByDate: Boolean = false,
        val notes: String? = null,
        val clearNotes: Boolean = false,
    )
}
