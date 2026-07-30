package app.plotted.catalogue.domain

import app.plotted.catalogue.integration.tmdb.TmdbTitleMapper
import app.plotted.catalogue.persistence.TitleRepository
import app.plotted.platform.events.TitleIngested
import app.plotted.platform.integration.tmdb.TmdbClient
import app.plotted.platform.integration.tmdb.TmdbException
import app.plotted.platform.integration.tmdb.TmdbMovieDetail
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.integration.tmdb.TmdbSearchPage
import app.plotted.platform.integration.tmdb.TmdbSearchResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * The behaviour worth pinning down is what happens when TMDB does not cooperate.
 * A catalogue refresh walks thousands of titles, so the useful property is that
 * one bad title does not take the run down with it.
 */
class TitleIngestionServiceTest {
    private val client = mockk<TmdbClient>()
    private val titles = mockk<TitleRepository>()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)

    private val service = TitleIngestionService(
        client = client,
        mapper = TmdbTitleMapper(TmdbProperties()),
        titles = titles,
        events = events,
    )

    private val titleId = UUID.randomUUID()

    @Test
    fun `a stored title reports whether it was new`() {
        every { client.movie(438631) } returns movieDetail()
        every { titles.upsert(any()) } returns TitleRepository.UpsertResult(titleId, created = true, unknownGenreIds = emptyList())

        val outcome = service.ingest(MediaType.MOVIE, 438631)

        outcome as TitleIngestionService.IngestionOutcome.Ingested
        outcome.titleId shouldBe titleId
        outcome.name shouldBe "Dune"
        outcome.created shouldBe true
    }

    @Test
    fun `ingesting publishes an event so availability can follow without a direct call`() {
        every { client.movie(any()) } returns movieDetail()
        every { titles.upsert(any()) } returns TitleRepository.UpsertResult(titleId, created = true, unknownGenreIds = emptyList())

        service.ingest(MediaType.MOVIE, 438631)

        val event = slot<TitleIngested>()
        verify { events.publishEvent(capture(event)) }
        event.captured.titleId shouldBe titleId
        event.captured.externalId shouldBe "438631"
        // A String, so the catalogue's MediaType does not leak across the boundary.
        event.captured.mediaType shouldBe "movie"
    }

    @Test
    fun `a title TMDB has deleted is reported as missing, not as a failure`() {
        every { client.movie(404404) } throws TmdbException.NotFound("/movie/404404")

        val outcome = service.ingest(MediaType.MOVIE, 404404)

        (outcome is TitleIngestionService.IngestionOutcome.NotFound) shouldBe true
        verify(exactly = 0) { titles.upsert(any()) }
        verify(exactly = 0) { events.publishEvent(any()) }
    }

    @Test
    fun `a transient failure is reported as retryable`() {
        every { client.movie(any()) } throws TmdbException.Upstream(503, "down")

        val outcome = service.ingest(MediaType.MOVIE, 1)

        outcome as TitleIngestionService.IngestionOutcome.Failed
        outcome.retryable shouldBe true
    }

    @Test
    fun `a rejected credential is reported as not retryable`() {
        every { client.movie(any()) } throws TmdbException.Unauthorised("bad token")

        val outcome = service.ingest(MediaType.MOVIE, 1)

        outcome as TitleIngestionService.IngestionOutcome.Failed
        // Retrying a wrong credential just spends the quota faster.
        outcome.retryable shouldBe false
    }

    @Test
    fun `a batch keeps going past a failure and reports what happened`() {
        every { client.movie(1) } returns movieDetail()
        every { client.movie(2) } throws TmdbException.NotFound("/movie/2")
        every { client.movie(3) } throws TmdbException.Upstream(500, "boom")
        every { titles.upsert(any()) } returns TitleRepository.UpsertResult(titleId, created = true, unknownGenreIds = emptyList())

        val report = service.ingestAll(
            listOf(1, 2, 3).map { TitleIngestionService.Request(MediaType.MOVIE, it) },
        )

        report.total shouldBe 3
        report.ingested.size shouldBe 1
        report.notFound.size shouldBe 1
        report.failed.size shouldBe 1
        report.retryable.size shouldBe 1
        report.summary() shouldBe
            "3 requested: 1 stored (1 new, 0 refreshed), 1 missing upstream, 1 failed (1 retryable)"
    }

    @Test
    fun `search returns titles and drops people`() {
        every { client.searchMulti("dune") } returns
            TmdbSearchPage(
                results = listOf(
                    TmdbSearchResult(id = 1, mediaType = "movie", title = "Dune"),
                    TmdbSearchResult(id = 2, mediaType = "person", name = "Denis Villeneuve"),
                ),
            )

        service.search("dune").map { it.name } shouldContainExactly listOf("Dune")
    }

    @Test
    fun `a failing search degrades to no results rather than an error page`() {
        every { client.searchMulti(any()) } throws TmdbException.Upstream(500, "down")

        service.search("dune").shouldContainExactly(emptyList())
    }

    @Test
    fun `a blank query never reaches TMDB`() {
        service.search("   ").shouldContainExactly(emptyList())

        verify(exactly = 0) { client.searchMulti(any(), any()) }
    }

    private fun movieDetail() = TmdbMovieDetail(id = 438631, title = "Dune", runtime = 155, releaseDate = "2021-09-15")
}
