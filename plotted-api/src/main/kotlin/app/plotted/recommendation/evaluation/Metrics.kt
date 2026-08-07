package app.plotted.recommendation.evaluation

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Ranking metrics, written out rather than pulled from a library.
 *
 * Not for want of a library — for want of an *argument*. Every one of these has
 * a detail that is silently wrong in some implementations, and a number nobody
 * can defend is worse than no number at all when the claim being made is "my
 * ranker beats popularity".
 */
object Metrics {
    /**
     * Normalised discounted cumulative gain at [k].
     *
     * The discount is `1 / log2(i + 1)` with `i` one-based, so position 1 is
     * undiscounted. The normaliser is the DCG of the *best achievable* ordering
     * of the same relevances, which is what makes lists of different difficulty
     * comparable — without it a query where everything is relevant scores higher
     * than one where nothing is, and the ranker gets credit for the question.
     *
     * Returns null, not zero, when there is nothing relevant to find. A query
     * with no relevant items is unanswerable rather than answered badly, and
     * averaging zeros for it drags every mean toward a number that describes the
     * dataset instead of the ranker. This is the single most common way an
     * NDCG figure gets quietly inflated or deflated.
     *
     * @param relevances gains in ranked order, most confident first.
     */
    fun ndcgAt(relevances: List<Double>, k: Int): Double? {
        require(k > 0) { "k must be positive" }
        val ideal = relevances.sortedDescending()
        val idealGain = dcgAt(ideal, k)
        if (idealGain <= 0.0) return null
        return dcgAt(relevances, k) / idealGain
    }

    fun dcgAt(relevances: List<Double>, k: Int): Double = relevances
        .take(k)
        .mapIndexed { index, relevance -> relevance / log2(index + 2.0) }
        .sum()

    /**
     * Share of the top [k] that were relevant.
     *
     * The denominator is `min(k, size)`, not `k`. Charging a ranker for slots it
     * was never given — a three-slot metric over a two-candidate list — measures
     * how short the list was.
     */
    fun precisionAt(relevances: List<Double>, k: Int, threshold: Double = 0.5): Double? {
        require(k > 0) { "k must be positive" }
        if (relevances.isEmpty()) return null
        val considered = relevances.take(k)
        return considered.count { it >= threshold }.toDouble() / considered.size
    }

    /**
     * Reciprocal rank of the first relevant item, or 0 when there is none.
     *
     * Zero rather than null here, deliberately, and the asymmetry with [ndcgAt]
     * is the point: "the ranker put nothing relevant anywhere in the list" is a
     * real and bad outcome that this metric exists to punish, whereas "there was
     * nothing relevant to find" is a property of the query. Only the second is
     * excluded, by the caller, before it gets here.
     */
    fun reciprocalRank(relevances: List<Double>, threshold: Double = 0.5): Double {
        val position = relevances.indexOfFirst { it >= threshold }
        return if (position < 0) 0.0 else 1.0 / (position + 1)
    }

    /**
     * Percentile bootstrap confidence interval for the mean of [values].
     *
     * Resampling with replacement rather than a normal approximation, because
     * per-query NDCG is bounded in [0,1] and heavily skewed — a t-interval on it
     * routinely produces bounds outside the range the metric can take, which is
     * a visible sign of a model that does not fit.
     *
     * Seeded, so the same data gives the same interval. An evaluation harness
     * that reports a different confidence interval each time it runs invites
     * exactly one behaviour: running it again.
     */
    fun bootstrapMean(
        values: List<Double>,
        resamples: Int = DEFAULT_RESAMPLES,
        confidence: Double = DEFAULT_CONFIDENCE,
        seed: Long = DEFAULT_SEED,
    ): Interval? {
        if (values.isEmpty()) return null
        if (values.size == 1) return Interval(values.single(), values.single(), values.single(), 1)

        val random = kotlin.random.Random(seed)
        val means = DoubleArray(resamples) {
            var total = 0.0
            repeat(values.size) { total += values[random.nextInt(values.size)] }
            total / values.size
        }
        means.sort()

        val tail = (1.0 - confidence) / 2.0
        return Interval(
            mean = values.average(),
            lower = means[percentileIndex(tail, resamples)],
            upper = means[percentileIndex(1.0 - tail, resamples)],
            sampleSize = values.size,
        )
    }

    /**
     * Paired bootstrap on the difference between two rankers over the same queries.
     *
     * Paired, and that is the whole reason this exists separately: the two
     * rankers saw identical queries, so most of the variance is the queries
     * being easy or hard rather than either ranker being good. Comparing two
     * independent intervals throws that pairing away and will call a real
     * improvement inconclusive.
     *
     * An interval that straddles zero means the data cannot tell the two apart —
     * which is a result, and one worth reporting rather than rerunning until it
     * does not.
     */
    fun bootstrapDifference(
        a: List<Double>,
        b: List<Double>,
        resamples: Int = DEFAULT_RESAMPLES,
        confidence: Double = DEFAULT_CONFIDENCE,
        seed: Long = DEFAULT_SEED,
    ): Interval? {
        require(a.size == b.size) { "Paired comparison needs the same queries: ${a.size} against ${b.size}" }
        if (a.isEmpty()) return null
        return bootstrapMean(a.indices.map { a[it] - b[it] }, resamples, confidence, seed)
    }

    /** Sample standard deviation. Reported alongside a mean that would otherwise hide its spread. */
    fun standardDeviation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun percentileIndex(percentile: Double, size: Int): Int = ((percentile * (size - 1)).toInt()).coerceIn(0, size - 1)

    private fun log2(value: Double): Double = ln(value) / LN_2

    /**
     * A mean with an interval around it.
     *
     * `sampleSize` travels with it because a mean of 0.62 over four queries and
     * a mean of 0.62 over four hundred are different claims, and a table of
     * numbers without it invites reading them as the same.
     */
    data class Interval(val mean: Double, val lower: Double, val upper: Double, val sampleSize: Int) {
        /** Whether the interval excludes zero — the only honest phrasing of "significant" here. */
        val excludesZero: Boolean get() = lower > 0.0 || upper < 0.0
    }

    private val LN_2 = ln(2.0)

    /** Enough that the interval is stable to about the third decimal, which is finer than anything reported. */
    const val DEFAULT_RESAMPLES = 2_000
    const val DEFAULT_CONFIDENCE = 0.95
    const val DEFAULT_SEED = 20260806L
}
