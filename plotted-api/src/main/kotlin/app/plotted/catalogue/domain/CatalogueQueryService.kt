package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.TitleSearchRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Read access to the stored catalogue.
 *
 * It looks like a pass-through today, and that is fine: the point is that no
 * controller talks to a repository directly.
 *
 * Phase 3 arrived and these reads are still not user-scoped, which was not what
 * the earlier version of this comment predicted. Watchlists turned out not to
 * need it — a watchlist filters itself — and `blocked_titles` is a hard filter on
 * *recommendations* rather than on search, so it belongs with the rest of the
 * hard filters in phase 4. Someone searching the catalogue for a title they have
 * blocked should still find it; refusing to show it would look like a missing
 * catalogue entry rather than a preference being honoured.
 *
 * The seam stays because that is still the right place for it: when phase 4 adds
 * blocked titles to ranking, and phase 9 adds a preference profile, this is the
 * one class that changes rather than every controller that forgot it existed.
 */
@Service
class CatalogueQueryService(
    private val titles: TitleSearchRepository,
) {
    fun search(query: String, limit: Int, mediaType: MediaType?): List<CatalogueTitle> = titles.search(query, limit, mediaType)

    fun findById(titleId: UUID): CatalogueTitle? = titles.findById(titleId)
}
