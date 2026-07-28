package app.plotted.catalogue.integration.tmdb

import java.time.Duration

/**
 * A typed failure taxonomy rather than a single "TMDB broke" exception.
 *
 * Callers have to act differently for each case: a missing title is a data
 * problem to record, a rate limit is a reason to wait, a server error is worth
 * retrying, and a bad credential will never succeed no matter how many times it
 * is tried. Collapsing them into one type means retrying the one case that can
 * never work and giving up on the ones that would.
 */
sealed class TmdbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Whether trying the same call again could plausibly succeed. */
    abstract val retryable: Boolean

    /** No token configured. Never retryable; it needs a human. */
    class NotConfigured : TmdbException(
        "TMDB is not configured. Set plotted.tmdb.read-access-token (or TMDB_READ_ACCESS_TOKEN).",
    ) {
        override val retryable = false
    }

    /** 401 or 403. The credential is wrong, not the request. */
    class Unauthorised(
        detail: String,
    ) : TmdbException("TMDB rejected the credential: $detail") {
        override val retryable = false
    }

    /** 404. The title genuinely does not exist upstream. */
    class NotFound(
        val path: String,
    ) : TmdbException("TMDB has no resource at $path") {
        override val retryable = false
    }

    /** 429. [retryAfter] is taken from the response header when TMDB supplies one. */
    class RateLimited(
        val retryAfter: Duration?,
    ) : TmdbException(
        "TMDB rate limit reached" + (retryAfter?.let { "; retry after ${it.seconds}s" } ?: ""),
    ) {
        override val retryable = true
    }

    /** 5xx. */
    class Upstream(
        val status: Int,
        detail: String,
    ) : TmdbException("TMDB returned $status: $detail") {
        override val retryable = true
    }

    /** Connection refused, DNS failure, timeout. */
    class Unavailable(
        detail: String,
        cause: Throwable? = null,
    ) : TmdbException("TMDB could not be reached: $detail", cause) {
        override val retryable = true
    }

    /**
     * A 2xx whose body could not be parsed. Not retryable: the same request will
     * produce the same unparseable body, and quietly dropping it would let a
     * schema change upstream become silent data loss.
     */
    class MalformedResponse(
        detail: String,
        cause: Throwable? = null,
    ) : TmdbException("TMDB returned a response that could not be parsed: $detail", cause) {
        override val retryable = false
    }
}
