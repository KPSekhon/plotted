package app.plotted.optimisation.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.platform.spi.WatchlistDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Cancel Culture: which services to hold, month by month, and which to let go.
 *
 * The optimiser itself is [PlanSolver]. This is the part that decides what to
 * *give* it, which is where the answer is actually determined — a solver returns
 * the optimum of the model it was handed, so every judgement about what belongs
 * in the model is a judgement about the advice.
 *
 * ### What is deliberately left out of the model
 *
 * Three classes of watchlist item never reach the solver, and each is reported
 * back rather than dropped (see [ExcludedDemand]):
 *
 * - **Free to watch.** A title on CBC Gem or Tubi needs no subscription, so it
 *   cannot argue for one. Leaving it in would let a title nobody has to pay for
 *   justify a service somebody does.
 * - **Never checked.** The same rule the coverage dashboard already follows:
 *   titles with no availability record are excluded from the denominator, not
 *   scored as uncovered. Scoring them would depress every plan's coverage in
 *   proportion to how stale Plotted's data is, invisibly, because a low
 *   percentage looks the same either way.
 * - **Only on a service with no known price.** `provider_plans` is researched
 *   per `docs/seed/provider-plans.md` and is deliberately incomplete. A service
 *   whose price nobody has established cannot be costed, and inventing one puts
 *   fabricated money into the objective function — which does not produce a
 *   visibly broken feature, it produces confident wrong financial advice.
 *
 * ### Why there is no transaction around the solve
 *
 * Gathering is four independent reads and the solve is several seconds of CPU
 * holding no database resources. Wrapping the two together would pin a
 * connection for the whole solve to buy a consistency guarantee against a user
 * editing their own watchlist mid-request.
 */
@Service
class CancelCultureService(
    private val watchlists: WatchlistDirectory,
    private val titles: TitleDirectory,
    private val availability: AvailabilityDirectory,
    private val subscriptions: SubscriptionDirectory,
    private val solver: PlanSolver,
    private val properties: TmdbProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun plan(userId: UUID, options: PlanOptions): PlanReport {
        val region = properties.region
        val today = LocalDate.now(clock)

        val services = gatherServices(userId, region, today)
        val demand = gatherDemand(userId, region, services)

        val providerNames = services.associate { it.providerId to it.name }

        if (demand.titles.isEmpty()) {
            // The optimum over an empty demand set is "cancel everything you are
            // not locked into", and it would come back with a dollar figure
            // attached. That is arithmetic, not advice.
            return PlanReport(
                outcome = PlanOutcome.NothingToPlan(explanationForEmptyDemand(demand.excluded)),
                excluded = demand.excluded,
                horizonMonths = options.horizonMonths,
                providerNames = providerNames,
            )
        }

        // Services that carry nothing on the list and that the user is not
        // already paying for are pure cost with no possible benefit: the solver
        // would never choose one, and every one of them adds 3 booleans per
        // month to the model. Held services stay in regardless — being able to
        // recommend cancelling one is the entire feature.
        val demanded = demand.titles.flatMapTo(mutableSetOf()) { it.availableOn }
        val modelled = services.filter { it.currentlySubscribed || it.providerId in demanded }

        val request = PlanRequest(
            services = modelled,
            titles = demand.titles,
            constraints = PlanConstraints(
                horizonMonths = options.horizonMonths,
                maximumMonthlyCents = options.maximumMonthlyCents,
                maximumActiveServices = options.maximumActiveServices,
                maximumMonthlySwitches = options.maximumMonthlySwitches,
            ),
            weights = options.weights,
        )

        val outcome = solver.solve(request)

        if (outcome is PlanOutcome.Solved && outcome.violations.isNotEmpty()) {
            // The independent checker disagreeing with the model is a defect in
            // the model, and it is logged loudly and still returned. A plan the
            // user can see and argue with beats an exception that hides it, and
            // the violations travel with the response so nobody has to take this
            // log line's word for it.
            logger.error(
                "PlanChecker rejected the solver's own plan for user {}: {}",
                userId,
                outcome.violations,
            )
        }

        return PlanReport(
            outcome = outcome,
            excluded = demand.excluded,
            horizonMonths = options.horizonMonths,
            providerNames = providerNames,
        )
    }

    /**
     * Every service the optimiser may switch on or off, priced.
     *
     * The union of what the user holds and what has a current list price. Where
     * both exist the held price wins: a grandfathered rate or a bundle discount
     * is what the person is really billed, and minimising against the list price
     * would optimise somebody else's bill.
     */
    private fun gatherServices(userId: UUID, region: String, today: LocalDate): List<ServiceOption> {
        val held = subscriptions.currentSubscriptions(userId, today).associateBy { it.providerId }
        val plans = subscriptions.availablePlans(region).associateBy { it.providerId }

        return (held.keys + plans.keys)
            .mapNotNull { providerId ->
                val holding = held[providerId]
                val plan = plans[providerId]
                // One of the two is always present — the id came from their
                // keys. Written so the compiler knows it rather than asserted,
                // because a service with no price at all must be left out
                // regardless of how it got here: there is nothing to optimise
                // against and nothing honest to invent.
                val priced = holding?.let { it.providerName to it.monthlyCents }
                    ?: plan?.let { it.providerName to it.monthlyCents }
                    ?: return@mapNotNull null
                ServiceOption(
                    providerId = providerId,
                    name = priced.first,
                    monthlyCents = priced.second,
                    committedMonths = holding?.committedMonths ?: 0,
                    currentlySubscribed = holding != null,
                )
            }
            .sortedBy { it.name }
    }

    /**
     * The watchlist, reduced to what a subscription decision can actually turn on.
     */
    private fun gatherDemand(userId: UUID, region: String, services: List<ServiceOption>): Demand {
        val entries = watchlists.outstandingItems(userId)
        if (entries.isEmpty()) return Demand(emptyList(), ExcludedDemand.NONE)

        val blocked = watchlists.blockedTitleIds(userId)
        val wanted = entries.filterNot { it.titleId in blocked }
        if (wanted.isEmpty()) return Demand(emptyList(), ExcludedDemand.NONE)

        val titleIds = wanted.map { it.titleId }
        val summaries = titles.findSummaries(titleIds).associateBy { it.titleId }
        val coverage = availability.subscriptionCoverage(titleIds, region)
        val priceable = services.mapTo(mutableSetOf()) { it.providerId }

        val demands = mutableListOf<TitleDemand>()
        val free = mutableListOf<ExcludedTitle>()
        val unchecked = mutableListOf<ExcludedTitle>()
        val unpriced = mutableListOf<ExcludedTitle>()

        wanted.forEach { entry ->
            // A watchlist row whose catalogue title has gone. Dropped rather
            // than reported, unlike the three exclusions below, because there is
            // nothing to report it *as* — the name lived in the row that
            // vanished. `TonightService` treats it the same way. It is a
            // catalogue integrity problem, and dressing it up as a subscription
            // finding would put it in front of the wrong person.
            val summary = summaries[entry.titleId] ?: return@forEach
            val offers = coverage.byTitle[entry.titleId].orEmpty()

            if (entry.titleId in coverage.unknownTitleIds) {
                unchecked += ExcludedTitle(entry.titleId, summary.name, emptyList())
                return@forEach
            }

            val freeOn = offers.filter { it.isFree }
            if (freeOn.isNotEmpty()) {
                free += ExcludedTitle(entry.titleId, summary.name, freeOn.map { it.name }.sorted())
                return@forEach
            }

            val payable = offers.filter { it.providerId in priceable }
            if (payable.isEmpty() && offers.isNotEmpty()) {
                unpriced += ExcludedTitle(entry.titleId, summary.name, offers.map { it.name }.distinct().sorted())
                return@forEach
            }

            demands += TitleDemand(
                titleId = entry.titleId,
                name = summary.name,
                priorityPoints = priorityPoints(entry.priority),
                // Checked and carried by nobody is a legitimate empty set: it
                // stays in the denominator, so the reported coverage admits that
                // part of the list is unreachable at any price.
                availableOn = payable.mapTo(mutableSetOf()) { it.providerId },
            )
        }

        return Demand(demands, ExcludedDemand(freeToWatch = free, neverChecked = unchecked, unpricedService = unpriced))
    }

    /**
     * Watchlist priority as a positive objective coefficient: 1 (highest)
     * becomes 5, 5 becomes 1.
     *
     * Proportional to `Priority.weight` on the coverage dashboard rather than
     * merely similar to it, so the two features report the same coverage
     * fraction for the same list. Two screens that disagree about how much a
     * title is worth is a bug the user experiences as the product contradicting
     * itself.
     */
    private fun priorityPoints(priority: Int): Int = (LOWEST_PRIORITY - priority + 1).coerceAtLeast(1)

    private fun explanationForEmptyDemand(excluded: ExcludedDemand): String = when {
        excluded.total == 0 ->
            "There is nothing outstanding on your watchlist, so there is no basis for a subscription plan. " +
                "Add what you want to watch and this will have something to work with."
        excluded.freeToWatch.isNotEmpty() && excluded.neverChecked.isEmpty() && excluded.unpricedService.isEmpty() ->
            "Everything outstanding on your list is already free to watch, so no subscription is needed for any of it."
        excluded.neverChecked.size >= excluded.freeToWatch.size + excluded.unpricedService.size ->
            "Plotted has not checked where any of your outstanding titles are streaming yet, " +
                "so there is nothing to plan against. This resolves itself once availability has been refreshed."
        else ->
            "None of your outstanding titles can be used for a subscription plan: they are either free to watch, " +
                "unchecked, or only on services with no established price."
    }

    private data class Demand(val titles: List<TitleDemand>, val excluded: ExcludedDemand)

    /** What the user asked for. Every field is a hard limit they set. */
    data class PlanOptions(
        val horizonMonths: Int,
        val maximumMonthlyCents: Long?,
        val maximumActiveServices: Int?,
        val maximumMonthlySwitches: Int?,
        val weights: PlanWeights,
    ) {
        companion object {
            /**
             * Six months: long enough for a rotation plan to be worth making and
             * short enough that the prices it is based on are still plausible.
             * Beyond about a year the list prices in `provider_plans` are the
             * dominant source of error, not the model.
             */
            const val DEFAULT_HORIZON_MONTHS = 6
            const val MAXIMUM_HORIZON_MONTHS = 12

            val DEFAULT = PlanOptions(
                horizonMonths = DEFAULT_HORIZON_MONTHS,
                maximumMonthlyCents = null,
                maximumActiveServices = null,
                maximumMonthlySwitches = null,
                weights = PlanWeights.DEFAULT,
            )
        }
    }

    private companion object {
        /** Mirrors `Priority.LOWEST`, which lives in another module and cannot be imported. */
        const val LOWEST_PRIORITY = 5
    }
}
