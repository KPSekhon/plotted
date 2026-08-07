package app.plotted.optimisation.domain

import java.util.UUID

/**
 * A service the optimiser may switch on or off, priced in whole cents.
 *
 * Cents rather than `BigDecimal` because CP-SAT is an integer solver and the
 * conversion has to happen exactly once, at the edge. Doing it inside the model
 * would mean rounding money in the middle of an objective function.
 */
data class ServiceOption(
    val providerId: UUID,
    val name: String,
    val monthlyCents: Long,
    /**
     * Months from now during which this cannot be cancelled, because the user is
     * inside a commitment. The optimiser must treat it as a *constraint*, not a
     * cost: advising someone to cancel something they cannot cancel discredits
     * every other thing the plan says.
     */
    val committedMonths: Int,
    /** Whether the user is paying for it today. Switching costs are measured from here. */
    val currentlySubscribed: Boolean,
)

/**
 * One thing the user wants to watch, and where it can be watched.
 *
 * `priorityPoints` is the watchlist priority inverted to a positive weight —
 * 1 (highest) becomes 5. Integer because it is an objective coefficient.
 */
data class TitleDemand(
    val titleId: UUID,
    val name: String,
    val priorityPoints: Int,
    val availableOn: Set<UUID>,
)

/**
 * The constraints the plan must satisfy. Every one of them is a hard limit the
 * user set, which is why the answer to "no plan satisfies these" is an
 * explanation rather than a quietly relaxed plan.
 */
data class PlanConstraints(
    val horizonMonths: Int,
    val maximumMonthlyCents: Long?,
    val maximumActiveServices: Int?,
    val maximumMonthlySwitches: Int?,
)

/**
 * How the three objectives trade off. Must sum to 1.
 *
 * Stated as fractions of a whole rather than as arbitrary magnitudes because the
 * three terms are normalised to [0,1] before weighting — see [PlanObjective].
 * Weights over un-normalised terms would silently mean "dollars matter about
 * forty times as much as coverage", which is not a thing anyone chose.
 */
data class PlanWeights(
    val coverage: Double,
    val cost: Double,
    val switching: Double,
) {
    init {
        require(coverage >= 0 && cost >= 0 && switching >= 0) { "Weights cannot be negative" }
        require(kotlin.math.abs(coverage + cost + switching - 1.0) < 1e-9) {
            "Weights must sum to 1, were ${coverage + cost + switching}"
        }
    }

    companion object {
        /** Coverage matters most, cost second, churn least. */
        val DEFAULT = PlanWeights(coverage = 0.55, cost = 0.35, switching = 0.10)
    }
}

/** Everything the optimiser needs, gathered once. */
data class PlanRequest(
    val services: List<ServiceOption>,
    val titles: List<TitleDemand>,
    val constraints: PlanConstraints,
    val weights: PlanWeights,
)

/** Which services to hold in a given month. */
data class MonthPlan(
    val month: Int,
    val subscribedProviderIds: Set<UUID>,
    val startedProviderIds: Set<UUID>,
    val stoppedProviderIds: Set<UUID>,
    val monthlyCents: Long,
)

/**
 * The three objective components, each on [0,1] and each recomputed exactly in
 * Kotlin from the chosen plan rather than read back out of the solver.
 *
 * Reading them from the solver would mean reporting whatever the model believed,
 * including any rounding introduced when the coefficients were made integral.
 * Recomputing them means the numbers shown to the user are the numbers, and the
 * independent checker has something to disagree with.
 */
data class PlanObjective(
    val coverage: Double,
    val costFraction: Double,
    val switchFraction: Double,
    val weighted: Double,
)

data class CoveredTitle(
    val titleId: UUID,
    val name: String,
    val month: Int,
    val providerId: UUID,
)

sealed interface PlanOutcome {
    data class Solved(
        val months: List<MonthPlan>,
        val objective: PlanObjective,
        val totalCents: Long,
        val covered: List<CoveredTitle>,
        val uncovered: List<TitleDemand>,
        /** What one more unit of each binding constraint would buy. */
        val sensitivity: List<Sensitivity>,
        val solveMillis: Long,
        /**
         * Problems the independent checker found in the solver's own answer.
         *
         * Empty in every case anyone wants to see. Non-empty means the model and
         * the rules disagree, which is a defect in the model rather than in the
         * plan — and it is reported rather than thrown, because a wrong plan the
         * user can see beats an exception that hides it.
         */
        val violations: List<String>,
    ) : PlanOutcome

    /**
     * No plan satisfies the constraints.
     *
     * Explained, never returned as an error: the constraints were the request,
     * and the useful answer is which one is impossible and by how much.
     */
    data class Infeasible(
        val explanation: String,
        val bindingConstraint: String?,
    ) : PlanOutcome

    /**
     * There is nothing to optimise against, so no advice is given.
     *
     * Distinct from [Infeasible], and the distinction is the whole point. With an
     * empty demand set the mathematically optimal plan is "cancel everything you
     * are not locked into" — which the solver would return, confidently, with a
     * cost saving attached. That is not a recommendation, it is an artefact of
     * having asked a question with no data behind it, and it is exactly the shape
     * of confident wrong financial advice this project keeps refusing to ship.
     */
    data class NothingToPlan(
        val explanation: String,
    ) : PlanOutcome
}

/**
 * An outcome plus what the model was never shown.
 *
 * The two are separate because [PlanSolver] only ever sees a [PlanRequest] and
 * should stay that way — deciding which titles are worth modelling is a product
 * judgement, and a solver that knew about it would be a solver you could no
 * longer test by handing it a request.
 */
data class PlanReport(
    val outcome: PlanOutcome,
    val excluded: ExcludedDemand,
    val horizonMonths: Int,
    /** Provider names for everything in the model, so the API never has to look them up again. */
    val providerNames: Map<UUID, String>,
)

/**
 * Watchlist items deliberately kept out of the model, grouped by reason.
 *
 * Reported rather than dropped. Each of these is a title the user put on a list
 * and then did not see in the answer, and "it is not there because Plotted has
 * never checked it" is a completely different fact from "no plan could afford
 * it". Silently narrowing the input is how a coverage percentage starts
 * describing Plotted's data quality instead of the user's options.
 */
data class ExcludedDemand(
    /** Watchable free or ad-supported, so no subscription decision turns on them. */
    val freeToWatch: List<ExcludedTitle>,
    /** Availability has never been checked, so scoring them would penalise every plan equally. */
    val neverChecked: List<ExcludedTitle>,
    /** Only on services with no known price. Guessing the price would put invented money in the objective. */
    val unpricedService: List<ExcludedTitle>,
) {
    val total: Int get() = freeToWatch.size + neverChecked.size + unpricedService.size

    companion object {
        val NONE = ExcludedDemand(emptyList(), emptyList(), emptyList())
    }
}

data class ExcludedTitle(
    val titleId: UUID,
    val name: String,
    /** Named so the interface can say which service, rather than "a service". */
    val providerNames: List<String>,
)

/**
 * What relaxing one constraint by one unit would buy.
 *
 * Nearly free to compute — it is the same model solved again — and it is the
 * single most useful sentence the optimiser can produce: "one more service buys
 * 14% more of your list for $20.99 a month."
 */
data class Sensitivity(
    val constraint: String,
    val relaxedBy: String,
    val coverageDelta: Double,
    val monthlyCentsDelta: Long,
)
