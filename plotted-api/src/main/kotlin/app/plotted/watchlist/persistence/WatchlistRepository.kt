package app.plotted.watchlist.persistence

import app.plotted.generated.jooq.tables.references.BLOCKED_TITLES
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import app.plotted.watchlist.domain.Priority
import app.plotted.watchlist.domain.WatchStatus
import app.plotted.watchlist.domain.Watchlist
import app.plotted.watchlist.domain.WatchlistItem
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Watchlist persistence.
 *
 * Every method that touches an item is scoped by watchlist id, and the only way
 * a caller obtains one is [findOrCreateDefault] for its own user. Ownership is
 * therefore a property of the query rather than a check someone has to remember
 * to write -- the same reasoning as the identity module's rule that no endpoint
 * accepts a user id.
 */
@Repository
class WatchlistRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /**
     * The user's default watchlist, creating it on first use.
     *
     * A partial unique index enforces one default per user, so two concurrent
     * first requests cannot both succeed. The loser catches the violation and
     * re-reads rather than failing the request: the row it wanted exists, which
     * is all it actually cared about.
     */
    fun findOrCreateDefault(userId: UUID): Watchlist {
        findDefault(userId)?.let { return it }

        val id = UUID.randomUUID()
        return try {
            dsl.insertInto(WATCHLISTS)
                .set(WATCHLISTS.ID, id)
                .set(WATCHLISTS.USER_ID, userId)
                .set(WATCHLISTS.NAME, DEFAULT_NAME)
                .set(WATCHLISTS.IS_DEFAULT, true)
                .set(WATCHLISTS.VISIBILITY, "private")
                .set(WATCHLISTS.CREATED_AT, OffsetDateTime.now(clock))
                .execute()
            Watchlist(id = id, name = DEFAULT_NAME, isDefault = true, createdAt = OffsetDateTime.now(clock).toInstant())
        } catch (_: DuplicateKeyException) {
            findDefault(userId) ?: error("Default watchlist for $userId vanished between insert and read")
        }
    }

    private fun findDefault(userId: UUID): Watchlist? = dsl.select(
        WATCHLISTS.ID,
        WATCHLISTS.NAME,
        WATCHLISTS.IS_DEFAULT,
        WATCHLISTS.CREATED_AT,
    )
        .from(WATCHLISTS)
        .where(WATCHLISTS.USER_ID.eq(userId))
        .and(WATCHLISTS.IS_DEFAULT.isTrue)
        .fetchOne()
        ?.let {
            Watchlist(
                id = it[WATCHLISTS.ID]!!,
                name = it[WATCHLISTS.NAME]!!,
                isDefault = it[WATCHLISTS.IS_DEFAULT]!!,
                createdAt = it[WATCHLISTS.CREATED_AT]!!.toInstant(),
            )
        }

    /**
     * Items on a list, most important first.
     *
     * Ordered by priority ascending because 1 is the highest, then by when it
     * was added so that two items of equal priority keep a stable order rather
     * than shuffling between requests.
     */
    fun findItems(watchlistId: UUID): List<WatchlistItem> = dsl.select(
        WATCHLIST_ITEMS.ID,
        WATCHLIST_ITEMS.TITLE_ID,
        WATCHLIST_ITEMS.PRIORITY,
        WATCHLIST_ITEMS.STATUS,
        WATCHLIST_ITEMS.ADDED_AT,
        WATCHLIST_ITEMS.DESIRED_BY_DATE,
        WATCHLIST_ITEMS.NOTES,
    )
        .from(WATCHLIST_ITEMS)
        .where(WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId))
        .orderBy(WATCHLIST_ITEMS.PRIORITY.asc(), WATCHLIST_ITEMS.ADDED_AT.asc())
        .fetch()
        .map(::toItem)

    fun findItem(watchlistId: UUID, itemId: UUID): WatchlistItem? = dsl.select(
        WATCHLIST_ITEMS.ID,
        WATCHLIST_ITEMS.TITLE_ID,
        WATCHLIST_ITEMS.PRIORITY,
        WATCHLIST_ITEMS.STATUS,
        WATCHLIST_ITEMS.ADDED_AT,
        WATCHLIST_ITEMS.DESIRED_BY_DATE,
        WATCHLIST_ITEMS.NOTES,
    )
        .from(WATCHLIST_ITEMS)
        .where(WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId))
        .and(WATCHLIST_ITEMS.ID.eq(itemId))
        .fetchOne()
        ?.let(::toItem)

    fun findItemByTitle(watchlistId: UUID, titleId: UUID): WatchlistItem? = dsl.select(
        WATCHLIST_ITEMS.ID,
        WATCHLIST_ITEMS.TITLE_ID,
        WATCHLIST_ITEMS.PRIORITY,
        WATCHLIST_ITEMS.STATUS,
        WATCHLIST_ITEMS.ADDED_AT,
        WATCHLIST_ITEMS.DESIRED_BY_DATE,
        WATCHLIST_ITEMS.NOTES,
    )
        .from(WATCHLIST_ITEMS)
        .where(WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId))
        .and(WATCHLIST_ITEMS.TITLE_ID.eq(titleId))
        .fetchOne()
        ?.let(::toItem)

    /**
     * Adds a title, or returns the existing row if it is already there.
     *
     * Adding something twice is a thing a person does by accident, not an error
     * worth a 409 -- so this is idempotent, and the caller can tell the two
     * apart by whether the returned item is the one it described.
     */
    fun addItem(watchlistId: UUID, titleId: UUID, priority: Priority, desiredByDate: LocalDate?, notes: String?): WatchlistItem {
        val id = UUID.randomUUID()
        return try {
            dsl.insertInto(WATCHLIST_ITEMS)
                .set(WATCHLIST_ITEMS.ID, id)
                .set(WATCHLIST_ITEMS.WATCHLIST_ID, watchlistId)
                .set(WATCHLIST_ITEMS.TITLE_ID, titleId)
                .set(WATCHLIST_ITEMS.PRIORITY, priority.value.toShort())
                .set(WATCHLIST_ITEMS.STATUS, WatchStatus.PENDING.dbValue)
                .set(WATCHLIST_ITEMS.ADDED_AT, OffsetDateTime.now(clock))
                .set(WATCHLIST_ITEMS.DESIRED_BY_DATE, desiredByDate)
                .set(WATCHLIST_ITEMS.NOTES, notes)
                .set(WATCHLIST_ITEMS.SOURCE, "manual")
                .execute()
            WatchlistItem(
                id = id,
                titleId = titleId,
                priority = priority,
                status = WatchStatus.PENDING,
                addedAt = OffsetDateTime.now(clock).toInstant(),
                desiredByDate = desiredByDate,
                notes = notes,
            )
        } catch (_: DuplicateKeyException) {
            findItemByTitle(watchlistId, titleId)
                ?: error("Watchlist item for title $titleId vanished between insert and read")
        }
    }

    /**
     * Applies a partial update, returning false when there is no such item.
     *
     * Built as a map of changes because a patch with nothing in it is a real
     * request -- `PATCH {}` is valid -- and an `UPDATE` with an empty `SET` is
     * not valid SQL. Assembling the columns first means the empty case is
     * visible and answerable rather than a syntax error at runtime.
     */
    fun updateItem(
        watchlistId: UUID,
        itemId: UUID,
        priority: Priority?,
        status: WatchStatus?,
        desiredByDate: LocalDate?,
        clearDesiredByDate: Boolean,
        notes: String?,
        clearNotes: Boolean,
    ): Boolean {
        val changes = mutableMapOf<org.jooq.Field<*>, Any?>()
        priority?.let { changes[WATCHLIST_ITEMS.PRIORITY] = it.value.toShort() }
        status?.let { changes[WATCHLIST_ITEMS.STATUS] = it.dbValue }
        // Clearing a field and not mentioning it are different requests, and a
        // nullable parameter alone cannot tell them apart.
        if (clearDesiredByDate) {
            changes[WATCHLIST_ITEMS.DESIRED_BY_DATE] = null
        } else {
            desiredByDate?.let {
                changes[WATCHLIST_ITEMS.DESIRED_BY_DATE] = it
            }
        }
        if (clearNotes) changes[WATCHLIST_ITEMS.NOTES] = null else notes?.let { changes[WATCHLIST_ITEMS.NOTES] = it }

        // Nothing to change: report whether the item exists, which is the same
        // answer the caller would have got from an update that matched it.
        if (changes.isEmpty()) return findItem(watchlistId, itemId) != null

        return dsl.update(WATCHLIST_ITEMS)
            .set(changes)
            .where(WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId))
            .and(WATCHLIST_ITEMS.ID.eq(itemId))
            .execute() > 0
    }

    /**
     * Titles this user has asked never to be shown.
     *
     * Keyed by user rather than by watchlist: blocking is a statement about the
     * person's taste, not about one list, and it must survive them starting a
     * new list.
     */
    fun blockedTitleIds(userId: UUID): Set<UUID> = dsl.select(BLOCKED_TITLES.TITLE_ID)
        .from(BLOCKED_TITLES)
        .where(BLOCKED_TITLES.USER_ID.eq(userId))
        .fetch()
        .mapNotNullTo(mutableSetOf()) { it.value1() }

    fun removeItem(watchlistId: UUID, itemId: UUID): Boolean = dsl.deleteFrom(WATCHLIST_ITEMS)
        .where(WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId))
        .and(WATCHLIST_ITEMS.ID.eq(itemId))
        .execute() > 0

    private fun toItem(record: org.jooq.Record): WatchlistItem = WatchlistItem(
        id = record[WATCHLIST_ITEMS.ID]!!,
        titleId = record[WATCHLIST_ITEMS.TITLE_ID]!!,
        priority = Priority(record[WATCHLIST_ITEMS.PRIORITY]!!.toInt()),
        status = WatchStatus.fromDb(record[WATCHLIST_ITEMS.STATUS]!!),
        addedAt = record[WATCHLIST_ITEMS.ADDED_AT]!!.toInstant(),
        desiredByDate = record[WATCHLIST_ITEMS.DESIRED_BY_DATE],
        notes = record[WATCHLIST_ITEMS.NOTES],
    )

    private companion object {
        const val DEFAULT_NAME = "My list"
    }
}
