package app.plotted.platform.ratelimit

import java.time.Duration

/**
 * A limit on how often something may be done.
 *
 * ### The decision that matters is what happens when Redis is down
 *
 * A rate limiter is a dependency in front of an endpoint, and it will be
 * unavailable sometimes. There are exactly two things it can do then, and both
 * are wrong for some endpoints:
 *
 *  * **Fail open** — allow the request. The endpoint keeps working and the limit
 *    silently stops existing.
 *  * **Fail closed** — refuse the request. The limit holds and the endpoint is
 *    down for everybody, including the people it was protecting.
 *
 * Picking one globally means picking it wrongly for half the endpoints, so
 * [Policy] is per-limit and every caller has to say which it wants. There is no
 * default: a limiter whose failure behaviour was chosen by whoever wrote the
 * config first is a limiter nobody has thought about.
 */
interface RateLimiter {
    /**
     * Whether this action may proceed, and how much of the allowance is left.
     *
     * Consumes one unit when it returns [Decision.Allowed]. Checking and
     * consuming are one operation on purpose: as two, every limit leaks under
     * concurrency by exactly the amount of traffic that arrives between them.
     */
    fun tryAcquire(limit: Limit, key: String): Decision

    /**
     * A named allowance.
     *
     * The name is part of the Redis key, so two limits with the same name share
     * a counter. That is occasionally what you want and usually a mistake, which
     * is why the names are declared in [RateLimits] rather than passed as
     * strings at the call site.
     */
    data class Limit(
        val name: String,
        val permits: Int,
        val window: Duration,
        val policy: Policy,
    ) {
        init {
            require(permits > 0) { "A limit of zero permits is a disabled endpoint, not a rate limit" }
            require(!window.isZero && !window.isNegative) { "A rate limit needs a positive window" }
        }
    }

    enum class Policy {
        /**
         * Allow the request when the limiter cannot answer.
         *
         * For endpoints where being unavailable is worse than being unlimited:
         * an expensive read that a burst would merely slow down. The failure is
         * counted so that "the limiter has been open for a week" is a thing
         * somebody can see rather than infer.
         */
        FAIL_OPEN,

        /**
         * Refuse the request when the limiter cannot answer.
         *
         * For endpoints where the limit is the only thing standing between a
         * script and a resource that does not recover — an unauthenticated write,
         * or anything spending a quota that cannot be bought back.
         */
        FAIL_CLOSED,
    }

    sealed interface Decision {
        data class Allowed(val remaining: Int) : Decision

        /** [retryAfter] is how long until the window rolls, for the `Retry-After` header. */
        data class Limited(val retryAfter: Duration) : Decision

        /**
         * The limiter could not be reached, and [Policy] decided the outcome.
         *
         * Distinct from [Allowed] and [Limited] rather than collapsed into them,
         * because "allowed because we checked" and "allowed because we could not
         * check" are different facts and only one of them is worth an alert.
         */
        data class Unavailable(val allowed: Boolean, val reason: String) : Decision
    }
}

/**
 * Every limit in the application, declared in one place.
 *
 * Together rather than beside their endpoints so the whole policy can be read at
 * once — including, importantly, which endpoints fail closed. That is the list
 * worth reviewing before a deployment, and it is not reviewable when it is seven
 * annotations in seven files.
 */
object RateLimits {
    /**
     * Demo account creation. Unauthenticated, and it writes.
     *
     * Fails closed. It is the one endpoint where the limit is load-bearing: a
     * script can fill a free-tier database through it, and a filled database
     * does not recover on its own the way a briefly unavailable demo does. The
     * service already refuses past `maximum-live-accounts` for the same reason;
     * this is the same argument applied per caller rather than in total.
     */
    val DEMO_SESSION = RateLimiter.Limit(
        name = "demo-session",
        permits = 5,
        window = Duration.ofHours(1),
        policy = RateLimiter.Policy.FAIL_CLOSED,
    )

    /**
     * The subscription optimiser.
     *
     * Fails open. Its worst case is four CP-SAT solves at a five-second cap — a
     * 20-second bound — so a burst is expensive, and it is authenticated, so the
     * blast radius is one account. Being briefly unlimited costs CPU; being
     * unavailable costs the headline feature.
     */
    val PLAN = RateLimiter.Limit(
        name = "plan",
        permits = 20,
        window = Duration.ofMinutes(1),
        policy = RateLimiter.Policy.FAIL_OPEN,
    )
}
