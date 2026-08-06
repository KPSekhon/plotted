package app.plotted.platform.spi

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
}
