package app.plotted.watchlist.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.spi.EpisodeDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.SeriesProgressRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Where somebody is in a series, and the difference between the three ways of
 * having nothing to watch.
 *
 * "Not started", "caught up" and "this is not a series" all produce a view with
 * no history, and they mean completely different things to the person reading
 * the screen. Most of these tests are about keeping them apart.
 */
class SeriesProgressServiceTest {
    private val progress = mockk<SeriesProgressRepository>(relaxed = true)
    private val episodes = mockk<EpisodeDirectory>()
    private val titles = mockk<TitleDirectory>()
    private val service = SeriesProgressService(progress, episodes, titles)

    private val userId = UUID.randomUUID()
    private val chainsawMan = UUID.randomUUID()

    @Test
    fun `with nothing recorded the next episode is the first one`() {
        givenNoProgress()
        givenNext(season = 1, episode = 1, runtime = 24)
        givenRemaining(episodes = 12, minutes = 288)

        val view = service.view(userId, chainsawMan)

        // Not started still has a next episode. Collapsing this into null would
        // make "you have not begun" indistinguishable from "there is nothing
        // left", which are opposite answers to the only question being asked.
        view.started shouldBe false
        view.caughtUp shouldBe false
        view.next!!.seasonNumber shouldBe 1
        view.next!!.episodeNumber shouldBe 1
    }

    @Test
    fun `a series with nothing left is caught up rather than unstarted`() {
        every { progress.find(userId, chainsawMan) } returns
            SeriesProgress(chainsawMan, 1, 12, Instant.EPOCH)
        every { episodes.nextEpisode(chainsawMan, 1, 12) } returns null
        givenRemaining(episodes = 0, minutes = null)

        val view = service.view(userId, chainsawMan)

        view.started shouldBe true
        view.caughtUp shouldBe true
        view.next.shouldBeNull()
    }

    @Test
    fun `an episode the catalogue has never heard of is refused`() {
        givenSeries()
        every { episodes.episodeExists(chainsawMan, 9, 1) } returns false

        // The table stores a position rather than a foreign key, so this check is
        // the only thing standing between it and season nine of a one-season show.
        shouldThrow<ApiException> { service.record(userId, chainsawMan, 9, 1) }
        verify(exactly = 0) { progress.record(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `progress is refused for a film`() {
        every { titles.findSummaries(listOf(chainsawMan)) } returns listOf(summary(mediaType = "movie"))

        shouldThrow<ApiException> { service.record(userId, chainsawMan, 1, 1) }
        verify(exactly = 0) { progress.record(any(), any(), any(), any(), any()) }
    }

    /**
     * Moving backwards is allowed on purpose.
     *
     * Correcting a mistake and starting a rewatch both go backwards, and a
     * marker that only ratchets forward is one the user cannot fix. This pins it
     * because "progress only increases" is the obvious invariant to add later.
     */
    @Test
    fun `an earlier episode replaces a later one`() {
        givenSeries()
        every { episodes.episodeExists(chainsawMan, 1, 2) } returns true
        every { progress.find(userId, chainsawMan) } returns SeriesProgress(chainsawMan, 1, 2, Instant.EPOCH)
        every { episodes.nextEpisode(chainsawMan, 1, 2) } returns episode(1, 3, 24)
        givenRemaining(episodes = 10, minutes = 240)

        service.record(userId, chainsawMan, 1, 2)

        verify { progress.record(userId, chainsawMan, 1, 2, "user") }
    }

    @Test
    fun `clearing progress is not a 404 when there was none`() {
        givenNoProgress()
        every { progress.clear(userId, chainsawMan) } returns false
        givenNext(season = 1, episode = 1, runtime = 24)
        givenRemaining(episodes = 12, minutes = 288)

        val view = service.clear(userId, chainsawMan)

        // Returns the view rather than throwing: clearing puts you back at
        // episode one, and the client has to render that.
        view.started shouldBe false
        view.next!!.episodeNumber shouldBe 1
    }

    // --- helpers -----------------------------------------------------------

    private fun givenSeries() {
        every { titles.findSummaries(listOf(chainsawMan)) } returns listOf(summary(mediaType = "series"))
    }

    private fun givenNoProgress() {
        every { progress.find(userId, chainsawMan) } returns null
    }

    private fun givenNext(season: Int, episode: Int, runtime: Int?) {
        every { episodes.nextEpisode(chainsawMan, null, null) } returns episode(season, episode, runtime)
    }

    private fun givenRemaining(episodes: Int, minutes: Int?) {
        every { this@SeriesProgressServiceTest.episodes.remaining(chainsawMan, any(), any()) } returns
            EpisodeDirectory.Remaining(episodes, minutes)
    }

    private fun episode(season: Int, number: Int, runtime: Int?) = EpisodeDirectory.Episode(
        episodeId = UUID.randomUUID(),
        seasonNumber = season,
        episodeNumber = number,
        name = "Episode $number",
        runtimeMinutes = runtime,
    )

    private fun summary(mediaType: String) = TitleDirectory.TitleSummary(
        titleId = chainsawMan,
        mediaType = mediaType,
        name = "Chainsaw Man",
        releaseYear = 2022,
        posterUrl = null,
        watchMinutes = 288,
        sessionMinutes = 24,
        communityRating = 8.5,
    )
}
