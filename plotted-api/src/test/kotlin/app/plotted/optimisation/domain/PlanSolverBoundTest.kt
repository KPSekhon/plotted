package app.plotted.optimisation.domain

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.util.UUID

/**
 * The 20-second latency bound, pinned as a count rather than as a stopwatch.
 *
 * Section 13.1 puts a budget on this endpoint, and the number everything is
 * reasoned about — twenty seconds — is not a measurement. It is
 * `SOLVE_TIME_LIMIT_SECONDS × the most solves one request can require`. On real
 * instances CP-SAT proves optimality in milliseconds and nothing has come close,
 * so measuring the wall clock would assert almost nothing and would assert it
 * flakily, on a shared CI runner, against the standing rule that tests must not
 * depend on how fast the machine happened to be.
 *
 * The count is the honest thing to test. It is deterministic, it is what the
 * bound is actually derived from, and a fourth sensitivity probe added later
 * would move the bound to twenty-five seconds without changing a single number
 * in any document — which is exactly the sort of silent drift this fails on.
 *
 * Gated like every other solver test: CP-SAT kills the JVM on the Windows dev
 * machine, so this runs in CI.
 */
@EnabledIf("app.plotted.support.SolverSupport#isSolverAvailable")
class PlanSolverBoundTest {
    private val solver = PlanSolver()

    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val disney = UUID.randomUUID()

    @Test
    fun `the worst case is four solves, which is where twenty seconds comes from`() {
        // Every constraint set and every one of them tight enough to bind, so the
        // sensitivity pass has a reason to re-solve for all three.
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = true),
                service(crave, "Crave", 2_500, currentlySubscribed = false),
                service(disney, "Disney+", 1_599, currentlySubscribed = false),
            ),
            titles = listOf(
                demand("Only on Crave", 5, crave),
                demand("Only on Disney", 4, disney),
                demand("Only on Netflix", 3, netflix),
            ),
            constraints = PlanConstraints(
                horizonMonths = 4,
                maximumMonthlyCents = 2_000,
                maximumActiveServices = 1,
                maximumMonthlySwitches = 1,
            ),
            weights = PlanWeights(coverage = 0.6, cost = 0.3, switching = 0.1),
        )

        solver.solve(request)

        // One for the plan, at most one per binding constraint, of which there
        // are three. Four × the five-second cap is the twenty seconds every
        // latency claim about this endpoint rests on.
        solver.solvesInLastRequest() shouldBeLessThanOrEqual MAXIMUM_SOLVES
    }

    @Test
    fun `a request with no constraints needs exactly one solve`() {
        // The other end of the range, and the common case. Nothing to probe means
        // nothing to re-solve, so the ordinary request costs a quarter of the
        // budget the worst case is allowed.
        val request = PlanRequest(
            services = listOf(service(netflix, "Netflix", 1_899, currentlySubscribed = true)),
            titles = listOf(demand("On Netflix", 3, netflix)),
            constraints = PlanConstraints(horizonMonths = 3, null, null, null),
            weights = PlanWeights.DEFAULT,
        )

        solver.solve(request)

        solver.solvesInLastRequest() shouldBe 1
    }

    @Test
    fun `one constraint costs one probe`() {
        // The middle of the range, and the case that caught an error in the first
        // version of this file. A budget below the cheapest service looks
        // infeasible and is not: the model simply holds nothing, which satisfies
        // every constraint and covers no titles. So this is a *solved* request
        // with one constraint set, and the sensitivity pass probes it once.
        val request = PlanRequest(
            services = listOf(service(crave, "Crave", 2_500, currentlySubscribed = false)),
            titles = listOf(demand("Only on Crave", 5, crave)),
            constraints = PlanConstraints(horizonMonths = 3, maximumMonthlyCents = 100, null, null),
            weights = PlanWeights.DEFAULT,
        )

        solver.solve(request)

        solver.solvesInLastRequest() shouldBe 2
    }

    @Test
    fun `a genuinely infeasible request does not re-solve at all`() {
        // A commitment is a hard equality in the model, so a commitment costing
        // more than the budget cannot be satisfied by holding nothing. This is
        // the real infeasible path.
        //
        // Worth pinning because the diagnosis is pure logic -- `explainInfeasibility`
        // reasons about the constraints rather than probing them -- and if it ever
        // grew a solve of its own, the infeasible path would silently cost more
        // than the feasible one. It is also where a stray probe would be least
        // visible: no plan to look wrong, only a slower answer.
        val request = PlanRequest(
            services = listOf(committed(crave, "Crave", 2_500, months = 3)),
            titles = listOf(demand("Only on Crave", 5, crave)),
            constraints = PlanConstraints(horizonMonths = 3, maximumMonthlyCents = 1_000, null, null),
            weights = PlanWeights.DEFAULT,
        )

        val outcome = solver.solve(request)

        (outcome is PlanOutcome.Infeasible) shouldBe true
        solver.solvesInLastRequest() shouldBe 1
    }

    private fun service(id: UUID, name: String, cents: Long, currentlySubscribed: Boolean) =
        ServiceOption(id, name, cents, 0, currentlySubscribed)

    private fun committed(id: UUID, name: String, cents: Long, months: Int) = ServiceOption(id, name, cents, months, true)

    private fun demand(name: String, points: Int, vararg on: UUID) = TitleDemand(UUID.randomUUID(), name, points, on.toSet())

    private companion object {
        /** The plan, plus one probe per constraint that can bind. */
        const val MAXIMUM_SOLVES = 4
    }
}
