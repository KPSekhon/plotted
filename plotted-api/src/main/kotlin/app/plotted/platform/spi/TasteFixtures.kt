package app.plotted.platform.spi

import java.util.UUID

/**
 * Seeds a taste profile for a demo account.
 *
 * Exists so `demo` can populate Pilot Season without importing from
 * `preferences`. The taste axes, the ladder and what an answer means all live
 * in that module and should stay there; what crosses this boundary is a request
 * and a count, not any knowledge of how a preference is represented.
 *
 * ### This is for fixtures, and only for fixtures
 *
 * Every comparison it records is manufactured. Nothing produced through this
 * interface is evidence about a person, and no figure derived from it may be
 * reported as a measurement of Plotted — not in `EVALUATION.md`, not in
 * `PROGRESS.md`, not on a screen that does not say it is a demo. The demo
 * account is the only caller, its rows carry `is_demo`, and the sweep deletes
 * them.
 *
 * If a real user ever needs a starting profile, that is a different feature
 * with different rules, and it should not be built by widening this one.
 */
interface TasteFixtures {
    /**
     * Answers up to [questions] of the taste questionnaire as the demo persona.
     *
     * Deliberately partial. Finishing the questionnaire would hide the fork
     * interaction, which is the part of Pilot Season worth showing, and would
     * leave no axis unasked — losing the demonstration that Plotted reports
     * what it was never able to measure.
     *
     * @return how many were answered, which is fewer than asked for when the
     *   catalogue runs out of pairs that contrast enough to be worth asking.
     */
    fun seedDemoPersona(userId: UUID, questions: Int): Int
}
