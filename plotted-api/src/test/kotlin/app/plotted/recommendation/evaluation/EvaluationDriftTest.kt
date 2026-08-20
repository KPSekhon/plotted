package app.plotted.recommendation.evaluation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/EVALUATION.md` still quotes the number this code produces.
 *
 * ### Why this was needed
 *
 * [EvaluationReportTest] proves the report is reproducible: two runs agree with
 * each other, and a different seed disagrees. Both are true, and neither says
 * anything about the *committed document* — which is the thing people read, and
 * the thing that gets quoted.
 *
 * So it drifted. The page claimed 0.0191 NDCG@3 while the simulation had moved
 * to 0.0184, and nothing objected until somebody compared them by hand. The
 * `evaluate` Gradle task *prints* the report rather than writing it, so keeping
 * the file current was a manual step, and manual steps stop happening. That is
 * the same failure the OpenAPI drift check exists to prevent, occurring in the
 * document that carries the only defensible measurement in the project.
 *
 * A number nobody can defend is worse than no number. A number that *used* to be
 * defensible is worse still, because it is quoted with confidence.
 *
 * ### Why it pins the headline only
 *
 * `EVALUATION.md` is mostly hand-written prose about what the figures do *not*
 * say, with generated numbers embedded. Regenerating it wholesale would throw
 * that away. The headline comparison is the claim the document is built around,
 * so that is what is pinned — and if it moves, the prose beside it needs
 * rereading anyway.
 */
class EvaluationDriftTest {
    @Test
    fun `the committed report quotes the figure the simulation produces now`() {
        val document = File(EVALUATION_DOCUMENT)
        // Skipped rather than failed when absent: the path is repository-relative
        // and a build run from an unexpected working directory should not invent
        // a failure about a file it simply cannot see.
        if (!document.isFile) return

        val harness = EvaluationHarness()
        val report = harness.run(MetadataCensoringSimulation(queries = QUERIES).generate(), STRATEGIES)
        val difference = harness.compare(report, LINEAR, BASELINE).difference
            ?: error("The paired comparison produced no interval, so there is nothing to check against.")

        val produced = "%.4f".format(difference.mean)
        val quoted = document.readText().contains(produced)

        if (!quoted) {
            throw AssertionError(
                "docs/EVALUATION.md no longer quotes $produced NDCG@3, which is what the simulation " +
                    "produces today for $LINEAR against $BASELINE.\n\n" +
                    "Run `./gradlew :plotted-api:evaluate` and update the document — including the prose " +
                    "around the figure, which is the part that stops it being read as a larger claim " +
                    "than it is.",
            )
        }
        quoted shouldBe true
    }

    private companion object {
        /** Matches the committed document, so the figures are comparable. */
        const val QUERIES = 2000

        const val LINEAR = "linear-v1"
        const val BASELINE = "linear-v1-no-renormalisation"

        /** Repository-relative: tests run with the `plotted-api` module as the working directory. */
        const val EVALUATION_DOCUMENT = "../docs/EVALUATION.md"

        val STRATEGIES = listOf(
            LinearModelStrategy(),
            NoRenormalisationStrategy(),
            WatchlistPriorityStrategy,
            PopularityStrategy,
            RandomStrategy(),
        )
    }
}
