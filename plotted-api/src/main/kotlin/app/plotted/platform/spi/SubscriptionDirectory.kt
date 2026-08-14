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
     *
     * Each plan states its own [PriceProvenance] and callers are expected to
     * read it. Today every row is `REFERENCE`, so in practice nothing here may
     * be optimised against at all — which is the point. Returning the price
     * anyway is deliberate: it is still the right figure to show on a screen and
     * to pre-fill a form with, and withholding it would push callers towards
     * inventing one.
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
        /**
         * Where [monthlyCents] came from. Holding a subscription does not make
         * its price confirmed: the repository reads
         * `COALESCE(actual_price, provider_plans.price)`, so one the user never
         * priced quietly adopts the researched list figure.
         */
        val priceProvenance: PriceProvenance,
    )

    data class Plan(
        val providerId: UUID,
        val providerName: String,
        val planName: String,
        val monthlyCents: Long,
        val priceProvenance: PriceProvenance,
    )

    /**
     * How far a price can be trusted, and therefore what may be done with it.
     *
     * A trust boundary rather than a display detail. Cancel Culture turns a
     * price into a recommendation to cancel a service, so a figure nobody
     * confirmed becomes financial advice the moment it enters the objective —
     * and unlike a wrong ranking, a wrong price does not look wrong.
     *
     * A published list price is not the same number as somebody's bill. Legacy
     * rates, student pricing, bundles, promotional periods, annual plans and
     * family arrangements all move it, and they all move it *down*, so
     * optimising against list prices systematically overstates what cancelling
     * would save.
     */
    enum class PriceProvenance {
        /** The user told us what they pay. The best source available. */
        USER_ENTERED,

        /**
         * Plotted checked it against a live source, with a date. Nothing
         * produces this yet — there is no pricing ingestion — and the value
         * exists so the eventual one has somewhere to land.
         */
        VERIFIED,

        /**
         * Researched from a published source, per `docs/seed/provider-plans.md`.
         * Fine to display, fine to pre-fill a form with, never to optimise
         * against.
         */
        REFERENCE,
        ;

        /**
         * Whether the optimiser may spend this number.
         *
         * A property of the provenance rather than a condition at the call
         * site, so a new value has to answer the question rather than falling
         * through whichever branch happened to be written last — and the
         * failure mode of that branch is spending money nobody confirmed.
         */
        val mayBeOptimisedAgainst: Boolean get() = this == USER_ENTERED || this == VERIFIED

        companion object {
            fun fromDb(value: String): PriceProvenance = when (value) {
                "reference" -> REFERENCE
                "verified" -> VERIFIED
                else -> error("Unknown price_provenance '$value'")
            }
        }
    }
}
