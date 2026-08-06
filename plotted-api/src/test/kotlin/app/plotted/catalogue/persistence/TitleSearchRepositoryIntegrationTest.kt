package app.plotted.catalogue.persistence

import app.plotted.generated.jooq.tables.references.MOVIES
import app.plotted.generated.jooq.tables.references.PROVIDERS
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.TITLE_AVAILABILITY
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two hand-written queries that other modules depend on.
 *
 * `findSummaries` builds its `IN` list from the request, and
 * `findDueForAvailabilityRefresh` reaches across a module boundary in SQL to
 * promote watchlisted titles. Neither is type-checked by anything, and the
 * second decides what the nightly job spends its finite budget on.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class TitleSearchRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: TitleSearchRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `findSummaries returns every title asked for, in one query`() {
        val first = givenMovie("Arrival", runtime = 116)
        val second = givenMovie("Dune", runtime = 155)

        val summaries = repository.findSummaries(listOf(first, second))

        summaries.map { it.id } shouldContainExactlyInAnyOrder listOf(first, second)
        summaries.single { it.id == first }.name shouldBe "Arrival"
        summaries.single { it.id == first }.watchMinutes shouldBe 116
    }

    @Test
    fun `findSummaries omits ids that do not exist rather than inventing blanks`() {
        val real = givenMovie("Arrival", runtime = 116)

        val summaries = repository.findSummaries(listOf(real, UUID.randomUUID()))

        // The caller knows what it asked for and can see what came back, which is
        // how a watchlist notices that one of its titles has been deleted.
        summaries.map { it.id } shouldBe listOf(real)
    }

    @Test
    fun `findSummaries with nothing to look up issues no statement`() {
        repository.findSummaries(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `findSummaries tolerates duplicate ids`() {
        val id = givenMovie("Arrival", runtime = 116)

        // A caller assembling ids from several sources can repeat one. The
        // result is a set of titles, not one row per request.
        repository.findSummaries(listOf(id, id, id)).map { it.id } shouldBe listOf(id)
    }

    @Test
    fun `a watchlisted title is refreshed before a staler one nobody has listed`() {
        val ignored = givenMovie("Nobody wants this", runtime = 90)
        val wanted = givenMovie("Someone is waiting", runtime = 90)

        // The ignored title is the more overdue of the two by a wide margin, so
        // pure staleness ordering would put it first.
        givenLastChecked(ignored, OffsetDateTime.now().minusDays(30))
        givenLastChecked(wanted, OffsetDateTime.now().minusHours(1))
        givenWatchlistItem(wanted, status = "pending")

        val due = repository.findDueForAvailabilityRefresh("CA", 50).map { it.titleId }

        // Section 17: watchlist titles daily, the long tail opportunistically.
        due.indexOf(wanted) shouldBe 0
        (due.indexOf(wanted) < due.indexOf(ignored)) shouldBe true
    }

    @Test
    fun `a finished watchlist item does not hold refresh priority`() {
        val finished = givenMovie("Already watched", runtime = 90)
        val stale = givenMovie("Genuinely overdue", runtime = 90)

        givenLastChecked(finished, OffsetDateTime.now().minusHours(1))
        givenLastChecked(stale, OffsetDateTime.now().minusDays(30))
        givenWatchlistItem(finished, status = "completed")

        val due = repository.findDueForAvailabilityRefresh("CA", 50).map { it.titleId }

        // The nightly batch is finite, so a title promoted is a title demoted.
        // Somebody who has already watched it is not waiting on it.
        (due.indexOf(stale) < due.indexOf(finished)) shouldBe true
    }

    // --- helpers -----------------------------------------------------------

    private fun givenMovie(name: String, runtime: Int): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "SEARCH-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, name)
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        dsl.insertInto(MOVIES)
            .set(MOVIES.TITLE_ID, id)
            .set(MOVIES.RUNTIME_MINUTES, runtime)
            .execute()
        return id
    }

    /**
     * An availability row exists only to date the last check; its content is
     * irrelevant here.
     *
     * Written through the typed API rather than as one plain-SQL string. In
     * plain SQL jOOQ has no target type for a bind, so an [OffsetDateTime] goes
     * across as `character varying` and Postgres rejects it against a
     * `timestamptz` column. Only `validity` needs the raw fragment, because it
     * is the one column the generator never sees.
     */
    private fun givenLastChecked(titleId: UUID, checkedAt: OffsetDateTime) {
        val providerId = dsl.select(PROVIDERS.ID)
            .from(PROVIDERS)
            .where(PROVIDERS.SLUG.eq("crave"))
            .fetchOne()!!
            .value1()!!

        dsl.insertInto(TITLE_AVAILABILITY)
            .set(TITLE_AVAILABILITY.ID, UUID.randomUUID())
            .set(TITLE_AVAILABILITY.TITLE_ID, titleId)
            .set(TITLE_AVAILABILITY.PROVIDER_ID, providerId)
            .set(TITLE_AVAILABILITY.REGION_CODE, "CA")
            .set(TITLE_AVAILABILITY.ACCESS_TYPE, "subscription")
            .set(TITLE_AVAILABILITY.SOURCE, "tmdb:justwatch")
            .set(TITLE_AVAILABILITY.SOURCE_CHECKED_AT, checkedAt)
            .set(TITLE_AVAILABILITY.CONFIDENCE, BigDecimal("1.000"))
            .set(TITLE_AVAILABILITY.ACTIVE, true)
            .set(
                DSL.field(DSL.name("validity"), String::class.java),
                DSL.field("daterange(CURRENT_DATE, NULL)", String::class.java),
            )
            .execute()
    }

    private fun givenWatchlistItem(titleId: UUID, status: String) {
        val userId = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, userId)
            .set(USERS.EMAIL, "refresh-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()

        val watchlistId = UUID.randomUUID()
        dsl.insertInto(WATCHLISTS)
            .set(WATCHLISTS.ID, watchlistId)
            .set(WATCHLISTS.USER_ID, userId)
            .set(WATCHLISTS.NAME, "My list")
            .set(WATCHLISTS.IS_DEFAULT, true)
            .set(WATCHLISTS.VISIBILITY, "private")
            .execute()

        dsl.insertInto(WATCHLIST_ITEMS)
            .set(WATCHLIST_ITEMS.ID, UUID.randomUUID())
            .set(WATCHLIST_ITEMS.WATCHLIST_ID, watchlistId)
            .set(WATCHLIST_ITEMS.TITLE_ID, titleId)
            .set(WATCHLIST_ITEMS.PRIORITY, 1.toShort())
            .set(WATCHLIST_ITEMS.STATUS, status)
            .set(WATCHLIST_ITEMS.SOURCE, "manual")
            .execute()
    }

    companion object {
        private val SEQUENCE = AtomicInteger(5_000_000)

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
