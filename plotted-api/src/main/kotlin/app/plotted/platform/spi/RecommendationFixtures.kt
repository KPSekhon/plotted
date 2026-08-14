package app.plotted.platform.spi

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Writes manufactured decision-log entries for a demo account.
 *
 * End Credits reports two numbers and both are correctly null until somebody
 * has used Plotted. That is the right answer to an empty log and a poor
 * demonstration of the screen, so a demo account arrives with a short history
 * behind it.
 *
 * ### This is for fixtures, and only for fixtures
 *
 * Every row it writes is invented. No figure computed from them is a
 * measurement of Plotted, and none may be reported as one — not in
 * `EVALUATION.md`, not in `PROGRESS.md`, and not anywhere a reader could
 * mistake it for evidence that the recommender works. What it demonstrates is
 * that the *screen* works.
 *
 * The demo account is the only caller. Its rows carry `is_demo` on the user and
 * the sweep deletes them, so they cannot accumulate into a corpus somebody
 * later analyses by accident.
 */
interface RecommendationFixtures {
    fun recordDemoDecision(decision: DemoDecision)

    /**
     * One manufactured decision.
     *
     * Timestamps are explicit rather than taken from the clock, which is the
     * whole point: a completion rate needs acceptances old enough to have been
     * judged, and a log written entirely at signup can only ever report that
     * everything is too recent to say anything about.
     */
    data class DemoDecision(
        val userId: UUID,
        val regionCode: String,
        val availableMinutes: Int?,
        /** Null when the request returned nothing — a refusal, which is also a decision. */
        val titleId: UUID?,
        val requestedAt: OffsetDateTime,
        /** Null when the pick was served and not taken. */
        val acceptedAt: OffsetDateTime?,
    )
}
