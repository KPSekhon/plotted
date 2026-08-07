package app.plotted.watchlist.domain

import app.plotted.platform.error.NotFoundException
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.WatchlistRepository
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
 * Blocking, and what it is careful not to do.
 *
 * The repository's side of this is covered against Postgres in
 * [app.plotted.watchlist.persistence.WatchlistRepositoryIntegrationTest]. What is
 * left here is plain Kotlin -- which entries get marked, and what happens to the
 * watchlist when someone blocks something already on it -- and those are
 * decisions rather than queries, so a mock is the right instrument for once.
 */
class WatchlistServiceTest {
    private val watchlists = mockk<WatchlistRepository>(relaxed = true)
    private val titles = mockk<TitleDirectory>()
    private val service = WatchlistService(watchlists, titles)

    private val userId = UUID.randomUUID()
    private val watchlistId = UUID.randomUUID()
    private val blockedTitle = UUID.randomUUID()
    private val ordinaryTitle = UUID.randomUUID()

    @Test
    fun `a blocked title stays on the list and is marked`() {
        givenList(listOf(item(ordinaryTitle), item(blockedTitle)), blocked = setOf(blockedTitle))

        val entries = service.list(userId).entries

        // Both recommenders already skip blocked ids. An unmarked blocked entry
        // would therefore sit on the list being quietly passed over forever, and
        // the interface would be showing something it has no intention of ever
        // offering -- with nothing on screen to explain why.
        entries.map { it.item.titleId } shouldBe listOf(ordinaryTitle, blockedTitle)
        entries.single { it.item.titleId == ordinaryTitle }.blocked shouldBe false
        entries.single { it.item.titleId == blockedTitle }.blocked shouldBe true
    }

    @Test
    fun `blocking leaves the watchlist entry alone`() {
        every { titles.findSummaries(listOf(blockedTitle)) } returns listOf(summary(blockedTitle))
        every { watchlists.block(userId, blockedTitle, "too grim") } returns
            BlockedTitle(blockedTitle, "too grim", Instant.EPOCH)

        service.block(userId, blockedTitle, "too grim")

        // Blocking something already on the list is a contradiction the user is
        // allowed to hold. Resolving it by deleting their row would destroy the
        // priority and notes they wrote, as a side effect of a different request.
        verify(exactly = 0) { watchlists.removeItem(any(), any()) }
    }

    @Test
    fun `blocking a title that is not in the catalogue is a 404, not a foreign key error`() {
        val unknown = UUID.randomUUID()
        every { titles.findSummaries(listOf(unknown)) } returns emptyList()

        shouldThrow<NotFoundException> { service.block(userId, unknown, null) }

        verify(exactly = 0) { watchlists.block(any(), any(), any()) }
    }

    @Test
    fun `a block whose title has been deleted is still returned, so it can be lifted`() {
        every { watchlists.blockedTitles(userId) } returns
            listOf(BlockedTitle(blockedTitle, null, Instant.EPOCH))
        every { titles.findSummaries(listOf(blockedTitle)) } returns emptyList()

        val entry = service.listBlocked(userId).single()

        // Dropping it would leave a block that still filters recommendations and
        // that nothing in the interface can show you or let you undo.
        entry.blocked.titleId shouldBe blockedTitle
        entry.title.shouldBeNull()
    }

    @Test
    fun `unblocking something that was not blocked is a 404`() {
        every { watchlists.unblock(userId, ordinaryTitle) } returns false

        shouldThrow<NotFoundException> { service.unblock(userId, ordinaryTitle) }
    }

    // --- helpers -----------------------------------------------------------

    private fun givenList(items: List<WatchlistItem>, blocked: Set<UUID>) {
        every { watchlists.findOrCreateDefault(userId) } returns
            Watchlist(watchlistId, "My list", isDefault = true, createdAt = Instant.EPOCH)
        every { watchlists.findItems(watchlistId) } returns items
        every { watchlists.blockedTitleIds(userId) } returns blocked
        every { titles.findSummaries(items.map { it.titleId }) } returns items.map { summary(it.titleId) }
    }

    private fun item(titleId: UUID) = WatchlistItem(
        id = UUID.randomUUID(),
        titleId = titleId,
        priority = Priority(3),
        status = WatchStatus.PENDING,
        addedAt = Instant.EPOCH,
        desiredByDate = null,
        notes = null,
        completedAt = null,
    )

    private fun summary(titleId: UUID) = TitleDirectory.TitleSummary(
        titleId = titleId,
        mediaType = "movie",
        name = "A Title",
        releaseYear = 2026,
        posterUrl = null,
        watchMinutes = 100,
        communityRating = null,
    )
}
