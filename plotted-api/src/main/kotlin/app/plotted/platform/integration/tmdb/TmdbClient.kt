package app.plotted.platform.integration.tmdb

import app.plotted.platform.ratelimit.TokenBucket
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.Duration

/**
 * The TMDB HTTP client.
 *
 * Three things this deliberately does, all of which section 8.4 of the
 * specification asks for and none of which come for free:
 *
 *  * **Stays inside the quota.** Every call passes through a token bucket.
 *    Exhausting the quota does not degrade Plotted, it stops it, because there
 *    is no second source for this data.
 *  * **Retries only what can succeed.** Rate limits, 5xx and connection failures
 *    are retried with exponential backoff, honouring `Retry-After` when TMDB
 *    sends one. A 401 or a 404 is returned immediately: retrying a wrong
 *    credential just spends the quota faster.
 *  * **Fails as a typed error.** Callers decide what a missing title means; this
 *    class only decides what happened.
 *
 * There is no caching here on purpose. Availability freshness is a product
 * concern with its own timestamps and confidence values, and burying it in an
 * HTTP client would make "verified 3 days ago" a lie the UI could not detect.
 */
@Component
class TmdbClient(
    private val properties: TmdbProperties,
    clock: Clock,
    private val sleeper: (Duration) -> Unit = { duration ->
        if (!duration.isNegative && !duration.isZero) Thread.sleep(duration.toMillis())
    },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val rateLimiter = TokenBucket(
        permitsPerSecond = properties.requestsPerSecond,
        burst = properties.burst,
        clock = clock,
    )

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(properties.connectTimeout)
                    .withReadTimeout(properties.readTimeout),
            ),
        )
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.readAccessToken}")
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build()

    fun movie(tmdbId: Int): TmdbMovieDetail = fetch("/movie/$tmdbId", TmdbMovieDetail::class.java, mapOf("language" to properties.language))

    fun series(tmdbId: Int): TmdbSeriesDetail = fetch("/tv/$tmdbId", TmdbSeriesDetail::class.java, mapOf("language" to properties.language))

    /**
     * One season with its episodes.
     *
     * A separate call per season, because that is the only place TMDB exposes
     * episode runtimes. It is also why season ingestion is a deliberate second
     * step rather than part of fetching a series: an eight-season show costs
     * eight extra requests, and quota is the scarcest thing this client spends.
     */
    fun season(seriesTmdbId: Int, seasonNumber: Int): TmdbSeasonDetail = fetch(
        "/tv/$seriesTmdbId/season/$seasonNumber",
        TmdbSeasonDetail::class.java,
        mapOf("language" to properties.language),
    )

    /** Films and series in one call; people are filtered out during mapping. */
    fun searchMulti(query: String, page: Int = 1): TmdbSearchPage = fetch(
        "/search/multi",
        TmdbSearchPage::class.java,
        mapOf(
            "query" to query,
            "page" to page.toString(),
            "language" to properties.language,
            "include_adult" to "false",
        ),
    )

    /**
     * Regional availability, keyed by region. Plotted reads only the configured
     * region; an absent entry means nobody carries the title there, which is a
     * fact worth recording rather than an error.
     */
    fun watchProviders(mediaType: TmdbMediaType, tmdbId: Int): TmdbWatchProviderResponse =
        fetch("/${mediaType.path}/$tmdbId/watch/providers", TmdbWatchProviderResponse::class.java)

    /** The canonical genre list. Its ids are the primary key of `genres`. */
    fun genres(mediaType: TmdbMediaType): List<TmdbGenre> = fetch(
        "/genre/${mediaType.path}/list",
        TmdbGenreList::class.java,
        mapOf("language" to properties.language),
    ).genres

    // --- plumbing ----------------------------------------------------------

    private fun <T : Any> fetch(path: String, type: Class<T>, query: Map<String, String> = emptyMap()): T {
        if (!properties.isConfigured) throw TmdbException.NotConfigured()

        var attempt = 1
        while (true) {
            val failure =
                try {
                    rateLimiter.acquire()
                    return execute(path, type, query)
                } catch (exception: TmdbException) {
                    exception
                }

            if (!failure.retryable || attempt >= properties.maxAttempts) throw failure

            val delay = backoffFor(attempt, failure)
            log.warn(
                "TMDB {} failed on attempt {}/{} ({}); retrying in {} ms",
                path,
                attempt,
                properties.maxAttempts,
                failure.message,
                delay.toMillis(),
            )
            sleeper(delay)
            attempt++
        }
    }

    private fun <T : Any> execute(path: String, type: Class<T>, query: Map<String, String>): T = try {
        restClient.get()
            .uri { builder ->
                builder.path(path)
                query.forEach { (key, value) -> builder.queryParam(key, value) }
                builder.build()
            }
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                throw toException(path, response.statusCode.value(), response.headers, readBody(response))
            }
            .body(type)
            ?: throw TmdbException.MalformedResponse("empty body for $path")
    } catch (exception: ResourceAccessException) {
        // Connection refused, DNS failure, or a timeout.
        throw TmdbException.Unavailable(exception.message ?: "no detail", exception)
    } catch (exception: RestClientException) {
        // The status handler above throws TmdbException; RestClient may wrap
        // it on the way out, so unwrap before deciding this was a parse failure.
        throw exception.unwrapTmdb() ?: TmdbException.MalformedResponse(
            exception.message ?: "no detail",
            exception,
        )
    }

    private fun toException(path: String, status: Int, headers: HttpHeaders, body: String): TmdbException = when {
        status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value() ->
            TmdbException.Unauthorised(body.take(ERROR_BODY_LIMIT))

        status == HttpStatus.NOT_FOUND.value() -> TmdbException.NotFound(path)

        status == HttpStatus.TOO_MANY_REQUESTS.value() ->
            TmdbException.RateLimited(retryAfterFrom(headers))

        status >= HttpStatus.INTERNAL_SERVER_ERROR.value() ->
            TmdbException.Upstream(status, body.take(ERROR_BODY_LIMIT))

        else -> TmdbException.Upstream(status, body.take(ERROR_BODY_LIMIT))
    }

    /**
     * Exponential backoff, capped. `Retry-After` wins when TMDB supplies one:
     * it knows when the window resets and guessing shorter only wastes quota.
     *
     * No jitter, deliberately. Jitter exists to stop many clients retrying in
     * lockstep; there is one ingestion worker here, and a deterministic delay is
     * far easier to assert on.
     */
    private fun backoffFor(attempt: Int, failure: TmdbException): Duration {
        if (failure is TmdbException.RateLimited && failure.retryAfter != null) {
            return minOf(failure.retryAfter, properties.retryMaxDelay)
        }
        val exponential = properties.retryBaseDelay.multipliedBy(1L shl (attempt - 1))
        return minOf(exponential, properties.retryMaxDelay)
    }

    private fun retryAfterFrom(headers: HttpHeaders): Duration? = headers.getFirst(HttpHeaders.RETRY_AFTER)
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let(Duration::ofSeconds)

    private fun readBody(response: org.springframework.http.client.ClientHttpResponse): String =
        runCatching { response.body.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")

    private fun Throwable.unwrapTmdb(): TmdbException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is TmdbException) return current
            current = current.cause.takeIf { it !== current }
        }
        return null
    }

    private companion object {
        /** Enough of an upstream error body to diagnose, not enough to fill the log. */
        const val ERROR_BODY_LIMIT = 500
    }
}

enum class TmdbMediaType(
    val path: String,
) {
    MOVIE("movie"),
    TV("tv"),
}
