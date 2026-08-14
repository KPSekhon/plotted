package app.plotted.solver

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.util.UUID
import kotlin.random.Random

/**
 * The solver and the checker, made to agree on plans neither one produced alone.
 *
 * [PlanCheckerTest] proves the checker notices violations. This proves the model
 * does not commit any — and, more importantly, that the model is *the right
 * model*. Those are different claims and only the second one is hard: a solver
 * returns the optimum of whatever you specified, so a mis-specified constraint
 * or an objective term with the wrong sign produces a plan that is internally
 * consistent, confidently presented, and wrong.
 *
 * The strongest test available is therefore not "does the plan satisfy the
 * rules" but **"is there a plan that satisfies the rules and scores better"**.
 * For instances small enough to enumerate, every possible plan is built, judged
 * feasible or not by [PlanChecker] alone, scored by [PlanChecker] alone, and the
 * best of them compared against what CP-SAT returned. Nothing in that path
 * shares a line of code with the model builder. If the two disagree, either the
 * model is wrong or the rules are — and either way it is worth a failing build.
 *
 * Gated on [app.plotted.solver.SolverSupport] like every other solver test:
 * CP-SAT kills the JVM on the Windows dev machine, so these run in CI.
 */
@EnabledIf("app.plotted.solver.SolverSupport#isSolverAvailable")
class PlanSolverAgreementTest {
    private val solver = PlanModel()

    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val disney = UUID.randomUUID()

    @Test
    fun `the solver never returns a plan the checker rejects`() {
        instances().forEach { (label, request) ->
            val outcome = solver.solve(request)
            if (outcome is PlanOutcome.Solved) {
                // Not `violations shouldBe emptyList()` — the messages are the
                // useful part of the failure, and they name the month.
                withClue(label) { outcome.violations.shouldBeEmpty() }
            }
        }
    }

    @Test
    fun `no feasible plan scores better than the one the solver chose`() {
        instances().forEach { (label, request) ->
            val outcome = solver.solve(request)
            val best = bestByExhaustiveSearch(request)

            if (best == null) {
                // The independent search found nothing feasible, so the solver
                // must not have found something either. A solver returning a
                // plan the rules cannot admit is the worst outcome available.
                withClue("$label: exhaustive search found nothing feasible") {
                    (outcome is PlanOutcome.Infeasible) shouldBe true
                }
                return@forEach
            }

            withClue("$label: exhaustive search found a feasible plan, solver did not") {
                (outcome is PlanOutcome.Solved) shouldBe true
            }
            val solved = outcome as PlanOutcome.Solved

            withClue(
                "$label: solver scored ${solved.objective.weighted}, " +
                    "exhaustive search found ${best.weighted} — the model is optimising the wrong thing",
            ) {
                (solved.objective.weighted >= best.weighted - TOLERANCE) shouldBe true
            }
        }
    }

    @Test
    fun `a commitment is never cancelled, however expensive and useless it is`() {
        // Crave is dear, carries nothing anyone wants, and is locked for two
        // months. Every incentive in the objective points at cancelling it.
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = false),
                service(crave, "Crave", 2_500, committedMonths = 2, currentlySubscribed = true),
            ),
            titles = listOf(demand("A Film", 5, netflix)),
            constraints = constraints(horizonMonths = 3),
            weights = PlanWeights(coverage = 0.2, cost = 0.7, switching = 0.1),
        )

        val solved = solver.solve(request).shouldBeSolved()

        solved.months.first { it.month == 0 }.subscribedProviderIds.contains(crave) shouldBe true
        solved.months.first { it.month == 1 }.subscribedProviderIds.contains(crave) shouldBe true
        // Free by month 2, and with cost weighted this heavily it should go.
        solved.months.first { it.month == 2 }.subscribedProviderIds.contains(crave) shouldBe false
        solved.violations.shouldBeEmpty()
    }

    @Test
    fun `churn is counted, not hidden`() {
        // The failure this pins: without `u + d <= 1` the solver can satisfy the
        // transition equality by setting both indicators for the same service in
        // the same month, which under-reports switching and so under-prices it.
        // The checker recomputes starts and stops from the subscription state, so
        // any such plan comes back with a "reports starting" violation.
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = true),
                service(crave, "Crave", 1_200, currentlySubscribed = false),
                service(disney, "Disney+", 1_599, currentlySubscribed = true),
            ),
            titles = listOf(
                demand("On Crave later", 5, crave),
                demand("On Netflix", 3, netflix),
            ),
            // Heavy switching weight: the model has every reason to under-report.
            constraints = constraints(horizonMonths = 4),
            weights = PlanWeights(coverage = 0.3, cost = 0.2, switching = 0.5),
        )

        val solved = solver.solve(request).shouldBeSolved()

        solved.violations.shouldBeEmpty()
        solved.months.forEach { month ->
            withClue("month ${month.month} both starts and stops the same service") {
                (month.startedProviderIds intersect month.stoppedProviderIds).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a zero-switch limit freezes the plan at what the user already pays for`() {
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = true),
                service(crave, "Crave", 1_200, currentlySubscribed = false),
            ),
            // Everything the user wants is on the service they do not have, so
            // the only reason not to switch is the limit itself.
            titles = listOf(demand("On Crave", 5, crave)),
            constraints = constraints(horizonMonths = 3, maximumMonthlySwitches = 0),
            weights = PlanWeights.DEFAULT,
        )

        val solved = solver.solve(request).shouldBeSolved()

        solved.months.forEach {
            it.subscribedProviderIds shouldBe setOf(netflix)
            it.startedProviderIds.shouldBeEmpty()
            it.stoppedProviderIds.shouldBeEmpty()
        }
        solved.violations.shouldBeEmpty()
        // And it says so: nothing was covered, because nothing could be.
        solved.covered.shouldBeEmpty()
    }

    @Test
    fun `a budget below the cheapest service is explained rather than returned as an error`() {
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, committedMonths = 3, currentlySubscribed = true),
            ),
            titles = listOf(demand("A Film", 5, netflix)),
            constraints = constraints(horizonMonths = 3, maximumMonthlyCents = 500),
            weights = PlanWeights.DEFAULT,
        )

        val outcome = solver.solve(request)

        (outcome is PlanOutcome.Infeasible) shouldBe true
        val infeasible = outcome as PlanOutcome.Infeasible
        infeasible.bindingConstraint shouldBe "maximumMonthlyBudget"
        // The explanation has to name the number the user can act on. "Infeasible"
        // alone is accurate and useless.
        infeasible.explanation.contains("5.00") shouldBe true
    }

    @Test
    fun `one service at a time over two months is answered by rotating, not by giving up half the list`() {
        // This is the feature working, and it is worth pinning explicitly: the
        // first version of the test below assumed a one-service limit had to
        // cost half the coverage, and the model found the plan a person would
        // want instead — hold the cheap one this month, switch to the other next
        // month, and see everything. The limit is on services held *at once*,
        // and only a model with a real time dimension can exploit that.
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = false),
                service(crave, "Crave", 1_200, currentlySubscribed = false),
            ),
            titles = listOf(demand("On Netflix", 5, netflix), demand("On Crave", 5, crave)),
            constraints = constraints(horizonMonths = 2, maximumActiveServices = 1),
            weights = PlanWeights.DEFAULT,
        )

        val solved = solver.solve(request).shouldBeSolved()

        solved.objective.coverage shouldBe 1.0
        solved.months.forEach { (it.subscribedProviderIds.size <= 1) shouldBe true }
        // Which month gets which service is a tie the solver may break either
        // way, so the assertion is about the rotation rather than its order.
        solved.months.flatMap { it.subscribedProviderIds }.toSet() shouldBe setOf(netflix, crave)
        solved.violations.shouldBeEmpty()
    }

    @Test
    fun `sensitivity reports what one more service would buy`() {
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = false),
                service(crave, "Crave", 1_200, currentlySubscribed = false),
            ),
            // One title on each and a single month, so rotation is off the table
            // and the limit genuinely costs coverage. Over a longer horizon the
            // solver rotates instead — see the test above.
            titles = listOf(demand("On Netflix", 5, netflix), demand("On Crave", 5, crave)),
            constraints = constraints(horizonMonths = 1, maximumActiveServices = 1),
            weights = PlanWeights(coverage = 0.8, cost = 0.15, switching = 0.05),
        )

        val solved = solver.solve(request).shouldBeSolved()

        val limit = solved.sensitivity.firstOrNull { it.constraint == "maximumActiveServices" }
        withClue("the active-service limit is binding and should be reported") { limit shouldNotBe null }
        // One more service buys the other half of the list, and costs money.
        (limit!!.coverageDelta > 0.0) shouldBe true
        (limit.monthlyCentsDelta > 0L) shouldBe true
    }

    @Test
    fun `a limit that changes nothing is not reported as binding`() {
        val request = PlanRequest(
            services = listOf(
                service(netflix, "Netflix", 1_899, currentlySubscribed = false),
                service(crave, "Crave", 1_200, currentlySubscribed = false),
            ),
            // Everything is on Netflix, so a second service slot buys nothing.
            titles = listOf(demand("On Netflix", 5, netflix)),
            constraints = constraints(horizonMonths = 2, maximumActiveServices = 1),
            weights = PlanWeights.DEFAULT,
        )

        val solved = solver.solve(request).shouldBeSolved()

        // Saying "relaxing this buys you nothing" is as useful as naming a price,
        // and reporting a non-binding constraint as binding is how a sensitivity
        // panel turns into noise.
        solved.sensitivity.none { it.constraint == "maximumActiveServices" } shouldBe true
    }

    // --- the independent optimum -------------------------------------------

    /**
     * Every possible plan, filtered and scored by [PlanChecker] alone.
     *
     * Deliberately the dullest thing that could work: iterate the `2^(services ×
     * months)` subscription assignments, derive starts and stops from the state
     * change, ask the checker whether the result is legal, score the survivors.
     * No CP-SAT, no shared helper with the model builder, no cleverness that
     * could import the model's assumptions by accident.
     *
     * Returns null when nothing is feasible.
     */
    private fun bestByExhaustiveSearch(request: PlanRequest): PlanObjective? {
        val serviceCount = request.services.size
        val monthCount = request.constraints.horizonMonths
        val bits = serviceCount * monthCount
        require(bits <= MAXIMUM_ENUMERABLE_BITS) { "Instance too large to enumerate: $bits bits" }

        var best: PlanObjective? = null
        for (assignment in 0 until (1 shl bits)) {
            val plan = planFor(request, assignment, monthCount)
            if (PlanChecker.check(request, plan).isNotEmpty()) continue
            val objective = PlanChecker.objective(request, plan, PlanChecker.coverage(request, plan))
            if (best == null || objective.weighted > best.weighted) best = objective
        }
        return best
    }

    private fun planFor(request: PlanRequest, assignment: Int, monthCount: Int): List<MonthPlan> {
        var previous = request.services.filter { it.currentlySubscribed }.mapTo(mutableSetOf()) { it.providerId }
        return (0 until monthCount).map { month ->
            val held = request.services
                .filterIndexed { index, _ -> (assignment shr (index * monthCount + month)) and 1 == 1 }
            val ids = held.mapTo(mutableSetOf()) { it.providerId }
            val plan = MonthPlan(
                month = month,
                subscribedProviderIds = ids,
                startedProviderIds = ids - previous,
                stoppedProviderIds = previous - ids,
                monthlyCents = held.sumOf { it.monthlyCents },
            )
            previous = ids
            plan
        }
    }

    // --- instances ----------------------------------------------------------

    /**
     * A fixed set of shapes plus a seeded random sweep.
     *
     * Seeded rather than random: a test that fails once a fortnight on a
     * different machine is a test people learn to re-run rather than read.
     */
    private fun instances(): List<Pair<String, PlanRequest>> {
        val fixed = listOf(
            "nothing held, one title" to PlanRequest(
                services = listOf(service(netflix, "Netflix", 1_899), service(crave, "Crave", 1_200)),
                titles = listOf(demand("On Netflix", 5, netflix)),
                constraints = constraints(horizonMonths = 3),
                weights = PlanWeights.DEFAULT,
            ),
            "everything held, nothing wanted anywhere" to PlanRequest(
                services = listOf(
                    service(netflix, "Netflix", 1_899, currentlySubscribed = true),
                    service(crave, "Crave", 1_200, currentlySubscribed = true),
                ),
                titles = listOf(demand("Carried by nobody", 5)),
                constraints = constraints(horizonMonths = 3),
                weights = PlanWeights.DEFAULT,
            ),
            "a tight budget and a commitment" to PlanRequest(
                services = listOf(
                    service(netflix, "Netflix", 1_899, currentlySubscribed = true),
                    service(crave, "Crave", 1_200, committedMonths = 2, currentlySubscribed = true),
                ),
                titles = listOf(demand("On Netflix", 5, netflix), demand("On Crave", 1, crave)),
                constraints = constraints(horizonMonths = 3, maximumMonthlyCents = 3_200),
                weights = PlanWeights.DEFAULT,
            ),
            "one service at a time, three titles spread across three" to PlanRequest(
                services = listOf(
                    service(netflix, "Netflix", 1_899),
                    service(crave, "Crave", 1_200),
                    service(disney, "Disney+", 1_599, currentlySubscribed = true),
                ),
                titles = listOf(
                    demand("On Netflix", 5, netflix),
                    demand("On Crave", 3, crave),
                    demand("On Disney", 1, disney),
                ),
                constraints = constraints(horizonMonths = 3, maximumActiveServices = 1),
                weights = PlanWeights.DEFAULT,
            ),
            "cost weighted to nothing: coverage should win outright" to PlanRequest(
                services = listOf(service(netflix, "Netflix", 9_999), service(crave, "Crave", 1_200)),
                titles = listOf(demand("On Netflix", 5, netflix)),
                constraints = constraints(horizonMonths = 2),
                weights = PlanWeights(coverage = 1.0, cost = 0.0, switching = 0.0),
            ),
        )
        return fixed + (0 until RANDOM_INSTANCES).map { seed -> "random seed $seed" to randomInstance(Random(seed)) }
    }

    private fun randomInstance(random: Random): PlanRequest {
        val monthCount = random.nextInt(1, 4)
        val serviceCount = random.nextInt(1, MAXIMUM_ENUMERABLE_BITS / monthCount + 1).coerceAtMost(3)
        val services = (0 until serviceCount).map {
            ServiceOption(
                providerId = UUID.randomUUID(),
                name = "Service $it",
                monthlyCents = random.nextLong(500, 3_000),
                committedMonths = if (random.nextInt(4) == 0) random.nextInt(1, monthCount + 1) else 0,
                currentlySubscribed = random.nextBoolean(),
            )
        }
        val titles = (0 until random.nextInt(1, 5)).map { index ->
            TitleDemand(
                titleId = UUID.randomUUID(),
                name = "Title $index",
                priorityPoints = random.nextInt(1, 6),
                // Sometimes carried by nobody, which is a real state and the one
                // most likely to divide by zero somewhere.
                availableOn = services.filter { random.nextInt(3) > 0 }.mapTo(mutableSetOf()) { it.providerId },
            )
        }
        val coverage = random.nextInt(0, 11) / 10.0
        val remainder = 1.0 - coverage
        val cost = remainder * 0.5
        return PlanRequest(
            services = services,
            titles = titles,
            constraints = PlanConstraints(
                horizonMonths = monthCount,
                maximumMonthlyCents = if (random.nextBoolean()) random.nextLong(500, 6_000) else null,
                maximumActiveServices = if (random.nextBoolean()) random.nextInt(0, serviceCount + 1) else null,
                maximumMonthlySwitches = if (random.nextBoolean()) random.nextInt(0, 3) else null,
            ),
            weights = PlanWeights(coverage = coverage, cost = cost, switching = remainder - cost),
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun service(id: UUID, name: String, cents: Long, committedMonths: Int = 0, currentlySubscribed: Boolean = false) =
        ServiceOption(id, name, cents, committedMonths, currentlySubscribed)

    private fun demand(name: String, points: Int, vararg on: UUID) = TitleDemand(UUID.randomUUID(), name, points, on.toSet())

    private fun constraints(
        horizonMonths: Int,
        maximumMonthlyCents: Long? = null,
        maximumActiveServices: Int? = null,
        maximumMonthlySwitches: Int? = null,
    ) = PlanConstraints(horizonMonths, maximumMonthlyCents, maximumActiveServices, maximumMonthlySwitches)

    private fun PlanOutcome.shouldBeSolved(): PlanOutcome.Solved {
        (this is PlanOutcome.Solved) shouldBe true
        return this as PlanOutcome.Solved
    }

    /**
     * Kotest's own `withClue` is available, but this one keeps the label in the
     * message for the plain JUnit assertions used above.
     */
    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (failure: AssertionError) {
        throw AssertionError("$clue\n${failure.message}", failure)
    }

    private companion object {
        /**
         * `2^12` plans per instance, enumerated and checked. Large enough for
         * three services over three months, small enough that the exhaustive
         * search stays faster than the solve it is auditing.
         */
        const val MAXIMUM_ENUMERABLE_BITS = 12

        const val RANDOM_INSTANCES = 40

        /**
         * The model's coefficients are integers at `OBJECTIVE_SCALE`, so its
         * optimum can sit a rounding error below the exact one. A few dozen terms
         * at 5e-7 each puts the worst case around 2e-5; this leaves room and is
         * still far tighter than any difference a real plan would turn on.
         */
        const val TOLERANCE = 1e-4
    }
}
