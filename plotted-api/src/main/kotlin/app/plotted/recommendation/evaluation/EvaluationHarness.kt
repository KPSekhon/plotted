package app.plotted.recommendation.evaluation

import java.time.LocalDate

/**
 * Runs a set of strategies over a set of queries and reports what happened.
 *
 * Deliberately does no I/O and holds no Spring bean. It takes queries and
 * returns numbers, which is what makes the whole harness runnable against a
 * simulation, against a temporal slice of the decision log, or against a fixture
 * in a test without any of those knowing about each other.
 */
class EvaluationHarness(
    private val k: Int = DEFAULT_K,
    private val resamples: Int = Metrics.DEFAULT_RESAMPLES,
    private val seed: Long = Metrics.DEFAULT_SEED,
) {
    /**
     * Scores every strategy over every answerable query.
     *
     * **Unanswerable queries are dropped once, up front, for everybody.**
     * Filtering per strategy would give each one a different denominator, and
     * two means over different query sets are not comparable however similar the
     * numbers look. It also keeps the pairing intact, which is what
     * [Metrics.bootstrapDifference] depends on.
     */
    fun run(queries: List<EvaluationQuery>, strategies: List<RankingStrategy>): Report {
        val answerable = queries.filter { it.isAnswerable }

        val perStrategy = strategies.associate { strategy ->
            strategy.name to answerable.map { query ->
                val ranked = strategy.rank(query)
                require(ranked.size == query.candidates.size) {
                    "${strategy.name} returned ${ranked.size} of ${query.candidates.size} candidates for ${query.queryId}. " +
                        "A strategy that drops candidates shortens the list it is scored on and flatters every metric."
                }
                query.relevancesFor(ranked)
            }
        }

        val results = strategies.map { strategy ->
            val relevances = perStrategy.getValue(strategy.name)
            val ndcgs = relevances.mapNotNull { Metrics.ndcgAt(it, k) }
            StrategyResult(
                name = strategy.name,
                ndcg = Metrics.bootstrapMean(ndcgs, resamples, seed = seed),
                ndcgStandardDeviation = Metrics.standardDeviation(ndcgs),
                precision = Metrics.bootstrapMean(relevances.mapNotNull { Metrics.precisionAt(it, k) }, resamples, seed = seed),
                meanReciprocalRank = Metrics.bootstrapMean(relevances.map { Metrics.reciprocalRank(it) }, resamples, seed = seed),
                perQueryNdcg = ndcgs,
            )
        }

        return Report(
            k = k,
            queriesConsidered = queries.size,
            queriesScored = answerable.size,
            results = results,
        )
    }

    /**
     * A paired comparison of two strategies over the same queries.
     *
     * Paired because both saw identical queries, so most of the variance is the
     * queries being easy or hard rather than either strategy being good.
     * Comparing two independent intervals throws that away and will call a real
     * improvement inconclusive.
     */
    fun compare(report: Report, strategy: String, against: String): Comparison {
        val a = report.result(strategy)
        val b = report.result(against)
        return Comparison(
            strategy = strategy,
            against = against,
            difference = Metrics.bootstrapDifference(a.perQueryNdcg, b.perQueryNdcg, resamples, seed = seed),
        )
    }

    /**
     * Splits queries by date: everything before [boundary] trains, everything on
     * or after it is held out.
     *
     * A random split leaks the future into the past. Preferences drift, catalogues
     * change and a title's availability today is evidence about tonight rather
     * than about last March — so a model tuned on a random sample of all time
     * gets to see outcomes it would not have had, and reports a number it cannot
     * reproduce in production. There is nothing to train yet, which is exactly
     * why the split belongs here now: phase 8 must not be the moment somebody
     * decides how to divide the data.
     */
    fun temporalSplit(queries: List<EvaluationQuery>, boundary: LocalDate): Split = Split(
        train = queries.filter { it.askedOn.isBefore(boundary) },
        test = queries.filter { !it.askedOn.isBefore(boundary) },
        boundary = boundary,
    )

    data class Split(val train: List<EvaluationQuery>, val test: List<EvaluationQuery>, val boundary: LocalDate)

    data class StrategyResult(
        val name: String,
        val ndcg: Metrics.Interval?,
        val ndcgStandardDeviation: Double?,
        val precision: Metrics.Interval?,
        val meanReciprocalRank: Metrics.Interval?,
        /** Kept so comparisons stay paired. Not for display. */
        val perQueryNdcg: List<Double>,
    )

    data class Comparison(val strategy: String, val against: String, val difference: Metrics.Interval?) {
        /**
         * How to say this out loud without overclaiming.
         *
         * An interval straddling zero means the data cannot separate the two.
         * That is a result and it is reported as one, rather than as a
         * near-miss that a few more queries would fix.
         */
        val verdict: String
            get() {
                val interval = difference ?: return "no data"
                return when {
                    !interval.excludesZero ->
                        "no measurable difference (%.4f, 95%% CI %.4f to %.4f, n=%d)"
                            .format(interval.mean, interval.lower, interval.upper, interval.sampleSize)
                    interval.mean > 0 ->
                        "%s ahead by %.4f NDCG (95%% CI %.4f to %.4f, n=%d)"
                            .format(strategy, interval.mean, interval.lower, interval.upper, interval.sampleSize)
                    else ->
                        "%s behind by %.4f NDCG (95%% CI %.4f to %.4f, n=%d)"
                            .format(strategy, -interval.mean, interval.lower, interval.upper, interval.sampleSize)
                }
            }
    }

    data class Report(
        val k: Int,
        val queriesConsidered: Int,
        val queriesScored: Int,
        val results: List<StrategyResult>,
    ) {
        fun result(name: String): StrategyResult =
            results.firstOrNull { it.name == name } ?: error("No strategy named '$name' in this report")

        /**
         * A markdown table, so a run can be pasted into `EVALUATION.md` without
         * anyone retyping numbers. Retyped numbers are how a document ends up
         * disagreeing with the code that produced it.
         */
        fun toMarkdown(): String = buildString {
            appendLine("| Strategy | NDCG@$k | 95% CI | Precision@$k | MRR |")
            appendLine("|---|---|---|---|---|")
            results.sortedByDescending { it.ndcg?.mean ?: -1.0 }.forEach { result ->
                appendLine(
                    "| %s | %s | %s | %s | %s |".format(
                        result.name,
                        result.ndcg?.let { "%.4f".format(it.mean) } ?: "—",
                        result.ndcg?.let { "%.4f – %.4f".format(it.lower, it.upper) } ?: "—",
                        result.precision?.let { "%.4f".format(it.mean) } ?: "—",
                        result.meanReciprocalRank?.let { "%.4f".format(it.mean) } ?: "—",
                    ),
                )
            }
            appendLine()
            appendLine("$queriesScored of $queriesConsidered queries were scorable; the rest had no relevant candidate to find.")
        }
    }

    companion object {
        /** Three, because the product answers with one pick and two backups. */
        const val DEFAULT_K = 3
    }
}
