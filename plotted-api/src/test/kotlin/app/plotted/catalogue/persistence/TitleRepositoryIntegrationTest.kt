package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedTitle
import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.MetadataStatus
import app.plotted.catalogue.domain.MovieDetails
import app.plotted.catalogue.domain.SeriesDetails
import app.plotted.generated.jooq.tables.references.MOVIES
import app.plotted.generated.jooq.tables.references.SERIES
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.TITLE_GENRES
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ingestion re-runs constantly: a nightly refresh, a second user adding a title
 * the first already added, a resumed import. "Run it twice" therefore has to be
 * a non-event, and idempotence is the property actually under test here.
 *
 * Runs against a real PostgreSQL 16 because the interesting parts are the
 * `ON CONFLICT` behaviour, the seeded genre foreign key, and the constraints
 * that the jOOQ generator never sees.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class TitleRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: TitleRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `stores a film with its genre links`() {
        val title = movie(name = "Arrival", runtime = 116, genreIds = listOf(878, 18))

        val result = repository.upsert(title)

        result.created shouldBe true
        result.unknownGenreIds.shouldContainExactlyInAnyOrder(emptyList())

        val stored = dsl.selectFrom(TITLES).where(TITLES.ID.eq(result.titleId)).fetchOne()!!
        stored.name shouldBe "Arrival"
        stored.mediaType shouldBe "movie"
        stored.externalSource shouldBe "tmdb"
        stored.metadataStatus shouldBe "complete"

        runtimeOf(result.titleId) shouldBe 116
        genreIdsOf(result.titleId).shouldContainExactlyInAnyOrder(listOf<Short>(878, 18))
    }

    @Test
    fun `re-ingesting the same title updates it in place rather than duplicating it`() {
        val first = repository.upsert(movie(name = "Arrival", runtime = 116, externalId = "TMDB-DUP"))
        val second = repository.upsert(movie(name = "Arrival (2016)", runtime = 118, externalId = "TMDB-DUP"))

        second.titleId shouldBe first.titleId
        second.created shouldBe false

        dsl.fetchCount(TITLES, TITLES.EXTERNAL_ID.eq("TMDB-DUP")) shouldBe 1
        val stored = dsl.selectFrom(TITLES).where(TITLES.ID.eq(first.titleId)).fetchOne()!!
        stored.name shouldBe "Arrival (2016)"
        runtimeOf(first.titleId) shouldBe 118
    }

    @Test
    fun `a refresh preserves when Plotted first saw the title`() {
        val result = repository.upsert(movie(name = "Past Lives", runtime = 105, externalId = "TMDB-CREATED"))
        val createdAt = dsl.select(TITLES.CREATED_AT).from(TITLES).where(TITLES.ID.eq(result.titleId)).fetchOne()!!.value1()

        repository.upsert(movie(name = "Past Lives", runtime = 106, externalId = "TMDB-CREATED"))

        val after = dsl.select(TITLES.CREATED_AT, TITLES.UPDATED_AT)
            .from(TITLES).where(TITLES.ID.eq(result.titleId)).fetchOne()!!
        after.value1() shouldBe createdAt
        after.value2() shouldNotBe null
    }

    @Test
    fun `genre links that TMDB has dropped are removed, not left behind`() {
        val externalId = "TMDB-REGENRE"
        val created = repository.upsert(movie(externalId = externalId, genreIds = listOf(878, 18, 53)))
        genreIdsOf(created.titleId).size shouldBe 3

        // Re-genred upstream. A stale link quietly biases genre affinity for
        // every user who has this title on a watchlist.
        repository.upsert(movie(externalId = externalId, genreIds = listOf(878)))

        genreIdsOf(created.titleId).shouldContainExactlyInAnyOrder(listOf<Short>(878))
    }

    @Test
    fun `an unseeded genre id is skipped and reported instead of failing the ingestion`() {
        // 99999 is not in TMDB's genre list, so it is not in the seed. Failing
        // the whole title over one link would be the worse outcome.
        val result = repository.upsert(movie(externalId = "TMDB-BADGENRE", genreIds = listOf(878, 99999)))

        result.unknownGenreIds.shouldContainExactlyInAnyOrder(listOf(99999))
        genreIdsOf(result.titleId).shouldContainExactlyInAnyOrder(listOf<Short>(878))
    }

    @Test
    fun `stores a series with the denormalised total runtime the optimiser reads`() {
        val result = repository.upsert(
            IngestedTitle(
                externalId = nextExternalId(),
                mediaType = MediaType.SERIES,
                name = "Psych",
                originalName = null,
                overview = "A fake psychic solves crimes.",
                releaseDate = LocalDate.of(2006, 7, 7),
                originalLanguage = "en",
                posterUrl = null,
                backdropUrl = null,
                popularityScore = BigDecimal("42.5"),
                communityRating = BigDecimal("8.4"),
                voteCount = 1200,
                genreIds = listOf(35, 80),
                movie = null,
                series = SeriesDetails(
                    status = "Ended",
                    firstAirDate = LocalDate.of(2006, 7, 7),
                    lastAirDate = LocalDate.of(2014, 3, 26),
                    seasonCount = 8,
                    episodeCount = 121,
                    averageEpisodeMinutes = 44,
                    totalRuntimeMinutes = 121 * 44,
                ),
                metadataStatus = MetadataStatus.COMPLETE,
            ),
        )

        val stored = dsl.selectFrom(SERIES).where(SERIES.TITLE_ID.eq(result.titleId)).fetchOne()!!
        stored.seasonCount shouldBe 8
        stored.episodeCount shouldBe 121
        stored.averageEpisodeMinutes shouldBe 44
        stored.totalRuntimeMinutes shouldBe 5324
        // A series must not have acquired a movies row.
        dsl.fetchCount(MOVIES, MOVIES.TITLE_ID.eq(result.titleId)) shouldBe 0
    }

    @Test
    fun `a title with no genres stores cleanly`() {
        val result = repository.upsert(movie(externalId = "TMDB-NOGENRE", genreIds = emptyList()))

        genreIdsOf(result.titleId).shouldContainExactlyInAnyOrder(emptyList())
    }

    @Test
    fun `findIdByExternalId returns null for a title that was never ingested`() {
        repository.findIdByExternalId("TMDB-NEVER-SEEN").shouldBeNull()
    }

    // --- helpers -----------------------------------------------------------

    private fun movie(
        name: String = "A Film",
        runtime: Int? = 100,
        genreIds: List<Int> = listOf(878),
        externalId: String = nextExternalId(),
    ) = IngestedTitle(
        externalId = externalId,
        mediaType = MediaType.MOVIE,
        name = name,
        originalName = null,
        overview = null,
        releaseDate = LocalDate.of(2016, 11, 11),
        originalLanguage = "en",
        posterUrl = null,
        backdropUrl = null,
        popularityScore = BigDecimal("10.0"),
        communityRating = BigDecimal("7.5"),
        voteCount = 100,
        genreIds = genreIds,
        movie = MovieDetails(runtimeMinutes = runtime),
        series = null,
        metadataStatus = if (runtime != null) MetadataStatus.COMPLETE else MetadataStatus.PARTIAL,
    )

    private fun runtimeOf(titleId: UUID): Int? =
        dsl.select(MOVIES.RUNTIME_MINUTES).from(MOVIES).where(MOVIES.TITLE_ID.eq(titleId)).fetchOne()?.value1()

    private fun genreIdsOf(titleId: UUID): List<Short> = dsl.select(TITLE_GENRES.GENRE_ID)
        .from(TITLE_GENRES)
        .where(TITLE_GENRES.TITLE_ID.eq(titleId))
        .fetch()
        .mapNotNull { it.value1() }

    private fun nextExternalId(): String = "TMDB-${SEQUENCE.incrementAndGet()}"

    companion object {
        private val SEQUENCE = AtomicInteger(1_000_000)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("plotted")
                .withUsername("plotted")
                .withPassword("plotted")
    }
}
