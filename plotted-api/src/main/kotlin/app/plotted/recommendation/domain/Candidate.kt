package app.plotted.recommendation.domain

import java.time.LocalDate
import java.util.UUID

/** Tonight's question, as asked. */
data class TonightContext(
    val regionCode: String,
    /**
     * How long they have. Null means "no particular limit", which is a real
     * request and is not the same as zero — it removes the runtime filter
     * entirely rather than failing everything.
     */
    val availableMinutes: Int?,
    val accessPolicy: AccessPolicy,
)

/** Everything the ranker knows about one thing the user might watch. */
data class Candidate(
    val titleId: UUID,
    val name: String,
    val mediaType: String,
    val posterUrl: String?,
    val watchMinutes: Int?,
    /** 1 is the highest, 5 the lowest. */
    val priority: Int,
    val desiredByDate: LocalDate?,
    /** 0..10 as stored, or null when nobody has rated it. Null is not zero. */
    val communityRating: Double?,
    val offers: List<Offer>,
) {
    data class Offer(
        val providerId: UUID,
        val providerName: String,
        val isFree: Boolean,
    )
}

/** A scored candidate, with the working kept. */
data class ScoredCandidate(
    val candidate: Candidate,
    val score: Double,
    val features: FeatureVector,
)

/** One slot in the answer. */
data class Pick(
    val candidate: Candidate,
    val score: Double,
    val features: FeatureVector,
    /** Whether this slot was filled by exploration rather than by rank. */
    val exploration: Boolean,
    /**
     * The probability this policy assigned to putting this title in this slot.
     * Strictly positive, because phase 7 divides by it.
     */
    val propensity: Double,
)

/** What the recommender answers with, including when the answer is "nothing". */
sealed interface Recommendation {
    data class Served(
        val picks: List<Pick>,
        val candidateCount: Int,
        val eligibleCount: Int,
    ) : Recommendation

    /**
     * No candidate survived the hard filters.
     *
     * Carries the counts per reason rather than an apology. Silently relaxing a
     * constraint to produce *something* is the failure mode this type exists to
     * make impossible: the constraints were the request.
     */
    data class NothingFits(
        val candidateCount: Int,
        val reasons: Map<Rejection, Int>,
    ) : Recommendation {
        /** The reason that removed the most candidates — the one worth saying first. */
        val dominantReason: Rejection? get() = reasons.maxByOrNull { it.value }?.key
    }
}
