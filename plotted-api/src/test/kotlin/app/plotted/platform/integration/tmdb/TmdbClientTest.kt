package app.plotted.platform.integration.tmdb

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * Fault injection against a stubbed TMDB.
 *
 * Section 19 asks for exactly this: timeouts, rate limits, partial data, expired
 * tokens and malformed JSON, each asserted to produce the documented degraded
 * behaviour rather than an unhandled failure. The happy path is the least
 * interesting thing verified here.
 */
class TmdbClientTest {
    private lateinit var server: WireMockServer
    private val slept = mutableListOf<Duration>()

    @BeforeEach
    fun startServer() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        slept.clear()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    // The default read timeout is generous deliberately. A tight default makes
    // every test race the clock: on a loaded machine WireMock answers late, the
    // client correctly classifies that as an outage and retries, and assertions
    // about request counts then fail for a reason unrelated to the case under
    // test. The timeout test passes its own short value explicitly.
    private fun client(token: String = "test-token", maxAttempts: Int = 3, readTimeout: Duration = Duration.ofSeconds(10)) = TmdbClient(
        properties = TmdbProperties(
            baseUrl = "http://localhost:${server.port()}",
            readAccessToken = token,
            maxAttempts = maxAttempts,
            readTimeout = readTimeout,
            retryBaseDelay = Duration.ofMillis(10),
            // Fast enough that the limiter never dominates a test, but still
            // exercised on every call.
            requestsPerSecond = 1000.0,
        ),
        clock = Clock.system(ZoneOffset.UTC),
        // Retries are asserted on, not waited for.
        sleeper = { slept += it },
    )

    // --- happy path --------------------------------------------------------

    @Test
    fun `deserialises a film, including the snake-case fields`() {
        stub("/movie/438631", 200, MOVIE_JSON)

        val movie = client().movie(438631)

        movie.title shouldBe "Dune"
        movie.runtime shouldBe 155
        movie.originalLanguage shouldBe "en"
        // TMDB returns only the path fragment; turning it into a URL is the
        // mapper's job and is asserted there.
        movie.posterPath shouldBe "/poster.jpg"
        movie.genres.map { it.name } shouldContainExactly listOf("Science Fiction", "Adventure")
    }

    @Test
    fun `sends the bearer credential and asks for the configured language`() {
        stub("/movie/1", 200, MOVIE_JSON)

        client().movie(1)

        server.verify(
            getRequestedFor(urlPathEqualTo("/movie/1"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withQueryParam("language", equalTo("en-CA")),
        )
    }

    @Test
    fun `reads only the configured region out of the watch-provider response`() {
        stub("/movie/438631/watch/providers", 200, PROVIDERS_JSON)

        val response = client().watchProviders(TmdbMediaType.MOVIE, 438631)

        val canada = response.results["CA"]!!
        canada.flatrate.map { it.providerName } shouldContainExactly listOf("Crave")
        canada.rent.map { it.providerName } shouldContainExactly listOf("Apple TV")
        // The US entry exists in the payload and is simply not read.
        response.results.keys.contains("US") shouldBe true
    }

    @Test
    fun `treats a region with no providers as an answer rather than an error`() {
        stub("/tv/99/watch/providers", 200, """{"id":99,"results":{}}""")

        val response = client().watchProviders(TmdbMediaType.TV, 99)

        response.results["CA"].shouldBeNull()
    }

    // --- failures that must not be retried ---------------------------------

    @Test
    fun `a missing token fails before any request is made`() {
        val failure = runCatching { client(token = "").movie(1) }.exceptionOrNull()

        (failure is TmdbException.NotConfigured) shouldBe true
        failure!!.message!! shouldContain "plotted.tmdb.read-access-token"
        server.verify(0, getRequestedFor(urlPathEqualTo("/movie/1")))
    }

    @Test
    fun `a rejected credential is not retried, because it never will succeed`() {
        stub("/movie/1", 401, """{"status_message":"Invalid API key"}""")

        val failure = runCatching { client().movie(1) }.exceptionOrNull()

        (failure is TmdbException.Unauthorised) shouldBe true
        server.verify(1, getRequestedFor(urlPathEqualTo("/movie/1")))
        slept.shouldContainExactly(emptyList())
    }

    @Test
    fun `a missing title is reported as absent rather than as an outage`() {
        stub("/movie/404404", 404, """{"status_message":"The resource you requested could not be found."}""")

        val failure = runCatching { client().movie(404404) }.exceptionOrNull()

        (failure is TmdbException.NotFound) shouldBe true
        (failure as TmdbException).retryable shouldBe false
        server.verify(1, getRequestedFor(urlPathEqualTo("/movie/404404")))
    }

    @Test
    fun `a 2xx with an unparseable body is not retried and is not silently dropped`() {
        stub("/movie/1", 200, "{ this is not json")

        val failure = runCatching { client().movie(1) }.exceptionOrNull()

        (failure is TmdbException.MalformedResponse) shouldBe true
        server.verify(1, getRequestedFor(urlPathEqualTo("/movie/1")))
    }

    // --- failures that must be retried -------------------------------------

    @Test
    fun `a rate limit is retried and Retry-After is honoured over the default backoff`() {
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .inScenario("rate limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7"))
                .willSetStateTo("recovered"),
        )
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .inScenario("rate limit")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(MOVIE_JSON)),
        )

        val movie = client().movie(1)

        movie.title shouldBe "Dune"
        // Seven seconds from the header, not the 10 ms base delay: TMDB knows
        // when its window resets and guessing shorter only wastes quota.
        slept.shouldContainExactly(listOf(Duration.ofSeconds(7)))
    }

    @Test
    fun `a server error is retried with exponential backoff`() {
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"),
        )
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("third"),
        )
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .inScenario("flaky")
                .whenScenarioStateIs("third")
                .willReturn(okJson(MOVIE_JSON)),
        )

        client().movie(1).title shouldBe "Dune"

        slept.shouldContainExactly(listOf(Duration.ofMillis(10), Duration.ofMillis(20)))
    }

    @Test
    fun `gives up after the configured number of attempts instead of retrying forever`() {
        stub("/movie/1", 500, "upstream on fire")

        val failure = runCatching { client(maxAttempts = 3).movie(1) }.exceptionOrNull()

        (failure is TmdbException.Upstream) shouldBe true
        server.verify(3, getRequestedFor(urlPathEqualTo("/movie/1")))
        slept.size shouldBe 2
    }

    @Test
    fun `a read timeout is treated as a retryable outage`() {
        server.stubFor(
            get(urlPathEqualTo("/movie/1"))
                .willReturn(okJson(MOVIE_JSON).withFixedDelay(10_000)),
        )

        val failure =
            runCatching {
                client(maxAttempts = 2, readTimeout = Duration.ofMillis(100)).movie(1)
            }.exceptionOrNull()

        (failure is TmdbException.Unavailable) shouldBe true
        (failure as TmdbException).retryable shouldBe true
        // Asserted against the recorded backoff rather than WireMock's request
        // journal: on a loaded machine the connection itself can time out, and
        // then no request reaches the server at all. Our own retry decision is
        // what this test is about, and it is deterministic.
        slept.size shouldBe 1
    }

    @Test
    fun `an upstream error body is truncated before it reaches the logs`() {
        stub("/movie/1", 500, "x".repeat(5_000))

        val failure = runCatching { client(maxAttempts = 1).movie(1) }.exceptionOrNull()

        failure!!.message!!.length shouldBe "TMDB returned 500: ".length + 500
    }

    // --- partial data ------------------------------------------------------

    @Test
    fun `a payload missing most optional fields deserialises rather than failing`() {
        stub("/tv/1", 200, SERIES_WITHOUT_RUNTIME_JSON)

        val series = client().series(1)

        series.name shouldBe "A Show With No Runtime"
        series.numberOfSeasons shouldBe 2
        series.episodeRunTime.shouldContainExactly(emptyList())
        // Empty string rather than null for an unknown date; the mapper is what
        // turns that into an absent value.
        series.firstAirDate shouldBe ""
        series.overview.shouldBeNull()
    }

    @Test
    fun `unknown fields added upstream do not break ingestion`() {
        stub("/movie/1", 200, MOVIE_JSON.dropLast(1) + ""","brand_new_field":{"nested":true}}""")

        client().movie(1).title shouldBe "Dune"
    }

    // --- helpers -----------------------------------------------------------

    private fun stub(path: String, status: Int, body: String) {
        server.stubFor(
            get(urlPathEqualTo(path))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )
    }

    private fun okJson(body: String) = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    private companion object {
        val MOVIE_JSON = """
            {
              "id": 438631,
              "title": "Dune",
              "original_title": "Dune",
              "overview": "Paul Atreides arrives on Arrakis.",
              "release_date": "2021-09-15",
              "runtime": 155,
              "original_language": "en",
              "poster_path": "/poster.jpg",
              "backdrop_path": "/backdrop.jpg",
              "popularity": 123.456,
              "vote_average": 7.8,
              "vote_count": 11000,
              "genres": [
                {"id": 878, "name": "Science Fiction"},
                {"id": 12, "name": "Adventure"}
              ]
            }
        """.trimIndent()

        val SERIES_WITHOUT_RUNTIME_JSON = """
            {
              "id": 1,
              "name": "A Show With No Runtime",
              "first_air_date": "",
              "episode_run_time": [],
              "number_of_seasons": 2,
              "genres": []
            }
        """.trimIndent()

        val PROVIDERS_JSON = """
            {
              "id": 438631,
              "results": {
                "CA": {
                  "link": "https://www.themoviedb.org/movie/438631/watch?locale=CA",
                  "flatrate": [{"provider_id": 230, "provider_name": "Crave", "display_priority": 1}],
                  "rent": [{"provider_id": 2, "provider_name": "Apple TV", "display_priority": 3}]
                },
                "US": {
                  "flatrate": [{"provider_id": 8, "provider_name": "Netflix", "display_priority": 1}]
                }
              }
            }
        """.trimIndent()
    }
}
