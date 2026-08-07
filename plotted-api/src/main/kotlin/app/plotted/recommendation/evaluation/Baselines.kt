package app.plotted.recommendation.evaluation

import app.plotted.recommendation.domain.Candidate
import app.plotted.recommendation.domain.Feature
import app.plotted.recommendation.domain.Ranker
import app.plotted.recommendation.domain.TonightContext
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * One evaluation query: everything a ranker is given, plus the answer.
 *
 * `relevance` is held apart from the candidates rather than on them, so that a
 * strategy cannot read the label it is being scored against. That is not
 * paranoia — a feature accidentally derived from the outcome is the classic way
 * an offline evaluation reports a number nobody can reproduce online, and
 * keeping the label out of reach makes it structurally impossible rather than
 * merely unlikely.
 */
data class EvaluationQuery(
    val queryId: String,
    val candidates: List<Candidate>,
    val context: TonightContext,
    val subscribedProviderIds: Set<UUID>,
    val askedOn: LocalDate,
    /** Gain per title. Absent means zero — the user did not take it. */
    val relevance: Map<UUID, Double>,
) {
    fun relevancesFor(ranked: List<UUID>): List<Double> = ranked.map { relevance[it] ?: 0.0 }

    /** Whether this query can be scored at all. Nothing relevant means nothing to find. */
    val isAnswerable: Boolean get() = candidates.any { (relevance[it.titleId] ?: 0.0) > 0.0 }
}

/**
 * A way of ordering candidates. The thing being compared.
 *
 * Every baseline here is one somebody would actually reach for, which is what
 * makes them worth beating. A harness whose baselines are straw men produces a
 * flattering number and no information.
 */
interface RankingStrategy {
    val name: String
    fun rank(query: EvaluationQuery): List<UUID>
}

/**
 * The floor. If a ranker cannot beat this, nothing else in the report matters.
 *
 * Seeded per query rather than globally so the order does not depend on which
 * queries were evaluated before it — otherwise adding a query silently changes
 * every later one's score.
 */
class RandomStrategy(private val seed: Long = 20260806L) : RankingStrategy {
    override val name = "random"

    override fun rank(query: EvaluationQuery): List<UUID> =
        query.candidates.map { it.titleId }.shuffled(Random(seed + query.queryId.hashCode()))
}

/**
 * Most acclaimed first. The baseline that is genuinely hard to beat.
 *
 * Popularity wins whenever preferences are more alike than they are different,
 * which for films is most of the time. Reporting where the linear model *loses*
 * to this is the point of having it: a harness that only produces flattering
 * numbers is not a harness.
 *
 * Unrated titles go last rather than scoring zero, for the same reason the
 * ranker treats a missing feature as absent — an unrated film is not a film
 * everybody hated. Ties break on title id so the order is stable.
 */
object PopularityStrategy : RankingStrategy {
    override val name = "popularity"

    override fun rank(query: EvaluationQuery): List<UUID> = query.candidates
        .sortedWith(
            compareByDescending<Candidate> { it.communityRating ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.titleId },
        )
        .map { it.titleId }
}

/**
 * What the user said they wanted most, and nothing else.
 *
 * The strongest single signal available, and a baseline the full model has to
 * justify itself against: if five weighted features cannot beat "read the
 * priority the user typed in", the other four are decoration.
 */
object WatchlistPriorityStrategy : RankingStrategy {
    override val name = "watchlist-priority"

    override fun rank(query: EvaluationQuery): List<UUID> = query.candidates
        .sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.titleId })
        .map { it.titleId }
}

/**
 * The shipped ranker's score, without diversification or exploration.
 *
 * MMR and the exploration slot are deliberately excluded. Both trade relevance
 * for something else on purpose — variety in the backups, and information for
 * this harness — so scoring them on a pure relevance metric would measure them
 * against a goal they were built to compromise. What is evaluated here is the
 * scoring function, which is the part that claims to be good at ranking.
 *
 * Candidates the ranker cannot score at all go last, in their original order.
 * Dropping them would quietly shrink the list and flatter every metric.
 */
class LinearModelStrategy(
    private val ranker: Ranker = Ranker(),
    override val name: String = "linear-v1",
) : RankingStrategy {
    override fun rank(query: EvaluationQuery): List<UUID> {
        val scored = query.candidates.map { candidate ->
            candidate to ranker.score(candidate, query.context, query.subscribedProviderIds, query.askedOn)?.score
        }
        return scored
            .sortedWith(compareByDescending<Pair<Candidate, Double?>> { it.second ?: Double.NEGATIVE_INFINITY }.thenBy { it.first.titleId })
            .map { it.first.titleId }
    }
}

/**
 * The linear model with renormalisation removed — the ablation that matters.
 *
 * The shipped scorer divides the weighted sum by the weight *actually present*
 * on a candidate. Remove that and a candidate missing a 0.10-weight feature can
 * score at most 0.90, so it loses to an otherwise identical candidate with
 * complete metadata, and the ranking silently becomes a ranking of data quality.
 *
 * ### Why this does not reimplement the scorer
 *
 * It rescales the real one. Since
 * `score = Σ(weight × value) / availableWeight`, the un-normalised score is
 * exactly `score × availableWeight / totalWeight`. So this ablation runs the
 * production scoring path and then undoes one division — which means it cannot
 * drift away from what ships, and the arithmetic relating the two is stated
 * rather than duplicated. A hand-written second scorer would have been a second
 * thing to keep in step, and any difference between them would have been
 * indistinguishable from the effect being measured.
 */
class NoRenormalisationStrategy(
    private val ranker: Ranker = Ranker(),
) : RankingStrategy {
    override val name = "linear-v1-no-renormalisation"

    override fun rank(query: EvaluationQuery): List<UUID> {
        val scored = query.candidates.map { candidate ->
            val vector = ranker.score(candidate, query.context, query.subscribedProviderIds, query.askedOn)
            val unnormalised = vector?.let { it.score * it.features.present.sumOf { feature -> feature.weight } / Feature.TOTAL_WEIGHT }
            candidate to unnormalised
        }
        return scored
            .sortedWith(compareByDescending<Pair<Candidate, Double?>> { it.second ?: Double.NEGATIVE_INFINITY }.thenBy { it.first.titleId })
            .map { it.first.titleId }
    }
}
