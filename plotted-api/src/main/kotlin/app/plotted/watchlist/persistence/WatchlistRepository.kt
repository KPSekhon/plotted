package app.plotted.watchlist.persistence

import app.plotted.generated.jooq.tables.references.BLOCKED_TITLES
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import app.plotted.watchlist.domain.BlockedTitle
import app.plotted.watchlist.domain.Priority
import app.plotted.watchlist.domain.TitleWatcher
import app.plotted.watchlist.domain.WatchStatus
import app.plotted.watchlist.domain.Watchlist
import app.plotted.watchlist.domain.WatchlistItem
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
     *
     * **Only the watchlist's own read-write endpoints may call this.** Everything
     * that merely reads the list goes through [findDefault] and treats an absent
     * one as empty. A reader that provisions is a `GET` that writes, and inside a
     * `readOnly` transaction Postgres refuses the insert outright — which is how
     * the coverage dashboard came to fail with a 500 for every account that
     * opened it before its watchlist existed.
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

    /** The user's default watchlist, or null if they have not got one yet. */
    fun findDefault(userId: UUID): Watchlist? = dsl.select(
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
        WATCHLIST_ITEMS.COMPLETED_AT,
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
        WATCHLIST_ITEMS.COMPLETED_AT,
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
        WATCHLIST_ITEMS.COMPLETED_AT,
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
                completedAt = null,
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
        status?.let {
            changes[WATCHLIST_ITEMS.STATUS] = it.dbValue
            changes[WATCHLIST_ITEMS.COMPLETED_AT] = completionTimeFor(it)
        }
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
     * What `completed_at` becomes when an item moves to [status].
     *
     * Expressed as SQL rather than decided in Kotlin because the answer depends
     * on the row's *current* status, and reading that first would be a race: two
     * concurrent updates could both observe "not completed yet" and the second
     * would overwrite the first's timestamp. Every `SET` expression in an
     * `UPDATE` sees the pre-update row, so the `CASE` below asks "was this
     * already completed?" of the value being replaced.
     *
     * The `ELSE` branch is the whole point. Re-saving an item that is already
     * completed -- editing its notes, correcting its priority -- has to leave the
     * original timestamp alone. A version that stamped `now()` on every update
     * mentioning `status = 'completed'` behaves identically in any test that
     * completes an item once, and on real traffic would walk the timestamp
     * forward until the temporal split it exists to serve was splitting on the
     * date of the last edit.
     *
     * Moving to any other status clears it, which is also what keeps the row
     * satisfying `watchlist_items_completion_time_requires_completion`.
     */
    private fun completionTimeFor(status: WatchStatus): Any? = if (status != WatchStatus.COMPLETED) {
        null
    } else {
        DSL.`when`(
            WATCHLIST_ITEMS.STATUS.ne(WatchStatus.COMPLETED.dbValue),
            DSL.`val`(OffsetDateTime.now(clock), WATCHLIST_ITEMS.COMPLETED_AT),
        ).otherwise(WATCHLIST_ITEMS.COMPLETED_AT)
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

    /**
     * Everything this user has blocked, most recent first.
     *
     * Exists so blocking is reversible from the interface. A preference you can
     * set and never see again is a one-way door, and the person most likely to
     * want it open again is the one who shut it by accident.
     */
    fun blockedTitles(userId: UUID): List<BlockedTitle> = dsl.select(
        BLOCKED_TITLES.TITLE_ID,
        BLOCKED_TITLES.REASON,
        BLOCKED_TITLES.CREATED_AT,
    )
        .from(BLOCKED_TITLES)
        .where(BLOCKED_TITLES.USER_ID.eq(userId))
        .orderBy(BLOCKED_TITLES.CREATED_AT.desc())
        .fetch()
        .map {
            BlockedTitle(
                titleId = it[BLOCKED_TITLES.TITLE_ID]!!,
                reason = it[BLOCKED_TITLES.REASON],
                createdAt = it[BLOCKED_TITLES.CREATED_AT]!!.toInstant(),
            )
        }

    /**
     * Blocks a title, or returns the existing block if it is already there.
     *
     * Idempotent for the same reason [addItem] is: blocking something twice is a
     * slip rather than an error worth a 409. The original reason and timestamp
     * win, because the first "no" is the one that was actually meant -- a second
     * request carrying an empty reason must not erase the first one's.
     */
    fun block(userId: UUID, titleId: UUID, reason: String?): BlockedTitle {
        dsl.insertInto(BLOCKED_TITLES)
            .set(BLOCKED_TITLES.USER_ID, userId)
            .set(BLOCKED_TITLES.TITLE_ID, titleId)
            .set(BLOCKED_TITLES.REASON, reason)
            .set(BLOCKED_TITLES.CREATED_AT, OffsetDateTime.now(clock))
            .onConflictDoNothing()
            .execute()

        return blockedTitles(userId).first { it.titleId == titleId }
    }

    /**
     * Users still waiting on a title, highest priority first.
     *
     * The reverse of [findItems], for Plot Armour: a title is leaving a service
     * and the question is who to tell. Blocked titles are excluded in the query
     * with a `NOT EXISTS`, so the one suppression that needs no judgement cannot
     * be forgotten by a caller.
     *
     * Only personal watchlists. A household list has a null `user_id`, and there
     * is nobody on it to notify until households ship.
     */
    fun watchersOf(titleId: UUID): List<TitleWatcher> = dsl.select(
        WATCHLISTS.USER_ID,
        WATCHLIST_ITEMS.PRIORITY,
    )
        .from(WATCHLIST_ITEMS)
        .join(WATCHLISTS).on(WATCHLISTS.ID.eq(WATCHLIST_ITEMS.WATCHLIST_ID))
        .where(WATCHLIST_ITEMS.TITLE_ID.eq(titleId))
        .and(WATCHLISTS.USER_ID.isNotNull)
        .and(WATCHLIST_ITEMS.STATUS.`in`(OUTSTANDING_STATUSES))
        .andNotExists(
            dsl.selectOne()
                .from(BLOCKED_TITLES)
                .where(BLOCKED_TITLES.USER_ID.eq(WATCHLISTS.USER_ID))
                .and(BLOCKED_TITLES.TITLE_ID.eq(titleId)),
        )
        .orderBy(WATCHLIST_ITEMS.PRIORITY.asc())
        .fetch()
        .map {
            TitleWatcher(
                userId = it[WATCHLISTS.USER_ID]!!,
                priority = Priority(it[WATCHLIST_ITEMS.PRIORITY]!!.toInt()),
            )
        }

    fun unblock(userId: UUID, titleId: UUID): Boolean = dsl.deleteFrom(BLOCKED_TITLES)
        .where(BLOCKED_TITLES.USER_ID.eq(userId))
        .and(BLOCKED_TITLES.TITLE_ID.eq(titleId))
        .execute() > 0

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
        completedAt = record[WATCHLIST_ITEMS.COMPLETED_AT]?.toInstant(),
    )

    private companion object {
        const val DEFAULT_NAME = "My list"

        /**
         * Derived from the enum rather than written out, so a new status cannot
         * be added to one and forgotten in the other. `WatchStatus.isOutstanding`
         * stays the single definition of who is still waiting on a title.
         */
        val OUTSTANDING_STATUSES: List<String> =
            WatchStatus.entries.filter { it.isOutstanding }.map { it.dbValue }
    }
}
