package app.plotted.recommendation.evaluation

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

        val strategies = listOf(
            LinearModelStrategy(),
            NoRenormalisationStrategy(),
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
        listOf(
            "linear-v1" to "linear-v1-no-renormalisation",
            "linear-v1" to "watchlist-priority",
            "linear-v1" to "popularity",
            "linear-v1" to "random",
        ).forEach { (strategy, against) ->
            println("- against **$against**: ${harness.compare(report, strategy, against).verdict}")
        }
        println()
        println(
            "Paired over identical queries. An interval straddling zero means the data cannot " +
                "separate the two, which is a result rather than a near miss.",
        )
    }
}
