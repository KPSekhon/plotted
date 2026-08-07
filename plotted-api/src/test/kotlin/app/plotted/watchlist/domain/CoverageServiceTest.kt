package app.plotted.watchlist.domain

import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.WatchlistRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Coverage is the number that will eventually tell someone to cancel a service,
 * so what it counts matters more than that it computes. These tests are about
 * the definition rather than the arithmetic: which items are in the denominator,
 * how priority weighting changes the ranking, and what happens to titles nobody
 * has checked.
 */
class CoverageServiceTest {
    private val watchlists = mockk<WatchlistRepository>()
    private val availability = mockk<AvailabilityDirectory>()
    private val titles = mockk<TitleDirectory>(relaxed = true)
    private val service = CoverageService(watchlists, availability, titles)

    private val userId = UUID.randomUUID()
    private val watchlistId = UUID.randomUUID()

    private val netflix = provider("Netflix", "netflix")
    private val crave = provider("Crave", "crave")

    @Test
    fun `a service carrying one urgent title outranks one carrying several minor ones`() {
        val urgent = titleId()
        val minorA = titleId()
        val minorB = titleId()
        val minorC = titleId()

        givenItems(
            item(urgent, priority = 1),
            item(minorA, priority = 5),
            item(minorB, priority = 5),
            item(minorC, priority = 5),
        )
        givenCoverage(
            urgent to listOf(netflix),
            minorA to listOf(crave),
            minorB to listOf(crave),
            minorC to listOf(crave),
        )

        val report = service.forUser(userId, "CA")

        // Crave carries three titles to Netflix's one, so an unweighted count
        // would rank Crave first. Weighted by priority the answer inverts:
        // 1.0 against 3 x 0.2. This assertion is the whole reason the weighting
        // exists, and it fails loudly if anyone "simplifies" it to a count.
        report.providers.first().slug shouldBe "netflix"
        report.providers.first().titleCount shouldBe 1
        report.providers.last().titleCount shouldBe 3
    }

    @Test
    fun `completed and abandoned items do not count`() {
        val outstanding = titleId()
        val finished = titleId()

        givenItems(
            item(outstanding, priority = 3),
            item(finished, priority = 1, status = WatchStatus.COMPLETED),
        )
        // Only the outstanding title is ever asked about.
        every { availability.subscriptionCoverage(listOf(outstanding), "CA") } returns
            AvailabilityDirectory.Coverage(mapOf(outstanding to listOf(netflix)), emptySet())

        val report = service.forUser(userId, "CA")

        report.consideredTitles shouldBe 1
        report.providers.single().titleCount shouldBe 1
    }

    @Test
    fun `titles nobody has checked are reported rather than scored as uncovered`() {
        val known = titleId()
        val neverChecked = titleId()

        givenItems(item(known, priority = 1), item(neverChecked, priority = 1))
        every { availability.subscriptionCoverage(any(), "CA") } returns
            AvailabilityDirectory.Coverage(
                byTitle = mapOf(known to listOf(netflix)),
                unknownTitleIds = setOf(neverChecked),
            )

        val report = service.forUser(userId, "CA")

        // The denominator excludes the unchecked title, so Netflix covers
        // everything Plotted actually knows about -- 100%, not 50%. Reporting
        // 50% would blame Netflix for a gap in Plotted's own data, and would do
        // it invisibly.
        report.consideredTitles shouldBe 1
        report.unknownTitles shouldBe 1
        report.providers.single().weightedShare shouldBe (1.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `a title carried by two services counts for both`() {
        val shared = titleId()
        givenItems(item(shared, priority = 2))
        givenCoverage(shared to listOf(netflix, crave))

        val report = service.forUser(userId, "CA")

        // Shares are per-service and deliberately do not sum to 1: the question
        // is "how much would this one subscription get me", not "how is the
        // watchlist divided up".
        report.providers.size shouldBe 2
        report.providers.forEach { it.weightedShare shouldBe (1.0 plusOrMinus TOLERANCE) }
    }

    @Test
    fun `an empty watchlist reports nothing rather than dividing by zero`() {
        every { watchlists.findOrCreateDefault(userId) } returns watchlist()
        every { watchlists.findItems(watchlistId) } returns emptyList()

        val report = service.forUser(userId, "CA")

        report.consideredTitles shouldBe 0
        report.totalWeight shouldBe 0.0
        report.providers shouldBe emptyList()
    }

    @Test
    fun `a watchlist whose titles are all unchecked yields no providers and no division by zero`() {
        val unchecked = titleId()
        givenItems(item(unchecked, priority = 1))
        every { availability.subscriptionCoverage(any(), "CA") } returns
            AvailabilityDirectory.Coverage(byTitle = emptyMap(), unknownTitleIds = setOf(unchecked))

        val report = service.forUser(userId, "CA")

        report.consideredTitles shouldBe 0
        report.unknownTitles shouldBe 1
        report.providers shouldBe emptyList()
    }

    // --- helpers -----------------------------------------------------------

    private fun givenItems(vararg items: WatchlistItem) {
        every { watchlists.findOrCreateDefault(userId) } returns watchlist()
        every { watchlists.findItems(watchlistId) } returns items.toList()
    }

    private fun givenCoverage(vararg entries: Pair<UUID, List<AvailabilityDirectory.ProviderRef>>) {
        every { availability.subscriptionCoverage(any(), "CA") } returns
            AvailabilityDirectory.Coverage(byTitle = entries.toMap(), unknownTitleIds = emptySet())
    }

    private fun watchlist() = Watchlist(id = watchlistId, name = "My list", isDefault = true, createdAt = Instant.EPOCH)

    private fun item(titleId: UUID, priority: Int, status: WatchStatus = WatchStatus.PENDING) = WatchlistItem(
        id = UUID.randomUUID(),
        titleId = titleId,
        priority = Priority(priority),
        status = status,
        addedAt = Instant.EPOCH,
        desiredByDate = null,
        notes = null,
        // Derived rather than passed in, so the fixture cannot build a row the
        // schema would reject: only a completed item may carry a completion time.
        completedAt = if (status == WatchStatus.COMPLETED) Instant.EPOCH else null,
    )

    private fun provider(name: String, slug: String) =
        AvailabilityDirectory.ProviderRef(providerId = UUID.randomUUID(), name = name, slug = slug, logoUrl = null, isFree = false)

    private fun titleId() = UUID.randomUUID()

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
