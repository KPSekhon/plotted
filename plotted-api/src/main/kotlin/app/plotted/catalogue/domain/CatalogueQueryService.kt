package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.TitleSearchRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Read access to the stored catalogue.
 *
 * It looks like a pass-through today, and that is fine: the point is that no
 * controller talks to a repository directly. Catalogue data is not user-scoped
 * yet, but watchlists and blocked titles arrive in phase 3 and will filter these
 * reads. Having the seam already there means that becomes a change in one class
 * rather than a change in every controller that forgot it existed.
 */
@Service
class CatalogueQueryService(
    private val titles: TitleSearchRepository,
) {
    fun search(query: String, limit: Int, mediaType: MediaType?): List<CatalogueTitle> = titles.search(query, limit, mediaType)

    fun findById(titleId: UUID): CatalogueTitle? = titles.findById(titleId)
}
