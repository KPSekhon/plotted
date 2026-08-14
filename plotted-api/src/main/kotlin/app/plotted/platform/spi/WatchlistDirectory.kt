package app.plotted.platform.spi

import java.time.LocalDate
import java.util.UUID

/**
 * What other modules are allowed to know about someone's watchlist.
 *
 * The recommender's candidate set is "things this person said they want to
 * watch", which is the watchlist. Rather than let `recommendation` reach into
 * `watchlist`, the shared kernel declares the interface — the same arrangement
 * as [TitleDirectory] and [AvailabilityDirectory], and for the reasons set out
 * in ADR 0008.
 */
interface WatchlistDirectory {
    /**
     * Items the user is still waiting on, with the intent attached.
     *
     * Outstanding only. Something already watched is not a candidate for
     * tonight, and a recommender that suggests it has misunderstood the question
     * rather than merely ranked badly.
     */
    fun outstandingItems(userId: UUID): List<WatchlistEntry>

    /** Titles this user has explicitly blocked. A hard filter, never a penalty. */
    fun blockedTitleIds(userId: UUID): Set<UUID>

    /**
     * Where the user is in each of these series, and what is next.
     *
     * Keyed by title id; a series with no entry has nothing to say -- either it
     * is not a series, or it has no episodes the catalogue knows about.
     *
     * Called with a handful of ids rather than a whole watchlist, deliberately.
     * Resolving "what is next" costs a query per series, and doing it for every
     * candidate before filtering would put an N+1 on the endpoint with the
     * tightest latency budget in the product. Tonight asks only about the titles
     * it is actually going to show.
     */
    fun seriesProgress(userId: UUID, titleIds: Collection<UUID>): Map<UUID, NextUp>

    /**
     * Everyone still waiting on a title.
     *
     * The reverse of [outstandingItems], and it exists because Plot Armour asks
     * the question the other way round: a title has been detected leaving a
     * service, and the question is who cares.
     *
     * Outstanding only, and blocked titles are already excluded — so the two
     * suppressions that need no judgement are applied by the query. Somebody who
     * has finished a title does not need telling it is leaving, and somebody who
     * blocked it needs it even less.
     */
    fun watchersOf(titleId: UUID): List<Watcher>

    data class Watcher(
        val userId: UUID,
        /** 1 is the highest, 5 the lowest. */
        val priority: Int,
    )

    data class WatchlistEntry(
        val titleId: UUID,
        /** 1 is the highest, 5 the lowest. Restated because getting it backwards is silent. */
        val priority: Int,
        val desiredByDate: LocalDate?,
    )

    /**
     * The next episode of a series, and how much of it is left.
     *
     * A flat shape rather than a nested episode object, because it crosses a
     * module boundary and the recommender should depend on primitives rather
     * than on watchlist's model. See ADR 0008.
     */
    data class NextUp(
        val episodeId: UUID,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String?,
        /**
         * This episode's own runtime, or null when upstream never gave one.
         *
         * Never filled in from the series average. The caller already has the
         * typical episode and can decide to fall back; substituting one here
         * would make a specific episode's length look measured when it is not.
         */
        val runtimeMinutes: Int?,
        /** Whether anything has been finished. False means this is episode one. */
        val started: Boolean,
        val remainingEpisodes: Int,
    )
}
