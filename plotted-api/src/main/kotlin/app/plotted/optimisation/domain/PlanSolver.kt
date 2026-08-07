package app.plotted.optimisation.domain

import com.google.ortools.Loader
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverStatus
import com.google.ortools.sat.LinearExpr
import com.google.ortools.sat.Literal
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * The CP-SAT model behind Cancel Culture.
 *
 * ### The variables (spec section 11.2)
 *
 * - `x[s][m]` — service `s` is held in month `m`
 * - `u[s][m]` — it is *started* in month `m`
 * - `d[s][m]` — it is *stopped* in month `m`
 * - `y[t][m]` — title `t` is watched in month `m`
 *
 * Start and stop are separate variables rather than one "changed" indicator
 * because they are not the same event. Starting costs a month of money you were
 * not spending; stopping costs access you had. Collapsing them into `|Δx|` makes
 * the two indistinguishable and quietly prices a cancellation like a signup.
 *
 * The linearisation that ties them together is `x[s][m] − x[s][m−1] = u − d`,
 * with `u + d ≤ 1` so a month cannot both start and stop the same service. This
 * is the part that goes wrong silently: without the second constraint the solver
 * can set both to 1, satisfy the equality, and under-report churn.
 *
 * ### Integer arithmetic
 *
 * CP-SAT is an integer solver, so every coefficient is scaled and rounded once,
 * here, at the edge. Money is in cents throughout. The objective is scaled by
 * [OBJECTIVE_SCALE] — considerably finer than the ×1000 the spec suggests,
 * because at ×1000 the rounding on a single service's cost coefficient can
 * exceed the difference between two genuinely different plans, and the solver
 * would then be indifferent between them for arithmetic reasons rather than real
 * ones. The reported numbers are recomputed exactly by [PlanChecker] regardless,
 * so this scale only affects which plan wins, never what the user is told it costs.
 */
@Component
class PlanSolver(
    /**
     * Optional so tests can build a solver without a registry, and so a missing
     * metrics backend can never be the reason the optimiser fails to start.
     */
    private val meters: MeterRegistry = SimpleMeterRegistry(),
) {
    /**
     * Solves in the request that is running now.
     *
     * The 20-second bound this whole endpoint is reasoned about comes from a
     * count, not a measurement: one solve for the plan plus at most one per
     * binding constraint, of which there are three, each capped at
     * [SOLVE_TIME_LIMIT_SECONDS]. Counting it here makes that claim observable in
     * production rather than only argued for in a comment -- and
     * `PlanSolverBoundTest` asserts the count cannot exceed four.
     */
    private val solveCount = ThreadLocal.withInitial { 0 }

    /** How many CP-SAT solves the last [solve] on this thread required. */
    fun solvesInLastRequest(): Int = solveCount.get()

    fun solve(request: PlanRequest): PlanOutcome {
        Loader.loadNativeLibraries()
        val startedAt = System.nanoTime()
        solveCount.set(0)

        val model = CpModel()
        val months = (0 until request.constraints.horizonMonths).toList()
        val services = request.services

        val x = services.map { s -> months.map { m -> model.newBoolVar("x_${s.providerId}_$m") } }
        val u = services.map { s -> months.map { m -> model.newBoolVar("u_${s.providerId}_$m") } }
        val d = services.map { s -> months.map { m -> model.newBoolVar("d_${s.providerId}_$m") } }
        val y = request.titles.map { t -> months.map { m -> model.newBoolVar("y_${t.titleId}_$m") } }

        addTransitionConstraints(model, request, months, x, u, d)
        addCoverageConstraints(model, request, months, x, y)
        addResourceConstraints(model, request, months, x, u, d)
        addObjective(model, request, months, x, u, d, y)

        val solver = CpSolver()
        // Bounded so a pathological instance degrades into "no answer yet"
        // rather than a hung request. Section 13.1 puts a latency budget on this.
        solver.parameters.setMaxTimeInSeconds(SOLVE_TIME_LIMIT_SECONDS)
        val status = timed("plan") { solver.solve(model) }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return explainInfeasibility(request)
        }

        val plan = readPlan(solver, request, months, x, u, d)
        // Coverage and the objective come from the checker, not the solver. What
        // the model believed and what the plan actually delivers are different
        // claims, and only one of them is worth showing anyone.
        val covered = PlanChecker.coverage(request, plan)
        val objective = PlanChecker.objective(request, plan, covered)
        val coveredIds = covered.mapTo(mutableSetOf()) { it.titleId }

        return PlanOutcome.Solved(
            months = plan,
            objective = objective,
            totalCents = plan.sumOf { it.monthlyCents },
            covered = covered,
            uncovered = request.titles.filterNot { it.titleId in coveredIds },
            sensitivity = sensitivity(request, objective, plan),
            solveMillis = elapsed,
            violations = PlanChecker.check(request, plan),
        )
    }

    private fun addTransitionConstraints(
        model: CpModel,
        request: PlanRequest,
        months: List<Int>,
        x: List<List<Literal>>,
        u: List<List<Literal>>,
        d: List<List<Literal>>,
    ) {
        request.services.forEachIndexed { s, service ->
            months.forEach { m ->
                val previous: LinearExpr = if (m == 0) {
                    // The horizon starts from what the user is paying for today,
                    // not from nothing. Starting from zero would charge them for
                    // "starting" services they already have.
                    LinearExpr.constant(if (service.currentlySubscribed) 1L else 0L)
                } else {
                    LinearExpr.term(x[s][m - 1], 1L)
                }

                // x[m] - x[m-1] = u[m] - d[m]
                model.addEquality(
                    LinearExpr.newBuilder().addTerm(x[s][m], 1L).addTerm(u[s][m], -1L).addTerm(d[s][m], 1L).build(),
                    previous,
                )
                // Without this the solver can set both and under-report churn.
                model.addLessOrEqual(LinearExpr.newBuilder().addTerm(u[s][m], 1L).addTerm(d[s][m], 1L).build(), 1L)

                // A commitment is a hard constraint. Never a penalty: no coverage
                // gain justifies advising a cancellation that is not possible.
                if (m < service.committedMonths) {
                    model.addEquality(x[s][m], 1L)
                }
            }
        }
    }

    private fun addCoverageConstraints(
        model: CpModel,
        request: PlanRequest,
        months: List<Int>,
        x: List<List<Literal>>,
        y: List<List<Literal>>,
    ) {
        val indexOf = request.services.withIndex().associate { (i, s) -> s.providerId to i }

        request.titles.forEachIndexed { t, title ->
            months.forEach { m ->
                val carriers = title.availableOn.mapNotNull { indexOf[it] }
                if (carriers.isEmpty()) {
                    // Nothing carries it, so it cannot be watched in any month.
                    model.addEquality(y[t][m], 0L)
                    return@forEach
                }
                // y ≤ Σ x over the services that carry it: you cannot watch what
                // you are not subscribed to.
                val available = LinearExpr.newBuilder()
                carriers.forEach { s -> available.addTerm(x[s][m], 1L) }
                model.addLessOrEqual(LinearExpr.term(y[t][m], 1L), available.build())
            }
            // Counted once over the whole horizon. Watching something twice does
            // not make the subscription twice as worthwhile, and without this the
            // objective would reward holding a service purely to re-count titles.
            val timesWatched = LinearExpr.newBuilder()
            months.forEach { m -> timesWatched.addTerm(y[t][m], 1L) }
            model.addLessOrEqual(timesWatched.build(), 1L)
        }
    }

    private fun addResourceConstraints(
        model: CpModel,
        request: PlanRequest,
        months: List<Int>,
        x: List<List<Literal>>,
        u: List<List<Literal>>,
        d: List<List<Literal>>,
    ) {
        months.forEach { m ->
            request.constraints.maximumMonthlyCents?.let { budget ->
                val spend = LinearExpr.newBuilder()
                request.services.forEachIndexed { s, service -> spend.addTerm(x[s][m], service.monthlyCents) }
                model.addLessOrEqual(spend.build(), budget)
            }

            request.constraints.maximumActiveServices?.let { limit ->
                val held = LinearExpr.newBuilder()
                request.services.indices.forEach { s -> held.addTerm(x[s][m], 1L) }
                model.addLessOrEqual(held.build(), limit.toLong())
            }

            request.constraints.maximumMonthlySwitches?.let { limit ->
                val switches = LinearExpr.newBuilder()
                request.services.indices.forEach { s ->
                    switches.addTerm(u[s][m], 1L)
                    switches.addTerm(d[s][m], 1L)
                }
                model.addLessOrEqual(switches.build(), limit.toLong())
            }
        }
    }

    /**
     * The normalised objective.
     *
     * Each of the three terms is a fraction of its own maximum before it is
     * weighted, so the weights mean what they say. Mixing raw dollars with a
     * coverage percentage would make a weight of 0.35 on cost mean something
     * entirely different for a $20 budget than for a $200 one — the weights
     * would stop being a preference and become an artefact of the units.
     *
     * Constant offsets are dropped: maximising `−cost` and maximising
     * `1 − cost` pick the same plan, and the reported values are recomputed
     * exactly elsewhere.
     */
    private fun addObjective(
        model: CpModel,
        request: PlanRequest,
        months: List<Int>,
        x: List<List<Literal>>,
        u: List<List<Literal>>,
        d: List<List<Literal>>,
        y: List<List<Literal>>,
    ) {
        val objective = LinearExpr.newBuilder()

        val totalPoints = request.titles.sumOf { it.priorityPoints }.coerceAtLeast(1)
        request.titles.forEachIndexed { t, title ->
            val coefficient = scaled(request.weights.coverage * title.priorityPoints / totalPoints)
            months.forEach { m -> objective.addTerm(y[t][m], coefficient) }
        }

        val maxSpend = (request.services.sumOf { it.monthlyCents } * request.constraints.horizonMonths).coerceAtLeast(1)
        request.services.forEachIndexed { s, service ->
            val coefficient = scaled(request.weights.cost * service.monthlyCents / maxSpend)
            months.forEach { m -> objective.addTerm(x[s][m], -coefficient) }
        }

        val maxSwitches = (request.services.size * request.constraints.horizonMonths).coerceAtLeast(1)
        val switchCoefficient = scaled(request.weights.switching / maxSwitches)
        request.services.indices.forEach { s ->
            months.forEach { m ->
                objective.addTerm(u[s][m], -switchCoefficient)
                objective.addTerm(d[s][m], -switchCoefficient)
            }
        }

        model.maximize(objective.build())
    }

    private fun readPlan(
        solver: CpSolver,
        request: PlanRequest,
        months: List<Int>,
        x: List<List<Literal>>,
        u: List<List<Literal>>,
        d: List<List<Literal>>,
    ): List<MonthPlan> = months.map { m ->
        val held = mutableSetOf<UUID>()
        val started = mutableSetOf<UUID>()
        val stopped = mutableSetOf<UUID>()
        var cents = 0L

        request.services.forEachIndexed { s, service ->
            if (solver.booleanValue(x[s][m])) {
                held += service.providerId
                cents += service.monthlyCents
            }
            if (solver.booleanValue(u[s][m])) started += service.providerId
            if (solver.booleanValue(d[s][m])) stopped += service.providerId
        }
        MonthPlan(m, held, started, stopped, cents)
    }

    /**
     * What one more unit of each constraint would buy, by solving again.
     *
     * Three extra solves of a model this size, and it produces the most
     * actionable sentence the optimiser has: relaxing a limit by one either
     * changes the answer or it does not, and either way that is worth knowing.
     * A constraint that buys nothing is not binding, and saying so is as useful
     * as saying what the binding one costs.
     */
    private fun sensitivity(request: PlanRequest, base: PlanObjective, basePlan: List<MonthPlan>): List<Sensitivity> {
        val baseMonthly = basePlan.maxOfOrNull { it.monthlyCents } ?: 0L
        val results = mutableListOf<Sensitivity>()

        request.constraints.maximumActiveServices?.let { limit ->
            relaxed(request, request.constraints.copy(maximumActiveServices = limit + 1))?.let { (objective, plan) ->
                results += Sensitivity(
                    constraint = "maximumActiveServices",
                    relaxedBy = "one more service",
                    coverageDelta = objective.coverage - base.coverage,
                    monthlyCentsDelta = (plan.maxOfOrNull { it.monthlyCents } ?: 0L) - baseMonthly,
                )
            }
        }

        request.constraints.maximumMonthlyCents?.let { budget ->
            val step = BUDGET_PROBE_CENTS
            relaxed(request, request.constraints.copy(maximumMonthlyCents = budget + step))?.let { (objective, plan) ->
                results += Sensitivity(
                    constraint = "maximumMonthlyBudget",
                    relaxedBy = "$${"%.2f".format(step / 100.0)} more per month",
                    coverageDelta = objective.coverage - base.coverage,
                    monthlyCentsDelta = (plan.maxOfOrNull { it.monthlyCents } ?: 0L) - baseMonthly,
                )
            }
        }

        request.constraints.maximumMonthlySwitches?.let { limit ->
            relaxed(request, request.constraints.copy(maximumMonthlySwitches = limit + 1))?.let { (objective, plan) ->
                results += Sensitivity(
                    constraint = "maximumMonthlySwitches",
                    relaxedBy = "one more change per month",
                    coverageDelta = objective.coverage - base.coverage,
                    monthlyCentsDelta = (plan.maxOfOrNull { it.monthlyCents } ?: 0L) - baseMonthly,
                )
            }
        }

        // Only the constraints that actually changed something are binding.
        return results.filter { it.coverageDelta > COVERAGE_EPSILON || it.monthlyCentsDelta != 0L }
    }

    private fun relaxed(request: PlanRequest, constraints: PlanConstraints): Pair<PlanObjective, List<MonthPlan>>? {
        // Recursion is bounded: the relaxed request carries no sensitivity pass
        // of its own because `solve` is not re-entered — this builds the model
        // directly instead.
        val relaxedRequest = request.copy(constraints = constraints)
        val outcome = solveOnce(relaxedRequest) ?: return null
        val covered = PlanChecker.coverage(relaxedRequest, outcome)
        return PlanChecker.objective(relaxedRequest, outcome, covered) to outcome
    }

    /** The model and one solve, with no sensitivity or auditing. */
    private fun solveOnce(request: PlanRequest): List<MonthPlan>? {
        val model = CpModel()
        val months = (0 until request.constraints.horizonMonths).toList()
        val x = request.services.map { s -> months.map { m -> model.newBoolVar("x_${s.providerId}_$m") } }
        val u = request.services.map { s -> months.map { m -> model.newBoolVar("u_${s.providerId}_$m") } }
        val d = request.services.map { s -> months.map { m -> model.newBoolVar("d_${s.providerId}_$m") } }
        val y = request.titles.map { t -> months.map { m -> model.newBoolVar("y_${t.titleId}_$m") } }

        addTransitionConstraints(model, request, months, x, u, d)
        addCoverageConstraints(model, request, months, x, y)
        addResourceConstraints(model, request, months, x, u, d)
        addObjective(model, request, months, x, u, d, y)

        val solver = CpSolver()
        solver.parameters.setMaxTimeInSeconds(SOLVE_TIME_LIMIT_SECONDS)
        val status = timed("sensitivity") { solver.solve(model) }
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) return null
        return readPlan(solver, request, months, x, u, d)
    }

    /**
     * Times one solve, counts it, and returns what it returned.
     *
     * Every call to CP-SAT goes through here, which is what makes the count
     * trustworthy: a fourth solve added somewhere would show up in the metric and
     * fail `PlanSolverBoundTest` rather than quietly moving the latency bound
     * from twenty seconds to twenty-five.
     */
    private fun <T> timed(kind: String, solve: () -> T): T {
        solveCount.set(solveCount.get() + 1)
        val startedAt = System.nanoTime()
        try {
            return solve()
        } finally {
            meters.timer("plotted.optimiser.solve", "kind", kind)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * Why no plan exists.
     *
     * Diagnosed by checking the constraints that can be impossible on their own,
     * in the order a person would. Returning "infeasible" alone would be
     * technically accurate and useless — the user set these limits and the
     * actionable answer is which one cannot be met.
     */
    private fun explainInfeasibility(request: PlanRequest): PlanOutcome.Infeasible {
        val committed = request.services.filter { it.committedMonths > 0 }
        val committedCost = committed.sumOf { it.monthlyCents }

        request.constraints.maximumMonthlyCents?.let { budget ->
            if (committed.isNotEmpty() && committedCost > budget) {
                return PlanOutcome.Infeasible(
                    explanation = "Your commitments alone cost $${money(committedCost)} a month, " +
                        "which is more than the $${money(budget)} budget. " +
                        "Until ${committed.maxOf { it.committedMonths }} months from now there is no plan that fits.",
                    bindingConstraint = "maximumMonthlyBudget",
                )
            }
            val cheapest = request.services.minOfOrNull { it.monthlyCents }
            if (cheapest != null && budget < cheapest && request.services.isNotEmpty()) {
                return PlanOutcome.Infeasible(
                    explanation = "A $${money(budget)} budget is below the cheapest service available " +
                        "($${money(cheapest)}), so no combination can be afforded.",
                    bindingConstraint = "maximumMonthlyBudget",
                )
            }
        }

        request.constraints.maximumActiveServices?.let { limit ->
            if (committed.size > limit) {
                return PlanOutcome.Infeasible(
                    explanation = "You are committed to ${committed.size} services but asked for at most $limit at a time. " +
                        "A commitment cannot be cancelled, so this cannot be satisfied yet.",
                    bindingConstraint = "maximumActiveServices",
                )
            }
        }

        return PlanOutcome.Infeasible(
            explanation = "No combination of services satisfies all of the limits you set. " +
                "Relaxing the budget or the number of services is the usual way through.",
            bindingConstraint = null,
        )
    }

    private fun scaled(value: Double): Long = (value * OBJECTIVE_SCALE).roundToLong()

    private fun money(cents: Long): String = "%.2f".format(cents / 100.0)

    private companion object {
        /**
         * Finer than the ×1000 in the spec, deliberately. At ×1000 the rounding
         * on one service's cost coefficient can exceed the real difference
         * between two plans, and the solver becomes indifferent for arithmetic
         * reasons. Nothing reported to the user comes from this scale.
         */
        const val OBJECTIVE_SCALE = 1_000_000.0

        /** Section 13.1 puts a latency budget on this; a hung solve is a failed request. */
        const val SOLVE_TIME_LIMIT_SECONDS = 5.0

        /** Ten dollars: roughly one service, and a step a person would recognise. */
        const val BUDGET_PROBE_CENTS = 1_000L

        const val COVERAGE_EPSILON = 1e-9
    }
}
