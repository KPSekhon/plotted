package app.plotted.watchlist.persistence

import app.plotted.catalogue.persistence.TitleSearchRepository
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import app.plotted.watchlist.domain.Priority
import app.plotted.watchlist.domain.WatchStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watchlist SQL, against a real database.
 *
 * Written because none of it had ever been executed: the queries here rely on a
 * partial unique index and a composite unique constraint that only exist in
 * Postgres, and both are load-bearing for correctness rather than performance.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class WatchlistRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: WatchlistRepository

    @Autowired
    private lateinit var dsl: DSLContext

    /**
     * Catalogue search, autowired here so the rule that blocking must not touch
     * it is asserted rather than only written down in a comment.
     */
    @Autowired
    private lateinit var search: TitleSearchRepository

    @Test
    fun `the default watchlist is created once and returned thereafter`() {
        val userId = givenUser()

        val first = repository.findOrCreateDefault(userId)
        val second = repository.findOrCreateDefault(userId)

        // Not merely equal ids: the partial unique index on (user_id) WHERE
        // is_default means a second row could not exist, and this is what proves
        // the second call reads rather than inserts.
        second.id shouldBe first.id
        countWatchlists(userId) shouldBe 1
    }

    @Test
    fun `two users get separate default lists`() {
        val one = repository.findOrCreateDefault(givenUser())
        val other = repository.findOrCreateDefault(givenUser())

        (one.id == other.id) shouldBe false
    }

    @Test
    fun `adding the same title twice returns the original row rather than failing`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val titleId = givenTitle()

        val first = repository.addItem(watchlist.id, titleId, Priority(2), null, null)
        val second = repository.addItem(watchlist.id, titleId, Priority(5), null, "changed my mind")

        // The unique constraint fires and the catch re-reads. Adding something
        // twice is a slip, not an error worth a 409 -- and the original intent
        // wins rather than being silently overwritten.
        second.id shouldBe first.id
        second.priority shouldBe Priority(2)
        repository.findItems(watchlist.id).size shouldBe 1
    }

    @Test
    fun `items come back highest priority first`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        repository.addItem(watchlist.id, givenTitle(), Priority(5), null, null)
        repository.addItem(watchlist.id, givenTitle(), Priority(1), null, null)
        repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        // 1 is the highest, so ascending is correct. If this ever reverses, the
        // coverage weighting silently inverts too.
        repository.findItems(watchlist.id).map { it.priority.value } shouldBe listOf(1, 3, 5)
    }

    @Test
    fun `a partial update leaves untouched columns alone`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(
            watchlist.id,
            givenTitle(),
            Priority(3),
            LocalDate.of(2026, 12, 24),
            "before the holidays",
        )

        repository.updateItem(
            watchlistId = watchlist.id,
            itemId = item.id,
            priority = Priority(1),
            status = null,
            desiredByDate = null,
            clearDesiredByDate = false,
            notes = null,
            clearNotes = false,
        ) shouldBe true

        val updated = repository.findItem(watchlist.id, item.id).shouldNotBeNull()
        updated.priority shouldBe Priority(1)
        // Null meant "leave alone", not "erase".
        updated.desiredByDate shouldBe LocalDate.of(2026, 12, 24)
        updated.notes shouldBe "before the holidays"
        updated.status shouldBe WatchStatus.PENDING
    }

    @Test
    fun `clearing a field is distinguishable from not mentioning it`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), LocalDate.of(2026, 12, 24), "note")

        repository.updateItem(
            watchlistId = watchlist.id,
            itemId = item.id,
            priority = null,
            status = null,
            desiredByDate = null,
            clearDesiredByDate = true,
            notes = null,
            clearNotes = false,
        ) shouldBe true

        val updated = repository.findItem(watchlist.id, item.id).shouldNotBeNull()
        updated.desiredByDate.shouldBeNull()
        // The flag cleared one field and left the other, which is the whole
        // reason the flags exist alongside the nullable parameters.
        updated.notes shouldBe "note"
    }

    @Test
    fun `an empty patch reports whether the item exists rather than failing`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        // An UPDATE with an empty SET is invalid SQL, so this path must not reach
        // the database at all. PATCH {} is a request a client can legitimately
        // make, and the answer is "yes, it is there".
        repository.updateItem(watchlist.id, item.id, null, null, null, false, null, false) shouldBe true
        repository.updateItem(watchlist.id, UUID.randomUUID(), null, null, null, false, null, false) shouldBe false
    }

    @Test
    fun `one user cannot touch another user's item`() {
        val mine = repository.findOrCreateDefault(givenUser())
        val theirs = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(theirs.id, givenTitle(), Priority(1), null, null)

        // Scoped by watchlist id in the WHERE clause, so this is not an
        // authorisation check that could be forgotten -- it is arithmetic.
        repository.findItem(mine.id, item.id).shouldBeNull()
        repository.updateItem(mine.id, item.id, Priority(5), null, null, false, null, false) shouldBe false
        repository.removeItem(mine.id, item.id) shouldBe false
        repository.findItem(theirs.id, item.id).shouldNotBeNull()
    }

    @Test
    fun `removing an item removes exactly one`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val keep = repository.addItem(watchlist.id, givenTitle(), Priority(1), null, null)
        val drop = repository.addItem(watchlist.id, givenTitle(), Priority(2), null, null)

        repository.removeItem(watchlist.id, drop.id) shouldBe true

        repository.findItems(watchlist.id).map { it.id } shouldBe listOf(keep.id)
        repository.removeItem(watchlist.id, drop.id) shouldBe false
    }

    @Test
    fun `every status round trips through the database`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        // The CHECK constraint lists the accepted values, and the enum lists
        // them again. This is the test that notices when the two drift apart.
        WatchStatus.entries.forEach { status ->
            repository.updateItem(watchlist.id, item.id, null, status, null, false, null, false) shouldBe true
            repository.findItem(watchlist.id, item.id)!!.status shouldBe status
        }
    }

    @Test
    fun `completing an item records when it happened`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        repository.findItem(watchlist.id, item.id)!!.completedAt.shouldBeNull()

        at(COMPLETED_ON).updateItem(watchlist.id, item.id, null, WatchStatus.COMPLETED, null, false, null, false)

        repository.findItem(watchlist.id, item.id)!!.completedAt shouldBe COMPLETED_ON
    }

    @Test
    fun `re-saving a completed item does not move its completion time`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)
        at(COMPLETED_ON).updateItem(watchlist.id, item.id, null, WatchStatus.COMPLETED, null, false, null, false)

        // A later edit that happens to mention the status it already has. The
        // clock is years ahead, so a `SET completed_at = now()` that failed to
        // ask whether the row was *already* completed would be unmistakable here
        // -- which is the point. Against the real system clock the two writes
        // land milliseconds apart and this assertion could pass without the CASE
        // expression existing at all.
        at(MUCH_LATER).updateItem(watchlist.id, item.id, Priority(1), WatchStatus.COMPLETED, null, false, "rewatched", false)

        val updated = repository.findItem(watchlist.id, item.id).shouldNotBeNull()
        updated.priority shouldBe Priority(1)
        updated.notes shouldBe "rewatched"
        // The edit landed; the outcome's date did not move with it.
        updated.completedAt shouldBe COMPLETED_ON
    }

    @Test
    fun `leaving completed clears the time, and completing again records the new one`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        at(COMPLETED_ON).updateItem(watchlist.id, item.id, null, WatchStatus.COMPLETED, null, false, null, false)
        at(MUCH_LATER).updateItem(watchlist.id, item.id, null, WatchStatus.IN_PROGRESS, null, false, null, false)

        // An item put back in progress is outstanding again, and a completion
        // time left behind would read to every later query as a finished item
        // that is somehow still waiting.
        repository.findItem(watchlist.id, item.id)!!.completedAt.shouldBeNull()

        at(MUCH_LATER).updateItem(watchlist.id, item.id, null, WatchStatus.COMPLETED, null, false, null, false)
        repository.findItem(watchlist.id, item.id)!!.completedAt shouldBe MUCH_LATER
    }

    @Test
    fun `the database refuses a completion time on an item that is not completed`() {
        val watchlist = repository.findOrCreateDefault(givenUser())
        val item = repository.addItem(watchlist.id, givenTitle(), Priority(3), null, null)

        // Run against the constraint directly rather than through the repository,
        // because the repository is the thing being protected. A guard nothing
        // has ever tried to violate is a guard nobody knows is there.
        shouldThrow<Exception> {
            dsl.update(WATCHLIST_ITEMS)
                .set(WATCHLIST_ITEMS.COMPLETED_AT, OffsetDateTime.now())
                .where(WATCHLIST_ITEMS.ID.eq(item.id))
                .execute()
        }
    }

    @Test
    fun `a blocked title is readable through the id set the recommenders filter on`() {
        val userId = givenUser()
        val titleId = givenTitle()

        repository.blockedTitleIds(userId).shouldBeEmpty()

        repository.block(userId, titleId, "seen it, hated it")

        // This is the whole point of the change: the reader has existed since V6
        // and both recommenders have filtered on it since phase 4, but nothing
        // could put a row in the table, so the filter could never fire.
        repository.blockedTitleIds(userId) shouldBe setOf(titleId)
    }

    @Test
    fun `blocking twice keeps the first reason and the first timestamp`() {
        val userId = givenUser()
        val titleId = givenTitle()

        val first = at(COMPLETED_ON).block(userId, titleId, "too violent")
        val second = at(MUCH_LATER).block(userId, titleId, null)

        // Idempotent like addItem, and the original wins. A second request
        // carrying no reason must not erase the reason the first one gave.
        second.reason shouldBe "too violent"
        second.createdAt shouldBe first.createdAt
        repository.blockedTitles(userId).size shouldBe 1
    }

    @Test
    fun `unblocking removes the block and says whether there was one`() {
        val userId = givenUser()
        val titleId = givenTitle()
        repository.block(userId, titleId, null)

        repository.unblock(userId, titleId) shouldBe true
        repository.blockedTitleIds(userId).shouldBeEmpty()

        // False rather than true, so the API can tell an undo from a no-op.
        repository.unblock(userId, titleId) shouldBe false
    }

    @Test
    fun `one user's block does not reach another user`() {
        val mine = givenUser()
        val theirs = givenUser()
        val titleId = givenTitle()

        repository.block(theirs, titleId, null)

        // Blocking is a statement about one person's taste. Scoped in the WHERE
        // clause, as everything else here is.
        repository.blockedTitleIds(mine).shouldBeEmpty()
        repository.unblock(mine, titleId) shouldBe false
        repository.blockedTitleIds(theirs) shouldBe setOf(titleId)
    }

    @Test
    fun `blocking a title does not hide it from catalogue search`() {
        val userId = givenUser()
        val titleId = givenTitle(name = "Marmalade Verdict")
        repository.block(userId, titleId, "not for me")

        // Search takes no user id at all, so it *cannot* filter -- and that is
        // deliberate rather than an oversight. Someone searching for something
        // they blocked should still find it: hiding it reads as a missing
        // catalogue entry rather than a preference being honoured, and it would
        // leave them no way to change their mind. This test exists so that a
        // later "fix" wiring blocking into search fails here instead of shipping.
        search.search("Marmalade Verdict", 10, null).map { it.id } shouldBe listOf(titleId)
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The repository, seeing a fixed instant.
     *
     * Built here rather than injected because these tests need two different
     * "now"s in one scenario, and the autowired instance carries the system
     * clock. Everything else about it is the bean under test.
     */
    private fun at(instant: Instant) = WatchlistRepository(dsl, Clock.fixed(instant, ZoneOffset.UTC))

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "watchlist-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    private fun givenTitle(name: String = "A Title"): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "WATCHLIST-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, name)
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        return id
    }

    private fun countWatchlists(userId: UUID): Int = dsl.fetchCount(
        app.plotted.generated.jooq.tables.references.WATCHLISTS,
        app.plotted.generated.jooq.tables.references.WATCHLISTS.USER_ID.eq(userId),
    )

    companion object {
        private val SEQUENCE = AtomicInteger(3_000_000)

        private val COMPLETED_ON: Instant = Instant.parse("2026-08-07T21:30:00Z")

        /** Far enough ahead that a timestamp which moved could not be mistaken for one that did not. */
        private val MUCH_LATER: Instant = Instant.parse("2031-02-14T09:00:00Z")

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
