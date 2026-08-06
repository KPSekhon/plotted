package app.plotted.availability.domain

import app.plotted.availability.persistence.ProviderRepository
import org.springframework.stereotype.Service

/**
 * Read access to the provider list.
 *
 * A pass-through today, and present for the same reason [AvailabilityQueryService]
 * is: no controller talks to a repository directly, so when the provider list
 * starts needing region filtering it becomes a change in one class.
 */
@Service
class ProviderCatalogueService(
    private val providers: ProviderRepository,
) {
    fun subscribable(): List<ProviderListing> = providers.findSubscribable()
}
