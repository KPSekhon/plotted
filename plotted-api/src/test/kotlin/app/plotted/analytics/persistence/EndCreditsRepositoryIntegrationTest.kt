package app.plotted.analytics.persistence

import app.plotted.generated.jooq.tables.references.RECOMMENDATION_ITEMS
import app.plotted.recommendation.domain.CandidateSource
import app.plotted.generated.jooq.tables.references.RECOMMENDATION_REQUESTS
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import app.plotted.recommendation.persistence.RecommendationLogRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.JSONB
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Acceptance, and the two metrics read back out of it.
 *
 * The ownership scoping on `accept` is the security-relevant part and lives
 * entirely in a `WHERE` clause, so nothing but a real database can check it. The
 * completion arithmetic is the part most likely to flatter, and each case here is
 * one specific way it could.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class EndCreditsRepositoryIntegrationTest {
    @Autowired
    private lateinit var log: RecommendationLogRepository

    @Autowired
    private lateinit var endCredits: EndCreditsRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `accepting a pick that was offered records it`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = minutesAgo(5))

        log.accept(userId, requestId, titleId) shouldBe true

        endCredits.acceptanceLatencies(userId).size shouldBe 1
    }

    @Test
    fun `accepting a title that was not offered records nothing`() {
        val userId = givenUser()
        val offered = givenTitle()
        val elsewhere = givenTitle()
        val requestId = givenServedRequest(userId, offered, requestedAt = minutesAgo(5))

        // The propensity and position are what make an acceptance usable for
        // evaluation, and a title that was never in the request has neither.
        log.accept(userId, requestId, elsewhere) shouldBe false
        endCredits.acceptanceLatencies(userId).shouldBeEmpty()
    }

    @Test
    fun `one user cannot accept another user's recommendation`() {
        val mine = givenUser()
        val theirs = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(theirs, titleId, requestedAt = minutesAgo(5))

        // Scoped by an EXISTS on the request's owner, so this is arithmetic
        // rather than an authorisation check somebody has to remember.
        log.accept(mine, requestId, titleId) shouldBe false
        endCredits.acceptanceLatencies(theirs).shouldBeEmpty()
    }

    @Test
    fun `accepting twice keeps the first decision`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = minutesAgo(30))

        log.accept(userId, requestId, titleId) shouldBe true
        // A double tap is not a second decision, and letting it move the
        // timestamp would make every measured latency the time to click twice.
        log.accept(userId, requestId, titleId) shouldBe false

        endCredits.acceptanceLatencies(userId).size shouldBe 1
    }

    @Test
    fun `latency is measured from the request, not from now`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = minutesAgo(10))

        givenAcceptance(requestId, titleId, acceptedAt = minutesAgo(8))

        // Two minutes, whenever this test happens to run.
        endCredits.acceptanceLatencies(userId).single() shouldBe Duration.ofMinutes(2)
    }

    @Test
    fun `a finished pick counts however recently it was accepted`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = minutesAgo(60))
        givenAcceptance(requestId, titleId, acceptedAt = minutesAgo(50))
        givenWatchlistItem(userId, titleId, completedAt = minutesAgo(10))

        val completion = endCredits.completionOfAccepted(userId, Duration.ofDays(14))

        // The maturity window exists to stop recent *unfinished* picks being
        // scored as failures. Something already watched needs no waiting.
        completion.completed shouldBe 1
        completion.judged shouldBe 1
        completion.tooRecentToJudge shouldBe 0
    }

    @Test
    fun `an unfinished pick accepted last night is held back rather than counted against`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = minutesAgo(600))
        givenAcceptance(requestId, titleId, acceptedAt = minutesAgo(590))
        givenWatchlistItem(userId, titleId, completedAt = null)

        val completion = endCredits.completionOfAccepted(userId, Duration.ofDays(14))

        // Counting it as a failure would make the rate a measure of how recently
        // the data was collected, climbing on its own as the log aged.
        completion.judged shouldBe 0
        completion.tooRecentToJudge shouldBe 1
    }

    @Test
    fun `an unfinished pick past the window is a real negative`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = daysAgo(40))
        givenAcceptance(requestId, titleId, acceptedAt = daysAgo(40))
        givenWatchlistItem(userId, titleId, completedAt = null)

        val completion = endCredits.completionOfAccepted(userId, Duration.ofDays(14))

        // The converse of the case above. If nothing ever counted against the
        // rate it would not be a measurement.
        completion.judged shouldBe 1
        completion.completed shouldBe 0
    }

    @Test
    fun `something finished before it was recommended does not count as a success`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = daysAgo(30))
        givenAcceptance(requestId, titleId, acceptedAt = daysAgo(30))
        givenWatchlistItem(userId, titleId, completedAt = daysAgo(60))

        val completion = endCredits.completionOfAccepted(userId, Duration.ofDays(14))

        // The join tests completed_at >= accepted_at rather than merely "this is
        // completed". Without that, a title somebody had already watched would be
        // credited to the recommendation that came thirty days later.
        completion.completed shouldBe 0
        completion.judged shouldBe 1
    }

    @Test
    fun `an accepted pick removed from the list is unfinished, not invisible`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = givenServedRequest(userId, titleId, requestedAt = daysAgo(40))
        givenAcceptance(requestId, titleId, acceptedAt = daysAgo(40))
        // No watchlist row at all: they accepted it and then removed it.

        val completion = endCredits.completionOfAccepted(userId, Duration.ofDays(14))

        // The join is left for exactly this. An inner join would drop the row and
        // raise the rate by deleting its own negative cases.
        completion.judged shouldBe 1
        completion.completed shouldBe 0
    }

    @Test
    fun `served and nothing-fit requests are counted apart`() {
        val userId = givenUser()
        givenServedRequest(userId, givenTitle(), requestedAt = minutesAgo(5))
        givenNothingFitRequest(userId)
        givenNothingFitRequest(userId)

        val counts = endCredits.requestCounts(userId)

        // Refusing is a feature here, so the count of refusals belongs beside the
        // rest rather than being folded into a single "requests" number.
        counts.served shouldBe 1
        counts.nothingFit shouldBe 2
    }

    // --- helpers -----------------------------------------------------------

    /**
     * One instant per test, so two fixture timestamps are exactly as far apart as
     * they claim.
     *
     * The first version read `OffsetDateTime.now()` separately in each helper and
     * asserted a latency of exactly two minutes. It failed in CI by four
     * milliseconds -- the time between the two calls -- which is the standing rule
     * about wall-clock in tests, broken in the fixture rather than the assertion.
     * JUnit builds a fresh instance per test, so this is per-test rather than
     * shared.
     */
    private val now: OffsetDateTime = OffsetDateTime.now()

    private fun minutesAgo(minutes: Long): OffsetDateTime = now.minusMinutes(minutes)

    private fun daysAgo(days: Long): OffsetDateTime = now.minusDays(days)

    private fun givenServedRequest(userId: UUID, titleId: UUID, requestedAt: OffsetDateTime): UUID {
        val requestId = UUID.randomUUID()
        dsl.insertInto(RECOMMENDATION_REQUESTS)
            .set(RECOMMENDATION_REQUESTS.ID, requestId)
            .set(RECOMMENDATION_REQUESTS.USER_ID, userId)
            .set(RECOMMENDATION_REQUESTS.REQUESTED_AT, requestedAt)
            .set(RECOMMENDATION_REQUESTS.REGION_CODE, "CA")
            .set(RECOMMENDATION_REQUESTS.ACCESS_POLICY, "active_subscriptions_only")
            .set(RECOMMENDATION_REQUESTS.CANDIDATE_COUNT, 5)
            .set(RECOMMENDATION_REQUESTS.ELIGIBLE_COUNT, 3)
            .set(RECOMMENDATION_REQUESTS.OUTCOME, "served")
            .set(RECOMMENDATION_REQUESTS.RANKER_VERSION, "test")
            .execute()

        dsl.insertInto(RECOMMENDATION_ITEMS)
            .set(RECOMMENDATION_ITEMS.ID, UUID.randomUUID())
            .set(RECOMMENDATION_ITEMS.REQUEST_ID, requestId)
            .set(RECOMMENDATION_ITEMS.TITLE_ID, titleId)
            .set(RECOMMENDATION_ITEMS.POSITION, 1.toShort())
            .set(RECOMMENDATION_ITEMS.SCORE, BigDecimal("0.80000"))
            .set(RECOMMENDATION_ITEMS.PROPENSITY, BigDecimal("0.9000000"))
            // Stated, because V20 deliberately gives this column no default: a
            // writer that can omit the source is a writer that can attribute a
            // discovered pick to the watchlist without anyone noticing. These
            // fixtures are about End Credits' arithmetic rather than about
            // provenance, so watchlist is the honest value for them.
            .set(RECOMMENDATION_ITEMS.CANDIDATE_SOURCE, CandidateSource.WATCHLIST.dbValue)
            .set(RECOMMENDATION_ITEMS.FEATURE_CONTRIBUTIONS, JSONB.valueOf("{}"))
            .execute()

        return requestId
    }

    private fun givenNothingFitRequest(userId: UUID) {
        dsl.insertInto(RECOMMENDATION_REQUESTS)
            .set(RECOMMENDATION_REQUESTS.ID, UUID.randomUUID())
            .set(RECOMMENDATION_REQUESTS.USER_ID, userId)
            .set(RECOMMENDATION_REQUESTS.REGION_CODE, "CA")
            .set(RECOMMENDATION_REQUESTS.ACCESS_POLICY, "active_subscriptions_only")
            .set(RECOMMENDATION_REQUESTS.CANDIDATE_COUNT, 4)
            .set(RECOMMENDATION_REQUESTS.ELIGIBLE_COUNT, 0)
            .set(RECOMMENDATION_REQUESTS.OUTCOME, "nothing_fit")
            .set(RECOMMENDATION_REQUESTS.RANKER_VERSION, "test")
            .execute()
    }

    /** Stamps an acceptance directly, so a test can place it in the past. */
    private fun givenAcceptance(requestId: UUID, titleId: UUID, acceptedAt: OffsetDateTime) {
        dsl.update(RECOMMENDATION_ITEMS)
            .set(RECOMMENDATION_ITEMS.ACCEPTED_AT, acceptedAt)
            .where(RECOMMENDATION_ITEMS.REQUEST_ID.eq(requestId))
            .and(RECOMMENDATION_ITEMS.TITLE_ID.eq(titleId))
            .execute()
    }

    private fun givenWatchlistItem(userId: UUID, titleId: UUID, completedAt: OffsetDateTime?) {
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
            .set(WATCHLIST_ITEMS.PRIORITY, 2.toShort())
            // The CHECK constraint from V14: only a completed row may carry a
            // completion time, so the fixture cannot build an impossible state.
            .set(WATCHLIST_ITEMS.STATUS, if (completedAt == null) "pending" else "completed")
            .set(WATCHLIST_ITEMS.COMPLETED_AT, completedAt)
            .set(WATCHLIST_ITEMS.SOURCE, "manual")
            .execute()
    }

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "credits-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    private fun givenTitle(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "CREDITS-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, "A Title")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        return id
    }

    companion object {
        private val SEQUENCE = AtomicInteger(9_000_000)

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
