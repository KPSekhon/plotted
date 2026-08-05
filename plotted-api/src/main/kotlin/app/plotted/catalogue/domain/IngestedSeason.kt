package app.plotted.catalogue.domain

import java.time.LocalDate

/**
 * A season and its episodes, as retrieved from TMDB.
 *
 * These exist mainly so Plotted can answer "how long is this, really?". The
 * series endpoint offers an average episode length at best, and Tonight Mode's
 * time filter is a hard filter -- section 6.3 is explicit that "it fits" is a
 * promise and scoring must never trade it away. A promise built on an average is
 * not one worth making.
 */
data class IngestedSeason(
    val externalId: String,
    val seasonNumber: Int,
    val name: String?,
    val airDate: LocalDate?,
    val episodes: List<IngestedEpisode>,
) {
    /**
     * TMDB puts specials in season 0. They are stored, because someone browsing
     * a show should see them, but excluded from "how long to watch this" -- a
     * viewer asking whether they can finish a series is not counting the
     * Christmas special.
     */
    val isSpecials: Boolean get() = seasonNumber == 0

    val knownRuntimeMinutes: Int? get() = episodes.mapNotNull { it.runtimeMinutes }.takeIf { it.isNotEmpty() }?.sum()
}

data class IngestedEpisode(
    val externalId: String,
    val episodeNumber: Int,
    val name: String?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val airDate: LocalDate?,
)
