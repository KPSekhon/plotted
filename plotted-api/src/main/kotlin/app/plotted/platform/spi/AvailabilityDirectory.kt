package app.plotted.platform.spi

import java.util.UUID

/**
 * What other modules are allowed to know about where titles can be watched.
 *
 * The coverage dashboard has to answer "which service covers the most of this
 * watchlist?", which needs an availability lookup over a set of titles at once.
 * Rather than let `watchlist` reach into `availability`, the shared kernel
 * declares the interface and `availability` implements it -- the same shape as
 * [TitleDirectory], for the same reason.
 *
 * Note what is deliberately missing: price, deep link, confidence and
 * last-verified time. Coverage is a question about *whether* a title is
 * included in a subscription, and every field beyond that is one the caller
 * would be tempted to display without its provenance. Anything that displays an
 * offer goes through the availability API, which carries the full record.
 */
interface AvailabilityDirectory {
    /**
     * Subscription-included offers for each of [titleIds], keyed by title.
     *
     * Only subscription-like access counts. A title you can rent for $6.99 is
     * not covered by a service you pay for monthly, and folding rentals in would
     * make every service look better than it is -- inflating exactly the number
     * the cancellation optimiser will later minimise cost against.
     *
     * Titles with no known offer are absent from the map rather than present
     * with an empty list. The distinction matters: [unknownTitleIds] separates
     * "we checked and nothing carries it" from "we have never checked", and the
     * dashboard must not report the second as though it were the first.
     */
    fun subscriptionCoverage(titleIds: Collection<UUID>, regionCode: String): Coverage

    data class Coverage(
        val byTitle: Map<UUID, List<ProviderRef>>,
        /** Titles with no availability record at all in this region. */
        val unknownTitleIds: Set<UUID>,
    )

    data class ProviderRef(
        val providerId: UUID,
        val name: String,
        val slug: String,
        val logoUrl: String?,
    )
}
