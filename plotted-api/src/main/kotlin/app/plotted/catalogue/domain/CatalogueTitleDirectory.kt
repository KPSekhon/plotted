package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.TitleSearchRepository
import app.plotted.platform.spi.TitleDirectory
import org.springframework.stereotype.Component

/**
 * Catalogue's side of the [TitleDirectory] contract.
 *
 * Thin on purpose. Its whole job is to keep the catalogue's own types on the
 * catalogue's side of the boundary, so that changing `MediaType` or
 * `CatalogueTitle` cannot ripple into the availability module.
 */
@Component
class CatalogueTitleDirectory(
    private val titles: TitleSearchRepository,
) : TitleDirectory {
    override fun findDueForAvailabilityRefresh(regionCode: String, limit: Int): List<TitleDirectory.TitleRef> =
        titles.findDueForAvailabilityRefresh(regionCode, limit).map { due ->
            TitleDirectory.TitleRef(
                titleId = due.titleId,
                mediaType = due.mediaType.dbValue,
                externalId = due.externalId,
            )
        }
}
