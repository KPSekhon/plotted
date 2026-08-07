package app.plotted.analytics.domain

import app.plotted.analytics.persistence.EndCreditsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * End Credits: what Plotted can honestly say it did for you.
 *
 * The repository returns raw observations and this decides what they support.
 * That split is deliberate: the windows below are judgements, and a judgement
 * buried in a `WHERE` clause is a judgement nobody can find, argue with, or test.
 * Here they are constants with reasons attached and a test that pins them.
 */
@Service
class EndCreditsService(
    private val endCredits: EndCreditsRepository,
) {
    @Transactional(readOnly = true)
    fun forUser(userId: UUID): EndCredits {
        val latencies = endCredits.acceptanceLatencies(userId)
        val (withinWindow, stale) = latencies.partition { it <= DecisionLatency.WINDOW }

        val completion = endCredits.completionOfAccepted(userId, CompletionRate.MATURITY)
        val counts = endCredits.requestCounts(userId)

        return EndCredits(
            decisionLatency = DecisionLatency.of(withinWindow, excludedAsStale = stale.size),
            acceptedAndCompleted = CompletionRate.of(
                completed = completion.completed,
                judged = completion.judged,
                tooRecentToJudge = completion.tooRecentToJudge,
            ),
            recommendationsServed = counts.served,
            nothingFitCount = counts.nothingFit,
        )
    }
}
