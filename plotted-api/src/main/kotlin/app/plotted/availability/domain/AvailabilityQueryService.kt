package app.plotted.availability.domain

import app.plotted.availability.persistence.AvailabilityRepository
import app.plotted.platform.integration.tmdb.TmdbProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Read access to availability, with the freshness judgement attached.
 *
 * Deciding what counts as stale belongs here rather than in the controller: the
 * recommendation pipeline will need the same answer when it refuses to serve a
 * title whose availability nobody has checked recently, and two definitions of
 * "fresh" would eventually disagree.
 */
@Service
class AvailabilityQueryService(
    private val availability: AvailabilityRepository,
    private val properties: TmdbProperties,
    private val clock: Clock,
) {
    fun forTitle(titleId: UUID): TitleAvailability {
        val offers = availability.findOffers(titleId, properties.region)
        val lastVerified = offers.maxOfOrNull { it.sourceCheckedAt }
        return TitleAvailability(
            regionCode = properties.region,
            offers = offers,
            lastVerifiedAt = lastVerified,
            stale = isStale(lastVerified),
        )
    }

    /**
     * A title nobody has ever checked is stale, not fresh. The difference
     * between "nothing carries it" and "nobody looked" matters a great deal to
     * someone deciding what to watch tonight.
     */
    private fun isStale(lastVerified: Instant?): Boolean =
        lastVerified == null || Duration.between(lastVerified, Instant.now(clock)) > FRESHNESS_BUDGET

    data class TitleAvailability(
        val regionCode: String,
        val offers: List<AvailabilityOffer>,
        val lastVerifiedAt: Instant?,
        val stale: Boolean,
    )

    private companion object {
        /**
         * Two days. Long enough that a missed nightly run does not blank the
         * interface, short enough that a week-old claim is never presented as
         * current.
         */
        val FRESHNESS_BUDGET: Duration = Duration.ofHours(48)
    }
}
