package app.plotted.optimisation.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.platform.spi.WatchlistDirectory
import app.plotted.solver.PlanObjective
import app.plotted.solver.PlanOutcome
import app.plotted.solver.PlanRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the optimiser is given, which is what decides what it says.
 *
 * A solver returns the optimum of the model it was handed, so the judgements
 * made here — which titles count, which services can be priced, whose price
 * wins — determine the advice more completely than the model does. These tests
 * are about those judgements rather than about the arithmetic, and they run
 * everywhere because none of them needs CP-SAT: [PlanSolver] is mocked, and the
 * thing under test is the [PlanRequest] that reaches it.
 */
class CancelCultureServiceTest {
    private val watchlists = mockk<WatchlistDirectory>()
    private val titles = mockk<TitleDirectory>()
    private val availability = mockk<AvailabilityDirectory>()
    private val subscriptions = mockk<SubscriptionDirectory>()
    private val solver = mockk<PlanSolver>()
    private val service = CancelCultureService(
        watchlists = watchlists,
        titles = titles,
        availability = availability,
        subscriptions = subscriptions,
        solver = solver,
        properties = TmdbProperties(region = "CA"),
        clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
    )

    private val userId = UUID.randomUUID()
    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val cbcGem = UUID.randomUUID()
    private val obscure = UUID.randomUUID()

    @Test
    fun `an empty watchlist produces no advice rather than advice to cancel everything`() {
        givenHeld(held(netflix, "Netflix", 1_899))
        givenPlans()
        every { watchlists.outstandingItems(userId) } returns emptyList()

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // The optimum over an empty demand set is "cancel Netflix, save $18.99",
        // and it would be presented with the same confidence as real advice.
        // Refusing to run is the whole point.
        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        report.outcome.explanationOrNull()!!.contains("nothing outstanding") shouldBe true
    }

    @Test
    fun `a title that is free to watch cannot argue for a subscription`() {
        val free = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(free, priority = 1))
        givenTitles(free to "On CBC Gem")
        every { availability.subscriptionCoverage(any(), "CA") } returns AvailabilityDirectory.Coverage(
            byTitle = mapOf(free to listOf(providerRef(cbcGem, "CBC Gem", isFree = true))),
            unknownTitleIds = emptySet(),
        )

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // No demand left, so nothing to plan — and the reason is reported, not
        // swallowed. A title nobody has to pay for must never justify a service
        // somebody does.
        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        report.excluded.freeToWatch.single().name shouldBe "On CBC Gem"
        report.excluded.freeToWatch.single().providerNames shouldContainExactly listOf("CBC Gem")
    }

    @Test
    fun `titles nobody has checked are excluded from the denominator, not scored as uncovered`() {
        val known = titleId()
        val unchecked = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(known, priority = 1), entry(unchecked, priority = 1))
        givenTitles(known to "Known", unchecked to "Never checked")
        every { availability.subscriptionCoverage(any(), "CA") } returns AvailabilityDirectory.Coverage(
            byTitle = mapOf(known to listOf(providerRef(netflix, "Netflix"))),
            unknownTitleIds = setOf(unchecked),
        )

        val request = captureRequest()
        service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // Same rule the coverage dashboard follows. Scoring the unchecked title
        // as uncovered would cap every plan at 50% for a reason no plan could
        // fix, and would do it invisibly.
        request.captured.titles.map { it.name } shouldContainExactly listOf("Known")
    }

    @Test
    fun `a title only on a service with no established price is excluded and named`() {
        val stranded = titleId()
        givenHeld()
        // No plan row for the provider carrying it, so there is no price to
        // optimise against and no honest way to invent one.
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(stranded, priority = 2))
        givenTitles(stranded to "Only on something unpriced")
        every { availability.subscriptionCoverage(any(), "CA") } returns AvailabilityDirectory.Coverage(
            byTitle = mapOf(stranded to listOf(providerRef(obscure, "Obscure Channel"))),
            unknownTitleIds = emptySet(),
        )

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        val excluded = report.excluded.unpricedService.single()
        excluded.name shouldBe "Only on something unpriced"
        // Named, so the interface can say which service rather than "a service".
        excluded.providerNames shouldContainExactly listOf("Obscure Channel")
    }

    /**
     * The failure this closes was not a missing price. It was a price that
     * existed, was researched rather than confirmed, and reached the objective
     * function looking exactly like one somebody had typed — because the
     * repository read `COALESCE(actual_price, provider_plans.price)` and nothing
     * downstream could tell which branch it came from.
     */
    @Test
    fun `a researched price is not spent, and the service it belongs to is named`() {
        val wanted = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899, SubscriptionDirectory.PriceProvenance.REFERENCE))
        givenWatchlist(entry(wanted, priority = 1))
        givenTitles(wanted to "On Netflix")
        givenCoverage(wanted to listOf(providerRef(netflix, "Netflix")))

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        // Reported apart from `unpricedService`, because the two ask different
        // things of the user: one is a gap in Plotted's data, this one closes
        // with a single field.
        report.excluded.unpricedService.shouldBeEmpty()
        val excluded = report.excluded.unconfirmedPrice.single()
        excluded.name shouldBe "On Netflix"
        excluded.providerNames shouldContainExactly listOf("Netflix")
    }

    @Test
    fun `a held subscription that never had its price confirmed is not spent either`() {
        val wanted = titleId()
        // The user holds Netflix but never told Plotted what they pay, so the
        // repository handed back the researched figure. Holding a service is not
        // the same as having confirmed its price, and this is the case that
        // would otherwise slip through, because it looks like a real
        // subscription all the way down.
        givenHeld(held(netflix, "Netflix", 1_899, provenance = SubscriptionDirectory.PriceProvenance.REFERENCE))
        givenPlans(plan(netflix, "Netflix", 1_899, SubscriptionDirectory.PriceProvenance.REFERENCE))
        givenWatchlist(entry(wanted, priority = 1))
        givenTitles(wanted to "On Netflix")
        givenCoverage(wanted to listOf(providerRef(netflix, "Netflix")))

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        report.excluded.unconfirmedPrice.single().name shouldBe "On Netflix"
    }

    @Test
    fun `what the user actually pays beats the researched list price`() {
        val wanted = titleId()
        // Grandfathered at $9.99 while the list price is $18.99.
        givenHeld(held(netflix, "Netflix", 999))
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(wanted, priority = 1))
        givenTitles(wanted to "On Netflix")
        givenCoverage(wanted to listOf(providerRef(netflix, "Netflix")))

        val request = captureRequest()
        service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // Minimising against the list price would optimise somebody else's bill.
        val option = request.captured.services.single { it.providerId == netflix }
        option.monthlyCents shouldBe 999
        option.currentlySubscribed shouldBe true
    }

    @Test
    fun `a held service stays in the model even when it carries nothing`() {
        val wanted = titleId()
        // Crave carries nothing on the list. Dropping it would make it
        // uncancellable, which is the one thing this feature exists to do.
        givenHeld(held(crave, "Crave", 2_500))
        givenPlans(plan(netflix, "Netflix", 1_899), plan(crave, "Crave", 2_500))
        givenWatchlist(entry(wanted, priority = 1))
        givenTitles(wanted to "On Netflix")
        givenCoverage(wanted to listOf(providerRef(netflix, "Netflix")))

        val request = captureRequest()
        service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        request.captured.services.map { it.name } shouldContainExactly listOf("Crave", "Netflix")
    }

    @Test
    fun `a service carrying nothing that the user does not hold is left out of the model`() {
        val wanted = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899), plan(crave, "Crave", 2_500))
        givenWatchlist(entry(wanted, priority = 1))
        givenTitles(wanted to "On Netflix")
        givenCoverage(wanted to listOf(providerRef(netflix, "Netflix")))

        val request = captureRequest()
        service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // Pure cost with no possible benefit: the solver would never pick it,
        // and each one costs three booleans a month to carry around.
        request.captured.services.map { it.name } shouldContainExactly listOf("Netflix")
    }

    @Test
    fun `priority 1 outweighs priority 5 by five to one`() {
        val urgent = titleId()
        val minor = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(urgent, priority = 1), entry(minor, priority = 5))
        givenTitles(urgent to "Urgent", minor to "Minor")
        givenCoverage(
            urgent to listOf(providerRef(netflix, "Netflix")),
            minor to listOf(providerRef(netflix, "Netflix")),
        )

        val request = captureRequest()
        service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // Proportional to Priority.weight on the coverage dashboard, so the two
        // screens report the same fraction for the same list. Two features that
        // disagree about what a title is worth is a bug the user experiences as
        // the product contradicting itself.
        request.captured.titles.single { it.name == "Urgent" }.priorityPoints shouldBe 5
        request.captured.titles.single { it.name == "Minor" }.priorityPoints shouldBe 1
    }

    @Test
    fun `a blocked title does not drive a subscription`() {
        val blocked = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899))
        every { watchlists.outstandingItems(userId) } returns listOf(entry(blocked, priority = 1))
        every { watchlists.blockedTitleIds(userId) } returns setOf(blocked)

        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        // Blocked is a hard filter everywhere else; paying for a service to
        // carry something the user asked never to be shown would be absurd.
        (report.outcome is PlanOutcome.NothingToPlan) shouldBe true
        report.excluded.total shouldBe 0
    }

    @Test
    fun `a checked title that nobody carries stays in the model as unreachable`() {
        val stranded = titleId()
        val wanted = titleId()
        givenHeld()
        givenPlans(plan(netflix, "Netflix", 1_899))
        givenWatchlist(entry(stranded, priority = 1), entry(wanted, priority = 1))
        givenTitles(stranded to "Streaming nowhere", wanted to "On Netflix")
        every { availability.subscriptionCoverage(any(), "CA") } returns AvailabilityDirectory.Coverage(
            // Checked, and carried by nobody. Different from never checked, and
            // the difference has to survive into the model: this one is honestly
            // unreachable at any price, so it belongs in the denominator.
            byTitle = mapOf(stranded to emptyList(), wanted to listOf(providerRef(netflix, "Netflix"))),
            unknownTitleIds = emptySet(),
        )

        val request = captureRequest()
        val report = service.plan(userId, CancelCultureService.PlanOptions.DEFAULT)

        request.captured.titles.map { it.name }.sorted() shouldContainExactly listOf("On Netflix", "Streaming nowhere")
        request.captured.titles.single { it.name == "Streaming nowhere" }.availableOn.shouldBeEmpty()
        report.excluded.total shouldBe 0
    }

    // --- helpers ------------------------------------------------------------

    private fun captureRequest(): io.mockk.CapturingSlot<PlanRequest> {
        val slot = slot<PlanRequest>()
        every { solver.solve(capture(slot)) } answers {
            PlanOutcome.Solved(
                months = emptyList(),
                objective = PlanObjective(0.0, 0.0, 0.0, 0.0),
                totalCents = 0,
                covered = emptyList(),
                uncovered = emptyList(),
                sensitivity = emptyList(),
                solveMillis = 0,
                violations = emptyList(),
            )
        }
        return slot
    }

    private fun givenHeld(vararg entries: SubscriptionDirectory.Held) {
        every { subscriptions.currentSubscriptions(userId, any()) } returns entries.toList()
    }

    private fun givenPlans(vararg entries: SubscriptionDirectory.Plan) {
        every { subscriptions.availablePlans("CA") } returns entries.toList()
    }

    private fun givenWatchlist(vararg entries: WatchlistDirectory.WatchlistEntry) {
        every { watchlists.outstandingItems(userId) } returns entries.toList()
        every { watchlists.blockedTitleIds(userId) } returns emptySet()
    }

    private fun givenTitles(vararg entries: Pair<UUID, String>) {
        every { titles.findSummaries(any()) } returns entries.map { (id, name) ->
            TitleDirectory.TitleSummary(
                titleId = id,
                mediaType = "movie",
                name = name,
                releaseYear = 2025,
                posterUrl = null,
                watchMinutes = 120,
                sessionMinutes = 120,
                communityRating = 7.5,
            )
        }
    }

    private fun givenCoverage(vararg entries: Pair<UUID, List<AvailabilityDirectory.ProviderRef>>) {
        every { availability.subscriptionCoverage(any(), "CA") } returns
            AvailabilityDirectory.Coverage(byTitle = entries.toMap(), unknownTitleIds = emptySet())
    }

    // Default USER_ENTERED, because these fixtures exist to exercise the
    // optimiser rather than the trust boundary in front of it. The boundary has
    // its own tests, and they pass REFERENCE explicitly -- a default of
    // REFERENCE here would silently empty every other case in this file.
    private fun held(
        providerId: UUID,
        name: String,
        cents: Long,
        committedMonths: Int = 0,
        provenance: SubscriptionDirectory.PriceProvenance = SubscriptionDirectory.PriceProvenance.USER_ENTERED,
    ) = SubscriptionDirectory.Held(providerId, name, cents, committedMonths, provenance)

    private fun plan(
        providerId: UUID,
        name: String,
        cents: Long,
        provenance: SubscriptionDirectory.PriceProvenance = SubscriptionDirectory.PriceProvenance.USER_ENTERED,
    ) = SubscriptionDirectory.Plan(providerId, name, "Standard", cents, provenance)

    private fun entry(titleId: UUID, priority: Int) = WatchlistDirectory.WatchlistEntry(titleId, priority, desiredByDate = null)

    private fun providerRef(providerId: UUID, name: String, isFree: Boolean = false) =
        AvailabilityDirectory.ProviderRef(providerId, name, name.lowercase().replace(' ', '-'), null, isFree)

    private fun titleId() = UUID.randomUUID()

    private fun PlanOutcome.explanationOrNull(): String? = when (this) {
        is PlanOutcome.NothingToPlan -> explanation
        is PlanOutcome.Infeasible -> explanation
        is PlanOutcome.Solved -> null
    }
}
