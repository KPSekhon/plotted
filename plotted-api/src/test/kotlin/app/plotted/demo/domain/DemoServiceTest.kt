package app.plotted.demo.domain

import app.plotted.demo.persistence.DemoRepository
import app.plotted.platform.config.PlottedProperties
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.RecommendationFixtures
import app.plotted.platform.spi.SessionIssuer
import app.plotted.platform.spi.TasteFixtures
import app.plotted.platform.spi.TitleDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The demo persona, which is a product decision rather than a fixture.
 *
 * What these pin is the shape of the story the demo tells: a list spread across
 * priorities so weighting is visible, one deadline so the feature appears
 * without dominating, and two subscriptions picked from the data so Cancel
 * Culture has both something to keep and something to cancel. Each of those is
 * a choice that could be quietly undone by someone tidying this class up, and
 * the result would still produce a demo — just a much less interesting one, with
 * nothing failing to say so.
 */
class DemoServiceTest {
    private val demo = mockk<DemoRepository>(relaxed = true)
    private val titles = mockk<TitleDirectory>()
    private val availability = mockk<AvailabilityDirectory>()
    private val sessions = mockk<SessionIssuer>()

    // Relaxed: seeding the taste fixture is a side effect of starting a demo,
    // not a property any test here is about. The one thing that does matter --
    // that a failure to seed does not fail the demo -- has its own test below.
    private val taste = mockk<TasteFixtures>(relaxed = true)
    private val decisions = mockk<RecommendationFixtures>(relaxed = true)

    private val userId = UUID.randomUUID()
    private val watchlistId = UUID.randomUUID()
    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val cbcGem = UUID.randomUUID()
    private val netflixPlan = UUID.randomUUID()
    private val cravePlan = UUID.randomUUID()
    private val today = LocalDate.of(2026, 8, 6)

    private val client = SessionIssuer.ClientContext(userAgent = "test", ipAddress = "127.0.0.1")

    private fun service(properties: DemoProperties = DemoProperties(enabled = true)) = DemoService(
        demo = demo,
        titles = titles,
        availability = availability,
        taste = taste,
        decisions = decisions,
        sessions = sessions,
        properties = properties,
        platform = platformProperties(),
        clock = Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `demo mode off is a 404, not a 403`() {
        val failure = shouldThrow<ApiException> { service(DemoProperties(enabled = false)).start(client) }

        // Saying "forbidden" would confirm the endpoint exists and could be
        // turned on. On a deployment that is not a demo, it simply is not there.
        failure.code shouldBe ErrorCode.NOT_FOUND
    }

    @Test
    fun `at the account ceiling it refuses rather than degrading`() {
        every { demo.countLiveDemoAccounts() } returns 500

        val failure = shouldThrow<ApiException> { service(DemoProperties(enabled = true, maximumLiveAccounts = 500)).start(client) }

        // This endpoint is unauthenticated and writes. A demo that is briefly
        // unavailable is recoverable; a free-tier database filled by a script is
        // not, so the ceiling is a refusal and not a soft limit.
        failure.code shouldBe ErrorCode.RATE_LIMITED
        verify(exactly = 0) { demo.createUser(any(), any(), any()) }
    }

    @Test
    fun `the watchlist is spread across every priority level`() {
        givenCatalogue(titleCount = 12)

        val priorities = mutableListOf<Int>()
        every { demo.insertWatchlistItem(any(), any(), capture(priorities), any(), any()) } returns Unit

        service().start(client)

        // A flat list would make priority-weighted coverage numerically equal to
        // a count, and the demo would silently stop showing the one decision the
        // coverage dashboard is built around.
        priorities.toSet() shouldBe setOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `exactly one title carries a deadline`() {
        givenCatalogue(titleCount = 12)

        val deadlines = mutableListOf<LocalDate?>()
        every { demo.insertWatchlistItem(any(), any(), any(), captureNullable(deadlines), any()) } returns Unit

        service().start(client)

        // One, not none and not several. None would leave the deadline feature
        // absent from every explanation; several would let it dominate all of
        // them, which misrepresents the ranker rather than merely omitting it.
        deadlines.count { it != null } shouldBe 1
        deadlines.filterNotNull().single() shouldBe today.plusDays(10)
    }

    @Test
    fun `it subscribes to the service covering most of the list and the one covering least`() {
        givenCatalogue(titleCount = 4)

        val result = service().start(client)

        // Netflix carries three of the four, Crave one. Nobody decided that in
        // advance — it falls out of the seeded availability.
        result.subscriptions shouldContainExactly listOf("Netflix", "Crave")
    }

    @Test
    fun `the service it wants to cancel is the one under commitment`() {
        givenCatalogue(titleCount = 4)

        val plans = mutableListOf<UUID>()
        val commitments = mutableListOf<LocalDate?>()
        every { demo.insertSubscription(any(), capture(plans), any(), captureNullable(commitments)) } returns Unit

        service().start(client)

        // The whole point of the demo's second act: the optimiser wants this
        // gone, cannot have it gone, says so, and drops it the month it is
        // allowed to.
        plans shouldContainExactly listOf(netflixPlan, cravePlan)
        commitments[0] shouldBe null
        commitments[1] shouldBe today.plusMonths(2)
    }

    @Test
    fun `a free service is never subscribed to`() {
        givenCatalogue(titleCount = 4, includeFreeProvider = true)

        val result = service().start(client)

        // CBC Gem covers part of the list and costs nothing. Proposing the
        // persona cancel it would be advice about a bill they do not have.
        result.subscriptions.contains("CBC Gem") shouldBe false
    }

    @Test
    fun `titles with no known runtime are left off the list`() {
        givenCatalogue(titleCount = 4, unmeasuredTitles = 2)

        val result = service().start(client)

        // Four measured, two not. Tonight Mode rejects what it cannot measure,
        // so a list of unmeasured titles would demo the "nothing fits" path and
        // nothing else. Without the filter all six would be here.
        result.watchlistSize shouldBe 4
    }

    @Test
    fun `an unseeded catalogue says so rather than pretending`() {
        every { demo.countLiveDemoAccounts() } returns 0
        every { demo.findCandidateTitleIds(any(), any(), any()) } returns emptyList()
        every { titles.findSummaries(any()) } returns emptyList()
        every { demo.createUser(any(), any(), any()) } returns userId
        every { demo.createWatchlist(any(), any()) } returns watchlistId
        givenSession()

        val result = service().start(client)

        // Two empty screens caused by a missing catalogue look exactly like two
        // broken features. The account is still created — the visitor can add
        // their own titles — but the response does not pretend.
        result.catalogueIsEmpty shouldBe true
        result.subscriptions shouldBe emptyList()
        verify(exactly = 0) { demo.insertSubscription(any(), any(), any(), any()) }
    }

    @Test
    fun `a taste profile that cannot be seeded does not fail the demo`() {
        givenCatalogue(titleCount = 4)
        givenSession()
        every { taste.seedDemoPersona(any(), any()) } throws IllegalStateException("ladder unavailable")

        val result = service().start(client)

        // The persona is a nicety; the account, the list and the subscriptions
        // are the demo. Letting a fixture take the whole thing down would mean
        // an empty taste ladder turning into a visitor seeing an error page
        // instead of the product.
        result.watchlistSize shouldBe 4
        verify { sessions.issueFor(userId, client) }
    }

    @Test
    fun `the manufactured history includes a failure and a refusal`() {
        givenCatalogue(titleCount = 8)
        givenSession()

        val recorded = mutableListOf<RecommendationFixtures.DemoDecision>()
        every { decisions.recordDemoDecision(capture(recorded)) } returns Unit

        service().start(client)

        // A history where everything was accepted and finished makes the
        // completion rate 100% and demonstrates nothing. These two rows are
        // what stop the demo flattering itself.
        recorded.any { it.titleId != null && it.acceptedAt == null } shouldBe true
        recorded.any { it.titleId == null } shouldBe true

        // And one acceptance outside the four-hour latency window, so the
        // "excluded as stale" count on the screen is not always zero.
        recorded.any {
            it.acceptedAt != null && it.requestedAt.plusHours(4).isBefore(it.acceptedAt)
        } shouldBe true
    }

    @Test
    fun `a decision log that cannot be seeded does not fail the demo`() {
        givenCatalogue(titleCount = 4)
        givenSession()
        every { decisions.recordDemoDecision(any()) } throws IllegalStateException("log unavailable")

        service().start(client).watchlistSize shouldBe 4
    }

    @Test
    fun `the questionnaire is left partly unanswered on purpose`() {
        givenCatalogue(titleCount = 4)
        givenSession()

        service().start(client)

        // Fewer than the fifteen the ladder holds. A demo that arrives finished
        // hides the fork, and answering every axis would lose the NOT_ASKED
        // verdict, which is the honest-refusal design being visible rather than
        // merely tested.
        verify { taste.seedDemoPersona(userId, 10) }
    }

    // --- fixtures -----------------------------------------------------------

    private fun givenCatalogue(titleCount: Int, unmeasuredTitles: Int = 0, includeFreeProvider: Boolean = false) {
        val ids = (0 until titleCount + unmeasuredTitles).map { UUID.randomUUID() }
        val measured = ids.take(titleCount)

        every { demo.countLiveDemoAccounts() } returns 0
        every { demo.createUser(any(), any(), any()) } returns userId
        every { demo.createWatchlist(any(), any()) } returns watchlistId
        every { demo.findCandidateTitleIds(any(), any(), any()) } returns ids
        every { titles.findSummaries(any()) } returns ids.map { id ->
            TitleDirectory.TitleSummary(
                titleId = id,
                mediaType = "movie",
                name = "Title $id",
                releaseYear = 2025,
                posterUrl = null,
                // The unmeasured ones are the tail, so the caller has to filter
                // rather than simply truncate.
                watchMinutes = if (id in measured) 118 else null,
                sessionMinutes = if (id in measured) 118 else null,
                communityRating = 7.4,
            )
        }
        every { demo.findCurrentPlanIdsByProvider(any()) } returns mapOf(netflix to netflixPlan, crave to cravePlan)
        every { availability.subscriptionCoverage(any(), any()) } answers {
            val requested = firstArg<Collection<UUID>>().toList()
            AvailabilityDirectory.Coverage(
                byTitle = requested.mapIndexed { index, id ->
                    val providers = buildList {
                        // Netflix carries all but the last; Crave only the last.
                        if (index < requested.lastIndex) add(providerRef(netflix, "Netflix")) else add(providerRef(crave, "Crave"))
                        if (includeFreeProvider && index == 0) add(providerRef(cbcGem, "CBC Gem", isFree = true))
                    }
                    id to providers
                }.toMap(),
                unknownTitleIds = emptySet(),
            )
        }
        givenSession()
    }

    private fun givenSession() {
        every { sessions.issueFor(any(), any()) } returns SessionIssuer.Session(
            accessToken = "token",
            accessTokenExpiresAt = Instant.parse("2026-08-06T12:15:00Z"),
            refreshToken = "refresh",
        )
    }

    private fun providerRef(id: UUID, name: String, isFree: Boolean = false) =
        AvailabilityDirectory.ProviderRef(id, name, name.lowercase().replace(' ', '-'), null, isFree)

    private fun platformProperties() = PlottedProperties(
        security = PlottedProperties.SecurityProperties(
            jwt = PlottedProperties.JwtProperties(secret = "test-secret-not-used-here"),
        ),
    )
}
