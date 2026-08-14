package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedEpisode
import app.plotted.catalogue.domain.IngestedSeason
import app.plotted.generated.jooq.tables.references.SERIES
import app.plotted.generated.jooq.tables.references.TITLES
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * "What do I watch next" as SQL, against a real database.
 *
 * The ordering here is the whole feature: get it wrong and Tonight confidently
 * names the wrong episode, which is worse than naming none. Three of these cases
 * are judgements rather than mechanics — specials, unaired episodes, and the
 * season boundary — and each is the sort that looks right until it is run
 * against a series shaped differently from the one you had in mind.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class NextEpisodeIntegrationTest {
    @Autowired
    private lateinit var seasons: SeasonRepository

    @Autowired
    private lateinit var dsl: DSLContext

    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    @Test
    fun `with no position the first episode of the main run is next`() {
        val series = givenSeries()
        seasons.upsert(series, season(1, episodes = 3, runtime = 24))

        val next = seasons.nextEpisode(series, null, null, today).shouldNotBeNull()

        next.seasonNumber shouldBe 1
        next.episodeNumber shouldBe 1
    }

    @Test
    fun `the next episode follows the one just finished`() {
        val series = givenSeries()
        seasons.upsert(series, season(1, episodes = 12, runtime = 24))

        val next = seasons.nextEpisode(series, 1, 7, today).shouldNotBeNull()

        next.episodeNumber shouldBe 8
    }

    /**
     * The case a naive `episode_number > ?` gets wrong.
     *
     * Finishing the last episode of season 1 has to step into season 2, and the
     * comparison is lexicographic over (season, episode) rather than over either
     * one alone. Ordering by episode number would answer S1 E1 of season two's
     * numbering, or nothing at all.
     */
    @Test
    fun `finishing a season steps into the next one`() {
        val series = givenSeries()
        seasons.upsert(series, season(1, episodes = 12, runtime = 24))
        seasons.upsert(series, season(2, episodes = 10, runtime = 24))

        val next = seasons.nextEpisode(series, 1, 12, today).shouldNotBeNull()

        next.seasonNumber shouldBe 2
        next.episodeNumber shouldBe 1
    }

    /**
     * Season 0 is where TMDB files specials, and `recalculateTotalRuntime`
     * already excludes it from the runtime a series is judged by. Excluding it
     * here keeps one answer to "how far through am I": otherwise next-up steps
     * out of the story into a Christmas episode and back again.
     */
    @Test
    fun `specials are never next`() {
        val series = givenSeries()
        seasons.upsert(series, season(0, episodes = 4, runtime = 24))
        seasons.upsert(series, season(1, episodes = 3, runtime = 24))

        val next = seasons.nextEpisode(series, null, null, today).shouldNotBeNull()

        next.seasonNumber shouldBe 1
        next.episodeNumber shouldBe 1
    }

    @Test
    fun `an episode that has not aired is not next`() {
        val series = givenSeries()
        seasons.upsert(
            series,
            IngestedSeason(
                externalId = "S-${SEQUENCE.incrementAndGet()}",
                seasonNumber = 1,
                name = "Season 1",
                airDate = today.minusMonths(2),
                episodes = listOf(
                    episode(1, 24, airDate = today.minusDays(14)),
                    episode(2, 24, airDate = today.minusDays(7)),
                    // Next Tuesday.
                    episode(3, 24, airDate = today.plusDays(5)),
                ),
            ),
        )

        // Caught up to the broadcast, which is a real state and not the same as
        // finished. Offering episode 3 sends somebody to look for something that
        // does not exist yet.
        seasons.nextEpisode(series, 1, 2, today).shouldBeNull()
    }

    @Test
    fun `an episode with no air date is still offered`() {
        val series = givenSeries()
        // Undated is a gap in Plotted's data rather than evidence of an
        // unreleased episode. Treating it as unreleased would hide most older
        // series from next-up entirely.
        seasons.upsert(series, season(1, episodes = 3, runtime = 24))

        seasons.nextEpisode(series, 1, 1, today).shouldNotBeNull().episodeNumber shouldBe 2
    }

    @Test
    fun `a finished series has nothing next`() {
        val series = givenSeries()
        seasons.upsert(series, season(1, episodes = 3, runtime = 24))

        seasons.nextEpisode(series, 1, 3, today).shouldBeNull()
    }

    @Test
    fun `remaining counts what is left, and excludes specials`() {
        val series = givenSeries()
        seasons.upsert(series, season(0, episodes = 5, runtime = 24))
        seasons.upsert(series, season(1, episodes = 12, runtime = 24))

        val remaining = seasons.remaining(series, 1, 4, today)

        remaining.episodes shouldBe 8
        remaining.minutes shouldBe 8 * 24
    }

    /**
     * The count includes episodes with no runtime; the sum does not.
     *
     * So "3 episodes, 48 minutes" can mean three episodes of which two are
     * measured. That asymmetry is deliberate — inventing a runtime here would
     * put a made-up number into a sentence about whether somebody can finish
     * before a removal date — and it is pinned because the obvious "fix" is to
     * make them agree.
     */
    @Test
    fun `remaining minutes skip episodes with no runtime while the count does not`() {
        val series = givenSeries()
        seasons.upsert(
            series,
            IngestedSeason(
                externalId = "S-${SEQUENCE.incrementAndGet()}",
                seasonNumber = 1,
                name = "Season 1",
                airDate = today.minusYears(1),
                episodes = listOf(episode(1, 24), episode(2, 24), episode(3, null)),
            ),
        )

        val remaining = seasons.remaining(series, null, null, today)

        remaining.episodes shouldBe 3
        remaining.minutes shouldBe 48
    }

    @Test
    fun `episodeExists is the guard the schema cannot be`() {
        val series = givenSeries()
        seasons.upsert(series, season(1, episodes = 3, runtime = 24))

        seasons.episodeExists(series, 1, 3) shouldBe true
        // V19 stores a position rather than a foreign key, so nothing in the
        // database refuses this. The service does, using exactly this.
        seasons.episodeExists(series, 9, 1) shouldBe false
    }

    // --- helpers -----------------------------------------------------------

    private fun givenSeries(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "NEXT-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "series")
            .set(TITLES.NAME, "A Series")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        dsl.insertInto(SERIES).set(SERIES.TITLE_ID, id).set(SERIES.AVERAGE_EPISODE_MINUTES, 24).execute()
        return id
    }

    private fun season(number: Int, episodes: Int, runtime: Int?) = IngestedSeason(
        externalId = "S-${SEQUENCE.incrementAndGet()}",
        seasonNumber = number,
        name = "Season $number",
        airDate = today.minusYears(1),
        episodes = (1..episodes).map { episode(it, runtime) },
    )

    private fun episode(number: Int, runtime: Int?, airDate: LocalDate? = null) = IngestedEpisode(
        externalId = "E-${SEQUENCE.incrementAndGet()}",
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        runtimeMinutes = runtime,
        airDate = airDate,
    )

    private companion object {
        val SEQUENCE = AtomicInteger()

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
