package app.plotted.preferences.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.spi.TitleDirectory
import app.plotted.preferences.persistence.PilotRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The flow around the maths.
 *
 * [BradleyTerry], [PilotLadder] and [PreferenceProfile] are tested on their own
 * terms elsewhere. What is left here is the plumbing, and two parts of it are
 * decisions rather than wiring: that a skip is recorded and is not evidence, and
 * that the attribute difference is computed from the catalogue rather than taken
 * from the request.
 */
class PilotServiceTest {
    private val titles = mockk<TitleDirectory>()
    private val pilot = mockk<PilotRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC)
    private val service = PilotService(titles, pilot, clock)

    private val userId = UUID.randomUUID()

    private val comedy = profile("A Light Comedy", genres = setOf("Comedy", "Family"), year = 2026, rating = 8.0)
    private val warFilm = profile("A Heavy War Film", genres = setOf("War", "Drama"), year = 2019, rating = 6.0)
    private val series = profile("A Long Series", genres = setOf("Drama"), year = 2025, rating = 7.5, mediaType = "series")

    @Test
    fun `the first question comes from the ladder`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()

        val state = service.next(userId)
        val question = state.question.shouldNotBeNull()

        question.position shouldBe 1
        setOf(question.left.titleId, question.right.titleId).size shouldBe 2
        state.total shouldBe PilotService.QUESTIONS
        state.complete shouldBe false
    }

    @Test
    fun `a skip is recorded with no choice and no difference`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()

        service.answer(userId, comedy.titleId, warFilm.titleId, chosenTitleId = null)

        // Recorded, so the ladder stops offering it -- and carrying neither a
        // choice nor a difference, so nothing downstream can mistake it for
        // evidence. The schema refuses the half-way states as well.
        verify {
            pilot.record(userId, any(), comedy.titleId, warFilm.titleId, chosenTitleId = null, attributeDifference = null)
        }
    }

    @Test
    fun `the recorded difference is chosen minus rejected, computed here`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()
        val difference = slot<DoubleArray?>()
        every {
            pilot.record(any(), any(), any(), any(), any(), captureNullable(difference))
        } returns true

        service.answer(userId, comedy.titleId, warFilm.titleId, chosenTitleId = comedy.titleId)

        // Comedy over a war film is a positive difference on LEVITY. The sign is
        // the whole contract with the fitter, and it is computed from the
        // catalogue rather than accepted from the request -- a client that could
        // post its own numbers could post any profile it liked.
        val captured = difference.captured.shouldNotBeNull()
        captured.size shouldBe TasteAxis.size
        (captured[TasteAxis.LEVITY.ordinal] > 0.0) shouldBe true
    }

    @Test
    fun `answering the other way round flips the sign`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()
        val difference = slot<DoubleArray?>()
        every { pilot.record(any(), any(), any(), any(), any(), captureNullable(difference)) } returns true

        service.answer(userId, comedy.titleId, warFilm.titleId, chosenTitleId = warFilm.titleId)

        (difference.captured.shouldNotBeNull()[TasteAxis.LEVITY.ordinal] < 0.0) shouldBe true
    }

    @Test
    fun `choosing a title that was not offered is refused before anything is written`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()

        shouldThrow<ApiException> {
            service.answer(userId, comedy.titleId, warFilm.titleId, chosenTitleId = series.titleId)
        }

        verify(exactly = 0) { pilot.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `comparing a title with itself is refused`() {
        givenCatalogue(comedy, warFilm, series)
        givenNothingAnswered()

        shouldThrow<ApiException> {
            service.answer(userId, comedy.titleId, comedy.titleId, chosenTitleId = comedy.titleId)
        }
    }

    @Test
    fun `no answers means no profile, rather than a profile of prior means`() {
        every { pilot.comparisonsForFitting(userId) } returns emptyList()

        // A profile fitted from nothing is the population's, not this person's.
        // Returning it would report the prior back as a discovery, which is the
        // failure PreferenceProfile's whole verdict system exists to avoid.
        service.profile(userId).shouldBeNull()
    }

    @Test
    fun `a decisive run produces a profile that says something`() {
        // Fifteen answers all leaning the same way on one axis. Separated data
        // like this is exactly the case maximum likelihood cannot fit, and the
        // prior is what keeps it finite.
        every { pilot.comparisonsForFitting(userId) } returns List(15) {
            AnsweredComparison(
                axis = TasteAxis.LEVITY.name,
                chosenTitleId = UUID.randomUUID(),
                attributeDifference = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                answeredAt = Instant.EPOCH,
            )
        }

        val profile = service.profile(userId).shouldNotBeNull()

        profile.observations shouldBe 15
        profile.converged shouldBe true
        profile.opinions.single { it.axis == TasteAxis.LEVITY }.verdict shouldBe Verdict.LIKES
        // Nothing contrasted the other axes, so they must not be reported as
        // findings. The weight sitting at the population average is an absence of
        // evidence, not a measurement.
        profile.opinions.single { it.axis == TasteAxis.PACE }.verdict shouldBe Verdict.NOT_ASKED
    }

    @Test
    fun `a catalogue too uniform to contrast reports exhausted rather than complete`() {
        // Identical attributes, so no pair clears the ladder's minimum contrast.
        val twins = List(4) { profile("Twin $it", genres = emptySet(), year = 2026, rating = 7.0) }
        givenCatalogue(*twins.toTypedArray())
        givenNothingAnswered()

        val state = service.next(userId)

        // Finishing the questionnaire and running out of usable questions both
        // end with nothing to ask, and they are not the same thing: the second is
        // a thin seed, and showing the same "all done" screen would hide it.
        state.question.shouldBeNull()
        state.exhausted shouldBe true
        state.complete shouldBe true
    }

    // --- helpers -----------------------------------------------------------

    private fun givenCatalogue(vararg profiles: TitleDirectory.TitleProfile) {
        every { titles.findForTasteProfiling(any()) } returns profiles.toList()
    }

    private fun givenNothingAnswered() {
        every { pilot.settledPairs(userId) } returns emptySet()
        every { pilot.comparisonsForFitting(userId) } returns emptyList()
        every { pilot.settledCount(userId) } returns 0
    }

    private fun profile(name: String, genres: Set<String>, year: Int, rating: Double, mediaType: String = "movie") =
        TitleDirectory.TitleProfile(
            titleId = UUID.randomUUID(),
            name = name,
            mediaType = mediaType,
            releaseYear = year,
            communityRating = rating,
            posterUrl = null,
            genres = genres,
        )
}
