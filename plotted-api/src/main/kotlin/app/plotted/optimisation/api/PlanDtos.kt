package app.plotted.optimisation.api

import app.plotted.optimisation.domain.ExcludedDemand
import app.plotted.optimisation.domain.ExcludedTitle
import app.plotted.optimisation.domain.PlanReport
import app.plotted.solver.MonthPlan
import app.plotted.solver.PlanOutcome
import app.plotted.solver.Sensitivity
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(
    description =
    "A subscription plan month by month. `months` is empty with a populated `diagnosis` when " +
        "no plan satisfies the limits that were set, or when there is nothing on the watchlist " +
        "to plan against — both are successful responses carrying information, not failures.",
)
data class PlanResponse(
    val status: String,
    val horizonMonths: Int,
    val months: List<MonthResponse>,
    @Schema(description = "Present only when no plan was produced.")
    val diagnosis: PlanDiagnosisResponse?,
    val objective: ObjectiveResponse?,
    @Schema(description = "Total spend across the whole horizon, in cents.")
    val totalCents: Long?,
    val covered: List<PlanCoveredTitleResponse>,
    val uncovered: List<UncoveredTitleResponse>,
    @Schema(description = "What relaxing each binding limit by one unit would buy. Empty when nothing is binding.")
    val sensitivity: List<SensitivityResponse>,
    @Schema(description = "Watchlist items deliberately kept out of the model, with the reason for each.")
    val excluded: ExcludedResponse,
    @Schema(
        description =
        "Problems an independent reimplementation of the rules found in the solver's own answer. " +
            "Always empty in a healthy system: non-empty means the model and the rules disagree, " +
            "which is a defect in the model. Surfaced rather than hidden, because a wrong plan you " +
            "can see beats one you cannot.",
    )
    val violations: List<String>,
    val solveMillis: Long?,
) {
    companion object {
        const val STATUS_SOLVED = "solved"
        const val STATUS_INFEASIBLE = "infeasible"
        const val STATUS_NOTHING_TO_PLAN = "nothing_to_plan"

        fun from(report: PlanReport): PlanResponse = when (val outcome = report.outcome) {
            is PlanOutcome.Solved -> PlanResponse(
                status = STATUS_SOLVED,
                horizonMonths = report.horizonMonths,
                months = outcome.months.sortedBy { it.month }.map { MonthResponse.from(it, report.providerNames) },
                diagnosis = null,
                objective = ObjectiveResponse(
                    coverage = outcome.objective.coverage,
                    costFraction = outcome.objective.costFraction,
                    switchFraction = outcome.objective.switchFraction,
                    weighted = outcome.objective.weighted,
                ),
                totalCents = outcome.totalCents,
                covered = outcome.covered.map {
                    PlanCoveredTitleResponse(
                        titleId = it.titleId,
                        name = it.name,
                        month = it.month,
                        providerId = it.providerId,
                        providerName = report.providerNames[it.providerId],
                    )
                },
                uncovered = outcome.uncovered.map {
                    UncoveredTitleResponse(
                        titleId = it.titleId,
                        name = it.name,
                        priorityPoints = it.priorityPoints,
                        // The difference between "no plan could afford it" and
                        // "nothing carries it at any price" is the difference
                        // between a limit worth relaxing and one that would not
                        // help. Collapsing them would make the answer useless.
                        reason = if (it.availableOn.isEmpty()) REASON_NOT_CARRIED else REASON_NOT_CHOSEN,
                        availableOn = it.availableOn.mapNotNull { id -> report.providerNames[id] }.sorted(),
                    )
                },
                sensitivity = outcome.sensitivity.map(SensitivityResponse::from),
                excluded = ExcludedResponse.from(report.excluded),
                violations = outcome.violations,
                solveMillis = outcome.solveMillis,
            )

            is PlanOutcome.Infeasible -> empty(report, STATUS_INFEASIBLE, outcome.explanation, outcome.bindingConstraint)

            is PlanOutcome.NothingToPlan -> empty(report, STATUS_NOTHING_TO_PLAN, outcome.explanation, null)
        }

        private fun empty(report: PlanReport, status: String, explanation: String, binding: String?) = PlanResponse(
            status = status,
            horizonMonths = report.horizonMonths,
            months = emptyList(),
            diagnosis = PlanDiagnosisResponse(explanation = explanation, bindingConstraint = binding),
            objective = null,
            totalCents = null,
            covered = emptyList(),
            uncovered = emptyList(),
            sensitivity = emptyList(),
            excluded = ExcludedResponse.from(report.excluded),
            violations = emptyList(),
            solveMillis = null,
        )

        const val REASON_NOT_CARRIED = "not_carried"
        const val REASON_NOT_CHOSEN = "not_chosen"
    }
}

data class MonthResponse(
    @Schema(description = "0 is this month.")
    val month: Int,
    val monthlyCents: Long,
    val subscribed: List<ServiceRefResponse>,
    val started: List<ServiceRefResponse>,
    val stopped: List<ServiceRefResponse>,
) {
    companion object {
        fun from(month: MonthPlan, names: Map<UUID, String>) = MonthResponse(
            month = month.month,
            monthlyCents = month.monthlyCents,
            subscribed = refs(month.subscribedProviderIds, names),
            started = refs(month.startedProviderIds, names),
            stopped = refs(month.stoppedProviderIds, names),
        )

        private fun refs(ids: Set<UUID>, names: Map<UUID, String>) = ids
            .map { ServiceRefResponse(it, names[it] ?: "Unnamed service") }
            .sortedBy { it.name }
    }
}

data class ServiceRefResponse(val providerId: UUID, val name: String)

@Schema(
    description =
    "Each component on 0..1, recomputed exactly from the chosen plan rather than read back " +
        "out of the solver — so these are the numbers, not what the model believed after its " +
        "coefficients were made integral.",
)
data class ObjectiveResponse(
    @Schema(description = "Share of watchlist priority points the plan delivers.")
    val coverage: Double,
    @Schema(description = "Spend as a fraction of subscribing to everything for the whole horizon.")
    val costFraction: Double,
    @Schema(description = "Switches as a fraction of the maximum possible.")
    val switchFraction: Double,
    val weighted: Double,
)

/**
 * Prefixed rather than called `CoveredTitleResponse`, which the coverage
 * dashboard already uses for a different shape. springdoc keys `components
 * .schemas` by simple class name, so two classes sharing one silently overwrite
 * each other in the document — no error, no warning, and the generated client is
 * wrong for whichever endpoint lost. [app.plotted.architecture.ModuleBoundaryTest]
 * now fails the build on the collision rather than leaving it to be noticed.
 */
data class PlanCoveredTitleResponse(
    val titleId: UUID,
    val name: String,
    @Schema(description = "The first month the plan makes it reachable.")
    val month: Int,
    val providerId: UUID,
    val providerName: String?,
)

data class UncoveredTitleResponse(
    val titleId: UUID,
    val name: String,
    val priorityPoints: Int,
    @Schema(description = "not_carried — no priced service has it. not_chosen — the plan could not fit it.")
    val reason: String,
    val availableOn: List<String>,
)

data class SensitivityResponse(
    val constraint: String,
    val relaxedBy: String,
    @Schema(description = "Extra share of the watchlist this would buy, 0..1.")
    val coverageDelta: Double,
    @Schema(description = "Change in peak monthly spend, in cents. Positive means it costs more.")
    val monthlyCentsDelta: Long,
) {
    companion object {
        fun from(sensitivity: Sensitivity) = SensitivityResponse(
            constraint = sensitivity.constraint,
            relaxedBy = sensitivity.relaxedBy,
            coverageDelta = sensitivity.coverageDelta,
            monthlyCentsDelta = sensitivity.monthlyCentsDelta,
        )
    }
}

@Schema(
    description =
    "Watchlist items the optimiser was never shown. Reported rather than dropped: a title " +
        "missing because Plotted has never checked it is a completely different fact from one " +
        "no plan could afford.",
)
data class ExcludedResponse(
    @Schema(description = "Watchable free or ad-supported, so no subscription decision turns on them.")
    val freeToWatch: List<ExcludedTitleResponse>,
    @Schema(description = "Availability has never been checked. Excluded from the denominator, never scored as uncovered.")
    val neverChecked: List<ExcludedTitleResponse>,
    @Schema(description = "Only on services with no established price. Guessing one would put invented money in the objective.")
    val unpricedService: List<ExcludedTitleResponse>,
    @Schema(
        description =
        "Only on services whose price Plotted researched but the user never confirmed. Kept apart from " +
            "unpricedService because this one closes with a single field: a published list price is not a " +
            "bill, and optimising against it overstates what cancelling would save.",
    )
    val unconfirmedPrice: List<ExcludedTitleResponse>,
) {
    companion object {
        fun from(excluded: ExcludedDemand) = ExcludedResponse(
            freeToWatch = excluded.freeToWatch.map(ExcludedTitleResponse::from),
            neverChecked = excluded.neverChecked.map(ExcludedTitleResponse::from),
            unpricedService = excluded.unpricedService.map(ExcludedTitleResponse::from),
            unconfirmedPrice = excluded.unconfirmedPrice.map(ExcludedTitleResponse::from),
        )
    }
}

/**
 * Carries a class-level description, because without one springdoc writes
 * whichever property description it happened to process last onto this shared
 * schema.
 *
 * Every field of [ExcludedResponse] is a `List<ExcludedTitleResponse>`, and
 * springdoc applies a collection property's `@Schema(description = …)` to the
 * *item* type rather than to the array. Before this, the published document
 * described `ExcludedTitleResponse` as "Only on services with no established
 * price" — one bucket's reason standing in for the shape all four of them share,
 * and the generated client would have carried it as documentation.
 *
 * A milder relative of the `CoveredTitleResponse` collision recorded in
 * `PROGRESS.md`: the document stayed internally consistent and matched itself,
 * so the drift check had nothing to object to.
 */
@Schema(description = "A watchlist title the optimiser was not shown, and the services carrying it.")
data class ExcludedTitleResponse(val titleId: UUID, val name: String, val providerNames: List<String>) {
    companion object {
        fun from(title: ExcludedTitle) = ExcludedTitleResponse(title.titleId, title.name, title.providerNames)
    }
}

data class PlanDiagnosisResponse(
    val explanation: String,
    @Schema(description = "Which limit made it impossible, when one can be identified.")
    val bindingConstraint: String?,
)
