package app.plotted.subscriptions.domain

import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.subscriptions.persistence.SubscriptionRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Subscriptions' side of the [SubscriptionDirectory] contract.
 *
 * The judgement it makes — which statuses count as "currently paying" — belongs
 * here rather than in the recommender, because it is a fact about subscriptions.
 * It reuses [SubscriptionStatus.isCurrent] rather than restating the list, so a
 * new status cannot mean one thing on the subscriptions screen and another to
 * the ranker. See ADR 0008.
 */
@Component
class SubscriptionDirectoryAdapter(
    private val subscriptions: SubscriptionRepository,
) : SubscriptionDirectory {
    override fun activeProviderIds(userId: UUID): Set<UUID> = subscriptions.findForUser(userId)
        .filter { it.status.isCurrent }
        .mapTo(mutableSetOf()) { it.providerId }
}
