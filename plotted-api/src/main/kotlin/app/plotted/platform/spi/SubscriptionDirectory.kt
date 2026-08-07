package app.plotted.platform.spi

import java.time.LocalDate
import java.util.UUID

/**
 * What other modules are allowed to know about what the user pays for.
 *
 * The recommender needs it to answer "can they actually watch this tonight
 * without paying again?", which is the difference between a suggestion and a
 * sales pitch. See ADR 0008 for why this is an interface in the shared kernel
 * rather than a direct dependency on `subscriptions`.
 */
interface SubscriptionDirectory {
    /**
     * Providers the user is currently paying for, including free trials.
     *
     * A trial counts: it provides access tonight, which is the only question
     * being asked here. Cancelled and lapsed subscriptions do not, however much
     * of the month is left on them — treating a cancelled service as available
     * is how a recommender sends someone to a paywall.
     */
    fun activeProviderIds(userId: UUID): Set<UUID>

    /**
     * What the user currently pays, per provider, with any commitment attached.
     *
     * The optimiser needs the money and the lock-in, not just the membership:
     * a service that cannot be cancelled for three more months is a constraint,
     * and one costing $23.99 is a different decision from one costing $5.99.
     */
    fun currentSubscriptions(userId: UUID, today: LocalDate): List<Held>

    /**
     * Services the user could switch *to*, with their list price.
     *
     * Sourced from `provider_plans`, which is researched rather than verified
     * (see `docs/seed/provider-plans.md`). Where the user has told us what they
     * actually pay, [currentSubscriptions] overrides this — a grandfathered rate
     * is the real number and the optimiser must minimise against it.
     */
    fun availablePlans(regionCode: String): List<Plan>

    data class Held(
        val providerId: UUID,
        /**
         * Carried here because a held service may have no plan row at all — the
         * user told us what they pay for something `provider_plans` has never
         * priced. The optimiser can still reason about it, and a plan that says
         * "cancel this" has to be able to name it.
         */
        val providerName: String,
        val monthlyCents: Long,
        /** Months from today during which this cannot be cancelled. Zero when free to cancel. */
        val committedMonths: Int,
    )

    data class Plan(
        val providerId: UUID,
        val providerName: String,
        val planName: String,
        val monthlyCents: Long,
    )
}
