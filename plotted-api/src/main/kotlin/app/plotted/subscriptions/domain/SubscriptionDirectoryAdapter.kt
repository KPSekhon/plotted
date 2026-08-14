package app.plotted.subscriptions.domain

import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.subscriptions.persistence.SubscriptionRepository
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Subscriptions' side of the [SubscriptionDirectory] contract.
 *
 * The judgements it makes — which statuses count as "currently paying", and how
 * long a commitment still has to run — belong here rather than in the callers,
 * because they are facts about subscriptions. It reuses
 * [SubscriptionStatus.isCurrent] rather than restating the list, so a new status
 * cannot mean one thing on the subscriptions screen and another to the
 * optimiser. See ADR 0008.
 */
@Component
class SubscriptionDirectoryAdapter(
    private val subscriptions: SubscriptionRepository,
) : SubscriptionDirectory {
    override fun activeProviderIds(userId: UUID): Set<UUID> = subscriptions.findForUser(userId)
        .filter { it.status.isCurrent }
        .mapTo(mutableSetOf()) { it.providerId }

    override fun currentSubscriptions(userId: UUID, today: LocalDate): List<SubscriptionDirectory.Held> = subscriptions.findForUser(userId)
        .filter { it.status.isCurrent }
        .map { subscription ->
            SubscriptionDirectory.Held(
                providerId = subscription.providerId,
                providerName = subscription.providerName,
                monthlyCents = subscription.monthlyCost.movePointRight(2).toLong(),
                committedMonths = committedMonths(subscription, today),
                priceProvenance = subscription.priceProvenance,
            )
        }

    /**
     * How many months of lock-in remain.
     *
     * `cannotCancel` without a date is treated as locked for the whole horizon
     * rather than as unlocked: the user has said they cannot cancel, and
     * guessing that they can is the error with the worse consequence. Rounded
     * up, because a commitment ending mid-month is a commitment that month.
     */
    private fun committedMonths(subscription: Subscription, today: LocalDate): Int {
        val endsOn = subscription.commitmentEndsOn
        return when {
            endsOn != null && endsOn.isAfter(today) ->
                (ChronoUnit.MONTHS.between(today.withDayOfMonth(1), endsOn.withDayOfMonth(1)) + 1).toInt()
            subscription.cannotCancel -> INDEFINITE_COMMITMENT_MONTHS
            else -> 0
        }
    }

    override fun availablePlans(regionCode: String): List<SubscriptionDirectory.Plan> = subscriptions.findCurrentPlans(regionCode)
        // Cheapest tier per provider: the optimiser decides whether a
        // *service* earns its place, and which tier to buy is the user's
        // call. Offering three Netflix rows would triple the search space
        // to answer a question nobody asked.
        .groupBy { it.providerId }
        .mapNotNull { (_, plans) -> plans.minByOrNull { it.monthlyCents } }
        .map {
            SubscriptionDirectory.Plan(
                providerId = it.providerId,
                providerName = it.providerName,
                planName = it.planName,
                monthlyCents = it.monthlyCents,
                priceProvenance = it.priceProvenance,
            )
        }
        .sortedBy { it.providerName }

    private companion object {
        /**
         * Long enough to cover any horizon the optimiser plans over, so a
         * `cannotCancel` flag with no end date never gets quietly ignored.
         */
        const val INDEFINITE_COMMITMENT_MONTHS = 120
    }
}
