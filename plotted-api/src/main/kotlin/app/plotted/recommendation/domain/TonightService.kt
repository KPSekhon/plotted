package app.plotted.recommendation.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.platform.spi.WatchlistDirectory
import app.plotted.recommendation.persistence.RecommendationLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Queue Theory: tonight's context in, one pick and two backups out, each with a
 * reason.
 *
 * The pipeline is deliberately linear and each stage is separately testable —
 * gather, screen, score, diversify, explore, log. The interesting failures in a
 * recommender are not exceptions, they are answers that look reasonable and are
 * wrong, so every stage that makes a judgement is a function that can be given
 * inputs and checked.
 */
@Service
class TonightService(
    private val watchlists: WatchlistDirectory,
    private val titles: TitleDirectory,
    private val availability: AvailabilityDirectory,
    private val subscriptions: SubscriptionDirectory,
    private val log: RecommendationLogRepository,
    private val properties: TmdbProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ranker = Ranker()

    /**
     * Tonight's answer, and the id of the decision that produced it.
     *
     * The id is returned rather than kept internal because acceptance has to
     * point at a specific served item. "The user watched this title" is a much
     * weaker fact than "the user chose this title out of the three offered at
     * 20:14, where it was in position 2 with a propensity of 0.31" -- and only the
     * second is usable for off-policy evaluation, which is the whole reason the
     * propensity column exists.
     */
    data class Outcome(val requestId: UUID, val recommendation: Recommendation)

    @Transactional
    fun recommend(userId: UUID, request: TonightRequest): Outcome {
        val startedAt = System.nanoTime()
        val context = TonightContext(
            regionCode = properties.region,
            availableMinutes = request.availableMinutes,
            accessPolicy = request.accessPolicy,
        )

        val candidates = gather(userId, context)
        val blocked = watchlists.blockedTitleIds(userId)
        val subscribed = subscriptions.activeProviderIds(userId)

        val screened = candidates.map { screen(it, context, blocked, subscribed) }
        val eligible = screened.filterIsInstance<Screened.Eligible>().map { it.candidate }
        val rejections = screened.filterIsInstance<Screened.Rejected>()
            .groupingBy { it.reason }
            .eachCount()

        if (eligible.isEmpty()) {
            val nothing = Recommendation.NothingFits(candidateCount = candidates.size, reasons = rejections)
            val requestId = log.record(userId, context, nothing, elapsedMs(startedAt), RANKER_VERSION)
            // Logged at info, not warn: an empty answer is a correct outcome of
            // constraints the user set, not a fault. It becomes interesting only
            // in aggregate, which is what the decision log is for.
            logger.info(
                "No candidate fit for user {}: {} candidates, reasons {}",
                userId,
                candidates.size,
                rejections,
            )
            return Outcome(requestId, nothing)
        }

        val today = LocalDate.now(clock)
        val scored = eligible.mapNotNull { ranker.score(it, context, subscribed, today) }
        if (scored.isEmpty()) {
            // Everything survived the filters but nothing could be scored, which
            // means no candidate had a single usable feature. Reported as a
            // distinct outcome rather than as an empty list, because the cause
            // and the fix are completely different from "nothing fits".
            val nothing = Recommendation.NothingFits(
                candidateCount = candidates.size,
                reasons = mapOf(Rejection.RUNTIME_UNKNOWN to eligible.size),
            )
            return Outcome(log.record(userId, context, nothing, elapsedMs(startedAt), RANKER_VERSION), nothing)
        }

        val selected = ranker.diversify(scored, SLOTS)
        val alternatives = scored.filterNot { chosen -> selected.any { it.candidate.titleId == chosen.candidate.titleId } }
        val picks = withNextEpisode(userId, ranker.explore(selected, alternatives))

        val served = Recommendation.Served(
            picks = picks,
            candidateCount = candidates.size,
            eligibleCount = eligible.size,
        )
        return Outcome(log.record(userId, context, served, elapsedMs(startedAt), RANKER_VERSION), served)
    }

    /**
     * Records that the user chose one of the picks they were offered.
     *
     * Delegated to the repository, which scopes the update by user and request so
     * that accepting somebody else's recommendation, or a title that was not in
     * this one, matches no rows rather than being rejected by a check somebody
     * has to remember to write.
     */
    @Transactional
    fun accept(userId: UUID, requestId: UUID, titleId: UUID): Boolean = log.accept(userId, requestId, titleId)

    /**
     * Assembles candidates from the watchlist, their titles and their
     * availability — three batched lookups, never one per row.
     */
    private fun gather(userId: UUID, context: TonightContext): List<Candidate> {
        val entries = watchlists.outstandingItems(userId)
        if (entries.isEmpty()) return emptyList()

        val titleIds = entries.map { it.titleId }
        val summaries = titles.findSummaries(titleIds).associateBy { it.titleId }
        val coverage = availability.subscriptionCoverage(titleIds, context.regionCode)

        return entries.mapNotNull { entry ->
            val summary = summaries[entry.titleId] ?: return@mapNotNull null
            Candidate(
                titleId = entry.titleId,
                name = summary.name,
                mediaType = summary.mediaType,
                posterUrl = summary.posterUrl,
                watchMinutes = summary.watchMinutes,
                sessionMinutes = summary.sessionMinutes,
                priority = entry.priority,
                desiredByDate = entry.desiredByDate,
                communityRating = summary.communityRating,
                offers = coverage.byTitle[entry.titleId].orEmpty().map { provider ->
                    Candidate.Offer(
                        providerId = provider.providerId,
                        providerName = provider.name,
                        isFree = provider.isFree,
                    )
                },
            )
        }
    }

    /**
     * Names the episode, for the handful of titles actually being shown.
     *
     * ### Why this happens after ranking rather than during `gather`
     *
     * Resolving "what is next" costs a query per series. Doing it in `gather`
     * would run it for every outstanding watchlist entry — dozens — before the
     * filters have thrown most of them away, on the endpoint with the tightest
     * latency budget in the product (median 15.8 ms). Here it is at most three.
     *
     * ### What that costs, stated rather than hidden
     *
     * The runtime *filter* upstream still reads the series' typical episode,
     * because that is the only figure available before this point. So a 45-minute
     * evening can be offered a series whose next episode is a 61-minute finale.
     * The card shows that episode's real runtime, so the user sees the true
     * number rather than the average — but the filter did not use it.
     *
     * Closing that properly means resolving the next episode for every candidate
     * in one batched query and filtering on it, which is a `DISTINCT ON` over a
     * per-series position and is owed. It is written down here rather than left
     * to be discovered, because the failure is quiet: a slightly wrong runtime on
     * a card looks like a rounding difference rather than like a filter reading
     * the wrong number, which is exactly how the `watchMinutes` defect survived.
     */
    private fun withNextEpisode(userId: UUID, picks: List<Pick>): List<Pick> {
        val series = picks.map { it.candidate }.filter { it.mediaType == SERIES_MEDIA_TYPE }.map { it.titleId }
        if (series.isEmpty()) return picks

        val nextUp = watchlists.seriesProgress(userId, series)
        if (nextUp.isEmpty()) return picks

        return picks.map { pick ->
            nextUp[pick.candidate.titleId]
                ?.let { pick.copy(candidate = pick.candidate.copy(nextUp = it)) }
                ?: pick
        }
    }

    private fun elapsedMs(startedAt: Long): Int = ((System.nanoTime() - startedAt) / 1_000_000).toInt()

    data class TonightRequest(
        val availableMinutes: Int?,
        val accessPolicy: AccessPolicy,
    )

    private companion object {
        /** One pick and two backups. */
        const val SLOTS = 3

        const val SERIES_MEDIA_TYPE = "series"

        /**
         * Stamped on every logged decision. Phase 7 must never pool rows from
         * two different scoring functions, and a version string is the cheapest
         * thing that makes that impossible by accident.
         */
        const val RANKER_VERSION = "linear-v1"
    }
}
