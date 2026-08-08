package app.plotted.demo.domain

import app.plotted.demo.persistence.DemoRepository
import app.plotted.platform.config.PlottedProperties
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SessionIssuer
import app.plotted.platform.spi.TasteFixtures
import app.plotted.platform.spi.TitleDirectory
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds a demo visitor and signs them in.
 *
 * ### The persona is designed, and designed to be argued with
 *
 * A demo that shows a recommender returning a film proves nothing; every product
 * in this category can do that. What distinguishes Plotted is what it does when
 * the answer is *no*, so the persona is constructed so both headline features
 * have something interesting to say within one click:
 *
 * - **A watchlist spread across all five priorities**, so the coverage figure is
 *   visibly priority-weighted rather than a count, and the ranker has something
 *   to rank.
 * - **One title wanted by a date inside the fortnight**, so the deadline feature
 *   contributes to a real explanation instead of being absent from every one.
 * - **Two subscriptions chosen from the data**: the service covering the most of
 *   the list and the one covering the least. The second is what Cancel Culture
 *   should propose dropping, and nobody had to decide in advance which service
 *   deserved to be the villain.
 * - **A commitment on the weak one.** This is the best thirty seconds of the
 *   demo: the optimiser wants to cancel it, cannot, says so, and then cancels it
 *   in the first month it is allowed to. A constraint honoured visibly is worth
 *   more than any successful recommendation.
 *
 * ### What is real and what is not
 *
 * Titles come from the seeded catalogue and prices from `provider_plans`, which
 * are researched and cited in `docs/seed/provider-plans.md`. The *taste* is
 * invented — which titles this person wants and how badly — and that is the one
 * thing here that cannot be sourced from anywhere, because it is a preference
 * rather than a fact. The screen says it is a demo.
 */
@Service
class DemoService(
    private val demo: DemoRepository,
    private val titles: TitleDirectory,
    private val availability: AvailabilityDirectory,
    private val taste: TasteFixtures,
    private val sessions: SessionIssuer,
    private val properties: DemoProperties,
    private val platform: PlottedProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun start(client: SessionIssuer.ClientContext): DemoSession {
        if (!properties.enabled) {
            // 404 rather than 403: on a deployment where demo mode is off the
            // endpoint does not exist, and "forbidden" would confirm it could.
            throw ApiException(ErrorCode.NOT_FOUND, "Demo mode is not enabled on this deployment")
        }
        if (demo.countLiveDemoAccounts() >= properties.maximumLiveAccounts) {
            throw ApiException(
                ErrorCode.RATE_LIMITED,
                "The demo is at capacity right now. Expired demo accounts are cleared hourly — please try again shortly.",
            )
        }

        val region = platform.region.default
        val chosen = chooseTitles(region)

        val userId = demo.createUser(DEMO_DISPLAY_NAME, region, properties.accountLifetime)
        val watchlistId = demo.createWatchlist(userId, "Demo list")

        chosen.forEachIndexed { index, titleId ->
            demo.insertWatchlistItem(
                watchlistId = watchlistId,
                titleId = titleId,
                priority = priorityFor(index),
                // Exactly one deadline. Giving several would let the feature
                // dominate every explanation, which is a worse misrepresentation
                // than omitting it: the demo would be showing a ranker nobody
                // built.
                desiredBy = if (index == DEADLINE_INDEX) LocalDate.now(clock).plusDays(DEADLINE_DAYS) else null,
            )
        }

        val subscribed = subscribe(userId, chosen, region)

        // Part of the questionnaire, not all of it. A demo that arrives
        // finished hides the fork, which is the interaction worth showing, and
        // answering every axis would lose the demonstration that Plotted says
        // so when it never asked. Failure here is not worth failing a demo
        // over: the questionnaire simply starts from the beginning.
        val answered = runCatching { taste.seedDemoPersona(userId, PILOT_ANSWERS) }
            .onFailure { logger.warn("Could not seed the demo taste profile for {}: {}", userId, it.message) }
            .getOrDefault(0)

        val session = sessions.issueFor(userId, client)

        logger.info(
            "Started demo account {} with {} titles, {} subscriptions and {} taste answers",
            userId,
            chosen.size,
            subscribed.size,
            answered,
        )

        return DemoSession(
            userId = userId,
            displayName = DEMO_DISPLAY_NAME,
            session = session,
            watchlistSize = chosen.size,
            subscriptions = subscribed,
            // Said plainly rather than hidden. A demo pointed at an unseeded
            // database shows two empty screens, and whoever is looking deserves
            // to know that is a missing catalogue rather than a broken feature.
            catalogueIsEmpty = chosen.isEmpty(),
        )
    }

    /**
     * The persona's list: carried titles whose runtime is actually known.
     *
     * The runtime filter is not fussiness. Tonight Mode rejects anything it
     * cannot measure, because promising a title fits a ninety-minute evening
     * without knowing how long it is would be a guess dressed as a filter. A
     * demo list of unmeasured titles would therefore demonstrate the "nothing
     * fits" path and nothing else — technically correct, and a waste of the
     * thirty seconds someone gave the project.
     */
    private fun chooseTitles(region: String): List<UUID> {
        val candidates = demo.findCandidateTitleIds(
            region,
            properties.watchlistSize * CANDIDATE_OVERFETCH,
            preferredExternalIds(),
        )
        if (candidates.isEmpty()) return emptyList()

        val usable = titles.findSummaries(candidates)
            .filter { it.watchMinutes != null }
            .associateBy { it.titleId }

        // Ranked order comes from the query; findSummaries does not promise one.
        return candidates.filter { it in usable.keys }.take(properties.watchlistSize)
    }

    /**
     * Subscribes the persona to the best and worst of what covers their list.
     *
     * Coverage comes from `AvailabilityDirectory`, so "covered" means the same
     * thing here as it does on the coverage dashboard — subscription-included
     * only, with rentals excluded. Counting a rental would make a service look
     * better than it is, and this is the count that decides which service the
     * demo recommends cancelling.
     *
     * Returns what was actually taken. With only one priced provider in the data
     * there is nothing to cancel and only one subscription is created; inventing
     * a second service to make the demo look better would be putting an
     * unverifiable subscription in front of someone evaluating the project.
     */
    private fun subscribe(userId: UUID, titleIds: List<UUID>, region: String): List<String> {
        if (titleIds.isEmpty()) return emptyList()

        val plans = demo.findCurrentPlanIdsByProvider(region)
        if (plans.isEmpty()) return emptyList()

        val coverage = availability.subscriptionCoverage(titleIds, region)
        val ranked = coverage.byTitle.values
            .flatten()
            // Free services are not a subscription decision — a demo persona
            // does not pay for CBC Gem, and proposing they cancel it would be
            // advice about a bill they do not have.
            .filterNot { it.isFree }
            .filter { it.providerId in plans }
            .groupingBy { it.providerId to it.name }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Pair<UUID, String>, Int>> { it.value }.thenBy { it.key.second })
        if (ranked.isEmpty()) return emptyList()

        val today = LocalDate.now(clock)
        val strongest = ranked.first().key
        demo.insertSubscription(
            userId = userId,
            planId = plans.getValue(strongest.first),
            startedOn = today.minusMonths(STARTED_MONTHS_AGO),
            commitmentEndsOn = null,
        )

        val weakest = ranked.last().key
        if (weakest.first == strongest.first) return listOf(strongest.second)

        demo.insertSubscription(
            userId = userId,
            planId = plans.getValue(weakest.first),
            startedOn = today.minusMonths(STARTED_MONTHS_AGO),
            // The commitment is the point. Without it the optimiser cancels this
            // in month 0 and the plan is a single instruction; with it, the plan
            // has to hold something it does not want for two months and then let
            // it go, which is the behaviour worth showing.
            commitmentEndsOn = today.plusMonths(COMMITMENT_MONTHS),
        )
        return listOf(strongest.second, weakest.second)
    }

    /**
     * Priorities 1 to 5 across the list, highest first and never all the same.
     *
     * A flat watchlist would make the priority-weighted coverage figure
     * numerically identical to a count, and the demo would silently fail to show
     * the one decision the coverage dashboard is built around.
     */
    private fun priorityFor(index: Int): Int = (index % PRIORITY_LEVELS) + 1

    fun sweepExpired(): Int = demo.deleteExpired()

    data class DemoSession(
        val userId: UUID,
        val displayName: String,
        val session: SessionIssuer.Session,
        val watchlistSize: Int,
        val subscriptions: List<String>,
        val catalogueIsEmpty: Boolean,
    )

    /**
     * The curated persona, read from a versioned resource.
     *
     * Read on each demo start rather than cached: this is a handful of lines
     * once per demo account, and a cache would mean editing the file did
     * nothing until a restart — which is exactly the kind of quiet
     * no-op this project keeps finding.
     *
     * A missing file is not an error. The persona simply falls back to being
     * entirely data-derived, which is what it was before this existed.
     */
    private fun preferredExternalIds(): List<String> {
        val resource = ClassPathResource(PREFERRED_PATH)
        if (!resource.exists()) return emptyList()

        return resource.inputStream.bufferedReader().useLines { lines ->
            lines
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    private companion object {
        const val DEMO_DISPLAY_NAME = "Demo visitor"

        /** Curated by a person. See the file's own header for why it exists. */
        const val PREFERRED_PATH = "demo/preferred-titles.txt"

        /**
         * Two thirds of the ladder, leaving five to answer.
         *
         * Enough for the fit to have something to say on the axes it saw, few
         * enough that at least one axis stays unasked — which is what puts the
         * `NOT_ASKED` verdict on screen rather than only in the tests.
         */
        const val PILOT_ANSWERS = 10
        const val PRIORITY_LEVELS = 5
        const val DEADLINE_INDEX = 1
        const val DEADLINE_DAYS = 10L
        const val COMMITMENT_MONTHS = 2L
        const val STARTED_MONTHS_AGO = 4L

        /** Spares, because titles with no known runtime are discarded. */
        const val CANDIDATE_OVERFETCH = 4
    }
}
