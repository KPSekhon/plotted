package app.plotted.platform.spi

import java.util.UUID

/**
 * Episodes, for the modules that track a user's place in a series.
 *
 * `watchlist` owns where somebody has got to and `catalogue` owns what the
 * episodes are, so the question "what comes after S1 E7" crosses a feature
 * boundary and does it through here rather than by importing. See ADR 0008.
 *
 * ### Two rules baked into every method, because they are the same rules
 *
 * **Season 0 does not count.** TMDB files specials there.
 * `SeasonRepository.recalculateTotalRuntime` already excludes them from the
 * runtime a series is judged by, and excluding them here keeps one answer to
 * "how far through am I": otherwise "next episode" steps out of the main run
 * into a Christmas special and back again, and the remaining count disagrees
 * with the total the rest of the product quotes.
 *
 * **An episode that has not aired is not next.** A series caught up to its
 * broadcast has no next episode, and saying otherwise sends someone to look for
 * something that does not exist yet. An episode with no air date at all is
 * treated as available, because a missing date is a gap in Plotted's data and
 * refusing on it would hide most older series.
 */
interface EpisodeDirectory {
    /**
     * Whether this series has an episode at this position.
     *
     * Progress is stored as a position rather than a foreign key (see V19), so
     * this is what stops somebody recording that they finished season nine of a
     * three-season show.
     */
    fun episodeExists(seriesTitleId: UUID, seasonNumber: Int, episodeNumber: Int): Boolean

    /**
     * The first aired episode after [afterSeason]/[afterEpisode], or the first
     * episode of the series when both are null.
     *
     * Null means there is nothing to watch next: either the series is finished,
     * or everything remaining is unaired.
     */
    fun nextEpisode(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?): Episode?

    /**
     * How many aired episodes remain after this position, and how long they run.
     *
     * Both figures are about *what is left*, which is the number a person needs
     * when deciding whether they can finish something before it leaves a
     * service. The total runtime the rest of the product quotes is the whole
     * series; these two are the remainder of it.
     */
    fun remaining(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?): Remaining

    data class Episode(
        val episodeId: UUID,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String?,
        /**
         * This episode's own runtime, or null when upstream never gave one.
         *
         * Null rather than the series' typical episode, deliberately. The caller
         * decides whether to fall back, and it has to know that it is falling
         * back -- quietly substituting an average here would make a runtime
         * filter look precise about an episode nobody has measured.
         */
        val runtimeMinutes: Int?,
    )

    data class Remaining(
        val episodes: Int,
        /**
         * Minutes across those episodes, or null when none of them has a
         * runtime and there is nothing honest to add up.
         */
        val minutes: Int?,
    )
}
