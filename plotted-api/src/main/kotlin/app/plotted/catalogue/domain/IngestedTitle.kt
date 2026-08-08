package app.plotted.catalogue.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class MediaType(
    val dbValue: String,
) {
    MOVIE("movie"),
    SERIES("series"),
    ;

    companion object {
        fun fromDb(value: String): MediaType = entries.firstOrNull { it.dbValue == value } ?: error("Unknown media_type '$value'")
    }
}

/**
 * How complete the stored metadata is.
 *
 * Worth tracking rather than inferring: a title added from a search result has a
 * name and a poster but no runtime, and runtime is a hard filter in Tonight
 * Mode. Serving a stub as though it were complete would silently drop it from
 * every time-constrained recommendation.
 */
enum class MetadataStatus(
    val dbValue: String,
) {
    /** Created from a search result or a watchlist add; details not fetched yet. */
    STUB("stub"),

    /** Detail fetched, but something the scorer wants is missing upstream. */
    PARTIAL("partial"),

    COMPLETE("complete"),

    /** Detail fetch failed permanently, e.g. the title was removed from TMDB. */
    FAILED("failed"),
    ;

    companion object {
        fun fromDb(value: String): MetadataStatus = entries.firstOrNull { it.dbValue == value } ?: error("Unknown metadata_status '$value'")
    }
}

/**
 * A title as retrieved from TMDB and ready to be stored, before it has a Plotted
 * identifier.
 *
 * This is deliberately a separate type from the wire DTOs: the mapping from
 * TMDB's shape to Plotted's happens once, in one place, so a change upstream
 * touches the mapper rather than the repository, the scorer and the API.
 */
data class IngestedTitle(
    val externalId: String,
    val mediaType: MediaType,
    val name: String,
    val originalName: String?,
    val overview: String?,
    val releaseDate: LocalDate?,
    val originalLanguage: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val popularityScore: BigDecimal?,
    val communityRating: BigDecimal?,
    val voteCount: Int?,
    /** TMDB genre ids, which are the primary key of `genres`. No translation table. */
    val genreIds: List<Int>,
    val movie: MovieDetails?,
    val series: SeriesDetails?,
    val metadataStatus: MetadataStatus,
) {
    init {
        require((mediaType == MediaType.MOVIE) == (movie != null)) {
            "A movie must carry MovieDetails and nothing else may"
        }
        require((mediaType == MediaType.SERIES) == (series != null)) {
            "A series must carry SeriesDetails and nothing else may"
        }
    }
}

data class MovieDetails(
    val runtimeMinutes: Int?,
)

data class SeriesDetails(
    val status: String?,
    val firstAirDate: LocalDate?,
    val lastAirDate: LocalDate?,
    val seasonCount: Int?,
    val episodeCount: Int?,
    val averageEpisodeMinutes: Int?,
    /**
     * Denormalised so commitment scoring and the optimiser's capacity constraint
     * can read it off a candidate row (spec section 14.3).
     *
     * Estimated as episodes x average episode length until the episode records
     * are ingested, at which point the nightly refresh replaces it with the sum
     * of actual runtimes. An estimate is right often enough to rank with and is
     * far better than a null that removes the title from consideration.
     */
    val totalRuntimeMinutes: Int?,
)

/** One result from a TMDB search, before the details are fetched. */
data class TitleSearchResult(
    val externalId: String,
    val mediaType: MediaType,
    val name: String,
    val releaseDate: LocalDate?,
    val overview: String?,
    val posterUrl: String?,
    val popularityScore: BigDecimal?,
)

/**
 * A title as Plotted has it stored.
 *
 * Distinct from [IngestedTitle], which is what arrives from upstream. This is
 * what comes back out: it carries a Plotted identifier and flattens the
 * film-versus-series split into the few fields every caller actually reads.
 */
data class CatalogueTitle(
    val id: UUID,
    val mediaType: MediaType,
    val name: String,
    val originalName: String?,
    val overview: String?,
    val releaseDate: LocalDate?,
    val posterUrl: String?,
    val popularityScore: BigDecimal?,
    val communityRating: BigDecimal?,
    val metadataStatus: MetadataStatus,
    val runtimeMinutes: Int?,
    val totalRuntimeMinutes: Int?,
    val averageEpisodeMinutes: Int?,
    val episodeCount: Int?,
) {
    /**
     * The whole thing, start to finish: a film's runtime, or every episode of a
     * series added up.
     *
     * A **commitment** figure. It belongs where somebody is deciding whether to
     * take a series on at all — the title page, the watchlist — and it is
     * emphatically not the answer to "will this fit tonight".
     */
    val watchMinutes: Int? get() = if (mediaType == MediaType.MOVIE) runtimeMinutes else totalRuntimeMinutes

    /**
     * How long **one sitting** is: a film, or a typical episode.
     *
     * What a time-constrained request has to measure against, and getting this
     * wrong was a real defect rather than a refinement. Tonight Mode filtered on
     * [watchMinutes], so a series was judged by its total — One Piece is 472
     * hours and therefore never fitted an evening, and neither did any other
     * multi-season series. Somebody with a list of half-hour comedies asking for
     * forty-five minutes was told *everything on your list is longer than the
     * time you have*: the product failing its one promise, in the commonest case
     * there is.
     *
     * Nobody watches a series in one sitting. They watch an episode.
     *
     * The average rather than a specific episode, because Plotted does not know
     * which episode you are on — `watchlist_items` carries a coarse status and
     * no position. So the claim is about a *typical* episode, and the interface
     * says "per episode" rather than quoting a length as though it were exact.
     */
    val sessionMinutes: Int? get() = if (mediaType == MediaType.MOVIE) runtimeMinutes else averageEpisodeMinutes

    /**
     * Ranking may still use a title without a runtime; a time-constrained
     * request must not, because "it fits" would be a guess.
     */
    val hasUsableRuntime: Boolean get() = watchMinutes != null
}
