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
    }
}
