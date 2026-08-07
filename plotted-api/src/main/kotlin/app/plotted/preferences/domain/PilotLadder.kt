package app.plotted.preferences.domain

import java.util.UUID
import kotlin.math.abs

/**
 * Chooses which pairs to ask about.
 *
 * ### A fixed ladder, not adaptive selection
 *
 * The obvious thing is to choose each question from the answers so far, picking
 * whatever the posterior is least sure about. That is the right idea eventually
 * and the wrong one now, for a reason worth stating rather than discovering:
 * adaptive selection tunes its choices against a model of the population, and
 * there is no population. With no users, an adaptive ladder would be adapting to
 * the prior — which is a fixed ladder, chosen by a more complicated route and
 * much harder to test.
 *
 * So the ladder is fixed and deterministic, and the interesting work is in
 * making each question *worth asking*.
 *
 * ### What makes a question worth asking
 *
 * A pair teaches you about an axis in proportion to how far apart the two titles
 * are **on that axis**, and it teaches you *confusingly* in proportion to how far
 * apart they are on every other one. Someone choosing between a light, fast,
 * recent film and a heavy, slow, old one has told you something — but not which
 * of the three differences drove it, and the fit cannot separate them either.
 * That is the collinearity `BradleyTerryTest` pins.
 *
 * So each pair maximises `contrast on its axis − CONFOUNDING_PENALTY × contrast
 * elsewhere`, and the ladder cycles the axes so every one gets asked about.
 */
object PilotLadder {
    /** A title the ladder may draw on. */
    data class Choice(val titleId: UUID, val name: String, val attributes: TitleAttributes)

    /** One question. [axis] is what it was chosen to isolate — recorded so a fit can be audited. */
    data class Pair(val left: Choice, val right: Choice, val axis: TasteAxis) {
        /** Chosen minus rejected, ready for the fitter. */
        fun difference(chosen: UUID): DoubleArray = when (chosen) {
            left.titleId -> left.attributes.minus(right.attributes)
            right.titleId -> right.attributes.minus(left.attributes)
            else -> error("$chosen is not in this pair")
        }
    }

    /**
     * Builds the ladder.
     *
     * Deterministic: the same catalogue produces the same questions, so somebody
     * who abandons the flow and returns is not restarted on a different one, and
     * two runs of a test compare like with like.
     *
     * A title appears at most [MAXIMUM_APPEARANCES] times. Without that cap one
     * strongly-polarised film tends to win every axis and turns the ladder into
     * fifteen questions about the same movie — which is both tedious and
     * statistically much weaker than it looks, since the answers stop being
     * independent of one another.
     */
    fun build(choices: List<Choice>, questions: Int): List<Pair> {
        require(questions > 0) { "A ladder needs at least one question" }
        if (choices.size < 2) return emptyList()

        val appearances = mutableMapOf<UUID, Int>()
        val used = mutableSetOf<Set<UUID>>()
        val ladder = mutableListOf<Pair>()

        // Round-robin over the axes rather than filling one axis at a time, so a
        // ladder cut short — somebody who answers six of fifteen — still covers
        // every axis instead of knowing everything about levity and nothing else.
        var axisIndex = 0
        while (ladder.size < questions) {
            // Checked at the top, before anything can `continue` past it. A
            // catalogue that cannot fill the ladder stops it here; stopping short
            // is the honest outcome, and the alternative is padding the ladder
            // with pairs that teach nothing.
            //
            // This guard used to sit at the *bottom* of the loop, which meant the
            // one path that needed it never reached it: when no pair could be
            // found the `?:` below jumped straight back to the `while`, so a
            // catalogue too small to supply `questions` pairs span forever rather
            // than returning a short ladder. It could not happen with an empty
            // ladder, which is the case the identical-titles reasoning covers, so
            // the bug lived exactly where a small *non-uniform* catalogue lives —
            // which is every catalogue this has ever run against except the
            // seeded one.
            if (axisIndex >= TasteAxis.size * questions) break

            val axis = TasteAxis.ordered[axisIndex % TasteAxis.size]
            axisIndex++

            val best = bestPairFor(axis, choices, appearances, used) ?: continue

            ladder += best
            used += setOf(best.left.titleId, best.right.titleId)
            appearances.merge(best.left.titleId, 1, Int::plus)
            appearances.merge(best.right.titleId, 1, Int::plus)
        }
        return ladder
    }

    private fun bestPairFor(axis: TasteAxis, choices: List<Choice>, appearances: Map<UUID, Int>, used: Set<Set<UUID>>): Pair? {
        var best: Pair? = null
        var bestScore = MINIMUM_CONTRAST

        val available = choices.filter { (appearances[it.titleId] ?: 0) < MAXIMUM_APPEARANCES }

        for (i in available.indices) {
            for (j in i + 1 until available.size) {
                val left = available[i]
                val right = available[j]
                if (setOf(left.titleId, right.titleId) in used) continue

                val onAxis = abs(left.attributes[axis] - right.attributes[axis])
                val elsewhere = left.attributes.distanceTo(right.attributes) - onAxis
                val score = onAxis - CONFOUNDING_PENALTY * elsewhere

                // Ties broken on id so the ladder is stable across runs. Without
                // it the questions would depend on the order the catalogue query
                // happened to return, which is not a promise Postgres makes.
                if (score > bestScore || (score == bestScore && breaksTie(left, right, best))) {
                    bestScore = score
                    best = Pair(left, right, axis)
                }
            }
        }
        return best
    }

    private fun breaksTie(left: Choice, right: Choice, current: Pair?): Boolean {
        if (current == null) return true
        val candidate = minOf(left.titleId, right.titleId)
        val incumbent = minOf(current.left.titleId, current.right.titleId)
        return candidate < incumbent
    }

    /**
     * How much a difference on another axis counts against a pair.
     *
     * Below 1 because some confounding is unavoidable — real films are not
     * one-dimensional, and demanding otherwise would reject every pair in a small
     * catalogue and return an empty ladder. High enough that a pair differing on
     * everything loses to one differing on almost nothing else.
     */
    private const val CONFOUNDING_PENALTY = 0.35

    /**
     * A pair must contrast at least this much on its own axis to be worth asking.
     *
     * Two titles that barely differ on the axis produce an answer driven entirely
     * by noise, recorded as though it were evidence. Better to ask fewer
     * questions than to ask one whose answer means nothing.
     */
    private const val MINIMUM_CONTRAST = 0.2

    /** Beyond this the ladder is asking about one film repeatedly, not about taste. */
    private const val MAXIMUM_APPEARANCES = 3
}
