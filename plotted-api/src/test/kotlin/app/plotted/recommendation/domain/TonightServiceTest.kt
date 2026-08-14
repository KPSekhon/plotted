package app.plotted.recommendation.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.platform.spi.WatchlistDirectory
import app.plotted.recommendation.persistence.RecommendationLogRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The orchestration in `TonightService.recommend`, which nothing covered.
 *
 * Every stage had its own tests — `HardFiltersTest`, `FeaturesTest`,
 * `RankerTest` — and the method that wires them together had none. That gap was
 * noticed the way these usually are: a signature change to `recommend` broke no
 * test at all, which means the sequence, the branch conditions and what reaches
 * the decision log were all unasserted.
 *
 * What is tested here is deliberately *not* the ranking. It is the wiring:
 *
 *  * that the two different empty answers stay different, because their causes
 *    and their fixes are unrelated;
 *  * that every outcome reaches the log exactly once, since an unlogged
 *    decision is invisible to phase 7 forever;
 *  * that the request id returned is the one the log minted, because acceptance
 *    points at it and a mismatched id silently records nothing;
 *  * that a watchlist row whose title has been deleted is dropped rather than
 *    crashing the request.
 *
 * The repository is mocked rather than run: what it writes is already covered
 * by `RecommendationLogRepositoryIntegrationTest` against real Postgres, and
 * that test needs Docker. This one runs anywhere, which is the point — the
 * orchestration is exactly the layer a developer changes most often and can
 * currently verify least.
 */
class TonightServiceTest {
    private val userId = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val netflix = UUID.randomUUID()

    private val watchlists = mockk<WatchlistDirectory>()
    private val titles = mockk<TitleDirectory>()
    private val availability = mockk<AvailabilityDirectory>()
    private val subscriptions = mockk<SubscriptionDirectory>()
    private val log = mockk<RecommendationLogRepository>()

    private val clock = Clock.fixed(Instant.parse("2026-08-07T20:00:00Z"), ZoneOffset.UTC)

    private fun service() = TonightService(
        watchlists = watchlists,
        titles = titles,
        availability = availability,
        subscriptions = subscriptions,
        log = log,
        properties = TmdbProperties(region = "CA"),
        clock = clock,
    )

    @Test
    fun `an empty watchlist is nothing-fits with no candidates, and is still logged`() {
        val requestId = givenTheLogAccepts()
        every { watchlists.outstandingItems(userId) } returns emptyList()
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)

        val outcome = service().recommend(userId, request())

        // Logged even though there was nothing to choose from. "We were asked
        // and had nothing" is a fact about the product that only exists if it
        // is written down -- and it is the shape of the empty log both End
        // Credits metrics currently report null from.
        outcome.requestId shouldBe requestId
        val nothing = outcome.recommendation.shouldBeInstanceOf<Recommendation.NothingFits>()
        nothing.candidateCount shouldBe 0
        nothing.reasons shouldBe emptyMap()
    }

    @Test
    fun `a title that no longer exists in the catalogue is dropped rather than throwing`() {
        givenTheLogAccepts()
        val present = UUID.randomUUID()
        val deleted = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(present), entry(deleted))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        // The directory omits unknown ids rather than returning blanks, which is
        // its documented contract and the reason this case is reachable at all.
        every { titles.findSummaries(any()) } returns listOf(summary(present, "Still here", 100))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(present to crave)

        val served = service().recommend(userId, request()).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        // Two rows on the list, one real title. The missing one must not become
        // a candidate with an empty name, and must not take the request down.
        served.candidateCount shouldBe 1
        served.picks.map { it.candidate.name } shouldContainExactly listOf("Still here")
    }

    @Test
    fun `everything filtered out reports why, per reason`() {
        givenTheLogAccepts()
        val tooLong = UUID.randomUUID()
        val blocked = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(tooLong), entry(blocked))
        every { watchlists.blockedTitleIds(userId) } returns setOf(blocked)
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns listOf(
            summary(tooLong, "Very long film", 240),
            summary(blocked, "Not interested", 90),
        )
        every { availability.subscriptionCoverage(any(), "CA") } returns
            coverage(tooLong to crave, blocked to crave)

        val nothing = service().recommend(userId, request(availableMinutes = 60)).recommendation
            .shouldBeInstanceOf<Recommendation.NothingFits>()

        // The counts are the whole value of this answer: they tell the user
        // which constraint to relax. Collapsing them into "nothing fits" would
        // make the screen an apology instead of a diagnosis.
        nothing.candidateCount shouldBe 2
        nothing.reasons shouldBe mapOf(Rejection.TOO_LONG to 1, Rejection.BLOCKED to 1)
    }

    @Test
    fun `a title of unknown length is still recommendable when no time limit was given`() {
        givenTheLogAccepts()
        val unknownLength = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(unknownLength))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns
            listOf(summary(unknownLength, "Unknown length", null))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(unknownLength to crave)

        val served = service().recommend(userId, request()).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        // Unknown runtime is only disqualifying when a budget was given -- there
        // is no promise to keep otherwise. The runtime feature is *absent*
        // rather than zero, and renormalisation redistributes its weight, so
        // this still scores on priority and access alone.
        served.picks.single().candidate.name shouldBe "Unknown length"
        served.picks.single().features.contributions()
            .map { it.feature } shouldNotContain Feature.RUNTIME_FIT
    }

    /**
     * The `scored.isEmpty()` branch in `recommend` cannot be reached.
     *
     * It builds a `NothingFits` carrying `RUNTIME_UNKNOWN`, which reads as a
     * real outcome — but `Ranker.score` returns null only when a candidate has
     * no usable feature at all, and `Feature.PRIORITY` is derived from the
     * watchlist priority, which is non-null on every entry by schema. So every
     * eligible candidate always scores, and that branch is dead.
     *
     * Asserted rather than deleted, because the assertion is what makes it stay
     * true: if a later change makes priority optional, this fails and whoever
     * made it has to decide deliberately what an unscorable candidate means.
     * Left in place in the service as defence — but nobody should read it as a
     * path that has ever run.
     */
    @Test
    fun `every eligible candidate scores, which is why the unscorable branch is unreachable`() {
        val bareMinimum = Candidate(
            titleId = UUID.randomUUID(),
            name = "Nothing known about it",
            mediaType = "movie",
            posterUrl = null,
            watchMinutes = null,
            sessionMinutes = null,
            priority = 3,
            desiredByDate = null,
            communityRating = null,
            offers = emptyList(),
        )

        val scored = Ranker().score(
            candidate = bareMinimum,
            context = TonightContext(regionCode = "CA", availableMinutes = null, accessPolicy = AccessPolicy.SUBSCRIBED_ONLY),
            subscribedProviderIds = emptySet(),
            today = LocalDate.now(clock),
        )

        // Priority alone is enough, and priority is always there.
        scored.shouldNotBeNull()
        scored.features.contributions().map { it.feature } shouldContainExactly listOf(Feature.PRIORITY)
    }

    @Test
    fun `the answer is capped at three slots and logged once, as served`() {
        val recorded = slot<Recommendation>()
        val requestId = UUID.randomUUID()
        every { log.record(userId, any(), capture(recorded), any(), any()) } returns requestId

        val ids = List(5) { UUID.randomUUID() }
        every { watchlists.outstandingItems(userId) } returns ids.map { entry(it) }
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave, netflix)
        every { titles.findSummaries(any()) } returns ids.mapIndexed { index, id ->
            summary(id, "Title $index", 90 + index)
        }
        every { availability.subscriptionCoverage(any(), "CA") } returns
            coverage(*ids.map { it to crave }.toTypedArray())

        val outcome = service().recommend(userId, request())
        val served = outcome.recommendation.shouldBeInstanceOf<Recommendation.Served>()

        // One pick and two backups, from five eligible.
        served.picks.size shouldBe 3
        served.candidateCount shouldBe 5
        served.eligibleCount shouldBe 5

        // The id handed back has to be the log's, because acceptance is scoped
        // by it. Returning a fresh UUID here would make every acceptance match
        // zero rows and fail silently -- the update is deliberately scoped
        // rather than checked, so nothing would throw.
        outcome.requestId shouldBe requestId
        recorded.captured.shouldBeInstanceOf<Recommendation.Served>()
    }

    @Test
    fun `the order of the picks is the answer, and slot one is the best of them`() {
        givenTheLogAccepts()
        val ids = List(3) { UUID.randomUUID() }
        every { watchlists.outstandingItems(userId) } returns ids.map { entry(it) }
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns ids.mapIndexed { index, id ->
            summary(id, "Title $index", 90)
        }
        every { availability.subscriptionCoverage(any(), "CA") } returns
            coverage(*ids.map { it to crave }.toTypedArray())

        val served = service().recommend(userId, request()).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        // `Pick` carries no position of its own: the repository writes
        // `index + 1` and the DTO mapper does the same, so the *order of this
        // list* is the position, in two places, silently. Nothing else asserts
        // that, and reordering here would renumber the decision log and the
        // response together while every unit test still passed.
        served.picks.map { it.candidate.titleId }.distinct().size shouldBe served.picks.size

        // Slot one is never traded away for variety -- `RankerTest` pins that
        // inside `diversify`, and this checks the property survives the wiring.
        served.picks.first().score shouldBe served.picks.maxOf { it.score }
    }

    // --- fixtures ---------------------------------------------------------

    /**
     * The point of the whole progress feature, at the level the user sees it.
     *
     * "Chainsaw Man, about 24 minutes an episode" leaves somebody to open
     * another app and work out where they were. "S1 E8" does not.
     */
    @Test
    fun `a series pick names the episode to actually put on`() {
        givenTheLogAccepts()
        val chainsawMan = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(chainsawMan))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns
            listOf(summary(chainsawMan, "Chainsaw Man", 288, mediaType = "series", sessionMinutes = 24))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(chainsawMan to crave)
        every { watchlists.seriesProgress(userId, listOf(chainsawMan)) } returns mapOf(
            chainsawMan to WatchlistDirectory.NextUp(
                episodeId = UUID.randomUUID(),
                seasonNumber = 1,
                episodeNumber = 8,
                name = "Gun Devil",
                runtimeMinutes = 24,
                started = true,
                remainingEpisodes = 5,
            ),
        )

        val served = service().recommend(userId, request(availableMinutes = 45)).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        val next = served.picks.single().candidate.nextUp.shouldNotBeNull()
        next.seasonNumber shouldBe 1
        next.episodeNumber shouldBe 8
    }

    /**
     * A watchlist of films must not ask about episodes at all, so the common
     * case pays nothing for a feature that cannot apply to it.
     */
    @Test
    fun `a film pick asks nothing about episodes`() {
        givenTheLogAccepts()
        val film = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(film))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns listOf(summary(film, "A Film", 100))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(film to crave)

        val served = service().recommend(userId, request()).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        served.picks.single().candidate.nextUp.shouldBeNull()
        verify(exactly = 0) { watchlists.seriesProgress(any(), any()) }
    }

    /**
     * No progress for anything, unless a test says otherwise.
     *
     * Series candidates now resolve their next episode *before* the filters run,
     * because the runtime filter has to measure the episode being offered rather
     * than the series' average. Most tests here are about films and never reach
     * it; this keeps them from having to know that.
     */
    /**
     * The defect this fix exists for.
     *
     * A 25-minute average admitted the series; the episode actually being
     * offered is a 61-minute finale. Before the filter read the real episode,
     * this passed the 45-minute window and the card then displayed 61 min --
     * the right number, arrived at after the decision that should have used it.
     *
     * Exactly the shape of the `watchMinutes` defect: a filter measuring
     * something adjacent to the question it is supposed to answer.
     */
    @Test
    fun `a long episode is refused even when the series average would fit`() {
        givenTheLogAccepts()
        val series = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(series))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns
            listOf(summary(series, "Mostly short", 600, mediaType = "series", sessionMinutes = 25))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(series to crave)
        every { watchlists.seriesProgress(userId, listOf(series)) } returns mapOf(
            series to WatchlistDirectory.NextUp(
                episodeId = UUID.randomUUID(),
                seasonNumber = 1,
                episodeNumber = 12,
                name = "The long one",
                runtimeMinutes = 61,
                started = true,
                remainingEpisodes = 1,
            ),
        )

        val nothing = service().recommend(userId, request(availableMinutes = 45)).recommendation
            .shouldBeInstanceOf<Recommendation.NothingFits>()

        nothing.reasons shouldBe mapOf(Rejection.TOO_LONG to 1)
    }

    /**
     * The fallback, pinned in the other direction.
     *
     * A series whose next episode has no stored runtime must fall back to the
     * typical one rather than becoming unrecommendable -- two thirds of the
     * seeded series catalogue had no episode runtime at all until ingest started
     * deriving it, and refusing on a gap upstream would hide them all again.
     */
    @Test
    fun `an episode with no runtime falls back to the typical one`() {
        givenTheLogAccepts()
        val series = UUID.randomUUID()

        every { watchlists.outstandingItems(userId) } returns listOf(entry(series))
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
        every { subscriptions.activeProviderIds(userId) } returns setOf(crave)
        every { titles.findSummaries(any()) } returns
            listOf(summary(series, "Unmeasured episode", 600, mediaType = "series", sessionMinutes = 25))
        every { availability.subscriptionCoverage(any(), "CA") } returns coverage(series to crave)
        every { watchlists.seriesProgress(userId, listOf(series)) } returns mapOf(
            series to WatchlistDirectory.NextUp(
                episodeId = UUID.randomUUID(),
                seasonNumber = 1,
                episodeNumber = 3,
                name = null,
                runtimeMinutes = null,
                started = false,
                remainingEpisodes = 20,
            ),
        )

        val served = service().recommend(userId, request(availableMinutes = 45)).recommendation
            .shouldBeInstanceOf<Recommendation.Served>()

        served.picks.single().candidate.sessionMinutes shouldBe 25
    }

    private fun givenNoSeriesProgress() {
        every { watchlists.seriesProgress(any(), any()) } returns emptyMap()
    }

    private fun givenTheLogAccepts(): UUID {
        val requestId = UUID.randomUUID()
        every { log.record(any(), any(), any(), any(), any()) } returns requestId
        return requestId
    }

    private fun request(availableMinutes: Int? = null) = TonightService.TonightRequest(
        availableMinutes = availableMinutes,
        accessPolicy = AccessPolicy.SUBSCRIBED_ONLY,
    )

    private fun entry(titleId: UUID) = WatchlistDirectory.WatchlistEntry(
        titleId = titleId,
        priority = 3,
        desiredByDate = null,
    )

    private fun summary(
        titleId: UUID,
        name: String,
        watchMinutes: Int?,
        mediaType: String = "movie",
        sessionMinutes: Int? = watchMinutes,
    ) = TitleDirectory.TitleSummary(
        titleId = titleId,
        mediaType = mediaType,
        name = name,
        releaseYear = 2024,
        posterUrl = null,
        watchMinutes = watchMinutes,
        sessionMinutes = sessionMinutes,
        communityRating = 7.5,
    )

    private fun coverage(vararg pairs: Pair<UUID, UUID>) = AvailabilityDirectory.Coverage(
        byTitle = pairs.groupBy({ it.first }, { providerRef(it.second) }),
        unknownTitleIds = emptySet(),
    )

    private fun providerRef(providerId: UUID) = AvailabilityDirectory.ProviderRef(
        providerId = providerId,
        name = if (providerId == crave) "Crave" else "Netflix",
        slug = if (providerId == crave) "crave" else "netflix",
        logoUrl = null,
        isFree = false,
    )
}
