package app.plotted.catalogue.integration.tmdb

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * TMDB is the source of every title fact in Plotted. Terms, attribution
 * obligations and refresh cadence are recorded in docs/data-sources.md.
 */
@ConfigurationProperties(prefix = "plotted.tmdb")
data class TmdbProperties(
    val baseUrl: String = "https://api.themoviedb.org/3",
    val imageBaseUrl: String = "https://image.tmdb.org/t/p",
    /**
     * The v4 read access token, sent as a bearer credential. Empty means TMDB is
     * not configured: the application still starts and every Phase 1 feature
     * works, and any call into this client fails with a message that says which
     * setting is missing rather than a 401 from a third party.
     */
    val readAccessToken: String = "",
    /** Canada only at launch, deliberately (spec section 1.2). */
    val region: String = "CA",
    val language: String = "en-CA",
    /**
     * Deliberately well under anything TMDB is known to permit. Ingestion is a
     * background job with no user waiting on it, so there is nothing to gain
     * from running near a limit and an entire product to lose from crossing one.
     */
    val requestsPerSecond: Double = 20.0,
    val burst: Int = 20,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
    /** Total attempts, not retries: 3 means one call and two retries. */
    val maxAttempts: Int = 3,
    val retryBaseDelay: Duration = Duration.ofMillis(250),
    val retryMaxDelay: Duration = Duration.ofSeconds(20),
) {
    val isConfigured: Boolean get() = readAccessToken.isNotBlank()

    /**
     * TMDB serves images from a separate CDN and returns only the path segment.
     * `w500` is the poster width the cards use; the original is far larger than
     * anything the UI displays.
     */
    fun posterUrl(path: String?): String? = path?.let { "$imageBaseUrl/w500$it" }

    fun backdropUrl(path: String?): String? = path?.let { "$imageBaseUrl/w1280$it" }
}
