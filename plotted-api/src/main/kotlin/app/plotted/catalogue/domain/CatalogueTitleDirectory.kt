package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.TitleSearchRepository
import app.plotted.platform.spi.TitleDirectory
import org.springframework.stereotype.Component
import java.util.UUID

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

    override fun findSummaries(titleIds: Collection<UUID>): List<TitleDirectory.TitleSummary> =
        titles.findSummaries(titleIds).map { title ->
            TitleDirectory.TitleSummary(
                titleId = title.id,
                mediaType = title.mediaType.dbValue,
                name = title.name,
                releaseYear = title.releaseDate?.year,
                posterUrl = title.posterUrl,
                // Resolved on this side of the boundary: whether runtime or
                // summed episode runtime applies depends on media type, and that
                // rule belongs to the catalogue. Handing over both fields would
                // invite the caller to pick, and eventually to pick wrong.
                watchMinutes = title.watchMinutes,
                communityRating = title.communityRating?.toDouble(),
            )
        }
}
