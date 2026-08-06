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

    @Transactional
    fun recommend(userId: UUID, request: TonightRequest): Recommendation {
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
            log.record(userId, context, nothing, elapsedMs(startedAt), RANKER_VERSION)
            // Logged at info, not warn: an empty answer is a correct outcome of
            // constraints the user set, not a fault. It becomes interesting only
            // in aggregate, which is what the decision log is for.
            logger.info(
                "No candidate fit for user {}: {} candidates, reasons {}",
                userId,
                candidates.size,
                rejections,
            )
            return nothing
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
            log.record(userId, context, nothing, elapsedMs(startedAt), RANKER_VERSION)
            return nothing
        }

        val selected = ranker.diversify(scored, SLOTS)
        val alternatives = scored.filterNot { chosen -> selected.any { it.candidate.titleId == chosen.candidate.titleId } }
        val picks = ranker.explore(selected, alternatives)

        val served = Recommendation.Served(
            picks = picks,
            candidateCount = candidates.size,
            eligibleCount = eligible.size,
        )
        log.record(userId, context, served, elapsedMs(startedAt), RANKER_VERSION)
        return served
    }

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

    private fun elapsedMs(startedAt: Long): Int = ((System.nanoTime() - startedAt) / 1_000_000).toInt()

    data class TonightRequest(
        val availableMinutes: Int?,
        val accessPolicy: AccessPolicy,
    )

    private companion object {
        /** One pick and two backups. */
        const val SLOTS = 3

        /**
         * Stamped on every logged decision. Phase 7 must never pool rows from
         * two different scoring functions, and a version string is the cheapest
         * thing that makes that impossible by accident.
         */
        const val RANKER_VERSION = "linear-v1"
    }
}
