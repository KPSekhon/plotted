package app.plotted.platform.spi

import java.util.UUID

/**
 * What the availability module is allowed to know about titles.
 *
 * The nightly refresh has to answer "which titles are most overdue a check?",
 * which is a query rather than something an event can push. Rather than let
 * `availability` reach into `catalogue`, the shared kernel declares the
 * interface, `catalogue` implements it, and `availability` depends only on this.
 *
 * That is the "published interface" half of section 13's rule -- events cover
 * things that happened, this covers things that need asking. Deliberately
 * minimal: three fields, no domain types, so neither module's model leaks
 * through it.
 */
interface TitleDirectory {
    fun findDueForAvailabilityRefresh(regionCode: String, limit: Int): List<TitleRef>

    data class TitleRef(
        val titleId: UUID,
        /** `movie` or `series`, as stored. A String so no enum crosses the boundary. */
        val mediaType: String,
        val externalId: String,
    )
}
