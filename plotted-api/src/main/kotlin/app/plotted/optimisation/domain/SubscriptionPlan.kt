package app.plotted.optimisation.domain

import app.plotted.solver.PlanOutcome
import app.plotted.solver.PlanRequest
import java.util.UUID

/**
 * What the optimiser was told, beside what it answered.
 *
 * The solved plan and its model live in `plotted-solver`; these are the product
 * judgements that decided what the model was shown, and they stay on this side
 * because they are about the user's watchlist rather than about optimisation.
 */

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
    /**
     * Only on services whose price Plotted researched but nobody confirmed.
     *
     * Kept apart from [unpricedService] because the two ask different things of
     * the user. A missing price is Plotted's problem. A researched one is a
     * figure that exists, is probably close, and is still not what *this* person
     * is billed -- legacy rates, bundles, student and promotional pricing all
     * move it, and all of them move it down, so optimising against list prices
     * overstates what cancelling would save. One field on the subscriptions
     * screen closes it, and saying which service is what makes that possible.
     */
    val unconfirmedPrice: List<ExcludedTitle>,
) {
    val total: Int get() = freeToWatch.size + neverChecked.size + unpricedService.size + unconfirmedPrice.size

    companion object {
        val NONE = ExcludedDemand(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

data class ExcludedTitle(
    val titleId: UUID,
    val name: String,
    /** Named so the interface can say which service, rather than "a service". */
    val providerNames: List<String>,
)
