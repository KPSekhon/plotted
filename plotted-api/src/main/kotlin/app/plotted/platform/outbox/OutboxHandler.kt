package app.plotted.platform.outbox

import java.time.Instant
import java.util.UUID

/**
 * One event, as a handler sees it.
 *
 * [attempts] is how many times delivery has already been tried, and it is
 * exposed rather than hidden because a handler occasionally wants it -- a
 * notification on its fifth attempt is worth sending differently from one on its
 * first, and there is no way to recover that from the payload.
 */
data class OutboxRecord(
    val id: Long,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: Map<String, Any?>,
    val createdAt: Instant,
    val attempts: Int,
)

/**
 * Something that does the work an outbox event describes.
 *
 * Handlers must be **idempotent**. The relay is at-least-once by construction:
 * the alternative is marking a row published before doing the work, which turns
 * every crash into silent data loss. Delivering twice is recoverable and losing
 * once is not, so the duplicate is the failure mode chosen on purpose -- but that
 * choice only pays off if handlers can absorb it.
 *
 * Throwing is how a handler reports failure. The relay records the message,
 * schedules a retry with backoff, and moves on to the next event; it does not
 * stop the batch, because one broken event type should not hold up every other.
 */
interface OutboxHandler {
    /** The `event_type` this handles. One handler per type; two is a startup failure. */
    val eventType: String

    fun handle(record: OutboxRecord)
}

/**
 * The event types carried by the outbox.
 *
 * They live in the shared kernel rather than beside whichever module emits them.
 * A producer in `availability` and a handler in `alerts` both need this string,
 * and if either owned it the other would have to import across a feature
 * boundary -- the coupling ADR 0008 exists to prevent. The event is the contract,
 * so the contract belongs to the platform.
 *
 * Constants rather than literals, because the relay matches handlers on exact
 * equality: a typo would leave the event unhandled and quietly accumulating
 * attempts, with nothing pointing at the two strings that stopped agreeing.
 *
 * **`ModuleBoundaryTest` cannot enforce this one.** The first version of Plot
 * Armour read the constant from `availability.domain`, straight across a feature
 * boundary, and `featureModulesAreIndependent` passed -- because a Kotlin
 * `const val` is inlined by the compiler, so the bytecode ArchUnit reads contains
 * the string and no reference to the class it came from. A cross-module constant
 * is invisible to the rule that exists to forbid cross-module coupling, which
 * makes where these live a matter of discipline rather than enforcement.
 */
object OutboxEventTypes {
    /**
     * A title has stopped being available on a provider in a region.
     *
     * Past tense. The nightly diff sees a title that was on a service and now is
     * not; predicting a departure before it happens needs the removal-risk model
     * and the months of snapshot history it trains on.
     */
    const val AVAILABILITY_REMOVED = "availability.removed"
}
