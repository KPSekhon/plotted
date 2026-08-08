package app.plotted.recommendation.persistence

import app.plotted.generated.jooq.tables.references.RECOMMENDATION_ITEMS
import app.plotted.generated.jooq.tables.references.RECOMMENDATION_REQUESTS
import app.plotted.recommendation.domain.Recommendation
import app.plotted.recommendation.domain.TonightContext
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Writes the decision log.
 *
 * This is the table phase 7 reads and phase 11 reports on, and neither can be
 * built retrospectively — a decision not recorded tonight is gone. So logging
 * runs on every request including the ones that returned nothing, because "the
 * constraints excluded everything" is one of the more interesting things a
 * recommender can be doing repeatedly.
 */
@Repository
class RecommendationLogRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun record(userId: UUID, context: TonightContext, outcome: Recommendation, latencyMs: Int, rankerVersion: String): UUID {
        val requestId = UUID.randomUUID()
        val (candidateCount, eligibleCount, outcomeValue, rejections) = when (outcome) {
            is Recommendation.Served -> Summary(outcome.candidateCount, outcome.eligibleCount, "served", null)
            is Recommendation.NothingFits -> Summary(
                outcome.candidateCount,
                0,
                "nothing_fit",
                outcome.reasons.mapKeys { it.key.name },
            )
        }

        dsl.insertInto(RECOMMENDATION_REQUESTS)
            .set(RECOMMENDATION_REQUESTS.ID, requestId)
            .set(RECOMMENDATION_REQUESTS.USER_ID, userId)
            .set(RECOMMENDATION_REQUESTS.REQUESTED_AT, OffsetDateTime.now(clock))
            .set(RECOMMENDATION_REQUESTS.REGION_CODE, context.regionCode)
            .set(RECOMMENDATION_REQUESTS.AVAILABLE_MINUTES, context.availableMinutes)
            .set(RECOMMENDATION_REQUESTS.ACCESS_POLICY, context.accessPolicy.dbValue)
            .set(RECOMMENDATION_REQUESTS.CANDIDATE_COUNT, candidateCount)
            .set(RECOMMENDATION_REQUESTS.ELIGIBLE_COUNT, eligibleCount)
            .set(RECOMMENDATION_REQUESTS.OUTCOME, outcomeValue)
            .set(
                RECOMMENDATION_REQUESTS.REJECTION_SUMMARY,
                rejections?.let { JSONB.valueOf(objectMapper.writeValueAsString(it)) },
            )
            .set(RECOMMENDATION_REQUESTS.RANKER_VERSION, rankerVersion)
            .set(RECOMMENDATION_REQUESTS.LATENCY_MS, latencyMs)
            .execute()

        if (outcome is Recommendation.Served) {
            outcome.picks.forEachIndexed { index, pick ->
                // A propensity of zero would make every importance-weighted
                // estimate in phase 7 divide by zero. The database rejects it
                // too, but failing here names the policy that produced it rather
                // than surfacing as a constraint violation three layers away.
                require(pick.propensity > 0.0) {
                    "Propensity must be positive to be usable for off-policy evaluation, was ${pick.propensity}"
                }

                dsl.insertInto(RECOMMENDATION_ITEMS)
                    .set(RECOMMENDATION_ITEMS.ID, UUID.randomUUID())
                    .set(RECOMMENDATION_ITEMS.REQUEST_ID, requestId)
                    .set(RECOMMENDATION_ITEMS.TITLE_ID, pick.candidate.titleId)
                    .set(RECOMMENDATION_ITEMS.POSITION, (index + 1).toShort())
                    .set(RECOMMENDATION_ITEMS.SCORE, pick.score.toBigDecimal(SCORE_SCALE))
                    .set(RECOMMENDATION_ITEMS.EXPLORATION, pick.exploration)
                    .set(RECOMMENDATION_ITEMS.PROPENSITY, pick.propensity.toBigDecimal(PROPENSITY_SCALE))
                    .set(
                        RECOMMENDATION_ITEMS.FEATURE_CONTRIBUTIONS,
                        JSONB.valueOf(
                            objectMapper.writeValueAsString(
                                pick.features.contributions().associate {
                                    it.feature.name to mapOf("value" to it.value, "share" to it.share)
                                },
                            ),
                        ),
                    )
                    .execute()
            }
        }

        logger.debug("Recorded recommendation {} for user {} ({})", requestId, userId, outcomeValue)
        return requestId
    }

    /**
     * Writes one manufactured decision at explicit timestamps.
     *
     * **Fixture path, demo accounts only.** See `RecommendationFixtures` for
     * what that forbids. It exists because [record] stamps `requested_at` from
     * the clock, and a log written entirely at signup can only ever report that
     * every acceptance is too recent to judge — which is the correct answer and
     * a useless demonstration.
     *
     * Kept beside [record] rather than in a test fixture because it writes the
     * same two tables and has to keep agreeing with them. A copy living
     * somewhere else would drift the first time a column was added, and the
     * failure would show up as a demo screen quietly missing a number.
     *
     * The propensity is 1.0 and the score is nominal. Neither is used by End
     * Credits, and inventing a plausible-looking propensity would put a number
     * into the column phase 7 divides by — which is precisely the column that
     * must never contain anything nobody computed.
     */
    fun recordFixture(
        userId: UUID,
        regionCode: String,
        availableMinutes: Int?,
        accessPolicy: String,
        titleId: UUID?,
        requestedAt: OffsetDateTime,
        acceptedAt: OffsetDateTime?,
    ): UUID {
        val requestId = UUID.randomUUID()
        val served = titleId != null

        dsl.insertInto(RECOMMENDATION_REQUESTS)
            .set(RECOMMENDATION_REQUESTS.ID, requestId)
            .set(RECOMMENDATION_REQUESTS.USER_ID, userId)
            .set(RECOMMENDATION_REQUESTS.REQUESTED_AT, requestedAt)
            .set(RECOMMENDATION_REQUESTS.REGION_CODE, regionCode)
            .set(RECOMMENDATION_REQUESTS.AVAILABLE_MINUTES, availableMinutes)
            .set(RECOMMENDATION_REQUESTS.ACCESS_POLICY, accessPolicy)
            .set(RECOMMENDATION_REQUESTS.CANDIDATE_COUNT, FIXTURE_CANDIDATES)
            .set(RECOMMENDATION_REQUESTS.ELIGIBLE_COUNT, if (served) FIXTURE_ELIGIBLE else 0)
            .set(RECOMMENDATION_REQUESTS.OUTCOME, if (served) "served" else "nothing_fit")
            .set(
                RECOMMENDATION_REQUESTS.REJECTION_SUMMARY,
                if (served) null else JSONB.valueOf("""{"TOO_LONG":$FIXTURE_CANDIDATES}"""),
            )
            .set(RECOMMENDATION_REQUESTS.RANKER_VERSION, FIXTURE_RANKER_VERSION)
            .set(RECOMMENDATION_REQUESTS.LATENCY_MS, FIXTURE_LATENCY_MS)
            .execute()

        if (titleId != null) {
            dsl.insertInto(RECOMMENDATION_ITEMS)
                .set(RECOMMENDATION_ITEMS.ID, UUID.randomUUID())
                .set(RECOMMENDATION_ITEMS.REQUEST_ID, requestId)
                .set(RECOMMENDATION_ITEMS.TITLE_ID, titleId)
                .set(RECOMMENDATION_ITEMS.POSITION, 1.toShort())
                .set(RECOMMENDATION_ITEMS.SCORE, BigDecimal.ZERO.setScale(SCORE_SCALE))
                .set(RECOMMENDATION_ITEMS.EXPLORATION, false)
                .set(RECOMMENDATION_ITEMS.PROPENSITY, BigDecimal.ONE.setScale(PROPENSITY_SCALE))
                .set(RECOMMENDATION_ITEMS.FEATURE_CONTRIBUTIONS, JSONB.valueOf("{}"))
                .set(RECOMMENDATION_ITEMS.ACCEPTED_AT, acceptedAt)
                .execute()
        }

        return requestId
    }

    /**
     * Marks one served item as the one the user chose.
     *
     * The `EXISTS` is what makes ownership a property of the query rather than a
     * check somebody has to remember: accepting another user's recommendation, or
     * a title that was not among the picks offered, matches no rows and returns
     * false. Nothing here trusts the caller to have verified either.
     *
     * Only the first acceptance counts. A second one -- a double tap, a retried
     * request -- leaves the original timestamp alone, because the decision
     * latency being measured is the time to *decide*, and the second click is not
     * a second decision.
     */
    fun accept(userId: UUID, requestId: UUID, titleId: UUID): Boolean = dsl.update(RECOMMENDATION_ITEMS)
        .set(RECOMMENDATION_ITEMS.ACCEPTED_AT, OffsetDateTime.now(clock))
        .where(RECOMMENDATION_ITEMS.REQUEST_ID.eq(requestId))
        .and(RECOMMENDATION_ITEMS.TITLE_ID.eq(titleId))
        .and(RECOMMENDATION_ITEMS.ACCEPTED_AT.isNull)
        .andExists(
            dsl.selectOne()
                .from(RECOMMENDATION_REQUESTS)
                .where(RECOMMENDATION_REQUESTS.ID.eq(requestId))
                .and(RECOMMENDATION_REQUESTS.USER_ID.eq(userId)),
        )
        .execute() > 0

    private fun Double.toBigDecimal(scale: Int): BigDecimal = BigDecimal.valueOf(this).setScale(scale, RoundingMode.HALF_UP)

    private data class Summary(
        val candidateCount: Int,
        val eligibleCount: Int,
        val outcome: String,
        val rejections: Map<String, Int>?,
    )

    private companion object {
        const val SCORE_SCALE = 5
        const val PROPENSITY_SCALE = 7

        /**
         * Shapes for [recordFixture], named so nothing plausible-looking gets
         * typed inline and later mistaken for something that was computed.
         */
        const val FIXTURE_CANDIDATES = 12
        const val FIXTURE_ELIGIBLE = 9
        const val FIXTURE_LATENCY_MS = 24

        /**
         * Deliberately not `linear-v1`. Phase 7 must never pool rows from two
         * scoring functions, and manufactured rows did not come from a scoring
         * function at all — so they are stamped with a version no analysis will
         * match, and an accidental inclusion shows up as an unknown ranker
         * rather than as quietly worse numbers.
         */
        const val FIXTURE_RANKER_VERSION = "demo-fixture"
    }
}
