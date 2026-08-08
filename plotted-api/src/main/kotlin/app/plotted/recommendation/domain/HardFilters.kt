package app.plotted.recommendation.domain

import java.util.UUID

/**
 * The reasons a candidate can be excluded outright.
 *
 * Each one is a constraint that cannot be traded against anything: no score is
 * high enough to make a blocked title acceptable or a three-hour film fit into
 * ninety minutes. Keeping them as an enum rather than a boolean is what makes
 * the "nothing fits" answer diagnosable — the counts per reason are the whole
 * explanation.
 */
enum class Rejection(val explanation: String) {
    BLOCKED("you asked not to be shown it"),
    NOT_AVAILABLE("it is not streaming anywhere in your region"),
    ACCESS_POLICY("it is only available on a service you are not paying for"),
    RUNTIME_UNKNOWN("nobody knows how long it is, so it cannot be promised to fit"),
    TOO_LONG("it is longer than the time you have"),
}

/**
 * The user's rule about what counts as watchable.
 *
 * `SUBSCRIBED_ONLY` is the strictest and the default: recommending something
 * that requires a new subscription is a sales pitch rather than an answer to
 * "what should I watch tonight".
 */
enum class AccessPolicy(val dbValue: String) {
    /** Only services the user already pays for. */
    SUBSCRIBED_ONLY("active_subscriptions_only"),

    /** Also anything free or ad-supported. */
    INCLUDE_FREE("include_free"),

    /** Also anything on any subscription service, paid for or not. */
    ANY_SUBSCRIPTION("any_subscription"),
    ;

    companion object {
        fun fromDb(value: String): AccessPolicy = entries.firstOrNull { it.dbValue == value } ?: SUBSCRIBED_ONLY

        fun parse(value: String): AccessPolicy? = entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}

/**
 * A candidate that survived, or the reason it did not.
 *
 * Rejections are collected rather than discarded, because "nothing fits" is only
 * a useful answer if it can say *why* — and the counts are what turn it from an
 * apology into a diagnosis the user can act on ("you have 45 minutes; everything
 * on your list is longer").
 */
sealed interface Screened {
    data class Eligible(val candidate: Candidate) : Screened
    data class Rejected(val titleId: UUID, val reason: Rejection) : Screened
}

/**
 * Applies every hard constraint, in the order that produces the most useful
 * diagnosis.
 *
 * Order matters for the explanation rather than for correctness: a title that is
 * both blocked and too long should be reported as blocked, because that is the
 * reason the user already knows about and can immediately recognise.
 */
fun screen(candidate: Candidate, context: TonightContext, blockedTitleIds: Set<UUID>, subscribedProviderIds: Set<UUID>): Screened {
    if (candidate.titleId in blockedTitleIds) {
        return Screened.Rejected(candidate.titleId, Rejection.BLOCKED)
    }

    if (candidate.offers.isEmpty()) {
        return Screened.Rejected(candidate.titleId, Rejection.NOT_AVAILABLE)
    }

    val reachable = candidate.offers.filter { offer ->
        when (context.accessPolicy) {
            AccessPolicy.SUBSCRIBED_ONLY -> offer.providerId in subscribedProviderIds
            AccessPolicy.INCLUDE_FREE -> offer.providerId in subscribedProviderIds || offer.isFree
            AccessPolicy.ANY_SUBSCRIPTION -> true
        }
    }
    if (reachable.isEmpty()) {
        return Screened.Rejected(candidate.titleId, Rejection.ACCESS_POLICY)
    }

    // A time budget turns an unknown runtime from a missing feature into a
    // disqualification. Ranking may use a title with no runtime; a request that
    // says "I have 90 minutes" may not, because "it fits" would be a guess and
    // the promise this product makes is precisely that it is not guessing.
    // Measured against one sitting, not the whole thing. A series is judged by
    // a typical episode, because that is what somebody watches this evening --
    // filtering on the total made One Piece 472 hours long and refused it, and
    // every other multi-season series with it, for every evening anybody has.
    context.availableMinutes?.let { budget ->
        val sessionMinutes = candidate.sessionMinutes
            ?: return Screened.Rejected(candidate.titleId, Rejection.RUNTIME_UNKNOWN)
        if (sessionMinutes > budget * (1 + OVERSHOOT_TOLERANCE)) {
            return Screened.Rejected(candidate.titleId, Rejection.TOO_LONG)
        }
    }

    return Screened.Eligible(candidate.copy(offers = reachable))
}
