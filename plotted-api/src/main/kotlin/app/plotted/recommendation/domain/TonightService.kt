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
        val picks = ranker.explore(selected, alternatives)

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

        // Resolved here, before the filters, and batched so it costs a fixed
        // number of queries rather than one per series.
        //
        // It has to happen before screening because the runtime filter is a
        // promise about *this evening*, and the thing being offered is one
        // episode rather than the series' average. Doing it afterwards -- which
        // is how this worked first -- let a 45-minute window admit a series on a
        // 25-minute average and then show a 61-minute finale on the card. The
        // number displayed was right and the filter had not used it, which is
        // the same shape as the `watchMinutes` defect: a filter measuring
        // something adjacent to the question.
        val seriesIds = entries.mapNotNull { entry ->
            entry.titleId.takeIf { summaries[it]?.mediaType == SERIES_MEDIA_TYPE }
        }
        // Skipped entirely for a watchlist of films, so the common case pays
        // nothing at all for a feature that cannot apply to it.
        val nextUp = if (seriesIds.isEmpty()) emptyMap() else watchlists.seriesProgress(userId, seriesIds)

        return entries.mapNotNull { entry ->
            val summary = summaries[entry.titleId] ?: return@mapNotNull null
            val next = nextUp[entry.titleId]
            Candidate(
                titleId = entry.titleId,
                name = summary.name,
                mediaType = summary.mediaType,
                posterUrl = summary.posterUrl,
                watchMinutes = summary.watchMinutes,
                // The episode being offered, when it is known, and the typical
                // one otherwise. A series whose next episode has no stored
                // runtime falls back rather than becoming unrecommendable --
                // that would remove two thirds of the series catalogue for a
                // gap upstream.
                sessionMinutes = next?.runtimeMinutes ?: summary.sessionMinutes,
                nextUp = next,
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

        const val SERIES_MEDIA_TYPE = "series"

        /**
         * Stamped on every logged decision. Phase 7 must never pool rows from
         * two different scoring functions, and a version string is the cheapest
         * thing that makes that impossible by accident.
         */
        const val RANKER_VERSION = "linear-v1"
    }
}
