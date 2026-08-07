package app.plotted.preferences.persistence

import app.plotted.generated.jooq.tables.references.PILOT_COMPARISONS
import app.plotted.preferences.domain.AnsweredComparison
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pilot Season's answers.
 *
 * Two reads and one write, and the interesting part is what the read leaves out:
 * [comparisonsForFitting] returns only rows that carry a choice. A skipped
 * question is stored — that is how the ladder knows not to ask it again — and it
 * is not evidence, so it must never reach the fitter. Filtering it here rather
 * than at the call site means a future caller cannot forget.
 */
@Repository
class PilotRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Records an answer, or a skip when [chosenTitleId] is null.
     *
     * Idempotent on the pair. Somebody double-tapping an answer, or a client
     * retrying a request whose response was lost, must not produce two rows: the
     * fit counts rows, so a duplicate would be the same person's single opinion
     * counted twice, weighted twice, and reported with a tighter interval than
     * the evidence supports. That is a worse failure than a rejected retry,
     * because nothing about it looks wrong.
     *
     * The unique index is on the *normalised* pair, so answering (A, B) also
     * settles (B, A).
     */
    fun record(
        userId: UUID,
        axis: String,
        leftTitleId: UUID,
        rightTitleId: UUID,
        chosenTitleId: UUID?,
        attributeDifference: DoubleArray?,
    ): Boolean = try {
        dsl.insertInto(PILOT_COMPARISONS)
            .set(PILOT_COMPARISONS.ID, UUID.randomUUID())
            .set(PILOT_COMPARISONS.USER_ID, userId)
            .set(PILOT_COMPARISONS.AXIS, axis)
            .set(PILOT_COMPARISONS.LEFT_TITLE_ID, leftTitleId)
            .set(PILOT_COMPARISONS.RIGHT_TITLE_ID, rightTitleId)
            .set(PILOT_COMPARISONS.CHOSEN_TITLE_ID, chosenTitleId)
            .set(
                PILOT_COMPARISONS.ATTRIBUTE_DIFFERENCE,
                attributeDifference?.let { JSONB.valueOf(objectMapper.writeValueAsString(it)) },
            )
            .set(PILOT_COMPARISONS.ANSWERED_AT, OffsetDateTime.now(clock))
            .execute() > 0
    } catch (_: DuplicateKeyException) {
        // Already answered. The first answer stands, for the same reason the
        // first block does: it is the one the person actually meant.
        false
    }

    /**
     * Pairs this user has already been shown, normalised so the order the titles
     * appeared in does not matter.
     *
     * Includes skipped pairs. A question someone declined should not come back
     * on the next request — that is a questionnaire arguing with them.
     */
    fun settledPairs(userId: UUID): Set<Set<UUID>> = dsl.select(
        PILOT_COMPARISONS.LEFT_TITLE_ID,
        PILOT_COMPARISONS.RIGHT_TITLE_ID,
    )
        .from(PILOT_COMPARISONS)
        .where(PILOT_COMPARISONS.USER_ID.eq(userId))
        .fetch()
        .mapTo(mutableSetOf()) {
            setOf(it[PILOT_COMPARISONS.LEFT_TITLE_ID]!!, it[PILOT_COMPARISONS.RIGHT_TITLE_ID]!!)
        }

    /**
     * The evidence, oldest first.
     *
     * Skipped rows are excluded here rather than by the caller. A forced choice
     * between two titles somebody has not seen is a coin flip, and recording one
     * as a preference is worse than a shorter questionnaire — so a skip has to be
     * incapable of reaching the fit, not merely unlikely to.
     */
    fun comparisonsForFitting(userId: UUID): List<AnsweredComparison> = dsl.select(
        PILOT_COMPARISONS.AXIS,
        PILOT_COMPARISONS.CHOSEN_TITLE_ID,
        PILOT_COMPARISONS.ATTRIBUTE_DIFFERENCE,
        PILOT_COMPARISONS.ANSWERED_AT,
    )
        .from(PILOT_COMPARISONS)
        .where(PILOT_COMPARISONS.USER_ID.eq(userId))
        .and(PILOT_COMPARISONS.CHOSEN_TITLE_ID.isNotNull)
        .orderBy(PILOT_COMPARISONS.ANSWERED_AT.asc())
        .fetch()
        .map {
            AnsweredComparison(
                axis = it[PILOT_COMPARISONS.AXIS]!!,
                chosenTitleId = it[PILOT_COMPARISONS.CHOSEN_TITLE_ID]!!,
                attributeDifference = objectMapper.readValue(
                    it[PILOT_COMPARISONS.ATTRIBUTE_DIFFERENCE]!!.data(),
                    DoubleArray::class.java,
                ),
                answeredAt = it[PILOT_COMPARISONS.ANSWERED_AT]!!.toInstant(),
            )
        }

    /** How many questions have been settled, answered and skipped alike. */
    fun settledCount(userId: UUID): Int = dsl.fetchCount(PILOT_COMPARISONS, PILOT_COMPARISONS.USER_ID.eq(userId))

    /** Discards everything this user has answered, so they can start again. */
    fun reset(userId: UUID): Int = dsl.deleteFrom(PILOT_COMPARISONS)
        .where(PILOT_COMPARISONS.USER_ID.eq(userId))
        .execute()
}
