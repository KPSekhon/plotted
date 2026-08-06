package app.plotted.availability.domain

import app.plotted.availability.persistence.AvailabilityRepository
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.AvailabilityDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Availability's side of the [AvailabilityDirectory] contract.
 *
 * Thin on purpose, like `CatalogueTitleDirectory`: its job is to keep
 * [AccessType], [Provider] and the rest of this module's model on this side of
 * the boundary.
 *
 * The one judgement it makes is which access types count as coverage, and that
 * belongs here because it is a fact about availability rather than about
 * watchlists. It reuses [AccessType.isIncluded] rather than restating the list,
 * so a new access type cannot mean one thing to the title page and another to
 * the dashboard.
 */
@Component
class AvailabilityCoverageDirectory(
    private val availability: AvailabilityRepository,
    private val properties: TmdbProperties,
) : AvailabilityDirectory {
    override fun subscriptionCoverage(titleIds: Collection<UUID>, regionCode: String): AvailabilityDirectory.Coverage {
        val wanted = titleIds.distinct()
        if (wanted.isEmpty()) {
            return AvailabilityDirectory.Coverage(byTitle = emptyMap(), unknownTitleIds = emptySet())
        }

        val region = regionCode.ifBlank { properties.region }
        val rows = availability.findActiveForTitles(wanted, region)

        val byTitle = rows
            .filter { it.accessType.isIncluded }
            .groupBy { it.titleId }
            .mapValues { (_, offers) ->
                offers
                    // Grouped by provider, because one provider can carry a
                    // title under more than one included access type -- with ads
                    // and without. That is one covered title, not two, and
                    // counting it twice would overstate whichever service
                    // happens to list it the most ways.
                    .groupBy { it.provider.id }
                    .map { (_, forProvider) ->
                        val first = forProvider.first()
                        AvailabilityDirectory.ProviderRef(
                            providerId = first.provider.id,
                            name = first.provider.name,
                            slug = first.provider.slug,
                            logoUrl = first.providerLogoUrl,
                            // Free if *any* included offer on this provider is
                            // free or ad-supported. Taking the first offer's
                            // access type instead would make the answer depend
                            // on row order.
                            isFree = forProvider.any {
                                it.accessType == AccessType.FREE || it.accessType == AccessType.ADS
                            },
                        )
                    }
                    .sortedBy { it.name }
            }

        // Checked-and-empty is not the same as never-checked. A title whose only
        // offers are rentals is genuinely uncovered; a title with no rows at all
        // is unknown, and the dashboard has to be able to say which it is rather
        // than quietly scoring both as zero.
        val everChecked = rows.mapTo(mutableSetOf()) { it.titleId }
        return AvailabilityDirectory.Coverage(
            byTitle = byTitle,
            unknownTitleIds = wanted.filterNot(everChecked::contains).toSet(),
        )
    }
}
