package app.plotted.catalogue.integration.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

/**
 * TMDB wire formats.
 *
 * Every type ignores unknown properties on purpose: TMDB adds fields regularly,
 * and an ingestion job that fails because a new key appeared is worse than one
 * that ignores it. Only the fields Plotted actually stores are modelled, so this
 * file also documents exactly how much of the upstream payload is retained.
 *
 * Dates arrive as empty strings for unreleased titles rather than as null, which
 * is why they are [String] here and parsed defensively in [TmdbMapper].
 */

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenre(
    val id: Int,
    val name: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenreList(
    val genres: List<TmdbGenre> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val originalLanguage: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val popularity: BigDecimal? = null,
    val voteAverage: BigDecimal? = null,
    val voteCount: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeriesDetail(
    val id: Int,
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    /** A list because anthologies and specials vary; usually one entry. */
    val episodeRunTime: List<Int> = emptyList(),
    val originalLanguage: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val popularity: BigDecimal? = null,
    val voteAverage: BigDecimal? = null,
    val voteCount: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeasonSummary(
    val id: Int,
    val seasonNumber: Int,
    val name: String? = null,
    val episodeCount: Int? = null,
    val airDate: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSearchPage(
    val page: Int = 1,
    val totalPages: Int = 0,
    val totalResults: Int = 0,
    val results: List<TmdbSearchResult> = emptyList(),
)

/**
 * `search/multi` mixes movies, series and people in one array, distinguished
 * only by `media_type`. People are filtered out during mapping.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSearchResult(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: BigDecimal? = null,
) {
    /** `title` for films, `name` for series. */
    val displayName: String? get() = title ?: name

    val displayDate: String? get() = releaseDate ?: firstAirDate
}

/**
 * `/watch/providers` is keyed by ISO-3166-1 region code. Plotted only ever reads
 * the Canadian entry, and its absence is itself meaningful: it means nobody
 * streams this title in Canada.
 *
 * Powered by JustWatch, which requires attribution and prohibits redistributing
 * the data as a dataset.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbWatchProviderResponse(
    val id: Int? = null,
    val results: Map<String, TmdbRegionProviders> = emptyMap(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbRegionProviders(
    /** A JustWatch landing page, not a provider deep link. */
    val link: String? = null,
    /** Included with a subscription. */
    val flatrate: List<TmdbProvider> = emptyList(),
    val rent: List<TmdbProvider> = emptyList(),
    val buy: List<TmdbProvider> = emptyList(),
    /** Free with advertising. */
    val ads: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList(),
) {
    val isEmpty: Boolean
        get() = flatrate.isEmpty() && rent.isEmpty() && buy.isEmpty() && ads.isEmpty() && free.isEmpty()
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbProvider(
    val providerId: Int,
    val providerName: String,
    val logoPath: String? = null,
    val displayPriority: Int? = null,
)

/**
 * TMDB returns an empty string rather than null for a date it does not have, and
 * occasionally a malformed one. A single bad date must not fail an ingestion
 * run, so it becomes null and the row is stored without it.
 */
internal fun parseTmdbDate(value: String?): LocalDate? = value?.takeIf { it.isNotBlank() }?.let {
    runCatching { LocalDate.parse(it) }.getOrNull()
}
