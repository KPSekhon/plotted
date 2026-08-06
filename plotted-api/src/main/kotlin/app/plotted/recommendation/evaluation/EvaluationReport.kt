package app.plotted.recommendation.evaluation

import app.plotted.recommendation.model.OnnxScorer
import java.nio.file.Path

/**
 * Runs the evaluation and prints it as markdown.
 *
 *     ./gradlew :plotted-api:evaluate
 *
 * No Spring context and no database, so it runs on a clean checkout. That is
 * deliberate: a report nobody can regenerate is a report that stops being true
 * without anybody noticing, and requiring an environment is the usual reason
 * nobody regenerates one.
 *
 * Everything it prints today comes from [MetadataCensoringSimulation], which has
 * no user in it. The moment there are logged decisions with outcomes, the same
 * [EvaluationHarness] runs over those instead and the simulation becomes the
 * unit test it always was.
 */
object EvaluationReport {
    @JvmStatic
    fun main(args: Array<String>) {
        val harness = EvaluationHarness()
        val queries = MetadataCensoringSimulation().generate()

        // The learned model joins the table only if one is on disk. Absent is
        // the normal state, and a report that refused to run without a model
        // would be a report nobody could run on a clean checkout.
        val learned = when (val result = OnnxScorer.load(Path.of(MODEL_PATH))) {
            is OnnxScorer.LoadResult.Loaded -> result.scorer
            is OnnxScorer.LoadResult.Refused -> null
        }

        val strategies = listOfNotNull(
            LinearModelStrategy(),
            NoRenormalisationStrategy(),
            learned?.let { LearnedModelStrategy(it) },
            WatchlistPriorityStrategy,
            PopularityStrategy,
            RandomStrategy(),
        )
        val report = harness.run(queries, strategies)

        println("# Evaluation run")
        println()
        println("Simulation: ${MetadataCensoringSimulation.DEFAULT_QUERIES} queries, ")
        println("${MetadataCensoringSimulation.DEFAULT_CANDIDATES} candidates each, ")
        println("${(MetadataCensoringSimulation.DEFAULT_CENSOR_RATE * 100).toInt()}% of optional fields censored, ")
        println("seed ${MetadataCensoringSimulation.DEFAULT_SEED}.")
        println()
        print(report.toMarkdown())
        println()
        println("## Paired comparisons, NDCG@${report.k}")
        println()
        buildList {
            add("linear-v1" to "linear-v1-no-renormalisation")
            add("linear-v1" to "watchlist-priority")
            add("linear-v1" to "popularity")
            add("linear-v1" to "random")
            // The learned model is compared against the thing it would replace,
            // not against random. Beating a shuffle is not an argument for
            // shipping anything.
            learned?.let { add(LearnedModelStrategy(it).name to "linear-v1") }
        }.forEach { (strategy, against) ->
            println("- against **$against**: ${harness.compare(report, strategy, against).verdict}")
        }
        println()
        println(
            "Paired over identical queries. An interval straddling zero means the data cannot " +
                "separate the two, which is a result rather than a near miss.",
        )

        learned?.close()
    }

    /** Relative to `plotted-api/`, which is where Gradle runs the task from. */
    private const val MODEL_PATH = "../models/ranker.onnx"
}
