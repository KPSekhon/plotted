package app.plotted.recommendation.evaluation

import app.plotted.recommendation.model.FeatureSchema
import app.plotted.recommendation.model.OnnxScorer
import java.util.UUID

/**
 * The trained model, as something the evaluation harness can score.
 *
 * This is the whole reason phase 7 was built before phase 8. A learned ranker
 * that has not been measured against the model it replaces is a change nobody
 * can defend, and "the ML one" is not an argument. Putting the model behind a
 * [RankingStrategy] means it is compared on the same queries, with the same
 * metric and the same paired bootstrap, as the linear model and the
 * priority-only baseline — before it is allowed anywhere near a user.
 *
 * ### One batch, not one call per candidate
 *
 * A single session run over the whole candidate list. At this model size the
 * per-call overhead dominates the arithmetic, so scoring twelve candidates
 * individually costs roughly twelve times what scoring them together does, for
 * an identical answer.
 *
 * ### Ties
 *
 * Broken on title id, exactly as every other strategy here does. Not cosmetic:
 * without a deterministic tie-break the report's confidence intervals move
 * between runs, which is a bug this project has already shipped once and now
 * fails the build on.
 */
class LearnedModelStrategy(
    private val scorer: OnnxScorer,
    override val name: String = "learned-${scorer.metadata.modelVersion}",
) : RankingStrategy {
    override fun rank(query: EvaluationQuery): List<UUID> {
        val vectors = query.candidates.map { candidate ->
            FeatureSchema.extract(candidate, query.context, query.subscribedProviderIds, query.askedOn)
        }
        val scores = scorer.score(vectors)

        return query.candidates.indices
            .sortedWith(
                compareByDescending<Int> { scores[it] }.thenBy { query.candidates[it].titleId },
            )
            .map { query.candidates[it].titleId }
    }
}
