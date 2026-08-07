package app.plotted.demo.persistence

import app.plotted.availability.domain.AccessType
import app.plotted.availability.persistence.AvailabilityRepository
import app.plotted.availability.persistence.ProviderRepository
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.TITLE_AVAILABILITY
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.USER_SUBSCRIPTIONS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import app.plotted.subscriptions.domain.BillingPeriod
import app.plotted.subscriptions.persistence.SubscriptionRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Demo fixtures against a real database.
 *
 * Everything here writes to tables four other modules own, using SQL that
 * nothing else in the build exercises. Two things in particular are only ever
 * tested here: the `upper_inf(validity)` predicate behind plan lookup, which the
 * jOOQ generator cannot see, and the `ON DELETE CASCADE` chain the sweep relies
 * on to be a single statement rather than five.
 *
 * The sweep is the one worth being paranoid about. It is a `DELETE FROM users`
 * whose only guard is `is_demo`, and if that predicate were ever wrong it would
 * delete real accounts and report success.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class DemoRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: DemoRepository

    @Autowired
    private lateinit var subscriptions: SubscriptionRepository

    @Autowired
    private lateinit var providers: ProviderRepository

    @Autowired
    private lateinit var availability: AvailabilityRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `a demo user is created with no password and an expiry`() {
        val userId = repository.createUser("Demo visitor", "CA", Duration.ofHours(24))

        val row = dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne().shouldNotBeNull()
        // Null rather than a hash of something guessable: there is no password
        // that works, so the login path cannot reach this account at all.
        row.passwordHash shouldBe null
        row.isDemo shouldBe true
        row.expiresAt.shouldNotBeNull()
        row.email!!.endsWith("@demo.plotted.invalid") shouldBe true
    }

    @Test
    fun `the schema refuses a real account with an expiry`() {
        val userId = UUID.randomUUID()

        val rejected = try {
            dsl.insertInto(USERS)
                .set(USERS.ID, userId)
                .set(USERS.EMAIL, "real-$userId@example.com")
                .set(USERS.DISPLAY_NAME, "Real person")
                .set(USERS.IS_DEMO, false)
                .set(USERS.EXPIRES_AT, OffsetDateTime.now().plusHours(1))
                .execute()
            false
        } catch (_: Exception) {
            true
        }

        // The sweep deletes on expiry. A bug that set one on a real account
        // would destroy a real user's data and report success, so the database
        // refuses to represent the state at all.
        rejected shouldBe true
    }

    @Test
    fun `the schema refuses a demo account with no expiry`() {
        val userId = UUID.randomUUID()

        val rejected = try {
            dsl.insertInto(USERS)
                .set(USERS.ID, userId)
                .set(USERS.EMAIL, "demo-$userId@demo.plotted.invalid")
                .set(USERS.DISPLAY_NAME, "Demo visitor")
                .set(USERS.IS_DEMO, true)
                .execute()
            false
        } catch (_: Exception) {
            true
        }

        // The other direction, and it needs stating separately: a demo account
        // that never expires is never swept, and the table grows forever.
        rejected shouldBe true
    }

    @Test
    fun `the sweep removes expired demo accounts and everything hanging off them`() {
        val expired = repository.createUser("Demo visitor", "CA", Duration.ofHours(-1))
        val live = repository.createUser("Demo visitor", "CA", Duration.ofHours(24))
        val real = insertRealUser()

        val watchlistId = repository.createWatchlist(expired, "Demo list")
        val titleId = insertTitle("Swept title")
        repository.insertWatchlistItem(watchlistId, titleId, priority = 1, desiredBy = null)

        val deleted = repository.deleteExpired()

        deleted shouldBe 1
        dsl.fetchExists(USERS, USERS.ID.eq(expired)) shouldBe false
        dsl.fetchExists(USERS, USERS.ID.eq(live)) shouldBe true
        // The guard that matters. If `is_demo` were ever dropped from the WHERE
        // clause this is the assertion that notices.
        dsl.fetchExists(USERS, USERS.ID.eq(real)) shouldBe true
        // One statement, not five: users is the root of the cascade.
        dsl.fetchExists(WATCHLIST_ITEMS, WATCHLIST_ITEMS.WATCHLIST_ID.eq(watchlistId)) shouldBe false
    }

    @Test
    fun `candidate titles are those a subscription service carries here`() {
        val carried = insertTitle("Carried in Canada")
        val rented = insertTitle("Rental only")
        val elsewhere = insertTitle("Carried in the US")
        val netflix = providers.findBySlug("netflix")!!

        insertAvailability(carried, netflix.id, "CA", AccessType.SUBSCRIPTION)
        insertAvailability(rented, netflix.id, "CA", AccessType.RENT)
        insertAvailability(elsewhere, netflix.id, "US", AccessType.SUBSCRIPTION)

        val candidates = repository.findCandidateTitleIds("CA", 100)

        candidates shouldContain carried
        // A rental is not covered by a subscription, and counting it would make
        // every service look better than it is.
        candidates shouldNotContain rented
        candidates shouldNotContain elsewhere
    }

    @Test
    fun `an inactive availability row does not make a title a candidate`() {
        val withdrawn = insertTitle("Left the service")
        val netflix = providers.findBySlug("netflix")!!
        insertAvailability(withdrawn, netflix.id, "CA", AccessType.SUBSCRIPTION, active = false)

        repository.findCandidateTitleIds("CA", 100) shouldNotContain withdrawn
    }

    @Test
    fun `plan lookup returns the cheapest open price per provider`() {
        val crave = providers.findBySlug("crave")!!
        val dear = subscriptions.findOrCreatePlan(
            crave.id,
            "CA",
            "Demo Premium ${UUID.randomUUID()}",
            BillingPeriod.MONTHLY,
            BigDecimal("29.99"),
            "CAD",
        )
        val cheap = subscriptions.findOrCreatePlan(
            crave.id,
            "CA",
            "Demo Basic ${UUID.randomUUID()}",
            BillingPeriod.MONTHLY,
            BigDecimal("9.99"),
            "CAD",
        )

        val plans = repository.findCurrentPlanIdsByProvider("CA")

        // Which tier to buy is not the question this product answers, so the
        // demo takes the cheapest rather than offering a choice nobody asked for.
        //
        // Asserted directly. The first version said `== cheap || != dear`, which
        // is true for *any* value including null — a check with no way to fail,
        // which is the thing this project keeps finding and this file exists to
        // avoid.
        plans[crave.id] shouldBe cheap
        (plans[crave.id] == dear) shouldBe false
    }

    @Test
    fun `cannot_cancel is derived from the commitment date, never passed in`() {
        val userId = repository.createUser("Demo visitor", "CA", Duration.ofHours(24))
        val netflix = providers.findBySlug("netflix")!!
        val planId = subscriptions.findOrCreatePlan(
            netflix.id,
            "CA",
            "Demo ${UUID.randomUUID()}",
            BillingPeriod.MONTHLY,
            BigDecimal("18.99"),
            "CAD",
        )

        repository.insertSubscription(userId, planId, LocalDate.now().minusMonths(4), LocalDate.now().plusMonths(2))

        val row = dsl.selectFrom(USER_SUBSCRIPTIONS).where(USER_SUBSCRIPTIONS.USER_ID.eq(userId)).fetchOne().shouldNotBeNull()
        // A flag that disagrees with the date beside it is a bug the demo would
        // be demonstrating rather than avoiding.
        row.cannotCancel shouldBe true
        row.actualPrice shouldBe null
        // Renewal has to be in the future even though the start date is not.
        (row.renewsOn!!.isAfter(LocalDate.now())) shouldBe true
    }

    // --- fixtures -----------------------------------------------------------

    private fun insertRealUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "real-$id@example.com")
            .set(USERS.DISPLAY_NAME, "Real person")
            .execute()
        return id
    }

    private fun insertTitle(name: String): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "demo-${UUID.randomUUID()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, name)
            .execute()
        return id
    }

    /**
     * Opened through the repository, not hand-written.
     *
     * The first version of this built the row with the typed jOOQ API directly,
     * which cannot work: `title_availability.validity` is a `DATERANGE NOT NULL`
     * with no default, and it is fenced out of the generator's view, so jOOQ
     * does not know it exists. Every insert here would have failed on a
     * not-null violation — on Postgres only, which is to say only in CI.
     *
     * This is the phase 3 lesson arriving from a new direction: *write fixtures
     * the same way the repository writes*. There, a fixture took a shortcut the
     * real code avoided and reintroduced the bug the real code was designed
     * around. Here, the shortcut was avoiding the repository entirely, and the
     * column it could not see was the one the schema requires.
     */
    private fun insertAvailability(titleId: UUID, providerId: UUID, region: String, accessType: AccessType, active: Boolean = true) {
        val id = availability.open(
            titleId = titleId,
            providerId = providerId,
            regionCode = region,
            accessType = accessType,
            source = "test",
            confidence = BigDecimal("1.000"),
        )
        if (!active) {
            // `open` always writes an active row, which is correct — it exists to
            // record that something *is* streaming. Withdrawing it afterwards is
            // what the production path does too.
            dsl.update(TITLE_AVAILABILITY)
                .set(TITLE_AVAILABILITY.ACTIVE, false)
                .where(TITLE_AVAILABILITY.ID.eq(id))
                .execute()
        }
    }

    companion object {
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
