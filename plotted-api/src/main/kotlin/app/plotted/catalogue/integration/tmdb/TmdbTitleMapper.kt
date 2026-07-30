package app.plotted.catalogue.integration.tmdb

import app.plotted.catalogue.domain.IngestedTitle
import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.MetadataStatus
import app.plotted.catalogue.domain.MovieDetails
import app.plotted.catalogue.domain.SeriesDetails
import app.plotted.catalogue.domain.TitleSearchResult
import app.plotted.platform.integration.tmdb.TmdbMovieDetail
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.integration.tmdb.TmdbSearchPage
import app.plotted.platform.integration.tmdb.TmdbSeriesDetail
import app.plotted.platform.integration.tmdb.parseTmdbDate
import org.springframework.stereotype.Component

/**
 * The single place TMDB's title shape becomes Plotted's.
 *
 * Everything here is pure: no I/O, no clock, no database. That is what makes the
 * awkward cases -- an empty release date, a series with no runtime, a search
 * result that turns out to be a person -- cheap to test exhaustively.
 */
@Component
class TmdbTitleMapper(
    private val properties: TmdbProperties,
) {
    fun toIngestedTitle(movie: TmdbMovieDetail): IngestedTitle = IngestedTitle(
        externalId = movie.id.toString(),
        mediaType = MediaType.MOVIE,
        name = movie.title,
        originalName = movie.originalTitle?.takeIf { it != movie.title },
        overview = movie.overview?.takeIf { it.isNotBlank() },
        releaseDate = parseTmdbDate(movie.releaseDate),
        originalLanguage = movie.originalLanguage,
        posterUrl = properties.posterUrl(movie.posterPath),
        backdropUrl = properties.backdropUrl(movie.backdropPath),
        popularityScore = movie.popularity,
        communityRating = movie.voteAverage,
        voteCount = movie.voteCount,
        genreIds = movie.genres.map { it.id },
        movie = MovieDetails(runtimeMinutes = movie.runtime?.takeIf { it > 0 }),
        series = null,
        // Runtime is a hard filter in Tonight Mode, so a film without one is
        // not complete no matter how much else arrived.
        metadataStatus =
        if (movie.runtime != null && movie.runtime > 0) MetadataStatus.COMPLETE else MetadataStatus.PARTIAL,
    )

    fun toIngestedTitle(series: TmdbSeriesDetail): IngestedTitle {
        val averageEpisodeMinutes = averageEpisodeMinutes(series.episodeRunTime)
        val episodeCount = series.numberOfEpisodes?.takeIf { it > 0 }
        return IngestedTitle(
            externalId = series.id.toString(),
            mediaType = MediaType.SERIES,
            name = series.name,
            originalName = series.originalName?.takeIf { it != series.name },
            overview = series.overview?.takeIf { it.isNotBlank() },
            releaseDate = parseTmdbDate(series.firstAirDate),
            originalLanguage = series.originalLanguage,
            posterUrl = properties.posterUrl(series.posterPath),
            backdropUrl = properties.backdropUrl(series.backdropPath),
            popularityScore = series.popularity,
            communityRating = series.voteAverage,
            voteCount = series.voteCount,
            genreIds = series.genres.map { it.id },
            movie = null,
            series = SeriesDetails(
                status = series.status,
                firstAirDate = parseTmdbDate(series.firstAirDate),
                lastAirDate = parseTmdbDate(series.lastAirDate),
                // TMDB counts specials as season 0; excluding it would be wrong
                // in the other direction, so the upstream count is kept as-is
                // and season 0 is handled when seasons are ingested.
                seasonCount = series.numberOfSeasons?.takeIf { it > 0 },
                episodeCount = episodeCount,
                averageEpisodeMinutes = averageEpisodeMinutes,
                totalRuntimeMinutes = estimateTotalRuntime(episodeCount, averageEpisodeMinutes),
            ),
            metadataStatus =
            if (averageEpisodeMinutes != null && episodeCount != null) {
                MetadataStatus.COMPLETE
            } else {
                MetadataStatus.PARTIAL
            },
        )
    }

    /**
     * `search/multi` returns people alongside titles. They are dropped rather
     * than mapped to something meaningless, as are entries with no usable name.
     */
    fun toSearchResults(page: TmdbSearchPage): List<TitleSearchResult> = page.results.mapNotNull { result ->
        val mediaType =
            when (result.mediaType) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.SERIES
                else -> null
            } ?: return@mapNotNull null
        val name = result.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

        TitleSearchResult(
            externalId = result.id.toString(),
            mediaType = mediaType,
            name = name,
            releaseDate = parseTmdbDate(result.displayDate),
            overview = result.overview?.takeIf { it.isNotBlank() },
            posterUrl = properties.posterUrl(result.posterPath),
            popularityScore = result.popularity,
        )
    }

    /**
     * TMDB reports a list because anthologies and shows with specials vary.
     * The mean is used rather than the first entry: a show listing 30 and 60
     * should not be treated as a half-hour show when deciding whether it fits a
     * viewing window. Zeroes are placeholders upstream, not real runtimes.
     */
    private fun averageEpisodeMinutes(runtimes: List<Int>): Int? {
        val usable = runtimes.filter { it > 0 }
        if (usable.isEmpty()) return null
        return Math.round(usable.average()).toInt()
    }

    private fun estimateTotalRuntime(episodeCount: Int?, averageEpisodeMinutes: Int?): Int? {
        if (episodeCount == null || averageEpisodeMinutes == null) return null
        return episodeCount * averageEpisodeMinutes
    }
}
