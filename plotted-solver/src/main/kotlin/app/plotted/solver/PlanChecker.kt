package app.plotted.solver

import java.util.UUID

/**
 * An independent reimplementation of the rules, used to audit the solver.
 *
 * **This must never share code with the model builder.** A solver will happily
 * and optimally solve a model you specified wrong, and the result looks exactly
 * like a correct answer — same shape, same confidence, plausible numbers. The
 * only thing that catches it is a second implementation written from the rules
 * rather than from the model, disagreeing.
 *
 * So this file deliberately does the dull thing: plain loops over the plan,
 * arithmetic in Kotlin, no CP-SAT types anywhere. If it ever starts importing
 * from the builder to avoid duplication, it has stopped being a check.
 */
object PlanChecker {
    /**
     * Verifies a plan against the request that produced it.
     *
     * Returns the problems found, empty when the plan is sound. Every message
     * names the month and the numbers, because a violation nobody can locate is
     * only marginally better than one nobody noticed.
     */
    fun check(request: PlanRequest, months: List<MonthPlan>): List<String> {
        val problems = mutableListOf<String>()
        val byId = request.services.associateBy { it.providerId }

        if (months.size != request.constraints.horizonMonths) {
            problems += "Plan covers ${months.size} months, horizon is ${request.constraints.horizonMonths}"
        }

        months.forEach { month ->
            val unknown = month.subscribedProviderIds - byId.keys
            if (unknown.isNotEmpty()) {
                problems += "Month ${month.month} subscribes to ${unknown.size} service(s) that were never offered"
            }

            // Cost, recomputed rather than trusted.
            val cost = month.subscribedProviderIds.sumOf { byId[it]?.monthlyCents ?: 0L }
            if (cost != month.monthlyCents) {
                problems += "Month ${month.month} reports $${cents(month.monthlyCents)} but its services cost $${cents(cost)}"
            }
            request.constraints.maximumMonthlyCents?.let { budget ->
                if (cost > budget) {
                    problems += "Month ${month.month} costs $${cents(cost)}, over the $${cents(budget)} budget"
                }
            }

            request.constraints.maximumActiveServices?.let { limit ->
                if (month.subscribedProviderIds.size > limit) {
                    problems += "Month ${month.month} holds ${month.subscribedProviderIds.size} services, limit is $limit"
                }
            }

            request.constraints.maximumMonthlySwitches?.let { limit ->
                val switches = month.startedProviderIds.size + month.stoppedProviderIds.size
                if (switches > limit) {
                    problems += "Month ${month.month} makes $switches switches, limit is $limit"
                }
            }

            // A commitment is a constraint, not a preference. Cancelling inside
            // one is the single most damaging thing this optimiser could advise.
            request.services.filter { month.month < it.committedMonths }.forEach { committed ->
                if (committed.providerId !in month.subscribedProviderIds) {
                    problems += "Month ${month.month} drops ${committed.name}, which cannot be " +
                        "cancelled until month ${committed.committedMonths}"
                }
            }
        }

        problems += checkTransitions(request, months, byId)
        return problems
    }

    /**
     * Starts and stops must agree with the subscription state either side of them.
     *
     * Checked separately because this is where a linearisation goes wrong
     * quietly: a model that lets `u` and `d` float free still produces a plan,
     * it just under-counts churn and so under-charges for it.
     */
    private fun checkTransitions(request: PlanRequest, months: List<MonthPlan>, byId: Map<UUID, ServiceOption>): List<String> {
        val problems = mutableListOf<String>()
        var previous = request.services.filter { it.currentlySubscribed }.map { it.providerId }.toSet()

        months.sortedBy { it.month }.forEach { month ->
            val expectedStarts = month.subscribedProviderIds - previous
            val expectedStops = previous - month.subscribedProviderIds

            if (month.startedProviderIds != expectedStarts) {
                problems += "Month ${month.month} reports starting ${names(month.startedProviderIds, byId)} " +
                    "but the state change is ${names(expectedStarts, byId)}"
            }
            if (month.stoppedProviderIds != expectedStops) {
                problems += "Month ${month.month} reports stopping ${names(month.stoppedProviderIds, byId)} " +
                    "but the state change is ${names(expectedStops, byId)}"
            }
            previous = month.subscribedProviderIds
        }
        return problems
    }

    /**
     * Which titles the plan actually delivers, and when.
     *
     * Recomputed from the plan rather than read from the solver, so the coverage
     * figure shown to the user is arithmetic anyone can repeat. A title counts
     * once, in the first month it is reachable — watching it twice does not make
     * a subscription twice as worthwhile.
     */
    fun coverage(request: PlanRequest, months: List<MonthPlan>): List<CoveredTitle> {
        val covered = mutableListOf<CoveredTitle>()
        val claimed = mutableSetOf<UUID>()

        months.sortedBy { it.month }.forEach { month ->
            request.titles.forEach { title ->
                if (title.titleId in claimed) return@forEach
                val provider = title.availableOn.firstOrNull { it in month.subscribedProviderIds } ?: return@forEach
                claimed += title.titleId
                covered += CoveredTitle(title.titleId, title.name, month.month, provider)
            }
        }
        return covered
    }

    /**
     * The objective components, exactly, from the plan.
     *
     * The denominators are what make these comparable: total priority points for
     * coverage, the cost of subscribing to everything for the whole horizon for
     * cost, and the maximum number of switches for churn. Without normalising,
     * a weight of 0.35 on cost would mean something different for a user with a
     * $20 budget than for one with $200.
     */
    fun objective(request: PlanRequest, months: List<MonthPlan>, covered: List<CoveredTitle>): PlanObjective {
        val totalPoints = request.titles.sumOf { it.priorityPoints }
        val coveredPoints = covered.sumOf { hit -> request.titles.first { it.titleId == hit.titleId }.priorityPoints }
        val coverage = if (totalPoints == 0) 0.0 else coveredPoints.toDouble() / totalPoints

        val spend = months.sumOf { it.monthlyCents }
        val maxSpend = request.services.sumOf { it.monthlyCents } * request.constraints.horizonMonths
        val costFraction = if (maxSpend == 0L) 0.0 else spend.toDouble() / maxSpend

        val switches = months.sumOf { it.startedProviderIds.size + it.stoppedProviderIds.size }
        val maxSwitches = request.services.size * request.constraints.horizonMonths
        val switchFraction = if (maxSwitches == 0) 0.0 else switches.toDouble() / maxSwitches

        return PlanObjective(
            coverage = coverage,
            costFraction = costFraction,
            switchFraction = switchFraction,
            // Cost and churn enter as their complements so that every term
            // points the same way: higher is better, for all three.
            weighted = request.weights.coverage * coverage +
                request.weights.cost * (1 - costFraction) +
                request.weights.switching * (1 - switchFraction),
        )
    }

    private fun names(ids: Set<UUID>, byId: Map<UUID, ServiceOption>): String =
        if (ids.isEmpty()) "nothing" else ids.mapNotNull { byId[it]?.name }.sorted().joinToString(", ")

    private fun cents(value: Long): String = "%.2f".format(value / 100.0)
}
