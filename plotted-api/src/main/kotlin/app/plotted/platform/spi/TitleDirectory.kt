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

    /**
     * Titles with enough detail to place them on a taste axis, most popular first.
     *
     * Genres come with it, which nothing else here needs -- Pilot Season derives
     * its axes from them, and fetching them per title would turn building one
     * fifteen-question ladder into a query per candidate.
     *
     * Ordered by popularity because the ladder should ask about films someone has
     * plausibly heard of. A question about two titles the user does not recognise
     * gets skipped or, worse, guessed.
     */
    fun findForTasteProfiling(limit: Int): List<TitleProfile>

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

    /**
     * A title as a taste-profiling candidate.
     *
     * Separate from [TitleSummary] rather than an extension of it because it
     * carries genres and drops runtime -- the two are wanted by different callers
     * for different reasons, and one struct answering both would make every
     * watchlist render pay for a genre join it never reads.
     */
    data class TitleProfile(
        val titleId: UUID,
        val name: String,
        /** `movie` or `series`, as stored. A String so no enum crosses the boundary. */
        val mediaType: String,
        val releaseYear: Int?,
        val communityRating: Double?,
        val posterUrl: String?,
        /**
         * Genre names as TMDB gives them. Empty when the title has none recorded,
         * which contributes nothing to the genre-derived axes rather than being
         * guessed -- the same rule the rankers follow for a missing feature.
         */
        val genres: Set<String>,
    )
}
