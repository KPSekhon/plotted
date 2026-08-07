package app.plotted.analytics.persistence

import app.plotted.generated.jooq.tables.references.RECOMMENDATION_ITEMS
import app.plotted.generated.jooq.tables.references.RECOMMENDATION_REQUESTS
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The decision log, read back.
 *
 * Joins `recommendation_items` to `watchlist_items` across a feature boundary in
 * SQL, which ADR 0008 allows and this is the case it was written for: the two
 * tables live in one database, and answering "did they finish what they
 * accepted" in application code would mean paging the whole log into memory to
 * do a join Postgres already does well.
 */
@Repository
class EndCreditsRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /**
     * Time between being offered picks and choosing one, per acceptance.
     *
     * Returns every accepted item's latency without filtering, because deciding
     * what counts as a real decision is a judgement and judgements belong in the
     * domain where they can be named and tested rather than in a `WHERE` clause
     * nobody reads.
     */
    fun acceptanceLatencies(userId: UUID): List<Duration> = dsl.select(
        RECOMMENDATION_REQUESTS.REQUESTED_AT,
        RECOMMENDATION_ITEMS.ACCEPTED_AT,
    )
        .from(RECOMMENDATION_ITEMS)
        .join(RECOMMENDATION_REQUESTS).on(RECOMMENDATION_REQUESTS.ID.eq(RECOMMENDATION_ITEMS.REQUEST_ID))
        .where(RECOMMENDATION_REQUESTS.USER_ID.eq(userId))
        .and(RECOMMENDATION_ITEMS.ACCEPTED_AT.isNotNull)
        .fetch()
        .map {
            Duration.between(
                it[RECOMMENDATION_REQUESTS.REQUESTED_AT]!!.toInstant(),
                it[RECOMMENDATION_ITEMS.ACCEPTED_AT]!!.toInstant(),
            )
        }

    /**
     * Accepted picks, split by whether they were finished and whether they have
     * had time to be.
     *
     * The completion test is `completed_at >= accepted_at`, not merely "the
     * watchlist item is completed". Without the comparison a title somebody had
     * already finished and re-accepted would count as a success caused by the
     * recommendation, which it plainly was not.
     */
    fun completionOfAccepted(userId: UUID, maturity: Duration): Completion {
        val matureBefore = OffsetDateTime.now(clock).minus(maturity)

        val rows = dsl.select(
            RECOMMENDATION_ITEMS.ACCEPTED_AT,
            WATCHLIST_ITEMS.COMPLETED_AT,
        )
            .from(RECOMMENDATION_ITEMS)
            .join(RECOMMENDATION_REQUESTS).on(RECOMMENDATION_REQUESTS.ID.eq(RECOMMENDATION_ITEMS.REQUEST_ID))
            // Left, so an accepted pick the user later removed from their list
            // still appears -- as unfinished, which is what it is. An inner join
            // would drop it and quietly raise the rate by deleting its own
            // negative cases.
            .leftJoin(WATCHLISTS).on(WATCHLISTS.USER_ID.eq(RECOMMENDATION_REQUESTS.USER_ID))
            .leftJoin(WATCHLIST_ITEMS)
            .on(WATCHLIST_ITEMS.WATCHLIST_ID.eq(WATCHLISTS.ID))
            .and(WATCHLIST_ITEMS.TITLE_ID.eq(RECOMMENDATION_ITEMS.TITLE_ID))
            .where(RECOMMENDATION_REQUESTS.USER_ID.eq(userId))
            .and(RECOMMENDATION_ITEMS.ACCEPTED_AT.isNotNull)
            .fetch()

        var completed = 0
        var judged = 0
        var tooRecent = 0

        rows.forEach { row ->
            val acceptedAt = row[RECOMMENDATION_ITEMS.ACCEPTED_AT]!!
            val completedAt = row[WATCHLIST_ITEMS.COMPLETED_AT]
            val finished = completedAt != null && !completedAt.isBefore(acceptedAt)

            when {
                // Finished counts however recently it was accepted. Waiting out
                // the maturity window on something already watched would understate
                // the rate for no reason -- the window exists to stop *unfinished*
                // recent picks being scored as failures.
                finished -> {
                    completed++
                    judged++
                }

                acceptedAt.isAfter(matureBefore) -> tooRecent++
                else -> judged++
            }
        }

        return Completion(completed = completed, judged = judged, tooRecentToJudge = tooRecent)
    }

    /** Requests that produced picks, and requests that produced a diagnosis. */
    fun requestCounts(userId: UUID): RequestCounts {
        val served = dsl.fetchCount(
            RECOMMENDATION_REQUESTS,
            RECOMMENDATION_REQUESTS.USER_ID.eq(userId).and(RECOMMENDATION_REQUESTS.OUTCOME.eq("served")),
        )
        val nothingFit = dsl.fetchCount(
            RECOMMENDATION_REQUESTS,
            RECOMMENDATION_REQUESTS.USER_ID.eq(userId).and(RECOMMENDATION_REQUESTS.OUTCOME.eq("nothing_fit")),
        )
        return RequestCounts(served = served, nothingFit = nothingFit)
    }

    data class Completion(val completed: Int, val judged: Int, val tooRecentToJudge: Int)

    data class RequestCounts(val served: Int, val nothingFit: Int)
}
