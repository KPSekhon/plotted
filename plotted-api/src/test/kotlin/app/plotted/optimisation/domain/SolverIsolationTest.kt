package app.plotted.optimisation.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The isolation itself, asserted rather than assumed.
 *
 * `plotted-api` depends on `plotted-solver` for the model types and
 * `PlanChecker`, and excludes OR-Tools from that dependency. The exclusion is
 * the whole guarantee: with it, the native library that can kill a JVM is not on
 * this application's classpath and cannot be loaded here even by a mistake; take
 * it away and the API silently regains the ability to crash exactly as it did
 * before, with nothing failing to say so.
 *
 * A Gradle exclusion is four words in a build file and reads like tidying up.
 * This is what stops someone removing it.
 *
 * Watched fail before being trusted: with the `exclude` deleted from
 * `plotted-api/build.gradle.kts`, the class resolves and this test fails.
 */
class SolverIsolationTest {
    @Test
    fun `OR-Tools is not on the API's classpath`() {
        val found = runCatching { Class.forName("com.google.ortools.Loader") }.isSuccess

        // If this fails, do not fix it by deleting the assertion. The optimiser
        // runs in `plotted-solver`'s own process precisely so a native fault --
        // which is a process death, not an exception -- costs one solve instead
        // of every endpoint the API serves.
        found shouldBe false
    }

    @Test
    fun `the shared model and the checker are still reachable`() {
        // The other half of the claim. Excluding the native library must not cost
        // the types the two processes agree on, or the exclusion would have been
        // achieved by severing the contract instead of by moving the solver.
        runCatching { Class.forName("app.plotted.solver.PlanChecker") }.isSuccess shouldBe true
        runCatching { Class.forName("app.plotted.solver.PlanRequest") }.isSuccess shouldBe true
    }
}
