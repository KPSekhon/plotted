package app.plotted.watchlist.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A list of things someone intends to watch.
 *
 * Every user gets exactly one default list, created on demand. Multiple lists
 * are supported by the schema and deliberately not surfaced yet: nothing in
 * phases 4 and 5 needs them, and a list picker on every screen would be a
 * feature paid for in permanent interface clutter.
 */
data class Watchlist(
    val id: UUID,
    val name: String,
    val isDefault: Boolean,
    val createdAt: Instant,
)

/**
 * A title on a watchlist, with the intent attached.
 *
 * `priority` and `desiredByDate` are what make this more than a list of names:
 * they are the inputs the recommendation weighting and the cancellation
 * optimiser read, which is why they live on the item rather than being inferred
 * from the order someone happened to add things in.
 */
data class WatchlistItem(
    val id: UUID,
    val titleId: UUID,
    val priority: Priority,
    val status: WatchStatus,
    val addedAt: Instant,
    val desiredByDate: LocalDate?,
    val notes: String?,
    /**
     * When this item last became [WatchStatus.COMPLETED], or null if it is not
     * completed.
     *
     * Null on a *completed* item means the transition happened before the column
     * existed, which is a different fact from "not completed" and has to stay
     * distinguishable: the evaluation harness dates outcomes by this, and an
     * outcome it cannot date must be excluded rather than dated wrongly.
     */
    val completedAt: Instant?,
)

/**
 * A title the user has asked never to be recommended.
 *
 * Keyed by user rather than by watchlist: this is a statement about taste, not
 * about one list, and it has to survive them starting a new one.
 *
 * It is deliberately *not* a catalogue filter. Someone searching for something
 * they blocked should still find it — hiding it there reads as a missing
 * catalogue entry rather than as a preference being honoured, and it would also
 * leave no way to change their mind. Blocking suppresses recommendations, which
 * is what `TonightService` and `CancelCultureService` read it for.
 */
data class BlockedTitle(
    val titleId: UUID,
    val reason: String?,
    val createdAt: Instant,
)

/**
 * Somebody still waiting on a particular title.
 *
 * The watchlist read backwards. Plot Armour asks the question from the other
 * end — a title has been seen leaving a service, and what it needs is who cares
 * — and that is a different query rather than a filter over an existing one.
 */
data class TitleWatcher(
    val userId: UUID,
    val priority: Priority,
)

/**
 * User-assigned importance, 1 to 5.
 *
 * **1 is the highest.** The direction is stated in the column comment, in the
 * schema, and again here, because it is the kind of ambiguity that produces
 * optimiser bugs which look like bad recommendations rather than like defects
 * -- everything runs, the answers are simply backwards.
 *
 * Represented as a value class over Int rather than an enum so the arithmetic
 * the weighting does stays arithmetic, while construction stays checked.
 */
@JvmInline
value class Priority(val value: Int) {
    init {
        require(value in HIGHEST..LOWEST) { "Priority must be between $HIGHEST and $LOWEST, was $value" }
    }

    /**
     * Priority as a 0..1 weight, highest priority weighing most.
     *
     * Coverage is weighted by this so that a service carrying the one film
     * someone is desperate to see outranks a service carrying four they are
     * lukewarm about. An unweighted count would make the two look identical,
     * and the whole point of asking for a priority is that they are not.
     */
    val weight: Double get() = (LOWEST - value + 1).toDouble() / LOWEST

    companion object {
        const val HIGHEST = 1
        const val LOWEST = 5

        /** What an item gets when nobody says otherwise: the middle of the range. */
        val DEFAULT = Priority(3)

        fun of(value: Int?): Priority = if (value == null) DEFAULT else Priority(value)
    }
}

enum class WatchStatus(val dbValue: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    ABANDONED("abandoned"),

    /**
     * On the list, but not currently watchable anywhere the user will accept.
     * Distinct from `abandoned`: the user still wants it, so it keeps its place
     * and Plot Armour has something to notify about when it returns.
     */
    UNAVAILABLE("unavailable"),
    ;

    /**
     * Whether the user is still waiting on this.
     *
     * Drives both the nightly refresh priority and what coverage counts. A
     * finished title tells you nothing about which service to pay for next
     * month, and letting completed items keep voting would make coverage a
     * measure of what someone has already watched.
     */
    val isOutstanding: Boolean get() = this == PENDING || this == IN_PROGRESS || this == UNAVAILABLE

    companion object {
        fun fromDb(value: String): WatchStatus =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown watchlist item status '$value'")

        fun parse(value: String): WatchStatus? = entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}
