package app.plotted.platform.persistence

import app.plotted.generated.jooq.tables.references.OUTBOX
import app.plotted.platform.outbox.OutboxRecord
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The claim-and-settle half of the outbox.
 *
 * Kept apart from [OutboxRepository], which is the *writer* every domain
 * transaction calls. They touch one table and have opposite obligations: the
 * writer must run inside the caller's transaction, and everything here must run
 * in its own, so that recording a failure survives the failure.
 */
@Repository
class OutboxRelayRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * Leases up to [limit] due events to this worker.
     *
     * A lease rather than a lock, and the difference matters. The obvious version
     * is `SELECT ... FOR UPDATE SKIP LOCKED` on its own, but a row lock lasts
     * only as long as the transaction holding it -- so a relay that selects in one
     * transaction and then hands each event to a handler in another has already
     * released every lock before doing any work. Two instances would claim the
     * same rows and deliver everything twice, on every tick, while looking like
     * they were using `SKIP LOCKED` correctly.
     *
     * So the claim is an `UPDATE ... RETURNING` that pushes `next_attempt_at`
     * forward by [lease]. The inner `SELECT ... FOR UPDATE SKIP LOCKED` still
     * does its job -- it stops two workers colliding *during* the claim -- and the
     * lease is what keeps them apart afterwards. A worker that dies mid-batch
     * loses nothing: the lease expires and the event is claimed again, which is
     * the at-least-once behaviour handlers are already required to tolerate.
     *
     * The `ORDER BY id` in the subquery decides *which* rows are taken -- oldest
     * first, so nothing starves -- and says nothing about the order they come
     * back. `RETURNING` has no ordering guarantee at all, and Postgres returned
     * this batch reversed, which a test caught and an earlier version of this
     * comment cheerfully claimed otherwise about. The sort below is what actually
     * delivers events in the order they were written.
     *
     * Across concurrent relays that ordering is per-batch rather than global,
     * which is the honest limit of a poller and why handlers may not assume a
     * total order.
     */
    fun claim(limit: Int, lease: Duration): List<OutboxRecord> = dsl.fetch(
        // Every bind is cast explicitly. In plain SQL jOOQ has no target column to
        // infer a type from, so an OffsetDateTime crosses as `character varying`
        // and Postgres refuses to compare it to a `timestamptz` -- which is
        // exactly the failure phase 3 recorded, in a test fixture, under the
        // heading "write fixtures the same way the repository writes". This is
        // the same bug from the other end: the repository written the way the
        // fixture was. The typed DSL avoids it everywhere it can be used, and
        // this statement cannot use it because `SKIP LOCKED` has no DSL form here.
        //
        // Only the temporal binds actually needed it -- the other plain SQL in
        // this codebase binds strings, UUIDs and ints, which jOOQ maps
        // unambiguously and which have been running in CI since phase 2. The
        // casts on `limit` are belt and braces; the ones on the timestamps are
        // the fix.
        """
            UPDATE outbox
               SET next_attempt_at = ?::timestamptz
             WHERE id IN (
                   SELECT id
                     FROM outbox
                    WHERE published_at IS NULL
                      AND next_attempt_at <= ?::timestamptz
                    ORDER BY id
                    LIMIT ?::int
                      FOR UPDATE SKIP LOCKED
                   )
          RETURNING id, aggregate_type, aggregate_id, event_type, payload, created_at, attempts
        """.trimIndent(),
        OffsetDateTime.now(clock).plus(lease),
        OffsetDateTime.now(clock),
        limit,
    ).map { record ->
        OutboxRecord(
            id = record.get("id", Long::class.java),
            aggregateType = record.get("aggregate_type", String::class.java),
            aggregateId = record.get("aggregate_id", UUID::class.java),
            eventType = record.get("event_type", String::class.java),
            payload = objectMapper.readValue(
                record.get("payload", org.jooq.JSONB::class.java).data(),
                object : TypeReference<Map<String, Any?>>() {},
            ),
            createdAt = record.get("created_at", OffsetDateTime::class.java).toInstant(),
            attempts = record.get("attempts", Int::class.java),
        )
    }.sortedBy { it.id }

    /** Marks an event delivered. `published_at` is the only thing that stops it being claimed again. */
    fun markPublished(id: Long) {
        dsl.update(OUTBOX)
            .set(OUTBOX.PUBLISHED_AT, OffsetDateTime.now(clock))
            .set(OUTBOX.ATTEMPTS, OUTBOX.ATTEMPTS.plus(1))
            .where(OUTBOX.ID.eq(id))
            .execute()
    }

    /**
     * Records a failure and schedules the retry.
     *
     * The error is truncated rather than stored whole: a stack trace in a
     * database column is how one poisoned event turns into a table nobody can
     * read, and the useful part is always at the front.
     */
    fun markFailed(id: Long, error: String, retryIn: Duration) {
        dsl.update(OUTBOX)
            .set(OUTBOX.ATTEMPTS, OUTBOX.ATTEMPTS.plus(1))
            .set(OUTBOX.LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .set(OUTBOX.NEXT_ATTEMPT_AT, OffsetDateTime.now(clock).plus(retryIn))
            .where(OUTBOX.ID.eq(id))
            .execute()
    }

    /**
     * Events that have failed enough times to be worth a person looking.
     *
     * Counted rather than hidden. A relay that quietly parks its failures is a
     * relay that reports healthy while dropping work, which is the failure this
     * codebase keeps finding in other shapes.
     */
    fun stuckCount(attemptThreshold: Int): Int = dsl.fetchCount(
        OUTBOX,
        OUTBOX.PUBLISHED_AT.isNull.and(OUTBOX.ATTEMPTS.ge(attemptThreshold)),
    )

    /** Unpublished and not yet at the threshold: ordinary backlog. */
    fun pendingCount(): Int = dsl.fetchCount(OUTBOX, OUTBOX.PUBLISHED_AT.isNull)

    private companion object {
        const val MAX_ERROR_LENGTH = 1_000
    }
}
