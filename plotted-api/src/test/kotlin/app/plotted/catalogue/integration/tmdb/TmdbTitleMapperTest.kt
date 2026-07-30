package app.plotted.catalogue.integration.tmdb

import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.MetadataStatus
import app.plotted.platform.integration.tmdb.TmdbGenre
import app.plotted.platform.integration.tmdb.TmdbMovieDetail
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.integration.tmdb.TmdbSearchPage
import app.plotted.platform.integration.tmdb.TmdbSearchResult
import app.plotted.platform.integration.tmdb.TmdbSeriesDetail
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The mapper is pure, so the awkward upstream shapes can be enumerated cheaply.
 * Almost every case here is one TMDB actually produces.
 */
class TmdbTitleMapperTest {
    private val mapper = TmdbTitleMapper(TmdbProperties())

    @Test
    fun `maps a complete film`() {
        val mapped = mapper.toIngestedTitle(
            TmdbMovieDetail(
                id = 438631,
                title = "Dune",
                originalTitle = "Dune",
                overview = "Paul Atreides arrives on Arrakis.",
                releaseDate = "2021-09-15",
                runtime = 155,
                posterPath = "/p.jpg",
                genres = listOf(TmdbGenre(878, "Science Fiction")),
            ),
        )

        mapped.externalId shouldBe "438631"
        mapped.mediaType shouldBe MediaType.MOVIE
        mapped.releaseDate shouldBe LocalDate.of(2021, 9, 15)
        mapped.genreIds shouldContainExactly listOf(878)
        mapped.metadataStatus shouldBe MetadataStatus.COMPLETE
        // An original title identical to the title is noise, not information.
        mapped.originalName.shouldBeNull()
    }

    @Test
    fun `keeps the original title only when it differs`() {
        val mapped = mapper.toIngestedTitle(
            TmdbMovieDetail(id = 1, title = "Spirited Away", originalTitle = "千と千尋の神隠し", runtime = 125),
        )

        mapped.originalName shouldBe "千と千尋の神隠し"
    }

    @Test
    fun `treats an empty release date as absent instead of failing`() {
        // TMDB returns "" rather than null for unreleased titles.
        val mapped = mapper.toIngestedTitle(TmdbMovieDetail(id = 1, title = "Untitled", releaseDate = "", runtime = 100))

        mapped.releaseDate.shouldBeNull()
    }

    @Test
    fun `survives a malformed release date`() {
        val mapped =
            mapper.toIngestedTitle(TmdbMovieDetail(id = 1, title = "Broken", releaseDate = "2021-13-45", runtime = 100))

        // One bad date must not fail an ingestion run of thousands of titles.
        mapped.releaseDate.shouldBeNull()
    }

    @Test
    fun `a film without a runtime is partial, because runtime is a hard filter`() {
        val mapped = mapper.toIngestedTitle(TmdbMovieDetail(id = 1, title = "No Runtime", runtime = null))

        mapped.metadataStatus shouldBe MetadataStatus.PARTIAL
        mapped.movie?.runtimeMinutes.shouldBeNull()
    }

    @Test
    fun `a zero runtime is a placeholder upstream, not a real runtime`() {
        val mapped = mapper.toIngestedTitle(TmdbMovieDetail(id = 1, title = "Zero", runtime = 0))

        mapped.movie?.runtimeMinutes.shouldBeNull()
        mapped.metadataStatus shouldBe MetadataStatus.PARTIAL
    }

    @Test
    fun `estimates total series runtime from episode count and average length`() {
        val mapped = mapper.toIngestedTitle(
            TmdbSeriesDetail(
                id = 1,
                name = "Psych",
                firstAirDate = "2006-07-07",
                numberOfSeasons = 8,
                numberOfEpisodes = 121,
                episodeRunTime = listOf(44),
            ),
        )

        mapped.series?.averageEpisodeMinutes shouldBe 44
        mapped.series?.totalRuntimeMinutes shouldBe 121 * 44
        mapped.metadataStatus shouldBe MetadataStatus.COMPLETE
    }

    @Test
    fun `averages a mixed runtime list rather than taking the first entry`() {
        // A show listing 30 and 60 is not a half-hour show, and treating it as
        // one would let it be recommended into a window it cannot fit.
        val mapped = mapper.toIngestedTitle(
            TmdbSeriesDetail(id = 1, name = "Mixed", numberOfEpisodes = 10, episodeRunTime = listOf(30, 60)),
        )

        mapped.series?.averageEpisodeMinutes shouldBe 45
    }

    @Test
    fun `ignores zero-length entries in the runtime list`() {
        val mapped = mapper.toIngestedTitle(
            TmdbSeriesDetail(id = 1, name = "Padded", numberOfEpisodes = 10, episodeRunTime = listOf(0, 22, 0)),
        )

        mapped.series?.averageEpisodeMinutes shouldBe 22
    }

    @Test
    fun `leaves total runtime null when it cannot be estimated`() {
        val mapped = mapper.toIngestedTitle(
            TmdbSeriesDetail(id = 1, name = "Unknown", numberOfEpisodes = null, episodeRunTime = listOf(22)),
        )

        mapped.series?.totalRuntimeMinutes.shouldBeNull()
        mapped.metadataStatus shouldBe MetadataStatus.PARTIAL
    }

    @Test
    fun `drops people from a multi-search and keeps titles`() {
        val results = mapper.toSearchResults(
            TmdbSearchPage(
                results = listOf(
                    TmdbSearchResult(id = 1, mediaType = "movie", title = "Arrival", releaseDate = "2016-11-10"),
                    TmdbSearchResult(id = 2, mediaType = "person", name = "Denis Villeneuve"),
                    TmdbSearchResult(id = 3, mediaType = "tv", name = "Severance", firstAirDate = "2022-02-17"),
                ),
            ),
        )

        results.map { it.name } shouldContainExactly listOf("Arrival", "Severance")
        results.map { it.mediaType } shouldContainExactly listOf(MediaType.MOVIE, MediaType.SERIES)
        results[1].releaseDate shouldBe LocalDate.of(2022, 2, 17)
    }

    @Test
    fun `drops results with no usable name`() {
        val results = mapper.toSearchResults(
            TmdbSearchPage(results = listOf(TmdbSearchResult(id = 1, mediaType = "movie", title = "  "))),
        )

        results.shouldContainExactly(emptyList())
    }

    @Test
    fun `builds CDN urls at the widths the interface actually uses`() {
        val mapped = mapper.toIngestedTitle(
            TmdbMovieDetail(id = 1, title = "T", runtime = 90, posterPath = "/p.jpg", backdropPath = "/b.jpg"),
        )

        mapped.posterUrl shouldBe "https://image.tmdb.org/t/p/w500/p.jpg"
        mapped.backdropUrl shouldBe "https://image.tmdb.org/t/p/w1280/b.jpg"
    }

    @Test
    fun `leaves image urls null when TMDB has no artwork`() {
        val mapped = mapper.toIngestedTitle(TmdbMovieDetail(id = 1, title = "T", runtime = 90))

        mapped.posterUrl.shouldBeNull()
        mapped.backdropUrl.shouldBeNull()
    }
}
