package app.plotted.platform.integration.tmdb

import java.time.Clock
import kotlin.system.exitProcess

/**
 * The day-one check from Appendix A of the specification.
 *
 * > Confirm that `/watch/providers` returns useful `CA` data for twenty titles
 * > you personally care about. If it does not, the project's premise needs
 * > rework -- find this out on day one, not in month three.
 *
 * Plotted's entire value rests on knowing what is streaming where, in Canada,
 * today. If TMDB's Canadian coverage is thin, no amount of ranking or
 * optimisation downstream can rescue it, and the honest response is to change
 * the design rather than to discover the problem after building on top of it.
 *
 * Runs standalone: no Spring context, no database, no Docker. It needs only a
 * token.
 *
 *     export TMDB_READ_ACCESS_TOKEN=...
 *     ./gradlew :plotted-api:premiseCheck
 *
 * Exits non-zero when Canadian coverage falls below [MINIMUM_COVERAGE], so it
 * can be wired into CI later if the premise is ever worth re-checking.
 */
object TmdbPremiseCheck {
    /**
     * A deliberately awkward sample rather than twenty blockbusters. Coverage of
     * global hits proves nothing: they are on everything everywhere. The
     * interesting cases are prestige cable, Canadian public broadcasting,
     * anime, and older titles that have drifted between services -- which is
     * where a thin regional feed actually shows up.
     */
    private val SAMPLE_TITLES = listOf(
        "Severance",
        "The Bear",
        "Andor",
        "Barry",
        "Succession",
        "Psych",
        "Better Call Saul",
        "Schitt's Creek",
        "Letterkenny",
        "The Last of Us",
        "Dune Part Two",
        "Everything Everywhere All at Once",
        "Past Lives",
        "Portrait of a Lady on Fire",
        "Spirited Away",
        "Arrival",
        "Sorry to Bother You",
        "The Handmaid's Tale",
        "Fleabag",
        "Anne with an E",
    )

    /** Below this, the platform-neutral premise is in trouble and worth knowing about. */
    private const val MINIMUM_COVERAGE = 0.70

    @JvmStatic
    fun main(args: Array<String>) {
        val token = System.getenv("TMDB_READ_ACCESS_TOKEN").orEmpty()
        if (token.isBlank()) {
            System.err.println(
                """
                TMDB_READ_ACCESS_TOKEN is not set.

                Register a free account at https://www.themoviedb.org/settings/api and use the
                v4 read access token. Attribution obligations are recorded in
                docs/data-sources.md and must be honoured wherever the data is displayed.
                """.trimIndent(),
            )
            exitProcess(2)
        }

        val properties = TmdbProperties(readAccessToken = token)
        val client = TmdbClient(properties, Clock.systemUTC())
        val titles = if (args.isNotEmpty()) args.toList() else SAMPLE_TITLES

        println("Checking TMDB coverage for region ${properties.region} across ${titles.size} titles.")
        println("Streaming availability is supplied by JustWatch via TMDB.")
        println()

        val outcomes = titles.map { check(client, properties, it) }
        report(outcomes, properties.region)

        val resolved = outcomes.filterIsInstance<Outcome.Resolved>()
        val covered = resolved.count { it.hasAnyProvider }
        val coverage = if (resolved.isEmpty()) 0.0 else covered.toDouble() / resolved.size

        exitProcess(if (coverage >= MINIMUM_COVERAGE) 0 else 1)
    }

    private fun check(client: TmdbClient, properties: TmdbProperties, query: String): Outcome = try {
        // Search first, then look up providers: exactly the path a user
        // adding a title to a watchlist takes, so this exercises the real
        // pipeline rather than a hand-picked list of identifiers.
        val results = client.searchMulti(query)
        val best = results.results.firstOrNull { it.mediaType == "movie" || it.mediaType == "tv" }
            ?: return Outcome.NotFound(query)

        val mediaType = if (best.mediaType == "movie") TmdbMediaType.MOVIE else TmdbMediaType.TV
        val providers = client.watchProviders(mediaType, best.id).results[properties.region]

        Outcome.Resolved(
            query = query,
            resolvedName = best.displayName ?: query,
            mediaType = mediaType,
            subscription = providers?.flatrate.orEmpty().map { it.providerName },
            free = (providers?.free.orEmpty() + providers?.ads.orEmpty()).map { it.providerName },
            rentOrBuy = (providers?.rent.orEmpty() + providers?.buy.orEmpty()).map { it.providerName }.distinct(),
        )
    } catch (failure: TmdbException) {
        Outcome.Failed(query, failure.message ?: failure::class.simpleName.orEmpty())
    }

    private fun report(outcomes: List<Outcome>, region: String) {
        outcomes.forEach { outcome ->
            when (outcome) {
                is Outcome.NotFound -> println("  ?  ${outcome.query} — no title matched")
                is Outcome.Failed -> println("  !  ${outcome.query} — ${outcome.reason}")
                is Outcome.Resolved -> {
                    val marker = if (outcome.hasAnyProvider) "ok" else "--"
                    println("  $marker ${outcome.resolvedName}")
                    if (outcome.subscription.isNotEmpty()) {
                        println("        subscription: ${outcome.subscription.joinToString(", ")}")
                    }
                    if (outcome.free.isNotEmpty()) {
                        println("        free/ads:     ${outcome.free.joinToString(", ")}")
                    }
                    if (outcome.rentOrBuy.isNotEmpty()) {
                        println("        rent/buy:     ${outcome.rentOrBuy.joinToString(", ")}")
                    }
                    if (!outcome.hasAnyProvider) {
                        println("        no $region availability of any kind")
                    }
                }
            }
        }

        val resolved = outcomes.filterIsInstance<Outcome.Resolved>()
        val covered = resolved.count { it.hasAnyProvider }
        val subscription = resolved.count { it.subscription.isNotEmpty() }
        val coverage = if (resolved.isEmpty()) 0.0 else covered.toDouble() / resolved.size

        println()
        println("Resolved            ${resolved.size} of ${outcomes.size}")
        println("Any $region availability  $covered  (${percent(coverage)})")
        println("On a subscription   $subscription")
        println()
        println(
            if (coverage >= MINIMUM_COVERAGE) {
                "Premise holds: Canadian availability is dense enough to build on."
            } else {
                "Premise at risk: fewer than ${percent(MINIMUM_COVERAGE)} of sampled titles have any " +
                    "$region availability. Re-read section 7 before building further on this feed."
            },
        )
    }

    private fun percent(value: Double): String = "${Math.round(value * 100)}%"

    private sealed interface Outcome {
        data class Resolved(
            val query: String,
            val resolvedName: String,
            val mediaType: TmdbMediaType,
            val subscription: List<String>,
            val free: List<String>,
            val rentOrBuy: List<String>,
        ) : Outcome {
            val hasAnyProvider: Boolean
                get() = subscription.isNotEmpty() || free.isNotEmpty() || rentOrBuy.isNotEmpty()
        }

        data class NotFound(
            val query: String,
        ) : Outcome

        data class Failed(
            val query: String,
            val reason: String,
        ) : Outcome
    }
}
