package app.plotted.availability.persistence

import app.plotted.availability.domain.AccessType
import app.plotted.generated.jooq.tables.references.TITLES
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one test that exercises the fenced DDL.
 *
 * `title_availability.validity` is a `DATERANGE` and its non-overlap rule is a
 * GiST exclusion constraint. Neither is visible to the jOOQ generator, so the
 * range SQL in [AvailabilityRepository] is written by hand and nothing else in
 * the build type-checks it. Without this test that code is unverified, and it is
 * the code that decides whether a title looks available.
 *
 * The exclusion constraint is the headline: it is what makes the duplicate
 * availability rows that would inflate every coverage figure -- and so mislead
 * the subscription optimiser -- unrepresentable rather than merely unlikely.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class AvailabilityRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: AvailabilityRepository

    @Autowired
    private lateinit var providers: ProviderRepository

    @Autowired
    private lateinit var dsl: DSLContext

    private val region = "CA"

    /**
     * UTC, matching the injected [java.time.Clock] the repository writes with.
     * A default-zone date would disagree with the stored range for anyone west
     * of Greenwich after early evening — a flake that only appears at night.
     */
    private val today: LocalDate get() = LocalDate.now(Clock.systemUTC())

    @Test
    fun `opening a window stores an active row that reads back`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!

        val id = repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        val active = repository.findActive(titleId, region)
        active.size shouldBe 1
        active.single().id shouldBe id
        active.single().providerId shouldBe crave.id
        active.single().accessType shouldBe AccessType.SUBSCRIPTION
        // Half-open range starting today, still unbounded above.
        validityOf(id) shouldBe "[$today,)"
    }

    @Test
    fun `the exclusion constraint refuses a second overlapping window`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        val second = runCatching {
            repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)
        }

        // The original schema used a unique constraint containing a nullable
        // column, which never fires because NULL != NULL in Postgres. This is
        // the fix, and this assertion is the proof that it works.
        second.isFailure shouldBe true
        repository.findActive(titleId, region).size shouldBe 1
    }

    @Test
    fun `the same provider may offer the same title under a different access type`() {
        val titleId = givenTitle()
        val apple = providers.findBySlug("apple-tv-store")!!

        repository.open(titleId, apple.id, region, AccessType.RENT, SOURCE, FULL)
        repository.open(titleId, apple.id, region, AccessType.BUY, SOURCE, FULL)

        // Rent and buy are genuinely different transactions at different prices,
        // so the constraint must not collapse them.
        repository.findActive(titleId, region).size shouldBe 2
    }

    @Test
    fun `closing a window bounds it and frees the slot for a new one`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        val first = repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        repository.close(first)

        repository.findActive(titleId, region).shouldBe(emptyList())
        // The row survives with a bounded range: when it was available is the
        // entire signal the removal-risk model learns from.
        //
        // Opened and closed the same day, so the window is clamped to one day
        // rather than collapsing to `daterange(today, today)` -- which Postgres
        // normalises to `empty`, asserting the title was never available and
        // quietly exempting the row from the exclusion constraint.
        validityOf(first) shouldBe "[$today,${today.plusDays(1)})"

        // ...and the title can come back without tripping the constraint.
        val second = runCatching {
            repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)
        }
        second.isSuccess shouldBe true

        // The returning window abuts the closed one rather than overlapping it.
        // Read together the two rows say "available from today, uninterrupted",
        // which is what actually happened -- and no day is claimed twice, so the
        // exclusion constraint still means something for this slot.
        validityOf(second.getOrThrow()) shouldBe "[${today.plusDays(1)},)"
    }

    @Test
    fun `a closed window is kept rather than deleted`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        val id = repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        repository.close(id)

        dsl.fetchCount(
            DSL.table("title_availability"),
            DSL.field("id", UUID::class.java).eq(id),
        ) shouldBe 1
    }

    @Test
    fun `re-verifying moves the staleness timestamp forward`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        val id = repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)
        val before = repository.findActive(titleId, region).single().sourceCheckedAt

        dsl.update(DSL.table("title_availability"))
            .set(DSL.field("source_checked_at", OffsetDateTime::class.java), OffsetDateTime.now().minusDays(3))
            .where(DSL.field("id", UUID::class.java).eq(id))
            .execute()

        repository.markVerified(listOf(id))

        // A card reading "verified 2 hours ago" has to mean the check happened,
        // not that the row was written then.
        val after = repository.findActive(titleId, region).single().sourceCheckedAt
        (after.isAfter(before.minusSeconds(1))) shouldBe true
    }

    @Test
    fun `markVerified with nothing to do issues no statement`() {
        repository.markVerified(emptyList())
    }

    @Test
    fun `snapshots accumulate and the latest hash reads back`() {
        val titleId = givenTitle()

        repository.recordSnapshot(titleId, region, "a".repeat(64), mapOf("offers" to emptyList<String>()))
        repository.recordSnapshot(titleId, region, "b".repeat(64), mapOf("offers" to listOf("crave")))

        // Every refresh appends. A day with no change is still evidence.
        repository.countSnapshots(titleId) shouldBe 2
        repository.latestSnapshotHash(titleId, region) shouldBe "b".repeat(64)
    }

    @Test
    fun `the snapshot payload survives a round trip`() {
        val titleId = givenTitle()

        repository.recordSnapshot(
            titleId,
            region,
            "c".repeat(64),
            mapOf(
                "offers" to listOf(mapOf("provider" to "crave", "accessType" to "subscription")),
                "unmapped" to listOf(mapOf("tmdbProviderId" to 9999, "name" to "Some New Service")),
            ),
        )

        val stored = dsl.select(DSL.field("raw_summary", String::class.java))
            .from(DSL.table("availability_snapshots"))
            .where(DSL.field("title_id", UUID::class.java).eq(titleId))
            .fetchOne()!!
            .value1()

        // Unmapped providers are recorded even though they produce no
        // availability row, so a later fix to the alias seed can re-read them.
        stored.shouldNotBeNull()
        (stored.contains("Some New Service")) shouldBe true
    }

    @Test
    fun `a title with no snapshots has no latest hash`() {
        repository.latestSnapshotHash(givenTitle(), region).shouldBeNull()
    }

    @Test
    fun `availability for one title does not leak into another`() {
        val one = givenTitle()
        val other = givenTitle()
        val crave = providers.findBySlug("crave")!!
        repository.open(one, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        repository.findActive(other, region).shouldBe(emptyList())
        repository.findActive(one, region).size shouldBe 1
    }

    @Test
    fun `the same title in another region is a separate row`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        repository.open(titleId, crave.id, "CA", AccessType.SUBSCRIPTION, SOURCE, FULL)

        val other = runCatching {
            repository.open(titleId, crave.id, "US", AccessType.SUBSCRIPTION, SOURCE, FULL)
        }

        other.isSuccess shouldBe true
        repository.findActive(titleId, "CA").size shouldBe 1
        repository.findActive(titleId, "US").size shouldBe 1
    }

    @Test
    fun `confidence and price round trip at their stored precision`() {
        val titleId = givenTitle()
        val apple = providers.findBySlug("apple-tv-store")!!

        repository.open(
            titleId = titleId,
            providerId = apple.id,
            regionCode = region,
            accessType = AccessType.RENT,
            source = SOURCE,
            confidence = BigDecimal("0.800"),
            price = BigDecimal("4.99"),
            currency = "CAD",
            deepLink = "https://tv.apple.com/ca/movie/example",
        )

        val stored = repository.findActive(titleId, region).single()
        stored.price shouldBe BigDecimal("4.99")
        stored.currency shouldBe "CAD"
        stored.confidence.compareTo(BigDecimal("0.800")) shouldBe 0
        stored.deepLink shouldNotBe null
    }

    @Test
    fun `findActiveForTitles answers for several titles in one query`() {
        val one = givenTitle()
        val other = givenTitle()
        val crave = providers.findBySlug("crave")!!
        val apple = providers.findBySlug("apple-tv-store")!!
        repository.open(one, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)
        repository.open(other, apple.id, region, AccessType.RENT, SOURCE, FULL)

        val rows = repository.findActiveForTitles(listOf(one, other), region)

        rows.size shouldBe 2
        rows.single { it.titleId == one }.provider.slug shouldBe "crave"
        // The access type travels with the row: coverage has to be able to tell
        // a subscription from a rental, and a rental covers nothing.
        rows.single { it.titleId == other }.accessType shouldBe AccessType.RENT
    }

    @Test
    fun `findActiveForTitles omits a title with no offers rather than returning a blank`() {
        val covered = givenTitle()
        val nothingKnown = givenTitle()
        val crave = providers.findBySlug("crave")!!
        repository.open(covered, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)

        val rows = repository.findActiveForTitles(listOf(covered, nothingKnown), region)

        // Absence is what lets the caller separate "checked, nothing carries it"
        // from "never checked" -- the distinction the coverage dashboard reports
        // rather than scoring.
        rows.map { it.titleId } shouldBe listOf(covered)
    }

    @Test
    fun `findActiveForTitles ignores closed windows and other regions`() {
        val titleId = givenTitle()
        val crave = providers.findBySlug("crave")!!
        val closed = repository.open(titleId, crave.id, region, AccessType.SUBSCRIPTION, SOURCE, FULL)
        repository.close(closed)
        repository.open(titleId, crave.id, "US", AccessType.SUBSCRIPTION, SOURCE, FULL)

        repository.findActiveForTitles(listOf(titleId), region) shouldBe emptyList()
        repository.findActiveForTitles(listOf(titleId), "US").size shouldBe 1
    }

    @Test
    fun `findActiveForTitles with nothing to look up issues no statement`() {
        repository.findActiveForTitles(emptyList(), region) shouldBe emptyList()
    }

    // --- helpers -----------------------------------------------------------

    /** A minimal title, inserted directly so this test does not reach into the catalogue module. */
    private fun givenTitle(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "AVAIL-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, "A Title")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        return id
    }

    private fun validityOf(id: UUID): String? = dsl.select(DSL.field("validity::text", String::class.java))
        .from(DSL.table("title_availability"))
        .where(DSL.field("id", UUID::class.java).eq(id))
        .fetchOne()
        ?.value1()

    companion object {
        private const val SOURCE = "tmdb:justwatch"
        private val FULL = BigDecimal("1.000")
        private val SEQUENCE = AtomicInteger(2_000_000)

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
