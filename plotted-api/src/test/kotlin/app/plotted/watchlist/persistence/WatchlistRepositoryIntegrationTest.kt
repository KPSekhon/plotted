package app.plotted.watchlist.persistence

import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.watchlist.domain.Priority
import app.plotted.watchlist.domain.WatchStatus
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

    // --- helpers -----------------------------------------------------------

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "watchlist-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    private fun givenTitle(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "WATCHLIST-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, "A Title")
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
