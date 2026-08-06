package app.plotted.platform.spi

import java.util.UUID

/**
 * What other modules are allowed to know about titles.
 *
 * The nightly refresh has to answer "which titles are most overdue a check?",
 * which is a query rather than something an event can push. Rather than let
 * `availability` reach into `catalogue`, the shared kernel declares the
 * interface, `catalogue` implements it, and the caller depends only on this.
 *
 * That is the "published interface" half of section 13's rule -- events cover
 * things that happened, this covers things that need asking. Deliberately
 * minimal, and no domain types, so neither module's model leaks through it.
 */
interface TitleDirectory {
    fun findDueForAvailabilityRefresh(regionCode: String, limit: Int): List<TitleRef>

    /**
     * Enough about each title to render it in a list someone else owns.
     *
     * Batched rather than per-id because the caller is always drawing a
     * collection -- a watchlist, a coverage breakdown -- and the per-id shape of
     * this method is what decides whether that screen costs one query or fifty.
     *
     * Unknown ids are absent from the result rather than returned as blanks. A
     * watchlist row whose title has been deleted is a real state, and the caller
     * has to decide what to do about it; inventing an empty title here would
     * hide that decision.
     */
    fun findSummaries(titleIds: Collection<UUID>): List<TitleSummary>

    data class TitleRef(
        val titleId: UUID,
        /** `movie` or `series`, as stored. A String so no enum crosses the boundary. */
        val mediaType: String,
        val externalId: String,
    )

    data class TitleSummary(
        val titleId: UUID,
        /** `movie` or `series`, as stored. A String so no enum crosses the boundary. */
        val mediaType: String,
        val name: String,
        val releaseYear: Int?,
        val posterUrl: String?,
        /**
         * Runtime for a film, summed episode runtime for a series -- already
         * resolved, because which of the two applies is the catalogue's business
         * and getting it wrong is how a time filter starts lying.
         */
        val watchMinutes: Int?,
        /**
         * Community rating out of 10, or null when nobody has rated it.
         *
         * Null rather than zero, and the distinction is load-bearing: an unrated
         * film is not a film everyone hated, and scoring it as one would push
         * every obscure title down every list.
         */
        val communityRating: Double?,
    )
}
