package app.plotted.preferences.persistence

import app.plotted.generated.jooq.tables.references.PILOT_COMPARISONS
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.USERS
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pilot Season's answers, against a real database.
 *
 * Most of what this file asserts is enforced by CHECK constraints and a
 * normalising unique index, none of which exist anywhere the compiler can see
 * them. The two that matter most are the ones that keep a skip from becoming
 * evidence, and the one that keeps a duplicate answer from being counted twice.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class PilotRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: PilotRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `an answer is stored with the difference it was fitted from`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()
        val difference = doubleArrayOf(0.5, -0.25, 0.0, 1.0, -0.5, 0.125)

        repository.record(userId, "LEVITY", left, right, chosenTitleId = left, attributeDifference = difference) shouldBe true

        val stored = repository.comparisonsForFitting(userId).single()
        stored.chosenTitleId shouldBe left
        stored.axis shouldBe "LEVITY"
        // Round-tripped through JSONB rather than recomputed, which is the point:
        // the evidence is what it was when the person answered.
        stored.attributeDifference.toList() shouldBe difference.toList()
    }

    @Test
    fun `a skip is remembered but is not evidence`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()

        repository.record(userId, "PACE", left, right, chosenTitleId = null, attributeDifference = null) shouldBe true

        // Both halves. It counts as settled, so the ladder will not ask again --
        // a questionnaire that re-asks what you declined is arguing with you.
        repository.settledPairs(userId) shouldBe setOf(setOf(left, right))
        repository.settledCount(userId) shouldBe 1
        // And it is not a comparison. A forced choice between two unseen titles
        // is a coin flip, and one recorded as a preference is noise every later
        // fit would treat as signal.
        repository.comparisonsForFitting(userId).shouldBeEmpty()
    }

    @Test
    fun `answering the same pair twice keeps the first answer, whichever way round`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()

        repository.record(userId, "LEVITY", left, right, left, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)) shouldBe true

        // The same question with the sides swapped is the same question. The
        // unique index normalises the pair, so this is refused rather than
        // recorded as a second, independent opinion.
        repository.record(userId, "LEVITY", right, left, right, doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 0.0)) shouldBe false

        val stored = repository.comparisonsForFitting(userId).single()
        stored.chosenTitleId shouldBe left
        // The fit counts rows. A duplicate would be one person's single opinion
        // weighted twice and reported with a tighter interval than it earned.
        repository.settledCount(userId) shouldBe 1
    }

    @Test
    fun `the database refuses a choice that was not offered`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()
        val unrelated = givenTitle()

        // Run against the constraint rather than through the repository, because
        // the repository is what it protects. Without this a bug that posted an
        // unrelated id would be fitted as a preference and nothing downstream
        // could tell.
        shouldThrow<Exception> {
            insertRaw(userId, left, right, chosen = unrelated, difference = sixAxes())
        }
    }

    @Test
    fun `the database refuses a half-skipped row in either direction`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()

        // A choice with no difference cannot be fitted.
        shouldThrow<Exception> { insertRaw(userId, left, right, chosen = left, difference = null) }
        // A difference with no choice is a difference computed from nothing.
        shouldThrow<Exception> { insertRaw(userId, left, right, chosen = null, difference = sixAxes()) }
    }

    @Test
    fun `the database refuses a difference that is not six axes`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()

        // The length is written into the schema so that adding a seventh axis
        // fails loudly here rather than silently feeding the fitter vectors of
        // the right length and the wrong meaning.
        shouldThrow<Exception> {
            insertRaw(userId, left, right, chosen = left, difference = JSONB.valueOf("[1.0, 0.0, 0.0]"))
        }
    }

    @Test
    fun `the database refuses a pair of one title`() {
        val userId = givenUser()
        val title = givenTitle()

        // A zero-difference row is not neutral: it is an observation asserting an
        // indifference the user never expressed.
        shouldThrow<Exception> { insertRaw(userId, title, title, chosen = title, difference = sixAxes()) }
    }

    @Test
    fun `one user's answers do not reach another`() {
        val mine = givenUser()
        val theirs = givenUser()
        val left = givenTitle()
        val right = givenTitle()

        repository.record(theirs, "LEVITY", left, right, left, sixAxesArray())

        repository.comparisonsForFitting(mine).shouldBeEmpty()
        repository.settledPairs(mine).shouldBeEmpty()
        // The same pair is still an open question for someone else.
        repository.record(mine, "LEVITY", left, right, right, sixAxesArray()) shouldBe true
    }

    @Test
    fun `resetting discards everything and leaves the questionnaire open again`() {
        val userId = givenUser()
        val left = givenTitle()
        val right = givenTitle()
        repository.record(userId, "LEVITY", left, right, left, sixAxesArray())

        repository.reset(userId) shouldBe 1

        repository.settledPairs(userId).shouldBeEmpty()
        repository.comparisonsForFitting(userId).shouldBeEmpty()
        // Somebody redoing the questionnaire is saying the old answers were
        // wrong, so the pair has to be askable again.
        repository.record(userId, "LEVITY", left, right, right, sixAxesArray()) shouldBe true
    }

    // --- helpers -----------------------------------------------------------

    private fun insertRaw(userId: UUID, left: UUID, right: UUID, chosen: UUID?, difference: JSONB?) {
        dsl.insertInto(PILOT_COMPARISONS)
            .set(PILOT_COMPARISONS.ID, UUID.randomUUID())
            .set(PILOT_COMPARISONS.USER_ID, userId)
            .set(PILOT_COMPARISONS.AXIS, "LEVITY")
            .set(PILOT_COMPARISONS.LEFT_TITLE_ID, left)
            .set(PILOT_COMPARISONS.RIGHT_TITLE_ID, right)
            .set(PILOT_COMPARISONS.CHOSEN_TITLE_ID, chosen)
            .set(PILOT_COMPARISONS.ATTRIBUTE_DIFFERENCE, difference)
            .execute()
    }

    private fun sixAxes() = JSONB.valueOf("[1.0, 0.0, 0.0, 0.0, 0.0, 0.0]")

    private fun sixAxesArray() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "pilot-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    private fun givenTitle(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(TITLES)
            .set(TITLES.ID, id)
            .set(TITLES.EXTERNAL_SOURCE, "tmdb")
            .set(TITLES.EXTERNAL_ID, "PILOT-${SEQUENCE.incrementAndGet()}")
            .set(TITLES.MEDIA_TYPE, "movie")
            .set(TITLES.NAME, "A Title")
            .set(TITLES.METADATA_STATUS, "complete")
            .execute()
        return id
    }

    companion object {
        private val SEQUENCE = AtomicInteger(7_000_000)

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
