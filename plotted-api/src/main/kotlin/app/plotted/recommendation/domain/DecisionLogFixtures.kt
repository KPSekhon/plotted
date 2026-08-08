package app.plotted.recommendation.domain

import app.plotted.platform.spi.RecommendationFixtures
import app.plotted.recommendation.persistence.RecommendationLogRepository
import org.springframework.stereotype.Component

/**
 * Lets `demo` write decision-log rows without importing from `recommendation`.
 *
 * Nothing crosses this boundary except a decision and its timestamps. What a
 * propensity is, how a served item is stored, and which columns phase 7 will
 * later read all stay in this module — which is the same arrangement the taste
 * fixture uses, and for the same reason: the demo owns the *story*, the feature
 * owns the *representation*.
 */
@Component
class DecisionLogFixtures(
    private val log: RecommendationLogRepository,
) : RecommendationFixtures {

    override fun recordDemoDecision(decision: RecommendationFixtures.DemoDecision) {
        log.recordFixture(
            userId = decision.userId,
            regionCode = decision.regionCode,
            availableMinutes = decision.availableMinutes,
            accessPolicy = AccessPolicy.SUBSCRIBED_ONLY.dbValue,
            titleId = decision.titleId,
            requestedAt = decision.requestedAt,
            acceptedAt = decision.acceptedAt,
        )
    }
}
