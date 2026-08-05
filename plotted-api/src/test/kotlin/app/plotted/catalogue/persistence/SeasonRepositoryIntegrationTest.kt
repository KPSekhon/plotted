package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedEpisode
import app.plotted.catalogue.domain.IngestedSeason
import app.plotted.generated.jooq.tables.references.SERIES
import app.plotted.generated.jooq.tables.references.TITLES
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runtime recalculation is where the judgement lives, so it is what these
 * tests are about. Tonight Mode treats "it fits" as a promise and refuses to
 * trade it away in scoring; that promise is only as good as this number.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class SeasonRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: SeasonRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `stores a season with its episodes`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 22)

        repository.upsert(seriesId, season(1, episodes = 10, runtime = 22))

        repository.countSeasons(seriesId) shouldBe 1
        repository.countEpisodes(seriesId) shouldBe 10
    }

    @Test
    fun `re-ingesting a season updates it rather than duplicating episodes`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 22)
        repository.upsert(seriesId, season(1, episodes = 10, runtime = 22))

        repository.upsert(seriesId, season(1, episodes = 10, runtime = 24))

        repository.countSeasons(seriesId) shouldBe 1
        repository.countEpisodes(seriesId) shouldBe 10
        repository.recalculateTotalRuntime(seriesId) shouldBe 10 * 24
    }

    @Test
    fun `total runtime is summed from real episodes, replacing the estimate`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 30, estimate = 9999)
        repository.upsert(seriesId, season(1, episodes = 8, runtime = 25))
        repository.upsert(seriesId, season(2, episodes = 8, runtime = 25))

        val total = repository.recalculateTotalRuntime(seriesId)

        total shouldBe 16 * 25
        storedTotal(seriesId) shouldBe 16 * 25
        // The episode count is corrected to what was actually ingested.
        storedEpisodeCount(seriesId) shouldBe 16
    }

    @Test
    fun `specials are stored but excluded from how long the series takes`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 45)
        repository.upsert(seriesId, season(0, episodes = 4, runtime = 45))
        repository.upsert(seriesId, season(1, episodes = 10, runtime = 45))

        val total = repository.recalculateTotalRuntime(seriesId)

        // Someone asking whether they can finish a show before a renewal is not
        // counting the Christmas special.
        total shouldBe 10 * 45
        repository.countSeasons(seriesId) shouldBe 2
        repository.countEpisodes(seriesId) shouldBe 14
    }

    @Test
    fun `episodes with no runtime fall back to the series average`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 50)
        // TMDB leaves runtime null on plenty of episodes, especially older shows.
        val mixed = IngestedSeason(
            externalId = "S-${SEQUENCE.incrementAndGet()}",
            seasonNumber = 1,
            name = "Season 1",
            airDate = LocalDate.of(2006, 7, 7),
            episodes = listOf(
                episode(1, runtime = 60),
                episode(2, runtime = null),
                episode(3, runtime = null),
            ),
        )

        repository.upsert(seriesId, mixed)

        // Summing only the known runtimes would report 60 and make a five-hour
        // show look like it fits an hour.
        repository.recalculateTotalRuntime(seriesId) shouldBe 60 + 50 + 50
    }

    @Test
    fun `an existing estimate survives when nothing better is available`() {
        val seriesId = givenSeries(averageEpisodeMinutes = null, estimate = 480)
        repository.upsert(
            seriesId,
            IngestedSeason(
                externalId = "S-${SEQUENCE.incrementAndGet()}",
                seasonNumber = 1,
                name = null,
                airDate = null,
                episodes = listOf(episode(1, runtime = null), episode(2, runtime = null)),
            ),
        )

        val total = repository.recalculateTotalRuntime(seriesId)

        // Overwriting with null would drop the title out of every
        // time-constrained recommendation, which is worse than a rough estimate.
        total.shouldBeNull()
        storedTotal(seriesId) shouldBe 480
    }

    @Test
    fun `a series with no episodes at all leaves the estimate alone`() {
        val seriesId = givenSeries(averageEpisodeMinutes = 42, estimate = 420)

        repository.recalculateTotalRuntime(seriesId).shouldBeNull()

        storedTotal(seriesId) shouldBe 420
    }

    // --- helpers -----------------------------------------------------------

    private fun givenSeries(averageEpisodeMinutes: Int?, estimate: Int? = null): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "SEASON-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "series")
            .set(TITLES.NAME, "A Series")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        dsl.insertInto(SERIES)
            .set(SERIES.TITLE_ID, id)
            .set(SERIES.AVERAGE_EPISODE_MINUTES, averageEpisodeMinutes)
            .set(SERIES.TOTAL_RUNTIME_MINUTES, estimate)
            .execute()
        return id
    }

    private fun season(number: Int, episodes: Int, runtime: Int?) = IngestedSeason(
        externalId = "S-${SEQUENCE.incrementAndGet()}",
        seasonNumber = number,
        name = "Season $number",
        airDate = LocalDate.of(2020, 1, 1),
        episodes = (1..episodes).map { episode(it, runtime) },
    )

    private fun episode(number: Int, runtime: Int?) = IngestedEpisode(
        externalId = "E-${SEQUENCE.incrementAndGet()}",
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        runtimeMinutes = runtime,
        airDate = null,
    )

    private fun storedTotal(seriesId: UUID): Int? =
        dsl.select(SERIES.TOTAL_RUNTIME_MINUTES).from(SERIES).where(SERIES.TITLE_ID.eq(seriesId)).fetchOne()?.value1()

    private fun storedEpisodeCount(seriesId: UUID): Int? =
        dsl.select(SERIES.EPISODE_COUNT).from(SERIES).where(SERIES.TITLE_ID.eq(seriesId)).fetchOne()?.value1()

    companion object {
        private val SEQUENCE = AtomicInteger(3_000_000)

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
