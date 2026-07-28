package app.plotted.availability.integration.tmdb

import app.plotted.availability.domain.AccessType
import app.plotted.availability.domain.RawProviderOffer
import app.plotted.platform.integration.tmdb.TmdbProvider
import app.plotted.platform.integration.tmdb.TmdbRegionProviders
import org.springframework.stereotype.Component

/**
 * Flattens one region's watch providers into a neutral list, so provider
 * canonicalisation never has to know about TMDB's payload shape.
 */
@Component
class TmdbOfferMapper {
    /**
     * A null region is not an error: it means nobody carries the title there,
     * which is a fact worth recording rather than a failure.
     */
    fun toRawOffers(region: TmdbRegionProviders?): List<RawProviderOffer> {
        if (region == null) return emptyList()
        return buildList {
            addAll(region.flatrate.map { it.toOffer(AccessType.SUBSCRIPTION) })
            addAll(region.free.map { it.toOffer(AccessType.FREE) })
            addAll(region.ads.map { it.toOffer(AccessType.ADS) })
            addAll(region.rent.map { it.toOffer(AccessType.RENT) })
            addAll(region.buy.map { it.toOffer(AccessType.BUY) })
        }
    }

    private fun TmdbProvider.toOffer(accessType: AccessType) = RawProviderOffer(
        tmdbProviderId = providerId,
        providerName = providerName,
        accessType = accessType,
    )
}
