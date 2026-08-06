package app.plotted.recommendation.domain

import java.time.LocalDate
import kotlin.random.Random

/**
 * Turns eligible candidates into one pick and two backups.
 *
 * Three stages, in order: score, diversify, then explore. Each is separable and
 * separately testable, which matters because the interesting bugs here are not
 * crashes — they are rankings that look plausible and are wrong.
 */
class Ranker(
    private val random: Random = Random.Default,
    private val explorationRate: Double = DEFAULT_EXPLORATION_RATE,
) {
    fun score(
        candidate: Candidate,
        context: TonightContext,
        subscribedProviderIds: Set<java.util.UUID>,
        today: LocalDate,
    ): ScoredCandidate? {
        val features = FeatureVector.of(
            // Priority is 1..5 with 1 highest, so it inverts into a 0..1 weight.
            Feature.PRIORITY to (Priority.LOWEST - candidate.priority + 1).toDouble() / Priority.LOWEST,

            // Only scored when a budget was given and the runtime is known. When
            // there is no budget this feature is absent rather than neutral --
            // renormalisation then redistributes its weight instead of handing
            // every candidate the same meaningless 0.5.
            Feature.RUNTIME_FIT to context.availableMinutes?.let { budget ->
                candidate.watchMinutes?.let { runtimeFit(it, budget) }
            },

            // Already paid for beats free beats anything else. Under
            // SUBSCRIBED_ONLY every survivor scores 1.0 here, which is correct:
            // the feature stops discriminating precisely when the filter has
            // already done its work.
            Feature.ACCESS to candidate.offers.maxOfOrNull { offer ->
                when {
                    offer.providerId in subscribedProviderIds -> 1.0
                    offer.isFree -> 0.8
                    else -> 0.3
                }
            },

            Feature.DEADLINE to candidate.desiredByDate?.let { deadlineUrgency(it, today) },

            Feature.ACCLAIM to candidate.communityRating?.let { (it / 10.0).coerceIn(0.0, 1.0) },
        )

        val score = features.score() ?: return null
        return ScoredCandidate(candidate, score, features)
    }

    /**
     * Picks [count] items, trading score against variety.
     *
     * Maximal Marginal Relevance: each slot after the first is chosen for
     * `λ·score − (1−λ)·similarity to what is already picked`. Without it, three
     * slots of a list dominated by one long-running series returns three
     * episodes of that series — technically the three best answers, and useless
     * as a menu. Someone rejecting the top pick is usually rejecting a *kind* of
     * thing, so the backups have to differ in kind.
     *
     * The first pick is never diversified. It is the answer to the question, and
     * trading its quality for variety would be optimising the wrong thing.
     */
    fun diversify(scored: List<ScoredCandidate>, count: Int): List<ScoredCandidate> {
        if (scored.size <= 1) return scored.take(count)
        val ranked = scored.sortedByDescending { it.score }
        val selected = mutableListOf(ranked.first())
        val remaining = ranked.drop(1).toMutableList()

        while (selected.size < count && remaining.isNotEmpty()) {
            val next = remaining.maxBy { candidate ->
                val similarity = selected.maxOf { similarity(candidate.candidate, it.candidate) }
                LAMBDA * candidate.score - (1 - LAMBDA) * similarity
            }
            selected += next
            remaining -= next
        }
        return selected
    }

    /**
     * How alike two candidates are, 0 to 1.
     *
     * Deliberately crude: same provider and same media type are the two axes a
     * person actually notices when three suggestions feel samey. A learned
     * similarity belongs in phase 8, over embeddings that do not exist yet;
     * inventing a more elaborate metric now would only be harder to explain when
     * it misbehaves.
     */
    private fun similarity(a: Candidate, b: Candidate): Double {
        var score = 0.0
        if (a.mediaType == b.mediaType) score += 0.4
        val providersA = a.offers.map { it.providerId }.toSet()
        val providersB = b.offers.map { it.providerId }.toSet()
        if (providersA.intersect(providersB).isNotEmpty()) score += 0.6
        return score
    }

    /**
     * Fills the final slot by exploration with probability [explorationRate],
     * and records the propensity of whatever was chosen.
     *
     * The propensity is the point. Phase 7 estimates how a different ranker would
     * have done using these logs, and every off-policy estimator divides by the
     * probability the logged policy assigned to the action it took. That number
     * exists only at the moment of choosing.
     *
     * So the arithmetic is written out rather than assumed:
     *
     * - Deterministic slots are taken with probability `1 − explorationRate`
     *   *for the last slot only*; the pick and first backup are never explored,
     *   so their propensity is 1.
     * - An explored slot is one of `n` alternatives drawn uniformly, so its
     *   propensity is `explorationRate / n`.
     *
     * A propensity of zero is never logged, because dividing by it later
     * silently destroys the estimate rather than failing loudly.
     */
    fun explore(selected: List<ScoredCandidate>, alternatives: List<ScoredCandidate>): List<Pick> {
        // Whether exploration could have happened at all. This has to gate the
        // propensity as well as the draw: a slot that was never at risk of being
        // replaced was chosen with certainty, and discounting it anyway
        // understates the certainty. At an exploration rate of 1 it would record
        // a propensity of zero for a slot that was in fact deterministic, which
        // makes phase 7 divide by zero over a decision that was never in doubt.
        val canExplore = alternatives.isNotEmpty() && selected.size >= 2
        val exploitPropensity = if (canExplore) 1.0 - explorationRate else 1.0

        val deterministic = selected.mapIndexed { index, scored ->
            Pick(
                candidate = scored.candidate,
                score = scored.score,
                features = scored.features,
                exploration = false,
                // Only the last slot is subject to exploration, so only its
                // propensity is reduced by the chance it was replaced.
                propensity = if (index == selected.lastIndex) exploitPropensity else 1.0,
            )
        }

        if (!canExplore || random.nextDouble() >= explorationRate) {
            return deterministic
        }

        val explored = alternatives[random.nextInt(alternatives.size)]
        return deterministic.dropLast(1) + Pick(
            candidate = explored.candidate,
            score = explored.score,
            features = explored.features,
            exploration = true,
            propensity = explorationRate / alternatives.size,
        )
    }

    private object Priority {
        const val LOWEST = 5
    }

    companion object {
        /**
         * How often the last slot is given to something the ranker did not
         * choose. Small enough that the answer stays good, large enough that
         * phase 7 has variation to learn from — with none, off-policy evaluation
         * has nothing to estimate over.
         */
        const val DEFAULT_EXPLORATION_RATE = 0.10

        /** Weight on relevance against variety in MMR. */
        private const val LAMBDA = 0.7
    }
}
