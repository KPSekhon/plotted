package app.plotted.recommendation.persistence

import app.plotted.generated.jooq.tables.references.RECOMMENDATION_ITEMS
import app.plotted.generated.jooq.tables.references.RECOMMENDATION_REQUESTS
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.recommendation.domain.AccessPolicy
import app.plotted.recommendation.domain.Candidate
import app.plotted.recommendation.domain.Feature
import app.plotted.recommendation.domain.FeatureVector
import app.plotted.recommendation.domain.Pick
import app.plotted.recommendation.domain.Recommendation
import app.plotted.recommendation.domain.Rejection
import app.plotted.recommendation.domain.TonightContext
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The decision log, against a real database.
 *
 * This table is the one phase 7 reads, and a row written wrongly tonight is not
 * recoverable later — so the constraints that protect it are worth exercising
 * rather than trusting. The propensity checks are the point: a zero would make
 * every importance-weighted estimate divide by zero, and the failure would
 * surface months after the policy that produced it was gone.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class RecommendationLogRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: RecommendationLogRepository

    @Autowired
    private lateinit var dsl: DSLContext

    private val context = TonightContext(
        regionCode = "CA",
        availableMinutes = 90,
        accessPolicy = AccessPolicy.SUBSCRIBED_ONLY,
    )

    @Test
    fun `a served recommendation stores its picks, scores and propensities`() {
        val userId = givenUser()
        val first = givenTitle()
        val second = givenTitle()

        val requestId = repository.record(
            userId = userId,
            context = context,
            outcome = Recommendation.Served(
                picks = listOf(pick(first, score = 0.82, propensity = 1.0), pick(second, score = 0.61, propensity = 0.9)),
                candidateCount = 7,
                eligibleCount = 4,
            ),
            latencyMs = 12,
            rankerVersion = "linear-v1",
        )

        val request = dsl.selectFrom(RECOMMENDATION_REQUESTS)
            .where(RECOMMENDATION_REQUESTS.ID.eq(requestId))
            .fetchOne()
            .shouldNotBeNull()
        request.outcome shouldBe "served"
        request.candidateCount shouldBe 7
        request.eligibleCount shouldBe 4
        // Stamped so phase 7 cannot pool rows from two different scoring
        // functions by accident.
        request.rankerVersion shouldBe "linear-v1"

        val items = dsl.selectFrom(RECOMMENDATION_ITEMS)
            .where(RECOMMENDATION_ITEMS.REQUEST_ID.eq(requestId))
            .orderBy(RECOMMENDATION_ITEMS.POSITION)
            .fetch()
        items.size shouldBe 2
        items[0].position shouldBe 1.toShort()
        items[0].titleId shouldBe first
        items[0].score!!.compareTo(BigDecimal("0.82000")) shouldBe 0
        items[1].propensity!!.compareTo(BigDecimal("0.9000000")) shouldBe 0
    }

    @Test
    fun `feature contributions survive the round trip`() {
        val userId = givenUser()
        val titleId = givenTitle()

        val requestId = repository.record(
            userId,
            context,
            Recommendation.Served(listOf(pick(titleId, 0.5, 1.0)), candidateCount = 1, eligibleCount = 1),
            latencyMs = 3,
            rankerVersion = "linear-v1",
        )

        val stored = dsl.select(RECOMMENDATION_ITEMS.FEATURE_CONTRIBUTIONS)
            .from(RECOMMENDATION_ITEMS)
            .where(RECOMMENDATION_ITEMS.REQUEST_ID.eq(requestId))
            .fetchOne()!!
            .value1()!!
            .data()

        // The explanations shown to the user are rendered from this. If it did
        // not survive storage, phase 11 could report what was recommended but
        // never why.
        stored.contains("PRIORITY") shouldBe true
        stored.contains("share") shouldBe true
    }

    @Test
    fun `an empty answer is logged with its reasons and no items`() {
        val userId = givenUser()

        val requestId = repository.record(
            userId = userId,
            context = context,
            outcome = Recommendation.NothingFits(
                candidateCount = 5,
                reasons = mapOf(Rejection.TOO_LONG to 4, Rejection.ACCESS_POLICY to 1),
            ),
            latencyMs = 5,
            rankerVersion = "linear-v1",
        )

        val request = dsl.selectFrom(RECOMMENDATION_REQUESTS)
            .where(RECOMMENDATION_REQUESTS.ID.eq(requestId))
            .fetchOne()
            .shouldNotBeNull()

        // Logged rather than skipped: "the constraints excluded everything" is
        // one of the more interesting things a recommender can be doing
        // repeatedly, and it is invisible if only successes are recorded.
        request.outcome shouldBe "nothing_fit"
        request.eligibleCount shouldBe 0
        request.rejectionSummary!!.data().contains("TOO_LONG") shouldBe true

        dsl.fetchCount(RECOMMENDATION_ITEMS, RECOMMENDATION_ITEMS.REQUEST_ID.eq(requestId)) shouldBe 0
    }

    @Test
    fun `a zero propensity is refused before it reaches the database`() {
        val userId = givenUser()
        val titleId = givenTitle()

        // Dividing by this in phase 7 destroys the estimate silently. Failing
        // here names the policy that produced it; the CHECK constraint is the
        // second line of defence, not the first.
        shouldThrow<IllegalArgumentException> {
            repository.record(
                userId,
                context,
                Recommendation.Served(listOf(pick(titleId, 0.5, propensity = 0.0)), 1, 1),
                latencyMs = 1,
                rankerVersion = "linear-v1",
            )
        }
    }

    @Test
    fun `two picks cannot occupy the same position`() {
        val userId = givenUser()
        val titleId = givenTitle()
        val requestId = repository.record(
            userId,
            context,
            Recommendation.Served(listOf(pick(titleId, 0.5, 1.0)), 1, 1),
            latencyMs = 1,
            rankerVersion = "linear-v1",
        )

        // The unique constraint is what stops a ranking from being ambiguous
        // about which title was actually the pick.
        shouldThrow<Exception> {
            dsl.insertInto(RECOMMENDATION_ITEMS)
                .set(RECOMMENDATION_ITEMS.ID, UUID.randomUUID())
                .set(RECOMMENDATION_ITEMS.REQUEST_ID, requestId)
                .set(RECOMMENDATION_ITEMS.TITLE_ID, titleId)
                .set(RECOMMENDATION_ITEMS.POSITION, 1.toShort())
                .set(RECOMMENDATION_ITEMS.SCORE, BigDecimal("0.50000"))
                .set(RECOMMENDATION_ITEMS.PROPENSITY, BigDecimal("1.0000000"))
                .set(RECOMMENDATION_ITEMS.FEATURE_CONTRIBUTIONS, org.jooq.JSONB.valueOf("{}"))
                .execute()
        }
    }

    @Test
    fun `a request with no time limit stores null rather than zero`() {
        val userId = givenUser()

        val requestId = repository.record(
            userId,
            context.copy(availableMinutes = null),
            Recommendation.NothingFits(candidateCount = 0, reasons = emptyMap()),
            latencyMs = 1,
            rankerVersion = "linear-v1",
        )

        // "No particular limit" and "no time at all" are different requests, and
        // a sentinel would make them indistinguishable in the logs forever.
        dsl.select(RECOMMENDATION_REQUESTS.AVAILABLE_MINUTES)
            .from(RECOMMENDATION_REQUESTS)
            .where(RECOMMENDATION_REQUESTS.ID.eq(requestId))
            .fetchOne()!!
            .value1() shouldBe null
    }

    // --- helpers -----------------------------------------------------------

    private fun pick(titleId: UUID, score: Double, propensity: Double) = Pick(
        candidate = Candidate(
            titleId = titleId,
            name = "A Title",
            mediaType = "movie",
            posterUrl = null,
            watchMinutes = 100,
            sessionMinutes = 100,
            priority = 2,
            desiredByDate = null,
            communityRating = 7.5,
            offers = emptyList(),
        ),
        score = score,
        features = FeatureVector.of(Feature.PRIORITY to 0.8, Feature.ACCLAIM to 0.75),
        exploration = false,
        propensity = propensity,
    )

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "tonight-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    private fun givenTitle(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "TONIGHT-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, "A Title")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        return id
    }

    companion object {
        private val SEQUENCE = AtomicInteger(6_000_000)

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
