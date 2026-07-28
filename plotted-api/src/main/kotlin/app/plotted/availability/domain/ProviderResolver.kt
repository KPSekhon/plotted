package app.plotted.availability.domain

import app.plotted.availability.persistence.ProviderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Collapses TMDB's provider list onto services a user can actually subscribe to.
 *
 * TMDB reports billing variants and reseller channels as separate providers, so a
 * single title comes back as "Crave" and "Crave Amazon Channel", or as five
 * different Paramount Plus entries. Those are one catalogue bought several ways.
 *
 * Stored as-is they would inflate watchlist coverage, and coverage is the primary
 * input to the subscription optimiser -- so Cancel Culture would recommend
 * keeping a service on the strength of titles it had counted twice, or "cover" a
 * title through a subscription that does not independently exist. This is a
 * correctness problem in the money-facing feature, which is why it is handled
 * here rather than being left for the UI to tidy up.
 */
@Service
class ProviderResolver(
    private val providers: ProviderRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The mapping changes only when a migration adds rows, so it is loaded once.
     * [refresh] exists for the ingestion job to call after a seed update rather
     * than requiring a restart.
     */
    @Volatile
    private var aliases: Map<Int, Provider>? = null

    fun resolve(raw: List<RawProviderOffer>): ProviderResolution {
        val map = aliasMap()

        val mapped = mutableListOf<ProviderOffer>()
        val unmapped = mutableListOf<RawProviderOffer>()

        raw.forEach { offer ->
            val provider = map[offer.tmdbProviderId]
            if (provider == null) {
                unmapped += offer
            } else {
                mapped += ProviderOffer(
                    provider = provider,
                    accessType = offer.accessType,
                    sourceTmdbProviderId = offer.tmdbProviderId,
                    sourceName = offer.providerName,
                )
            }
        }

        if (unmapped.isNotEmpty()) {
            // Not a silent drop: a mapping gap removes real availability, which
            // surfaces as a title Plotted believes nobody carries.
            log.warn(
                "Unmapped TMDB providers, availability discarded: {}",
                unmapped.joinToString { "${it.tmdbProviderId} (${it.providerName})" },
            )
        }

        return ProviderResolution(offers = collapse(mapped), unmapped = unmapped)
    }

    fun refresh() {
        aliases = null
    }

    private fun aliasMap(): Map<Int, Provider> = aliases ?: providers.loadAliasMap().also {
        aliases = it
        log.info("Loaded {} TMDB provider aliases", it.size)
    }

    /**
     * Removes the duplication, in two steps.
     *
     * First, several TMDB entries can now name the same provider and access type
     * -- "Crave" and "Crave Amazon Channel" both as subscription -- and that is
     * one offer. The `direct` entry wins where there is one, so the retained
     * provenance points at the service rather than at a reseller.
     *
     * Second, a provider occasionally appears under more than one *included*
     * access type: TMDB really does list Amazon Prime Video as both subscription
     * and free for some titles. Those cannot both be true, so the most demanding
     * one is kept. Claiming something is free when it needs a subscription
     * destroys trust in a way the reverse does not.
     *
     * Rent and buy are left alone. The same storefront legitimately offers both
     * at different prices, and they are genuinely different transactions.
     */
    private fun collapse(offers: List<ProviderOffer>): List<ProviderOffer> {
        val bySlot = offers.groupBy { it.provider.id to it.accessType }
            .mapValues { (_, candidates) -> candidates.minByOrNull { rankOf(it) }!! }
            .values

        val (included, transactional) = bySlot.partition { it.accessType.isIncluded }

        val strongestIncluded = included
            .groupBy { it.provider.id }
            .map { (_, candidates) -> candidates.maxByOrNull { demandOf(it.accessType) }!! }

        return (strongestIncluded + transactional).sortedWith(
            compareBy({ it.provider.name }, { it.accessType.ordinal }),
        )
    }

    /** Lower is preferred: keep the service's own listing over a reseller's. */
    private fun rankOf(offer: ProviderOffer): Int = if (offer.sourceName.equals(offer.provider.name, ignoreCase = true)) 0 else 1

    /** Higher demands more of the user, so it is the safer claim to keep. */
    private fun demandOf(accessType: AccessType): Int = when (accessType) {
        AccessType.SUBSCRIPTION -> 3
        AccessType.ADS -> 2
        AccessType.FREE -> 1
        else -> 0
    }
}
