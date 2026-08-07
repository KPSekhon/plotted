package app.plotted.analytics.domain

import java.time.Duration

/**
 * The two numbers that carry Plotted's argument, and what they refuse to claim.
 *
 * Everything else an analytics screen could show — titles added, hours watched,
 * services compared — is decoration. Only two questions decide whether the
 * product works:
 *
 *  * **Does it actually save time?** [decisionLatency].
 *  * **Did they watch the thing?** [acceptedAndCompleted].
 *
 * Both are easy to compute wrongly in a flattering direction, and both carry the
 * count they rest on plus what was excluded, so a number built from four
 * observations cannot be mistaken for one built from four hundred.
 */
data class EndCredits(
    val decisionLatency: DecisionLatency,
    val acceptedAndCompleted: CompletionRate,
    /** Recommendations served, whether or not anything came of them. The denominator of acceptance. */
    val recommendationsServed: Int,
    /** Requests that returned nothing. Reported beside the rest, because refusing is a feature here. */
    val nothingFitCount: Int,
)

/**
 * How long between being shown three options and choosing one.
 *
 * **The median, not the mean.** This is wall-clock between the request and the
 * acceptance, and wall-clock has an unbounded tail: somebody opens Plotted,
 * leaves the tab, and accepts three hours later. One of those moves a mean
 * permanently and a median not at all.
 *
 * And even the median needs a window. An acceptance a day later is not a slow
 * decision, it is a different session that happened to reuse an old page — so
 * acceptances beyond [DecisionLatency.WINDOW] are left out of the statistic and
 * counted in [excludedAsStale] rather than silently dropped. That is the same
 * rule coverage follows for titles nobody has checked: excluded from the
 * denominator, reported separately, never scored as zero.
 *
 * [median] is null when nothing qualifies. A latency computed from no
 * observations is not zero, and reporting zero would be the best possible
 * number arrived at by having no evidence.
 */
data class DecisionLatency(
    val median: Duration?,
    val fastest: Duration?,
    val slowest: Duration?,
    /** Acceptances the median is built from. */
    val sampleSize: Int,
    /** Acceptances that arrived after the window and were left out. */
    val excludedAsStale: Int,
) {
    companion object {
        /**
         * Beyond this an acceptance is treated as a different session rather than
         * a long deliberation.
         *
         * Four hours is a judgement, not a measurement, and it is written here so
         * it can be argued with. It is generous enough to cover somebody who
         * asks before dinner and watches after it, and short enough that a page
         * left open overnight does not become a data point.
         */
        val WINDOW: Duration = Duration.ofHours(4)

        val NOTHING = DecisionLatency(null, null, null, 0, 0)

        /**
         * The median of what is left after the window.
         *
         * Written out rather than pulled from a library for the same reason the
         * phase 7 metrics were: the even-length case is where implementations
         * differ, and a number nobody can defend is worse than no number.
         */
        fun of(latencies: List<Duration>, excludedAsStale: Int): DecisionLatency {
            if (latencies.isEmpty()) return NOTHING.copy(excludedAsStale = excludedAsStale)

            val sorted = latencies.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                // The mean of the two central values. Taking the lower one is
                // also defensible and produces a different number on every
                // even-sized sample, which is exactly the kind of quiet
                // disagreement that makes two reports of "the median" disagree.
                sorted[middle - 1].plus(sorted[middle]).dividedBy(2)
            }

            return DecisionLatency(
                median = median,
                fastest = sorted.first(),
                slowest = sorted.last(),
                sampleSize = sorted.size,
                excludedAsStale = excludedAsStale,
            )
        }
    }
}

/**
 * Of the picks somebody accepted, how many they actually finished.
 *
 * The metric that separates a persuasive recommender from a correct one. A
 * system can be very good at getting people to click and still be wrong about
 * what they wanted, and acceptance alone cannot tell the two apart.
 *
 * **Recent acceptances are excluded from the denominator.** Something accepted
 * last night has not had time to be finished, and counting it as a failure would
 * make the rate a measure of how recently the data was collected — it would
 * climb on its own as the log aged, which looks exactly like the product
 * improving. Acceptances newer than [CompletionRate.MATURITY] are held back and
 * reported in [tooRecentToJudge].
 *
 * [rate] is null when the denominator is empty, rather than 0.0. No evidence and
 * evidence of failure are different findings.
 */
data class CompletionRate(
    val rate: Double?,
    val completed: Int,
    /** Accepted long enough ago to be judged. The denominator of [rate]. */
    val judged: Int,
    /** Accepted too recently to have been finished yet. Reported, never scored. */
    val tooRecentToJudge: Int,
) {
    companion object {
        /**
         * How long an acceptance is given before it counts as unfinished.
         *
         * Fourteen days is a judgement. A film is usually watched the same night
         * and a series is not, so this is generous to the second and slightly
         * unfair to nobody. Stated here rather than buried in SQL so that
         * changing it is a decision somebody makes rather than a query somebody
         * edits.
         */
        val MATURITY: Duration = Duration.ofDays(14)

        val NOTHING = CompletionRate(null, 0, 0, 0)

        fun of(completed: Int, judged: Int, tooRecentToJudge: Int): CompletionRate = CompletionRate(
            rate = if (judged == 0) null else completed.toDouble() / judged,
            completed = completed,
            judged = judged,
            tooRecentToJudge = tooRecentToJudge,
        )
    }
}
