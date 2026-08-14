package app.plotted.preferences.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.spi.TitleDirectory
import app.plotted.preferences.persistence.PilotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/**
 * Pilot Season, as a flow rather than as maths.
 *
 * [BradleyTerry], [PilotLadder] and [PreferenceProfile] already do everything
 * difficult. This class does the plumbing they were written to be plugged into:
 * pick the next unanswered pair, record an answer, and fit what has accumulated.
 *
 * ### Skipping
 *
 * A skip is recorded and is not evidence. Both halves matter. Recording it is
 * what stops the same question coming back on the next request — a questionnaire
 * that re-asks what you declined is arguing with you. And it must not become a
 * comparison, because a forced choice between two titles somebody has not seen
 * is a coin flip, and a coin flip recorded as a preference is worse than a
 * shorter questionnaire: it is noise that every later fit will treat as signal.
 *
 * The repository enforces the second half by filtering, and the schema enforces
 * it again by refusing a row with a choice and no difference, or a difference
 * and no choice.
 */
@Service
class PilotService(
    private val titles: TitleDirectory,
    private val pilot: PilotRepository,
    private val clock: Clock,
) {
    /**
     * The next question, or the state that explains why there is not one.
     *
     * The ladder is rebuilt on every call rather than stored. It is deterministic
     * given the catalogue, so a person who abandons the flow and comes back gets
     * the same questions in the same order — and rebuilding means a grown
     * catalogue improves the remaining questions rather than serving a session
     * frozen against a thinner one.
     */
    @Transactional(readOnly = true)
    fun next(userId: UUID): PilotState = stateFor(userId, catalogue())

    /**
     * Records an answer, or a skip when [chosenTitleId] is null.
     *
     * The attribute difference is computed here from the catalogue, never taken
     * from the request. A client that could post its own numbers could post any
     * profile it liked, and the fit would have no way to tell.
     */
    @Transactional
    fun answer(userId: UUID, leftTitleId: UUID, rightTitleId: UUID, chosenTitleId: UUID?): PilotState {
        if (leftTitleId == rightTitleId) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "A comparison needs two different titles")
        }
        if (chosenTitleId != null && chosenTitleId != leftTitleId && chosenTitleId != rightTitleId) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "The chosen title must be one of the two offered",
                mapOf("chosenTitleId" to "Must equal leftTitleId or rightTitleId"),
            )
        }

        val catalogue = catalogue()
        val left = catalogue.choice(leftTitleId)
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Unknown title $leftTitleId")
        val right = catalogue.choice(rightTitleId)
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Unknown title $rightTitleId")

        val pair = PilotLadder.Pair(left, right, axisFor(left, right, userId, catalogue))
        pilot.record(
            userId = userId,
            axis = pair.axis.name,
            leftTitleId = leftTitleId,
            rightTitleId = rightTitleId,
            chosenTitleId = chosenTitleId,
            attributeDifference = chosenTitleId?.let(pair::difference),
        )
        return stateFor(userId, catalogue)
    }

    /**
     * The fitted profile, or null when nobody has answered anything.
     *
     * Null rather than a profile of six prior means. A profile fitted from no
     * answers is the population's, not this person's, and returning it would
     * present the prior back as a discovery — which is the failure
     * [PreferenceProfile] spends its whole verdict system avoiding one axis at a
     * time.
     */
    @Transactional(readOnly = true)
    fun profile(userId: UUID): PreferenceProfile? {
        val comparisons = pilot.comparisonsForFitting(userId)
        if (comparisons.isEmpty()) return null

        val fit = BradleyTerry.fit(
            comparisons.map { BradleyTerry.Comparison(it.attributeDifference) },
            POPULATION_PRIOR,
        )
        return PreferenceProfile.from(fit, POPULATION_PRIOR)
    }

    @Transactional
    fun reset(userId: UUID) {
        pilot.reset(userId)
    }

    /**
     * Answers the first [count] ladder questions as a stated persona would.
     *
     * **Fixture data, for demo accounts only.** Nothing here is evidence about
     * anybody's taste, and no number derived from it belongs in `EVALUATION.md`
     * or any claim about how Plotted performs. It exists so a visitor can see
     * what a fitted profile looks like without answering fifteen questions
     * first, and so the screens can be evaluated before there are users.
     *
     * ### Why a weight vector rather than scripted answers
     *
     * The persona is expressed as [persona] — the same shape the fitter
     * produces — and each question is answered by whichever title that vector
     * scores higher. So the answers are not invented one at a time; they are
     * *implied* by a preference stated once and written down. That has a
     * property worth having: fitting these answers should approximately recover
     * the vector they came from, which is a real check on the fitter that
     * scripted answers could not provide.
     *
     * Every answer still goes through [answer], so the attribute difference is
     * derived from the catalogue exactly as it would be for a person. Writing
     * `pilot_comparisons` rows directly would have been simpler and would have
     * skipped the one computation that makes a comparison mean anything.
     *
     * Deliberately stops short of the full ladder. A demo that arrives with the
     * questionnaire already finished hides the most interesting thing about
     * this feature, which is the fork itself — and leaving axes unasked shows
     * off the `NOT_ASKED` verdict, which is the honest-refusal design working.
     *
     * @return how many were actually answered, which is fewer than [count] when
     *   the catalogue runs out of pairs worth asking about.
     */
    @Transactional
    fun seedPersona(userId: UUID, persona: DoubleArray, count: Int): Int {
        require(persona.size == TasteAxis.size) {
            "A persona needs one weight per axis, got ${persona.size} of ${TasteAxis.size}"
        }

        var answered = 0
        repeat(count) {
            val catalogue = catalogue()
            val question = stateFor(userId, catalogue).question ?: return answered

            val left = catalogue.choice(question.left.titleId) ?: return answered
            val right = catalogue.choice(question.right.titleId) ?: return answered

            // The persona's own utility, exactly as Bradley-Terry defines it:
            // prefer left when the difference it would record scores positive.
            val difference = left.attributes.minus(right.attributes)
            val utility = difference.indices.sumOf { axis -> difference[axis] * persona[axis] }

            val chosen = if (utility >= 0) left.titleId else right.titleId
            answer(userId, left.titleId, right.titleId, chosen)
            answered++
        }
        return answered
    }

    // --- internals ---------------------------------------------------------

    /**
     * The candidate pool, fetched once per request.
     *
     * Both the ladder and the rendering need it, and both used to ask for it
     * separately -- three copies of the same four-hundred-row query to answer one
     * question. Passing it through means they also cannot disagree about which
     * titles exist, which they could when each read the catalogue at a slightly
     * different moment.
     */
    private fun catalogue(): Catalogue {
        val year = LocalDate.now(clock).year
        val profiles = titles.findForTasteProfiling(CANDIDATE_POOL)
        return Catalogue(
            profiles.associateBy { it.titleId },
            profiles.map { title ->
                PilotLadder.Choice(
                    titleId = title.titleId,
                    name = title.name,
                    attributes = TitleAttributes.of(
                        genres = title.genres,
                        isSeries = title.mediaType == SERIES,
                        releaseYear = title.releaseYear,
                        communityRating = title.communityRating,
                        // Passed in rather than read inside, so an answer recorded
                        // today and a test run in five years agree about what
                        // "recent" meant at the moment somebody clicked.
                        currentYear = year,
                    ),
                )
            },
        )
    }

    private fun stateFor(userId: UUID, catalogue: Catalogue): PilotState {
        val settled = pilot.settledPairs(userId)
        val ladder = catalogue.ladder()
        val remaining = ladder.filterNot { setOf(it.left.titleId, it.right.titleId) in settled }

        val answered = pilot.comparisonsForFitting(userId).size
        val skipped = pilot.settledCount(userId) - answered

        return PilotState(
            question = remaining.firstOrNull()?.let { catalogue.toQuestion(it, position = settled.size + 1) },
            answered = answered,
            skipped = skipped,
            total = QUESTIONS,
            // Nothing left to ask, but the ladder is shorter than it should be:
            // the catalogue could not supply enough contrasting pairs. A finished
            // questionnaire and a thin seed both end here and are not the same
            // thing, so they are reported differently.
            exhausted = remaining.isEmpty() && ladder.size < QUESTIONS,
        )
    }

    /**
     * Which axis to file this answer under.
     *
     * Normally the ladder's own: the pair came from it, and its axis is the one
     * the question was chosen to isolate. A pair that is no longer in the ladder
     * — the catalogue grew between the page loading and the answer arriving —
     * falls back to whichever axis the two titles actually contrast most on.
     *
     * The fallback is acceptable because this column is audit metadata and never
     * reaches the fit. It is derived rather than taken from the request for the
     * same reason the attribute difference is: a field the client sets is a field
     * the client can set wrongly, and an audit trail recording what the caller
     * claimed is not an audit trail.
     */
    private fun axisFor(left: PilotLadder.Choice, right: PilotLadder.Choice, userId: UUID, catalogue: Catalogue): TasteAxis {
        val pair = setOf(left.titleId, right.titleId)
        catalogue.ladder().firstOrNull { setOf(it.left.titleId, it.right.titleId) == pair }?.let { return it.axis }

        return TasteAxis.ordered.maxBy { axis -> abs(left.attributes[axis] - right.attributes[axis]) }
    }

    /** One request's view of the catalogue: the detail for rendering and the choices for the ladder. */
    private class Catalogue(
        private val details: Map<UUID, TitleDirectory.TitleProfile>,
        private val choices: List<PilotLadder.Choice>,
    ) {
        private val ladder: List<PilotLadder.Pair> by lazy { PilotLadder.build(choices, QUESTIONS) }

        fun ladder(): List<PilotLadder.Pair> = ladder

        fun choice(titleId: UUID): PilotLadder.Choice? = choices.firstOrNull { it.titleId == titleId }

        fun toQuestion(pair: PilotLadder.Pair, position: Int) = PilotQuestion(
            left = option(pair.left),
            right = option(pair.right),
            axis = pair.axis,
            position = position.coerceAtMost(QUESTIONS),
        )

        private fun option(choice: PilotLadder.Choice): PilotOption {
            val detail = details[choice.titleId]
            return PilotOption(
                titleId = choice.titleId,
                name = choice.name,
                mediaType = detail?.mediaType ?: MOVIE,
                releaseYear = detail?.releaseYear,
                posterUrl = detail?.posterUrl,
            )
        }
    }

    companion object {
        /** Section 6.7's questionnaire length. Short enough to finish on a phone. */
        const val QUESTIONS = 15

        private const val SERIES = "series"
        private const val MOVIE = "movie"

        /**
         * How many titles the ladder may choose from.
         *
         * Kept in step with the repository's own ceiling. The ladder scores every
         * pair for every axis, which is quadratic, and the pool is
         * popularity-ordered — so a bigger one costs more and adds titles the
         * person is less likely to recognise, which is the opposite of what a
         * good question needs.
         */
        const val CANDIDATE_POOL = 400

        /**
         * The population's taste, and it is a placeholder rather than a finding.
         *
         * Zero mean because nobody has been measured. That is the same *number*
         * as "assume you are indifferent" and a different *claim*: once profiles
         * exist, the mean should become their average, which is what turns it
         * into "assume you are typical". Left until there are users, because an
         * average over nobody is still zero and pretending otherwise would be
         * inventing a population.
         *
         * The precision is the knob deciding how many answers it takes to
         * overrule the population. At 1.0, fifteen answers move a well-contrasted
         * axis decisively and leave an axis nobody was asked about sitting at the
         * prior — which is exactly the separation `NOT_ASKED` depends on.
         */
        val POPULATION_PRIOR = BradleyTerry.Prior(
            mean = DoubleArray(TasteAxis.size),
            precision = 1.0,
        )
    }
}
