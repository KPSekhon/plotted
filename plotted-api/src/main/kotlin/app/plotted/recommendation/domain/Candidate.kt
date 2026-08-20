package app.plotted.recommendation.domain

import app.plotted.platform.spi.WatchlistDirectory
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

/**
 * Where a candidate came from, and therefore what its acceptance means.
 *
 * Recorded per served item from the first decision onwards, before anything
 * produces [DISCOVERY], because the question it exists to answer is comparative:
 * *are the titles Plotted proposed accepted and finished as often as the ones
 * the user chose themselves?* That comparison needs a baseline of watchlist
 * decisions logged before discovery existed, and a source attached after the
 * fact is a guess.
 *
 * Deliberately not a ranking feature. Knowing where a candidate came from must
 * not become a reason to rank it higher — that would make the measurement circular
 * by construction, which is the failure `EVALUATION.md` already records for the
 * simulation's ground truth.
 */
enum class CandidateSource(val dbValue: String) {
    /** Explicitly on the watchlist. The user said they wanted this. */
    WATCHLIST("watchlist"),

    /**
     * A series already under way, resolved to its next episode.
     *
     * Separated from [WATCHLIST] because "carry on with this" and "start this"
     * are different recommendations even when they rank identically, and a
     * completion rate that pools them would be measuring two behaviours as one.
     */
    CONTINUING("continuing"),

    /**
     * Proposed from the wider catalogue rather than chosen by the user.
     *
     * Nothing produces this yet — see ADR 0009. The value exists so the column,
     * the response and the decision log are all ready on the day something does.
     */
    DISCOVERY("discovery"),
    ;

    companion object {
        fun fromDb(value: String): CandidateSource =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown candidate source '$value'")
    }
}

/** Everything the ranker knows about one thing the user might watch. */
data class Candidate(
    val titleId: UUID,
    val name: String,
    val mediaType: String,
    val posterUrl: String?,
    /**
     * The whole thing, start to finish. Shown so somebody can see what they are
     * taking on; never filtered against, because a long series is watched in
     * increments rather than in one go.
     */
    val watchMinutes: Int?,
    /**
     * One sitting — a film, or a typical episode. Everything time-related is
     * decided from this, because "what can I watch tonight" is a question about
     * an evening rather than about a box set.
     */
    val sessionMinutes: Int?,
    /** 1 is the highest, 5 the lowest. */
    val priority: Int,
    val desiredByDate: LocalDate?,
    /** 0..10 as stored, or null when nobody has rated it. Null is not zero. */
    val communityRating: Double?,
    val offers: List<Offer>,
    /**
     * How well this matches the user's Pilot Season profile, 0..1, or null.
     *
     * Null in two quite different situations that both mean the same thing here:
     * the user has not answered the questionnaire, or they answered it and it
     * found nothing it could defend saying. `PreferenceProfile` collapses the
     * second into null on purpose — a profile with no supportable opinion would
     * otherwise contribute a real-looking number computed from noise.
     *
     * Computed by the caller rather than derived here, because it needs the
     * title's genres and the fitted profile, and `Candidate` is what the ranker
     * sees rather than what the database holds.
     */
    val tasteMatch: Double? = null,
    /**
     * Which episode of a series this pick actually is, when the catalogue knows.
     *
     * Null for a film, and for a series with no episodes stored. Attached after
     * ranking rather than before it -- see `TonightService.recommend` for why the
     * filter still reads the typical episode.
     */
    val nextUp: WatchlistDirectory.NextUp? = null,
    /**
     * Where this candidate came from. Never read by the ranker; see
     * [CandidateSource] for why that matters.
     */
    val source: CandidateSource = CandidateSource.WATCHLIST,
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
